package dev.jtiisto.wellness.feature.coach.guidance

import dev.jtiisto.wellness.core.data.coach.PlanExerciseDto
import dev.jtiisto.wellness.core.data.coach.PlanSegmentDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The guide's clock: resolving a plan into a timeline, and reading a run off it.
 *
 * Three things carry this file. A timeline built from durations has to put every
 * segment where the plan meant it; the phase has to be a *reading* of the clock
 * rather than a latch, so that appending time to a finished ride simply unmakes
 * the finish; and a beat has to be judged against the band that was running when
 * it happened, which is the whole reason the window can be wider than a segment.
 */
class GuidanceTimelineTest {

    // ---- fixtures -------------------------------------------------------------------

    private fun segment(
        durationSec: Int,
        min: Int? = null,
        max: Int? = null,
        label: String? = null,
        role: String? = null,
    ) = PlanSegmentDto(
        durationSec = durationSec,
        hrMin = min,
        hrMax = max,
        label = label,
        role = role,
    )

    /** Warmup, then three hard/easy pairs — the VO2 shape, written out flat. */
    private val intervals = listOf(
        segment(300, min = 118, max = 132, label = "warmup"),
        segment(180, min = 158, max = 172, label = "hard"),
        segment(120, max = 144, label = "easy"),
        segment(180, min = 158, max = 172, label = "hard"),
        segment(120, max = 144, label = "easy"),
        segment(180, min = 158, max = 172, label = "hard"),
        segment(120, max = 144, label = "easy"),
    )

    private val steady = listOf(segment(1_800, min = 122, max = 138, label = "zone 2"))

    /**
     * The shape the role rules exist for: one work segment with preparation
     * around it. Five minutes easy, thirty steady, five spinning down.
     */
    private val wrappedSteady = listOf(
        segment(300, max = 118, label = "ease in", role = "warmup"),
        segment(1_800, min = 122, max = 138, label = "zone 2"),
        segment(300, max = 118, label = "spin down", role = "cooldown"),
    )

    private fun statusAt(
        timeline: GuidanceTimeline,
        elapsedMs: Long,
        extensionSec: Int = 0,
        started: Boolean = true,
    ) = guidanceStatus(
        timeline = timeline,
        run = GuidanceRun(startedAtMs = if (started) T0 else null, extensionSec = extensionSec),
        nowMs = T0 + elapsedMs,
    )

    // ---- building the timeline --------------------------------------------------------

