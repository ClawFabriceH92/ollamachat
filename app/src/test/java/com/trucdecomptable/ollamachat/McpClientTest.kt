package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.mcp.McpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpClientTest {

    private lateinit var server: MockWebServer
    private var now = 0L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        McpClient.invalidate()
        now = 1_000L
        McpClient.clock = { now }
    }

    @After
    fun tearDown() {
        server.shutdown()
        McpClient.invalidate()
        McpClient.clock = { System.currentTimeMillis() }
    }

    private fun url() = server.url("/mcp").toString()

    private fun rpcOk(result: String, sessionId: String? = null): MockResponse =
        MockResponse()
            .setBody("""{"jsonrpc":"2.0","id":1,"result":$result}""")
            .setHeader("Content-Type", "application/json")
            .apply { sessionId?.let { setHeader("Mcp-Session-Id", it) } }

    private fun toolsResult() =
        """{"tools":[{"name":"meteo","description":"météo","inputSchema":{"type":"object","properties":{"ville":{"type":"string"}}}}]}"""

    /** initialize, notifications/initialized, then the actual call. */
    private fun drainHandshake(): Pair<RecordedRequest, RecordedRequest> =
        server.takeRequest() to server.takeRequest()

    @Test
    fun `tools are read from the JSON-RPC result envelope`() = runBlocking {
        server.enqueue(rpcOk("""{"protocolVersion":"2025-06-18"}"""))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(rpcOk(toolsResult()))

        val tools = McpClient.listTools(url()).getOrThrow()

        assertEquals(1, tools.size)
        assertEquals("meteo", tools[0].name)
        assertEquals("météo", tools[0].description)
        assertTrue(tools[0].inputSchema.contains("ville"))
    }

    @Test
    fun `the session id from initialize is replayed on every later request`() = runBlocking {
        server.enqueue(rpcOk("""{}""", sessionId = "sess-42"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(rpcOk(toolsResult()))

        McpClient.listTools(url()).getOrThrow()

        val (initialize, notification) = drainHandshake()
        val list = server.takeRequest()
        assertNull(initialize.getHeader("Mcp-Session-Id"))
        assertEquals("sess-42", notification.getHeader("Mcp-Session-Id"))
        assertEquals("sess-42", list.getHeader("Mcp-Session-Id"))
        assertTrue(initialize.body.readUtf8().contains("\"initialize\""))
    }

    @Test
    fun `a second call reuses the session and the cached tool list`() = runBlocking {
        server.enqueue(rpcOk("""{}""", sessionId = "sess-1"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(rpcOk(toolsResult()))

        McpClient.listTools(url()).getOrThrow()
        McpClient.listTools(url()).getOrThrow()

        // 3 requests, not 6: no second handshake, no second tools/list.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `the tool list is refreshed once the cache expires`() = runBlocking {
        server.enqueue(rpcOk("""{}""", sessionId = "sess-1"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(rpcOk(toolsResult()))
        server.enqueue(rpcOk(toolsResult()))

        McpClient.listTools(url()).getOrThrow()
        now += McpClient.TOOL_CACHE_TTL_MS + 1
        McpClient.listTools(url()).getOrThrow()

        // One extra tools/list, still a single handshake.
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `an expired session triggers one new handshake and succeeds`() = runBlocking {
        server.enqueue(rpcOk("""{}""", sessionId = "old"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setResponseCode(404).setBody("session not found"))
        server.enqueue(rpcOk("""{}""", sessionId = "new"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(rpcOk(toolsResult()))

        val tools = McpClient.listTools(url()).getOrThrow()

        assertEquals(1, tools.size)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `a JSON-RPC error surfaces as a failure`() = runBlocking {
        server.enqueue(rpcOk("""{}"""))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            MockResponse()
                .setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""")
                .setHeader("Content-Type", "application/json")
        )

        val result = McpClient.listTools(url())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Method not found"))
    }

    @Test
    fun `callTool flattens the content blocks`() = runBlocking {
        server.enqueue(rpcOk("""{}"""))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(rpcOk("""{"content":[{"type":"text","text":"18 °C"},{"type":"text","text":"vent 12"}]}"""))

        val output = McpClient.callTool(url(), "meteo", """{"ville":"Lyon"}""").getOrThrow()

        assertEquals("18 °C\nvent 12", output)
    }

    @Test
    fun `callTool reports a tool-side error`() {
        assertEquals(
            "Erreur de l'outil MCP : boom",
            McpClient.renderToolResult(
                JSONObject("""{"isError":true,"content":[{"type":"text","text":"boom"}]}""")
            )
        )
    }

    @Test
    fun `SSE framed replies are understood`() = runBlocking {
        server.enqueue(rpcOk("""{}"""))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            MockResponse()
                .setBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":${toolsResult()}}\n\n")
                .setHeader("Content-Type", "text/event-stream")
        )

        assertEquals(listOf("meteo"), McpClient.listTools(url()).getOrThrow().map { it.name })
    }

    @Test
    fun `malformed arguments do not break a tool call`() = runBlocking {
        server.enqueue(rpcOk("""{}"""))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(rpcOk("""{"content":[{"type":"text","text":"ok"}]}"""))

        McpClient.callTool(url(), "t", "pas du json").getOrThrow()

        drainHandshake()
        val call = server.takeRequest()
        val params = JSONObject(call.body.readUtf8()).getJSONObject("params")
        assertEquals(0, params.getJSONObject("arguments").length())
    }
}
