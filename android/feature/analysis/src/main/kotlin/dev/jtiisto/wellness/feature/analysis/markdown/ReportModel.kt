package dev.jtiisto.wellness.feature.analysis.markdown

/**
 * A run of text inside a block.
 *
 * There is deliberately **no HTML node**. Report bodies are model-produced and a
 * stored one could carry `<img onerror=…>`; in the PWA that had to be entity-
 * escaped because the markdown was handed to `innerHTML`. Here nothing can
 * interpret markup, so raw HTML arrives as [Text] carrying its own source
 * lexeme — visible, inert, and *unescaped*, because `&lt;img` is what the user
 * would actually read on screen if we escaped it.
 */
sealed interface ReportInline {
    data class Text(val text: String) : ReportInline
    data class Code(val text: String) : ReportInline
    data class Strong(val children: List<ReportInline>) : ReportInline
    data class Emphasis(val children: List<ReportInline>) : ReportInline

    /**
     * A link, with its destination kept.
     *
     * Rendered as `label (destination)` rather than as a tap target: health
     * reports cite sources, and silently dropping the URL would leave a citation
     * pointing nowhere.
     */
    data class Link(val children: List<ReportInline>, val destination: String) : ReportInline
}

/** A block-level element of a rendered report. */
sealed interface ReportBlock {
    /** Levels 1–6 all render. The PWA's own test pins an `<h1>`. */
    data class Heading(val level: Int, val inlines: List<ReportInline>) : ReportBlock

    data class Paragraph(val inlines: List<ReportInline>) : ReportBlock

    /** Items are block lists, so a list can contain a list. */
    data class BulletList(val items: List<List<ReportBlock>>) : ReportBlock

    data class OrderedList(val start: Int, val items: List<List<ReportBlock>>) : ReportBlock

    data class Quote(val children: List<ReportBlock>) : ReportBlock

    data class CodeBlock(val text: String) : ReportBlock

    /** GFM tables. Cells are inline lists — bold and code inside them are common. */
    data class Table(
        val header: List<List<ReportInline>>,
        val rows: List<List<List<ReportInline>>>,
    ) : ReportBlock

    data object Rule : ReportBlock
}
