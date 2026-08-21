package dev.jtiisto.wellness.feature.coach.guidance

import dev.jtiisto.wellness.core.data.coach.PlanSegmentDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.util.Locale

/**
 * Every word the guide draws, and the sentence it says instead.
 *
 * Drawn strings are natural-cased here and uppercased by the callsite — the
 * `CoachNotation` eyebrow convention — so the assertions read in title case
 * while the screen reads mono-caps. The spoken halves are asserted beside their
 * drawn halves rather than in a section of their own, which is the point of
 * carrying both in one value: a state that gained a word on screen and lost one
 * out loud would fail here.
 */
class GuidanceNotationTest {

    /**
     * A locale whose short time is a 24-hour clock.
     *
     * Deliberately not `Locale.US`, for `CoachNotationTest`'s reason: CLDR 42
     * puts a narrow no-break space before AM/PM, and pinning an en-US caption
     * would pin a CLDR revision rather than our formatting.
     */
    private val uk = Locale.UK
    private val utc = ZoneId.of("UTC")

    private fun segment(durationSec: Int, min: Int? = null, max: Int? = null, label: String? = null) =
        PlanSegmentDto(durationSec = durationSec, hrMin = min, hrMax = max, label = label)

    private val intervals = listOf(
        segment(300, min = 118, max = 132, label = "warmup"),
        segment(180, min = 158, max = 172, label = "hard"),
        segment(120, max = 144, label = "easy"),
        segment(180, min = 158, max = 172, label = "hard"),
        segment(120, max = 144, label = "easy"),
    )

    private fun statusAt(
        segments: List<PlanSegmentDto>?,
        elapsedMs: Long,
        plannedDurationSec: Int? = null,
        extensionSec: Int = 0,
        started: Boolean = true,
    ) = guidanceStatus(
        timeline = guidanceTimeline(segments, plannedDurationSec),
        run = GuidanceRun(startedAtMs = if (started) T0 else null, extensionSec = extensionSec),
        nowMs = T0 + elapsedMs,
    )

    // ---- the context line's Start slot ------------------------------------------------

    @Test
    @DisplayName("a repeated label is counted among its own kind, not by position in the list")
    fun labelledSegmentsAreCountedByKind() {
        // Second `hard` of two, at index 3 of five — the count the rider wants is
        // the effort's, not the list's.
        val line = segmentContext(statusAt(intervals, elapsedMs = 660_000))

        // The label renders exactly as the plan author wrote it; the callsite
        // sets the whole line mono-caps, so the case here is not the case drawn.
        assertEquals("hard · 2 of 2", line.drawn)
        assertEquals("hard, 2 of 2", line.spoken)
    }

    @Test
    @DisplayName("case and padding do not split a label: one kind typed two ways is one kind")
    fun labelGroupingIsCaseFolded() {
        val mixed = listOf(
            segment(60, min = 150, label = "Hard"),
            segment(60, max = 130, label = "easy"),
            segment(60, min = 150, label = " HARD "),
        )

        assertEquals("Hard · 1 of 2", segmentContext(statusAt(mixed, 0)).drawn)
        assertEquals("HARD · 2 of 2", segmentContext(statusAt(mixed, 120_000)).drawn)
    }

    @Test
    @DisplayName("a label with no siblings stands alone — 1 of 1 is noise")
    fun uniqueLabelIsNotCounted() {
        assertEquals("warmup", segmentContext(statusAt(intervals, 0)).drawn)
    }

    @Test
    @DisplayName("an unlabelled segment falls back to its position, which is the only identity it has")
    fun unlabelledSegmentsFallBackToPosition() {
        val unnamed = listOf(segment(60, max = 130), segment(90, min = 150), segment(60, max = 130))

        val line = segmentContext(statusAt(unnamed, 60_000))

        assertEquals("Segment 2 of 3", line.drawn)
        assertEquals("Segment 2 of 3", line.spoken)
    }

    @Test
    @DisplayName("one segment is STEADY whatever its author called it — the shape defines the case")
    fun singleSegmentIsSteady() {
        val line = segmentContext(statusAt(listOf(segment(1_800, min = 122, max = 138, label = "zone 2")), 0))

        assertEquals("Steady", line.drawn)
        assertEquals("Steady state", line.spoken)
    }

    @Test
    @DisplayName("a guide with no timeline says so, and pairs with the dash beneath it")
    fun segmentlessContext() {
        val status = statusAt(segments = null, elapsedMs = 0, plannedDurationSec = 2_400)

        assertEquals("No target", segmentContext(status).drawn)
        assertEquals("No heart-rate target", segmentContext(status).spoken)
        assertEquals("—", targetToken(status).drawn)
    }

