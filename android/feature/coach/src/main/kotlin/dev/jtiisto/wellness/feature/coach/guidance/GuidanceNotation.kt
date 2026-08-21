package dev.jtiisto.wellness.feature.coach.guidance

import dev.jtiisto.wellness.core.ui.theme.INK_BANG
import dev.jtiisto.wellness.feature.coach.hrBoundsToken
import dev.jtiisto.wellness.feature.coach.segmentClock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The cardio guide's notation: every word the overlay draws, and the sentence it
 * says instead.
 *
 * The header grammar is binding (user feedback on the mockup): between the title
 * and the chart there is **one type size per line** — a mono-caps context line
 * with a Start and an End item, then the two live numbers on one shared baseline
 * at fixed Start/End edges, then their small mono-caps labels in the same
 * columns. No number's position may depend on a neighbour's text width. That is
 * a layout rule, but it only holds if the *strings* are decided somewhere a test
 * can read them, which is here — the same split `CoachNotation` makes, for the
 * same reason.
 *
 * Every drawn state carries its spoken twin in the same value, so the two cannot
 * drift: a chart is geometry to a screen reader, and a live instrument that says
 * nothing is an instrument with no readout at all. Drawn strings are
 * **natural-cased and uppercased by the callsite** (Logbook sets these lines
 * mono-caps and Compose has no text-transform) — `CoachNotation`'s eyebrow
 * convention, which is why the spoken halves are written as sentences rather
 * than derived from the drawn halves by lowercasing them.
 */

/** What the guide draws, and what it says. Both are decided here, together. */
data class GuidanceLine(val drawn: String, val spoken: String)

/** Nothing to report in a slot that must keep its column. */
private const val EM_DASH = "—"

/** The separator between a slot's two words (U+00B7), matching the static line. */
private const val DOT = " · "

// ---- the context line ---------------------------------------------------------------

/**
 * The context line's **Start** slot: which part of the timeline is running.
 *
 * The counted form (`Hard · 3 of 4`) counts **segments sharing a label**, not
 * position in the flat list — a VO2 session's four hard efforts are written out
 * one after another with the easy periods between them, and "3 of 4" is the
 * thing the rider wants to know. Grouping is by the label alone, trimmed and
 * case-folded; a segment with no label has no identity to group by and falls
 * back to its position (`Segment 3 of 9`), which is honest rather than clever.
 *
 * The order of the rules is the order of what outranks what:
 *
 * 1. [GuidancePhase.DONE] — the timeline is over; naming a segment would name
 *    one that has already finished.
 * 2. no segments at all — say so. This slot's whole job is naming the band, and
 *    the segmentless guide has none: `No target` is what distinguishes it from
 *    every other guide, and it pairs with the `TARGET —` beneath it rather than
 *    contradicting it.
 * 3. a single segment — `Steady`, per the spec, **whatever it is labelled**. The
 *    Zone 2 case is defined by its shape (one continuous band), not by a word
 *    the plan author happened to choose.
 * 4. a labelled segment — its label, counted when it has siblings.
 * 5. anything else — its position.
 */
fun segmentContext(status: GuidanceStatus): GuidanceLine {
    if (status.phase == GuidancePhase.DONE) return GuidanceLine("Done", "Timeline complete")
    if (status.isSegmentless) return GuidanceLine("No target", "No heart-rate target")
    if (status.isSteady) return GuidanceLine("Steady", "Steady state")
    val current = status.current
        ?: return GuidanceLine("Done", "Timeline complete")
    val label = current.label
    if (label == null) {
        val position = "Segment ${current.index + 1} of ${status.segments.size}"
        return GuidanceLine(position, position)
    }
    val siblings = status.segments.count { it.labelKey() == current.labelKey() }
    if (siblings <= 1) return GuidanceLine(label, label)
    val ordinal = status.segments.filter { it.labelKey() == current.labelKey() }.indexOf(current) + 1
    return GuidanceLine("$label$DOT$ordinal of $siblings", "$label, $ordinal of $siblings")
}

