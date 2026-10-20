package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.db.Message
import com.trucdecomptable.ollamachat.data.repo.ContextBudget
import com.trucdecomptable.ollamachat.data.repo.TurboProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    private fun message(content: String, imagePath: String? = null, role: String = "user") =
        Message(conversationId = 1, role = role, content = content, imagePaths = imagePath)

    /**
     * Alternating user/assistant turns of [chars] characters, the last one a
     * question. Letters only: digits count one token each.
     */
    private fun dialogue(count: Int, chars: Int) = List(count) { i ->
        message("${'a' + i % 26}:" + "x".repeat(chars), role = if ((count - 1 - i) % 2 == 0) "user" else "assistant")
    }

    /** What a default summary of a window of 8 192 reserves. */
    private val reserve8k = ContextBudget.summaryReserve(1024)

    @Test
    fun `text is estimated at three characters per token`() {
        assertEquals(34, ContextBudget.tokens("x".repeat(100)))
        // Four tokens of framing per message on top of its text.
        assertEquals(38, ContextBudget.estimateTokens(listOf(message("x".repeat(100))), ""))
        assertEquals(72, ContextBudget.estimateTokens(listOf(message("x".repeat(100))), "y".repeat(100)))
    }

    @Test
    fun `every digit is a token`() {
        // Qwen splits numbers digit by digit: a column of amounts is dense.
        assertEquals(10, ContextBudget.tokens("1234567890"))
        assertEquals(9, ContextBudget.tokens("12 345,67 €"))
    }

    @Test
    fun `every image is counted, unless it is not sent`() {
        assertEquals(1004, ContextBudget.estimateTokens(listOf(message("", "/tmp/a.jpg")), ""))
        assertEquals(2004, ContextBudget.estimateTokens(listOf(message("", "/tmp/a.jpg\n/tmp/b.jpg")), ""))
        // A blind model gets a one-line note in place of each image.
        assertEquals(24, ContextBudget.tokens(message("", "/tmp/a.jpg"), imageTokens = 20))
        // The legacy inline copy is not sent next to the stored one.
        val both = Message(conversationId = 1, role = "user", content = "", imagePaths = "/tmp/a.jpg", imageBase64 = "AAAA")
        assertEquals(1004, ContextBudget.tokens(both))
    }

    @Test
    fun `the answer keeps at most a quarter of the window`() {
        assertEquals(2048, ContextBudget.reserve(8192, 4096))
        assertEquals(1024, ContextBudget.reserve(8192, 1024))
        // Unlimited num_predict: the quarter, not the whole window.
        assertEquals(2048, ContextBudget.reserve(8192, -1))
        assertEquals(2048, ContextBudget.reserve(8192, 0))
    }

    @Test
    fun `the history budget leaves room for the prompt and the answer`() {
        val fixed = 1500
        assertEquals(8192 - 2048 - fixed - ContextBudget.TEMPLATE_TOKENS, ContextBudget.historyBudget(8192, 4096, fixed))
        // Never negative, however large the fixed part.
        assertEquals(0, ContextBudget.historyBudget(2048, 4096, 10_000))
    }

    @Test
    fun `a history that fits is sent whole and never compacted`() {
        val history = dialogue(20, 30)
        val selection = ContextBudget.select(history, 5_000)
        assertEquals(history, selection.messages)
        assertEquals(0, selection.dropped)
        assertFalse(selection.overflow)
        assertNull(ContextBudget.plan(history, 5_000, reserve8k))
    }

    @Test
    fun `selection keeps the question and the newest turns that fit`() {
        val history = dialogue(10, 300) // about 105 tokens each
        val selection = ContextBudget.select(history, 350)
        // The tail, contiguous, ending on the question.
        assertEquals(history.takeLast(selection.messages.size), selection.messages)
        assertEquals(3, selection.messages.size)
        assertEquals(7, selection.dropped)
        assertFalse(selection.overflow)
    }

    @Test
    fun `the question is sent even when it alone overflows`() {
        val history = dialogue(3, 30) + message("x".repeat(30_000))
        val selection = ContextBudget.select(history, 1_000)
        assertEquals(listOf(history.last()), selection.messages)
        assertTrue(selection.overflow)
    }

    @Test
    fun `the latest summary goes first and a document too big is skipped alone`() {
        val summary = message("Contexte compacté automatiquement : résumé", role = "system")
        val document = message("d".repeat(30_000), role = "system")
        val history = listOf(summary, message("q1"), message("r1", role = "assistant"), document, message("q2"))
        val selection = ContextBudget.select(history, 500)
        assertEquals(listOf(summary, history[1], history[2], history[4]), selection.messages)
        assertEquals(1, selection.dropped)
    }

    @Test
    fun `a turn that does not fit stops the dialogue there`() {
        val history = listOf(message("vieux"), message("y".repeat(30_000), role = "assistant"), message("q"))
        val selection = ContextBudget.select(history, 500)
        // No hole: the older question does not come back without its answer.
        assertEquals(listOf(history.last()), selection.messages)
    }

    @Test
    fun `compaction leaves room for its summary, so the next message does not summarise again`() {
        val history = dialogue(60, 300) // 105 tokens each
        val budget = 5_000
        val plan = assertNotNullPlan(history, budget, reserve8k)
        assertEquals(history, plan.summarize + plan.keep)
        assertEquals(history.last(), plan.keep.last())
        // The longest summary the request allows, written at the estimate's
        // worst, plus what was kept verbatim: about half the budget.
        val summary = message(ContextBudget.SUMMARY_HEADER + "r".repeat(1024 * 4), role = "system")
        val after = listOf(summary) + plan.keep
        assertTrue(after.sumOf { ContextBudget.tokens(it) } <= budget / 2 + 50)
        // A new exchange still fits: no second summary straight away.
        assertNull(ContextBudget.plan(after + dialogue(2, 300), budget, reserve8k))
    }

    @Test
    fun `a huge recent message is summarised, not kept verbatim forever`() {
        val document = message("d".repeat(60_000), role = "system")
        val history = listOf(message("q1"), message("r1", role = "assistant"), document, message("q2"))
        val plan = assertNotNullPlan(history, 5_000, reserve8k)
        assertTrue(document in plan.summarize)
        assertEquals(listOf(history.last()), plan.keep)
    }

    @Test
    fun `nothing older than the question means nothing to compact`() {
        assertNull(ContextBudget.plan(listOf(message("x".repeat(60_000))), 100, 10))
    }

    @Test
    fun `the summary's length follows the budget, and too small a budget is not summarised`() {
        assertEquals(1024, ContextBudget.summaryPredict(8192, 5_000))
        assertEquals(500, ContextBudget.summaryPredict(8192, 2_000))
        assertEquals(512, ContextBudget.summaryPredict(2048, 5_000))
        assertTrue(ContextBudget.canCompact(8192, 5_000))
        // A summary of a few dozen words, written again on every message.
        assertFalse(ContextBudget.canCompact(8192, 600))
        assertFalse(ContextBudget.canCompact(8192, 0))
    }

    @Test
    fun `one summary request only takes what its window can read`() {
        val toSummarize = dialogue(10, 3_000) // 1 005 tokens each
        val slice = ContextBudget.sliceToSummarize(toSummarize, 3_500)
        assertEquals(toSummarize.take(3), slice)
    }

    @Test
    fun `a document after a summary is cut in, not left for ever`() {
        val summary = message(ContextBudget.SUMMARY_HEADER + "Q1, R1.", role = "system")
        val document = message("d".repeat(60_000), role = "system")
        val slice = ContextBudget.sliceToSummarize(listOf(summary, document, message("q")), 6_000)
        // Folding the summary alone would summarise a summary on every message.
        assertEquals(2, slice.size)
        assertEquals(summary, slice[0])
        assertTrue(slice[1].content.endsWith(ContextBudget.TRUNCATED_MARK))
        assertTrue(slice.sumOf { ContextBudget.tokens(it) } <= 6_000)
    }

    @Test
    fun `a first message larger than the window is cut, not skipped`() {
        val document = message("d".repeat(60_000), role = "system")
        val slice = ContextBudget.sliceToSummarize(listOf(document, message("q")), 1_000)
        assertEquals(1, slice.size)
        assertEquals(document.id, slice.single().id)
        assertTrue(slice.single().content.endsWith(ContextBudget.TRUNCATED_MARK))
        assertTrue(ContextBudget.tokens(slice.single()) <= 1_000)
    }

    @Test
    fun `the summary window leaves room for the summary`() {
        val instruction = "Résume."
        val budget = ContextBudget.summaryInputBudget(8192, 1024, instruction)
        assertEquals(8192 - 1024 - ContextBudget.tokens(instruction) - ContextBudget.TEMPLATE_TOKENS, budget)
    }

    @Test
    fun `a tool output is cut to its share, or omitted when nothing is left`() {
        val page = "p".repeat(40_000)
        val cut = ContextBudget.fitToolOutput(page, 1_000)
        assertTrue(cut.endsWith(ContextBudget.TRUNCATED_MARK))
        assertTrue(ContextBudget.tokens(cut) <= 1_000)
        // Figures are denser: the cut still holds.
        val figures = ContextBudget.fitToolOutput("4".repeat(40_000), 1_000)
        assertTrue(ContextBudget.tokens(figures) <= 1_000)
        assertEquals("court", ContextBudget.fitToolOutput("court", 1_000))
        assertEquals(ContextBudget.TOOL_OUTPUT_OMITTED, ContextBudget.fitToolOutput(page, 10))
        assertEquals(ContextBudget.TOOL_OUTPUT_OMITTED, ContextBudget.fitToolOutput(page, -50))
    }

    @Test
    fun `summaries are recognised with or without their marker`() {
        assertTrue(ContextBudget.isSummary(message("Contexte compacté automatiquement : …", role = "system")))
        assertTrue(
            ContextBudget.isSummary(
                Message(conversationId = 1, role = "system", content = "…", contentType = ContextBudget.CONTENT_SUMMARY)
            )
        )
        assertFalse(ContextBudget.isSummary(message("Contexte compacté automatiquement : …", role = "user")))
    }

    private fun assertNotNullPlan(history: List<Message>, budget: Int, reserve: Int): ContextBudget.Plan {
        val plan = ContextBudget.plan(history, budget, reserve)
        assertNotNull("aucune compaction prévue", plan)
        return plan!!
    }
}

class TurboProfileTest {

    @Test
    fun `turbo answers with its own model when one is set`() {
        assertEquals("qwen3:1.7b", TurboProfile.model(true, "qwen3:1.7b", "qwen3:27b"))
        assertEquals("qwen3:27b", TurboProfile.model(true, "", "qwen3:27b"))
        assertEquals("qwen3:27b", TurboProfile.model(false, "qwen3:1.7b", "qwen3:27b"))
    }

    @Test
    fun `the conversation's own model keeps its window, so switching reloads nothing`() {
        assertEquals(8192, TurboProfile.numCtx(true, "", "qwen3:27b", 8192))
        assertEquals(8192, TurboProfile.numCtx(true, "qwen3:27b", "qwen3:27b", 8192))
        assertEquals(8192, TurboProfile.numCtx(false, "qwen3:1.7b", "qwen3:27b", 8192))
    }

    @Test
    fun `a distinct turbo model gets the smaller window`() {
        assertEquals(TurboProfile.NUM_CTX, TurboProfile.numCtx(true, "qwen3:1.7b", "qwen3:27b", 8192))
        assertEquals(2048, TurboProfile.numCtx(true, "qwen3:1.7b", "qwen3:27b", 2048))
    }
}
