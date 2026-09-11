package ai.rever.boss.plugin.dynamic.warden.ui

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.dynamic.warden.gateway.ApprovalRequest
import ai.rever.boss.plugin.dynamic.warden.gateway.Capability
import ai.rever.boss.plugin.dynamic.warden.gateway.HostGovernanceGap
import ai.rever.boss.plugin.dynamic.warden.gateway.InvocationRecord
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * Roughly how long an MCP client waits before giving up on a tool call.
 *
 * Measured, not guessed: Claude Code 2.1.268 abandons one at 120 seconds while this
 * gateway's own approval timeout is five minutes. Past this point the operator's
 * answer is still recorded and still correct, and the agent has stopped listening
 * for it, so the panel says so rather than letting them believe otherwise.
 */
private const val CLIENT_PATIENCE_SECONDS = 120

/** Wide enough for the longest outcome word, so the tool names line up down the list. */
private const val OUTCOME_COLUMN = 74

/** Below this a duration is noise. Above it, it is the reason somebody is looking. */
private const val NOTABLE_MILLIS = 1_000L

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
        val held by runtime.pendingApproval.collectAsState()
        val now = tickingClock(active = held != null || runtime.grants.remaining().isNotEmpty())

        BossTheme {
            Column(
                Modifier.fillMaxSize().background(BossThemeColors.BackgroundColor).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusHeader(status.running, status.port, status.lastError)
                // Above the policy card on purpose. It is the only thing in this panel
                // that is asking the operator for something, and a held call is the one
                // state where reading the rest first would be reading it too late.
                held?.let { HeldCall(it, now) }
                status.traceError?.let { TraceFault(it) }
                ProfileSelector(status.profile) { runtime.setProfile(it) }
                GrantsRow(tick = now)
                SessionBar(session)
                Ledger(session)
            }
        }
    }

    /**
     * A once-a-second recomposition, running only while something is counting.
     *
     * The panel had two displays of elapsed time and neither moved: [GrantsRow] read
     * `grants.remaining()`, a plain map, so a countdown rendered once and then froze
     * at whatever it said. A number that stops being true one second after it is
     * drawn is worse than no number, because it is read as current.
     *
     * Gated on [active] so an idle panel is not recomposing forever behind a tab
     * nobody is looking at.
     */
    @Composable
    private fun tickingClock(active: Boolean): Long {
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(active) {
            while (active) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
        return now
    }

    /**
     * The call currently stopped in front of the operator.
     *
     * `ApprovalCoordinator.pending` has existed since the first version, with a KDoc
     * saying it was there so the panel could explain why it was waiting. Nothing read
     * it. Live, that meant the dialog was up, a shell command was held, and the panel
     * behind it said "0 calls", which is the most misleading thing it could have said.
     *
     * The elapsed count is shown because the useful question is not that a call is
     * waiting but how long it has been, and past about two minutes the answer stops
     * mattering: the agent's own client will have given up and the verdict, whatever
     * it turns out to be, reaches nobody.
     */
    @Composable
    private fun HeldCall(request: ApprovalRequest, now: Long) {
        val heldFor = ((now - request.askedAtMillis) / 1000).coerceAtLeast(0)
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(BossThemeColors.WarningColor)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Label("Waiting on you", bold = true, color = BossThemeColors.WarningColor)
                    Label(
                        "${request.toolName} wants to ${request.capability.label.lowercase()}",
                        color = BossThemeColors.TextSecondary,
                    )
                    Label(
                        request.argumentsPreview,
                        color = BossThemeColors.TextMuted,
                        size = 10,
                        mono = true,
                        maxLines = 1,
                    )
                }
                Label("${heldFor}s", color = BossThemeColors.TextSecondary, mono = true)
            }
            if (heldFor > CLIENT_PATIENCE_SECONDS) {
                Spacer(Modifier.height(6.dp))
                Label(
                    "Most agents stop waiting after about two minutes. Your answer will still be " +
                        "recorded, but it may not reach this one.",
                    color = BossThemeColors.TextMuted,
                    size = 10,
                )
            }
        }
    }

    /**
     * Says when the durable trace is not being written.
     *
     * Loud, because the failure is silent otherwise and its consequence is that the
     * operator believes there is a record of this session when there is not.
     */
    @Composable
    private fun TraceFault(message: String) {
        Card {
            Label("The agent trace is not being written", bold = true, color = BossThemeColors.ErrorColor)
            Spacer(Modifier.height(4.dp))
            Label(message, color = BossThemeColors.TextSecondary, size = 10)
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
    private fun GrantsRow(@Suppress("UNUSED_PARAMETER") tick: Long) {
        // [tick] is never read, and is still what makes the countdown below move.
        // Compose skips a composable whose arguments all compare equal to last time,
        // so a changing argument is the mechanism; without one this row would render
        // its remaining time once and then sit at that number while it expired.
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
                    // Calls and stopped moved to the activity card's header, beside the
                    // list they describe. What is left is only rendered when it is not
                    // zero: a row of four counters where three usually are is a row
                    // nobody reads, and the zeros were carrying no information.
                    val extras =
                        listOfNotNull(
                            session.fileTouches.size.takeIf { it > 0 }?.let { "$it file change(s) observed" },
                            session.notes.size.takeIf { it > 0 }?.let { "$it note(s) from the agent" },
                        )
                    Label(
                        extras.joinToString(" · ").ifEmpty { "Running" },
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
            // The one number that says what this gateway adds over BOSS's own policy
            // engine and ledger, which cannot see the terminal tools. Shown only when
            // the session actually used one: a row asserting the plugin's usefulness
            // on a session that did not demonstrate it would be advertising. Same
            // reason the report's section prints "Nothing" rather than a zero.
            val ungoverned = HostGovernanceGap.ungovernedCallCount(session.records)
            if (ungoverned > 0) {
                Spacer(Modifier.height(6.dp))
                Label(
                    "$ungoverned call(s) went to tools BOSS's own approval gate cannot receive.",
                    color = BossThemeColors.TextSecondary,
                    size = 10,
                )
            }
            // A trace nobody can find is not a trace. Shown once it exists rather than
            // as a promise, so the line is evidence that writing is working and not
            // just a path somebody would have to go and check.
            runtime.tracePath?.let { path ->
                Spacer(Modifier.height(6.dp))
                Label(
                    "Every call above is also appended to $path",
                    color = BossThemeColors.TextMuted,
                    size = 10,
                    maxLines = 1,
                )
            }
        }
    }

    // ---- ledger --------------------------------------------------------------

    @Composable
    private fun ColumnScope.Ledger(session: SessionSnapshot) {
        Card(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("What the agent did", bold = true)
                Spacer(Modifier.weight(1f))
                if (session.records.isNotEmpty()) {
                    // The two counts an operator is actually looking for, and nothing
                    // else. A row of four counters where three are usually zero is a
                    // row nobody reads.
                    Label(
                        "${session.records.size} calls" +
                            if (session.stoppedCount > 0) ", ${session.stoppedCount} stopped" else "",
                        color = BossThemeColors.TextMuted,
                        size = 10,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (session.records.isEmpty()) {
                Label(
                    "Nothing yet. Every call an agent makes through the gateway appears here, " +
                        "with what it asked for and what was decided.",
                    color = BossThemeColors.TextMuted,
                )
                return@Card
            }
            // Newest first: the operator is watching for what just happened, and a list
            // that grows downwards makes them chase it.
            //
            // Newest first is not enough on its own. A LazyColumn keeps its scroll
            // offset, so prepending a row pushes the view down by exactly one row and
            // the new call lands just above the top edge. Seen live: a refusal arrived
            // and the panel went on showing the two calls before it. The list is
            // returned to the top whenever the newest record changes, and only then, so
            // an operator who has deliberately scrolled back is not yanked away while
            // nothing new is happening.
            val listState = rememberLazyListState()
            val newestId = session.records.lastOrNull()?.id
            LaunchedEffect(newestId) { listState.scrollToItem(0) }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(session.records.reversed(), key = { it.id }) { record -> ActivityRow(record) }
            }
        }
    }

    /**
     * One call, told in the order somebody asks about it: when, what, what happened,
     * and only then the detail.
     *
     * The previous version put the tool name first and the outcome second in the same
     * weight, which reads as a list of tool names. What an operator scans for is the
     * outcome, so it gets the colour and a fixed column: the eye can run down the left
     * edge and stop at the red one without reading any of the rest.
     */
    @Composable
    private fun ActivityRow(record: InvocationRecord) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Label(
                clockFormat.format(Instant.ofEpochMilli(record.atMillis)),
                color = BossThemeColors.TextMuted,
                size = 10,
                mono = true,
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(OUTCOME_COLUMN.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(outcomeColor(record.outcome), size = 6)
                    Spacer(Modifier.width(5.dp))
                    Label(outcomeWord(record.outcome), color = outcomeColor(record.outcome), size = 10, bold = true)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(record.toolName, bold = true, mono = true)
                    Spacer(Modifier.width(8.dp))
                    Label(capabilityWord(record), color = BossThemeColors.TextMuted, size = 10)
                    timingWord(record)?.let {
                        Spacer(Modifier.width(8.dp))
                        Label(it, color = BossThemeColors.TextMuted, size = 10)
                    }
                }
                Label(
                    record.argumentsPreview,
                    color = BossThemeColors.TextSecondary,
                    size = 10,
                    mono = true,
                    maxLines = 1,
                )
                // Only where the row would otherwise be a puzzle: a refusal without its
                // reason, or an allowed run_command under a profile that escalates them.
                whyWord(record)?.let {
                    Label(it, color = BossThemeColors.TextMuted, size = 10, maxLines = 2)
                }
            }
        }
    }

    /** Past tense, because every row is something that already happened. */
    private fun outcomeWord(outcome: Outcome): String =
        when (outcome) {
            Outcome.ALLOWED -> "allowed"
            Outcome.GRANTED -> "granted"
            Outcome.APPROVED -> "you allowed"
            Outcome.REFUSED -> "you refused"
            Outcome.BLOCKED -> "blocked"
            Outcome.FAILED -> "failed"
        }

    /**
     * What the call was treated as, saying so when that is not what its name implied.
     *
     * An allowed `run_command` under Read only is a contradiction on the face of it,
     * and a reader who cannot see why is right to distrust the rest of the row.
     */
    private fun capabilityWord(record: InvocationRecord): String =
        if (record.declaredCapability != null) {
            "${record.declaredCapability.label.lowercase()}, judged ${record.capability.label.lowercase()}"
        } else {
            record.capability.label.lowercase()
        }

    /**
     * How long, and whose time it was.
     *
     * Absent for anything fast enough not to matter, because a duration on every row
     * is a column of noise that hides the one that took two minutes.
     */
    private fun timingWord(record: InvocationRecord): String? {
        val waited = record.waitedForOperatorMillis
        if (waited != null && waited >= NOTABLE_MILLIS) return "you took ${waited / 1000}s"
        val upstream = record.upstreamMillis ?: return null
        return if (upstream >= NOTABLE_MILLIS) "took ${upstream / 1000}s" else null
    }

    private fun whyWord(record: InvocationRecord): String? =
        when {
            record.declaredCapability != null -> "Judged read-only, so it did not need your approval."
            record.outcome == Outcome.BLOCKED -> record.detail
            else -> null
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

    /**
     * [maxLines] defaults to unbounded, which is right for the prose labels. The
     * ledger passes 1: an argument preview is attacker-influenced text of no fixed
     * length, and a single `run_command` with a long argument list would otherwise
     * grow its row until it pushed everything else off the panel. Truncating loses
     * nothing that matters, because the preview is redacted anyway and the full
     * command is in the terminal it ran in.
     */
    @Composable
    private fun Label(
        text: String,
        color: Color = BossThemeColors.TextPrimary,
        bold: Boolean = false,
        size: Int = 11,
        mono: Boolean = false,
        maxLines: Int = Int.MAX_VALUE,
    ) {
        androidx.compose.material.Text(
            text = text,
            color = color,
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
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
