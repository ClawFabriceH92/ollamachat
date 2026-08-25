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

    private const val MAX_ADDRESSES = 640
    private const val CONCURRENCY = 32

    private data class NetworkInfo(val ip: String, val prefixLength: Int, val ifaceName: String)

    suspend fun scanForOllama(ports: List<Int> = listOf(11434, 11435)): List<ScanResult> =
        withContext(Dispatchers.IO) {
            val networks = findLocalNetworks()
            if (networks.isEmpty()) return@withContext emptyList()
            val candidates = enumerateCandidates(networks)
            if (candidates.isEmpty()) return@withContext emptyList()
            coroutineScope {
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
        }

    private fun probe(baseUrl: String): String? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(600, TimeUnit.MILLISECONDS)
                .readTimeout(600, TimeUnit.MILLISECONDS)
                .callTimeout(900, TimeUnit.MILLISECONDS)
                .build()
            val req = Request.Builder().url("$baseUrl/api/version").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val version = org.json.JSONObject(body).optString("version", "")
                version.ifBlank { null } ?: "?"
            }
        } catch (_: Exception) {
            null
        }
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
