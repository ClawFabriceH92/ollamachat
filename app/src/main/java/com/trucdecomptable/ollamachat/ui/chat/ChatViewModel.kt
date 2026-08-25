package com.trucdecomptable.ollamachat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trucdecomptable.ollamachat.AppContainer
import com.trucdecomptable.ollamachat.data.db.Conversation
import com.trucdecomptable.ollamachat.data.db.Message
import com.trucdecomptable.ollamachat.data.ollama.ModelCapabilities
import com.trucdecomptable.ollamachat.data.ollama.ModelInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val streamingText: String = "",
    val isSending: Boolean = false,
    val conversationTitle: String = "",
    val systemPrompt: String? = null,
    val conversationModel: String? = null,
    val defaultModel: String = "",
    val models: List<ModelInfo> = emptyList(),
    val modelCapabilities: ModelCapabilities? = null,
    val error: String? = null,
    val toast: String? = null,
)

class ChatViewModel(
    private val conversationId: Long,
    private val container: AppContainer,
) : ViewModel() {

    private val repo = container.chatRepository
    private val db = container.database
    private val settings = container.settings

    private val streamingText = MutableStateFlow("")
    private val error = MutableStateFlow<String?>(null)
    private val toast = MutableStateFlow<String?>(null)
    private val models = MutableStateFlow<List<ModelInfo>>(emptyList())
    private val capabilities = MutableStateFlow<ModelCapabilities?>(null)

    private val conversation = db.conversationDao().observeById(conversationId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val messages = db.messageDao().observeForConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val defaultModel = settings.model
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val uiState: StateFlow<ChatUiState> = combine(
        messages,
        streamingText,
        repo.isSendingFlow,
        conversation,
        models,
        capabilities,
        error,
        toast,
        defaultModel,
    ) { msgs, stream, sending, conv, mods, caps, err, tst, defModel ->
        ChatUiState(
            messages = msgs,
            streamingText = stream,
            isSending = sending,
            conversationTitle = conv?.title ?: "",
            systemPrompt = conv?.systemPrompt,
            conversationModel = conv?.model,
            defaultModel = defModel,
            models = mods,
            modelCapabilities = caps,
            error = err,
            toast = tst,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    val activeModel: String
        get() = uiState.value.conversationModel ?: uiState.value.defaultModel

    init {
        refreshModels()
        refreshCapabilities()
    }

    private fun refreshModels() {
        viewModelScope.launch {
            val url = settings.baseUrl.first()
            if (url.isBlank()) return@launch
            container.ollamaClient.listModels(url).onSuccess { models.value = it }
        }
    }

    private fun refreshCapabilities() {
        viewModelScope.launch {
            val url = settings.baseUrl.first()
            val model = activeModel
            if (url.isBlank() || model.isBlank()) return@launch
            container.ollamaClient.modelCapabilities(url, model).onSuccess {
                capabilities.value = it
                settings.setVisionDetected(it.vision)
            }
        }
    }

    fun send(text: String, images: List<String> = emptyList()) {
        val clean = text.trim()
        if (clean.isEmpty() && images.isEmpty()) return
        if (repo.isSending) return
        streamingText.value = ""
        error.value = null
        repo.send(
            conversationId = conversationId,
            content = clean,
            images = images,
            onDelta = { streamingText.value += it },
            onResult = { finalText, err ->
                if (err != null) {
                    error.value = err
                    toast.value = "Erreur : $err"
                    if (!finalText.isNullOrBlank()) {
                        viewModelScope.launch {
                            db.messageDao().insert(
                                Message(
                                    conversationId = conversationId,
                                    role = "assistant",
                                    content = finalText + "\n\n⚠️ $err",
                                )
                            )
                        }
                    }
                }
                streamingText.value = ""
            },
        )
    }

    fun cancel() = repo.cancel()

    /** Extracts and stores an imported document (text -> system message, image -> user message). */
    fun importDocument(uri: android.net.Uri, mime: String, context: android.content.Context) {
        viewModelScope.launch {
            val extracted = com.trucdecomptable.ollamachat.ui.documents.DocumentExtractor.extract(context, uri, mime)
            if (extracted.imageBase64 != null) {
                db.messageDao().insert(
                    Message(
                        conversationId = conversationId,
                        role = "user",
                        content = "Image : ${extracted.label}",
                        contentType = "image",
                        imageBase64 = extracted.imageBase64,
                    )
                )
                toast.value = "Image ajoutée — envoie ta question"
            } else {
                db.messageDao().insert(
                    Message(
                        conversationId = conversationId,
                        role = "system",
                        content = "📄 Document « ${extracted.label} » :\n${extracted.text.orEmpty()}",
                    )
                )
                toast.value = "Document importé — pose ta question dessus"
            }
        }
    }

    fun consumeToast() {
        toast.value = null
    }

    fun renameConversation(title: String) {
        viewModelScope.launch { db.conversationDao().rename(conversationId, title) }
    }

    fun setConversationSystemPrompt(prompt: String) {
        viewModelScope.launch {
            val conv = db.conversationDao().getById(conversationId) ?: return@launch
            db.conversationDao().update(conv.copy(systemPrompt = prompt))
        }
    }

    fun setConversationModel(model: String) {
        viewModelScope.launch {
            val conv = db.conversationDao().getById(conversationId) ?: return@launch
            db.conversationDao().update(conv.copy(model = model))
            refreshCapabilities()
        }
    }

    fun archiveConversation() {
        viewModelScope.launch { db.conversationDao().setArchived(conversationId, true) }
    }

    fun deleteConversation() {
        viewModelScope.launch {
            db.conversationDao().getById(conversationId)?.let { db.conversationDao().delete(it) }
        }
    }

    fun clearMessages() {
        viewModelScope.launch { db.messageDao().deleteForConversation(conversationId) }
    }

    class Factory(private val conversationId: Long, private val container: AppContainer) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(conversationId, container) as T
    }
}
