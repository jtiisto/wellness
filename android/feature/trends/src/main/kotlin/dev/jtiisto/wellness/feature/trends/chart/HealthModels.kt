package dev.jtiisto.wellness.feature.trends.chart

import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.trends.LabPanel
import dev.jtiisto.wellness.core.data.trends.RecoveryDay
import dev.jtiisto.wellness.core.data.trends.Scan
import dev.jtiisto.wellness.core.data.trends.WeightPoint
import kotlin.math.max
import kotlin.math.min

/** The Health screen's cards, as data. */

private val RECOVERY_MARGINS = Margins(top = 10.0, right = 10.0, bottom = 22.0, left = 40.0)

const val NO_HRV_TEXT = "No HRV data in range"
const val NO_RHR_TEXT = "No RHR data in range"
const val NO_SLEEP_TEXT = "No sleep data in range"

/**
 * The days a scrub can land on: every day inside the plotted x range that has
 * something drawn above it, whichever series put it there.
 *
 * A rolling mean outlives the readings it is made of, and Garmin's baseline
 * outlives both, so a night the watch was off can still carry a visible mark —
 * and the finger has to find it rather than snapping to a neighbour. Days
 * *outside* the domain are excluded in the same breath: their marks are off the
 * canvas, and an anchor at a negative x would quietly capture every scrub near
 * the left edge.
 */
private fun <T> anchorDays(
    frame: DayChartFrame<T>,
    days: List<T>,
    dateOf: (T) -> DateString,
): List<T> = days.filter {
    val offset = dayIndex(dateOf(it), frame.origin).toDouble()
    offset >= frame.xMin && offset <= frame.xMax
}

// ---- HRV -------------------------------------------------------------------

private const val HRV_HEIGHT = 200.0

/**
 * Last night's HRV against Garmin's own baseline band.
 *
 * The band is never recomputed here — it is the watch's balanced range, and a
 * client-side reimplementation would disagree with the watch on the mornings
 * that matter. A dot goes to the warning tone only below `low_floor`, the
 * ceiling of Garmin's own low zone.
 */
fun hrvCardModel(days: List<RecoveryDay>): PlotModel? {
    val frame = dayChart(days, { it.date }, { it.hrv }) ?: return null

    val bandValues = days.flatMap { day ->
        day.hrvBand?.let { listOfNotNull(it.low, it.high, it.lowFloor) } ?: emptyList()
    }
    val values = frame.present.mapNotNull { it.hrv }
    val yMin = (values + bandValues).min()
    val yMax = (values + bandValues).max()
    val pad = firstNonZero((yMax - yMin) * 0.1, 1.0)

    val xScale = linearScale(
        frame.xMin,
        frame.xMax + 1,
        RECOVERY_MARGINS.left,
        LOGICAL_WIDTH - RECOVERY_MARGINS.right,
    )
    val yScale = linearScale(
        yMin - pad,
        yMax + pad,
        HRV_HEIGHT - RECOVERY_MARGINS.bottom,
        RECOVERY_MARGINS.top,
    )

    val segments = dailyBandSegments(
        days,
        { dayIndex(it.date, frame.origin).toDouble() },
        { day -> day.hrvBand?.let { BandBounds(it.low, it.high) } },
    )
    val rects = steppedBandRects(
        segments,
        frame.xMin,
        frame.xMax + 1,
        xScale,
        yScale,
        RECOVERY_MARGINS.top,
        HRV_HEIGHT - RECOVERY_MARGINS.bottom,
    ).map { PlotRect(it.x, it.yTop, it.w, it.yBot - it.yTop, PlotTone.BAND) }

    val dots = seriesToPoints(
        frame.present,
        { dayIndex(it.date, frame.origin).toDouble() },
        { it.hrv },
        xScale,
        yScale,
    )
    val mean = rollingMean(days.map { DatedValue(it.date, it.hrv) }, 7)
    val meanByDate = mean.associate { it.date to it.value }
    val (gridlines, yLabels) = yAxisMarks(
        yMin = yMin - pad,
        yMax = yMax + pad,
        yScale = yScale,
        x0 = RECOVERY_MARGINS.left,
        x1 = LOGICAL_WIDTH - RECOVERY_MARGINS.right,
    )
    val ticks = dateTicks(frame.present, { it.date }, frame.origin)

    return PlotModel(
        height = HRV_HEIGHT,
        rects = rects,
        gridlines = gridlines,
        lines = listOfNotNull(meanLine(mean, frame.origin, xScale, yScale, PlotTone.PRIMARY)),
        dots = dots.map { point ->
            PlotDot(
                x = point.x,
                y = point.y,
                radius = 2.5.dp,
                tone = if (belowFloor(point.raw)) PlotTone.WARN else PlotTone.VALUE,
            )
        },
        labels = yLabels + xAxisLabels(ticks, xScale, HRV_HEIGHT),
        anchors = mergeScrubAnchors(
            anchorDays(frame, days) { it.date }.mapNotNull { day ->
                val rows = buildList {
                    day.hrv?.let { add(TooltipRow("hrv", "${jsNumberString(it)} ms")) }
                    meanByDate[day.date]?.let { add(TooltipRow("7d", fixed1(it))) }
                    bandRow(day)?.let(::add)
                }
                if (rows.isEmpty()) return@mapNotNull null
                AnchorContribution(
                    key = day.date,
                    x = xScale(dayIndex(day.date, frame.origin).toDouble()),
                    label = monthDay(day.date),
                    rows = rows,
                )
            },
        ),
    )
}