    @Test
    @DisplayName("a finished timeline reads DONE rather than naming a segment that has ended")
    fun doneOutranksTheSegment() {
        val line = segmentContext(statusAt(intervals, elapsedMs = 1_000_000))

        assertEquals("Done", line.drawn)
        assertEquals("Timeline complete", line.spoken)
    }

    // ---- the context line's End slot ---------------------------------------------------

    @Test
    @DisplayName("the next segment is named by its label and how long it lasts")
    fun nextSegmentByLabel() {
        val line = nextUp(statusAt(intervals, elapsedMs = 310_000))

        assertEquals("Next · easy 2:00", line?.drawn)
        assertEquals("Next: easy, 2 minutes", line?.spoken)
    }

    @Test
    @DisplayName("an unnamed next segment is named by its target instead")
    fun nextSegmentByTarget() {
        val unnamed = listOf(segment(300, min = 118, max = 132, label = "warmup"), segment(90, min = 155))

        val line = nextUp(statusAt(unnamed, elapsedMs = 0))

        assertEquals("Next · ≥155 1:30", line?.drawn)
        assertEquals("Next: ≥155, 1 minute 30 seconds", line?.spoken)
    }

    @Test
    @DisplayName("a next segment with neither name nor target is still announced by its length")
    fun nextSegmentByDurationAlone() {
        val bare = listOf(segment(60, max = 130), segment(45))

        assertEquals("Next · 0:45", nextUp(statusAt(bare, 0))?.drawn)
    }

    @Test
    @DisplayName("the last segment of an un-extended timeline leaves the slot empty")
    fun nothingComesAfterTheLastSegment() {
        assertNull(nextUp(statusAt(intervals, elapsedMs = 780_000)))
    }

    @Test
    @DisplayName("the extension note is cumulative and outranks whatever is coming next")
    fun extensionOutranksTheNextSegment() {
        val line = nextUp(statusAt(intervals, elapsedMs = 310_000, extensionSec = 600))

        assertEquals("Extended · +10:00", line?.drawn)
        assertEquals("Extended by 10 minutes", line?.spoken)
    }

    // ---- the labels under the live numbers ----------------------------------------------

    @Test
    @DisplayName("the three target shapes are the static plan line's own")
    fun targetShapes() {
        val range = statusAt(listOf(segment(60, min = 158, max = 172)), 0)
        assertEquals("158–172", targetToken(range).drawn)
        assertEquals("Target 158 to 172 beats per minute", targetToken(range).spoken)

        val floor = statusAt(listOf(segment(60, min = 140)), 0)
        assertEquals("≥140", targetToken(floor).drawn)
        assertEquals("Target at least 140 beats per minute", targetToken(floor).spoken)

        val ceiling = statusAt(listOf(segment(60, max = 144)), 0)
        assertEquals("≤144", targetToken(ceiling).drawn)
        assertEquals("Target at most 144 beats per minute", targetToken(ceiling).spoken)
    }

    @Test
    @DisplayName("a boundless segment and a finished timeline both hold the column with a dash")
    fun targetDashes() {
        assertEquals("—", targetToken(statusAt(listOf(segment(60)), 0)).drawn)
        assertEquals("No target", targetToken(statusAt(listOf(segment(60)), 0)).spoken)
        assertEquals("—", targetToken(statusAt(intervals, elapsedMs = 1_000_000)).drawn)
    }

    @Test
    @DisplayName("REMAINING ticks in M:SS and says which clock it is counting")
    fun remainingScopeIsSpoken() {
        val inSegment = remaining(statusAt(intervals, elapsedMs = 318_000))
        assertEquals("2:42", inSegment.drawn)
        assertEquals("2 minutes 42 seconds left in this segment", inSegment.spoken)

        val inSession = remaining(statusAt(segments = null, elapsedMs = 60_000, plannedDurationSec = 2_400))
        assertEquals("39:00", inSession.drawn)
        assertEquals("39 minutes left in the session", inSession.spoken)
    }

    @Test
    @DisplayName("a finished countdown reads 0:00 and says what that means")
    fun remainingWhenDone() {
        val line = remaining(statusAt(intervals, elapsedMs = 1_000_000))

        assertEquals("0:00", line.drawn)
        assertEquals("Timeline complete", line.spoken)
    }

    @Test
    @DisplayName("an open-ended ride has no countdown, and the slot says so rather than showing zero")
    fun remainingWhenOpenEnded() {
        val line = remaining(statusAt(segments = null, elapsedMs = 60_000))

        assertEquals("—", line.drawn)
        assertTrue(line.spoken.startsWith("No countdown"))
    }

