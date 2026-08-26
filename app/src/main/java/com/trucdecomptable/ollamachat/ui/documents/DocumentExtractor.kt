package com.trucdecomptable.ollamachat.ui.documents

import android.content.Context
import android.net.Uri
import com.trucdecomptable.ollamachat.data.db.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts readable text from user-picked documents.
 *
 *  - fichier texte   -> raw text (UTF-8)
 *  - application/pdf -> PDFBox text layer
 *  - docx            -> word/document.xml paragraphs
 *  - images          -> downscaled JPEG bytes (sent to vision models)
 */
object DocumentExtractor {

    /** Guard against a multi-hundred-MB pick eating the heap. */
    private const val MAX_INPUT_BYTES = 32 * 1024 * 1024

    /** Extracted text is capped so one document cannot swallow the whole context. */
    private const val MAX_TEXT_CHARS = 60_000

    data class Extracted(
        val text: String? = null,
        val imageBytes: ByteArray? = null,
        val label: String,
    ) {
        // ByteArray in a data class: identity equality is the sane behaviour here.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    suspend fun extract(context: Context, uri: Uri, mime: String): Extracted =
        withContext(Dispatchers.IO) {
            val name = queryName(context, uri) ?: "document"
            when {
                mime.startsWith("image/") -> Extracted(
                    // Downscale on import: a 12 Mpx photo is ~5 MB of base64
                    // for no extra accuracy on any vision model.
                    imageBytes = ImageStore.downscale(readBytes(context, uri)),
                    label = name,
                )
                mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) ->
                    Extracted(text = truncate(extractPdf(context, uri)), label = name)
                mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                    name.endsWith(".docx", ignoreCase = true) ->
                    Extracted(text = truncate(extractDocx(context, uri)), label = name)
                else -> Extracted(text = truncate(readText(context, uri)), label = name)
            }
        }

    private fun truncate(text: String): String =
        if (text.length <= MAX_TEXT_CHARS) text
        else text.take(MAX_TEXT_CHARS) + "\n\n[… document tronqué, trop long pour le contexte]"

    private fun extractPdf(context: Context, uri: Uri): String {
        return try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
            val bytes = readBytes(context, uri)
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes).use { doc ->
                com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
                    .ifBlank { "PDF sans texte extractible (document scanné ?)" }
            }
        } catch (e: Exception) {
            "Impossible d'extraire le texte du PDF : ${e.message}"
        } catch (e: OutOfMemoryError) {
            "PDF trop volumineux pour être lu sur cet appareil."
        }
    }

    private fun extractDocx(context: Context, uri: Uri): String {
        return try {
            val bytes = readBytes(context, uri)
            val sb = StringBuilder()
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        // Paragraphs -> newlines, then strip all tags.
                        sb.append(
                            xml.replace("</w:p>", "\n")
                                .replace(Regex("<[^>]+>"), "")
                                .trim()
                        )
                        break
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            sb.toString().ifBlank { "Aucun texte trouvé dans le document." }
        } catch (e: Exception) {
            "Impossible de lire le DOCX : ${e.message}"
        }
    }

    private fun readText(context: Context, uri: Uri): String {
        return try {
            readBytes(context, uri).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            "Erreur de lecture : ${e.message}"
        }
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray {
        val bos = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0
            var n = input.read(buf)
            while (n != -1) {
                total += n
                if (total > MAX_INPUT_BYTES) break
                bos.write(buf, 0, n)
                n = input.read(buf)
            }
        }
        return bos.toByteArray()
    }

    private fun queryName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }
}
