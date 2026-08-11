package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The rules tying a heart-rate capture to a workout.
 *
 * Both of them fail silently when they are wrong — a sheet that never appears
 * costs the recording, and an End Workout that stops the wrong capture closes a
 * session the user is still wearing — so the whole matrix is pinned here rather
 * than inferred from the ViewModel's behaviour.
 */
class WorkoutCapturePolicyTest {

    @Test
    @DisplayName("a known strap, nothing recording, permission in hand: ask")
    fun asksWhenThereIsSomethingToOffer() {
        assertEquals(
            StartCaptureAction.PROMPT,
            WorkoutCapturePolicy.startAction(
                hasKnownStrap = true,
                captureRunning = false,
                connectPermitted = true,
            ),
        )
    }

    @Test
    @DisplayName("no strap has ever been paired: Start Workout says nothing about straps")
    fun silentWithNoStrap() {
        assertEquals(
            StartCaptureAction.NONE,
            WorkoutCapturePolicy.startAction(
                hasKnownStrap = false,
                captureRunning = false,
                connectPermitted = true,
            ),
        )
    }

    @Test
    @DisplayName("without BLUETOOTH_CONNECT the sheet is suppressed rather than offering a dead button")
    fun silentWithoutPermission() {
        assertEquals(
            StartCaptureAction.NONE,
            WorkoutCapturePolicy.startAction(
                hasKnownStrap = true,
                captureRunning = false,
                connectPermitted = false,
            ),
        )
    }

    @Test
    @DisplayName("a running capture is anchored, never asked about — and anchoring wins over everything")
    fun runningCaptureAnchors() {
        // Including the states where the sheet could not have been shown anyway:
        // what is missing is the session's link to this workout, not a connection.
        for (known in listOf(true, false)) {
            for (permitted in listOf(true, false)) {
                assertEquals(
                    StartCaptureAction.ANCHOR,
                    WorkoutCapturePolicy.startAction(
                        hasKnownStrap = known,
                        captureRunning = true,
                        connectPermitted = permitted,
                    ),
                    "known=$known permitted=$permitted",
                )
            }
        }
    }

    @Test
    @DisplayName("End Workout stops the capture it anchored")
    fun stopsItsOwnCapture() {
        val anchor = WorkoutAnchor("2026-08-10", sessionId = 7)

        assertTrue(WorkoutCapturePolicy.stopsCapture(anchor, anchor, captureRunning = true))
    }

    @Test
    @DisplayName("a capture nobody anchored survives End Workout")
    fun leavesAnUnanchoredCapture() {
        assertFalse(
            WorkoutCapturePolicy.stopsCapture(
                anchor = null,
                ending = WorkoutAnchor("2026-08-10", sessionId = 7),
                captureRunning = true,
            ),
        )
    }

    @Test
    @DisplayName("another workout's capture is not this one's to stop")
    fun leavesAnotherWorkoutsCapture() {
        val recording = WorkoutAnchor("2026-08-09", sessionId = 6)
        val ending = WorkoutAnchor("2026-08-10", sessionId = 7)

        assertFalse(WorkoutCapturePolicy.stopsCapture(recording, ending, captureRunning = true))
        // Same day, different session: an extra session logged the same evening.
        assertFalse(
            WorkoutCapturePolicy.stopsCapture(
                anchor = WorkoutAnchor("2026-08-10", sessionId = 6),
                ending = ending,
                captureRunning = true,
            ),
        )
    }

    @Test
    @DisplayName("nothing running, nothing to stop — the anchor alone is not enough")
    fun nothingToStop() {
        val anchor = WorkoutAnchor("2026-08-10", sessionId = 7)

        assertFalse(WorkoutCapturePolicy.stopsCapture(anchor, anchor, captureRunning = false))
    }

    // ---- reading the anchor off the published state ----------------------------

    @Test
    @DisplayName("the capture state carries its own anchor, hook session included")
    fun anchorComesOffTheState() {
        val state = HrCaptureState(isRunning = true, workoutDate = "2026-08-10", workoutSessionId = 7)

        assertEquals(WorkoutAnchor("2026-08-10", sessionId = 7), state.workoutAnchor())
    }

    @Test
    @DisplayName("a capture started from the strap settings has no anchor at all")
    fun unanchoredStateHasNoAnchor() {
        assertNull(HrCaptureState(isRunning = true).workoutAnchor())
    }

    @Test
    @DisplayName("the date is the anchor's identity — a hook id without one anchors nothing")
    fun dateIsTheIdentity() {
        // Both columns are written together, so this state cannot arise; the
        // mapping refuses to invent an anchor from half of one regardless.
        assertNull(HrCaptureState(isRunning = true, workoutSessionId = 7).workoutAnchor())
    }

    // ---- what the sheet says about the attachment --------------------------------

    @Test
    @DisplayName("the sheet distinguishes this workout, another one, and none")
    fun linkNamesTheAttachment() {
        val onScreen = WorkoutAnchor("2026-08-10", sessionId = 7)

        assertEquals(CaptureLink.THIS_WORKOUT, WorkoutCapturePolicy.linkFor(onScreen, onScreen))
        assertEquals(CaptureLink.UNANCHORED, WorkoutCapturePolicy.linkFor(null, onScreen))
        assertEquals(
            CaptureLink.OTHER_WORKOUT,
            WorkoutCapturePolicy.linkFor(WorkoutAnchor("2026-08-09", sessionId = 6), onScreen),
        )
        // Same day, different session: an extra session logged the same evening.
        assertEquals(
            CaptureLink.OTHER_WORKOUT,
            WorkoutCapturePolicy.linkFor(WorkoutAnchor("2026-08-10", sessionId = 6), onScreen),
        )
    }

    @Test
    @DisplayName("every link has its own sentence, and none of them tells the user to do the impossible")
    fun everyLinkReads() {
        val texts = CaptureLink.entries.map(CaptureCopy::linkText)

        assertEquals(texts.size, texts.distinct().size)
        assertTrue(texts.none { it.isBlank() })
    }
}
