package dev.jtiisto.wellness.core.data.sync

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * When backgrounding is worth a Worker.
 *
 * The rule is one line and the reason it is worth a test is battery: an
 * unconditional enqueue wakes the process on the system's schedule to discover
 * there was nothing to send.
 */
class SyncFlushSchedulerTest {

    @Test
    @DisplayName("dirty rows at backgrounding enqueue exactly one flush")
    fun dirtyEnqueues() = runTest {
        var enqueued = 0
        SyncFlushScheduler(hasDirtyData = { true }, enqueue = { enqueued++ }).onBackgrounded()

        assertEquals(1, enqueued)
    }

    @Test
    @DisplayName("a clean database enqueues nothing")
    fun cleanEnqueuesNothing() = runTest {
        var enqueued = 0
        SyncFlushScheduler(hasDirtyData = { false }, enqueue = { enqueued++ }).onBackgrounded()

        assertEquals(0, enqueued)
    }

    @Test
    @DisplayName("a dirty probe that throws fails closed instead of taking the app scope with it")
    fun probeFailureIsSwallowed() = runTest {
        var enqueued = 0
        val scheduler = SyncFlushScheduler(
            hasDirtyData = { throw IllegalStateException("database busy") },
            enqueue = { enqueued++ },
        )

        // This runs on the orchestrator's control dispatcher; an exception here
        // would cancel every scheduler in the process.
        scheduler.onBackgrounded()

        assertEquals(0, enqueued)
    }

    @Test
    @DisplayName("backgrounding repeatedly asks each time — the unique-work policy is what dedups")
    fun repeatedBackgroundingAsksEachTime() = runTest {
        var enqueued = 0
        val scheduler = SyncFlushScheduler(hasDirtyData = { true }, enqueue = { enqueued++ })

        scheduler.onBackgrounded()
        scheduler.onBackgrounded()

        // Deduplication belongs to WorkManager's ExistingWorkPolicy.KEEP, not
        // here: this class cannot see what is already enqueued.
        assertEquals(2, enqueued)
    }

    @Test
    @DisplayName("a foreground sync winning the tryLock race is a success, not a retry")
    fun busySkipIsNotRetried() {
        val skipped = SyncResult(success = false, reason = SyncSkipReason.ALREADY_SYNCING)

        // The Worker runs the ordinary triggerSync precisely because the store's
        // tryLock makes this safe: the sync it would have done is already
        // happening, so queueing a backoff would achieve nothing.
        assertEquals(false, SyncFlushWorker.needsRetry(listOf(skipped, skipped)))
    }

    @Test
    @DisplayName("an OFFLINE skip DOES retry — it is the only durable attempt this data gets")
    fun offlineSkipIsRetried() {
        val offline = SyncResult(success = false, reason = SyncSkipReason.OFFLINE)

        // WorkManager's CONNECTED constraint is satisfied by a captive or
        // unvalidated Wi-Fi association, where the app's validated-internet
        // check correctly reports offline. Reporting success there let the
        // process die with the dirty rows still unsent.
        assertEquals(true, SyncFlushWorker.needsRetry(listOf(offline, SyncResult(success = true))))
        assertEquals(true, SyncFlushWorker.needsRetry(listOf(offline, offline)))
    }

    @Test
    @DisplayName("a busy skip beside an offline skip still retries — the offline half never ran")
    fun mixedSkipsRetry() {
        val offline = SyncResult(success = false, reason = SyncSkipReason.OFFLINE)
        val busy = SyncResult(success = false, reason = SyncSkipReason.ALREADY_SYNCING)

        assertEquals(true, SyncFlushWorker.needsRetry(listOf(busy, offline)))
    }

    @Test
    @DisplayName("both modules landing cleanly is the one case that needs no retry")
    fun cleanRunDoesNotRetry() {
        assertEquals(
            false,
            SyncFlushWorker.needsRetry(listOf(SyncResult(success = true), SyncResult(success = true))),
        )
    }

    @Test
    @DisplayName("a real error earns the exponential backoff")
    fun errorIsRetried() {
        val failed = SyncResult(success = false, error = IllegalStateException("server down"))

        assertEquals(true, SyncFlushWorker.needsRetry(listOf(SyncResult(success = true), failed)))
    }

    @Test
    @DisplayName("either module being dirty is enough")
    fun eitherModuleIsEnough() = runTest {
        var enqueued = 0
        var journalDirty = false
        val coachDirty = true
        SyncFlushScheduler(
            hasDirtyData = { journalDirty || coachDirty },
            enqueue = { enqueued++ },
        ).onBackgrounded()

        assertEquals(1, enqueued)
        journalDirty = true
    }

    @Test
    @DisplayName("pending heart-rate rows are dirty data too, on their own")
    fun heartRateAloneIsEnough() = runTest {
        var enqueued = 0
        // The composition Koin builds. A set tick made seconds before the app
        // went away is exactly the row the Worker exists for, and it belongs to
        // neither of the two modules that used to be the whole of this check.
        SyncFlushScheduler(
            hasDirtyData = { journalDirty() || coachDirty() || hrPending() },
            enqueue = { enqueued++ },
        ).onBackgrounded()

        assertEquals(1, enqueued)
    }

    private suspend fun journalDirty(): Boolean = false

    private suspend fun coachDirty(): Boolean = false

    private suspend fun hrPending(): Boolean = true
}