private fun belowFloor(day: RecoveryDay): Boolean {
    val floor = day.hrvBand?.lowFloor ?: return false
    val hrv = day.hrv ?: return false
    return hrv < floor
}

/** The baseline as a row: a range, a bound, or nothing at all. */
private fun bandRow(day: RecoveryDay): TooltipRow? {
    val band = day.hrvBand ?: return null
    return TooltipRow("baseline", "${jsNumberString(band.low)}–${jsNumberString(band.high)}")
}

// ---- Resting HR ------------------------------------------------------------

private const val RHR_HEIGHT = 180.0

/**
 * Resting heart rate with both rolling means.
 *
 * The x domain is *not* extended by a day the way HRV's is: there is no band
 * here needing width past the last reading, and the extra day would only push
 * the series off the right edge.
 */
fun rhrCardModel(days: List<RecoveryDay>): PlotModel? {
    val frame = dayChart(days, { it.date }, { it.rhr }) ?: return null

    val values = frame.present.mapNotNull { it.rhr }
    val yMin = values.min()
    val yMax = values.max()
    val pad = firstNonZero((yMax - yMin) * 0.1, 1.0)

    val xScale = linearScale(
        frame.xMin,
        frame.xMax,
        RECOVERY_MARGINS.left,
        LOGICAL_WIDTH - RECOVERY_MARGINS.right,
    )
    val yScale = linearScale(
        yMin - pad,
        yMax + pad,
        RHR_HEIGHT - RECOVERY_MARGINS.bottom,
        RECOVERY_MARGINS.top,
    )

    val dated = days.map { DatedValue(it.date, it.rhr) }
    val mean7 = rollingMean(dated, 7)
    val mean28 = rollingMean(dated, 28)
    val byDate7 = mean7.associate { it.date to it.value }
    val byDate28 = mean28.associate { it.date to it.value }

    val dots = seriesToPoints(
        frame.present,
        { dayIndex(it.date, frame.origin).toDouble() },
        { it.rhr },
        xScale,
        yScale,
    )
    val (gridlines, yLabels) = yAxisMarks(
        yMin = yMin - pad,
        yMax = yMax + pad,
        yScale = yScale,
        x0 = RECOVERY_MARGINS.left,
        x1 = LOGICAL_WIDTH - RECOVERY_MARGINS.right,
    )
    val ticks = dateTicks(frame.present, { it.date }, frame.origin)

    return PlotModel(
        height = RHR_HEIGHT,
        gridlines = gridlines,
        lines = listOfNotNull(
            meanLine(mean7, frame.origin, xScale, yScale, PlotTone.PRIMARY),
            meanLine(mean28, frame.origin, xScale, yScale, PlotTone.ALT),
        ),
        dots = dots.map { PlotDot(it.x, it.y, 2.dp, PlotTone.VALUE) },
        dotsBelowLines = true,
        labels = yLabels + xAxisLabels(ticks, xScale, RHR_HEIGHT),
        anchors = mergeScrubAnchors(
            anchorDays(frame, days) { it.date }.mapNotNull { day ->
                val rows = buildList {
                    day.rhr?.let { add(TooltipRow("rhr", "${jsNumberString(it)} bpm")) }
                    byDate7[day.date]?.let { add(TooltipRow("7d", fixed1(it))) }
                    byDate28[day.date]?.let { add(TooltipRow("28d", fixed1(it))) }
                }
                if (rows.isEmpty()) return@mapNotNull null
                AnchorContribution(
                    key = day.date,
                    x = xScale(dayIndex(day.date, frame.origin).toDouble()),
                    label = monthDay(day.date),
                    rows = rows,
                )
            },
        ),
    )
}

