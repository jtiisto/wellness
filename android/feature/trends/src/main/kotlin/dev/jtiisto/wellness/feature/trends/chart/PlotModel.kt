package dev.jtiisto.wellness.feature.trends.chart

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one description every Trends chart reduces to, and the transform that
 * puts it on screen.
 *
 * Fourteen cards share a single Canvas because the drawing is the same drawing:
 * rectangles, polylines, dots, gridlines, a few labels. What differs between an
 * HRV chart and a volume chart is entirely *which* of those a builder emits —
 * so the builders are pure functions with tests, and the composable that draws
 * a [PlotModel] has no branches worth testing.
 *
 * Everything here is in logical 360-wide units. Nothing is a pixel until
 * [LogicalScale] is applied at the draw site.
 */

/** What a mark means, resolved to a colour by the theme at draw time. */
enum class PlotTone {
    /** The module accent: the headline series. */
    PRIMARY,

    /** The second full-strength series — a 28-day mean, an e1RM line. */
    ALT,

    /** Deliberately quiet: RPE, sleep score, the extra-minutes stack. */
    SECONDARY,

    /** DEXA scans and lab observations — a different *kind* of measurement. */
    SCAN,

    /** A plotted reading, as opposed to a derived line. */
    VALUE,

    /** Below the floor Garmin set, or carrying the lab's own H/L flag. */
    WARN,

    /** Off-plan, paused, or otherwise present but not counted. */
    MUTED,

    /** A target or reference band behind the series. */
    BAND,

    /** A plain bar with no stack identity. */
    BAR,

    MET,
    PARTIAL,
    MISSED,

    /** The week in progress, laid over whatever it covers. */
    IN_PROGRESS,

    STACK_0,
    STACK_1,
    STACK_2,
    STACK_OTHER,
}

/** Where a text label sits relative to its x. */
enum class LabelAlign { START, CENTER, END }

data class PlotRect(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val tone: PlotTone,
    /**
     * A corner, in **dp**. Ink does not scale with the chart: a 1.5dp round on
     * a bar is 1.5dp on every screen, or it stops being a hairline round.
     */
    val radius: Dp = 0.dp,
    /** An incomplete week, drawn back so it is not read against finished ones. */
    val partial: Boolean = false,
)

/**
 * A polyline. The points keep whatever payload their series carried — the
 * drawing only ever reads x and y, so nothing here has to be re-wrapped.
 */
data class PlotLine(val points: List<ChartPoint<*>>, val tone: PlotTone)

/**
 * A plotted point. [radius] is **dp**, not a logical unit — that is what stops
 * it from being multiplied by the width factor along with the coordinates, and
 * the type is what makes the mistake impossible rather than merely documented.
 */
data class PlotDot(
    val x: Double,
    val y: Double,
    val radius: Dp,
    val tone: PlotTone,
    val muted: Boolean = false,
)

/** A horizontal rule: a gridline, or the 8-hour sleep guide. */
data class PlotGuide(val y: Double, val x0: Double, val x1: Double, val dashed: Boolean = false)

data class PlotLabel(val x: Double, val y: Double, val text: String, val align: LabelAlign)

/** One row of a scrub tooltip. */
data class TooltipRow(val label: String, val value: String)

/**
 * One scrub position: a date or week, its x, and everything worth saying about
 * it. Exactly one anchor per key — [ChartScrubState] resolves ties to the
 * lowest index, so a duplicate x would strand every anchor but the first.
 */
data class ScrubAnchor(val key: String, val x: Double, val label: String, val rows: List<TooltipRow>)

/** One series' contribution to an anchor, before the merge. */
data class AnchorContribution(
    val key: String,
    val x: Double,
    val label: String,
    val rows: List<TooltipRow>,
)

/**
 * Fold every series' contributions into one anchor per key, in lexical key
 * order.
 *
 * Lexical is chronological here — keys are `YYYY-MM-DD` dates or week starts —
 * and it is also what makes the same-day case work: a weight reading and a DEXA
 * scan on one date become a single anchor carrying both rows, rather than two
 * anchors sitting on the same pixel.
 */
fun mergeScrubAnchors(contributions: List<AnchorContribution>): List<ScrubAnchor> {
    if (contributions.isEmpty()) return emptyList()
    val byKey = LinkedHashMap<String, MutableList<AnchorContribution>>()
    for (contribution in contributions) {
        byKey.getOrPut(contribution.key) { mutableListOf() } += contribution
    }
    return byKey.keys.sorted().map { key ->
        val group = byKey.getValue(key)
        ScrubAnchor(
            key = key,
            x = group.first().x,
            // Falling back to the key rather than throwing: a chart whose
            // series all forgot to label themselves should still scrub.
            label = group.firstOrNull { it.label.isNotEmpty() }?.label ?: key,
            rows = group.flatMap { it.rows },
        )
    }
}

/**
 * A chart, ready to draw.
 *
 * Draw order is the field order: bands and bars first, then guides, then lines,
 * then dots on top — the same layering the PWA's SVG relies on, expressed as
 * separate lists rather than as document order.
 */
data class PlotModel(
    val height: Double,
    val rects: List<PlotRect> = emptyList(),
    val gridlines: List<PlotGuide> = emptyList(),
    val guides: List<PlotGuide> = emptyList(),
    val lines: List<PlotLine> = emptyList(),
    val dots: List<PlotDot> = emptyList(),
    val labels: List<PlotLabel> = emptyList(),
    val anchors: List<ScrubAnchor> = emptyList(),
    /**
     * Put the dots *under* the lines instead of over them.
     *
     * Resting HR is the one card that wants this: its dots are a dense daily
     * scatter and the two rolling means are the thing being read, so the means
     * have to stay legible across it rather than being stippled by it.
     */
    val dotsBelowLines: Boolean = false,
)

/**
 * Logical 360-wide units → pixels.
 *
 * One factor for **positions and extents alike**: bar widths, band heights and
 * path vertices all scale, so the chart keeps its proportions at any width.
 * What does *not* scale is ink — stroke widths, dot radii, text sizes — which
 * come from the theme in dp and sp, because a hairline that grew with the
 * screen would stop being a hairline.
 */
data class LogicalScale(val widthPx: Float) {

    val factor: Float = (widthPx / LOGICAL_WIDTH).toFloat()

    fun px(logical: Double): Float = (logical * factor).toFloat()

    // Deliberately no overload for ink. Stroke widths, text sizes, dot radii
    // and corner rounds are Dp/Sp and resolve against density, never against
    // this factor — see CRITICAL 21.

    fun heightPx(logicalHeight: Double): Float = px(logicalHeight)

    /**
     * Anchor positions in pixels, which is the only thing [ChartScrubState]
     * ever sees — pointer input is compared against these directly and never
     * divided back into logical space.
     */
    fun anchorsPx(anchors: List<ScrubAnchor>): FloatArray =
        FloatArray(anchors.size) { px(anchors[it].x) }
}
