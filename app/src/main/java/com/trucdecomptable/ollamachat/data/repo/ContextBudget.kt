package com.trucdecomptable.ollamachat.data.repo

import com.trucdecomptable.ollamachat.data.db.Message

/**
 * Decides when a conversation no longer fits the model's context window.
 *
 * Pure functions on purpose: this is the logic that decides whether the app
 * rewrites the user's history, so it is covered by unit tests rather than
 * verified by reading it.
 */
object ContextBudget {

    /** Compaction kicks in once the estimate passes this share of num_ctx. */
    const val FILL_RATIO = 0.72

    /** Never compact below this, whatever num_ctx says. */
    const val MIN_THRESHOLD = 1024

    /** Recent turns always stay verbatim. */
    const val KEEP_LAST = 8

    /** Rough estimate: ~4 chars per token for text, ~1000 tokens per image. */
    fun estimateTokens(messages: List<Message>, systemPrompt: String): Int {
        var total = systemPrompt.length / 4
        messages.forEach { m ->
            total += m.content.length / 4
            if (m.imagePath != null || m.imageBase64 != null) total += 1000
        }
        return total
    }

    fun threshold(numCtx: Int): Int = (numCtx * FILL_RATIO).toInt().coerceAtLeast(MIN_THRESHOLD)

    fun shouldCompact(messages: List<Message>, systemPrompt: String, numCtx: Int): Boolean =
        messages.size > KEEP_LAST && estimateTokens(messages, systemPrompt) > threshold(numCtx)

    /** The older slice folded into a summary; empty when nothing should move. */
    fun toSummarize(messages: List<Message>): List<Message> =
        if (messages.size <= KEEP_LAST) emptyList()
        else messages.subList(0, messages.size - KEEP_LAST)
}
