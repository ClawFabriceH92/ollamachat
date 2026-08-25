package com.trucdecomptable.ollamachat.data.ollama

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

    suspend fun scanForOllama(ports: List<Int> = listOf(11434, 11435)): ScanOutcome =
        withContext(Dispatchers.IO) {
            val networks = findLocalNetworks() + readRoutedNetworks()
            if (networks.isEmpty()) return@withContext ScanOutcome(emptyList(), emptyList())
            val candidates = enumerateCandidates(networks)
            val subnetSummary = networks.map { "${it.ip}/${it.prefixLength}" }.distinct()
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
     * Reads the kernel routing table (/proc/net/route) to find networks that
     * are reachable by routing — typically the LAN behind a VPN tunnel, whose
     * subnet does NOT match any local interface address (e.g. phone has a
     * 10.8.0.x tunnel IP, server lives on 192.168.0.0/24 behind the tunnel).
     */
    private fun readRoutedNetworks(): List<NetworkInfo> {
        val result = mutableListOf<NetworkInfo>()
        try {
            val lines = java.io.File("/proc/net/route").readLines()
            for (line in lines.drop(1)) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 8) continue
                val iface = parts[0]
                val dest = hexToInt(parts[1]) ?: continue
                val mask = hexToInt(parts[7]) ?: continue
                if (dest == 0 || mask == 0) continue // default route or host route
                val prefix = Integer.bitCount(mask)
                if (prefix < 8 || prefix > 30) continue
                val ip = intToIp(dest)
                result.add(NetworkInfo(ip, prefix, iface.lowercase()))
            }
        } catch (_: Exception) {
            // /proc/net/route unreadable — fall back to interfaces only.
        }
        return result
    }

    /** Parses a little-endian hex IPv4 (e.g. "0000A8C0" -> 0xC0A80000). Visible for tests. */
    internal fun hexToInt(hex: String): Int? {
        val v = hex.toLongOrNull(16) ?: return null
        val result = ((v and 0xFFL) shl 24) or
            (((v shr 8) and 0xFFL) shl 16) or
            (((v shr 16) and 0xFFL) shl 8) or
            ((v shr 24) and 0xFFL)
        return result.toInt()
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
        val result = mutableListOf<String>()
        networks.forEach { net ->
            val parts = net.ip.split(".").map { it.toIntOrNull() ?: 0 }
            val own24 = parts[0] * 16777216 + parts[1] * 65536 + parts[2] * 256
            val prefix = net.prefixLength.coerceIn(8, 32)

            // Full subnet when it fits in a /24 (or narrower).
            if (prefix >= 24) {
                val hosts = 1 shl (32 - prefix)
                for (i in 1 until hosts) {
                    if (result.size >= MAX_ADDRESSES) return result
                    result.add(intToIp(own24 + i))
                }
            } else {
                // Wide network: scan the interface's own /24.
                for (i in 1..254) {
                    if (result.size >= MAX_ADDRESSES) return result
                    result.add(intToIp(own24 + i))
                }
                // Plus the x.y.0.0/24 of the same first two octets.
                val gw24 = parts[0] * 16777216 + parts[1] * 65536
                for (i in 1..254) {
                    if (result.size >= MAX_ADDRESSES) return result
                    val candidate = intToIp(gw24 + i)
                    if (candidate !in result) result.add(candidate)
                }
            }
        }
        return result.distinct()
    }

    private fun intToIp(value: Int): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
}
