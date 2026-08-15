package dev.jtiisto.wellness.feature.trends.chart

import dev.jtiisto.wellness.core.data.network.DateString
import java.time.LocalDate

/**
 * The small shared pieces every Trends chart is built from: the range
 * selector's window arithmetic, sparse tick selection, and the y-axis
 * label-dedup rule.
 */

/** One entry in the range selector. A null [days] means "everything". */
data class RangeSpec(val id: String, val label: String, val days: Int?)

val RANGES = listOf(
    RangeSpec(id = "4w", label = "4w", days = 28),
    RangeSpec(id = "12w", label = "12w", days = 84),
    RangeSpec(id = "6m", label = "6m", days = 182),
    RangeSpec(id = "all", label = "All", days = null),
)

/** The Trends tab's five sub-screens, in the order the switcher shows them. */
data class ScreenSpec(val id: String, val label: String)

val TREND_SCREENS = listOf(
    ScreenSpec("overview", "Overview"),
    ScreenSpec("strength", "Strength"),
    ScreenSpec("cardio", "Cardio"),
    ScreenSpec("journal", "Journal"),
    ScreenSpec("health", "Health"),
)

/**
 * The `start` query parameter for [rangeId], or null for All — and for an id
 * nothing recognises, which is what a preference written by a future build
 * looks like to this one.
 */
fun rangeStart(rangeId: String, today: DateString): DateString? {
    val days = RANGES.firstOrNull { it.id == rangeId }?.days ?: return null
    return LocalDate.parse(today).minusDays(days.toLong()).toString()
}

/**
 * Up to [n] evenly spread positions across a list of [size] items, first and
 * last always included.
 *
 * Indices are what gets deduplicated, not the items themselves: two ticks a
 * fortnight apart can carry identical labels, and dropping one of *those*
 * would silently thin the axis.
 */
fun spread(size: Int, n: Int): List<Int> {
    if (size <= 0 || n <= 0) return emptyList()
    if (size <= n) return (0 until size).toList()
    if (n == 1) return listOf(0)
    val picked = LinkedHashSet<Int>()
    for (i in 0 until n) {
        picked += jsRound(i.toDouble() * (size - 1) / (n - 1)).toInt()
    }
    return picked.toList()
}

/** A y-axis tick. A null [label] draws the gridline and nothing else. */
data class AxisTick(val value: Double, val label: String?)

/**
 * Gridline values with their labels, suppressing a label that repeats the one
 * above it.
 *
 * An integer formatter over a narrow domain produced "0, 1, 1"; the duplicate
 * reads as a broken axis, while the gridline it belongs to is still useful.
 */
fun axisTicks(
    yMin: Double,
    yMax: Double,
    targetCount: Int = 4,
    format: (Double) -> String = ::formatNum,
): List<AxisTick> {
    var previous: String? = null
    return niceTicks(yMin, yMax, targetCount).map { value ->
        val label = format(value)
        val duplicate = label == previous
        previous = label
        AxisTick(value = value, label = if (duplicate) null else label)
    }
}
