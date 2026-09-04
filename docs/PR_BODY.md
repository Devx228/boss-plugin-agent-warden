## The problem

BOSS attaches an agent to a workspace and hands it a large MCP tool surface.
Measured against a running BossConsole 9.5.7 with the default plugin set, that is
**30 tools**, including shell execution (`run_command`, `run_in_sidebar`,
`send_input`, `cli`), JavaScript evaluation inside the embedded browser
(`browser_run_js`), and terminal scrollback reads.

Two things are missing.

**Nothing records which tools an agent invoked.** `ApplicationEventBus` carries
`FileChangeEvent`, `TerminalSessionEvent`, `TabEvent` and the rest, but no
invocation event. After a session there is no way to answer "what did the agent
actually do here?"

**Governance is global and manual.** `McpToolRegistry.setToolEnabled` and the
Toolbox's MCP tab give a persistent per-tool kill switch; `manage_tools` exposes
enable/disable for BossTerm's 13 built-ins. Neither is scoped to a task or a
session, and neither prompts at call time.

The hackathon brief's Web Workflow Kit starter asks for something that captures
evidence and keeps approval with the operator. This is that, at the tool-call
layer.

## The approach

A plugin, with no core changes, that hosts an MCP endpoint the agent attaches to
instead of BOSS's own and forwards to it. Every `tools/call` passes one point that
can record it, refuse it, or put it to the operator.

Interception is at the **JSON-RPC wire**, not through `McpToolRegistry`. The
registry would have been less code and would have covered the wrong half of the
surface: the tools worth governing are BossTerm's built-ins, served by that
plugin's own MCP server rather than registered with the host. The wire covers both
kinds identically and needs cooperation from neither.

No new dependencies. `com.sun.net.httpserver` and `java.net.http` are JDK
built-ins, and `buildPluginJar` bundles only this project's classes, so a
third-party HTTP library would resolve at compile time and be missing at runtime.

## Design decisions worth reviewing

**Policy is expressed over capabilities, not tool names.** A renamed tool or a
newly installed plugin cannot fall outside a profile. Unclassified tools resolve to
`UNKNOWN` and are never auto-allowed by any profile, including the most permissive.

**There is no tool for changing the policy.** The agent can read its policy and log
its intent. It cannot widen what it may do. `manage_tools` is refused under every
profile and filtered out of `tools/list` entirely, so the agent is never told it
exists. This is the flaw the plugin answers: `manage_tools` is agent-callable,
takes an `enable` operation, and its own description states it cannot be disabled,
so an agent reaching it can hand back any permission the operator removed.

**Everything fails closed.** An approval timeout denies. A prompt that throws
denies. A dismissed dialog denies. "Refuse" is the pre-selected choice, so a reflex
Enter cannot be the keystroke that grants shell access.

**The plugin must always load.** A `register()` that throws is recorded by the host
as binary incompatible and disabled, which is worse than any degraded mode, so
every entry point catches and leaves the panel able to explain itself.

## Evidence of testing

**167 tests**, run on every build. Full breakdown in
[docs/VALIDATION.md](VALIDATION.md).

The gateway is tested over real sockets against a recording fake upstream, because
the guarantees are about bytes on a wire and the central assertions are negative -
"a refused call never reaches upstream" is a fact about a list of received
requests, not something a mock can establish.

**Mutation testing** was run on the security-critical branches. Removing hard
denial is caught by 7 tests. Removing the explicit `UNKNOWN` branch was caught by
**none**: it is a no-op for the three shipped profiles, which all sit below
`UNKNOWN` in the escalation order. The branch is what makes the documented promise
true for a profile whose ceiling *is* `UNKNOWN`, and no test constructed one. One
now does.

**Run against a live BossConsole 9.5.7**, which found three bugs no unit test would
have:

1. `warden_get_policy` was unclassified, so the tool whose purpose is telling an
   agent what it may do raised an approval dialog and blocked.
2. The deep-link handler registered under the plugin id, but `boss://plugin?id=<x>`
   resolves by the panel id. Links answered "no handler registered" in the host log
   while the caller still received `ok: true`.
3. `PluginContext.projectPath` answers `""`, not `null`, when no project is open,
   so the report path resolved to the filesystem root.

**A real Claude Code session** (2.1.260) was pointed at the gateway and asked to
enable `run_command` for itself. Its reply: *"there is no `manage_tools` tool on the
boss server. I searched the deferred-tool list by exact name and by keywords; no
match."* Not refused - invisible. Nothing attempted, nothing retried.

[docs/example-report.md](example-report.md) is a real exported session report.

## Limitations

Stated in the README rather than left to be discovered. The short version: this
governs the MCP surface, not the agent; pointing the agent at the gateway is manual
and reversible; classification is by name, because nothing on the MCP wire carries
provenance; file changes are correlated by time, not cause; and the agent's own
notes are unverified and labelled as such.

## Related

Opened `risa-labs-inc/BossConsole#332` before building, asking which integration
surface the maintainers would prefer. Building against the proxy in the meantime
because the ledger, policy, approval flow and panel are unaffected by that answer -
only the transport would change.

A smaller upstream contribution suggests itself from this work: `McpToolDefinition`
carries a `readOnly` flag that is never serialised into `tools/list`, so no MCP
client of BOSS can see it. MCP has a standard slot for this
(`annotations.readOnlyHint`). Exposing it would let this plugin's catalog stop being
a hand-maintained table, and would help every other client too.
