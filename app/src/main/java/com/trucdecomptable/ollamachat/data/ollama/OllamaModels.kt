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
    val role: String,           // system | user | assistant | tool
    val content: String,
    val images: List<String> = emptyList(), // base64-encoded images
    val toolCalls: List<ToolCall> = emptyList(),
)

/** A tool call requested by the model. */
data class ToolCall(
    val name: String,
    val arguments: String,      // JSON object as text
)

/** A tool definition exposed to the model (function schema). */
data class ToolDef(
    val name: String,
    val description: String,
    val parametersJson: String, // JSON Schema object as text
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

/** Result of a chat stream, including optional tool calls and generation stats. */
data class ChatStreamResult(
    val fullText: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val tokPerSec: Double? = null,
    val evalCount: Int? = null,
    val promptEvalCount: Int? = null,
    val cancelled: Boolean = false,
    val error: String? = null,
) {
    /** Short human-readable stats line, e.g. "42 tok/s · 850 tokens". */
    fun statsLine(): String? {
        val tps = tokPerSec
        val n = evalCount
        if (tps == null && n == null) return null
        val parts = mutableListOf<String>()
        if (tps != null) parts.add("%.0f tok/s".format(tps))
        if (n != null) parts.add("$n tokens")
        return parts.joinToString(" · ")
    }
}

/** JSON helpers used by the client. */
fun JSONObject.putTools(tools: List<ToolDef>): JSONObject {
    if (tools.isEmpty()) return this
    val arr = JSONArray()
    tools.forEach { t ->
        arr.put(
            JSONObject().apply {
                put(
                    "type", "function"
                )
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
    put("tools", arr)
    return this
}
