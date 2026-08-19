package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * The mark language: nine shapes, no colour, one canvas.
 *
 * Written for the journal's seven-day runs and rollup clusters, and hoisted into
 * `core/ui` when Trends' focus ribbons needed the *same* vocabulary — features
 * never depend on each other, and a second copy of a shape grammar is how a
 * language starts to drift. It lives beside [InkMark] and the [InkButton] pair
 * for the same reason: these are the design system's furniture, not one screen's.
 *
 * Which shape a day earns is decided by the consuming feature (journal's
 * `JournalNotation`, trends' `TrendsNotation`) — this only picks tokens and puts
 * geometry on the page.
 *
 * The shapes are chosen so that the *modifiers* mean the same thing wherever they
 * appear: a slash is "broken through", a hollow is "nothing here yet", a fill is
 * "this happened". That is what lets a legend name seven marks and leave the
 * reader able to read all nine.
 */
enum class WeekMark {
    FILLED_DOT,
    HALF_DOT,
    OPEN_DOT,
    SLASHED_DOT,
    DASH,
    HOOP,
    SLASHED_HOOP,
    FILLED_DIAMOND,
    OUTLINE_DIAMOND,
}

/**
 * The word for a drawn mark, so a screen reader hears what the page shows.
 *
 * [WeekMark.OPEN_DOT] says "open", not "missed": the whole point of the
 * today-open rule is that those are different statements, and a spoken twin that
 * collapsed them would undo the rule for exactly the users who cannot see the
 * difference drawn.
 */
fun WeekMark.a11yLabel(): String = when (this) {
    WeekMark.FILLED_DOT -> "done"
    WeekMark.HALF_DOT -> "partial"
    WeekMark.OPEN_DOT -> "open"
    WeekMark.SLASHED_DOT -> "missed"
    WeekMark.DASH -> "off schedule"
    WeekMark.HOOP -> "held"
    WeekMark.SLASHED_HOOP -> "broken"
    WeekMark.FILLED_DIAMOND -> "noted"
    WeekMark.OUTLINE_DIAMOND -> "not noted"
}

/**
 * One [WeekMark], drawn.
 *
 * [ringed] is the strip's "you are here", drawn as an offset ring **outside** the
 * mark's own bounds rather than as a bigger mark — a run whose last dot were
 * larger than the other six would read as a different kind of day rather than as
 * the selected one. Nothing clips it: the ring lands in the gap the run already
 * leaves between marks.
 */
@Composable
fun WeekMarkGlyph(
    mark: WeekMark,
    modifier: Modifier = Modifier,
    ringed: Boolean = false,
    markSize: Dp = ROW_MARK_SIZE,
) {
    val palette = LogbookTheme.palette
    Canvas(modifier = modifier.size(markSize)) {
        drawWeekMark(mark, palette)
        if (ringed) {
            drawCircle(
                color = palette.ink,
                radius = size.minDimension / 2f + RING_OFFSET.toPx(),
                style = Stroke(width = RING_STROKE.toPx()),
            )
        }
    }
}

private fun DrawScope.drawWeekMark(mark: WeekMark, palette: LogbookPalette) {
    val hairline = OUTLINE_STROKE.toPx()
    val radius = size.minDimension / 2f
    when (mark) {
        // Something happened, and it counted.
        WeekMark.FILLED_DOT -> drawCircle(color = palette.ink)

        // Half the target: the left half fills, the outline closes the circle so
        // the missing half reads as unfinished rather than as a smaller dot.
        WeekMark.HALF_DOT -> {
            clipRect(right = center.x) { drawCircle(color = palette.ink) }
            drawCircle(
                color = palette.ink,
                radius = radius - hairline / 2f,
                style = Stroke(width = hairline),
            )
        }

        // No verdict exists. Faint, hollow, and deliberately not the slashed dot.
        WeekMark.OPEN_DOT -> drawCircle(
            color = palette.inkFaint,
            radius = radius - hairline / 2f,
            style = Stroke(width = hairline),
        )

        // A verdict that went the other way — struck through, never alarmed.
        WeekMark.SLASHED_DOT -> {
            drawCircle(
                color = palette.inkFaint,
                radius = radius - hairline / 2f,
                style = Stroke(width = hairline),
            )
            drawSlash(palette.inkSoft, hairline)
        }

        // A non-day. A short rule, not a shape: nothing was asked, so nothing is
        // being reported.
        WeekMark.DASH -> {
            val half = size.minDimension * DASH_LENGTH_RATIO / 2f
            drawLine(
                color = palette.ruleStrong,
                start = Offset(center.x - half, center.y),
                end = Offset(center.x + half, center.y),
                strokeWidth = hairline,
                cap = StrokeCap.Butt,
            )
        }

        // Restraint held: a ring around an empty middle, with a point to say the
        // day was accounted for. Never the filled dot — "did not drink" is not
        // an achievement in the same shape as "went for a walk".
        WeekMark.HOOP -> {
            val stroke = HOOP_STROKE.toPx()
            drawCircle(
                color = palette.inkSoft,
                radius = radius - stroke / 2f,
                style = Stroke(width = stroke),
            )
            drawCircle(color = palette.inkSoft, radius = HOOP_PIP_RADIUS.toPx())
        }

        // The hoop, broken. The same slash as the missed dot, because it is the
        // same event: a day that went the other way.
        WeekMark.SLASHED_HOOP -> {
            val stroke = HOOP_STROKE.toPx()
            drawCircle(
                color = palette.ink,
                radius = radius - stroke / 2f,
                style = Stroke(width = stroke),
            )
            drawSlash(palette.ink, hairline)
        }

        // Noticed. A diamond rather than a dot, because nobody judged it.
        WeekMark.FILLED_DIAMOND -> drawDiamond(palette.ink, filled = true, stroke = hairline)

        WeekMark.OUTLINE_DIAMOND -> drawDiamond(palette.inkFaint, filled = false, stroke = hairline)
    }
}

/**
 * The strike: a rule through the mark at 45°, overhanging both sides.
 *
 * Drawn rather than typed. A `/` glyph would sit on the text baseline at
 * whatever weight the face happens to have, next to eight marks that are
 * geometry — and it would be the only part of the vocabulary that changed size
 * with the user's font settings.
 */
private fun DrawScope.drawSlash(color: Color, stroke: Float) {
    val half = size.minDimension / 2f + SLASH_OVERHANG.toPx()
    // Up and to the right: screen y grows downward, so the rise is negative.
    val step = half / sqrt(2f)
    drawLine(
        color = color,
        start = Offset(center.x - step, center.y + step),
        end = Offset(center.x + step, center.y - step),
        strokeWidth = stroke,
        cap = StrokeCap.Butt,
    )
}

/**
 * The observation mark: a square stood on its corner.
 *
 * The footprint is measured point to point, so the square inside it is its
 * diagonal's worth smaller — and an outline's stroke straddles that square, so
 * it comes off the side too. Both states then occupy the same box, which is what
 * keeps a run of diamonds on one rhythm with a run of dots.
 */
private fun DrawScope.drawDiamond(color: Color, filled: Boolean, stroke: Float) {
    val diagonal = size.minDimension * DIAMOND_RATIO
    val side = diagonal / sqrt(2f) - if (filled) 0f else stroke
    val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
    rotate(DIAMOND_ROTATION) {
        drawRoundRect(
            color = color,
            topLeft = topLeft,
            size = Size(side, side),
            cornerRadius = CornerRadius(DIAMOND_CORNER.toPx()),
            style = if (filled) Fill else Stroke(stroke),
        )
    }
}

/** A row's run. The rollup cluster is a size up — one language, two voices. */
val ROW_MARK_SIZE = 8.dp
val ROLLUP_MARK_SIZE = 9.dp

private const val DIAMOND_ROTATION = 45f
private const val DIAMOND_RATIO = 0.875f
private const val DASH_LENGTH_RATIO = 0.75f

private val DIAMOND_CORNER = 1.dp

private val OUTLINE_STROKE = 1.2.dp
private val HOOP_STROKE = 1.5.dp
private val HOOP_PIP_RADIUS = 1.25.dp
private val SLASH_OVERHANG = 2.dp
private val RING_OFFSET = 2.dp
private val RING_STROKE = 1.2.dp
