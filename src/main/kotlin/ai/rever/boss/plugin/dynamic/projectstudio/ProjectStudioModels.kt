package ai.rever.boss.plugin.dynamic.projectstudio

import kotlinx.serialization.Serializable

/** The five stages a Project Studio project moves through. */
@Serializable
enum class StageId(val displayName: String) {
    RESEARCH("Research"),
    PLAN("Plan"),
    CODE("Code"),
    REVIEW("Review"),
    FINAL("Final"),
}

@Serializable
enum class StageStatus {
    NOT_STARTED,
    IN_PROGRESS,
    DONE,
    ;

    /** Cycles NOT_STARTED -> IN_PROGRESS -> DONE -> NOT_STARTED, for a single click/tap toggle. */
    fun next(): StageStatus =
        when (this) {
            NOT_STARTED -> IN_PROGRESS
            IN_PROGRESS -> DONE
            DONE -> NOT_STARTED
        }
}

@Serializable
data class NoteEntry(
    val text: String,
    val addedAtEpochMs: Long,
)

@Serializable
data class SourceEntry(
    val title: String,
    val url: String? = null,
    val note: String? = null,
    val addedAtEpochMs: Long,
)

@Serializable
data class StageState(
    val status: StageStatus = StageStatus.NOT_STARTED,
    val summary: String? = null,
    val notes: List<NoteEntry> = emptyList(),
    val sources: List<SourceEntry> = emptyList(),
)

/**
 * A project's full Project Studio state: one [StageState] per [StageId], keyed
 * by the BOSS project path it belongs to. Persisted as JSON — see
 * [ProjectStudioStateStore].
 */
@Serializable
data class ProjectStudioState(
    val projectPath: String,
    val stages: Map<StageId, StageState> = StageId.entries.associateWith { StageState() },
) {
    fun stage(id: StageId): StageState = stages[id] ?: StageState()

    fun withStage(
        id: StageId,
        transform: (StageState) -> StageState,
    ): ProjectStudioState = copy(stages = stages + (id to transform(stage(id))))
}
