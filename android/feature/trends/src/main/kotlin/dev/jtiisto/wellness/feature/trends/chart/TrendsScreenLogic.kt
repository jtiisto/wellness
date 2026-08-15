package dev.jtiisto.wellness.feature.trends.chart

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.trends.LabObs
import dev.jtiisto.wellness.core.data.trends.LabTest
import dev.jtiisto.wellness.core.data.trends.TrackerSummary
import dev.jtiisto.wellness.core.data.trends.VolumeWeek
import kotlin.math.max

/**
 * The screen rules the PWA inlines into its components, extracted so they can
 * be tested without a renderer.
 *
 * Nothing here draws or fetches; each function is the answer to one question a
 * screen asks — how big is that delta, which slugs get their own stack colour,
 * which lab tests are worth a chart at all.
 */

/** `MM-DD` — the axis label under a daily chart. */
fun monthDay(date: DateString): String = date.substring(5)

/** `YY-MM` — scans and lab reports are months apart; the day is noise. */
fun yearMonth(date: DateString): String = date.substring(2, 7)

/**
 * The percentage change from a four-week average, rounded the way JavaScript
 * rounds.
 *
 * Null unless there is both a value and a *usable* average: a zero average
 * would divide the tile's headline into infinity, and the PWA's truthiness test
 * excludes it for exactly that reason.
 */
fun statTileDelta(value: Double?, avg: Double?): Int? {
    if (value == null || avg == null || avg == 0.0 || avg.isNaN()) return null
    return jsRound((value / avg - 1) * 100).toInt()
}

// ---- Volume stacks ---------------------------------------------------------

data class VolumeStacks(
    val weeks: List<StackedWeek>,
    /** Stacking order, bottom-up: the top slugs, then `other`. */
    val keys: List<String>,
    val names: Map<String, String>,
    /** Whether `other` ever carried anything — the legend entry hangs off this. */
    val hasOther: Boolean,
)

const val OTHER_KEY = "other"

/**
 * The [topN] slugs by total tonnage get their own stack colour; everything else
 * folds into one `other` band.
 *
 * Ranking is over the whole range rather than per week, so a slug keeps the
 * same colour from bar to bar — a stack that reshuffled weekly would be
 * unreadable however correct each column was.
 */
fun foldVolumeStacks(weeks: List<VolumeWeek>, topN: Int = 3): VolumeStacks {
    val totals = LinkedHashMap<String, Double>()
    val names = LinkedHashMap<String, String>()
    for (week in weeks) {
        for (exercise in week.byExercise) {
            totals[exercise.slug] = (totals[exercise.slug] ?: 0.0) + exercise.tonnageKg
            names[exercise.slug] = exercise.name
        }
    }
    val top = totals.entries.sortedByDescending { it.value }.take(topN).map { it.key }

    var hasOther = false
    val stacked = weeks.map { week ->
        val values = LinkedHashMap<String, Double>()
        var other = 0.0
        for (exercise in week.byExercise) {
            if (exercise.slug in top) {
                values[exercise.slug] = exercise.tonnageKg
            } else {
                other += exercise.tonnageKg
            }
        }
        if (other > 0) hasOther = true
        values[OTHER_KEY] = other
        StackedWeek(weekStart = week.weekStart, values = values)
    }

    return VolumeStacks(
        weeks = stacked,
        keys = top + OTHER_KEY,
        names = names,
        hasOther = hasOther,
    )
}

/** The volume axis: thousands collapse to tonnes at one decimal. */
fun volumeYLabel(value: Double): String =
    if (value >= 1000) "${jsNumberString(jsRound(value / 100) / 10.0)}t" else formatNum(value)

// ---- Pickers ---------------------------------------------------------------

data class NamedItem(val value: String, val name: String)

data class PickerOption(val value: String, val label: String)

