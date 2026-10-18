package dev.jtiisto.wellness.core.ble.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * When a link loss becomes worth a sentence.
 *
 * The harm being fixed is a notice for a non-event: five drops on 2026-10-16
 * each healed in about two seconds, and each one told the user "Connect attempt
 * 1 failed — retrying" about an attempt that had not failed. The rule is that a
 * loss is news once the record has a hole in it ([GAP_THRESHOLD_MS]) or once a
 * connect attempt has in fact failed — and not before.
 */
class ReconnectNoticeTest {

    @Test
    @DisplayName("the threshold is the data's own gap threshold, three seconds")
    fun thresholdValue() {
        assertEquals(3_000L, GAP_THRESHOLD_MS)
    }

    @Test
    @DisplayName("a fresh loss with no failed attempt says nothing")
    fun freshLossIsSilent() {
        assertNull(ReconnectNotice.detail(sinceLossMs = 0, failedAttempt = 1, verdict = null, attemptFailed = false))
    }

    @Test
    @DisplayName("a loss that heals inside the threshold is never news")
    fun justUnderThresholdIsSilent() {
        assertNull(
            ReconnectNotice.detail(
                sinceLossMs = GAP_THRESHOLD_MS - 1,
                failedAttempt = 1,
                verdict = null,
                attemptFailed = false,
            ),
        )
    }

    @Test
    @DisplayName("exactly at the threshold counts as past it")
    fun exactlyAtThresholdIsNews() {
        // Same convention as StreamLivenessPolicy.isStale: the timer re-asks on
        // every wake, and "not yet" at the boundary would be a zero-length wait.
        assertEquals(
            ConnectDiagnostics.dropDetail(),
            ReconnectNotice.detail(sinceLossMs = GAP_THRESHOLD_MS, failedAttempt = 1, verdict = null, attemptFailed = false),
        )
        assertTrue(ReconnectNotice.isPastThreshold(GAP_THRESHOLD_MS))
        assertFalse(ReconnectNotice.isPastThreshold(GAP_THRESHOLD_MS - 1))
    }

    @Test
    @DisplayName("a long outage with no failed attempt says the link dropped, not that anything failed")
    fun longOutageReadsAsDrop() {
        val text = ReconnectNotice.detail(sinceLossMs = 60_000, failedAttempt = 4, verdict = "ignored", attemptFailed = false)

        assertEquals("Strap link dropped — reconnecting", text)
    }

    @Test
    @DisplayName("a failed attempt is news immediately, with its verdict")
    fun failedAttemptIsNewsAtOnce() {
        val verdict = ConnectDiagnostics.verdict(ProbeState.LISTENING, rssi = null)

        assertEquals(
            ConnectDiagnostics.retryDetail(2, verdict),
            ReconnectNotice.detail(sinceLossMs = 0, failedAttempt = 2, verdict = verdict, attemptFailed = true),
        )
    }

    @Test
    @DisplayName("a failed attempt outranks the drop text once both are true")
    fun failedAttemptWinsPastThreshold() {
        assertEquals(
            "Connect attempt 3 failed — retrying",
            ReconnectNotice.detail(sinceLossMs = 30_000, failedAttempt = 3, verdict = null, attemptFailed = true),
        )
    }

    @Test
    @DisplayName("the retry text reports the failed attempt's own number, not the backoff budget")
    fun reportsTheFailedAttemptNumber() {
        // The connection counts connect attempts per outage: the first reconnect
        // after a drop is attempt 1 even though the drop already spent a backoff
        // step. Whatever number it passes is the number the user reads.
        assertEquals(
            "Connect attempt 1 failed — retrying",
            ReconnectNotice.detail(sinceLossMs = 20_000, failedAttempt = 1, verdict = null, attemptFailed = true),
        )
    }

    @Test
    @DisplayName("the attempt number is ignored while nothing has failed")
    fun attemptNumberIgnoredForADrop() {
        assertEquals(
            ConnectDiagnostics.dropDetail(),
            ReconnectNotice.detail(sinceLossMs = GAP_THRESHOLD_MS, failedAttempt = 7, verdict = null, attemptFailed = false),
        )
        assertNull(ReconnectNotice.detail(sinceLossMs = 0, failedAttempt = 7, verdict = null, attemptFailed = false))
    }

    @Test
    @DisplayName("a clock read out of order is not an outage")
    fun negativeAgeIsSilent() {
        assertNull(ReconnectNotice.detail(sinceLossMs = -5_000, failedAttempt = 1, verdict = null, attemptFailed = false))
        assertFalse(ReconnectNotice.isPastThreshold(Long.MIN_VALUE))
    }

    @Test
    @DisplayName("the wait is what is left of the threshold, never negative")
    fun remainingWait() {
        assertEquals(GAP_THRESHOLD_MS, ReconnectNotice.remainingMs(0))
        assertEquals(1L, ReconnectNotice.remainingMs(GAP_THRESHOLD_MS - 1))
        assertEquals(0L, ReconnectNotice.remainingMs(GAP_THRESHOLD_MS))
        assertEquals(0L, ReconnectNotice.remainingMs(GAP_THRESHOLD_MS + 10_000))
    }

    @Test
    @DisplayName("a wait that ends below the threshold is never zero, so the timer cannot spin")
    fun neverAZeroWaitWhileNotYetNews() {
        // The timer sleeps remainingMs, wakes, and asks again. Every age that is
        // not yet news must yield a positive sleep.
        for (age in listOf(0L, 1L, 1_500L, GAP_THRESHOLD_MS - 1)) {
            assertFalse(ReconnectNotice.isPastThreshold(age))
            assertTrue(ReconnectNotice.remainingMs(age) > 0, "age=$age")
        }
    }
}
