package dev.jtiisto.wellness.feature.coach.guidance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jtiisto.wellness.core.ble.trace.TraceSample
import dev.jtiisto.wellness.core.ui.chart.ChartTheme
import dev.jtiisto.wellness.core.ui.chart.chartGuideDash
import dev.jtiisto.wellness.core.ui.chart.rememberChartTheme
import dev.jtiisto.wellness.core.ui.hr.HrCaptureDisplay
import dev.jtiisto.wellness.core.ui.hr.HrToneDot
import dev.jtiisto.wellness.core.ui.theme.INK_BANG
import dev.jtiisto.wellness.core.ui.theme.LogbookPalette
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme

/**
 * The instrument: three lines of header, the scrolling window, and the strip.
 *
 * Everything here is a painter. The window's geometry is [hrTraceModel]'s, its
 * words are [GuidanceNotation]'s, and the handful of decisions in between —
 * which beat is *the* reading, where a caption sits, how a strip block is
 * filled — are [GuidanceReadout]'s. What is left is scaling logical units to
 * pixels and choosing ink, which is the `PlotModel` → `drawPlot` seam this is
 * modelled on. There is deliberately nothing in this file a JVM test would want
 * to reach.
 *
 * ## Motion
 *
 * None of its own. The overlay's one-second tick recomposes this whole subtree
 * with a new [nowMs]; there is no animation, no transition and no frame clock
 * anywhere below here, which is the design system's first motion rule and the
 * reason a live trace can stand next to a wake lock at all.
 *
 * ## Ink
 *
 * The chart spends no colour. Bands are the system's wash-between-hairlines
 * idiom, the trace and its marks are ink, the grid is a hairline, and the one
 * coloured thing on the screen is the strap's own tone dot in the header — the
 * live-signal exception, whose consumer set this feature deliberately does not
 * grow (Variant A, the user's choice: an out-of-band beat is an open dot and a
 * mono bang, never a colour).
 */
@Composable
internal fun GuidanceInstrument(
    capture: HrCaptureDisplay,
    status: GuidanceStatus,
    samples: List<TraceSample>,
    domain: BpmDomain,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    // Rebuilt on the tick, and only on the tick: the model is a pure function of
    // its inputs, so remembering it across a recomposition that changed none of
    // them is free correctness rather than an optimisation.
    val model = remember(samples, status, domain, nowMs) {
        hrTraceModel(samples = samples, status = status, domain = domain, nowMs = nowMs)
    }
    val reading = remember(model, status, nowMs) { currentReading(model, status, nowMs) }

    Column(modifier = modifier.fillMaxWidth()) {
        HeaderGrammar(capture = capture, status = status, reading = reading)
        TraceWindow(
            model = model,
            description = traceDescription(reading.bpm, reading.segment),
            modifier = Modifier.padding(top = LogbookSpace.grid * 3),
        )
        SessionStrip(status = status, modifier = Modifier.padding(top = LogbookSpace.group))
    }
}

// ---- the header grammar --------------------------------------------------------------

/**
 * Three lines, one type size each — the user's binding feedback on the mockup.
 *
 * 1. the mono-caps context line: which part of the timeline is running (Start)
 *    and what is coming or what has been added (End);
 * 2. the two live numbers on **one shared baseline** at fixed edges;
 * 3. their small mono-caps labels, in the same two columns.
 *
 * The shared baseline is `alignByBaseline` on the numerals themselves rather
 * than a vertical alignment on the row, because the two numbers are set at the
 * same size but sit beside things that are not — a tone dot, a unit, a bang —
 * and only the baselines of the numerals may decide where the line sits.
 *
 * **No number's position depends on its neighbour's width.** The BPM group is
 * anchored to the Start edge and the countdown to the End, with a weighted
 * spacer between them holding the two apart; the bang appearing beside the BPM
 * therefore moves nothing at all, and neither number can be pushed by the other
 * growing a digit. That is the whole reason the line is built as one row of
 * three parts instead of two columns.
 */
