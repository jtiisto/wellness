package dev.jtiisto.wellness.core.data.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Transcribed from `test/js/sync-scheduler-logic.test.js`: the backoff math and
 * outcome routing whose bugs mean silently lost syncs.
 */
class SyncSchedulerLogicTest {

    @Test
    @DisplayName("computeRetryDelay: exponential backoff doubles per attempt")
    fun backoffDoubles() {
        assertEquals(5_000L, computeRetryDelay(0, 5_000, 120_000))
        assertEquals(10_000L, computeRetryDelay(1, 5_000, 120_000))
        assertEquals(20_000L, computeRetryDelay(2, 5_000, 120_000))
        assertEquals(40_000L, computeRetryDelay(3, 5_000, 120_000))
    }

    @Test
    @DisplayName("computeRetryDelay: capped at maxMs")
    fun backoffCaps() {
        assertEquals(120_000L, computeRetryDelay(5, 5_000, 120_000)) // 160000 capped
        assertEquals(120_000L, computeRetryDelay(50, 5_000, 120_000)) // no overflow blowup
    }

    @Test
    @DisplayName("classifySyncOutcome: success and handled-conflicts reset retry state")
    fun successAndConflictsReset() {
        assertEquals(SyncOutcome.RESET, classifySyncOutcome(SyncResult(success = true)))
        assertEquals(
            SyncOutcome.RESET,
            classifySyncOutcome(SyncResult(success = false, reason = SyncSkipReason.CONFLICTS)),
        )
    }

    @Test
    @DisplayName("classifySyncOutcome: offline / already-syncing are not errors")
    fun offlineAndBusySkip() {
        assertEquals(
            SyncOutcome.SKIP,
            classifySyncOutcome(SyncResult(success = false, reason = SyncSkipReason.OFFLINE)),
        )
        assertEquals(
            SyncOutcome.SKIP,
            classifySyncOutcome(SyncResult(success = false, reason = SyncSkipReason.ALREADY_SYNCING)),
        )
    }

    @Test
    @DisplayName("classifySyncOutcome: failure with error object routes to error handling")
    fun failureWithErrorRoutesToError() {
        assertEquals(
            SyncOutcome.ERROR,
            classifySyncOutcome(SyncResult(success = false, error = RuntimeException("x"))),
        )
    }

    @Test
    @DisplayName("classifySyncOutcome: generic failure schedules a silent retry")
    fun genericFailureRetries() {
        assertEquals(SyncOutcome.RETRY, classifySyncOutcome(SyncResult(success = false)))
        assertEquals(
            SyncOutcome.RETRY,
            classifySyncOutcome(SyncResult(success = false, reason = SyncSkipReason.OTHER)),
        )
    }

    // --- Beyond the JS suite ---

    @Test
    @DisplayName("classifySyncOutcome: success wins over an error carried alongside it")
    fun successOutranksError() {
        assertEquals(
            SyncOutcome.RESET,
            classifySyncOutcome(SyncResult(success = true, error = RuntimeException("ignored"))),
        )
        assertEquals(
            SyncOutcome.RESET,
            classifySyncOutcome(
                SyncResult(success = false, reason = SyncSkipReason.CONFLICTS, error = RuntimeException("ignored")),
            ),
        )
    }

    @Test
    @DisplayName("classifySyncOutcome: a skip reason wins over an error carried alongside it")
    fun skipOutranksError() {
        assertEquals(
            SyncOutcome.SKIP,
            classifySyncOutcome(
                SyncResult(success = false, reason = SyncSkipReason.OFFLINE, error = RuntimeException("ignored")),
            ),
        )
    }

    @Test
    @DisplayName("computeRetryDelay: saturates rather than overflowing at absurd attempts")
    fun backoffSaturates() {
        assertEquals(120_000L, computeRetryDelay(61, 5_000, 120_000))
        assertEquals(120_000L, computeRetryDelay(62, 5_000, 120_000))
        assertEquals(120_000L, computeRetryDelay(1_000, 5_000, 120_000))
        assertEquals(120_000L, computeRetryDelay(Int.MAX_VALUE, 5_000, 120_000))
        // A base large enough to overflow the shift still yields the ceiling.
        assertEquals(120_000L, computeRetryDelay(40, Long.MAX_VALUE / 2, 120_000))
    }

    @Test
    @DisplayName("computeRetryDelay: a negative attempt clamps to the base delay")
    fun negativeAttemptClamps() {
        assertEquals(5_000L, computeRetryDelay(-1, 5_000, 120_000))
        assertEquals(5_000L, computeRetryDelay(Int.MIN_VALUE, 5_000, 120_000))
    }
}
