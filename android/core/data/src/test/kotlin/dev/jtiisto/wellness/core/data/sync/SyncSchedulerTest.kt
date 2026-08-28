package dev.jtiisto.wellness.core.data.sync

import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Timing behaviour of [SyncScheduler] on virtual time.
 *
 * The scheduler's state is confined to the main dispatcher, so every test
 * installs a [StandardTestDispatcher] as Main sharing the test's scheduler:
 * scheduler state, timers and the flight all advance on one clock, which makes
 * these assertions exact rather than approximate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {

    // --- Debounce ---

    @Test
    @DisplayName("rapid edits coalesce into a single sync one debounce after the last one")
    fun debounceCoalesces() = schedulerTest { robot ->
        val scheduler = robot.build()

        scheduler.scheduleUpload()
        advanceTimeBy(500)
        scheduler.scheduleUpload()
        advanceTimeBy(500)
        scheduler.scheduleUpload()

        advanceTimeBy(2_499)
        assertEquals(emptyList<Long>(), robot.syncStarts)
        advanceTimeBy(2)
        assertEquals(listOf(3_500L), robot.syncStarts)
    }

    // --- Polling ---

    @Test
    @DisplayName("the poll loop is sequential: a slow check never overlaps the next cycle")
    fun pollLoopIsSequential() = schedulerTest { robot ->
        robot.pollCheck = { delay(45_000); true }
        val scheduler = robot.build()

        scheduler.startPolling()
        advanceTimeBy(200_001)

        // Each wait starts only once the previous cycle finished, so the cadence
        // is interval + duration rather than a fixed rate.
        assertEquals(listOf(30_000L, 105_000L, 180_000L), robot.pollChecks)
        assertEquals(listOf(75_000L, 150_000L), robot.syncStarts)
        assertEquals(1, robot.maxConcurrentPollChecks)
    }

    @Test
    @DisplayName("startPolling is idempotent — three calls still poll once per interval")
    fun startPollingIsIdempotent() = schedulerTest { robot ->
        val scheduler = robot.build()

        scheduler.startPolling()
        scheduler.startPolling()
        scheduler.startPolling()
        advanceTimeBy(65_000)

        assertEquals(listOf(30_000L, 60_000L), robot.syncStarts)
    }

    @Test
    @DisplayName("a poll check returning false skips the cycle")
    fun pollCheckFalseSkipsTheCycle() = schedulerTest { robot ->
        robot.pollCheck = { false }
        val scheduler = robot.build()

        scheduler.startPolling()
        advanceTimeBy(95_000)

        assertEquals(listOf(30_000L, 60_000L, 90_000L), robot.pollChecks)
        assertEquals(emptyList<Long>(), robot.syncStarts)
    }

    @Test
    @DisplayName("a poll check that throws skips the cycle silently: no error surfaced, no retry")
    fun pollCheckThrowSkipsTheCycleSilently() = schedulerTest { robot ->
        robot.pollCheck = { throw IOException("plans-version unreachable") }
        val scheduler = robot.build()

        scheduler.startPolling()
        advanceTimeBy(95_000)

        assertEquals(listOf(30_000L, 60_000L, 90_000L), robot.pollChecks)
        assertEquals(emptyList<Long>(), robot.syncStarts)
        assertTrue(robot.serverErrors.isEmpty())
    }

    @Test
    @DisplayName("a poll cycle is skipped entirely while the store reports itself syncing")
    fun pollSkipsWhileStoreIsSyncing() = schedulerTest { robot ->
        robot.storeSyncing = true
        robot.pollCheck = { true }
        val scheduler = robot.build()

        scheduler.startPolling()
        advanceTimeBy(95_000)

        assertEquals(emptyList<Long>(), robot.pollChecks)
        assertEquals(emptyList<Long>(), robot.syncStarts)
    }

    @Test
    @DisplayName("a poll landing mid-flight is skipped before its check and sets no pendingSync")
    fun pollSkipsWhileFlightIsActive() = schedulerTest { robot ->
        robot.syncDurationMs = 45_000
        robot.pollCheck = { true }
        val scheduler = robot.build()

        scheduler.startPolling()
        scheduler.requestSync()      // flight spans t=0..45_000, across the t=30_000 poll
        advanceTimeBy(75_001)

        // The mid-flight poll returned before its check ran and set no
        // pendingSync — so no debounce-armed sync at 47_500; the next real sync
        // is the t=60_000 poll's.
        assertEquals(listOf(60_000L), robot.pollChecks)
        assertEquals(listOf(0L, 60_000L), robot.syncStarts)
    }

    @Test
    @DisplayName("a dirty probe that throws fails closed: no crash, no re-arm, scheduler keeps working")
    fun dirtyProbeFailureFailsClosed() = schedulerTest { robot ->
        robot.dirtyProbe = { error("database gone") }
        val scheduler = robot.build()

        scheduler.requestSync()
        runCurrent()
        assertEquals(listOf(0L), robot.syncStarts)

        // The throwing probe was swallowed to false — nothing re-armed.
        advanceTimeBy(10_000)
        assertEquals(listOf(0L), robot.syncStarts)

        // And the scheduler survived to serve the next trigger.
        scheduler.requestSync()
        runCurrent()
        assertEquals(listOf(0L, 10_000L), robot.syncStarts)
    }

    // --- Retry / backoff ---

    @Test
    @DisplayName("retries back off 5s, 10s, 20s, 40s, 80s then hold at the 120s cap")
    fun retryBacksOffToTheCap() = schedulerTest { robot ->
        robot.behavior = { SyncResult(success = false, error = IOException("server down")) }
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(400_001)

        assertEquals(
            listOf(0L, 5_000L, 15_000L, 35_000L, 75_000L, 155_000L, 275_000L, 395_000L),
            robot.syncStarts,
        )
    }

    @Test
    @DisplayName("requestSync drops the pending retry timer but keeps the attempt counter")
    fun requestSyncKeepsTheAttemptCounter() = schedulerTest { robot ->
        robot.behavior = { SyncResult(success = false, error = IOException("server down")) }
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(1_000)
        scheduler.requestSync()
        advanceTimeBy(20_000)

        // Third attempt lands 10s (attempt 1) after the second, not 5s: an
        // immediate request is not evidence that the server recovered.
        assertEquals(listOf(0L, 1_000L, 11_000L), robot.syncStarts)
    }

    @Test
    @DisplayName("onOffline drops the pending retry timer but keeps the attempt counter")
    fun onOfflineKeepsTheAttemptCounter() = schedulerTest { robot ->
        robot.behavior = { SyncResult(success = false, error = IOException("server down")) }
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(1_000)
        robot.online = false
        scheduler.onOffline()
        advanceTimeBy(30_000)
        assertEquals(listOf(0L), robot.syncStarts)

        robot.online = true
        scheduler.requestSync()
        advanceTimeBy(20_000)

        assertEquals(listOf(0L, 31_000L, 41_000L), robot.syncStarts)
    }

    @Test
    @DisplayName("a successful sync zeroes both the retry timer and the attempt counter")
    fun resetOutcomeZeroesBoth() = schedulerTest { robot ->
        robot.behavior = { attempt ->
            if (attempt == 2) SyncResult(success = true) else SyncResult(false, error = IOException("down"))
        }
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(6_000)
        scheduler.requestSync()
        advanceTimeBy(10_000)

        // Fourth attempt is 5s after the third — the counter went back to zero.
        assertEquals(listOf(0L, 5_000L, 6_000L, 11_000L), robot.syncStarts)
    }

    @Test
    @DisplayName("resetRetry clears the timer and the counter together")
    fun resetRetryClearsBoth() = schedulerTest { robot ->
        robot.behavior = { SyncResult(success = false, error = IOException("down")) }
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(1_000)
        scheduler.resetRetry()
        advanceTimeBy(20_000)
        assertEquals(listOf(0L), robot.syncStarts)

        scheduler.requestSync()
        advanceTimeBy(6_000)

        assertEquals(listOf(0L, 21_000L, 26_000L), robot.syncStarts)
    }

    @Test
    @DisplayName("a skip result touches neither the retry timer nor the counter")
    fun skipOutcomeTouchesNothing() = schedulerTest { robot ->
        robot.behavior = { attempt ->
            if (attempt == 2) {
                SyncResult(success = false, reason = SyncSkipReason.ALREADY_SYNCING)
            } else {
                SyncResult(success = false, error = IOException("down"))
            }
        }
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(20_000)
        // The skip armed nothing of its own.
        assertEquals(listOf(0L, 5_000L), robot.syncStarts)

        scheduler.requestSync()
        advanceTimeBy(15_000)

        // And it left the counter at 1, so this retry waits 10s.
        assertEquals(listOf(0L, 5_000L, 20_000L, 30_000L), robot.syncStarts)
    }

    @Test
    @DisplayName("a debounce and a retry can be armed at once, and the debounce does not clear the retry")
    fun dualTimersCoexist() = schedulerTest { robot ->
        robot.dirty = true
        robot.behavior = { attempt ->
            if (attempt == 1) {
                SyncResult(success = false, error = IOException("down"))
            } else {
                robot.dirty = false
                SyncResult(success = false, reason = SyncSkipReason.OFFLINE)
            }
        }
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(10_001)

        // t=0 failed (retry armed for 5_000) and had dirty data (debounce armed
        // for 2_500). Both fired.
        assertEquals(listOf(0L, 2_500L, 5_000L), robot.syncStarts)
    }

    // --- Single flight and pendingSync ---

    @Test
    @DisplayName("a trigger arriving mid-flight skips the finally; the running flight re-arms for it")
    fun busyTriggerSkipsTheFinally() = schedulerTest { robot ->
        robot.syncDurationMs = 1_000
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(100)
        scheduler.requestSync()
        advanceTimeBy(100)
        scheduler.requestSync()
        advanceTimeBy(5_000)

        // Had the busy path run the finally it would have cleared the in-flight
        // flag, letting the third trigger start a second concurrent sync, and
        // consumed pendingSync so the flight never re-armed.
        assertEquals(listOf(0L, 3_500L), robot.syncStarts)
        assertEquals(1, robot.maxConcurrentSyncs)
    }

    @Test
    @DisplayName("two triggers in the same instant run one flight")
    fun concurrentTriggersRunOneFlight() = schedulerTest { robot ->
        robot.syncDurationMs = 1_000
        val scheduler = robot.build()

        scheduler.requestSync()
        scheduler.requestSync()
        scheduler.scheduleUpload()
        advanceTimeBy(10_000)

        assertEquals(1, robot.maxConcurrentSyncs)
        assertEquals(listOf(0L, 3_500L), robot.syncStarts)
    }

    @Test
    @DisplayName("a retry firing mid-flight becomes a pendingSync rather than a second flight")
    fun retryDuringFlightBecomesPending() = schedulerTest { robot ->
        robot.behavior = { attempt ->
            if (attempt == 1) SyncResult(success = false, error = IOException("down")) else SyncResult(true)
        }
        val scheduler = robot.build()

        scheduler.requestSync()
        runCurrent()
        robot.syncDurationMs = 10_000
        scheduler.scheduleUpload()
        advanceTimeBy(20_000)

        // Retry was due at 5_000, mid-flight; it set the flag and the flight's
        // finally turned it into one debounced follow-up.
        assertEquals(listOf(0L, 2_500L, 15_000L), robot.syncStarts)
        assertEquals(1, robot.maxConcurrentSyncs)
    }

    @Test
    @DisplayName("a trigger while the store alone is busy leaves pendingSync for the next flight's finally")
    fun externallyBusyTriggerLeavesThePendingFlag() = schedulerTest { robot ->
        robot.storeSyncing = true
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(1_000)
        assertEquals(emptyList<Long>(), robot.syncStarts)

        // The flag itself is PWA parity: nothing owned by the scheduler was
        // running, so it survives until some later execution reaches its
        // finally — which is the second start below. What is NOT parity is the
        // timer this now also arms (see the test after this one); it is
        // cancelled here by `requestSync`, so the timeline is unchanged.
        robot.storeSyncing = false
        scheduler.requestSync()
        advanceTimeBy(5_000)

        assertEquals(listOf(1_000L, 3_500L), robot.syncStarts)
    }

    @Test
    @DisplayName("a trigger refused because an EXTERNAL syncer is busy still arms the debounce")
    fun externallyBusyTriggerArmsTheDebounce() = schedulerTest { robot ->
        // The flush Worker or the force-sync orchestrator owns the store's flag.
        robot.storeSyncing = true
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(1_000)
        assertEquals(emptyList<Long>(), robot.syncStarts)

        // It finishes, and nothing triggers again — the case the PWA's parity
        // does not cover, because the PWA had no external syncers. Without the
        // armed timer the latch sits unread and the requested sync simply never
        // happens.
        robot.storeSyncing = false
        advanceTimeBy(2_000)

        assertEquals(listOf(2_500L), robot.syncStarts)
    }

    @Test
    @DisplayName("the scheduler's OWN flight arms nothing extra — its finally is what re-arms")
    fun ownFlightDoesNotDoubleArm() = schedulerTest { robot ->
        robot.syncDurationMs = 10_000
        val scheduler = robot.build()

        scheduler.requestSync()
        runCurrent()
        // Refused because this scheduler is mid-flight: the running invocation
        // owns the flag and consumes it in its finally, so arming here would be
        // a second debounce racing that one.
        scheduler.requestSync()
        advanceTimeBy(30_000)

        assertEquals(listOf(0L, 12_500L), robot.syncStarts)
        assertEquals(1, robot.maxConcurrentSyncs)
    }

    @Test
    @DisplayName("an in-flight sync survives scheduleUpload, requestSync, onOffline and stop")
    fun inFlightSyncSurvivesEverything() = schedulerTest { robot ->
        robot.syncDurationMs = 5_000
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(1_000)
        scheduler.scheduleUpload()
        scheduler.requestSync()
        scheduler.onOffline()
        scheduler.stop()
        advanceTimeBy(20_000)

        assertEquals(listOf(0L), robot.syncStarts)
        assertEquals(listOf(5_000L), robot.syncEnds)
    }

    // --- Error handling ---

    @Test
    @DisplayName("a CancellationException is never classified and never retried")
    fun cancellationIsNeverClassified() = schedulerTest { robot ->
        robot.behavior = { attempt ->
            if (attempt == 1) throw CancellationException("aborted") else SyncResult(true)
        }
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(200_000)
        assertEquals(listOf(0L), robot.syncStarts)
        assertTrue(robot.serverErrors.isEmpty())

        // The scheduler itself is unharmed.
        scheduler.requestSync()
        advanceTimeBy(1)
        assertEquals(2, robot.syncStarts.size)
    }

    @Test
    @DisplayName("server errors are surfaced; network errors retry silently")
    fun serverErrorsSurfaceAndNetworkErrorsDoNot() = schedulerTest { robot ->
        robot.behavior = { SyncResult(success = false, error = IllegalStateException("500")) }
        robot.networkError = false
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(1)
        assertEquals(1, robot.serverErrors.size)

        robot.networkError = true
        scheduler.requestSync()
        advanceTimeBy(1)
        assertEquals(1, robot.serverErrors.size)
    }

    @Test
    @DisplayName("a thrown exception takes the same error path as a returned one")
    fun thrownExceptionTakesTheErrorPath() = schedulerTest { robot ->
        robot.networkError = false
        robot.behavior = { throw IllegalStateException("boom") }
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(6_000)

        assertEquals(listOf(0L, 5_000L), robot.syncStarts)
        assertEquals(2, robot.serverErrors.size)
    }

    // --- The returned job, and what a pull can conclude from it ---

    @Test
    @DisplayName("the job requestSync returns spans the whole flight")
    fun requestSyncJobSpansTheFlight() = schedulerTest { robot ->
        robot.syncDurationMs = 5_000
        val scheduler = robot.build()

        val job = scheduler.requestSync()
        advanceTimeBy(4_999)
        assertFalse(job.isCompleted, "the sync is still running")

        advanceTimeBy(2)
        assertTrue(job.isCompleted)
        assertEquals(listOf(0L), robot.syncStarts)
    }

    @Test
    @DisplayName("the job completes AT ONCE when the flight is somebody else's — the pull's trap")
    fun requestSyncJobCompletesInstantlyWhenBusy() = schedulerTest { robot ->
        // A background flush or a force sync holds the store's own flag; this
        // scheduler never enters syncFn, so its job has nothing to wait for.
        robot.storeSyncing = true
        val scheduler = robot.build()

        val job = scheduler.requestSync()
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals(emptyList<Long>(), robot.syncStarts)
    }

    @Test
    @DisplayName("the trigger name travels into the debug log; the default is still 'request'")
    fun triggerNameIsRecorded() = schedulerTest { robot ->
        val scheduler = robot.build()

        scheduler.requestSync(SyncScheduler.TRIGGER_PULL)
        runCurrent()
        scheduler.requestSync()
        runCurrent()
        scheduler.scheduleUpload()
        advanceTimeBy(2_501)

        assertEquals(listOf("pull", "request", "debounce"), robot.triggers)
    }

    // --- Offline and teardown ---

    @Test
    @DisplayName("nothing executes while offline")
    fun nothingRunsWhileOffline() = schedulerTest { robot ->
        robot.online = false
        val scheduler = robot.build()

        scheduler.requestSync()
        scheduler.scheduleUpload()
        advanceTimeBy(60_000)

        assertEquals(emptyList<Long>(), robot.syncStarts)
    }

    @Test
    @DisplayName("going offline mid-flight still lets the finally arm a debounce, which exits at the online check")
    fun offlineDuringFlightArmsADebounceThatExits() = schedulerTest { robot ->
        robot.syncDurationMs = 5_000
        robot.dirty = true
        val scheduler = robot.build()

        scheduler.requestSync()
        advanceTimeBy(1_000)
        robot.online = false
        scheduler.onOffline()
        advanceTimeBy(20_000)
        assertEquals(listOf(0L), robot.syncStarts)

        // The debounce armed at 5_000 already fired at 7_500 and returned at the
        // online check; coming back online does not resurrect it.
        robot.online = true
        advanceTimeBy(60_000)
        assertEquals(listOf(0L), robot.syncStarts)
    }

    @Test
    @DisplayName("stop is idempotent and every later call is a no-op")
    fun stopIsTerminalAndIdempotent() = schedulerTest { robot ->
        val scheduler = robot.build()

        scheduler.stop()
        scheduler.stop()
        runCurrent()

        scheduler.scheduleUpload()
        scheduler.requestSync()
        scheduler.onOnline()
        scheduler.startPolling()
        advanceTimeBy(200_000)

        assertEquals(emptyList<Long>(), robot.syncStarts)
    }

    /**
     * Main is reset only after [runTest] has torn the test scope down —
     * resetting inside the body would leave the cancellation of leftover
     * coroutines dispatching to a main looper that does not exist here.
     */
    @AfterEach
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun schedulerTest(body: suspend TestScope.(Robot) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        body(Robot(this))
    }
}

