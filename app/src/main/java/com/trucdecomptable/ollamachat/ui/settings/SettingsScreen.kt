package com.trucdecomptable.ollamachat.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trucdecomptable.ollamachat.OllamaChatApp
import com.trucdecomptable.ollamachat.R
import com.trucdecomptable.ollamachat.data.backup.BackupCrypto
import com.trucdecomptable.ollamachat.ui.chat.resolve
import com.trucdecomptable.ollamachat.util.PinUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(app().container)),
) {
    val state by vm.uiState.collectAsState()
    var showPinDialog by remember { mutableStateOf(false) }
    var showMcpDialog by remember { mutableStateOf(false) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var exportUri by remember { mutableStateOf<Uri?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // The file is chosen first, the passphrase asked second: cancelling the
    // picker then leaves nothing half-entered behind.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> exportUri = uri }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> importUri = uri }

    LaunchedEffect(state.backupMessage) {
        state.backupMessage?.let { message ->
            if (!state.backupBusy) {
                snackbarHostState.showSnackbar(message.resolve(context))
                vm.consumeBackupMessage()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
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
            SectionTitle(stringResource(R.string.settings_section_connection))

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = vm::onBaseUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_server_url)) },
                placeholder = { Text(stringResource(R.string.settings_server_url_hint)) },
                singleLine = true,
            )
            OutlinedButton(
                onClick = { vm.scanNetwork() },
                enabled = !state.scanning && !state.testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_scanning))
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_scan))
                }
            }
            state.scanResults.forEach { result ->
                Text(
                    text = result.baseUrl + (result.version?.let { " (v$it)" } ?: ""),
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
                Button(onClick = { vm.testConnection(state.baseUrl) }, enabled = !state.testing) {
                    if (state.testing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(
                            if (state.testing) R.string.settings_testing else R.string.settings_test
                        )
                    )
                }
                Spacer(Modifier.width(12.dp))
                when (state.testOk) {
                    true -> {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.settings_test_ok),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    false -> Text(
                        stringResource(R.string.settings_test_failed, state.testResult.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    null -> Unit
                }
            }

            ModelDropdown(
                models = state.models.map { it.name },
                selected = state.model,
                onSelect = vm::onModelChange,
                onRefresh = { vm.refreshModels() },
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_web))
            OutlinedTextField(
                value = state.braveApiKey,
                onValueChange = vm::onBraveApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_brave_key)) },
                placeholder = { Text(stringResource(R.string.settings_brave_key_hint)) },
                singleLine = true,
                // An API key should not sit in plain sight on screen.
                visualTransformation = if (showKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (showKey) R.string.settings_hide_key else R.string.settings_show_key
                            ),
                        )
                    }
                },
            )
            Hint(stringResource(R.string.settings_brave_help))

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_generation))

            SliderField(
                label = stringResource(R.string.settings_temperature),
                value = state.temperature,
                range = 0.0..2.0,
                display = { "%.2f".format(it) },
                onChange = vm::onTemperatureChange,
            )
            SliderField(
                label = stringResource(R.string.settings_top_p),
                value = state.topP,
                range = 0.0..1.0,
                display = { "%.2f".format(it) },
                onChange = vm::onTopPChange,
            )
            IntField(stringResource(R.string.settings_top_k), state.topK, vm::onTopKChange)
            IntField(stringResource(R.string.settings_num_predict), state.numPredict, vm::onNumPredictChange)
            IntField(stringResource(R.string.settings_num_ctx), state.numCtx, vm::onNumCtxChange)
            OutlinedTextField(
                value = state.keepAlive,
                onValueChange = vm::onKeepAliveChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_keep_alive)) },
                singleLine = true,
            )
            SwitchRow(
                label = stringResource(R.string.settings_streaming),
                checked = state.streaming,
                onChange = vm::onStreamingChange,
            )
            SwitchRow(
                label = stringResource(R.string.settings_compact),
                checked = state.contextCompactEnabled,
                onChange = vm::onContextCompactEnabledChange,
            )
            Hint(stringResource(R.string.settings_compact_help))
            SwitchRow(
                label = stringResource(R.string.settings_think),
                checked = state.thinkEnabled,
                onChange = vm::onThinkEnabledChange,
            )
            Hint(stringResource(R.string.settings_think_help))
            SwitchRow(
                label = stringResource(R.string.settings_tools),
                checked = state.toolsEnabled,
                onChange = vm::onToolsEnabledChange,
            )
            Hint(stringResource(R.string.settings_tools_help))

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_mcp))
            state.mcpServers.forEach { server ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            TextButton(onClick = { showMcpDialog = true }) {
                Text(stringResource(R.string.settings_mcp_add))
            }
            Hint(stringResource(R.string.settings_mcp_help))

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_prompt))
            OutlinedTextField(
                value = state.defaultSystemPrompt,
                onValueChange = vm::onSystemPromptChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_appearance))
            ThemeDropdown(selected = state.theme, onSelect = vm::onThemeChange)

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_security))
            SwitchRow(
                label = stringResource(R.string.settings_lock),
                checked = state.lockEnabled,
                onChange = vm::onLockEnabledChange,
            )
            if (state.lockEnabled) {
                SwitchRow(
                    label = stringResource(R.string.settings_lock_background),
                    checked = state.lockOnBackground,
                    onChange = vm::onLockOnBackgroundChange,
                )
            }
            TextButton(onClick = { showPinDialog = true }) {
                Text(
                    stringResource(
                        if (state.hasPin) R.string.settings_change_pin else R.string.settings_set_pin
                    )
                )
            }
            if (!state.hasPin) Hint(stringResource(R.string.settings_lock_needs_pin))
            Hint(stringResource(R.string.settings_security_help))

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_data))
            OutlinedButton(
                onClick = { exportLauncher.launch("ollamachat-sauvegarde.ocb") },
                enabled = !state.backupBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_export)) }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = !state.backupBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_import)) }
            if (state.backupBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_working), style = MaterialTheme.typography.bodySmall)
                }
            }
            Hint(stringResource(R.string.settings_backup_help))

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPinDialog) {
        PinChangeDialog(
            requiresOldPin = state.hasPin,
            message = state.pinMessage?.let { stringResource(it.resId) },
            onDismiss = {
                showPinDialog = false
                vm.consumePinMessage()
            },
            onConfirm = { old, new, confirm -> vm.changePin(old, new, confirm) },
        )
    }

    exportUri?.let { uri ->
        PassphraseDialog(
            title = stringResource(R.string.backup_export_title),
            confirmPassphrase = true,
            note = stringResource(R.string.settings_backup_help),
            onDismiss = { exportUri = null },
            onConfirm = { passphrase ->
                vm.exportBackup(context, uri, passphrase)
                exportUri = null
            },
        )
    }

    importUri?.let { uri ->
        PassphraseDialog(
            title = stringResource(R.string.backup_import_title),
            confirmPassphrase = false,
            note = stringResource(R.string.backup_import_note),
            onDismiss = { importUri = null },
            onConfirm = { passphrase ->
                vm.importBackup(context, uri, passphrase)
                importUri = null
            },
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
private fun McpServerDialog(onDismiss: () -> Unit, onAdd: (name: String, url: String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_mcp_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_mcp_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.settings_mcp_url)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, url) }, enabled = name.isNotBlank() && url.isNotBlank()) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PassphraseDialog(
    title: String,
    confirmPassphrase: Boolean,
    note: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }

    val tooShort = passphrase.length < BackupCrypto.MIN_PASSPHRASE
    val mismatch = confirmPassphrase && confirmation != passphrase
    val error = when {
        passphrase.isEmpty() -> null
        tooShort -> stringResource(R.string.backup_passphrase_too_short, BackupCrypto.MIN_PASSPHRASE)
        mismatch && confirmation.isNotEmpty() -> stringResource(R.string.backup_passphrase_mismatch)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (visible) R.string.settings_hide_key else R.string.settings_show_key
                                ),
                            )
                        }
                    },
                )
                if (confirmPassphrase) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text(stringResource(R.string.backup_passphrase_confirm)) },
                        singleLine = true,
                        visualTransformation = if (visible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Hint(note)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = !tooShort && !mismatch,
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            label = { Text(stringResource(R.string.settings_model)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { m ->
                DropdownMenuItem(text = { Text(m) }, onClick = { onSelect(m); expanded = false })
            }
            if (models.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_model_empty)) },
                    onClick = { expanded = false },
                )
            }
        }
    }
    TextButton(onClick = onRefresh) { Text(stringResource(R.string.settings_model_refresh)) }
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
        Slider(
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
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeDropdown(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "system" to stringResource(R.string.settings_theme_system),
        "light" to stringResource(R.string.settings_theme_light),
        "dark" to stringResource(R.string.settings_theme_dark),
    )
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: options[0].second,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_theme)) },
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
    requiresOldPin: Boolean,
    message: String?,
    onDismiss: () -> Unit,
    onConfirm: (old: String, new: String, confirm: String) -> Unit,
) {
    var old by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    fun sanitize(value: String) = value.filter(Char::isDigit).take(PinUtils.MAX_LENGTH)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (requiresOldPin) {
                    OutlinedTextField(
                        value = old,
                        onValueChange = { old = sanitize(it) },
                        label = { Text(stringResource(R.string.pin_old)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = new,
                    onValueChange = { new = sanitize(it) },
                    label = { Text(stringResource(R.string.pin_new)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = sanitize(it) },
                    label = { Text(stringResource(R.string.pin_confirm)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                if (!message.isNullOrBlank()) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(old, new, confirm) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun app(): OllamaChatApp =
    (LocalContext.current.applicationContext as OllamaChatApp)
