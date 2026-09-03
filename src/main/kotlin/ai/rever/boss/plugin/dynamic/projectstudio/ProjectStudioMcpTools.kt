package ai.rever.boss.plugin.dynamic.projectstudio

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { prettyPrint = true; encodeDefaults = true }

/**
 * MCP tools an agent running in a BOSS terminal can call to read and update
 * the currently open project's Project Studio state, without going through
 * the panel UI at all.
 */
class ProjectStudioMcpToolProvider(
    private val store: ProjectStudioStateStore,
) : McpToolProvider {
    override val providerId: String = PROJECT_STUDIO_PLUGIN_ID

    override fun tools(): List<McpToolDefinition> =
        listOf(
            McpToolDefinition(
                name = "project_studio_get_state",
                description =
                    "Returns the current project's Project Studio state as JSON: each of the five " +
                        "stages (research, plan, code, review, final) with its status, summary, notes, " +
                        "and sources.",
                handler = McpToolHandler { getState() },
            ),
            McpToolDefinition(
                name = "project_studio_set_milestone",
                description =
                    "Sets a Project Studio stage's status and optional summary. " +
                        "'stage' must be one of research, plan, code, review, final. " +
                        "'status' must be one of not_started, in_progress, done.",
                inputSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "stage": {"type": "string", "enum": ["research", "plan", "code", "review", "final"]},
                        "status": {"type": "string", "enum": ["not_started", "in_progress", "done"]},
                        "summary": {"type": "string"}
                      },
                      "required": ["stage", "status"]
                    }
                    """.trimIndent(),
                readOnly = false,
                handler = McpToolHandler { args -> setMilestone(args) },
            ),
            McpToolDefinition(
                name = "project_studio_add_source",
                description = "Adds a research source (title, optional url, optional note) to a stage.",
                inputSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "stage": {"type": "string", "enum": ["research", "plan", "code", "review", "final"]},
                        "title": {"type": "string"},
                        "url": {"type": "string"},
                        "note": {"type": "string"}
                      },
                      "required": ["title"]
                    }
                    """.trimIndent(),
                readOnly = false,
                handler = McpToolHandler { args -> addSource(args) },
            ),
            McpToolDefinition(
                name = "project_studio_add_note",
                description = "Adds a free-form note to a stage.",
                inputSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "stage": {"type": "string", "enum": ["research", "plan", "code", "review", "final"]},
                        "text": {"type": "string"}
                      },
                      "required": ["text"]
                    }
                    """.trimIndent(),
                readOnly = false,
                handler = McpToolHandler { args -> addNote(args) },
            ),
        )

    private suspend fun getState(): McpToolResult {
        store.reload()
        return McpToolResult(json.encodeToString(store.current.value))
    }

    private fun parseStage(args: McpToolArgs): StageId? =
        when (args.string("stage")?.lowercase()) {
            "research" -> StageId.RESEARCH
            "plan" -> StageId.PLAN
            "code" -> StageId.CODE
            "review" -> StageId.REVIEW
            "final" -> StageId.FINAL
            null -> StageId.RESEARCH // sensible default: most tool calls happen early in a project
            else -> null
        }

    private fun parseStatus(args: McpToolArgs): StageStatus? =
        when (args.string("status")?.lowercase()) {
            "not_started" -> StageStatus.NOT_STARTED
            "in_progress" -> StageStatus.IN_PROGRESS
            "done" -> StageStatus.DONE
            else -> null
        }

    private suspend fun setMilestone(args: McpToolArgs): McpToolResult {
        val stage = parseStage(args) ?: return McpToolResult("Unknown or missing 'stage'.", isError = true)
        val status = parseStatus(args) ?: return McpToolResult("Unknown or missing 'status'.", isError = true)
        val summary = args.string("summary")
        val next =
            store.update { state ->
                state.withStage(stage) { it.copy(status = status, summary = summary ?: it.summary) }
            }
        return McpToolResult(json.encodeToString(next.stage(stage)))
    }

    private suspend fun addSource(args: McpToolArgs): McpToolResult {
        val stage = parseStage(args) ?: return McpToolResult("Unknown 'stage'.", isError = true)
        val title = args.string("title") ?: return McpToolResult("'title' is required.", isError = true)
        val entry =
            SourceEntry(
                title = title,
                url = args.string("url"),
                note = args.string("note"),
                addedAtEpochMs = System.currentTimeMillis(),
            )
        val next =
            store.update { state ->
                state.withStage(stage) { it.copy(sources = it.sources + entry) }
            }
        return McpToolResult(json.encodeToString(next.stage(stage)))
    }

    private suspend fun addNote(args: McpToolArgs): McpToolResult {
        val stage = parseStage(args) ?: return McpToolResult("Unknown 'stage'.", isError = true)
        val text = args.string("text") ?: return McpToolResult("'text' is required.", isError = true)
        val entry = NoteEntry(text = text, addedAtEpochMs = System.currentTimeMillis())
        val next =
            store.update { state ->
                state.withStage(stage) { it.copy(notes = it.notes + entry) }
            }
        return McpToolResult(json.encodeToString(next.stage(stage)))
    }
}
