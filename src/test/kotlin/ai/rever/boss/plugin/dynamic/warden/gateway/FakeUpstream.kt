package ai.rever.boss.plugin.dynamic.warden.gateway

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.Executors

/** One request as the fake upstream saw it, so tests can assert on what was forwarded. */
data class SeenRequest(
    val method: String,
    val body: String,
    val sessionId: String?,
    val accept: String?,
    val contentType: String?,
) {
    val toolName: String?
        get() = Regex(""""name"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
}

/**
 * A stand-in for BOSS's MCP endpoint.
 *
 * The gateway's most important guarantee is negative - that a refused call never
 * reaches the real server - and a negative cannot be asserted against a live BOSS
 * without trusting that nothing else on the machine made the call. Recording every
 * request here turns "was it blocked?" into a fact about a list.
 *
 * Deliberately not a real MCP implementation. It answers the three methods the
 * gateway distinguishes and echoes the session header, which is the entire
 * contract the gateway depends on.
 */
class FakeUpstream(
    private val toolNames: List<String> = DEFAULT_TOOLS,
) {
    private val received: MutableList<SeenRequest> = Collections.synchronizedList(mutableListOf())
    private lateinit var server: HttpServer

    /** Set to make every tools/call answer with isError, exercising the FAILED path. */
    @Volatile
    var failToolCalls: Boolean = false

    /** Set to return a non-200, exercising status passthrough. */
    @Volatile
    var statusOverride: Int? = null

    val requests: List<SeenRequest> get() = received.toList()

    val toolCalls: List<SeenRequest> get() = requests.filter { """"method":"tools/call"""" in it.body.replace(" ", "") }

    fun clear() = received.clear()

    lateinit var uri: URI
        private set

    fun start(): FakeUpstream {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/mcp") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            received.add(
                SeenRequest(
                    method = exchange.requestMethod,
                    body = body,
                    sessionId = exchange.requestHeaders.getFirst("mcp-session-id"),
                    accept = exchange.requestHeaders.getFirst("accept"),
                    contentType = exchange.requestHeaders.getFirst("content-type"),
                ),
            )
            val response = respondTo(body)
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("content-type", "application/json")
            exchange.responseHeaders.add("mcp-session-id", SESSION_ID)
            exchange.sendResponseHeaders(statusOverride ?: 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        uri = URI.create("http://127.0.0.1:${server.address.port}/mcp")
        return this
    }

    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    private fun respondTo(body: String): String {
        val id = Regex(""""id"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1) ?: "1"
        val compact = body.replace(" ", "")
        return when {
            """"method":"tools/list"""" in compact -> {
                val tools = toolNames.joinToString(",") { """{"name":"$it","description":"d","inputSchema":{}}""" }
                """{"jsonrpc":"2.0","id":$id,"result":{"tools":[$tools]}}"""
            }
            """"method":"tools/call"""" in compact ->
                """{"jsonrpc":"2.0","id":$id,"result":""" +
                    """{"content":[{"type":"text","text":"$UPSTREAM_MARKER"}],"isError":$failToolCalls}}"""
            else ->
                """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"fake"}}}"""
        }
    }

    companion object {
        const val SESSION_ID = "fake-session-1234"

        /** Text only the real upstream can produce, so "was this forwarded?" is unambiguous. */
        const val UPSTREAM_MARKER = "reached-upstream"

        val DEFAULT_TOOLS =
            listOf("list_tabs", "editor_read_file", "run_command", "browser_run_js", "manage_tools", "mystery_tool")
    }
}
