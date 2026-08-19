package dev.jtiisto.wellness.feature.analysis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jtiisto.wellness.core.ui.theme.InkJudgment
import dev.jtiisto.wellness.core.ui.theme.LogbookPalette
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.core.ui.theme.bottomRule
import dev.jtiisto.wellness.core.ui.theme.drawInkJudgment
import dev.jtiisto.wellness.feature.analysis.AnalysisUiLogic
import dev.jtiisto.wellness.feature.analysis.markdown.ReportBlock
import dev.jtiisto.wellness.feature.analysis.markdown.ReportInline
import dev.jtiisto.wellness.feature.analysis.markdown.StatusMarker

/**
 * A parsed report, drawn on paper.
 *
 * Everything here is text and rules: there is no HTML path, no `WebView`, and no
 * node in the model that could carry markup. A report body containing
 * `<img onerror=…>` renders those characters, and that is the whole of it.
 *
 * The treatments are the app's own, applied to somebody else's structure: the
 * model's top heading level becomes a Logbook section head, a quote becomes the
 * marginalia a coach's note is written in, a code block sits between hairlines
 * with no box around it, and a GFM table is set exactly like the coach's set
 * table. LLM prose is the one content in the app nobody here wrote, so the
 * furniture around it has to be unmistakably ours.
 */
@Composable
fun ReportBody(blocks: List<ReportBlock>, modifier: Modifier = Modifier) {
    // The document's own shallowest heading is its top: a model that opens at
    // `##` means the same thing by it as one that opens at `#`.
    val topLevel = remember(blocks) { AnalysisUiLogic.topHeadingLevel(blocks) }
    Blocks(blocks = blocks, topLevel = topLevel, marginalia = false, modifier = modifier)
}

@Composable
private fun Blocks(
    blocks: List<ReportBlock>,
    topLevel: Int,
    marginalia: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
    ) {
        blocks.forEach { block -> Block(block, topLevel, marginalia) }
    }
}

@Composable
private fun Block(block: ReportBlock, topLevel: Int, marginalia: Boolean) {
    val palette = LogbookTheme.palette
    val type = LogbookTheme.type
    when (block) {
        is ReportBlock.Heading -> Heading(block, topLevel)

        is ReportBlock.Paragraph -> Text(
            text = inlineText(block.inlines),
            style = if (marginalia) type.body.copy(fontStyle = FontStyle.Italic) else type.body,
            color = if (marginalia) palette.inkSoft else palette.ink,
            inlineContent = statusMarks(),
        )

        // The en-dash is the log's list marker; a bullet is a shape the system
        // does not otherwise draw, and it would read as a mark.
        is ReportBlock.BulletList -> Column(
            verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid),
        ) {
            block.items.forEach { item ->
                ListRow(marker = EN_DASH, mono = false) { Blocks(item, topLevel, marginalia) }
            }
        }

        is ReportBlock.OrderedList -> Column(
            verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid),
        ) {
            block.items.forEachIndexed { index, item ->
                ListRow(marker = "${block.start + index}.", mono = true) {
                    Blocks(item, topLevel, marginalia)
                }
            }
        }

        // Marginalia: the execution-note treatment, because that is what a quote
        // in a report is — an aside spoken to the reader rather than a block of
        // findings. Nested quotes keep the voice; the rail simply indents again.
        is ReportBlock.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        color = palette.ruleStrong,
                        size = Size(MARGINALIA_RAIL.toPx(), size.height),
                    )
                }
                .padding(start = MARGINALIA_INSET, top = 2.dp, bottom = 2.dp),
        ) {
            Blocks(block.children, topLevel, marginalia = true)
        }

        // Between hairlines, with no fill and no box: a fill would be a second
        // surface, and the rules already say where the code starts and stops.
        is ReportBlock.CodeBlock -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val stroke = LogbookSpace.hairline.toPx()
                    drawRect(color = palette.rule, size = Size(size.width, stroke))
                    drawRect(
                        color = palette.rule,
                        topLeft = Offset(0f, size.height - stroke),
                        size = Size(size.width, stroke),
                    )
                }
                .padding(vertical = CODE_BLOCK_PADDING),
        ) {
            Text(
                text = block.text,
                style = type.data,
                color = palette.inkSoft,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            )
        }

        is ReportBlock.Table -> ReportTable(block)

        ReportBlock.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = LogbookSpace.grid * 2),
            thickness = LogbookSpace.hairline,
            color = palette.ruleStrong,
        )
    }
}

