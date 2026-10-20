package com.trucdecomptable.ollamachat.util

import com.trucdecomptable.ollamachat.data.prefs.LockConfig
import com.trucdecomptable.ollamachat.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What entering a PIN does to the stored lock, apart from unlocking.
 *
 * Out of the activity so it can be tested: the length recorded here decides
 * when the pad submits on its own, and a wrong one is a lock screen nobody
 * gets past.
 */
object PinUnlock {

    /**
     * Checks [pin] against [lock]. A match upgrades an old hash, learns the
     * length of a PIN set before lengths were recorded, and clears the
     * failures; a mismatch counts one more.
     */
    suspend fun verifyAndRecord(settings: SettingsRepository, lock: LockConfig, pin: String): Boolean {
        // PBKDF2 is intentionally slow: never on the main thread.
        val ok = withContext(Dispatchers.Default) { PinUtils.verify(pin, lock.pinHash) }
        if (!ok) {
            settings.recordFailedUnlock()
            return false
        }
        if (PinUtils.needsUpgrade(lock.pinHash)) {
            val upgraded = withContext(Dispatchers.Default) { PinUtils.hash(pin) }
            settings.setPinHash(upgraded, pin.length)
        } else if (lock.pinLength == null) {
            // A PIN set before the length was recorded: learn it now, so the
            // pad can submit on its own from here on.
            settings.setPinLength(pin.length)
        }
        settings.clearFailedUnlocks()
        return true
    }
}
