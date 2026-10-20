package com.trucdecomptable.ollamachat.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.RouteInfo
import com.trucdecomptable.ollamachat.data.ollama.OllamaClient
import com.trucdecomptable.ollamachat.update.UpdateChecker
import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NoRouteToHostException

/**
 * Which leg of the network is down, and why.
 *
 * Two legs are probed separately — the Ollama server and the update host —
 * because "it does not work" describes failures with opposite fixes. The
 * verdict is then read off the device's own routing table rather than
 * guessed from which leg failed: an earlier version called a home WireGuard
 * tunnel with a missing return route "a commercial VPN", the one cause it was
 * not, because it never looked at the routes it had just printed.
 */
object ConnectivityProbe {

    data class Leg(
        val name: String,
        val ok: Boolean,
        val detail: String,
        val millis: Long,
        /** Something answered, even with a refusal: the network itself works. */
        val reached: Boolean = false,
    )

    /** How the device would send packets to the server, per its routing table. */
    enum class ServerRoute {
        /** A tunnel route covers the server's address specifically. */
        VPN_SPECIFIC,

        /** Only the tunnel's default route would carry it. */
        VPN_DEFAULT_ONLY,

        /** A tunnel is up, but none of its routes covers the server. */
        OUTSIDE_VPN,

        /** Outside any tunnel, on a network the phone is directly attached to (the Wi-Fi). */
        LOCAL_LINK,

        /** No tunnel, a host name instead of an address, or routes unreadable. */
        UNKNOWN,
    }

