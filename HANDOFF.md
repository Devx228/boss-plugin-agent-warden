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

This was a guess when the plugin was designed. It has since been checked four ways, the last of
them against a running instance:

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
- **Live, on 11 September 2026.** BossConsole 9.5.11 built from source, dev mode, this plugin
  loaded, calls sent to BOSS's own endpoint on 7677 with the gateway out of the path.
  `warden_get_policy` produced a row in `~/.boss_debug/mcp-calls.jsonl` and `run_command` and
  `list_tabs` produced none. A real Claude Code agent pointed at 7677 ran a shell command
  through `run_command` with no dialog and no ledger row. Written up in
  [docs/VALIDATION.md](docs/VALIDATION.md).

Written up as **BossConsole#495**, with a host-side record in **#498**.

The live run is what closes it, and the reason it closes it rather than merely agreeing with the
static argument is the order of operations inside the host. The gate runs before the handler, and
what came back were BossTerm's own errors, so BossTerm's handler ran and the gate did not. The
host writes its ledger row in a `finally` under `NonCancellable`, so a denial, a timeout and a
cancellation would each still leave one. No row means `invoke` was never entered.

**#495 and #498 stand. Nothing was withdrawn.** The two ways the static argument could have been
wrong, both stated in #495, are also closed by this: a reflection-based hop would still have
produced a ledger row, and the bytecode check has since been repeated on terminal-tab 2.5.74
after the host auto-updated it, with the same result and the same positive control.

## 3. Code map

```
gateway/
  McpGateway.kt         the JSON-RPC proxy. JDK http server + java.net.http only, deliberately
  Capabilities.kt       Capability enum + ToolCatalog, the name-to-capability table
  CommandRisk.kt        the only decision taken on an argument rather than a name
  Policy.kt             Profile (READ_ONLY / BUILD / FULL) and the Decision it produces
  ApprovalCoordinator.kt   raises the operator dialog, holds the call while it waits
  GrantBook.kt          short-lived capability grants, so one yes is not forty prompts
  Ledger.kt             InvocationRecord + Redactor
  HostGovernanceGap.kt  what BOSS's own gate cannot receive. The only file that makes a
                        claim about BossConsole rather than about this plugin
trace/
  TraceLog.kt           the durable, append-only, redacted record of every call
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
- **`CommandRisk` is an allowlist, and the list is short on purpose.** It can only ever lower
  `run_command` from execution to a read, and only when the program is one of a few that cannot
  run anything else, nothing is quoted or path-qualified, and the line contains no character
  that can chain, redirect or substitute. A denylist of dangerous commands would be decoration:
  `rm` is also `/bin/rm`, `"rm"` and `$(echo rm)`. Adding a program to the list is a security
  decision, not a convenience one. `env` is absent despite being a read, because it is a
  credential dump with no other use; `send_input` is never judged because it types into whatever
  is already running and the same text means different things depending on state the gateway
  cannot see.
- **A judged call still records what it was called as.** `InvocationRecord.declaredCapability`
  and the trace's `declaredCapability` are non-null only on a downgrade. Without them an allowed
  `run_command` under a profile that escalates execution is a contradiction on its face, and a
  reader who cannot see why is right to distrust the rest of the record.
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
- **The trace opens, appends and closes per line rather than holding a stream.** That is a
  syscall per tool call, and at the rate an agent emits them it is nothing. It buys the property
  the file exists for: a line that has been written is on disk rather than in a buffer a crash
  discards. It also leaves nothing to close on unload.
- **There is no tool for reading the trace**, for the same reason there is no tool for changing
  policy. A supervised party that can read its own supervision record is being asked politely.
- **A phase that did not happen is null in the trace, not zero.** "Not asked" and "asked and
  answered instantly" are different facts, and a zero makes them indistinguishable to anything
  aggregating the file.
- **Every collector runs on `context.pluginScope`.** Unload cancels them without `dispose()`
  having to remember. `dispose()` only stops the gateway and revokes grants.
- **The gateway also closes when the plugin is merely disabled, and that needs a workaround.**
  The host calls `dispose()` from `unloadPlugin` only; disable unregisters the panel and tools
  and never calls in, so the socket used to stay open and keep proxying with no panel to show
  it. `WardenHost.unregistered()` watches the host registry for this plugin's own provider
  disappearing, which is the only observable signal there is. It is a workaround for a missing
  hook and should be deleted if the host grows one.

## 5. State as of handoff

- **228 tests, zero failures, zero errors, zero skipped.** `./gradlew test`
- **Jar builds.** `./gradlew buildPluginJar`, about 290 KB
- **Version pins are correct and were checked.** `apiVersion 1.0.87` against a host on 1.0.89:
  the rule is major equal and host minor greater or equal, and the plugin docs say to pin low
  deliberately so it runs on the widest range of hosts. `minBossVersion 9.5.0` against 9.5.11.
- **Not run:** ktlint or detekt. This repo has neither configured, unlike BossConsole.
- **Run live against BossConsole 9.5.11 on 11 September 2026**, by curl and by a real Claude
  Code agent, at both endpoints. See section 2 and `docs/VALIDATION.md`. Two bugs came out of it
  and are fixed: a refusal was dropped from the audit trail when the caller had already hung up,
  and the report named the profile the session opened under rather than the one its decisions
  were taken under.

## 6. Open work, roughly in value order

1. **The panel's layout at its real size.** Done as a critique, not as a change: see
   [docs/PANEL-REVIEW.md](docs/PANEL-REVIEW.md). The headline is that at the height BOSS gives
   it when you open it, the panel shows the gateway card and the word "Policy" and nothing else.
   The call list, which is the point, is below the fold with nothing to indicate it exists.
   `docs/panel.png` was never a bad crop; it is what the panel looks like.
2. **The approval timeout outlives the clients it serves.** The coordinator waits five minutes;
   Claude Code gives up at two. The block still holds, and the trace now records both halves of
   it, so the disagreement is legible rather than mysterious. What is still unresolved is whether
   to shorten the wait, or to answer early once a client is known to be gone. Neither is
   obviously right, which is why neither was done.
3. **The panel's call list was rebuilt as an activity view and the rest of
   `docs/PANEL-REVIEW.md` is still open.** What changed: a held call now appears above the
   policy card with a counter that moves, rows lead with the outcome in a fixed column so the
   eye can scan for the red one, a judged call says why it did not need approval, and the list
   returns to the top when a new call arrives. What did not: the default panel height still
   shows almost nothing, the actions are still pinned a screen away from their labels, and the
   cards still read as one surface in the light theme.
4. **`CommandRisk`'s allowlist will need widening and each addition is a judgement.** The
   obvious candidates an agent reaches for are `git show`, `git rev-parse` (already in),
   `cargo tree`, `npm ls`, `docker ps`. Each needs the same question asked: can this program,
   with any flag, run something else or change anything.
4. **The repository transfer is not a blocker, and chasing it was a mistake.** The contributor
   guide settles it: a transfer into `risa-labs-inc` "is not a prerequisite to prototyping and
   should only happen by agreement", and store publication is a separate maintainer decision
   needing credentials this repository should not ask for. The org release workflow has been
   removed from `.github/workflows` accordingly, because the guide says in as many words that
   copying it into a personal repository does not grant store access. What is there now is a
   build-and-test workflow and a tag-triggered GitHub Release, neither of which uses a secret.
   The submodule PR (boss-plugins#31) was also opened out of order: umbrella registration comes
   after a proposal and a conversation about fit, not before one.
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
