# Validation

What has been tested, how, and what has not been.

## Automated: 228 tests

```bash
./gradlew test
```

| Suite | Tests | What it pins |
|---|---:|---|
| `McpGatewayTest` | 36 | Wire behaviour over real sockets against a recording fake upstream |
| `WardenRuntimeTest` | 27 | Orchestration end to end with no BOSS running |
| `SessionReportTest` | 23 | What the report must never omit and never claim |
| `SessionRecorderTest` | 19 | Concurrency, capping, and event collapsing |
| `TraceLogTest` | 16 | Durability, redaction, rotation, and failing without taking a call down |
| `CommandRiskTest` | 15 | Which shell commands may skip the operator, mostly by counting the ones that may not |
| `WardenMcpToolsTest` | 15 | The agent-facing contract, including what is deliberately absent |
| `ToolCatalogTest` | 12 | Classification, including the bug found in live testing |
| `ApprovalCoordinatorTest` | 11 | Serialisation, timeouts, and fail-closed behaviour |
| `PolicyTest` | 11 | The decision matrix, exhaustively |
| `WardenDeepLinksTest` | 11 | Deep-link actions, and the policy change that is refused |
| `RedactorTest` | 9 | Redaction, written from the leak backwards |
| `GrantBookTest` | 8 | Expiry against an injected clock, and thread safety |
| `HostGovernanceGapTest` | 8 | The claim about BossConsole, including the tool deliberately excluded from it |
| `ReportLocationTest` | 7 | Where reports go when the host answers blank |

Three choices worth explaining.

**The gateway is tested over real sockets, not a mocked HTTP layer.** The
guarantees are about bytes on a wire. A mock would pass while the real server
dropped a session header or answered in a shape agents read as a crash.

**The central assertions are negative.** "A refused call never reaches upstream"
cannot be established against a live BOSS without trusting that nothing else on
the machine made the call. `FakeUpstream` records every request it receives, so
the question becomes a fact about a list.

**Expiry and timing are driven by injected clocks.** Nothing in the suite sleeps,
so the whole run takes about fifteen seconds.

## Mutation testing on the security-critical branches

Passing tests are not evidence that the tests would catch a regression. Two
mutations were applied to `Profile.decide` and the suite re-run.

| Mutation | Killed by |
|---|---|
| Hard denial stops denying (`capability in hardDenied` → `false`) | 7 tests |
| The explicit `UNKNOWN` branch is removed | **0 tests** |

The second was a real gap, and it is worth reading carefully because the
underlying code was correct.

`UNKNOWN` is the highest ordinal in `Capability`, and all three shipped profiles
have a ceiling below it, so they reach `Ask` through the ordinal comparison whether
or not the explicit branch exists. The mutation was semantically equivalent for
every profile the tests exercised. But the branch is what makes the documented
promise true - "never returns `Allow` for `UNKNOWN`, whatever the ceiling" - for a
profile whose ceiling *is* `UNKNOWN`, and no test constructed one.

`PolicyTest.a ceiling of UNKNOWN still does not auto-allow unclassified tools` now
does, and it kills the mutant.

## Manual: against a running BossConsole 9.5.7

Local build from source, default plugin set, local Supabase backend.

**The plugin loads.**

```text
07:54:45 INFO DynamicPluginLoader: Plugin loaded successfully
         | {pluginId=ai.rever.boss.plugin.dynamic.warden, version=0.2.0}
07:54:45 INFO McpToolRegistry: MCP tool provider registered
         | {providerId=ai.rever.boss.plugin.dynamic.warden, tools=3}
07:54:45 INFO AgentWarden: Agent Warden registered | {}
07:54:46 INFO McpGateway: MCP gateway listening
         | {port=7678, upstream=http://127.0.0.1:7677/mcp}
```

**The panel renders.**

![The Agent Warden panel in BOSS](panel.png)

**The gateway proxies faithfully.** `tools/list` through 7678 returns the same
tools a direct connection to 7677 returns, minus the ones policy hides, plus this
plugin's own three.

**Policy is enforced end to end.** Driven with curl against the live gateway,
default `Read only` profile:

```text
[1] warden_get_policy    -> the agent is told what it may do, and that it cannot change it
[2] warden_log_intent    -> "Recorded."
[3] list_tabs            -> forwarded, real answer returned, no prompt
[4] manage_tools(enable) -> "Change its own permissions is refused by the 'Read only' profile."
[5] warden_session_summary:
      Tool calls: 4
        allowed: 3
        blocked: 1
      Notes you have recorded: 1
      1 of your calls did not reach BOSS. Report that honestly rather than
      describing the work as complete.
```

