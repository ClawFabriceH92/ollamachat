package com.trucdecomptable.ollamachat.data.web

import com.trucdecomptable.ollamachat.util.IpPrefix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
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

    /** Redirects are followed by hand, each hop checked before it is taken. */
    private const val MAX_REDIRECTS = 5

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"

    /** A fetch landed on an address inside a private network it had no right to. */
    class PrivateAddressBlocked(host: String) :
        IOException("adresse de réseau privé refusée ($host)")

    /** Carried on each request so the last-resort interceptor knows what is allowed. */
    private class Access(val privateHost: String?)

    /** Name resolution, replaceable by a test. */
    @Volatile
    internal var dns: Dns = Dns.SYSTEM

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            // Direct, never through a system proxy: through one, the address
            // checked would be the proxy's, not the page's.
            .proxy(Proxy.NO_PROXY)
            // Last resort only. A network interceptor runs once the connection
            // is open, and by then a SYN to a LAN port has already told whoever
            // asked whether it answers. The real checks come before connecting:
            // the resolver of [guardedClient] and [checkLiteral].
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val privateHost = request.tag(Access::class.java)?.privateHost
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
     * A client that refuses, before connecting, a name resolving into a
     * private network (DNS rebinding included: the check runs on every
     * resolution), except [privateHost]. It follows no redirect by itself.
     */
    private fun guardedClient(privateHost: String?): OkHttpClient = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = dns.lookup(hostname)
                if (hostname != privateHost && addresses.any { isPrivate(it.address) || onAttachedNetwork(it) }) {
                    throw PrivateAddressBlocked(hostname)
                }
                return addresses
            }
        })
        .build()

    /** An IP literal never reaches the resolver: checked here, before connecting. */
    private fun checkLiteral(host: String, privateHost: String?) {
        if (host == privateHost || !isIpLiteral(host)) return
        // A literal is parsed, not looked up.
        val address = InetAddress.getByName(host)
        if (isPrivate(address.address) || onAttachedNetwork(address)) throw PrivateAddressBlocked(host)
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || IPV4.matches(host)

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /**
     * Reads a page.
     *
     * [allowPrivate] is for an address the owner typed themselves, and it
     * opens the LAN only to a host that is on the LAN by its very form: an IP
     * literal, a bare machine name, a local suffix. A public name typed by the
     * owner, a link pasted from a mail say, could resolve into the LAN on the
     * second lookup. When the model chose the address, the LAN is out of
     * reach altogether: a planted page could otherwise have it read the router
     * or the Ollama server and pass the result on.
     */
    suspend fun fetch(rawUrl: String, allowPrivate: Boolean = false): Result<String> =
        withContext(Dispatchers.IO) {
            val url = rawUrl.trim()
            val host = url.toHttpUrlOrNull()?.host
            if ((!url.startsWith("http://") && !url.startsWith("https://")) || host == null) {
                return@withContext Result.failure(IllegalArgumentException("URL invalide — commence par http:// ou https://"))
            }
            val local = isPrivateLiteral(url) || looksLocal(host)
            try {
                when (val direct = tryDirect(url, privateHost = if (allowPrivate && local) host else null)) {
                    is Direct.Page -> Result.success(truncate(direct.text, url))
                    Direct.Unreachable -> Result.failure(IllegalStateException("Impossible de joindre le site"))
                    // The reader service sees the full address: only for a
                    // public site that answered, never for a local one or a
                    // hop that went nowhere, or it would learn the LAN's layout
                    // and whoever chose the address with it.
                    Direct.Answered -> if (local) {
                        Result.failure(IllegalStateException("Impossible de lire la page"))
                    } else {
                        tryReader(url)?.let { Result.success(truncate(it, url)) }
                            ?: Result.failure(IllegalStateException("Impossible de lire la page (site protégé ?)"))
                    }
                }
            } catch (e: PrivateAddressBlocked) {
                // Final: falling back to the reader would hand it the address.
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** What the site itself gave. */
    private sealed interface Direct {
        data class Page(val text: String) : Direct

        /** It answered, with nothing readable: the reader service may do better. */
        data object Answered : Direct

        /** Nothing answered. */
        data object Unreachable : Direct
    }

    private fun tryDirect(url: String, privateHost: String?): Direct {
        val client = guardedClient(privateHost)
        var target = url.toHttpUrlOrNull() ?: return Direct.Unreachable
        var hops = 0
        while (true) {
            checkLiteral(target.host, privateHost)
            val request = Request.Builder()
                .url(target)
                .header("User-Agent", USER_AGENT)
                .tag(Access::class.java, Access(privateHost))
                .get()
                .build()
            val response = try {
                client.newCall(request).execute()
            } catch (e: PrivateAddressBlocked) {
                throw e
            } catch (_: IOException) {
                return Direct.Unreachable
            }
            val next = try {
                response.use { resp ->
                    if (resp.isRedirect) {
                        resp.header("Location")?.let { resp.request.url.resolve(it) } ?: return Direct.Answered
                    } else {
                        if (!resp.isSuccessful) return Direct.Answered
                        val text = stripHtml(resp.body?.string() ?: return Direct.Answered)
                        return if (text.isBlank() || text.length < 200) Direct.Answered else Direct.Page(text)
                    }
                }
            } catch (_: IOException) {
                // Headers came, the body did not: the site is there.
                return Direct.Answered
            }
            if (++hops > MAX_REDIRECTS) return Direct.Answered
            target = next
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
