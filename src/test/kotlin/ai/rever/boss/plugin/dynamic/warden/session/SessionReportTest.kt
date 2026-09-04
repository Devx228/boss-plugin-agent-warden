package ai.rever.boss.plugin.dynamic.warden.session

import ai.rever.boss.plugin.dynamic.warden.gateway.Capability
import ai.rever.boss.plugin.dynamic.warden.gateway.InvocationRecord
import ai.rever.boss.plugin.dynamic.warden.gateway.Outcome
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The report is the artefact a person hands to a reviewer, so these tests are
 * about what it must never omit and what it must never claim.
 *
 * The time zone is pinned so output is byte-for-byte reproducible; a report
 * generator that cannot be diffed cannot be trusted not to drift.
 */
class SessionReportTest {
    private val utc = ZoneId.of("UTC")
    private val t0 = 1_757_000_000_000L

    private fun record(
        id: Long,
        tool: String,
        capability: Capability,
        outcome: Outcome,
        preview: String = "{}",
        offsetMillis: Long = 0,
    ) = InvocationRecord(
        id = id,
        atMillis = t0 + offsetMillis,
        toolName = tool,
        capability = capability,
        outcome = outcome,
        argumentsPreview = preview,
        durationMillis = 12,
    )

    private fun snapshot(
        records: List<InvocationRecord> = emptyList(),
        touches: List<FileTouch> = emptyList(),
        notes: List<AgentNote> = emptyList(),
        git: GitSnapshot? = null,
        unclassified: List<String> = emptyList(),
        ended: Long? = t0 + 65_000,
    ) = SessionSnapshot(
        id = "s1",
        label = "Refactor the parser",
        startedAtMillis = t0,
        endedAtMillis = ended,
        profileName = "Read only",
        projectPath = "/home/dev/project",
        records = records,
        fileTouches = touches,
        notes = notes,
        git = git,
        unclassifiedTools = unclassified,
    )

    private fun render(s: SessionSnapshot) = SessionReport.render(s, utc)

    @Test
    fun `a refused call is reported with what it was and how it was stopped`() {
        // The headline fact of the whole document. If a refusal can be rendered
        // without appearing here, the report is not an audit trail.
        val md =
            render(
                snapshot(
                    listOf(
                        record(1, "browser_run_js", Capability.BROWSER_SCRIPT, Outcome.REFUSED, "script=document..."),
                    ),
                ),
            )
        assertTrue("## What was stopped" in md)
        assertTrue("browser_run_js" in md, md)
        assertTrue("by the operator" in md, md)
        assertTrue("1** call(s) never reached BOSS" in md, md)
    }

    @Test
    fun `policy blocks and operator refusals are distinguished`() {
        val md =
            render(
                snapshot(
                    listOf(
                        record(1, "manage_tools", Capability.GOVERN, Outcome.BLOCKED),
                        record(2, "run_command", Capability.EXECUTE, Outcome.REFUSED, offsetMillis = 1000),
                    ),
                ),
            )
        assertTrue("by policy" in md, "a policy block was not labelled as such")
        assertTrue("by the operator" in md, "an operator refusal was not labelled as such")
    }

    @Test
    fun `a clean session says so explicitly rather than leaving a blank section`() {
        // An empty section reads as a rendering bug and invites the reader to assume
        // the tool failed rather than that nothing was refused.
        val md = render(snapshot(listOf(record(1, "list_tabs", Capability.INSPECT, Outcome.ALLOWED))))
        assertTrue("Nothing was refused" in md, md)
    }

    @Test
    fun `agent notes are labelled as testimony, not evidence`() {
        // The strongest claim the report could wrongly make is that the agent's own
        // account is corroborated. The label is what stops that.
        val md = render(snapshot(notes = listOf(AgentNote(t0, "Read the tokenizer", "to find the bug"))))
        assertTrue("testimony, not evidence" in md, md)
        assertTrue("Read the tokenizer" in md, md)
        assertTrue("to find the bug" in md, md)
    }

    @Test
    fun `an agent that logged nothing is not reported as a failure`() {
        val md = render(snapshot())
        assertTrue("The agent logged nothing" in md, md)
        assertTrue("not a fault" in md, md)
    }

    @Test
    fun `file evidence is marked as not authored by the agent`() {
        val md = render(snapshot(touches = listOf(FileTouch("/p/a.kt", "MODIFIED", t0 + 500))))
        assertTrue("not by the agent" in md, md)
        assertTrue("/p/a.kt" in md, md)
        assertTrue("MODIFIED" in md, md)
    }

