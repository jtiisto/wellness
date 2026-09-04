package dev.jtiisto.wellness.feature.trends.chart

import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.trends.SleepDebtDay
import dev.jtiisto.wellness.core.data.trends.SleepTonight
import dev.jtiisto.wellness.core.data.trends.hoursMinutes
import kotlin.math.max
import kotlin.math.min

/**
 * The sleep-need history panel: what each night asked for, what it got, and the
 * debt it left behind.
 *
 * The debt series is the debt **on waking** — the server emits each night's own
 * product, not the balance it started with — so the last point is the number the
 * tonight card is showing, and the point beside a night explains the *next*
 * night's need rather than its own.
 *
 * Its own file rather than another section of `HealthModels.kt`, which already
 * carries six cards; nothing here is shared with them beyond the geometry
 * primitives every builder uses.
 *
 * Two charts, one x axis. They are built against the same scale on purpose:
 * hours and debt are read against the same nights, and a second domain would
 * let the two disagree about where a Tuesday is. It also means one anchor list
 * serves both, so a scrub reads the same date on either.
 */

private val SLEEP_DEBT_MARGINS = Margins(top = 10.0, right = 10.0, bottom = 22.0, left = 40.0)

private const val NEED_HEIGHT = 180.0
private const val DEBT_HEIGHT = 120.0

/**
 * Shared with `sleepCardModel`'s axis, and shared for its reason: an axis that
 * refits itself to a bad week draws the bad week full-height. The two cards sit
 * on the same screen, so a night has to be the same size on both.
 */
private const val SLEEP_Y_FLOOR_HOURS = 9.0

/**
 * The debt axis never collapses below an hour.
 *
 * A stretch with no debt at all is the *good* outcome, and without a floor its
 * flat zero line would be scaled to fill the plot — the best fortnight of the
 * year drawn exactly like the worst.
 */
private const val DEBT_Y_FLOOR_HOURS = 1.0

private const val SLEPT_KEY = "h"

data class SleepDebtSectionModel(
    val needPlot: PlotModel,
    val needLegend: List<LegendEntry>,
    /** Null under two nights: a debt of one point is a dot, not a history. */
    val debtPlot: PlotModel?,
    val debtLegend: List<LegendEntry>,
    /** Tonight's need, for the section head. Null when the payload has none. */
    val latest: String?,
)

/**
 * Build the panel, or null when there is nothing in the window.
 *
 * Unlike the recovery cards there is no present/absent split to make: every row
 * the server emits carries all four numbers, so the frame is the whole list and
 * the geometry is built from it directly rather than through `dayChart`.
 */
fun sleepDebtSection(days: List<SleepDebtDay>, tonight: SleepTonight?): SleepDebtSectionModel? {
    if (days.isEmpty()) return null

    val origin = days.first().date
    val offsets = days.map { dayIndex(it.date, origin).toDouble() }
    val xMin = offsets.min()
    val xMax = offsets.max()

    val sleptHours = days.map { it.sleptMin / MINUTES_PER_HOUR }
    val needHours = days.map { it.needMin / MINUTES_PER_HOUR }
    val yMax = max(max(sleptHours.max(), needHours.max()), SLEEP_Y_FLOOR_HOURS) * Y_HEADROOM

    // Half a slot of padding at each end, so the first and last bars are not
    // sliced by the plot edge — the same domain `sleepCardModel` uses.
    val xScale = linearScale(
        xMin - 0.5,
        xMax + 0.5,
        SLEEP_DEBT_MARGINS.left,
        LOGICAL_WIDTH - SLEEP_DEBT_MARGINS.right,
    )
    val yScale = linearScale(
        0.0,
        yMax,
        NEED_HEIGHT - SLEEP_DEBT_MARGINS.bottom,
        SLEEP_DEBT_MARGINS.top,
    )
    val barWidth = max(
        1.5,
        min(
            10.0,
            ((LOGICAL_WIDTH - SLEEP_DEBT_MARGINS.left - SLEEP_DEBT_MARGINS.right) /
                (xMax - xMin + 1)) * 0.7,
        ),
    )

    // The index-based xScale contract, exactly as the sleep card spends it:
    // these are daily bars, so the closure maps slot number to day offset to x.
    val layout = stackedBarLayout(
        days.map { StackedWeek(it.date, mapOf(SLEPT_KEY to (it.sleptMin / MINUTES_PER_HOUR))) },
        listOf(SLEPT_KEY),
        { index -> xScale(dayIndex(days[index].date, origin).toDouble()) },
        yScale,
        barWidth,
    )

    val needPoints = seriesToPoints(
        days,
        { dayIndex(it.date, origin).toDouble() },
        { it.needMin / MINUTES_PER_HOUR },
        xScale,
        yScale,
    )

    val (gridlines, yLabels) = yAxisMarks(
        yMin = 0.0,
        yMax = yMax,
        yScale = yScale,
        x0 = SLEEP_DEBT_MARGINS.left,
        x1 = LOGICAL_WIDTH - SLEEP_DEBT_MARGINS.right,
    )
    val ticks = dateTicks(days, { it.date }, origin)
    val anchors = sleepDebtAnchors(days, origin, xScale)

    return SleepDebtSectionModel(
        needPlot = PlotModel(
            height = NEED_HEIGHT,
            rects = layout.flatMap { column ->
                column.segs.map { PlotRect(it.x, it.y, it.w, it.h, PlotTone.PRIMARY, radius = 1.dp) }
            },
            gridlines = gridlines,
            // No eight-hour guide here, unlike the sleep card: the need line IS
            // the guide, and a second horizontal reference would invite reading
            // the personal target against a generic one.
            lines = if (needPoints.size > 1) listOf(PlotLine(needPoints, PlotTone.SECONDARY)) else emptyList(),
            labels = yLabels + xAxisLabels(ticks, xScale, NEED_HEIGHT),
            anchors = anchors,
        ),
        needLegend = NEED_LEGEND,
        debtPlot = debtPlot(days, origin, xScale, ticks, anchors),
        debtLegend = DEBT_LEGEND,
        latest = tonight?.let { "need ${hoursMinutes(it.needMin)}" },
    )
}

