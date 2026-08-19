package dev.jtiisto.wellness.feature.analysis.markdown

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The markdown pipeline, and the four vectors the PWA suite pins — translated,
 * not transcribed.
 *
 * `analysis-markdown.test.js` asserts on **HTML strings**: `&lt;img` present,
 * `<img` absent. Neither assertion can be made here, because there is no HTML
 * anywhere in this pipeline. What those tests were really protecting is that a
 * report body cannot smuggle markup into the render tree, and the equivalent
 * statement about this model is stronger: no node type exists that could carry
 * markup, so the tag arrives as inert text — **unescaped**, because Compose has
 * nothing to escape it from and `&lt;img` is what a user would then read.
 */
class AnalysisMarkdownTest {

    private fun render(markdown: String) = AnalysisMarkdown.render(markdown)

    private fun statusInlines(text: String) = AnalysisMarkdown.statusInlines(text)

    /** Every inline in a block tree, flattened, for structural assertions. */
    private fun inlinesOf(blocks: List<ReportBlock>): List<ReportInline> =
        blocks.flatMap { block ->
            when (block) {
                is ReportBlock.Heading -> flattenInlines(block.inlines)
                is ReportBlock.Paragraph -> flattenInlines(block.inlines)
                is ReportBlock.BulletList -> block.items.flatMap(::inlinesOf)
                is ReportBlock.OrderedList -> block.items.flatMap(::inlinesOf)
                is ReportBlock.Quote -> inlinesOf(block.children)
                is ReportBlock.Table ->
                    flattenInlines(block.header.flatten()) +
                        flattenInlines(block.rows.flatten().flatten())
                is ReportBlock.CodeBlock -> emptyList()
                ReportBlock.Rule -> emptyList()
            }
        }

    private fun flattenInlines(inlines: List<ReportInline>): List<ReportInline> =
        inlines.flatMap { inline ->
            when (inline) {
                is ReportInline.Strong -> listOf(inline) + flattenInlines(inline.children)
                is ReportInline.Emphasis -> listOf(inline) + flattenInlines(inline.children)
                is ReportInline.Link -> listOf(inline) + flattenInlines(inline.children)
                else -> listOf(inline)
            }
        }

    /** Everything a reader would see, including code-block bodies. */
    private fun allText(blocks: List<ReportBlock>): String {
        val fromInlines = inlinesOf(blocks).joinToString(" ") { inline ->
            when (inline) {
                is ReportInline.Text -> inline.text
                is ReportInline.Code -> inline.text
                else -> ""
            }
        }
        val fromCode = blocks.filterIsInstance<ReportBlock.CodeBlock>().joinToString(" ") { it.text }
        return "$fromInlines $fromCode"
    }

    // ---- the four ported vectors -------------------------------------------

    @Test
    @DisplayName("vector 1: block-level raw HTML becomes inert text, unescaped, and no markup node exists")
    fun blockLevelRawHtmlIsInert() {
        val blocks = render("<img src=x onerror=\"alert(1)\">\n\ntext")

        val text = allText(blocks)
        assertTrue(text.contains("<img src=x onerror=\"alert(1)\">"), text)
        assertFalse(text.contains("&lt;"), "escaping is an innerHTML mechanism; here it would just look wrong")
        assertTrue(text.contains("text"), "the markdown around it still renders")
        // The structural half of the JS assertion: the model has no node that
        // could ever be interpreted as markup, so the tag can only be text.
        assertTrue(
            inlinesOf(blocks).all { it is ReportInline.Text || it is ReportInline.Code },
            "a raw-HTML block must produce plain text nodes only",
        )
    }

    @Test
    @DisplayName("vector 2: inline raw HTML becomes inert text and the markdown around it survives")
    fun inlineRawHtmlIsInert() {
        val blocks = render("before <b onmouseover=\"alert(1)\">bold</b> after")

        val text = allText(blocks)
        assertTrue(text.contains("<b onmouseover=\"alert(1)\">"), text)
        assertTrue(text.contains("</b>"), text)
        assertTrue(text.contains("before"), text)
        assertTrue(text.contains("after"), text)
        assertFalse(text.contains("&lt;"), text)
    }

