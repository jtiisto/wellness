package dev.jtiisto.wellness.feature.coach.guidance

import dev.jtiisto.wellness.core.data.coach.PlanExerciseDto
import dev.jtiisto.wellness.core.data.coach.PlanSegmentDto

/**
 * The cardio guide's clock: a plan's target-HR timeline, the live run over it,
 * and the reading the display takes off the two once a second.
 *
 * Everything here is a value. Nothing observes a clock, owns a coroutine or
 * remembers anything between calls: [guidanceStatus] is handed `nowMs` and
 * answers with the whole of what the overlay draws at that instant. That is what
 * lets the motion contract — one discrete tick per second, and nothing animating
 * while the overlay is not composed — be a property of the *caller* rather than
 * something this has to promise.
 *
 * Times are epoch milliseconds, and arithmetic on them is legal for the reason
 * `HrTraceRing` records of its own: these are **data values** off the phone's
 * wall clock, not server-issued sync watermarks, so the opaque-timestamp rule
 * has nothing to reach here.
 */

/**
 * What one `+ 5 MIN` tap appends.
 *
 * Five minutes to the **live timeline**, never to the plan. Raising
 * `target_duration_min` mid-ride would un-complete an exercise the rider had
 * already satisfied — completion for a `duration` exercise is logged minutes vs
 * the plan's target and nothing else — so the extension lives out here, in a
 * value the log never sees. The log records the ride that actually happened.
 */
const val EXTENSION_STEP_SEC = 300

/**
 * One step of the timeline, with its place on it resolved.
 *
 * The wire carries durations; this carries offsets, because everything the guide
 * asks is positional — which segment holds this instant, where does the next
 * one's band start, how much of it is left. [startSec] is inclusive and [endSec]
 * exclusive, so the boundary instant belongs to the segment that is *arriving*:
 * at 5:00 of a 5:00 warmup the rider is in the first work interval, which is
 * what both the countdown and the band step say.
 *
 * The label is trimmed on the way in and blank counts as absent: a mono-caps
 * line drawn from the wire verbatim would carry the author's stray space into
 * the middle of a fixed column.
 *
 * Bounds are absolute bpm and **normalised on the way in**: a zero is absent,
 * not a bound of zero (the server's floor is 1, so a `0` can only be a
 * hand-edited row, and `formatSegments` already reads it that way). Which bounds
 * survive is the segment's meaning — min only is a floor, max only a ceiling,
 * both a range, neither is a step with no target at all.
 */
data class GuidanceSegment(
    val index: Int,
    val label: String?,
    val startSec: Int,
    val endSec: Int,
    val hrMin: Int?,
    val hrMax: Int?,
) {
    val durationSec: Int get() = endSec - startSec

    val startMs: Long get() = startSec * MILLIS_PER_SECOND

    val endMs: Long get() = endSec * MILLIS_PER_SECOND

    /** Whether this segment draws a band at all. A boundless step draws none. */
    val hasBand: Boolean get() = hrMin != null || hrMax != null

    /** Whether this segment holds an instant, measured from the timeline's start. */
    fun holdsAt(elapsedMs: Long): Boolean = elapsedMs >= startMs && elapsedMs < endMs

    /**
     * Whether a reading sits outside this segment's band.
     *
     * The bounds are inclusive: a beat exactly on the floor or the ceiling is
     * *in*. A band drawn between two hairlines includes the hairlines, and a
     * rider holding the ceiling exactly is doing what was asked — flagging that
     * would put an open dot on the one beat that is perfectly on target.
     *
     * A segment with no bounds has no band, and nothing can be outside it.
     */
    fun outOfBand(bpm: Int): Boolean = when {
        hrMin != null && bpm < hrMin -> true
        hrMax != null && bpm > hrMax -> true
        else -> false
    }
}

