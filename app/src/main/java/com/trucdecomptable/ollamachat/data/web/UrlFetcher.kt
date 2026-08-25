package com.trucdecomptable.ollamachat.data.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches a web page's readable content so the model can answer from it.
 *
 * Strategy: try a direct GET with a browser User-Agent first (works for most
 * simple sites), then fall back to the r.jina.ai reader (converts HTML to
 * clean markdown; handles JS-rendered pages). Cloudflare/CAPTCHA-protected
 * sites (e.g. Légifrance) may refuse both — reported as an error.
 */
object UrlFetcher {

    private const val MAX_CHARS = 40000

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetch(rawUrl: String): Result<String> = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext Result.failure(IllegalArgumentException("URL invalide — commence par http:// ou https://"))
        }
        try {
            val direct = tryDirect(url)
            if (direct != null) return@withContext Result.success(truncate(direct, url))
            val viaReader = tryReader(url)
                ?: return@withContext Result.failure(IllegalStateException("Impossible de lire la page (site protégé ?)"))
            Result.success(truncate(viaReader, url))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun tryDirect(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val text = stripHtml(body)
                if (text.isBlank() || text.length < 200) null else text
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryReader(url: String): String? {
        return try {
            val req = Request.Builder()
                .url("https://r.jina.ai/$url")
                .header("User-Agent", "OllamaChat/1.0 (Android)")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val marker = "Markdown Content:"
                val idx = body.indexOf(marker)
                val content = if (idx >= 0) body.substring(idx + marker.length) else body
                val cleaned = content.trim()
                if (cleaned.isBlank() || cleaned.length < 100) null else cleaned
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun truncate(text: String, url: String): String {
        val clean = text.trim()
        val head = "📄 Contenu de la page $url :\n\n"
        return if (clean.length <= MAX_CHARS) head + clean
        else head + clean.take(MAX_CHARS) + "\n\n[… contenu tronqué — page trop longue]"
    }
}
