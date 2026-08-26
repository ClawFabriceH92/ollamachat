package com.trucdecomptable.ollamachat

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.trucdecomptable.ollamachat.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Migration coverage without exported schema files.
 *
 * The v1 and v2 schemas were never exported, so the historical DDL is spelled
 * out here and the real Migration objects are run against it — what matters is
 * that the SQL applies and that existing rows survive.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: OllamaChatApp would spin up the DI container, the
// database and the maintenance coroutines for a test that only needs SQLite.
@Config(application = android.app.Application::class)
class MigrationTest {

    private lateinit var context: Context
    private lateinit var file: File
    private var helper: SupportSQLiteOpenHelper? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File.createTempFile("migration-test", ".db").also { it.delete() }
    }

    @After
    fun tearDown() {
        helper?.close()
        file.delete()
    }

    private fun openAt(version: Int): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createV1(db)
                if (version >= 2) AppDatabase.MIGRATION_1_2.migrate(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val created = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(file.absolutePath)
                .callback(callback)
                .build()
        )
        helper = created
        return created.writableDatabase
    }

    /** The schema as it shipped in v1.0. */
    private fun createV1(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS conversations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "title TEXT NOT NULL, systemPrompt TEXT, model TEXT, " +
                "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                "archived INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "conversationId INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, " +
                "contentType TEXT NOT NULL, imageBase64 TEXT, createdAt INTEGER NOT NULL, " +
                "FOREIGN KEY(conversationId) REFERENCES conversations(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId ON messages (conversationId)")
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info($table)").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

    private fun indices(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA index_list($table)").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

    @Test
    fun `1 to 2 adds stats and the memory table`() {
        val db = openAt(1)
        AppDatabase.MIGRATION_1_2.migrate(db)

        assertTrue("stats" in columns(db, "messages"))
        db.query("SELECT COUNT(*) FROM memories").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun `2 to 3 adds the new columns and the sort index`() {
        val db = openAt(2)
        AppDatabase.MIGRATION_2_3.migrate(db)

        val cols = columns(db, "messages")
        assertTrue(cols.containsAll(listOf("imagePath", "thinking", "toolName", "excludedFromContext")))
        assertTrue("index_messages_conversationId_createdAt" in indices(db, "messages"))
    }

    @Test
    fun `2 to 3 keeps existing messages and leaves them in context`() {
        val db = openAt(2)
        db.execSQL(
            "INSERT INTO conversations (id, title, systemPrompt, model, createdAt, updatedAt, archived) " +
                "VALUES (1, 'Test', NULL, NULL, 10, 10, 0)"
        )
        db.execSQL(
            "INSERT INTO messages (id, conversationId, role, content, contentType, imageBase64, createdAt, stats) " +
                "VALUES (1, 1, 'user', 'salut', 'text', 'QUJD', 20, NULL)"
        )

        AppDatabase.MIGRATION_2_3.migrate(db)

        db.query("SELECT content, imageBase64, imagePath, excludedFromContext FROM messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("salut", c.getString(0))
            // The legacy blob is left in place; ImageStore moves it at startup.
            assertEquals("QUJD", c.getString(1))
            assertTrue(c.isNull(2))
            assertEquals(0, c.getInt(3))
        }
    }

    @Test
    fun `the whole 1 to 3 chain applies cleanly`() {
        val db = openAt(1)
        AppDatabase.MIGRATIONS.forEach { it.migrate(db) }

        assertTrue(columns(db, "messages").containsAll(listOf("stats", "imagePath", "excludedFromContext")))
        assertTrue(columns(db, "memories").contains("content"))
    }
}