/**
 * A cardio exercise's timeline **as planned** — the extension is not in here.
 *
 * [segments] is empty for the segmentless case: a `duration` exercise whose
 * author supplied no timeline still gets the guide (timer, trace and extension;
 * the band simply does not draw), which is the spec's explicit YES.
 *
 * [plannedTotalSec] is null only when there is nothing to count down to — a
 * `duration` exercise carrying neither segments nor a target duration. That
 * timeline is **open-ended**: it never reaches DONE, the footer shows elapsed
 * alone, and there is no proportion for the session strip to draw. Nothing on
 * the server produces such an exercise; it is what an incomplete row degrades
 * to, and degrading beats showing a countdown to zero.
 */
data class GuidanceTimeline(
    val segments: List<GuidanceSegment>,
    val plannedTotalSec: Int?,
) {
    companion object {
        /** The open-ended, bandless timeline — a guide that is only a stopwatch. */
        val OPEN = GuidanceTimeline(emptyList(), null)
    }
}

/**
 * Resolve a plan's segments into a timeline.
 *
 * When segments are present **they are the timeline**: the total is their
 * durations summed, and [plannedDurationSec] is ignored even if it disagrees.
 * The two are not cross-validated anywhere on the server, and the guide draws
 * the thing it can draw — a band per segment — rather than a countdown to a
 * number no segment reaches. Completion and Trends keep reading
 * `target_duration_min`, which is a statement about the *log*, not about this.
 *
 * Durations are clamped at zero on the way in. The server's floor is 1, so a
 * non-positive one can only come from a hand-edited row, and a segment that ran
 * backwards would put every later offset behind the one before it.
 */
fun guidanceTimeline(
    segments: List<PlanSegmentDto>?,
    plannedDurationSec: Int?,
): GuidanceTimeline {
    val resolved = mutableListOf<GuidanceSegment>()
    var offset = 0
    segments.orEmpty().forEachIndexed { index, segment ->
        val duration = segment.durationSec.coerceAtLeast(0)
        resolved += GuidanceSegment(
            index = index,
            label = segment.label?.trim()?.takeIf { it.isNotEmpty() },
            startSec = offset,
            endSec = offset + duration,
            hrMin = segment.hrMin?.takeIf { it != 0 },
            hrMax = segment.hrMax?.takeIf { it != 0 },
        )
        offset += duration
    }
    return GuidanceTimeline(
        segments = resolved,
        plannedTotalSec = if (resolved.isEmpty()) plannedDurationSec?.takeIf { it > 0 } else offset,
    )
}

/**
 * The timeline this planned exercise guides against.
 *
 * `targetDurationMin` is the cardio duration field — the same one
 * `CoachUiLogic` reads for the `N min` scheme — and it is only consulted when
 * the exercise carries no segments. `targetDurationSec` is deliberately not
 * consulted: it belongs to `weighted_time`, a strength shape that gets no guide.
 */
fun PlanExerciseDto.guidanceTimeline(): GuidanceTimeline =
    guidanceTimeline(segments, targetDurationMin?.takeIf { it > 0 }?.times(SECONDS_PER_MINUTE))

/**
 * The live run: when the rider pressed START, and how much they have appended.
 *
 * Owned by the UI layer keyed on (date, exercise) so it outlives the composable
 * — dismissing the overlay does not stop the clock, and reopening restores the
 * position. **At most one run is ever held per key**: there is no history, which
 * is what "the guide runs at most once" means. The record of a session is the
 * log, not this.
 */
data class GuidanceRun(
    val startedAtMs: Long? = null,
    val extensionSec: Int = 0,
) {

    /** Whether a run has been anchored. False is [GuidancePhase.READY]. */
    val isStarted: Boolean get() = startedAtMs != null

    /**
     * Anchor the timeline at [nowMs], **discarding whatever came before**.
     *
     * One transition serves both the first START and the fresh run offered after
     * a completed one, and it has to drop the old extension with the old anchor:
     * a second ride is a second ride, and inheriting five appended minutes from
     * the last one would start it already lengthened. The UI offers this in
     * READY and DONE only; the value type does not police that, because a
     * restart mid-run is a legitimate thing for a future round to offer and
     * nothing here would need to change to allow it.
     */
    fun start(nowMs: Long): GuidanceRun = GuidanceRun(startedAtMs = nowMs, extensionSec = 0)

    /**
     * Append another [EXTENSION_STEP_SEC] to the live timeline. Cumulative: the
     * context line's `EXTENDED · +N:00` is the running total of these taps, which
     * is exactly why this adds to the timeline rather than re-basing it on now.
     */
    fun extend(): GuidanceRun = copy(extensionSec = extensionSec + EXTENSION_STEP_SEC)
}

