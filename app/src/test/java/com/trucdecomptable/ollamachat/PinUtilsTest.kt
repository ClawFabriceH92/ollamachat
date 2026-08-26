package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.prefs.LockoutPolicy
import com.trucdecomptable.ollamachat.util.PinUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinUtilsTest {

    @Test
    fun `hash is salted so the same pin never yields the same digest`() {
        assertNotEquals(PinUtils.hash("1234"), PinUtils.hash("1234"))
    }

    @Test
    fun `verify accepts the right pin and rejects the wrong one`() {
        val stored = PinUtils.hash("1234")
        assertTrue(PinUtils.verify("1234", stored))
        assertFalse(PinUtils.verify("4321", stored))
        assertFalse(PinUtils.verify("", stored))
    }

    @Test
    fun `verify still accepts the legacy unsalted format`() {
        val legacy = PinUtils.legacyHash("0000")
        assertTrue(PinUtils.verify("0000", legacy))
        assertFalse(PinUtils.verify("1111", legacy))
        assertTrue(PinUtils.needsUpgrade(legacy))
        assertFalse(PinUtils.needsUpgrade(PinUtils.hash("0000")))
    }

    @Test
    fun `an empty stored hash unlocks nothing`() {
        assertFalse(PinUtils.verify("0000", ""))
        assertFalse(PinUtils.verify("", ""))
        assertFalse(PinUtils.needsUpgrade(""))
    }

    @Test
    fun `isValidPin accepts 4 to 8 digits`() {
        assertTrue(PinUtils.isValidPin("0000"))
        assertTrue(PinUtils.isValidPin("98765432"))
        assertFalse(PinUtils.isValidPin("123"))
        assertFalse(PinUtils.isValidPin("123456789"))
        assertFalse(PinUtils.isValidPin("12a4"))
        assertFalse(PinUtils.isValidPin(""))
    }

    @Test
    fun `lockout grows after the free attempts and is capped`() {
        assertEquals(0L, LockoutPolicy.lockoutMillis(1))
        assertEquals(0L, LockoutPolicy.lockoutMillis(LockoutPolicy.FREE_ATTEMPTS))
        assertEquals(5_000L, LockoutPolicy.lockoutMillis(LockoutPolicy.FREE_ATTEMPTS + 1))
        assertEquals(10_000L, LockoutPolicy.lockoutMillis(LockoutPolicy.FREE_ATTEMPTS + 2))
        assertEquals(20_000L, LockoutPolicy.lockoutMillis(LockoutPolicy.FREE_ATTEMPTS + 3))
        assertEquals(5 * 60_000L, LockoutPolicy.lockoutMillis(50))
    }
}
