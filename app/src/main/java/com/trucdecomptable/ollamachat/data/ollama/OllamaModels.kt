package com.trucdecomptable.ollamachat.data.ollama

import org.json.JSONObject

/** A model entry returned by GET /api/tags. */
data class ModelInfo(
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: String,
) {
    companion object {
        fun fromJson(obj: JSONObject): ModelInfo = ModelInfo(
            name = obj.optString("name", obj.optString("model", "")),
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
    val toolName: String? = null,           // set on role = tool
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
    val thinking: String = "",
    val error: String? = null,
)

/** Capabilities reported by POST /api/show. */
data class ModelCapabilities(
    val vision: Boolean = false,
    val tools: Boolean = false,
    val thinking: Boolean = false,
    /**
     * The server listed them. False when it could not be asked, or is too old
     * to say: then "no vision" means "unknown", and images are not withheld.
     */
    val known: Boolean = true,
)

/** What went wrong, as a code the UI turns into a localized message. */
enum class ChatErrorCode {
    CONNECTION,     // server unreachable
    CONNECT_TIMEOUT,// nothing answered at all; detail carries the address
    NO_FIRST_BYTE,  // connected, but no first token in time; detail carries the address
    TIMEOUT,        // server reachable but too slow
    HTTP,           // non-2xx without a usable body
    SERVER,         // Ollama reported an error (detail carries its text)
    EMPTY,          // no content at all
    NO_MODEL,       // no model selected
    NO_CONVERSATION,
    TOOL_LOOP_LIMIT,// the step budget ran out before the model answered
    LENGTH,         // num_predict ran out before any answer (reasoning took it all)
    NO_VISION,      // the question carries images the model cannot see; detail carries the model
    UNKNOWN,
}

data class ChatError(val code: ChatErrorCode, val detail: String? = null)

/** Result of a chat stream, including optional tool calls and generation stats. */
data class ChatStreamResult(
    val fullText: String,
    val thinking: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val tokPerSec: Double? = null,
    val evalCount: Int? = null,
    val promptEvalCount: Int? = null,
    val cancelled: Boolean = false,
    val error: ChatError? = null,
    /** Ollama stopped at num_predict (done_reason "length"): the answer is cut. */
    val truncated: Boolean = false,
) {
    /**
     * Short human-readable stats line, e.g. "42 tok/s · 850 tokens". It is
     * stored with the message, which is what keeps a cut answer marked as
     * such after the screen is gone.
     */
    fun statsLine(): String? {
        val tps = tokPerSec
        val n = evalCount
        val parts = mutableListOf<String>()
        if (tps != null) parts.add("%.0f tok/s".format(tps))
        if (n != null) parts.add("$n tokens")
        if (truncated) parts.add("tronquée (limite num_predict)")
        return parts.joinToString(" · ").ifEmpty { null }
    }
}
