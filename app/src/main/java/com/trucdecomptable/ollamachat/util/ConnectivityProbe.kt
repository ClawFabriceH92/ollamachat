package com.trucdecomptable.ollamachat.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import com.trucdecomptable.ollamachat.update.UpdateChecker
import kotlinx.coroutines.CancellationException

/**
 * Which leg of the network is down.
 *
 * A tunnel breaks one of two opposite things depending on how it is routed.
 * A full tunnel back home reaches the Ollama server on the LAN but has no way
 * out to the internet, so the chat works and the update cannot be checked. A
 * commercial VPN is the mirror image: the internet answers, the LAN does not,
 * so the update works and the server is unreachable. "It does not work" fits
 * both, which is why the two legs are probed and reported separately.
 */
object ConnectivityProbe {

    data class Leg(
        val name: String,
        val ok: Boolean,
        val detail: String,
        val millis: Long,
    )

    data class Report(
        val vpnActive: Boolean,
        val legs: List<Leg>,
        /** What the device can reach, straight from the system. */
        val networks: List<String> = emptyList(),
    ) {

        val allOk: Boolean get() = legs.all { it.ok }

        fun formatted(): String = buildString {
            appendLine("VPN actif : ${if (vpnActive) "oui" else "non"}")
            legs.forEach { leg ->
                append(if (leg.ok) "✓ " else "✗ ")
                append(leg.name)
                append(" — ")
                append(leg.detail.ifBlank { if (leg.ok) "joignable" else "injoignable" })
                appendLine(" (${leg.millis} ms)")
            }
            if (networks.isNotEmpty()) {
                appendLine()
                appendLine("Ce que l'appareil sait joindre :")
                networks.forEach { appendLine("  $it") }
            }
            ConnectivityProbe.diagnosis(this@Report)?.let {
                appendLine()
                append(it)
            }
        }
    }

    /** The sentence that names the cause, when the two legs disagree. */
    internal fun diagnosis(report: Report): String? {
        val server = report.legs.firstOrNull { it.name.startsWith(SERVER) }
        val update = report.legs.firstOrNull { it.name.startsWith(UPDATE) }
        if (server == null || update == null) return null
        return when {
            server.ok && update.ok -> null
            server.ok && !update.ok && report.vpnActive ->
                "Le serveur répond mais pas GitHub : le tunnel capte tout le trafic sans " +
                    "offrir de sortie vers Internet. Les mises à jour ne peuvent pas aboutir " +
                    "tant qu'il est actif."
            !server.ok && update.ok && report.vpnActive ->
                "Internet répond mais pas le serveur : le tunnel ne route pas le réseau local. " +
                    "Un VPN commercial produit exactement cela."
            !server.ok && !update.ok ->
                "Aucune des deux destinations ne répond : le problème est en amont des deux " +
                    "(pas de réseau, ou un tunnel actif qui ne route rien)."
            else -> null
        }
    }

    internal const val SERVER = "Serveur Ollama"
    internal const val UPDATE = "Mise à jour (github.com)"

    suspend fun run(context: Context, baseUrl: String, client: OllamaClient): Report {
        val legs = listOf(
            timed("$SERVER ($baseUrl)") {
                require(baseUrl.isNotBlank()) { "aucune adresse configurée" }
                client.testConnection(baseUrl).getOrThrow()
                "joignable"
            },
            // The real updater path, not a synthetic ping: what fails here is
            // what fails when the popup does not come.
            timed(UPDATE) {
                when (val result = UpdateChecker.check()) {
                    is UpdateChecker.CheckResult.Unreachable -> error(result.reason)
                    is UpdateChecker.CheckResult.Available -> "v${result.info.version} disponible"
                    UpdateChecker.CheckResult.UpToDate -> "à jour"
                }
            },
        )
        return Report(
            vpnActive = vpnActive(context),
            legs = legs,
            networks = describeRouting(context),
        )
    }

    /** True when the active network is a tunnel. */
    fun vpnActive(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    /**
     * Every network the system knows, with its addresses and its routes.
     *
     * This is the one fact no amount of reasoning from here can supply. A
     * tunnel carrying only a default route swallows LAN traffic and loses it
     * at the far end; a tunnel with no route covering the LAN never carries
     * that traffic at all. Both look identical from the app — ten seconds of
     * silence — and they need opposite fixes.
     *
     * Everything is read through toString(): LinkAddress and RouteInfo print
     * what we want, and going through their getters would pull in IpPrefix,
     * which is API 27 while this app supports 26.
     */
    fun describeRouting(context: Context): List<String> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lines = mutableListOf<String>()
        @Suppress("DEPRECATION")
        val networks = cm?.allNetworks?.toList().orEmpty()
        networks.forEach { network ->
            val link = cm?.getLinkProperties(network) ?: return@forEach
            val caps = cm.getNetworkCapabilities(network)
            val kind = when {
                caps == null -> "réseau"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "réseau"
            }
            val addresses = link.linkAddresses
                .map { it.toString() }
                .filter { isIpv4(it) }
            lines += "$kind (${link.interfaceName ?: "?"}) : " +
                addresses.joinToString(", ").ifBlank { "—" }
            link.routes.forEach { route ->
                val text = route.toString()
                if (!isIpv4(text)) return@forEach
                lines += "    $text" + if (route.isDefaultRoute) "  (route par défaut)" else ""
            }
        }
        lines.ifEmpty { listOf("Routage : aucune information") }
    }.getOrElse { listOf("Routage : illisible (${it.javaClass.simpleName})") }

    /** IPv6 is not what any of this is about, and its colons give it away. */
    internal fun isIpv4(text: String): Boolean = !text.contains(':')

    private suspend fun timed(name: String, block: suspend () -> String): Leg {
        val start = System.currentTimeMillis()
        return try {
            val detail = block()
            Leg(name, ok = true, detail = detail, millis = System.currentTimeMillis() - start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Leg(
                name = name,
                ok = false,
                detail = e.message.orEmpty().ifBlank { e.javaClass.simpleName },
                millis = System.currentTimeMillis() - start,
            )
        }
    }
}
