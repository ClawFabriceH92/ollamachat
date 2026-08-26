package com.trucdecomptable.ollamachat.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trucdecomptable.ollamachat.R

/**
 * Renders the markdown models emit. Falls back to plain text for anything the
 * parser does not know, so nothing is ever swallowed.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block -> MarkdownBlock(block, style, color) }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, style: TextStyle, color: Color) {
    when (block) {
        is MdBlock.Paragraph -> InlineText(block.spans, style, color)

        is MdBlock.Heading -> Text(
            text = annotate(block.spans, color),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            color = color,
            fontWeight = FontWeight.Bold,
        )

        is MdBlock.Code -> CodeBlock(block)

        is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.items.forEach { ListRow(it, style, color) }
        }

        is MdBlock.Numbers -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.items.forEach { ListRow(it, style, color) }
        }

        is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(8.dp))
            InlineText(block.spans, style, MaterialTheme.colorScheme.onSurfaceVariant)
        }

        is MdBlock.Table -> MarkdownTable(block, style, color)

        MdBlock.Divider -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun ListRow(item: MdListItem, style: TextStyle, color: Color) {
    Row(modifier = Modifier.padding(start = (item.indent * 14).dp)) {
        Text(
            text = item.marker,
            style = style,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(if (item.marker == "•") 16.dp else 24.dp),
        )
        InlineText(item.spans, style, color)
    }
}

@Composable
private fun InlineText(spans: List<MdSpan>, style: TextStyle, color: Color) {
    // Links are carried by the AnnotatedString itself, so a plain Text handles
    // the taps — ClickableText is deprecated and swallowed accessibility.
    Text(text = annotate(spans, color), style = style, color = color)
}

@Composable
private fun annotate(spans: List<MdSpan>, color: Color): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return buildAnnotatedString {
        spans.forEach { span ->
            val spanStyle = SpanStyle(
                color = if (span.link != null) linkColor else color,
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) codeBackground else Color.Unspecified,
                textDecoration = when {
                    span.strike -> TextDecoration.LineThrough
                    span.link != null -> TextDecoration.Underline
                    else -> null
                },
            )
            if (span.link != null) {
                withLink(
                    LinkAnnotation.Url(
                        url = span.link,
                        styles = TextLinkStyles(style = spanStyle),
                    )
                ) { append(span.text) }
            } else {
                withStyle(spanStyle) { append(span.text) }
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = block.language.ifBlank { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(block.code)) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.action_copy_code),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // Code must never force the bubble to grow: it scrolls on its own.
        Text(
            text = block.code,
            style = LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(end = 8.dp),
        )
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, style: TextStyle, color: Color) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
    ) {
        Row {
            table.header.forEach { cell ->
                Text(
                    text = annotate(cell, color),
                    style = style,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(140.dp).padding(4.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        table.rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Text(
                        text = annotate(cell, color),
                        style = style,
                        color = color,
                        modifier = Modifier.width(140.dp).padding(4.dp),
                    )
                }
            }
        }
    }
}