    // ---- the footer and the strip's caption ---------------------------------------------

    @Test
    @DisplayName("the footer counts against the timeline being ridden, extension included")
    fun footerCountsTheLiveTotal() {
        // A 15:00 plan with five minutes appended: the footer counts to 20:00,
        // not to what the plan asked for.
        val line = elapsedFooter(statusAt(intervals, elapsedMs = 978_000, extensionSec = 300))

        assertEquals("Elapsed 16:18 · of 20:00", line.drawn)
        assertEquals("16 minutes 18 seconds elapsed of 20 minutes", line.spoken)
    }

    @Test
    @DisplayName("an open-ended ride counts up and stops there")
    fun footerWithoutATotal() {
        val line = elapsedFooter(statusAt(segments = null, elapsedMs = 90_000))

        assertEquals("Elapsed 1:30", line.drawn)
        assertEquals("1 minute 30 seconds elapsed", line.spoken)
    }

    @Test
    @DisplayName("the strip's caption counts segments and names what the extension grew from")
    fun sessionCaption() {
        assertEquals("Session · 15:00 · 5 segments", sessionSummary(statusAt(intervals, 0))?.drawn)

        val extended = statusAt(listOf(segment(1_800, min = 122, max = 138)), 0, extensionSec = 600)
        assertEquals("Session · 40:00 · extended from 30:00", sessionSummary(extended)?.drawn)
        assertEquals("Session, 40 minutes, extended from 30 minutes", sessionSummary(extended)?.spoken)
    }

    @Test
    @DisplayName("an open-ended ride has no proportion, so it captions no strip")
    fun noCaptionWithoutATotal() {
        assertNull(sessionSummary(statusAt(segments = null, elapsedMs = 0)))
    }

    // ---- the eyebrow ----------------------------------------------------------------------

    @Test
    @DisplayName("the eyebrow records the wall-clock start once the clock is anchored")
    fun eyebrowRecordsTheStart() {
        // T0 is midnight UTC; six and a half hours in is a legible 24-hour time.
        val run = GuidanceRun(startedAtMs = T0 + 23_400_000)

        val line = guideEyebrow(run, uk, utc)

        assertEquals("Guide · Started 06:30", line.drawn)
        assertEquals("Guide, started at 06:30", line.spoken)
    }

    @Test
    @DisplayName("before START the eyebrow is the bare word — there is no time to record")
    fun eyebrowBeforeStart() {
        val line = guideEyebrow(GuidanceRun(), uk, utc)

        assertEquals("Guide", line.drawn)
        assertEquals("Guide, not started", line.spoken)
    }

    @Test
    @DisplayName("the zone decides the caption, so the guide reads in the phone's own clock")
    fun eyebrowHonoursTheZone() {
        val run = GuidanceRun(startedAtMs = T0 + 23_400_000)

        assertEquals("Guide · Started 07:30", guideEyebrow(run, uk, ZoneId.of("Europe/Berlin")).drawn)
    }

    // ---- the chart's own words --------------------------------------------------------------

    @Test
    @DisplayName("a band is captioned by its label and target, or by its target alone — drawn and spoken")
    fun bandCaptions() {
        val timeline = guidanceTimeline(
            listOf(segment(120, max = 144, label = "easy"), segment(90, min = 155), segment(30)),
            null,
        )

        assertEquals("easy ≤144", bandCaption(timeline.segments[0])?.drawn)
        assertEquals("easy, at most 144 beats per minute", bandCaption(timeline.segments[0])?.spoken)
        assertEquals("≥155", bandCaption(timeline.segments[1])?.drawn)
        assertEquals("at least 155 beats per minute", bandCaption(timeline.segments[1])?.spoken)
        assertNull(bandCaption(timeline.segments[2]))
    }

    @Test
    @DisplayName("the spoken bounds cover all three shapes and keep the zero-is-absent rule")
    fun spokenBoundsShapes() {
        assertEquals("between 125 and 140 beats per minute", spokenBounds(125, 140))
        assertEquals("at least 155 beats per minute", spokenBounds(155, null))
        assertEquals("at most 150 beats per minute", spokenBounds(null, 150))
        assertEquals("at most 130 beats per minute", spokenBounds(0, 130))
        assertNull(spokenBounds(null, null))
        assertNull(spokenBounds(0, 0))
    }