Step 4 is the one that matters. `manage_tools` is BOSS's own tool for enabling and
disabling MCP tools, it is callable by the agent, and its description states it
cannot itself be disabled. Through the gateway it is refused and does not appear in
`tools/list` at all.

## A bug this found

`warden_get_policy` was not in `ToolCatalog`, so it classified as `UNKNOWN`, was
escalated, raised an approval dialog and blocked until the call timed out. The one
tool whose purpose is telling an agent what it may do could not be called without
interrupting the operator.

This is exactly the class of bug the unit tests could not find, because every test
either supplied its own catalog expectations or used tool names that were already
classified. It took running the real plugin against the real host.

Fixed in `6de2074`, with `ToolCatalogTest` now pinning the provider's tool list
against the catalog so a fourth warden tool without a catalog row fails the build.

## A real agent, driven through the gateway

A live Claude Code session (2.1.260) pointed at the gateway rather than at BOSS:

```jsonc
// warden-mcp.json
{ "mcpServers": { "boss": { "type": "http", "url": "http://127.0.0.1:7678/mcp" } } }
```

```bash
claude -p "Call warden_get_policy and tell me what profile you are under. Then call
list_tabs. Then try manage_tools with operation=enable and names=[\"run_command\"],
and report exactly what happened." --mcp-config warden-mcp.json
```

The agent's own reply, abridged:

> **Profile:** "Read only" - inspect/read run freely; workspace control, command
> execution, and browser scripting are escalated to you each time; changing my own
> permissions is refused outright and cannot be approved.
>
> **list_tabs:** `{"tabs": []}`
>
> **manage_tools:** I couldn't call it - there is no `manage_tools` tool on the boss
> server. I searched the deferred-tool list by exact name and by keywords; no match.

Three things happened there, and the third is the one worth having.

The agent read its policy and described it accurately, so the disclosure works as
guidance rather than as something it has to discover by failing. `list_tabs` was
forwarded and answered normally, so ordinary work is unaffected.

And **`manage_tools` was not refused, it was invisible.** The agent searched for it
by name and by keyword and concluded it does not exist. There was nothing to
attempt, nothing to retry, and no reasoning spent on working around a limit. That
is the difference between filtering `tools/list` and only refusing at call time.

Afterwards, the ledger had recorded the session:

```text
Session: Agent session
Profile: Read only
Tool calls: 7
  allowed: 6
  blocked: 1
Notes you have recorded: 1
```

## Report export, and two bugs it found

Export is reachable from `boss://plugin?id=agent-warden&action=end-session`, so it
can be triggered from the `boss` CLI, from BOSS's own `cli` tool, or from a script,
rather than only by clicking. Triggered that way against the running plugin, it
wrote [docs/example-report.md](example-report.md), reproduced from a real session.

Getting there took two fixes, both of which only a live host could have surfaced.

**The deep-link handler was registered under the wrong id.** It used the plugin id,
but `boss://plugin?id=<x>` resolves by whatever id the caller writes, and a caller
reaching for this plugin writes the *panel* id: it is what the sidebar shows and
what `cli(open_panel)` takes. Every link answered "No deep-link action handler
registered" in the host log while the caller still received `ok: true`, because
dispatch succeeded and only the lookup failed. A test now pins the two ids together.

**`PluginContext.projectPath` answers `""`, not `null`, when no project is open.**
So the Elvis fallback to the home directory never fired and the report path became
`/.agent-warden/...`, which is the filesystem root. The resolution now lives in
`ReportLocation`, treats every blank candidate as absent, falls back to `user.home`,
and has seven tests of its own.

One thing worth noticing in the exported report: the arguments column contains
`token=<redacted>`. That was a real GitHub token shape passed to `editor_read_file`
during the test, dropped at capture before it reached either the ledger or the file.

## The coverage gap, verified against a live 9.5.11

This is the claim the whole plugin rests on, and until 11 September 2026 it had
only ever been checked statically. BossConsole#495 says BOSS's own approval gate,
added in 9.5.11, cannot see the terminal tools, because it lives inside
`McpToolRegistryCore.invoke` and that function opens with a host-registry lookup,
while BossTerm serves those tools directly.

