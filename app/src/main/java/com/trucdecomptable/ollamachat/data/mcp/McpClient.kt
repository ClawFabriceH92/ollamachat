package com.trucdecomptable.ollamachat.data.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.trucdecomptable.ollamachat.util.DiagnosticLog

/**
 * Minimal Model Context Protocol (MCP) client over streamable HTTP (JSON-RPC).
 *
 *   initialize -> notifications/initialized -> tools/list -> tools/call
 *
 * Two things this transport gets wrong easily, and both used to be wrong here:
 *  - the server hands out an `Mcp-Session-Id` on initialize that every later
 *    request must carry, otherwise it answers 400/404;
 *  - every reply is a JSON-RPC envelope, so the payload lives under `result`,
 *    not at the top level.
 */
object McpClient {

    data class McpTool(
        val name: String,
        val description: String,
        val inputSchema: String, // JSON Schema as text
    )

    private const val JSON = "application/json; charset=utf-8"
    private const val PROTOCOL = "2025-06-18"
    private const val SESSION_HEADER = "Mcp-Session-Id"

    /** Tool lists barely change; re-listing on every message is pure latency. */
    internal const val TOOL_CACHE_TTL_MS = 5 * 60_000L

    /** url -> session id ("" when the server does not use sessions). */
    private val sessions = ConcurrentHashMap<String, String>()
    private val toolCache = ConcurrentHashMap<String, CachedTools>()
    private val requestId = AtomicInteger(1)

    private data class CachedTools(val tools: List<McpTool>, val fetchedAt: Long)

    /** Overridable so cache expiry is testable without sleeping. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /** Drops cached sessions and tool lists (server list edited, or tests). */
    fun invalidate(url: String? = null) {
        if (url == null) {
            sessions.clear()
            toolCache.clear()
        } else {
            sessions.remove(url)
            toolCache.remove(url)
        }
    }

    /** Lists the tools exposed by the MCP server. */
    suspend fun listTools(url: String): Result<List<McpTool>> = withContext(Dispatchers.IO) {
        toolCache[url]?.let { cached ->
            if (clock() - cached.fetchedAt < TOOL_CACHE_TTL_MS) return@withContext Result.success(cached.tools)
        }
        try {
            val result = withSession(url) { sessionId ->
                rpc(url, request("tools/list"), sessionId)
            }
            val array = result.optJSONArray("tools") ?: JSONArray()
            val tools = (0 until array.length()).mapNotNull { i ->
                val t = array.optJSONObject(i) ?: return@mapNotNull null
                val name = t.optString("name", "")
                if (name.isBlank()) return@mapNotNull null
                McpTool(
                    name = name,
                    description = t.optString("description", ""),
                    inputSchema = t.optJSONObject("inputSchema")?.toString()
                        ?: """{"type":"object","properties":{}}""",
                )
            }
            toolCache[url] = CachedTools(tools, clock())
            Result.success(tools)
        } catch (e: Exception) {
            DiagnosticLog.record("mcp/list", e)
            Result.failure(e)
        }
    }

