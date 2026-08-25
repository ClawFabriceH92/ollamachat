package com.trucdecomptable.ollamachat.ui.conversations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trucdecomptable.ollamachat.OllamaChatApp
import com.trucdecomptable.ollamachat.data.db.Conversation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onOpenConversation: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    vm: ConversationsViewModel = viewModel(factory = ConversationsViewModel.Factory(app().container)),
) {
    val state by vm.uiState.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) } // 0 = actives, 1 = archives
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OllamaChat", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Réglages")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.createAndOpen(onOpenConversation) }) {
                Icon(Icons.Filled.Add, contentDescription = "Nouvelle conversation")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Actives (${state.active.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Archives (${state.archived.size})") })
            }

            val list = if (tab == 0) state.active else state.archived
            if (list.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = if (tab == 0) "Aucune conversation.\nTouche + pour en créer une."
                        else "Aucune conversation archivée.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(list, key = { it.id }) { conv ->
                        ConversationRow(
                            conv = conv,
                            onClick = { onOpenConversation(conv.id) },
                            onRename = { renameTarget = conv },
                            onArchive = { if (tab == 0) vm.archive(conv.id) else vm.restore(conv.id) },
                            onDelete = { deleteTarget = conv },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { conv ->
        var name by rememberSaveable(conv.id) { mutableStateOf(conv.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Renommer") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(conv.id, name.trim())
                    renameTarget = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Annuler") }
            },
        )
    }

    deleteTarget?.let { conv ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Supprimer définitivement ?") },
            text = { Text("« ${conv.title.ifBlank { "Nouvelle conversation" } } » et tous ses messages seront effacés. Action irréversible.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(conv.id)
                    deleteTarget = null
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Annuler") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conv: Conversation,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onRename)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conv.title.ifBlank { "Nouvelle conversation" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(conv.updatedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Filled.Edit, contentDescription = "Renommer")
        }
        IconButton(onClick = onArchive) {
            Icon(
                if (conv.archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                contentDescription = if (conv.archived) "Restaurer" else "Archiver",
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun app(): OllamaChatApp =
    (androidx.compose.ui.platform.LocalContext.current.applicationContext as OllamaChatApp)
