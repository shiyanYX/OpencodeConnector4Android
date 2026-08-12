package com.opencode.remote.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    // ── Helpers ──────────────────────────────────────────────────────

    private fun cell(vararg spans: MdSpan) = MdSegment.Cell(spans.toList())
    private fun plain(text: String) = MdSpan.Plain(text)
    private fun para(vararg spans: MdSpan) = MdSegment.Paragraph(spans.toList())

    // ── Tables ───────────────────────────────────────────────────────

    @Test
    fun `basic table parses header and rows`() {
        val input = """
            | A | B |
            |---|---|
            | 1 | 2 |
        """.trimIndent()

        val result = parseMarkdown(input)

        assertEquals(
            listOf(
                MdSegment.Table(
                    header = listOf(cell(plain("A")), cell(plain("B"))),
                    rows = listOf(listOf(cell(plain("1")), cell(plain("2")))),
                    aligns = listOf(Align.LEFT, Align.LEFT),
                )
            ),
            result,
        )
    }

    @Test
    fun `table without outer pipes parses`() {
        val input = "A | B\n--- | ---\n1 | 2"

        val result = parseMarkdown(input)

        assertEquals(
            listOf(
                MdSegment.Table(
                    header = listOf(cell(plain("A")), cell(plain("B"))),
                    rows = listOf(listOf(cell(plain("1")), cell(plain("2")))),
                    aligns = listOf(Align.LEFT, Align.LEFT),
                )
            ),
            result,
        )
    }

    @Test
    fun `table alignment from delimiter colons`() {
        val input = """
            | L | C | R |
            |:--|:-:|--:|
            | a | b | c |
        """.trimIndent()

        val result = parseMarkdown(input)

        assertEquals(
            listOf(
                MdSegment.Table(
                    header = listOf(cell(plain("L")), cell(plain("C")), cell(plain("R"))),
                    rows = listOf(listOf(cell(plain("a")), cell(plain("b")), cell(plain("c")))),
                    aligns = listOf(Align.LEFT, Align.CENTER, Align.RIGHT),
                )
            ),
            result,
        )
    }

    @Test
    fun `escaped pipe inside cell keeps the pipe`() {
        val input = """
            | A | B |
            |---|---|
            | a \| b | c |
        """.trimIndent()

        val result = parseMarkdown(input)

        val table = result.first() as MdSegment.Table
        assertEquals("a | b", ((table.rows[0][0].spans.single()) as MdSpan.Plain).text)
    }

    @Test
    fun `missing delimiter row falls back to paragraph`() {
        val input = "| A | B |\n| nope |"

        val result = parseMarkdown(input)

        assertEquals(
            listOf(para(plain("| A | B |\n| nope |"))),
            result,
        )
    }

    @Test
    fun `truncated table during streaming does not crash`() {
        val input = """
            | A | B |
            |---|---|
            | 1 |
        """.trimIndent()

        val result = parseMarkdown(input)

        val table = result.first() as MdSegment.Table
        assertEquals(2, table.rows[0].size)
        assertEquals("1", ((table.rows[0][0].spans.single()) as MdSpan.Plain).text)
        assertEquals("", ((table.rows[0][1].spans.single()) as MdSpan.Plain).text)
    }

    // ── Headings ─────────────────────────────────────────────────────

    @Test
    fun `heading levels parse`() {
        val result = parseMarkdown("# One\n\n## Two\n\n### Three")

        assertEquals(
            listOf(
                MdSegment.Heading(1, listOf(plain("One"))),
                MdSegment.Heading(2, listOf(plain("Two"))),
                MdSegment.Heading(3, listOf(plain("Three"))),
            ),
            result,
        )
    }

    @Test
    fun `heading with inline spans`() {
        val result = parseMarkdown("# **Bold** title")

        assertEquals(listOf(MdSegment.Heading(1, listOf(MdSpan.Bold("Bold"), plain(" title")))), result)
    }

    @Test
    fun `hash inside paragraph is not a heading`() {
        val result = parseMarkdown("not # a heading")

        assertEquals(listOf(para(plain("not # a heading"))), result)
    }

    // ── Horizontal rules ─────────────────────────────────────────────

    @Test
    fun `horizontal rules parse for dash star underscore`() {
        val result = parseMarkdown("---\n\n***\n\n___")

        assertEquals(
            listOf(MdSegment.HorizontalRule, MdSegment.HorizontalRule, MdSegment.HorizontalRule),
            result,
        )
    }

    @Test
    fun `dashes without space to the right are not a list or rule`() {
        val result = parseMarkdown("one-and-two")

        assertEquals(listOf(para(plain("one-and-two"))), result)
    }

    // ── Blockquotes ──────────────────────────────────────────────────

    @Test
    fun `consecutive quote lines merge into one blockquote`() {
        val result = parseMarkdown("> line1\n> line2\n\nrest")

        assertEquals(
            listOf(
                MdSegment.Blockquote(listOf(plain("line1\nline2"))),
                para(plain("rest")),
            ),
            result,
        )
    }

    // ── Lists ────────────────────────────────────────────────────────

    @Test
    fun `unordered list parses`() {
        val result = parseMarkdown("- one\n- two")

        assertEquals(
            listOf(
                MdSegment.ItemList(
                    listOf(
                        MdSegment.ListItem(0, ordered = false, number = 1, spans = listOf(plain("one"))),
                        MdSegment.ListItem(0, ordered = false, number = 1, spans = listOf(plain("two"))),
                    )
                )
            ),
            result,
        )
    }

    @Test
    fun `ordered list keeps numbers`() {
        val result = parseMarkdown("1. first\n2. second")

        assertEquals(
            listOf(
                MdSegment.ItemList(
                    listOf(
                        MdSegment.ListItem(0, ordered = true, number = 1, spans = listOf(plain("first"))),
                        MdSegment.ListItem(0, ordered = true, number = 2, spans = listOf(plain("second"))),
                    )
                )
            ),
            result,
        )
    }

    @Test
    fun `nested list derives level from indentation`() {
        val result = parseMarkdown("- top\n  - sub")

        assertEquals(
            listOf(
                MdSegment.ItemList(
                    listOf(
                        MdSegment.ListItem(0, ordered = false, number = 1, spans = listOf(plain("top"))),
                        MdSegment.ListItem(1, ordered = false, number = 1, spans = listOf(plain("sub"))),
                    )
                )
            ),
            result,
        )
    }

    @Test
    fun `list items with inline spans`() {
        val result = parseMarkdown("- **bold** item")

        assertEquals(
            listOf(
                MdSegment.ItemList(
                    listOf(
                        MdSegment.ListItem(0, ordered = false, number = 1, spans = listOf(MdSpan.Bold("bold"), plain(" item"))),
                    )
                )
            ),
            result,
        )
    }

    // ── Inline spans ─────────────────────────────────────────────────

    @Test
    fun `bold italic and inline code still parse`() {
        assertEquals(
            listOf(MdSpan.Bold("b"), plain(" "), MdSpan.Italic("i"), plain(" "), MdSpan.InlineCode("c")),
            parseInlineSpans("**b** *i* `c`"),
        )
    }

    @Test
    fun `strikethrough parses`() {
        assertEquals(listOf(MdSpan.Strikethrough("gone")), parseInlineSpans("~~gone~~"))
    }

    @Test
    fun `link parses text and url`() {
        assertEquals(listOf(MdSpan.Link("example", "https://example.com")), parseInlineSpans("[example](https://example.com)"))
    }

    @Test
    fun `link with surrounding text`() {
        assertEquals(
            listOf(plain("see "), MdSpan.Link("docs", "https://docs.dev"), plain(" here")),
            parseInlineSpans("see [docs](https://docs.dev) here"),
        )
    }

    @Test
    fun `plain text with no markers stays plain`() {
        assertEquals(listOf(plain("no markers")), parseInlineSpans("no markers"))
    }

    // ── Code blocks (regression) ─────────────────────────────────────

    @Test
    fun `fenced code block with language parses`() {
        val input = "```kotlin\nval x = 1\n```"

        assertEquals(listOf(MdSegment.CodeBlock("kotlin", "val x = 1")), parseMarkdown(input))
    }

    @Test
    fun `paragraph after code block parses`() {
        val input = "```\ncode\n```\n\nafter"

        assertEquals(
            listOf(MdSegment.CodeBlock("", "code"), para(plain("after"))),
            parseMarkdown(input),
        )
    }

    // ── Mixed document ───────────────────────────────────────────────

    @Test
    fun `mixed document produces all segment types in order`() {
        val input = """
            # Title

            intro paragraph

            | H |
            |---|
            | v |

            - item

            > quote

            ---
        """.trimIndent()

        val result = parseMarkdown(input)
        val types = result.map { it::class.simpleName }

        assertEquals(
            listOf("Heading", "Paragraph", "Table", "ItemList", "Blockquote", "HorizontalRule"),
            types,
        )
    }

    // ── Edge cases ───────────────────────────────────────────────────

    @Test
    fun `empty and blank input produce no segments`() {
        assertTrue(parseMarkdown("").isEmpty())
        assertTrue(parseMarkdown("\n\n  \n").isEmpty())
    }

    @Test
    fun `paragraph separated by blank lines become separate segments`() {
        assertEquals(
            listOf(para(plain("one")), para(plain("two"))),
            parseMarkdown("one\n\ntwo"),
        )
    }
}