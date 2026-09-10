package ai.rever.boss.plugin.dynamic.warden.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * This object is the only thing in the plugin that makes a claim about BossConsole
 * rather than about itself, so it is the only thing that can be wrong without any
 * of the plugin's own behaviour being wrong.
 *
 * These tests therefore guard the two ways it could mislead: overstating what the
 * gateway adds, and drifting out of step with [ToolCatalog].
 */
class HostGovernanceGapTest {
    private fun record(
        id: Long,
        tool: String,
        capability: Capability = Capability.EXECUTE,
        outcome: Outcome = Outcome.ALLOWED,
    ) = InvocationRecord(
        id = id,
        atMillis = 1_757_000_000_000L + id,
        toolName = tool,
        capability = capability,
        outcome = outcome,
        argumentsPreview = "{}",
    )

    @Test
    fun `the shell tools BOSS cannot govern are recognised`() {
        for (tool in listOf("run_command", "run_in_sidebar", "run_in_panel", "send_input")) {
            assertFalse(HostGovernanceGap.isGovernedByHost(tool), tool)
        }
    }

    @Test
    fun `registry-provided tools are left to BOSS`() {
        // The control. Without it, an object that answered false to everything would
        // pass the test above and would claim credit for the entire surface.
        for (tool in listOf("browser_run_js", "editor_read_file", "k8s_exec", "secret_get")) {
            assertTrue(HostGovernanceGap.isGovernedByHost(tool), tool)
        }
    }

    @Test
    fun `an unknown tool is assumed governed by BOSS`() {
        // Fails towards understating what this plugin adds. The opposite default would
        // let every newly installed plugin's tools inflate the number in the report.
        assertTrue(HostGovernanceGap.isGovernedByHost("some_new_plugin_tool"))
    }

    @Test
    fun `the client-side prefix does not change the answer`() {
        assertFalse(HostGovernanceGap.isGovernedByHost("mcp__boss__run_command"))
        assertTrue(HostGovernanceGap.isGovernedByHost("mcp__boss__k8s_exec"))
    }

    @Test
    fun `ungoverned tools are listed once, in the order they were first used`() {
        val records =
            listOf(
                record(1, "cli"),
                record(2, "browser_run_js"),
                record(3, "run_command"),
                record(4, "run_command"),
                record(5, "editor_read_file"),
            )
        // Order is first-use, not alphabetical: the report reads as a narrative and
        // sorting would put `cli` above the `run_command` that mattered.
        assertEquals(listOf("cli", "run_command"), HostGovernanceGap.ungovernedToolsIn(records))
        assertEquals(3, HostGovernanceGap.ungovernedCallCount(records))
    }

    @Test
    fun `a session of only registry tools reports no gap`() {
        val records = listOf(record(1, "browser_run_js"), record(2, "editor_read_file"))
        assertEquals(emptyList(), HostGovernanceGap.ungovernedToolsIn(records))
        assertEquals(0, HostGovernanceGap.ungovernedCallCount(records))
    }

    /**
     * The drift guard.
     *
     * Every tool this plugin classifies as shell execution or as editing the tool
     * surface is one whose whole justification is that BOSS cannot reach it. If a
     * future edit moves one of those into the governed set, the argument in the
     * README quietly stops being true, and nothing else in the suite would notice.
     */
    @Test
    fun `every high-blast-radius terminal tool is one BOSS cannot reach`() {
        val terminalTools =
            listOf("run_command", "run_in_panel", "run_in_sidebar", "send_input", "send_signal", "manage_tools")
        for (tool in terminalTools) {
            assertTrue(
                ToolCatalog.capabilityOf(tool).isHighRisk,
                "$tool is no longer high risk in the catalog",
            )
            assertFalse(
                HostGovernanceGap.isGovernedByHost(tool),
                "$tool is now recorded as governed by BOSS, which undercuts the reason for the wire seam",
            )
        }
    }

    /**
     * `browser_run_js` is the counter-example that keeps the argument honest: it is
     * the single most dangerous tool on the surface and BOSS *can* govern it, because
     * the browser plugin registers it through the registry. The gateway's value is
     * about placement, not about being the only thing that works.
     */
    @Test
    fun `the most dangerous tool on the surface is one BOSS can already govern`() {
        assertEquals(Capability.BROWSER_SCRIPT, ToolCatalog.capabilityOf("browser_run_js"))
        assertTrue(HostGovernanceGap.isGovernedByHost("browser_run_js"))
    }
}
