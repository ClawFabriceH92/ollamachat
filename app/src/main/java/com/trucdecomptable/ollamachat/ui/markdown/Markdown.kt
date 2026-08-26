package com.trucdecomptable.ollamachat.ui.markdown

/**
 * A deliberately small markdown subset — the part language models actually
 * emit: headings, emphasis, inline and fenced code, lists, quotes, tables,
 * links and rules.
 *
 * Pure Kotlin on purpose, so the parsing is covered by plain JVM unit tests
 * and the Compose layer stays a straight rendering of these blocks.
 */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Bullets(val items: List<MdListItem>) : MdBlock
    data class Numbers(val items: List<MdListItem>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data class Table(val header: List<List<MdSpan>>, val rows: List<List<List<MdSpan>>>) : MdBlock
    data object Divider : MdBlock
}

data class MdListItem(val spans: List<MdSpan>, val indent: Int, val marker: String)

/** A run of text sharing the same styling. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

object Markdown {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBER = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val DIVIDER = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(input: String): List<MdBlock> {
        val lines = input.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val blocks = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            blocks.add(MdBlock.Paragraph(parseInline(paragraph.joinToString("\n"))))
            paragraph.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // Fenced code — kept verbatim, including blank lines.
            val fence = fenceOf(line)
            if (fence != null) {
                flushParagraph()
                val language = line.trim().removePrefix(fence).trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && fenceOf(lines[i]) == null) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++ // closing fence
                blocks.add(MdBlock.Code(language, code.toString().trimEnd('\n')))
                continue
            }

            if (line.isBlank()) {
                flushParagraph()
                i++
                continue
            }

            if (DIVIDER.matches(line)) {
                flushParagraph()
                blocks.add(MdBlock.Divider)
                i++
                continue
            }

            val heading = HEADING.find(line)
            if (heading != null) {
                flushParagraph()
                blocks.add(
                    MdBlock.Heading(
                        level = heading.groupValues[1].length,
                        spans = parseInline(heading.groupValues[2].trim()),
                    )
                )
                i++
                continue
            }

            // Table: a header row followed by a |---|---| separator.
            if (line.contains('|') && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1])) {
                flushParagraph()
                val header = splitRow(line)
                val rows = mutableListOf<List<List<MdSpan>>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows.add(splitRow(lines[i]))
                    i++
                }
                blocks.add(MdBlock.Table(header, rows))
                continue
            }

            if (line.trimStart().startsWith(">")) {
                flushParagraph()
                val quoted = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoted.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                blocks.add(MdBlock.Quote(parseInline(quoted.joinToString("\n"))))
                continue
            }

            if (BULLET.matches(line)) {
                flushParagraph()
                val items = mutableListOf<MdListItem>()
                while (i < lines.size) {
                    val m = BULLET.find(lines[i]) ?: break
                    items.add(
                        MdListItem(
                            spans = parseInline(m.groupValues[2]),
                            indent = m.groupValues[1].length / 2,
                            marker = "•",
                        )
                    )
                    i++
                }
                blocks.add(MdBlock.Bullets(items))
                continue
            }

            if (NUMBER.matches(line)) {
                flushParagraph()
                val items = mutableListOf<MdListItem>()
                while (i < lines.size) {
                    val m = NUMBER.find(lines[i]) ?: break
                    items.add(
                        MdListItem(
                            spans = parseInline(m.groupValues[3]),
                            indent = m.groupValues[1].length / 2,
                            marker = m.groupValues[2] + ".",
                        )
                    )
                    i++
                }
                blocks.add(MdBlock.Numbers(items))
                continue
            }

            paragraph.add(line)
            i++
        }
        flushParagraph()
        return blocks
    }

    private fun fenceOf(line: String): String? {
        val trimmed = line.trimStart()
        return when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
    }

    private fun splitRow(line: String): List<List<MdSpan>> =
        line.trim().removePrefix("|").removeSuffix("|")
            .split("|")
            .map { parseInline(it.trim()) }

    /**
     * Inline styling. Scans once, left to right; an unmatched marker stays
     * literal text rather than swallowing the rest of the line.
     */
    fun parseInline(input: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        val plain = StringBuilder()
        var i = 0

        fun flush() {
            if (plain.isNotEmpty()) {
                spans.add(MdSpan(plain.toString()))
                plain.clear()
            }
        }

        while (i < input.length) {
            val rest = input.substring(i)

            // Inline code wins over every other marker.
            if (rest.startsWith("`")) {
                val end = rest.indexOf('`', startIndex = 1)
                if (end > 0) {
                    flush()
                    spans.add(MdSpan(rest.substring(1, end), code = true))
                    i += end + 1
                    continue
                }
            }

            if (rest.startsWith("![")) {
                // Images render as their alt text; the bytes are not fetched.
                val close = rest.indexOf(']')
                val open = if (close > 0) rest.indexOf('(', close) else -1
                val paren = if (open == close + 1) rest.indexOf(')', open) else -1
                if (paren > 0) {
                    flush()
                    spans.add(MdSpan(rest.substring(2, close), italic = true))
                    i += paren + 1
                    continue
                }
            }

            if (rest.startsWith("[")) {
                val close = rest.indexOf(']')
                val open = if (close > 0) rest.indexOf('(', close) else -1
                val paren = if (open == close + 1) rest.indexOf(')', open) else -1
                if (paren > 0) {
                    flush()
                    spans.add(
                        MdSpan(
                            text = rest.substring(1, close),
                            link = rest.substring(open + 1, paren).trim(),
                        )
                    )
                    i += paren + 1
                    continue
                }
            }

            val emphasis = matchDelimited(rest, "**") ?: matchDelimited(rest, "__")
            if (emphasis != null) {
                flush()
                spans.addAll(parseInline(emphasis.first).map { it.copy(bold = true) })
                i += emphasis.second
                continue
            }

            val strike = matchDelimited(rest, "~~")
            if (strike != null) {
                flush()
                spans.addAll(parseInline(strike.first).map { it.copy(strike = true) })
                i += strike.second
                continue
            }

            val italic = matchDelimited(rest, "*") ?: matchDelimited(rest, "_")
            if (italic != null) {
                flush()
                spans.addAll(parseInline(italic.first).map { it.copy(italic = true) })
                i += italic.second
                continue
            }

            if (rest.startsWith("http://") || rest.startsWith("https://")) {
                val end = rest.indexOfFirst { it.isWhitespace() }.let { if (it < 0) rest.length else it }
                val url = rest.substring(0, end).trimEnd('.', ',', ')', ';', ':')
                if (url.length > 8) {
                    flush()
                    spans.add(MdSpan(url, link = url))
                    i += url.length
                    continue
                }
            }

            plain.append(input[i])
            i++
        }
        flush()
        return spans.ifEmpty { listOf(MdSpan("")) }
    }

    /**
     * Matches `<delim>content<delim>` at the start of [text].
     * Returns the content and how many characters were consumed.
     */
    private fun matchDelimited(text: String, delim: String): Pair<String, Int>? {
        if (!text.startsWith(delim)) return null
        // "** bold" is not emphasis; markdown needs a non-space after the opener.
        val contentStart = delim.length
        if (contentStart >= text.length || text[contentStart].isWhitespace()) return null
        val end = text.indexOf(delim, startIndex = contentStart)
        if (end <= contentStart) return null
        val content = text.substring(contentStart, end)
        if (content.isBlank()) return null
        return content to (end + delim.length)
    }
}