/**
 * Where a run stands against its timeline.
 *
 * **DONE is quiet, and it is a reading rather than a latch.** Nothing locks when
 * the timeline ends: the window keeps scrolling, the trace keeps drawing, and
 * the ride may continue for as long as the rider likes — the log records
 * reality. Because the phase is derived from the clock and not stored, appending
 * time to a finished timeline simply makes it unfinished again, with no resume
 * transition to write and no stored state that could contradict the clock.
 */
enum class GuidancePhase {
    /** Opened, not yet anchored. The band and trace draw; the timeline reads 0:00. */
    READY,

    /** Anchored, and the clock is inside the timeline. */
    RUNNING,

    /** Anchored, and the clock has passed the end — including any extension. */
    DONE,
}

/**
 * Everything the overlay reads at one instant: the whole of the guide's state,
 * rebuilt from values on every tick.
 *
 * [segments] is the **effective** timeline — the extension is already folded
 * into it — so nothing downstream has to remember to apply it. [plannedTotalSec]
 * is kept beside [totalSec] for the one surface that needs to tell them apart:
 * the session strip, which draws appended time dashed.
 */
data class GuidanceStatus(
    val phase: GuidancePhase,
    /**
     * The instant the timeline's `0:00` sits at.
     *
     * Before START this is `nowMs` itself, so the timeline is pinned to the
     * now-line: the first segment's band waits at it and extends into the
     * lookahead, which is what "band already live, timeline at 0:00" looks like
     * when nothing has started. The history half carries no band, correctly —
     * nothing can be out of a band that has not begun.
     */
    val anchorMs: Long,
    val elapsedMs: Long,
    val elapsedSec: Int,
    val plannedTotalSec: Int?,
    val extensionSec: Int,
    val totalSec: Int?,
    val segments: List<GuidanceSegment>,
    val currentIndex: Int?,
    /**
     * The header's `REMAINING` number: what is left of the **current segment**,
     * or of the session when there are no segments. For a single-segment
     * timeline those are the same thing, which is what makes one label honest
     * across both shapes. Null only on an open-ended segmentless timeline, where
     * there is genuinely nothing to count down.
     */
    val remainingSec: Int?,
    /** What is left of the whole timeline, extension included. */
    val remainingTotalSec: Int?,
) {

    /** The segment holding this instant, or null past the end of the timeline. */
    val current: GuidanceSegment? get() = currentIndex?.let(segments::getOrNull)

    /** The segment after the current one, or null when this is the last. */
    val next: GuidanceSegment? get() = currentIndex?.let { segments.getOrNull(it + 1) }

    /** No timeline: the guide is a stopwatch and a trace, and no band draws. */
    val isSegmentless: Boolean get() = segments.isEmpty()

    /** One continuous band for the whole ride — the Zone 2 shape. */
    val isSteady: Boolean get() = segments.size == 1

    /**
     * Whether `+ 5 MIN` is offered.
     *
     * Steady-state only, per the spec, and only when there is a length to append
     * to: appending to an open-ended timeline would move a total that does not
     * exist while still counting up the `EXTENDED` note, which would be a
     * control that reports work it did not do.
     */
    val canExtend: Boolean get() = segments.size <= 1 && plannedTotalSec != null

    /**
     * The segment holding an arbitrary instant of the timeline.
     *
     * The out-of-band verdict for a *sample* has to come through here rather
     * than off [current]: the window is thirty seconds wide and routinely spans
     * a boundary, so beats on the left of it were judged against the segment
     * that was running **then**. Judging the whole window against the current
     * segment would open-dot a warmup for failing a target that had not been set
     * when it was ridden.
     *
     * An instant before the timeline starts, or past its end, belongs to no
     * segment.
     */
    fun segmentAt(elapsedMs: Long): GuidanceSegment? = segments.firstOrNull { it.holdsAt(elapsedMs) }
}

