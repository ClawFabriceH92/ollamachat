package com.trucdecomptable.ollamachat.util

/**
 * Reads the diagnostic log and says what is wrong, in words that name an action.
 *
 * Sharing a raw log only works when someone who knows the code is on the other
 * end. The failures this app actually produces have recognisable shapes — a
 * connect timeout between two different subnets is a tunnel that does not
 * carry the LAN, an exhausted GitHub quota is a shared VPN exit — and the app
 * can say so itself.
 *
 * It is deliberately cautious. Every finding carries the line it was drawn
 * from, so a wrong guess is visible rather than authoritative, and a log that
 * matches nothing yields nothing: claiming a cause we cannot see would repeat
 * the very bug this whole diagnostic effort exists to undo.
 */
object LogExpert {

    enum class Severity { BLOCKING, WARNING, INFO }

    data class Finding(
        val severity: Severity,
        val title: String,
        /** The log line this was read from, so the reasoning can be checked. */
        val evidence: String,
        val action: String,
        /** How many log entries matched; 1 unless the failure repeats. */
        val occurrences: Int = 1,
    )

    /** "to /192.168.0.162 (port 11434) from /192.168.27.65 (port 38780)" */
    private val CONNECT_FAILURE =
        Regex("""to /(\d+\.\d+\.\d+\.\d+) \(port (\d+)\) from /(\d+\.\d+\.\d+\.\d+)""")

    fun analyze(entries: List<DiagnosticLog.Entry>): List<Finding> {
        val findings = listOfNotNull(
            tunnelNeverReachesServer(entries),
            connectionRefused(entries),
            nameResolutionFailed(entries),
            githubQuotaExhausted(entries),
            updateChecksFailing(entries),
            mcpServersUnreachable(entries),
            databaseNotEncrypted(entries),
        )
        return findings.sortedBy { it.severity.ordinal }
    }

    /** True when a tunnel was up the last time connectivity was measured. */
    internal fun vpnSeen(entries: List<DiagnosticLog.Entry>): Boolean =
        entries.any { it.tag == "réseau" && it.message.contains("VPN actif : oui") }

    private fun tunnelNeverReachesServer(entries: List<DiagnosticLog.Entry>): Finding? {
        val matches = entries.filter {
            it.tag.startsWith("ollama") && it.message.contains("SocketTimeoutException")
        }
        val hit = matches.firstNotNullOfOrNull { entry ->
            CONNECT_FAILURE.find(entry.message)?.let { entry to it }
        } ?: return null
        val (entry, match) = hit
        val server = match.groupValues[1]
        val port = match.groupValues[2]
        val phone = match.groupValues[3]
        if (sameSubnet24(server, phone)) return null

        val tunnel = if (vpnSeen(entries)) "Un tunnel est actif. " else ""
        return Finding(
            severity = Severity.BLOCKING,
            title = "Le serveur n'est jamais joint : $phone ne reçoit rien de $server",
            evidence = entry.message,
            occurrences = matches.size,
            action = buildString {
                append(tunnel)
                append("Les deux adresses sont sur des réseaux différents et la connexion ")
                append("meurt en silence — ni refus, ni « route absente ». Les paquets partent ")
                append("et rien ne revient.\n\n")
                append("Deux tests qui tranchent :\n")
                append("1. Depuis ce téléphone, ouvre la page de ta box (souvent ")
                append("http://${server.substringBeforeLast('.')}.1). Si elle s'affiche, le ")
                append("tunnel route bien ce réseau et le problème est propre à $server : ")
                append("son pare-feu, ou sa route de retour vers $phone.\n")
                append("2. Si elle ne s'affiche pas, le tunnel ne mène pas à ce réseau ; ")
                append("c'est sa configuration qu'il faut revoir, pas le serveur.\n\n")
                append("Vérifie aussi que le serveur écoute bien sur le port $port et qu'il ")
                append("accepte les connexions venant d'ailleurs que de son propre réseau.")
            },
        )
    }

