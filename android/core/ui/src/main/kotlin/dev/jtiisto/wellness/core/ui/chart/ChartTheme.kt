package dev.jtiisto.wellness.core.ui.chart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme

/**
 * How a chart is drawn, in Logbook.
 *
 * Everything here is *structure* — grid, ticks, guides, the scrub marker, the
 * tooltip's paper — and structure has no colour in this system. Which series a
 * mark belongs to is decided one layer up, by `PlotTone`, and only ever answers
 * in ink or in a plate; nothing on this side of the line ever does.
 *
 * Trends is the only consumer, so the whole theme moved with it rather than
 * growing a second mode.
 */
@Immutable
data class ChartTheme(
    /** A single-series stroke — the sparkline, which spends no colour at all. */
    val line: Color,
    val lineWidth: Dp,
    val altLineWidth: Dp,
    val grid: Color,
    val gridWidth: Dp,
    val tickStyle: TextStyle,
    val tickColor: Color,
    val guide: Color,
    val guideWidth: Dp,
    /** The scrub marker: the vertical rule under the finger. */
    val point: Color,
)

/** 5 on, 3 off — a guide you can tell from a series at a glance. */
val chartGuideDash: PathEffect
    get() = PathEffect.dashPathEffect(floatArrayOf(5f, 3f))

/** A 7-day mean: the dash a reader reads as "derived", not as a second reading. */
val chartMeanDash: PathEffect
    get() = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))

/** A 28-day mean: the same statement, further back. */
val chartFaintMeanDash: PathEffect
    get() = PathEffect.dashPathEffect(floatArrayOf(2f, 3f))

@Composable
@ReadOnlyComposable
fun rememberChartTheme(): ChartTheme {
    val palette = LogbookTheme.palette
    return ChartTheme(
        line = palette.ink,
        lineWidth = 1.5.dp,
        altLineWidth = 1.25.dp,
        grid = palette.rule,
        // One hairline, the same 1dp every rule in the system is drawn at.
        gridWidth = 1.dp,
        // Ticks are numbers, so they are mono — at the smallest size the ramp
        // goes, because an axis label is the quietest number on the page.
        tickStyle = LogbookTheme.type.meta.copy(fontSize = 9.5.sp, lineHeight = 12.sp),
        tickColor = palette.inkFaint,
        guide = palette.inkFaint,
        guideWidth = 1.dp,
        point = palette.ink,
    )
}
