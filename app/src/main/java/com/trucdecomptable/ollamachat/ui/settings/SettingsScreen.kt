package com.trucdecomptable.ollamachat.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trucdecomptable.ollamachat.OllamaChatApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(app().container)),
) {
    val state by vm.uiState.collectAsState()
    var showPinDialog by remember { mutableStateOf(false) }
    var showMcpDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Connexion")

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = vm::onBaseUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Adresse du serveur Ollama") },
                placeholder = { Text("http://192.168.1.50:11434") },
                singleLine = true,
            )
            OutlinedButton(
                onClick = { vm.scanNetwork() },
                enabled = !state.scanning && !state.testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.scanning) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan du réseau en cours…")
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Détecter automatiquement un serveur Ollama")
                }
            }
            state.scanResults.forEach { result ->
                Text(
                    text = "🖥️ ${result.baseUrl}" + (result.version?.let { "  (v$it)" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.applyScanResult(result) }
                        .padding(vertical = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vm.testConnection(state.baseUrl) },
                    enabled = !state.testing,
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.testing) "Test…" else "Tester la connexion")
                }
                Spacer(Modifier.width(12.dp))
                when {
                    state.testOk == true -> {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            state.testResult.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    state.testOk == false -> {
                        Text(
                            state.testResult.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            ModelDropdown(
                models = state.models.map { it.name },
                selected = state.model,
                onSelect = vm::onModelChange,
                onRefresh = { vm.refreshModels() },
            )

            HorizontalDivider()
            SectionTitle("Recherche web (pour répondre avec des infos à jour)")
            OutlinedTextField(
                value = state.braveApiKey,
                onValueChange = vm::onBraveApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Clé API Brave (optionnel)") },
                placeholder = { Text("Sans clé : recherche Wikipedia seulement") },
                singleLine = true,
            )
            Text(
                text = "Clé gratuite sur api.search.brave.com (2000 req/mois). Le bouton 🔍 cherche sur le web et injecte les résultats au modèle.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SectionTitle("Génération")

            SliderField(
                label = "Température",
                value = state.temperature,
                range = 0.0..2.0,
                display = { "%.2f".format(it) },
                onChange = vm::onTemperatureChange,
            )
            SliderField(
                label = "top_p",
                value = state.topP,
                range = 0.0..1.0,
                display = { "%.2f".format(it) },
                onChange = vm::onTopPChange,
            )
            IntField("top_k", state.topK, vm::onTopKChange)
            IntField("Max tokens (num_predict)", state.numPredict, vm::onNumPredictChange)
            IntField("Contexte (num_ctx)", state.numCtx, vm::onNumCtxChange)
            OutlinedTextField(
                value = state.keepAlive,
                onValueChange = vm::onKeepAliveChange,
                label = { Text("Keep alive (ex. 5m, -1 = toujours)") },
                singleLine = true,
            )
            SwitchRow(
                label = "Streaming (réponse en direct)",
                checked = state.streaming,
                onChange = vm::onStreamingChange,
            )
            SwitchRow(
                label = "Compacter automatiquement le contexte (résumé quand il est plein)",
                checked = state.contextCompactEnabled,
                onChange = vm::onContextCompactEnabledChange,
            )

            HorizontalDivider()
            SectionTitle("Serveurs MCP (outils externes)")
            state.mcpServers.forEach { server ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(server.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            server.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.removeMcpServer(server) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Supprimer",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            TextButton(onClick = { showMcpDialog = true }) {
                Text("Ajouter un serveur MCP")
            }
            Text(
                text = "Le modèle pourra utiliser les outils des serveurs MCP (protocole streamable HTTP, ex. http://IP:PORT/mcp).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SectionTitle("Prompt système par défaut")
            OutlinedTextField(
                value = state.defaultSystemPrompt,
                onValueChange = vm::onSystemPromptChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            HorizontalDivider()
            SectionTitle("Apparence")
            ThemeDropdown(selected = state.theme, onSelect = vm::onThemeChange)

            HorizontalDivider()
            SectionTitle("Sécurité")
            SwitchRow(
                label = "Verrouiller l'app (PIN ou biométrie)",
                checked = state.lockEnabled,
                onChange = vm::onLockEnabledChange,
            )
            if (state.lockEnabled) {
                SwitchRow(
                    label = "Verrouiller à la mise en arrière-plan",
                    checked = state.lockOnBackground,
                    onChange = vm::onLockOnBackgroundChange,
                )
                TextButton(onClick = { showPinDialog = true }) {
                    Text("Changer le PIN")
                }
            }
            Text(
                text = "PIN par défaut : 0000",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPinDialog) {
        PinChangeDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { old, new ->
                vm.changePin(old, new)
                showPinDialog = false
            },
            message = state.pinMessage,
        )
    }

    if (showMcpDialog) {
        McpServerDialog(
            onDismiss = { showMcpDialog = false },
            onAdd = { name, url ->
                vm.addMcpServer(name, url)
                showMcpDialog = false
            },
        )
    }
}

@Composable
private fun McpServerDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter un serveur MCP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom (ex. météo)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL du serveur (ex. http://192.168.0.50:8000/mcp)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, url) }, enabled = name.isNotBlank() && url.isNotBlank()) {
                Text("Ajouter")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Modèle") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m) },
                    onClick = { onSelect(m); expanded = false },
                )
            }
            if (models.isEmpty()) {
                DropdownMenuItem(text = { Text("Aucun modèle — tester la connexion") }, onClick = { expanded = false })
            }
        }
    }
    TextButton(onClick = onRefresh) { Text("Rafraîchir la liste des modèles") }
}

@Composable
private fun SliderField(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    display: (Double) -> String,
    onChange: (Double) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display(value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        )
    }
}

@Composable
private fun IntField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { new -> new.toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        singleLine = true,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeDropdown(selected: String, onSelect: (String) -> Unit) {
    val options = listOf("system" to "Système", "light" to "Clair", "dark" to "Sombre")
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "Système",
            onValueChange = {},
            readOnly = true,
            label = { Text("Thème") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(key); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun PinChangeDialog(
    onDismiss: () -> Unit,
    onConfirm: (old: String, new: String) -> Unit,
    message: String?,
) {
    var old by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Changer le PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = old,
                    onValueChange = { old = it.filter(Char::isDigit).take(4) },
                    label = { Text("Ancien PIN") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = new,
                    onValueChange = { new = it.filter(Char::isDigit).take(4) },
                    label = { Text("Nouveau PIN (4 chiffres)") },
                    singleLine = true,
                )
                if (!message.isNullOrBlank()) {
                    Text(
                        message,
                        color = if (message.contains("✅")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(old, new) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun app(): OllamaChatApp =
    (androidx.compose.ui.platform.LocalContext.current.applicationContext as OllamaChatApp)
