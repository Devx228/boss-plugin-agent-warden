package ai.rever.boss.plugin.dynamic.warden.gateway

import kotlinx.serialization.Serializable

/** What the gateway decided to do about one call, and why. */
sealed interface Decision {
    /** Forward upstream without asking. */
    data object Allow : Decision

    /** Refuse without asking. The agent sees a tool error, not a transport failure. */
    data class Deny(val reason: String) : Decision

    /** Suspend the call and put it in front of the operator. */
    data class Ask(val capability: Capability) : Decision
}

/**
 * A named least-privilege profile.
 *
 * [ceiling] is the highest capability allowed without asking. Anything above it
 * is escalated to the operator rather than refused outright, because a profile
 * that silently blocked work would train the user to switch to [FULL] and leave
 * it there, which is the failure mode this whole feature exists to avoid.
 *
 * [hardDenied] is the exception: capabilities that are refused even with a person
 * watching. [Capability.GOVERN] is here by default and the reasoning is the point
 * of the design. BOSS's own `manage_tools` is an MCP tool the agent can call, it
 * takes an `enable` operation, and it cannot itself be disabled - so an agent that
 * can reach it can hand back any permission that was taken away. A control plane
 * whose limits the controlled party can lift is decoration, so this gateway
 * exposes no tool that edits its own policy and refuses the upstream one that
 * does.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val description: String,
    val ceiling: Capability,
    val hardDenied: Set<Capability> = setOf(Capability.GOVERN),
) {
    /**
     * Never returns [Decision.Allow] for [Capability.UNKNOWN], whatever the ceiling,
     * because "unknown" is an absence of information and cannot be covered by a
     * blanket permission the operator granted about things they could see.
     */
    fun decide(capability: Capability): Decision =
        when {
            capability in hardDenied ->
                Decision.Deny(
                    "${capability.label} is refused by the '$name' profile. ${capability.consequence}",
                )
            capability == Capability.UNKNOWN -> Decision.Ask(capability)
            capability.ordinal <= ceiling.ordinal -> Decision.Allow
            else -> Decision.Ask(capability)
        }

    companion object {
        val READ_ONLY =
            Profile(
                id = "read-only",
                name = "Read only",
                description =
                    "The agent may look at the workspace and read content, but every " +
                        "command, script and change is escalated to you.",
                ceiling = Capability.READ_CONTENT,
            )

        val BUILD =
            Profile(
                id = "build",
                name = "Build",
                description =
                    "Shell commands run without prompting. Browser scripting still asks, " +
                        "because the browser is signed in to your accounts.",
                ceiling = Capability.EXECUTE,
            )

        val FULL =
            Profile(
                id = "full",
                name = "Full access",
                description =
                    "Everything runs unprompted except changes to the tool surface itself. " +
                        "Calls are still recorded.",
                ceiling = Capability.BROWSER_SCRIPT,
            )

        val ALL = listOf(READ_ONLY, BUILD, FULL)

        /** Unknown ids resolve to the safest profile rather than the last one used. */
        fun byId(id: String?): Profile = ALL.firstOrNull { it.id == id } ?: READ_ONLY
    }
}

/**
 * A capability the operator has allowed for a limited time, so that approving one
 * `run_command` does not mean approving the next forty individually.
 *
 * Grants are deliberately **not persisted**. A grant that outlived the session
 * would be a permission the operator granted for one task quietly applying to the
 * next one, which is the drift this feature exists to stop.
 */
data class Grant(val capability: Capability, val expiresAtMillis: Long) {
    fun isLive(nowMillis: Long): Boolean = nowMillis < expiresAtMillis
}