// ---- Sleep -----------------------------------------------------------------

private val SLEEP_MARGINS = Margins(top = 10.0, right = 34.0, bottom = 22.0, left = 40.0)
private const val SLEEP_HEIGHT = 180.0

/** The axis never shrinks below this, so a bad week still reads as a bad week. */
private const val SLEEP_Y_FLOOR = 9.0
private const val SLEEP_GUIDE_HOURS = 8.0

/**
 * Sleep hours as daily bars, with the score on a fixed right-hand 0–100 axis.
 *
 * Both fixed choices carry meaning: a y axis floored at nine hours keeps a
 * five-hour night looking short instead of filling the card, and a score axis
 * that never rescales keeps 70 in the same place every week.
 */
fun sleepCardModel(days: List<RecoveryDay>): PlotModel? {
    val frame = dayChart(days, { it.date }, { it.sleepHours }) ?: return null

    val hours = frame.present.mapNotNull { it.sleepHours }
    val yMax = max(hours.max(), SLEEP_Y_FLOOR)

    val xScale = linearScale(
        frame.xMin - 0.5,
        frame.xMax + 0.5,
        SLEEP_MARGINS.left,
        LOGICAL_WIDTH - SLEEP_MARGINS.right,
    )
    val yScale = linearScale(
        0.0,
        yMax * 1.05,
        SLEEP_HEIGHT - SLEEP_MARGINS.bottom,
        SLEEP_MARGINS.top,
    )
    val barWidth = max(
        1.5,
        min(
            10.0,
            ((LOGICAL_WIDTH - SLEEP_MARGINS.left - SLEEP_MARGINS.right) /
                (frame.xMax - frame.xMin + 1)) * 0.7,
        ),
    )

    // The index-based xScale contract earns its keep here: bars are daily, not
    // weekly, so the closure maps slot number to day offset to x.
    val layout = stackedBarLayout(
        frame.present.map { StackedWeek(it.date, mapOf(SLEEP_KEY to (it.sleepHours ?: 0.0))) },
        listOf(SLEEP_KEY),
        { index -> xScale(dayIndex(frame.present[index].date, frame.origin).toDouble()) },
        yScale,
        barWidth,
    )

    val scoreScale = linearScale(0.0, 100.0, SLEEP_HEIGHT - SLEEP_MARGINS.bottom, SLEEP_MARGINS.top)
    // Bounded by the domain, which the *hours* series defines: a score logged
    // on a night with no sleep record before the first such night would map
    // left of the axis and draw off the card.
    val inDomain = anchorDays(frame, days) { it.date }
    val scoreDots = inDomain.filter { it.sleepScore != null }.map { day ->
        PlotDot(
            x = xScale(dayIndex(day.date, frame.origin).toDouble()),
            y = scoreScale(day.sleepScore!!),
            radius = 1.8.dp,
            tone = PlotTone.SECONDARY,
        )
    }

    val (gridlines, yLabels) = yAxisMarks(
        yMin = 0.0,
        yMax = yMax * 1.05,
        yScale = yScale,
        x0 = SLEEP_MARGINS.left,
        x1 = LOGICAL_WIDTH - SLEEP_MARGINS.right,
    )
    val scoreLabels = listOf(100.0, 0.0).map {
        PlotLabel(
            x = LOGICAL_WIDTH - SLEEP_MARGINS.right + 4.0,
            y = scoreScale(it),
            text = jsNumberString(it),
            align = LabelAlign.START,
        )
    }
    val ticks = dateTicks(frame.present, { it.date }, frame.origin)

    val hoursByDate = days.associate { it.date to it.sleepHours }
    val scoreByDate = days.associate { it.date to it.sleepScore }
    val anchors = mergeScrubAnchors(
        inDomain.filter { it.sleepHours != null || it.sleepScore != null }.map { day ->
            AnchorContribution(
                key = day.date,
                x = xScale(dayIndex(day.date, frame.origin).toDouble()),
                label = monthDay(day.date),
                rows = buildList {
                    hoursByDate[day.date]?.let { add(TooltipRow("hours", fixed1(it))) }
                    scoreByDate[day.date]?.let { add(TooltipRow("score", jsNumberString(it))) }
                },
            )
        },
    )

    return PlotModel(
        height = SLEEP_HEIGHT,
        rects = layout.flatMap { column ->
            column.segs.map { PlotRect(it.x, it.y, it.w, it.h, PlotTone.PRIMARY, radius = 1.dp) }
        },
        gridlines = gridlines,
        guides = listOf(
            PlotGuide(
                y = yScale(SLEEP_GUIDE_HOURS),
                x0 = SLEEP_MARGINS.left,
                x1 = LOGICAL_WIDTH - SLEEP_MARGINS.right,
                dashed = true,
            ),
        ),
        dots = scoreDots,
        labels = yLabels + scoreLabels + xAxisLabels(ticks, xScale, SLEEP_HEIGHT),
        anchors = anchors,
    )
}

