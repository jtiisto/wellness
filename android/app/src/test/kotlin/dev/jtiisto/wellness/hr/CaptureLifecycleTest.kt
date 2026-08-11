package dev.jtiisto.wellness.hr

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.data.hr.CaptureStopResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The capture service's start/stop bookkeeping.
 *
 * Two production failures are pinned here, and neither could be caught in a test
 * of the service itself — it is device-only glue, excluded from the coverage
 * gate, so a regression in it would hide behind a green suite.
 *
 * The first is a leaked teardown: an exception on the way out left the stop claim
 * standing, and every later stop was then refused as a duplicate while a wake
 * lock and a foreground notification stayed up. The second is a stop arriving
 * while the startup was still suspended, after which the startup resumed and
 * opened a GATT link nothing was tracking.
 */
class CaptureLifecycleTest {

    @Test
    @DisplayName("the first start is claimed and the second is refused")
    fun onlyOneStartAtATime() {
        val lifecycle = CaptureLifecycle()

        assertTrue(lifecycle.claimStart())
        // A repeated intent is a duplicate delivery, not a second capture.
        assertFalse(lifecycle.claimStart())
    }

    @Test
    @DisplayName("a startup may continue while nothing has asked it to stop")
    fun startupContinuesWhileStarting() {
        val lifecycle = CaptureLifecycle()
        lifecycle.claimStart()

        assertTrue(lifecycle.mayContinueStartup())
    }

    @Test
    @DisplayName("a stop request aborts a startup that has not connected yet")
    fun stopAbortsAnInFlightStartup() {
        val lifecycle = CaptureLifecycle()
        lifecycle.claimStart()

        lifecycle.claimStop()

        // The startup is suspended somewhere in its session write. When it comes
        // back it must not go on to open a link the teardown has stopped looking
        // for — cancellation alone cannot promise that, because the run-up to
        // connectGatt has no suspension point to observe it at.
        assertFalse(lifecycle.mayContinueStartup())
    }

    @Test
    @DisplayName("nothing may continue a startup that was never claimed")
    fun startupCannotContinueWithoutAClaim() {
        assertFalse(CaptureLifecycle().mayContinueStartup())
    }

    @Test
    @DisplayName("the first stop is claimed and a concurrent second is refused")
    fun onlyOneTeardownAtATime() {
        val lifecycle = CaptureLifecycle()
        lifecycle.claimStart()

        assertTrue(lifecycle.claimStop())
        // Two teardowns at once race each other through disconnect, session
        // close and stopSelf.
        assertFalse(lifecycle.claimStop())
    }

    @Test
    @DisplayName("a second stop stays blocked for as long as the first teardown is unreleased")
    fun secondStopBlockedUntilReleased() {
        val lifecycle = CaptureLifecycle()
        lifecycle.claimStart()
        assertTrue(lifecycle.claimStop())

        // Mid-teardown: the disconnect is in flight and released() has not run.
        // Every stop that arrives in that window is a duplicate, not a retry.
        assertFalse(lifecycle.claimStop())
        assertFalse(lifecycle.claimStop())

        lifecycle.released()
        // And the moment teardown finishes, a genuine retry is accepted again.
        assertTrue(lifecycle.claimStop())
    }

    @Test
    @DisplayName("a stop is claimable even when no start ever was")
    fun stopWithoutAStart() {
        // An ACTION_STOP for a capture that never got going still has to reach
        // the teardown, or the service stays up with nothing running.
        assertTrue(CaptureLifecycle().claimStop())
    }

    @Test
    @DisplayName("releasing after teardown lets the next capture start — the leak regression")
    fun releaseAllowsTheNextCapture() {
        val lifecycle = CaptureLifecycle()
        lifecycle.claimStart()
        lifecycle.claimStop()

        lifecycle.released()

        // Reached from a finally. Left unreleased because a disconnect threw,
        // this is the state in which the service could never be stopped or
        // started again.
        assertTrue(lifecycle.claimStart())
        assertTrue(lifecycle.mayContinueStartup())
        assertTrue(lifecycle.claimStop())
    }

