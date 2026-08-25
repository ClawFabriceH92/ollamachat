package com.trucdecomptable.ollamachat.data.repo

import com.trucdecomptable.ollamachat.data.db.AppDatabase
import com.trucdecomptable.ollamachat.data.db.Conversation
import com.trucdecomptable.ollamachat.data.db.Message
import com.trucdecomptable.ollamachat.data.mcp.McpClient
import com.trucdecomptable.ollamachat.data.ollama.ChatStreamResult
import com.trucdecomptable.ollamachat.data.ollama.OllamaChatMessage
import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import com.trucdecomptable.ollamachat.data.ollama.ToolCall
import com.trucdecomptable.ollamachat.data.ollama.ToolDef
import com.trucdecomptable.ollamachat.data.prefs.SettingsRepository
import com.trucdecomptable.ollamachat.data.tools.ToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Orchestrates a chat round-trip:
 *  - persists the user message
 *  - auto-compacts the context when it grows past the window (optional)
 *  - injects long-term memories into the system prompt
 *  - runs the tool-calling loop (built-in tools + MCP servers)
 *  - persists the assistant answer with generation stats (tok/s)
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

    private val maxToolLoop = 5

    fun cancel() {
        sendJob?.cancel()
        sendJob = null
        isSending = false
        _isSending.value = false
    }

    /**
     * @param onDelta  called with each streaming piece (UI only)
     * @param onResult called once with (finalText, statsLine, error) — exactly one of
     *                 finalText/error is non-null on completion; both null if cancelled.
     */
    fun send(
        conversationId: Long,
        content: String,
        images: List<String> = emptyList(),
        onDelta: (String) -> Unit = {},
        onResult: (finalText: String?, statsLine: String?, error: String?) -> Unit,
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
                    onResult(null, null, "Conversation introuvable")
                    return@launch
                }

                val systemPrompt = conv.systemPrompt ?: settings.defaultSystemPrompt.first()
                val model = conv.model ?: settings.model.first()
                if (model.isBlank()) {
                    onResult(null, null, "Aucun modèle sélectionné — ouvre les réglages")
                    return@launch
                }
                val baseUrl = settings.baseUrl.first()
                val braveKey = settings.braveApiKey.first()

                // 1. Optional context compaction (before building the payload).
                maybeCompact(conversationId, model, baseUrl, systemPrompt)

                // 2. Build the message list: system (prompt + memories) + history.
                val memories = db.memoryDao().listAll().take(8)
                val history = db.messageDao().listForConversation(conversationId)
                val messages = buildList {
                    val sys = buildString {
                        append(systemPrompt)
                        if (memories.isNotEmpty()) {
                            append("\n\nMémoires persistantes (faits à retenir sur l'utilisateur) :\n")
                            memories.forEach { append("- ").append(it.content).append("\n") }
                        }
                        append(
                            "\n\nTu peux utiliser les outils disponibles quand c'est utile (recherche web, lecture d'URL, météo, calcul). " +
                                "Appelle l'outil, puis réponds avec le résultat. " +
                                "Si l'utilisateur partage un fait durable (préférence, info personnelle), enregistre-le avec save_memory."
                        )
                    }
                    add(OllamaChatMessage(role = "system", content = sys))
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
                val think = settings.thinkEnabled.first()

                // 3. Tool-calling loop.
                val tools = loadTools()
                var working = messages
                var result: ChatStreamResult? = null
                for (iteration in 0 until maxToolLoop) {
                    result = client.chatStream(
                        baseUrl = baseUrl,
                        model = model,
                        messages = working,
                        options = options,
                        keepAlive = keepAlive,
                        tools = tools,
                        think = think,
                        onDelta = onDelta,
                    )
                    if (result.error != null) {
                        onResult(result.fullText.ifBlank { null }, null, result.error)
                        return@launch
                    }
                    if (result.toolCalls.isEmpty()) break
                    // Execute each requested tool and feed results back.
                    val mcpServers = settings.mcpServers.first()
                    val toolMessages = mutableListOf<OllamaChatMessage>()
                    result.toolCalls.forEach { tc ->
                        val output = when {
                            tc.name == "save_memory" -> {
                                val content = org.json.JSONObject(tc.arguments).optString("content", "").trim()
                                if (content.isNotEmpty()) {
                                    db.memoryDao().insert(
                                        com.trucdecomptable.ollamachat.data.db.Memory(content = content)
                                    )
                                    "Mémorisé ✅ : $content"
                                } else {
                                    "Contenu vide, rien mémorisé"
                                }
                            }
                            mcpServers.any { tc.name.startsWith("${it.name}_") } -> {
                                val server = mcpServers.first { tc.name.startsWith("${it.name}_") }
                                val toolName = tc.name.removePrefix("${server.name}_")
                                executeMcpTool(server, toolName, tc.arguments)
                            }
                            else -> ToolExecutor.execute(tc.name, tc.arguments, braveKey)
                        }
                        db.messageDao().insert(
                            Message(
                                conversationId = conversationId,
                                role = "system",
                                content = "🔧 Outil « ${tc.name} » :\n$output",
                            )
                        )
                        toolMessages.add(OllamaChatMessage(role = "assistant", content = "", toolCalls = listOf(tc)))
                        toolMessages.add(OllamaChatMessage(role = "tool", content = output))
                    }
                    working = working + toolMessages
                }

                // 4. Persist the final assistant answer with stats.
                val final = result ?: ChatStreamResult("")
                if (final.fullText.isBlank() && final.toolCalls.isEmpty()) {
                    // Guard: never insert an invisible empty bubble.
                    onResult(null, null, "Le modèle n'a pas produit de réponse")
                    return@launch
                }
                val statsLine = final.statsLine()
                db.messageDao().insert(
                    Message(
                        conversationId = conversationId,
                        role = "assistant",
                        content = final.fullText,
                        stats = statsLine,
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
                onResult(final.fullText, statsLine, null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(null, null, e.message ?: "Erreur inattendue")
            } finally {
                isSending = false
                _isSending.value = false
            }
        }
    }

    /** Loads built-in tools + tools from configured MCP servers (failures ignored). */
    private suspend fun loadTools(): List<ToolDef> {
        val tools = ToolExecutor.nativeToolDefs.toMutableList()
        val servers = settings.mcpServers.first()
        servers.forEach { server ->
            try {
                val mcpTools = McpClient.listTools(server.url).getOrNull().orEmpty()
                mcpTools.forEach { mt ->
                    tools.add(
                        ToolDef(
                            name = "${server.name}_${mt.name}",
                            description = "[MCP ${server.name}] ${mt.description}",
                            parametersJson = mt.inputSchema,
                        )
                    )
                }
            } catch (_: Exception) {
                // Unreachable MCP server: skip silently.
            }
        }
        return tools
    }

    /** Executes an MCP-backed tool (name starts with "<server>_"). */
    private suspend fun executeMcpTool(
        server: com.trucdecomptable.ollamachat.data.prefs.McpServer,
        toolName: String,
        argumentsJson: String,
    ): String {
        return McpClient.callTool(server.url, toolName, argumentsJson)
            .getOrElse { "Erreur MCP : ${it.message}" }
    }

    /**
     * When the conversation is close to overflowing the context window and the
     * option is enabled, summarizes the older messages into one memory message.
     */
    private suspend fun maybeCompact(
        conversationId: Long,
        model: String,
        baseUrl: String,
        systemPrompt: String,
    ) {
        if (!settings.contextCompactEnabled.first()) return
        val numCtx = settings.numCtx.first()
        val history = db.messageDao().listForConversation(conversationId)
        val totalTokens = estimateTokens(history, systemPrompt)
        val threshold = (numCtx * 0.72).toInt().coerceAtLeast(1024)
        if (totalTokens <= threshold) return
        if (history.size <= 8) return

        val keepLast = 8
        val toSummarize = history.subList(0, history.size - keepLast)
        val lastMessages = history.takeLast(keepLast)

        try {
            val summaryMessages = listOf(
                OllamaChatMessage(
                    role = "system",
                    content = "Voici le début d'une conversation. Résume-la en français, en conservant tous les faits, " +
                        "décisions, préférences et informations importants. Sois concis mais complet.",
                )
            ) + toSummarize.map { m ->
                OllamaChatMessage(
                    role = m.role,
                    content = m.content,
                    images = if (m.imageBase64 != null) listOf(m.imageBase64) else emptyList(),
                )
            }
            val summary = client.chatOnce(
                baseUrl = baseUrl,
                model = model,
                messages = summaryMessages,
                options = mapOf("num_predict" to 1024, "num_ctx" to 8192),
            ).getOrNull() ?: return

            // Replace the summarized messages with the summary in the DB.
            toSummarize.forEach { db.messageDao().deleteById(it.id) }
            db.messageDao().insert(
                Message(
                    conversationId = conversationId,
                    role = "system",
                    content = "📌 Contexte compacté automatiquement (résumé des messages précédents) :\n$summary",
                )
            )
        } catch (_: Exception) {
            // Compaction must never block the send.
        }
    }

    /** Rough token estimate: ~4 chars/token for text, ~1000 tokens per image. */
    private fun estimateTokens(messages: List<Message>, systemPrompt: String): Int {
        var total = systemPrompt.length / 4
        messages.forEach { m ->
            total += m.content.length / 4
            if (m.imageBase64 != null) total += 1000
        }
        return total
    }

    private fun deriveTitle(content: String): String {
        val clean = content.trim().replace(Regex("\\s+"), " ")
        return if (clean.length <= 40) clean else clean.take(40) + "…"
    }

    suspend fun createConversation(systemPrompt: String? = null, model: String? = null): Long =
        db.conversationDao().insert(Conversation(title = "", systemPrompt = systemPrompt, model = model))
}
