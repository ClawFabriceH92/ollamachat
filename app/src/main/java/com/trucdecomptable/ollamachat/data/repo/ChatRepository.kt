package com.trucdecomptable.ollamachat.data.repo

import com.trucdecomptable.ollamachat.data.db.AppDatabase
import com.trucdecomptable.ollamachat.data.db.Conversation
import com.trucdecomptable.ollamachat.data.db.Message
import com.trucdecomptable.ollamachat.data.ollama.OllamaChatMessage
import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import com.trucdecomptable.ollamachat.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Orchestrates a chat round-trip: persists the user message, builds the context
 * (system prompt + history), streams the assistant answer and reports deltas +
 * the final result to the caller (ViewModel), which is responsible for
 * persisting the assistant message (so live streaming stays out of the DB).
 */
class ChatRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val client: OllamaClient,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private var sendJob: Job? = null

    private val _isSending = MutableStateFlow(false)
    val isSendingFlow: StateFlow<Boolean> = _isSending

    @Volatile
    var isSending: Boolean = false
        private set

    fun cancel() {
        sendJob?.cancel()
        sendJob = null
        isSending = false
        _isSending.value = false
    }

    /**
     * @param onDelta  called with each streaming piece (UI only)
     * @param onResult called once with (finalText, error) — exactly one of them
     *                 is non-null on completion; both null if cancelled.
     */
    fun send(
        conversationId: Long,
        content: String,
        images: List<String> = emptyList(),
        onDelta: (String) -> Unit = {},
        onResult: (finalText: String?, error: String?) -> Unit,
    ) {
        cancel()
        sendJob = scope.launch {
            isSending = true
            _isSending.value = true
            try {
                db.messageDao().insert(
                    Message(
                        conversationId = conversationId,
                        role = "user",
                        content = content,
                        contentType = if (images.isNotEmpty()) "image" else "text",
                        imageBase64 = images.firstOrNull(),
                    )
                )

                val conv = db.conversationDao().getById(conversationId)
                if (conv == null) {
                    onResult(null, "Conversation introuvable")
                    return@launch
                }

                val systemPrompt = conv.systemPrompt ?: settings.defaultSystemPrompt.first()
                val model = conv.model ?: settings.model.first()
                if (model.isBlank()) {
                    onResult(null, "Aucun modèle sélectionné — ouvre les réglages")
                    return@launch
                }

                val history = db.messageDao().listForConversation(conversationId)
                val messages = buildList {
                    if (systemPrompt.isNotBlank()) {
                        add(OllamaChatMessage(role = "system", content = systemPrompt))
                    }
                    history.forEach { m ->
                        add(
                            OllamaChatMessage(
                                role = m.role,
                                content = m.content,
                                images = if (m.imageBase64 != null) listOf(m.imageBase64) else emptyList(),
                            )
                        )
                    }
                }

                val options = buildMap {
                    put("temperature", settings.temperature.first())
                    put("top_p", settings.topP.first())
                    put("top_k", settings.topK.first())
                    put("num_predict", settings.numPredict.first())
                    put("num_ctx", settings.numCtx.first())
                }
                val keepAlive = settings.keepAlive.first()
                val baseUrl = settings.baseUrl.first()

                val result = client.chatStream(
                    baseUrl = baseUrl,
                    model = model,
                    messages = messages,
                    options = options,
                    keepAlive = keepAlive,
                    onDelta = onDelta,
                )

                if (result.error != null) {
                    onResult(result.fullText.ifBlank { null }, result.error)
                } else {
                    // Persist assistant message + refresh conversation meta.
                    db.messageDao().insert(
                        Message(
                            conversationId = conversationId,
                            role = "assistant",
                            content = result.fullText,
                        )
                    )
                    val fresh = db.conversationDao().getById(conversationId)
                    if (fresh != null) {
                        db.conversationDao().update(
                            fresh.copy(
                                title = if (fresh.title.isBlank()) deriveTitle(content) else fresh.title,
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                    }
                    onResult(result.fullText, null)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(null, e.message ?: "Erreur inattendue")
            } finally {
                isSending = false
                _isSending.value = false
            }
        }
    }

    private fun deriveTitle(content: String): String {
        val clean = content.trim().replace(Regex("\\s+"), " ")
        return if (clean.length <= 40) clean else clean.take(40) + "…"
    }

    suspend fun createConversation(systemPrompt: String? = null, model: String? = null): Long =
        db.conversationDao().insert(Conversation(title = "", systemPrompt = systemPrompt, model = model))
}