    @Test
    @DisplayName("vector 3: svg, script and iframe payloads are text like everything else")
    fun scriptPayloadsAreInert() {
        for (payload in listOf(
            "<svg onload=\"alert(1)\"></svg>",
            "<script>alert(1)</script>",
            "<iframe src=\"javascript:alert(1)\"></iframe>",
        )) {
            val blocks = render(payload)
            val text = allText(blocks)
            assertTrue(text.contains(payload.substringBefore('>')), "$payload -> $text")
            assertTrue(
                inlinesOf(blocks).all { it is ReportInline.Text },
                "$payload produced something other than text",
            )
        }
    }

    @Test
    @DisplayName("vector 4: markdown features still render — h1, strong, code and a table")
    fun markdownFeaturesStillRender() {
        val blocks = render("# Title\n\n**bold** and `code`\n\n| a | b |\n|---|---|\n| 1 | 2 |")

        val heading = blocks.filterIsInstance<ReportBlock.Heading>().single()
        assertEquals(1, heading.level, "the JS vector pins an h1 specifically")
        assertEquals("Title", (heading.inlines.single() as ReportInline.Text).text)

        val inlines = inlinesOf(blocks)
        assertTrue(inlines.any { it is ReportInline.Strong })
        assertTrue(inlines.any { it is ReportInline.Code && it.text == "code" })

        val table = blocks.filterIsInstance<ReportBlock.Table>().single()
        assertEquals(2, table.header.size)
        assertEquals(1, table.rows.size)
        assertEquals(2, table.rows.single().size)
    }

    // ---- status markers: the wire's three tokens ----------------------------

    @Test
    @DisplayName("each token becomes the status node it names, and nothing else")
    fun tokensBecomeStatusNodes() {
        assertEquals(listOf(ReportInline.Status(StatusMarker.OK)), statusInlines("[ok]"))
        assertEquals(listOf(ReportInline.Status(StatusMarker.WATCH)), statusInlines("[watch]"))
        assertEquals(listOf(ReportInline.Status(StatusMarker.ACT)), statusInlines("[act]"))
    }

    @Test
    @DisplayName("a marker keeps the prose either side of it — a run can hold several")
    fun markersSplitTheirOwnRun() {
        assertEquals(
            listOf(
                ReportInline.Text("Recovery is "),
                ReportInline.Status(StatusMarker.OK),
                ReportInline.Text(" and load is "),
                ReportInline.Status(StatusMarker.ACT),
                ReportInline.Text("."),
            ),
            statusInlines("Recovery is [ok] and load is [act]."),
        )
    }

    @Test
    @DisplayName("a token the model shouted still reads — the output contract is not enforced upstream")
    fun tokenMatchingIsCaseInsensitive() {
        assertEquals(listOf(ReportInline.Status(StatusMarker.OK)), statusInlines("[OK]"))
        assertEquals(listOf(ReportInline.Status(StatusMarker.WATCH)), statusInlines("[Watch]"))
    }

    @Test
    @DisplayName("a token outside the vocabulary stays the literal text it was written as")
    fun unknownTokensAreLeftAlone() {
        assertEquals(listOf(ReportInline.Text("[flag] and [ok2]")), statusInlines("[flag] and [ok2]"))
    }

    // ---- status markers: the legacy grammar device caches still speak -------

    @Test
    @DisplayName("every legacy emoji maps onto the same three nodes")
    fun legacyEmojiMapToStatusNodes() {
        assertEquals(listOf(ReportInline.Status(StatusMarker.OK)), statusInlines("✅"))
        assertEquals(listOf(ReportInline.Status(StatusMarker.OK)), statusInlines("🟢"))
        assertEquals(listOf(ReportInline.Status(StatusMarker.WATCH)), statusInlines("🟡"))
        assertEquals(listOf(ReportInline.Status(StatusMarker.WATCH)), statusInlines("⚠️"))
        // The same marker without its variation selector.
        assertEquals(listOf(ReportInline.Status(StatusMarker.WATCH)), statusInlines("⚠"))
        assertEquals(listOf(ReportInline.Status(StatusMarker.ACT)), statusInlines("🔴"))
        assertEquals(listOf(ReportInline.Status(StatusMarker.ACT)), statusInlines("❌"))
    }

