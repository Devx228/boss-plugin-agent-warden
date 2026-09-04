package ai.rever.boss.plugin.dynamic.warden.gateway

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** What the operator chose when asked about one call. */
enum class ApprovalChoice {
    /** Allow this call only. The next one asks again. */
    ONCE,

    /** Allow this call and open a bounded [Grant] for the whole capability. */
    FOR_A_WHILE,

    /** Refuse this call. */
    DENY,
}

/** Raises one approval question and waits for an answer. Implemented over the host's dialogs. */
fun interface ApprovalPrompt {
    suspend fun ask(request: ApprovalRequest): ApprovalChoice
}

data class ApprovalRequest(
    val toolName: String,
    val capability: Capability,
    val argumentsPreview: String,
    val grantDurationMillis: Long,
)

/**
 * Turns concurrent, racing approval questions into one queue with one dialog at a
 * time, and guarantees every one of them terminates.
 *
 * Three problems, each of which broke something real in testing:
 *
 * **Concurrency.** The gateway serves calls on a thread pool, so two tools can
 * need approval at once. `GenericDialogProvider` has no queue; a second dialog
 * raised while the first is up either replaces it or is dropped, and either way an
 * operator answers one question believing they answered the other. [mutex]
 * serialises them so each is asked and answered on its own.
 *
 * **Redundancy.** While the operator reads a dialog about `run_command`, the agent
 * may fire four more. Re-checking [isAlreadyGranted] after acquiring the lock
 * means the queue drains instantly once a grant is given, instead of asking the
 * same question five times about a permission that is now live.
 *
 * **Absence.** The operator may not be at the machine. An MCP call blocks a
 * gateway worker thread while it waits, so an unanswered dialog is a leaked thread
 * and an agent hung forever. [timeoutMillis] bounds the wait and **denies on
 * expiry**, because a call nobody approved must not proceed on the strength of
 * nobody having refused it either.
 */
class ApprovalCoordinator(
    private val prompt: ApprovalPrompt,
    private val grants: GrantBook,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val grantDurationMillis: Long = GrantBook.DEFAULT_DURATION_MILLIS,
) {
    private val mutex = Mutex()

    /** True when a dialog is currently open, so the panel can say why it is waiting. */
    @Volatile
    var pending: ApprovalRequest? = null
        private set

    /**
     * Returns whether the call may proceed.
     *
     * A [Capability] already covered by a live grant returns true without asking.
     * That check happens twice, once before queueing and once after acquiring the
     * lock, and the second is the one that matters: it is what stops a backlog of
     * queued questions from surviving the answer that resolved them.
     */
    suspend fun requestApproval(
        toolName: String,
        capability: Capability,
        argumentsPreview: String,
    ): ApprovalOutcome {
        if (grants.isGranted(capability)) return ApprovalOutcome.GRANTED

        return mutex.withLock {
            if (grants.isGranted(capability)) return@withLock ApprovalOutcome.GRANTED

            val request =
                ApprovalRequest(
                    toolName = toolName,
                    capability = capability,
                    argumentsPreview = argumentsPreview,
                    grantDurationMillis = grantDurationMillis,
                )
            pending = request
            try {
                when (askWithTimeout(request)) {
                    ApprovalChoice.ONCE -> ApprovalOutcome.APPROVED
                    ApprovalChoice.FOR_A_WHILE -> {
                        grants.grant(capability, grantDurationMillis)
                        ApprovalOutcome.APPROVED
                    }
                    ApprovalChoice.DENY -> ApprovalOutcome.REFUSED
                }
            } finally {
                pending = null
            }
        }
    }

    /**
     * Any failure is a refusal.
     *
     * A dialog provider that throws, a host with none at all, or an operator who
     * walked away must all land on "no". The alternative - treating a broken prompt
     * as consent - would make the gateway most permissive exactly when it is least
     * able to supervise.
     */
    private suspend fun askWithTimeout(request: ApprovalRequest): ApprovalChoice =
        try {
            withTimeout(timeoutMillis) { prompt.ask(request) }
        } catch (e: TimeoutCancellationException) {
            ApprovalChoice.DENY
        } catch (e: Exception) {
            ApprovalChoice.DENY
        }

    companion object {
        /**
         * Bounds how long one gateway worker thread can be held. Long enough that a
         * person who stepped away for coffee still gets to answer, short enough that an
         * abandoned session frees its thread within the hour.
         */
        const val DEFAULT_TIMEOUT_MILLIS = 5L * 60L * 1000L
    }
}

/** Distinguishes the three ways a call can be permitted, because the ledger shows them differently. */
enum class ApprovalOutcome {
    /** A live grant covered it. No dialog was shown. */
    GRANTED,

    /** The operator said yes to this call. */
    APPROVED,

    /** The operator said no, or nobody answered in time. */
    REFUSED,
    ;

    val allowed: Boolean get() = this != REFUSED

    fun toLedgerOutcome(): Outcome =
        when (this) {
            GRANTED -> Outcome.GRANTED
            APPROVED -> Outcome.APPROVED
            REFUSED -> Outcome.REFUSED
        }
}
