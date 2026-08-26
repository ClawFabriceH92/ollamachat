package com.trucdecomptable.ollamachat.ui.welcome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trucdecomptable.ollamachat.R
import com.trucdecomptable.ollamachat.data.ollama.NetworkScanner
import com.trucdecomptable.ollamachat.ui.settings.SettingsViewModel

/**
 * First-launch screen: ask for the Ollama server address (manual or auto
 * discovery), test it, pick a model, then hand off to the conversations list.
 *
 * The model step is only shown AFTER a successful connection test — otherwise
 * the user would be stuck on an empty model list. The whole screen scrolls so
 * long model lists never push the confirm button off screen.
 */
@Composable
fun WelcomeScreen(
    onDone: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(app().container)),
) {
    val state by vm.uiState.collectAsState()
    var url by rememberSaveable { mutableStateOf(state.baseUrl) }
    var model by rememberSaveable { mutableStateOf("") }
    val connected = state.testOk == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        if (!connected) {
            // --- Step 1: server address (manual or auto) + connection test ---
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_server_url)) },
                placeholder = { Text(stringResource(R.string.settings_server_url_hint)) },
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { vm.scanNetwork() },
                enabled = !state.scanning && !state.testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.scanning) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_scanning))
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_scan))
                }
            }

            // Scan results (tap to select)
            state.scanResults.forEach { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = result.baseUrl + (result.version?.let { " (v$it)" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            url = result.baseUrl
                            vm.applyScanResult(result)
                        }
                        .padding(vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    vm.onBaseUrlChange(url.trim())
                    vm.testConnection(url.trim())
                },
                enabled = url.isNotBlank() && !state.testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(if (state.testing) R.string.settings_testing else R.string.settings_test))
            }
            if (state.testOk == false) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_test_failed, state.testResult.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // --- Step 2: pick the default model ---
            Text(stringResource(R.string.welcome_connected), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.welcome_pick_model), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            if (state.models.isEmpty()) {
                Text(
                    stringResource(R.string.welcome_no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    vm.onBaseUrlChange(url.trim())
                    vm.testConnection(url.trim())
                }) { Text(stringResource(R.string.welcome_retest)) }
            } else {
                state.models.forEach { m ->
                    TextButton(onClick = { model = m.name }) {
                        Text(
                            m.name + if (m.name == model) "  ✓" else "",
                            fontWeight = if (model == m.name) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        vm.onModelChange(model)
                        vm.onBaseUrlChange(url.trim())
                        vm.onFirstLaunchDone()
                        onDone()
                    },
                    enabled = model.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.welcome_finish))
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun app(): com.trucdecomptable.ollamachat.OllamaChatApp =
    (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.trucdecomptable.ollamachat.OllamaChatApp)
