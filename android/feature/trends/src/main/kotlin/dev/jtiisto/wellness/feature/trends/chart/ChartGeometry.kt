package dev.jtiisto.wellness.feature.trends.chart

import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Pure data → geometry, ported 1:1 from the PWA's `trends/chart-logic.js`.
 *
 * Everything here works in the PWA's **logical coordinate space**: a 360-unit
 * wide viewBox with y growing *down*, which is why y scales are built with an
 * inverted range (`r0 > r1`) by their callers. Nothing in this file knows about
 * pixels, density or Compose — the transform to screen space happens once, at
 * the draw site, and is the only place a device dimension appears.
 *
 * Keeping the JS names and argument order is deliberate: `ChartGeometryTest`
 * transcribes the JS suite case for case, and a renamed argument is one more
 * thing to check when the two are compared.
 */

/** The PWA's viewBox width. Every chart is authored against it. */
const val LOGICAL_WIDTH = 360.0

/**
 * A plotted point, carrying whatever the caller needs to describe it later.
 *
 * [raw] exists so nothing has to index back into the source list: a series with
 * a nullable accessor produces *fewer* points than it has records, and the
 * PWA's `points[i] ↔ records[i]` pairing is a bug waiting for the first null,
 * not a contract worth porting.
 */
data class ChartPoint<out T>(
    val x: Double,
    val y: Double,
    val raw: T,
    val muted: Boolean = false,
)

/** A point with nothing to carry — geometry for its own sake. */
fun plainPoint(x: Double, y: Double): ChartPoint<Unit> = ChartPoint(x, y, Unit)

/** A `[low, high]` pair read off a record; either bound may be missing. */
data class BandBounds(val low: Double?, val high: Double?)

/** An effective-dated band, x in day-index units. [x1] is **exclusive**. */
data class BandSegment(val x0: Double, val x1: Double, val min: Double?, val max: Double?)

/** A band drawn in logical coordinates. */
data class BandRect(val x: Double, val w: Double, val yTop: Double, val yBot: Double)

/** One week's stackable values, keyed by series. */
data class StackedWeek(val weekStart: String, val values: Map<String, Double>)

data class BarSegment(val key: String, val x: Double, val y: Double, val w: Double, val h: Double)

data class BarColumn(val weekStart: String, val segs: List<BarSegment>, val zeroY: Double)

/** One week of adherence counts, as the ribbon reads it. */
data class RibbonWeek(
    val weekStart: String,
    val scheduledDays: Int,
    val met: Int,
    val partialDays: Int,
    val missed: Int,
    val paused: Boolean,
)

data class RibbonCell(
    val x: Double,
    val w: Double,
    val muted: Boolean,
    val met: Double,
    val partial: Double,
    val missed: Double,
    val weekStart: String,
)

/** A dated value, nullable where the day has no reading. */
data class DatedValue(val date: DateString, val value: Double?)

// ---- Scales ----------------------------------------------------------------

/**
 * Linear scale mapping `[d0, d1]` onto `[r0, r1]`, unclamped.
 *
 * A degenerate domain maps everything to the range midpoint, so a single-point
 * series renders centred instead of dividing by zero.
 */
fun linearScale(d0: Double, d1: Double, r0: Double, r1: Double): (Double) -> Double {
    if (d0 == d1) {
        val mid = (r0 + r1) / 2
        return { mid }
    }
    val k = (r1 - r0) / (d1 - d0)
    return { v -> r0 + (v - d0) * k }
}

/**
 * A tracker value as a finite number, or null.
 *
 * The one entry point for `TrackerValue.value`. A tracker converted away from
 * type `note` keeps free-text entries — the column is REAL-affinity, so the
 * text survives — and a single string in a series must not poison every scale,
 * mean and path it touches. Booleans are explicitly not values.
 *
 * The `isFinite` check after parsing is what makes Kotlin and JS agree:
 * `"Infinity".toDoubleOrNull()` succeeds here, and JS's `Number('Infinity')`
 * likewise parses, but neither is a value a chart can use.
 */