private const val SLEEP_KEY = "h"

// ---- Body weight + DEXA ----------------------------------------------------

private val BODY_MARGINS = Margins(top = 10.0, right = 10.0, bottom = 22.0, left = 40.0)
private const val BODY_HEIGHT = 200.0

const val NO_SCANS_IN_RANGE_TEXT = "no scans in range — see composition below"

data class BodyCardModel(val plot: PlotModel, val legend: List<LegendEntry>)

/**
 * Scale weight and DEXA total mass on one axis — the sanity check between two
 * instruments that ought to agree.
 *
 * Lean and fat mass deliberately stay on the composition card below: at roughly
 * 58 and 25 kg they would flatten this axis into a straight line.
 */
fun bodyCardModel(series: List<WeightPoint>, scans: List<Scan>): BodyCardModel? {
    if (series.isEmpty()) return null

    val origin = series.first().date
    val last = series.last().date
    // Lexical comparison, as everywhere: these are ISO dates and never parsed.
    val inRange = scans.filter { it.date >= origin && it.date <= last && it.totalKg != null }

    val ys = series.map { it.kg } + inRange.mapNotNull { it.totalKg }
    val xs = series.map { dayIndex(it.date, origin).toDouble() }
    val pad = firstNonZero((ys.max() - ys.min()) * 0.12, 0.5)

    val xScale = linearScale(xs.min(), xs.max(), BODY_MARGINS.left, LOGICAL_WIDTH - BODY_MARGINS.right)
    val yScale = linearScale(
        ys.min() - pad,
        ys.max() + pad,
        BODY_HEIGHT - BODY_MARGINS.bottom,
        BODY_MARGINS.top,
    )

    val dated = series.map { DatedValue(it.date, it.kg) }
    val mean7 = rollingMean(dated, 7)
    val byDate7 = mean7.associate { it.date to it.value }
    val dots = seriesToPoints(series, { dayIndex(it.date, origin).toDouble() }, { it.kg }, xScale, yScale)
    val scanPoints = inRange.map {
        ChartPoint(
            x = xScale(dayIndex(it.date, origin).toDouble()),
            y = yScale(it.totalKg!!),
            raw = Unit,
        )
    }

    val (gridlines, yLabels) = yAxisMarks(
        yMin = ys.min() - pad,
        yMax = ys.max() + pad,
        yScale = yScale,
        x0 = BODY_MARGINS.left,
        x1 = LOGICAL_WIDTH - BODY_MARGINS.right,
        format = ::fixed1,
    )
    val ticks = dateTicks(series, { it.date }, origin)

    val anchors = mergeScrubAnchors(
        series.map { point ->
            AnchorContribution(
                key = point.date,
                x = xScale(dayIndex(point.date, origin).toDouble()),
                label = monthDay(point.date),
                rows = buildList {
                    add(TooltipRow("kg", jsNumberString(point.kg)))
                    byDate7[point.date]?.let { add(TooltipRow("7d", fixed1(it))) }
                },
            )
        } + inRange.map { scan ->
            AnchorContribution(
                key = scan.date,
                x = xScale(dayIndex(scan.date, origin).toDouble()),
                label = monthDay(scan.date),
                rows = listOf(TooltipRow("DEXA", "${jsNumberString(scan.totalKg!!)} kg")),
            )
        },
    )

    return BodyCardModel(
        plot = PlotModel(
            height = BODY_HEIGHT,
            gridlines = gridlines,
            lines = listOfNotNull(
                meanLine(mean7, origin, xScale, yScale, PlotTone.PRIMARY),
                if (scanPoints.size > 1) PlotLine(scanPoints, PlotTone.SCAN) else null,
            ),
            dots = dots.map { PlotDot(it.x, it.y, 2.dp, PlotTone.VALUE) } +
                scanPoints.map { PlotDot(it.x, it.y, 4.dp, PlotTone.SCAN) },
            labels = yLabels + xAxisLabels(ticks, xScale, BODY_HEIGHT),
            anchors = anchors,
        ),
        legend = buildList {
            add(LegendEntry("7d mean", PlotTone.PRIMARY))
            add(LegendEntry("DEXA total", PlotTone.SCAN))
            if (inRange.isEmpty()) add(LegendEntry(NO_SCANS_IN_RANGE_TEXT, PlotTone.MUTED))
        },
    )
}

