package ai.rever.boss.plugin.dynamic.warden.runtime

import ai.rever.boss.plugin.dynamic.warden.gateway.ApprovalChoice
import ai.rever.boss.plugin.dynamic.warden.gateway.ApprovalRequest
import ai.rever.boss.plugin.dynamic.warden.session.GitSnapshot
import kotlinx.coroutines.flow.Flow

/** A file change as the workspace reported it, flattened off the host's event type. */
data class HostFileChange(val path: String, val change: String)

/**
 * Everything [WardenRuntime] needs from BOSS, and nothing more.
 *
 * The seam exists so the orchestration can be tested. `PluginContext` is a
 * seventy-member interface whose providers are all nullable and mostly unavailable
 * outside a running app; a runtime written directly against it could only be
 * exercised by launching BOSS, which means in practice it would not be exercised
 * at all.
 *
 * Every method is written to be **survivable when the host cannot answer**.
 * Providers on `PluginContext` are nullable by design - a plugin can be loaded in
 * a window with no project, no git repository and no dialog provider - so the
 * implementation returns null or a no-op rather than throwing, and the runtime
 * degrades instead of failing to load.
 */
interface WardenHost {
    /** Null when no project is open, which is a normal state, not an error. */
    val projectPath: String?

    /**
     * Null when the host exposes no event bus. The runtime then records no file
     * evidence and the report says the section is empty rather than implying nothing
     * changed.
     */
    fun fileChanges(): Flow<HostFileChange>?

    /**
     * Reads repository state, or null when there is no git repository open.
     *
     * [previous] is passed so an end-of-session read can carry the starting HEAD
     * forward rather than losing it; the two reads are seconds to hours apart and
     * the second one can fail.
     */
    suspend fun gitSnapshot(previous: GitSnapshot? = null): GitSnapshot?

    /**
     * Writes a report and returns the path written, or null if it could not be
     * written. Implementations open it in the editor when the host can.
     */
    suspend fun writeReport(fileName: String, content: String): String?

    /** Best-effort operator-visible message. Silently does nothing when unavailable. */
    fun notify(title: String, message: String, isError: Boolean = false)

    /**
     * Puts one call to the operator.
     *
     * Throwing is a legitimate answer: [ApprovalCoordinator] treats any failure as a
     * refusal, so a host with no dialog provider fails closed rather than silently
     * permitting everything it cannot ask about.
     */
    suspend fun askApproval(request: ApprovalRequest): ApprovalChoice

    /**
     * Tool names currently advertised upstream, used to report catalog coverage.
     *
     * Empty on failure rather than throwing. Coverage reporting is diagnostic, and
     * an unreachable upstream must not be able to take the panel down with it.
     */
    suspend fun upstreamToolNames(): List<String>
}
