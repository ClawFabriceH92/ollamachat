package com.trucdecomptable.ollamachat.data.prefs

/**
 * How long the PIN pad stays closed after wrong attempts.
 *
 * A 4-digit code is only 10 000 candidates: without a growing delay the lock
 * is decorative against anyone holding the phone.
 */
object LockoutPolicy {

    /** Wrong tries allowed before any delay kicks in. */
    const val FREE_ATTEMPTS = 4

    private const val BASE_MS = 5_000L
    private const val MAX_MS = 5 * 60_000L

    fun lockoutMillis(attempts: Int): Long {
        if (attempts <= FREE_ATTEMPTS) return 0L
        val step = attempts - FREE_ATTEMPTS      // 1, 2, 3, ...
        return (BASE_MS shl (step - 1).coerceAtMost(10)).coerceAtMost(MAX_MS)
    }
}
