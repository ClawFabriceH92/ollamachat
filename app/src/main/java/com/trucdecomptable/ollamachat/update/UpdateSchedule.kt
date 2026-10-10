package com.trucdecomptable.ollamachat.update

/**
 * When an automatic check is allowed to run.
 *
 * It lives apart from [UpdateManager] so the rule can be tested without
 * standing up a main-thread scope, and so the one that matters has a name:
 * a check that never reached GitHub does not hold the slot. The caller clears
 * the timestamp on failure, and this returns due again immediately.
 */
internal object UpdateSchedule {

    const val MIN_INTERVAL_MS = 60 * 60_000L

    /** No check has landed yet, or the last one failed and released the slot. */
    const val NEVER = 0L

    /**
     * The [NEVER] branch is spelled out rather than left to arithmetic: with a
     * wall clock, `now - 0` already exceeds any interval, so the released slot
     * would work by accident of the epoch being far from zero instead of
     * because the rule says so. A test with a small clock caught exactly that.
     */
    fun isDue(manual: Boolean, lastCheckAt: Long, now: Long): Boolean =
        manual || lastCheckAt == NEVER || now - lastCheckAt >= MIN_INTERVAL_MS
}
