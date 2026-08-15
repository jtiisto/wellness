package dev.jtiisto.wellness.feature.trends.chart

import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.trends.CardioDto
import dev.jtiisto.wellness.core.data.trends.SteadySession

/** The Cardio screen's two cards, as data. */

// ---- Weekly Zone 2 ---------------------------------------------------------

const val ZONE2_PLANNED_KEY = "planned"
const val ZONE2_EXTRA_KEY = "extra"

data class Zone2CardModel(val plot: PlotModel?, val legend: List<LegendEntry>)

/**
 * Planned versus extra Zone 2 minutes per week.
 *
 * The interval count is a legend line rather than a series: intervals are not
 * Zone 2 and stacking them here would answer a question nobody asked, but
 * knowing they happened explains a light Zone 2 week.
 */
fun zone2CardModel(cardio: CardioDto): Zone2CardModel {
    val weeks = cardio.weeks.map {
        StackedWeek(
            weekStart = it.weekStart,
            values = mapOf(ZONE2_PLANNED_KEY to it.zone2PlannedMin, ZONE2_EXTRA_KEY to it.zone2ExtraMin),
        )
    }
    val plot = barChartModel(
        weeks = weeks,
        keys = listOf(ZONE2_PLANNED_KEY, ZONE2_EXTRA_KEY),
        tones = mapOf(ZONE2_PLANNED_KEY to PlotTone.STACK_0, ZONE2_EXTRA_KEY to PlotTone.SECONDARY),
        partial = cardio.weeks.map { it.partial },
        anchorsOf = { xScale, _ ->
            mergeScrubAnchors(
                cardio.weeks.mapIndexed { index, week ->
                    AnchorContribution(
                        key = week.weekStart,
                        x = xScale(index),
                        label = monthDay(week.weekStart),
                        rows = buildList {
                            add(TooltipRow("planned", "${jsNumberString(week.zone2PlannedMin)} min"))
                            add(TooltipRow("extra", "${jsNumberString(week.zone2ExtraMin)} min"))
                            add(
                                TooltipRow(
                                    "total",
                                    "${jsNumberString(week.zone2PlannedMin + week.zone2ExtraMin)} min",
                                ),
                            )
                            if (week.intervalSessions > 0) {
                                add(TooltipRow("intervals", week.intervalSessions.toString()))
                            }
                        },
                    )
                },
            )
        },
    )

    val totalIntervals = cardio.weeks.sumOf { it.intervalSessions }
    val legend = buildList {
        add(LegendEntry("planned", PlotTone.STACK_0))
        add(LegendEntry("extra", PlotTone.SECONDARY))
        if (totalIntervals > 0) {
            val plural = if (totalIntervals == 1) "" else "s"
            add(LegendEntry("$totalIntervals interval session$plural in range", PlotTone.MUTED))
        }
    }
    return Zone2CardModel(plot = plot, legend = legend)
}

// ---- Aerobic proxy ---------------------------------------------------------

private val AEROBIC_MARGINS = Margins(top = 10.0, right = 10.0, bottom = 22.0, left = 36.0)
private const val AEROBIC_HEIGHT = 190.0

/** Fixed, not proportional: four beats is four beats whatever the spread. */
private const val AEROBIC_HR_PAD = 4.0

const val AEROBIC_EMPTY_TEXT = "No steady sessions with HR in range"

/**
 * Average heart rate of steady sessions, dot size by duration.
 *
 * Deliberately humble: a proxy for aerobic base, not a lactate test. The dot
 * area carries duration because a 90-minute ride at 132 bpm and a 20-minute one
 * at the same rate are not the same evidence.
 */
fun aerobicProxyModel(sessions: List<SteadySession>): PlotModel? {
    if (sessions.isEmpty()) return null

    val origin = sessions.first().date
    val xs = sessions.map { dayIndex(it.date, origin).toDouble() }
    val hrs = sessions.map { it.avgHr }

    val xScale = linearScale(xs.min(), xs.max(), AEROBIC_MARGINS.left, LOGICAL_WIDTH - AEROBIC_MARGINS.right)
    val yScale = linearScale(
        hrs.min() - AEROBIC_HR_PAD,
        hrs.max() + AEROBIC_HR_PAD,
        AEROBIC_HEIGHT - AEROBIC_MARGINS.bottom,
        AEROBIC_MARGINS.top,
    )
    val radiusScale = dotSizeScale(2.5, 7.0, sessions.map { it.durationMin })

    val points = seriesToPoints(
        sessions,
        { dayIndex(it.date, origin).toDouble() },
        { it.avgHr },
        xScale,
        yScale,
    )
    val (gridlines, yLabels) = yAxisMarks(
        yMin = hrs.min() - AEROBIC_HR_PAD,
        yMax = hrs.max() + AEROBIC_HR_PAD,
        yScale = yScale,
        x0 = AEROBIC_MARGINS.left,
        x1 = LOGICAL_WIDTH - AEROBIC_MARGINS.right,
    )
    val ticks = dateTicks(sessions, { it.date }, origin)

    return PlotModel(
        height = AEROBIC_HEIGHT,
        gridlines = gridlines,
        dots = points.map {
            PlotDot(
                x = it.x,
                y = it.y,
                radius = radiusScale(it.raw.durationMin).dp,
                tone = PlotTone.PRIMARY,
                muted = it.raw.offPlan,
            )
        },
        labels = yLabels + xAxisLabels(ticks, xScale, AEROBIC_HEIGHT),
        anchors = mergeScrubAnchors(
            sessions.map { session ->
                AnchorContribution(
                    key = session.date,
                    x = xScale(dayIndex(session.date, origin).toDouble()),
                    label = monthDay(session.date),
                    rows = listOf(
                        TooltipRow("avg HR", "${jsNumberString(session.avgHr)} bpm"),
                        TooltipRow("duration", "${jsNumberString(session.durationMin)} min"),
                    ),
                )
            },
        ),
    )
}
