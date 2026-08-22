package dev.jtiisto.wellness.feature.coach.guidance

import dev.jtiisto.wellness.core.ble.trace.TraceSample
import dev.jtiisto.wellness.core.data.coach.PlanSegmentDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What a finished guided ride writes into its log entry.
 *
 * Two things carry this file. The duration has to come off the **timeline**,
 * because the clock keeps running after DONE and a rider who stretches before
 * dismissing must not log the stretching; and the heart rates have to come off
 * the **work** of the ride, because a warmup inside an average is a lower number
 * than the session asked for.
 */
class GuidedRideFillTest {

    private fun segment(
        durationSec: Int,
        min: Int? = null,
        max: Int? = null,
        role: String? = null,
    ) = PlanSegmentDto(durationSec = durationSec, hrMin = min, hrMax = max, role = role)

    /** Five minutes easy, ten steady, five spinning down. */
    private val wrapped = listOf(
        segment(300, max = 118, role = "warmup"),
        segment(600, min = 122, max = 138),
        segment(300, max = 118, role = "cooldown"),
    )

    private val run = GuidanceRun(startedAtMs = T0)

    /** A beat [atSec] into the run. */
    private fun beat(atSec: Int, bpm: Int) = TraceSample(T0 + atSec * 1_000L, bpm)

    /** One beat a second from [fromSec] until [toSec], all at [bpm]. */
    private fun beats(fromSec: Int, toSec: Int, bpm: Int) =
        (fromSec until toSec).map { beat(it, bpm) }

    private fun fill(
        beats: List<TraceSample>,
        segments: List<PlanSegmentDto>? = wrapped,
        plannedDurationSec: Int? = null,
        run: GuidanceRun = this.run,
    ) = guidedRideFill(beats, guidanceTimeline(segments, plannedDurationSec), run)

    @Test
    @DisplayName("the heart rates are the work segment's, and the warmup and cooldown are not in them")
    fun heartRatesCoverTheWorkOnly() {
        val ride = beats(0, 300, 100) + beats(300, 900, 130) + beats(900, 1_200, 95)

        val filled = requireNotNull(fill(ride))

        assertEquals(130, filled.avgHr)
        // The cooldown's beats are the lowest and the warmup's are not the
        // highest — a max taken across the whole ride would still be 130 here,
        // so the average is what proves the span, and this proves the max is
        // taken from the same beats.
        assertEquals(130, filled.maxHr)
        assertEquals(20, filled.durationMin)
    }

    @Test
    @DisplayName("a warmup harder than the work cannot inflate either number")
    fun aHardWarmupStaysOutOfTheNumbers() {
        // The case that makes the span rule visible rather than incidental: the
        // rider went out too fast and settled. The log records the session.
        val ride = beats(0, 300, 170) + beats(300, 900, 128) + beats(900, 1_200, 170)

        val filled = requireNotNull(fill(ride))

        assertEquals(128, filled.avgHr)
        assertEquals(128, filled.maxHr)
    }

    @Test
    @DisplayName("the easy steps between efforts count: they are the protocol, not preparation")
    fun recoveryStepsAreWork() {
        val intervals = listOf(
            segment(60, max = 118, role = "warmup"),
            segment(60, min = 158, max = 172),
            segment(60, max = 144),
            segment(60, min = 158, max = 172),
            segment(60, max = 118, role = "cooldown"),
        )
        val ride = beats(0, 60, 100) + beats(60, 120, 160) +
            beats(120, 180, 120) + beats(180, 240, 160) + beats(240, 300, 90)

        val filled = requireNotNull(fill(ride, segments = intervals))

        // (160 + 120 + 160) / 3 — the recovery minute is in the average, and
        // neither end of the ride is.
        assertEquals(147, filled.avgHr)
        assertEquals(160, filled.maxHr)
    }

    @Test
    @DisplayName("a segmentless ride is entirely work — there is no part of it to leave out")
    fun segmentlessRideIsAllWork() {
        val ride = beats(0, 1_800, 126) + listOf(beat(1_900, 190))

        val filled = requireNotNull(fill(ride, segments = null, plannedDurationSec = 1_800))

        assertEquals(30, filled.durationMin)
        assertEquals(126, filled.avgHr)
        // The 190 is a minute and a half past the end of the timeline. The ride
        // the guide described is the timeline, and beats after it are not in it.
        assertEquals(126, filled.maxHr)
    }

