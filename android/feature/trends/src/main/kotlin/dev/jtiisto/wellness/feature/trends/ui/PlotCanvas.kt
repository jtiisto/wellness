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
import androidx.compose.ui.graphics.PathEffect
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
import dev.jtiisto.wellness.core.ui.chart.chartFaintMeanDash
import dev.jtiisto.wellness.core.ui.chart.chartGuideDash
import dev.jtiisto.wellness.core.ui.chart.chartMeanDash
import dev.jtiisto.wellness.core.ui.chart.chartScrub
import dev.jtiisto.wellness.core.ui.chart.rememberChartScrubState
import dev.jtiisto.wellness.core.ui.chart.rememberChartTheme
import dev.jtiisto.wellness.core.ui.theme.LogbookPalette
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.feature.trends.chart.ChartInk
import dev.jtiisto.wellness.feature.trends.chart.LOGICAL_WIDTH
import dev.jtiisto.wellness.feature.trends.chart.LabelAlign
import dev.jtiisto.wellness.feature.trends.chart.LogicalScale
import dev.jtiisto.wellness.feature.trends.chart.PlotLabel
import dev.jtiisto.wellness.feature.trends.chart.PlotModel
import dev.jtiisto.wellness.feature.trends.chart.PlotTone
import dev.jtiisto.wellness.feature.trends.chart.SeriesInk
import dev.jtiisto.wellness.feature.trends.chart.SeriesMark
import dev.jtiisto.wellness.feature.trends.chart.isOpenMark
import dev.jtiisto.wellness.feature.trends.chart.seriesStyle

/**
 * Every Trends chart, drawn.
 *
 * The composable takes a finished [PlotModel] and does three things with it:
 * scales logical coordinates to pixels, resolves tones to colours through the
 * chart's own [ChartInk] plan, and hands the anchor positions to the scrub
 * state. No geometry decisions happen here, which is why there is one of these
 * rather than fourteen.
 */

/**
 * The palette half of the vocabulary: [SeriesInk] tokens as real colours.
 *
 * *Which* ink a series takes is [ChartInk]'s decision and is made per chart,
 * because plates are assigned positionally per chart. This class only says what
 * each token is on the current paper, which is the one part of the answer that
 * needs a palette — and keeping the two apart is what lets every rule about
 * meaning be asserted on the JVM.
 */
@Immutable
class PlotColors(private val palette: LogbookPalette) {

    /** What an open mark is filled with, so the page shows through it. */
    val paper: Color = palette.paper

    /** A band's hairline bounds — the wash alone has no edge to read. */
    val bandEdge: Color = palette.ruleStrong

    fun of(ink: SeriesInk): Color = when (ink) {
        SeriesInk.INK -> palette.ink
        SeriesInk.INK_SOFT -> palette.inkSoft
        SeriesInk.INK_FAINT -> palette.inkFaint
        SeriesInk.RULE -> palette.rule
        SeriesInk.PLATE_1 -> palette.plates[0]
        SeriesInk.PLATE_2 -> palette.plates[1]
        SeriesInk.PLATE_3 -> palette.plates[2]
        // The week in progress, laid over whatever it covers: today's treatment,
        // applied to a bar.
        SeriesInk.INK_LID -> palette.ink.copy(alpha = IN_PROGRESS_ALPHA)
    }

    /** What [tone] is drawn in on [chart] — the plan's answer, or the default. */
    fun of(tone: PlotTone, chart: ChartInk? = null): Color = of(seriesStyle(tone, chart).ink)
}

/** A [SeriesMark], as the dash the canvas actually strokes with. */
private fun dashOf(mark: SeriesMark): PathEffect? = when (mark) {
    SeriesMark.DASHED_LINE -> chartMeanDash
    SeriesMark.FAINT_DASHED_LINE -> chartFaintMeanDash
    else -> null
}