    @Test
    @DisplayName("the redundant word beside the emoji is absorbed, in either order and any case")
    fun legacyPairsAreAbsorbed() {
        for (pair in listOf("✅ OK", "OK ✅", "✅ PASS", "✅ green", "GREEN ✅")) {
            assertEquals(listOf(ReportInline.Status(StatusMarker.OK)), statusInlines(pair), pair)
        }
        for (pair in listOf("🔴 RED", "RED 🔴", "🔴 FAIL", "FAIL 🔴", "🔴 Red")) {
            assertEquals(listOf(ReportInline.Status(StatusMarker.ACT)), statusInlines(pair), pair)
        }
        assertEquals(listOf(ReportInline.Status(StatusMarker.WATCH)), statusInlines("🟡 YELLOW"))
    }

    @Test
    @DisplayName("the emoji decides the verdict, never the word beside it")
    fun theEmojiWinsTheContradiction() {
        // The collapse this replaced let the dot win, and reports have been
        // read that way since 2026-07: inheriting it beats inventing a
        // tie-break nobody has seen.
        assertEquals(listOf(ReportInline.Status(StatusMarker.WATCH)), statusInlines("⚠️ GREEN"))
    }

    @Test
    @DisplayName("a longer word starting with a status word keeps its letters")
    fun markersRespectWordBoundaries() {
        assertEquals(
            listOf(ReportInline.Status(StatusMarker.OK), ReportInline.Text(" OKAY")),
            statusInlines("✅ OKAY"),
        )
        assertEquals(
            listOf(ReportInline.Text("REDACTED "), ReportInline.Status(StatusMarker.ACT)),
            statusInlines("REDACTED 🔴"),
        )
    }

    @Test
    @DisplayName("a bare status word is prose, and a plain check mark is left alone")
    fun unmarkedTextPassesThrough() {
        val prose = "The session went well, readiness is OK and the numbers held."
        assertEquals(listOf(ReportInline.Text(prose)), statusInlines(prose))
        assertEquals(listOf(ReportInline.Text("✓ Zone 2 held")), statusInlines("✓ Zone 2 held"))
        assertEquals(listOf(ReportInline.Text("🚩 flagged")), statusInlines("🚩 flagged"))
    }

    // ---- status markers: through the whole pipeline -------------------------

    @Test
    @DisplayName("a table cell carrying a marker renders as the status node, token or legacy")
    fun markersSurviveIntoRenderedCells() {
        val blocks = render(
            "| Metric | Status |\n|---|---|\n| Sleep | [ok] |\n| Load | 🔴 RED |",
        )

        val rows = blocks.filterIsInstance<ReportBlock.Table>().single().rows
        assertEquals(ReportInline.Status(StatusMarker.OK), rows[0][1].single())
        assertEquals(ReportInline.Status(StatusMarker.ACT), rows[1][1].single())
    }

    @Test
    @DisplayName("markers inside emphasis and prose reach the model too")
    fun markersSurviveInsideOtherInlines() {
        val blocks = render("**[watch]** readiness, and load is [act].")

        val strong = inlinesOf(blocks).filterIsInstance<ReportInline.Strong>().single()
        assertEquals(ReportInline.Status(StatusMarker.WATCH), strong.children.single())
        assertTrue(inlinesOf(blocks).contains(ReportInline.Status(StatusMarker.ACT)))
    }

    @Test
    @DisplayName("a token written as code is the token itself, not a status")
    fun codeSpansAreNotScanned() {
        val blocks = render("Write `[ok]` in the cell.")

        assertEquals("[ok]", inlinesOf(blocks).filterIsInstance<ReportInline.Code>().single().text)
        assertTrue(inlinesOf(blocks).none { it is ReportInline.Status })
    }

    // ---- inline mapping ----------------------------------------------------

    @Test
    @DisplayName("a link keeps its destination — a citation with no URL is not a citation")
    fun linkKeepsDestination() {
        val blocks = render("See [the source](https://example.invalid/fixture) for detail.")

        val link = inlinesOf(blocks).filterIsInstance<ReportInline.Link>().single()
        assertEquals("https://example.invalid/fixture", link.destination)
        assertEquals("the source", (link.children.single() as ReportInline.Text).text)
    }

