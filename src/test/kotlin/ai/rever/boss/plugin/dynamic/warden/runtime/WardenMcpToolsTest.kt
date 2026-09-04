package ai.rever.boss.plugin.dynamic.warden.runtime

import ai.rever.boss.plugin.api.McpToolArgs
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
class WardenMcpToolsTest {
    private val host = FakeWardenHost()
    private val store = FakeSettingsStore()

    private fun TestScope.provider(): Pair<WardenMcpToolProvider, WardenRuntime> {
        val runtime = WardenRuntime(host, backgroundScope, store) { 10_000L }
        return WardenMcpToolProvider(runtime, "test.warden") to runtime
    }

    private fun TestScope.drain() = testScheduler.runCurrent()

    private fun args(vararg pairs: Pair<String, Any>) =
        McpToolArgs(pairs.toMap(), "{}")

    /** McpToolHandler.call is a suspend function, so every invocation needs a coroutine. */
    private suspend fun WardenMcpToolProvider.call(name: String, args: McpToolArgs = args()) =
        tools().first { it.name == name }.handler.call(args)

    // ---- the absence that is the design --------------------------------------

    @Test
    fun `no tool can widen what the agent is allowed to do`() {
        // The whole point of the plugin. BOSS's own manage_tools is agent-callable,
        // takes an 'enable' operation, and cannot be disabled - so an agent reaching
        // it hands itself back anything the operator removed. This provider must never
        // grow the same hole, and a reviewer adding a convenience tool should trip here.
        val (provider, _) = TestScope().provider()
        for (tool in provider.tools()) {
            for (forbidden in WardenMcpToolProvider.FORBIDDEN_TOOL_SUBSTRINGS) {
                assertFalse(
                    forbidden in tool.name.lowercase(),
                    "tool '${tool.name}' looks like it changes policy; the agent must not be able to",
                )
            }
        }
    }

    @Test
    fun `every tool is declared read-only with respect to the workspace`() {
        val (provider, _) = TestScope().provider()
        assertTrue(
            provider.tools().all { it.readOnly },
            "a warden tool declared itself mutating; none of them touch the workspace",
        )
    }

    @Test
    fun `the policy description tells the agent it cannot change the policy`() = runTest {
        // An agent that does not know the limit is fixed will spend turns trying to
        // lift it. Saying so plainly is both honest and cheaper.
        val (provider, _) = provider()
        val text = provider.call(WardenMcpToolProvider.TOOL_GET_POLICY).text
        assertTrue("no tool for changing this policy" in text, text)
        assertTrue("Only the operator can" in text, text)
    }

    // ---- policy disclosure ---------------------------------------------------

    @Test
    fun `the policy description names every capability and its verdict`() = runTest {
        val (provider, runtime) = provider()
        runtime.initialise()
        drain()
        runtime.setProfile(Profile.READ_ONLY)
        drain()

        val text = provider.call(WardenMcpToolProvider.TOOL_GET_POLICY).text
        assertTrue("Read only" in text, text)
        assertTrue("Execute commands: asks the operator each time" in text, text)
        assertTrue("Change its own permissions: refused, and cannot be approved" in text, text)
        assertTrue("Inspect: runs freely" in text, text)
    }

    @Test
    fun `the policy description reflects a profile change`() = runTest {
        val (provider, runtime) = provider()
        runtime.initialise()
        drain()
        runtime.setProfile(Profile.BUILD)
        drain()
        val text = provider.call(WardenMcpToolProvider.TOOL_GET_POLICY).text
        assertTrue("Execute commands: runs freely" in text, text)
        assertTrue("Script the browser: asks the operator each time" in text, text)
    }

    @Test
    fun `the policy description warns about unclassified tools`() = runTest {
        val (provider, _) = provider()
        val text = provider.call(WardenMcpToolProvider.TOOL_GET_POLICY).text
        assertTrue("does not recognise" in text, text)
    }

    // ---- intent logging ------------------------------------------------------

    @Test
    fun `logging an intent records it against the open session`() = runTest {
        val (provider, runtime) = provider()
        runtime.initialise()
        drain()
        runtime.beginSession("work")
        drain()

        val result = provider.call(
            WardenMcpToolProvider.TOOL_LOG_INTENT,
            args("summary" to "Reading the tokenizer", "detail" to "looking for the parse bug"),
        )
        assertFalse(result.isError)
        val note = runtime.recorder.state.value.notes.single()
        assertEquals("Reading the tokenizer", note.summary)
        assertEquals("looking for the parse bug", note.detail)
    }

