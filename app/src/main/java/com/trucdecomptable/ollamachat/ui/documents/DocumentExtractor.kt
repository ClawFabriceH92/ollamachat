package com.trucdecomptable.ollamachat.ui.documents

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts readable text from user-picked documents.
 *
 *  - fichier texte   -> raw text (UTF-8)
 *  - application/pdf -> PDFBox text layer
 *  - docx            -> word/document.xml paragraphs
 *  - images          -> bytes kept as-is (sent to vision models)
 */
object DocumentExtractor {

    data class Extracted(
        val text: String? = null,
        val imageBase64: String? = null,
        val label: String,
    )

    suspend fun extract(context: Context, uri: Uri, mime: String): Extracted = withContextIO {
        val name = queryName(context, uri) ?: "document"
        when {
            mime.startsWith("image/") -> {
                val bytes = readBytes(context, uri)
                Extracted(
                    imageBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                    label = name,
                )
            }
            mime == "application/pdf" -> Extracted(text = extractPdf(context, uri), label = name)
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                name.endsWith(".docx", ignoreCase = true) ->
                Extracted(text = extractDocx(context, uri), label = name)
            mime.startsWith("text/") || name.endsWith(".md", ignoreCase = true) ||
                name.endsWith(".txt", ignoreCase = true) ->
                Extracted(text = readText(context, uri), label = name)
            else -> Extracted(text = readText(context, uri), label = name)
        }
    }

    private fun extractPdf(context: Context, uri: Uri): String {
        return try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
            val bytes = readBytes(context, uri)
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes).use { doc ->
                com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
                    .ifBlank { "⚠️ PDF sans texte extractible (document scanné ?)" }
            }
        } catch (e: Exception) {
            "⚠️ Impossible d'extraire le texte du PDF : ${e.message}"
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
            sb.toString().ifBlank { "⚠️ Aucun texte trouvé dans le document." }
        } catch (e: Exception) {
            "⚠️ Impossible de lire le DOCX : ${e.message}"
        }
    }

    private fun readText(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: "⚠️ Fichier illisible."
        } catch (e: Exception) {
            "⚠️ Erreur de lecture : ${e.message}"
        }
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray {
        val bos = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            var n = input.read(buf)
            while (n != -1) {
                bos.write(buf, 0, n)
                n = input.read(buf)
            }
        }
        return bos.toByteArray()
    }

    private fun queryName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun <T> withContextIO(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
}
