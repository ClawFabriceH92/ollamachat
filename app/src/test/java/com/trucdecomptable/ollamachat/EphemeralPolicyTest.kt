package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.repo.EphemeralPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EphemeralPolicyTest {

    private val start = 1_000_000L
    private val minute = 60_000L

    @Test
    fun `zero means the conversation is kept`() {
        assertFalse(EphemeralPolicy.isEphemeral(EphemeralPolicy.OFF))
        assertNull(EphemeralPolicy.expiresAt(start, 0))
        assertNull(EphemeralPolicy.remainingMillis(start, 0, start + 10 * minute))
        assertFalse(EphemeralPolicy.isExpired(start, 0, Long.MAX_VALUE))
    }

    @Test
    fun `the deadline counts from the last activity`() {
        assertEquals(start + 15 * minute, EphemeralPolicy.expiresAt(start, 15))
    }

    @Test
    fun `a conversation expires exactly at its deadline, not before`() {
        assertFalse(EphemeralPolicy.isExpired(start, 5, start + 5 * minute - 1))
        assertTrue(EphemeralPolicy.isExpired(start, 5, start + 5 * minute))
        assertTrue(EphemeralPolicy.isExpired(start, 5, start + 60 * minute))
    }

    @Test
    fun `remaining time never goes negative`() {
        assertEquals(3 * minute, EphemeralPolicy.remainingMillis(start, 5, start + 2 * minute))
        assertEquals(0L, EphemeralPolicy.remainingMillis(start, 5, start + 99 * minute))
    }

    @Test
    fun `activity restarts the countdown`() {
        val firstDeadline = EphemeralPolicy.expiresAt(start, 10)!!
        val afterNewMessage = EphemeralPolicy.expiresAt(start + 9 * minute, 10)!!
        assertTrue(afterNewMessage > firstDeadline)
    }

    @Test
    fun `the presets start with off and grow`() {
        assertEquals(EphemeralPolicy.OFF, EphemeralPolicy.PRESETS.first())
        assertEquals(EphemeralPolicy.PRESETS.sorted(), EphemeralPolicy.PRESETS)
        assertTrue(EphemeralPolicy.PRESETS.drop(1).all { EphemeralPolicy.isEphemeral(it) })
    }
}
