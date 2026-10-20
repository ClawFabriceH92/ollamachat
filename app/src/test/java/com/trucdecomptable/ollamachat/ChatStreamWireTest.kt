package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.ollama.ChatErrorCode
import com.trucdecomptable.ollamachat.data.ollama.OllamaChatMessage
import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** chatStream against a real socket: what a parser test cannot show. */
class ChatStreamWireTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun url() = server.url("/").toString().trimEnd('/')

    private val question = listOf(OllamaChatMessage(role = "user", content = "q"))

    @Test
    fun `tool calls spread over several lines all survive`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"web_search","arguments":{"query":"a"}}}]},"done":false}
                {"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"calculate","arguments":{"expression":"2+2"}}}]},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}
                """.trimIndent()
            )
        )
        val result = OllamaClient().chatStream(url(), "qwen3", question)

        // Keeping only the latest line used to drop every call but the last.
        assertEquals(listOf("web_search", "calculate"), result.toolCalls.map { it.name })
    }

    @Test
    fun `a server that takes the request and never answers is not a network failure`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        // The check made before saying so: the server does answer.
        server.enqueue(MockResponse().setBody("{\"version\":\"0.6.0\"}"))
        // The production client, interceptor included, with a short read timeout.
        val client = OllamaClient(
            streamHttp = OllamaClient.streamClientBuilder().readTimeout(1, TimeUnit.SECONDS).build(),
        )
        val result = client.chatStream(url(), "qwen3", question)

        assertEquals(ChatErrorCode.NO_FIRST_BYTE, result.error?.code)
    }

    @Test
    fun `a connection that took the request but leads nowhere is not called a working network`() = runBlocking {
        // A pooled connection the tunnel stopped carrying: the request is
        // written, nothing ever comes back, and the server cannot be reached.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        // OkHttp retries a dropped connection: every attempt finds it dropped.
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        val client = OllamaClient(
            streamHttp = OllamaClient.streamClientBuilder().readTimeout(1, TimeUnit.SECONDS).build(),
        )
        val result = client.chatStream(url(), "qwen3", question)

        assertEquals(ChatErrorCode.CONNECT_TIMEOUT, result.error?.code)
    }
}