Checked live, against BossConsole 9.5.11 built from source in dev mode, with the
default plugin set and this plugin loaded. Every call below went to BOSS's **own**
endpoint on 7677. The gateway was not in the path. `~/.boss_debug/mcp-calls.jsonl`
did not exist beforehand, so the file's whole content is what these calls produced.

| Tool | Served by | Host ledger row | Approval dialog |
|---|---|---|---|
| `warden_get_policy` | the host registry, bridged through `McpDynamicTools.registerOne` | yes | no, policy resolves to ALLOW |
| `list_tabs` | BossTerm | no | no |
| `run_command` | BossTerm | no | no |

The control is the first row. It is the only row the ledger ever held:

```jsonc
{"toolName":"warden_get_policy","providerId":"ai.rever.boss.plugin.dynamic.warden",
 "policyApplied":"ALLOW","approvalDisposition":"AUTO_ALLOWED","durationMs":8,"isError":false}
```

So the ledger was live and writing. `run_command` produced no row.

Two details make this decisive rather than suggestive.

**The gate runs before the handler.** `McpToolRegistryCore.invoke` resolves the
tool, asks `McpPolicyEngine`, and raises the dialog, all before `executeAuthorized`.
`run_command` is in the host's own `McpMutatingToolCatalog.KNOWN_MUTATING_TOOLS`,
so its resolved action is ASK and a dialog was due. What came back instead were
BossTerm's own errors, first `Missing required argument: script` and then
`No registered terminal window`, which are its argument validation and its terminal
registry. BossTerm's handler ran. The gate did not.

**The ledger cannot miss a governed call.** `invoke` writes its row in a `finally`
block under `NonCancellable`, so a policy denial, an approval timeout and a
cancelled call all still produce one. No row at all means the function was never
entered.

Then the same thing with a real agent rather than curl. A Claude Code session
(2.1.268) pointed at 7677, told to call `run_command` with `echo AGENT-DIRECT-7677`.
The command ran, in a BossTerm pane, and its output is visible in the terminal. No
dialog appeared and the ledger still held one row.