    @Test
    @DisplayName("an image degrades to a labelled placeholder rather than disappearing")
    fun imageBecomesPlaceholder() {
        val blocks = render("![a chart](https://example.invalid/fx.png)")

        val text = allText(blocks)
        assertTrue(text.contains("[image: a chart]"), text)
        assertTrue(text.contains("(https://example.invalid/fx.png)"), text)
    }

    @Test
    @DisplayName("inlines nest: bold and code inside a table cell both survive")
    fun tableCellsKeepNestedInlines() {
        val blocks = render("| Metric | Note |\n|---|---|\n| **Zone 2** | `fx-1` |")

        val row = blocks.filterIsInstance<ReportBlock.Table>().single().rows.single()
        val strong = row[0].single() as ReportInline.Strong
        assertEquals("Zone 2", (strong.children.single() as ReportInline.Text).text)
        assertEquals("fx-1", (row[1].single() as ReportInline.Code).text)
    }

    @Test
    @DisplayName("emphasis nested inside strong keeps both levels")
    fun nestedEmphasisSurvives() {
        val blocks = render("**bold with *italic* inside**")

        val strong = inlinesOf(blocks).filterIsInstance<ReportInline.Strong>().single()
        assertTrue(strong.children.any { it is ReportInline.Emphasis })
    }

    @Test
    @DisplayName("a soft break is a space and a hard break is a newline")
    fun breaksMapToWhitespace() {
        val soft = allText(render("first\nsecond"))
        assertTrue(soft.contains("first"), soft)
        assertTrue(soft.contains("second"), soft)

        val hard = render("first  \nsecond")
        val inlines = (hard.single() as ReportBlock.Paragraph).inlines
        assertTrue(inlines.any { it is ReportInline.Text && it.text == "\n" }, inlines.toString())
    }

    // ---- block mapping -----------------------------------------------------