@Composable
@ReadOnlyComposable
fun rememberPlotColors(): PlotColors = PlotColors(LogbookTheme.palette)

/** Bars and bands of an unfinished week, drawn back. */
private const val PARTIAL_ALPHA = 0.45f

/** The lid over the week in progress: today's treatment, applied to a bar. */
private const val IN_PROGRESS_ALPHA = 0.45f

/** An open mark's outline: a hairline at mark scale, not at chart scale. */
private val OPEN_MARK_STROKE = 1.4.dp

/**
 * How much bigger an open dot draws than the filled one it replaces.
 *
 * Its outline is drawn *on* the radius, so at the same size a hollow mark reads
 * smaller than a solid one; the step back is what keeps a flagged reading from
 * looking like a lesser reading.
 */
private const val OPEN_MARK_SCALE = 1.2f

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
    chart: ChartInk? = null,
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
            drawPlot(model, scale, colors, theme, measurer, chart)
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
    chart: ChartInk?,
) {
    for (rect in model.rects) {
        val color = colors.of(rect.tone, chart)
        val topLeft = Offset(scale.px(rect.x), scale.px(rect.y))
        val size = Size(scale.px(rect.w), scale.px(rect.h))
        drawRoundRect(
            color = if (rect.partial) color.copy(alpha = color.alpha * PARTIAL_ALPHA) else color,
            topLeft = topLeft,
            size = size,
            cornerRadius = CornerRadius(rect.radius.toPx()),
        )
        // A band is a wash, and a wash on paper has no edge — the two hairlines
        // are what make a target or a reference range read as bounded rather
        // than as a smudge behind the series.
        if (rect.tone == PlotTone.BAND) {
            val stroke = theme.gridWidth.toPx()
            for (edge in listOf(topLeft.y, topLeft.y + size.height)) {
                drawLine(
                    color = colors.bandEdge,
                    start = Offset(topLeft.x, edge),
                    end = Offset(topLeft.x + size.width, edge),
                    strokeWidth = stroke,
                )
            }
        }
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
    if (model.dotsBelowLines) drawPlotDots(model, scale, colors, chart)
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
            color = colors.of(line.tone, chart),
            style = Stroke(
                width = if (line.tone == PlotTone.PRIMARY) {
                    theme.lineWidth.toPx()
                } else {
                    theme.altLineWidth.toPx()
                },
                pathEffect = dashOf(seriesStyle(line.tone, chart).mark),
            ),
        )
    }
    if (!model.dotsBelowLines) drawPlotDots(model, scale, colors, chart)
    for (label in model.labels) {
        drawPlotLabel(label, scale, theme, measurer)
    }
}

/**
 * Dots, and the two that are drawn open.
 *
 * A **muted** point is present but not counted — an off-plan session — and a
 * **[PlotTone.WARN]** point is one the source itself flagged: a morning under
 * Garmin's own floor, a lab result carrying an H or an L. Both hollow out
 * rather than change colour, because a judgment in this system is a shape: the
 * journal's open dot, turned outward. The filled paper is what stops the band
 * or the line underneath from showing through and reading as a filled mark.
 */
private fun DrawScope.drawPlotDots(
    model: PlotModel,
    scale: LogicalScale,
    colors: PlotColors,
    chart: ChartInk?,
) {
    for (dot in model.dots) {
        val center = Offset(scale.px(dot.x), scale.px(dot.y))
        val style = seriesStyle(dot.tone, chart)
        val color = colors.of(style.ink)
        val open = dot.muted || isOpenMark(style.mark)
        if (!open) {
            drawCircle(color, dot.radius.toPx(), center)
            continue
        }
        val stroke = OPEN_MARK_STROKE.toPx()
        val radius = dot.radius.toPx() * OPEN_MARK_SCALE
        drawCircle(colors.paper, radius, center)
        drawCircle(color, radius - stroke / 2f, center, style = Stroke(stroke))
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
