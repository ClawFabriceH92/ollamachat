package com.trucdecomptable.ollamachat

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trucdecomptable.ollamachat.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration coverage for the message table.
 *
 * Run with `./gradlew connectedDebugAndroidTest` (needs an emulator and the
 * exported schemas under app/schemas, produced by any build).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate2To3_keepsExistingMessages_andAddsTheNewColumns() {
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                "INSERT INTO conversations (id, title, systemPrompt, model, createdAt, updatedAt, archived) " +
                    "VALUES (1, 'Test', NULL, NULL, 10, 10, 0)"
            )
            db.execSQL(
                "INSERT INTO messages (id, conversationId, role, content, contentType, imageBase64, createdAt, stats) " +
                    "VALUES (1, 1, 'user', 'salut', 'text', NULL, 20, NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 3, true, AppDatabase.MIGRATION_2_3)

        db.query("SELECT content, excludedFromContext, imagePath, thinking, toolName FROM messages")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("salut", cursor.getString(0))
                // Existing rows must stay in the model context.
                assertEquals(0, cursor.getInt(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertTrue(cursor.isNull(4))
            }
        db.close()
    }

    @Test
    fun migrate1To3_runsTheWholeChain() {
        helper.createDatabase(dbName, 1).close()
        val db = helper.runMigrationsAndValidate(dbName, 3, true, *AppDatabase.MIGRATIONS)
        db.query("SELECT COUNT(*) FROM memories").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }
}
