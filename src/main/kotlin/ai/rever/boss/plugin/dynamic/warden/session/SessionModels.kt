package ai.rever.boss.plugin.dynamic.warden.session

import ai.rever.boss.plugin.dynamic.warden.gateway.InvocationRecord
import ai.rever.boss.plugin.dynamic.warden.gateway.Outcome
import kotlinx.serialization.Serializable

/**
 * A file the workspace reported changing while a session was open.
 *
 * Sourced from `ApplicationEventBus.fileChanges()`, which fires for **any** write,
 * not only an agent's. That is the point: it is the half of the record the agent
 * does not author and cannot suppress. Correlating it with the call log is what
 * separates "the agent said it edited three files" from "three files changed".
 */
@Serializable
data class FileTouch(
    val path: String,
    val change: String,
    val atMillis: Long,
)

/** Per-file diff stats from `GitDataProvider`, which is authoritative where the event bus is suggestive. */
@Serializable
data class GitFileDelta(
    val path: String,
    val additions: Int,
    val deletions: Int,
)

/**
 * Repository state either side of a session.
 *
 * `headBefore` and `headAfter` differing means the agent committed, which the
 * file-change stream cannot tell you: a commit writes nothing in the working tree.
 */
@Serializable
data class GitSnapshot(
    val branch: String? = null,
    val headBefore: String? = null,
    val headAfter: String? = null,
    val deltas: List<GitFileDelta> = emptyList(),
) {
    val committed: Boolean get() = headBefore != null && headAfter != null && headBefore != headAfter
    val totalAdditions: Int get() = deltas.sumOf { it.additions }
    val totalDeletions: Int get() = deltas.sumOf { it.deletions }
}

/**
 * Something the agent said it was doing, written through `warden_log_intent`.
 *
 * **This is the only part of a session the agent authors**, and the report labels
 * it as such. It is testimony, not evidence: an agent can write anything here, or
 * nothing. It earns its place by being the only source of *why*, which no amount
 * of observing tool calls recovers - and it sits next to the involuntary record so
 * a reader can check one against the other.
 */
@Serializable
data class AgentNote(
    val atMillis: Long,
    val summary: String,
    val detail: String? = null,
)

/**
 * Everything known about one session, in the shape the report renders from.
 *
 * Deliberately a plain serialisable value with no host types in it, so the report
 * generator is a pure function and can be tested without a running BOSS.
 */
@Serializable
data class SessionSnapshot(
    val id: String,
    val label: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val profileName: String,
    val projectPath: String? = null,
    val records: List<InvocationRecord> = emptyList(),
    val fileTouches: List<FileTouch> = emptyList(),
    val notes: List<AgentNote> = emptyList(),
    val git: GitSnapshot? = null,
    /**
     * Tools present in `tools/list` that this build could not classify. Surfaced
     * rather than hidden: it is the honest measure of how much of the surface the
     * catalog actually covers, and every one of them cost the operator a prompt.
     */
    val unclassifiedTools: List<String> = emptyList(),
) {
    val durationMillis: Long get() = (endedAtMillis ?: System.currentTimeMillis()) - startedAtMillis

    val byOutcome: Map<Outcome, Int>
        get() = records.groupingBy { it.outcome }.eachCount()

    /** Calls that did not reach BOSS, which is the number the whole feature exists to make non-zero. */
    val stoppedCount: Int
        get() = records.count { it.outcome == Outcome.BLOCKED || it.outcome == Outcome.REFUSED }

    val escalatedCount: Int
        get() = records.count { it.outcome == Outcome.APPROVED || it.outcome == Outcome.REFUSED }

    fun countOf(outcome: Outcome): Int = records.count { it.outcome == outcome }
}
