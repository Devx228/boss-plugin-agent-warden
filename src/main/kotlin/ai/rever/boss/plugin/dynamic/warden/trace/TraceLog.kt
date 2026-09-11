package ai.rever.boss.plugin.dynamic.warden.trace

import ai.rever.boss.plugin.dynamic.warden.gateway.HostGovernanceGap
import ai.rever.boss.plugin.dynamic.warden.gateway.InvocationRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One line of the trace.
 *
 * Deliberately flat and mostly optional, because the file is meant to be read with
 * `grep` and `jq` by somebody who does not have this source open. A nested shape
 * would read better in an IDE and worse at a terminal, and the terminal is where
 * an operator actually goes when an agent has done something surprising.
 *
 * [at] is a human timestamp and [atMillis] the machine one. Both, because sorting
 * and diffing want the number while reading wants the clock, and deriving one from
 * the other at read time is the sort of small friction that stops people looking.
 *
 * [arguments] is already redacted before it reaches here, because the only thing
 * that writes a call line is [TraceLog.call], and that takes an `InvocationRecord`
 * whose `argumentsPreview` has been through `Redactor` by construction. The
 * boundary is structural rather than a rule somebody has to remember.
 */
@Serializable
data class TraceEvent(
    val seq: Long,
    val at: String,
    val atMillis: Long,
    val kind: String,
    val session: String,
    val tool: String? = null,
    val capability: String? = null,
    /**
     * What the tool name alone said, when a shell command was judged to need less.
     *
     * Absent on the ordinary call. Present so that grepping the trace for
     * `run_command` still finds every one of them with the reason it was treated the
     * way it was, rather than a set of allowed reads with no visible connection to
     * execution.
     */
    val declaredCapability: String? = null,
    val outcome: String? = null,
    val arguments: String? = null,
    val waitedForOperatorMs: Long? = null,
    val upstreamMs: Long? = null,
    val totalMs: Long? = null,
    /**
     * Whether BOSS's own gate could have seen this call, from `HostGovernanceGap`.
     *
     * Carried per line so the trace can be checked against `~/.boss/mcp-calls.jsonl`
     * without re-deriving the classification, which is the comparison that makes
     * BossConsole#495 checkable by somebody who does not take this plugin's word for it.
     */
    val governedByHost: Boolean? = null,
    val detail: String? = null,
) {
    companion object {
        /** A tool call that reached a verdict. */
        const val KIND_CALL = "call"

        /**
         * A verdict that was reached but could not be delivered, because the caller had
         * already gone. Its own kind rather than a field on the call, because the call
         * line is written before the answer is attempted and must not be held back
         * waiting to find out whether it arrived.
         */
        const val KIND_UNDELIVERED = "undelivered"

        /** Session opened, closed, or moved to a different policy. */
        const val KIND_SESSION = "session"
    }
}

/**
 * An append-only, redacted record of what an agent did, written as it happens.
 *
 * **Why this exists at all.** Until it did, everything the plugin observed lived in
 * memory until somebody pressed Export. A crash, a plugin reload or a closed window
 * took the whole session with it, which is a strange property for the thing whose
 * job is to be able to say afterwards what an agent was allowed to do. The report
 * remains the artefact you hand to a person; this is the one you still have when
 * nobody thought to ask for a report.
 *
 * **Durability over throughput.** Each line is a separate open-append-close through
 * [Files.write] rather than a kept-open buffered stream. That is a syscall per tool
 * call, which at the rate an agent emits them is nothing, and it buys the property
 * the file exists for: a line that has been written is on disk, not sitting in a
 * buffer that a crash discards. It also leaves nothing to close on unload.
 *
 * **It never throws into the call path.** A tool call must not fail because the
 * disk is full. Failures are swallowed and surfaced once through [onFault], which
 * matters more than it sounds: an operator who believes there is a trace and has
 * none is worse off than one who knows there is none, so the fault is sticky and
 * meant to be shown, not logged and forgotten.
 */
