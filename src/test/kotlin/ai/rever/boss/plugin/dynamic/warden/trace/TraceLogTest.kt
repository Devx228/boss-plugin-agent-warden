package ai.rever.boss.plugin.dynamic.warden.trace

import ai.rever.boss.plugin.dynamic.warden.gateway.Capability
import ai.rever.boss.plugin.dynamic.warden.gateway.InvocationRecord
import ai.rever.boss.plugin.dynamic.warden.gateway.Outcome
import ai.rever.boss.plugin.dynamic.warden.gateway.Redactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trace is the copy that survives the thing the report does not: a crash, an
 * unload, a window closed without anyone pressing Export. So these tests are mostly
 * about the properties that make it worth having rather than about its shape.
 *
 * The time zone and clock are pinned so the file is byte-comparable, for the same
 * reason the report generator's are.
 */
class TraceLogTest {
    private lateinit var dir: Path
    private var now = 1_757_000_000_000L

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("warden-trace-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun log(
        maxBytes: Long = TraceLog.DEFAULT_MAX_BYTES,
        maxBackups: Int = TraceLog.DEFAULT_MAX_BACKUPS,
        onFault: (String) -> Unit = {},
    ) = TraceLog(dir, { now }, ZoneId.of("UTC"), maxBytes, maxBackups, onFault)

    private fun record(
        id: Long = 1,
        tool: String = "run_command",
        outcome: Outcome = Outcome.REFUSED,
        preview: String = "script=echo hi",
        capability: Capability = Capability.EXECUTE,
        waited: Long? = null,
        upstream: Long? = null,
    ) = InvocationRecord(
        id = id,
        atMillis = now,
        toolName = tool,
        capability = capability,
        outcome = outcome,
        argumentsPreview = preview,
        durationMillis = 12,
        waitedForOperatorMillis = waited,
        upstreamMillis = upstream,
    )

    private fun lines() = log().file.readText().trim().lines()

    private fun parsed(index: Int = 0): JsonObject =
        Json.parseToJsonElement(lines()[index]) as JsonObject

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    // ---- durability ----------------------------------------------------------

    @Test
    fun `a call is on disk as soon as it is written`() {
        // The whole point. Nothing here is buffered until a flush that a crash would
        // skip, because the scenario this file exists for is the one where no orderly
        // shutdown happens.
        val t = log()
        t.call("session-1", record())
        assertTrue(Files.exists(t.file))
        assertEquals(1, lines().size)
    }

    @Test
    fun `each line is one self-contained json object`() {
        val t = log()
        t.call("session-1", record(id = 1, tool = "list_tabs"))
        t.call("session-1", record(id = 2, tool = "run_command"))
        val all = lines()
        assertEquals(2, all.size)
        // Read one line without the others: a trace whose lines only parse together
        // cannot be tailed, grepped or truncated, which is most of how it gets used.
        assertEquals("list_tabs", (Json.parseToJsonElement(all[0]) as JsonObject).str("tool"))
        assertEquals("run_command", (Json.parseToJsonElement(all[1]) as JsonObject).str("tool"))
    }

    @Test
    fun `sequence numbers are issued in order and without gaps`() {
        // A reader tells a quiet period from a dropped write by the numbers. They are
        // worth nothing unless exactly one thing issues them.
        val t = log()
        repeat(5) { t.call("s", record(id = it.toLong())) }
        val seqs = lines().map { (Json.parseToJsonElement(it) as JsonObject).str("seq")?.toInt() }
        assertEquals(listOf(1, 2, 3, 4, 5), seqs)
    }

    @Test
    fun `concurrent writers do not interleave or lose lines`() {
        // The gateway serves calls on a pool. A half-written line is an unparseable
        // file, and an unparseable audit trail is not one.
        val t = log()
        val start = CountDownLatch(1)
        val threads = (1..24).map {
            thread {
                start.await(10, TimeUnit.SECONDS)
                t.call("s", record(id = it.toLong(), preview = "n=$it"))
            }
        }
        start.countDown()
        threads.forEach { it.join(20_000) }
        val all = lines()
        assertEquals(24, all.size, "lines were lost under concurrency")
        assertEquals(24, all.map { (Json.parseToJsonElement(it) as JsonObject).str("seq") }.toSet().size)
    }

    // ---- what it must not contain --------------------------------------------

    @Test
    fun `arguments reach the file already redacted`() {
        // The trace is a new file on disk that did not exist before, so it is a new
        // way to leak the thing being audited. It inherits the report's redaction
        // rather than getting its own weaker copy.
        val leaked = Redactor.preview(
            Json.parseToJsonElement("""{"token":"ghp_A1b2C3d4E5f6G7h8I9j0K1l2"}""") as JsonObject,
        )
        log().call("s", record(preview = leaked))
        val written = parsed().str("arguments")
        assertEquals("token=<redacted>", written)
        assertFalse("ghp_A1b2C3d4E5f6G7h8I9j0K1l2" in log().file.readText())
    }

    // ---- what it must contain ------------------------------------------------

