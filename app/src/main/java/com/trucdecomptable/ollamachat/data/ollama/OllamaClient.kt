package com.trucdecomptable.ollamachat.data.ollama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.trucdecomptable.ollamachat.util.DiagnosticLog

/**
 * Thin HTTP client for the Ollama REST API.
 *
 * Open so tests can stand in a scripted server: the orchestration in
 * ChatRepository is the part worth covering, and it needs a seam here.
 *
 * Endpoints used:
 *  - GET  {base}/api/version         -> connectivity check
 *  - GET  {base}/api/tags            -> list installed models
 *  - POST {base}/api/chat            -> chat (streaming NDJSON, tools, stats)
 *  - POST {base}/api/show            -> model capabilities (vision, tools)
 */
open class OllamaClient(
    private val http: OkHttpClient = defaultClient(),
    private val streamHttp: OkHttpClient = sharedStreamClient(),
) {
    /** The streaming call in flight, so [cancelActiveStream] can really stop it. */
    private val activeCall = AtomicReference<Call?>(null)

    companion object {
        private const val JSON = "application/json; charset=utf-8"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        /**
         * Streaming client: long read timeout, no overall call timeout.
         * Built once — a fresh OkHttpClient per message means a new connection
         * pool and dispatcher every time, and no connection reuse at all.
         */
        private val streamClientInstance: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build()
        }

        fun sharedStreamClient(): OkHttpClient = streamClientInstance

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
                val models = (0 until arr.length())
                    .map { ModelInfo.fromJson(arr.getJSONObject(it)) }
                    .filter { it.name.isNotBlank() }
                Result.success(models)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Fetch model capabilities (vision / tool calling / reasoning) via /api/show. */
    open suspend fun modelCapabilities(baseUrl: String, model: String): Result<ModelCapabilities> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().put("model", model).toString()
                val req = Request.Builder()
                    .url("${normalizeBaseUrl(baseUrl)}/api/show")
                    .post(body.toRequestBody(JSON.toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                    Result.success(parseCapabilities(resp.body?.string() ?: "{}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Progress of a model download, as Ollama reports it. */
    data class PullProgress(val status: String, val completed: Long, val total: Long) {
        val fraction: Float get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /**
     * Downloads a model onto the server. Until now the only way to install one
     * was to reach the machine itself, which the welcome screen cheerfully
     * told the user to go and do.
     */
    open suspend fun pullModel(
        baseUrl: String,
        model: String,
        onProgress: (PullProgress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().put("model", model).put("stream", true)
            val req = Request.Builder()
                .url("${normalizeBaseUrl(baseUrl)}/api/pull")
                .post(payload.toString().toRequestBody(JSON.toMediaType()))
                .build()
            streamHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                }
                val source = resp.body?.source()
                    ?: return@withContext Result.failure(IOException("Corps vide"))
                while (isActive && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val json = try {
                        JSONObject(line)
                    } catch (_: Exception) {
                        continue
                    }
                    val error = json.optString("error", "")
                    if (error.isNotBlank()) return@withContext Result.failure(IOException(error))
                    onProgress(
                        PullProgress(
                            status = json.optString("status", ""),
                            completed = json.optLong("completed", 0L),
                            total = json.optLong("total", 0L),
                        )
                    )
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            DiagnosticLog.record("ollama/pull", e)
            Result.failure(e)
        }
    }

    /** Removes a model from the server. */
    open suspend fun deleteModel(baseUrl: String, model: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().put("model", model).toString()
                val req = Request.Builder()
                    .url("${normalizeBaseUrl(baseUrl)}/api/delete")
                    .delete(payload.toRequestBody(JSON.toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Result.success(Unit)
                    else Result.failure(IOException("HTTP ${resp.code}"))
                }
            } catch (e: Exception) {
                DiagnosticLog.record("ollama/delete", e)
                Result.failure(e)
            }
        }

    /** Stops the streaming call in flight; the partial answer is still returned. */
    open fun cancelActiveStream() {
        activeCall.getAndSet(null)?.cancel()
    }

    /**
     * Stream a chat completion. Calls [onDelta] for each content piece as it
     * arrives and [onThinking] for reasoning tokens. Supports optional [tools];
     * tool calls and generation stats (tok/s) come back in the result.
     *
     * On [cancelActiveStream] the result carries `cancelled = true` together
     * with everything generated so far.
     */
    open suspend fun chatStream(
        baseUrl: String,
        model: String,
        messages: List<OllamaChatMessage>,
        options: Map<String, Any> = emptyMap(),
        keepAlive: String? = null,
        tools: List<ToolDef> = emptyList(),
        think: Boolean? = null,
        onDelta: (String) -> Unit = {},
        onThinking: (String) -> Unit = {},
    ): ChatStreamResult = withContext(Dispatchers.IO) {
        val payload = buildChatPayload(model, messages, options, keepAlive, tools, stream = true, think = think)
        val req = Request.Builder()
            .url("${normalizeBaseUrl(baseUrl)}/api/chat")
            .post(payload.toString().toRequestBody(JSON.toMediaType()))
            .build()

        val call = streamHttp.newCall(req)
        activeCall.set(call)
        // If the whole scope dies, close the socket instead of letting the
        // server keep generating tokens for a screen nobody is watching.
        val cancelBridge = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        val text = StringBuilder()
        val reasoning = StringBuilder()
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val detail = resp.body?.string()?.let { extractError(it) }
                    return@withContext ChatStreamResult(
                        fullText = "",
                        error = if (detail.isNullOrBlank()) ChatError(ChatErrorCode.HTTP, "HTTP ${resp.code}")
                        else ChatError(ChatErrorCode.SERVER, detail),
                    )
                }
                val source = resp.body?.source()
                    ?: return@withContext ChatStreamResult("", error = ChatError(ChatErrorCode.EMPTY))

                var toolCalls: List<ToolCall> = emptyList()
                var evalCount: Int? = null
                var evalDurationNs: Long? = null
                var promptEvalCount: Int? = null

                while (isActive && !call.isCanceled() && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val json = try {
                        JSONObject(line)
                    } catch (_: Exception) {
                        continue
                    }
                    val chunk = parseChunk(json)
                    if (chunk.error != null) {
                        return@withContext ChatStreamResult(
                            text.toString(),
                            thinking = reasoning.toString(),
                            error = ChatError(ChatErrorCode.SERVER, chunk.error),
                        )
                    }
                    if (chunk.content.isNotEmpty()) {
                        text.append(chunk.content)
                        onDelta(chunk.content)
                    }
                    if (chunk.thinking.isNotEmpty()) {
                        reasoning.append(chunk.thinking)
                        onThinking(chunk.thinking)
                    }
                    // Tool calls arrive in the FIRST chunk (done=false) in
                    // streaming mode — parse them on every line.
                    json.optJSONObject("message")?.let { msg ->
                        val calls = parseToolCalls(msg.optJSONArray("tool_calls"))
                        if (calls.isNotEmpty()) toolCalls = calls
                    }
                    if (chunk.done) {
                        evalCount = if (json.has("eval_count")) json.optInt("eval_count") else null
                        evalDurationNs = if (json.has("eval_duration")) json.optLong("eval_duration") else null
                        promptEvalCount =
                            if (json.has("prompt_eval_count")) json.optInt("prompt_eval_count") else null
                        break
                    }
                }

                if (call.isCanceled()) {
                    return@withContext ChatStreamResult(
                        text.toString(),
                        thinking = reasoning.toString(),
                        cancelled = true,
                    )
                }

                val duration = evalDurationNs
                val count = evalCount
                val tps = if (count != null && duration != null && duration > 0) {
                    count / (duration / 1e9)
                } else null
                ChatStreamResult(
                    fullText = text.toString(),
                    thinking = reasoning.toString(),
                    toolCalls = toolCalls,
                    tokPerSec = tps,
                    evalCount = count,
                    promptEvalCount = promptEvalCount,
                )
            }
        } catch (e: IOException) {
            if (call.isCanceled()) {
                ChatStreamResult(text.toString(), thinking = reasoning.toString(), cancelled = true)
            } else {
                ChatStreamResult(text.toString(), thinking = reasoning.toString(), error = classify(e))
            }
        } catch (e: Exception) {
            ChatStreamResult(text.toString(), thinking = reasoning.toString(), error = classify(e))
        } finally {
            cancelBridge?.dispose()
            activeCall.compareAndSet(call, null)
        }
    }

    /**
     * Single non-streaming chat completion (used for context compaction).
     * Returns the full assistant text.
     */
    open suspend fun chatOnce(
        baseUrl: String,
        model: String,
        messages: List<OllamaChatMessage>,
        options: Map<String, Any> = emptyMap(),
        keepAlive: String? = null,
        tools: List<ToolDef> = emptyList(),
        think: Boolean? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = buildChatPayload(model, messages, options, keepAlive, tools, stream = false, think = think)
            val req = Request.Builder()
                .url("${normalizeBaseUrl(baseUrl)}/api/chat")
                .post(payload.toString().toRequestBody(JSON.toMediaType()))
                .build()
            // Summarizing a long history can take a while: use the streaming
            // client's generous read timeout rather than the 20 s call budget.
            streamHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                val text = json.optJSONObject("message")?.optString("content", "").orEmpty()
                if (text.isBlank()) Result.failure(IOException("Empty response"))
                else Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun buildChatPayload(
        model: String,
        messages: List<OllamaChatMessage>,
        options: Map<String, Any>,
        keepAlive: String?,
        tools: List<ToolDef>,
        stream: Boolean,
        think: Boolean? = null,
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("stream", stream)
        // Qwen3-family models reason by default; think=false makes them answer
        // immediately (much lower first-token latency).
        think?.let { put("think", it) }
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(
                JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                    m.toolName?.let { put("tool_name", it) }
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
                                            put("arguments", safeArguments(tc.arguments))
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
                                put("parameters", safeArguments(t.parametersJson))
                            }
                        )
                    }
                )
            }
            put("tools", toolsArr)
        }
    }

    private fun safeArguments(json: String): JSONObject = try {
        JSONObject(json)
    } catch (_: Exception) {
        JSONObject()
    }

    internal fun parseCapabilities(body: String): ModelCapabilities = try {
        val json = JSONObject(body)
        val arr = json.optJSONArray("capabilities") ?: JSONArray()
        val names = (0 until arr.length()).map { arr.optString(it, "").lowercase() }
        ModelCapabilities(
            vision = names.contains("vision"),
            tools = names.contains("tools"),
            thinking = names.contains("thinking"),
        )
    } catch (_: Exception) {
        ModelCapabilities()
    }

    private fun classify(e: Exception): ChatError {
        DiagnosticLog.record("ollama", e)
        return classifyCode(e)
    }

    private fun classifyCode(e: Exception): ChatError = when (e) {
        is SocketTimeoutException -> ChatError(ChatErrorCode.TIMEOUT)
        is ConnectException, is UnknownHostException -> ChatError(ChatErrorCode.CONNECTION)
        else -> ChatError(ChatErrorCode.UNKNOWN, e.message)
    }

    private fun extractError(body: String): String? = try {
        JSONObject(body).optString("error", "").ifBlank { null }
    } catch (_: Exception) {
        body.take(200).ifBlank { null }
    }

    internal fun parseChunk(json: JSONObject): OllamaChatChunk {
        val message = json.optJSONObject("message")
        return OllamaChatChunk(
            done = json.optBoolean("done", false),
            content = message?.optString("content", "").orEmpty(),
            thinking = message?.optString("thinking", "").orEmpty(),
            error = if (json.has("error")) json.optString("error").ifBlank { null } else null,
        )
    }

    internal fun parseToolCalls(arr: JSONArray?): List<ToolCall> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val call = arr.getJSONObject(i)
                val fn = call.optJSONObject("function") ?: return@mapNotNull null
                val name = fn.optString("name", "")
                if (name.isBlank()) return@mapNotNull null
                ToolCall(
                    name = name,
                    arguments = fn.optJSONObject("arguments")?.toString()
                        ?: fn.optString("arguments", "").ifBlank { "{}" },
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
