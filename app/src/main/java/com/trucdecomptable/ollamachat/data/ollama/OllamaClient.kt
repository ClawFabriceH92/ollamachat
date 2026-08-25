package com.trucdecomptable.ollamachat.data.ollama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP client for the Ollama REST API.
 *
 * Endpoints used:
 *  - GET  {base}/api/version         -> connectivity check
 *  - GET  {base}/api/tags            -> list installed models
 *  - POST {base}/api/chat            -> chat (streaming NDJSON, tools, stats)
 *  - GET  {base}/api/show            -> model capabilities (vision)
 */
class OllamaClient(
    private val http: OkHttpClient = defaultClient(),
) {
    companion object {
        private const val JSON = "application/json; charset=utf-8"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        /** Streaming client: long read timeout, no overall call timeout. */
        fun streamClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()

        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (url.isEmpty()) return url
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            return url
        }
    }

    /** True when the server answers on /api/version. */
    suspend fun testConnection(baseUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${normalizeBaseUrl(baseUrl)}/api/version")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(IOException("HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** List models installed on the server. */
    suspend fun listModels(baseUrl: String): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${normalizeBaseUrl(baseUrl)}/api/tags")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                val body = resp.body?.string() ?: return@withContext Result.failure(IOException("Empty body"))
                val json = JSONObject(body)
                val arr = json.optJSONArray("models") ?: JSONArray()
                val models = (0 until arr.length()).map { ModelInfo.fromJson(arr.getJSONObject(it)) }
                Result.success(models)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Fetch model capabilities (vision support) via /api/show. */
    suspend fun modelCapabilities(baseUrl: String, model: String): Result<ModelCapabilities> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().put("model", model).toString()
                val req = Request.Builder()
                    .url("${normalizeBaseUrl(baseUrl)}/api/show")
                    .post(body.toRequestBody(JSON.toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val capabilities = json.optJSONArray("capabilities") ?: JSONArray()
                    val vision = (0 until capabilities.length()).any { capabilities.getString(it) == "vision" }
                    Result.success(ModelCapabilities(vision = vision))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Stream a chat completion. Calls [onDelta] for each content piece as it
     * arrives. Supports optional [tools] (tool calling); the final chunk's
     * tool_calls and generation stats (tok/s) are returned in the result.
     */
    suspend fun chatStream(
        baseUrl: String,
        model: String,
        messages: List<OllamaChatMessage>,
        options: Map<String, Any> = emptyMap(),
        keepAlive: String? = null,
        tools: List<ToolDef> = emptyList(),
        onDelta: (String) -> Unit = {},
    ): ChatStreamResult = withContext(Dispatchers.IO) {
        val payload = buildChatPayload(model, messages, options, keepAlive, tools, stream = true)
        val req = Request.Builder()
            .url("${normalizeBaseUrl(baseUrl)}/api/chat")
            .post(payload.toString().toRequestBody(JSON.toMediaType()))
            .build()

        try {
            streamClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.let { extractError(it) } ?: "HTTP ${resp.code}"
                    return@withContext ChatStreamResult(fullText = "", error = err)
                }
                val source = resp.body?.source()
                    ?: return@withContext ChatStreamResult(fullText = "", error = "Corps vide")
                val sb = StringBuilder()
                var toolCalls: List<ToolCall> = emptyList()
                var evalCount: Int? = null
                var evalDurationNs: Long? = null
                var promptEvalCount: Int? = null
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val chunk = parseChunk(line)
                    if (chunk.error != null) {
                        return@withContext ChatStreamResult(sb.toString(), error = chunk.error)
                    }
                    if (chunk.content.isNotEmpty()) {
                        sb.append(chunk.content)
                        onDelta(chunk.content)
                    }
                    if (chunk.done) {
                        // Final chunk carries tool_calls + stats.
                        try {
                            val finalJson = JSONObject(line)
                            finalJson.optJSONObject("message")?.let { msg ->
                                toolCalls = parseToolCalls(msg.optJSONArray("tool_calls"))
                            }
                            evalCount = if (finalJson.has("eval_count")) finalJson.getInt("eval_count") else null
                            evalDurationNs = if (finalJson.has("eval_duration")) finalJson.getLong("eval_duration") else null
                            promptEvalCount = if (finalJson.has("prompt_eval_count")) finalJson.getInt("prompt_eval_count") else null
                        } catch (_: Exception) {
                        }
                        break
                    }
                }
                val tps = if (evalCount != null && evalDurationNs != null && evalDurationNs!! > 0) {
                    evalCount!! / (evalDurationNs!! / 1e9)
                } else null
                ChatStreamResult(
                    fullText = sb.toString(),
                    toolCalls = toolCalls,
                    tokPerSec = tps,
                    evalCount = evalCount,
                    promptEvalCount = promptEvalCount,
                )
            }
        } catch (e: Exception) {
            ChatStreamResult(fullText = "", error = e.message ?: "Erreur réseau")
        }
    }

    /**
     * Single non-streaming chat completion (used for context compaction).
     * Returns the full assistant text.
     */
    suspend fun chatOnce(
        baseUrl: String,
        model: String,
        messages: List<OllamaChatMessage>,
        options: Map<String, Any> = emptyMap(),
        keepAlive: String? = null,
        tools: List<ToolDef> = emptyList(),
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = buildChatPayload(model, messages, options, keepAlive, tools, stream = false)
            val req = Request.Builder()
                .url("${normalizeBaseUrl(baseUrl)}/api/chat")
                .post(payload.toString().toRequestBody(JSON.toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                val text = json.optJSONObject("message")?.optString("content", "")
                    ?: return@withContext Result.failure(IOException("Réponse vide"))
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildChatPayload(
        model: String,
        messages: List<OllamaChatMessage>,
        options: Map<String, Any>,
        keepAlive: String?,
        tools: List<ToolDef>,
        stream: Boolean,
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("stream", stream)
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(
                JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                    if (m.images.isNotEmpty()) {
                        put("images", JSONArray(m.images))
                    }
                    if (m.toolCalls.isNotEmpty()) {
                        val calls = JSONArray()
                        m.toolCalls.forEach { tc ->
                            calls.put(
                                JSONObject().apply {
                                    put(
                                        "function", JSONObject().apply {
                                            put("name", tc.name)
                                            put("arguments", JSONObject(tc.arguments))
                                        }
                                    )
                                }
                            )
                        }
                        put("tool_calls", calls)
                    }
                }
            )
        }
        put("messages", arr)
        if (options.isNotEmpty()) put("options", JSONObject(options))
        keepAlive?.let { put("keep_alive", it) }
        if (tools.isNotEmpty()) {
            val toolsArr = JSONArray()
            tools.forEach { t ->
                toolsArr.put(
                    JSONObject().apply {
                        put("type", "function")
                        put(
                            "function", JSONObject().apply {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", JSONObject(t.parametersJson))
                            }
                        )
                    }
                )
            }
            put("tools", toolsArr)
        }
    }

    private fun extractError(body: String): String = try {
        JSONObject(body).optString("error", "HTTP ${JSONObject(body).optString("status", "")}")
    } catch (_: Exception) {
        "Réponse serveur invalide"
    }

    private fun parseChunk(line: String): OllamaChatChunk = try {
        val json = JSONObject(line)
        OllamaChatChunk(
            done = json.optBoolean("done", false),
            content = json.optJSONObject("message")?.optString("content", "") ?: "",
            error = if (json.has("error")) json.optString("error") else null,
        )
    } catch (_: Exception) {
        OllamaChatChunk(done = false, content = "")
    }

    private fun parseToolCalls(arr: JSONArray?): List<ToolCall> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val call = arr.getJSONObject(i)
                val fn = call.optJSONObject("function") ?: return@mapNotNull null
                ToolCall(
                    name = fn.optString("name", ""),
                    arguments = fn.optJSONObject("arguments")?.toString() ?: "{}",
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
