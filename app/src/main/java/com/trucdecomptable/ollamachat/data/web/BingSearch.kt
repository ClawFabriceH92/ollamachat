package com.trucdecomptable.ollamachat.data.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Recherche web sans clé API, en interrogeant la page HTML de Bing.
 *
 * Google n'est pas une option : ses pages de résultats sont rendues en
 * JavaScript et une requête HTTP nue reçoit une coquille vide ou un mur
 * anti-robot (vérifié le 10/11/2026 depuis ARGOS : 200, 93 Ko, zéro résultat
 * extractible). Qwant, Mojeek et Ecosia répondent 403, DuckDuckGo sert une page
 * anti-bot, Startpage un défi JavaScript, et les instances SearXNG publiques
 * sont fermées ou saturées. Bing, lui, rend ses résultats dans le HTML et les
 * laisse lire : c'est le seul moteur utilisable sans clé.
 *
 * L'extraction est une fonction pure ([extraire]) pour être testable sans
 * réseau ; la requête HTTP ne fait que la nourrir.
 */
object BingSearch {

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Bloc de résultat Bing : <li class="b_algo"> … </li>. */
    private val BLOC = Regex("""<li class="b_algo".*?</li>""", RegexOption.DOT_MATCHES_ALL)
    private val TITRE = Regex("""<h2[^>]*>(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL)
    private val LIEN = Regex("""href="(https?://[^"]+)"""")
    private val EXTRAIT = Regex("""<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)

    /** Lit les résultats d'une page Bing. Renvoie une liste vide si rien n'est reconnu. */
    fun extraire(html: String): List<WebSearchClient.SearchItem> =
        BLOC.findAll(html).mapNotNull { bloc ->
            val texte = bloc.value
            val titre = TITRE.find(texte)?.groupValues?.get(1)?.let(::nettoyer)
                ?: return@mapNotNull null
            val url = LIEN.find(texte)?.groupValues?.get(1) ?: return@mapNotNull null
            // Bing préfixe parfois ses liens par un redirecteur : on garde l'URL
            // réelle quand elle est présente, sinon le lien tel quel.
            val reel = Regex("""[?&]u=a1([^&"]+)""").find(url)?.groupValues?.get(1)
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: url
            val extrait = EXTRAIT.find(texte)?.groupValues?.get(1)?.let(::nettoyer).orEmpty()
            WebSearchClient.SearchItem(title = titre, url = reel, snippet = extrait)
        }.toList().filter { it.title.isNotBlank() && it.url.startsWith("http") }
            .distinctBy { it.url }

    private fun nettoyer(brut: String): String =
        brut.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#233;", "é").replace("&#232;", "è").replace("&#224;", "à")
            .replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    suspend fun rechercher(query: String): List<WebSearchClient.SearchItem> =
        withContext(Dispatchers.IO) {
            val url = "https://www.bing.com/search?q=" +
                java.net.URLEncoder.encode(query, "UTF-8") + "&setlang=fr&count=10"
            val requete = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept-Language", "fr-FR,fr;q=0.9")
                .get()
                .build()
            http.newCall(requete).execute().use { rep ->
                if (!rep.isSuccessful) throw IllegalStateException("Bing HTTP ${rep.code}")
                extraire(rep.body?.string() ?: "")
            }
        }
}
