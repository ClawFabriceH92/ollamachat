package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.ollama.OllamaChatMessage
import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import com.trucdecomptable.ollamachat.data.ollama.ToolCall
import com.trucdecomptable.ollamachat.data.ollama.ToolDef
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaClientParsingTest {

    private val client = OllamaClient()

    @Test
    fun `normalizeBaseUrl adds the scheme and trims the slash`() {
        assertEquals("http://192.168.1.5:11434", OllamaClient.normalizeBaseUrl("192.168.1.5:11434"))
        assertEquals("http://192.168.1.5:11434", OllamaClient.normalizeBaseUrl(" 192.168.1.5:11434/ "))
        assertEquals("https://host", OllamaClient.normalizeBaseUrl("https://host/"))
        assertEquals("", OllamaClient.normalizeBaseUrl("   "))
    }

    @Test
    fun `parseChunk reads content, reasoning and completion`() {
        val chunk = client.parseChunk(
            JSONObject("""{"message":{"content":"salut","thinking":"hmm"},"done":false}""")
        )
        assertEquals("salut", chunk.content)
        assertEquals("hmm", chunk.thinking)
        assertFalse(chunk.done)
        assertNull(chunk.error)
    }

    @Test
    fun `parseChunk surfaces a server error`() {
        val chunk = client.parseChunk(JSONObject("""{"error":"model not found"}"""))
        assertEquals("model not found", chunk.error)
    }

    @Test
    fun `parseToolCalls reads the function name and arguments`() {
        val calls = client.parseToolCalls(
            JSONArray("""[{"function":{"name":"get_weather","arguments":{"city":"Lyon"}}}]""")
        )
        assertEquals(1, calls.size)
        assertEquals("get_weather", calls[0].name)
        assertEquals("Lyon", JSONObject(calls[0].arguments).getString("city"))
    }

    @Test
    fun `parseToolCalls skips malformed entries instead of throwing`() {
        val calls = client.parseToolCalls(
            JSONArray("""[{"nope":1},{"function":{"name":""}},{"function":{"name":"ok"}}]""")
        )
        assertEquals(listOf("ok"), calls.map { it.name })
        assertEquals("{}", calls[0].arguments)
        assertTrue(client.parseToolCalls(null).isEmpty())
    }

    @Test
    fun `parseCapabilities detects vision and tools`() {
        val caps = client.parseCapabilities("""{"capabilities":["completion","tools","vision"]}""")
        assertTrue(caps.tools)
        assertTrue(caps.vision)
        assertFalse(caps.thinking)

        val none = client.parseCapabilities("""{}""")
        assertFalse(none.tools)
        assertFalse(none.vision)
    }

    @Test
    fun `payload omits tools when none are offered`() {
        val payload = client.buildChatPayload(
            model = "qwen3",
            messages = listOf(OllamaChatMessage("user", "salut")),
            options = mapOf("temperature" to 0.7),
            keepAlive = "5m",
            tools = emptyList(),
            stream = true,
            think = false,
        )
        assertFalse(payload.has("tools"))
        assertEquals("qwen3", payload.getString("model"))
        assertTrue(payload.getBoolean("stream"))
        assertFalse(payload.getBoolean("think"))
        assertEquals("5m", payload.getString("keep_alive"))
    }

    @Test
    fun `payload carries tool definitions and tool replies`() {
        val payload = client.buildChatPayload(
            model = "qwen3",
            messages = listOf(
                OllamaChatMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(ToolCall("get_weather", """{"city":"Lyon"}""")),
                ),
                OllamaChatMessage(role = "tool", content = "18 °C", toolName = "get_weather"),
            ),
            options = emptyMap(),
            keepAlive = null,
            tools = listOf(
                ToolDef("get_weather", "météo", """{"type":"object","properties":{}}""")
            ),
            stream = false,
        )
        val tools = payload.getJSONArray("tools")
        assertEquals("get_weather", tools.getJSONObject(0).getJSONObject("function").getString("name"))

        val messages = payload.getJSONArray("messages")
        val call = messages.getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("Lyon", call.getJSONObject("function").getJSONObject("arguments").getString("city"))
        assertEquals("get_weather", messages.getJSONObject(1).getString("tool_name"))
        assertFalse(payload.has("keep_alive"))
    }

    @Test
    fun `malformed tool arguments do not break the payload`() {
        val payload = client.buildChatPayload(
            model = "m",
            messages = listOf(
                OllamaChatMessage("assistant", "", toolCalls = listOf(ToolCall("t", "not json")))
            ),
            options = emptyMap(),
            keepAlive = null,
            tools = emptyList(),
            stream = true,
        )
        val args = payload.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("tool_calls").getJSONObject(0)
            .getJSONObject("function").getJSONObject("arguments")
        assertEquals(0, args.length())
    }
}
