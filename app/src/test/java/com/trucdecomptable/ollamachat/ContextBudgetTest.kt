package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.db.Message
import com.trucdecomptable.ollamachat.data.repo.ContextBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    private fun message(content: String, imagePath: String? = null) =
        Message(conversationId = 1, role = "user", content = content, imagePaths = imagePath)

    @Test
    fun `text is estimated at four characters per token`() {
        assertEquals(25, ContextBudget.estimateTokens(listOf(message("x".repeat(100))), ""))
        assertEquals(50, ContextBudget.estimateTokens(listOf(message("x".repeat(100))), "y".repeat(100)))
    }

    @Test
    fun `an image costs about a thousand tokens`() {
        val withImage = ContextBudget.estimateTokens(listOf(message("", "/tmp/a.jpg")), "")
        assertEquals(1000, withImage)
    }

    @Test
    fun `several images on one message are all counted`() {
        val two = ContextBudget.estimateTokens(
            listOf(message("", "/tmp/a.jpg\n/tmp/b.jpg")),
            "",
        )
        assertEquals(2000, two)
    }

    @Test
    fun `threshold never drops below the floor`() {
        assertEquals(ContextBudget.MIN_THRESHOLD, ContextBudget.threshold(512))
        assertEquals(5898, ContextBudget.threshold(8192))
    }

    @Test
    fun `a short conversation is never compacted`() {
        val history = List(4) { message("x".repeat(40_000)) }
        assertFalse(ContextBudget.shouldCompact(history, "", 8192))
        assertTrue(ContextBudget.toSummarize(history).isEmpty())
    }

    @Test
    fun `a long conversation past the threshold is compacted`() {
        val history = List(20) { message("x".repeat(4_000)) }
        assertTrue(ContextBudget.shouldCompact(history, "", 8192))
        assertEquals(20 - ContextBudget.KEEP_LAST, ContextBudget.toSummarize(history).size)
    }

    @Test
    fun `a long but light conversation stays untouched`() {
        val history = List(20) { message("court") }
        assertFalse(ContextBudget.shouldCompact(history, "", 8192))
    }

    @Test
    fun `the most recent turns are always kept verbatim`() {
        val history = List(12) { message("message $it") }
        val summarized = ContextBudget.toSummarize(history)
        assertEquals("message 0", summarized.first().content)
        assertEquals("message 3", summarized.last().content)
    }
}
