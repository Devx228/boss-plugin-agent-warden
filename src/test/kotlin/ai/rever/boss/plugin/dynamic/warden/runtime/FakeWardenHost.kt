package ai.rever.boss.plugin.dynamic.warden.runtime

import ai.rever.boss.plugin.dynamic.warden.gateway.ApprovalChoice
import ai.rever.boss.plugin.dynamic.warden.gateway.ApprovalRequest
import ai.rever.boss.plugin.dynamic.warden.session.GitSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.CopyOnWriteArrayList

/** Records what the runtime asked the host to do, and lets a test decide the answers. */
class FakeWardenHost(
    override var projectPath: String? = "/home/dev/project",
) : WardenHost {
    val fileEvents = MutableSharedFlow<HostFileChange>(extraBufferCapacity = 64)
    val notifications = CopyOnWriteArrayList<String>()
    val errors = CopyOnWriteArrayList<String>()
    val writtenReports = CopyOnWriteArrayList<Pair<String, String>>()
    val approvalsAsked = CopyOnWriteArrayList<ApprovalRequest>()

    /** Null makes the runtime take the "host has no event bus" path. */
    var emitFileChanges: Boolean = true

    var git: GitSnapshot? = GitSnapshot(branch = "main", headBefore = "aaa1111")
    var toolNames: List<String> = listOf("list_tabs", "run_command", "some_plugin_tool")
    var approvalAnswer: ApprovalChoice = ApprovalChoice.ONCE
    var failReportWrite: Boolean = false
    var failToolListing: Boolean = false

    override fun fileChanges(): Flow<HostFileChange>? = if (emitFileChanges) fileEvents else null

    override suspend fun gitSnapshot(previous: GitSnapshot?): GitSnapshot? =
        git?.copy(headBefore = previous?.headBefore ?: git?.headBefore)

    override suspend fun writeReport(fileName: String, content: String): String? {
        if (failReportWrite) return null
        writtenReports.add(fileName to content)
        return "/home/dev/project/$fileName"
    }

    override fun notify(title: String, message: String, isError: Boolean) {
        (if (isError) errors else notifications).add(message)
    }

    override suspend fun askApproval(request: ApprovalRequest): ApprovalChoice {
        approvalsAsked.add(request)
        return approvalAnswer
    }

    /**
     * Emits when a test calls [disablePlugin], standing in for the host unregistering
     * this plugin's tools. Null when [exposesRegistry] is false, which is the host
     * that offers no registry at all.
     */
    var exposesRegistry: Boolean = true
    private val unregistered = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    override fun unregistered(): Flow<Unit>? = if (exposesRegistry) unregistered else null

    /** Simulates the operator switching the plugin off in the Toolbox. */
    suspend fun disablePlugin() = unregistered.emit(Unit)

    override suspend fun upstreamToolNames(): List<String> {
        if (failToolListing) throw IllegalStateException("upstream unreachable")
        return toolNames
    }
}

/** In-memory settings, with hooks for the failure paths the runtime must survive. */
class FakeSettingsStore(
    var current: WardenSettings = WardenSettings(preferredPort = 0, gatewayEnabled = false),
) : WardenRuntime.SettingsStore {
    var failLoad: Boolean = false
    var failSave: Boolean = false
    var saveCount: Int = 0

    override suspend fun load(): WardenSettings {
        if (failLoad) throw IllegalStateException("storage unavailable")
        return current
    }

    override suspend fun save(settings: WardenSettings) {
        saveCount++
        if (failSave) throw IllegalStateException("storage unavailable")
        current = settings
    }
}
