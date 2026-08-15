package dev.jtiisto.wellness.feature.analysis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.feature.analysis.markdown.ReportBlock
import dev.jtiisto.wellness.feature.analysis.markdown.ReportInline

/**
 * A parsed report, drawn.
 *
 * Everything here is text and rules: there is no HTML path, no `WebView`, and no
 * node in the model that could carry markup. A report body containing
 * `<img onerror=…>` renders those characters, and that is the whole of it.
 */
@Composable
fun ReportBody(blocks: List<ReportBlock>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        blocks.forEach { block -> Block(block) }
    }
}

@Composable
private fun Block(block: ReportBlock) {
    val palette = WellnessTheme.palette
    val type = WellnessTheme.type
    when (block) {
        is ReportBlock.Heading -> Text(
            text = inlineText(block.inlines),
            style = headingStyle(block.level),
            color = palette.textPrimary,
            modifier = Modifier.padding(top = if (block.level <= 2) WellnessSpace.sm else 2.dp),
        )

        is ReportBlock.Paragraph -> Text(
            text = inlineText(block.inlines),
            style = type.body,
            color = palette.textPrimary,
        )

        is ReportBlock.BulletList -> Column(
            verticalArrangement = Arrangement.spacedBy(WellnessSpace.xs),
        ) {
            block.items.forEach { item -> ListRow(marker = "•") { ReportBody(item) } }
        }

        is ReportBlock.OrderedList -> Column(
            verticalArrangement = Arrangement.spacedBy(WellnessSpace.xs),
        ) {
            block.items.forEachIndexed { index, item ->
                ListRow(marker = "${block.start + index}.") { ReportBody(item) }
            }
        }

        is ReportBlock.Quote -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(WellnessShape.input)
                .background(palette.band)
                .padding(WellnessSpace.sm),
        ) {
            ReportBody(block.children)
        }

        is ReportBlock.CodeBlock -> Text(
            text = block.text,
            style = type.body.copy(fontFamily = FontFamily.Monospace, fontSize = CODE_SIZE),
            color = palette.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(WellnessShape.input)
                .background(palette.input)
                .horizontalScroll(rememberScrollState())
                .padding(WellnessSpace.sm),
        )

        is ReportBlock.Table -> ReportTable(block)

        ReportBlock.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = WellnessSpace.xs),
            thickness = WellnessSpace.hairline,
            color = palette.line,
        )
    }
}

/** A bullet or number in its own gutter, so wrapped lines stay aligned under the text. */
@Composable
private inline fun ListRow(marker: String, content: @Composable ColumnScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = marker,
            style = WellnessTheme.type.body,
            color = WellnessTheme.palette.textFaint,
            modifier = Modifier.width(MARKER_GUTTER),
        )
        Column(content = content)
    }
}

/**
 * A GFM table.
 *
 * Fixed-width cells inside one horizontal scroller: a report table is data, and
 * columns that shift width row to row are unreadable. The scroller is the
 * `table-wrap` the PWA used, for the same reason — a wide table must not force
 * the page itself sideways.
 */
@Composable
private fun ReportTable(table: ReportBlock.Table) {
    val palette = WellnessTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WellnessShape.card)
            .background(palette.card)
            .horizontalScroll(rememberScrollState()),
    ) {
        if (table.header.isNotEmpty()) {
            Row(modifier = Modifier.background(palette.band)) {
                table.header.forEach { cell ->
                    Cell(cell, style = WellnessTheme.type.label, color = palette.textSecondary)
                }
            }
        }
        table.rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Cell(cell, style = WellnessTheme.type.secondary, color = palette.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun Cell(inlines: List<ReportInline>, style: TextStyle, color: Color) {
    Text(
        text = inlineText(inlines),
        style = style,
        color = color,
        modifier = Modifier
            .width(CELL_WIDTH)
            .padding(horizontal = WellnessSpace.sm, vertical = CELL_PADDING),
    )
}

/**
 * Inlines, flattened into one styled string.
 *
 * A link renders as `label (destination)` in the quiet tone: inert, because a
 * report is a document rather than a page, but never stripped — a citation with
 * its URL removed is not a citation.
 */
@Composable
private fun inlineText(inlines: List<ReportInline>): AnnotatedString {
    val palette = WellnessTheme.palette
    return buildAnnotatedString { appendInlines(inlines, palette.textSecondary, palette.band) }
}

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<ReportInline>,
    quiet: Color,
    codeBackground: Color,
) {
    inlines.forEach { inline ->
        when (inline) {
            is ReportInline.Text -> append(inline.text)
            is ReportInline.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
            ) { append(inline.text) }
            is ReportInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight(STRONG_WEIGHT))) {
                appendInlines(inline.children, quiet, codeBackground)
            }
            is ReportInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlines(inline.children, quiet, codeBackground)
            }
            is ReportInline.Link -> {
                appendInlines(inline.children, quiet, codeBackground)
                if (inline.destination.isNotBlank()) {
                    withStyle(SpanStyle(color = quiet)) { append(" (${inline.destination})") }
                }
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int) = when (level) {
    1 -> WellnessTheme.type.headline
    2 -> WellnessTheme.type.title
    else -> WellnessTheme.type.title.copy(fontSize = SUBHEADING_SIZE)
}

private const val STRONG_WEIGHT = 650
private val MARKER_GUTTER = 20.dp
private val CELL_WIDTH = 140.dp
private val CELL_PADDING = 6.dp
private val CODE_SIZE = 13.sp
private val SUBHEADING_SIZE = 15.sp