    @Test
    @DisplayName("the ride window is half-open — a beat at exactly its end is after the ride")
    fun rideWindowIsHalfOpen() {
        // The convention every boundary in the guide uses (`holdsAt`, the
        // spans, the boundary-owns-the-arriving-segment rule), applied to the
        // ride itself: the instant the timeline completes is DONE, not riding.
        // The DAO's inclusive BETWEEN may fetch this beat; this function owns
        // the rule and excludes it — deliberate, reviewed, and pinned here so
        // it cannot be read as an off-by-one.
        val lastInside = beat(1_799, 131)
        val atTheEnd = beat(1_800, 190)

        val filled = requireNotNull(
            fill(listOf(lastInside, atTheEnd), segments = null, plannedDurationSec = 1_800),
        )
        assertEquals(131, filled.maxHr)

        // And when the boundary beat is the ONLY beat, the window holds nothing:
        // a one-beat ride whose beat is outside it fills nothing at all.
        assertNull(fill(listOf(atTheEnd), segments = null, plannedDurationSec = 1_800))
    }

    @Test
    @DisplayName("the duration is the timeline including its appended minutes, never the clock")
    fun durationIncludesTheExtension() {
        val extended = GuidanceRun(startedAtMs = T0, extensionSec = 600)
        val ride = beats(0, 2_100, 130)

        val filled = requireNotNull(fill(ride, run = extended))

        // 20 planned minutes plus 10 appended, whatever the wall clock says.
        assertEquals(30, filled.durationMin)
    }

    @Test
    @DisplayName("appended minutes belong to the work segment, so they are inside the averaged span")
    fun theExtensionExtendsTheAveragedSpan() {
        val extended = GuidanceRun(startedAtMs = T0, extensionSec = 300)
        // Warmup, then the work segment — now fifteen minutes rather than ten —
        // and the cooldown five minutes later than the plan put it.
        val ride = beats(0, 300, 100) + beats(300, 1_200, 140) + beats(1_200, 1_500, 95)

        val filled = requireNotNull(fill(ride, run = extended))

        assertEquals(140, filled.avgHr)
        assertEquals(25, filled.durationMin)
    }

    @Test
    @DisplayName("a strapless ride fills nothing at all")
    fun noBeatsNoFill() {
        assertNull(fill(emptyList()))
        // Beats from some other time are the same thing as none: the window is
        // the ride, and a session recorded hours earlier is not it.
        assertNull(fill(listOf(TraceSample(T0 - 7_200_000L, 130))))
    }

    @Test
    @DisplayName("beats only outside the work still log the ride's length, with no heart rate to report")
    fun beatsOutsideTheWorkFillTheDurationAlone() {
        // The belt that came off after the warmup. The ride happened and its
        // length is known; the numbers it could not measure stay empty for the
        // rider to fill by hand.
        val filled = requireNotNull(fill(beats(0, 300, 100)))

        assertEquals(20, filled.durationMin)
        assertNull(filled.avgHr)
        assertNull(filled.maxHr)
    }

    @Test
    @DisplayName("a run that was never anchored, and one with nothing to count down, fill nothing")
    fun nothingToDescribe() {
        assertNull(fill(beats(0, 600, 130), run = GuidanceRun()))
        assertNull(guidedRideFill(beats(0, 600, 130), GuidanceTimeline.OPEN, run))
    }

    @Test
    @DisplayName("the minute is rounded, and a ride shorter than half a minute still logs one")
    fun durationRounding() {
        fun minutesFor(sec: Int) =
            fill(beats(0, 60, 130), segments = null, plannedDurationSec = sec)?.durationMin

        assertEquals(30, minutesFor(1_800))
        assertEquals(31, minutesFor(1_830))
        assertEquals(30, minutesFor(1_829))
        assertEquals(1, minutesFor(20))
    }

    @Test
    @DisplayName("a beat exactly on a boundary belongs to the segment that is arriving")
    fun boundaryBeatsFollowTheHalfOpenRule() {
        // The same rule the band, the countdown and the out-of-band verdict all
        // use: [start, end). The beat at 300 s is the work segment's first.
        val filled = requireNotNull(fill(listOf(beat(300, 150), beat(900, 90))))

        assertEquals(150, filled.avgHr)
        assertEquals(150, filled.maxHr)
    }
}
