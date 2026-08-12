package com.opencode.remote.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

// ─── Markdown Data Model ──────────────────────────────────────────────────

internal sealed class MdSegment {
    data class CodeBlock(val language: String, val code: String) : MdSegment()
    data class Paragraph(val spans: List<MdSpan>) : MdSegment()
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdSegment()
    data class Blockquote(val spans: List<MdSpan>) : MdSegment()
    data class ItemList(val items: List<ListItem>) : MdSegment()
    data class Table(
        val header: List<Cell>,
        val rows: List<List<Cell>>,
        val aligns: List<Align>,
    ) : MdSegment()
    object HorizontalRule : MdSegment()

    data class ListItem(val level: Int, val ordered: Boolean, val number: Int, val spans: List<MdSpan>)
    data class Cell(val spans: List<MdSpan>)
}

internal enum class Align { LEFT, CENTER, RIGHT }

internal sealed class MdSpan {
    data class Bold(val text: String) : MdSpan()
    data class Italic(val text: String) : MdSpan()
    data class Strikethrough(val text: String) : MdSpan()
    data class InlineCode(val text: String) : MdSpan()
    data class Link(val text: String, val url: String) : MdSpan()
    data class Plain(val text: String) : MdSpan()
}

// ─── Markdown Parsing ─────────────────────────────────────────────────────

/** Compiled once — compiling a Regex on every inline parse caused JIT churn during streaming. */
private val inlineSpanRegex = Regex(
    """(\*\*(.+?)\*\*|~~(.+?)~~|\[([^\]]+)\]\(([^)\s]+)\)|`([^`]+)`|\*(.+?)\*)"""
)

private val headingRegex = Regex("""^\s*(#{1,6})\s+(.*)$""")
private val hrRegex = Regex("""\s*(-{3,}|\*{3,}|_{3,})\s*""")
private val listItemRegex = Regex("""^\s*([-*+]|\d{1,9}\.)\s+(.*)$""")
private val delimiterCellRegex = Regex(""":?-+:?""")

/**
 * Line-oriented, streaming-safe block parser. Splits raw markdown into
 * block segments (paragraphs, fenced code, headings, lists, blockquotes,
 * horizontal rules and GFM tables) before inline span parsing.
 */
internal fun parseMarkdown(text: String): List<MdSegment> {
    val segments = mutableListOf<MdSegment>()
    val lines = text.lines()
    var i = 0

    var pendingParagraph = mutableListOf<String>()

    fun flushParagraph() {
        if (pendingParagraph.isNotEmpty()) {
            val joined = pendingParagraph.joinToString("\n")
            if (joined.isNotBlank()) {
                segments.add(MdSegment.Paragraph(parseInlineSpans(joined)))
            }
        }
        pendingParagraph = mutableListOf()
    }

    while (i < lines.size) {
        val line = lines[i]

        if (line.isBlank()) {
            flushParagraph()
            i++
            continue
        }

        // Fenced code block ```lang ... ```
        if (line.trimStart().startsWith("```")) {
            flushParagraph()
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            segments.add(MdSegment.CodeBlock(lang, codeLines.joinToString("\n")))
            if (i < lines.size) i++ // skip closing ```
            continue
        }

        // ATX heading: # .. ######
        val headingMatch = headingRegex.find(line)
        if (headingMatch != null) {
            flushParagraph()
            val level = headingMatch.groupValues[1].length
            segments.add(MdSegment.Heading(level, parseInlineSpans(headingMatch.groupValues[2])))
            i++
            continue
        }

        // Blockquote: consecutive '>' lines
        if (line.trimStart().startsWith(">")) {
            flushParagraph()
            val quoteLines = mutableListOf<String>()
            while (i < lines.size) {
                val q = lines[i].trimStart()
                if (!q.startsWith(">")) break
                quoteLines.add(q.removePrefix(">").trimStart())
                i++
            }
            segments.add(MdSegment.Blockquote(parseInlineSpans(quoteLines.joinToString("\n"))))
            continue
        }

        // Horizontal rule: --- *** ___
        if (hrRegex.matches(line)) {
            flushParagraph()
            segments.add(MdSegment.HorizontalRule)
            i++
            continue
        }

        // GFM table: header row directly followed by a delimiter row
        if (containsUnescapedPipe(line) && i + 1 < lines.size && isTableDelimiter(lines[i + 1])) {
            flushParagraph()
            val headerCells = tableCells(line)
            val aligns = tableCells(lines[i + 1]).map { alignOf(it) }
            i += 2
            val rows = mutableListOf<List<MdSegment.Cell>>()
            while (i < lines.size) {
                val rowLine = lines[i]
                if (rowLine.isBlank() || !containsUnescapedPipe(rowLine)) break
                val cells = tableCells(rowLine)
                rows.add(padCells(cells, aligns.size))
                i++
            }
            segments.add(
                MdSegment.Table(
                    header = padCells(headerCells, aligns.size),
                    rows = rows,
                    aligns = aligns,
                )
            )
            continue
        }

        // List item: - * + or 1. with indentation-based nesting level
        val listMatch = listItemRegex.matchEntire(line)
        if (listMatch != null) {
            flushParagraph()
            val items = mutableListOf<MdSegment.ListItem>()
            while (i < lines.size) {
                val m = listItemRegex.matchEntire(lines[i]) ?: break
                val indent = lines[i].indexOfFirst { it != ' ' }.let { if (it < 0) 0 else it }
                val marker = m.groupValues[1]
                val ordered = marker.endsWith(".")
                val number = marker.trimEnd('.').toIntOrNull() ?: 1
                items.add(
                    MdSegment.ListItem(
                        level = indent / 2,
                        ordered = ordered,
                        number = number,
                        spans = parseInlineSpans(m.groupValues[2]),
                    )
                )
                i++
            }
            segments.add(MdSegment.ItemList(items))
            continue
        }

        // Ordinary paragraph line — accumulate until a blank line or a
        // line that starts a dedicated block (matches the loop above).
        pendingParagraph.add(line)
        i++
    }

    flushParagraph()
    return segments
}

