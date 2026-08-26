package com.trucdecomptable.ollamachat.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trucdecomptable.ollamachat.AppContainer
import com.trucdecomptable.ollamachat.data.db.ConversationSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationsUiState(
    val active: List<ConversationSummary> = emptyList(),
    val archived: List<ConversationSummary> = emptyList(),
    val query: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModel(private val container: AppContainer) : ViewModel() {

    private val db = container.database
    private val repo = container.chatRepository

    private val query = MutableStateFlow("")

    private val active = query.flatMapLatest { q ->
        db.conversationDao().observeSummaries(archived = false, query = q.trim())
    }
    private val archived = query.flatMapLatest { q ->
        db.conversationDao().observeSummaries(archived = true, query = q.trim())
    }

    val uiState: StateFlow<ConversationsUiState> = combine(active, archived, query) { a, b, q ->
        ConversationsUiState(active = a, archived = b, query = q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConversationsUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun createAndOpen(onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(repo.createConversation()) }
    }

    fun archive(id: Long) {
        viewModelScope.launch { db.conversationDao().setArchived(id, true) }
    }

    fun restore(id: Long) {
        viewModelScope.launch { db.conversationDao().setArchived(id, false) }
    }

    /** Deletes the conversation and the image files it referenced. */
    fun delete(id: Long) {
        viewModelScope.launch { repo.deleteConversation(id) }
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
