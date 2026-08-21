package dev.jtiisto.wellness.feature.coach.guidance

/**
 * What the instrument is handed, decided where a test can read it.
 *
 * [HrTraceModel] answers where everything goes and [GuidanceNotation] answers
 * what everything says; this is the third, smaller question the painter would
 * otherwise answer for itself — *which* beat the header is reading, which band
 * a caption belongs against, how tall a strip block stands and what it is
 * filled with. None of those are big decisions, and every one of them is a
 * decision: a painter that picked the wrong sample or the wrong fill would draw
 * a perfectly plausible instrument reading the wrong thing, and nothing off a
 * device could tell.
 *
 * The rule this file exists to keep is the `PlotModel` → `drawPlot` seam's:
 * **the composable has no branches worth testing**. Everything here is a value,
 * with no Compose import anywhere in it.
 */

/**
 * The beat the header reads, and the band it was ridden against.
 *
 * Taken from the model's own points rather than from the ring, so the number
 * above the chart and the ink inside it can never disagree: the model has
 * already dropped what falls outside the window and has already decided, per
 * beat, which segment it was judged against. Reading the newest of *those* is
 * what makes [GuidanceNotation.bandBreach] on this reading exactly
 * [HrTraceModel.currentOutOfBand] — the drawn bang, the spoken verdict and the
 * open dot are then three renderings of one fact rather than three
 * computations of it.
 *
 * A null [bpm] is "no current reading": either nothing has arrived or the
 * newest beat is too old to be called current. It is never a zero.
 */
data class TraceReading(val bpm: Int?, val segment: GuidanceSegment?)

/**
 * The newest beat, if it is recent enough to be the reading.
 *
 * The recency test is [TRACE_GAP_THRESHOLD_MS] — the same silence that breaks
 * the trace into polylines and the same one the model applies to its own
 * current-out-of-band flag. Deliberately stricter than the capture stack's
 * stream-liveness watchdog, and for a different question: the watchdog asks
 * whether the *link* has died, while this asks whether the number above a live
 * trace is still the rider's heart rate. Three seconds of silence is already a
 * visible gap between the last point and the now-line, and a number left
 * standing over that gap would be the one thing this display could assert that
 * the display itself contradicts.
 *
 * [nowMs] − timestamp is used as the model uses it, unsigned by intent: a beat
 * stamped in the future by a clock correction still reads as current here,
 * exactly as it does there, because the two must agree about the same beat more
 * than either must be clever about a wrong clock.
 */
fun currentReading(model: HrTraceModel, status: GuidanceStatus, nowMs: Long): TraceReading {
    val newest = model.points.lastOrNull() ?: return NO_READING
    if (nowMs - newest.timestampMs > TRACE_GAP_THRESHOLD_MS) return NO_READING
    return TraceReading(
        bpm = newest.bpm,
        segment = status.segmentAt(newest.timestampMs - status.anchorMs),
    )
}

private val NO_READING = TraceReading(bpm = null, segment = null)

/**
 * Where a band's caption sits: **inside the band, against the edge it has**.
 *
 * A band with a ceiling hangs its caption under that ceiling; a floor-only band
 * is open-topped, so its caption sits above the floor instead. The point is
 * that the caption always lands in the wash it names rather than in the air
 * above an open band, where it would read as belonging to whatever is drawn
 * there. [inset] is the painter's margin, in the model's logical units.
 *
 * A band with neither edge cannot be drawn at all
 * ([GuidanceSegment.hasBand] is what stops one being built), so the fallback
 * only has to be somewhere sane rather than right.
 *
 * Clamped to the plot's own height less the inset on both sides: a band edge
 * near the domain boundary would otherwise push the baseline off the canvas,
 * and the painter's box-clamp would then park the caption flush against the
 * edge, on top of the very hairline it names (deep-review find).
 */
fun bandCaptionY(yTop: Double?, yBot: Double?, inset: Double, height: Double): Double {
    val raw = when {
        yTop != null -> yTop + inset
        yBot != null -> yBot - inset
        else -> inset
    }
    return raw.coerceIn(inset, (height - inset).coerceAtLeast(inset))
}

/**
 * The bands the window captions: **approaching ones only**. The band being
 * held is named by the header's `TARGET` slot, and a second copy of it inside
 * the wash would be the same instruction twice at two type sizes.
 */
