package com.trucdecomptable.ollamachat.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trucdecomptable.ollamachat.R
import com.trucdecomptable.ollamachat.data.ollama.ConnectionStatus
import com.trucdecomptable.ollamachat.ui.theme.connected

/**
 * Green when the Ollama server answers, red when it does not, grey until the
 * first probe comes back.
 *
 * Colour alone says nothing to a screen reader, so the state is also spelled
 * out in the content description — and, wherever this dot is tappable, in the
 * snackbar the tap raises.
 */
@Composable
fun ConnectionDot(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
) {
    val color = when (status) {
        ConnectionStatus.ONLINE -> MaterialTheme.colorScheme.connected
        ConnectionStatus.OFFLINE -> MaterialTheme.colorScheme.error
        ConnectionStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    Icon(
        imageVector = Icons.Filled.Circle,
        contentDescription = stringResource(connectionLabel(status)),
        modifier = modifier.size(size),
        tint = color,
    )
}

/** The status in words — used for the dot's label and for the tap feedback. */
@StringRes
fun connectionLabel(status: ConnectionStatus): Int = when (status) {
    ConnectionStatus.ONLINE -> R.string.connection_online
    ConnectionStatus.OFFLINE -> R.string.connection_offline
    ConnectionStatus.UNKNOWN -> R.string.connection_checking
}
