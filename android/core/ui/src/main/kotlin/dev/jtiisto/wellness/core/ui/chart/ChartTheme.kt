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
import dev.jtiisto.wellness.core.ui.theme.ModuleAccent
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.core.ui.theme.colors

/**
 * How a chart is drawn.
 *
 * The primary series is the module's accent, because a chart in the Trends tab
 * is Trends data; a second series borrows Analysis violet rather than inventing
 * a colour, so two lines never read as two modules. Everything else is
 * structure: grid, ticks, guides — all neutral, all quiet.
 */
@Immutable
data class ChartTheme(
    val line: Color,
    val lineWidth: Dp,
    val altLine: Color,
    val altLineWidth: Dp,
    val grid: Color,
    val gridWidth: Dp,
    val tickStyle: TextStyle,
    val tickColor: Color,
    /** Fill under the line, and the band behind a highlighted range. */
    val bandFill: Color,
    val guide: Color,
    val guideWidth: Dp,
    /** The endpoint dot, and the scrub marker. */
    val point: Color,
    val pointRadius: Dp,
    val tooltipSurface: Color,
)

/** 5 on, 3 off — a guide you can tell from a series at a glance. */
val chartGuideDash: PathEffect
    get() = PathEffect.dashPathEffect(floatArrayOf(5f, 3f))

@Composable
@ReadOnlyComposable
fun rememberChartTheme(): ChartTheme {
    val palette = WellnessTheme.palette
    val accent = WellnessTheme.accent
    return ChartTheme(
        line = accent.fill,
        lineWidth = 1.5.dp,
        altLine = ModuleAccent.ANALYSIS.colors(palette).fill.copy(alpha = 0.9f),
        altLineWidth = 1.25.dp,
        grid = palette.line.copy(alpha = 0.6f),
        gridWidth = 0.5.dp,
        // Ticks are numbers, so they take the tabular label style rather than
        // the uppercase taxonomy one, at the ramp's smallest size.
        tickStyle = WellnessTheme.type.label.copy(fontSize = 11.sp),
        tickColor = palette.textFaint,
        bandFill = accent.chipFill,
        guide = palette.line,
        guideWidth = 1.dp,
        point = accent.fill,
        pointRadius = 3.dp,
        tooltipSurface = palette.card,
    )
}