![A shell command run by an agent through BOSS's own endpoint, ungoverned](ungoverned-direct.png)

The bytecode check was also reproduced independently: of 2286 classes under
`ai/rever/bossterm` in `boss-plugin-terminal-tab-2.5.71.jar`, none references
`McpToolRegistry`, while the same search over terminal-tab's own package finds
seven, including `McpDynamicToolsKt$registerOne$1`, whose disassembly contains the
`McpToolRegistry.invoke` interface call.

**Repeated on 2.5.74**, after the host auto-updated terminal-tab mid-testing. This
matters because #495 names a newer terminal-tab routing `additionalTools`
differently as one of the two ways its argument could be wrong. It does not: 2214
classes under `ai/rever/bossterm`, zero references to `McpToolRegistry`, the same
seven in terminal-tab's own package, and `registerOne` still calling
`registry.invoke`. The gap now has two versions behind it rather than one.

**#495 and #498 stand.** Nothing was withdrawn.

## The same agent, through the gateway

Identical prompt, identical binary, only the endpoint changed to 7678. Profile set
to Read only in the panel.

The approval dialog appeared, titled "Agent wants to execute commands", naming the
tool, the redacted argument and the consequence, with Refuse preselected. The
command did not run while it was open.

![The gateway holding an agent's run_command for an operator decision](approval-dialog.png)

Driven with curl so the client timeout could not confuse the result:

| Call | Answer given | Held for | Result the caller got |
|---|---|---|---|
| `run_command` `echo WARDEN-REFUSE-PROBE` | Refuse | 31s | `Refused by the operator.`, `isError: true` |
| `run_command` `echo WARDEN-ALLOWED-PROBE` | Allow once | forwarded | the command ran, output captured |
| `manage_tools` `operation=enable` | none, refused by profile | 1ms | `Change its own permissions is refused by the 'Read only' profile.` |

Neither `run_command` produced a row in BOSS's own ledger, which stayed at one.
Both are in the gateway's, and the exported report's gap section reads "3 of 3
call(s), across 2 tool(s)".

![The panel after all three calls](panel-in-session.png)

## Two bugs the live run found

Both were invisible to the test suite because both are about what happens when a
caller goes away, and both were fixed with the failing test written first.

**A stopped call was lost from the audit trail exactly when it mattered.** The
gateway wrote the refusal to the client and recorded it afterwards. Approvals take
as long as a person takes; the agent's client gave up at 120 seconds and closed the
socket; the operator answered at 133 seconds; writing to the dead socket threw; the
throw unwound past the record call. The panel showed "0 calls" and the report would
have omitted a shell command the operator had just refused. Recording now happens
before the answer is written, on both the refuse and the hard-deny paths.
`McpGatewayTest` pins the order by holding the handler inside the record callback
and asserting the caller has not been answered yet; reverting the order fails both
new tests and no others.

**The report named the wrong policy.** `beginSession` captured the profile name
once, and changing profile mid-session never reached the snapshot. The first live
export was headed "Profile | Full access" while listing a `run_command` the
operator had refused, which only Read only escalates. A report that misnames the
policy is worse than one that omits it, because the misnamed one gets believed.
`SessionSnapshot` now carries the changes, the row shows the profile in force at
the end, and a session whose policy moved says so and lists when. A session that
ran under one policy prints nothing extra.

Both fixes were then put back in front of a running 9.5.11 rather than left at
green tests. A call was made with a ten second client timeout and answered at
twenty six seconds, so the caller was gone before the operator decided; the
refusal appears in the panel and in the report. The profile was moved to Build and
back, and the exported report names Read only with both changes listed under it.
`docs/example-report.md` is that session, exported from the panel, unedited.

## The durable trace, verified live

Added after the run above, for the hole it found: everything the plugin observed
lived in memory until somebody pressed Export, so a crash or a closed window took
the session with it. Driven against BossConsole 9.5.11 with the plugin loaded and
the profile on Read only.

All three kinds of line appeared, in one file, in order. Verbatim, with only the
long ones wrapped:

```jsonc
{"seq":1,"kind":"session","session":"session-1789082391888",
 "detail":"session opened under 'Read only'"}

{"seq":2,"kind":"call","tool":"list_tabs","capability":"INSPECT","outcome":"ALLOWED",
 "arguments":"{}","upstreamMs":30,"totalMs":32,"governedByHost":false}

{"seq":3,"kind":"call","tool":"manage_tools","capability":"GOVERN","outcome":"BLOCKED",
 "arguments":"operation=enable, names=[\"run_command\"]","totalMs":0,
 "governedByHost":false,"detail":"Change its own permissions is refused by ..."}

{"seq":4,"kind":"call","tool":"editor_read_file","capability":"READ_CONTENT",
 "outcome":"FAILED","arguments":"path=/tmp/x, token=<redacted>","upstreamMs":31,
 "totalMs":31,"governedByHost":true}

{"seq":6,"kind":"call","tool":"run_command","capability":"EXECUTE","outcome":"REFUSED",
 "arguments":"script=echo TRACE-HELD","waitedForOperatorMs":65889,"totalMs":65889,
 "governedByHost":false,"detail":"Refused by the operator."}

{"seq":7,"kind":"undelivered","tool":"run_command",
 "detail":"An established connection was aborted by the software in your host machine"}
```

Six things in there were the point of building it.

**A real credential did not reach the file.** `editor_read_file` was called with
`token=ghp_A1b2C3d4E5f6G7h8I9j0K1l2` and the file holds `token=<redacted>`. The
trace uses the same `Redactor` as the report rather than a weaker copy of its own.

**The phases are separated.** `list_tabs` shows 30ms upstream and no operator wait
at all. The refused `run_command` shows 65889ms of operator wait and no upstream,
because it never got there. A single duration could not say either of those things.

**A phase that did not happen is absent, not zero.** There is no
`waitedForOperatorMs` on `list_tabs`, so nothing aggregating this file can confuse
"not asked" with "answered instantly".

**`governedByHost` is right on both sides of the line.** False for the BossTerm
tools, true for `editor_read_file`, which the host registry does serve. That is the
field that lets somebody diff this against `~/.boss_debug/mcp-calls.jsonl` without
taking the plugin's word for the classification.

**Seq 6 and 7 are the pair worth having.** The client's 60 second timeout expired,
the operator answered a second later, and the refusal was correct, recorded and
delivered to nobody. Before that second line, the trace showed a clean refusal
while the agent's own transcript showed an unexplained timeout, and there was
nothing to reconcile them.

**`manage_tools` shows totalMs 0 and no upstream leg**, which is what a hard denial
should look like: refused by the profile without anything being asked or forwarded.

The panel half was checked in the same run. A held call now renders above the
policy card with a counter that moves, so the panel no longer reads "0 calls" while
a shell command sits at a dialog:

![The panel showing a call held at an approval dialog](held-call.png)

The line naming the trace file appears in the session card once one has been
written, which is deliberate: shown as evidence that writing is working rather than
as a path somebody would have to go and check.

## A real agent doing ordinary work, judged command by command

The point of judging commands rather than tool names is that an agent should be
able to look around without interrupting anyone, and should still be stopped when
it reaches for something that changes the machine. Driven with a real Claude Code
session (2.1.268) through the gateway on 7678, profile on Read only, told to run
five commands in order and report each one.

The whole session's trace, as written:

```text
14:01:48  session      session opened under 'Read only'
14:02:36  ALLOWED      list_tabs    INSPECT                            {}
14:04:48  ALLOWED      list_tabs    INSPECT                            {}
14:06:04  ALLOWED      run_command  EXECUTE->READ_CONTENT              script=git status
14:06:15  ALLOWED      run_command  EXECUTE->READ_CONTENT              script=pwd
14:06:26  ALLOWED      run_command  EXECUTE->READ_CONTENT              script=ls -la
14:06:36  ALLOWED      run_command  EXECUTE->READ_CONTENT              script=git log --oneline -5
14:09:23  REFUSED      run_command  EXECUTE              waited 164s   script=rm -rf /tmp/warden-demo-target
14:09:23  undelivered  run_command                                     Connection reset by peer
```

**Four shell commands ran with no prompt at all.** Not approved quickly, not
covered by a grant: never put to anybody. Each one carries `EXECUTE->READ_CONTENT`
so the record says both what it was called as and what it was decided as.

**The fifth stopped.** `rm -rf` is not on any list, so it stayed execution, raised
the dialog, and waited. Nothing in the terminal shows it having run.

The agent's own account of the same five calls, abridged, is the other half of the
evidence because it was written without knowing which of them had been judged:

> 1. **git status** succeeded functionally. It reported branch `main` with no
>    commits yet and a very large untracked list.
> 2. **pwd** succeeded and printed the working directory.
> 3. **ls -la** failed because the pane runs PowerShell, not a POSIX shell.
> 4. **git log --oneline -5** failed with `fatal: your current branch 'main' does
>    not have any commits yet`.
> 5. **rm -rf /tmp/warden-demo-target** returned only `The operation timed out.`
>    with no stdout, stderr, exit code, or pane metadata. That response shape
>    differs from the other four, which all came back as structured JSON. I cannot
>    tell from this alone whether the command was blocked, is waiting on a prompt,
>    or ran.

Four ran and returned real output, including their real failures. The fifth came
back shaped differently and the agent could not tell why, which is what a block
should look like from the far side.

![Three commands already run, the delete stopped at a dialog, and the panel showing it held](judged-and-stopped.png)

The screenshot is one frame of the same run: the terminal has `git status`, `pwd`
and `ls -la` in it, the dialog is up for the delete, and the panel behind it reads
"Waiting on you" with the counter moving.

**Two things that only showed up by running it.**

The operator warning fired and was accurate. Past two minutes the held card says
most agents have stopped waiting, and this one had given up at eight seconds. The
refusal was still recorded 164 seconds later, and the `undelivered` line names the
reason it reached nobody.

The activity list kept its scroll offset. Prepending a row pushed the view down by
exactly one row, so the refusal arrived and the panel went on showing the two calls
before it. Newest-first is not enough on its own when the list can scroll; it now
returns to the top when the newest record changes, and only then, so an operator
who has deliberately scrolled back is not yanked away while nothing is happening.

## Installing the way the contributor guide says to

The guide's documented install path is Toolbox, **From File**, pick the jar. On a
development build from source at 9.5.11 that button answers:

```text
File picker not available. Use GitHub URL instead.
```

So this path could not be tested, and the plugin has never been installed that way.
The cause is host-side rather than anything in this plugin: the host does ship a
desktop file picker (`DesktopFilePickerProvider`, AWT/Swing, in
`FilePickerProviderFactory.kt`), and `DefaultPlugin.filePickerProvider` returns it,
but the Toolbox plugin at 1.9.24 is reading it as null and falling back to the
message above. Worth reporting separately; it is not a finding about Agent Warden.

What this means practically is that the install path a reviewer can actually use is
Toolbox's **From GitHub**, which needs a published release. That is the main reason
the release workflow exists in this repository.

Installing by copying the jar into `~/.boss_debug/plugins/` and restarting has been
done many times across this testing and works.

## Disabling the plugin, and the bug that found

The contributor guide says to test disable and re-enable. Doing it found the worst
defect in this project so far, and it would have shipped.

**Disabling Agent Warden did not stop it.** From the Toolbox, toggling it off
removed the panel and its three MCP tools, and left port 7678 listening. Probed
immediately afterwards, the orphaned gateway completed an MCP handshake and
forwarded a shell command to BOSS, which ran:

```text
(base) PS C:\Users\devan> echo DISABLED-WARDEN-PROBE
DISABLED-WARDEN-PROBE
```

An operator who switches off the supervision layer has every reason to believe it
is off. Instead it was still in the path, still proxying, with no panel and no way
to see any of it.

**The cause is that the host never tells a plugin it has been disabled.**
`dispose()` runs from `unloadPlugin` only. Disable goes through
`TrackingPluginContext.unregisterAll`, which unregisters panels, tab types, MCP tool
providers and UI extensions, then stops the sandbox. It never calls into the plugin
instance. For a plugin whose whole footprint is a registered panel that is fine.
This one holds a `ServerSocket`, and a socket nobody closes stays open.

The host's own notification also reads "Plugin 'ai.rever.boss.plugin.dynamic.warden'
has been disabled due to repeated failures" on an ordinary operator-initiated
disable, logged at ERROR. That wording is the host's and is unrelated to any failure
here; the disable sequence in the log is clean.

**The fix, and why it is shaped the way it is.** There is no disable hook to use, so
the plugin watches for the one thing the host demonstrably does do: unregister its
tool provider. `McpToolRegistry.allTools` is a `StateFlow` carrying `providerId`, so
`WardenHost.unregistered()` waits for this plugin's provider to appear once and then
emits when it goes. The collector runs on `pluginScope`, which belongs to the window
rather than the plugin and so outlives the disable, which is exactly what lets it
observe one.

This is a workaround for a missing hook, not a design, and it says so in its own
KDoc. The proper fix is host-side and worth raising separately.

Pinned by `WardenRuntimeTest`, which starts a real gateway, signals a disable, and
asserts the port can be bound again afterwards. Removing the watch fails both new
tests and no others. A host that exposes no registry degrades to the old behaviour
rather than refusing to load, and that has its own test.

## What the live run did not settle

- **`run_command` never returns promptly on this machine.** BossTerm waits for
  shell integration to report completion, the BossTerm setup wizard was skipped, so
  every call returned `Timed out after 120000ms waiting for command to finish` with
  the output captured. The command runs. The result is slow. This is environmental
  and independent of the gateway, but it is why both agent transcripts report a
  timeout rather than the refusal text.
- **The approval timeout outlives the clients it serves.** `ApprovalCoordinator`
  waits five minutes; Claude Code gives up at two. An operator who takes longer than
  two minutes leaves the agent with an opaque transport timeout rather than
  "Refused by the operator". The block is real either way, and both halves are now
  in the trace as a `call` and an `undelivered` line, so the disagreement between
  the two accounts is legible. Whether to shorten the wait, or to answer early once
  a client is known to be gone, is still open. The panel warns the operator past two
  minutes rather than deciding it for them.

## What has not been tested

Stated so nobody assumes otherwise.

- **Only Claude Code has been driven through it.** Codex, Gemini and OpenCode speak
  the same protocol and are expected to work, but have not been run.
- **The approval dialog has been observed firing, not driven.** The dialog was
  raised by the classification bug above, which is how the bug was noticed. The
  three-way choice and the grant timer are covered by unit tests against
  `ApprovalPrompt`, not by clicking the real dialog.
- **Windows only.** Nothing platform-specific is used beyond JDK APIs, but it has
  not been run on macOS or Linux.
- **Single window.** Two BOSS windows would each construct a runtime and the second
  would fall back to an ephemeral port. Not exercised.

## Reproducing

```bash
./gradlew test
./gradlew buildPluginJar
cp build/libs/boss-plugin-agent-warden-*.jar ~/.boss_debug/plugins/
# restart BOSS, then:
curl -s -D /tmp/h -X POST http://127.0.0.1:7678/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"1"}}}'
```

To drive the gateway without BOSS at all:

```bash
./gradlew runGatewayHarness --args="read-only true"
```