    @Test
    @DisplayName("every heading level from one to six renders")
    fun allHeadingLevelsRender() {
        val markdown = (1..6).joinToString("\n\n") { "${"#".repeat(it)} Level $it" }

        val levels = render(markdown).filterIsInstance<ReportBlock.Heading>().map { it.level }
        assertEquals(listOf(1, 2, 3, 4, 5, 6), levels)
    }

    @Test
    @DisplayName("lists nest, and an ordered list keeps the number it started at")
    fun listsNest() {
        val blocks = render("- one\n  - one a\n- two\n\n3. third\n4. fourth")

        val bullets = blocks.filterIsInstance<ReportBlock.BulletList>().single()
        assertEquals(2, bullets.items.size)
        val nested = bullets.items[0].filterIsInstance<ReportBlock.BulletList>().single()
        assertEquals("one a", allText(nested.items.single()).trim())

        val ordered = blocks.filterIsInstance<ReportBlock.OrderedList>().single()
        assertEquals(3, ordered.start)
        assertEquals(2, ordered.items.size)
    }

    @Test
    @DisplayName("a block quote keeps its children as blocks")
    fun blockQuoteKeepsChildren() {
        val quote = render("> quoted line\n>\n> second paragraph")
            .filterIsInstance<ReportBlock.Quote>()
            .single()

        assertEquals(2, quote.children.size)
        assertTrue(allText(quote.children).contains("quoted line"))
    }

    @Test
    @DisplayName("fenced and indented code blocks both keep their literal body")
    fun codeBlocksKeepTheirBody() {
        val fenced = render("```text\nfixture body\n```").filterIsInstance<ReportBlock.CodeBlock>().single()
        assertEquals("fixture body", fenced.text)

        val indented = render("    indented body").filterIsInstance<ReportBlock.CodeBlock>().single()
        assertEquals("indented body", indented.text)
    }

    @Test
    @DisplayName("a thematic break is a rule")
    fun thematicBreakIsARule() {
        assertTrue(render("above\n\n---\n\nbelow").contains(ReportBlock.Rule))
    }

    @Test
    @DisplayName("a node type this mapper does not know falls back to its own source text")
    fun unknownNodesFallBackToSource() {
        // A link reference definition is a real commonmark node with no mapping
        // here. The rule is that nothing disappears silently, so it comes back
        // as exactly the text it was written as, via its source span.
        val blocks = render("[fixture]: https://example.invalid/ref\n\nbody")

        assertEquals(2, blocks.size)
        assertEquals(
            "[fixture]: https://example.invalid/ref",
            ((blocks[0] as ReportBlock.Paragraph).inlines.single() as ReportInline.Text).text,
        )
        assertTrue(allText(blocks).contains("body"))
    }

    // ---- degenerate input --------------------------------------------------

    @Test
    @DisplayName("no body renders nothing at all")
    fun emptyInputRendersNothing() {
        assertTrue(AnalysisMarkdown.render(null).isEmpty())
        assertTrue(AnalysisMarkdown.render("").isEmpty())
        assertTrue(AnalysisMarkdown.render("   ").isEmpty())
    }

    @Test
    @DisplayName("windows line endings parse the same as unix ones")
    fun crlfIsNormalized() {
        val blocks = render("# Title\r\n\r\nbody text\r\n")

        assertEquals(1, blocks.filterIsInstance<ReportBlock.Heading>().size)
        assertTrue(allText(blocks).contains("body text"))
    }

    // ---- the golden report body --------------------------------------------

    @Test
    @DisplayName("the golden report body renders every construct, with its attack string inert")
    fun goldenReportBodyRenders() {
        val markdown = requireNotNull(GOLDEN_MARKDOWN.find(goldenFixture())) {
            "the golden report fixture no longer carries a response_markdown"
        }.groupValues[1].unescapeJson()

        val blocks = AnalysisMarkdown.render(markdown)

        assertEquals(listOf(1, 2, 3), blocks.filterIsInstance<ReportBlock.Heading>().map { it.level })
        assertEquals(1, blocks.filterIsInstance<ReportBlock.Table>().size)
        assertEquals(1, blocks.filterIsInstance<ReportBlock.BulletList>().size)
        assertEquals(1, blocks.filterIsInstance<ReportBlock.OrderedList>().size)
        assertEquals(1, blocks.filterIsInstance<ReportBlock.Quote>().size)
        assertEquals(1, blocks.filterIsInstance<ReportBlock.CodeBlock>().size)
        assertTrue(blocks.contains(ReportBlock.Rule))

        val inlines = inlinesOf(blocks)
        assertTrue(inlines.any { it is ReportInline.Strong })
        assertTrue(inlines.any { it is ReportInline.Emphasis })
        assertTrue(inlines.any { it is ReportInline.Link })

        val text = allText(blocks)
        assertTrue(text.contains("<img src=x onerror=\"alert(1)\">"), "the block vector must be visible text")
        assertTrue(text.contains("<b onmouseover=\"alert(1)\">"), "the inline vector must be visible text")
        assertFalse(text.contains("&lt;"), "nothing in this pipeline escapes, because nothing interprets")
        assertTrue(text.contains("[image: fixture alt]"), text)

        // The status column: two tokens as the server now serves them, and one
        // legacy pair as a cached body still says it — all three arriving as
        // the same node, none of them left as characters in the text.
        assertEquals(
            listOf(
                ReportInline.Status(StatusMarker.OK),
                ReportInline.Status(StatusMarker.ACT),
                ReportInline.Status(StatusMarker.OK),
            ),
            inlinesOf(blocks).filterIsInstance<ReportInline.Status>(),
        )
        assertFalse(text.contains("[ok]"), "a token must not survive as text")
        assertFalse(text.contains("[act]"), "a token must not survive as text")
        assertFalse(text.contains("🟢"), "a legacy emoji must not survive as text")
        assertFalse(text.contains("OK"), "the word beside the legacy emoji is absorbed with it")
    }

    private fun goldenFixture(): String =
        requireNotNull(javaClass.getResourceAsStream("/golden/analysis/report-completed.json")) {
            "missing golden fixture golden/analysis/report-completed.json"
        }.use { it.readBytes().decodeToString() }

    /** The fixture is JSON; its markdown arrives escaped. */
    private fun String.unescapeJson(): String = replace("\\n", "\n").replace("\\\"", "\"")

    private companion object {
        val GOLDEN_MARKDOWN = Regex("\"response_markdown\": \"(.*?)\",\\n", RegexOption.DOT_MATCHES_ALL)
    }
}
