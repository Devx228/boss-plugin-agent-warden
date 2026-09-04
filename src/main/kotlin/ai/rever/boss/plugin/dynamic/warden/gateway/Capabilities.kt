package ai.rever.boss.plugin.dynamic.warden.gateway

/**
 * What a tool can do to the workspace, independent of which plugin provides it.
 *
 * Policy is expressed over capabilities rather than tool names because tool names
 * are not stable: BOSS's MCP surface is assembled at runtime from whichever
 * plugins are loaded, so a profile written as a name list would silently stop
 * covering a tool the moment it was renamed, and would not cover a newly
 * installed plugin's tools at all.
 *
 * Ordering is significant. [ordinal] is the escalation order used by
 * [Profile.allows], so a profile that permits [EXECUTE] also permits everything
 * below it. Insert new members in the right place rather than appending.
 */
enum class Capability(
    val label: String,
    /** Shown to the operator in the approval dialog, so it says consequence, not category. */
    val consequence: String,
) {
    INSPECT(
        "Inspect",
        "List what is open in the workspace. Reveals structure, not content.",
    ),
    READ_CONTENT(
        "Read content",
        "Read file contents, terminal scrollback or page text. Terminal history " +
            "routinely contains tokens and passwords.",
    ),
    CONTROL_UI(
        "Control the workspace",
        "Open, close or navigate tabs and panels. Visible, and reversible by hand.",
    ),
    EXECUTE(
        "Execute commands",
        "Run shell commands with your user's privileges. Anything you can do, it can do.",
    ),
    BROWSER_SCRIPT(
        "Script the browser",
        "Run JavaScript inside the embedded browser, which is signed in to your " +
            "accounts. Reaches sessions a shell may hold no credentials for.",
    ),
    GOVERN(
        "Change its own permissions",
        "Alter which tools are exposed. A tool that can do this can undo any limit " +
            "you have set.",
    ),

    /**
     * Not in [ToolCatalog], so nothing is known about it.
     *
     * Fails closed: an unrecognised tool is treated as the most dangerous thing it
     * could be, because the alternative is that installing any plugin silently
     * widens what an agent may do without the operator being asked once. New tools
     * appear routinely, so this is the normal path for anything unclassified, not
     * an error case.
     */
    UNKNOWN(
        "Unrecognised",
        "This tool is not in the catalog, so what it does is unknown. It is treated " +
            "as high risk until classified.",
    ),
    ;

    val isHighRisk: Boolean
        get() = this == EXECUTE || this == BROWSER_SCRIPT || this == GOVERN || this == UNKNOWN
}

/**
 * Curated tool-name to [Capability] mapping.
 *
 * This has to be a literal table, and that is worth explaining because it looks
 * like something that should be derived. `McpToolDefinition` carries a `readOnly`
 * flag, but **it is not serialised into the `tools/list` response** - verified
 * against a live BossConsole 9.5.7 endpoint, where every tool object carries only
 * `name`, `description` and `inputSchema`. So a gateway sitting on the wire cannot
 * read it, and neither can any other MCP client.
 *
 * `McpToolRegistry.getAllTools()` does expose `readOnly` in-process, but only for
 * tools registered through the host registry; BossTerm's built-ins are the
 * dangerous ones and are served by that plugin's own server. Reading the registry
 * would therefore classify the safe half and miss the half that matters.
 *
 * Entries cover the surface of a default install. Anything absent resolves to
 * [Capability.UNKNOWN] and is treated as high risk, so an out-of-date table
 * over-prompts rather than under-protects.
 */
object ToolCatalog {
    private val table: Map<String, Capability> =
        mapOf(
            // Structure only.
            "list_tabs" to Capability.INSPECT,
            "get_active_tab" to Capability.INSPECT,
            "list_panes" to Capability.INSPECT,
            "plugins_list" to Capability.INSPECT,
            "ai_compose_status" to Capability.INSPECT,
            "browser_get_url" to Capability.INSPECT,
            "editor_detect_language" to Capability.INSPECT,
            // Content, which is where secrets leak from.
            "read_scrollback" to Capability.READ_CONTENT,
            "read_debug_console" to Capability.READ_CONTENT,
            "search_output" to Capability.READ_CONTENT,
            "get_last_command" to Capability.READ_CONTENT,
            "editor_read_file" to Capability.READ_CONTENT,
            "editor_read_buffer" to Capability.READ_CONTENT,
            "editor_get_selection" to Capability.READ_CONTENT,
            // Visible and undoable by hand.
            "close_panel" to Capability.CONTROL_UI,
            "editor_open_split" to Capability.CONTROL_UI,
            "show_image" to Capability.CONTROL_UI,
            "browser_navigate" to Capability.CONTROL_UI,
            "cli" to Capability.CONTROL_UI,
            // Arbitrary code as the user.
            "run_command" to Capability.EXECUTE,
            "run_in_panel" to Capability.EXECUTE,
            "run_in_sidebar" to Capability.EXECUTE,
            "send_input" to Capability.EXECUTE,
            "send_signal" to Capability.EXECUTE,
            // Arbitrary code in an authenticated browser.
            "browser_run_js" to Capability.BROWSER_SCRIPT,
            // Governs the tool surface itself.
            "manage_tools" to Capability.GOVERN,
        )

    /**
     * [Capability.UNKNOWN] for anything not in [table], including this plugin's own
     * tools: a gateway that exempted its owner would be the first thing worth
     * impersonating.
     */
    fun capabilityOf(toolName: String): Capability = table[toolName] ?: Capability.UNKNOWN

    /** Tool names this build knows about, for the panel's coverage indicator. */
    val classifiedNames: Set<String> get() = table.keys
}
