package dev.jtiisto.wellness.feature.coach.guidance

import dev.jtiisto.wellness.core.ble.trace.TraceSample
import kotlin.math.roundToInt

/**
 * What a finished guided ride writes into the cardio entry it was ridden for.
 *
 * The log's three manual fields, computed from the two things the guide already
 * knows — the timeline the rider was guided against and the beats the strap
 * stored — so that a ride the app watched end to end does not then have to be
 * typed in from memory. It is a *fill*, never an authority: every field is
 * written only into an empty one, and a rider who typed a number keeps it.
 *
 * [avgHr] and [maxHr] are null together, and only when the work spans hold no
 * beats at all — a strap that died after the warmup, most plausibly. [durationMin]
 * still stands in that case: it is a statement about the timeline, not about the
 * beats.
 */
data class GuidedRideFill(
    val durationMin: Int,
    val avgHr: Int?,
    val maxHr: Int?,
)

/**
 * Read a finished run's three numbers off its beats, or null when there is
 * nothing to fill from.
 *
 * Pure, and given the run rather than a clock: **whether** to fill is the
 * ViewModel's question (the guide was dismissed, the phase had reached DONE),
 * and *what* to fill is this one. Null here means the ride cannot be described —
 * no anchor, no length, or no beats inside it — and the entry stays exactly as
 * manual as it was before the guide existed.
 *
 * ## duration_min is the timeline, never the clock
 *
 * The total including every appended minute, because that is the ride the guide
 * described. The elapsed clock is deliberately not used: DONE is a reading
 * rather than a latch, so a rider who finishes and leaves the overlay up while
 * they stretch would otherwise log the stretching. A run dismissed *before*
 * DONE fills nothing at all — an early bail is the rider's to log, exactly as it
 * always was.
 *
 * ## The heart rates are the work, not the preparation
 *
 * Both numbers are taken over the **work-role spans only** ([SegmentRole]),
 * which is what the role field exists for: a warmup and a cooldown inside an
 * average drag it below what the session actually asked for, and a max taken
 * across them is the same number either way only by luck. The easy/recovery
 * steps between efforts are work-role and therefore *in* — they are part of the
 * protocol, not preparation for it.
 *
 * A segmentless ride is entirely work: there is no timeline to tell one part
 * from another, and the whole span is what the rider rode.
 *
 * Spans are absolute instants — anchor plus the resolved offsets, with the
 * appended time already folded into the segment that absorbed it — and a beat
 * belongs to the span holding its own timestamp, on the half-open convention
 * [GuidanceSegment.holdsAt] uses everywhere else.
 *
 * Each stored beat is one RR interval carrying the notification's reported rate,
 * so the average is weighted by beats rather than by notifications. That is the
 * right weighting for "what was the heart rate over this stretch": a slow minute
 * contributes fewer beats to it, which is what actually happened.
 */
fun guidedRideFill(
    beats: List<TraceSample>,
    timeline: GuidanceTimeline,
    run: GuidanceRun,
): GuidedRideFill? {
    val anchorMs = run.startedAtMs ?: return null
    val extensionSec = run.extensionSec.coerceAtLeast(0)
    val totalSec = timeline.plannedTotalSec?.plus(extensionSec) ?: return null
    if (totalSec <= 0) return null

    val ride = anchorMs until anchorMs + totalSec * MILLIS_PER_SECOND
    // No beats anywhere in the ride is the strapless case, and it fills nothing:
    // the guide was a display, and the log is the rider's to write.
    if (beats.none { it.timestampMs in ride }) return null

    val segments = timeline.segments.extendedBy(extensionSec)
    val workSpans = if (segments.isEmpty()) {
        listOf(ride)
    } else {
        segments.filter { it.isWork }
            .map { anchorMs + it.startMs until anchorMs + it.endMs }
    }
    val work = beats.filter { beat -> workSpans.any { beat.timestampMs in it } }.map { it.bpm }

    return GuidedRideFill(
        // Rounded to the nearest minute, with a floor of one: the field is the
        // minutes a human would write down, and a timeline whose segments do not
        // land on a whole minute is still a ride that happened.
        durationMin = (totalSec / SECONDS_PER_MINUTE.toDouble()).roundToInt().coerceAtLeast(1),
        avgHr = work.average().takeIf { work.isNotEmpty() }?.roundToInt(),
        maxHr = work.maxOrNull(),
    )
}
