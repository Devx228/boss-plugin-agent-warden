package ai.rever.boss.plugin.dynamic.warden.session

import ai.rever.boss.plugin.dynamic.warden.gateway.Capability
import ai.rever.boss.plugin.dynamic.warden.gateway.HostGovernanceGap
import ai.rever.boss.plugin.dynamic.warden.gateway.Outcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders a session as Markdown.
 *
 * A pure function of its [SessionSnapshot], with the clock and time zone injected,
 * so the output is byte-for-byte reproducible in tests. That matters more than it
 * sounds: this document is the artefact somebody hands to a reviewer, and a report
 * generator that cannot be diffed cannot be trusted not to drift.
 *
 * The structure answers questions in the order a sceptical reader asks them: what
 * was stopped, what the agent claims it did, what it actually called, what changed
 * on disk, and finally what this document does not prove.
 */
object SessionReport {
    fun render(
        snapshot: SessionSnapshot,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
        val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)
        return buildString {
            appendLine("# Agent session: ${snapshot.label}")
            appendLine()
            appendOverview(snapshot, time)
            appendLine()
            appendOutcomes(snapshot)
            appendLine()
            appendRefusals(snapshot, clock)
            appendLine()
            appendNotes(snapshot, clock)
            appendLine()
            appendCalls(snapshot, clock)
            appendLine()
            appendFileEvidence(snapshot, clock)
            appendLine()
            appendGit(snapshot)
            appendLine()
            appendCoverage(snapshot)
            appendLine()
            appendHostGap(snapshot)
            appendLine()
            appendHowToRead(snapshot)
        }
    }

    private fun StringBuilder.appendOverview(s: SessionSnapshot, time: DateTimeFormatter) {
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Project | ${s.projectPath ?: "_no project open_"} |")
        appendLine("| Profile | ${s.profileName} |")
        appendLine("| Started | ${time.format(Instant.ofEpochMilli(s.startedAtMillis))} |")
        appendLine(
            "| Ended | ${s.endedAtMillis?.let { time.format(Instant.ofEpochMilli(it)) } ?: "_still open_"} |",
        )
        appendLine("| Duration | ${humanDuration(s.durationMillis)} |")
        appendLine("| Tool calls | ${s.records.size} |")
    }

    private fun StringBuilder.appendOutcomes(s: SessionSnapshot) {
        appendLine("## Outcomes")
        appendLine()
        if (s.records.isEmpty()) {
            appendLine("No tool calls were made through the gateway during this session.")
            return
        }
        appendLine("| Outcome | Calls | Meaning |")
        appendLine("|---|---:|---|")
        for (outcome in Outcome.entries) {
            val count = s.countOf(outcome)
            if (count == 0) continue
            appendLine("| ${outcome.name} | $count | ${outcomeMeaning(outcome)} |")
        }
        appendLine()
        appendLine(
            "**${s.stoppedCount}** call(s) never reached BOSS. " +
                "**${s.escalatedCount}** were put to the operator.",
        )
    }

    private fun StringBuilder.appendRefusals(s: SessionSnapshot, clock: DateTimeFormatter) {
        appendLine("## What was stopped")
        appendLine()
        val stopped = s.records.filter { it.outcome == Outcome.BLOCKED || it.outcome == Outcome.REFUSED }
        if (stopped.isEmpty()) {
            appendLine("Nothing was refused. Every call the agent made was permitted by the profile or approved.")
            return
        }
        appendLine("| Time | Tool | Capability | How | Arguments |")
        appendLine("|---|---|---|---|---|")
        for (r in stopped) {
            val how = if (r.outcome == Outcome.BLOCKED) "by policy" else "by the operator"
            appendLine(
                "| ${clock.format(Instant.ofEpochMilli(r.atMillis))} | `${r.toolName}` | " +
                    "${r.capability.label} | $how | ${cell(r.argumentsPreview)} |",
            )
        }
    }

    private fun StringBuilder.appendNotes(s: SessionSnapshot, clock: DateTimeFormatter) {
        appendLine("## What the agent said it was doing")
        appendLine()
        appendLine("_Written by the agent through `warden_log_intent`. This section is testimony, not evidence._")
        appendLine()
        if (s.notes.isEmpty()) {
            appendLine("The agent logged nothing. That is not a fault: the tool is optional and an agent may ignore it.")
            return
        }
        for (n in s.notes) {
            appendLine("- **${clock.format(Instant.ofEpochMilli(n.atMillis))}** ${n.summary}")
            n.detail?.takeIf { it.isNotBlank() }?.let { appendLine("  - $it") }
        }
    }

    private fun StringBuilder.appendCalls(s: SessionSnapshot, clock: DateTimeFormatter) {
        appendLine("## Every tool call")
        appendLine()
        if (s.records.isEmpty()) {
            appendLine("_None._")
            return
        }
        appendLine("| Time | Tool | Capability | Outcome | ms | Arguments (redacted) |")
        appendLine("|---|---|---|---|---:|---|")
        for (r in s.records) {
            appendLine(
                "| ${clock.format(Instant.ofEpochMilli(r.atMillis))} | `${r.toolName}` | ${r.capability.label} | " +
                    "${r.outcome.name} | ${r.durationMillis ?: 0} | ${cell(r.argumentsPreview)} |",
            )
        }
        appendLine()
        appendLine(
            "_Arguments are redacted at capture. Values under credential-shaped keys are dropped entirely and " +
                "long key-like strings are truncated, so this table is safe to share._",
        )
    }

    private fun StringBuilder.appendFileEvidence(s: SessionSnapshot, clock: DateTimeFormatter) {
        appendLine("## What actually changed on disk")
        appendLine()
        appendLine("_Reported by the workspace, not by the agent. The agent cannot suppress this._")
        appendLine()
        if (s.fileTouches.isEmpty()) {
            appendLine("No file changes were observed while this session was open.")
            return
        }
        appendLine("| Time | Change | Path |")
        appendLine("|---|---|---|")
        for (t in s.fileTouches) {
            appendLine("| ${clock.format(Instant.ofEpochMilli(t.atMillis))} | ${t.change} | `${t.path}` |")
        }
    }

    private fun StringBuilder.appendGit(s: SessionSnapshot) {
        appendLine("## Git")
        appendLine()
        val git = s.git
        if (git == null) {
            appendLine("No git repository was open, so there is no commit-level record for this session.")
            return
        }
        appendLine("- Branch: `${git.branch ?: "unknown"}`")
        appendLine("- HEAD at start: `${git.headBefore ?: "unknown"}`")
        appendLine("- HEAD at end: `${git.headAfter ?: "unknown"}`")
        appendLine(
            if (git.committed) {
                "- **HEAD moved during this session, so work was committed.** " +
                    "A commit writes nothing in the working tree, so the file list above would not show it."
            } else {
                "- HEAD did not move, so nothing was committed during this session."
            },
        )
        if (git.deltas.isNotEmpty()) {
            appendLine()
            appendLine("| File | + | - |")
            appendLine("|---|---:|---:|")
            for (d in git.deltas) appendLine("| `${d.path}` | ${d.additions} | ${d.deletions} |")
            appendLine()
            appendLine("Total: +${git.totalAdditions} / -${git.totalDeletions} across ${git.deltas.size} file(s).")
        }
    }

    private fun StringBuilder.appendCoverage(s: SessionSnapshot) {
        appendLine("## Classification coverage")
        appendLine()
        if (s.unclassifiedTools.isEmpty()) {
            appendLine("Every tool offered during this session was classified by the catalog.")
            return
        }
        appendLine(
            "${s.unclassifiedTools.size} tool(s) were offered that this build cannot classify. " +
                "They are treated as high risk and escalated to the operator every time:",
        )
        appendLine()
        for (name in s.unclassifiedTools) appendLine("- `$name`")
        appendLine()
        appendLine(
            "This list is expected to be non-empty on a workspace with plugins installed. " +
                "It is shown because each entry costs the operator a prompt, and because a catalog " +
                "that silently drifted out of date would otherwise look identical to one that is current.",
        )
    }

    /**
     * What BOSS's own governance would not have recorded.
     *
     * Printed only when the session actually used such a tool, because a section
     * asserting this plugin's usefulness on a session that did not demonstrate it
     * would be advertising rather than evidence. On a read-only session it is absent,
     * which is the honest answer.
     */
    private fun StringBuilder.appendHostGap(s: SessionSnapshot) {
        appendLine("## What BOSS's own governance would not have seen")
        appendLine()
        val tools = HostGovernanceGap.ungovernedToolsIn(s.records)
        if (tools.isEmpty()) {
            appendLine(
                "Nothing. Every tool used in this session is one BOSS's own policy engine and " +
                    "ledger can act on, so this gateway added a second opinion rather than the " +
                    "only one.",
            )
            return
        }
        val calls = HostGovernanceGap.ungovernedCallCount(s.records)
        appendLine(
            "$calls of ${s.records.size} call(s), across ${tools.size} tool(s), went to tools " +
                "BOSS's own approval gate cannot receive. Those calls are in this report because " +
                "the gateway sits on the wire; they would not appear in BOSS's operation ledger.",
        )
        appendLine()
        for (name in tools) appendLine("- `$name`")
        appendLine()
        appendLine(
            "BOSS's policy engine, approval dialog and ledger all live inside " +
                "`McpToolRegistryCore.invoke`, which begins by looking the tool up in the host " +
                "registry. BossTerm serves the terminal tools directly, so they never arrive. " +
                "See BossConsole#495.",
        )
        appendLine()
        appendLine(
            "This is a claim about a specific BossConsole version and it can go stale. If the " +
                "host closes the gap these tools become governed twice, which costs nothing and " +
                "only makes this section not worth printing.",
        )
    }

    /**
     * States the limits in the artefact itself.
     *
     * A report that overstates what it proves is worse than none, because it gets
     * believed. Everything here is a real gap someone could otherwise discover after
     * relying on the document.
     */
    private fun StringBuilder.appendHowToRead(s: SessionSnapshot) {
        appendLine("## How to read this")
        appendLine()
        appendLine(
            "- **This records calls through the gateway, not everything an agent did.** An agent with its " +
                "own shell can act without any MCP tool. What is captured is the workspace surface BOSS " +
                "exposes, which is the part this plugin can stand in front of.",
        )
        appendLine(
            "- **File changes are correlated by time, not by cause.** Anything writing during the session " +
                "appears here, including your own edits and a build running in the background.",
        )
        appendLine(
            "- **The agent's own notes are unverified.** They sit beside the involuntary record " +
                "deliberately, so the two can be compared.",
        )
        appendLine(
            "- **Arguments are redacted, so this is not a replay log.** It cannot be used to re-run what " +
                "happened, by design.",
        )
        if (s.endedAtMillis == null) {
            appendLine("- **This session was still open when the report was written**, so it is a snapshot.")
        }
    }

    private fun outcomeMeaning(outcome: Outcome): String =
        when (outcome) {
            Outcome.ALLOWED -> "Permitted by the profile without asking."
            Outcome.APPROVED -> "Escalated; the operator allowed it."
            Outcome.GRANTED -> "Covered by a time-boxed grant the operator had already given."
            Outcome.REFUSED -> "Escalated; the operator refused, or nobody answered in time."
            Outcome.BLOCKED -> "Refused by the profile without asking."
            Outcome.FAILED -> "Permitted and forwarded, but the tool itself reported an error."
        }

    /** Markdown tables break on a raw pipe, and arguments routinely contain them. */
    private fun cell(text: String): String = text.replace("|", "\\|")

    private fun humanDuration(millis: Long): String {
        if (millis < 0) return "unknown"
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val sec = totalSeconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${sec}s"
            else -> "${sec}s"
        }
    }

    /** Filename for a session's report. Safe on Windows, which rules out a colon in the timestamp. */
    fun fileNameFor(snapshot: SessionSnapshot, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(zone)
                .format(Instant.ofEpochMilli(snapshot.startedAtMillis))
        val slug =
            snapshot.label.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifEmpty { "session" }
                .take(40)
        return "agent-session-$stamp-$slug.md"
    }

    /** Capabilities worth calling out in a summary line, highest first. */
    val NOTABLE_CAPABILITIES = listOf(Capability.GOVERN, Capability.BROWSER_SCRIPT, Capability.EXECUTE)
}
