package dev.jtiisto.wellness.feature.trends.chart

import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.trends.OverviewDto
import dev.jtiisto.wellness.core.data.trends.PrSummary
import dev.jtiisto.wellness.core.data.trends.WeightPoint

/** The Overview screen's four cards, as data. */

// ---- Stat tiles ------------------------------------------------------------

/**
 * A headline tile.
 *
 * [headline] is the **last complete** week, which is what makes the delta
 * honest: comparing a Tuesday against a four-week average would report a
 * collapse every Tuesday. The week in progress gets its own delta-free line.
 */
data class StatTileModel(
    val label: String,
    val unit: String,
    val headline: String,
    val avgLine: String?,
    val soFarLine: String,
    val sparkline: String,
    /** Which sub-screen a tap opens. */
    val target: String,
)

private const val SPARKLINE_W = 96.0
private const val SPARKLINE_H = 26.0

fun overviewStatTiles(overview: OverviewDto): List<StatTileModel> = listOf(
    statTile(
        label = "Zone 2 last week",
        unit = "min",
        value = overview.zone2.lastWeekMin,
        avg = overview.zone2.fourWeekAvgMin,
        soFar = overview.zone2.thisWeekMin,
        spark = overview.zone2.sparkline.map { it.plannedMin + it.extraMin },
        target = "cardio",
    ),
    statTile(
        label = "Tonnage last week",
        unit = "kg",
        value = overview.tonnage.lastWeekKg,
        avg = overview.tonnage.fourWeekAvgKg,
        soFar = overview.tonnage.thisWeekKg,
        spark = overview.tonnage.sparkline.map { it.tonnageKg },
        target = "strength",
    ),
)

private fun statTile(
    label: String,
    unit: String,
    value: Double?,
    avg: Double?,
    soFar: Double,
    spark: List<Double>,
    target: String,
): StatTileModel {
    val delta = statTileDelta(value, avg)
    return StatTileModel(
        label = label,
        unit = unit,
        headline = jsNumberString(value ?: 0.0),
        avgLine = avg?.let {
            val suffix = delta?.let { d -> " · ${if (d >= 0) "+" else ""}$d%" }.orEmpty()
            "4wk avg ${jsNumberString(it)}$suffix"
        },
        soFarLine = "this week so far: ${jsNumberString(soFar)} $unit",
        sparkline = sparklinePoints(spark, SPARKLINE_W, SPARKLINE_H),
        target = target,
    )
}

// ---- PR tile ---------------------------------------------------------------

data class PrTileModel(val badge: String, val latest: String?)

/** Null when nothing was set in the last thirty days — the tile hides entirely. */
fun prTileModel(prs: PrSummary): PrTileModel? {
    if (prs.count30d <= 0) return null
    val plural = if (prs.count30d == 1) "" else "s"
    return PrTileModel(
        badge = "🏆 ${prs.count30d} PR$plural in 30d",
        latest = prs.latest?.let {
            "latest: ${it.name} e1RM ${jsNumberString(it.e1rm)} ${it.unit} (${it.date})"
        },
    )
}

// ---- Adherence focus -------------------------------------------------------

data class FocusDayModel(val date: DateString, val status: String)

data class FocusRowModel(
    val trackerId: String,
    val name: String,
    val rate: String,
    val dropping: Boolean,
    val ribbon: List<FocusDayModel>,
)

fun focusRowModels(overview: OverviewDto): List<FocusRowModel> =
    overview.adherenceFocus.map { row ->
        FocusRowModel(
            trackerId = row.trackerId,
            name = row.name,
            rate = "${jsRound(row.rate * 100)}% ${row.metricKind}",
            dropping = row.dropping,
            ribbon = row.ribbon.map { FocusDayModel(it.date, it.status) },
        )
    }

// ---- Weight card -----------------------------------------------------------

data class WeightCardModel(val plot: PlotModel, val latest: String)

