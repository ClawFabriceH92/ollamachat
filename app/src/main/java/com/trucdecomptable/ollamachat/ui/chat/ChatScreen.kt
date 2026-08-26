package com.trucdecomptable.ollamachat.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trucdecomptable.ollamachat.OllamaChatApp
import com.trucdecomptable.ollamachat.R
import com.trucdecomptable.ollamachat.data.db.Memory
import com.trucdecomptable.ollamachat.data.db.Message
import com.trucdecomptable.ollamachat.data.db.images
import com.trucdecomptable.ollamachat.data.ollama.ChatError
import com.trucdecomptable.ollamachat.data.ollama.ChatErrorCode
import com.trucdecomptable.ollamachat.data.repo.EphemeralPolicy
import com.trucdecomptable.ollamachat.ui.markdown.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    var showConfirmClear by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var showEphemeralDialog by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<Message?>(null) }
    var editTarget by remember { mutableStateOf<Message?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val turboAnnounced = remember { mutableStateOf<Boolean?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.ephemeralMinutes) {
        while (state.ephemeralMinutes > 0) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val remainingLabel = remainingLabel(state.ephemeralMinutes, state.lastActivity, now)

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.importDocuments(uris, context) }

    val streamingVisible = state.streamingText.isNotBlank() || state.streamingThinking.isNotBlank()
    val headerCount = if (state.hasOlderMessages) 1 else 0
    val itemCount = headerCount + state.messages.size + if (streamingVisible || state.isSending) 1 else 0

    // Only follow the stream while the user is already at the bottom, so
    // scrolling back through the conversation is not fought by every token.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }

    LaunchedEffect(state.toast) {
        state.toast?.let { message ->
            snackbarHostState.showSnackbar(message.resolve(context))
            vm.consumeToast()
        }
    }

    LaunchedEffect(state.turbo) {
        // Skip the very first composition: only report an actual change.
        if (turboAnnounced.value != null && turboAnnounced.value != state.turbo) {
            snackbarHostState.showSnackbar(
                context.getString(if (state.turbo) R.string.turbo_on else R.string.turbo_off)
            )
        }
        turboAnnounced.value = state.turbo
    }

    LaunchedEffect(state.error) {
        state.error?.let { error ->
            snackbarHostState.showSnackbar(errorText(context, error))
            vm.consumeError()
        }
    }

    LaunchedEffect(state.messages.size) {
        if (isAtBottom && itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    LaunchedEffect(state.streamingText) {
        // Instant scroll, not animated: an animation restarted 20x/s never ends.
        if (isAtBottom && itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.conversationTitle.ifBlank { stringResource(R.string.chat_new_conversation) },
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = state.activeModel.ifBlank { stringResource(R.string.chat_model_undefined) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            // A countdown you cannot see is a countdown you
                            // will be surprised by.
                            remainingLabel?.let { label ->
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Filled.Timer,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    // Reachable in one tap: a speed switch buried in the
                    // settings is a speed switch nobody flips.
                    IconButton(onClick = { vm.setTurbo(!state.turbo) }) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = stringResource(R.string.turbo_toggle),
                            tint = if (state.turbo) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_menu))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_rename)) },
                                onClick = { showMenu = false; showRenameDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_system_prompt)) },
                                onClick = { showMenu = false; showSystemPromptDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_model)) },
                                onClick = { showMenu = false; showModelDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_memory)) },
                                onClick = { showMenu = false; showMemoryDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Bookmark, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_ephemeral)) },
                                onClick = { showMenu = false; showEphemeralDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Timer, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_export)) },
                                onClick = {
                                    showMenu = false
                                    exportConversation(context, vm.exportMarkdown())
                                },
                                leadingIcon = { Icon(Icons.Filled.Share, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_read_url)) },
                                onClick = { showMenu = false; showUrlDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Language, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_clear)) },
                                onClick = { showMenu = false; showConfirmClear = true },
                                leadingIcon = { Icon(Icons.Filled.Cancel, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_archive)) },
                                onClick = { showMenu = false; vm.archiveConversation(); onBack() },
                                leadingIcon = { Icon(Icons.Filled.Archive, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_menu_delete)) },
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.hasOlderMessages) {
                    item(key = "older") {
                        TextButton(
                            onClick = { vm.loadOlderMessages() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.chat_load_older)) }
                    }
                }
                if (state.messages.isEmpty() && !state.isSending) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.chat_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        )
                    }
                }
                items(state.messages, key = { it.id }) { msg ->
                    MessageBubble(
                        message = msg,
                        onLongPress = { actionTarget = msg },
                    )
                }
                if (streamingVisible) {
                    item(key = "streaming") {
                        StreamBubble(text = state.streamingText, thinking = state.streamingThinking)
                    }
                } else if (state.isSending) {
                    item(key = "thinking") { ThinkingBubble() }
                }
            }

            AnimatedVisibility(
                visible = !isAtBottom && itemCount > 0,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(itemCount - 1) } },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.action_scroll_bottom),
                    )
                }
            }
        }
    }

    actionTarget?.let { message ->
        MessageActionsDialog(
            message = message,
            canRegenerate = message.role == "assistant" && !state.isSending,
            canEdit = message.role == "user" && !state.isSending,
            onCopy = {
                clipboard.setText(AnnotatedString(message.content))
                android.widget.Toast
                    .makeText(context, R.string.toast_copied, android.widget.Toast.LENGTH_SHORT)
                    .show()
                actionTarget = null
            },
            onEdit = {
                editTarget = message
                actionTarget = null
            },
            onRegenerate = {
                vm.regenerate()
                actionTarget = null
            },
            onDelete = {
                vm.deleteMessage(message)
                actionTarget = null
            },
            onDismiss = { actionTarget = null },
        )
    }

    editTarget?.let { message ->
        TextInputDialog(
            title = stringResource(R.string.chat_edit_title),
            initial = message.content,
            multiline = true,
            supporting = stringResource(R.string.chat_edit_body),
            confirm = { text ->
                vm.editAndResend(message.id, text)
                editTarget = null
            },
            dismiss = { editTarget = null },
        )
    }

    if (showRenameDialog) {
        TextInputDialog(
            title = stringResource(R.string.chat_rename_title),
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
            title = stringResource(R.string.chat_system_prompt_title),
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
            current = state.activeModel,
            confirm = { model ->
                vm.setConversationModel(model)
                showModelDialog = false
            },
            dismiss = { showModelDialog = false },
        )
    }

    if (showUrlDialog) {
        UrlDialog(
            onConfirm = { url ->
                showUrlDialog = false
                vm.fetchUrl(url)
            },
            onDismiss = { showUrlDialog = false },
        )
    }

    if (showEphemeralDialog) {
        EphemeralDialog(
            current = state.ephemeralMinutes,
            onSelect = { minutes ->
                vm.setEphemeral(minutes)
                showEphemeralDialog = false
            },
            onDismiss = { showEphemeralDialog = false },
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

    if (showConfirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.chat_clear_title),
            body = stringResource(R.string.chat_clear_body),
            onConfirm = {
                showConfirmClear = false
                vm.clearMessages()
            },
            onDismiss = { showConfirmClear = false },
        )
    }

    if (showConfirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.chat_delete_title),
            body = stringResource(R.string.chat_delete_body),
            onConfirm = {
                showConfirmDelete = false
                vm.deleteConversation()
                onBack()
            },
            onDismiss = { showConfirmDelete = false },
        )
    }
}

