package dev.jtiisto.wellness.feature.trends.chart

import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.trends.TrackerDetailDto
import dev.jtiisto.wellness.core.data.trends.UsageWeek
import kotlin.math.max
import kotlin.math.min

/** The Journal screen's three cards, as data. */

// ---- Value vs target -------------------------------------------------------

/** A series that never moved: the number itself, and how many entries said so. */
data class ConstantValue(val value: String, val unit: String, val note: String)

data class ValueTargetCardModel(
    val title: String,
    val subtitle: String,
    val plot: PlotModel?,
    val constant: ConstantValue?,
    val emptyText: String?,
)

const val NO_VALUES_TEXT = "No values in range"

private val VALUE_TARGET_MARGINS = Margins(top = 10.0, right = 10.0, bottom = 22.0, left = 40.0)
private const val VALUE_TARGET_HEIGHT = 200.0

/**
 * Daily values against the effective-dated target band.
 *
 * Two things here are easy to get wrong and are both deliberate. Values are
 * coerced *before* they are filtered, so one free-text entry from a tracker's
 * note era drops out instead of poisoning every scale on the card. And the x
 * domain runs one day past the last dot, because a target that took effect on
 * that very day would otherwise clip to zero width and today would render
 * against yesterday's band.
 */
fun valueTargetCardModel(detail: TrackerDetailDto): ValueTargetCardModel {
    val name = detail.tracker.name.orEmpty()
    val values = detail.values.mapNotNull { entry ->
        coerceNumeric(entry.value)?.let { DatedValue(entry.date, it) }
    }
    if (values.isEmpty()) {
        return ValueTargetCardModel(name, "", null, null, NO_VALUES_TEXT)
    }

    val numbers = values.map { it.value!! }
    constantSeriesNote(numbers)?.let { note ->
        return ValueTargetCardModel(
            title = name,
            subtitle = "",
            plot = null,
            constant = ConstantValue(
                value = jsNumberString(numbers.first()),
                unit = detail.tracker.unit.orEmpty(),
                note = note,
            ),
            emptyText = null,
        )
    }

    val origin = values.first().date
    val xs = values.map { dayIndex(it.date, origin).toDouble() }
    val bandYs = detail.targetSegments.flatMap { listOfNotNull(it.min, it.max) }
    val yMin = (numbers + bandYs).min()
    val yMax = (numbers + bandYs).max()
    val pad = firstNonZero((yMax - yMin) * 0.1, yMax * 0.05, 1.0)

    val xMin = xs.min()
    val xMax = xs.max()
    val xScale = linearScale(
        xMin,
        xMax + 1,
        VALUE_TARGET_MARGINS.left,
        LOGICAL_WIDTH - VALUE_TARGET_MARGINS.right,
    )
    val yScale = linearScale(
        yMin - pad,
        yMax + pad,
        VALUE_TARGET_HEIGHT - VALUE_TARGET_MARGINS.bottom,
        VALUE_TARGET_MARGINS.top,
    )

    // Inclusive [start, end] on the wire; exclusive x1 in geometry, so the band
    // has to cover the end day rather than stop at its dot.
    val segments = detail.targetSegments.map {
        BandSegment(
            x0 = dayIndex(it.start, origin).toDouble(),
            x1 = dayIndex(it.end, origin).toDouble() + 1,
            min = it.min,
            max = it.max,
        )
    }
    val rects = steppedBandRects(
        segments,
        xMin,
        xMax + 1,
        xScale,
        yScale,
        VALUE_TARGET_MARGINS.top,
        VALUE_TARGET_HEIGHT - VALUE_TARGET_MARGINS.bottom,
    ).map { PlotRect(it.x, it.yTop, it.w, it.yBot - it.yTop, PlotTone.BAND) }

    val dots = seriesToPoints(values, { dayIndex(it.date, origin).toDouble() }, { it.value }, xScale, yScale)
    val mean = rollingMean(values, 7)
    val (gridlines, yLabels) = yAxisMarks(
        yMin = yMin - pad,
        yMax = yMax + pad,
        yScale = yScale,
        x0 = VALUE_TARGET_MARGINS.left,
        x1 = LOGICAL_WIDTH - VALUE_TARGET_MARGINS.right,
    )
    val ticks = dateTicks(values, { it.date }, origin)

    val unit = detail.tracker.unit
    val subtitle = listOfNotNull(
        unit?.takeIf { it.isNotEmpty() },
        "7d mean",
        if (detail.targetSegments.isNotEmpty()) "target band" else null,
    ).joinToString(" · ")

    val anchors = mergeScrubAnchors(
        values.mapIndexed { index, point ->
            AnchorContribution(
                key = point.date,
                x = xScale(dayIndex(point.date, origin).toDouble()),
                label = monthDay(point.date),
                rows = buildList {
                    val value = jsNumberString(point.value!!)
                    add(TooltipRow("value", if (unit.isNullOrEmpty()) value else "$value $unit"))
                    mean[index].value?.let { add(TooltipRow("7d", fixed1(it))) }
                    targetRow(detail, point.date)?.let(::add)
                },
            )
        },
    )

    return ValueTargetCardModel(
        title = name,
        subtitle = subtitle,
        plot = PlotModel(
            height = VALUE_TARGET_HEIGHT,
            rects = rects,
            gridlines = gridlines,
            lines = listOfNotNull(meanLine(mean, origin, xScale, yScale, PlotTone.PRIMARY)),
            dots = dots.map { PlotDot(it.x, it.y, 2.5.dp, PlotTone.VALUE) },
            labels = yLabels + xAxisLabels(ticks, xScale, VALUE_TARGET_HEIGHT),
            anchors = anchors,
        ),
        constant = null,
        emptyText = null,
    )
}

