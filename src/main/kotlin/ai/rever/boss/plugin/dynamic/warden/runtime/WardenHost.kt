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
     * Emits once when the host has unregistered this plugin's MCP tools.
     *
     * This exists because there is no disable hook. `dispose()` runs on unload only:
     * disabling a plugin unregisters its panels, tools and UI extensions and stops
     * its sandbox, and never calls into the plugin at all. For most plugins that is
     * harmless, because unregistering the panel is the whole of what they were doing.
     * This one holds a listening socket, and a socket nobody told to close stays
     * open.
     *
     * Measured, not assumed: after disabling Agent Warden from the Toolbox, port
     * 7678 was still listening and still forwarded `run_command` to BOSS, with the
     * panel gone and no way for the operator to see it. An operator who switches off
     * the supervision layer has every reason to believe it is off.
     *
     * The registry is the signal because the host genuinely does unregister the tool
     * provider on disable, and `allTools` is a `StateFlow` this plugin can watch.
     * That is a workaround for a missing hook rather than a design, and it should be
     * replaced if the host ever grows one.
     *
     * Null when the host exposes no registry, in which case the gateway keeps the
     * behaviour it had before, which is the one being fixed. Nothing here can make
     * that worse.
     */
    fun unregistered(): Flow<Unit>?

    /**
     * Tool names currently advertised upstream, used to report catalog coverage.
     *
     * Empty on failure rather than throwing. Coverage reporting is diagnostic, and
     * an unreachable upstream must not be able to take the panel down with it.
     */
    suspend fun upstreamToolNames(): List<String>
}
