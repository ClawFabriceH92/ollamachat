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
 *  - POST {base}/api/chat            -> chat (streaming NDJSON supported)
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
            .readTimeout(120, TimeUnit.SECONDS)
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
     * arrives (main-thread safe: invoked from the IO dispatcher).
     *
     * Returns the accumulated text. On error, [ChatStreamResult.error] is set.
     */
    suspend fun chatStream(
        baseUrl: String,
        model: String,
        messages: List<OllamaChatMessage>,
        options: Map<String, Any> = emptyMap(),
        keepAlive: String? = null,
        onDelta: (String) -> Unit,
    ): ChatStreamResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", model)
            put("stream", true)
            val arr = JSONArray()
            messages.forEach { m ->
                arr.put(
                    JSONObject().apply {
                        put("role", m.role)
                        put("content", m.content)
                        if (m.images.isNotEmpty()) {
                            put("images", JSONArray(m.images))
                        }
                    }
                )
            }
            put("messages", arr)
            if (options.isNotEmpty()) put("options", JSONObject(options))
            keepAlive?.let { put("keep_alive", it) }
        }
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
                    if (chunk.done) break
                }
                ChatStreamResult(fullText = sb.toString())
            }
        } catch (e: Exception) {
            ChatStreamResult(fullText = "", error = e.message ?: "Erreur réseau")
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
            content = json.optString("message", "").let {
                if (it.isEmpty()) "" else JSONObject(it).optString("content", "")
            },
            error = if (json.has("error")) json.optString("error") else null,
        )
    } catch (_: Exception) {
        OllamaChatChunk(done = false, content = "")
    }
}