    /** Calls a tool on the MCP server. */
    suspend fun callTool(url: String, name: String, argumentsJson: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val params = JSONObject().apply {
                    put("name", name)
                    put("arguments", parseArguments(argumentsJson))
                }
                val result = withSession(url) { sessionId ->
                    rpc(url, request("tools/call", params), sessionId)
                }
                Result.success(renderToolResult(result))
            } catch (e: Exception) {
                DiagnosticLog.record("mcp/call", e)
                Result.failure(e)
            }
        }

    /** Flattens the `content` blocks of a tools/call result into text. */
    internal fun renderToolResult(result: JSONObject): String {
        val content = result.optJSONArray("content") ?: JSONArray()
        val texts = (0 until content.length()).mapNotNull { i ->
            val block = content.optJSONObject(i) ?: return@mapNotNull null
            when (block.optString("type", "")) {
                "text" -> block.optString("text", "").ifBlank { null }
                "resource" -> block.optJSONObject("resource")?.optString("text", "")?.ifBlank { null }
                else -> null
            }
        }
        val body = texts.joinToString("\n").ifBlank { "Réponse MCP vide" }
        return if (result.optBoolean("isError", false)) "Erreur de l'outil MCP : $body" else body
    }

    /**
     * Runs [block] with a live session, re-doing the handshake once if the
     * server says the session is gone.
     */
    private fun <T> withSession(url: String, block: (String?) -> T): T {
        val existing = sessions[url] ?: handshake(url).also { sessions[url] = it }
        return try {
            block(existing.ifBlank { null })
        } catch (e: SessionExpiredException) {
            sessions.remove(url)
            toolCache.remove(url)
            val fresh = handshake(url)
            sessions[url] = fresh
            block(fresh.ifBlank { null })
        }
    }

    /** initialize + notifications/initialized. Returns the session id, "" if none. */
    private fun handshake(url: String): String {
        val params = JSONObject().apply {
            put("protocolVersion", PROTOCOL)
            put("capabilities", JSONObject())
            put(
                "clientInfo", JSONObject().apply {
                    put("name", "OllamaChat")
                    put("version", "1.4.0")
                }
            )
        }
        val response = execute(url, request("initialize", params), sessionId = null)
        response.requireNoError()
        val sessionId = response.sessionId.orEmpty()

        // The notification must already carry the session, or the server drops it.
        val notification = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
            put("params", JSONObject())
        }
        runCatching { execute(url, notification, sessionId.ifBlank { null }) }
        return sessionId
    }

    private fun request(method: String, params: JSONObject? = null): JSONObject = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", requestId.getAndIncrement())
        put("method", method)
        put("params", params ?: JSONObject())
    }

    /** Sends a JSON-RPC call and returns its `result` object. */
    private fun rpc(url: String, payload: JSONObject, sessionId: String?): JSONObject {
        val response = execute(url, payload, sessionId)
        response.requireNoError()
        return response.envelope?.optJSONObject("result")
            ?: throw IllegalStateException("Réponse MCP sans résultat")
    }

    private fun execute(url: String, payload: JSONObject, sessionId: String?): RpcResponse {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", JSON)
        sessionId?.let { builder.header(SESSION_HEADER, it) }
        val req = builder.post(payload.toString().toRequestBody(JSON.toMediaType())).build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 404 (unknown session) and 400 "Missing session ID" both mean
                // "handshake again", not "give up".
                if (resp.code == 404 || (resp.code == 400 && body.contains("session", ignoreCase = true))) {
                    throw SessionExpiredException("MCP session expirée (HTTP ${resp.code})")
                }
                throw IllegalStateException("MCP HTTP ${resp.code}")
            }
            return RpcResponse(
                envelope = parseMcpResponse(body),
                sessionId = resp.header(SESSION_HEADER),
            )
        }
    }

    private class RpcResponse(val envelope: JSONObject?, val sessionId: String?) {
        fun requireNoError() {
            val error = envelope?.optJSONObject("error") ?: return
            val message = error.optString("message", "erreur inconnue")
            val code = error.optInt("code", 0)
            throw IllegalStateException("MCP $code : $message")
        }
    }

    private class SessionExpiredException(message: String) : Exception(message)

    private fun parseArguments(json: String): JSONObject = try {
        JSONObject(json)
    } catch (_: Exception) {
        JSONObject()
    }

    /** Handles both plain JSON and SSE (`data: {json}`) responses; null for 202-style empties. */
    internal fun parseMcpResponse(body: String): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) return runCatching { JSONObject(trimmed) }.getOrNull()
        // SSE: keep the last data: line that parses as JSON.
        var last: JSONObject? = null
        trimmed.lineSequence().forEach { line ->
            val candidate = line.removePrefix("data:").trim()
            if (candidate.startsWith("{")) {
                runCatching { JSONObject(candidate) }.getOrNull()?.let { last = it }
            }
        }
        return last
    }
}
