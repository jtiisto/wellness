package dev.jtiisto.wellness.feature.coach.guidance

import dev.jtiisto.wellness.core.ble.trace.TraceSample
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The scrolling window, as geometry.
 *
 * The `PlotModel` → `drawPlot` seam, applied to a live instrument: everything
 * that could be *wrong* about the window — where a beat lands, which band it was
 * judged against, where a boundary steps, what the axis says — is decided by a
 * pure function with tests, and the composable that draws an [HrTraceModel] has
 * no branches worth testing. `ChartScrubState` is explicitly not reused: it
 * assumes fixed anchors a finger can land on, and there is nothing to scrub on a
 * trace that moves.
 *
 * ## The window
 *
 * Thirty seconds of history with the now-line at **two thirds** of the width.
 * Those two constraints fix the third: the future is half the history, fifteen
 * seconds, and an upcoming band is visible for that long before it arrives. The
 * spec's `+10 s` is drawn as a **tick label** on the lookahead rather than as
 * the window's right edge — the mockup's geometry is exactly this (its `+10 s`
 * sits inside the plot, not at its corner), and reading it as the edge instead
 * would mean either twenty seconds of history or a now-line at three quarters,
 * each contradicting something the spec states outright.
 *
 * ## Units
 *
 * Logical units, [TRACE_LOGICAL_WIDTH] wide with y growing **down**, the same
 * convention the Trends charts are authored in. Duplicated rather than shared
 * because features never depend on each other; nothing here is a pixel until the
 * painter scales it, and ink — stroke widths, dot radii, text sizes — never
 * scales at all.
 */

/** History behind the now-line. The window's headline number. */
const val TRACE_HISTORY_SEC = 30

/** Where the now-line sits. Fixes the lookahead at half the history. */
const val TRACE_NOW_FRACTION = 2.0 / 3.0

/** Lookahead ahead of the now-line: [TRACE_HISTORY_SEC] × ½, forced by the two above. */
const val TRACE_FUTURE_SEC = TRACE_HISTORY_SEC / 2

/** The whole window. */
const val TRACE_SPAN_SEC = TRACE_HISTORY_SEC + TRACE_FUTURE_SEC

/** Where the lookahead tick is labelled, in seconds after now. */
const val TRACE_LOOKAHEAD_TICK_SEC = 10

/**
 * More than three seconds between beats is a break in the trace, not a line
 * across it.
 *
 * The **connection's own threshold**, re-derived here from timestamps because
 * the ring carries none of the connection's flags — `GarminHrmConnection`
 * stamps `isGapBefore` on exactly this rule, so the silence the guide draws a
 * gap for and the silence the record marks a discontinuity for are the same
 * event, seen twice. At the strap's real one-to-two beats a second, three
 * seconds is unreachable by ordinary jitter.
 *
 * A **backwards** step is a discontinuity too. The ring stores what it is given,
 * verbatim and unsorted, and a wall clock corrected by NTP mid-capture can hand
 * it a timestamp behind its predecessor; drawing that would run the line back
 * across itself.
 */
const val TRACE_GAP_THRESHOLD_MS = 3_000L

/** The logical viewBox: the PWA's chart width, and the mockup's window height. */
const val TRACE_LOGICAL_WIDTH = 360.0

const val TRACE_LOGICAL_HEIGHT = 150.0

/** How far past the timeline's own bounds the axis reaches. */
const val TRACE_DOMAIN_PAD_BPM = 25

/** The narrowest axis worth drawing: below this a hairline band fills the chart. */
const val TRACE_DOMAIN_MIN_SPAN_BPM = 60

/**
 * The axis for a guide with no timeline, and the one number in this file chosen
 * rather than derived — **read this before changing it**.
 *
 * A segmentless guide has no target, so there is no extent to fit an axis to and
 * nothing on screen to judge the trace against: the band that would give a
 * vertical position its meaning does not draw. What the axis has to do there is
 * therefore only *keep the trace on screen*, and 60–200 bpm does that for every
 * ride a human takes — resting at the bottom, a maximal effort at the top —
 * without ever needing to move.
 *
 * Moving it is the thing that is forbidden. The motion contract allows the
 * window to scroll and nothing else, so an axis that re-fitted itself to the
 * data would be a jumping ruler under a trace that is trying to hold steady, and
 * the trace's own shape would stop meaning anything between two ticks. A fixed
 * axis that is too generous costs vertical detail nobody can act on; a moving
 * one costs the reading itself.
 */
