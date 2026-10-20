package com.trucdecomptable.ollamachat.data.repo

/**
 * The overrides applied while turbo mode is on.
 *
 * Turbo does not rewrite the user's settings — it is a layer applied when the
 * request is built, so switching it off restores exactly what was configured.
 *
 * The values target what actually costs time before the first token appears:
 *  - the model having been unloaded (keep_alive),
 *  - compaction running a whole generation first,
 *  - the tool array inflating the prompt and buying a decision round-trip,
 *  - reasoning before answering.
 *
 * The context window is left alone for the conversation's own model: Ollama
 * reloads a model entirely when num_ctx changes, so a smaller window made
 * every switch between turbo and normal cost a full load of a 27B model, for
 * no gain on the first token. The history sent is chosen by budget, like
 * outside turbo, which keeps it a stable prefix the server can reuse.
 */
object TurboProfile {

    /**
     * Window of a turbo model distinct from the conversation's: it is loaded
     * for turbo only, so a smaller window costs no reload and saves memory.
     */
    const val NUM_CTX = 4096

    /** Answers stay short; this caps the tail, not the first token. */
    const val NUM_PREDICT = 1024

    /** Keeps the model resident so the next message skips the load entirely. */
    const val KEEP_ALIVE = "30m"

    /** Fewer long-term facts injected into the system prompt. */
    const val MEMORIES = 3

    /** The model a turn runs on: the turbo model when one is set, else the conversation's. */
    fun model(turbo: Boolean, turboModel: String, conversationModel: String): String =
        if (turbo) turboModel.ifBlank { conversationModel } else conversationModel

    /** num_ctx for that model: reduced only for a distinct turbo model. */
    fun numCtx(turbo: Boolean, turboModel: String, conversationModel: String, configured: Int): Int =
        if (turbo && turboModel.isNotBlank() && turboModel != conversationModel) minOf(configured, NUM_CTX)
        else configured
}
