package com.trucdecomptable.ollamachat.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.trucdecomptable.ollamachat.security.DatabaseEncryption
import com.trucdecomptable.ollamachat.security.DatabaseKey
import com.trucdecomptable.ollamachat.util.DiagnosticLog
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [Conversation::class, Message::class, Memory::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v1 -> v2: assistant message stats column + long-term memory table. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN stats TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memories (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v2 -> v3: images move to files, messages can be hidden from the model
         * context, tool traces are labelled, reasoning is kept, and the history
         * gets an index matching its sort order.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imagePath TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN thinking TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN toolName TEXT")
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN excludedFromContext INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_conversationId_createdAt " +
                        "ON messages (conversationId, createdAt)"
                )
            }
        }

        /** v3 -> v4: conversations can expire on their own. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN ephemeralMinutes INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /**
         * The database is encrypted at rest: a PIN that only guards the screen
         * leaves every conversation readable to anything that can reach app
         * storage.
         */
        private fun build(context: Context): AppDatabase {
            val encrypted = runCatching {
                System.loadLibrary("sqlcipher")
                val passphrase = DatabaseKey.get(context)
                DatabaseEncryption.ensureEncrypted(context, passphrase)
                Room.databaseBuilder(context, AppDatabase::class.java, DatabaseEncryption.ENCRYPTED_NAME)
                    .openHelperFactory(SupportOpenHelperFactory(passphrase))
                    .addMigrations(*MIGRATIONS)
                    .build()
            }
            encrypted.getOrNull()?.let { return it }

            // No SQLCipher for this ABI, or the Keystore refused: the app still
            // has to open, so fall back to the plaintext file it used before.
            encrypted.exceptionOrNull()?.let { DiagnosticLog.record("db/open", it) }
            return Room.databaseBuilder(context, AppDatabase::class.java, DatabaseEncryption.PLAIN_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
        }
    }
}
