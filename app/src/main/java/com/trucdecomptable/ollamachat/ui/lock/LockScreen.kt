package com.trucdecomptable.ollamachat.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trucdecomptable.ollamachat.R
import com.trucdecomptable.ollamachat.util.PinUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen PIN pad.
 *
 * [onSubmit] verifies the code off the main thread (the hash is deliberately
 * slow) and returns true on success; [lockedUntil] is the epoch millis before
 * which entry is refused after too many wrong tries.
 */
@Composable
fun LockScreen(
    biometricAvailable: Boolean,
    lockedUntil: Long,
    onSubmit: suspend (String) -> Boolean,
    onBiometric: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(lockedUntil) {
        while (true) {
            val left = lockedUntil - System.currentTimeMillis()
            remainingSeconds = if (left > 0) (left + 999) / 1000 else 0
            if (remainingSeconds <= 0L) break
            delay(500)
        }
    }

    val blocked = remainingSeconds > 0L

    fun submit() {
        if (checking || blocked) return
        val candidate = pin
        checking = true
        scope.launch {
            val ok = onSubmit(candidate)
            checking = false
            pin = ""
            error = !ok
        }
    }

    fun press(digit: Char) {
        if (checking || blocked) return
        if (pin.length >= PinUtils.MAX_LENGTH) return
        pin += digit
        error = false
        if (pin.length == PinUtils.MIN_LENGTH) submit()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.lock_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    blocked -> stringResource(R.string.lock_locked_out, remainingSeconds.toInt())
                    error -> stringResource(R.string.lock_wrong_pin)
                    else -> stringResource(R.string.lock_enter_pin)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (blocked || error) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(PinUtils.MAX_LENGTH.coerceAtMost(maxOf(pin.length, PinUtils.MIN_LENGTH))) { index ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(
                                color = if (index < pin.length) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                shape = CircleShape,
                            )
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(24.dp))
            }

            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { digit -> DigitKey(digit) { press(digit) } }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(72.dp)) {
                    if (biometricAvailable) {
                        IconButton(onClick = onBiometric, modifier = Modifier.size(72.dp)) {
                            Icon(
                                Icons.Filled.Fingerprint,
                                contentDescription = stringResource(R.string.lock_biometric),
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
                DigitKey('0') { press('0') }
                Box(modifier = Modifier.size(72.dp)) {
                    IconButton(
                        onClick = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = stringResource(R.string.lock_backspace),
                        )
                    }
                }
            }

            if (pin.length > PinUtils.MIN_LENGTH) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { submit() }, enabled = !checking && !blocked) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.size(72.dp)) {
        Text(digit.toString(), fontSize = 24.sp, fontWeight = FontWeight.Medium)
    }
}
