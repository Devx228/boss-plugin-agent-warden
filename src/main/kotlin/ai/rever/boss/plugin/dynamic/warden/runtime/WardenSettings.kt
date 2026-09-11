package ai.rever.boss.plugin.dynamic.warden.runtime

import ai.rever.boss.plugin.dynamic.warden.gateway.ApprovalCoordinator
import ai.rever.boss.plugin.dynamic.warden.gateway.GrantBook
import ai.rever.boss.plugin.dynamic.warden.gateway.Profile
import kotlinx.serialization.Serializable

/**
 * What the operator has chosen, and the only part of this plugin's state that
 * survives a restart.
 *
 * Grants are deliberately absent: a time-boxed permission that outlived the
 * session would be a permission given for one task quietly applying to the next.
 * See [GrantBook].
 */
@Serializable
data class WardenSettings(
    val profileId: String = Profile.READ_ONLY.id,
    val preferredPort: Int = DEFAULT_PORT,
    val gatewayEnabled: Boolean = true,
    /** Hard-denied tools are filtered from `tools/list`, so the agent never plans around them. */
    val hideDeniedTools: Boolean = true,
    val approvalTimeoutMillis: Long = ApprovalCoordinator.DEFAULT_TIMEOUT_MILLIS,
    val grantDurationMillis: Long = GrantBook.DEFAULT_DURATION_MILLIS,
    /**
     * Whether a shell command may be decided on what it actually runs.
     *
     * On by default, because the alternative measured badly in practice: every
     * `git status` an agent emits raising a dialog is what teaches an operator to
     * approve without reading. Off restores the older behaviour of escalating every
     * shell call regardless of contents, which is stricter and noisier. See
     * [ai.rever.boss.plugin.dynamic.warden.gateway.CommandRisk].
     */
    val judgeShellCommands: Boolean = true,
    /** Where BOSS's own MCP endpoint lives. Configurable because the port is a setting there too. */
    val upstreamUrl: String = DEFAULT_UPSTREAM,
) {
    val profile: Profile get() = Profile.byId(profileId)

    /**
     * Clamps anything a hand-edited settings file could make nonsensical.
     *
     * Storage is a JSON blob the operator can edit and an older build can have
     * written, so these values are not guaranteed to be anything. A zero approval
     * timeout would deny every escalated call while looking like a policy decision,
     * and a port outside the valid range would send the gateway to an ephemeral one
     * without explanation.
     */
    fun sanitised(): WardenSettings =
        copy(
            profileId = Profile.byId(profileId).id,
            // 0 is kept, not clamped: it is the OS convention for "any free port", and
            // an operator who cannot use a fixed one needs a way to say so.
            preferredPort = if (preferredPort in 0..65535) preferredPort else DEFAULT_PORT,
            approvalTimeoutMillis = approvalTimeoutMillis.coerceIn(MIN_APPROVAL_TIMEOUT, MAX_APPROVAL_TIMEOUT),
            grantDurationMillis = grantDurationMillis.coerceIn(MIN_GRANT, MAX_GRANT),
            upstreamUrl = upstreamUrl.ifBlank { DEFAULT_UPSTREAM },
        )

    companion object {
        /**
         * One above BOSS's own 7677. Not a registered port and not near one, so a
         * collision means another BOSS window rather than an unrelated service.
         */
        const val DEFAULT_PORT = 7678
        const val DEFAULT_UPSTREAM = "http://127.0.0.1:7677/mcp"

        /** Long enough to read a dialog; short enough that an unattended machine frees the thread. */
        const val MIN_APPROVAL_TIMEOUT = 5_000L
        const val MAX_APPROVAL_TIMEOUT = 60L * 60L * 1000L

        const val MIN_GRANT = 60_000L
        const val MAX_GRANT = 8L * 60L * 60L * 1000L

        const val STORAGE_KEY = "warden-settings"
    }
}
