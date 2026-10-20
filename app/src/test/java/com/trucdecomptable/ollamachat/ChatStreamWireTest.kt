package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.ollama.ChatErrorCode
import com.trucdecomptable.ollamachat.data.ollama.OllamaChatMessage
import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `an answer stopped by num_predict says so`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"message":{"role":"assistant","content":"Le début"},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"done_reason":"length","eval_count":5}
                """.trimIndent()
            )
        )
        val result = OllamaClient().chatStream(url(), "qwen3", question)

        assertTrue(result.truncated)
        assertTrue(result.statsLine().orEmpty().contains("tronquée"))
    }

    @Test
    fun `chatOnce gathers a streamed answer and asks for a stream`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"message":{"role":"assistant","content":"Bonjour "},"done":false}
                {"message":{"role":"assistant","content":"le monde"},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}
                """.trimIndent()
            )
        )
        val result = OllamaClient().chatOnce(url(), "qwen3", question, think = false)

        assertEquals("Bonjour le monde", result.getOrNull())
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        // Every line re-arms the read timeout; one whole response had to beat it.
        assertTrue(sent.getBoolean("stream"))
        assertFalse(sent.getBoolean("think"))
    }

    @Test
    fun `chatOnce refuses an answer cut by num_predict`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"message":{"role":"assistant","content":"Résumé coupé"},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"done_reason":"length"}
                """.trimIndent()
            )
        )
        assertTrue(OllamaClient().chatOnce(url(), "qwen3", question).isFailure)
    }

    @Test
    fun `chatOnce refuses a summary whose input filled the window`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"message":{"role":"assistant","content":"Résumé"},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":8100,"eval_count":200}
                """.trimIndent()
            )
        )
        // Ollama made room by dropping the oldest messages: they were never read.
        val result = OllamaClient().chatOnce(
            url(), "qwen3", question,
            options = mapOf("num_ctx" to 8192, "num_predict" to 1024),
        )
        assertTrue(result.exceptionOrNull() is OllamaClient.WindowFull)
    }

    @Test
    fun `chatOnce accepts a summary that fitted, however large num_predict was`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"message":{"role":"assistant","content":"Résumé"},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":7300,"eval_count":600}
                """.trimIndent()
            )
        )
        // 7 300 + 600 fits in 8 192: nothing was dropped, whatever the cap.
        val result = OllamaClient().chatOnce(
            url(), "qwen3", question,
            options = mapOf("num_ctx" to 8192, "num_predict" to 1024),
        )
        assertEquals("Résumé", result.getOrNull())
    }

    @Test
    fun `stop interrupts chatOnce`() = runBlocking {
        val line = "{\"message\":{\"role\":\"assistant\",\"content\":\"a\"},\"done\":false}\n"
        // About three minutes of body: the stop has to be what ends it.
        server.enqueue(MockResponse().setBody(line.repeat(50)).throttleBody(16, 1, TimeUnit.SECONDS))
        val client = OllamaClient()
        // Once the server has the request the call is registered: it is set
        // before execute(), so the stop cannot arrive too early.
        val stopper = Thread {
            server.takeRequest(10, TimeUnit.SECONDS)
            client.cancelActiveStream()
        }
        stopper.start()
        val result = client.chatOnce(url(), "qwen3", question)
        stopper.join()

        assertTrue(result.exceptionOrNull() is OllamaClient.Cancelled)
    }

    @Test
    fun `the warm-up loads the model with the chat's window`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(OllamaClient().warmUp(url(), "qwen3", "30m", numCtx = 8192).isSuccess)

        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("30m", sent.getString("keep_alive"))
        assertEquals(8192, sent.getJSONObject("options").getInt("num_ctx"))
    }

    @Test
    fun `a capability answer that is not JSON fails instead of reading as no capability`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>portail captif</html>"))
        assertTrue(OllamaClient().modelCapabilities(url(), "qwen3").isFailure)
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

    @Test
    fun `a check that times out is a dead network, not a stop`() = runBlocking {
        // Both the answer and the check meet silence: the check's own time
        // limit cancels it, which must not read as the owner pressing Stop.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OllamaClient(
            streamHttp = OllamaClient.streamClientBuilder().readTimeout(1, TimeUnit.SECONDS).build(),
        )
        val result = client.chatStream(url(), "qwen3", question)

        assertFalse(result.cancelled)
        assertEquals(ChatErrorCode.CONNECT_TIMEOUT, result.error?.code)
    }
}
