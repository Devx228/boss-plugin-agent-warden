# Handoff: Agent Warden

Written for whoever picks this up next, human or agent. It is not a summary of the README.
It records what was decided, what was checked, what is unverified, and where the sharp edges
are, so the next person argues with the reasoning rather than rediscovering it.

Context: BOSS Contributor Hackathon, submissions close **20 September 2026, 23:59 IST**.
Owner: Devansh (@Devx228). Today is 11 September 2026, so there are nine days left.

**The goal is to win.** That is worth stating because it changes what "done" means. A plugin
that works is not the target; a submission a maintainer finds hard to argue with is. What has
carried this project so far is not features, it is that every claim in it has been checked and
the ones that could not be checked are labelled. Keep doing that. The fastest way to lose is
one inflated number that a reviewer catches, because then they stop believing the rest.

---

## 0. Read this first if you are new

Three things to understand before touching anything.

**What BOSS is.** An operator's console for AI agents, built on the JVM with Compose
Multiplatform rather than Electron. It gives an agent a real workspace: an embedded browser it
can navigate and script, a terminal you can share to a phone by QR, a code editor, a secret
manager, git. You bring your own agent, whether Claude Code, Codex, Gemini or OpenCode. The
unusual part is that the same MCP layer the agent uses to do work also exposes BOSS itself, so
its tabs, terminals, browser and editor are published as `mcp__boss__*` tools and the agent
drives the running app rather than only talking about your code. Plugins hot-reload at runtime
and Toolbox is an app store inside the app.

**What this plugin is.** Agent Warden stands up an MCP endpoint on `127.0.0.1:7678` that an
agent attaches to instead of BOSS's own on `7677`, and forwards to it. Every JSON-RPC tool call
passes through it, so each one can be classified by blast radius, refused, escalated to the
operator, and recorded. At the end of a session it exports a Markdown report setting what the
agent said it was doing beside what the workspace observed changing.

**Why it is not redundant with BOSS's own governance.** BOSS already has role-based access
control, a kill switch per tool, scoped secrets and signed plugins. Every one of those answers
"may this tool ever be used", and answers it in advance. None answers "should this call, with
these arguments, happen now". BOSS's own README admits two gaps on top of that: an admin
bypasses every permission check, and coverage is per-tool with some mutating tools declaring no
permission at all. This project established a third, which is section 2. **BOSS governs the
surface. This plugin governs the calls.** If you cannot explain that distinction in one
sentence, you will drift into rebuilding something the host already does.

---

## 1. Where everything lives

| Thing | Where |
|---|---|
| This plugin | `Devx228/boss-plugin-agent-warden`, checked out at `boss-plugin-project-studio` |
| BossConsole source | `~/OneDrive/Desktop/BOSS-risa`, currently 9.5.11 |
| BOSS dev data root | `~/.boss_debug` (dev mode; `~/.boss` is the normal one) |
| Reports and trace | `~/.agent-warden/` |
| Host's own MCP ledger | `~/.boss_debug/mcp-calls.jsonl` |

Running BOSS from source: `./gradlew :composeApp:run` in `BOSS-risa`. It needs local Supabase up
(Docker Desktop, then the containers auto-start) or it sits on an offline screen and never loads
plugins. Expect eight to fifteen minutes for a cold compile.

**Do not run gradle in the plugin repo while BOSS is running from source.** It stops BOSS's
gradle daemon and kills the app. This cost hours. Build the plugin, then launch BOSS, then leave
gradle alone.

---

## 2. The one load-bearing design decision

**Interception is at the JSON-RPC wire, not at `McpToolRegistry`.**

This is the decision everything else rests on, and it is the thing to attack first if you think
the plugin is wrong. The argument:

BOSS 9.5.11 added its own policy engine, operator approval dialog and operation ledger, in
`McpToolRegistryCore.invoke`. That function opens by looking the tool up in the host registry.
BossTerm's MCP server, which is what an agent actually attaches to, serves its own built-ins
directly and terminal-tab adds more through `BossTermMcpConfig.additionalTools`. Neither group
enters that function, so the host cannot govern them. They include every shell tool.

This was a guess when the plugin was designed. It has since been checked four ways, the last of
them against a running instance:

- **By source.** `McpDynamicTools.registerOne` calls back through `registry.invoke`;
  `McpHostTools.bossHostMcpTools` calls the handler directly.
- **By absence in the host.** `git grep` on BossConsole `dev` finds no definition of
  `run_command`, `send_input`, `manage_tools` or the rest. They appear only in policy tables
  and in tests.