fun coerceNumeric(value: JsonElement?): Double? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    if (primitive.isString) {
        val text = primitive.content.trim()
        if (text.isEmpty()) return null
        val parsed = text.toDoubleOrNull() ?: return null
        return if (parsed.isFinite()) parsed else null
    }
    if (primitive.booleanOrNull != null) return null
    val number = primitive.doubleOrNull ?: return null
    return if (number.isFinite()) number else null
}

/**
 * Day offset of one local `YYYY-MM-DD` from another.
 *
 * Epoch-day subtraction only: an `Instant`/`Duration` route would drag a
 * timezone into arithmetic that is pure calendar days on both sides.
 */
fun dayIndex(date: DateString, origin: DateString): Int =
    (LocalDate.parse(date).toEpochDay() - LocalDate.parse(origin).toEpochDay()).toInt()

/**
 * "Nice" tick values covering `[min, max]`: 1/2/5 × 10ⁿ steps landing near
 * [targetCount] ticks, all of them inside the domain.
 *
 * The `step / 1e6` slack in the loop condition and the per-tick re-round are
 * both load-bearing: without them, accumulated floating-point error either
 * drops the top tick or prints one as `0.30000000000000004`.
 */
fun niceTicks(min: Double, max: Double, targetCount: Int = 4): List<Double> {
    if (!(max > min)) return listOf(min)
    val rawStep = (max - min) / max(1, targetCount)
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val normalized = rawStep / magnitude
    val step = (if (normalized <= 1) 1.0 else if (normalized <= 2) 2.0 else if (normalized <= 5) 5.0 else 10.0) *
        magnitude
    val ticks = mutableListOf<Double>()
    var tick = ceil(min / step) * step
    while (tick <= max + step / 1e6) {
        ticks += fixed10(tick)
        tick += step
    }
    return ticks.ifEmpty { listOf(min) }
}

// ---- Series geometry -------------------------------------------------------

/**
 * Map records to plot points, skipping any whose [accessor] returns null.
 *
 * The output is deliberately **not** index-aligned with the input; each point
 * carries its own record instead.
 */
fun <T> seriesToPoints(
    records: List<T>,
    xValue: (T) -> Double,
    accessor: (T) -> Double?,
    xScale: (Double) -> Double,
    yScale: (Double) -> Double,
): List<ChartPoint<T>> {
    val points = mutableListOf<ChartPoint<T>>()
    for (record in records) {
        val value = accessor(record) ?: continue
        points += ChartPoint(x = xScale(xValue(record)), y = yScale(value), raw = record)
    }
    return points
}

/**
 * An SVG-style `"M x y L x y"` path through [points]; empty below two points,
 * where a lone dot is the component's job rather than the line's.
 */
fun linePath(points: List<ChartPoint<*>>): String {
    if (points.size < 2) return ""
    return points.mapIndexed { index, point ->
        val command = if (index == 0) "M" else "L"
        "$command ${jsNumberString(round2(point.x))} ${jsNumberString(round2(point.y))}"
    }.joinToString(" ")
}

/**
 * One rectangle per effective-dated segment, clipped to `[xStart, xEnd]`.
 *
 * A null `max` clamps to [yTopEdge] and a null min to [yBotEdge], so a
 * one-sided target renders as an open-ended band. Gaps between segments simply
 * produce no rect, and a segment entirely outside the window produces none
 * either. The min/max swap at the end tolerates the inverted y scales these
 * charts are built with.
 */
fun steppedBandRects(
    segments: List<BandSegment>,
    xStart: Double,
    xEnd: Double,
    xScale: (Double) -> Double,
    yScale: (Double) -> Double,
    yTopEdge: Double,
    yBotEdge: Double,
): List<BandRect> {
    val rects = mutableListOf<BandRect>()
    for (segment in segments) {
        val x0 = xScale(max(segment.x0, xStart))
        val x1 = xScale(min(segment.x1, xEnd))
        if (!(x1 > x0)) continue
        val yTop = segment.max?.let(yScale) ?: yTopEdge
        val yBot = segment.min?.let(yScale) ?: yBotEdge
        rects += BandRect(
            x = round2(x0),
            w = round2(x1 - x0),
            yTop = round2(min(yTop, yBot)),
            yBot = round2(max(yTop, yBot)),
        )
    }
    return rects
}