val UNTARGETED_BPM_DOMAIN = BpmDomain(60, 200)

/** The fixed vertical extent of one guidance session, in bpm. */
data class BpmDomain(val lo: Int, val hi: Int) {
    val span: Int get() = hi - lo
}

/**
 * A band rectangle, with **optional edges**.
 *
 * A null [yTop] is a floor-only segment — open-topped, because "at least 140"
 * has no upper edge and drawing one would invent a ceiling the plan did not set.
 * A null [yBot] is a ceiling-only segment, open-bottomed for the same reason.
 * This is where the model parts company with the Trends band builder, which
 * clamps a missing bound to the plot edge: there, a band that reaches the edge
 * and a band that has no edge look alike and it does not matter; here the
 * hairline that bounds the wash is the instruction, and drawing one across the
 * top of an open band would be a different instruction.
 *
 * [ahead] marks a segment that has not started — the mockup draws those back, so
 * the band being held now reads louder than the one approaching.
 */
data class TraceBand(
    val x0: Double,
    val x1: Double,
    val yTop: Double?,
    val yBot: Double?,
    /** Drawn beside an upcoming band, with its spoken twin riding along. */
    val caption: GuidanceLine?,
    val ahead: Boolean,
)

/** Where the timeline steps from one segment to the next: a dashed vertical. */
data class TraceBoundary(val x: Double, val segmentIndex: Int)

/**
 * One beat, placed.
 *
 * [outOfBand] was decided against the segment running at **this beat's**
 * timestamp, not the one running now — see [GuidanceStatus.segmentAt]. The ink
 * is the painter's business; the model only says which beats earned it.
 */
data class TracePoint(
    val x: Double,
    val y: Double,
    val bpm: Int,
    val timestampMs: Long,
    val outOfBand: Boolean,
)

/** An unbroken run of the trace. A new one starts wherever the beats stopped. */
data class TracePolyline(val points: List<TracePoint>)

/** A horizontal rule at a bpm tick, with the number that labels it. */
data class TraceGridline(val y: Double, val bpm: Int)

/** A labelled position along the time axis. */
data class TraceTimeTick(val x: Double, val label: String)

/**
 * The window, ready to draw.
 *
 * Field order is draw order, the [dev.jtiisto.wellness.feature.trends.chart]
 * convention: bands behind, then the grid, then boundaries, then the trace, then
 * the beats on top of it.
 */
data class HrTraceModel(
    val width: Double,
    val height: Double,
    val nowX: Double,
    val domain: BpmDomain,
    val bands: List<TraceBand> = emptyList(),
    val gridlines: List<TraceGridline> = emptyList(),
    val boundaries: List<TraceBoundary> = emptyList(),
    val polylines: List<TracePolyline> = emptyList(),
    val points: List<TracePoint> = emptyList(),
    val timeTicks: List<TraceTimeTick> = emptyList(),
    /**
     * Whether the newest beat is outside its band **and recent enough to be
     * called current**.
     *
     * This is what lights the mono bang beside the trace and beside the BPM
     * readout. The recency test is [TRACE_GAP_THRESHOLD_MS], the same silence
     * that breaks the line: a reading the strap stopped confirming three seconds
     * ago is not a rider who is currently over their ceiling, and holding an
     * alarm up against a stream that has gone quiet is the one way this display
     * could actively mislead.
     */
    val currentOutOfBand: Boolean = false,
)

/**
 * The fixed axis for a whole session, computed **once** from the plan.
 *
 * Taken as a parameter by [hrTraceModel] rather than derived inside it, which is
 * what makes "no per-tick rescaling" structural instead of a promise: the caller
 * holds one domain for the run, and there is no path by which a tick could
 * produce a different one. It is a pure function of the timeline's bounds, so
 * the extension cannot move it either — appended time changes when a segment
 * ends, never what it asks for.
 */