    private fun connectionRefused(entries: List<DiagnosticLog.Entry>): Finding? {
        val matches = entries.filter {
            it.tag.startsWith("ollama") && it.message.contains("ConnectException")
        }
        val entry = matches.firstOrNull() ?: return null
        return Finding(
            severity = Severity.BLOCKING,
            title = "Connexion refusée par le serveur",
            evidence = entry.message,
            occurrences = matches.size,
            action = "L'adresse est joignable mais rien n'écoute sur ce port. Vérifie " +
                "qu'Ollama tourne, et qu'il écoute au-delà de 127.0.0.1 " +
                "(OLLAMA_HOST=0.0.0.0) — sans quoi lui seul peut s'y connecter.",
        )
    }

    private fun nameResolutionFailed(entries: List<DiagnosticLog.Entry>): Finding? {
        val matches = entries.filter { it.message.contains("UnknownHostException") }
        val entry = matches.firstOrNull() ?: return null
        return Finding(
            severity = Severity.BLOCKING,
            title = "Nom d'hôte non résolu",
            evidence = entry.message,
            occurrences = matches.size,
            action = "Le DNS ne répond pas. Un tunnel qui capte tout le trafic sans " +
                "fournir de serveur DNS donne exactement cela. Une adresse IP à la " +
                "place du nom contourne le problème et confirme le diagnostic.",
        )
    }

    private fun githubQuotaExhausted(entries: List<DiagnosticLog.Entry>): Finding? {
        val entry = entries.firstOrNull {
            it.tag.startsWith("update") && it.message.contains("quota")
        } ?: return null
        return Finding(
            severity = Severity.WARNING,
            title = "Mises à jour bloquées par le quota GitHub",
            evidence = entry.message,
            action = "L'API GitHub autorise 60 requêtes par heure et par adresse IP. " +
                "Derrière une sortie VPN partagée, des inconnus l'ont souvent déjà " +
                "épuisé. Les versions à partir de la 1.5.3 lisent un manifeste publié " +
                "à côté de l'APK, qui échappe à ce quota.",
        )
    }

    private fun updateChecksFailing(entries: List<DiagnosticLog.Entry>): Finding? {
        val matches = entries.filter {
            it.tag.startsWith("update") && it.message.contains("vérification impossible")
        }
        // The quota case is reported on its own, with a better explanation.
        if (matches.isEmpty() || matches.all { it.message.contains("quota") }) return null
        return Finding(
            severity = Severity.WARNING,
            title = "Les vérifications de mise à jour n'aboutissent pas",
            evidence = matches.first().message,
            occurrences = matches.size,
            action = "GitHub n'est pas joignable depuis cet appareil. Si le chat " +
                "fonctionne par ailleurs, le tunnel route le réseau local mais " +
                "n'offre pas de sortie vers Internet.",
        )
    }

    private fun mcpServersUnreachable(entries: List<DiagnosticLog.Entry>): Finding? {
        val matches = entries.filter { it.tag.startsWith("mcp/") }
        val entry = matches.firstOrNull() ?: return null
        return Finding(
            severity = Severity.WARNING,
            title = "Un serveur MCP ne répond pas",
            evidence = "[${entry.tag}] ${entry.message}",
            occurrences = matches.size,
            action = "Le modèle reçoit simplement moins d'outils ; le chat continue de " +
                "fonctionner. Vérifie l'adresse du serveur dans les réglages.",
        )
    }

    private fun databaseNotEncrypted(entries: List<DiagnosticLog.Entry>): Finding? {
        val entry = entries.firstOrNull { it.tag == "db/open" || it.tag == "db/key" }
            ?: return null
        return Finding(
            severity = Severity.WARNING,
            title = "La base chiffrée n'a pas pu être ouverte",
            evidence = "[${entry.tag}] ${entry.message}",
            action = "L'application est repassée sur une base non chiffrée pour rester " +
                "utilisable. Les conversations sont donc lisibles par qui accède aux " +
                "fichiers de l'application. Une sauvegarde puis une réinstallation " +
                "rétablit le chiffrement.",
        )
    }

    /** Two IPv4 addresses sharing their first three octets. */
    internal fun sameSubnet24(a: String, b: String): Boolean =
        a.substringBeforeLast('.') == b.substringBeforeLast('.')
}
