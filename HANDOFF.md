# Handoff: Agent Warden

Written for whoever picks this up next, human or agent. It is not a summary of the README.
It records what was decided, what was checked, what is unverified, and where the sharp edges
are, so the next person argues with the reasoning rather than rediscovering it.

Context: BOSS Contributor Hackathon, submissions close **20 September 2026, 23:59 IST**.
Owner: Devansh (@Devx228).

---

## 1. What this plugin is, in one paragraph

Agent Warden stands up an MCP endpoint on `127.0.0.1:7678` that an agent attaches to instead
of BOSS's own on `7677`, and forwards to BOSS. Every JSON-RPC tool call passes through it, so
each one can be classified by blast radius, refused, escalated to the operator, and recorded.
At the end of a session it exports a Markdown report that sets what the agent said it was
doing beside what the workspace observed changing.

## 2. The one load-bearing design decision

**Interception is at the JSON-RPC wire, not at `McpToolRegistry`.**

This is the decision everything else rests on, and it is the thing to attack first if you
think the plugin is wrong. The argument, as it now stands:

BOSS 9.5.11 added its own policy engine, operator approval dialog and operation ledger, in
`McpToolRegistryCore.invoke`. That function opens by looking the tool up in the host registry.
BossTerm's MCP server, which is what an agent actually attaches to, serves its own built-ins
directly and terminal-tab adds two more through `BossTermMcpConfig.additionalTools`. Neither
group enters that function, so the host cannot govern them. They include every shell tool.

This was a guess when the plugin was designed. It has since been checked three ways:

- **By source.** `McpDynamicTools.registerOne` calls back through `registry.invoke`;
  `McpHostTools.bossHostMcpTools` calls the handler directly.
- **By absence in the host.** `git grep` on BossConsole `dev` finds no definition of
  `run_command`, `send_input`, `manage_tools` or the rest. They appear only in policy tables
  and in tests.
- **By bytecode.** Unpacking `boss-plugin-terminal-tab-2.5.71.jar`: the thirteen tool names are
  in `ai.rever.bossterm.compose.mcp.BossTermMcpServer`, and nothing anywhere under
  `ai.rever.bossterm` references `ai/rever/boss/mcp/Mcp*`. The control matters: the same search
  over terminal-tab's own package *does* find `McpToolRegistry` in `McpDynamicToolsKt$registerOne$1`,
  so the method detects the dependency where it exists.

Written up as **BossConsole#495**, with a host-side record in **#498**.

**What is still unverified, and it is the top of the list for whoever is next:** nobody has
watched a live instance. The whole argument is static. Launch BOSS, attach an agent, run
something through `run_command`, and confirm no approval dialog appears and no entry lands in
BOSS's own ledger. If a dialog appears, #495 and #498 are wrong and should be withdrawn fast,
and this plugin's value proposition shrinks to "a second opinion", which is still real but much
smaller. Two ways the static argument could be wrong are stated in #495: a reflection-based hop
would not show in the constant pool, and a terminal-tab newer than 2.5.71 might route
`additionalTools` differently.

## 3. Code map

```
gateway/
  McpGateway.kt         the JSON-RPC proxy. JDK http server + java.net.http only, deliberately
  Capabilities.kt       Capability enum + ToolCatalog, the name-to-capability table
  Policy.kt             Profile (READ_ONLY / BUILD / FULL) and the Decision it produces
  ApprovalCoordinator.kt   raises the operator dialog, holds the call while it waits
  GrantBook.kt          short-lived capability grants, so one yes is not forty prompts
  Ledger.kt             InvocationRecord + Redactor
  HostGovernanceGap.kt  what BOSS's own gate cannot receive. The only file that makes a
                        claim about BossConsole rather than about this plugin
session/
  SessionRecorder.kt    live session state
  SessionModels.kt      SessionSnapshot, FileTouch, GitSnapshot, AgentNote
  SessionReport.kt      pure snapshot-to-Markdown, clock injected so output is diffable
runtime/
  WardenRuntime.kt      wires the gateway to BOSS behind WardenHost
  WardenHost.kt         the seam. FakeWardenHost in tests implements it
  PluginContextWardenHost.kt   the real implementation over PluginContext
  WardenMcpTools.kt     the three agent-facing tools
  WardenDeepLinks.kt    boss://plugin?id=...&action=export
ui/
  WardenPanel.kt        the operator panel
```

## 4. Decisions that will look wrong until you know why

Each of these has cost someone time already. Change them only with the reason in hand.

- **There is no tool for changing policy.** No `warden_set_policy`, and `manage_tools` is
  refused outright and filtered out of `tools/list` so the agent is never told it exists. BOSS's
  own `manage_tools` takes an `enable` operation and states it cannot itself be disabled, so an
  agent that reaches it can restore any permission you removed. A control plane whose limits the
  controlled party can lift is decoration.
- **Unknown tools fail closed to "ask", not to allow.** A new plugin's tools would otherwise
  silently widen what the agent may do. This means a stale catalogue over-prompts, which is the
  intended direction.
- **`Profile.decide` never returns Allow for `UNKNOWN`, whatever the ceiling.** "Unknown" is an
  absence of information and cannot be covered by a blanket permission granted about things the
  operator could see.
