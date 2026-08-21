package dev.jtiisto.wellness.feature.coach.guidance

import dev.jtiisto.wellness.core.data.coach.PlanSegmentDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The guide's lifecycle, as values.
 *
 * The three promises the spec makes about a run — a dismiss does not stop the
 * clock, reopening restores the position, and START after a finished run starts
 * a fresh one — are all statements about what [GuidanceRuns] holds and when.
 * They are asserted here, off any composition, because the overlay that
 * *shows* them has no test rig and the rules must not live where only a device
 * can check them. `CoachViewModelTest` then pins the same rules through the
 * actual open/dismiss/start calls.
 */
class GuidanceOverlayStateTest {

    private val zone2 = GuidanceKey(date = "2030-01-06", exerciseId = "ex_cardio")
    private val intervals = GuidanceKey(date = "2030-01-06", exerciseId = "ex_vo2")

    // ---- the run holder ---------------------------------------------------------

    @Test
    @DisplayName("a key that was never started reads as an un-anchored run")
    fun unknownKeyIsFresh() {
        val runs = GuidanceRuns()

        assertEquals(GuidanceRun(), runs[zone2])
        assertNull(runs[zone2].startedAtMs)
        assertEquals(GuidancePhase.READY, guidanceStatus(GuidanceTimeline.OPEN, runs[zone2], 0L).phase)
    }

    @Test
    @DisplayName("START anchors the key's run at the instant it is given")
    fun startAnchors() {
        val runs = GuidanceRuns().started(zone2, nowMs = 1_700_000_000_000L)

        assertEquals(1_700_000_000_000L, runs[zone2].startedAtMs)
    }

    @Test
    @DisplayName("the holder is a value: starting a run leaves the old holder alone")
    fun startedIsPure() {
        val before = GuidanceRuns()

        val after = before.started(zone2, nowMs = 1_000L)

        assertNull(before[zone2].startedAtMs)
        assertEquals(1_000L, after[zone2].startedAtMs)
        assertNotEquals(before, after)
    }

    @Test
    @DisplayName("two exercises guided in one session keep their own clocks")
    fun keysAreIsolated() {
        val runs = GuidanceRuns()
            .started(zone2, nowMs = 1_000L)
            .started(intervals, nowMs = 9_000L)

        assertEquals(1_000L, runs[zone2].startedAtMs)
        assertEquals(9_000L, runs[intervals].startedAtMs)
    }

    @Test
    @DisplayName("the same exercise on another day is another run")
    fun dateIsHalfTheIdentity() {
        val tuesday = GuidanceKey(date = "2030-01-06", exerciseId = "ex_cardio")
        val wednesday = GuidanceKey(date = "2030-01-07", exerciseId = "ex_cardio")

        val runs = GuidanceRuns().started(tuesday, nowMs = 1_000L)

        assertEquals(1_000L, runs[tuesday].startedAtMs)
        assertNull(runs[wednesday].startedAtMs)
    }

    @Test
    @DisplayName("a second START re-anchors and drops the finished run's extension")
    fun restartDiscardsTheOldRun() {
        val first = GuidanceRuns().started(zone2, nowMs = 1_000L)
        // Two taps of + 5 MIN on the ride that just finished.
        val extended = GuidanceRuns(mapOf(zone2 to first[zone2].extend().extend()))

        val second = extended.started(zone2, nowMs = 5_000_000L)

        assertEquals(5_000_000L, second[zone2].startedAtMs)
        assertEquals(0, second[zone2].extensionSec)
    }

    @Test
    @DisplayName("+ 5 MIN appends to one key's run, cumulatively, and leaves the holder it came from alone")
    fun extensionIsCumulativeAndIsolated() {
        val before = GuidanceRuns().started(zone2, nowMs = 1_000L)

        val after = before.extended(zone2).extended(zone2)

        assertEquals(2 * EXTENSION_STEP_SEC, after[zone2].extensionSec)
        assertEquals(0, before[zone2].extensionSec)
        assertEquals(0, after[intervals].extensionSec)
        // The anchor is untouched: appending time moves the end of the timeline,
        // never its start.
        assertEquals(1_000L, after[zone2].startedAtMs)
    }

