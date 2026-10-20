package com.trucdecomptable.ollamachat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trucdecomptable.ollamachat.data.prefs.LockConfig
import com.trucdecomptable.ollamachat.data.prefs.SettingsRepository
import com.trucdecomptable.ollamachat.util.PinUnlock
import com.trucdecomptable.ollamachat.util.PinUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The length stored with the PIN decides when the pad submits on its own:
 * a wrong one is a lock screen nobody gets past.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PinUnlockTest {

    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository(context)
        // DataStore is process-wide: start from a known length.
        runBlocking {
            settings.setPinLength(99)
            settings.clearFailedUnlocks()
        }
    }

    @Test
    fun `setting a pin records its length with it`() = runBlocking {
        settings.setPinHash(PinUtils.hash("123456"), 6)
        assertEquals(6, settings.lockConfig.first().pinLength)

        // Changing it to a shorter one: the length follows.
        settings.setPinHash(PinUtils.hash("1234"), 4)
        assertEquals(4, settings.lockConfig.first().pinLength)
    }

    @Test
    fun `the first unlock of an old pin learns its length`() = runBlocking {
        val hash = PinUtils.hash("12345678")
        settings.setPinHash(hash, 99)
        // As MainActivity sees a PIN set before lengths were recorded.
        val legacy = LockConfig(enabled = true, pinHash = hash, lockOnBackground = false, pinLength = null)

        assertTrue(PinUnlock.verifyAndRecord(settings, legacy, "12345678"))
        assertEquals(8, settings.lockConfig.first().pinLength)
    }

    @Test
    fun `a wrong pin learns nothing and counts a failure`() = runBlocking {
        val hash = PinUtils.hash("123456")
        settings.setPinHash(hash, 6)
        val lock = LockConfig(enabled = true, pinHash = hash, lockOnBackground = false, pinLength = null)

        assertFalse(PinUnlock.verifyAndRecord(settings, lock, "654321"))
        assertEquals(6, settings.lockConfig.first().pinLength)
        assertEquals(1, settings.lockConfig.first().failedAttempts)
    }
}
