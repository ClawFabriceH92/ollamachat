package com.trucdecomptable.ollamachat.ui.update

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trucdecomptable.ollamachat.BuildConfig
import com.trucdecomptable.ollamachat.R
import com.trucdecomptable.ollamachat.update.UpdateManager

/**
 * The update prompt, in the app rather than in a notification.
 *
 * The previous flow relied on a system broadcast that never arrived, so an
 * update downloaded and then sat there. Here the whole thing — offer,
 * progress, install — happens where the user can see it.
 */
@Composable
fun UpdateDialog(
    state: UpdateManager.State,
    context: Context,
    onSkip: (version: String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateManager.State.Available -> AlertDialog(
            onDismissRequest = { onSkip(state.version) },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.update_available_body,
                            state.version,
                            BuildConfig.VERSION_NAME,
                        )
                    )
                    if (state.notes.isNotBlank()) {
                        Text(
                            state.notes.take(300),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!UpdateManager.canInstall(context)) {
                        Text(
                            stringResource(R.string.update_permission_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                if (UpdateManager.canInstall(context)) {
                    TextButton(onClick = { UpdateManager.download(context) }) {
                        Text(stringResource(R.string.update_now))
                    }
                } else {
                    TextButton(onClick = { UpdateManager.openInstallSettings(context) }) {
                        Text(stringResource(R.string.update_open_settings))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { onSkip(state.version) }) {
                    Text(stringResource(R.string.update_later))
                }
            },
        )

        is UpdateManager.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading, state.version)) },
            text = {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {},
        )

        is UpdateManager.State.ReadyToInstall -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_ready_title)) },
            text = { Text(stringResource(R.string.update_ready_body, state.version)) },
            confirmButton = {
                TextButton(onClick = { UpdateManager.install(context) }) {
                    Text(stringResource(R.string.update_install))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_later)) }
            },
        )

        is UpdateManager.State.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(state.detail.orEmpty().ifBlank { stringResource(R.string.error_generic) }) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            },
        )

        is UpdateManager.State.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_up_to_date, state.version)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            },
        )

        UpdateManager.State.Idle -> Unit
    }
}