/**
 * The context line's **End** slot: what is coming, or what has been added.
 *
 * The extension outranks the next segment, and in the case that offers the
 * control there is no competition — a steady ride has no next segment. The note
 * is **cumulative**: `+10:00` after two taps, because the extension is a
 * property of the timeline rather than of the tap that made it.
 *
 * A next segment is named the way the rider will recognise it: its label when
 * the author wrote one, otherwise its target — a step nobody named is still
 * `≤150`, and that is more use in the two seconds before it arrives than the
 * word "segment" would be. Then its duration, so the rider knows how long they
 * are about to hold it.
 *
 * Null when there is nothing to say: the last segment of an un-extended
 * timeline. The slot draws nothing rather than filling with a placeholder.
 */
fun nextUp(status: GuidanceStatus): GuidanceLine? {
    if (status.extensionSec > 0) {
        val clock = segmentClock(status.extensionSec)
        return GuidanceLine(
            drawn = "Extended$DOT+$clock",
            spoken = "Extended by ${spokenClock(status.extensionSec)}",
        )
    }
    val next = status.next ?: return null
    val name = next.label ?: hrBoundsToken(next.hrMin, next.hrMax)
    val clock = segmentClock(next.durationSec)
    val drawn = if (name == null) "Next$DOT$clock" else "Next$DOT$name $clock"
    val spokenName = if (name == null) "" else "$name, "
    return GuidanceLine(drawn, "Next: $spokenName${spokenClock(next.durationSec)}")
}

// ---- the two live numbers, and their labels ----------------------------------------

/**
 * The `TARGET` slot's value — the token alone, since the word itself is chrome
 * the header owns and never changes.
 *
 * The three shapes are the static plan line's own ([hrBoundsToken]), so the day
 * view and the overlay cannot disagree about what the plan asked for. A segment
 * with no band, and the interval after the timeline has ended, both read as a
 * dash: the column has to hold, and there is nothing true to put in it.
 */
fun targetToken(status: GuidanceStatus): GuidanceLine {
    val segment = status.current?.takeIf { status.phase != GuidancePhase.DONE }
    val token = segment?.let { hrBoundsToken(it.hrMin, it.hrMax) }
        ?: return GuidanceLine(EM_DASH, "No target")
    val min = segment.hrMin
    val max = segment.hrMax
    val spoken = when {
        min != null && max != null -> "Target $min to $max beats per minute"
        min != null -> "Target at least $min beats per minute"
        else -> "Target at most $max beats per minute"
    }
    return GuidanceLine(token, spoken)
}

/**
 * The header's other live number: the heart rate itself.
 *
 * The drawn half is the bare numeral — the unit is chrome the header sets
 * beside it once, and a number that carried its own unit could not share a
 * baseline with the countdown opposite. A null reading draws the same em dash
 * every other empty slot in this file draws, which is also the character the
 * BPM chip shows when a capture has had no beat yet: the guide and the chip
 * blank identically, because they are blanking for the same reason.
 *
 * What null *means* here is [currentReading]'s decision, not this one — this
 * only says how the two cases are spelled. "No current heart rate" rather than
 * "no heart rate": the strap may be perfectly alive and merely quiet, and the
 * tone dot beside this number is the surface that reports on the link.
 */
fun bpmReadout(bpm: Int?): GuidanceLine {
    if (bpm == null) return GuidanceLine(EM_DASH, "No current heart rate")
    return GuidanceLine(bpm.toString(), "Heart rate $bpm beats per minute")
}

/**
 * The `REMAINING` number: mono `M:SS`, so it ticks in place.
 *
 * What it counts down is the current **segment** when there are several and the
 * **session** when there are not — which for a single-segment timeline is the
 * same instant either way, and is what lets one label sit under both shapes
 * without lying about either. The spoken twin says which, because a screen
 * reader cannot see the band the number sits above.
 */
fun remaining(status: GuidanceStatus): GuidanceLine {
    val seconds = status.remainingSec
        ?: return GuidanceLine(EM_DASH, "No countdown — this exercise has no planned length")
    if (status.phase == GuidancePhase.DONE) {
        return GuidanceLine(segmentClock(seconds), "Timeline complete")
    }
    val scope = if (status.segments.size > 1) "in this segment" else "in the session"
    return GuidanceLine(segmentClock(seconds), "${spokenClock(seconds)} left $scope")
}