/**
 * Stacked bars, bottom-up in [keys] order.
 *
 * [xScale] takes the week's **index**, not a date — the Sleep card exploits
 * that with a closure mapping index → day index → x, which is what lets daily
 * bars reuse this. Non-positive values are dropped entirely, so a zero week
 * keeps its slot but draws nothing.
 */
fun stackedBarLayout(
    weeks: List<StackedWeek>,
    keys: List<String>,
    xScale: (Int) -> Double,
    yScale: (Double) -> Double,
    barWidth: Double,
): List<BarColumn> {
    val zeroY = yScale(0.0)
    return weeks.mapIndexed { index, week ->
        var accumulated = 0.0
        val segments = mutableListOf<BarSegment>()
        for (key in keys) {
            val value = week.values[key] ?: 0.0
            if (value <= 0) continue
            val y0 = yScale(accumulated)
            val y1 = yScale(accumulated + value)
            segments += BarSegment(
                key = key,
                x = round2(xScale(index) - barWidth / 2),
                y = round2(min(y0, y1)),
                w = round2(barWidth),
                h = round2(abs(y0 - y1)),
            )
            accumulated += value
        }
        BarColumn(weekStart = week.weekStart, segs = segments, zeroY = round2(zeroY))
    }
}

/**
 * Weekly adherence cells with met/partial/missed as fractions of what was
 * scheduled. A paused week, or one with nothing scheduled, is muted rather
 * than empty — the distinction between "did not do it" and "was not asked to"
 * is the whole point of the ribbon.
 */
fun ribbonCells(
    weeks: List<RibbonWeek>,
    xScale: (Int) -> Double,
    cellWidth: Double,
): List<RibbonCell> = weeks.mapIndexed { index, week ->
    val total = week.scheduledDays
    val muted = week.paused || total == 0
    RibbonCell(
        x = round2(xScale(index) - cellWidth / 2),
        w = round2(cellWidth),
        muted = muted,
        met = if (muted) 0.0 else week.met.toDouble() / total,
        partial = if (muted) 0.0 else week.partialDays.toDouble() / total,
        missed = if (muted) 0.0 else week.missed.toDouble() / total,
        weekStart = week.weekStart,
    )
}

/**
 * Collapse a date-ascending daily series into stepped band segments.
 *
 * Consecutive items sharing a `[low, high]` merge into one segment, and **runs
 * survive missing days** — only an item whose band is null or incomplete closes
 * the current run. Built for HRV, where Garmin's baseline moves slowly and
 * per-day rectangles would be hundreds of slivers.
 */
fun <T> dailyBandSegments(
    items: List<T>,
    xOf: (T) -> Double,
    bandOf: (T) -> BandBounds?,
): List<BandSegment> {
    val segments = mutableListOf<BandSegment>()
    var current: BandSegment? = null
    for (item in items) {
        val band = bandOf(item)
        val low = band?.low
        val high = band?.high
        if (low == null || high == null) {
            current = null
            continue
        }
        val x = xOf(item)
        val open = current
        if (open != null && open.min == low && open.max == high) {
            val extended = open.copy(x1 = x + 1)
            segments[segments.lastIndex] = extended
            current = extended
        } else {
            val started = BandSegment(x0 = x, x1 = x + 1, min = low, max = high)
            segments += started
            current = started
        }
    }
    return segments
}

/**
 * A `"x,y x,y"` polyline for a sparkline of raw values in a `w × h` box.
 *
 * The x axis spans the **full** list including nulls, so a gap reads as a gap
 * rather than closing up; empty below two present values.
 */
