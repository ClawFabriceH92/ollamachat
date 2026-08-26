package com.trucdecomptable.ollamachat.data.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One exported conversation, without database identifiers. */
data class BackupConversation(
    val ref: Long,                 // local reference, remapped on import
    val title: String,
    val systemPrompt: String?,
    val model: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean,
)

data class BackupMessage(
    val conversationRef: Long,
    val role: String,
    val content: String,
    val contentType: String,
    val imageName: String?,        // entry name inside the archive
    val createdAt: Long,
    val stats: String?,
    val thinking: String?,
    val toolName: String?,
    val excludedFromContext: Boolean,
)

data class BackupMemory(val content: String, val createdAt: Long, val updatedAt: Long)

data class BackupPayload(
    val version: Int = BackupArchive.FORMAT_VERSION,
    val exportedAt: Long,
    val appVersion: String,
    val conversations: List<BackupConversation>,
    val messages: List<BackupMessage>,
    val memories: List<BackupMemory>,
)

/**
 * The archive itself: a ZIP holding `payload.json` plus the image files the
 * messages point at. Kept free of Android APIs so the round-trip is covered by
 * plain unit tests.
 */
object BackupArchive {

    const val FORMAT_VERSION = 1
    private const val PAYLOAD_ENTRY = "payload.json"
    private const val IMAGE_PREFIX = "images/"

    class UnsupportedVersionException(val found: Int) :
        Exception("Sauvegarde en version $found, non prise en charge")

    class CorruptArchiveException : Exception("Sauvegarde illisible")

    fun write(payload: BackupPayload, images: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(PAYLOAD_ENTRY))
            zip.write(encode(payload).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            images.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(IMAGE_PREFIX + name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun read(archive: ByteArray): Pair<BackupPayload, Map<String, ByteArray>> {
        var payload: BackupPayload? = null
        val images = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(archive.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == PAYLOAD_ENTRY -> payload = decode(zip.readBytes().toString(Charsets.UTF_8))
                        name.startsWith(IMAGE_PREFIX) && !entry.isDirectory ->
                            // Ignore any path component: a crafted archive must
                            // not be able to write outside the image folder.
                            images[name.removePrefix(IMAGE_PREFIX).substringAfterLast('/')] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: UnsupportedVersionException) {
            throw e
        } catch (e: Exception) {
            throw CorruptArchiveException()
        }
        return (payload ?: throw CorruptArchiveException()) to images
    }

    fun encode(payload: BackupPayload): String = JSONObject().apply {
        put("version", payload.version)
        put("exportedAt", payload.exportedAt)
        put("appVersion", payload.appVersion)
        put(
            "conversations",
            JSONArray().apply {
                payload.conversations.forEach { c ->
                    put(
                        JSONObject().apply {
                            put("ref", c.ref)
                            put("title", c.title)
                            putOpt("systemPrompt", c.systemPrompt)
                            putOpt("model", c.model)
                            put("createdAt", c.createdAt)
                            put("updatedAt", c.updatedAt)
                            put("archived", c.archived)
                        }
                    )
                }
            }
        )
        put(
            "messages",
            JSONArray().apply {
                payload.messages.forEach { m ->
                    put(
                        JSONObject().apply {
                            put("conversationRef", m.conversationRef)
                            put("role", m.role)
                            put("content", m.content)
                            put("contentType", m.contentType)
                            putOpt("imageName", m.imageName)
                            put("createdAt", m.createdAt)
                            putOpt("stats", m.stats)
                            putOpt("thinking", m.thinking)
                            putOpt("toolName", m.toolName)
                            put("excludedFromContext", m.excludedFromContext)
                        }
                    )
                }
            }
        )
        put(
            "memories",
            JSONArray().apply {
                payload.memories.forEach { m ->
                    put(
                        JSONObject().apply {
                            put("content", m.content)
                            put("createdAt", m.createdAt)
                            put("updatedAt", m.updatedAt)
                        }
                    )
                }
            }
        )
    }.toString()

    fun decode(json: String): BackupPayload {
        val root = JSONObject(json)
        val version = root.optInt("version", 0)
        if (version != FORMAT_VERSION) throw UnsupportedVersionException(version)

        val conversations = root.optJSONArray("conversations").map { o ->
            BackupConversation(
                ref = o.optLong("ref"),
                title = o.optString("title", ""),
                systemPrompt = o.optStringOrNull("systemPrompt"),
                model = o.optStringOrNull("model"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                archived = o.optBoolean("archived", false),
            )
        }
        val messages = root.optJSONArray("messages").map { o ->
            BackupMessage(
                conversationRef = o.optLong("conversationRef"),
                role = o.optString("role", "user"),
                content = o.optString("content", ""),
                contentType = o.optString("contentType", "text"),
                imageName = o.optStringOrNull("imageName"),
                createdAt = o.optLong("createdAt"),
                stats = o.optStringOrNull("stats"),
                thinking = o.optStringOrNull("thinking"),
                toolName = o.optStringOrNull("toolName"),
                excludedFromContext = o.optBoolean("excludedFromContext", false),
            )
        }
        val memories = root.optJSONArray("memories").map { o ->
            BackupMemory(
                content = o.optString("content", ""),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
        return BackupPayload(
            version = version,
            exportedAt = root.optLong("exportedAt"),
            appVersion = root.optString("appVersion", ""),
            conversations = conversations,
            messages = messages,
            memories = memories,
        )
    }

    private fun <T> JSONArray?.map(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(transform) }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, "").ifBlank { null }
}