/**
 * A heading, ranked against the report's own top level.
 *
 * The top level sets as a Logbook section — display caps over a `rule-strong`
 * hairline — so the model's outline reads as the page's own structure. Below it
 * the ramp is weight, not size: a semibold line and then a medium one, because
 * three sizes of condensed caps inside a report would compete with the report's
 * title.
 */
@Composable
private fun Heading(heading: ReportBlock.Heading, topLevel: Int) {
    val palette = LogbookTheme.palette
    val type = LogbookTheme.type
    when (AnalysisUiLogic.headingRank(heading.level, topLevel)) {
        0 -> Text(
            text = inlineText(heading.inlines, caps = true),
            style = type.section.copy(fontSize = TOP_HEADING_SIZE, lineHeight = TOP_HEADING_LEADING),
            color = palette.ink,
            inlineContent = statusMarks(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = LogbookSpace.grid * 2)
                .bottomRule(palette.ruleStrong)
                .padding(bottom = LogbookSpace.grid),
        )

        1 -> Text(
            text = inlineText(heading.inlines),
            style = type.body.copy(fontWeight = FontWeight.SemiBold),
            color = palette.ink,
            inlineContent = statusMarks(),
            modifier = Modifier.padding(top = LogbookSpace.grid),
        )

        else -> Text(
            text = inlineText(heading.inlines),
            style = type.body.copy(fontWeight = FontWeight.Medium),
            color = palette.ink,
            inlineContent = statusMarks(),
        )
    }
}

/** A marker in its own gutter, so wrapped lines stay aligned under the text. */
@Composable
private inline fun ListRow(marker: String, mono: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = marker,
            style = if (mono) LogbookTheme.type.data else LogbookTheme.type.body,
            color = LogbookTheme.palette.inkSoft,
            modifier = Modifier.width(MARKER_GUTTER),
        )
        Column(content = content)
    }
}

/**
 * A GFM table, set as the coach's set table.
 *
 * Mono cells, a faint caps header over a `rule-strong` rule, hairline row rules
 * and nothing else — no fills, no zebra, no box. The first column is the row's
 * name and sets in the body face at the start; every other column is data and
 * lands at the end, which is what makes a column of numbers readable down the
 * page.
 *
 * Fixed-width cells inside one horizontal scroller: a report table is data, and
 * columns that shift width row to row are unreadable. The scroller is the
 * `table-wrap` the PWA used, for the same reason — a wide table must not force
 * the page itself sideways.
 */
