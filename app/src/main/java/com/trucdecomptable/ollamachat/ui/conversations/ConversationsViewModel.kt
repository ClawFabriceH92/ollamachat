package com.trucdecomptable.ollamachat.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trucdecomptable.ollamachat.AppContainer
import com.trucdecomptable.ollamachat.data.db.Conversation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationsUiState(
    val active: List<Conversation> = emptyList(),
    val archived: List<Conversation> = emptyList(),
)

class ConversationsViewModel(private val container: AppContainer) : ViewModel() {

    private val db = container.database
    private val repo = container.chatRepository

    val uiState: StateFlow<ConversationsUiState> = combine(
        db.conversationDao().observeActive(),
        db.conversationDao().observeArchived(),
    ) { active, archived ->
        ConversationsUiState(active = active, archived = archived)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConversationsUiState())

    fun createAndOpen(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repo.createConversation()
            onCreated(id)
        }
    }

    fun archive(id: Long) {
        viewModelScope.launch { db.conversationDao().setArchived(id, true) }
    }

    fun restore(id: Long) {
        viewModelScope.launch { db.conversationDao().setArchived(id, false) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            db.conversationDao().getById(id)?.let { db.conversationDao().delete(it) }
        }
    }

    fun rename(id: Long, title: String) {
        viewModelScope.launch { db.conversationDao().rename(id, title) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConversationsViewModel(container) as T
    }
}