// ---- Composition -----------------------------------------------------------

private data class CompositionMetric(
    val label: String,
    val unit: String,
    val select: (Scan) -> Double?,
)

private val COMPOSITION_METRICS = listOf(
    CompositionMetric("Lean mass", "kg") { it.leanKg },
    CompositionMetric("Fat mass", "kg") { it.fatKg },
    CompositionMetric("Body fat", "%") { it.bodyFatPct },
    CompositionMetric("VAT", "kg") { it.vatKg },
    CompositionMetric("A/G ratio", "") { it.agRatio },
)

private val COMPOSITION_MARGINS = Margins(top = 0.0, right = 44.0, bottom = 0.0, left = 64.0)
private const val MINI_METRIC_HEIGHT = 56.0
private const val MINI_METRIC_INSET = 8.0
private const val COMPOSITION_AXIS_HEIGHT = 16.0

data class MiniMetricModel(val label: String, val latest: String, val plot: PlotModel)

data class BoneRow(val date: DateString, val bmd: String, val tScore: String)

data class CompositionCardModel(
    val metrics: List<MiniMetricModel>,
    val axis: PlotModel,
    val bone: List<BoneRow>,
)

/**
 * Small multiples over every scan ever, sharing one x axis.
 *
 * Range-immune on purpose: scans are months apart, so a twelve-week window
 * would show at most one of them and a trend of one point is not a trend.
 */
