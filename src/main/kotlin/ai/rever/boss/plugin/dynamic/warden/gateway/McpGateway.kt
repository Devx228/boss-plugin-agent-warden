package ai.rever.boss.plugin.dynamic.warden.gateway

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

private val logger = BossLogger.forComponent("McpGateway")

private val gatewayJson = Json { ignoreUnknownKeys = true }

/**
 * An MCP endpoint that stands in front of BOSS's own, so that every tool call an
 * agent makes passes one point that can record it, refuse it, or put it in front
 * of the operator.
 *
 * **Why the wire and not the registry.** `McpToolRegistry` exposes `invoke`,
 * `getAllTools` and `setToolEnabled` in-process, and intercepting there would be
 * less code. It would also cover the wrong half of the surface: the tools worth
 * governing are BossTerm's built-ins - `run_command`, `send_input`,
 * `browser_run_js` - and those are served by that plugin's own MCP server rather
 * than registered with the host. Sitting on the JSON-RPC wire covers
 * plugin-contributed and built-in tools identically, and needs cooperation from
 * neither. Verified against a live endpoint: `tools/list` through this gateway
 * returns all 30 tools a direct connection returns.
 *
 * **No dependencies.** `com.sun.net.httpserver` and `java.net.http` are JDK
 * built-ins. The plugin jar bundles only its own classes, so any other library
 * would resolve at compile time and be missing at runtime, which is the failure
 * BOSS's own notes describe as making a plugin unloadable on every host.
 *
 * The gateway is deliberately ignorant of Compose, storage and the panel. It is
 * handed lambdas and reports what it did, which is what makes it testable without
 * a running BOSS.
 */
