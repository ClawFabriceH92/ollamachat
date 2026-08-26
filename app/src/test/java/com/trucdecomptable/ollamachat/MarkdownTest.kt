package com.trucdecomptable.ollamachat

import com.trucdecomptable.ollamachat.ui.markdown.MdBlock
import com.trucdecomptable.ollamachat.ui.markdown.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `plain text is a single paragraph`() {
        val blocks = Markdown.parse("Bonjour tout le monde")
        assertEquals(1, blocks.size)
        val paragraph = blocks.first() as MdBlock.Paragraph
        assertEquals("Bonjour tout le monde", paragraph.spans.joinToString("") { it.text })
    }

    @Test
    fun `headings keep their level`() {
        val blocks = Markdown.parse("# Titre\n## Sous-titre")
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
    }

    @Test
    fun `fenced code keeps its language and body verbatim`() {
        val blocks = Markdown.parse("Avant\n\n```kotlin\nval x = 1\n\nval y = 2\n```\n\nAprès")
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\n\nval y = 2", code.code)
        assertEquals(2, blocks.filterIsInstance<MdBlock.Paragraph>().size)
    }

    @Test
    fun `an unclosed fence does not swallow the rest silently`() {
        val blocks = Markdown.parse("```\nval x = 1")
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("val x = 1", code.code)
    }

    @Test
    fun `bullets and numbers become lists`() {
        val bullets = Markdown.parse("- un\n- deux").filterIsInstance<MdBlock.Bullets>().single()
        assertEquals(2, bullets.items.size)
        val numbers = Markdown.parse("1. un\n2. deux").filterIsInstance<MdBlock.Numbers>().single()
        assertEquals(listOf("1.", "2."), numbers.items.map { it.marker })
    }

    @Test
    fun `tables need a separator row`() {
        val table = Markdown.parse("| a | b |\n|---|---|\n| 1 | 2 |")
            .filterIsInstance<MdBlock.Table>().single()
        assertEquals(2, table.header.size)
        assertEquals(1, table.rows.size)
        assertEquals("1", table.rows[0][0].joinToString("") { it.text })

        // Without the separator it is just text, not a broken table.
        assertTrue(Markdown.parse("| a | b |").none { it is MdBlock.Table })
    }

    @Test
    fun `inline emphasis and code are recognised`() {
        val spans = Markdown.parseInline("du **gras**, de l'*italique* et du `code`")
        assertTrue(spans.any { it.bold && it.text == "gras" })
        assertTrue(spans.any { it.italic && it.text == "italique" })
        assertTrue(spans.any { it.code && it.text == "code" })
    }

    @Test
    fun `links carry their target`() {
        val spans = Markdown.parseInline("voir [le site](https://example.com) pour la suite")
        val link = spans.single { it.link != null }
        assertEquals("le site", link.text)
        assertEquals("https://example.com", link.link)
    }

    @Test
    fun `bare urls become links`() {
        val spans = Markdown.parseInline("source : https://example.com/page.")
        assertEquals("https://example.com/page", spans.single { it.link != null }.link)
    }

    @Test
    fun `an unmatched marker stays literal`() {
        val spans = Markdown.parseInline("2 * 3 = 6 et un `backtick orphelin")
        assertEquals("2 * 3 = 6 et un `backtick orphelin", spans.joinToString("") { it.text })
    }

    @Test
    fun `a divider is not confused with a list item`() {
        assertTrue(Markdown.parse("---").single() is MdBlock.Divider)
        assertTrue(Markdown.parse("- item").single() is MdBlock.Bullets)
    }

    @Test
    fun `nothing is dropped on a realistic answer`() {
        val answer = """
            # Résultat

            Voici **le** résumé :

            - point un
            - point deux

            ```python
            print("ok")
            ```

            > citation

            | col | val |
            |---|---|
            | a | 1 |
        """.trimIndent()
        val blocks = Markdown.parse(answer)
        assertTrue(blocks.any { it is MdBlock.Heading })
        assertTrue(blocks.any { it is MdBlock.Paragraph })
        assertTrue(blocks.any { it is MdBlock.Bullets })
        assertTrue(blocks.any { it is MdBlock.Code })
        assertTrue(blocks.any { it is MdBlock.Quote })
        assertTrue(blocks.any { it is MdBlock.Table })
    }
}