private val WEIGHT_MARGINS = Margins(top = 10.0, right = 10.0, bottom = 22.0, left = 40.0)
private const val WEIGHT_HEIGHT = 200.0

/**
 * Body weight with its seven- and twenty-eight-day means.
 *
 * The y padding is a fraction of the *observed* spread rather than a fixed
 * number of kilos: over a fortnight the whole series might move 400 g, and a
 * 2 kg pad would flatten it into a straight line. A series that genuinely does
 * not move falls back to half a kilo so the dots do not sit on the axis.
 */
fun weightCardModel(series: List<WeightPoint>): WeightCardModel? {
    if (series.isEmpty()) return null

    val origin = series.first().date
    val xs = series.map { dayIndex(it.date, origin).toDouble() }
    val ys = series.map { it.kg }
    val pad = firstNonZero((ys.max() - ys.min()) * 0.15, 0.5)

    val xScale = linearScale(xs.min(), xs.max(), WEIGHT_MARGINS.left, LOGICAL_WIDTH - WEIGHT_MARGINS.right)
    val yScale = linearScale(
        ys.min() - pad,
        ys.max() + pad,
        WEIGHT_HEIGHT - WEIGHT_MARGINS.bottom,
        WEIGHT_MARGINS.top,
    )

    val dated = series.map { DatedValue(it.date, it.kg) }
    val mean7 = rollingMean(dated, 7)
    val mean28 = rollingMean(dated, 28)

    val dots = seriesToPoints(series, { dayIndex(it.date, origin).toDouble() }, { it.kg }, xScale, yScale)
    val (gridlines, yLabels) = yAxisMarks(
        yMin = ys.min() - pad,
        yMax = ys.max() + pad,
        yScale = yScale,
        x0 = WEIGHT_MARGINS.left,
        x1 = LOGICAL_WIDTH - WEIGHT_MARGINS.right,
        format = ::fixed1,
    )
    val ticks = dateTicks(series, { it.date }, origin)

    val anchors = mergeScrubAnchors(
        series.mapIndexed { index, point ->
            AnchorContribution(
                key = point.date,
                x = xScale(dayIndex(point.date, origin).toDouble()),
                label = monthDay(point.date),
                rows = buildList {
                    add(TooltipRow("kg", jsNumberString(point.kg)))
                    mean7[index].value?.let { add(TooltipRow("7d", fixed1(it))) }
                    mean28[index].value?.let { add(TooltipRow("28d", fixed1(it))) }
                },
            )
        },
    )

    return WeightCardModel(
        plot = PlotModel(
            height = WEIGHT_HEIGHT,
            gridlines = gridlines,
            lines = listOfNotNull(
                meanLine(mean28, origin, xScale, yScale, PlotTone.ALT),
                meanLine(mean7, origin, xScale, yScale, PlotTone.PRIMARY),
            ),
            dots = dots.map { PlotDot(it.x, it.y, 2.dp, PlotTone.VALUE) },
            labels = yLabels + xAxisLabels(ticks, xScale, WEIGHT_HEIGHT),
            anchors = anchors,
        ),
        latest = "${jsNumberString(series.last().kg)} kg",
    )
}

/**
 * A rolling-mean series as a polyline, or null when fewer than two days have
 * one. The nulls are dropped here rather than inside [rollingMean], which
 * keeps its output aligned with its input for exactly this reason.
 */
internal fun meanLine(
    mean: List<DatedValue>,
    origin: DateString,
    xScale: (Double) -> Double,
    yScale: (Double) -> Double,
    tone: PlotTone,
): PlotLine? {
    val present = mean.filter { it.value != null }
    if (present.size < 2) return null
    return PlotLine(
        points = present.map {
            ChartPoint(
                x = xScale(dayIndex(it.date, origin).toDouble()),
                y = yScale(it.value!!),
                raw = Unit,
            )
        },
        tone = tone,
    )
}
