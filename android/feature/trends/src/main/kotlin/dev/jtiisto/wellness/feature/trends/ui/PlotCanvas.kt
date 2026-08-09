package dev.jtiisto.wellness.feature.trends.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.ui.chart.ChartScrubTooltip
import dev.jtiisto.wellness.core.ui.chart.ChartTheme
import dev.jtiisto.wellness.core.ui.chart.chartGuideDash
import dev.jtiisto.wellness.core.ui.chart.chartScrub
import dev.jtiisto.wellness.core.ui.chart.rememberChartScrubState
import dev.jtiisto.wellness.core.ui.chart.rememberChartTheme
import dev.jtiisto.wellness.core.ui.theme.ModuleAccent
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.core.ui.theme.colors
import dev.jtiisto.wellness.feature.trends.chart.LOGICAL_WIDTH
import dev.jtiisto.wellness.feature.trends.chart.LabelAlign
import dev.jtiisto.wellness.feature.trends.chart.LogicalScale
import dev.jtiisto.wellness.feature.trends.chart.PlotLabel
import dev.jtiisto.wellness.feature.trends.chart.PlotModel
import dev.jtiisto.wellness.feature.trends.chart.PlotTone

/**
 * Every Trends chart, drawn.
 *
 * The composable takes a finished [PlotModel] and does three things with it:
 * scales logical coordinates to pixels, resolves tones to colours, and hands
 * the anchor positions to the scrub state. No geometry decisions happen here,
 * which is why there is one of these rather than fourteen.
 */

/** [PlotTone] resolved against the current theme. */
@Immutable
class PlotColors(
    private val primary: Color,
    private val alt: Color,
    private val secondary: Color,
    private val scan: Color,
    private val value: Color,
    private val warn: Color,
    private val muted: Color,
    private val band: Color,
    private val met: Color,
    private val partial: Color,
    private val missed: Color,
    private val inProgress: Color,
    private val stackThird: Color,
    private val stackOther: Color,
) {
    fun of(tone: PlotTone): Color = when (tone) {
        PlotTone.PRIMARY, PlotTone.BAR, PlotTone.STACK_0 -> primary
        PlotTone.ALT, PlotTone.STACK_1 -> alt
        PlotTone.SECONDARY -> secondary
        PlotTone.SCAN -> scan
        PlotTone.VALUE -> value
        PlotTone.WARN -> warn
        PlotTone.MUTED -> muted
        PlotTone.BAND -> band
        PlotTone.MET -> met
        PlotTone.PARTIAL -> partial
        PlotTone.MISSED -> missed
        PlotTone.IN_PROGRESS -> inProgress
        PlotTone.STACK_2 -> stackThird
        PlotTone.STACK_OTHER -> stackOther
    }
}

/**
 * Series colours come from [ChartTheme], which already fixes the policy: the
 * module accent leads, and a second series borrows Analysis violet rather than
 * inventing a hue. A stacked chart needs a third, so it borrows Journal amber
 * the same way — four modules' worth of accent is the whole colour vocabulary
 * this design has, and inventing a fifth for a bar chart would break it.
 */
@Composable
@ReadOnlyComposable
fun rememberPlotColors(): PlotColors {
    val theme = rememberChartTheme()
    val palette = WellnessTheme.palette
    return PlotColors(
        primary = theme.line,
        alt = theme.altLine,
        secondary = theme.line.copy(alpha = 0.45f),
        scan = theme.altLine,
        // Raw readings sit under their own rolling mean: same colour, less of it.
        value = theme.point.copy(alpha = 0.6f),
        warn = palette.warning,
        muted = palette.textFaint,
        band = theme.bandFill,
        met = palette.success,
        partial = palette.warning,
        // The base a ribbon cell's fills are painted over, not a semantic red:
        // a missed day is information, not an error.
        missed = palette.line,
        inProgress = palette.canvas.copy(alpha = 0.4f),
        stackThird = ModuleAccent.JOURNAL.colors(palette).fill,
        stackOther = palette.textFaint,
    )
}

/** Bars and bands of an unfinished week, drawn back. */
private const val PARTIAL_ALPHA = 0.45f

/**
 * Draw [model], and let the finger read it.
 *
 * [identity] is what the chart is *of* — endpoint, range, selection. When it
 * changes, the scrub and any pin are dropped before the new anchors are
 * adopted: a tooltip pinned to the 3rd of July must never quietly become the
 * 10th because the range changed underneath it.
 *
 * [touchPadding] widens the gesture area vertically without changing what is
 * drawn, which is what makes a 56dp strip scrubbable by a thumb.
 */
@Composable
fun PlotCanvas(
    model: PlotModel,
    identity: Any,
    modifier: Modifier = Modifier,
    scrubEnabled: Boolean = true,
    touchPadding: Dp = 0.dp,
) {
    val scrub = rememberChartScrubState()
    val colors = rememberPlotColors()
    val theme = rememberChartTheme()
    val measurer = rememberTextMeasurer()
    var widthPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(identity, widthPx, model.anchors) {
        scrub.endScrub()
        scrub.clearPin()
        if (widthPx > 0f) {
            scrub.updateAnchors(LogicalScale(widthPx).anchorsPx(model.anchors))
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (scrubEnabled) Modifier.chartScrub(scrub) else Modifier)
            .padding(vertical = touchPadding),
    ) {
        val displayed = scrub.displayIndex?.takeIf { it in model.anchors.indices }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio((LOGICAL_WIDTH / model.height).toFloat())
                .onSizeChanged { widthPx = it.width.toFloat() },
        ) {
            val scale = LogicalScale(size.width)
            drawPlot(model, scale, colors, theme, measurer)
            if (displayed != null) {
                val x = scale.px(model.anchors[displayed].x)
                drawLine(
                    color = theme.point,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = theme.gridWidth.toPx(),
                )
            }
        }
        if (displayed != null) {
            val anchor = model.anchors[displayed]
            ChartScrubTooltip(
                state = scrub,
                label = anchor.label,
                values = anchor.rows.map { it.label to it.value },
                anchor = IntOffset(LogicalScale(widthPx).px(anchor.x).toInt(), 0),
            )
        }
    }
}

