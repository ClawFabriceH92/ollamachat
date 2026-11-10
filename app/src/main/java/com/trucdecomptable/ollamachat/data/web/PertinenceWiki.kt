package com.trucdecomptable.ollamachat.data.web

import java.text.Normalizer

/**
 * Filtre de pertinence des résultats Wikipédia.
 *
 * Wikipédia ne répond pas « rien » quand il ne connaît pas un nom : son moteur
 * de recherche rend les pages qui ressemblent le plus à la requête. Chercher
 * « Fabrice Heuvrard » renvoie ainsi « Xylophone » (vérifié le 10/11/2026), et
 * le modèle d'IA, qui reçoit ces résultats comme un fait, les présente comme la
 * réponse. Ce n'est pas le modèle qui invente : c'est la recherche qui ment.
 *
 * On ne garde donc qu'une page partageant au moins un mot significatif avec la
 * requête. Liste vide = aucun résultat pertinent, et l'app le dira franchement.
 */
object PertinenceWiki {

    /** Mots trop courants pour prouver qu'une page parle de la requête. */
    private val MOTS_VIDES = setOf(
        "dans", "avec", "pour", "mais", "donc", "alors", "cette", "celui", "celle",
        "elle", "ils", "nous", "vous", "leur", "plus", "moins", "sans", "sous",
        "entre", "quoi", "quel", "quels", "quelle", "quelles", "comment", "pourquoi",
        "quand", "sont", "etre", "avoir", "fait", "tout", "tous", "toute", "toutes",
        "info", "infos", "information", "informations", "recherche", "rechercher",
        "cherche", "chercher", "trouve", "trouver", "donne", "donner", "peux",
        "peut", "veux", "veut", "sais", "savoir", "web", "internet", "google",
        "wikipedia", "page", "site", "lien", "sujet",
    )

    /** Minuscules, sans accents, sans ponctuation. */
    fun normaliser(texte: String): String {
        val sansAccents = Normalizer.normalize(texte, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return sansAccents.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    /** Mots significatifs d'une requête : 4 lettres et plus, hors mots vides. */
    fun motsCles(requete: String): List<String> =
        normaliser(requete)
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 4 && it !in MOTS_VIDES }
            .distinct()

    /** Vrai si la page partage au moins un mot-clé avec la requête. */
    fun estPertinent(titre: String, extrait: String, motsCles: List<String>): Boolean {
        if (motsCles.isEmpty()) return false
        val contenu = normaliser("$titre $extrait")
        // Un mot de 4 lettres et plus suffit ; le titre pèse plus lourd, mais on
        // cherche dans les deux pour ne pas jeter une page légitime dont seul
        // l'extrait nomme le sujet.
        return motsCles.any { mot -> Regex("""\b${Regex.escape(mot)}""").containsMatchIn(contenu) }
    }

    /** Ne garde que les résultats qui parlent vraiment de la requête. */
    fun pertinents(items: List<WebSearchClient.SearchItem>, requete: String): List<WebSearchClient.SearchItem> {
        val mots = motsCles(requete)
        return items.filter { estPertinent(it.title, it.snippet, mots) }
    }
}
