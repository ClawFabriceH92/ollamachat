package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.util.PinUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinUtilsTest {

    @Test
    fun `hash is deterministic`() {
        assertEquals(PinUtils.hash("1234"), PinUtils.hash("1234"))
        assertNotEquals(PinUtils.hash("1234"), PinUtils.hash("4321"))
    }

    @Test
    fun `hash is 64 hex chars`() {
        assertTrue(PinUtils.hash("0000").matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `isValidPin enforces 4 digits`() {
        assertTrue(PinUtils.isValidPin("0000"))
        assertTrue(PinUtils.isValidPin("9876"))
        assertFalse(PinUtils.isValidPin("123"))
        assertFalse(PinUtils.isValidPin("12345"))
        assertFalse(PinUtils.isValidPin("12a4"))
    }
}
