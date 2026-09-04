package ai.rever.boss.plugin.dynamic.warden.runtime

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.dynamic.warden.gateway.Capability
import ai.rever.boss.plugin.dynamic.warden.gateway.Decision
import ai.rever.boss.plugin.dynamic.warden.gateway.Outcome

/**
 * The tools this plugin offers an agent.
 *
 * **What is deliberately absent is the design.** There is no `warden_set_policy`,
 * no `warden_grant`, and no way through MCP to widen what the agent may do. That
 * is the flaw this plugin exists to answer: BOSS's own `manage_tools` is an
 * agent-callable tool with an `enable` operation that cannot itself be disabled, so
 * an agent that reaches it can hand back any permission the operator removed. A
 * control plane whose limits the controlled party can lift is decoration.
 *
 * What the agent gets instead is honesty in the readable direction. It can see the
 * policy it is under and why a call was refused, which is strictly better for it
 * than discovering limits by hitting them - an agent that knows shell is escalated
 * asks once rather than retrying five ways. And it can record what it was trying to
 * do, which is the only source of intent that exists.
 *
 * All three are `readOnly = true` with respect to the workspace. `warden_log_intent`
 * writes, but only into this plugin's own session note list; it can change no file,
 * run no command, and alter no permission.
 */
class WardenMcpToolProvider(
    private val runtime: WardenRuntime,
    override val providerId: String,
) : McpToolProvider {
    override fun tools(): List<McpToolDefinition> =
        listOf(
            McpToolDefinition(
                name = TOOL_GET_POLICY,
                description =
                    "Returns the operator's current policy for this workspace: which capabilities run " +
                        "freely, which are escalated to the operator for approval, and which are refused " +
                        "outright. Call this before attempting privileged work so you can ask for what you " +
                        "need instead of discovering the limits by hitting them.",
                readOnly = true,
                handler = McpToolHandler { describePolicy() },
            ),
            McpToolDefinition(
                name = TOOL_LOG_INTENT,
                description =
                    "Record what you are about to do and why, in one line. This is the only record of " +
                        "your reasoning that survives the session, and it is shown to the operator beside " +
                        "the tool calls you actually made. Use it when you start a task, change approach, " +
                        "or hit something unexpected. It changes nothing and grants you nothing.",
                inputSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "summary": {
                          "type": "string",
                          "description": "One line, in the past or present tense, e.g. 'Reading the tokenizer to find the parse bug'."
                        },
                        "detail": {
                          "type": "string",
                          "description": "Optional longer reasoning, one or two sentences."
                        }
                      },
                      "required": ["summary"]
                    }
                    """.trimIndent(),
                readOnly = true,
                handler = McpToolHandler { args -> logIntent(args) },
            ),
            McpToolDefinition(
                name = TOOL_SESSION_SUMMARY,
                description =
                    "Returns a summary of the current session: how many tool calls were made, how many " +
                        "were allowed, escalated, or refused, and which files the workspace observed " +
                        "changing. Useful for reporting honestly on what you did.",
                readOnly = true,
                handler = McpToolHandler { describeSession() },
            ),
        )

    private fun describePolicy(): McpToolResult {
        val profile = runtime.status.value.profile
        val text =
            buildString {
                appendLine("Policy profile: ${profile.name}")
                appendLine(profile.description)
                appendLine()
                for (capability in Capability.entries) {
                    if (capability == Capability.UNKNOWN) continue
                    val verdict =
                        when (profile.decide(capability)) {
                            is Decision.Allow -> "runs freely"
                            is Decision.Ask -> "asks the operator each time"
                            is Decision.Deny -> "refused, and cannot be approved"
                        }
                    appendLine("- ${capability.label}: $verdict")
                }
                appendLine()
                appendLine(
                    "Tools this build does not recognise are treated as high risk and always escalated.",
                )
                appendLine(
                    "There is no tool for changing this policy. Only the operator can, from the " +
                        "Agent Warden panel. If you need a capability, say so in your reply and " +
                        "explain why; do not attempt to work around the limit.",
                )
            }
        return McpToolResult(text)
    }

    private fun logIntent(args: McpToolArgs): McpToolResult {
        val summary = args.string("summary")?.trim().orEmpty()
        if (summary.isEmpty()) {
            // An empty note is worse than none: it fills the operator's timeline with
            // blank rows and makes a diligent agent indistinguishable from a broken one.
            return McpToolResult("'summary' is required and must not be empty.", isError = true)
        }
        if (!runtime.recorder.isRecording) {
            return McpToolResult(
                "No session is open, so there is nothing to record against. This is not your fault.",
                isError = true,
            )
        }
        runtime.logIntent(summary.take(MAX_SUMMARY), args.string("detail")?.trim()?.take(MAX_DETAIL))
        return McpToolResult("Recorded.")
    }

    private fun describeSession(): McpToolResult {
        val s = runtime.recorder.state.value
        if (!runtime.recorder.isRecording && s.records.isEmpty()) {
            return McpToolResult("No session is open.")
        }
        val text =
            buildString {
                appendLine("Session: ${s.label}")
                appendLine("Profile: ${s.profileName}")
                appendLine("Tool calls: ${s.records.size}")
                for (outcome in Outcome.entries) {
                    val n = s.countOf(outcome)
                    if (n > 0) appendLine("  ${outcome.name.lowercase()}: $n")
                }
                appendLine("Files the workspace observed changing: ${s.fileTouches.size}")
                if (s.fileTouches.isNotEmpty()) {
                    s.fileTouches.map { it.path }.distinct().take(MAX_LISTED_FILES).forEach { appendLine("  $it") }
                }
                appendLine("Notes you have recorded: ${s.notes.size}")
                if (s.stoppedCount > 0) {
                    appendLine()
                    appendLine(
                        "${s.stoppedCount} of your calls did not reach BOSS. Report that honestly " +
                            "rather than describing the work as complete.",
                    )
                }
            }
        return McpToolResult(text)
    }

    companion object {
        const val TOOL_GET_POLICY = "warden_get_policy"
        const val TOOL_LOG_INTENT = "warden_log_intent"
        const val TOOL_SESSION_SUMMARY = "warden_session_summary"

        /** Names this provider must never grow, restated as a test in WardenMcpToolsTest. */
        val FORBIDDEN_TOOL_SUBSTRINGS = listOf("set_policy", "grant", "enable", "disable", "allow", "revoke")

        const val MAX_SUMMARY = 300
        const val MAX_DETAIL = 1000
        const val MAX_LISTED_FILES = 20
    }
}
