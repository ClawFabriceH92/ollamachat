package com.trucdecomptable.ollamachat.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trucdecomptable.ollamachat.util.PinUtils

/**
 * Full-screen PIN pad. Calls [onUnlock] when the entered PIN matches [expectedHash],
 * and [onBiometric] when the user taps the fingerprint button (enabled only when
 * [biometricAvailable]).
 */
@Composable
fun LockScreen(
    expectedHash: String,
    biometricAvailable: Boolean,
    onUnlock: () -> Unit,
    onBiometric: () -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }

    fun submit() {
        if (PinUtils.hash(pin) == expectedHash) {
            pin = ""
            error = false
            onUnlock()
        } else {
            error = true
            pin = ""
        }
    }

    fun press(digit: Char) {
        if (pin.length >= 4) return
        pin += digit
        error = false
        if (pin.length == 4) submit()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "OllamaChat verrouillé",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Entrez votre code PIN",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            // Dots
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { i ->
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                color = when {
                                    i < pin.length -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = CircleShape,
                            )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (error) {
                Text(
                    text = "Code PIN incorrect",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(32.dp))

            // Keypad
            val rows = listOf(
                listOf('1', '2', '3'),
                listOf('4', '5', '6'),
                listOf('7', '8', '9'),
                listOf('\u0000', '0', '\u0000'),
            )
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    row.forEach { key ->
                        Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                            when (key) {
                                '\u0000' -> {
                                    if (key == row[0]) {
                                        if (biometricAvailable) {
                                            IconButton(onClick = onBiometric) {
                                                Icon(
                                                    imageVector = Icons.Filled.Fingerprint,
                                                    contentDescription = "Biométrie",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(36.dp),
                                                )
                                            }
                                        } else {
                                            Spacer(Modifier.size(36.dp))
                                        }
                                    } else {
                                        IconButton(onClick = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }) {
                                            Icon(
                                                imageVector = Icons.Filled.Backspace,
                                                contentDescription = "Effacer",
                                                modifier = Modifier.size(28.dp),
                                            )
                                        }
                                    }
                                }
                                else -> TextButton(onClick = { press(key) }) {
                                    Text(
                                        text = key.toString(),
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "PIN par défaut : 0000 (modifiable dans les réglages)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
