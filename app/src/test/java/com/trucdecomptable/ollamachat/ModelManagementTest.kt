package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelManagementTest {

    private lateinit var server: MockWebServer
    private val client = OllamaClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun url() = server.url("/").toString().trimEnd('/')

    @Test
    fun `pull reports progress and finishes`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"status":"pulling manifest"}
                {"status":"downloading","completed":50,"total":200}
                {"status":"downloading","completed":200,"total":200}
                {"status":"success"}
                """.trimIndent()
            )
        )

        val seen = mutableListOf<Float>()
        val result = client.pullModel(url(), "qwen3:8b") { seen.add(it.fraction) }

        assertTrue(result.isSuccess)
        assertEquals(listOf(0f, 0.25f, 1f, 0f), seen)

        val request = server.takeRequest()
        assertEquals("/api/pull", request.path)
        assertEquals("qwen3:8b", JSONObject(request.body.readUtf8()).getString("model"))
    }

    @Test
    fun `an error reported mid-stream fails the pull`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"status":"pulling manifest"}
                {"error":"model not found"}
                """.trimIndent()
            )
        )

        val result = client.pullModel(url(), "inexistant")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("model not found"))
    }

    @Test
    fun `an HTTP failure fails the pull`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(client.pullModel(url(), "qwen3").isFailure)
    }

    @Test
    fun `a malformed progress line does not abort the download`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"status":"downloading","completed":1,"total":2}
                ceci n'est pas du json
                {"status":"success"}
                """.trimIndent()
            )
        )
        assertTrue(client.pullModel(url(), "qwen3").isSuccess)
    }

    @Test
    fun `delete sends the model name and reports success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client.deleteModel(url(), "qwen3:8b")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/delete", request.path)
        assertEquals("qwen3:8b", JSONObject(request.body.readUtf8()).getString("model"))
    }

    @Test
    fun `deleting an unknown model fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = client.deleteModel(url(), "inconnu")
        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `progress fraction is safe when the total is unknown`() {
        assertEquals(0f, OllamaClient.PullProgress("downloading", 10, 0).fraction)
        assertEquals(1f, OllamaClient.PullProgress("downloading", 300, 200).fraction)
    }
}
