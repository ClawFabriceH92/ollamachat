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

/** Transient (non-persisted) UI state merged with the Room flows. */
private data class Transient(
    val streamingText: String = "",
    val error: String? = null,
    val toast: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val capabilities: ModelCapabilities? = null,
)

class ChatViewModel(
    private val conversationId: Long,
    private val container: AppContainer,
) : ViewModel() {

    private val repo = container.chatRepository
    private val db = container.database
    private val settings = container.settings

    private val transient = MutableStateFlow(Transient())

    private val conversation = db.conversationDao().observeById(conversationId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val messages = db.messageDao().observeForConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val defaultModel = settings.model
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val convWithModel = combine(conversation, defaultModel) { c, m -> c to m }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null to "")

    val uiState: StateFlow<ChatUiState> = combine(
        messages,
        transient,
        repo.isSendingFlow,
        convWithModel,
    ) { msgs, tr, sending, pair ->
        val (conv, defModel) = pair
        ChatUiState(
            messages = msgs,
            streamingText = tr.streamingText,
            isSending = sending,
            conversationTitle = conv?.title ?: "",
            systemPrompt = conv?.systemPrompt,
            conversationModel = conv?.model,
            defaultModel = defModel,
            models = tr.models,
            modelCapabilities = tr.capabilities,
            error = tr.error,
            toast = tr.toast,
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
            container.ollamaClient.listModels(url).onSuccess { mods ->
                transient.value = transient.value.copy(models = mods)
            }
        }
    }

    private fun refreshCapabilities() {
        viewModelScope.launch {
            val url = settings.baseUrl.first()
            val model = activeModel
            if (url.isBlank() || model.isBlank()) return@launch
            container.ollamaClient.modelCapabilities(url, model).onSuccess {
                transient.value = transient.value.copy(capabilities = it)
                settings.setVisionDetected(it.vision)
            }
        }
    }

    fun send(text: String, images: List<String> = emptyList()) {
        val clean = text.trim()
        if (clean.isEmpty() && images.isEmpty()) return
        if (repo.isSending) {
            transient.value = transient.value.copy(toast = "Réponse en cours — patiente un instant")
            return
        }
        transient.value = transient.value.copy(streamingText = "", error = null)
        repo.send(
            conversationId = conversationId,
            content = clean,
            images = images,
            onDelta = { delta ->
                transient.value = transient.value.copy(streamingText = transient.value.streamingText + delta)
            },
            onResult = { finalText, _stats, err ->
                if (err != null) {
                    transient.value = transient.value.copy(
                        error = err,
                        toast = "Erreur : $err",
                    )
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
                transient.value = transient.value.copy(streamingText = "")
            },
        )
    }

    fun cancel() = repo.cancel()

    /**
     * Searches the web for [query] (Brave if a key is set, Wikipedia otherwise),
     * injects the results as a system message, then sends the question to the model.
     */
    fun searchAndSend(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            transient.value = transient.value.copy(toast = "Recherche web en cours…")
            val key = settings.braveApiKey.first()
            val results = com.trucdecomptable.ollamachat.data.web.WebSearchClient.search(clean, key)
                .getOrNull().orEmpty()
            if (results.isEmpty()) {
                transient.value = transient.value.copy(
                    toast = "Recherche infructueuse — essayez une autre formulation",
                )
                return@launch
            }
            val contextText = results.mapIndexed { i, r ->
                "${i + 1}. ${r.title} — ${r.url}\n   ${r.snippet}"
            }.joinToString("\n\n")
            db.messageDao().insert(
                Message(
                    conversationId = conversationId,
                    role = "system",
                    content = "Résultats de recherche web pour « $clean » :\n\n$contextText\n\nRéponds à la question en t'appuyant sur ces résultats.",
                )
            )
            transient.value = transient.value.copy(toast = null)
            send(clean)
        }
    }

    /** Fetches a web page and injects its content as a system message. */
    fun fetchUrl(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isEmpty()) return
        viewModelScope.launch {
            transient.value = transient.value.copy(toast = "Lecture de la page…")
            val result = com.trucdecomptable.ollamachat.data.web.UrlFetcher.fetch(url)
            result.onSuccess { content ->
                db.messageDao().insert(
                    Message(
                        conversationId = conversationId,
                        role = "system",
                        content = content,
                    )
                )
                transient.value = transient.value.copy(toast = "Page lue — pose ta question dessus")
            }.onFailure { e ->
                transient.value = transient.value.copy(
                    toast = "Échec de lecture : ${e.message ?: "erreur inconnue"}",
                )
            }
        }
    }

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
                transient.value = transient.value.copy(toast = "Image ajoutée — envoie ta question")
            } else {
                db.messageDao().insert(
                    Message(
                        conversationId = conversationId,
                        role = "system",
                        content = "📄 Document « ${extracted.label} » :\n${extracted.text.orEmpty()}",
                    )
                )
                transient.value = transient.value.copy(toast = "Document importé — pose ta question dessus")
            }
        }
    }

    fun consumeToast() {
        transient.value = transient.value.copy(toast = null)
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

    // --- Long-term memory ---

    val memories: StateFlow<List<com.trucdecomptable.ollamachat.data.db.Memory>> =
        db.memoryDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addMemory(content: String) {
        val clean = content.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            db.memoryDao().insert(com.trucdecomptable.ollamachat.data.db.Memory(content = clean))
        }
    }

    fun deleteMemory(memory: com.trucdecomptable.ollamachat.data.db.Memory) {
        viewModelScope.launch { db.memoryDao().delete(memory) }
    }

    /** Builds a markdown export of the conversation. */
    fun exportMarkdown(): String {
        val conv = uiState.value
        val sb = StringBuilder()
        sb.append("# ").append(conv.conversationTitle.ifBlank { "Conversation" }).append("\n\n")
        conv.messages.forEach { m ->
            val role = when (m.role) {
                "user" -> "**Vous**"
                "assistant" -> "**Assistant**"
                else -> "*Système*"
            }
            sb.append("### ").append(role).append("\n\n")
            sb.append(m.content).append("\n\n")
            m.stats?.let { sb.append("_").append(it).append("_\n\n") }
        }
        return sb.toString()
    }

    class Factory(private val conversationId: Long, private val container: AppContainer) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(conversationId, container) as T
    }
}
