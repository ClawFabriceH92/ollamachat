package com.trucdecomptable.ollamachat.data.repo

/**
 * When an ephemeral conversation is due for deletion.
 *
 * The countdown runs from the last activity, not from creation: a
 * conversation you are still using must not vanish mid-sentence.
 *
 * Pure functions — this decides when the user's data is destroyed, so it is
 * covered by tests rather than trusted by reading.
 */
object EphemeralPolicy {

    /** 0 means "kept indefinitely". */
    const val OFF = 0

    /** Offered in the picker, in minutes. */
    val PRESETS = listOf(OFF, 5, 15, 60, 8 * 60, 24 * 60)

    private const val MINUTE_MS = 60_000L

    fun isEphemeral(minutes: Int): Boolean = minutes > OFF

    /** Deletion deadline, or null when the conversation is permanent. */
    fun expiresAt(lastActivity: Long, minutes: Int): Long? =
        if (isEphemeral(minutes)) lastActivity + minutes * MINUTE_MS else null

    fun isExpired(lastActivity: Long, minutes: Int, now: Long): Boolean {
        val deadline = expiresAt(lastActivity, minutes) ?: return false
        return now >= deadline
    }

    /** Milliseconds left, floored at zero; null when the conversation is permanent. */
    fun remainingMillis(lastActivity: Long, minutes: Int, now: Long): Long? {
        val deadline = expiresAt(lastActivity, minutes) ?: return null
        return (deadline - now).coerceAtLeast(0L)
    }
}