- **By bytecode.** Unpacking terminal-tab: the tool names are in
  `ai.rever.bossterm.compose.mcp.BossTermMcpServer`, and nothing under `ai.rever.bossterm`
  references `McpToolRegistry`. The control matters: the same search over terminal-tab's own
  package *does* find it in `McpDynamicToolsKt$registerOne$1`, so the method detects the
  dependency where it exists. Repeated on 2.5.71 and 2.5.74.
- **Live, on 11 September 2026.** Calls sent to BOSS's own endpoint on 7677 with the gateway out
  of the path. `warden_get_policy` produced a row in `mcp-calls.jsonl`; `run_command` and
  `list_tabs` produced none. A real Claude Code agent pointed at 7677 ran a shell command
  through `run_command` with no dialog and no ledger row.

The live run is what closes it, and the reason it closes it rather than merely agreeing with the
static argument is the order of operations inside the host. The gate runs before the handler, and
what came back were BossTerm's own errors, so BossTerm's handler ran and the gate did not. The
host writes its ledger row in a `finally` under `NonCancellable`, so a denial, a timeout and a
cancellation would each still leave one. No row means `invoke` was never entered.

Written up as **BossConsole#495**, with a host-side record in **#498**.

**#495 and #498 stand. Nothing was withdrawn.** The two ways the static argument could have been
wrong, both stated in #495, are also closed: a reflection-based hop would still have produced a
ledger row, and the bytecode check has been repeated on a newer terminal-tab.

---

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
  SessionModels.kt      SessionSnapshot, FileTouch, GitSnapshot, AgentNote, ProfileChange
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

---

## 4. Decisions that will look wrong until you know why

Each of these has cost someone time already. Change them only with the reason in hand.

- **There is no tool for changing policy.** No `warden_set_policy`, and `manage_tools` is
  refused outright and filtered out of `tools/list` so the agent is never told it exists. BOSS's
  own `manage_tools` takes an `enable` operation and states it cannot itself be disabled, so an
  agent that reaches it can restore any permission you removed. A control plane whose limits the
  controlled party can lift is decoration.
- **There is no tool for reading the trace either**, for the same reason. A supervised party
  that can read its own supervision record is being asked politely.
- **Unknown tools fail closed to "ask", not to allow.** A new plugin's tools would otherwise
  silently widen what the agent may do. This means a stale catalogue over-prompts, which is the
  intended direction. See section 7, because it is also the biggest scaling problem.
- **`Profile.decide` never returns Allow for `UNKNOWN`, whatever the ceiling.** "Unknown" is an
  absence of information and cannot be covered by a blanket permission granted about things the
  operator could see.
- **`CommandRisk` is an allowlist, and the list is short on purpose.** It can only ever lower
  `run_command` from execution to a read, and only when the program is one of a few that cannot
  run anything else, nothing is quoted or path-qualified, and the line contains no character
  that can chain, redirect or substitute. A denylist would be decoration: `rm` is also
  `/bin/rm`, `"rm"` and `$(echo rm)`. Adding a program is a security decision. `env` is absent
  despite being a read, because it is a credential dump with no other use. `send_input` is never
  judged because it types into whatever is already running.
- **A judged call still records what it was called as.** `InvocationRecord.declaredCapability`
  is non-null only on a downgrade. Without it, an allowed `run_command` under a profile that
  escalates execution is a contradiction on its face.
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
- **The trace opens, appends and closes per line rather than holding a stream.** A syscall per
  tool call, which at the rate an agent emits them is nothing, and it buys the property the file
  exists for: a written line is on disk rather than in a buffer a crash discards.
- **A phase that did not happen is null in the trace, not zero.** "Not asked" and "asked and
  answered instantly" are different facts.
- **The gateway also closes when the plugin is merely disabled, and that needs a workaround.**
  The host calls `dispose()` from `unloadPlugin` only; disable unregisters the panel and tools
  and never calls in. `WardenHost.unregistered()` watches the host registry for this plugin's
  own provider disappearing. It is a workaround for a missing hook and should be deleted if the
  host grows one.
- **Every collector runs on `context.pluginScope`.** Unload cancels them without `dispose()`
  having to remember.

---

## 5. State as of handoff

