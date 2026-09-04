<!-- markdownlint-disable MD041 -->
# Agent Warden

An approval and audit layer for the MCP tools an AI agent can call inside
[BOSS Console](https://github.com/risa-labs-inc/BossConsole). Built for the
[BOSS Contributor Hackathon](https://bossconsole.ai/hackathon/), Extend BOSS track.

Agent Warden stands up an MCP endpoint that your agent attaches to instead of
BOSS's own. Every tool call passes through it, so each one can be recorded,
refused, or put in front of you before it runs.

```text
  agent (Claude Code / Codex / Gemini / OpenCode)
    |
    v
  Agent Warden  :7678   <- classify, decide, record
    |
    v
  BOSS MCP      :7677   <- 30 tools, including shell and browser JS
```

## The problem

The hackathon brief asks, under its Web Workflow Kit starter, for something that
captures evidence and keeps approval with the operator. This is that, at the
tool-call layer.

Measured against a running BossConsole 9.5.7 with the default plugin set, an
attached agent is offered **30 MCP tools**. Among them:

| Tool | What it does |
|---|---|
| `run_command`, `run_in_sidebar`, `run_in_panel`, `send_input`, `cli` | Run shell commands as you |
| `browser_run_js` | Evaluate JavaScript in the embedded browser, which is signed in to your accounts |
| `read_scrollback`, `read_debug_console` | Read terminal history, which routinely contains tokens |
| `editor_read_file`, `editor_read_buffer` | Read files |

Two things are missing, and this plugin adds them.

**Nothing records which tools an agent invoked.** `ApplicationEventBus` carries
`FileChangeEvent`, `TerminalSessionEvent`, `TabEvent` and the rest, but nothing for
tool invocation. After a session there is no way to answer "what did the agent
actually do here?"

**Governance is global and manual.** `McpToolRegistry.setToolEnabled` and the
Toolbox's MCP tab give a persistent per-tool kill switch, and `manage_tools`
exposes enable/disable for BossTerm's 13 built-ins. Neither is scoped to a task or
a session, and neither prompts at call time.

## What it does

**Classifies every tool by blast radius**, not by name. Policy is expressed over
capabilities, so a renamed tool or a newly installed plugin cannot fall outside a
profile.

| Capability | Example | Read only | Build | Full access |
|---|---|---|---|---|
| Inspect | `list_tabs` | runs | runs | runs |
| Read content | `read_scrollback` | runs | runs | runs |
| Control the workspace | `browser_navigate` | asks | runs | runs |
| Execute commands | `run_command` | asks | runs | runs |
| Script the browser | `browser_run_js` | asks | asks | runs |
| Change its own permissions | `manage_tools` | **refused** | **refused** | **refused** |
| Unrecognised | anything unlisted | asks | asks | asks |

**Asks you about the rest**, once, with the tool name and its redacted arguments,
and offers a bounded grant so approving one `run_command` does not mean approving
the next forty individually.

**Records everything** in a session ledger, and exports a Markdown report that sets
what the agent said it did beside what the workspace observed changing.

## Two design decisions worth arguing about

**There is no tool for changing the policy.** The agent can read its policy and
record what it is doing. It cannot widen what it may do, and there is no
`warden_set_policy` to call.

This is deliberate, and it is the flaw the plugin answers. BOSS's own
`manage_tools` is an MCP tool the agent can call, it takes an `enable` operation,
and its own description states it cannot be disabled. An agent that reaches it can
hand back any permission you removed. A control plane whose limits the controlled
party can lift is decoration, so Agent Warden refuses that tool outright and
filters it from `tools/list` so the agent is never told it exists.

**Interception is at the JSON-RPC wire, not through `McpToolRegistry`.** The
registry would have been less code. It would also have covered the wrong half of
the surface: the tools worth governing are BossTerm's built-ins, and those are
served by that plugin's own MCP server rather than registered with the host.
Sitting on the wire covers plugin-contributed and built-in tools identically and
needs cooperation from neither. Verified: `tools/list` through the gateway returns
all 30 tools a direct connection returns.

## Install

Requires BossConsole 9.5.0 or later and `boss-plugin-api` 1.0.87.

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-agent-warden-*.jar ~/.boss_debug/plugins/   # or ~/.boss/plugins
```

Restart BOSS, or reload plugins from the Toolbox. The panel appears in the left
sidebar and the gateway starts on port 7678.

Then point your agent at the gateway instead of BOSS:

```jsonc
// .mcp.json, or your agent CLI's MCP config
{ "mcpServers": { "boss": { "url": "http://127.0.0.1:7678/mcp" } } }
```

That one line is the whole integration. If the gateway is stopped, point the agent
back at 7677 and nothing else changes.

## Agent-facing tools

| Tool | Purpose |
|---|---|
| `warden_get_policy` | What the agent may do, what will be escalated, and that the policy cannot be changed by asking |
| `warden_log_intent` | One line on what it is doing and why. The only record of intent that exists |
| `warden_session_summary` | Call counts, including how many were stopped, with a prompt to report that honestly |

All three are read-only with respect to the workspace.

## Development

```bash
./gradlew test                                        # 149 tests
./gradlew buildPluginJar
./gradlew runGatewayHarness --args="read-only true"   # drive the gateway with curl, no BOSS needed
```

The plugin jar bundles only its own classes, so the gateway is built on
`com.sun.net.httpserver` and `java.net.http`, both JDK built-ins. A third-party
HTTP library would resolve at compile time and be missing at runtime.

See [docs/VALIDATION.md](docs/VALIDATION.md) for what has been tested and how.

## Limitations

Stated here rather than discovered later.

- **This governs the MCP surface, not the agent.** An agent with its own shell can
  act without any MCP tool. What is covered is the workspace surface BOSS exposes,
  which is the part a plugin can stand in front of.
- **Pointing the agent at the gateway is manual and reversible.** Nothing stops a
  user, or an agent that can edit its own config, from switching back to 7677. This
  is a supervision tool, not a sandbox.
- **Classification is by name.** Another plugin registering a tool called
  `list_tabs` inherits that row's capability. Nothing on the MCP wire carries
  provenance, so a gateway at this layer cannot tell two tools of one name apart.
  Unlisted tools fail closed to "ask", so a stale catalog over-prompts rather than
  under-protects.
- **File changes are correlated by time, not cause.** Anything writing during a
  session appears in the report, including your own edits and a background build.
- **The agent's notes are unverified.** They are labelled as testimony in the report
  and sit beside the involuntary record so the two can be compared.
- **Arguments are redacted, so the ledger is not a replay log.** By design.
- **One window.** BOSS creates a `PluginContext` per window; a second window binds
  an ephemeral port instead, and the panel shows which.

## History

This repository began as "Project Studio", a five-stage project tracker, and was
redirected once the more interesting problem turned out to be what an agent
attached to BOSS is allowed to do, rather than how a person tracks their own work.
The original panel is in the git history at `b3a90b1`.

## Licence

Apache 2.0, matching BossConsole.
