package dev.jtiisto.wellness.core.ble.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * When a strap that is still connected has stopped being a heart-rate monitor.
 *
 * The failure this guards is silent by construction — the link stays CONNECTED,
 * the platform reports nothing, and the last beat received sits on screen as the
 * current heart rate. The watchdog that consumes this policy cannot run off a
 * device, so everything about it that could be *wrong* is here: the two numbers,
 * which clock applies, and the arithmetic that decides one has elapsed.
 */
class StreamLivenessPolicyTest {

    @Test
    @DisplayName("an unproven link gets ten seconds, a proven one fifteen")
    fun theTwoClocks() {
        // Longer for a link that has been delivering: it has earned more
        // patience than one that never started, and tearing a healthy link down
        // over a radio hiccup costs real beats.
        assertEquals(
            StreamLivenessPolicy.FIRST_SAMPLE_TIMEOUT_MS,
            StreamLivenessPolicy.timeoutMs(hasDelivered = false),
        )
        assertEquals(
            StreamLivenessPolicy.STALE_AFTER_MS,
            StreamLivenessPolicy.timeoutMs(hasDelivered = true),
        )
        assertEquals(10_000L, StreamLivenessPolicy.FIRST_SAMPLE_TIMEOUT_MS)
        assertEquals(15_000L, StreamLivenessPolicy.STALE_AFTER_MS)
    }

    @Test
    @DisplayName("the steady-state window is the longer of the two")
    fun steadyStateIsThePatientOne() {
        // Pinned as a relationship rather than as two literals: if a later tuning
        // pass ever inverts them, an unproven link would outlive a proven one,
        // which is the opposite of what either number is for.
        assertTrue(StreamLivenessPolicy.STALE_AFTER_MS > StreamLivenessPolicy.FIRST_SAMPLE_TIMEOUT_MS)
    }

    @Test
    @DisplayName("a stream is dead once the silence reaches the threshold, not after")
    fun boundaryIsStale() {
        assertFalse(StreamLivenessPolicy.isStale(silenceMs = 14_999, timeoutMs = 15_000))
        // Exactly at the boundary counts, and has to: the countdown re-reads the
        // real silence on every wake, so a boundary that resolved to "not yet"
        // would compute a zero-length wait and spin.
        assertTrue(StreamLivenessPolicy.isStale(silenceMs = 15_000, timeoutMs = 15_000))
        assertTrue(StreamLivenessPolicy.isStale(silenceMs = 60_000, timeoutMs = 15_000))
    }

    @Test
    @DisplayName("sub-second jitter and a brief radio hiccup are not a dead strap")
    fun jitterDoesNotFlap() {
        // Straps notify at about 1 Hz, and this package already calls three
        // seconds of silence a discontinuity. None of that may reach the verdict
        // that tears the link down.
        val steady = StreamLivenessPolicy.timeoutMs(hasDelivered = true)
        for (silence in listOf(0L, 900L, 1_100L, 3_000L, 9_000L, 14_000L)) {
            assertFalse(StreamLivenessPolicy.isStale(silence, steady), "${silence}ms of silence flapped")
        }
    }

    @Test
    @DisplayName("a clock that went backwards is not a dead strap either")
    fun negativeSilenceIsNotStale() {
        // Unreachable from the elapsed-time source the watchdog is given, and
        // the one reading that would be actively wrong if it ever were.
        assertFalse(StreamLivenessPolicy.isStale(silenceMs = -5_000, timeoutMs = 15_000))
        assertEquals(15_000L, StreamLivenessPolicy.remainingMs(silenceMs = 0, timeoutMs = 15_000))
    }

    @Test
    @DisplayName("the wait is what is left, and never negative")
    fun remainingIsTheSleep() {
        assertEquals(6_000L, StreamLivenessPolicy.remainingMs(silenceMs = 9_000, timeoutMs = 15_000))
        assertEquals(0L, StreamLivenessPolicy.remainingMs(silenceMs = 15_000, timeoutMs = 15_000))
        // A negative sleep is not a thing to hand to delay(); the verdict is
        // isStale's to give, and this only ever says how long to wait for it.
        assertEquals(0L, StreamLivenessPolicy.remainingMs(silenceMs = 90_000, timeoutMs = 15_000))
    }

    @Test
    @DisplayName("a wait is always long enough to make progress")
    fun theWaitNeverSpins() {
        // The watchdog loops on "still alive? then sleep for what's left". If
        // those two could ever agree on zero it would busy-loop on the main
        // thread for the rest of the capture.
        for (silence in 0L..15_000L step 500) {
            val timeout = StreamLivenessPolicy.timeoutMs(hasDelivered = true)
            if (!StreamLivenessPolicy.isStale(silence, timeout)) {
                assertTrue(
                    StreamLivenessPolicy.remainingMs(silence, timeout) > 0,
                    "a live stream at ${silence}ms asked for a zero-length wait",
                )
            }
        }
    }

    @Test
    @DisplayName("every beat re-arms it: silence is measured from the last one, not from the connect")
    fun everyBeatRearms() {
        // The bug being fixed in one assertion. The ported watchdog disarmed on
        // the first sample and never came back; this replays a connection that
        // beats for a minute and then dies, and the verdict must depend only on
        // the gap since the *last* beat.
        val steady = StreamLivenessPolicy.timeoutMs(hasDelivered = true)
        var lastBeatAt = 0L

        // Sixty seconds of 1 Hz beats: never stale, however long the session runs.
        for (beatAt in 1_000L..60_000L step 1_000) {
            assertFalse(
                StreamLivenessPolicy.isStale(beatAt - lastBeatAt, steady),
                "a healthy stream read as dead at ${beatAt}ms",
            )
            lastBeatAt = beatAt
        }

        // Then the notifications stop. Still trusted at fourteen seconds of
        // silence, declared dead at fifteen — a whole minute of proven history
        // buys the stream nothing once it goes quiet.
        assertFalse(StreamLivenessPolicy.isStale(74_000 - lastBeatAt, steady))
        assertTrue(StreamLivenessPolicy.isStale(75_000 - lastBeatAt, steady))
    }

    @Test
    @DisplayName("a reconnect is unproven again, and held to the shorter clock")
    fun reconnectStartsUnproven() {
        // hasDelivered is per connection, not per capture: a link that comes back
        // and never resumes sending must be caught in ten seconds, the same as
        // one that never started, not in fifteen because an earlier link worked.
        assertTrue(
            StreamLivenessPolicy.isStale(
                silenceMs = 10_000,
                timeoutMs = StreamLivenessPolicy.timeoutMs(hasDelivered = false),
            ),
        )
        assertFalse(
            StreamLivenessPolicy.isStale(
                silenceMs = 10_000,
                timeoutMs = StreamLivenessPolicy.timeoutMs(hasDelivered = true),
            ),
        )
    }

    @Test
    @DisplayName("the connection may override both numbers, and the defaults stay the defaults")
    fun timeoutsAreInjectable() {
        assertEquals(
            50L,
            StreamLivenessPolicy.timeoutMs(hasDelivered = false, firstSampleTimeoutMs = 50, staleAfterMs = 80),
        )
        assertEquals(
            80L,
            StreamLivenessPolicy.timeoutMs(hasDelivered = true, firstSampleTimeoutMs = 50, staleAfterMs = 80),
        )
    }
}
