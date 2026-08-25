package com.trucdecomptable.ollamachat.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trucdecomptable.ollamachat.ui.settings.SettingsViewModel

/**
 * First-launch screen: ask for the Ollama server address, test it, pick a
 * model, then hand off to the conversations list.
 */
@Composable
fun WelcomeScreen(
    onDone: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(app().container)),
) {
    val state by vm.uiState.collectAsState()
    var url by rememberSaveable { mutableStateOf(state.baseUrl) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var model by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("OllamaChat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connectez votre téléphone à votre serveur Ollama.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        if (!showPicker) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Adresse du serveur") },
                placeholder = { Text("http://192.168.1.50:11434") },
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    vm.onBaseUrlChange(url.trim())
                    vm.testConnection()
                    showPicker = true
                },
                enabled = url.isNotBlank() && !state.testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Tester la connexion")
            }
            state.testResult?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.testOk == true) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Text("Choisissez le modèle par défaut :", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            state.models.forEach { m ->
                TextButton(onClick = { model = m.name }) {
                    Text(
                        m.name + if (m == state.models.firstOrNull { it.name == model }) "  ✓" else "",
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
                Text("Commencer à discuter")
            }
        }
    }
}

private fun app(): com.trucdecomptable.ollamachat.OllamaChatApp =
    (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.trucdecomptable.ollamachat.OllamaChatApp)
