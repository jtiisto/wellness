package dev.jtiisto.wellness.feature.analysis.markdown

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/**
 * Report markdown, turned into something Compose can draw.
 *
 * The pipeline is: parse with commonmark-java, then walk the AST into
 * [ReportBlock]s, reading the status markers out of the text runs as they go.
 * **No HTML is produced at any point** — there is no renderer in the
 * dependency, no `WebView`, and no node type in the model that could carry
 * markup. That is a stronger guarantee than the PWA's escaping, which existed
 * only because its output went to `innerHTML`.
 *
 * Source spans are on so nothing can vanish: a node type this mapper does not
 * know is emitted as the literal text it came from, rather than being skipped.
 */
object AnalysisMarkdown {

    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create()))
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()

    /** Empty in, empty out — the PWA returns `''` for a missing body. */
    fun render(markdown: String?): List<ReportBlock> {
        if (markdown.isNullOrEmpty()) return emptyList()
        val source = markdown.replace("\r\n", "\n")
        val document = parser.parse(source)
        return Mapper(SourceText(source)).blocks(document)
    }

    /**
     * A text run, split into the prose and the status markers inside it.
     *
     * Markers live in table cells almost always and in prose occasionally, so
     * this reads them wherever they fall rather than assuming a whole cell is
     * one. A run carrying no marker comes back as the single [ReportInline.Text]
     * it was, which is the common case: most report bodies have no marker at all.
     *
     * Two grammars feed the one node. The tokens are what the server serves
     * (docs/ARCHITECTURE.md, "Status marker vocabulary"). The legacy emoji —
     * optionally paired with the word saying the same thing again, in either
     * order — is what a body cached in `payload_cache` before the protocol
     * change still says, and that cache is never re-fetched from the server, so
     * the mapping stays until nothing on any device can still be serving one.
     * A token outside the vocabulary is not matched at all and stays its own
     * literal text.
     */
    fun statusInlines(text: String): List<ReportInline> {
        val markers = MARKER.findAll(text).toList()
        if (markers.isEmpty()) return listOf(ReportInline.Text(text))
        val inlines = mutableListOf<ReportInline>()
        var cursor = 0
        for (marker in markers) {
            if (marker.range.first > cursor) {
                inlines += ReportInline.Text(text.substring(cursor, marker.range.first))
            }
            inlines += ReportInline.Status(statusOf(marker))
            cursor = marker.range.last + 1
        }
        if (cursor < text.length) inlines += ReportInline.Text(text.substring(cursor))
        return inlines
    }

    private fun statusOf(marker: MatchResult): StatusMarker {
        val token = marker.groupValues[1]
        if (token.isNotEmpty()) {
            return when (token.lowercase()) {
                "ok" -> StatusMarker.OK
                "watch" -> StatusMarker.WATCH
                else -> StatusMarker.ACT
            }
        }
        return when (marker.groupValues[2]) {
            "✅", "🟢" -> StatusMarker.OK
            "🟡", "⚠" -> StatusMarker.WATCH
            else -> StatusMarker.ACT
        }
    }

    private const val WORD = "(?:OK|RED|YELLOW|GREEN|PASS|FAIL)"

    /** The legacy dot set, kept whole: the emoji decides, never the word. */
    private const val DOT = "(?:🟢|🟡|🔴|✅|❌|⚠)"

    /**
     * Spelled as an escape rather than pasted in, because the character is
     * invisible in source: the variation selector that makes ⚠ and ⚠️ one marker.
     */
    private const val VARIATION_SELECTOR = "\\uFE0F"

    private val MARKER = Regex(
        "\\[(ok|watch|act)]" +
            "|(?:\\b$WORD\\s+)?($DOT)$VARIATION_SELECTOR?(?:\\s+$WORD\\b)?",
        RegexOption.IGNORE_CASE,
    )
}

/**
 * The original text behind a node.
 *
 * Kept line-indexed because that is the shape commonmark reports spans in, and
 * it is the one part of the span API that has not moved between versions.
 */
private class SourceText(source: String) {
    private val lines = source.split("\n")

    fun of(node: Node): String = node.sourceSpans.joinToString("\n") { span ->
        val line = lines.getOrNull(span.lineIndex).orEmpty()
        val start = span.columnIndex.coerceIn(0, line.length)
        val end = (start + span.length).coerceIn(start, line.length)
        line.substring(start, end)
    }
}