/**
 * One anchor per wake date, carrying that night's durations in `h:mm`.
 *
 * `h:mm` and not decimal hours: the tooltip's job is to answer "how much
 * short?", and 0.18 h is not an answer anyone acts on. [hoursMinutes] comes
 * from `:core:data` — the same function that formats the headline card — so the
 * panel and the number above it cannot round differently.
 */
private fun sleepDebtAnchors(
    days: List<SleepDebtDay>,
    origin: String,
    xScale: (Double) -> Double,
): List<ScrubAnchor> = mergeScrubAnchors(
    days.map { day ->
        AnchorContribution(
            key = day.date,
            x = xScale(dayIndex(day.date, origin).toDouble()),
            label = monthDay(day.date),
            rows = buildList {
                add(TooltipRow("slept", hoursMinutes(day.sleptMin)))
                add(TooltipRow("need", hoursMinutes(day.needMin)))
                // "woke with", not "debt": the row is what this night LEFT,
                // and a bare label would be read as what it was carrying.
                add(TooltipRow("woke with", hoursMinutes(day.debtMin)))
                // The need plotted above is the one the nap already paid down
                // — the server subtracted it before shipping the row — so
                // without the credit named, the dip in the line has no visible
                // cause. Named before the reset for the reason the tonight card
                // orders them that way: this qualifies the numbers above it,
                // the reset qualifies the whole ledger.
                if (day.napMin > 0) add(TooltipRow("nap", "$MINUS_SIGN${hoursMinutes(day.napMin)}"))
                // Why the need beside it carries no debt: the ledger had
                // nothing to carry INTO this night. Without the row a reset
                // reads as an unexplained easy target.
                if (day.gap) add(TooltipRow("reset", "missing night"))
            },
        )
    },
)

/**
 * The debt on waking, broken wherever the ledger restarted.
 *
 * A gap row is a night the watch recorded nothing before, so the run of nights
 * ending at the previous point and the run starting here are not one chain: the
 * ledger was cleared between them by a night nobody observed. A segment drawn
 * across that break would draw a trend through a night that has no datum — so
 * each continuous run gets its own polyline and the gap day carries an open ring
 * instead. (The gap row's own value is a real debt like any other, and is
 * plotted; only the connection to what came before is withheld.)
 */
private fun debtPlot(
    days: List<SleepDebtDay>,
    origin: String,
    xScale: (Double) -> Double,
    ticks: List<DateTick>,
    anchors: List<ScrubAnchor>,
): PlotModel? {
    if (days.size < 2) return null

    val debtHours = days.map { it.debtMin / MINUTES_PER_HOUR }
    val yMax = max(debtHours.max(), DEBT_Y_FLOOR_HOURS) * Y_HEADROOM
    val yScale = linearScale(
        0.0,
        yMax,
        DEBT_HEIGHT - SLEEP_DEBT_MARGINS.bottom,
        SLEEP_DEBT_MARGINS.top,
    )

    fun point(day: SleepDebtDay) = ChartPoint(
        x = xScale(dayIndex(day.date, origin).toDouble()),
        y = yScale(day.debtMin / MINUTES_PER_HOUR),
        raw = Unit,
    )

    val lines = continuousRuns(days)
        .filter { it.size > 1 }
        .map { run -> PlotLine(run.map(::point), PlotTone.SCAN) }

    val (gridlines, yLabels) = yAxisMarks(
        yMin = 0.0,
        yMax = yMax,
        yScale = yScale,
        x0 = SLEEP_DEBT_MARGINS.left,
        x1 = LOGICAL_WIDTH - SLEEP_DEBT_MARGINS.right,
    )

    return PlotModel(
        height = DEBT_HEIGHT,
        gridlines = gridlines,
        lines = lines,
        dots = days.filter { it.gap }.map { day ->
            val plotted = point(day)
            PlotDot(x = plotted.x, y = plotted.y, radius = 3.dp, tone = PlotTone.WARN)
        },
        labels = yLabels + xAxisLabels(ticks, xScale, DEBT_HEIGHT),
        anchors = anchors,
    )
}

/**
 * Split the nights into runs the ledger carried through without restarting.
 *
 * A gap row **begins** the run it belongs to rather than ending the previous
 * one: the unobserved night sits immediately BEFORE it, so it is the first
 * point of what comes after, not the last point of what came before.
 */
private fun continuousRuns(days: List<SleepDebtDay>): List<List<SleepDebtDay>> {
    val runs = mutableListOf<MutableList<SleepDebtDay>>()
    for (day in days) {
        if (runs.isEmpty() || day.gap) runs.add(mutableListOf())
        runs.last() += day
    }
    return runs
}

private val NEED_LEGEND = listOf(
    LegendEntry("slept", PlotTone.PRIMARY),
    LegendEntry("need", PlotTone.SECONDARY),
)

private val DEBT_LEGEND = listOf(
    LegendEntry("debt on waking", PlotTone.SCAN),
    LegendEntry("reset", PlotTone.WARN),
)

private const val MINUTES_PER_HOUR = 60.0

/**
 * U+2212 MINUS SIGN, as the tonight card spells it — a hyphen would read as a
 * range dash beside a duration, and the two are indistinguishable in a diff.
 */
private const val MINUS_SIGN = "\u2212"

/** The 5% the tallest bar is kept clear of the top gridline by. */
private const val Y_HEADROOM = 1.05
