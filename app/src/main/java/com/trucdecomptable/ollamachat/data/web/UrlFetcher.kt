package com.trucdecomptable.ollamachat.data.web

import com.trucdecomptable.ollamachat.util.IpPrefix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Proxy
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

    /** A fetch the model asked for landed on an address inside a private network. */
    class PrivateAddressBlocked(host: String) :
        IOException("adresse de réseau privé refusée ($host)")

    /**
     * Carried on each request, redirects included, so the network interceptor
     * knows what is allowed: the LAN, on the one host the owner typed.
     */
    private class Access(val privateHost: String?)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            // Direct, never through a system proxy: through one, the address
            // connected to is the proxy's, and the check below would vouch for
            // the proxy instead of the page.
            .proxy(Proxy.NO_PROXY)
            // Checked on the address actually connected to, at every hop: this
            // covers redirects from a public page into the LAN, DNS that
            // answers a private address, and IP literals alike. Checking the
            // URL text before the request would miss all three.
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val privateHost = request.tag(Access::class.java)?.privateHost
                // The owner's word covers the host they typed, not a redirect
                // from it to another machine of the LAN.
                val allowed = privateHost != null && request.url.host == privateHost
                val address = chain.connection()?.route()?.socketAddress?.address
                if (!allowed && (address == null || isPrivate(address.address) || onAttachedNetwork(address))) {
                    throw PrivateAddressBlocked(address?.hostAddress ?: request.url.host)
                }
                chain.proceed(request)
            }
            .build()
    }

    /**
     * Reads a page.
     *
     * [allowPrivate] is for an address the owner typed themselves. When the
     * model chose the address, the LAN is out of reach: a planted page could
     * otherwise have it read the router or the Ollama server and pass the
     * result on.
     */
    suspend fun fetch(rawUrl: String, allowPrivate: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            val url = rawUrl.trim()
            val host = url.toHttpUrlOrNull()?.host
            if ((!url.startsWith("http://") && !url.startsWith("https://")) || host == null) {
                return@withContext Result.failure(IllegalArgumentException("URL invalide — commence par http:// ou https://"))
            }
            try {
                val direct = tryDirect(url, privateHost = if (allowPrivate) host else null)
                if (direct != null) return@withContext Result.success(truncate(direct, url))
                // The reader service sees the full address. One that can only
                // be on the LAN would leak its layout without any chance of
                // success, typed by the owner or not.
                if (isPrivateLiteral(url) || looksLocal(host)) {
                    return@withContext Result.failure(IllegalStateException("Impossible de lire la page"))
                }
                val viaReader = tryReader(url)
                    ?: return@withContext Result.failure(IllegalStateException("Impossible de lire la page (site protégé ?)"))
                Result.success(truncate(viaReader, url))
            } catch (e: PrivateAddressBlocked) {
                // Final: falling back to the reader would hand it the address.
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun tryDirect(url: String, privateHost: String?): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                .tag(Access::class.java, Access(privateHost))
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val text = stripHtml(body)
                if (text.isBlank() || text.length < 200) null else text
            }
        } catch (e: PrivateAddressBlocked) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private val LOCAL_SUFFIXES = listOf(
        ".local", ".lan", ".home", ".internal", ".intranet", ".localdomain", ".home.arpa",
    )

    /**
     * A host only a local network can resolve: an IPv6 literal, a bare
     * machine name, or a suffix home routers hand out.
     */
    internal fun looksLocal(host: String): Boolean {
        val name = host.lowercase().trimEnd('.')
        if (name.contains(':')) return true
        if (!name.contains('.')) return true
        return LOCAL_SUFFIXES.any { name.endsWith(it) }
    }

    /** The prefixes of the networks the phone sits on, read again every few seconds. */
    @Volatile
    private var attached: Pair<Long, List<Pair<InetAddress, Int>>> = 0L to emptyList()

    /**
     * True for an address on a network the phone is attached to. This is
     * what catches the LAN in IPv6: a home router hands out global addresses,
     * which no fixed range can tell from the Internet.
     */
    internal fun onAttachedNetwork(address: InetAddress): Boolean {
        val now = System.currentTimeMillis()
        var snapshot = attached
        if (now - snapshot.first > 10_000) {
            snapshot = now to runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.interfaceAddresses }
                    .map { it.address to it.networkPrefixLength.toInt() }
            }.getOrDefault(emptyList())
            attached = snapshot
        }
        // A zero-length prefix would cover everything; none is a real network.
        return snapshot.second.any { (prefix, length) -> length > 0 && IpPrefix.matches(address, prefix, length) }
    }

    /** True for an IP literal host inside a private range; hostnames are unknown. */
    internal fun isPrivateLiteral(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        val parts = host.split(".")
        if (parts.size != 4) return false
        val bytes = parts.map { it.toIntOrNull() ?: return false }
        if (bytes.any { it !in 0..255 }) return false
        return isPrivate(ByteArray(4) { bytes[it].toByte() })
    }

    /**
     * Loopback, RFC 1918, link-local, carrier-grade NAT, the unspecified
     * address, IPv6 unique-local and link-local, and IPv4-mapped forms of all
     * of them. An address of unknown family is refused.
     */
    internal fun isPrivate(ip: ByteArray): Boolean {
        val b = ip.map { it.toInt() and 0xFF }
        if (b.size == 16) {
            val mapped = (0 until 10).all { b[it] == 0 } && b[10] == 0xFF && b[11] == 0xFF
            if (mapped) return isPrivate(ip.copyOfRange(12, 16))
            if (b.all { it == 0 }) return true
            if ((0 until 15).all { b[it] == 0 } && b[15] == 1) return true
            if ((b[0] and 0xFE) == 0xFC) return true
            if (b[0] == 0xFE && (b[1] and 0xC0) == 0x80) return true
            return false
        }
        if (b.size != 4) return true
        return when {
            b[0] == 0 || b[0] == 10 || b[0] == 127 -> true
            b[0] == 169 && b[1] == 254 -> true
            b[0] == 172 && b[1] in 16..31 -> true
            b[0] == 192 && b[1] == 168 -> true
            b[0] == 100 && b[1] in 64..127 -> true
            else -> false
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
