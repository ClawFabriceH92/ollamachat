package com.trucdecomptable.ollamachat.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    /**
     * Conversation list with a preview of the last real message.
     * [query] empty means "no filter"; otherwise it matches the title or any
     * message body.
     */
    @Query(
        """
        SELECT c.id AS id,
               c.title AS title,
               c.updatedAt AS updatedAt,
               c.archived AS archived,
               c.ephemeralMinutes AS ephemeralMinutes,
               (SELECT m.content FROM messages m
                 WHERE m.conversationId = c.id AND m.role IN ('user', 'assistant')
                 ORDER BY m.createdAt DESC, m.id DESC LIMIT 1) AS preview
        FROM conversations c
        WHERE c.archived = :archived
          AND (:query = ''
               OR c.title LIKE '%' || :query || '%'
               OR EXISTS (SELECT 1 FROM messages m2
                           WHERE m2.conversationId = c.id
                             AND m2.content LIKE '%' || :query || '%'))
        ORDER BY c.updatedAt DESC
        """
    )
    fun observeSummaries(archived: Boolean, query: String): Flow<List<ConversationSummary>>

    @Query("SELECT * FROM conversations ORDER BY createdAt ASC, id ASC")
    suspend fun listAll(): List<Conversation>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: Long): Flow<Conversation?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): Conversation?

    @Insert
    suspend fun insert(c: Conversation): Long

    @Update
    suspend fun update(c: Conversation)

    @Delete
    suspend fun delete(c: Conversation)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE conversations SET archived = :archived, updatedAt = :ts WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, ts: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    /** Restarts the ephemeral countdown; called whenever the user acts. */
    @Query("UPDATE conversations SET updatedAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET ephemeralMinutes = :minutes, updatedAt = :ts WHERE id = :id")
    suspend fun setEphemeral(id: Long, minutes: Int, ts: Long = System.currentTimeMillis())

    /**
     * Conversations whose countdown has run out. The arithmetic lives in the
     * query so a long list never has to be loaded to find the few expired.
     */
    @Query(
        """
        SELECT * FROM conversations
        WHERE ephemeralMinutes > 0 AND (updatedAt + ephemeralMinutes * 60000) <= :now
        """
    )
    suspend fun listExpired(now: Long): List<Conversation>
}

@Dao
interface MessageDao {
    // createdAt has millisecond resolution, so two rows written in the same
    // millisecond (a user message and the tool trace that follows it) would
    // otherwise come back in arbitrary order — id breaks the tie.
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeForConversation(conversationId: Long): Flow<List<Message>>

    /**
     * The last [limit] messages, oldest first. Opening a conversation should
     * not mean loading years of history before the first frame.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM messages WHERE conversationId = :conversationId
            ORDER BY createdAt DESC, id DESC LIMIT :limit
        ) ORDER BY createdAt ASC, id ASC
        """
    )
    fun observeRecent(conversationId: Long, limit: Int): Flow<List<Message>>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    fun observeCount(conversationId: Long): Flow<Int>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun listForConversation(conversationId: Long): List<Message>

    /** History actually replayed to the model. */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND excludedFromContext = 0
        ORDER BY createdAt ASC, id ASC
        """
    )
    suspend fun listForContext(conversationId: Long): List<Message>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): Message?

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND role = 'assistant'
        ORDER BY createdAt DESC, id DESC LIMIT 1
        """
    )
    suspend fun lastAssistant(conversationId: Long): Message?

    @Insert
    suspend fun insert(m: Message): Long

    @Update
    suspend fun update(m: Message)

    @Query("UPDATE messages SET excludedFromContext = 1 WHERE id IN (:ids)")
    suspend fun excludeFromContext(ids: List<Long>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Deletes [fromId] and every message inserted after it (regenerate / edit). */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id >= :fromId")
    suspend fun deleteFrom(conversationId: Long, fromId: Long)

    @Query("SELECT imagePath FROM messages WHERE conversationId = :conversationId AND imagePath IS NOT NULL")
    suspend fun imagePathsFor(conversationId: Long): List<String?>


}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Memory>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<Memory>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    suspend fun listAll(): List<Memory>

    @Insert
    suspend fun insert(m: Memory): Long

    @Update
    suspend fun update(m: Memory)

    @Delete
    suspend fun delete(m: Memory)
}