- **228 tests, zero failures, zero errors, zero skipped.** `./gradlew test`
- **Jar builds**, about 360 KB. `./gradlew clean buildPluginJar`
- **CI is green on a clean Linux runner**, tests and jar, with the jar uploaded as an artifact.
- **Released as v0.2.0** with the jar attached.
- **Version pins checked.** `apiVersion 1.0.87` against a host on 1.0.89: major equal, host
  minor greater or equal, and the plugin docs say to pin low deliberately. `minBossVersion
  9.5.0` is the range it is written for, not the range it has been run on, which is 9.5.7 and
  9.5.11. The README says so.
- **Run live against BossConsole 9.5.11** by curl and by two real Claude Code agent sessions, at
  both endpoints. Three defects came out of it and are fixed; see below.
- **Not run:** ktlint or detekt. This repo has neither configured, unlike BossConsole.
- **Not run:** macOS or Linux, other than CI.

### What the live run found and fixed

1. **A stopped call was lost from the audit trail exactly when it mattered.** The refusal was
   written to the client and recorded afterwards; an agent that had given up waiting meant the
   write threw and the throw unwound past the record. The panel read "0 calls" after a shell
   command had just been refused. Recording now happens first.
2. **The report named the wrong policy.** A mid-session profile change never reached the
   snapshot, so an export was headed "Full access" while listing a refusal only "Read only"
   produces.
3. **Disabling the plugin did not stop it.** Port 7678 stayed listening and still forwarded
   `run_command`, with the panel gone. Fixed as described in section 4.

Each is pinned by a test that fails when the fix is removed.

### What was added

- **A durable trace.** Everything used to live in memory until somebody pressed Export.
  `agent-trace.jsonl` now gets every call as it resolves, with the operator wait and the
  upstream leg measured separately, whether the host could have governed it, and its own
  `undelivered` line when a verdict was reached and delivered to nobody.
- **Commands judged, not just tools.** See `CommandRisk` in section 4. Live, under Read only, a
  real agent ran `git status`, `pwd`, `ls -la` and `git log` with no prompt at all, and was
  stopped on `rm -rf`.
- **The panel's call list rebuilt as an activity view**, with a held-call card that ticks, an
  explanation on judged calls, and the list returning to the top when a call arrives.

---

## 6. Submission state, and what the contributor guide actually requires

`BOSS-Plugin-Contributor-Guide.md` is in the repo root and gitignored. Read it. The audit
against it is in `docs/VALIDATION.md`. Current position:

| Milestone | State |
|---|---|
| Share the source | Done. Public repo, v0.2.0 release with the jar |
| Proposal in boss-plugins Issues | **boss-plugins#33**, open, tags @kshivang for review |
| Umbrella registration | **boss-plugins#31** open, but opened *before* the proposal, which is the wrong order. A comment on it says so and offers to close |
| Store publication | Not requested. Maintainer decision, needs credentials this repo should not ask for |

Two guide rules that were being broken and are now fixed, so do not reintroduce them:

- **Do not copy the org's release workflow into this repo.** It referenced
  `BOSS_STORE_PLUGIN_PUBLISH_KEY`, which only resolves inside the org, and the guide says in as
  many words that copying it grants no store access. What is there now is build-and-test plus a
  tag-triggered release, neither using a secret.
- **A repository transfer into `risa-labs-inc` is not a prerequisite** and should only happen by
  agreement. Chasing it was wasted effort.

### Open contributions

| Item | What | State |
|---|---|---|
| BossConsole#495 | the coverage gap, with the bytecode and live verification | open issue |
| BossConsole#498 | host-side record of which tools the gate can receive, plus drift guard | open PR |
| BossConsole#496 | publishes `readOnly` on the discovery channel | open PR |
| BossConsole#497 | policy engine consults `readOnly`, not only name tables | open PR |
| BossConsole#514 | `projectPath` returns null instead of empty string | open PR |
| BossConsole#527 | CLI shims and deep-link handler agree on parameter names | open PR |
| BossConsole#332 | the original proposal for operator approval and evidence capture | open issue |
| boss-plugins#33 | the plugin proposal | open issue |
| boss-plugins#31 | the submodule entry | open PR, out of order |
| BossConsole#416 | kshivang's own issue asking for an agent-trace plugin | theirs |
| BossConsole#432, #434 | others' work on host observations and an MCP timeline | others' |

All five BossConsole PRs are mergeable and waiting on a maintainer approving a CI run. That wait
is not ours to end. **Do not open more PRs into BossConsole to pad a count.** The guide warns
against it and a maintainer will read it as noise.

---

## 7. Open work, in value order

