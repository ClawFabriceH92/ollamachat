package com.trucdecomptable.ollamachat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trucdecomptable.ollamachat.ui.lock.LockScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * The pad on a real device, keys pressed one by one.
 *
 * It used to submit at the fourth digit whatever the PIN, then clear the
 * field: a 5- to 8-digit PIN, which the settings offered, could never be
 * typed, and the owner was locked out of their own conversations.
 */
@RunWith(AndroidJUnit4::class)
class LockScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val okLabel: String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.action_ok)

    private fun type(digits: String) =
        digits.forEach { compose.onNodeWithText(it.toString()).performClick() }

    @Test
    fun aSixDigitPinIsSubmittedWhole() {
        val submitted = Collections.synchronizedList(mutableListOf<String>())
        compose.setContent {
            LockScreen(
                biometricAvailable = false,
                lockedUntil = 0L,
                pinLength = 6,
                onSubmit = { submitted += it; true },
                onBiometric = {},
            )
        }
        type("1234")
        compose.waitForIdle()
        assertTrue("soumis dès le 4e chiffre : $submitted", submitted.isEmpty())

        type("56")
        compose.waitUntil(timeoutMillis = 5_000) { submitted.isNotEmpty() }
        assertEquals(listOf("123456"), submitted.toList())
    }

    @Test
    fun aPinOfUnknownLengthWaitsForOk() {
        // PINs set before the length was recorded: no auto-submit, OK instead.
        val submitted = Collections.synchronizedList(mutableListOf<String>())
        compose.setContent {
            LockScreen(
                biometricAvailable = false,
                lockedUntil = 0L,
                pinLength = null,
                onSubmit = { submitted += it; true },
                onBiometric = {},
            )
        }
        type("12345")
        compose.waitForIdle()
        assertTrue("soumis sans OK : $submitted", submitted.isEmpty())

        compose.onNodeWithText(okLabel).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { submitted.isNotEmpty() }
        assertEquals(listOf("12345"), submitted.toList())
    }

    @Test
    fun aFourDigitPinOfUnknownLengthCanStillBeConfirmed() {
        val submitted = Collections.synchronizedList(mutableListOf<String>())
        compose.setContent {
            LockScreen(
                biometricAvailable = false,
                lockedUntil = 0L,
                pinLength = null,
                onSubmit = { submitted += it; true },
                onBiometric = {},
            )
        }
        type("1234")
        compose.onNodeWithText(okLabel).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { submitted.isNotEmpty() }
        assertEquals(listOf("1234"), submitted.toList())
    }
}
