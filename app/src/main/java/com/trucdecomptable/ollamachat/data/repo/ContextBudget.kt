package com.trucdecomptable.ollamachat.data.repo

import com.trucdecomptable.ollamachat.data.db.Message
import com.trucdecomptable.ollamachat.data.db.images
import com.trucdecomptable.ollamachat.data.ollama.ToolDef

/**
 * What fits in the model's context window, and what to do when it does not.
 *
 * Pure functions on purpose: this is the logic that decides whether the app
 * rewrites the user's history and what the model gets to see, so it is
 * covered by unit tests rather than verified by reading it.
 *
 * Everything is counted against one budget: num_ctx, minus the room kept for
 * the answer, minus the fixed part of the prompt (system prompt, memories,
 * tool definitions). The earlier version measured the history alone against
 * 72 % of num_ctx, so the real prompt overflowed before any compaction ran,
 * and Ollama silently cut its beginning, system prompt included.
 */
object ContextBudget {

    /**
     * French, figures and JSON split into more tokens than English prose:
     * about three characters each, not four. Overestimating costs a little
     * history; underestimating costs the start of the prompt.
     */
    const val CHARS_PER_TOKEN = 3

    /** A rough price for one image. */
    const val IMAGE_TOKENS = 1000

    /** Role markers and framing around each message. */
    private const val MESSAGE_OVERHEAD = 4

    /** The chat template and the framing of the tool definitions. */
    const val TEMPLATE_TOKENS = 300

    /**
     * Compaction brings the history back down to this share of its budget,
     * so the next message does not trigger another summary straight away.
     */
    const val TARGET_RATIO = 0.5

    /** The summary request asks for at most this much. */
    const val SUMMARY_PREDICT = 1024

    /** A tool output given less room than this is omitted rather than cut to nothing. */
    const val MIN_TOOL_TOKENS = 150

    /** contentType of a compaction summary. */
    const val CONTENT_SUMMARY = "summary"

    /** Summaries written before they were marked are recognised by their opening words. */
    const val SUMMARY_PREFIX = "Contexte compacté automatiquement"

    const val TRUNCATED_MARK = "\n[… tronqué : contexte limité …]"

    const val TOOL_OUTPUT_OMITTED =
        "Sortie omise : le contexte du modèle est plein. Réponds avec ce que tu as déjà."