    @Test
    fun `a commit is called out because the file list cannot show one`() {
        // The subtlest gap in the evidence model: committing writes nothing in the
        // working tree, so a reader trusting the file list alone concludes nothing
        // happened. The report has to say this where they will see it.
        val md = render(snapshot(git = GitSnapshot(branch = "main", headBefore = "aaa", headAfter = "bbb")))
        assertTrue("HEAD moved" in md, md)
        assertTrue("would not show it" in md, md)
    }

    @Test
    fun `no commit is stated positively rather than left to inference`() {
        val md = render(snapshot(git = GitSnapshot(branch = "main", headBefore = "aaa", headAfter = "aaa")))
        assertTrue("nothing was committed" in md, md)
    }

    @Test
    fun `git diff stats are totalled`() {
        val md =
            render(
                snapshot(
                    git =
                        GitSnapshot(
                            branch = "main",
                            deltas = listOf(GitFileDelta("a.kt", 10, 2), GitFileDelta("b.kt", 5, 3)),
                        ),
                ),
            )
        assertTrue("+15 / -5" in md, md)
        assertTrue("2 file(s)" in md, md)
    }

    @Test
    fun `unclassified tools are disclosed rather than hidden`() {
        // Catalog coverage decays as plugins are installed. A report that hid this
        // would look identical whether the catalog was current or two years stale.
        val md = render(snapshot(unclassified = listOf("some_plugin_tool")))
        assertTrue("some_plugin_tool" in md, md)
        assertTrue("treated as high risk" in md, md)
    }

    @Test
    fun `full coverage is stated positively`() {
        val md = render(snapshot())
        assertTrue("Every tool offered during this session was classified" in md, md)
    }

    @Test
    fun `the limitations section always appears`() {
        // Every report, including a completely clean one. This is the section that
        // stops the document being over-trusted, so it cannot be conditional.
        val md = render(snapshot())
        assertTrue("## How to read this" in md)
        assertTrue("not everything an agent did" in md, md)
        assertTrue("correlated by time, not by cause" in md, md)
        assertTrue("not a replay log" in md, md)
    }

    @Test
    fun `an open session is marked as a snapshot`() {
        val md = render(snapshot(ended = null))
        assertTrue("still open" in md, md)
        assertTrue("it is a snapshot" in md, md)
    }

    @Test
    fun `pipes in arguments do not break the markdown table`() {
        // Shell arguments contain pipes constantly, and an unescaped one silently
        // shifts every following column, corrupting the record visually.
        val md = render(snapshot(listOf(record(1, "run_command", Capability.EXECUTE, Outcome.ALLOWED, "script=ls | wc"))))
        assertTrue("""\|""" in md, "a pipe in an argument was not escaped: $md")
        val row = md.lines().first { "run_command" in it && it.startsWith("|") }
        assertEquals(6, row.split(Regex("""(?<!\\)\|""")).size - 2, "table row has the wrong column count: $row")
    }

    @Test
    fun `rendering is deterministic for a fixed snapshot and zone`() {
        val s = snapshot(listOf(record(1, "list_tabs", Capability.INSPECT, Outcome.ALLOWED)))
        assertEquals(render(s), render(s))
    }

    @Test
    fun `every outcome that occurred is explained in the outcomes table`() {
        // A reader meeting GRANTED or FAILED for the first time must not have to
        // guess. Iterates the enum so a new outcome without an explanation fails.
        val records = Outcome.entries.mapIndexed { i, o -> record(i.toLong(), "t$i", Capability.INSPECT, o, offsetMillis = i * 1000L) }
        val md = render(snapshot(records))
        for (outcome in Outcome.entries) {
            assertTrue("| ${outcome.name} |" in md, "${outcome.name} missing from the outcomes table")
        }
        assertFalse("| |  |" in md, "an outcome rendered with no explanation")
    }

    @Test
    fun `the filename is filesystem safe and carries the timestamp`() {
        // Windows forbids a colon, which an ISO timestamp contains, and the report is
        // written through the host's file provider on all three platforms.
        val name = SessionReport.fileNameFor(snapshot(), utc)
        assertFalse(":" in name, name)
        assertTrue(name.startsWith("agent-session-"), name)
        assertTrue(name.endsWith(".md"), name)
        assertTrue("refactor-the-parser" in name, name)
    }

    @Test
    fun `a label of only punctuation still yields a usable filename`() {
        val weird = snapshot().copy(label = "///???")
        val name = SessionReport.fileNameFor(weird, utc)
        assertTrue(name.endsWith("-session.md"), "punctuation-only label produced: $name")
    }

    @Test
    fun `duration renders in human units`() {
        assertTrue("1m 5s" in render(snapshot()), render(snapshot()))
        val long = snapshot().copy(endedAtMillis = t0 + 7_400_000)
        assertTrue("2h 3m" in render(long), render(long))
    }
}