fun sparklinePoints(values: List<Double?>, w: Double, h: Double): String {
    val present = values.filterNotNull()
    if (present.size < 2) return ""
    val xs = linearScale(0.0, (values.size - 1).toDouble(), 0.0, w)
    val ys = linearScale(present.min(), present.max(), h, 0.0)
    return values.mapIndexedNotNull { index, value ->
        value?.let { "${jsNumberString(round2(xs(index.toDouble())))},${jsNumberString(round2(ys(it)))}" }
    }.joinToString(" ")
}

/**
 * Date-aware trailing rolling mean.
 *
 * The output is the **same length** as the input with nulls preserved, so
 * callers filter rather than re-align. Gap days contribute nothing: the mean is
 * over values that were actually logged inside the window, never diluted by
 * days that were not.
 */
fun rollingMean(daily: List<DatedValue>, windowDays: Int): List<DatedValue> {
    val out = mutableListOf<DatedValue>()
    for (index in daily.indices) {
        val endDate = daily[index].date
        var sum = 0.0
        var count = 0
        for (back in index downTo 0) {
            if (dayIndex(endDate, daily[back].date) >= windowDays) break
            val value = daily[back].value
            if (value != null) {
                sum += value
                count += 1
            }
        }
        out += DatedValue(endDate, if (count > 0) sum / count else null)
    }
    return out
}

/**
 * Dot radius by value, scaled so **area** is proportional: a session twice as
 * long reads as twice the ink, not twice the radius. Empty input pins every dot
 * at [minR]; a constant input puts them all at the midpoint.
 */
fun dotSizeScale(minR: Double, maxR: Double, values: List<Double?>): (Double) -> Double {
    val present = values.filterNotNull()
    if (present.isEmpty()) return { minR }
    val lo = present.min()
    val hi = present.max()
    if (lo == hi) return { (minR + maxR) / 2 }
    return { v -> minR + (maxR - minR) * sqrt((v - lo) / (hi - lo)) }
}

// ---- Rounding --------------------------------------------------------------

/**
 * JavaScript's `Math.round`: ties go **up, toward +∞**, so `-2.5` rounds to
 * `-2`, not `-3`.
 *
 * Kotlin's `Double.roundToLong` has exactly these semantics. `kotlin.math.round`
 * does not — it rounds half to even — and quietly disagrees on every negative
 * half, which is where a percentage delta lives.
 */
fun jsRound(value: Double): Long = value.roundToLong()

/** Two decimal places, JS-round semantics. Non-finite input passes through. */
fun round2(value: Double): Double =
    if (value.isFinite()) jsRound(value * 100) / 100.0 else value

/**
 * `Number(v.toFixed(10))` — strip the floating-point noise that accumulates
 * across repeated tick additions.
 *
 * ECMA rounds the tie to the *larger* value, which is HALF_UP above zero and
 * HALF_DOWN below it. The distinction cannot matter at the tenth decimal of a
 * chart tick, but writing it down is cheaper than wondering later.
 */
private fun fixed10(value: Double): Double {
    if (!value.isFinite()) return value
    val mode = if (value < 0) RoundingMode.HALF_DOWN else RoundingMode.HALF_UP
    return BigDecimal(value).setScale(10, mode).toDouble()
}

/**
 * A number the way JavaScript stringifies it: no decimal part when the value is
 * integral, `'.'` as the separator, and `-0` printed as `0`.
 *
 * One helper for both jobs — serializing path coordinates and labelling axes,
 * legends and tooltips — because a tick reading `1` above a path that says
 * `1.0` is the kind of mismatch nobody notices until a screenshot.
 */
fun jsNumberString(value: Double): String {
    if (!value.isFinite()) return value.toString()
    if (value == 0.0) return "0"
    val asLong = value.toLong()
    if (asLong.toDouble() == value) return asLong.toString()
    return BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
}

/** The display alias. Same rendering, read at the callsite as formatting. */
fun formatNum(value: Double): String = jsNumberString(value)
