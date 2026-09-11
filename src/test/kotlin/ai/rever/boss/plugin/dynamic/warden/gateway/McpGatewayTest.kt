package ai.rever.boss.plugin.dynamic.warden.gateway

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end over real sockets, because the guarantees being tested are about
 * bytes on a wire. A mock of the HTTP layer would pass while the actual server
 * dropped a session header or answered in a shape agents read as a crash.
 *
 * The central assertion in most of these is negative: that a refused call was
 * never seen by the upstream at all. [FakeUpstream] records every request, so that
 * is a fact rather than an inference.
 */
class McpGatewayTest {
    private lateinit var upstream: FakeUpstream
    private lateinit var gateway: McpGateway
    private lateinit var endpoint: URI

    private val ledger = CopyOnWriteArrayList<InvocationRecord>()
    private val ids = AtomicLong(1)
    private val client: HttpClient = HttpClient.newHttpClient()

    private var profile: Profile = Profile.READ_ONLY
    private var approvalAnswer: ApprovalOutcome = ApprovalOutcome.APPROVED
    private val approvalsAsked = CopyOnWriteArrayList<String>()

    /** Lets a test hold the handler thread inside the record call. See the ordering tests. */
    private var onRecordHook: ((InvocationRecord) -> Unit)? = null

    private val undelivered = CopyOnWriteArrayList<Pair<String, String>>()

    private var judgeCommands = true

    @BeforeTest
    fun setUp() {
        upstream = FakeUpstream().start()
        gateway =
            McpGateway(
                upstream = upstream.uri,
                decide = { _, capability -> profile.decide(capability) },
                approve = { tool, _, _ ->
                    approvalsAsked.add(tool)
                    approvalAnswer
                },
                onRecord = {
                    ledger.add(it)
                    onRecordHook?.invoke(it)
                },
                onAnswerUndelivered = { tool, reason -> undelivered.add(tool to reason) },
                judgeShellCommands = { judgeCommands },
                nextRecordId = { ids.getAndIncrement() },
                hiddenCapabilities = { profile.hardDenied },
            )
        val port = gateway.start(0)
        endpoint = URI.create("http://127.0.0.1:$port/mcp")
    }

    @AfterTest
    fun tearDown() {
        gateway.stop()
        upstream.stop()
    }

    // ---- helpers -------------------------------------------------------------