fun bpmDomain(timeline: GuidanceTimeline): BpmDomain {
    val bounds = timeline.segments.flatMap { listOfNotNull(it.hrMin, it.hrMax) }
    if (bounds.isEmpty()) return UNTARGETED_BPM_DOMAIN
    var lo = bounds.min() - TRACE_DOMAIN_PAD_BPM
    var hi = bounds.max() + TRACE_DOMAIN_PAD_BPM
    val shortfall = TRACE_DOMAIN_MIN_SPAN_BPM - (hi - lo)
    if (shortfall > 0) {
        lo -= shortfall / 2
        hi += shortfall - shortfall / 2
    }
    return BpmDomain(
        lo = (floorToTen(lo)).coerceAtLeast(0),
        hi = ceilToTen(hi),
    )
}

/**
 * The bpm values the axis labels: 1/2/5 × 10ⁿ steps landing near [targetCount]
 * of them, all inside the domain.
 *
 * The Trends `niceTicks` rule, in integers — a heart rate has no decimal place
 * worth an axis label, and keeping the arithmetic in `Int` is what stops a tick
 * printing as `159.99999999999997`.
 */
fun bpmTicks(domain: BpmDomain, targetCount: Int = 4): List<Int> {
    if (domain.span <= 0) return listOf(domain.lo)
    val raw = domain.span.toDouble() / targetCount.coerceAtLeast(1)
    val magnitude = 10.0.pow(floor(log10(raw)))
    val normalized = raw / magnitude
    val multiplier = when {
        normalized <= 1 -> 1.0
        normalized <= 2 -> 2.0
        normalized <= 5 -> 5.0
        else -> 10.0
    }
    val step = (multiplier * magnitude).roundToInt().coerceAtLeast(1)
    val first = ceilTo(domain.lo, step)
    return generateSequence(first) { it + step }.takeWhile { it <= domain.hi }.toList()
        .ifEmpty { listOf(domain.lo) }
}

/**
 * The whole window at one instant.
 *
 * [samples] is the ring's published snapshot, oldest first — stored verbatim, so
 * this is where two beats sharing a millisecond and a clock that stepped
 * backwards both have to be tolerated rather than repaired.
 *
 * Samples outside the window are dropped rather than clipped. The ring is about
 * twice the window's length, so the oldest visible beat is never more than one
 * beat from the left edge, and clipping a line to an off-canvas vertex would buy
 * that fraction of a second at the price of geometry nothing can check.
 */
@Suppress("LongParameterList")
fun hrTraceModel(
    samples: List<TraceSample>,
    status: GuidanceStatus,
    domain: BpmDomain,
    nowMs: Long,
    width: Double = TRACE_LOGICAL_WIDTH,
    height: Double = TRACE_LOGICAL_HEIGHT,
): HrTraceModel {
    val spanMs = TRACE_SPAN_SEC * MILLIS_PER_SECOND
    val windowStartMs = nowMs - TRACE_HISTORY_SEC * MILLIS_PER_SECOND
    val windowEndMs = windowStartMs + spanMs
    // Scale before dividing, in both axes. `30_000 / 45_000 * 360` is
    // 239.99999999999997 and `30_000 * 360 / 45_000` is 240 — the now-line has
    // to land on two thirds exactly, or every geometry assertion downstream
    // needs a tolerance to hide an error that is ours rather than the data's.
    val x = { atMs: Long -> (atMs - windowStartMs).toDouble() * width / spanMs }
    val y = { bpm: Int ->
        ((domain.hi - bpm).toDouble() * height / domain.span).coerceIn(0.0, height)
    }

    val points = samples.filter { it.timestampMs in windowStartMs..windowEndMs }.map { sample ->
        TracePoint(
            x = x(sample.timestampMs),
            y = y(sample.bpm),
            bpm = sample.bpm,
            timestampMs = sample.timestampMs,
            outOfBand = status.segmentAt(sample.timestampMs - status.anchorMs)
                ?.outOfBand(sample.bpm) == true,
        )
    }
    val newest = points.lastOrNull()

    return HrTraceModel(
        width = width,
        height = height,
        nowX = x(nowMs),
        domain = domain,
        bands = status.segments.mapNotNull { segment ->
            band(segment, status, windowStartMs, windowEndMs, nowMs, x, y)
        },
        gridlines = bpmTicks(domain).map { TraceGridline(y = y(it), bpm = it) },
        boundaries = status.segments.mapNotNull { segment ->
            val startAtMs = status.anchorMs + segment.startMs
            if (startAtMs < windowStartMs || startAtMs > windowEndMs) null
            else TraceBoundary(x = x(startAtMs), segmentIndex = segment.index)
        },
        polylines = splitOnGaps(points),
        points = points,
        timeTicks = listOf(
            TraceTimeTick(x = x(nowMs), label = "now"),
            TraceTimeTick(
                x = x(nowMs + TRACE_LOOKAHEAD_TICK_SEC * MILLIS_PER_SECOND),
                label = "+${TRACE_LOOKAHEAD_TICK_SEC}s",
            ),
        ),
        currentOutOfBand = newest != null &&
            newest.outOfBand &&
            nowMs - newest.timestampMs <= TRACE_GAP_THRESHOLD_MS,
    )
}