// ---- the footer, the strip's caption, and the eyebrow -------------------------------

/**
 * The footer's Start item: `Elapsed 16:18 · of 25:00`.
 *
 * The total includes the extension, because it is the total of the timeline the
 * rider is now riding — what was *planned* is said by the strip's caption, which
 * is the surface that owns the distinction.
 *
 * An open-ended timeline drops the second half rather than counting toward a
 * number nobody set.
 */
fun elapsedFooter(status: GuidanceStatus): GuidanceLine {
    val elapsed = segmentClock(status.elapsedSec)
    val total = status.totalSec
        ?: return GuidanceLine("Elapsed $elapsed", "${spokenClock(status.elapsedSec)} elapsed")
    return GuidanceLine(
        drawn = "Elapsed $elapsed${DOT}of ${segmentClock(total)}",
        spoken = "${spokenClock(status.elapsedSec)} elapsed of ${spokenClock(total)}",
    )
}

/**
 * The mono caption above the session strip.
 *
 * Deliberately **not** the mockup's prose (`4 × (3:00 hard / 2:00 easy) after
 * 5:00 warmup`): recovering that sentence means detecting the repeat structure
 * in a flat list, and the wire flattened it on purpose — the spec's rule is
 * explicit, ordered segments with no rounds shorthand and no derivation. A
 * caption that re-derived the structure would be inventing exactly what the plan
 * declined to state, and would go wrong on the first plan that does not repeat
 * cleanly. The count and the total say what is true.
 *
 * Null on an open-ended timeline: with no total there is no proportion, and the
 * strip it captions does not draw either.
 */
fun sessionSummary(status: GuidanceStatus): GuidanceLine? {
    val total = status.totalSec ?: return null
    val planned = status.plannedTotalSec
    val parts = mutableListOf("Session", segmentClock(total))
    val spokenParts = mutableListOf("Session, ${spokenClock(total)}")
    if (status.segments.size > 1) {
        parts += "${status.segments.size} segments"
        spokenParts += "${status.segments.size} segments"
    }
    if (status.extensionSec > 0 && planned != null) {
        parts += "extended from ${segmentClock(planned)}"
        spokenParts += "extended from ${spokenClock(planned)}"
    }
    return GuidanceLine(parts.joinToString(DOT), spokenParts.joinToString(", "))
}

/**
 * The overlay's eyebrow: `Guide · Started 6:41 pm`, or just `Guide` before the
 * clock is anchored.
 *
 * [locale] and [zone] are required rather than defaulted, for `CoachNotation`'s
 * reason: the one caller already takes both from the state build, and a silent
 * `systemDefault()` in here would be a second clock nobody could pin from a
 * test. The instant is the phone's own wall clock — no server timestamp is
 * parsed, and nothing derived here is ever compared, stored or uploaded.
 */
fun guideEyebrow(run: GuidanceRun, locale: Locale, zone: ZoneId): GuidanceLine {
    val startedAtMs = run.startedAtMs ?: return GuidanceLine("Guide", "Guide, not started")
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(startedAtMs).atZone(zone))
    return GuidanceLine("Guide${DOT}Started $time", "Guide, started at $time")
}

// ---- the chart's own words ----------------------------------------------------------

/**
 * A segment's HR constraint in words: `between 125 and 140 beats per minute`,
 * `at least 155 beats per minute`, `at most 150 beats per minute`.
 *
 * The spoken twin of [hrBoundsToken]'s three symbol shapes, kept beside its
 * consumers rather than in `CoachNotation` because only the guide speaks —
 * the static plan line is read as its drawn text.
 */
fun spokenBounds(hrMin: Int?, hrMax: Int?): String? {
    val min = hrMin?.takeIf { it != 0 }
    val max = hrMax?.takeIf { it != 0 }
    return when {
        min != null && max != null -> "between $min and $max beats per minute"
        min != null -> "at least $min beats per minute"
        max != null -> "at most $max beats per minute"
        else -> null
    }
}

/**
 * The caption drawn beside an upcoming band: `easy ≤150`, or just its target
 * when the author named nothing — paired with the words that stand in for it
 * (`easy, at most 150 beats per minute`), because a drawn string without a
 * spoken twin is a reading TalkBack cannot give.
 *
 * Null for a segment with no band — there is no rectangle to caption.
 */
