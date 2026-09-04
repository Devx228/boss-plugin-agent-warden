package ai.rever.boss.plugin.dynamic.warden.runtime

import ai.rever.boss.plugin.dynamic.warden.gateway.Capability
import ai.rever.boss.plugin.dynamic.warden.gateway.Profile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WardenDeepLinksTest {
    private val host = FakeWardenHost()
    private val store = FakeSettingsStore(WardenSettings(preferredPort = 0, gatewayEnabled = true))

    private fun TestScope.handler(): Pair<WardenDeepLinkHandler, WardenRuntime> {
        val runtime = WardenRuntime(host, backgroundScope, store) { 10_000L }
        return WardenDeepLinkHandler(runtime, "test.warden") to runtime
    }

    private fun TestScope.drain() = testScheduler.runCurrent()

    @Test
    fun `export writes a report without ending the session`() = runTest {
        val (links, runtime) = handler()
        runtime.initialise()
        drain()
        assertTrue(links.handle(WardenDeepLinkHandler.ACTION_EXPORT, emptyMap()))
        drain()
        assertEquals(1, host.writtenReports.size)
        assertTrue(runtime.recorder.isRecording, "export closed the session it was only meant to snapshot")
    }

    @Test
    fun `end-session writes a report and closes the session`() = runTest {
        val (links, runtime) = handler()
        runtime.initialise()
        drain()
        assertTrue(links.handle(WardenDeepLinkHandler.ACTION_END_SESSION, emptyMap()))
        drain()
        assertEquals(1, host.writtenReports.size)
        assertFalse(runtime.recorder.isRecording)
    }

    @Test
    fun `start-session takes a label, and falls back when it is blank`() = runTest {
        val (links, runtime) = handler()
        runtime.initialise()
        drain()
        links.handle(WardenDeepLinkHandler.ACTION_START_SESSION, mapOf("label" to "Nightly audit"))
        drain()
        assertEquals("Nightly audit", runtime.recorder.state.value.label)

        links.handle(WardenDeepLinkHandler.ACTION_START_SESSION, mapOf("label" to "   "))
        drain()
        assertEquals(WardenDeepLinkHandler.DEFAULT_LABEL, runtime.recorder.state.value.label)
    }

    @Test
    fun `start and stop drive the gateway`() = runTest {
        val (links, runtime) = handler()
        runtime.initialise()
        drain()
        assertTrue(runtime.status.value.running)

        links.handle(WardenDeepLinkHandler.ACTION_STOP, emptyMap())
        drain()
        assertFalse(runtime.status.value.running)

        links.handle(WardenDeepLinkHandler.ACTION_START, emptyMap())
        drain()
        assertTrue(runtime.status.value.running)
    }

    @Test
    fun `revoke-grants drops live grants`() = runTest {
        val (links, runtime) = handler()
        runtime.initialise()
        drain()
        runtime.grants.grant(Capability.EXECUTE, 600_000)
        links.handle(WardenDeepLinkHandler.ACTION_REVOKE, emptyMap())
        drain()
        assertTrue(runtime.grants.remaining().isEmpty())
    }

    // ---- the refusal that matters --------------------------------------------

    @Test
    fun `a policy change over a deep link is refused`() = runTest {
        // boss:// is registered with the OS, so any program that can ask the OS to
        // open a URL produces the same input. A link is not proof the operator asked
        // for anything, and widening policy is the one thing that must come from them.
        val (links, runtime) = handler()
        runtime.initialise()
        drain()
        runtime.setProfile(Profile.READ_ONLY)
        drain()

        assertFalse(
            links.handle(WardenDeepLinkHandler.ACTION_PROFILE, mapOf("profile" to Profile.FULL.id)),
            "a deep link was allowed to change the policy",
        )
        drain()
        assertEquals(Profile.READ_ONLY, runtime.status.value.profile, "the profile changed anyway")
    }

    @Test
    fun `a refused policy change is announced to the operator, not just logged`() = runTest {
        // This is the event the operator most wants to know about, so it must not be
        // a log line nobody reads.
        val (links, runtime) = handler()
        runtime.initialise()
        drain()
        links.handle(WardenDeepLinkHandler.ACTION_PROFILE, mapOf("profile" to Profile.FULL.id))
        drain()
        assertTrue(host.errors.any { "Refused a request" in it }, host.errors.toString())
    }

    @Test
    fun `no supported action can widen what the agent may do`() {
        // Pins the shape of the handler: everything it honours is a session or
        // lifecycle action. Adding a permissive verb should trip this.
        val widening = setOf(WardenDeepLinkHandler.ACTION_PROFILE)
        assertTrue(
            WardenDeepLinkHandler.REFUSED.containsAll(widening),
            "a policy-widening action is not in the refused set",
        )
        assertTrue(
            (WardenDeepLinkHandler.SUPPORTED - WardenDeepLinkHandler.REFUSED)
                .none { it.contains("profile") || it.contains("grant") && !it.startsWith("revoke") },
            "an honoured action looks like it grants something",
        )
    }

    @Test
    fun `the handler is registered under the same id as the panel`() {
        // boss://plugin?id=<x> resolves by whatever id the caller wrote, and a caller
        // reaching for this plugin writes the panel id: it is what the sidebar shows
        // and what cli(open_panel) takes. Registering under the plugin id instead made
        // every link answer "No deep-link action handler registered" while the caller
        // still saw ok:true, because dispatch succeeded and only the lookup failed.
        assertEquals(
            ai.rever.boss.plugin.dynamic.warden.WARDEN_PANEL_ID,
            ai.rever.boss.plugin.dynamic.warden.WardenPanelInfo.id.panelId,
            "the deep-link id and the panel id have drifted apart",
        )
    }

    @Test
    fun `an unknown action is declined rather than silently swallowed`() = runTest {
        // Returning true for an unrecognised verb would make the host believe it was
        // handled, and the operator would see nothing happen with no explanation.
        val (links, runtime) = handler()
        runtime.initialise()
        drain()
        assertFalse(links.handle("definitely-not-an-action", emptyMap()))
        assertFalse(links.handle("", emptyMap()))
    }

    @Test
    fun `actions are matched case-insensitively`() = runTest {
        // Deep links get typed by hand and mangled by shells.
        val (links, runtime) = handler()
        runtime.initialise()
        drain()
        assertTrue(links.handle("EXPORT", emptyMap()))
        drain()
        assertEquals(1, host.writtenReports.size)
    }
}