@Suppress("LongParameterList")
private fun band(
    segment: GuidanceSegment,
    status: GuidanceStatus,
    windowStartMs: Long,
    windowEndMs: Long,
    nowMs: Long,
    x: (Long) -> Double,
    y: (Int) -> Double,
): TraceBand? {
    if (!segment.hasBand) return null
    val startAtMs = status.anchorMs + segment.startMs
    val endAtMs = status.anchorMs + segment.endMs
    val x0 = x(max(startAtMs, windowStartMs))
    val x1 = x(min(endAtMs, windowEndMs))
    if (x1 <= x0) return null
    return TraceBand(
        x0 = x0,
        x1 = x1,
        yTop = segment.hrMax?.let(y),
        yBot = segment.hrMin?.let(y),
        caption = bandCaption(segment),
        ahead = startAtMs > nowMs,
    )
}

/**
 * Cut the trace wherever the beats stopped.
 *
 * A gap is drawn as a gap: the spec's honest-silence rule, and the one thing a
 * live trace must not do is interpolate across a stretch where the strap said
 * nothing. Runs of a single point are kept — a lone beat between two silences is
 * still a beat, and dropping it would hide the only evidence the strap was ever
 * there.
 */
private fun splitOnGaps(points: List<TracePoint>): List<TracePolyline> {
    if (points.isEmpty()) return emptyList()
    val runs = mutableListOf<TracePolyline>()
    var run = mutableListOf(points.first())
    for (index in 1 until points.size) {
        val step = points[index].timestampMs - points[index - 1].timestampMs
        if (step < 0 || step > TRACE_GAP_THRESHOLD_MS) {
            runs += TracePolyline(run)
            run = mutableListOf()
        }
        run += points[index]
    }
    runs += TracePolyline(run)
    return runs
}

// ---- the whole-session strip ---------------------------------------------------------

/** What a strip block is: done behind the cursor, ahead of it, or appended. */
enum class StripState { DONE, CURRENT, AHEAD, EXTENSION }

/**
 * One block of the session strip.
 *
 * [intensity] ranks this segment's band against the others in the timeline, 0
 * for the easiest and 1 for the hardest, so the painter can draw a hard interval
 * taller than the easy one between them — the mockup's shape, expressed as a
 * number rather than as a rule the composable would have to re-derive. Null when
 * the segment has no band to rank, which is every block of a segmentless strip.
 */
data class StripBlock(
    val x0: Double,
    val x1: Double,
    val state: StripState,
    val label: String?,
    val intensity: Double?,
)

/** The session at a glance: blocks in plan order, and where the ride has got to. */
data class GuidanceStrip(
    val width: Double,
    val blocks: List<StripBlock>,
    val cursorX: Double,
)

