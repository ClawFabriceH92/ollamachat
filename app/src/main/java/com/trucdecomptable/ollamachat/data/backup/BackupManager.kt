package com.trucdecomptable.ollamachat.data.backup

import android.content.Context
import com.trucdecomptable.ollamachat.data.db.AppDatabase
import com.trucdecomptable.ollamachat.data.db.Conversation
import com.trucdecomptable.ollamachat.data.db.ImageStore
import com.trucdecomptable.ollamachat.data.db.imagePathsOf
import com.trucdecomptable.ollamachat.data.db.images
import com.trucdecomptable.ollamachat.data.db.Memory
import com.trucdecomptable.ollamachat.data.db.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Moves the whole local dataset in and out of an encrypted archive.
 *
 * System backup is deliberately disabled so conversations cannot be lifted off
 * the device by anyone holding it; this is the replacement the user controls —
 * without it, changing phone would mean losing everything.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val appVersion: String,
) {

    data class ImportResult(
        val conversations: Int,
        val messages: Int,
        val memories: Int,
        val images: Int,
        val missingImages: Int,
    )

    suspend fun export(passphrase: CharArray, now: Long = System.currentTimeMillis()): ByteArray =
        withContext(Dispatchers.IO) {
            val conversations = db.conversationDao().listAll()
            val images = mutableMapOf<String, ByteArray>()
            val messages = mutableListOf<BackupMessage>()

            conversations.forEach { conversation ->
                db.messageDao().listForConversation(conversation.id).forEach { message ->
                    val imageNames = message.images.mapNotNull { path ->
                        val file = File(path)
                        if (!file.exists()) return@mapNotNull null
                        file.name.also { images[it] = file.readBytes() }
                    }
                    messages.add(
                        BackupMessage(
                            conversationRef = conversation.id,
                            role = message.role,
                            content = message.content,
                            contentType = message.contentType,
                            imageNames = imageNames,
                            createdAt = message.createdAt,
                            stats = message.stats,
                            thinking = message.thinking,
                            toolName = message.toolName,
                            excludedFromContext = message.excludedFromContext,
                        )
                    )
                }
            }

            val payload = BackupPayload(
                exportedAt = now,
                appVersion = appVersion,
                conversations = conversations.map {
                    BackupConversation(
                        ref = it.id,
                        title = it.title,
                        systemPrompt = it.systemPrompt,
                        model = it.model,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt,
                        archived = it.archived,
                    )
                },
                messages = messages,
                memories = db.memoryDao().listAll().map {
                    BackupMemory(it.content, it.createdAt, it.updatedAt)
                },
            )
            BackupCrypto.encrypt(BackupArchive.write(payload, images), passphrase)
        }

    /**
     * Adds the archive's contents alongside what is already there. Importing
     * never overwrites or deletes: a restore that eats the current data is a
     * worse failure than a duplicate conversation.
     */
    suspend fun import(container: ByteArray, passphrase: CharArray): ImportResult =
        withContext(Dispatchers.IO) {
            val (payload, images) = BackupArchive.read(BackupCrypto.decrypt(container, passphrase))

            // Image entry name -> path on this device.
            val restored = mutableMapOf<String, String>()
            images.forEach { (name, bytes) ->
                ImageStore.saveBytes(context, bytes)?.let { restored[name] = it }
            }

            val idByRef = mutableMapOf<Long, Long>()
            payload.conversations.forEach { c ->
                idByRef[c.ref] = db.conversationDao().insert(
                    Conversation(
                        title = c.title,
                        systemPrompt = c.systemPrompt,
                        model = c.model,
                        createdAt = c.createdAt,
                        updatedAt = c.updatedAt,
                        archived = c.archived,
                    )
                )
            }

            var insertedMessages = 0
            var missingImages = 0
            payload.messages.forEach { m ->
                val conversationId = idByRef[m.conversationRef] ?: return@forEach
                val paths = m.imageNames.mapNotNull { name ->
                    restored[name] ?: run { missingImages++; null }
                }
                db.messageDao().insert(
                    Message(
                        conversationId = conversationId,
                        role = m.role,
                        content = m.content,
                        contentType = m.contentType,
                        imagePaths = imagePathsOf(paths),
                        createdAt = m.createdAt,
                        stats = m.stats,
                        thinking = m.thinking,
                        toolName = m.toolName,
                        excludedFromContext = m.excludedFromContext,
                    )
                )
                insertedMessages++
            }

            // Memories are facts, not history: re-importing must not double them.
            val known = db.memoryDao().listAll().map { it.content }.toSet()
            var insertedMemories = 0
            payload.memories.forEach { memory ->
                if (memory.content.isBlank() || memory.content in known) return@forEach
                db.memoryDao().insert(
                    Memory(
                        content = memory.content,
                        createdAt = memory.createdAt,
                        updatedAt = memory.updatedAt,
                    )
                )
                insertedMemories++
            }

            ImportResult(
                conversations = idByRef.size,
                messages = insertedMessages,
                memories = insertedMemories,
                images = restored.size,
                missingImages = missingImages,
            )
        }
}