    @Test
    @DisplayName("durations become offsets: each segment starts where the one before it ended")
    fun offsetsAreCumulative() {
        val timeline = guidanceTimeline(intervals, plannedDurationSec = null)

        assertEquals(listOf(0, 300, 480, 600, 780, 900, 1_080), timeline.segments.map { it.startSec })
        assertEquals(listOf(300, 480, 600, 780, 900, 1_080, 1_200), timeline.segments.map { it.endSec })
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), timeline.segments.map { it.index })
    }

    @Test
    @DisplayName("segments are the timeline: their sum wins over a disagreeing target duration")
    fun segmentSumIsTheTotal() {
        val timeline = guidanceTimeline(intervals, plannedDurationSec = 9_999)

        assertEquals(1_200, timeline.plannedTotalSec)
    }

    @Test
    @DisplayName("without segments the exercise's own duration is the timeline")
    fun segmentlessUsesTargetDuration() {
        val timeline = guidanceTimeline(segments = null, plannedDurationSec = 2_400)

        assertTrue(timeline.segments.isEmpty())
        assertEquals(2_400, timeline.plannedTotalSec)
    }

    @Test
    @DisplayName("no segments and no duration is open-ended — a guide that is only a stopwatch")
    fun openEndedTimeline() {
        assertNull(guidanceTimeline(segments = null, plannedDurationSec = null).plannedTotalSec)
        assertNull(guidanceTimeline(segments = emptyList(), plannedDurationSec = 0).plannedTotalSec)
    }

    @Test
    @DisplayName("a zero bound is absent, and a blank label is no label")
    fun boundsAndLabelsAreNormalised() {
        val timeline = guidanceTimeline(
            listOf(segment(60, min = 0, max = 140, label = "   ")),
            plannedDurationSec = null,
        )

        val only = timeline.segments.single()
        assertNull(only.hrMin)
        assertEquals(140, only.hrMax)
        assertNull(only.label)
    }

    @Test
    @DisplayName("a non-positive duration cannot push later segments backwards")
    fun durationsAreClamped() {
        val timeline = guidanceTimeline(
            listOf(segment(-90, max = 140), segment(120, max = 150)),
            plannedDurationSec = null,
        )

        assertEquals(listOf(0, 0), timeline.segments.map { it.startSec })
        assertEquals(listOf(0, 120), timeline.segments.map { it.endSec })
        assertEquals(120, timeline.plannedTotalSec)
    }

    @Test
    @DisplayName("a planned exercise reads its timeline from segments, else from target minutes")
    fun timelineFromExercise() {
        val cardio = PlanExerciseDto(id = "e1", name = "Ride", type = "duration", targetDurationMin = 40)

        assertEquals(2_400, cardio.guidanceTimeline().plannedTotalSec)
        assertEquals(1_800, cardio.copy(segments = steady).guidanceTimeline().plannedTotalSec)
        assertNull(cardio.copy(targetDurationMin = 0).guidanceTimeline().plannedTotalSec)
    }

    @Test
    @DisplayName("target_duration_sec belongs to weighted_time and is never read as a cardio length")
    fun weightedTimeSecondsAreIgnored() {
        val exercise = PlanExerciseDto(id = "e1", name = "Plank", type = "duration", targetDurationSec = 90)

        assertNull(exercise.guidanceTimeline().plannedTotalSec)
    }

    // ---- the phase -------------------------------------------------------------------

    @Test
    @DisplayName("before START the guide is READY with the timeline pinned at 0:00")
    fun readyBeforeStart() {
        val status = statusAt(guidanceTimeline(intervals, null), elapsedMs = 600_000, started = false)

        assertEquals(GuidancePhase.READY, status.phase)
        assertEquals(0L, status.elapsedMs)
        assertEquals(0, status.elapsedSec)
        assertEquals("warmup", status.current?.label)
    }

    @Test
    @DisplayName("READY anchors the timeline at now, so the first band waits on the now-line")
    fun readyAnchorsAtNow() {
        val now = T0 + 987_654
        val status = guidanceStatus(guidanceTimeline(intervals, null), GuidanceRun(), now)

        assertEquals(now, status.anchorMs)
    }

    @Test
    @DisplayName("START anchors the clock; the phase runs until the timeline's last second")
    fun runsUntilTheEnd() {
        val timeline = guidanceTimeline(steady, null)

        assertEquals(GuidancePhase.RUNNING, statusAt(timeline, 0).phase)
        assertEquals(GuidancePhase.RUNNING, statusAt(timeline, 1_799_999).phase)
        assertEquals(GuidancePhase.DONE, statusAt(timeline, 1_800_000).phase)
        assertEquals(GuidancePhase.DONE, statusAt(timeline, 5_000_000).phase)
    }

    @Test
    @DisplayName("an open-ended timeline never reaches DONE — there is no end to reach")
    fun openEndedNeverCompletes() {
        val status = statusAt(GuidanceTimeline.OPEN, elapsedMs = 10_000_000)

        assertEquals(GuidancePhase.RUNNING, status.phase)
        assertNull(status.totalSec)
        assertNull(status.remainingSec)
        assertNull(status.remainingTotalSec)
    }

    @Test
    @DisplayName("a wall clock stepping backwards mid-ride freezes at 0:00 rather than going negative")
    fun elapsedIsClamped() {
        val status = guidanceStatus(
            timeline = guidanceTimeline(steady, null),
            run = GuidanceRun(startedAtMs = T0),
            nowMs = T0 - 60_000,
        )

        assertEquals(0L, status.elapsedMs)
        assertEquals(GuidancePhase.RUNNING, status.phase)
    }

    // ---- segment resolution -----------------------------------------------------------

    @Test
    @DisplayName("the boundary instant belongs to the segment arriving, not the one ending")
    fun boundariesBelongToTheNextSegment() {
        val timeline = guidanceTimeline(intervals, null)

        assertEquals(0, statusAt(timeline, 299_999).currentIndex)
        assertEquals(1, statusAt(timeline, 300_000).currentIndex)
        assertEquals(1, statusAt(timeline, 479_999).currentIndex)
        assertEquals(2, statusAt(timeline, 480_000).currentIndex)
    }

    @Test
    @DisplayName("the next segment is the one after the current, and null on the last")
    fun nextSegment() {
        val timeline = guidanceTimeline(intervals, null)

        assertEquals("hard", statusAt(timeline, 299_000).next?.label)
        assertNull(statusAt(timeline, 1_100_000).next)
    }

    @Test
    @DisplayName("past the end no segment holds the clock, so the target has nothing to name")
    fun noSegmentPastTheEnd() {
        val status = statusAt(guidanceTimeline(intervals, null), elapsedMs = 1_200_000)

        assertNull(status.currentIndex)
        assertNull(status.current)
        assertNull(status.next)
    }

    @Test
    @DisplayName("a beat is judged against the segment running at its own timestamp")
    fun segmentAtAnArbitraryInstant() {
        // The window is thirty seconds wide and a boundary sits inside it: the
        // beat five seconds back was ridden under the warmup, the one now under
        // the first hard interval.
        val status = statusAt(guidanceTimeline(intervals, null), elapsedMs = 302_000)

        assertEquals("warmup", status.segmentAt(297_000)?.label)
        assertEquals("hard", status.segmentAt(302_000)?.label)
        assertNull(status.segmentAt(-4_000))
        assertNull(status.segmentAt(1_200_000))
    }

    // ---- the countdown -----------------------------------------------------------------

    @Test
    @DisplayName("elapsed floors and remaining ceils, so a segment reads its full length as it opens")
    fun clocksRoundOppositeWays() {
        val timeline = guidanceTimeline(intervals, null)

        assertEquals(300, statusAt(timeline, 0).remainingSec)
        assertEquals(300, statusAt(timeline, 500).remainingSec)
        assertEquals(299, statusAt(timeline, 1_000).remainingSec)
        assertEquals(0, statusAt(timeline, 500).elapsedSec)
        assertEquals(1, statusAt(timeline, 1_999).elapsedSec)
    }

    @Test
    @DisplayName("REMAINING counts the segment when there are several and the session when there are not")
    fun remainingScope() {
        assertEquals(120, statusAt(guidanceTimeline(intervals, null), 480_000).remainingSec)
        assertEquals(600, statusAt(guidanceTimeline(null, 2_400), 1_800_000).remainingSec)
    }

    @Test
    @DisplayName("a finished timeline has nothing left, in the segment or the session")
    fun remainingIsZeroWhenDone() {
        val status = statusAt(guidanceTimeline(steady, null), elapsedMs = 2_000_000)

        assertEquals(0, status.remainingSec)
        assertEquals(0, status.remainingTotalSec)
    }

    // ---- extension ---------------------------------------------------------------------

    @Test
    @DisplayName("taps accumulate: the note is the running total, not the last tap")
    fun extensionAccumulates() {
        val run = GuidanceRun(startedAtMs = T0).extend().extend().extend()

        assertEquals(900, run.extensionSec)
    }

    @Test
    @DisplayName("appended time stretches the work segment, and the plan's own length is kept beside it")
    fun extensionStretchesTheWorkSegment() {
        val status = statusAt(guidanceTimeline(steady, null), elapsedMs = 60_000, extensionSec = 600)

        assertEquals(2_400, status.totalSec)
        assertEquals(1_800, status.plannedTotalSec)
        assertEquals(2_400, status.segments.single().endSec)
        assertEquals(2_340, status.remainingSec)
        assertEquals(0, status.extendedIndex)
    }

    @Test
    @DisplayName("the work segment is lengthened where it sits, and the cooldown after it shifts later intact")
    fun extensionMovesTheSegmentsAfterTheWorkOne() {
        val status = statusAt(guidanceTimeline(wrappedSteady, null), elapsedMs = 0, extensionSec = 300)

        assertEquals(1, status.extendedIndex)
        assertEquals(listOf(0, 300, 2_400), status.segments.map { it.startSec })
        assertEquals(listOf(300, 2_400, 2_700), status.segments.map { it.endSec })
        // The cooldown is five minutes long before and after: it moved, it did
        // not grow. Only the work segment is longer.
        assertEquals(listOf(300, 2_100, 300), status.segments.map { it.durationSec })
        assertEquals(2_700, status.totalSec)
        assertEquals(2_400, status.plannedTotalSec)
    }

    @Test
    @DisplayName("the current segment follows the shifted boundaries, not the planned ones")
    fun extensionMovesTheBoundariesTheClockIsReadAgainst() {
        val extended = guidanceTimeline(wrappedSteady, null)
        // 2_150 s in: past the planned end of the work segment (2_100), and in
        // the cooldown were it not for the five appended minutes.
        val status = statusAt(extended, elapsedMs = 2_150_000, extensionSec = 300)

        assertEquals(1, status.currentIndex)
        assertEquals("zone 2", status.current?.label)
        assertEquals(250, status.remainingSec)
    }

    @Test
    @DisplayName("a run extended before its plan changed shape still lands its minutes somewhere")
    fun extensionFallsBackToTheLastSegment() {
        // Several work segments have no single work segment to lengthen — the
        // control is never offered here, but a mid-ride re-sync can leave an
        // extension behind, and the segments must still sum to the total or the
        // strip and the countdown would disagree.
        val status = statusAt(guidanceTimeline(intervals, null), elapsedMs = 0, extensionSec = 300)

        assertEquals(6, status.extendedIndex)
        assertEquals(1_500, status.totalSec)
        assertEquals(1_500, status.segments.last().endSec)
        assertEquals(1_500, status.segments.sumOf { it.durationSec })
    }

    @Test
    @DisplayName("with no appended time nothing is extended and nothing moves")
    fun noExtensionNoTarget() {
        val status = statusAt(guidanceTimeline(wrappedSteady, null), elapsedMs = 0)

        assertNull(status.extendedIndex)
        assertEquals(listOf(0, 300, 2_100), status.segments.map { it.startSec })
    }

    @Test
    @DisplayName("appending to a finished ride makes it unfinished again — DONE is a reading, not a latch")
    fun extendingAfterDoneResumesRunning() {
        val timeline = guidanceTimeline(steady, null)
        assertEquals(GuidancePhase.DONE, statusAt(timeline, 1_810_000).phase)

        assertEquals(GuidancePhase.RUNNING, statusAt(timeline, 1_810_000, extensionSec = 300).phase)
    }

    @Test
    @DisplayName("one tap does not always undo DONE: the timeline is appended to, never re-based on now")
    fun extendingDoesNotChaseAnOvershoot() {
        // Twelve minutes past a thirty-minute plan. `+ 5 MIN` adds five minutes
        // to the *timeline*, which is what keeps `EXTENDED · +N:00` equal to five
        // times the taps and what the strip's dashed block draws; it does not
        // hand out five minutes from wherever the rider happens to be.
        val timeline = guidanceTimeline(steady, null)
        val overshot = 1_800_000L + 720_000L

        assertEquals(GuidancePhase.DONE, statusAt(timeline, overshot, extensionSec = 300).phase)
        assertEquals(GuidancePhase.DONE, statusAt(timeline, overshot, extensionSec = 600).phase)
        assertEquals(GuidancePhase.RUNNING, statusAt(timeline, overshot, extensionSec = 900).phase)
    }

    @Test
    @DisplayName("a fresh START discards the previous run's anchor and its appended minutes both")
    fun startDiscardsThePreviousRun() {
        val finished = GuidanceRun(startedAtMs = T0, extensionSec = 900)

        val fresh = finished.start(T0 + 4_000_000)

        assertEquals(T0 + 4_000_000, fresh.startedAtMs)
        assertEquals(0, fresh.extensionSec)
    }

    @Test
    @DisplayName("a run that has not started is the one state START is offered from")
    fun runTracksWhetherItStarted() {
        assertFalse(GuidanceRun().isStarted)
        assertTrue(GuidanceRun().start(T0).isStarted)
    }

    @Test
    @DisplayName("+ 5 MIN is offered on one-work-segment rides with a length, and nowhere else")
    fun extendIsOneWorkSegmentOnly() {
        assertTrue(statusAt(guidanceTimeline(steady, null), 0).canExtend)
        assertTrue(statusAt(guidanceTimeline(null, 2_400), 0).canExtend)
        assertFalse(statusAt(guidanceTimeline(intervals, null), 0).canExtend)
        assertFalse(statusAt(GuidanceTimeline.OPEN, 0).canExtend)
    }

    @Test
    @DisplayName("warmup and cooldown around one work segment stay extendable — the shape the size rule refused")
    fun extendAllowsPreparationAroundTheWork() {
        assertTrue(statusAt(guidanceTimeline(wrappedSteady, null), 0).canExtend)

        // And a VO2 session is still refused, roles or no roles: six work
        // segments have no single one that "five more minutes" could mean.
        val rolledIntervals = listOf(
            segment(300, max = 132, label = "warmup", role = "warmup"),
            segment(180, min = 158, max = 172, label = "hard"),
            segment(120, max = 144, label = "easy"),
            segment(180, min = 158, max = 172, label = "hard"),
            segment(300, max = 130, label = "cooldown", role = "cooldown"),
        )
        assertFalse(statusAt(guidanceTimeline(rolledIntervals, null), 0).canExtend)
    }

    @Test
    @DisplayName("a timeline that is all preparation has no work segment to lengthen")
    fun extendRefusesATimelineWithNoWork() {
        val noWork = listOf(
            segment(300, max = 118, role = "warmup"),
            segment(300, max = 118, role = "cooldown"),
        )

        assertFalse(statusAt(guidanceTimeline(noWork, null), 0).canExtend)
    }

    @Test
    @DisplayName("a negative extension cannot shorten a timeline")
    fun negativeExtensionIsIgnored() {
        val status = statusAt(guidanceTimeline(steady, null), elapsedMs = 0, extensionSec = -600)

        assertEquals(0, status.extensionSec)
        assertEquals(1_800, status.totalSec)
    }

    // ---- roles ----------------------------------------------------------------------------

    @Test
    @DisplayName("roles resolve onto the timeline, and an absent one is work")
    fun rolesResolve() {
        val segments = guidanceTimeline(wrappedSteady, null).segments

        assertEquals(
            listOf(SegmentRole.WARMUP, SegmentRole.WORK, SegmentRole.COOLDOWN),
            segments.map { it.role },
        )
        assertEquals(listOf(false, true, false), segments.map { it.isWork })
    }

    @Test
    @DisplayName("a role the server would have rejected degrades to work rather than closing the guide")
    fun unknownRolesDegradeToWork() {
        // The server holds the closed set, so anything else is a hand-edited
        // row — the zero-bound rule's stance, applied to a string.
        assertEquals(SegmentRole.WORK, SegmentRole.fromWire("sprint"))
        assertEquals(SegmentRole.WORK, SegmentRole.fromWire(""))
        assertEquals(SegmentRole.WORK, SegmentRole.fromWire(null))
        // A transcription of the right answer is the right answer.
        assertEquals(SegmentRole.WARMUP, SegmentRole.fromWire(" Warmup "))
        assertEquals(SegmentRole.COOLDOWN, SegmentRole.fromWire("COOLDOWN"))
    }

    @Test
    @DisplayName("a role-less timeline behaves exactly as it did before roles existed")
    fun roleLessTimelinesAreUnchanged() {
        // The compatibility promise, stated as a test: absence is work, so one
        // segment is extendable and several are not — which is the shipped rule,
        // and both readings of it agree on every role-less plan.
        assertTrue(statusAt(guidanceTimeline(steady, null), 0).canExtend)
        assertFalse(statusAt(guidanceTimeline(intervals, null), 0).canExtend)

        val single = statusAt(guidanceTimeline(steady, null), 0, extensionSec = 300)
        assertEquals(2_100, single.segments.single().endSec)

        val many = statusAt(guidanceTimeline(intervals, null), 0, extensionSec = 300)
        assertEquals(listOf(0, 300, 480, 600, 780, 900, 1_080), many.segments.map { it.startSec })
        assertEquals(1_500, many.segments.last().endSec)
    }

    // ---- shape predicates ---------------------------------------------------------------

    @Test
    @DisplayName("one segment is steady, none is segmentless, several is neither")
    fun shapePredicates() {
        val steadyStatus = statusAt(guidanceTimeline(steady, null), 0)
        assertTrue(steadyStatus.isSteady)
        assertFalse(steadyStatus.isSegmentless)

        val bare = statusAt(guidanceTimeline(null, 2_400), 0)
        assertTrue(bare.isSegmentless)
        assertFalse(bare.isSteady)

        assertFalse(statusAt(guidanceTimeline(intervals, null), 0).isSteady)
    }

    // ---- the out-of-band predicate --------------------------------------------------------

    @Test
    @DisplayName("a range flags either side, and both bounds are inside the band")
    fun rangeOutOfBand() {
        val range = guidanceTimeline(listOf(segment(60, min = 158, max = 172)), null).segments.single()

        assertTrue(range.outOfBand(157))
        assertFalse(range.outOfBand(158))
        assertFalse(range.outOfBand(165))
        assertFalse(range.outOfBand(172))
        assertTrue(range.outOfBand(173))
    }

    @Test
    @DisplayName("a floor flags below only, a ceiling above only")
    fun oneSidedOutOfBand() {
        val floor = guidanceTimeline(listOf(segment(60, min = 140)), null).segments.single()
        assertTrue(floor.outOfBand(139))
        assertFalse(floor.outOfBand(140))
        assertFalse(floor.outOfBand(199))

        val ceiling = guidanceTimeline(listOf(segment(60, max = 144)), null).segments.single()
        assertFalse(ceiling.outOfBand(90))
        assertFalse(ceiling.outOfBand(144))
        assertTrue(ceiling.outOfBand(145))
    }

    @Test
    @DisplayName("a segment with no bounds draws no band, and nothing can be outside it")
    fun boundlessSegmentHasNoBand() {
        val boundless = guidanceTimeline(listOf(segment(60)), null).segments.single()

        assertFalse(boundless.hasBand)
        assertFalse(boundless.outOfBand(30))
        assertFalse(boundless.outOfBand(220))
    }

    // ---- the timeline as the record keeps it ------------------------------------------
    //
    // What a `start` event carries: the segments as guided, in the coach wire's
    // own segment shape, so a consumer parses them exactly as it parses a plan's.
    // Boundaries are derived from these durations, so a duration that came out
    // here wrong would misplace every one of them.

    @Test
    @DisplayName("the recorded timeline is the wire's segment shape, absent bounds omitted")
    fun guidedSegmentsSerializeToTheWireShape() {
        val json = guidanceTimeline(
            listOf(segment(300, min = 118, max = 132, label = "warmup"), segment(120, max = 144)),
            null,
        ).guidedSegmentsJson()

        assertEquals(
            """[{"duration_sec":300,"hr_min":118,"hr_max":132,"label":"warmup"},""" +
                """{"duration_sec":120,"hr_max":144}]""",
            json,
        )
    }

    @Test
    @DisplayName("roles are recorded, and a work segment records as the absence that means work")
    fun guidedSegmentsCarryRoles() {
        val json = guidanceTimeline(wrappedSteady, null).guidedSegmentsJson()

        assertEquals(
            """[{"duration_sec":300,"hr_max":118,"label":"ease in","role":"warmup"},""" +
                """{"duration_sec":1800,"hr_min":122,"hr_max":138,"label":"zone 2"},""" +
                """{"duration_sec":300,"hr_max":118,"label":"spin down","role":"cooldown"}]""",
            json,
        )
    }

    @Test
    @DisplayName("a role-less ride records exactly what it recorded before the field existed")
    fun guidedSegmentsOfARoleLessRideAreUnchanged() {
        assertEquals(
            """[{"duration_sec":1800,"hr_min":122,"hr_max":138,"label":"zone 2"}]""",
            guidanceTimeline(steady, null).guidedSegmentsJson(),
        )
    }

    @Test
    @DisplayName("a ride with no authored timeline records an empty list, not a missing one")
    fun segmentlessTimelineSerializesEmpty() {
        assertEquals("[]", guidanceTimeline(emptyList(), 1_800).guidedSegmentsJson())
        assertEquals("[]", GuidanceTimeline.OPEN.guidedSegmentsJson())
    }

    @Test
    @DisplayName("the durations recorded are the resolved ones, not the ones as authored")
    fun guidedSegmentsRecordWhatWasGuided() {
        // A hand-edited negative duration is clamped on the way in, and the
        // record says what the rider was actually guided to rather than what the
        // row claimed.
        val json = guidanceTimeline(listOf(segment(-90, min = 120), segment(600, min = 122)), null)
            .guidedSegmentsJson()

        assertEquals("""[{"duration_sec":0,"hr_min":120},{"duration_sec":600,"hr_min":122}]""", json)
    }

    @Test
    @DisplayName("the extension is not folded in — it is its own timestamped row")
    fun guidedSegmentsExcludeTheExtension() {
        val timeline = guidanceTimeline(steady, null)
        val extended = guidanceStatus(timeline, GuidanceRun(startedAtMs = T0, extensionSec = 600), T0)

        // The live timeline has grown by ten minutes; what a start event records
        // has not, because two extend rows already say so with their own instants.
        assertEquals(2_400, extended.segments.single().durationSec)
        assertEquals(
            """[{"duration_sec":1800,"hr_min":122,"hr_max":138,"label":"zone 2"}]""",
            timeline.guidedSegmentsJson(),
        )
    }
}

/** A far-future base, so no value here can be mistaken for a real capture. */
internal const val T0 = 1_893_456_000_000L