**1. Classification does not scale, and this is the strongest candidate.** The catalog covers
the install tested exactly, 29 of 29. But BOSS's own `McpMutatingToolCatalog` names 19 more that
this plugin does not classify at all: seven `docker_*`, five `k8s_*`, four `helm_*`, plus
`secret_get`, `codebase_write` and `project_replace`. BOSS advertises 100+ tools. On a fuller
install almost everything falls to `UNKNOWN`, which fails closed and escalates every call, and
you are straight back to the prompt fatigue that `CommandRisk` was written to fix. A longer
hand-written table does not solve this. Getting the `readOnly` flag onto the discovery wire does,
and that is exactly what **BossConsole#496** already proposes. That would join the host PRs to
the plugin, which is a much better story than either alone.

**2. Detect the bypass.** The honest weakness, stated in the README, is that nothing stops an
agent pointing back at 7677. That cannot be closed from a plugin. It can be *detected*: compare
this plugin's own trace against the host's `mcp-calls.jsonl` and report calls that went around
it. Converting an admitted weakness into a detection is more valuable than pretending it is
closed, and it fits how this project argues.

**3. Databases and the browser, which is the direction Devansh specifically asked to explore.**
BOSS bundles a real embedded browser the agent can navigate and script, a Secret Manager with
browser auto-fill, and Supabase underneath the host. Nothing has been investigated here yet and
no web research has been done. Obvious questions to ask: what an agent doing database work
actually needs that BOSS does not give it; whether `browser_run_js` on a browser signed in to
your accounts deserves its own treatment beyond being escalated; whether a query-level approval
layer for a database plugin is the same idea as this one applied to a different surface. **This
is unexplored and is the most likely place a second plugin lives.**

**4. The panel's layout.** `docs/PANEL-REVIEW.md` is an honest critique written after seeing it
running. Items 4 and 5 are fixed and the call list was rebuilt. One, two, three, six and seven
are still open, and the first one is real: at the height BOSS gives it when you open it, the
panel shows the gateway card and the word "Policy" and nothing else.

**5. The approval timeout outlives the clients it serves.** The coordinator waits five minutes;
Claude Code gives up at two. Both halves are now in the trace so the disagreement is legible.
Whether to shorten the wait, or answer early once a client is known to be gone, is unresolved.

**6. Host-side fixes worth reporting.** The missing disable hook (section 4), and Toolbox's
**From File** answering "File picker not available" on a source build even though the host ships
`DesktopFilePickerProvider` and `DefaultPlugin.filePickerProvider` returns it, so the Toolbox
plugin at 1.9.24 appears to read it as null. Both are real and neither has been filed.

**7. Multi-window.** BOSS creates a `PluginContext` per window. A second window binds an
ephemeral port and the panel shows which. Nobody has decided what the right behaviour is.

---

## 8. Known limits, stated so nobody assumes more

- **This governs the MCP surface, not the agent.** An agent with its own shell acts without any
  MCP tool.
- **Pointing the agent at the gateway is manual and reversible.** Supervision, not a sandbox.
- **Classification is by name.** Nothing on the MCP wire carries provenance, so two tools of one
  name are indistinguishable here.
- **`CommandRisk` judges a string, not a process.** It is deliberately conservative and will
  refuse to vouch for things that are in fact harmless. That is the correct direction.
- **File changes are correlated by time, not cause.** Your own edits and a background build
  appear too.
- **The agent's notes are unverified testimony**, labelled as such in the report.
- **Arguments are redacted, so the ledger is not a replay log.** By design.
- **`HostGovernanceGap` is a claim about a specific BossConsole version** and can go stale. If
  the host closes the gap, these tools become governed twice, which costs nothing.

---

## 9. House style, because it is enforced

BossConsole's `AGENTS.md` bans em-dashes in anything a person reads, and CI fails a PR that adds
one to a `.md` or `.html` file. Use a spaced hyphen. This repo follows the same rule.

Prose here and in the README explains *why*, not *what*; the code says what. Comments carry the
reasoning for decisions that look wrong without it, which is why the comment ratio in main
source is about a third and that is deliberate.

Two conventions that were got wrong once and cost a round trip:

- **Repo Markdown is hard-wrapped at about 80 columns. GitHub issue and PR bodies are not.**
  Wrapping a GitHub comment makes it look broken. Let paragraphs flow there.
- **Never quote a number you have not measured.** A stale "30 tools" survived in three places
  from an earlier version; the live answer was 29. If a count can go stale, say what was
  measured and when, or do not give one.

ktlint allows 140 columns in BossConsole and detekt allows 120, so a line can pass one and fail
the other. Keep new code under 120. Do not add a detekt baseline entry to carry your own change.
