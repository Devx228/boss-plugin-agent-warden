package ai.rever.boss.plugin.dynamic.warden.gateway

/**
 * Which tools BOSS's own governance layer cannot act on, and therefore what this
 * gateway is actually adding rather than duplicating.
 *
 * BossConsole gained a policy engine, an operator approval dialog and an operation
 * ledger in 9.5.11. On the face of it that makes this plugin redundant. It does not,
 * and the reason is where the host put the gate rather than what the gate does.
 *
 * All three live inside `McpToolRegistryCore.invoke`, and that function opens by
 * looking the tool up in the host registry. The MCP server an agent attaches to is
 * BossTerm's, and terminal-tab assembles it from three sources:
 *
 * | Source | Reaches the host gate |
 * |---|---|
 * | tools bridged from the registry, which call back through `registry.invoke` | yes |
 * | terminal-tab's own, through `BossTermMcpConfig.additionalTools` | no |
 * | BossTerm's built-ins | no |
 *
 * The first group is the browser, editor, docker, kubernetes and secret tools. The
 * other two are the terminal, which is where shell execution lives. A tool in those
 * groups cannot be governed by the host, cannot be refused by it, and never appears
 * in its ledger, because it never arrives.
 *
 * This gateway sits on the JSON-RPC wire instead, in front of the whole endpoint, so
 * the distinction does not exist for it. That was a guess when this plugin was
 * designed and it is now checked: see BossConsole#495 for the finding and
 * BossConsole#498 for the host-side record of it.
 *
 * **This set is a claim about BossConsole, not about this plugin**, so it can go
 * stale in a way the rest of the plugin cannot. If the host closes the gap, these
 * tools become governed twice, which is harmless and merely means the sentence in
 * the report is no longer worth printing. Nothing here changes a policy decision:
 * [ToolCatalog] and [Profile] decide what happens to a call, and this only describes
 * what would have happened without the gateway.
 */
object HostGovernanceGap {
    /**
     * Tools served to agents by BossTerm rather than through the host registry.
     *
     * Derived by elimination rather than by reading BossTerm, which is a bundled
     * library: none is defined anywhere in BossConsole, and none is in terminal-tab's
     * `bossHostMcpToolDefs` except the two marked. That leaves BossTerm's own server
     * as the only remaining source. Kept byte-identical to
     * `McpGovernanceCoverage.EXTERNALLY_SERVED_TOOLS` in BossConsole#498, so the two
     * halves of the argument cannot drift.
     */
    val UNGOVERNED_BY_HOST: Set<String> =
        setOf(
            // Shell execution.
            "run_command",
            "run_in_panel",
            "send_input",
            "send_signal",
            // terminal-tab's own, through BossTermMcpConfig.additionalTools.
            "run_in_sidebar",
            "cli",
            // Terminal reads. Scrollback routinely contains tokens.
            "read_scrollback",
            "read_debug_console",
            "search_output",
            "get_last_command",
            // Workspace structure and display.
            "list_tabs",
            "get_active_tab",
            "list_panes",
            "close_panel",
            "show_image",
            // Edits the tool surface itself, and cannot be disabled through it.
            "manage_tools",
        )

    /**
     * Whether BOSS's own gate could have acted on [toolName].
     *
     * An unrecognised name answers true, matching the host's own default in
     * `McpGovernanceCoverage.isEnforceable`. The registry is the only source either
     * side can enumerate, so an unknown tool is far more likely to be a plugin's than
     * one of BossTerm's, and claiming credit for governing something the host already
     * governs would be the worse error here. Overstating what this plugin adds is the
     * failure mode this whole object has to avoid.
     */
    fun isGovernedByHost(toolName: String): Boolean = normalise(toolName) !in UNGOVERNED_BY_HOST

    /**
     * The distinct tools in [records] that only this gateway saw, in the order they
     * were first invoked.
     *
     * Ordered by first use rather than alphabetically because the report reads as a
     * narrative of the session, and sorted output would put `cli` above the
     * `run_command` that mattered.
     */
    fun ungovernedToolsIn(records: List<InvocationRecord>): List<String> =
        records
            .asSequence()
            .map { normalise(it.toolName) }
            .filter { it in UNGOVERNED_BY_HOST }
            .distinct()
            .toList()

    /** Calls in [records] that BOSS's own governance would not have seen at all. */
    fun ungovernedCallCount(records: List<InvocationRecord>): Int = records.count { !isGovernedByHost(it.toolName) }

    /**
     * Strips the client-side prefix an MCP client shows, matching
     * `DefaultMcpRiskEvaluator` and `McpGovernanceCoverage` in the host.
     *
     * Two answers for one tool, depending on whether the caller had already stripped
     * the prefix, would be worse than no answer.
     */
    private fun normalise(toolName: String): String = toolName.removePrefix("mcp__boss__")
}
