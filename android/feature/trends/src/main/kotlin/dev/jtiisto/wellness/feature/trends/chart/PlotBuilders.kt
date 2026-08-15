package dev.jtiisto.wellness.feature.trends.chart

import androidx.compose.ui.unit.dp

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The two reusable chart shapes — a sparse line chart and a stacked bar chart —
 * ported from `LineChart.js` and `BarChartStacked.js`.
 *
 * Both return null when there is nothing to plot, which is the caller's cue to
 * render the empty-state text instead. The bespoke cards do not go through
 * these: the PWA hand-rolls each of them with its own margins and padding
 * rules, and folding six near-identical-but-not-identical charts into one
 * parameterised builder would hide exactly the differences that matter.
 */

/** Plot margins, in logical units. */
data class Margins(val top: Double, val right: Double, val bottom: Double, val left: Double)

/** A point in domain space: an x in day-index (or scale-input) units. */
data class XYPoint(val x: Double, val y: Double, val muted: Boolean = false)

/** One decimal place, the way `Number.prototype.toFixed(1)` writes it. */
fun fixed1(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/** The y baseline an x-axis label sits on, converted to a vertical centre. */
private const val AXIS_LABEL_LIFT = 4.0

/** Gap between the plot's left edge and its right-aligned tick labels. */
private const val Y_LABEL_GAP = 4.0

/**
 * Gridlines and their labels for a y scale, with the duplicate-label rule
 * applied. The label sits just left of [x0], vertically centred on its line.
 */
fun yAxisMarks(
    yMin: Double,
    yMax: Double,
    yScale: (Double) -> Double,
    x0: Double,
    x1: Double,
    targetCount: Int = 4,
    format: (Double) -> String = ::formatNum,
): Pair<List<PlotGuide>, List<PlotLabel>> {
    val ticks = axisTicks(yMin, yMax, targetCount, format)
    val guides = ticks.map { PlotGuide(y = yScale(it.value), x0 = x0, x1 = x1) }
    val labels = ticks.mapNotNull { tick ->
        tick.label?.let {
            PlotLabel(x = x0 - Y_LABEL_GAP, y = yScale(tick.value), text = it, align = LabelAlign.END)
        }
    }
    return guides to labels
}

/** Centred date labels along the bottom of a plot of logical height [height]. */
fun xAxisLabels(ticks: List<DateTick>, xScale: (Double) -> Double, height: Double): List<PlotLabel> =
    ticks.map {
        PlotLabel(
            x = xScale(it.x),
            y = height - 6.0 - AXIS_LABEL_LIFT,
            text = it.label,
            align = LabelAlign.CENTER,
        )
    }

/**
 * The JS `a || b || c` fall-through, which treats **zero** as "try the next
 * one". A flat series has no spread to pad by, so it borrows a fraction of its
 * own magnitude, and a flat series *at zero* falls all the way through to a
 * literal.
 */
fun firstNonZero(vararg candidates: Double): Double =
    candidates.firstOrNull { it != 0.0 && !it.isNaN() } ?: candidates.last()

// ---- Line chart ------------------------------------------------------------

val LINE_CHART_MARGINS = Margins(top = 10.0, right = 34.0, bottom = 22.0, left = 40.0)

/**
 * Dots and a thin line, built for sparse series — fifteen to thirty points over
 * months.
 *
 * The optional [secondary] series rides a *fixed* right-hand domain rather than
 * its own: the whole point of the RPE overlay is that the same weight at a
 * falling RPE is progress, which a self-scaling axis would flatten away.
 *
 * The x domain spans the points **and** the first/last tick, so a tick can
 * never land outside the plot it labels.
 */
fun lineChartModel(
    primary: List<XYPoint>,
    alt: List<XYPoint> = emptyList(),
    secondary: List<XYPoint> = emptyList(),
    secondaryDomain: Pair<Double, Double> = 5.0 to 10.0,
    xTicks: List<DateTick> = emptyList(),
    height: Double = 220.0,
    margins: Margins = LINE_CHART_MARGINS,
    yFormat: (Double) -> String = ::formatNum,
    anchorsOf: (xScale: (Double) -> Double, yScale: (Double) -> Double) -> List<ScrubAnchor> =
        { _, _ -> emptyList() },
): PlotModel? {
    val all = primary + alt
    if (all.isEmpty()) return null

    val tickXs = if (xTicks.isEmpty()) emptyList() else listOf(xTicks.first().x, xTicks.last().x)
    val xMin = (all.map { it.x } + tickXs).min()
    val xMax = (all.map { it.x } + tickXs).max()
    val yMin = all.minOf { it.y }
    val yMax = all.maxOf { it.y }
    val pad = firstNonZero((yMax - yMin) * 0.08, yMax * 0.05, 1.0)

    val xScale = linearScale(xMin, xMax, margins.left, LOGICAL_WIDTH - margins.right)
    val yScale = linearScale(yMin - pad, yMax + pad, height - margins.bottom, margins.top)
    val secondaryScale = linearScale(
        secondaryDomain.first,
        secondaryDomain.second,
        height - margins.bottom,
        margins.top,
    )

    val (gridlines, yLabels) = yAxisMarks(
        yMin = yMin - pad,
        yMax = yMax + pad,
        yScale = yScale,
        x0 = margins.left,
        x1 = LOGICAL_WIDTH - margins.right,
        format = yFormat,
    )

    fun plot(points: List<XYPoint>) = points.map {
        ChartPoint(x = xScale(it.x), y = yScale(it.y), raw = Unit, muted = it.muted)
    }

    val primaryPlotted = plot(primary)
    val altPlotted = plot(alt)
    val secondaryPlotted = secondary.map {
        ChartPoint(x = xScale(it.x), y = secondaryScale(it.y), raw = Unit)
    }

    val lines = buildList {
        if (secondaryPlotted.size > 1) add(PlotLine(secondaryPlotted, PlotTone.SECONDARY))
        if (altPlotted.size > 1) add(PlotLine(altPlotted, PlotTone.ALT))
        if (primaryPlotted.size > 1) add(PlotLine(primaryPlotted, PlotTone.PRIMARY))
    }
    val dots = altPlotted.map { PlotDot(it.x, it.y, 2.5.dp, PlotTone.ALT, it.muted) } +
        primaryPlotted.map { PlotDot(it.x, it.y, 3.dp, PlotTone.PRIMARY, it.muted) }

    val secondaryLabels = if (secondaryPlotted.isEmpty()) {
        emptyList()
    } else {
        niceTicks(secondaryDomain.first, secondaryDomain.second, 3).map {
            PlotLabel(
                x = LOGICAL_WIDTH - margins.right + Y_LABEL_GAP,
                y = secondaryScale(it),
                text = formatNum(it),
                align = LabelAlign.START,
            )
        }
    }

    return PlotModel(
        height = height,
        gridlines = gridlines,
        lines = lines,
        dots = dots,
        labels = yLabels + secondaryLabels + xAxisLabels(xTicks, xScale, height),
        anchors = anchorsOf(xScale, yScale),
    )
}

// ---- Stacked bars ----------------------------------------------------------

val BAR_CHART_MARGINS = Margins(top = 10.0, right = 8.0, bottom = 22.0, left = 44.0)

val BAR_CORNER_RADIUS = 1.5.dp

/**
 * Weekly stacked bars.
 *
 * The y domain always reaches at least 1, so a range with nothing logged still
 * draws an axis rather than collapsing onto itself, and the 5% headroom keeps
 * the tallest bar off the top gridline.
 */
fun barChartModel(
    weeks: List<StackedWeek>,
    keys: List<String>,
    tones: Map<String, PlotTone>,
    partial: List<Boolean> = emptyList(),
    height: Double = 180.0,
    margins: Margins = BAR_CHART_MARGINS,
    yFormat: (Double) -> String = ::formatNum,
    tickFormat: (String) -> String = ::monthDay,
    anchorsOf: (xScale: (Int) -> Double, barWidth: Double) -> List<ScrubAnchor> = { _, _ -> emptyList() },
): PlotModel? {
    if (weeks.isEmpty()) return null

    val totals = weeks.map { week -> keys.sumOf { week.values[it] ?: 0.0 } }
    val yMax = max(totals.max(), 1.0)

    val xScale = linearScale(-0.5, weeks.size - 0.5, margins.left, LOGICAL_WIDTH - margins.right)
    val yScale = linearScale(0.0, yMax * 1.05, height - margins.bottom, margins.top)
    val barWidth = min(26.0, ((LOGICAL_WIDTH - margins.left - margins.right) / weeks.size) * 0.72)

    val indexScale: (Int) -> Double = { xScale(it.toDouble()) }
    val layout = stackedBarLayout(weeks, keys, indexScale, yScale, barWidth)

    val rects = layout.flatMapIndexed { index, column ->
        column.segs.map { segment ->
            PlotRect(
                x = segment.x,
                y = segment.y,
                w = segment.w,
                h = segment.h,
                tone = tones[segment.key] ?: PlotTone.BAR,
                radius = BAR_CORNER_RADIUS,
                partial = partial.getOrElse(index) { false },
            )
        }
    }

    val (gridlines, yLabels) = yAxisMarks(
        yMin = 0.0,
        yMax = yMax * 1.05,
        yScale = yScale,
        x0 = margins.left,
        x1 = LOGICAL_WIDTH - margins.right,
        format = yFormat,
    )
    val xLabels = spread(weeks.size, 5).map { index ->
        PlotLabel(
            x = indexScale(index),
            y = height - 6.0 - AXIS_LABEL_LIFT,
            text = tickFormat(weeks[index].weekStart),
            align = LabelAlign.CENTER,
        )
    }

    return PlotModel(
        height = height,
        rects = rects,
        gridlines = gridlines,
        labels = yLabels + xLabels,
        anchors = anchorsOf(indexScale, barWidth),
    )
}
