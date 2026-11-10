package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.data.web.PertinenceWiki
import com.trucdecomptable.ollamachat.data.web.WebSearchClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le filtre qui empêche Wikipédia de faire passer « Xylophone » pour la réponse à
 * « qui est Fabrice Heuvrard » (cas réel du 10/11/2026).
 */
class PertinenceWikiTest {

    private fun item(titre: String, extrait: String = "") =
        WebSearchClient.SearchItem(title = titre, url = "https://fr.wikipedia.org/wiki/x", snippet = extrait)

    @Test
    fun `un nom absent de wikipedia ne garde pas une page hors sujet`() {
        val resultats = listOf(
            item("Xylophone", "suivant la méthode des éléments finis. « Xylophones », sur xylophones-resta"),
        )
        assertTrue(PertinenceWiki.pertinents(resultats, "Fabrice Heuvrard").isEmpty())
    }

    @Test
    fun `une page qui nomme le sujet est gardée`() {
        val resultats = listOf(
            item("Fabrice Heuvrard", "expert-comptable et commissaire aux comptes"),
            item("Xylophone", "instrument de musique"),
        )
        val gardes = PertinenceWiki.pertinents(resultats, "Fabrice Heuvrard")
        assertEquals(listOf("Fabrice Heuvrard"), gardes.map { it.title })
        assertFalse(gardes.any { it.title == "Xylophone" })
    }

    @Test
    fun `les accents et la casse n empechent pas la correspondance`() {
        val resultats = listOf(item("Facturation électronique", "obligation à partir de septembre 2026"))
        assertEquals(1, PertinenceWiki.pertinents(resultats, "facturation electronique 2026").size)
    }

    @Test
    fun `le mot du sujet peut n apparaitre que dans l extrait`() {
        val resultats = listOf(item("Commissaire aux comptes", "le rapport de Fabrice Heuvrard"))
        assertEquals(1, PertinenceWiki.pertinents(resultats, "Heuvrard").size)
    }

    @Test
    fun `une requete faite de mots vides ne retient rien`() {
        assertTrue(PertinenceWiki.motsCles("cherche sur internet pour moi").isEmpty())
        val resultats = listOf(item("Xylophone"), item("Internet"))
        assertTrue(PertinenceWiki.pertinents(resultats, "cherche sur internet").isEmpty())
    }
}
