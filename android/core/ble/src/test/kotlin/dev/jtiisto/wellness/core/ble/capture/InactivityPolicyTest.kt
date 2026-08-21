package dev.jtiisto.wellness.core.ble.capture

import dev.jtiisto.wellness.core.ble.model.ConnectionState
import dev.jtiisto.wellness.core.ble.quality.SignalQuality
import dev.jtiisto.wellness.core.ble.quality.SignalQualityLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/** A far-future capture start, so it can never be read as a real session's. */
private const val STARTED_AT = 1_893_456_000_000L

/**
 * When a capture with nothing coming in gives up on itself — the net under a
 * strap that goes flat or is left in a bag, so a dead session cannot hold a
 * wake lock and a foreground notification indefinitely.
 */
class InactivityPolicyTest {

    @Test
    @DisplayName("a dropped or backing-off link starts the countdown")
    fun failureStatesArm() {
        // RECONNECTING as well as DISCONNECTED: the backoff *is* the failure,
        // and waiting for its fifteen attempts before starting the clock would
        // double the effective timeout.
        assertEquals(InactivityAction.ARM, InactivityPolicy.actionFor(ConnectionState.DISCONNECTED))
        assertEquals(InactivityAction.ARM, InactivityPolicy.actionFor(ConnectionState.RECONNECTING))
    }

    @Test
    @DisplayName("a live link stops it")
    fun connectedCancels() {
        assertEquals(InactivityAction.CANCEL, InactivityPolicy.actionFor(ConnectionState.CONNECTED))
    }

    @Test
    @DisplayName("the states on the way to a connect leave a running countdown alone")
    fun transientStatesDoNothing() {
        // Cancelling here would let a strap that connects and immediately drops
        // loop forever with the clock reset on every attempt.
        assertEquals(InactivityAction.LEAVE, InactivityPolicy.actionFor(ConnectionState.SCANNING))
        assertEquals(InactivityAction.LEAVE, InactivityPolicy.actionFor(ConnectionState.CONNECTING))
    }

    @Test
    @DisplayName("the timeout is the five minutes the spec names")
    fun timeoutIsFiveMinutes() {
        assertEquals(5.minutes, InactivityPolicy.TIMEOUT)
    }

    @Test
    @DisplayName("a stream declared dead lands somewhere that starts the countdown")
    fun aDeadStreamReachesTheStop() {
        // The half of the liveness fix this policy owns. A silent strap used to
        // hold the link at CONNECTED, which CANCELS — so the wake lock and the
        // notification were held indefinitely with nothing being recorded. The
        // sample watchdog now drives the same teardown a real drop does, and
        // both states it can land in have to arm: DISCONNECTED while the
        // reconnect is being scheduled, RECONNECTING once it is, and
        // DISCONNECTED again for good if the retry budget is spent.
        assertEquals(InactivityAction.ARM, InactivityPolicy.actionFor(ConnectionState.DISCONNECTED))
        assertEquals(InactivityAction.ARM, InactivityPolicy.actionFor(ConnectionState.RECONNECTING))
    }
}

/**
 * The state the service publishes and the UI collects — asserted here because
 * the chip's "hidden when idle" rule reads straight off the defaults.
 */
class HrCaptureStateTest {

    @Test
    @DisplayName("the default state is idle, which is what hides the chip")
    fun defaultIsIdle() {
        val idle = HrCaptureState()

        assertFalse(idle.isRunning)
        assertEquals(ConnectionState.DISCONNECTED, idle.connectionState)
        assertNull(idle.bpm)
        assertNull(idle.sessionId)
        // Null exactly when the session id is: nothing is recording, so there is
        // no instant recording began.
        assertNull(idle.startedAtMs)
        assertNull(idle.deviceAddress)
        assertNull(idle.deviceName)
        assertNull(idle.detail)
        // Teardown resets the state to exactly this value, so "the anchor is
        // cleared when a capture ends" is this assertion.
        assertNull(idle.workoutDate)
        assertNull(idle.workoutSessionId)
        assertEquals(SignalQualityLevel.MEASURING, idle.signalQuality.level)
        // Idle is not stale: nothing is claiming to deliver, so there is nothing
        // to disbelieve. Teardown resets to exactly this, so a capture cannot
        // inherit the previous one's dead stream.
        assertFalse(idle.isStreamStale)
    }