@Composable
private fun HeaderGrammar(
    capture: HrCaptureDisplay,
    status: GuidanceStatus,
    reading: TraceReading,
) {
    val palette = LogbookTheme.palette
    val context = segmentContext(status)
    val ahead = nextUp(status)
    val bpm = bpmReadout(reading.bpm)
    val breach = bandBreach(reading.bpm, reading.segment)
    val countdown = remaining(status)
    val target = targetToken(status)

    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = context.drawn.uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = palette.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = context.spoken },
        )
        ahead?.let { note ->
            Text(
                text = note.drawn.uppercase(),
                style = LogbookTheme.type.eyebrow,
                color = palette.inkSoft,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = LogbookSpace.grid * 2)
                    .semantics { contentDescription = note.spoken },
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LogbookSpace.grid * 2),
    ) {
        HrToneDot(
            tone = capture.tone,
            size = TONE_DOT_SIZE,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(end = LogbookSpace.grid * 2),
        )
        Text(
            text = bpm.drawn,
            style = HeroNumeral,
            color = palette.ink,
            // Never two lines: a wrapped numeral would take the shared baseline
            // with it, and this line's whole grammar is that baseline.
            maxLines = 1,
            modifier = Modifier
                .alignByBaseline()
                .semantics { contentDescription = bpm.spoken },
        )
        Text(
            text = BPM_UNIT,
            style = LogbookTheme.type.eyebrow,
            color = palette.inkSoft,
            modifier = Modifier
                .alignByBaseline()
                .padding(start = LogbookSpace.grid * 1.5f),
        )
        // The bang lives on the Start side of the spacer, so lighting it moves
        // nothing: the numeral is to its left and the countdown is anchored to
        // the far edge. An alarm that shifted the numbers it warns about would
        // be worse than no alarm.
        breach?.let { breached ->
            Text(
                text = breached.drawn,
                style = BangMark,
                color = palette.ink,
                modifier = Modifier
                    .alignByBaseline()
                    .padding(start = LogbookSpace.grid * 2)
                    .semantics { contentDescription = breached.spoken },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = countdown.drawn,
            style = HeroNumeral,
            color = palette.ink,
            maxLines = 1,
            modifier = Modifier
                .alignByBaseline()
                .semantics { contentDescription = countdown.spoken },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LogbookSpace.grid),
    ) {
        Text(
            text = "$TARGET_LABEL ${target.drawn}".uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = palette.inkSoft,
            maxLines = 1,
            modifier = Modifier.semantics { contentDescription = target.spoken },
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = REMAINING_LABEL,
            style = LogbookTheme.type.eyebrow,
            color = palette.inkSoft,
            // The countdown above already says what it counts and of what; a
            // second node repeating the word would make TalkBack stop twice on
            // one number.
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

// ---- the scrolling window ------------------------------------------------------------

/**
 * The window, drawn — and **one semantics node**.
 *
 * A chart is geometry, and geometry read aloud is noise: the node says what the
 * instrument *reads* — how much time is on screen, the current beat, and
 * whether that beat is where the plan asked it to be — which is
 * [traceDescription]'s whole job. The verdict it carries was computed against
 * the segment the beat was ridden in, so the words and the open dot can never
 * disagree about the same beat at a boundary.
 */
@Composable
private fun TraceWindow(model: HrTraceModel, description: String, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette
    val theme = rememberChartTheme()
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio((model.width / model.height).toFloat())
            .semantics { contentDescription = description },
    ) {
        val scale = (size.width / model.width).toFloat()
        drawTraceWindow(model, scale, palette, theme, measurer)
    }
}

private fun DrawScope.drawTraceWindow(
    model: HrTraceModel,
    scale: Float,
    palette: LogbookPalette,
    theme: ChartTheme,
    measurer: TextMeasurer,
) {
    val px = { logical: Double -> (logical * scale).toFloat() }
    val hairline = theme.gridWidth.toPx()

    // Bands first, behind everything: the wash is the target, and the hairlines
    // are what make it read as bounded rather than as a smudge. An open edge
    // gets no hairline at all — that absence is the instruction.
    for (band in model.bands) {
        val top = px(band.yTop ?: 0.0)
        val bottom = px(band.yBot ?: model.height)
        drawRect(
            color = palette.rule,
            topLeft = Offset(px(band.x0), top),
            size = Size(px(band.x1) - px(band.x0), bottom - top),
            alpha = if (band.ahead) BAND_AHEAD_ALPHA else BAND_ALPHA,
        )
        for (edge in listOfNotNull(band.yTop, band.yBot)) {
            drawLine(
                color = palette.ruleStrong,
                start = Offset(px(band.x0), px(edge)),
                end = Offset(px(band.x1), px(edge)),
                strokeWidth = LogbookSpace.sectionUnderline.toPx(),
            )
        }
    }

    for (line in model.gridlines) {
        val y = px(line.y)
        drawLine(
            color = theme.grid,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = hairline,
        )
        drawTick(
            text = line.bpm.toString(),
            x = px(GRID_LABEL_INSET),
            baselineY = y - px(GRID_LABEL_LIFT),
            theme = theme,
            measurer = measurer,
        )
    }

    // Where the timeline steps. The guide dash, so a boundary reads as the
    // system's "this line is a reference" rather than as a second series.
    for (boundary in model.boundaries) {
        drawLine(
            color = palette.ruleStrong,
            start = Offset(px(boundary.x), 0f),
            end = Offset(px(boundary.x), size.height),
            strokeWidth = hairline,
            pathEffect = chartGuideDash,
        )
    }

    // Which bands are captioned, and where the caption sits, are decisions —
    // they live in GuidanceReadout with tests; this only inks the answer.
    for (band in captionedBands(model)) {
        drawTick(
            text = band.caption!!.drawn,
            x = px(band.x0) + px(CAPTION_INSET),
            baselineY = px(bandCaptionY(band.yTop, band.yBot, CAPTION_INSET, model.height)),
            theme = theme,
            measurer = measurer,
        )
    }

    for (polyline in model.polylines) {
        if (polyline.points.size < 2) continue
        val path = Path()
        polyline.points.forEachIndexed { index, point ->
            val x = px(point.x)
            val y = px(point.y)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = palette.ink, style = Stroke(width = theme.lineWidth.toPx()))
    }

    drawTraceMarks(model, px, palette, theme, measurer)

    val nowX = px(model.nowX)
    drawLine(
        color = palette.ink,
        start = Offset(nowX, 0f),
        end = Offset(nowX, size.height),
        strokeWidth = LogbookSpace.sectionUnderline.toPx(),
    )
    for (tick in model.timeTicks) {
        drawTick(
            text = tick.label,
            x = px(tick.x),
            baselineY = px(TIME_LABEL_BASELINE),
            theme = theme,
            measurer = measurer,
            centred = true,
        )
    }
}

/**
 * The beats worth marking: the ones outside their band, and the newest one.
 *
 * Not every sample — a dot per beat over thirty seconds is a bead curtain the
 * line has to be read through. An **open** dot is the system's judgment shape
 * (paper fill, ink outline — the journal's open mark turned outward), and the
 * mono bang rides beside the newest beat while it is the one out of band, which
 * is the same condition that lights the bang in the header.
 */
private fun DrawScope.drawTraceMarks(
    model: HrTraceModel,
    px: (Double) -> Float,
    palette: LogbookPalette,
    theme: ChartTheme,
    measurer: TextMeasurer,
) {
    // Which beats get marks, and as what, is the tested decision set in
    // GuidanceReadout.traceMarks; list order is draw order. Only the ink —
    // radii, strokes, the bang's lift off the beat — is decided here.
    val stroke = OPEN_POINT_STROKE.toPx()
    val openRadius = POINT_RADIUS.toPx() * OPEN_POINT_SCALE
    for (mark in traceMarks(model)) {
        val centre = Offset(px(mark.x), px(mark.y))
        when (mark.kind) {
            TraceMarkKind.FILLED_NEWEST -> drawCircle(
                color = palette.ink,
                radius = POINT_RADIUS.toPx(),
                center = centre,
            )
            TraceMarkKind.OPEN_OUT_OF_BAND -> {
                drawCircle(color = palette.paper, radius = openRadius, center = centre)
                drawCircle(
                    color = palette.ink,
                    radius = openRadius - stroke / 2f,
                    center = centre,
                    style = Stroke(stroke),
                )
            }
            TraceMarkKind.BANG -> drawTick(
                text = INK_BANG,
                x = px(mark.x),
                baselineY = px(mark.y) - px(BANG_LIFT),
                theme = theme,
                measurer = measurer,
                color = palette.ink,
                centred = true,
            )
        }
    }
}

// ---- the session strip ---------------------------------------------------------------

/**
 * The whole ride at a glance, and the sentence that captions it.
 *
 * The two are **one node**: the strip is a picture of the caption above it —
 * how long the session is, how many segments it has, whether it was extended —
 * and the only fact it adds is where the cursor stands, which the footer's
 * elapsed line already speaks. So TalkBack stops once, on the sentence, rather
 * than once on the sentence and again on a canvas it cannot describe.
 *
 * Nothing draws on an open-ended timeline: with no total there is no
 * proportion, and a strip that filled at a rate nobody set would be an invented
 * measurement.
 */
@Composable
private fun SessionStrip(status: GuidanceStatus, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette
    val summary = sessionSummary(status) ?: return
    val strip = guidanceStrip(status) ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = summary.spoken },
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
    ) {
        Text(
            text = summary.drawn.uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = palette.inkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio((strip.width / STRIP_LOGICAL_HEIGHT).toFloat()),
        ) {
            drawSessionStrip(strip, (size.width / strip.width).toFloat(), palette)
        }
    }
}

