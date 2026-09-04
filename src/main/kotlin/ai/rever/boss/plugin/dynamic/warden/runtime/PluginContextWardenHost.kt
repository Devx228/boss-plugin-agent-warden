package ai.rever.boss.plugin.dynamic.warden.runtime

import ai.rever.boss.plugin.api.DialogChoice
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.dynamic.warden.gateway.ApprovalChoice
import ai.rever.boss.plugin.dynamic.warden.gateway.ApprovalRequest
import ai.rever.boss.plugin.dynamic.warden.session.GitFileDelta
import ai.rever.boss.plugin.dynamic.warden.session.GitSnapshot
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = BossLogger.forComponent("WardenHost")
private val hostJson = Json { ignoreUnknownKeys = true }

/**
 * The real [WardenHost], over BOSS's `PluginContext`.
 *
 * Every provider on `PluginContext` is nullable, and several are genuinely absent
 * in normal use: a window with no project open has no git provider, a headless or
 * partially initialised host may have no dialog provider. So this class is written
 * so that **nothing it does can throw into the runtime**. Each method degrades to
 * null, an empty list, or a refusal.
 *
 * The one deliberate exception is [askApproval] when there is no dialog provider.
 * It throws, because [ai.rever.boss.plugin.dynamic.warden.gateway.ApprovalCoordinator]
 * treats a failed prompt as a refusal - so a host that cannot ask fails closed
 * rather than silently permitting everything it was unable to put to anybody.
 */