fun compositionCardModel(scans: List<Scan>): CompositionCardModel? {
    if (scans.isEmpty()) return null

    val origin = scans.first().date
    val xMax = max(scans.maxOf { dayIndex(it.date, origin).toDouble() }, 1.0)
    val xScale = linearScale(
        0.0,
        xMax,
        COMPOSITION_MARGINS.left,
        LOGICAL_WIDTH - COMPOSITION_MARGINS.right,
    )

    val metrics = COMPOSITION_METRICS.mapNotNull { metric ->
        val points = scans.filter { metric.select(it) != null }
        if (points.isEmpty()) return@mapNotNull null

        val values = points.mapNotNull(metric.select)
        val pad = firstNonZero((values.max() - values.min()) * 0.15, 0.1)
        val yScale = linearScale(
            values.min() - pad,
            values.max() + pad,
            MINI_METRIC_HEIGHT - MINI_METRIC_INSET,
            MINI_METRIC_INSET,
        )
        val plotted = points.map {
            ChartPoint(
                x = xScale(dayIndex(it.date, origin).toDouble()),
                y = yScale(metric.select(it)!!),
                raw = Unit,
            )
        }
        val latest = metric.select(points.last())!!

        MiniMetricModel(
            label = metric.label,
            latest = "${jsNumberString(latest)}${metric.unit}",
            plot = PlotModel(
                height = MINI_METRIC_HEIGHT,
                lines = if (plotted.size > 1) listOf(PlotLine(plotted, PlotTone.SCAN)) else emptyList(),
                dots = plotted.map { PlotDot(it.x, it.y, 2.5.dp, PlotTone.SCAN) },
                anchors = mergeScrubAnchors(
                    points.map { scan ->
                        AnchorContribution(
                            key = scan.date,
                            x = xScale(dayIndex(scan.date, origin).toDouble()),
                            label = yearMonth(scan.date),
                            rows = listOf(
                                TooltipRow(
                                    metric.label,
                                    "${jsNumberString(metric.select(scan)!!)}${metric.unit}",
                                ),
                            ),
                        )
                    },
                ),
            ),
        )
    }

    val axisLabels = spread(scans.size, 4).map { index ->
        PlotLabel(
            x = xScale(dayIndex(scans[index].date, origin).toDouble()),
            y = COMPOSITION_AXIS_HEIGHT / 2,
            text = yearMonth(scans[index].date),
            align = LabelAlign.CENTER,
        )
    }

    return CompositionCardModel(
        metrics = metrics,
        axis = PlotModel(height = COMPOSITION_AXIS_HEIGHT, labels = axisLabels),
        bone = scans.filter { it.bmdTotal != null }.map { scan ->
            BoneRow(
                date = scan.date,
                bmd = "${jsNumberString(scan.bmdTotal!!)} g/cm²",
                // The PWA prints a bare `null` when the t-score is missing; an
                // em dash says the same thing without looking like a bug.
                tScore = "t-score ${scan.tScoreTotal?.let(::jsNumberString) ?: "—"}",
            )
        },
    )
}

// ---- Labs ------------------------------------------------------------------

private val MINI_LAB_MARGINS = Margins(top = 10.0, right = 52.0, bottom = 14.0, left = 8.0)
private const val MINI_LAB_HEIGHT = 64.0

data class MiniLabModel(val name: String, val latest: String, val plot: PlotModel)

data class LabRowModel(
    val name: String,
    val subLabel: String,
    val value: String,
    val flagged: Boolean,
)

data class LabsSectionModel(
    val panelOptions: List<PickerOption>,
    val selectedPanel: String,
    val charts: List<MiniLabModel>,
    val rows: List<LabRowModel>,
)

/**
 * One panel's tests: a mini chart each for the ones with a history, a row each
 * for the rest.
 *
 * The origin is the earliest observation across the **whole panel**, not per
 * test, so the strips line up vertically — a magnesium draw and a ferritin draw
 * from the same morning sit at the same x.
 */
fun labsSectionModel(panels: List<LabPanel>, persistedPanel: String?): LabsSectionModel? {
    if (panels.isEmpty()) return null
    val names = panels.map { it.name }
    val selected = resolveSelection(persistedPanel, names) ?: return null
    val panel = panels.first { it.name == selected }

    val partition = labsPartition(panel.tests)
    val origin = panel.tests.flatMap { test -> test.observations.map { it.date } }.minOrNull()

    return LabsSectionModel(
        panelOptions = names.map { PickerOption(it, it) },
        selectedPanel = selected,
        charts = if (origin == null) emptyList() else partition.chartable.map { miniLabModel(it, origin) },
        rows = partition.tabular.mapNotNull { test ->
            val last = test.observations.lastOrNull() ?: return@mapNotNull null
            LabRowModel(
                name = test.name,
                subLabel = last.refText?.let { "${last.date} · ref $it" } ?: last.date,
                value = labValueText(last, test.unit),
                flagged = !last.flag.isNullOrEmpty(),
            )
        },
    )
}

