package com.trucdecomptable.ollamachat.data.ollama

import org.json.JSONArray
import org.json.JSONObject

/** A model entry returned by GET /api/tags. */
data class ModelInfo(
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: String,
) {
    companion object {
        fun fromJson(obj: JSONObject): ModelInfo = ModelInfo(
            name = obj.getString("name"),
            sizeBytes = obj.optLong("size", 0L),
            modifiedAt = obj.optString("modified_at", ""),
        )
    }
}

/** One message in an Ollama /api/chat request. */
data class OllamaChatMessage(
    val role: String,           // system | user | assistant
    val content: String,
    val images: List<String> = emptyList(), // base64-encoded images
)

/** A generated response chunk (stream mode). */
data class OllamaChatChunk(
    val done: Boolean,
    val content: String,
    val error: String? = null,
)

/** Capabilities reported by GET /api/show. */
data class ModelCapabilities(
    val vision: Boolean,
)

/** Result of a chat stream. */
data class ChatStreamResult(
    val fullText: String,
    val cancelled: Boolean = false,
    val error: String? = null,
)
