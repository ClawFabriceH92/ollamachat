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
import java.net.NoRouteToHostException
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
         *
         * The read timeout covers the wait for the first token, and Ollama
         * sends nothing at all — not even headers — until the model is loaded
         * and the prompt evaluated. A 27B model with a long prompt can take
         * minutes; Stop really closes the socket, so a generous bound costs
         * nothing.
         */
        private val streamClientInstance: OkHttpClient by lazy { streamClientBuilder().build() }

        /** The streaming client's setup, so a test can shorten a timeout and keep the rest. */
        internal fun streamClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            // Network interceptors only run once a connection exists — pooled
            // or fresh — which is exactly the fact a timeout is classified by.
            // An attempt that fails otherwise than by waiting (a pooled
            // connection found dead) proves nothing: OkHttp retries on a new
            // one, and that one has to connect on its own.
            .addNetworkInterceptor { chain ->
                val phase = chain.request().tag(CallPhase::class.java)
                phase?.connected = true
                try {
                    chain.proceed(chain.request())
                } catch (e: IOException) {
                    if (e !is SocketTimeoutException) phase?.connected = false
                    throw e
                }
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

    /**
     * True when anything at all answers at [target], an HTTP error included;
     * null when Stop interrupted the check. Registered as the active call, so
     * Stop reaches it like the answer it stands for, and kept short.
     */
    private suspend fun answers(target: String): Boolean? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$target/api/version").get().build()
        val check = http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build().newCall(req)
        activeCall.set(check)
        try {
            val answered = runCatching { check.execute().use { true } }.getOrDefault(false)
            if (check.isCanceled()) null else answered
        } finally {
            activeCall.compareAndSet(check, null)
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

    /**
     * Asks the server to load [model] into memory and hold it there.
     *
     * A model that has been evicted costs several seconds before the first
     * token of the next message; warming it up when a conversation opens moves
     * that wait out of the user's way. Failures are irrelevant — this is an
     * optimisation, not a step.
     */
    open suspend fun warmUp(baseUrl: String, model: String, keepAlive: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                // An empty prompt loads the model without generating anything.
                val payload = JSONObject()
                    .put("model", model)
                    .put("keep_alive", keepAlive)
                    .toString()
                val req = Request.Builder()
                    .url("${normalizeBaseUrl(baseUrl)}/api/generate")
                    .post(payload.toRequestBody(JSON.toMediaType()))
                    .build()
                streamHttp.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Result.success(Unit)
                    else Result.failure(IOException("HTTP ${resp.code}"))
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

    /**
     * Per-call record of how far a request got, carried as a request tag.
     *
     * Whether the response headers arrived is not enough to tell "never
     * reached the server" from "the server is slow": Ollama holds its headers
     * until the first token, so a model loading for minutes looked exactly
     * like a tunnel dropping packets.
     */
    internal class CallPhase {
        @Volatile
        var connected = false
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
        val target = normalizeBaseUrl(baseUrl)
        val phase = CallPhase()
        val req = Request.Builder()
            .url("$target/api/chat")
            .post(payload.toString().toRequestBody(JSON.toMediaType()))
            .tag(CallPhase::class.java, phase)
            .build()

        val call = streamHttp.newCall(req)
        activeCall.set(call)
        // If the whole scope dies, close the socket instead of letting the
        // server keep generating tokens for a screen nobody is watching.
        val cancelBridge = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        val text = StringBuilder()
        val reasoning = StringBuilder()
        // Flipped the moment response headers arrive, which is what separates
        // "never reached the server" from "the server is slow".
        var responseStarted = false
        try {
            call.execute().use { resp ->
                responseStarted = true
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
                    // Tool calls can arrive on several lines when the model asks
                    // for more than one in a turn: they accumulate. Keeping only
                    // the latest line silently dropped every call but the last.
                    json.optJSONObject("message")?.let { msg ->
                        val calls = parseToolCalls(msg.optJSONArray("tool_calls"))
                        if (calls.isNotEmpty()) toolCalls = (toolCalls + calls).distinct()
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
                var error = classify(e, target, responseStarted, phase.connected)
                // "Connected, the network works" is only said once checked: a
                // pooled connection the tunnel silently stopped carrying also
                // gets its request written, then waits for nothing.
                val reachable = if (error.code == ChatErrorCode.NO_FIRST_BYTE) answers(target) else true
                if (reachable == null || call.isCanceled()) {
                    ChatStreamResult(text.toString(), thinking = reasoning.toString(), cancelled = true)
                } else {
                    if (!reachable) error = ChatError(ChatErrorCode.CONNECT_TIMEOUT, target)
                    ChatStreamResult(text.toString(), thinking = reasoning.toString(), error = error)
                }
            }
        } catch (e: Exception) {
            ChatStreamResult(
                text.toString(),
                thinking = reasoning.toString(),
                error = classify(e, target, responseStarted, phase.connected),
            )
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

    private fun classify(
        e: Exception,
        target: String? = null,
        responseStarted: Boolean = true,
        connected: Boolean = true,
    ): ChatError {
        DiagnosticLog.record("ollama", e)
        return classifyCode(e, target, responseStarted, connected)
    }

    /**
     * Three timeouts that mean three different things.
     *
     * - [connected] false: nothing ever answered. The packets are dropped on
     *   the way, which is what a tunnel with no return path looks like.
     * - connected but no [responseStarted]: the server took the request and
     *   has not produced a first token — a model loading, a long prompt, or
     *   a server busy with another request. Calling that a network failure
     *   sent the owner to check a VPN that was working.
     * - both: the answer stalled mid-stream.
     *
     * Both flags default to true: a caller that cannot tell must not claim a
     * failure it did not observe.
     */
    internal fun classifyCode(
        e: Exception,
        target: String? = null,
        responseStarted: Boolean = true,
        connected: Boolean = true,
    ): ChatError = when {
        // A connect timeout says so itself (Android: "failed to connect to …",
        // the JVM: "connect timed out"), whatever an earlier attempt left set.
        e is SocketTimeoutException && (!connected || isConnectTimeout(e)) ->
            ChatError(ChatErrorCode.CONNECT_TIMEOUT, target)
        e is SocketTimeoutException && !responseStarted -> ChatError(ChatErrorCode.NO_FIRST_BYTE, target)
        e is SocketTimeoutException -> ChatError(ChatErrorCode.TIMEOUT)
        e is ConnectException || e is UnknownHostException || e is NoRouteToHostException ->
            ChatError(ChatErrorCode.CONNECTION)
        else -> ChatError(ChatErrorCode.UNKNOWN, e.message)
    }

    private fun isConnectTimeout(e: SocketTimeoutException): Boolean {
        val message = e.message.orEmpty().lowercase()
        return message.startsWith("failed to connect") || message.startsWith("connect timed out")
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
