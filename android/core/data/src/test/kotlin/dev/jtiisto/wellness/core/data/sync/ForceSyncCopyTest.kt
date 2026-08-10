package dev.jtiisto.wellness.core.data.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The force-sync dialog's copy, over the whole per-module matrix.
 *
 * Worth pinning because this is the only report the user ever sees of an
 * operation that reconciles everything they own, and the difference between
 * "failed" and "was already running" changes what they should do next.
 */
class ForceSyncCopyTest {

    private fun journalSuccess(accepted: Int = 3, conflicts: Int = 1) =
        ForceSyncModuleResult.Success(ForceSyncCounts.Journal(accepted, conflicts))

    private fun coachSuccess(dates: Int = 2) =
        ForceSyncModuleResult.Success(ForceSyncCounts.Coach(dates))

    private val failed = ForceSyncModuleResult.Failed("Boom")
    private val offline = ForceSyncModuleResult.Skipped(ForceSyncSkipReason.OFFLINE)
    private val busy = ForceSyncModuleResult.Skipped(ForceSyncSkipReason.BUSY)

    @Test
    @DisplayName("both modules succeeded: one line each, with their own units")
    fun bothSucceeded() {
        val report = ForceSyncReport(coach = coachSuccess(12), journal = journalSuccess(7, 2))

        assertTrue(ForceSyncCopy.isSuccess(report))
        assertEquals(
            "Coach: 12 days uploaded. Journal: 7 records accepted, 2 conflicts",
            ForceSyncCopy.message(report),
        )
    }

    @Test
    @DisplayName("a clean force sync still reports zeroes rather than saying nothing")
    fun zeroCountsAreStillSuccess() {
        val report = ForceSyncReport(coach = coachSuccess(0), journal = journalSuccess(0, 0))

        assertTrue(ForceSyncCopy.isSuccess(report))
        assertEquals(
            "Coach: 0 days uploaded. Journal: 0 records accepted, 0 conflicts",
            ForceSyncCopy.message(report),
        )
    }

    @Test
    @DisplayName("one module failing still shows the other's counts, and still reads as success")
    fun mixedSuccessAndFailure() {
        val report = ForceSyncReport(coach = failed, journal = journalSuccess(4, 0))

        assertTrue(ForceSyncCopy.isSuccess(report), "the journal did reconcile")
        assertEquals("Coach: failed. Journal: 4 records accepted, 0 conflicts", ForceSyncCopy.message(report))
    }

    @Test
    @DisplayName("both failing is not a success")
    fun bothFailed() {
        val report = ForceSyncReport(coach = failed, journal = failed)

        assertFalse(ForceSyncCopy.isSuccess(report))
        assertEquals("Coach: failed. Journal: failed", ForceSyncCopy.message(report))
    }

    @Test
    @DisplayName("a connection lost between the two modules is rendered honestly, not hidden")
    fun offlineIsPerModule() {
        // Reachable exactly because offline is evaluated at each module's own
        // start rather than once up front.
        val report = ForceSyncReport(coach = coachSuccess(5), journal = offline)

        assertTrue(ForceSyncCopy.isSuccess(report))
        assertEquals("Coach: 5 days uploaded. Journal: offline", ForceSyncCopy.message(report))
    }

    @Test
    @DisplayName("a module already syncing says so rather than looking like a failure")
    fun busySkipHasItsOwnLine() {
        val report = ForceSyncReport(coach = busy, journal = journalSuccess(1, 0))

        assertEquals("Coach: sync already running. Journal: 1 records accepted, 0 conflicts", ForceSyncCopy.message(report))
    }

    @Test
    @DisplayName("failure and a busy skip are different lines")
    fun failureAndBusyAreDistinct() {
        assertEquals("Coach: failed", ForceSyncCopy.line("Coach", failed))
        assertEquals("Coach: sync already running", ForceSyncCopy.line("Coach", busy))
        assertEquals("Coach: offline", ForceSyncCopy.line("Coach", offline))
    }

    @Test
    @DisplayName("nothing ran at all: one sentence, not two excuses")
    fun bothSkippedCollapses() {
        val report = ForceSyncReport(coach = offline, journal = offline)

        assertFalse(ForceSyncCopy.isSuccess(report))
        assertEquals(ForceSyncCopy.NOTHING_SYNCED, ForceSyncCopy.message(report))
    }

    @Test
    @DisplayName("both skipped collapses even when the two skips have different reasons")
    fun bothSkippedDifferentReasons() {
        val report = ForceSyncReport(coach = busy, journal = offline)

        assertEquals(ForceSyncCopy.NOTHING_SYNCED, ForceSyncCopy.message(report))
    }

    @Test
    @DisplayName("a skip beside a failure is still spelled out — something was attempted")
    fun skipBesideFailureDoesNotCollapse() {
        val report = ForceSyncReport(coach = busy, journal = failed)

        assertFalse(ForceSyncCopy.isSuccess(report))
        assertEquals("Coach: sync already running. Journal: failed", ForceSyncCopy.message(report))
    }

    @Test
    @DisplayName("the confirm copy is the PWA's, verbatim")
    fun confirmCopy() {
        assertEquals("This will reconcile all data with the server. Continue?", ForceSyncCopy.CONFIRM_BODY)
        assertEquals("Syncing…", ForceSyncCopy.BUSY_BUTTON)
    }
}
