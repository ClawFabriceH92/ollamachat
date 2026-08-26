package com.trucdecomptable.ollamachat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.trucdecomptable.ollamachat.data.backup.BackupCrypto
import com.trucdecomptable.ollamachat.data.backup.BackupManager
import com.trucdecomptable.ollamachat.data.db.AppDatabase
import com.trucdecomptable.ollamachat.data.db.Conversation
import com.trucdecomptable.ollamachat.data.db.ImageStore
import com.trucdecomptable.ollamachat.data.db.Memory
import com.trucdecomptable.ollamachat.data.db.Message
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** End-to-end: what leaves the device is exactly what comes back. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class BackupManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var manager: BackupManager
    private val secret = "phrase de passe longue".toCharArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = BackupManager(context, db, "test")
    }

    @After
    fun tearDown() {
        db.close()
        ImageStore.dir(context).deleteRecursively()
    }

    private suspend fun seed(): String {
        val conversationId = db.conversationDao().insert(Conversation(title = "Voyage", model = "qwen3"))
        val imagePath = ImageStore.saveBytes(context, byteArrayOf(4, 2, 4, 2))!!
        db.messageDao().insert(
            Message(conversationId = conversationId, role = "user", content = "Où aller ?")
        )
        db.messageDao().insert(
            Message(
                conversationId = conversationId,
                role = "user",
                content = "Photo",
                contentType = "image",
                imagePath = imagePath,
            )
        )
        db.messageDao().insert(
            Message(
                conversationId = conversationId,
                role = "tool",
                content = "météo : 18°",
                toolName = "get_weather",
                excludedFromContext = true,
            )
        )
        db.messageDao().insert(
            Message(conversationId = conversationId, role = "assistant", content = "Lisbonne", stats = "40 tok/s")
        )
        db.memoryDao().insert(Memory(content = "aime la mer"))
        return imagePath
    }

    @Test
    fun `export then import restores conversations, messages, image and memory`() = runBlocking {
        seed()
        val container = manager.export(secret)

        // Wipe everything, as if this were a new phone.
        db.conversationDao().listAll().forEach { db.conversationDao().delete(it) }
        db.memoryDao().listAll().forEach { db.memoryDao().delete(it) }
        assertTrue(db.conversationDao().listAll().isEmpty())

        val result = manager.import(container, secret)

        assertEquals(1, result.conversations)
        assertEquals(4, result.messages)
        assertEquals(1, result.memories)
        assertEquals(1, result.images)
        assertEquals(0, result.missingImages)

        val conversation = db.conversationDao().listAll().single()
        assertEquals("Voyage", conversation.title)
        assertEquals("qwen3", conversation.model)

        val messages = db.messageDao().listForConversation(conversation.id)
        assertEquals(listOf("Où aller ?", "Photo", "météo : 18°", "Lisbonne"), messages.map { it.content })
        assertEquals("get_weather", messages[2].toolName)
        assertTrue(messages[2].excludedFromContext)
        assertEquals("40 tok/s", messages[3].stats)

        val restoredImage = messages[1].imagePath
        assertTrue(restoredImage != null && File(restoredImage).exists())
        assertArrayEquals(byteArrayOf(4, 2, 4, 2), File(restoredImage!!).readBytes())
        assertEquals("aime la mer", db.memoryDao().listAll().single().content)
    }

    @Test
    fun `importing never deletes what is already there`() = runBlocking {
        seed()
        val container = manager.export(secret)

        manager.import(container, secret)

        // The original conversation plus the imported copy.
        assertEquals(2, db.conversationDao().listAll().size)
        // Memories are facts: the identical one is not duplicated.
        assertEquals(1, db.memoryDao().listAll().size)
    }

    @Test
    fun `a wrong passphrase leaves the database untouched`() = runBlocking {
        seed()
        val container = manager.export(secret)
        val before = db.conversationDao().listAll().size

        assertThrows(BackupCrypto.WrongPassphraseException::class.java) {
            runBlocking { manager.import(container, "autre chose".toCharArray()) }
        }
        assertEquals(before, db.conversationDao().listAll().size)
    }

    @Test
    fun `a message whose image file vanished still exports`() = runBlocking {
        val imagePath = seed()
        File(imagePath).delete()

        val container = manager.export(secret)
        db.conversationDao().listAll().forEach { db.conversationDao().delete(it) }
        val result = manager.import(container, secret)

        assertEquals(4, result.messages)
        assertEquals(0, result.images)
        val messages = db.messageDao().listForConversation(db.conversationDao().listAll().single().id)
        assertEquals(null, messages[1].imagePath)
    }
}
