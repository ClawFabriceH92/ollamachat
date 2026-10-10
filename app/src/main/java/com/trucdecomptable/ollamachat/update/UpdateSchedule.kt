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

    /** [lastCheckAt] of 0 means "nothing landed yet", so a check is due. */
    fun isDue(manual: Boolean, lastCheckAt: Long, now: Long): Boolean =
        manual || now - lastCheckAt >= MIN_INTERVAL_MS
}