private fun DrawScope.drawPlot(
    model: PlotModel,
    scale: LogicalScale,
    colors: PlotColors,
    theme: ChartTheme,
    measurer: TextMeasurer,
) {
    for (rect in model.rects) {
        val color = colors.of(rect.tone)
        drawRoundRect(
            color = if (rect.partial) color.copy(alpha = color.alpha * PARTIAL_ALPHA) else color,
            topLeft = Offset(scale.px(rect.x), scale.px(rect.y)),
            size = Size(scale.px(rect.w), scale.px(rect.h)),
            cornerRadius = CornerRadius(rect.radius.toPx()),
        )
    }
    for (line in model.gridlines) {
        drawLine(
            color = theme.grid,
            start = Offset(scale.px(line.x0), scale.px(line.y)),
            end = Offset(scale.px(line.x1), scale.px(line.y)),
            strokeWidth = theme.gridWidth.toPx(),
        )
    }
    for (guide in model.guides) {
        drawLine(
            color = theme.guide,
            start = Offset(scale.px(guide.x0), scale.px(guide.y)),
            end = Offset(scale.px(guide.x1), scale.px(guide.y)),
            strokeWidth = theme.guideWidth.toPx(),
            pathEffect = if (guide.dashed) chartGuideDash else null,
        )
    }
    if (model.dotsBelowLines) drawPlotDots(model, scale, colors, theme)
    for (line in model.lines) {
        if (line.points.size < 2) continue
        val path = Path()
        line.points.forEachIndexed { index, point ->
            val x = scale.px(point.x)
            val y = scale.px(point.y)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = colors.of(line.tone),
            style = Stroke(
                width = if (line.tone == PlotTone.PRIMARY) {
                    theme.lineWidth.toPx()
                } else {
                    theme.altLineWidth.toPx()
                },
            ),
        )
    }
    if (!model.dotsBelowLines) drawPlotDots(model, scale, colors, theme)
    for (label in model.labels) {
        drawPlotLabel(label, scale, theme, measurer)
    }
}

/** A muted point is outlined rather than filled — present, but not counted. */
private fun DrawScope.drawPlotDots(
    model: PlotModel,
    scale: LogicalScale,
    colors: PlotColors,
    theme: ChartTheme,
) {
    for (dot in model.dots) {
        val center = Offset(scale.px(dot.x), scale.px(dot.y))
        val radius = dot.radius.toPx()
        if (dot.muted) {
            drawCircle(colors.of(dot.tone), radius, center, style = Stroke(theme.gridWidth.toPx() * 2))
        } else {
            drawCircle(colors.of(dot.tone), radius, center)
        }
    }
}

private fun DrawScope.drawPlotLabel(
    label: PlotLabel,
    scale: LogicalScale,
    theme: ChartTheme,
    measurer: TextMeasurer,
) {
    val style = theme.tickStyle.copy(color = theme.tickColor)
    val measured = measurer.measure(label.text, style)
    val x = scale.px(label.x) - when (label.align) {
        LabelAlign.START -> 0f
        LabelAlign.CENTER -> measured.size.width / 2f
        LabelAlign.END -> measured.size.width.toFloat()
    }
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(x, scale.px(label.y) - measured.size.height / 2f),
    )
}

/**
 * A sparkline: no axes, no scrub, no anchors — the whole tile is the tap
 * target, so a second gesture on top of it would fight the navigation.
 */
@Composable
fun Sparkline(points: String, modifier: Modifier = Modifier) {
    val theme = rememberChartTheme()
    val parsed = remember(points) { parseSparkline(points) }
    if (parsed.size < 2) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawWithCache {
                val scaleX = size.width / SPARKLINE_LOGICAL_W
                val scaleY = size.height / SPARKLINE_LOGICAL_H
                val path = Path()
                parsed.forEachIndexed { index, (x, y) ->
                    val px = x * scaleX
                    val py = y * scaleY
                    if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                onDrawBehind {
                    drawPath(path, theme.line, style = Stroke(theme.lineWidth.toPx()))
                }
            },
    )
}

private const val SPARKLINE_LOGICAL_W = 96f
private const val SPARKLINE_LOGICAL_H = 26f

/** `"x,y x,y"` back into points — the same string the geometry port emits. */
internal fun parseSparkline(points: String): List<Pair<Float, Float>> =
    points.split(' ').mapNotNull { pair ->
        val parts = pair.split(',')
        if (parts.size != 2) return@mapNotNull null
        val x = parts[0].toFloatOrNull() ?: return@mapNotNull null
        val y = parts[1].toFloatOrNull() ?: return@mapNotNull null
        x to y
    }