/**
 * Display names for a picker, disambiguated only where they collide.
 *
 * Near-duplicate slugs are meant to be spottable, but suffixing every row with
 * one turns the common case into noise — so the slug appears exactly where two
 * entries would otherwise read identically.
 */
fun pickerLabels(items: List<NamedItem>): List<PickerOption> {
    val counts = items.groupingBy { it.name }.eachCount()
    return items.map { item ->
        PickerOption(
            value = item.value,
            label = if ((counts[item.name] ?: 0) > 1) "${item.name} (${item.value})" else item.name,
        )
    }
}

/** The tracker picker names its unit instead — trackers do not share names. */
fun trackerPickerOptions(trackers: List<TrackerSummary>): List<PickerOption> =
    trackers.map { tracker ->
        val name = tracker.name.orEmpty()
        PickerOption(
            value = tracker.id,
            label = if (tracker.unit.isNullOrEmpty()) name else "$name (${tracker.unit})",
        )
    }

/**
 * The selected id, or the first available one when what was remembered is no
 * longer on offer. Never an error: a tracker that was deleted, or an exercise
 * that fell out of the range, is an ordinary thing to come back to.
 */
fun resolveSelection(persisted: String?, available: List<String>): String? {
    if (available.isEmpty()) return null
    return if (persisted != null && persisted in available) persisted else available.first()
}

// ---- Constant series -------------------------------------------------------

/**
 * The note that replaces a chart when every value in range is the same.
 *
 * A fixed-dose supplement plots as a meaningless flat line; the number itself
 * and a count of entries say more, and the adherence ribbon below carries the
 * actual signal. Null when there is anything to plot.
 */
fun constantSeriesNote(values: List<Double>): String? {
    if (values.isEmpty()) return null
    if (values.toSet().size != 1) return null
    return if (values.size == 1) {
        "the only entry in range"
    } else {
        "same value for all ${values.size} entries in range"
    }
}

// ---- Labs ------------------------------------------------------------------

data class NumericObs(val date: DateString, val num: Double, val source: LabObs)

data class ChartableTest(val name: String, val unit: String?, val obs: List<NumericObs>)

data class LabsPartition(val chartable: List<ChartableTest>, val tabular: List<LabTest>)

/**
 * Split a panel's tests into the ones worth a chart and the ones worth a row.
 *
 * Two numeric observations is the threshold because one point is not a trend,
 * and a test whose results are words ("Not Detected") never becomes one. The
 * partition is complete: every test lands on exactly one side.
 */
fun labsPartition(tests: List<LabTest>): LabsPartition {
    val chartable = mutableListOf<ChartableTest>()
    val tabular = mutableListOf<LabTest>()
    for (test in tests) {
        val numeric = test.observations.mapNotNull { obs ->
            // The wire types this one as a number already, so coercion reduces
            // to the finiteness half of `coerceNumeric`'s contract.
            val value = obs.value?.takeIf { it.isFinite() } ?: return@mapNotNull null
            NumericObs(date = obs.date, num = value, source = obs)
        }
        if (numeric.size >= 2) {
            chartable += ChartableTest(name = test.name, unit = test.unit, obs = numeric)
        } else {
            tabular += test
        }
    }
    return LabsPartition(chartable = chartable, tabular = tabular)
}

/** The row a non-chartable test shows: its words, or its number with a prefix. */
fun labValueText(obs: LabObs, unit: String?): String {
    val body = obs.text ?: "${obs.prefix.orEmpty()}${obs.value?.let(::jsNumberString) ?: "—"}"
    return if (unit.isNullOrEmpty()) body else "$body $unit"
}

// ---- Day-series scaffolding ------------------------------------------------

/**
 * The x extent of a daily chart, over the days that actually have a value.
 *
 * The origin is the **full** array's first date rather than the first present
 * one, so every series on the card — dots, means, bands — measures its day
 * offsets from the same zero. Null when nothing is present at all.
 */
