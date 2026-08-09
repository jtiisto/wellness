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
 * The pipeline is: collapse the redundant status pairs, parse with
 * commonmark-java, then walk the AST into [ReportBlock]s. **No HTML is produced
 * at any point** — there is no renderer in the dependency, no `WebView`, and no
 * node type in the model that could carry markup. That is a stronger guarantee
 * than the PWA's escaping, which existed only because its output went to
 * `innerHTML`.
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
        val source = collapseStatusText(markdown).replace("\r\n", "\n")
        val document = parser.parse(source)
        return Mapper(SourceText(source)).blocks(document)
    }

    /**
     * Collapse `🟢 OK` (and `OK 🟢`) to just the dot.
     *
     * Status cells in report tables carry the colour and the word, which say the
     * same thing twice and make the column impossible to scan. Applied to the
     * raw markdown before parsing, so it catches prose as well as cells — a 1:1
     * port of the PWA's two regexes, word set and dot set included.
     */
    fun collapseStatusText(markdown: String): String = markdown
        .replace(DOT_THEN_WORD, "$1")
        .replace(WORD_THEN_DOT, "$1")

    private const val WORD = "(?:OK|RED|YELLOW|GREEN|PASS|FAIL)"
    private const val DOT = "[🟢🟡🔴✅❌⚠️]"

    private val DOT_THEN_WORD = Regex("($DOT)\\s+$WORD\\b", RegexOption.IGNORE_CASE)
    private val WORD_THEN_DOT = Regex("\\b$WORD\\s+($DOT)", RegexOption.IGNORE_CASE)
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

    fun inlines(parent: Node): List<ReportInline> = parent.children().mapNotNull(::inline)

    private fun inline(node: Node): ReportInline? = when (node) {
        is Text -> ReportInline.Text(node.literal)
        is Code -> ReportInline.Code(node.literal)
        is StrongEmphasis -> ReportInline.Strong(inlines(node))
        is Emphasis -> ReportInline.Emphasis(inlines(node))
        is Link -> ReportInline.Link(inlines(node), node.destination.orEmpty())
        is Image -> ReportInline.Text("[image: ${plainText(node)}] (${node.destination.orEmpty()})")
        is SoftLineBreak -> ReportInline.Text(" ")
        is HardLineBreak -> ReportInline.Text("\n")
        is HtmlInline -> ReportInline.Text(node.literal)
        else -> source.of(node).takeIf { it.isNotBlank() }?.let(ReportInline::Text)
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