    @Test
    @DisplayName("a running session publishes its id, its start and its workout anchor together")
    fun captureSessionIsFolded() {
        val running = HrCaptureState(isRunning = true)

        val anchored = running.withCaptureSession(
            CaptureSession("session-1", STARTED_AT, workoutDate = "2026-08-10", workoutSessionId = 7L),
        )

        assertEquals("session-1", anchored.sessionId)
        assertEquals(STARTED_AT, anchored.startedAtMs)
        assertEquals("2026-08-10", anchored.workoutDate)
        assertEquals(7L, anchored.workoutSessionId)
    }

    @Test
    @DisplayName("a capture started outside a workout publishes no anchor")
    fun unanchoredCaptureLeavesTheFieldsNull() {
        val state = HrCaptureState(isRunning = true)
            .withCaptureSession(CaptureSession("session-1", STARTED_AT))

        assertEquals("session-1", state.sessionId)
        // The start is not part of the anchor and does not depend on one: a
        // capture begun from the strap settings has no workout and still began.
        assertEquals(STARTED_AT, state.startedAtMs)
        assertNull(state.workoutDate)
        assertNull(state.workoutSessionId)
    }

    @Test
    @DisplayName("re-anchoring replaces the whole anchor, both halves at once")
    fun anchorIsReplacedWholesale() {
        val anchored = HrCaptureState(isRunning = true).withCaptureSession(
            CaptureSession("s", STARTED_AT, workoutDate = "2026-08-09", workoutSessionId = 7L),
        )

        val moved = anchored.withCaptureSession(
            CaptureSession("s", STARTED_AT, workoutDate = "2026-08-10"),
        )

        assertEquals("2026-08-10", moved.workoutDate)
        // Not left over from the previous anchor: the two halves describe one
        // workout and a mixed pair would name a session that never existed.
        assertNull(moved.workoutSessionId)
        // The start belongs to the session, not to the anchor, so moving a
        // capture to another workout must not restart its clock.
        assertEquals(STARTED_AT, moved.startedAtMs)
    }

    @Test
    @DisplayName("no session leaves the last published values alone")
    fun nullSessionChangesNothing() {
        val anchored = HrCaptureState(isRunning = true).withCaptureSession(
            CaptureSession("s", STARTED_AT, workoutDate = "2026-08-10", workoutSessionId = 7L),
        )

        // Clearing here would open a window where the state still says running
        // but has forgotten its workout — exactly the reading that makes End
        // Workout skip a capture it ought to stop. Teardown does the clearing.
        assertEquals(anchored, anchored.withCaptureSession(null))
    }

    @Test
    @DisplayName("a running capture is the same value with the fields the UI reads filled in")
    fun runningStateCarriesTheDetails() {
        val running = HrCaptureState().copy(
            isRunning = true,
            connectionState = ConnectionState.CONNECTED,
            bpm = 142,
            deviceAddress = "AA:BB",
            deviceName = "HRM-Pro",
            sessionId = "session-1",
            // The documented invariant: a session that exists began at some
            // instant, so startedAtMs is null exactly when sessionId is. A
            // fixture modelling "running" must model that too.
            startedAtMs = 1_893_456_000_000L,
            signalQuality = SignalQuality(SignalQualityLevel.GOOD, rrCoveragePercent = 98),
            detail = null,
        )

        assertTrue(running.isRunning)
        assertEquals(142, running.bpm)
        assertEquals("session-1", running.sessionId)
        assertEquals(SignalQualityLevel.GOOD, running.signalQuality.level)
        assertEquals(running, running.copy())
        assertTrue(running != running.copy(bpm = 143))
        assertTrue(running.toString().contains("HRM-Pro"))
    }
}
