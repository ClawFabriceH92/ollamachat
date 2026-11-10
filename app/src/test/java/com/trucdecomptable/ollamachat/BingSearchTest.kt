package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.web.BingSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extraction des résultats Bing. Google est inutilisable sans JavaScript, donc
 * c'est Bing qui sert de recherche par défaut sans clé API : si l'extraction
 * casse, l'app retombe sur Wikipédia et ressort des « Xylophone ».
 */
class BingSearchTest {

    private val page = """
        <html><body><ol id="b_results">
        <li class="b_algo"><h2><a href="https://entreprendre.service-public.gouv.fr/vosdroits/F23208">Tout savoir sur la facturation</a></h2>
        <p class="b_lineclamp2">La facturation &amp; ses obligations pour les entreprises.</p></li>
        <li class="b_algo"><h2><a href="https://www.impots.gouv.fr/sites/default/files/media/1_metier/2_profes">Facturation &#233;lectronique : guide</a></h2>
        <p>Guide pratique de d&#233;marrage au 1er septembre 2026.</p></li>
        <li class="b_ans">autre chose, pas un résultat organique</li>
        </ol></body></html>
    """.trimIndent()

    @Test
    fun `les resultats organiques sont extraits dans l ordre`() {
        val items = BingSearch.extraire(page)
        assertEquals(2, items.size)
        assertEquals("Tout savoir sur la facturation", items[0].title)
        assertEquals("https://entreprendre.service-public.gouv.fr/vosdroits/F23208", items[0].url)
        assertTrue(items[0].snippet.contains("obligations"))
    }

    @Test
    fun `les entites html sont decodees`() {
        val items = BingSearch.extraire(page)
        assertEquals("Facturation électronique : guide", items[1].title)
        assertTrue(items[1].snippet.contains("démarrage"))
    }

    @Test
    fun `une page sans resultat ne renvoie rien`() {
        assertTrue(BingSearch.extraire("<html><body>Aucun résultat</body></html>").isEmpty())
    }

    @Test
    fun `un lien en double n est garde qu une fois`() {
        val double = page.replace("b_ans", "b_algo")
        val items = BingSearch.extraire(double)
        assertEquals(items.map { it.url }.distinct().size, items.size)
    }
}