- **`HostGovernanceGap` assumes an unrecognised tool IS governed by the host.** This fails
  towards *understating* what the plugin adds. The opposite default would let every newly
  installed plugin's tools inflate the number in the report, which would make the claim
  worthless.
- **`browser_run_js` is deliberately excluded from the gap.** It is the most dangerous tool on
  the surface and BOSS *can* govern it, because the browser plugin registers it through the
  registry. Claiming it would make the whole section unbelievable. There is a test named for it.
- **The gap section prints "Nothing" rather than a zero.** A section asserting the plugin's
  usefulness on a session that did not demonstrate it is advertising, not evidence.
- **The report states its own limits in `appendHowToRead`.** A report that overstates what it
  proves is worse than no report, because it gets believed.
- **Only JDK HTTP is used.** The plugin jar bundles only its own classes, so a third-party HTTP
  library would resolve at compile time and be missing at runtime.
- **Every collector runs on `context.pluginScope`.** Unload cancels them without `dispose()`
  having to remember. `dispose()` only stops the gateway and revokes grants.

## 5. State as of handoff

- **179 tests, zero failures, zero errors, zero skipped.** `./gradlew test`
- **Jar builds.** `./gradlew buildPluginJar`, about 290 KB
- **Version pins are correct and were checked.** `apiVersion 1.0.87` against a host on 1.0.89:
  the rule is major equal and host minor greater or equal, and the plugin docs say to pin low
  deliberately so it runs on the widest range of hosts. `minBossVersion 9.5.0` against 9.5.11.
- **Not run:** ktlint or detekt. This repo has neither configured, unlike BossConsole.
- **Not run:** any live BOSS instance, by me. See section 2.

## 6. Open work, roughly in value order

1. **Verify #495 live.** Section 2. Everything else is smaller than this.
2. **Screenshots or a recording.** There is one cropped `docs/panel.png` showing the top two
   cards. Nobody has seen the approval dialog, the ledger with real rows, or the exported
   report rendered. For a hackathon submission judged on "evidence that you tested the change",
   thirty seconds of video is probably worth more than another feature.
3. **A real visual pass on `WardenPanel.kt`.** It uses `BossTheme` and `BossThemeColors`
   correctly, has empty states, and renders newest-first. It has never been critiqued by anyone
   who could see it. I deliberately did not redesign it blind.
4. **The repository transfer.** The submodule PR (boss-plugins#31) points at
   `Devx228/boss-plugin-agent-warden`, while every other entry points at `risa-labs-inc`. The
   store publish key `BOSS_STORE_PLUGIN_PUBLISH_KEY` only resolves inside the org, so until this
   moves, the release workflow builds and then fails to publish. Asked in the PR, unanswered.
5. **Multi-window.** BOSS creates a `PluginContext` per window. A second window binds an
   ephemeral port and the panel shows which. Nobody has decided what the right behaviour is.
6. **Consume BossConsole#432's observation contract** if it lands, rather than proposing a
   second one.

## 7. Known limits, stated so nobody assumes more

- **This governs the MCP surface, not the agent.** An agent with its own shell acts without any
  MCP tool.
- **Pointing the agent at the gateway is manual and reversible.** Nothing stops a user, or an
  agent that can edit its own config, switching back to 7677. Supervision, not a sandbox.
- **Classification is by name.** Nothing on the MCP wire carries provenance, so two tools of one
  name are indistinguishable here.
- **File changes are correlated by time, not cause.** Your own edits and a background build
  appear too.
- **The agent's notes are unverified testimony**, labelled as such in the report.
- **Arguments are redacted, so the ledger is not a replay log.** By design.
- **`HostGovernanceGap` is a claim about a specific BossConsole version** and can go stale in a
  way nothing else here can. If the host closes the gap, these tools become governed twice,
  which costs nothing and only makes the report section not worth printing.

## 8. Related work in BossConsole, so you do not duplicate it

| Item | What | Whose |
|---|---|---|
| #495 | the coverage gap, with the bytecode verification | Devx228 |
| #498 | host-side record of which tools the gate can receive, plus drift guard | Devx228 |
| #496 | publishes `readOnly` on the discovery channel | Devx228 |
| #514 | `projectPath` returns null instead of empty string | Devx228 |
| #497 | policy engine consults `readOnly`, not only name tables | Devx228, another agent |
| #416 | maintainer's own issue asking for an agent-trace plugin | kshivang |
| #432 | host MCP execution observations for plugins | Sahvendra7 |
| #434 | MCP activity timeline | Ayush04-C |
| #336, #371 | the governance layer this all concerns | Nightingale2494, InfoSage05 |

A comment on #416 offers this plugin against that checklist. It offers the finding first and the
plugin second, deliberately, and does not ask for #342 to move.

## 9. House style, because it is enforced

BossConsole's `AGENTS.md` bans em-dashes in anything a person reads, and CI fails a PR that adds
one to a `.md` or `.html` file. Use a spaced hyphen. This repo follows the same rule. Prose here
and in the README explains *why*, not *what*; the code says what.
