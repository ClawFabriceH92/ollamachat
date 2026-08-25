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
 * Strategy: derive the phone's own IPv4 + subnet mask, enumerate the reachable
 * host addresses (capped to a sane range), then probe each one on the given
 * ports with a short timeout. Probes run concurrently.
 */
object NetworkScanner {

    data class ScanResult(val baseUrl: String, val version: String?)

    private const val MAX_ADDRESSES = 512
    private const val CONCURRENCY = 32

    private data class NetworkInfo(val ip: String, val prefixLength: Int)

    suspend fun scanForOllama(ports: List<Int> = listOf(11434, 11435)): List<ScanResult> =
        withContext(Dispatchers.IO) {
            val net = findLocalNetwork() ?: return@withContext emptyList()
            val candidates = enumerateCandidates(net)
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
                .connectTimeout(500, TimeUnit.MILLISECONDS)
                .readTimeout(500, TimeUnit.MILLISECONDS)
                .callTimeout(800, TimeUnit.MILLISECONDS)
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

    /** Finds the phone's primary IPv4 (non-loopback, not link-local). */
    private fun findLocalNetwork(): NetworkInfo? {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
            if (!nif.isUp || nif.isLoopback) return@forEach
            val ipv4 = nif.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()
                ?: return@forEach
            val ip = ipv4.hostAddress ?: return@forEach
            if (ip.startsWith("169.254.")) return@forEach
            if (ip.startsWith("127.")) return@forEach
            val mask = (nif.interfaceAddresses.firstOrNull { it.address is Inet4Address }
                ?.networkPrefixLength ?: 24).toInt()
            return NetworkInfo(ip, mask)
        }
        return null
    }

    /** Enumerates host addresses to probe, capped to MAX_ADDRESSES. */
    private fun enumerateCandidates(net: NetworkInfo): List<String> {
        val parts = net.ip.split(".").map { it.toIntOrNull() ?: 0 }
        val networkPrefix = net.prefixLength.coerceIn(8, 30)
        val result = mutableListOf<String>()

        if (networkPrefix >= 24) {
            // Classic /24 (or narrower): scan the whole subnet.
            val base = parts[0] * 16777216 + parts[1] * 65536 + parts[2] * 256
            val hosts = 1 shl (32 - networkPrefix)
            for (i in 1 until hosts) {
                if (result.size >= MAX_ADDRESSES) break
                val ipInt = base + i
                result.add(intToIp(ipInt))
            }
        } else {
            // Wider subnet (/16, /8): scan the phone's own /24 first, then the
            // gateway subnet (x.y.0.0/24) — a practical compromise.
            val own24 = parts[0] * 16777216 + parts[1] * 65536 + parts[2] * 256
            for (i in 1..254) result.add(intToIp(own24 + i))
            val gw24 = parts[0] * 16777216 + parts[1] * 65536
            for (i in 1..254) {
                if (result.size >= MAX_ADDRESSES) break
                result.add(intToIp(gw24 + i))
            }
        }
        return result.distinct()
    }

    private fun intToIp(value: Int): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
}