    // ---- the footer's two controls ------------------------------------------------

    @Test
    @DisplayName("START is offered before a run and after one, and says which it is")
    fun startButtonLabels() {
        assertEquals("Start", startButtonLabel(GuidancePhase.READY))
        assertEquals("Start again", startButtonLabel(GuidancePhase.DONE))
    }

    @Test
    @DisplayName("a run under way offers no START — nothing may silently discard it")
    fun noRestartMidRun() {
        assertNull(startButtonLabel(GuidancePhase.RUNNING))
    }

    // ---- who may extend --------------------------------------------------------------

    /** A steady ride: one continuous band, the shape the control exists for. */
    private fun steady(started: Boolean, elapsedMs: Long = 0L, extensionSec: Int = 0) =
        guidanceStatus(
            timeline = guidanceTimeline(
                segments = listOf(PlanSegmentDto(durationSec = 1_800, hrMin = 118, hrMax = 132)),
                plannedDurationSec = null,
            ),
            run = GuidanceRun(startedAtMs = if (started) T0 else null, extensionSec = extensionSec),
            nowMs = T0 + elapsedMs,
        )

    @Test
    @DisplayName("a running steady ride offers + 5 MIN")
    fun steadyRunOffersTheExtension() {
        assertTrue(canOfferExtension(steady(started = true, elapsedMs = 600_000)))
    }

    @Test
    @DisplayName("a finished steady ride still offers it — that is how a ride carries on past its plan")
    fun doneStillOffersTheExtension() {
        val done = steady(started = true, elapsedMs = 1_800_000)

        assertEquals(GuidancePhase.DONE, done.phase)
        assertTrue(canOfferExtension(done))
    }

    @Test
    @DisplayName("READY offers nothing — START would throw the added minutes away")
    fun readyOffersNoExtension() {
        val ready = steady(started = false)

        assertEquals(GuidancePhase.READY, ready.phase)
        assertFalse(canOfferExtension(ready))
        // And the discard is real, which is why: a run started after being
        // extended keeps no trace of the extension.
        assertEquals(0, GuidanceRun(extensionSec = EXTENSION_STEP_SEC).start(T0).extensionSec)
    }

    @Test
    @DisplayName("a structured session never offers it, however far into the ride")
    fun structuredSessionOffersNoExtension() {
        val status = guidanceStatus(
            timeline = guidanceTimeline(
                segments = listOf(
                    PlanSegmentDto(durationSec = 300, hrMin = 118, hrMax = 132, label = "warmup"),
                    PlanSegmentDto(durationSec = 180, hrMin = 155, hrMax = 172, label = "hard"),
                ),
                plannedDurationSec = null,
            ),
            run = GuidanceRun(startedAtMs = T0),
            nowMs = T0 + 60_000,
        )

        assertFalse(canOfferExtension(status))
    }

    @Test
    @DisplayName("a segmentless ride with a planned length offers it; an open-ended one does not")
    fun segmentlessNeedsALengthToAppendTo() {
        val timed = guidanceStatus(
            timeline = guidanceTimeline(segments = null, plannedDurationSec = 1_800),
            run = GuidanceRun(startedAtMs = T0),
            nowMs = T0 + 60_000,
        )
        val openEnded = guidanceStatus(GuidanceTimeline.OPEN, GuidanceRun(startedAtMs = T0), T0 + 60_000)

        assertTrue(canOfferExtension(timed))
        assertFalse(canOfferExtension(openEnded))
    }

    // ---- the shell's words ---------------------------------------------------------

    @Test
    @DisplayName("the affordance names the exercise it opens")
    fun affordanceDescriptionNamesTheExercise() {
        assertEquals("Open the guide for Tempo Ride", GuidanceOverlayCopy.openDescription("Tempo Ride"))
    }
}