class TraceLog(
    private val directory: Path,
    private val clock: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxBackups: Int = DEFAULT_MAX_BACKUPS,
    /** Called with a human explanation the first time writing fails, and not again until one succeeds. */
    private val onFault: (String) -> Unit = {},
) {
    private val json = Json { encodeDefaults = false; explicitNulls = false }
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)
    private val lock = Any()
    private var seq = 0L

    /** Non-null while the trace is known to be incomplete. Sticky until a write succeeds. */
    @Volatile
    var fault: String? = null
        private set

    val file: Path get() = directory.resolve(FILE_NAME)

    /**
     * Appends [build]'s event, filling in the sequence number and both timestamps.
     *
     * Takes a builder rather than a finished event so the caller cannot supply its
     * own sequence number: the numbers are what let a reader tell a gap from a quiet
     * period, and they are only worth anything if exactly one thing issues them.
     */
    fun append(build: (seq: Long, at: String, atMillis: Long) -> TraceEvent) {
        val now = clock()
        val line =
            synchronized(lock) {
                seq += 1
                runCatching { json.encodeToString(build(seq, stamp.format(Instant.ofEpochMilli(now)), now)) }
                    .getOrNull()
            } ?: return
        write(line)
    }

    private fun write(line: String) {
        synchronized(lock) {
            try {
                Files.createDirectories(directory)
                rotateIfNeeded()
                Files.write(
                    file,
                    (line + "\n").toByteArray(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
                fault = null
            } catch (e: IOException) {
                raiseFault("${e.message ?: "I/O error"}")
            } catch (e: SecurityException) {
                raiseFault("permission denied")
            }
        }
    }

    /**
     * First failure only.
     *
     * A full disk would otherwise raise one message per tool call, and an operator
     * shown forty identical warnings learns to dismiss the one that matters.
     */
    private fun raiseFault(reason: String) {
        if (fault != null) return
        val message = "The agent trace could not be written to $file: $reason"
        fault = message
        onFault(message)
    }

    /**
     * Rolls the active file aside once it passes [maxBytes], keeping [maxBackups].
     *
     * Same shape as the host's own `McpOperationLedger`, deliberately: an operator
     * looking at two rotated JSONL files from the same machine should not have to
     * learn two conventions to know which is current.
     */
    private fun rotateIfNeeded() {
        if (!Files.exists(file) || Files.size(file) < maxBytes) return
        runCatching {
            val oldest = directory.resolve("$FILE_NAME.$maxBackups")
            if (Files.exists(oldest)) Files.delete(oldest)
            for (i in maxBackups - 1 downTo 1) {
                val from = directory.resolve("$FILE_NAME.$i")
                if (Files.exists(from)) Files.move(from, directory.resolve("$FILE_NAME.${i + 1}"))
            }
            Files.move(file, directory.resolve("$FILE_NAME.1"))
        }
    }

    /**
     * Writes one resolved tool call.
     *
     * Takes the record rather than its parts, which is the whole redaction argument:
     * `InvocationRecord.argumentsPreview` has already been through `Redactor` at
     * capture, so there is no overload here that would accept a raw argument string
     * and no way to reach the file with one.
     *
     * [Outcome] is carried instead of the three-way decision the profile made,
     * because it is strictly more informative: it separates a call nobody was asked
     * about from one a person approved, and a grant from a plain allow, which the
     * decision alone cannot.
     */
    fun call(sessionId: String, record: InvocationRecord) =
        append { seq, at, atMillis ->
            TraceEvent(
                seq = seq,
                at = at,
                atMillis = atMillis,
                kind = TraceEvent.KIND_CALL,
                session = sessionId,
                tool = record.toolName,
                capability = record.capability.name,
                declaredCapability = record.declaredCapability?.name,
                outcome = record.outcome.name,
                arguments = record.argumentsPreview,
                waitedForOperatorMs = record.waitedForOperatorMillis,
                upstreamMs = record.upstreamMillis,
                totalMs = record.durationMillis,
                governedByHost = HostGovernanceGap.isGovernedByHost(record.toolName),
                detail = record.detail,
            )
        }

    /**
     * Notes that a verdict was reached but never reached the caller.
     *
     * Worth a line of its own. Observed live: an agent's client gives up after two
     * minutes, the operator answers at two minutes thirteen, and the refusal is
     * correct, recorded, and delivered to nobody. Without this the trace shows a
     * clean refusal and the agent's own transcript shows an unexplained timeout, and
     * whoever compares them concludes one of the two is lying.
     */
    fun undelivered(sessionId: String, toolName: String, reason: String) =
        append { seq, at, atMillis ->
            TraceEvent(
                seq = seq,
                at = at,
                atMillis = atMillis,
                kind = TraceEvent.KIND_UNDELIVERED,
                session = sessionId,
                tool = toolName,
                detail = reason,
            )
        }

    /** Session opened, closed, or moved to a different policy. */
    fun session(sessionId: String, detail: String) =
        append { seq, at, atMillis ->
            TraceEvent(
                seq = seq,
                at = at,
                atMillis = atMillis,
                kind = TraceEvent.KIND_SESSION,
                session = sessionId,
                detail = detail,
            )
        }

    /**
     * The last [count] lines, newest last, for anything that wants to show the trace
     * without reading a file that may have grown to megabytes.
     *
     * Empty rather than throwing when the file is absent, which is the normal state
     * before the first call of a session.
     */
    fun tail(count: Int): List<String> =
        runCatching {
            if (!Files.exists(file)) return emptyList()
            Files.readAllLines(file, StandardCharsets.UTF_8).takeLast(count)
        }.getOrElse { emptyList() }

    companion object {
        const val FILE_NAME = "agent-trace.jsonl"

        /** Smaller than the host's 10 MB: one line here is far shorter than one of its records. */
        const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024

        const val DEFAULT_MAX_BACKUPS = 3
    }
}