/**
 * The target in force on [date], as a tooltip row.
 *
 * Both wire ends are inclusive here — the exclusive `+1` belongs to the band
 * geometry, not to the question "what was I aiming for that day". A one-sided
 * target reads as a bound rather than a range; no target at all shows nothing.
 */
internal fun targetRow(detail: TrackerDetailDto, date: String): TooltipRow? {
    val segment = detail.targetSegments.lastOrNull { date >= it.start && date <= it.end } ?: return null
    val low = segment.min
    val high = segment.max
    val text = when {
        low != null && high != null -> "${jsNumberString(low)}–${jsNumberString(high)}"
        low != null -> "≥ ${jsNumberString(low)}"
        high != null -> "≤ ${jsNumberString(high)}"
        else -> return null
    }
    return TooltipRow("target", text)
}

// ---- Usage -----------------------------------------------------------------

private const val USAGE_HEIGHT = 120.0
const val USAGE_COUNT_KEY = "count"

/**
 * Entries per week, for the trackers where *how often* is the signal.
 *
 * An as-needed medication logged at the same dose every time charts as a flat
 * line that says nothing; the count says everything.
 */
fun usageCardModel(weeks: List<UsageWeek>): PlotModel? = barChartModel(
    weeks = weeks.map { StackedWeek(it.weekStart, mapOf(USAGE_COUNT_KEY to it.count.toDouble())) },
    keys = listOf(USAGE_COUNT_KEY),
    // A plain bar, not a stack member: usage has one key, and a plate on a
    // single-series chart would be a colour identifying nothing — with no
    // legend to decode it, since the card carries none.
    tones = mapOf(USAGE_COUNT_KEY to PlotTone.BAR),
    partial = weeks.map { it.partial },
    height = USAGE_HEIGHT,
    yFormat = { jsNumberString(jsRound(it).toDouble()) },
    anchorsOf = { xScale, _ ->
        mergeScrubAnchors(
            weeks.mapIndexed { index, week ->
                AnchorContribution(
                    key = week.weekStart,
                    x = xScale(index),
                    label = monthDay(week.weekStart),
                    rows = listOf(TooltipRow("entries", week.count.toString())),
                )
            },
        )
    },
)

// ---- Weekly adherence ribbon -----------------------------------------------

data class AdherenceCardModel(
    val title: String,
    val currentStreak: Int,
    val bestStreak: Int,
    val plot: PlotModel,
    val legend: List<LegendEntry>,
)

private const val RIBBON_HEIGHT = 64.0
private const val RIBBON_TOP = 8.0
private const val RIBBON_CELL_HEIGHT = 28.0
private const val RIBBON_SIDE_MARGIN = 8.0

fun adherenceCardModel(detail: TrackerDetailDto): AdherenceCardModel {
    val weeks = detail.weeklyAdherence
    // The 0.5 floor keeps a single-week ribbon from collapsing its domain onto
    // one point, which would centre the cell instead of placing it.
    val xScale = linearScale(
        -0.5,
        max(weeks.size - 0.5, 0.5),
        RIBBON_SIDE_MARGIN,
        LOGICAL_WIDTH - RIBBON_SIDE_MARGIN,
    )
    val cellWidth = min(
        24.0,
        ((LOGICAL_WIDTH - 2 * RIBBON_SIDE_MARGIN) / max(weeks.size, 1)) * 0.8,
    )
    val cells = ribbonCells(
        weeks.map {
            RibbonWeek(
                weekStart = it.weekStart,
                scheduledDays = it.scheduledDays,
                met = it.met,
                partialDays = it.partialDays,
                missed = it.missed,
                paused = it.paused,
            )
        },
        { index -> xScale(index.toDouble()) },
        cellWidth,
    )
    val rects = adherenceRects(
        cells = cells,
        partialWeeks = weeks.map { it.partial },
        top = RIBBON_TOP,
        height = RIBBON_CELL_HEIGHT,
    )
    val labels = spread(weeks.size, 5).map { index ->
        PlotLabel(
            x = xScale(index.toDouble()),
            y = RIBBON_HEIGHT - 12.0,
            text = monthDay(weeks[index].weekStart),
            align = LabelAlign.CENTER,
        )
    }
    val anchors = mergeScrubAnchors(
        weeks.mapIndexed { index, week ->
            AnchorContribution(
                key = week.weekStart,
                x = xScale(index.toDouble()),
                label = monthDay(week.weekStart),
                rows = buildList {
                    add(TooltipRow("met", "${week.met} of ${week.scheduledDays}"))
                    add(TooltipRow("partial", week.partialDays.toString()))
                    add(TooltipRow("missed", week.missed.toString()))
                    week.rate?.let { add(TooltipRow("rate", "${jsRound(it * 100)}%")) }
                },
            )
        },
    )

    return AdherenceCardModel(
        title = "Weekly ${weeks.firstOrNull()?.metricKind ?: "adherence"}",
        currentStreak = detail.streaks.current,
        bestStreak = detail.streaks.best,
        plot = PlotModel(height = RIBBON_HEIGHT, rects = rects, labels = labels, anchors = anchors),
        legend = listOf(
            LegendEntry("met", PlotTone.MET),
            LegendEntry("partial", PlotTone.PARTIAL),
            LegendEntry("missed", PlotTone.MISSED),
            LegendEntry("paused/off", PlotTone.MUTED),
        ),
    )
}