/** Maps a data-layer error code to a localized message. */
private fun errorText(context: Context, error: ChatError): String = when (error.code) {
    ChatErrorCode.CONNECTION -> context.getString(R.string.error_connection)
    ChatErrorCode.TIMEOUT -> context.getString(R.string.error_timeout)
    ChatErrorCode.HTTP -> context.getString(R.string.error_http, error.detail.orEmpty())
    ChatErrorCode.SERVER -> context.getString(R.string.error_server, error.detail.orEmpty())
    ChatErrorCode.EMPTY -> context.getString(R.string.error_empty)
    ChatErrorCode.NO_MODEL -> context.getString(R.string.error_no_model)
    ChatErrorCode.NO_CONVERSATION -> context.getString(R.string.error_no_conversation)
    ChatErrorCode.UNKNOWN ->
        if (error.detail.isNullOrBlank()) context.getString(R.string.error_generic)
        else context.getString(R.string.error_unknown, error.detail)
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
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onAttach) {
                Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.chat_attach))
            }
            IconButton(onClick = onSearchWeb, enabled = input.isNotBlank()) {
                Icon(Icons.Filled.TravelExplore, contentDescription = stringResource(R.string.chat_search_web))
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.width(4.dp))
            if (isSending) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.action_stop),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                IconButton(onClick = onSend, enabled = input.isNotBlank()) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.action_send),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: Message, onLongPress: () -> Unit) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val isSystem = message.role == "system"

    if (isTool || isSystem) {
        ContextBubble(message, onLongPress)
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                // Fraction of the available width instead of a fixed 320dp, so
                // tablets and landscape are not stuck with a narrow column.
                .fillMaxWidth(if (isUser) 0.85f else 0.92f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(12.dp),
        ) {
            if (message.images.isNotEmpty()) MessageImages(message.images)

            if (!message.thinking.isNullOrBlank()) {
                ThinkingSection(message.thinking)
            }

            if (message.content.isNotBlank()) {
                MarkdownText(
                    markdown = message.content,
                    color = MaterialTheme.colorScheme.onSurface,
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
                text = remember(message.createdAt) {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** Tool traces and injected context: collapsed by default, they are plumbing. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContextBubble(message: Message, onLongPress: () -> Unit) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val label = message.toolName?.let { stringResource(R.string.chat_tool_label, it) }
        ?: message.content.lineSequence().firstOrNull()?.take(60).orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = { expanded = !expanded }, onLongClick = onLongPress)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (message.toolName != null) Icons.Filled.Build else Icons.Filled.Language,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThinkingSection(thinking: String) {
    var expanded by rememberSaveable(thinking.length) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            Icon(
                Icons.Filled.Psychology,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.chat_reasoning),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Text(
                text = thinking,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MessageImages(paths: List<String>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        paths.forEach { path -> MessageImage(path) }
    }
}

@Composable
private fun MessageImage(path: String) {
    // Decoded off the main thread, and re-decoded only when the path changes.
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val file = File(path)
                if (!file.exists()) null
                else BitmapFactory.decodeFile(path)?.asImageBitmap()
            } catch (_: Throwable) {
                null
            }
        }
    }
    bitmap?.let { image ->
        Image(
            bitmap = image,
            contentDescription = stringResource(R.string.chat_image_sent),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(10.dp))
                .padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun StreamBubble(text: String, thinking: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        ) {
            if (thinking.isNotBlank() && text.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.chat_reasoning),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = thinking.takeLast(400),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (text.isNotBlank()) {
                MarkdownText(markdown = text, color = MaterialTheme.colorScheme.onSurface)
            }
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
            Text(stringResource(R.string.chat_answering), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Human-readable time left, or null when the conversation is permanent. */
@Composable
private fun remainingLabel(minutes: Int, lastActivity: Long, now: Long): String? {
    val remaining = EphemeralPolicy.remainingMillis(lastActivity, minutes, now) ?: return null
    val totalMinutes = (remaining / 60_000L).toInt()
    return when {
        totalMinutes < 1 -> stringResource(R.string.ephemeral_remaining_soon)
        totalMinutes < 60 -> stringResource(R.string.ephemeral_remaining_minutes, totalMinutes)
        else -> stringResource(R.string.ephemeral_remaining_hours, totalMinutes / 60)
    }
}

/** Label for one preset in the picker. */
@Composable
private fun ephemeralLabel(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.ephemeral_off)
    minutes < 60 -> stringResource(R.string.ephemeral_minutes, minutes)
    else -> stringResource(R.string.ephemeral_hours, minutes / 60)
}

@Composable
private fun EphemeralDialog(current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ephemeral_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EphemeralPolicy.PRESETS.forEach { minutes ->
                    Text(
                        text = ephemeralLabel(minutes) + if (minutes == current) "  ✓" else "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(minutes) }
                            .padding(vertical = 10.dp),
                        fontWeight = if (minutes == current) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.ephemeral_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.ephemeral_caveat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun MessageActionsDialog(
    message: Message,
    canRegenerate: Boolean,
    canEdit: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(message.content.take(48).ifBlank { stringResource(R.string.chat_image_sent) }) },
        text = {
            Column {
                ActionRow(Icons.Filled.ContentCopy, stringResource(R.string.action_copy), onCopy)
                if (canEdit) ActionRow(Icons.Filled.Edit, stringResource(R.string.action_edit), onEdit)
                if (canRegenerate) {
                    ActionRow(Icons.Filled.Refresh, stringResource(R.string.action_regenerate), onRegenerate)
                }
                ActionRow(
                    Icons.Filled.Delete,
                    stringResource(R.string.action_delete),
                    onDelete,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = tint, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun UrlDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_url_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.chat_url_label)) },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.chat_url_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url) }, enabled = url.isNotBlank()) {
                Text(stringResource(R.string.chat_url_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    multiline: Boolean = false,
    supporting: String? = null,
    confirm: (String) -> Unit,
    dismiss: () -> Unit,
) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = if (multiline) 4 else 1,
                )
                supporting?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { confirm(value) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    current: String,
    confirm: (String) -> Unit,
    dismiss: () -> Unit,
) {
    var selected by rememberSaveable(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.chat_model_title)) },
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
                if (models.isEmpty()) Text(stringResource(R.string.chat_model_empty))
            }
        },
        confirmButton = {
            TextButton(onClick = { confirm(selected) }, enabled = selected.isNotBlank()) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MemoryDialog(
    memories: List<Memory>,
    onAdd: (String) -> Unit,
    onDelete: (Memory) -> Unit,
    onDismiss: () -> Unit,
) {
    var newMemory by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_memory_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.chat_memory_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = newMemory,
                    onValueChange = { newMemory = it },
                    label = { Text(stringResource(R.string.chat_memory_new)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (memories.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_memory_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
                                    contentDescription = stringResource(R.string.action_delete),
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
            }) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Shares the conversation as a .md file via the Android share sheet. */
private fun exportConversation(context: Context, markdown: String) {
    try {
        val file = File(context.cacheDir, "ollamachat-export.md")
        file.writeText(markdown)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.chat_export_subject))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, context.getString(R.string.chat_export_chooser))
        )
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, R.string.toast_export_failed, android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}

@Composable
private fun app(): OllamaChatApp =
    (LocalContext.current.applicationContext as OllamaChatApp)