    @Test
    @DisplayName("the bang says which side of the band the rider is on, and is silent inside it")
    fun bandBreachSides() {
        val range = guidanceTimeline(listOf(segment(60, min = 158, max = 172)), null).segments.single()

        assertEquals("Below target", bandBreach(150, range)?.spoken)
        assertEquals("Above target", bandBreach(180, range)?.spoken)
        assertEquals("!", bandBreach(180, range)?.drawn)
        assertNull(bandBreach(165, range))
        assertNull(bandBreach(null, range))
        assertNull(bandBreach(180, null))
    }

    @Test
    @DisplayName("a ceiling-only segment reads as above target — there is no floor to fall below")
    fun bandBreachOnACeiling() {
        val ceiling = guidanceTimeline(listOf(segment(60, max = 144)), null).segments.single()

        assertEquals("Above target", bandBreach(190, ceiling)?.spoken)
    }

    @Test
    @DisplayName("the window's spoken twin is the reading, not the shape of the line")
    fun traceIsSpokenAsAReading() {
        val range = guidanceTimeline(listOf(segment(180, min = 158, max = 172)), null)
            .segments.single()

        assertEquals(
            "Heart rate trace, last 30 seconds and next 15, 165 beats per minute, within target.",
            traceDescription(bpm = 165, sampledSegment = range),
        )
        assertEquals(
            "Heart rate trace, last 30 seconds and next 15, 180 beats per minute, above target.",
            traceDescription(bpm = 180, sampledSegment = range),
        )
    }

    @Test
    @DisplayName("the spoken verdict follows the sample's own segment, so it cannot disagree with the ink")
    fun traceVerdictFollowsTheSampledSegment() {
        // The ink judged this beat against the band running when it happened.
        // The description takes THAT segment, not the one running now — so a
        // beat over the warmup's ceiling stays "above target" in speech even
        // after the step into a band that would have allowed it.
        val warmup = guidanceTimeline(
            listOf(segment(300, min = 120, max = 140), segment(300, min = 140, max = 160)),
            null,
        ).segments.first()

        assertEquals(
            "Heart rate trace, last 30 seconds and next 15, 155 beats per minute, above target.",
            traceDescription(bpm = 155, sampledSegment = warmup),
        )
    }

    @Test
    @DisplayName("with no band there is no verdict, and with no beat there is no reading")
    fun traceWithoutAVerdict() {
        assertEquals(
            "Heart rate trace, last 30 seconds and next 15, 136 beats per minute.",
            traceDescription(bpm = 136, sampledSegment = null),
        )
        assertEquals(
            "Heart rate trace, last 30 seconds and next 15, no current reading.",
            traceDescription(bpm = null, sampledSegment = null),
        )
    }

    // ---- spoken durations ---------------------------------------------------------------------

    @Test
    @DisplayName("durations are said in whole words, singular where it matters")
    fun spokenDurations() {
        assertEquals("0 seconds", spokenClock(0))
        assertEquals("1 second", spokenClock(1))
        assertEquals("42 seconds", spokenClock(42))
        assertEquals("1 minute", spokenClock(60))
        assertEquals("1 minute 1 second", spokenClock(61))
        assertEquals("2 minutes", spokenClock(120))
        assertEquals("16 minutes 18 seconds", spokenClock(978))
    }

    @Test
    @DisplayName("an hour is said in minutes, matching the M:SS the number beside it draws")
    fun spokenDurationsDoNotWrapAtAnHour() {
        assertEquals("61 minutes 30 seconds", spokenClock(3_690))
    }

    @Test
    @DisplayName("a negative duration cannot reach the ear as a negative count")
    fun spokenDurationsAreClamped() {
        assertEquals("0 seconds", spokenClock(-90))
    }

    @Test
    @DisplayName("every state the header draws carries a spoken twin, and neither half is empty")
    fun everyDrawnStateSpeaks() {
        val states = listOf(
            statusAt(intervals, 0, started = false),
            statusAt(intervals, 318_000),
            statusAt(intervals, 1_000_000),
            statusAt(listOf(segment(1_800, min = 122, max = 138)), 60_000, extensionSec = 300),
            statusAt(segments = null, elapsedMs = 60_000, plannedDurationSec = 2_400),
            statusAt(segments = null, elapsedMs = 60_000),
        )
        for (status in states) {
            val lines = listOfNotNull(
                segmentContext(status),
                nextUp(status),
                targetToken(status),
                remaining(status),
                elapsedFooter(status),
                sessionSummary(status),
            )
            for (line in lines) {
                assertTrue(line.drawn.isNotBlank(), "a drawn line is never blank: $line")
                assertTrue(line.spoken.isNotBlank(), "a spoken twin is never blank: $line")
            }
        }
    }
}