/**
 * The whole-session strip.
 *
 * The strip is the one surface that draws the **plan** and the **extension**
 * apart: the segment that absorbed the appended time is split at its planned
 * end and the remainder gets its own dashed block, because the strip answers
 * "what was added" while the band answers "what am I holding". Both are true at
 * once — the rider is still holding the same target, and those minutes were not
 * in the plan.
 *
 * The dashed block therefore sits wherever the lengthened segment ends, which is
 * **mid-strip when a cooldown follows it**: the appended minutes belong to the
 * work, and drawing them after a cooldown that has moved later would say the
 * ride was extended at the end, which is not what happened.
 *
 * Null on an open-ended timeline. With no total there is no proportion, and a
 * strip that filled at a rate nobody set would be an invented measurement.
 */
fun guidanceStrip(status: GuidanceStatus, width: Double = TRACE_LOGICAL_WIDTH): GuidanceStrip? {
    val planned = status.plannedTotalSec ?: return null
    val total = status.totalSec ?: return null
    if (total <= 0) return null
    val x = { seconds: Int -> seconds.toDouble() * width / total }
    val levels = status.segments.associate { it.index to it.level() }
    val floor = levels.values.filterNotNull().minOrNull()
    val ceiling = levels.values.filterNotNull().maxOrNull()

    val blocks = mutableListOf<StripBlock>()
    if (status.segments.isEmpty()) {
        blocks += StripBlock(
            x0 = 0.0,
            x1 = x(planned),
            state = if (status.elapsedSec >= planned) StripState.DONE else StripState.CURRENT,
            label = null,
            intensity = null,
        )
        if (total > planned) {
            blocks += StripBlock(
                x0 = x(planned),
                x1 = width,
                state = StripState.EXTENSION,
                label = null,
                intensity = null,
            )
        }
    } else {
        for (segment in status.segments) {
            // The appended seconds are already inside this segment's end; the
            // split puts them back where the strip can name them.
            val appended = if (segment.index == status.extendedIndex) status.extensionSec else 0
            val plannedEnd = (segment.endSec - appended).coerceAtLeast(segment.startSec)
            if (plannedEnd > segment.startSec) {
                blocks += StripBlock(
                    x0 = x(segment.startSec),
                    x1 = x(plannedEnd),
                    state = when {
                        status.elapsedSec >= plannedEnd -> StripState.DONE
                        status.elapsedSec >= segment.startSec -> StripState.CURRENT
                        else -> StripState.AHEAD
                    },
                    label = segment.label,
                    intensity = normalise(levels[segment.index], floor, ceiling),
                )
            }
            if (segment.endSec > plannedEnd) {
                blocks += StripBlock(
                    x0 = x(plannedEnd),
                    x1 = x(segment.endSec),
                    state = StripState.EXTENSION,
                    label = null,
                    intensity = null,
                )
            }
        }
    }
    return GuidanceStrip(
        width = width,
        blocks = blocks,
        cursorX = x(status.elapsedSec.coerceIn(0, total)),
    )
}

/**
 * How hard a segment asks for, as one number: the middle of its band, or the one
 * bound it has. A boundless segment has no level and takes no height.
 */
private fun GuidanceSegment.level(): Double? {
    val min = hrMin
    val max = hrMax
    return when {
        min != null && max != null -> (min + max) / 2.0
        min != null -> min.toDouble()
        max != null -> max.toDouble()
        else -> null
    }
}

/**
 * A level's rank in the timeline, 0..1. A timeline whose segments all ask for
 * the same thing ranks them all at the midpoint rather than dividing by zero —
 * equal effort should draw equal blocks, not a full-height row.
 */
private fun normalise(level: Double?, floor: Double?, ceiling: Double?): Double? {
    if (level == null || floor == null || ceiling == null) return null
    if (ceiling <= floor) return 0.5
    return (level - floor) / (ceiling - floor)
}

private fun floorToTen(value: Int): Int = Math.floorDiv(value, 10) * 10

private fun ceilToTen(value: Int): Int = ceilTo(value, 10)

private fun ceilTo(value: Int, step: Int): Int = -Math.floorDiv(-value, step) * step