@Composable
private fun ReportTable(table: ReportBlock.Table) {
    val palette = LogbookTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        if (table.header.isNotEmpty()) {
            Row(modifier = Modifier.bottomRule(palette.ruleStrong)) {
                table.header.forEachIndexed { index, cell ->
                    Cell(
                        inlines = cell,
                        index = index,
                        style = LogbookTheme.type.tableHeader,
                        color = palette.inkFaint,
                        caps = true,
                    )
                }
            }
        }
        table.rows.forEach { row ->
            Row(modifier = Modifier.bottomRule(palette.rule)) {
                row.forEachIndexed { index, cell ->
                    Cell(
                        inlines = cell,
                        index = index,
                        style = if (index == 0) LogbookTheme.type.body else LogbookTheme.type.data,
                        color = palette.ink,
                        caps = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun Cell(
    inlines: List<ReportInline>,
    index: Int,
    style: TextStyle,
    color: Color,
    caps: Boolean,
) {
    val first = index == 0
    Text(
        text = inlineText(inlines, caps = caps),
        style = style.copy(textAlign = if (first) TextAlign.Start else TextAlign.End),
        color = color,
        inlineContent = statusMarks(),
        modifier = Modifier
            .width(if (first) NAME_CELL_WIDTH else DATA_CELL_WIDTH)
            .padding(end = LogbookSpace.grid * 2, top = CELL_PADDING, bottom = CELL_PADDING),
    )
}

/**
 * Inlines, flattened into one styled string.
 *
 * A link renders as `label (destination)` in the quiet tone: inert, because a
 * report is a document rather than a page, but never stripped — a citation with
 * its URL removed is not a citation.
 *
 * [caps] uppercases the **prose** runs only. A caps heading is a Logbook rule
 * about display type; a code span inside one is an identifier, and case is part
 * of what it says.
 */
@Composable
private fun inlineText(inlines: List<ReportInline>, caps: Boolean = false): AnnotatedString {
    val palette = LogbookTheme.palette
    val mono = LogbookTheme.type.data.fontFamily
    return buildAnnotatedString { appendInlines(inlines, palette, mono, caps) }
}

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<ReportInline>,
    palette: LogbookPalette,
    mono: FontFamily?,
    caps: Boolean,
) {
    inlines.forEach { inline ->
        when (inline) {
            is ReportInline.Text -> append(if (caps) inline.text.uppercase() else inline.text)
            is ReportInline.Status -> appendStatus(inline.status, mono)

            // Underlined, not chipped: a tinted background would be the only
            // filled box on the page. The rule takes the text's own ink because
            // a span style has no separate colour for its decoration — the one
            // place the mockup's `rule-strong` underline could not be honoured
            // exactly.
            is ReportInline.Code -> withStyle(
                SpanStyle(fontFamily = mono, textDecoration = TextDecoration.Underline),
            ) { append(inline.text) }

            is ReportInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                appendInlines(inline.children, palette, mono, caps)
            }

            is ReportInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlines(inline.children, palette, mono, caps)
            }

            is ReportInline.Link -> {
                appendInlines(inline.children, palette, mono, caps)
                if (inline.destination.isNotBlank()) {
                    withStyle(SpanStyle(color = palette.inkSoft, fontFamily = mono)) {
                        append(" (${inline.destination})")
                    }
                }
            }
        }
    }
}

/**
 * A status marker: the mark, then the word it is spoken as.
 *
 * The wire serves judgment as a token and this client draws it — the journal's
 * shape grammar turned onto content, so a verdict is read as a filled, half or
 * open mark rather than as a colour. The word is not decoration: it is what a
 * screen reader announces (the mark itself occupies a blank placeholder, so
 * nothing is spoken twice), and it is what makes the three marks legible to a
 * reader who has never been given a legend. The `!` on [StatusMarker.ACT] is
 * the system's alarm — ink and a mono bang, never red.
 */
private fun AnnotatedString.Builder.appendStatus(status: StatusMarker, mono: FontFamily?) {
    appendInlineContent(statusMarkId(status), STATUS_MARK_ALT)
    withStyle(
        SpanStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Medium,
            letterSpacing = STATUS_TRACKING,
        ),
    ) {
        append(statusWord(status))
    }
}

private fun statusWord(status: StatusMarker): String = when (status) {
    StatusMarker.OK -> "OK"
    StatusMarker.WATCH -> "WATCH"
    StatusMarker.ACT -> "! ACT"
}

private fun statusMarkId(status: StatusMarker): String = "status-${status.name}"

/** The three marks, as inline content the running text holds a place for. */
@Composable
private fun statusMarks(): Map<String, InlineTextContent> {
    val palette = LogbookTheme.palette
    return StatusMarker.entries.associate { status ->
        statusMarkId(status) to InlineTextContent(
            Placeholder(STATUS_MARK_BOX, STATUS_MARK_SIZE, PlaceholderVerticalAlign.TextCenter),
        ) { _ ->
            // The box is wider than the mark it holds, and the difference is
            // the gap before the word.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Canvas(modifier = Modifier.fillMaxHeight().aspectRatio(1f)) {
                    drawInkJudgment(status.judgment(), palette)
                }
            }
        }
    }
}

/** Filled, half, open — the same three verdicts the week marks draw. */
private fun StatusMarker.judgment(): InkJudgment = when (this) {
    StatusMarker.OK -> InkJudgment.SETTLED
    StatusMarker.WATCH -> InkJudgment.PARTIAL
    StatusMarker.ACT -> InkJudgment.ATTENTION
}

private const val EN_DASH = "–"

/** One blank the mark is drawn over, so the reader hears the word and not it. */
private const val STATUS_MARK_ALT = " "
private val STATUS_MARK_SIZE = 10.sp
private val STATUS_MARK_BOX = 14.sp
private val STATUS_TRACKING = 0.5.sp

private val TOP_HEADING_SIZE = 15.sp
private val TOP_HEADING_LEADING = 18.sp

private val MARKER_GUTTER = 16.dp
private val MARGINALIA_RAIL = 2.dp
private val MARGINALIA_INSET = 12.dp
private val CODE_BLOCK_PADDING = 9.dp
private val CELL_PADDING = 7.dp

/** The row's name column, then every data column at one width so digits line up. */
private val NAME_CELL_WIDTH = 150.dp
private val DATA_CELL_WIDTH = 92.dp