    @Test
    fun `a call carries the phases separately, not just a total`() {
        // "Slow" is not a useful answer. Whether the wait was the operator's or
        // upstream's is, and it is the difference between a hung tool and an
        // unattended dialog.
        log().call("s", record(waited = 118_000, upstream = 42))
        val e = parsed()
        assertEquals("118000", e.str("waitedForOperatorMs"))
        assertEquals("42", e.str("upstreamMs"))
    }

    @Test
    fun `a phase that did not happen is absent rather than zero`() {
        // An allowed call was not "asked and answered in 0ms". Writing a zero would
        // make the two indistinguishable to anything aggregating the file.
        log().call("s", record(outcome = Outcome.ALLOWED, upstream = 9))
        assertNull(parsed().str("waitedForOperatorMs"))
    }

    @Test
    fun `each call says whether BOSS could have governed it`() {
        // Carried per line so the trace can be diffed against the host's own
        // mcp-calls.jsonl without re-deriving the classification. This is the field
        // that makes BossConsole#495 checkable by someone who does not trust us.
        log().call("s", record(tool = "run_command"))
        assertEquals("false", parsed().str("governedByHost"))

        tearDown(); setUp()
        log().call("s", record(tool = "browser_run_js", capability = Capability.BROWSER_SCRIPT))
        assertEquals("true", parsed().str("governedByHost"), "browser_run_js is governed by the host")
    }

    @Test
    fun `an undelivered verdict gets its own line`() {
        // Observed live: the client gives up at 120s, the operator answers at 133s,
        // and the refusal is correct, recorded and delivered to nobody. Without this
        // line the trace and the agent's own transcript disagree and neither explains
        // the other.
        val t = log()
        t.call("s", record())
        t.undelivered("s", "run_command", "An established connection was aborted")
        val all = lines()
        assertEquals(TraceEvent.KIND_CALL, (Json.parseToJsonElement(all[0]) as JsonObject).str("kind"))
        val second = Json.parseToJsonElement(all[1]) as JsonObject
        assertEquals(TraceEvent.KIND_UNDELIVERED, second.str("kind"))
        assertEquals("run_command", second.str("tool"))
    }

    @Test
    fun `session lifecycle is on the same timeline as the calls`() {
        // Separate files would make "which policy was in force for this call" a join
        // across two clocks. One file in one order answers it by reading downwards.
        val t = log()
        t.session("s", "session opened under 'Read only'")
        t.call("s", record())
        t.session("s", "policy is now 'Build'")
        assertEquals(
            listOf(TraceEvent.KIND_SESSION, TraceEvent.KIND_CALL, TraceEvent.KIND_SESSION),
            lines().map { (Json.parseToJsonElement(it) as JsonObject).str("kind") },
        )
    }

    // ---- failing safely ------------------------------------------------------

    @Test
    fun `a write it cannot do is reported once, not every call`() {
        // A full disk raising one message per tool call teaches the operator to
        // dismiss the one that matters.
        val faults = mutableListOf<String>()
        val blocked = TraceLog(dir.resolve("a-file-not-a-dir"), { now }, ZoneId.of("UTC"), onFault = { faults += it })
        Files.write(dir.resolve("a-file-not-a-dir"), byteArrayOf(1))
        repeat(5) { blocked.call("s", record()) }
        assertEquals(1, faults.size, "the same failure was announced repeatedly")
        assertNotNull(blocked.fault, "a trace that is not being written must say so")
    }

    @Test
    fun `a failed write never propagates into the call path`() {
        // A tool call must not fail because the audit copy could not be taken. The
        // gateway calls this inline on the request thread.
        val blocked = TraceLog(dir.resolve("nope"), { now }, ZoneId.of("UTC"))
        Files.write(dir.resolve("nope"), byteArrayOf(1))
        blocked.call("s", record())
        blocked.session("s", "opened")
        blocked.undelivered("s", "run_command", "gone")
    }

    // ---- rotation ------------------------------------------------------------

    @Test
    fun `the file rolls aside rather than growing without limit`() {
        val t = log(maxBytes = 200, maxBackups = 2)
        repeat(20) { t.call("s", record(id = it.toLong(), preview = "n=$it")) }
        assertTrue(Files.exists(dir.resolve("${TraceLog.FILE_NAME}.1")), "nothing was rotated")
        assertTrue(Files.size(t.file) < 1_000)
    }

    @Test
    fun `rotation keeps at most the configured number of backups`() {
        val t = log(maxBytes = 120, maxBackups = 2)
        repeat(40) { t.call("s", record(id = it.toLong(), preview = "n=$it")) }
        assertFalse(
            Files.exists(dir.resolve("${TraceLog.FILE_NAME}.3")),
            "backups grew past the limit, so the trace has no bounded size after all",
        )
    }

    @Test
    fun `tail answers empty before anything has been written`() {
        assertEquals(emptyList(), log().tail(10))
    }

    @Test
    fun `tail returns the newest lines last`() {
        val t = log()
        repeat(5) { t.call("s", record(id = it.toLong(), preview = "n=$it")) }
        val last = t.tail(2)
        assertEquals(2, last.size)
        assertTrue("n=4" in last.last())
    }
}