private fun DrawScope.drawSessionStrip(strip: GuidanceStrip, scale: Float, palette: LogbookPalette) {
    val px = { logical: Double -> (logical * scale).toFloat() }
    val hairline = LogbookSpace.hairline.toPx()
    for (block in strip.blocks) {
        val bar = stripBar(block.intensity, STRIP_LOGICAL_HEIGHT)
        val topLeft = Offset(px(block.x0), px(bar.y))
        // The gap is what keeps two adjacent blocks from reading as one. A
        // segment too short to hold it draws as nothing, which is the honest
        // rendering of a segment too short to see.
        val width = (px(block.x1) - px(block.x0) - px(STRIP_BLOCK_GAP)).coerceAtLeast(0f)
        val barSize = Size(width, px(bar.height))
        when (stripFill(block.state)) {
            StripFill.SOLID_INK -> drawRect(palette.ink, topLeft, barSize)
            StripFill.SOLID_FAINT -> drawRect(palette.inkFaint, topLeft, barSize)
            StripFill.OUTLINE -> drawRect(palette.inkFaint, topLeft, barSize, style = Stroke(hairline))
            StripFill.DASHED_OUTLINE -> drawRect(
                color = palette.inkFaint,
                topLeft = topLeft,
                size = barSize,
                style = Stroke(width = hairline, pathEffect = chartGuideDash),
            )
        }
    }
    drawLine(
        color = palette.ink,
        start = Offset(px(strip.cursorX), 0f),
        end = Offset(px(strip.cursorX), size.height),
        strokeWidth = LogbookSpace.sectionUnderline.toPx(),
    )
}