internal fun parseInlineSpans(text: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    var lastEnd = 0

    for (match in inlineSpanRegex.findAll(text)) {
        if (match.range.first > lastEnd) {
            spans.add(MdSpan.Plain(text.substring(lastEnd, match.range.first)))
        }
        val g = match.groupValues
        when {
            g[2].isNotEmpty() -> spans.add(MdSpan.Bold(g[2]))
            g[3].isNotEmpty() -> spans.add(MdSpan.Strikethrough(g[3]))
            g[4].isNotEmpty() -> spans.add(MdSpan.Link(g[4], g[5]))
            g[6].isNotEmpty() -> spans.add(MdSpan.InlineCode(g[6]))
            g[7].isNotEmpty() -> spans.add(MdSpan.Italic(g[7]))
        }
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        spans.add(MdSpan.Plain(text.substring(lastEnd)))
    }
    if (spans.isEmpty()) {
        spans.add(MdSpan.Plain(text))
    }
    return spans
}

// ─── Table cell helpers ───────────────────────────────────────────────────

/** Split a row on unescaped '|' characters (handles '\|' escapes). */
private fun splitUnescaped(line: String): List<String> {
    val result = mutableListOf<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '\\' && i + 1 < line.length && line[i + 1] == '|' -> {
                sb.append('|')
                i += 2
            }
            c == '|' -> {
                result.add(sb.toString())
                sb.clear()
                i++
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    result.add(sb.toString())
    return result
}

private fun containsUnescapedPipe(line: String): Boolean = splitUnescaped(line).size > 1

/** Parse a table row into trimmed cells, dropping the optional leading/trailing pipes. */
private fun tableCells(line: String): List<String> {
    var cells = splitUnescaped(line.trim())
    if (cells.size >= 2 && cells.first().isBlank()) cells = cells.drop(1)
    if (cells.size >= 2 && cells.last().isBlank()) cells = cells.dropLast(1)
    return cells.map { it.trim() }
}

/** True when every (trimmed) cell matches the GFM delimiter pattern (`:---`, `---:`, `:---:`, `---`). */
private fun isTableDelimiter(line: String): Boolean {
    val cells = tableCells(line)
    return cells.isNotEmpty() && cells.all { it.isNotBlank() && delimiterCellRegex.matches(it) }
}

private fun alignOf(cell: String): Align {
    val t = cell.trim()
    return when {
        t.startsWith(":") && t.endsWith(":") -> Align.CENTER
        t.endsWith(":") -> Align.RIGHT
        else -> Align.LEFT
    }
}

/** Pad/truncate a row's cells to the table column count, parsing inline spans. */
private fun padCells(cells: List<String>, columnCount: Int): List<MdSegment.Cell> {
    return (0 until columnCount).map { idx ->
        MdSegment.Cell(parseInlineSpans(cells.getOrElse(idx) { "" }))
    }
}

// ─── Markdown Text Composable ─────────────────────────────────────────────

/** Build the annotated string for a list of inline spans (shared by all blocks). */
@Composable
private fun spanAnnotated(spans: List<MdSpan>, codeBackground: Color): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        for (span in spans) {
            when (span) {
                is MdSpan.Plain -> withStyle(SpanStyle()) { append(span.text) }
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                is MdSpan.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(span.text)
                }
                is MdSpan.InlineCode -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    )
                ) {
                    append(" ${span.text} ")
                }
                is MdSpan.Link -> {
                    val start = length
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(span.text)
                    }
                    addStringAnnotation(URL_ANNOTATION, span.url, start, length)
                }
            }
        }
    }
}

/**
 * Renders an annotated string; when it contains links they become
 * clickable (opened in the system browser via [UriHandler]), otherwise
 * the text stays selectable.
 */