data class DayChartFrame<T>(
    val present: List<T>,
    val origin: DateString,
    val xMin: Double,
    val xMax: Double,
)

fun <T> dayChart(
    days: List<T>,
    dateOf: (T) -> DateString,
    valueOf: (T) -> Double?,
): DayChartFrame<T>? {
    if (days.isEmpty()) return null
    val present = days.filter { valueOf(it) != null }
    if (present.isEmpty()) return null
    val origin = dateOf(days.first())
    val offsets = present.map { dayIndex(dateOf(it), origin).toDouble() }
    return DayChartFrame(
        present = present,
        origin = origin,
        xMin = offsets.min(),
        xMax = offsets.max(),
    )
}

/** A date label at a **domain** x — a day offset, not yet a coordinate. */
data class DateTick(val x: Double, val label: String)

/**
 * Up to [n] date labels spread across [items], positioned in day-index space.
 *
 * Left unscaled because the shared line chart derives its x domain partly from
 * its ticks: handing it pre-scaled positions would ask it to widen a domain
 * using numbers already measured against that domain.
 */
fun <T> dateTicks(
    items: List<T>,
    dateOf: (T) -> DateString,
    origin: DateString,
    n: Int = 5,
    format: (DateString) -> String = ::monthDay,
): List<DateTick> = spread(items.size, n).map { index ->
    val date = dateOf(items[index])
    DateTick(x = dayIndex(date, origin).toDouble(), label = format(date))
}

// ---- Adherence ribbon ------------------------------------------------------

/**
 * The three-rectangle painter's stack behind one adherence cell.
 *
 * Missed is the full-height base, partial covers `met + partial` from the
 * bottom, and met covers `met` on top of that — so each fill is drawn over the
 * one it outranks rather than positioned against it. The in-progress week gets
 * a translucent lid over whatever it ended up with.
 */
fun adherenceRects(
    cells: List<RibbonCell>,
    partialWeeks: List<Boolean>,
    top: Double,
    height: Double,
    radius: Dp = 3.dp,
): List<PlotRect> {
    val rects = mutableListOf<PlotRect>()
    cells.forEachIndexed { index, cell ->
        if (cell.muted) {
            rects += PlotRect(cell.x, top, cell.w, height, PlotTone.MUTED, radius)
        } else {
            rects += PlotRect(cell.x, top, cell.w, height, PlotTone.MISSED, radius)
            if (cell.partial + cell.met > 0) {
                rects += PlotRect(
                    x = cell.x,
                    y = top + height * (1 - cell.met - cell.partial),
                    w = cell.w,
                    h = height * (cell.met + cell.partial),
                    tone = PlotTone.PARTIAL,
                    radius = radius,
                )
            }
            if (cell.met > 0) {
                rects += PlotRect(
                    x = cell.x,
                    y = top + height * (1 - cell.met),
                    w = cell.w,
                    h = height * cell.met,
                    tone = PlotTone.MET,
                    radius = radius,
                )
            }
        }
        if (partialWeeks.getOrElse(index) { false }) {
            rects += PlotRect(cell.x, top, cell.w, height, PlotTone.IN_PROGRESS, radius)
        }
    }
    return rects
}

// ---- Staleness -------------------------------------------------------------

/**
 * The cached-data badge, or null when nothing on screen is stale.
 *
 * The oldest stamp wins: a screen showing one fresh card and one from
 * yesterday is a screen showing yesterday's data, and rounding that down to the
 * fresher number would be a comfortable lie. Recomputed on recomposition
 * rather than on a timer — the badge is a fact about the fetch, not a clock.
 */
fun staleBadgeText(stamps: List<Long>, now: Long): String? {
    val oldest = stamps.minOrNull() ?: return null
    val minutes = max(1L, jsRound((now - oldest) / 60_000.0))
    val age = if (minutes < 60) "${minutes}m" else "${jsRound(minutes / 60.0)}h"
    return "cached · $age ago"
}
