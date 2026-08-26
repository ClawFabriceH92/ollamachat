package com.trucdecomptable.ollamachat.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val systemPrompt: String? = null,       // per-conversation override (null = use default)
    val model: String? = null,              // per-conversation model override (null = use default)
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("conversationId"),
        Index(value = ["conversationId", "createdAt"]),
    ],
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,        // user | assistant | system | tool
    val content: String,
    val contentType: String = "text", // text | image
    /**
     * Legacy inline base64 image. Rows created before v1.3 carry one; they are
     * moved to [imagePath] at startup because a base64 photo easily exceeds
     * SQLite's 2 MB CursorWindow and makes reading the conversation throw.
     */
    val imageBase64: String? = null,
    /** Absolute path of the image file on app-private storage (v1.3+). */
    val imagePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val stats: String? = null,        // e.g. "42 tok/s · 850 tokens" (assistant messages)
    /** Model reasoning emitted before the answer, when the model exposes it. */
    val thinking: String? = null,
    /** Name of the tool that produced this message (role = tool). */
    val toolName: String? = null,
    /**
     * Kept in the transcript for the user, but not replayed to the model —
     * set on messages folded into a compaction summary and on tool traces.
     */
    @ColumnInfo(defaultValue = "0")
    val excludedFromContext: Boolean = false,
)

/** Long-term memory injected into the system prompt of every conversation. */
@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Row backing the conversation list: header fields + a preview of the last message. */
data class ConversationSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val archived: Boolean,
    val preview: String?,
)