class PluginContextWardenHost(
    private val context: PluginContext,
    private val settings: () -> WardenSettings,
) : WardenHost {
    private val http: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    /**
     * Null when no project is open.
     *
     * `PluginContext.projectPath` answers `""` rather than `null` in that case, so
     * the blank check is what makes the type's nullability mean what callers assume.
     */
    override val projectPath: String?
        get() = runCatching { context.projectPath?.takeIf { it.isNotBlank() } }.getOrNull()

    override fun fileChanges(): Flow<HostFileChange>? =
        runCatching {
            context.applicationEventBus?.fileChanges()?.map { event ->
                HostFileChange(event.filePath, event.changeType.name)
            }
        }.getOrNull()

    /**
     * Reads branch and HEAD, plus per-file diff stats for the working tree.
     *
     * [previous] carries the starting HEAD forward. The two reads are minutes to
     * hours apart and the second can fail; losing the first would silently discard
     * the only evidence that a commit happened.
     */
    override suspend fun gitSnapshot(previous: GitSnapshot?): GitSnapshot? {
        val git = runCatching { context.gitDataProvider }.getOrNull() ?: return previous
        return runCatching {
            if (git.isGitRepository.value != true) return previous

            git.refreshLog(1)
            val head = git.commitLog.value.firstOrNull()?.shortHash
            val branch =
                git.branches().firstOrNull { it.isCurrentBranchOrNull() }?.nameOrNull()
                    ?: previous?.branch

            val deltas =
                git.diffNames(false).mapNotNull { status ->
                    val diffs = runCatching { git.diffFile(status.path, false) }.getOrNull().orEmpty()
                    diffs.firstOrNull()?.let { GitFileDelta(it.path, it.additions, it.deletions) }
                }

            GitSnapshot(
                branch = branch,
                headBefore = previous?.headBefore ?: head,
                headAfter = head,
                deltas = deltas.ifEmpty { previous?.deltas.orEmpty() },
            )
        }.getOrElse {
            logger.warn(LogCategory.SYSTEM, "Could not read git state", emptyMap(), it)
            previous
        }
    }

    /**
     * Writes the report into the project, then opens it.
     *
     * Reports go next to the work they describe rather than into a plugin data
     * directory, because the point of the document is to be handed to somebody, and
     * a file the operator cannot find has not been produced.
     */
    override suspend fun writeReport(fileName: String, content: String): String? {
        val fs = runCatching { context.fileSystemDataProvider }.getOrNull() ?: return null
        val directory =
            ReportLocation.resolveBaseDirectory(
                projectPath = projectPath,
                homeDirectory = runCatching { fs.getHomeDirectory() }.getOrNull(),
            ) ?: return null
        val target = ReportLocation.reportPath(directory, fileName)
        return runCatching {
            fs.createFolder(directory, ReportLocation.DIRECTORY_NAME)
            fs.writeFile(target, content).getOrThrow()
            // Opening is a courtesy, not the job. A failure here must not report the
            // write as failed when the file is sitting on disk.
            runCatching { fs.openFile(target, fileName) }
            target
        }.getOrElse {
            logger.error(LogCategory.FILE, "Could not write session report", mapOf("path" to target), it)
            null
        }
    }

    override fun notify(title: String, message: String, isError: Boolean) {
        runCatching {
            val notifications = context.notificationProvider ?: return
            if (isError) notifications.showError(title, message) else notifications.showInfo(title, message)
        }
    }

    /**
     * Puts one call to the operator as a three-way choice.
     *
     * The wording leads with consequence rather than category, because "Execute
     * commands" means nothing at the moment somebody is deciding, and the argument
     * preview is what actually tells them whether this call is the one they expected.
     */
    override suspend fun askApproval(request: ApprovalRequest): ApprovalChoice {
        val dialogs =
            context.genericDialogProvider
                ?: error("no dialog provider, so nothing can be approved") // fails closed
        val minutes = request.grantDurationMillis / 60_000
        val choice =
            dialogs.showChoiceDialog(
                "Agent wants to ${request.capability.label.lowercase()}",
                buildString {
                    appendLine("Tool: ${request.toolName}")
                    appendLine("Arguments: ${request.argumentsPreview}")
                    appendLine()
                    append(request.capability.consequence)
                },
                listOf(
                    DialogChoice(CHOICE_ONCE, "Allow once", "Permit this one call. The next will ask again."),
                    DialogChoice(
                        CHOICE_WHILE,
                        "Allow for $minutes minutes",
                        "Stop asking about ${request.capability.label.lowercase()} until the timer runs out.",
                    ),
                    DialogChoice(CHOICE_DENY, "Refuse", "The agent is told the call was refused."),
                ),
                DEFAULT_CHOICE_INDEX,
            )
        return when (choice?.id) {
            CHOICE_ONCE -> ApprovalChoice.ONCE
            CHOICE_WHILE -> ApprovalChoice.FOR_A_WHILE
            // A dismissed dialog is not consent. Anything that is not an explicit
            // allow, including closing the window, refuses.
            else -> ApprovalChoice.DENY
        }
    }

    /**
     * Asks the upstream endpoint what it advertises, purely to report catalog
     * coverage. Empty on any failure; this is diagnostic and must never be able to
     * take a start down with it.
     */
    override suspend fun upstreamToolNames(): List<String> =
        runCatching {
            val endpoint = URI.create(settings().upstreamUrl)
            val session = initialiseUpstream(endpoint) ?: return emptyList()
            val body =
                post(
                    endpoint,
                    session,
                    """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
                )
            val tools =
                (hostJson.parseToJsonElement(body).jsonObjectOrNull()?.get("result") as? JsonObject)
                    ?.get("tools") as? JsonArray ?: return emptyList()
            tools.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content }
        }.getOrElse { emptyList() }

    private fun initialiseUpstream(endpoint: URI): String? {
        val request =
            HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("content-type", "application/json")
                .header("accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05",""" +
                            """"capabilities":{},"clientInfo":{"name":"agent-warden","version":"1"}}}""",
                    ),
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.headers().firstValue("mcp-session-id").orElse(null)
    }

    private fun post(endpoint: URI, sessionId: String, body: String): String {
        val request =
            HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("content-type", "application/json")
                .header("accept", "application/json, text/event-stream")
                .header("mcp-session-id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }

    private companion object {
        const val CHOICE_ONCE = "once"
        const val CHOICE_WHILE = "while"
        const val CHOICE_DENY = "deny"

        /**
         * Refuse is pre-selected. If a dialog implementation ever confirms a default
         * on Enter, the reflex keystroke must not be the one that grants shell access.
         */
        const val DEFAULT_CHOICE_INDEX = 2
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

/**
 * `GitBranchRefData`'s member names are not pinned by this plugin's compile
 * against the API jar in a way that survives a rename, so both are read
 * reflectively and answer null rather than failing the whole snapshot. Branch is
 * cosmetic in the report; HEAD is what carries the evidence.
 */
private fun Any.isCurrentBranchOrNull(): Boolean =
    runCatching {
        javaClass.methods.firstOrNull { it.name == "isCurrent" || it.name == "getCurrent" }
            ?.invoke(this) as? Boolean
    }.getOrNull() ?: false

private fun Any.nameOrNull(): String? =
    runCatching { javaClass.methods.firstOrNull { it.name == "getName" }?.invoke(this) as? String }.getOrNull()
