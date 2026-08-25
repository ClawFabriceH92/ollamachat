package com.trucdecomptable.ollamachat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trucdecomptable.ollamachat.OllamaChatApp
import com.trucdecomptable.ollamachat.data.db.Message
import com.trucdecomptable.ollamachat.ui.documents.DocumentExtractor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: ChatViewModel = viewModel(factory = ChatViewModel.Factory(conversationId, app().container)),
) {
    val state by vm.uiState.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showConfirmDelete by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "*/*"
            vm.importDocument(uri, mime, context)
        }
    }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeToast()
        }
    }

    // Auto-scroll to bottom when messages or streaming text change.
    LaunchedEffect(state.messages.size, state.streamingText.length) {
        if (state.messages.isNotEmpty() || state.streamingText.isNotEmpty()) {
            listState.animateScrollToItem(Int.MAX_VALUE)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.conversationTitle.ifBlank { "Nouvelle conversation" },
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = vm.activeModel.ifBlank { "modèle non défini" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Réglages")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Renommer") },
                                onClick = { showMenu = false; showRenameDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Prompt système…") },
                                onClick = { showMenu = false; showSystemPromptDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Changer de modèle…") },
                                onClick = { showMenu = false; showModelDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Mémoire…") },
                                onClick = { showMenu = false; showMemoryDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Bookmark, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Exporter la conversation…") },
                                onClick = {
                                    showMenu = false
                                    exportConversation(context, vm.exportMarkdown())
                                },
                                leadingIcon = { Icon(Icons.Filled.Share, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Lire une page web…") },
                                onClick = { showMenu = false; showUrlDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Language, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Vider la conversation") },
                                onClick = {
                                    showMenu = false
                                    vm.clearMessages()
                                },
                                leadingIcon = { Icon(Icons.Filled.Cancel, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Archiver") },
                                onClick = { showMenu = false; vm.archiveConversation(); onBack() },
                                leadingIcon = { Icon(Icons.Filled.Archive, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Supprimer définitivement") },
                                onClick = { showMenu = false; showConfirmDelete = true },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            ChatInputBar(
                input = input,
                onInputChange = { input = it },
                isSending = state.isSending,
                onSend = {
                    if (input.isNotBlank()) {
                        vm.send(input)
                        input = ""
                    }
                },
                onCancel = { vm.cancel() },
                onSearchWeb = {
                    if (input.isNotBlank()) {
                        vm.searchAndSend(input)
                        input = ""
                    }
                },
                onAttach = {
                    documentLauncher.launch(
                        arrayOf(
                            "text/*",
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "image/*",
                        )
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = { it.id }) { msg ->
                MessageBubble(
                    message = msg,
                    onCopy = {
                        if (msg.content.isNotBlank()) {
                            clipboard.setText(AnnotatedString(msg.content))
                        }
                    },
                )
            }
            if (state.streamingText.isNotBlank()) {
                item(key = "streaming") {
                    StreamBubble(text = state.streamingText)
                }
            } else if (state.isSending) {
                item(key = "thinking") {
                    ThinkingBubble()
                }
            }
        }
    }

    if (showRenameDialog) {
        TextInputDialog(
            title = "Renommer la conversation",
            initial = state.conversationTitle,
            confirm = { name ->
                vm.renameConversation(name)
                showRenameDialog = false
            },
            dismiss = { showRenameDialog = false },
        )
    }

    if (showSystemPromptDialog) {
        TextInputDialog(
            title = "Prompt système de cette conversation",
            initial = state.systemPrompt ?: "",
            multiline = true,
            confirm = { prompt ->
                vm.setConversationSystemPrompt(prompt)
                showSystemPromptDialog = false
            },
            dismiss = { showSystemPromptDialog = false },
        )
    }

    if (showModelDialog) {
        ModelPickerDialog(
            models = state.models.map { it.name },
            current = vm.activeModel,
            confirm = { model ->
                vm.setConversationModel(model)
                showModelDialog = false
            },
            dismiss = { showModelDialog = false },
        )
    }

    if (showUrlDialog) {
        var url by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Lire une page web") },
            text = {
                Column {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        placeholder = { Text("https://…") },
                        singleLine = true,
                    )
                    Text(
                        "Le contenu de la page sera ajouté à la conversation, puis tu poses ta question dessus.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUrlDialog = false
                    vm.fetchUrl(url)
                }) { Text("Lire") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("Annuler") }
            },
        )
    }

    if (showMemoryDialog) {
        MemoryDialog(
            memories = vm.memories.collectAsState().value,
            onAdd = { vm.addMemory(it) },
            onDelete = { vm.deleteMemory(it) },
            onDismiss = { showMemoryDialog = false },
        )
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Supprimer définitivement ?") },
            text = { Text("Tous les messages de cette conversation seront effacés. Action irréversible.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDelete = false
                    vm.deleteConversation()
                    onBack()
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onSearchWeb: () -> Unit,
    onAttach: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        IconButton(onClick = onAttach) {
            Icon(Icons.Filled.AttachFile, contentDescription = "Importer un document")
        }
        IconButton(onClick = onSearchWeb, enabled = input.isNotBlank()) {
            Icon(Icons.Filled.TravelExplore, contentDescription = "Rechercher sur le web")
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Écrire un message…") },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        if (isSending) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Stop, contentDescription = "Arrêter", tint = MaterialTheme.colorScheme.error)
            }
        } else {
            IconButton(onClick = onSend, enabled = input.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Envoyer",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: Message, onCopy: () -> Unit) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = onCopy,
                )
                .padding(12.dp),
        ) {
            if (message.contentType == "image") {
                Text(
                    text = "🖼️ Image envoyée",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (message.content.isNotBlank()) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            message.stats?.let { stats ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "⚡ $stats",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun StreamBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("En train de répondre…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    multiline: Boolean = false,
    confirm: (String) -> Unit,
    dismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = if (multiline) 4 else 1,
            )
        },
        confirmButton = {
            TextButton(onClick = { confirm(value) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    current: String,
    confirm: (String) -> Unit,
    dismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Modèle de cette conversation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                models.forEach { m ->
                    Text(
                        text = m + if (m == selected) "  ✓" else "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = m }
                            .padding(vertical = 8.dp),
                        fontWeight = if (m == selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                if (models.isEmpty()) {
                    Text("Aucun modèle — vérifie la connexion au serveur")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm(selected) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun MemoryDialog(
    memories: List<com.trucdecomptable.ollamachat.data.db.Memory>,
    onAdd: (String) -> Unit,
    onDelete: (com.trucdecomptable.ollamachat.data.db.Memory) -> Unit,
    onDismiss: () -> Unit,
) {
    var newMemory by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mémoire persistante") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ces faits sont injectés dans chaque conversation pour que le modèle te connaisse.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = newMemory,
                    onValueChange = { newMemory = it },
                    label = { Text("Nouveau fait à retenir") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (memories.isEmpty()) {
                    Text("Aucune mémoire pour l'instant.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    memories.forEach { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = m.content,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(onClick = { onDelete(m) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Supprimer",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newMemory.isNotBlank()) {
                    onAdd(newMemory)
                    newMemory = ""
                }
            }) { Text("Ajouter") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        },
    )
}

/** Shares the conversation as a .md file via the Android share sheet. */
private fun exportConversation(context: android.content.Context, markdown: String) {
    try {
        val file = java.io.File(context.cacheDir, "ollamachat-export.md")
        file.writeText(markdown)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Conversation OllamaChat")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Exporter la conversation"))
    } catch (_: Exception) {
        // Share sheet unavailable — ignore.
    }
}

@Composable
private fun app(): OllamaChatApp =
    (androidx.compose.ui.platform.LocalContext.current.applicationContext as OllamaChatApp)
