package com.trucdecomptable.ollamachat.data.repo

import com.trucdecomptable.ollamachat.data.db.Message

/**
 * The overrides applied while turbo mode is on.
 *
 * Turbo does not rewrite the user's settings — it is a layer applied when the
 * request is built, so switching it off restores exactly what was configured.
 *
 * The values target the four things that actually cost time before the first
 * token appears:
 *  - the model having been unloaded (keep_alive),
 *  - compaction running a whole generation first,
 *  - the tool array inflating the prompt and buying a decision round-trip,
 *  - the prompt itself being long (history, memories, num_ctx).
 */
object TurboProfile {

    /** Smaller window: prompt evaluation is linear in what you send. */
    const val NUM_CTX = 4096

    /** Answers stay short; this caps the tail, not the first token. */
    const val NUM_PREDICT = 1024

    /** Keeps the model resident so the next message skips the load entirely. */
    const val KEEP_ALIVE = "30m"

    /** Recent turns kept verbatim; everything older is dropped for this request. */
    const val HISTORY_MESSAGES = 6

    /** Fewer long-term facts injected into the system prompt. */
    const val MEMORIES = 3

    /**
     * Keeps only the most recent messages. The message being answered is the
     * last one, so it always survives.
     */
    fun trimHistory(history: List<Message>): List<Message> =
        if (history.size <= HISTORY_MESSAGES) history else history.takeLast(HISTORY_MESSAGES)
}
