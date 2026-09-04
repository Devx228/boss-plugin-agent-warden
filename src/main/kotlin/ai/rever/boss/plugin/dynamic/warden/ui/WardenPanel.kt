package ai.rever.boss.plugin.dynamic.warden.ui

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.dynamic.warden.gateway.Capability
import ai.rever.boss.plugin.dynamic.warden.gateway.Outcome
import ai.rever.boss.plugin.dynamic.warden.gateway.Profile
import ai.rever.boss.plugin.dynamic.warden.runtime.WardenRuntime
import ai.rever.boss.plugin.dynamic.warden.session.SessionSnapshot
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * The operator's view of what the agent is doing and what it is allowed to do.
 *
 * Laid out so the two questions that matter are answerable without scrolling: what
 * is the policy right now, and what has been stopped. The full call list is below
 * them, because it is the thing you read after something surprises you rather than
 * the thing you watch.
 */
class WardenPanelComponent(
    componentContext: ComponentContext,
    override val panelInfo: PanelInfo,
    private val runtime: WardenRuntime,
) : PanelComponentWithUI, ComponentContext by componentContext {
    @Composable
    override fun Content() {
        val status by runtime.status.collectAsState()
        val session by runtime.recorder.state.collectAsState()

        BossTheme {
            Column(
                Modifier.fillMaxSize().background(BossThemeColors.BackgroundColor).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusHeader(status.running, status.port, status.lastError)
                ProfileSelector(status.profile) { runtime.setProfile(it) }
                GrantsRow()
                SessionBar(session)
                Ledger(session)
            }
        }
    }

    // ---- header --------------------------------------------------------------

    @Composable
    private fun StatusHeader(running: Boolean, port: Int?, error: String?) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (running) BossThemeColors.SuccessColor else BossThemeColors.TextMuted)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Label(if (running) "Gateway running" else "Gateway stopped", bold = true)
                    // The endpoint is the one piece of state the operator has to copy
                    // somewhere else, so it is shown in full rather than summarised.
                    Label(
                        text = port?.let { "http://127.0.0.1:$it/mcp" } ?: "not listening",
                        color = BossThemeColors.TextSecondary,
                        mono = true,
                    )
                }
                TextAction(if (running) "Stop" else "Start") {
                    if (running) runtime.stop() else runtime.start()
                }
            }
            error?.let {
                Spacer(Modifier.height(6.dp))
                Label("Could not start: $it", color = BossThemeColors.ErrorColor)
            }
            Spacer(Modifier.height(6.dp))
            Label(
                "Point your agent at this endpoint instead of BOSS's own to have its calls pass through here.",
                color = BossThemeColors.TextMuted,
                size = 10,
            )
        }
    }

    // ---- policy --------------------------------------------------------------

    @Composable
    private fun ProfileSelector(active: Profile, onSelect: (Profile) -> Unit) {
        Card {
            Label("Policy", bold = true)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (profile in Profile.ALL) {
                    val selected = profile.id == active.id
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (selected) BossThemeColors.AccentColor else BossThemeColors.SurfaceColor)
                            .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(4.dp))
                            .clickable { onSelect(profile) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Label(
                            profile.name,
                            color = if (selected) Color.White else BossThemeColors.TextSecondary,
                            bold = selected,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Label(active.description, color = BossThemeColors.TextMuted, size = 10)
            Spacer(Modifier.height(4.dp))
            // Naming the refusal explicitly is the point of the whole plugin, so it is
            // stated in the UI and not left to the README.
            Label(
                "Changing the tool surface is always refused, and cannot be approved.",
                color = BossThemeColors.TextMuted,
                size = 10,
            )
        }
    }

    @Composable
    private fun GrantsRow() {
        val remaining = runtime.grants.remaining()
        if (remaining.isEmpty()) return
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Temporary permissions", bold = true, color = BossThemeColors.WarningColor)
                    for ((capability, millis) in remaining) {
                        Label(
                            "${capability.label} for ${millis / 60_000}m ${(millis / 1000) % 60}s more",
                            color = BossThemeColors.TextSecondary,
                        )
                    }
                }
                TextAction("Revoke all") { runtime.revokeGrants() }
            }
        }
    }

    // ---- session -------------------------------------------------------------

    @Composable
    private fun SessionBar(session: SessionSnapshot) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(session.label, bold = true)
                    Label(
                        "${session.records.size} calls · ${session.stoppedCount} stopped · " +
                            "${session.fileTouches.size} file changes · ${session.notes.size} agent notes",
                        color = BossThemeColors.TextSecondary,
                        size = 10,
                    )
                }
                TextAction("Export") { runtime.exportSnapshot() }
                Spacer(Modifier.width(8.dp))
                TextAction("End + report") { runtime.endSessionAndExport() }
            }
            if (session.unclassifiedTools.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Label(
                    "${session.unclassifiedTools.size} unrecognised tool(s) are being treated as high risk.",
                    color = BossThemeColors.WarningColor,
                    size = 10,
                )
            }
        }
    }

    // ---- ledger --------------------------------------------------------------

    @Composable
    private fun ColumnScope.Ledger(session: SessionSnapshot) {
        Card(Modifier.weight(1f)) {
            Label("Calls", bold = true)
            Spacer(Modifier.height(6.dp))
            if (session.records.isEmpty()) {
                Label(
                    "Nothing yet. Calls appear here as the agent makes them.",
                    color = BossThemeColors.TextMuted,
                )
                return@Card
            }
            // Newest first: the operator is watching for what just happened, and a list
            // that grows downwards makes them chase it.
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(session.records.reversed(), key = { it.id }) { record ->
                    Row(verticalAlignment = Alignment.Top) {
                        Dot(outcomeColor(record.outcome), size = 6)
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Row {
                                Label(record.toolName, bold = true, mono = true)
                                Spacer(Modifier.width(6.dp))
                                Label(
                                    record.outcome.name.lowercase(),
                                    color = outcomeColor(record.outcome),
                                    size = 10,
                                )
                                Spacer(Modifier.width(6.dp))
                                Label(
                                    clockFormat.format(Instant.ofEpochMilli(record.atMillis)),
                                    color = BossThemeColors.TextMuted,
                                    size = 10,
                                )
                            }
                            Label(record.argumentsPreview, color = BossThemeColors.TextSecondary, size = 10, mono = true)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun outcomeColor(outcome: Outcome): Color {
        return when (outcome) {
            // Blocked and refused share a colour because to the operator they are one
            // fact - this did not happen - and differ only in who decided.
            Outcome.BLOCKED, Outcome.REFUSED -> BossThemeColors.ErrorColor
            Outcome.APPROVED, Outcome.GRANTED -> BossThemeColors.WarningColor
            Outcome.FAILED -> BossThemeColors.SecondaryColor
            Outcome.ALLOWED -> BossThemeColors.SuccessColor
        }
    }

    // ---- small pieces --------------------------------------------------------

    @Composable
    private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(BossThemeColors.SurfaceColor)
                .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(6.dp))
                .padding(10.dp),
            content = content,
        )
    }

    @Composable
    private fun Label(
        text: String,
        color: Color = BossThemeColors.TextPrimary,
        bold: Boolean = false,
        size: Int = 11,
        mono: Boolean = false,
    ) {
        androidx.compose.material.Text(
            text = text,
            color = color,
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }

    @Composable
    private fun TextAction(text: String, onClick: () -> Unit) {
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Label(text, color = BossThemeColors.AccentColor, bold = true)
        }
    }

    @Composable
    private fun Dot(color: Color, size: Int = 8) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(color))
    }
}

/** Capabilities rendered with emphasis in the panel, kept here so the UI has one list. */
internal val HIGH_RISK_CAPABILITIES = Capability.entries.filter { it.isHighRisk }
