package ai.rever.boss.plugin.dynamic.projectstudio

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.launch

/**
 * The live Project Studio panel. Shows the five stages of the open project as
 * a checklist; clicking a stage's status dot cycles it, clicking the row
 * expands notes/sources and a quick "add note" field. All edits go through
 * [ProjectStudioStateStore.update], so they persist and stay visible to the
 * MCP tools in [ProjectStudioMcpToolProvider].
 */
class ProjectStudioComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val store: ProjectStudioStateStore,
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        BossTheme {
            val state by store.current.collectAsState()
            val scope = rememberCoroutineScope()
            var expandedStage by remember { mutableStateOf<StageId?>(null) }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(BossThemeColors.BackgroundColor)
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                Text("Project Studio", color = BossThemeColors.TextPrimary, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        if (state.projectPath == ProjectStudioStateStore.NO_PROJECT) {
                            "No project open"
                        } else {
                            state.projectPath
                        },
                    color = BossThemeColors.TextMuted,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(12.dp))

                StageId.entries.forEach { stageId ->
                    StageRow(
                        stageId = stageId,
                        stageState = state.stage(stageId),
                        expanded = expandedStage == stageId,
                        onToggleExpanded = { expandedStage = if (expandedStage == stageId) null else stageId },
                        onCycleStatus = {
                            scope.launch {
                                store.update { s -> s.withStage(stageId) { it.copy(status = it.status.next()) } }
                            }
                        },
                        onAddNote = { text ->
                            scope.launch {
                                store.update { s ->
                                    s.withStage(stageId) {
                                        it.copy(notes = it.notes + NoteEntry(text, System.currentTimeMillis()))
                                    }
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun StageRow(
    stageId: StageId,
    stageState: StageState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCycleStatus: () -> Unit,
    onAddNote: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(BossThemeColors.SurfaceColor)
                .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(6.dp))
                .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(statusColor(stageState.status))
                        .clickable(onClick = onCycleStatus),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stageId.displayName,
                color = BossThemeColors.TextPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(statusLabel(stageState.status), color = BossThemeColors.TextMuted, fontSize = 11.sp)
        }

        if (stageState.summary != null) {
            Spacer(Modifier.height(4.dp))
            Text(stageState.summary, color = BossThemeColors.TextSecondary, fontSize = 11.sp)
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "${stageState.notes.size} note${if (stageState.notes.size == 1) "" else "s"} · " +
                "${stageState.sources.size} source${if (stageState.sources.size == 1) "" else "s"}",
            color = BossThemeColors.TextMuted,
            fontSize = 10.sp,
        )

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            stageState.sources.forEach { source ->
                Text(
                    "• ${source.title}${source.url?.let { " ($it)" } ?: ""}",
                    color = BossThemeColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
            stageState.notes.forEach { note ->
                Text("- ${note.text}", color = BossThemeColors.TextSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            AddNoteField(onAddNote)
        }
    }
}

@Composable
private fun AddNoteField(onAddNote: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier =
                Modifier
                    .weight(1f)
                    .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                    .padding(6.dp),
            textStyle = TextStyle(color = BossThemeColors.TextPrimary, fontSize = 11.sp),
            cursorBrush = SolidColor(BossThemeColors.TextPrimary),
        )
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = {
            if (text.isNotBlank()) {
                onAddNote(text.trim())
                text = ""
            }
        }) {
            Text("Add", fontSize = 11.sp)
        }
    }
}

private fun statusColor(status: StageStatus) =
    when (status) {
        StageStatus.NOT_STARTED -> BossThemeColors.TextMuted
        StageStatus.IN_PROGRESS -> BossThemeColors.WarningColor
        StageStatus.DONE -> BossThemeColors.SuccessColor
    }

private fun statusLabel(status: StageStatus) =
    when (status) {
        StageStatus.NOT_STARTED -> "Not started"
        StageStatus.IN_PROGRESS -> "In progress"
        StageStatus.DONE -> "Done"
    }