    fun tokens(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    fun tokens(m: Message): Int =
        tokens(m.content) + MESSAGE_OVERHEAD +
            IMAGE_TOKENS * (m.images.size + if (m.imageBase64 != null) 1 else 0)

    /** The tool definitions travel with every request, as JSON. */
    fun toolTokens(tools: List<ToolDef>): Int =
        tools.sumOf { tokens(it.name) + tokens(it.description) + tokens(it.parametersJson) + 10 }

    fun estimateTokens(messages: List<Message>, systemPrompt: String): Int =
        tokens(systemPrompt) + messages.sumOf { tokens(it) }

    /**
     * Room kept for the answer: num_predict, but never more than a quarter of
     * the window. Reserving all of it would leave almost nothing for the
     * history (8192 − 4096); an unlimited num_predict (-1, or 0) gets the quarter.
     */
    fun reserve(numCtx: Int, numPredict: Int): Int =
        if (numPredict <= 0) numCtx / 4 else minOf(numPredict, numCtx / 4)

    /** What the history may take once the answer and the fixed part of the prompt are set aside. */
    fun historyBudget(numCtx: Int, numPredict: Int, fixedTokens: Int): Int =
        (numCtx - reserve(numCtx, numPredict) - fixedTokens - TEMPLATE_TOKENS).coerceAtLeast(0)

    fun isSummary(m: Message): Boolean =
        m.role == "system" && (m.contentType == CONTENT_SUMMARY || m.content.startsWith(SUMMARY_PREFIX))

    /** What one request sends, and what it had to leave out. */
    data class Selection(
        val messages: List<Message>,
        /** Older messages not sent this time. */
        val dropped: Int,
        /** Even the question being answered does not fit. */
        val overflow: Boolean,
    )

    /**
     * Chooses the history one request sends, within [budget].
     *
     * The question being answered always goes, with anything after it. Then
     * the latest summary, which stands for everything already folded. Then
     * the dialogue backwards while it fits: once a turn does not fit, nothing
     * older goes either, since a dialogue with holes misleads more than a
     * shorter one. A document or page the app injected that does not fit is
     * skipped on its own, and older turns can still come.
     *
     * What is sent stays a stable prefix from one message to the next until
     * the window is full, which lets Ollama reuse its cache. The fixed window
     * of six messages turbo used before slid on every message, so the whole
     * prompt was evaluated again each time.
     */
    fun select(history: List<Message>, budget: Int): Selection {
        if (history.isEmpty()) return Selection(history, 0, false)
        val lastUser = history.indexOfLast { it.role == "user" }
        val mustFrom = if (lastUser >= 0) lastUser else history.lastIndex
        val chosen = BooleanArray(history.size)
        var left = budget
        for (i in mustFrom..history.lastIndex) {
            chosen[i] = true
            left -= tokens(history[i])
        }
        val summary = (0 until mustFrom).lastOrNull { isSummary(history[it]) }
        if (summary != null && tokens(history[summary]) <= left) {
            chosen[summary] = true
            left -= tokens(history[summary])
        }
        for (i in mustFrom - 1 downTo 0) {
            if (chosen[i]) continue
            val cost = tokens(history[i])
            when {
                cost <= left -> {
                    chosen[i] = true
                    left -= cost
                }
                history[i].role == "system" -> continue
                else -> break
            }
        }
        val messages = history.filterIndexed { i, _ -> chosen[i] }
        return Selection(messages, history.size - messages.size, overflow = left < 0)
    }

    /** What compaction folds into a summary, and what it leaves verbatim. */
    data class Plan(val summarize: List<Message>, val keep: List<Message>)

    /**
     * Null when the history fits. Otherwise the recent messages kept verbatim
     * fill about half the budget, always at least the question being
     * answered, and everything older is summarised: a long document too,
     * since that is usually what overflowed.
     *
     * Keeping a fixed eight messages instead meant that when those eight did
     * not fit, every message ran a new summary on a 27B model and still sent
     * too much.
     */
    fun plan(history: List<Message>, budget: Int): Plan? {
        if (history.sumOf { tokens(it) } <= budget) return null
        val lastUser = history.indexOfLast { it.role == "user" }
        val mustFrom = if (lastUser >= 0) lastUser else history.lastIndex
        val target = (budget * TARGET_RATIO).toInt()
        var kept = 0
        var cut = history.size
        for (i in history.lastIndex downTo 0) {
            val cost = tokens(history[i])
            if (i < mustFrom && kept + cost > target) break
            kept += cost
            cut = i
        }
        if (cut == 0) return null
        return Plan(history.subList(0, cut), history.subList(cut, history.size))
    }

    fun summaryPredict(numCtx: Int): Int = minOf(SUMMARY_PREDICT, numCtx / 4)

    /** What one summary request may send, so Ollama does not cut the start of what it summarises. */
    fun summaryInputBudget(numCtx: Int, instruction: String): Int =
        (numCtx - summaryPredict(numCtx) - tokens(instruction) - TEMPLATE_TOKENS).coerceAtLeast(0)

    /**
     * The oldest messages of [toSummarize] that one summary request can take.
     * Only those are folded; the rest waits for the next compaction, with
     * this summary at its head.
     *
     * A first message larger than the whole budget, an imported document
     * say, goes in cut rather than blocking compaction for good: a summary
     * is lossy anyway, and that document could never fit in this window.
     */
    fun sliceToSummarize(toSummarize: List<Message>, budget: Int): List<Message> {
        val slice = mutableListOf<Message>()
        var left = budget
        for (m in toSummarize) {
            val cost = tokens(m)
            if (cost <= left) {
                slice += m
                left -= cost
            } else {
                if (slice.isEmpty()) slice += cut(m, left)
                break
            }
        }
        return slice
    }

    private fun cut(m: Message, budgetTokens: Int): Message {
        val chars = ((budgetTokens - MESSAGE_OVERHEAD) * CHARS_PER_TOKEN - TRUNCATED_MARK.length)
            .coerceAtLeast(0)
        return m.copy(content = m.content.take(chars) + TRUNCATED_MARK)
    }

    /**
     * A tool output cut to [budgetTokens], or replaced by a short notice when
     * there is no room left. A page of 40 000 characters used to go in whole,
     * some 13 000 tokens against a window of 8 192, and what Ollama cut to
     * make room was the start of the prompt.
     */
    fun fitToolOutput(text: String, budgetTokens: Int): String = when {
        tokens(text) <= budgetTokens -> text
        budgetTokens < MIN_TOOL_TOKENS -> TOOL_OUTPUT_OMITTED
        else -> text.take(budgetTokens * CHARS_PER_TOKEN - TRUNCATED_MARK.length) + TRUNCATED_MARK
    }
}