// ---- shared drawing ------------------------------------------------------------------

/**
 * A label inside a canvas, at the axis-tick size.
 *
 * Positioned by its **baseline** rather than by its box, so a number beside a
 * gridline and a caption inside a band sit where the geometry says rather than
 * where a font's ascent happens to put them — then held inside the canvas on
 * both axes. The clamp is not cosmetic: a tick can land exactly on the top of
 * the domain (whenever the axis's round step divides its top), and a label
 * three units above *that* line would be drawn entirely outside the plot. A
 * gridline nobody can read the value of is a gridline that says nothing.
 */
@Suppress("LongParameterList")
private fun DrawScope.drawTick(
    text: String,
    x: Float,
    baselineY: Float,
    theme: ChartTheme,
    measurer: TextMeasurer,
    color: Color = theme.tickColor,
    centred: Boolean = false,
) {
    val measured = measurer.measure(text, theme.tickStyle.copy(color = color))
    val left = if (centred) x - measured.size.width / 2f else x
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(
            x = left.coerceIn(0f, (size.width - measured.size.width).coerceAtLeast(0f)),
            y = (baselineY - measured.firstBaseline)
                .coerceIn(0f, (size.height - measured.size.height).coerceAtLeast(0f)),
        ),
    )
}

/**
 * The two live numbers, at the one size the ramp does not name.
 *
 * The `copy()`-at-the-callsite convention: this is the mono role set large
 * enough to read at arm's length on a bike, which is the whole reason the guide
 * exists, and it is a property of this one surface rather than a ninth entry in
 * a system-wide ramp. Medium weight for the same reason the meta line's values
 * take it — a number being *read* rather than referenced.
 */
private val HeroNumeral
    @Composable get() = LogbookTheme.type.data.copy(
        fontSize = HERO_SIZE,
        lineHeight = HERO_SIZE,
        fontWeight = FontWeight.Medium,
    )

/** The bang beside the readout: mono and ink, sized to be seen without shouting. */
private val BangMark
    @Composable get() = LogbookTheme.type.data.copy(
        fontSize = BANG_SIZE,
        lineHeight = BANG_SIZE,
        fontWeight = FontWeight.Medium,
    )

private val HERO_SIZE = 36.sp
private val BANG_SIZE = 22.sp

/** Between the chip's 6dp and the sheet's 8dp: it sits beside a 36sp numeral. */
private val TONE_DOT_SIZE = 10.dp

private const val BPM_UNIT = "bpm"
private const val TARGET_LABEL = "Target"
private const val REMAINING_LABEL = "REMAINING"

/** A held band, and one that has not arrived yet — drawn back so the two rank. */
private const val BAND_ALPHA = 0.55f
private const val BAND_AHEAD_ALPHA = 0.35f

/** Tick labels sit just inside the left edge, riding above their own gridline. */
private const val GRID_LABEL_INSET = 3.0
private const val GRID_LABEL_LIFT = 3.0

/** A band caption's margin from the edge it hangs off, in logical units. */
private const val CAPTION_INSET = 5.0

/** `now` and `+10s` ride the top of the plot, clear of the trace's own line. */
private const val TIME_LABEL_BASELINE = 9.0

/** How far above its beat the bang sits. */
private const val BANG_LIFT = 9.0

private val POINT_RADIUS = 3.dp

/** An open mark's outline, and the step back that keeps it from reading smaller. */
private val OPEN_POINT_STROKE = 1.5.dp
private const val OPEN_POINT_SCALE = 1.2f

/** The strip's logical height, against [GuidanceStrip.width]'s 360. */
private const val STRIP_LOGICAL_HEIGHT = 26.0

private const val STRIP_BLOCK_GAP = 2.0
