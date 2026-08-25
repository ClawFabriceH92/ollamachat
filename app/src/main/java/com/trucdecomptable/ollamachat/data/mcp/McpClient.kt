package com.trucdecomptable.ollamachat.data.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal Model Context Protocol (MCP) client over streamable HTTP (JSON-RPC).
 *
 * Used to expose tools from external MCP servers to the model:
 *   initialize -> notifications/initialized -> tools/list -> tools/call
 */
object McpClient {

    data class McpTool(
        val name: String,
        val description: String,
        val inputSchema: String, // JSON Schema as text
    )

    private const val JSON = "application/json; charset=utf-8"
    private const val PROTOCOL = "2025-06-18"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /** Initializes a session with the server (idempotent). */
    suspend fun initialize(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                put(
                    "params", JSONObject().apply {
                        put("protocolVersion", PROTOCOL)
                        put("capabilities", JSONObject())
                        put(
                            "clientInfo", JSONObject().apply {
                                put("name", "OllamaChat")
                                put("version", "1.2.0")
                            }
                        )
                    }
                )
            }
            rpc(url, payload)
            // Notify initialized (fire-and-forget, ignore errors).
            val notif = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
                put("params", JSONObject())
            }
            rpc(url, notif)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Lists the tools exposed by the MCP server. */
    suspend fun listTools(url: String): Result<List<McpTool>> = withContext(Dispatchers.IO) {
        try {
            initialize(url).getOrThrow()
            val payload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/list")
            }
            val result = rpc(url, payload)
            val tools = result.optJSONArray("tools") ?: JSONArray()
            val list = (0 until tools.length()).mapNotNull { i ->
                val t = tools.getJSONObject(i)
                McpTool(
                    name = t.optString("name", ""),
                    description = t.optString("description", ""),
                    inputSchema = t.optJSONObject("inputSchema")?.toString() ?: """{"type":"object","properties":{}}""",
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Calls a tool on the MCP server. */
    suspend fun callTool(url: String, name: String, argumentsJson: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", 3)
                    put("method", "tools/call")
                    put(
                        "params", JSONObject().apply {
                            put("name", name)
                            put("arguments", JSONObject(argumentsJson))
                        }
                    )
                }
                val result = rpc(url, payload)
                val content = result.optJSONArray("content") ?: JSONArray()
                val texts = (0 until content.length()).mapNotNull { i ->
                    val c = content.getJSONObject(i)
                    if (c.optString("type", "") == "text") c.optString("text", "") else null
                }
                if (texts.isEmpty()) {
                    val err = result.optJSONObject("isError")?.let { if (it.optBoolean("isError", false)) "erreur" else null }
                    Result.success("Réponse MCP vide${if (err != null) " ($err)" else ""}")
                } else {
                    Result.success(texts.joinToString("\n"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun rpc(url: String, payload: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .post(payload.toString().toRequestBody(JSON.toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("MCP HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IllegalStateException("Réponse MCP vide")
            return parseMcpResponse(body)
        }
    }

    /** Handles both plain JSON and SSE (data: {json}) responses. */
    private fun parseMcpResponse(body: String): JSONObject {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return JSONObject(trimmed)
        // SSE: pick the last data: line that parses as JSON.
        var lastJson: JSONObject? = null
        trimmed.lineSequence().forEach { line ->
            val data = line.removePrefix("data:")
            val candidate = data.trim()
            if (candidate.startsWith("{")) {
                try {
                    lastJson = JSONObject(candidate)
                } catch (_: Exception) {
                }
            }
        }
        return lastJson ?: throw IllegalStateException("Réponse MCP non-JSON")
    }
}