/**
 * Test double for everything a scheduler talks to, recording virtual-time
 * instants so the assertions read as a timeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class Robot(private val scope: TestScope) {

    var online = true
    var storeSyncing = false
    var dirty = false

    /** Overrides the dirty probe entirely (e.g. to throw); default reads [dirty]. */
    var dirtyProbe: (suspend () -> Boolean)? = null

    /** How long a sync takes; 0 completes within the same instant. */
    var syncDurationMs = 0L

    /** Verdict of `isNetworkError` for whatever the sync failed with. */
    var networkError = true

    /** Outcome of the n-th sync, 1-based. May throw. */
    var behavior: suspend (Int) -> SyncResult = { SyncResult(success = true) }

    /** Set before [build] to install a poll check. */
    var pollCheck: (suspend () -> Boolean)? = null

    val syncStarts = mutableListOf<Long>()
    val syncEnds = mutableListOf<Long>()
    val pollChecks = mutableListOf<Long>()
    val serverErrors = mutableListOf<Throwable>()

    /** Every trigger name the scheduler recorded, in order — the log is where it says so. */
    val triggers = mutableListOf<String>()

    private val debugLog = mockk<DebugLog>(relaxed = true).also { log ->
        every { log.log(any(), any(), any()) } answers {
            if (secondArg<String>() == "sync triggered") {
                val data = thirdArg<JsonElement?>() as JsonObject
                triggers += data.getValue("trigger").jsonPrimitive.content
            }
        }
    }
    var maxConcurrentSyncs = 0
        private set
    var maxConcurrentPollChecks = 0
        private set

    private var syncsInFlight = 0
    private var pollChecksInFlight = 0

    fun build(
        uploadDebounceMs: Long = 2_500,
        pollIntervalMs: Long = 30_000,
        baseRetryMs: Long = 5_000,
        maxRetryMs: Long = 120_000,
    ): SyncScheduler = SyncScheduler(
        scope = scope.backgroundScope,
        name = "test",
        syncFn = ::runSync,
        isSyncing = { storeSyncing },
        hasDirtyData = { dirtyProbe?.invoke() ?: dirty },
        isOnline = { online },
        pollCheckFn = pollCheck?.let { check -> suspend { runPollCheck(check) } },
        onServerError = { serverErrors += it },
        isNetworkError = { networkError },
        uploadDebounceMs = uploadDebounceMs,
        pollIntervalMs = pollIntervalMs,
        baseRetryMs = baseRetryMs,
        maxRetryMs = maxRetryMs,
        debugLog = debugLog,
    )

    private suspend fun runSync(): SyncResult {
        val attempt = syncStarts.size + 1
        syncStarts += scope.currentTime
        syncsInFlight++
        maxConcurrentSyncs = maxOf(maxConcurrentSyncs, syncsInFlight)
        try {
            if (syncDurationMs > 0) delay(syncDurationMs)
            return behavior(attempt)
        } finally {
            syncsInFlight--
            syncEnds += scope.currentTime
        }
    }

    private suspend fun runPollCheck(check: suspend () -> Boolean): Boolean {
        pollChecks += scope.currentTime
        pollChecksInFlight++
        maxConcurrentPollChecks = maxOf(maxConcurrentPollChecks, pollChecksInFlight)
        try {
            return check()
        } finally {
            pollChecksInFlight--
        }
    }
}
