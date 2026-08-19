package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A verdict about one thing, drawn.
 *
 * The week-mark grammar ([WeekMark]) turned outward onto content: the same
 * fill/half/hollow modifiers, saying the same three things, in the tone content
 * needs. A journal row's marks report on *days* and recede accordingly; these
 * report on a line of a report or a row of a history list, sit inside running
 * text, and are read at the weight of the words around them — so [ATTENTION] is
 * ink where a day's open dot is faint, and [PENDING] is the faint one.
 *
 * There is no red here and no green. A failure is an open mark and the system's
 * mono `!`; Logbook owns no semantic colour to reach for, which is the point.
 */
enum class InkJudgment {
    /** Filled ink dot: it happened, and it counted. */
    SETTLED,

    /** Half-filled dot: part of the way, or worth an eye. */
    PARTIAL,

    /** Open ink dot: something needs doing. Carries the mono bang beside it. */
    ATTENTION,

    /** Open faint dot: no verdict has been reached yet. */
    PENDING,
}

/** One [InkJudgment], drawn at [markSize]. */
@Composable
fun InkJudgmentGlyph(
    judgment: InkJudgment,
    modifier: Modifier = Modifier,
    markSize: Dp = JUDGMENT_MARK_SIZE,
) {
    val palette = LogbookTheme.palette
    Canvas(modifier = modifier.size(markSize)) { drawInkJudgment(judgment, palette) }
}

/**
 * The same three shapes, for a caller that owns its own canvas — the report
 * renderer holds a blank in the running text and draws the mark into it.
 */
fun DrawScope.drawInkJudgment(judgment: InkJudgment, palette: LogbookPalette) {
    val hairline = JUDGMENT_STROKE.toPx()
    val radius = size.minDimension / 2f
    when (judgment) {
        InkJudgment.SETTLED -> drawCircle(color = palette.ink)

        // The left half fills and the outline closes the circle, so the missing
        // half reads as unfinished rather than as a smaller dot.
        InkJudgment.PARTIAL -> {
            clipRect(right = center.x) { drawCircle(color = palette.ink) }
            drawCircle(
                color = palette.ink,
                radius = radius - hairline / 2f,
                style = Stroke(width = hairline),
            )
        }

        InkJudgment.ATTENTION -> drawCircle(
            color = palette.ink,
            radius = radius - hairline / 2f,
            style = Stroke(width = hairline),
        )

        InkJudgment.PENDING -> drawCircle(
            color = palette.inkFaint,
            radius = radius - hairline / 2f,
            style = Stroke(width = hairline),
        )
    }
}

/**
 * The system's alarm: a mono bang, and never a colour.
 *
 * Kept beside the marks it accompanies so the two cannot drift — a bang set in
 * the body face beside a drawn mark reads as a typo.
 */
const val INK_BANG = "!"

/**
 * A line the reader has to notice: the bang in its own gutter, the sentence in
 * ink beside it.
 *
 * This is what Logbook says instead of turning text red. The gutter means a
 * notice and an ordinary paragraph start at the same left edge, so a screen that
 * gains one does not appear to shift; the bang itself is dropped from the
 * spoken node, since "exclamation mark, export failed" is not what the sentence
 * means.
 */
@Composable
fun InkNotice(text: String, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = INK_BANG,
            style = LogbookTheme.type.data.copy(fontWeight = FontWeight.Medium),
            color = palette.ink,
            modifier = Modifier
                .width(BANG_COLUMN)
                .clearAndSetSemantics { },
        )
        Text(text = text, style = LogbookTheme.type.body, color = palette.ink)
    }
}

/** A shade larger than a journal row's mark: these sit beside prose, not in a run of seven. */
val JUDGMENT_MARK_SIZE = 9.dp

private val JUDGMENT_STROKE = 1.4.dp

/** Wide enough for the bang plus the space a sentence would have after it. */
private val BANG_COLUMN = 14.dp
