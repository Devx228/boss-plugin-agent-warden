package ai.rever.boss.plugin.dynamic.warden.runtime

import ai.rever.boss.plugin.dynamic.warden.gateway.Capability
import ai.rever.boss.plugin.dynamic.warden.gateway.Decision
import ai.rever.boss.plugin.dynamic.warden.gateway.Profile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the orchestration end to end with no BOSS running, which is the whole
 * reason [WardenHost] exists as a seam.
 *
 * The bias throughout is that this plugin must load and keep working when the host
 * cannot answer. A panel that throws during `register()` gets recorded as binary
 * incompatible and disabled, which is worse than any degraded mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WardenRuntimeTest {
    private val host = FakeWardenHost()
    private val store = FakeSettingsStore()
    private var now = 10_000L
    private val runtimes = mutableListOf<WardenRuntime>()

    /**
     * Built on [TestScope.backgroundScope], not on the test's own scope.
     *
     * The runtime collects the host's file-change flow in a coroutine that by design
     * never completes. Launched in the test scope, `runTest` waits for it forever;
     * backgroundScope is cancelled when the test body ends, which is exactly the
     * lifetime a plugin scope has.
     */
    private fun TestScope.runtime(): WardenRuntime =
        WardenRuntime(host, backgroundScope, store) { now }.also { runtimes.add(it) }

    /**
     * Drains queued work, including the runtime's own launches.
     *
     * `advanceUntilIdle()` is the obvious choice and is wrong here: as of coroutines
     * 1.10 it deliberately does not run `backgroundScope` tasks, so every assertion
     * after it saw pre-launch state and the whole suite failed identically.
     * `runCurrent()` drains what is scheduled at the current virtual time, nested
     * launches included, which is what this runtime does - it schedules work, it does
     * not wait on timers.
     */
    private fun TestScope.drain() = testScheduler.runCurrent()

    /** True when nothing is listening on [port], which is how a released socket looks. */
    private fun portIsFree(port: Int): Boolean =
        runCatching { java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress("127.0.0.1", port)); true } }
            .getOrDefault(false)

    @AfterTest
    fun tearDown() {
        runtimes.forEach { it.dispose() }
    }

    // ---- lifecycle -----------------------------------------------------------

    @Test
    fun `initialise loads settings and starts when the gateway is enabled`() = runTest {
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = true, profileId = Profile.BUILD.id)
        val r = runtime()
        r.initialise()
        drain()
        assertTrue(r.status.value.running, "gateway did not start")
        assertNotNull(r.status.value.port)
        assertEquals(Profile.BUILD, r.status.value.profile)
    }

    @Test
    fun `initialise honours the gateway being switched off`() = runTest {
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = false)
        val r = runtime()
        r.initialise()
        drain()
        assertFalse(r.status.value.running)
        assertNull(r.status.value.port)
    }

    @Test
    fun `settings that cannot be loaded fall back to defaults rather than failing`() = runTest {
        // Plugin storage is nullable and can fail. Refusing to initialise would leave
        // the operator with a panel that never appears and no explanation.
        store.failLoad = true
        val r = runtime()
        r.initialise()
        drain()
        assertEquals(Profile.READ_ONLY, r.status.value.profile, "did not fall back to the safest profile")
    }

    @Test
    fun `a gateway that cannot start records the reason instead of throwing`() = runTest {
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = true, upstreamUrl = "not a url at all")
        val r = runtime()
        r.initialise()
        drain()
        assertFalse(r.status.value.running)
        assertNotNull(r.status.value.lastError, "a failed start left nothing for the panel to explain")
    }

    @Test
    fun `starting opens a session so no call can arrive unrecorded`() = runTest {
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = true)
        val r = runtime()
        r.initialise()
        drain()
        assertTrue(r.recorder.isRecording, "gateway was accepting calls with no session open")
    }

    @Test
    fun `stopping clears the port so the panel cannot advertise a dead endpoint`() = runTest {
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = true)
        val r = runtime()
        r.initialise()
        drain()
        r.stop()
        drain()
        assertFalse(r.status.value.running)
        assertNull(r.status.value.endpoint)
    }

    // ---- policy --------------------------------------------------------------

    @Test
    fun `changing profile revokes live grants`() = runTest {
        // Without this, dropping from Full access to Read only leaves a live EXECUTE
        // grant quietly overriding the profile just chosen, which is the opposite of
        // what pressing that control means.
        val r = runtime()
        r.initialise()
        drain()
        r.grants.grant(Capability.EXECUTE, 600_000)
        assertTrue(r.grants.isGranted(Capability.EXECUTE))

        r.setProfile(Profile.READ_ONLY)
        drain()
        assertFalse(r.grants.isGranted(Capability.EXECUTE), "a grant survived a profile change")
    }

    @Test
    fun `a profile change is persisted`() = runTest {
        val r = runtime()
        r.initialise()
        drain()
        r.setProfile(Profile.BUILD)
        drain()
        assertEquals(Profile.BUILD.id, store.current.profileId)
    }

    @Test
    fun `a profile change survives storage that cannot save`() = runTest {
        // The in-memory choice must still take effect, or the operator presses
        // "read only", sees nothing change, and concludes the control is broken.
        store.failSave = true
        val r = runtime()
        r.initialise()
        drain()
        r.setProfile(Profile.BUILD)
        drain()
        assertEquals(Profile.BUILD, r.status.value.profile)
    }

    @Test
    fun `a live grant overrides the profile for that capability only`() = runTest {
        val r = runtime()
        r.initialise()
        drain()
        r.setProfile(Profile.READ_ONLY)
        drain()
        r.grants.grant(Capability.EXECUTE, 600_000)

        assertTrue(r.grants.isGranted(Capability.EXECUTE))
        assertFalse(r.grants.isGranted(Capability.BROWSER_SCRIPT), "a grant leaked to another capability")
        assertTrue(r.status.value.profile.decide(Capability.BROWSER_SCRIPT) is Decision.Ask)
    }

    @Test
    fun `revokeGrants drops everything immediately`() = runTest {
        val r = runtime()
        r.initialise()
        drain()
        r.grants.grant(Capability.EXECUTE, 600_000)
        r.revokeGrants()
        assertTrue(r.grants.remaining().isEmpty())
    }

    // ---- sessions and reporting ---------------------------------------------

    @Test
    fun `ending a session writes a report and says where it went`() = runTest {
        val r = runtime()
        r.initialise()
        drain()
        r.beginSession("Refactor the parser")
        drain()
        r.logIntent("Read the tokenizer", "looking for the bug")
        r.endSessionAndExport()
        drain()

        assertEquals(1, host.writtenReports.size, "no report was written")
        val (name, content) = host.writtenReports.single()
        assertTrue(name.endsWith(".md"), name)
        assertTrue("Refactor the parser" in content)
        assertTrue("Read the tokenizer" in content, "the agent's note is missing from the report")
        assertTrue(host.notifications.any { "report written" in it }, host.notifications.toString())
    }

    @Test
    fun `ending with no session open says so rather than writing an empty report`() = runTest {
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = false)
        val r = runtime()
        r.initialise()
        drain()
        r.endSessionAndExport()
        drain()
        assertTrue(host.writtenReports.isEmpty(), "an empty report was written for a session that never existed")
        assertTrue(host.notifications.any { "No session" in it })
    }

    @Test
    fun `a report that cannot be written is reported as an error`() = runTest {
        // Silence here would leave the operator believing an audit trail exists.
        host.failReportWrite = true
        val r = runtime()
        r.initialise()
        drain()
        r.beginSession("s")
        drain()
        r.endSessionAndExport()
        drain()
        assertTrue(host.errors.any { "Could not write" in it }, host.errors.toString())
    }

    @Test
    fun `a snapshot can be exported without closing the session`() = runTest {
        val r = runtime()
        r.initialise()
        drain()
        r.beginSession("ongoing")
        drain()
        r.exportSnapshot()
        drain()
        assertEquals(1, host.writtenReports.size)
        assertTrue(r.recorder.isRecording, "exporting a snapshot closed the session")
        assertTrue("still open" in host.writtenReports.single().second, "an open session was not marked as a snapshot")
    }

    @Test
    fun `git state is captured at both ends of a session`() = runTest {
        val r = runtime()
        r.initialise()
        drain()
        r.beginSession("s")
        drain()
        host.git = ai.rever.boss.plugin.dynamic.warden.session.GitSnapshot(
            branch = "main",
            headBefore = "aaa1111",
            headAfter = "bbb2222",
        )
        r.endSessionAndExport()
        drain()
        assertTrue("HEAD moved" in host.writtenReports.single().second, "a commit during the session was not reported")
    }

    // ---- evidence ------------------------------------------------------------

    @Test
    fun `file changes reach the session record`() = runTest {
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = true)
        val r = runtime()
        r.initialise()
        drain()
        host.fileEvents.emit(HostFileChange("/home/dev/project/a.kt", "MODIFIED"))
        host.fileEvents.emit(HostFileChange("/home/dev/project/b.kt", "CREATED"))
        drain()
        assertEquals(2, r.recorder.state.value.fileTouches.size)
    }

    @Test
    fun `a host with no event bus still runs, just without file evidence`() = runTest {
        host.emitFileChanges = false
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = true)
        val r = runtime()
        r.initialise()
        drain()
        assertTrue(r.status.value.running, "a missing event bus stopped the gateway")
        assertTrue(r.recorder.state.value.fileTouches.isEmpty())
    }

    @Test
    fun `unclassified upstream tools are recorded for the coverage section`() = runTest {
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = true)
        host.toolNames = listOf("list_tabs", "run_command", "some_plugin_tool", "another_unknown")
        val r = runtime()
        r.initialise()
        drain()
        assertEquals(
            listOf("another_unknown", "some_plugin_tool"),
            r.recorder.state.value.unclassifiedTools,
            "coverage did not identify exactly the unclassified tools",
        )
    }

    @Test
    fun `an unreachable upstream does not stop the gateway starting`() = runTest {
        // Coverage reporting is diagnostic. It must never be able to fail a start.
        host.failToolListing = true
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = true)
        val r = runtime()
        r.initialise()
        drain()
        assertTrue(r.status.value.running)
    }

    // ---- settings ------------------------------------------------------------

    @Test
    fun `settings are sanitised before use`() = runTest {
        store.current = WardenSettings(preferredPort = 999_999, approvalTimeoutMillis = 1, grantDurationMillis = 1)
        val r = runtime()
        r.initialise()
        drain()
        val s = r.status.value.settings
        assertEquals(WardenSettings.DEFAULT_PORT, s.preferredPort)
        assertTrue(s.approvalTimeoutMillis >= WardenSettings.MIN_APPROVAL_TIMEOUT)
        assertTrue(s.grantDurationMillis >= WardenSettings.MIN_GRANT)
    }

    @Test
    fun `port zero is kept as a request for any free port`() = runTest {
        val settings = WardenSettings(preferredPort = 0).sanitised()
        assertEquals(0, settings.preferredPort, "port 0 was clamped, removing the ephemeral option")
    }

    @Test
    fun `changing the port restarts a running gateway on the new one`() = runTest {
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = true)
        val r = runtime()
        r.initialise()
        drain()
        val first = r.status.value.port
        r.updateSettings { it.copy(preferredPort = 0) }
        drain()
        assertTrue(r.status.value.running, "the gateway did not come back after a port change")
        assertNotNull(r.status.value.port)
        assertFalse(first == null)
    }

    // ---- being switched off ---------------------------------------------------

    @Test
    fun `being disabled closes the gateway, because the host never tells the plugin`() = runTest {
        // Measured against 9.5.11, and the reason this exists: disabling the plugin
        // from the Toolbox unregisters its panel and tools and stops its sandbox, and
        // never calls dispose(). Port 7678 stayed open and went on forwarding
        // run_command with the panel gone, which is the one state a supervision tool
        // must not be in.
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = true)
        val r = runtime()
        r.initialise()
        drain()
        assertTrue(r.status.value.running, "the gateway never started, so this proves nothing")
        val port = r.status.value.port
        assertNotNull(port)

        host.disablePlugin()
        drain()

        assertFalse(r.status.value.running, "the panel would still claim to be running")
        assertNull(r.status.value.port, "a dead endpoint was left advertised")
        assertTrue(portIsFree(port), "the socket outlived the plugin being switched off")
    }

    @Test
    fun `being disabled drops live grants too`() = runTest {
        // A grant is a permission the operator gave this gateway. Switching the
        // gateway off and leaving the permission behind would be the wrong half.
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = true)
        val r = runtime()
        r.initialise()
        drain()
        r.grants.grant(Capability.EXECUTE, 600_000)
        host.disablePlugin()
        drain()
        assertTrue(r.grants.remaining().isEmpty())
    }

    @Test
    fun `a host with no registry still loads, it just cannot notice a disable`() = runTest {
        // Every provider on PluginContext is nullable. Degrading to the old behaviour
        // is correct here; refusing to start would be worse than the bug being fixed.
        host.exposesRegistry = false
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = true)
        val r = runtime()
        r.initialise()
        drain()
        assertTrue(r.status.value.running)
    }

    @Test
    fun `dispose stops the gateway and drops grants`() = runTest {
        store.current = WardenSettings(preferredPort = 0, gatewayEnabled = true)
        val r = runtime()
        r.initialise()
        drain()
        r.grants.grant(Capability.EXECUTE, 600_000)
        r.dispose()
        assertTrue(r.grants.remaining().isEmpty(), "grants survived dispose, so a reload would inherit them")
    }
}
