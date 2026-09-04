package ai.rever.boss.plugin.dynamic.warden.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What happened to a call, as recorded. Distinct from [Decision], which is what was decided. */
@Serializable
enum class Outcome {
    /** Allowed by profile, no person involved. */
    ALLOWED,

    /** Escalated and the operator said yes. */
    APPROVED,

    /** Escalated and the operator said no. */
    REFUSED,

    /** Refused by profile without asking. */
    BLOCKED,

    /** Allowed because a live [Grant] covered it. */
    GRANTED,

    /** Forwarded, but upstream reported a tool error. Recorded because a blocked
     *  call and a failing one look identical to the agent and must not to the operator. */
    FAILED,
}

/**
 * One tool call as it passed through the gateway.
 *
 * [argumentsPreview] is redacted, never raw. The gateway sits in front of tools
 * that take a shell script, a JavaScript body and a terminal buffer, so arguments
 * routinely carry credentials the operator has not chosen to show anyone. The
 * ledger is written to plugin storage and rendered in a panel that gets
 * screenshotted, so raw capture would turn an audit feature into a way of leaking
 * the thing being audited.
 */
@Serializable
data class InvocationRecord(
    val id: Long,
    val atMillis: Long,
    val toolName: String,
    val capability: Capability,
    val outcome: Outcome,
    val argumentsPreview: String,
    val detail: String? = null,
    val durationMillis: Long? = null,
)

/**
 * Redaction for anything that reaches the ledger or the approval dialog.
 *
 * Deliberately crude, and it errs towards hiding. It is not a secret scanner and
 * cannot be: a shell script's argument list is not structured, so there is no
 * reliable way to tell a token from a filename. What it does guarantee is that no
 * single value is reproduced at a length useful for reuse, and that values under
 * an obviously sensitive key are never shown at all.
 *
 * The operator can always see the full command in the terminal it runs in. This
 * is the audit copy, not the source of truth, so losing fidelity here costs
 * nothing that matters.
 */
object Redactor {
    private val sensitiveKey =
        Regex("(?i)(pass|pwd|secret|token|key|auth|credential|cookie|session|bearer)")

    /** Long unbroken runs of key-ish characters, which is what most credentials look like. */
    private val secretish = Regex("""[A-Za-z0-9_\-]{24,}""")

    private const val VALUE_LIMIT = 80
    private const val TOTAL_LIMIT = 220

    fun preview(arguments: JsonElement?): String {
        if (arguments !is JsonObject || arguments.isEmpty()) return "{}"
        val parts =
            arguments.entries.map { (key, value) ->
                val shown =
                    if (sensitiveKey.containsMatchIn(key)) {
                        "<redacted>"
                    } else {
                        scrub(value)
                    }
                "$key=$shown"
            }
        return parts.joinToString(", ").take(TOTAL_LIMIT).let {
            if (it.length == TOTAL_LIMIT) "$it..." else it
        }
    }

    private fun scrub(value: JsonElement): String {
        val raw =
            when (value) {
                is JsonPrimitive -> value.content
                else -> value.toString()
            }
        val masked = secretish.replace(raw) { m -> m.value.take(4) + "...<${m.value.length} chars>" }
        val flat = masked.replace(Regex("""\s+"""), " ").trim()
        return if (flat.length > VALUE_LIMIT) flat.take(VALUE_LIMIT) + "..." else flat
    }
}

/**
 * The ledger's in-memory shape.
 *
 * Bounded at [CAPACITY]. An agent can emit hundreds of calls a minute, and an
 * unbounded list behind a `StateFlow` that a Compose panel recomposes on is a
 * memory leak with a UI attached. Oldest entries are dropped; the exported report
 * is the durable artefact, not this.
 */
@Serializable
data class LedgerState(
    val records: List<InvocationRecord> = emptyList(),
    val nextId: Long = 1,
) {
    fun record(entry: InvocationRecord): LedgerState =
        copy(
            records = (records + entry).takeLast(CAPACITY),
            nextId = maxOf(nextId, entry.id + 1),
        )

    val blockedCount: Int get() = records.count { it.outcome == Outcome.BLOCKED || it.outcome == Outcome.REFUSED }

    companion object {
        const val CAPACITY = 500
    }
}
