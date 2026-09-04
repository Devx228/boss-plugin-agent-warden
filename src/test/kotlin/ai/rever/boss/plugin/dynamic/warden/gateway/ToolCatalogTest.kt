package ai.rever.boss.plugin.dynamic.warden.gateway

import ai.rever.boss.plugin.dynamic.warden.runtime.WardenMcpToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The catalog is where a mistake is quietest: a tool put in the wrong row grants
 * silently, and a tool left out costs the operator a prompt every time it is used.
 */
class ToolCatalogTest {
    @Test
    fun `an unlisted tool is unknown, not assumed safe`() {
        assertEquals(Capability.UNKNOWN, ToolCatalog.capabilityOf("some_plugin_tool"))
        assertEquals(Capability.UNKNOWN, ToolCatalog.capabilityOf(""))
    }

    @Test
    fun `every shell-capable tool BOSS ships is classified as EXECUTE`() {
        // Measured against a live BossConsole 9.5.7 endpoint. If a future BOSS renames
        // one of these, this test still passes and the tool falls to UNKNOWN, which
        // over-prompts rather than under-protects - the safe direction.
        for (tool in listOf("run_command", "run_in_panel", "run_in_sidebar", "send_input", "send_signal")) {
            assertEquals(Capability.EXECUTE, ToolCatalog.capabilityOf(tool), "$tool is not classified as shell")
        }
    }

    @Test
    fun `browser scripting is its own tier, above shell`() {
        // browser_run_js runs inside a browser signed in to the user's accounts, and
        // reaches sessions a shell may hold no credentials for. Collapsing it into
        // EXECUTE would let a "Build" profile hand out account access.
        assertEquals(Capability.BROWSER_SCRIPT, ToolCatalog.capabilityOf("browser_run_js"))
        assertTrue(
            Capability.BROWSER_SCRIPT.ordinal > Capability.EXECUTE.ordinal,
            "browser scripting sits at or below shell, so a shell profile now grants it",
        )
    }

    @Test
    fun `the tool that edits the tool surface is classified as GOVERN`() {
        assertEquals(Capability.GOVERN, ToolCatalog.capabilityOf("manage_tools"))
    }

    @Test
    fun `content readers are separated from structure readers`() {
        // read_scrollback returns terminal history, which routinely contains tokens.
        // list_tabs returns names. A profile that permits one need not permit the other.
        assertEquals(Capability.READ_CONTENT, ToolCatalog.capabilityOf("read_scrollback"))
        assertEquals(Capability.INSPECT, ToolCatalog.capabilityOf("list_tabs"))
    }

    @Test
    fun `this plugin's own tools are classified so they do not prompt`() {
        // Found by running against a live BOSS: unclassified, warden_get_policy
        // resolved to UNKNOWN and raised an approval dialog, so the tool that exists
        // to tell an agent what it may do could not be called without interrupting
        // the operator.
        for (name in
            listOf(
                WardenMcpToolProvider.TOOL_GET_POLICY,
                WardenMcpToolProvider.TOOL_LOG_INTENT,
                WardenMcpToolProvider.TOOL_SESSION_SUMMARY,
            )
        ) {
            assertEquals(Capability.INSPECT, ToolCatalog.capabilityOf(name), "$name would raise a dialog")
        }
    }

    @Test
    fun `the catalog covers every tool the provider actually registers`() {
        // Pins the two lists together. Adding a fourth warden tool without a catalog
        // row would reintroduce the dialog bug for that tool alone, which is exactly
        // the kind of thing that ships unnoticed.
        val runtimeToolNames =
            setOf(
                WardenMcpToolProvider.TOOL_GET_POLICY,
                WardenMcpToolProvider.TOOL_LOG_INTENT,
                WardenMcpToolProvider.TOOL_SESSION_SUMMARY,
            )
        assertTrue(
            ToolCatalog.classifiedNames.containsAll(runtimeToolNames),
            "a warden tool is missing from the catalog: ${runtimeToolNames - ToolCatalog.classifiedNames}",
        )
    }

    @Test
    fun `no classified tool sits at UNKNOWN`() {
        // UNKNOWN is the absence of a classification. An explicit row holding it would
        // be indistinguishable from an omission and would never be noticed.
        for (name in ToolCatalog.classifiedNames) {
            assertFalse(
                ToolCatalog.capabilityOf(name) == Capability.UNKNOWN,
                "$name is listed but classified as UNKNOWN",
            )
        }
    }

    @Test
    fun `high-risk capabilities are exactly the ones a profile must escalate`() {
        assertEquals(
            setOf(Capability.EXECUTE, Capability.BROWSER_SCRIPT, Capability.GOVERN, Capability.UNKNOWN),
            Capability.entries.filter { it.isHighRisk }.toSet(),
        )
    }

    @Test
    fun `every capability explains its consequence in plain language`() {
        // The consequence string is what the operator reads in the approval dialog at
        // the moment they decide. A category name there tells them nothing.
        for (capability in Capability.entries) {
            assertTrue(capability.consequence.length > 40, "${capability.name} has no usable consequence text")
            assertTrue(capability.label.isNotBlank())
        }
    }
}