class McpGateway(
    private val upstream: URI,
    /** Synchronous, because a profile lookup must not be able to stall a call. */
    private val decide: (toolName: String, capability: Capability) -> Decision,
    /**
     * Suspends while the operator looks at a dialog, or returns immediately when a
     * live grant already covers the capability. The outcomes stay distinct because
     * an audit trail that cannot tell "a person said yes to this call" from "this
     * fell under a standing permission" is not an audit trail.
     */
    private val approve: suspend (toolName: String, capability: Capability, preview: String) -> ApprovalOutcome,
    private val onRecord: (InvocationRecord) -> Unit,
    private val nextRecordId: () -> Long,
    /** Capabilities filtered out of `tools/list`, so the agent is never told they exist. */
    private val hiddenCapabilities: () -> Set<Capability> = { emptySet() },
) {
    private var server: HttpServer? = null

    private val http: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    val boundPort: Int? get() = server?.address?.port

    /**
     * Binds to [preferredPort], or to an ephemeral port if that one is taken, and
     * returns the port actually bound.
     *
     * Falling back rather than failing is deliberate. A stale gateway from a
     * previous run, or a second BOSS window, must not leave the operator with a
     * plugin that silently does nothing. The panel shows the real port.
     */
    fun start(preferredPort: Int): Int {
        stop()
        val bound =
            runCatching { HttpServer.create(InetSocketAddress(LOOPBACK, preferredPort), 0) }
                .getOrElse { HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0) }
        bound.createContext("/mcp") { exchange -> handle(exchange) }
        // Bounded pool. An agent pipelines calls, and the default single-threaded
        // executor would queue every other request behind an open approval dialog.
        bound.executor = Executors.newFixedThreadPool(WORKER_THREADS)
        bound.start()
        server = bound
        logger.info(
            LogCategory.NETWORK,
            "MCP gateway listening",
            mapOf("port" to bound.address.port, "upstream" to upstream.toString()),
        )
        return bound.address.port
    }

    fun stop() {
        server?.let {
            it.stop(0)
            logger.info(LogCategory.NETWORK, "MCP gateway stopped", emptyMap())
        }
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        try {
            when (exchange.requestMethod.uppercase()) {
                "POST" -> handlePost(exchange)
                // GET carries the server-to-client notification stream and DELETE ends a
                // session. Neither can contain a tool call, so both relay untouched.
                "GET", "DELETE" -> relayRaw(exchange)
                else -> respond(exchange, STATUS_METHOD_NOT_ALLOWED, emptyMap(), ByteArray(0))
            }
        } catch (e: Exception) {
            logger.error(LogCategory.NETWORK, "Gateway request failed", emptyMap(), e)
            runCatching { respond(exchange, STATUS_BAD_GATEWAY, emptyMap(), "gateway error".toByteArray()) }
        } finally {
            exchange.close()
        }
    }

    private fun handlePost(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes()
        val message = parseObject(body)
        val method = message?.stringOrNull("method")

        if (method == "tools/call") {
            val params = message["params"] as? JsonObject
            val toolName = params?.stringOrNull("name")
            if (toolName != null) {
                interceptToolCall(exchange, body, message, toolName, params["arguments"])
                return
            }
        }

        val response = forward(body, exchange)
        val payload = if (method == "tools/list") filterToolList(response.body()) else response.body()
        respond(exchange, response.statusCode(), passthroughHeaders(response), payload)
    }

    private fun interceptToolCall(
        exchange: HttpExchange,
        body: ByteArray,
        message: JsonObject,
        toolName: String,
        arguments: JsonElement?,
    ) {
        val capability = ToolCatalog.capabilityOf(toolName)
        val preview = Redactor.preview(arguments)
        val id = nextRecordId()
        val startedAt = System.currentTimeMillis()

        val allowedOutcome: Outcome =
            when (val decision = decide(toolName, capability)) {
                is Decision.Deny -> {
                    refuse(exchange, message, decision.reason)
                    record(id, startedAt, toolName, capability, Outcome.BLOCKED, preview, decision.reason)
                    return
                }

                is Decision.Ask -> {
                    // runBlocking is correct here rather than a shortcut. This is a pool
                    // thread the gateway owns, the call genuinely cannot proceed until a
                    // person answers, and MCP has no "pending" reply to send in the interim.
                    val approval = runBlocking { approve(toolName, capability, preview) }
                    if (!approval.allowed) {
                        refuse(exchange, message, REFUSED_BY_OPERATOR)
                        record(id, startedAt, toolName, capability, Outcome.REFUSED, preview, REFUSED_BY_OPERATOR)
                        return
                    }
                    approval.toLedgerOutcome()
                }

                is Decision.Allow -> Outcome.ALLOWED
            }

        val response = forward(body, exchange)
        val outcome = if (looksLikeToolError(response.body())) Outcome.FAILED else allowedOutcome
        record(id, startedAt, toolName, capability, outcome, preview, null)
        respond(exchange, response.statusCode(), passthroughHeaders(response), response.body())
    }

    /**
     * Answers the agent with a tool-level error rather than a transport one.
     *
     * The distinction matters. A JSON-RPC error reads to most clients as a broken
     * server and provokes a retry or a disconnect. `isError` inside an otherwise
     * normal result is the protocol's way of saying the tool ran and said no, which
     * is what actually happened, and which agents handle by trying something else.
     */
    private fun refuse(exchange: HttpExchange, message: JsonObject, reason: String) {
        val payload =
            buildJsonObject {
                put("jsonrpc", "2.0")
                message["id"]?.let { put("id", it) }
                putJsonObject("result") {
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", reason)
                            },
                        )
                    }
                    put("isError", true)
                }
            }
        val headers = mutableMapOf("content-type" to "application/json")
        exchange.requestHeaders.getFirst("mcp-session-id")?.let { headers["mcp-session-id"] = it }
        respond(exchange, STATUS_OK, headers, payload.toString().toByteArray())
    }

    /** Drops hidden-capability tools, so a refused tool is absent rather than advertised. */
    private fun filterToolList(body: ByteArray): ByteArray {
        val hidden = hiddenCapabilities()
        if (hidden.isEmpty()) return body
        val parsed = parseObject(body) ?: return body
        val result = parsed["result"] as? JsonObject ?: return body
        val tools = result["tools"] as? JsonArray ?: return body
        val kept =
            tools.filter { tool ->
                val name = (tool as? JsonObject)?.stringOrNull("name")
                name == null || ToolCatalog.capabilityOf(name) !in hidden
            }
        if (kept.size == tools.size) return body
        val rebuilt =
            buildJsonObject {
                parsed.forEach { (key, value) -> if (key != "result") put(key, value) }
                putJsonObject("result") {
                    result.forEach { (key, value) -> if (key != "tools") put(key, value) }
                    put("tools", JsonArray(kept))
                }
            }
        return rebuilt.toString().toByteArray()
    }

    private fun looksLikeToolError(body: ByteArray): Boolean {
        val result = parseObject(body)?.get("result") as? JsonObject ?: return false
        return (result["isError"] as? JsonPrimitive)?.content == "true"
    }

    private fun forward(body: ByteArray, exchange: HttpExchange): HttpResponse<ByteArray> {
        val builder =
            HttpRequest.newBuilder(upstream)
                .timeout(Duration.ofMinutes(UPSTREAM_TIMEOUT_MINUTES))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        copyRequestHeaders(exchange, builder)
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun relayRaw(exchange: HttpExchange) {
        val builder = HttpRequest.newBuilder(upstream).timeout(Duration.ofMinutes(STREAM_TIMEOUT_MINUTES))
        if (exchange.requestMethod.equals("DELETE", ignoreCase = true)) builder.DELETE() else builder.GET()
        copyRequestHeaders(exchange, builder)
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        passthroughHeaders(response).forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        // Zero means chunked with unknown length, which is what an event stream needs.
        exchange.sendResponseHeaders(response.statusCode(), 0)
        response.body().use { upstreamBody -> exchange.responseBody.use { upstreamBody.copyTo(it) } }
    }

    private fun copyRequestHeaders(exchange: HttpExchange, builder: HttpRequest.Builder) {
        FORWARDED_HEADERS.forEach { name ->
            exchange.requestHeaders.getFirst(name)?.let { builder.header(name, it) }
        }
    }

    private fun passthroughHeaders(response: HttpResponse<*>): Map<String, String> =
        FORWARDED_HEADERS.mapNotNull { name ->
            response.headers().firstValue(name).orElse(null)?.let { name to it }
        }.toMap()

    private fun respond(exchange: HttpExchange, status: Int, headers: Map<String, String>, body: ByteArray) {
        headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1L else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
    }

    private fun record(
        id: Long,
        startedAt: Long,
        toolName: String,
        capability: Capability,
        outcome: Outcome,
        preview: String,
        detail: String?,
    ) {
        onRecord(
            InvocationRecord(
                id = id,
                atMillis = startedAt,
                toolName = toolName,
                capability = capability,
                outcome = outcome,
                argumentsPreview = preview,
                detail = detail,
                durationMillis = System.currentTimeMillis() - startedAt,
            ),
        )
    }

    private fun parseObject(body: ByteArray): JsonObject? =
        runCatching { gatewayJson.parseToJsonElement(body.decodeToString()) as? JsonObject }.getOrNull()

    /** `jsonPrimitive.content` throws on a JSON null, and MCP messages contain them legitimately. */
    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val WORKER_THREADS = 8
        const val STATUS_OK = 200
        const val STATUS_METHOD_NOT_ALLOWED = 405
        const val STATUS_BAD_GATEWAY = 502
        const val UPSTREAM_TIMEOUT_MINUTES = 5L
        const val STREAM_TIMEOUT_MINUTES = 30L
        const val REFUSED_BY_OPERATOR = "Refused by the operator."

        /**
         * Only these cross the gateway. An allow-list rather than copy-everything:
         * `Host`, `Content-Length` and the connection headers must be recomputed per
         * hop, and forwarding them produces requests that fail in ways that look like
         * the gateway itself is broken.
         */
        val FORWARDED_HEADERS =
            listOf("content-type", "accept", "mcp-session-id", "mcp-protocol-version", "last-event-id")
    }
}
