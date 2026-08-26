package com.trucdecomptable.ollamachat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.trucdecomptable.ollamachat.data.db.AppDatabase
import com.trucdecomptable.ollamachat.data.db.Message
import com.trucdecomptable.ollamachat.data.ollama.ChatError
import com.trucdecomptable.ollamachat.data.ollama.ChatErrorCode
import com.trucdecomptable.ollamachat.data.ollama.ChatStreamResult
import com.trucdecomptable.ollamachat.data.ollama.ModelCapabilities
import com.trucdecomptable.ollamachat.data.ollama.OllamaChatMessage
import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import com.trucdecomptable.ollamachat.data.ollama.ToolCall
import com.trucdecomptable.ollamachat.data.ollama.ToolDef
import com.trucdecomptable.ollamachat.data.prefs.SettingsRepository
import com.trucdecomptable.ollamachat.data.repo.ChatRepository
import com.trucdecomptable.ollamachat.data.repo.TurboProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The orchestration nobody could see before: tool loop, cancellation,
 * compaction, regeneration. Driven against a real Room database and a fake
 * server so the assertions are about behaviour, not mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ChatRepositoryTest {

    /** Replays a scripted sequence of answers and records what it was asked. */
    private class FakeClient(
        private val script: MutableList<ChatStreamResult>,
        private val capabilities: ModelCapabilities = ModelCapabilities(tools = true),
    ) : OllamaClient() {

        val requests = mutableListOf<List<OllamaChatMessage>>()
        val toolsOffered = mutableListOf<List<ToolDef>>()
        val optionsSeen = mutableListOf<Map<String, Any>>()
        val keepAliveSeen = mutableListOf<String?>()
        val thinkSeen = mutableListOf<Boolean?>()
        val modelsSeen = mutableListOf<String>()
        var compactionRan = false
        var cancelled = false
        var onBeforeAnswer: (() -> Unit)? = null

        override suspend fun modelCapabilities(baseUrl: String, model: String): Result<ModelCapabilities> =
            Result.success(capabilities)

        override suspend fun chatStream(
            baseUrl: String,
            model: String,
            messages: List<OllamaChatMessage>,
            options: Map<String, Any>,
            keepAlive: String?,
            tools: List<ToolDef>,
            think: Boolean?,
            onDelta: (String) -> Unit,
            onThinking: (String) -> Unit,
        ): ChatStreamResult {
            requests.add(messages)
            toolsOffered.add(tools)
            optionsSeen.add(options)
            keepAliveSeen.add(keepAlive)
            thinkSeen.add(think)
            modelsSeen.add(model)
            onBeforeAnswer?.invoke()
            val next = if (script.isEmpty()) ChatStreamResult("") else script.removeAt(0)
            if (next.fullText.isNotEmpty()) onDelta(next.fullText)
            return next
        }

        override suspend fun chatOnce(
            baseUrl: String,
            model: String,
            messages: List<OllamaChatMessage>,
            options: Map<String, Any>,
            keepAlive: String?,
            tools: List<ToolDef>,
            think: Boolean?,
        ): Result<String> {
            compactionRan = true
            return Result.success("Résumé des échanges précédents.")
        }

        override fun cancelActiveStream() {
            cancelled = true
        }
    }

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(context)
        // DataStore is process-wide, so every key a test touches is reset here
        // rather than leaking into the next one.
        runBlocking {
            settings.setBaseUrl("http://localhost:11434")
            settings.setModel("qwen3")
            settings.setNumCtx(8192)
            settings.setToolsEnabled(true)
            settings.setContextCompactEnabled(false)
            settings.setThinkEnabled(false)
            settings.setKeepAlive("5m")
            settings.setTurboEnabled(false)
            settings.setTurboModel("")
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repository(client: FakeClient) =
        ChatRepository(db, settings, client) { null }

    /** Runs one send and waits for its single callback. */
    private fun send(
        repo: ChatRepository,
        conversationId: Long,
        text: String = "Bonjour",
    ): Result4 {
        val latch = CountDownLatch(1)
        var captured = Result4()
        repo.send(conversationId, text) { finalText, stats, error, cancelled ->
            captured = Result4(finalText, stats, error, cancelled)
            latch.countDown()
        }
        assertTrue("le callback n'a jamais été appelé", latch.await(10, TimeUnit.SECONDS))
        return captured
    }

    private data class Result4(
        val text: String? = null,
        val stats: String? = null,
        val error: ChatError? = null,
        val cancelled: Boolean = false,
    )

    private suspend fun newConversation(): Long =
        db.conversationDao().insert(
            com.trucdecomptable.ollamachat.data.db.Conversation(title = "")
        )

    private suspend fun contents(conversationId: Long): List<Pair<String, String>> =
        db.messageDao().listForConversation(conversationId).map { it.role to it.content }

    @Test
    fun `a plain exchange stores the question and the answer`() = runBlocking {
        val id = newConversation()
        val client = FakeClient(mutableListOf(ChatStreamResult("Salut !", tokPerSec = 40.0, evalCount = 12)))
        val result = send(repository(client), id, "Bonjour")

        assertNull(result.error)
        assertEquals("Salut !", result.text)
        assertEquals(listOf("user" to "Bonjour", "assistant" to "Salut !"), contents(id))
        // The title is derived from the first question.
        assertEquals("Bonjour", db.conversationDao().getById(id)?.title)
    }

    @Test
    fun `a tool round trip keeps the intermediate text, the trace and the answer`() = runBlocking {
        val id = newConversation()
        val client = FakeClient(
            mutableListOf(
                ChatStreamResult("Je regarde.", toolCalls = listOf(ToolCall("get_current_time", "{}"))),
                ChatStreamResult("Il est midi."),
            )
        )
        send(repository(client), id, "Quelle heure ?")

        val stored = contents(id)
        assertEquals("user" to "Quelle heure ?", stored[0])
        // Text written before the call keeps its place instead of being lost.
        assertEquals("assistant" to "Je regarde.", stored[1])
        assertEquals("tool", stored[2].first)
        assertEquals("assistant" to "Il est midi.", stored[3])
        assertEquals("get_current_time", db.messageDao().listForConversation(id)[2].toolName)
    }

    @Test
    fun `tool traces are never replayed as history`() = runBlocking {
        val id = newConversation()
        val client = FakeClient(
            mutableListOf(
                ChatStreamResult("", toolCalls = listOf(ToolCall("get_current_time", "{}"))),
                ChatStreamResult("Voilà."),
                ChatStreamResult("Deuxième réponse."),
            )
        )
        val repo = repository(client)
        send(repo, id, "Quelle heure ?")
        send(repo, id, "Et demain ?")

        // The third request is the second question: its history must not carry
        // the tool trace from the first exchange.
        val lastRequest = client.requests.last()
        assertTrue(lastRequest.none { it.content.startsWith("🔧") })
        assertEquals(0, lastRequest.count { it.role == "tool" })
        assertTrue(db.messageDao().listForConversation(id).any { it.role == "tool" })
    }

    @Test
    fun `no tools are offered to a model that does not support them`() = runBlocking {
        val id = newConversation()
        val client = FakeClient(
            mutableListOf(ChatStreamResult("ok")),
            capabilities = ModelCapabilities(tools = false),
        )
        send(repository(client), id)

        assertTrue(client.toolsOffered.single().isEmpty())
        // …and the system prompt does not promise tools either.
        assertFalse(client.requests.single().first().content.contains("outils"))
    }

    @Test
    fun `tools are offered when the model supports them`() = runBlocking {
        val id = newConversation()
        val client = FakeClient(mutableListOf(ChatStreamResult("ok")))
        send(repository(client), id)

        assertTrue(client.toolsOffered.single().isNotEmpty())
        assertTrue(client.requests.single().first().content.contains("outils"))
    }

    @Test
    fun `stopping keeps what was already generated`() = runBlocking {
        val id = newConversation()
        val client = FakeClient(mutableListOf(ChatStreamResult("Début de rép", cancelled = true)))
        val result = send(repository(client), id)

        assertTrue(result.cancelled)
        assertNull(result.error)
        assertEquals("Début de rép", result.text)
        assertEquals(listOf("user" to "Bonjour", "assistant" to "Début de rép"), contents(id))
    }

    @Test
    fun `a failure keeps the partial answer and reports the error`() = runBlocking {
        val id = newConversation()
        val client = FakeClient(
            mutableListOf(
                ChatStreamResult("Moitié", error = ChatError(ChatErrorCode.CONNECTION))
            )
        )
        val result = send(repository(client), id)

        assertEquals(ChatErrorCode.CONNECTION, result.error?.code)
        assertEquals("Moitié", result.text)
        assertEquals("Moitié", db.messageDao().lastAssistant(id)?.content)
    }

    @Test
    fun `an empty answer is reported instead of leaving a blank bubble`() = runBlocking {
        val id = newConversation()
        val result = send(repository(FakeClient(mutableListOf(ChatStreamResult("")))), id)

        assertEquals(ChatErrorCode.EMPTY, result.error?.code)
        assertEquals(listOf("user" to "Bonjour"), contents(id))
    }

    @Test
    fun `a missing model is refused before any request`() = runBlocking {
        settings.setModel("")
        val id = newConversation()
        val client = FakeClient(mutableListOf(ChatStreamResult("jamais")))
        val result = send(repository(client), id)

        assertEquals(ChatErrorCode.NO_MODEL, result.error?.code)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `a second send is refused while one is running`() = runBlocking {
        val id = newConversation()
        val client = FakeClient(mutableListOf(ChatStreamResult("un"), ChatStreamResult("deux")))
        val repo = repository(client)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        client.onBeforeAnswer = {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }

        val finished = CountDownLatch(1)
        repo.send(id, "premier") { _, _, _, _ -> finished.countDown() }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        var secondCallbackFired = false
        repo.send(id, "second") { _, _, _, _ -> secondCallbackFired = true }
        release.countDown()
        assertTrue(finished.await(10, TimeUnit.SECONDS))

        assertFalse("le second envoi n'aurait pas dû démarrer", secondCallbackFired)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `a send issued from the result callback is accepted`() = runBlocking {
        val id = newConversation()
        val client = FakeClient(mutableListOf(ChatStreamResult("un"), ChatStreamResult("deux")))
        val repo = repository(client)
        val second = CountDownLatch(1)

        repo.send(id, "premier") { _, _, _, _ ->
            // The busy flag must already be down here, otherwise this is
            // silently dropped and the user sees nothing happen.
            repo.send(id, "second") { _, _, _, _ -> second.countDown() }
        }

        assertTrue("le second envoi a été refusé", second.await(10, TimeUnit.SECONDS))
        assertEquals(2, client.requests.size)
    }

    @Test
    fun `regenerating replaces the last answer without touching the question`() = runBlocking {
        val id = newConversation()
        val client = FakeClient(mutableListOf(ChatStreamResult("Première"), ChatStreamResult("Seconde")))
        val repo = repository(client)
        send(repo, id, "Une question")

        val latch = CountDownLatch(1)
        repo.regenerate(id) { _, _, _, _ -> latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))

        assertEquals(listOf("user" to "Une question", "assistant" to "Seconde"), contents(id))
    }

    @Test
    fun `editing a message drops what followed it`() = runBlocking {
        val id = newConversation()
        val client = FakeClient(mutableListOf(ChatStreamResult("Réponse A"), ChatStreamResult("Réponse B")))
        val repo = repository(client)
        send(repo, id, "Version 1")
        val userMessage = db.messageDao().listForConversation(id).first { it.role == "user" }

        val latch = CountDownLatch(1)
        repo.editAndResend(id, userMessage.id, "Version 2") { _, _, _, _ -> latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))

        assertEquals(listOf("user" to "Version 2", "assistant" to "Réponse B"), contents(id))
    }

    @Test
    fun `compaction hides the old messages instead of deleting them`() = runBlocking {
        settings.setNumCtx(2048)
        settings.setContextCompactEnabled(true)
        val id = newConversation()
        repeat(20) { i ->
            db.messageDao().insert(
                Message(conversationId = id, role = if (i % 2 == 0) "user" else "assistant", content = "x".repeat(2_000))
            )
        }
        val before = db.messageDao().listForConversation(id).size

        send(repository(FakeClient(mutableListOf(ChatStreamResult("ok")))), id)

        val all = db.messageDao().listForConversation(id)
        // Nothing was destroyed: the transcript still holds every message.
        assertTrue(all.size >= before)
        assertTrue(all.any { it.excludedFromContext })
        assertTrue(all.any { it.content.startsWith("Contexte compacté") })
    }

    // --- turbo ---

    private suspend fun seedLongHistory(id: Long, count: Int) {
        repeat(count) { i ->
            db.messageDao().insert(
                Message(
                    conversationId = id,
                    role = if (i % 2 == 0) "user" else "assistant",
                    content = "message $i",
                )
            )
        }
    }

    @Test
    fun `turbo drops tools, compaction and thinking, and holds the model in memory`() = runBlocking {
        settings.setTurboEnabled(true)
        settings.setContextCompactEnabled(true)
        settings.setThinkEnabled(true)
        settings.setNumCtx(2048)
        val id = newConversation()
        seedLongHistory(id, 30)

        val client = FakeClient(
            mutableListOf(ChatStreamResult("vite")),
            capabilities = ModelCapabilities(tools = true, thinking = true),
        )
        send(repository(client), id)

        assertTrue("des outils ont été proposés", client.toolsOffered.single().isEmpty())
        assertFalse("la compaction a tourné", client.compactionRan)
        assertEquals(false, client.thinkSeen.single())
        assertEquals(TurboProfile.KEEP_ALIVE, client.keepAliveSeen.single())
        assertEquals(TurboProfile.NUM_CTX, client.optionsSeen.single()["num_ctx"])
        assertEquals(TurboProfile.NUM_PREDICT, client.optionsSeen.single()["num_predict"])
    }

    @Test
    fun `turbo sends only the recent turns`() = runBlocking {
        settings.setTurboEnabled(true)
        val id = newConversation()
        seedLongHistory(id, 30)

        val client = FakeClient(mutableListOf(ChatStreamResult("vite")))
        send(repository(client), id, "la question")

        // One system prompt plus the trimmed history.
        val sent = client.requests.single()
        assertEquals(1 + TurboProfile.HISTORY_MESSAGES, sent.size)
        // The message being answered always survives the trim.
        assertEquals("la question", sent.last().content)
    }

    @Test
    fun `turbo uses the dedicated model when one is set`() = runBlocking {
        settings.setTurboEnabled(true)
        settings.setTurboModel("qwen3:1.7b")
        val id = newConversation()

        val client = FakeClient(mutableListOf(ChatStreamResult("vite")))
        send(repository(client), id)

        assertEquals("qwen3:1.7b", client.modelsSeen.single())
    }

    @Test
    fun `turbo falls back to the configured model when none is set`() = runBlocking {
        settings.setTurboEnabled(true)
        settings.setTurboModel("")
        val id = newConversation()

        val client = FakeClient(mutableListOf(ChatStreamResult("vite")))
        send(repository(client), id)

        assertEquals("qwen3", client.modelsSeen.single())
    }

    @Test
    fun `turning turbo off restores the configured settings untouched`() = runBlocking {
        settings.setTurboEnabled(false)
        settings.setNumCtx(8192)
        settings.setKeepAlive("5m")
        val id = newConversation()

        val client = FakeClient(mutableListOf(ChatStreamResult("normal")))
        send(repository(client), id)

        assertEquals(8192, client.optionsSeen.single()["num_ctx"])
        assertEquals("5m", client.keepAliveSeen.single())
        assertTrue(client.toolsOffered.single().isNotEmpty())
    }

    // --- ephemeral conversations ---

    @Test
    fun `an expired conversation and its messages are purged`() = runBlocking {
        val id = newConversation()
        val repo = repository(FakeClient(mutableListOf(ChatStreamResult("ok"))))
        send(repo, id)
        repo.setEphemeral(id, 5)

        val now = System.currentTimeMillis()
        assertEquals(0, repo.purgeExpired(now))
        assertEquals(1, repo.purgeExpired(now + 6 * 60_000L))

        assertNull(db.conversationDao().getById(id))
        assertTrue(db.messageDao().listForConversation(id).isEmpty())
    }

    @Test
    fun `a permanent conversation is never purged`() = runBlocking {
        val id = newConversation()
        val repo = repository(FakeClient(mutableListOf(ChatStreamResult("ok"))))
        send(repo, id)

        assertEquals(0, repo.purgeExpired(System.currentTimeMillis() + 365L * 24 * 60 * 60_000L))
        assertNotNull(db.conversationDao().getById(id))
    }

    @Test
    fun `sending restarts the countdown`() = runBlocking {
        val id = newConversation()
        val repo = repository(FakeClient(mutableListOf(ChatStreamResult("un"), ChatStreamResult("deux"))))
        send(repo, id, "premier")
        repo.setEphemeral(id, 5)
        val firstDeadline = db.conversationDao().getById(id)!!.updatedAt

        Thread.sleep(20)
        send(repo, id, "second")

        val secondDeadline = db.conversationDao().getById(id)!!.updatedAt
        assertTrue("le compte à rebours n'a pas redémarré", secondDeadline > firstDeadline)
    }

    @Test
    fun `deleting a conversation removes its messages`() = runBlocking {
        val id = newConversation()
        val repo = repository(FakeClient(mutableListOf(ChatStreamResult("ok"))))
        send(repo, id)

        repo.deleteConversation(id)

        assertTrue(db.messageDao().listForConversation(id).isEmpty())
        assertNull(db.conversationDao().getById(id))
    }
}