private class Mapper(private val source: SourceText) {

    fun blocks(parent: Node): List<ReportBlock> = parent.children().mapNotNull(::block)

    private fun block(node: Node): ReportBlock? = when (node) {
        is Heading -> ReportBlock.Heading(node.level.coerceIn(1, MAX_HEADING), inlines(node))
        is Paragraph -> ReportBlock.Paragraph(inlines(node))
        is BulletList -> ReportBlock.BulletList(items(node))
        is OrderedList -> ReportBlock.OrderedList(node.markerStartNumber ?: 1, items(node))
        is BlockQuote -> ReportBlock.Quote(blocks(node))
        is FencedCodeBlock -> ReportBlock.CodeBlock(node.literal.trimEnd('\n'))
        is IndentedCodeBlock -> ReportBlock.CodeBlock(node.literal.trimEnd('\n'))
        is ThematicBreak -> ReportBlock.Rule
        is TableBlock -> table(node)
        // A raw-HTML block is text, and only text. The lexeme goes through
        // unescaped because Compose has no HTML interpretation to defend
        // against, and `&lt;img` is what an escaped one would literally read as.
        is HtmlBlock -> ReportBlock.Paragraph(
            listOf(ReportInline.Text(node.literal.trimEnd('\n'))),
        )
        else -> fallbackBlock(node)
    }

    /** Nothing silently disappears: an unmapped block comes back as its own source. */
    private fun fallbackBlock(node: Node): ReportBlock? =
        source.of(node).takeIf { it.isNotBlank() }
            ?.let { ReportBlock.Paragraph(listOf(ReportInline.Text(it))) }

    private fun items(list: Node): List<List<ReportBlock>> =
        list.children().filterIsInstance<ListItem>().map(::blocks)

    private fun table(node: TableBlock): ReportBlock.Table {
        val header = mutableListOf<List<ReportInline>>()
        val rows = mutableListOf<List<List<ReportInline>>>()
        for (section in node.children()) {
            when (section) {
                is TableHead -> section.children().filterIsInstance<TableRow>()
                    .forEach { row -> header += cells(row) }
                is TableBody -> section.children().filterIsInstance<TableRow>()
                    .forEach { row -> rows += cells(row) }
                else -> Unit
            }
        }
        return ReportBlock.Table(header, rows)
    }

    private fun cells(row: TableRow): List<List<ReportInline>> =
        row.children().filterIsInstance<TableCell>().map(::inlines)

    fun inlines(parent: Node): List<ReportInline> = parent.children().flatMap(::inline)

    // A text run can hold several markers and the prose between them, so one
    // node maps to a list. Code spans are deliberately not scanned: `[ok]`
    // written as code is a reader asking to see the token itself.
    private fun inline(node: Node): List<ReportInline> = when (node) {
        is Text -> AnalysisMarkdown.statusInlines(node.literal)
        is Code -> listOf(ReportInline.Code(node.literal))
        is StrongEmphasis -> listOf(ReportInline.Strong(inlines(node)))
        is Emphasis -> listOf(ReportInline.Emphasis(inlines(node)))
        is Link -> listOf(ReportInline.Link(inlines(node), node.destination.orEmpty()))
        is Image -> listOf(
            ReportInline.Text("[image: ${plainText(node)}] (${node.destination.orEmpty()})"),
        )
        is SoftLineBreak -> listOf(ReportInline.Text(" "))
        is HardLineBreak -> listOf(ReportInline.Text("\n"))
        is HtmlInline -> listOf(ReportInline.Text(node.literal))
        else -> listOfNotNull(
            source.of(node).takeIf { it.isNotBlank() }?.let(ReportInline::Text),
        )
    }

    /** An image's alt text: whatever plain words are nested inside it. */
    private fun plainText(node: Node): String = buildString {
        for (child in node.children()) {
            when (child) {
                is Text -> append(child.literal)
                is Code -> append(child.literal)
                else -> append(plainText(child))
            }
        }
    }

    private companion object {
        const val MAX_HEADING = 6
    }
}

/** commonmark's AST is a sibling chain, not a collection. */
private fun Node.children(): List<Node> {
    val result = mutableListOf<Node>()
    var child = firstChild
    while (child != null) {
        result += child
        child = child.next
    }
    return result
}