@Composable
private fun RichText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val hasLinks = remember(text) { text.getStringAnnotations(URL_ANNOTATION, 0, text.length).isNotEmpty() }
    if (hasLinks) {
        ClickableText(
            text = text,
            style = style,
            modifier = modifier,
            onClick = { offset ->
                text.getStringAnnotations(URL_ANNOTATION, offset, offset)
                    .firstOrNull()?.let { uriHandler.openUri(it.item) }
            },
        )
    } else {
        SelectionContainer(modifier = modifier) {
            Text(text = text, style = style, color = color)
        }
    }
}

private const val URL_ANNOTATION = "md-link-url"
private val TABLE_CELL_H_PADDING = 6.dp

@Composable
private fun headingTextStyle(level: Int): TextStyle {
    val typography = MaterialTheme.typography
    val base = when (level) {
        1 -> typography.headlineSmall
        2 -> typography.titleLarge
        3 -> typography.titleMedium
        else -> typography.titleSmall
    }
    return base.copy(fontWeight = FontWeight.Bold)
}

@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val segments = remember(text) { parseMarkdown(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is MdSegment.CodeBlock -> {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = codeBackground,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            if (segment.language.isNotEmpty()) {
                                Text(
                                    text = segment.language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                            }
                            Box(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = segment.code,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = color,
                                    )
                                }
                            }
                        }
                    }
                }

                is MdSegment.Paragraph -> {
                    val annotated = spanAnnotated(segment.spans, codeBackground)
                    RichText(
                        text = annotated,
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                    )
                }

                is MdSegment.Heading -> {
                    val annotated = spanAnnotated(segment.spans, codeBackground)
                    RichText(
                        text = annotated,
                        style = headingTextStyle(segment.level),
                        color = color,
                    )
                }

                is MdSegment.Blockquote -> {
                    val annotated = spanAnnotated(segment.spans, codeBackground)
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                RichText(
                                    text = annotated,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                is MdSegment.HorizontalRule -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }

                is MdSegment.ItemList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        segment.items.forEach { item ->
                            Row(modifier = Modifier.padding(start = (item.level * 16).dp)) {
                                Box(contentAlignment = Alignment.TopStart) {
                                    Text(
                                        text = if (item.ordered) "${item.number}." else "\u2022",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = color,
                                        modifier = Modifier.width(24.dp),
                                    )
                                }
                                val annotated = spanAnnotated(item.spans, codeBackground)
                                RichText(
                                    text = annotated,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = color,
                                )
                            }
                        }
                    }
                }

                is MdSegment.Table -> {
                    val measurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    val bodyStyle = MaterialTheme.typography.bodyMedium
                    // Align columns vertically by rendering every cell at the
                    // widest width its column needs (measured over all rows).
                    val colWidths = (0 until segment.aligns.size).map { col ->
                        val maxPx = (listOf(segment.header) + segment.rows).maxOf { row ->
                            val annotated = spanAnnotated(row[col].spans, codeBackground)
                            measurer.measure(annotated, bodyStyle).size.width
                        }
                        with(density) { maxPx.toDp() } + TABLE_CELL_H_PADDING * 2 + 8.dp
                    }
                    val tableWidth = colWidths.fold(0.dp) { acc, w -> acc + w }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = codeBackground,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp),
                        ) {
                            // Header row
                            Row {
                                segment.header.forEachIndexed { col, cell ->
                                    val annotated = spanAnnotated(cell.spans, codeBackground)
                                    Text(
                                        text = annotated,
                                        style = bodyStyle.copy(fontWeight = FontWeight.Bold),
                                        color = color,
                                        textAlign = textAlignOf(segment.aligns.getOrElse(col) { Align.LEFT }),
                                        modifier = Modifier
                                            .width(colWidths[col])
                                            .padding(horizontal = TABLE_CELL_H_PADDING, vertical = 4.dp),
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .width(tableWidth)
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            )
                            // Body rows
                            segment.rows.forEachIndexed { rowIdx, row ->
                                Row {
                                    row.forEachIndexed { col, cell ->
                                        val annotated = spanAnnotated(cell.spans, codeBackground)
                                        Text(
                                            text = annotated,
                                            style = bodyStyle,
                                            color = color,
                                            textAlign = textAlignOf(segment.aligns.getOrElse(col) { Align.LEFT }),
                                            modifier = Modifier
                                                .width(colWidths[col])
                                                .padding(horizontal = TABLE_CELL_H_PADDING, vertical = 4.dp),
                                        )
                                    }
                                }
                                if (rowIdx != segment.rows.lastIndex) {
                                    Box(
                                        modifier = Modifier
                                            .width(tableWidth)
                                            .height(1.dp)
                                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun textAlignOf(align: Align): TextAlign = when (align) {
    Align.LEFT -> TextAlign.Start
    Align.CENTER -> TextAlign.Center
    Align.RIGHT -> TextAlign.End
}