    private fun post(body: String, sessionId: String? = null): HttpResponse<String> {
        val builder =
            HttpRequest.newBuilder(endpoint)
                .header("content-type", "application/json")
                .header("accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        sessionId?.let { builder.header("mcp-session-id", it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun callTool(name: String, args: String = "{}", id: Int = 1) =
        post("""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$args}}""")

    private fun listTools() = post("""{"jsonrpc":"2.0","id":9,"method":"tools/list","params":{}}""")

    private fun reachedUpstream() = upstream.requests.any { FakeUpstream.UPSTREAM_MARKER in it.body || it.toolName != null }

    private val lastRecord: InvocationRecord get() = ledger.last()

    // ---- forwarding ----------------------------------------------------------

    @Test
    fun `an allowed call reaches upstream and its real answer comes back`() {
        val response = callTool("list_tabs")
        assertEquals(200, response.statusCode())
        assertTrue(FakeUpstream.UPSTREAM_MARKER in response.body(), "gateway did not return the upstream answer")
        assertEquals(1, upstream.toolCalls.size)
        assertEquals(Outcome.ALLOWED, lastRecord.outcome)
    }

    @Test
    fun `a blocked call never reaches upstream`() {
        // The whole point. manage_tools is hard-denied under every profile.
        val response = callTool("manage_tools", """{"operation":"enable","names":["run_command"]}""")
        assertEquals(200, response.statusCode())
        assertFalse(FakeUpstream.UPSTREAM_MARKER in response.body(), "a blocked call was forwarded")
        assertTrue(upstream.toolCalls.isEmpty(), "upstream saw a call that should have been blocked")
        assertEquals(Outcome.BLOCKED, lastRecord.outcome)
    }

    @Test
    fun `a call the operator refuses never reaches upstream`() {
        approvalAnswer = ApprovalOutcome.REFUSED
        val response = callTool("run_command", """{"script":"rm -rf /"}""")
        assertFalse(FakeUpstream.UPSTREAM_MARKER in response.body())
        assertTrue(upstream.toolCalls.isEmpty(), "a refused call was forwarded anyway")
        assertEquals(Outcome.REFUSED, lastRecord.outcome)
    }

    @Test
    fun `a call the operator approves does reach upstream`() {
        // Not "ls", which CommandRisk now judges a read and lets through unasked.
        // A test that escalates has to use something that genuinely needs escalating.
        approvalAnswer = ApprovalOutcome.APPROVED
        val response = callTool("run_command", """{"script":"npm install"}""")
        assertTrue(FakeUpstream.UPSTREAM_MARKER in response.body())
        assertEquals(listOf("run_command"), approvalsAsked)
        assertEquals(Outcome.APPROVED, lastRecord.outcome)
    }

    @Test
    fun `a granted call is recorded distinctly from one a person approved`() {
        approvalAnswer = ApprovalOutcome.GRANTED
        callTool("run_command", """{"script":"npm install"}""")
        assertEquals(Outcome.GRANTED, lastRecord.outcome)
    }

    // ---- refusal shape -------------------------------------------------------

    @Test
    fun `a refusal is a tool error, not a transport error`() {
        // A JSON-RPC error or a non-200 reads to most clients as a broken server and
        // provokes a retry or a disconnect. isError inside a normal result is the
        // protocol's way of saying the tool ran and said no.
        val response = callTool("manage_tools")
        assertEquals(200, response.statusCode())
        val body = response.body()
        assertTrue(""""isError":true""" in body.replace(" ", ""), "refusal was not marked isError: $body")
        assertFalse(""""error""" in body, "refusal used a JSON-RPC error envelope: $body")
        assertTrue(""""jsonrpc":"2.0"""" in body.replace(" ", ""))
    }

    @Test
    fun `a refusal echoes the request id so the client can match it`() {
        // An unmatched id leaves the client waiting on a reply that never comes.
        val response = callTool("manage_tools", id = 4242)
        assertTrue(""""id":4242""" in response.body().replace(" ", ""), "refusal lost the request id: ${response.body()}")
    }

    @Test
    fun `a refusal explains itself rather than just saying no`() {
        val body = callTool("manage_tools").body()
        assertTrue(Capability.GOVERN.label in body, "refusal did not name the capability: $body")
    }

    // ---- classification ------------------------------------------------------

    @Test
    fun `an unclassified tool is escalated rather than allowed`() {
        // mystery_tool is not in the catalog. Under read-only it must ask, not pass.
        callTool("mystery_tool")
        assertEquals(listOf("mystery_tool"), approvalsAsked)
        assertEquals(Capability.UNKNOWN, lastRecord.capability)
    }

    @Test
    fun `an unclassified tool is escalated even under the most permissive profile`() {
        profile = Profile.FULL
        callTool("mystery_tool")
        assertEquals(listOf("mystery_tool"), approvalsAsked, "Full access silently allowed an unknown tool")
    }

    @Test
    fun `read-only allows reads without asking anyone`() {
        callTool("editor_read_file", """{"path":"/tmp/x"}""")
        assertTrue(approvalsAsked.isEmpty(), "a read raised a dialog under read-only")
        assertEquals(Outcome.ALLOWED, lastRecord.outcome)
    }

    // ---- tools/list filtering ------------------------------------------------

    @Test
    fun `hidden capabilities are removed from tools-list entirely`() {
        // Not refused when called: absent, so the agent never plans around it.
        val body = listTools().body()
        assertFalse("manage_tools" in body, "a hard-denied tool was advertised: $body")
        assertTrue("run_command" in body, "filtering removed more than it should")
    }

    @Test
    fun `tools-list is untouched when nothing is hidden`() {
        profile = Profile.READ_ONLY.copy(hardDenied = emptySet())
        val body = listTools().body()
        assertTrue("manage_tools" in body, "tools were filtered with an empty hidden set")
        assertEquals(FakeUpstream.DEFAULT_TOOLS.size, Regex(""""name":"""").findAll(body.replace(" ", "")).count())
    }

    @Test
    fun `tools-list filtering does not disturb the rest of the envelope`() {
        val body = listTools().body().replace(" ", "")
        assertTrue(""""jsonrpc":"2.0"""" in body, "envelope lost jsonrpc: $body")
        assertTrue(""""id":9""" in body, "envelope lost the request id: $body")
    }

    // ---- passthrough ---------------------------------------------------------

    @Test
    fun `non-tool methods pass through untouched and are not recorded`() {
        val response = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        assertEquals(200, response.statusCode())
        assertTrue("fake" in response.body(), "initialize did not reach upstream")
        assertTrue(ledger.isEmpty(), "a non-tool method was written to the invocation ledger")
    }

    @Test
    fun `the session header survives in both directions`() {
        // MCP sessions die without it, and the failure looks like the gateway being
        // flaky rather than dropping one header.
        val response = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""", sessionId = "abc-123")
        assertEquals("abc-123", upstream.requests.first().sessionId, "session id was not forwarded upstream")
        assertEquals(
            FakeUpstream.SESSION_ID,
            response.headers().firstValue("mcp-session-id").orElse(null),
            "upstream session id was not returned to the client",
        )
    }

    @Test
    fun `a refusal still carries the session header back`() {
        // A refused call must not look like a dead session.
        val response = callTool("manage_tools").let { it }
        assertNull(response.headers().firstValue("mcp-session-id").orElse(null))

        val withSession =
            client.send(
                HttpRequest.newBuilder(endpoint)
                    .header("content-type", "application/json")
                    .header("mcp-session-id", "keep-me")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"manage_tools"}}""",
                        ),
                    ).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals("keep-me", withSession.headers().firstValue("mcp-session-id").orElse(null))
    }

    @Test
    fun `upstream status codes are passed through rather than masked`() {
        upstream.statusOverride = 503
        assertEquals(503, callTool("list_tabs").statusCode(), "gateway masked an upstream failure")
    }

    // ---- outcome fidelity ----------------------------------------------------

    @Test
    fun `a tool that runs and fails is recorded as failed, not as allowed`() {
        // A blocked call and a failing one look identical to the agent. They must not
        // to the operator, or the ledger cannot answer "did my policy do this?"
        upstream.failToolCalls = true
        callTool("list_tabs")
        assertEquals(Outcome.FAILED, lastRecord.outcome)
        assertEquals(1, upstream.toolCalls.size, "the call should still have been forwarded")
    }

    @Test
    fun `records carry a redacted preview, never raw arguments`() {
        val token = "C".repeat(40)
        callTool("editor_read_file", """{"path":"/tmp/x","token":"$token"}""")
        assertFalse(token in lastRecord.argumentsPreview, "raw secret reached the ledger: ${lastRecord.argumentsPreview}")
    }

    @Test
    fun `records carry ids, timing and the resolved capability`() {
        callTool("list_tabs")
        callTool("editor_read_file")
        assertEquals(listOf(1L, 2L), ledger.map { it.id }, "record ids are not sequential")
        assertEquals(Capability.INSPECT, ledger[0].capability)
        assertEquals(Capability.READ_CONTENT, ledger[1].capability)
        assertTrue(ledger.all { (it.durationMillis ?: -1) >= 0 }, "a record has no duration")
        assertTrue(ledger.all { it.atMillis > 0 })
    }

    // ---- robustness ----------------------------------------------------------

    @Test
    fun `a malformed body is relayed rather than crashing the gateway`() {
        // Deciding what a broken message means is upstream's job, not the gateway's.
        // The gateway must not become the thing that breaks the session.
        val response = post("not json at all")
        assertTrue(response.statusCode() in 200..599)
        val after = callTool("list_tabs")
        assertTrue(FakeUpstream.UPSTREAM_MARKER in after.body(), "gateway stopped working after a malformed message")
    }

    @Test
    fun `a tools-call with no tool name is relayed rather than intercepted`() {
        post("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{}}""")
        assertTrue(ledger.isEmpty(), "a nameless call was recorded as an invocation")
        assertEquals(1, upstream.requests.size, "a nameless call was swallowed instead of relayed")
    }

    @Test
    fun `an unsupported http method is rejected without touching upstream`() {
        val response =
            client.send(
                HttpRequest.newBuilder(endpoint).method("PUT", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(405, response.statusCode())
        assertTrue(upstream.requests.isEmpty())
    }

    @Test
    fun `concurrent calls are all recorded exactly once`() {
        // The server runs a thread pool; a shared counter or list that was not
        // thread-safe would drop or duplicate entries here.
        val threads = (1..24).map { i -> Thread { callTool(if (i % 2 == 0) "list_tabs" else "editor_read_file") } }
        threads.forEach { it.start() }
        threads.forEach { it.join(20_000) }
        assertEquals(24, ledger.size, "records lost or duplicated under concurrency")
        assertEquals(24, ledger.map { it.id }.toSet().size, "duplicate record ids issued")
    }

    // ---- the record outlives the caller --------------------------------------

    /**
     * Asserts an ordering rather than a value, and the reason is a live failure.
     *
     * A stopped call used to be written to the client first and recorded second. An
     * approval takes as long as a person takes, and MCP clients give up well before
     * this gateway does, so by the time the operator answered, the socket was often
     * gone. Writing to it threw, the throw unwound past the record call, and the one
     * entry the operator most needed - a shell command they had just refused - was
     * absent from the panel and from the exported report. Seen against BossConsole
     * 9.5.11 with a real agent whose client timed out at 120s.
     *
     * Holding the handler inside the record call proves the order without depending
     * on a broken socket, which is what makes this deterministic. The short negative
     * wait is the only sleep in the suite and it is what the assertion is made of:
     * an answer that has not arrived while the record is still being taken is the
     * property under test.
     */
    private fun assertRecordedBeforeAnswering(tool: String) {
        val recordTaken = CountDownLatch(1)
        val releaseRecord = CountDownLatch(1)
        val clientDone = CountDownLatch(1)
        onRecordHook = {
            recordTaken.countDown()
            releaseRecord.await(10, TimeUnit.SECONDS)
        }

        thread {
            runCatching { callTool(tool) }
            clientDone.countDown()
        }

        assertTrue(recordTaken.await(10, TimeUnit.SECONDS), "the stopped call was never recorded at all")
        assertFalse(
            clientDone.await(500, TimeUnit.MILLISECONDS),
            "the answer was written before the record was taken, so a client that hung up loses the entry",
        )
        releaseRecord.countDown()
        assertTrue(clientDone.await(10, TimeUnit.SECONDS), "the gateway never answered")
        assertEquals(tool, lastRecord.toolName)
        assertFalse(reachedUpstream(), "a stopped call must not reach upstream")
    }

    @Test
    fun `an operator refusal is recorded before the agent is answered`() {
        profile = Profile.READ_ONLY
        approvalAnswer = ApprovalOutcome.REFUSED
        assertRecordedBeforeAnswering("run_command")
        assertEquals(Outcome.REFUSED, lastRecord.outcome)
    }

    @Test
    fun `a hard-denied call is recorded before the agent is answered`() {
        profile = Profile.READ_ONLY
        assertRecordedBeforeAnswering("manage_tools")
        assertEquals(Outcome.BLOCKED, lastRecord.outcome)
    }

    // ---- judging the command, not the tool -----------------------------------

    @Test
    fun `a read-only shell command runs without asking anyone`() {
        // The feature, end to end over a socket. Under Read only this used to raise a
        // dialog for every status check an agent made, which is what teaches an
        // operator to approve without reading.
        profile = Profile.READ_ONLY
        val response = callTool("run_command", """{"script":"git status"}""")
        assertTrue(FakeUpstream.UPSTREAM_MARKER in response.body(), "a harmless command was not forwarded")
        assertTrue(approvalsAsked.isEmpty(), "the operator was asked about a read-only command")
        assertEquals(Outcome.ALLOWED, lastRecord.outcome)
    }

    @Test
    fun `a dangerous shell command still asks, under the same profile`() {
        // The other half. Without this the test above only proves the gate is off.
        profile = Profile.READ_ONLY
        approvalAnswer = ApprovalOutcome.REFUSED
        callTool("run_command", """{"script":"rm -rf /"}""")
        assertEquals(listOf("run_command"), approvalsAsked)
        assertEquals(Outcome.REFUSED, lastRecord.outcome)
    }

    @Test
    fun `a call that was judged says so in the record`() {
        // An allowed run_command under a profile that escalates execution is a
        // contradiction on the face of it. The record has to carry why.
        profile = Profile.READ_ONLY
        callTool("run_command", """{"script":"git status"}""")
        assertEquals(Capability.EXECUTE, lastRecord.declaredCapability, "the row does not say what it was called as")
        assertEquals(Capability.READ_CONTENT, lastRecord.capability)
        assertTrue("git status" in (lastRecord.detail ?: ""), "the reason does not name the command")
    }

    @Test
    fun `judging can be turned off, and then everything asks again`() {
        // It is a relaxation, so it has to be defeatable. With it off the older, noisier
        // behaviour returns exactly.
        judgeCommands = false
        profile = Profile.READ_ONLY
        approvalAnswer = ApprovalOutcome.REFUSED
        callTool("run_command", """{"script":"git status"}""")
        assertEquals(listOf("run_command"), approvalsAsked)
        assertEquals(Outcome.REFUSED, lastRecord.outcome)
    }

    // ---- phases --------------------------------------------------------------

    @Test
    fun `an escalated call records how long the operator took, separately from upstream`() {
        // A single duration cannot answer the question the operator actually asks
        // when something was slow, which is whether the wait was theirs.
        profile = Profile.READ_ONLY
        approvalAnswer = ApprovalOutcome.APPROVED
        callTool("run_command")
        val r = lastRecord
        assertNotNull(r.waitedForOperatorMillis, "the approval wait was not measured")
        assertNotNull(r.upstreamMillis, "the upstream leg was not measured")
    }

    @Test
    fun `a call nobody was asked about has no operator wait at all`() {
        // Null, not zero. "Not asked" and "asked and answered instantly" are different
        // facts and anything aggregating the trace has to be able to tell them apart.
        profile = Profile.READ_ONLY
        callTool("list_tabs")
        assertNull(lastRecord.waitedForOperatorMillis)
        assertNotNull(lastRecord.upstreamMillis)
    }

    @Test
    fun `a refused call measures the wait but never the upstream it did not reach`() {
        profile = Profile.READ_ONLY
        approvalAnswer = ApprovalOutcome.REFUSED
        callTool("run_command")
        assertNotNull(lastRecord.waitedForOperatorMillis)
        assertNull(lastRecord.upstreamMillis, "a refused call reported time upstream it never spent")
    }

    @Test
    fun `a verdict delivered normally reports nothing undelivered`() {
        // The negative half of the pair below. Without it, a hook that fired on every
        // call would look identical to one that fired on the right ones.
        profile = Profile.READ_ONLY
        approvalAnswer = ApprovalOutcome.REFUSED
        callTool("run_command")
        assertTrue(undelivered.isEmpty(), "a delivered answer was reported as undelivered")
    }

    @Test
    fun `stopping the gateway releases its port`() {
        val port = gateway.boundPort
        gateway.stop()
        assertNull(gateway.boundPort)
        val rebound = McpGateway(upstream.uri, { _, _ -> Decision.Allow }, { _, _, _ -> ApprovalOutcome.APPROVED }, {}, { 1 })
        assertEquals(port, rebound.start(port!!), "port was not released, so a reload would silently move the endpoint")
        rebound.stop()
    }
}