fun captionedBands(model: HrTraceModel): List<TraceBand> =
    model.bands.filter { it.ahead && it.caption != null }

/** What a mark over the trace is: which beat, drawn as what. */
enum class TraceMarkKind { FILLED_NEWEST, OPEN_OUT_OF_BAND, BANG }

/** One mark, at the beat's own logical position. List order is draw order. */
data class TraceMark(val x: Double, val y: Double, val kind: TraceMarkKind)

/**
 * The sparse marks the window draws over its line — the whole of the
 * out-of-band ink language, decided here so a JVM test can hold it:
 * one filled dot on the newest beat while it is in band, an open dot per
 * out-of-band beat, and the bang above the newest beat while it is out
 * (never both dots on one beat — the newest is either in band or it is not).
 * A dot per sample would be a bead curtain to read a line through.
 */
fun traceMarks(model: HrTraceModel): List<TraceMark> {
    val marks = mutableListOf<TraceMark>()
    val newest = model.points.lastOrNull()
    if (newest != null && !newest.outOfBand) {
        marks += TraceMark(newest.x, newest.y, TraceMarkKind.FILLED_NEWEST)
    }
    for (point in model.points) {
        if (point.outOfBand) marks += TraceMark(point.x, point.y, TraceMarkKind.OPEN_OUT_OF_BAND)
    }
    if (model.currentOutOfBand && newest != null) {
        marks += TraceMark(newest.x, newest.y, TraceMarkKind.BANG)
    }
    return marks
}

/** A strip block's vertical extent: how tall it stands, and where. */
data class StripBar(val y: Double, val height: Double)

/** The shortest a strip block draws, as a fraction of the strip's height. */
const val STRIP_BAR_MIN_FRACTION = 0.38

/** The tallest. A block never fills the strip — the cursor has to clear it. */
const val STRIP_BAR_MAX_FRACTION = 0.70

/**
 * How tall a strip block stands, from how hard its segment asks.
 *
 * The mockup's shape — a hard interval taller than the easy one beside it —
 * expressed as the ranking [StripBlock.intensity] already computed. Bars are
 * centred on the strip's own middle so a row of mixed heights reads as one
 * line of blocks rather than as a bar chart sitting on a baseline; the strip
 * says *what is coming in what order*, and a segment's height is a hint about
 * effort, not a measurement of it.
 *
 * A null intensity is a segment with no band to rank — every block of a
 * segmentless ride, and the appended extension — and takes the shortest bar.
 * There is no effort to state, so it states none rather than guessing at a
 * middle.
 */
fun stripBar(intensity: Double?, stripHeight: Double): StripBar {
    val ranked = (intensity ?: 0.0).coerceIn(0.0, 1.0)
    val fraction = STRIP_BAR_MIN_FRACTION + (STRIP_BAR_MAX_FRACTION - STRIP_BAR_MIN_FRACTION) * ranked
    val height = stripHeight * fraction
    return StripBar(y = (stripHeight - height) / 2.0, height = height)
}

/**
 * How a strip block is filled — the four treatments, as a vocabulary.
 *
 * The `ChartInk` seam, at the scale this needs: which ink a block takes is a
 * decision with a right answer, so it is made here and asserted, and the
 * canvas only turns the answer into a fill. The grammar is the design system's
 * own — solid for what has happened, outline for what has not, and the dash
 * that has meant "derived rather than planned" since the Trends means.
 */
enum class StripFill {
    /** The segment being ridden now: the one block in full ink. */
    SOLID_INK,

    /** Behind the cursor. Filled, because it happened — but quietly. */
    SOLID_FAINT,

    /** Ahead: an outline is a promise, not a record. */
    OUTLINE,

    /** Appended by `+ 5 MIN`: outlined *and* dashed, because it was never planned. */
    DASHED_OUTLINE,
}

fun stripFill(state: StripState): StripFill = when (state) {
    StripState.DONE -> StripFill.SOLID_FAINT
    StripState.CURRENT -> StripFill.SOLID_INK
    StripState.AHEAD -> StripFill.OUTLINE
    StripState.EXTENSION -> StripFill.DASHED_OUTLINE
}