/**
 * Read the guide's whole state at [nowMs].
 *
 * The one entry point, called once per tick. Elapsed time is clamped at zero: a
 * wall clock that steps backwards under NTP mid-ride must not produce a negative
 * timeline position, and freezing at 0:00 for the correction is the smallest
 * visible wrong thing available. (The window's own liveness is the capture
 * stack's business, not this clock's.)
 */
fun guidanceStatus(timeline: GuidanceTimeline, run: GuidanceRun, nowMs: Long): GuidanceStatus {
    val anchorMs = run.startedAtMs ?: nowMs
    val extensionSec = run.extensionSec.coerceAtLeast(0)
    val elapsedMs = (nowMs - anchorMs).coerceAtLeast(0L)
    val segments = timeline.segments.extendedBy(extensionSec)
    val totalSec = timeline.plannedTotalSec?.plus(extensionSec)
    val phase = when {
        run.startedAtMs == null -> GuidancePhase.READY
        totalSec == null -> GuidancePhase.RUNNING
        elapsedMs >= totalSec * MILLIS_PER_SECOND -> GuidancePhase.DONE
        else -> GuidancePhase.RUNNING
    }
    val currentIndex = segments.indexOfFirst { it.holdsAt(elapsedMs) }.takeIf { it >= 0 }
    val current = currentIndex?.let(segments::getOrNull)
    val remainingSec = when {
        phase == GuidancePhase.DONE -> 0
        current != null -> ceilSeconds(current.endMs - elapsedMs)
        segments.isNotEmpty() -> 0
        totalSec != null -> ceilSeconds(totalSec * MILLIS_PER_SECOND - elapsedMs)
        else -> null
    }
    return GuidanceStatus(
        phase = phase,
        anchorMs = anchorMs,
        elapsedMs = elapsedMs,
        elapsedSec = (elapsedMs / MILLIS_PER_SECOND).toInt(),
        plannedTotalSec = timeline.plannedTotalSec,
        extensionSec = extensionSec,
        totalSec = totalSec,
        segments = segments,
        currentIndex = currentIndex,
        remainingSec = remainingSec,
        remainingTotalSec = totalSec?.let { ceilSeconds(it * MILLIS_PER_SECOND - elapsedMs) },
    )
}

/**
 * Fold appended time into the **last** segment.
 *
 * One rule, and it is the right one for the only case that offers the control: a
 * steady ride's extension is more of the same ride, one continuous band held for
 * longer, which is exactly what stretching the single segment draws. Applied to
 * a multi-segment timeline it would lengthen the last step, which is the
 * sensible reading there too (you cool down for longer) — so the rule needs no
 * special case for a shape the UI does not currently expose.
 *
 * The **session strip** deliberately draws this differently: it clips the
 * segments back to the planned length and draws the appended time as its own
 * dashed block, because the strip's question is "what was added" while the
 * band's is "what am I holding". Both answers are true at once.
 */
private fun List<GuidanceSegment>.extendedBy(extensionSec: Int): List<GuidanceSegment> {
    if (extensionSec <= 0 || isEmpty()) return this
    val last = last()
    return dropLast(1) + last.copy(endSec = last.endSec + extensionSec)
}

/**
 * A countdown's seconds: **rounded up**, so a clock reads `3:00` for the whole
 * first second of a three-minute segment and only reaches `0:00` at the
 * boundary itself.
 *
 * The opposite of elapsed, which floors. The pair is the ordinary convention for
 * a clock that counts both ways, and the alternative — flooring both — would
 * show `2:59` the instant a segment began.
 */
internal fun ceilSeconds(millis: Long): Int =
    if (millis <= 0) 0 else ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()

internal const val MILLIS_PER_SECOND = 1000L

internal const val SECONDS_PER_MINUTE = 60
