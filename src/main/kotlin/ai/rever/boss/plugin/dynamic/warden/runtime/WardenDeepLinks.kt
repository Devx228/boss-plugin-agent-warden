package ai.rever.boss.plugin.dynamic.warden.runtime

import ai.rever.boss.plugin.api.DeepLinkActionHandler
import ai.rever.boss.plugin.dynamic.warden.gateway.Profile
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory

private val logger = BossLogger.forComponent("WardenDeepLinks")

/**
 * Makes the panel's actions reachable from `boss://plugin?id=agent-warden&action=...`,
 * and so from the `boss` CLI and BOSS's own `cli` MCP tool.
 *
 * Two reasons this exists rather than leaving the buttons as the only way in.
 *
 * An audit trail that can only be exported by a person clicking is not usable in
 * anything automated: nobody can end a session and file its report from a script,
 * a git hook, or the end of a CI job, which is exactly where a record of what an
 * agent did wants to be collected.
 *
 * And a button is not testable. Every other path through this plugin can be driven
 * without a running BOSS; export was the one thing that could only be reached by
 * clicking, which meant in practice it was the one thing not verified against the
 * real host.
 *
 * **What is deliberately not here is a way to widen policy.** [ACTION_PROFILE]
 * accepts a profile id, and every shipped profile is a narrowing or a widening the
 * *operator* chose - but a deep link is not the operator. `boss://` is registered
 * with the OS, so any program that can ask the OS to open a URL produces the same
 * input, and BOSS's own notes say as much. So profile changes through this handler
 * are refused, and the action exists only to be refused loudly rather than to fail
 * as an unknown verb somebody then files a bug about.
 */
class WardenDeepLinkHandler(
    private val runtime: WardenRuntime,
    override val handlerId: String,
) : DeepLinkActionHandler {
    override fun handle(action: String, params: Map<String, String>): Boolean {
        logger.info(LogCategory.SYSTEM, "Deep link action", mapOf("action" to action))
        return when (action.lowercase()) {
            ACTION_EXPORT -> {
                runtime.exportSnapshot()
                true
            }

            ACTION_END_SESSION -> {
                runtime.endSessionAndExport()
                true
            }

            ACTION_START_SESSION -> {
                runtime.beginSession(params["label"]?.takeIf { it.isNotBlank() } ?: DEFAULT_LABEL)
                true
            }

            ACTION_START -> {
                runtime.start()
                true
            }

            ACTION_STOP -> {
                runtime.stop()
                true
            }

            ACTION_REVOKE -> {
                runtime.revokeGrants()
                true
            }

            ACTION_PROFILE -> {
                // Refused, on purpose. See the class KDoc: a boss:// link is not proof
                // that the operator asked for anything.
                val requested = Profile.byId(params["profile"]).name
                runtime.notifyPolicyChangeRefused(requested)
                logger.warn(
                    LogCategory.SYSTEM,
                    "Refused a policy change requested over a deep link",
                    mapOf("requested" to requested),
                )
                false
            }

            else -> false
        }
    }

    companion object {
        const val ACTION_EXPORT = "export"
        const val ACTION_END_SESSION = "end-session"
        const val ACTION_START_SESSION = "start-session"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_REVOKE = "revoke-grants"

        /** Recognised so it can be refused with an explanation rather than ignored. */
        const val ACTION_PROFILE = "set-profile"

        const val DEFAULT_LABEL = "Agent session"

        /** Everything this handler answers, for the test that pins the list. */
        val SUPPORTED =
            setOf(
                ACTION_EXPORT,
                ACTION_END_SESSION,
                ACTION_START_SESSION,
                ACTION_START,
                ACTION_STOP,
                ACTION_REVOKE,
                ACTION_PROFILE,
            )

        /** Actions that must never be honoured from a link, whoever sends it. */
        val REFUSED = setOf(ACTION_PROFILE)
    }
}
