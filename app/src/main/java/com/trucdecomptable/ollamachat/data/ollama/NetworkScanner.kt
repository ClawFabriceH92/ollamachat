package com.trucdecomptable.ollamachat.data.ollama

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Discovers Ollama servers on the local network.
 *
 * Strategy: collect EVERY IPv4 interface of the phone (WiFi, mobile, VPN
 * tunnels) with physical interfaces first, then probe each /24 subnet derived
 * from those interfaces on the given ports. Probes run concurrently with a
 * short timeout. This handles VPN setups where the phone's WiFi IP and its
 * tunnel IP live on different subnets.
 */
object NetworkScanner {

    data class ScanResult(val baseUrl: String, val version: String?)

    /** Scan outcome: found servers + a human-readable summary of scanned subnets. */
    data class ScanOutcome(
        val results: List<ScanResult>,
        val scannedSubnets: List<String>,
    )

    private const val MAX_ADDRESSES = 640
    private const val CONCURRENCY = 32

    private data class NetworkInfo(val ip: String, val prefixLength: Int, val ifaceName: String)

    /** One shared client for all probes — creating hundreds of clients saturates sockets/threads. */
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(2000, TimeUnit.MILLISECONDS)
            .callTimeout(3000, TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun scanForOllama(
        context: Context,
        ports: List<Int> = listOf(11434, 11435),
    ): ScanOutcome =
        withContext(Dispatchers.IO) {
            // Networks behind a tunnel first: they are the reason this exists,
            // and the address cap is otherwise spent on the phone's own mobile
            // and tunnel subnets before it gets there. Compared by network
            // address, so a route and an interface on the same subnet count once.
            val networks = (readRoutedNetworks(context) + findLocalNetworks())
                // Keyed on the /24 actually enumerated: a wide network is
                // scanned around its own address, so 10.0.0.0/16 and
                // 10.0.5.37/16 are two scans, not one.
                .distinctBy { networkOf(it.ip, maxOf(it.prefixLength, 24)) to it.prefixLength }
            if (networks.isEmpty()) return@withContext ScanOutcome(emptyList(), emptyList())
            val candidates = enumerateCandidates(networks)
            val subnetSummary = networks.map { "${networkOf(it.ip, it.prefixLength)}/${it.prefixLength}" }.distinct()
            if (candidates.isEmpty()) return@withContext ScanOutcome(emptyList(), subnetSummary)
            val results = coroutineScope {
                candidates.map { ip ->
                    async {
                        ports.forEach { port ->
                            val baseUrl = "http://$ip:$port"
                            val version = probe(baseUrl) ?: return@async null
                            return@async ScanResult(baseUrl, version)
                        }
                        null
                    }
                }.awaitAll().filterNotNull()
            }
            ScanOutcome(results, subnetSummary)
        }

    private fun probe(baseUrl: String): String? {
        return try {
            val req = Request.Builder().url("$baseUrl/api/version").get().build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val version = org.json.JSONObject(body).optString("version", "")
                version.ifBlank { null } ?: "?"
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Networks reachable through a route rather than an interface address —
     * typically the LAN behind a VPN tunnel, whose subnet matches no address
     * of the phone (a 192.168.27.x tunnel address, a server in 192.168.0.0/24).
     *
     * Read through ConnectivityManager. This used to read /proc/net/route,
     * which Android has not let an app read since Android 10: the failure was
     * swallowed and the LAN behind the tunnel was never scanned at all.
     */
    private fun readRoutedNetworks(context: Context): List<NetworkInfo> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching emptyList()
        @Suppress("DEPRECATION")
        cm.allNetworks.toList()
            // Only tunnels: the other networks' routes are the subnets of their
            // own interfaces, which findLocalNetworks already has.
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            .flatMap { network ->
                val link = cm.getLinkProperties(network) ?: return@flatMap emptyList<NetworkInfo>()
                val iface = link.interfaceName.orEmpty().lowercase()
                val own = link.linkAddresses
                link.routes.mapNotNull { route ->
                    val destination = route.destination
                    val address = destination.address
                    val text = route.toString()
                    when {
                        route.isDefaultRoute -> null
                        address !is Inet4Address -> null
                        // A tunnel's excluded ranges are ways out of it, not networks.
                        text.contains(" throw") || text.contains(" unreachable") -> null
                        destination.prefixLength !in 8..30 -> null
                        // "Exclure les IP privées" fills the tunnel with public
                        // ranges: an Ollama server lives on a private network.
                        !address.isSiteLocalAddress && !isCarrierGrade(address) -> null
                        // The tunnel's own subnet is scanned with its interface,
                        // but only when the interface spans it: a /32 tunnel
                        // address covers nothing, and a server at 10.8.0.1
                        // behind a 10.8.0.2/32 tunnel lives in the route alone.
                        // A wider route around a /32 tunnel address would be
                        // scanned around its network address, far from any
                        // peer (Tailscale's 100.64.0.0/10): left out as before.
                        own.any {
                            route.matches(it.address) &&
                                (it.prefixLength <= destination.prefixLength || destination.prefixLength < 24)
                        } -> null
                        else -> address.hostAddress?.let { NetworkInfo(it, destination.prefixLength, iface) }
                    }
                }
            }
    }.getOrDefault(emptyList())

    /** 100.64.0.0/10, which Tailscale and some VPNs use for their own networks. */
    private fun isCarrierGrade(address: Inet4Address): Boolean {
        val b = address.address
        return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
    }

    /** The network address of [ip] for a prefix, as dotted text. */
    internal fun networkOf(ip: String, prefixLength: Int): String {
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return ip
        val value = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val length = prefixLength.coerceIn(0, 32)
        val mask = if (length == 0) 0 else -1 shl (32 - length)
        return intToIp(value and mask)
    }

    /** Collects every usable IPv4 interface; physical interfaces first, VPN tunnels last. */
    private fun findLocalNetworks(): List<NetworkInfo> {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val collected = mutableListOf<NetworkInfo>()
        all.forEach { nif ->
            if (!nif.isUp || nif.isLoopback) return@forEach
            val ipv4 = nif.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()
                ?: return@forEach
            val ip = ipv4.hostAddress ?: return@forEach
            if (ip.startsWith("169.254.") || ip.startsWith("127.")) return@forEach
            val mask = (nif.interfaceAddresses.firstOrNull { it.address is Inet4Address }
                ?.networkPrefixLength ?: 24).toInt()
            collected.add(NetworkInfo(ip, mask, nif.name.lowercase()))
        }
        // Physical interfaces (wlan/eth/rmnet/wifi) before tunnels (tun/ppp/tap/vpn).
        return collected.sortedBy { info ->
            when {
                info.ifaceName.contains("tun") || info.ifaceName.contains("tap") ||
                    info.ifaceName.contains("ppp") || info.ifaceName.contains("vpn") -> 2
                else -> 0
            }
        }
    }

    /**
     * Enumerates host addresses to probe. For each interface, always scan its
     * own /24 (covers VPN /30 and /32 tunnels too, where peers live on nearby
     * addresses). Wide networks (> /24) also get the x.y.0.0/24 prefix scan.
     */
    private fun enumerateCandidates(networks: List<NetworkInfo>): List<String> {
        // A set: an address listed twice must not spend the cap twice.
        val result = LinkedHashSet<String>()
        networks.forEach { net ->
            val parts = net.ip.split(".").map { it.toIntOrNull() ?: 0 }
            val own24 = parts[0] * 16777216 + parts[1] * 65536 + parts[2] * 256
            val prefix = net.prefixLength.coerceIn(8, 32)

            // Full subnet when it fits in a /24 (or narrower).
            if (prefix >= 24) {
                val hosts = 1 shl (32 - prefix)
                for (i in 1 until hosts) {
                    if (result.size >= MAX_ADDRESSES) return result.toList()
                    result.add(intToIp(own24 + i))
                }
            } else {
                // Wide network: scan the interface's own /24.
                for (i in 1..254) {
                    if (result.size >= MAX_ADDRESSES) return result.toList()
                    result.add(intToIp(own24 + i))
                }
                // Plus the x.y.0.0/24 of the same first two octets.
                val gw24 = parts[0] * 16777216 + parts[1] * 65536
                for (i in 1..254) {
                    if (result.size >= MAX_ADDRESSES) return result.toList()
                    result.add(intToIp(gw24 + i))
                }
            }
        }
        return result.toList()
    }

    private fun intToIp(value: Int): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
}