    @Test
    @DisplayName("a fresh start clears a stop left over from the capture before it")
    fun startClearsAStaleStop() {
        val lifecycle = CaptureLifecycle()
        lifecycle.claimStop()

        assertTrue(lifecycle.claimStart())

        assertTrue(lifecycle.mayContinueStartup())
    }

    @Test
    @DisplayName("the buffer timer stops only once its last batch is down")
    fun bufferTimerStopsOnlyWhenTheDataIsSafe() {
        assertTrue(bufferTimerStops(CaptureStopResult.CLOSED))
        assertTrue(bufferTimerStops(CaptureStopResult.SESSION_GONE))
        // The rows are still in memory and the buffer's own retry is the only
        // thing that will ever get them down — along with the deferred close
        // that is waiting on them.
        assertFalse(bufferTimerStops(CaptureStopResult.LEFT_OPEN))
        // This service never had a capture, so the timer belongs to someone else.
        assertFalse(bufferTimerStops(CaptureStopResult.NOT_RUNNING))
    }

    @Test
    @DisplayName("every stop outcome has an answer")
    fun everyOutcomeIsDecided() {
        val decided = CaptureStopResult.entries.map(::bufferTimerStops)

        assertEquals(CaptureStopResult.entries.size, decided.size)
    }

    // ---- the notification trails the state -------------------------------

    @Test
    @DisplayName("a publisher whose value is still live paints the notification")
    fun livePublisherPaints() {
        val published = HrCaptureState(isRunning = true, bpm = 142)

        assertTrue(notificationFollows(committed = true, published = published, live = published))
    }

    @Test
    @DisplayName("a write that never landed paints nothing")
    fun rejectedWritePaintsNothing() {
        // The capture was torn down, so the state rejected the update. Painting
        // what it would have written resurrects a finished session on screen.
        val published = HrCaptureState(isRunning = true, bpm = 142)

        assertFalse(notificationFollows(committed = false, published = published, live = HrCaptureState()))
    }

    @Test
    @DisplayName("a teardown between the write and the paint wins — no ghost notification")
    fun teardownBeatsThePaint() {
        val published = HrCaptureState(isRunning = true, bpm = 142)

        // The write landed, then teardown reset the state and removed the
        // notification. Painting here would put it back, for a capture that has
        // stopped, with nothing left running to ever clear it again.
        assertFalse(notificationFollows(committed = true, published = published, live = HrCaptureState()))
    }

    @Test
    @DisplayName("a superseded publisher defers to the newer one")
    fun supersededPublisherDefers() {
        val published = HrCaptureState(isRunning = true, bpm = 142)
        val newer = published.copy(bpm = 143)

        // Correct rather than merely safe: the newer value is the one on screen,
        // and it does its own painting.
        assertFalse(notificationFollows(committed = true, published = published, live = newer))
    }

    @Test
    @DisplayName("a paint that outlived its capture is taken back down")
    fun paintIsVerifiedAfterTheFact() {
        // Teardown landed between the pre-check and notify(), so the call put
        // back a notification nothing is left running to clear.
        assertTrue(notificationOutlivedCapture(HrCaptureState()))
    }

    @Test
    @DisplayName("a paint superseded by a newer one is left alone, not cancelled")
    fun supersededPaintIsNotCancelled() {
        // The question after painting is "is anything capturing", not "is my
        // value still live" — cancelling here would remove a live notification.
        assertFalse(notificationOutlivedCapture(HrCaptureState(isRunning = true, bpm = 143)))
    }

    @Test
    @DisplayName("identity, not equality — an equal-but-later value is still a different write")
    fun identityIsTheTest() {
        val published = HrCaptureState(isRunning = true, bpm = 142)
        val equalButSeparate = HrCaptureState(isRunning = true, bpm = 142)

        assertFalse(notificationFollows(committed = true, published = published, live = equalButSeparate))
    }
}