    data class Report(
        val vpnActive: Boolean,
        val legs: List<Leg>,
        /** What the device can reach, straight from the system. */
        val networks: List<String> = emptyList(),
        val serverRoute: ServerRoute = ServerRoute.UNKNOWN,
        /** A tunnel carries a default route: everything goes through it. */
        val vpnDefaultRoute: Boolean = false,
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

    /** The sentence that names the cause, or null when both legs answer. */
    internal fun diagnosis(report: Report): String? {
        val server = report.legs.firstOrNull { it.name.startsWith(SERVER) }
        val update = report.legs.firstOrNull { it.name.startsWith(UPDATE) }
        if (server == null || update == null) return null
        if (server.ok && update.ok) return null

        if (!server.ok) {
            // A refusal came back from the server's machine: the way there and
            // back works, whatever the routes say.
            if (server.reached && server.detail.startsWith("HTTP ")) {
                return "Un serveur web répond à cette adresse (${server.detail}) mais ce n'est pas " +
                    "l'API d'Ollama : vérifie le port (11434 par défaut) et l'adresse."
            }
            if (server.reached) {
                return "Le serveur répond mais refuse la connexion : Ollama ne " +
                    "tourne pas, n'écoute que sur 127.0.0.1 (règle OLLAMA_HOST=0.0.0.0), ou un " +
                    "pare-feu rejette le port. Le réseau, lui, achemine bien les paquets."
            }
            if (!update.ok && !update.reached) {
                return "Aucune des deux destinations ne répond : le problème est en amont " +
                    "des deux (pas de réseau, ou un tunnel actif qui ne route rien)."
            }
            return when (report.serverRoute) {
                ServerRoute.VPN_SPECIFIC ->
                    "Le tunnel achemine bien le réseau du serveur : ce qui manque est au-delà. " +
                        "Il faut une route de retour vers le sous-réseau du tunnel (sur la box ou " +
                        "le serveur), ou du NAT (masquerade) sur le serveur VPN ; sinon, c'est le " +
                        "pare-feu de la machine du serveur qui refuse les adresses du tunnel."
                ServerRoute.VPN_DEFAULT_ONLY ->
                    "Tout le trafic part dans le tunnel, qui n'a pas de route dédiée vers le " +
                        "serveur. Un VPN commercial ne peut pas joindre ton réseau local ; avec " +
                        "ton VPN maison, c'est l'extrémité du tunnel qui ne joint pas ce serveur " +
                        "ou ne sait pas répondre aux adresses du tunnel."
                ServerRoute.OUTSIDE_VPN ->
                    "Un tunnel est actif mais aucune de ses routes ne couvre le serveur : ajoute " +
                        "son sous-réseau aux adresses autorisées du VPN (AllowedIPs dans " +
                        "WireGuard), ou décoche « Exclure les IP privées »."
                ServerRoute.LOCAL_LINK ->
                    "Le serveur est sur le réseau local du téléphone, hors de tout tunnel, et ne " +
                        "répond pas : la machine est éteinte ou en veille, ou son pare-feu ignore " +
                        "le téléphone."
                ServerRoute.UNKNOWN ->
                    "Internet répond mais pas le serveur. La table de routage ci-dessus dit " +
                        "par où ses paquets partent."
            }
        }

        // The server answers; only the update host does not.
        return when {
            update.reached ->
                "GitHub répond mais refuse (${update.detail}) : Internet passe, le problème " +
                    "vient de GitHub ou du dépôt, pas du réseau."
            report.vpnActive && report.vpnDefaultRoute ->
                "Le serveur répond mais GitHub est injoignable : le tunnel capte tout le trafic " +
                    "sans offrir de sortie vers Internet. Les mises à jour ne peuvent pas " +
                    "aboutir tant qu'il est actif."
            else ->
                "Le serveur répond mais GitHub est injoignable : l'accès de l'appareil à " +
                    "Internet est coupé ou filtré."
        }
    }

    internal const val SERVER = "Serveur Ollama"
    internal const val UPDATE = "Mise à jour (github.com)"

    /** A failed leg that still knows whether anything answered. */
    private class LegFailure(message: String, val reached: Boolean) : Exception(message)

    suspend fun run(context: Context, baseUrl: String, client: OllamaClient): Report {
        val legs = listOf(
            timed("$SERVER ($baseUrl)") {
                require(baseUrl.isNotBlank()) { "aucune adresse configurée" }
                client.testConnection(baseUrl).getOrElse { e ->
                    throw LegFailure(e.message ?: e.javaClass.simpleName, reached = refusedByServer(e))
                }
                "joignable"
            },
            // The real updater path, not a synthetic ping: what fails here is
            // what fails when the popup does not come.
            timed(UPDATE) {
                when (val result = UpdateChecker.check()) {
                    is UpdateChecker.CheckResult.Unreachable ->
                        throw LegFailure(result.reason, reached = result.answered)
                    is UpdateChecker.CheckResult.Available -> "v${result.info.version} disponible"
                    UpdateChecker.CheckResult.UpToDate -> "à jour"
                }
            },
        )
        val routes = routes(context)
        val vpn = routes.filter { it.vpn }
        return Report(
            vpnActive = vpnActive(context),
            legs = legs,
            networks = describeRouting(context),
            serverRoute = ipv4Host(baseUrl)
                ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
                ?.let { classify(it, routes) }
                ?: ServerRoute.UNKNOWN,
            // An IPv6 default route sends nothing of an IPv4 server anywhere.
            vpnDefaultRoute = vpn.any { it.isDefault && !it.excluded && it.prefix is Inet4Address },
        )
    }

    /**
     * Whether a failed connection proves the server's machine answered: a
     * refusal (a reset came back) or an HTTP error. A timeout or "no route"
     * proves nothing came back at all.
     */
    internal fun refusedByServer(e: Throwable): Boolean {
        if (e.message.orEmpty().startsWith("HTTP ")) return true
        if (e is NoRouteToHostException) return false
        var cause: Throwable? = e
        while (cause != null) {
            val text = cause.message.orEmpty()
            if (text.contains("ECONNREFUSED") || text.contains("Connection refused", ignoreCase = true)) return true
            cause = cause.cause
        }
        return e is ConnectException && e.message.orEmpty().contains("refused", ignoreCase = true)
    }

    /** A route, reduced to what the classification needs. */
    internal data class Route(
        val prefix: InetAddress,
        val length: Int,
        val isDefault: Boolean,
        /** Declared by a tunnel. */
        val vpn: Boolean,
        /** A tunnel's way out (throw or unreachable), not a way in. */
        val excluded: Boolean = false,
    )

    /**
     * How packets for [server] would leave, from the routes of every network.
     * A tunnel's exclusion covering the server sends it outside the tunnel;
     * then a network the phone is attached to may still carry it.
     */
    internal fun classify(server: InetAddress, routes: List<Route>): ServerRoute {
        fun covers(r: Route) = IpPrefix.matches(server, r.prefix, r.length)
        val vpn = routes.filter { it.vpn }
        val excluded = vpn.any { it.excluded && covers(it) }
        val inside = vpn.filterNot { it.excluded }
        return when {
            !excluded && inside.any { !it.isDefault && covers(it) } -> ServerRoute.VPN_SPECIFIC
            !excluded && inside.any { it.isDefault && covers(it) } -> ServerRoute.VPN_DEFAULT_ONLY
            routes.any { !it.vpn && !it.isDefault && covers(it) } -> ServerRoute.LOCAL_LINK
            vpn.isNotEmpty() -> ServerRoute.OUTSIDE_VPN
            else -> ServerRoute.UNKNOWN
        }
    }

    /** True when the active network is a tunnel. */
    fun vpnActive(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    /** Every route of every network, marked with what it means for the classification. */
    private fun routes(context: Context): List<Route> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching emptyList()
        @Suppress("DEPRECATION")
        cm.allNetworks.toList().flatMap { network ->
            val vpn = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            cm.getLinkProperties(network)?.routes.orEmpty().map { route -> route.toRoute(vpn) }
        }
    }.getOrDefault(emptyList())

    private fun RouteInfo.toRoute(vpn: Boolean) = Route(
        prefix = destination.address,
        length = destination.prefixLength,
        isDefault = isDefaultRoute,
        vpn = vpn,
        excluded = isExclusion(toString()),
    )

    /** RouteInfo prints its type after the route when it is not a plain unicast route. */
    internal fun isExclusion(route: String): Boolean =
        route.contains(" throw") || route.contains(" unreachable")

    /** The host of [baseUrl] when it is an IPv4 literal, else null. */
    internal fun ipv4Host(baseUrl: String): String? {
        val url = OllamaClient.normalizeBaseUrl(baseUrl)
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        val parts = host.split(".")
        if (parts.size != 4) return null
        return if (parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) host else null
    }

    /**
     * Every network the system knows, with its addresses and its routes.
     *
     * This is the one fact no amount of reasoning from here can supply. A
     * tunnel carrying only a default route swallows LAN traffic and loses it
     * at the far end; a tunnel with no route covering the LAN never carries
     * that traffic at all. Both look identical from the app — ten seconds of
     * silence — and they need opposite fixes. Printed through toString(),
     * which already gives the route, gateway, interface and type.
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
                reached = (e as? LegFailure)?.reached ?: false,
            )
        }
    }
}
