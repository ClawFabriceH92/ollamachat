package com.trucdecomptable.ollamachat

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trucdecomptable.ollamachat.data.db.AppDatabase
import com.trucdecomptable.ollamachat.data.db.Conversation
import com.trucdecomptable.ollamachat.data.db.Message
import com.trucdecomptable.ollamachat.security.DatabaseEncryption
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The plaintext-to-encrypted migration, on a real device.
 *
 * This is the test that makes shipping SQLCipher defensible: it proves the
 * conversations survive the conversion and that the resulting file no longer
 * shows them to anyone reading the bytes.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionTest {

    private lateinit var context: Context
    private val plainName = "test-plain.db"
    private val encryptedName = "test-enc.db"
    private val passphrase = "phrase-de-test-très-longue".toByteArray()
    private val marker = "rendez-vous chez le notaire jeudi"

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        context = InstrumentationRegistry.getInstrumentation().targetContext
        cleanUp()
    }

    @After
    fun tearDown() = cleanUp()

    private fun cleanUp() {
        listOf(plainName, encryptedName).forEach { name ->
            listOf("", "-wal", "-shm", "-journal", ".tmp").forEach { suffix ->
                File(context.getDatabasePath(name).absolutePath + suffix).delete()
            }
        }
    }

    private fun seedPlaintext(): Int {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, plainName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        val count = runBlocking {
            val id = db.conversationDao().insert(Conversation(title = "Notaire"))
            db.messageDao().insert(Message(conversationId = id, role = "user", content = marker))
            db.messageDao().insert(Message(conversationId = id, role = "assistant", content = "Noté."))
            db.messageDao().listForConversation(id).size
        }
        db.close()
        return count
    }

    private fun openEncrypted(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, encryptedName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

    @Test
    fun migrationMovesEveryRowAndRemovesThePlaintextFile() {
        assertEquals(2, seedPlaintext())

        val migrated = DatabaseEncryption.ensureEncrypted(context, passphrase, plainName, encryptedName)
        assertTrue("la migration n'a pas eu lieu", migrated)

        assertFalse(context.getDatabasePath(plainName).exists())
        assertTrue(context.getDatabasePath(encryptedName).exists())

        val db = openEncrypted()
        runBlocking {
            val conversation = db.conversationDao().listAll().single()
            assertEquals("Notaire", conversation.title)
            val messages = db.messageDao().listForConversation(conversation.id)
            assertEquals(listOf(marker, "Noté."), messages.map { it.content })
        }
        db.close()
    }

    @Test
    fun theEncryptedFileDoesNotExposeItsContents() {
        seedPlaintext()
        // The plaintext file really does show the message, which is the point.
        val plainBytes = context.getDatabasePath(plainName).readBytes()
        assertTrue(String(plainBytes, Charsets.ISO_8859_1).contains(marker))

        DatabaseEncryption.ensureEncrypted(context, passphrase, plainName, encryptedName)

        val encryptedBytes = context.getDatabasePath(encryptedName).readBytes()
        assertFalse(String(encryptedBytes, Charsets.ISO_8859_1).contains(marker))
        // Not even the SQLite header survives in an encrypted file.
        assertFalse(String(encryptedBytes.copyOfRange(0, 16), Charsets.ISO_8859_1).startsWith("SQLite format"))
    }

    @Test
    fun migrationIsSkippedWhenTheEncryptedFileAlreadyExists() {
        seedPlaintext()
        DatabaseEncryption.ensureEncrypted(context, passphrase, plainName, encryptedName)

        // A second run has nothing to do and must not touch anything.
        assertFalse(DatabaseEncryption.ensureEncrypted(context, passphrase, plainName, encryptedName))
    }

    @Test
    fun migrationIsSkippedWhenThereIsNothingToConvert() {
        assertFalse(DatabaseEncryption.ensureEncrypted(context, passphrase, plainName, encryptedName))
        assertFalse(context.getDatabasePath(encryptedName).exists())
    }

    @Test
    fun aWrongPassphraseCannotOpenTheDatabase() {
        seedPlaintext()
        DatabaseEncryption.ensureEncrypted(context, passphrase, plainName, encryptedName)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, encryptedName)
            .openHelperFactory(SupportOpenHelperFactory("mauvaise phrase".toByteArray()))
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        var refused = false
        try {
            runBlocking { db.conversationDao().listAll() }
        } catch (_: Throwable) {
            refused = true
        } finally {
            runCatching { db.close() }
        }
        assertTrue("la base s'est ouverte avec une mauvaise phrase de passe", refused)
    }
}