    @Test
    fun `an empty summary is refused rather than recorded`() = runTest {
        // A blank note fills the operator's timeline with empty rows and makes a
        // diligent agent indistinguishable from a broken one.
        val (provider, runtime) = provider()
        runtime.initialise()
        drain()
        runtime.beginSession("work")
        drain()

        assertTrue(provider.call(WardenMcpToolProvider.TOOL_LOG_INTENT, args("summary" to "   ")).isError)
        assertTrue(provider.call(WardenMcpToolProvider.TOOL_LOG_INTENT).isError)
        assertTrue(runtime.recorder.state.value.notes.isEmpty())
    }

    @Test
    fun `logging with no session open explains itself without blaming the agent`() = runTest {
        // The agent cannot open a session and should not be told it did something wrong.
        val (provider, runtime) = provider()
        runtime.initialise()
        drain()
        val result = provider.call(WardenMcpToolProvider.TOOL_LOG_INTENT, args("summary" to "x"))
        assertTrue(result.isError)
        assertTrue("not your fault" in result.text, result.text)
    }

    @Test
    fun `an over-long note is truncated rather than refused`() = runTest {
        // Refusing would lose the note entirely; the operator would rather have the
        // first three hundred characters than nothing.
        val (provider, runtime) = provider()
        runtime.initialise()
        drain()
        runtime.beginSession("work")
        drain()
        provider.call(WardenMcpToolProvider.TOOL_LOG_INTENT, args("summary" to "x".repeat(5000)))
        assertEquals(WardenMcpToolProvider.MAX_SUMMARY, runtime.recorder.state.value.notes.single().summary.length)
    }

    // ---- session summary -----------------------------------------------------

    @Test
    fun `the session summary reports counts the agent should own up to`() = runTest {
        val (provider, runtime) = provider()
        runtime.initialise()
        drain()
        runtime.beginSession("work")
        drain()
        runtime.logIntent("did a thing", null)

        val text = provider.call(WardenMcpToolProvider.TOOL_SESSION_SUMMARY).text
        assertTrue("Session: work" in text, text)
        assertTrue("Notes you have recorded: 1" in text, text)
    }

    @Test
    fun `the summary tells the agent when its calls were stopped`() = runTest {
        // An agent that reports success while calls were being refused is the exact
        // failure this whole feature is trying to make visible.
        val (provider, runtime) = provider()
        runtime.initialise()
        drain()
        runtime.beginSession("work")
        drain()
        runtime.recorder.record(
            ai.rever.boss.plugin.dynamic.warden.gateway.InvocationRecord(
                id = 1,
                atMillis = 10_000,
                toolName = "run_command",
                capability = ai.rever.boss.plugin.dynamic.warden.gateway.Capability.EXECUTE,
                outcome = ai.rever.boss.plugin.dynamic.warden.gateway.Outcome.BLOCKED,
                argumentsPreview = "{}",
            ),
        )
        val text = provider.call(WardenMcpToolProvider.TOOL_SESSION_SUMMARY).text
        assertTrue("did not reach BOSS" in text, text)
        assertTrue("Report that honestly" in text, text)
    }

    @Test
    fun `the summary says so plainly when nothing is open`() = runTest {
        val (provider, runtime) = provider()
        runtime.initialise()
        drain()
        assertEquals("No session is open.", provider.call(WardenMcpToolProvider.TOOL_SESSION_SUMMARY).text)
    }

    // ---- contract ------------------------------------------------------------

    @Test
    fun `tool names are unique, prefixed, and stable`() {
        // The prefix is what keeps these from colliding with another plugin's tools
        // in a flat MCP namespace, and the names are part of the published contract.
        val (provider, _) = TestScope().provider()
        val names = provider.tools().map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate tool name")
        assertTrue(names.all { it.startsWith("warden_") }, names.toString())
        assertEquals(
            setOf(
                WardenMcpToolProvider.TOOL_GET_POLICY,
                WardenMcpToolProvider.TOOL_LOG_INTENT,
                WardenMcpToolProvider.TOOL_SESSION_SUMMARY,
            ),
            names.toSet(),
        )
    }

    @Test
    fun `every tool has a description that tells an agent when to call it`() {
        val (provider, _) = TestScope().provider()
        for (tool in provider.tools()) {
            assertTrue(tool.description.length > 80, "description for ${tool.name} is too thin to guide an agent")
        }
    }
}
