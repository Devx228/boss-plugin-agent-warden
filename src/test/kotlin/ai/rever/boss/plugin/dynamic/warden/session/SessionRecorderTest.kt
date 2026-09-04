package ai.rever.boss.plugin.dynamic.warden.session

import ai.rever.boss.plugin.dynamic.warden.gateway.Capability
import ai.rever.boss.plugin.dynamic.warden.gateway.InvocationRecord
import ai.rever.boss.plugin.dynamic.warden.gateway.Outcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRecorderTest {
    private var now = 1_000L
    private fun recorder() = SessionRecorder { now }

    private fun entry(id: Long, tool: String = "list_tabs") =
        InvocationRecord(
            id = id,
            atMillis = now,
            toolName = tool,
            capability = Capability.INSPECT,
            outcome = Outcome.ALLOWED,
            argumentsPreview = "{}",
        )

    @Test
    fun `nothing is recorded before a session starts`() {
        // The gateway can outlive a session, and a call arriving between sessions
        // must not be filed against whichever one happens to be in the flow.
        val r = recorder()
        r.record(entry(1))
        r.note("hello")
        r.fileTouched("/a", "MODIFIED")
        assertTrue(r.state.value.records.isEmpty())
        assertTrue(r.state.value.notes.isEmpty())
        assertTrue(r.state.value.fileTouches.isEmpty())
        assertFalse(r.isRecording)
    }

    @Test
    fun `nothing is recorded after a session ends`() {
        val r = recorder()
        r.start("s", "Read only", "/p")
        r.end()
        r.record(entry(1))
        assertTrue(r.state.value.records.isEmpty(), "a call after end() was filed against the closed session")
    }

    @Test
    fun `starting captures the label, profile and project`() {
        val r = recorder()
        val s = r.start("Refactor", "Build", "/home/p")
        assertEquals("Refactor", s.label)
        assertEquals("Build", s.profileName)
        assertEquals("/home/p", s.projectPath)
        assertNull(s.endedAtMillis)
        assertTrue(r.isRecording)
    }

    @Test
    fun `a blank label falls back to a usable default`() {
        // The label reaches a filename. An empty one produced "agent-session-...-.md".
        val r = recorder()
        assertEquals(SessionRecorder.DEFAULT_LABEL, r.start("   ", "Read only", null).label)
    }

    @Test
    fun `end returns the final snapshot rather than requiring a re-read`() {
        // Closes a race: a caller that ended a session then read the flow could get a
        // newly started session's state instead of the one it just closed.
        val r = recorder()
        r.start("s", "Read only", "/p")
        r.record(entry(1))
        now += 5000
        val finished = r.end()
        assertNotNull(finished)
        assertEquals(1, finished.records.size)
        assertEquals(now, finished.endedAtMillis)
        assertFalse(r.isRecording)
    }

    @Test
    fun `ending twice returns null the second time`() {
        val r = recorder()
        r.start("s", "Read only", null)
        assertNotNull(r.end())
        assertNull(r.end(), "a second end() produced a duplicate session to persist")
    }

    @Test
    fun `repeated identical file events are collapsed`() {
        // An editor autosave or a build loop emits MODIFIED for one file many times a
        // second. Keeping each would evict the rest of the session from the capped
        // list and render the evidence table as one file repeated.
        val r = recorder()
        r.start("s", "Read only", "/p")
        repeat(50) { r.fileTouched("/p/a.kt", "MODIFIED") }
        assertEquals(1, r.state.value.fileTouches.size)
    }

    @Test
    fun `a different file or a different change is not collapsed`() {
        val r = recorder()
        r.start("s", "Read only", "/p")
        r.fileTouched("/p/a.kt", "MODIFIED")
        r.fileTouched("/p/b.kt", "MODIFIED")
        r.fileTouched("/p/b.kt", "DELETED")
        r.fileTouched("/p/b.kt", "MODIFIED")
        assertEquals(4, r.state.value.fileTouches.size, "collapsing swallowed distinct events")
    }

    @Test
    fun `records, touches and notes are all capped`() {
        // Each of these sits behind a StateFlow a Compose panel recomposes on.
        val r = recorder()
        r.start("s", "Read only", "/p")
        repeat(SessionRecorder.MAX_RECORDS + 120) { r.record(entry(it.toLong(), "tool$it")) }
        repeat(SessionRecorder.MAX_NOTES + 40) { r.note("note $it") }
        repeat(SessionRecorder.MAX_TOUCHES + 40) { r.fileTouched("/p/f$it", "MODIFIED") }
        val s = r.state.value
        assertEquals(SessionRecorder.MAX_RECORDS, s.records.size)
        assertEquals(SessionRecorder.MAX_NOTES, s.notes.size)
        assertEquals(SessionRecorder.MAX_TOUCHES, s.fileTouches.size)
    }

    @Test
    fun `capping drops the oldest and keeps the most recent`() {
        // Truncating the wrong end would leave the operator looking at the start of a
        // long session while the interesting part scrolled off.
        val r = recorder()
        r.start("s", "Read only", "/p")
        repeat(SessionRecorder.MAX_RECORDS + 10) { r.record(entry(it.toLong(), "tool$it")) }
        val records = r.state.value.records
        assertEquals("tool${SessionRecorder.MAX_RECORDS + 9}", records.last().toolName)
        assertEquals("tool10", records.first().toolName)
    }

    @Test
    fun `notes are trimmed and empty detail becomes null`() {
        val r = recorder()
        r.start("s", "Read only", null)
        r.note("  did a thing  ", "   ")
        val note = r.state.value.notes.single()
        assertEquals("did a thing", note.summary)
        assertNull(note.detail, "whitespace-only detail should not render as an empty bullet")
    }

    @Test
    fun `starting a new session discards the previous one from the flow`() {
        val r = recorder()
        r.start("first", "Read only", "/p")
        r.record(entry(1))
        r.end()
        val second = r.start("second", "Build", "/p")
        assertEquals("second", second.label)
        assertTrue(r.state.value.records.isEmpty(), "a new session inherited the previous session's calls")
    }

    @Test
    fun `unclassified tool names are deduplicated and sorted`() {
        val r = recorder()
        r.start("s", "Read only", null)
        r.setUnclassifiedTools(listOf("zeta", "alpha", "zeta", "mid"))
        assertEquals(listOf("alpha", "mid", "zeta"), r.state.value.unclassifiedTools)
    }

    @Test
    fun `git can be attached at start and replaced at end`() {
        val r = recorder()
        r.start("s", "Read only", "/p", GitSnapshot(branch = "main", headBefore = "aaa"))
        val finished = r.end(GitSnapshot(branch = "main", headBefore = "aaa", headAfter = "bbb"))
        assertTrue(finished!!.git!!.committed)
    }

    @Test
    fun `ending without git keeps what was already attached`() {
        // The end-of-session git read can fail. Losing the start-of-session state
        // because of that would silently drop the only commit evidence there was.
        val r = recorder()
        r.start("s", "Read only", "/p", GitSnapshot(branch = "main", headBefore = "aaa"))
        assertEquals("aaa", r.end(null)!!.git!!.headBefore)
    }

    @Test
    fun `concurrent writers do not lose or corrupt entries`() {
        // The gateway pool, the event bus and the MCP tools all write here at once.
        val r = SessionRecorder()
        r.start("s", "Read only", "/p")
        val writers = 8
        val each = 40
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)
        val pool = Executors.newFixedThreadPool(writers)
        repeat(writers) { w ->
            pool.submit {
                start.await()
                repeat(each) { i ->
                    r.record(entry((w * each + i).toLong(), "t$w-$i"))
                    r.note("n$w-$i")
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "recorder deadlocked under concurrent writes")
        pool.shutdownNow()
        assertEquals(writers * each, r.state.value.records.size, "records lost under concurrency")
    }
}