private fun miniLabModel(test: ChartableTest, origin: DateString): MiniLabModel {
    val latest = test.obs.last()
    val xMax = max(test.obs.maxOf { dayIndex(it.date, origin).toDouble() }, 1.0)
    val xScale = linearScale(0.0, xMax, MINI_LAB_MARGINS.left, LOGICAL_WIDTH - MINI_LAB_MARGINS.right)

    // One band, from the LATEST reference range: ranges rarely move, and
    // per-observation correctness comes from the lab's own flag anyway.
    val low = latest.source.refLow
    val high = latest.source.refHigh
    val bandValues = listOfNotNull(low, high)
    val values = test.obs.map { it.num }
    val yMin = (values + bandValues).min()
    val yMax = (values + bandValues).max()
    val pad = firstNonZero((yMax - yMin) * 0.2, yMax * 0.1, 1.0)
    val yScale = linearScale(
        yMin - pad,
        yMax + pad,
        MINI_LAB_HEIGHT - MINI_LAB_MARGINS.bottom,
        MINI_LAB_MARGINS.top,
    )

    val rects = if (low == null && high == null) {
        emptyList()
    } else {
        steppedBandRects(
            listOf(BandSegment(x0 = 0.0, x1 = xMax + 1, min = low, max = high)),
            0.0,
            xMax + 1,
            xScale,
            yScale,
            MINI_LAB_MARGINS.top,
            MINI_LAB_HEIGHT - MINI_LAB_MARGINS.bottom,
        ).map { PlotRect(it.x, it.yTop, it.w, it.yBot - it.yTop, PlotTone.BAND) }
    }

    val plotted = test.obs.map {
        ChartPoint(x = xScale(dayIndex(it.date, origin).toDouble()), y = yScale(it.num), raw = it)
    }
    val labels = spread(test.obs.size, 3).map { index ->
        PlotLabel(
            x = xScale(dayIndex(test.obs[index].date, origin).toDouble()),
            y = MINI_LAB_HEIGHT - 7.0,
            text = yearMonth(test.obs[index].date),
            align = LabelAlign.CENTER,
        )
    }

    return MiniLabModel(
        name = test.name,
        latest = labChartValue(latest, test.unit),
        plot = PlotModel(
            height = MINI_LAB_HEIGHT,
            rects = rects,
            lines = if (plotted.size > 1) listOf(PlotLine(plotted, PlotTone.SCAN)) else emptyList(),
            dots = plotted.map {
                PlotDot(
                    x = it.x,
                    y = it.y,
                    radius = 2.5.dp,
                    tone = if (!it.raw.source.flag.isNullOrEmpty()) PlotTone.WARN else PlotTone.VALUE,
                )
            },
            labels = labels,
            anchors = mergeScrubAnchors(
                test.obs.map { obs ->
                    AnchorContribution(
                        key = obs.date,
                        x = xScale(dayIndex(obs.date, origin).toDouble()),
                        label = yearMonth(obs.date),
                        rows = buildList {
                            add(TooltipRow(test.name, labChartValue(obs, test.unit)))
                            refRow(obs)?.let(::add)
                            obs.source.flag?.takeIf { it.isNotEmpty() }
                                ?.let { add(TooltipRow("flag", it)) }
                        },
                    )
                },
            ),
        ),
    )
}

/** The number as charted, shown with whatever prefix the report carried. */
private fun labChartValue(obs: NumericObs, unit: String?): String {
    val body = "${obs.source.prefix.orEmpty()}${jsNumberString(obs.num)}"
    return if (unit.isNullOrEmpty()) body else "$body $unit"
}

private fun refRow(obs: NumericObs): TooltipRow? {
    val low = obs.source.refLow
    val high = obs.source.refHigh
    val text = when {
        low != null && high != null -> "${jsNumberString(low)}–${jsNumberString(high)}"
        low != null -> "≥ ${jsNumberString(low)}"
        high != null -> "≤ ${jsNumberString(high)}"
        else -> return null
    }
    return TooltipRow("ref", text)
}