fun bandCaption(segment: GuidanceSegment): GuidanceLine? {
    val token = hrBoundsToken(segment.hrMin, segment.hrMax) ?: return null
    val spoken = spokenBounds(segment.hrMin, segment.hrMax) ?: return null
    val label = segment.label ?: return GuidanceLine(token, spoken)
    return GuidanceLine("$label $token", "$label, $spoken")
}

/**
 * The out-of-band alarm: the mono bang beside the trace and beside the BPM
 * readout, and the words that stand in for it.
 *
 * Ink only — Variant A, the user's choice. The live-signal palette keeps its
 * closed two-consumer set, so the only colour on this screen stays the strap's
 * own tone dot. Null when the reading is inside its band, or when there is no
 * band to be outside of.
 */
fun bandBreach(bpm: Int?, segment: GuidanceSegment?): GuidanceLine? {
    if (bpm == null || segment == null || !segment.outOfBand(bpm)) return null
    val min = segment.hrMin
    val spoken = if (min != null && bpm < min) "Below target" else "Above target"
    return GuidanceLine(INK_BANG, spoken)
}

/**
 * What the scrolling window says out loud.
 *
 * The chart is one node and it is pure geometry, so this is the whole of it: how
 * much time it covers (the drawn `now` and `+10s` tick labels are accounted for
 * here — the window sentence names both the history and the lookahead, which is
 * everything those labels say), the current reading, and whether that reading is
 * where it was asked to be. Deliberately not a description of the trace's shape
 * — a screen reader user is being told what the instrument reads, not what it
 * looks like, and the numbers above it are the reading.
 *
 * [sampledSegment] is the segment active AT THE LATEST SAMPLE'S TIMESTAMP — the
 * same segment the ink's out-of-band verdict was computed against — not the
 * segment active now. Right after a boundary the two differ, and re-deriving
 * the verdict against "now" would let the spoken answer disagree with the drawn
 * bang about the same beat.
 */
fun traceDescription(bpm: Int?, sampledSegment: GuidanceSegment?): String {
    val window =
        "Heart rate trace, last $TRACE_HISTORY_SEC seconds and next $TRACE_FUTURE_SEC"
    val reading = bpm?.let { "$it beats per minute" } ?: "no current reading"
    val verdict = when {
        bpm == null -> null
        sampledSegment?.hasBand != true -> null
        sampledSegment.outOfBand(bpm) ->
            bandBreach(bpm, sampledSegment)?.spoken?.lowercase(Locale.ROOT)
        else -> "within target"
    }
    return listOfNotNull(window, reading, verdict).joinToString(", ") + "."
}

// ---- shared spelling ----------------------------------------------------------------

/**
 * A duration in words: `1 minute 42 seconds`.
 *
 * The spoken counterpart of [segmentClock]'s `M:SS`, and it keeps that
 * formatter's one quirk on purpose — minutes are not wrapped into hours, so a
 * long ride is said as `61 minutes 30 seconds`. The drawn and spoken forms
 * naming the same span the same way is worth more than the elegance of an hour
 * field that only one of them would have.
 */
fun spokenClock(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    val minutes = total / SECONDS_PER_MINUTE
    val rest = total % SECONDS_PER_MINUTE
    val words = mutableListOf<String>()
    if (minutes > 0) words += "$minutes ${plural(minutes, "minute")}"
    if (rest > 0 || minutes == 0) words += "$rest ${plural(rest, "second")}"
    return words.joinToString(" ")
}

private fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"

/**
 * A segment's grouping identity: its label, case-folded. (It arrives trimmed —
 * [guidanceTimeline] does that on the way in.)
 *
 * Case-folded because `Hard` and `HARD` in one plan are one kind of effort typed
 * twice, not two kinds — and a count that split them would say `2 of 2` beside a
 * session anyone can see has four. [Locale.ROOT] is what keeps the fold off the
 * device's locale, where a Turkish `I` would fold to a letter the plan does not
 * contain.
 */
private fun GuidanceSegment.labelKey(): String? = label?.lowercase(Locale.ROOT)
