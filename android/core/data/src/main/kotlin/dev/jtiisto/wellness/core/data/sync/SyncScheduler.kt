package dev.jtiisto.wellness.core.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sync triggering for one module — a coroutine port of `sync-scheduler.js`.
 *
 * Debounced uploads, foreground polling, retry with backoff, and the
 * `pendingSync` re-arm, all driven by [SyncOrchestrator]. One instance per
 * module, living for the process, not for an activity.
 *
 * Two invariants carry the whole design:
 *
 * 1. **Timers and the flight are separate jobs.** The debounce, retry and poll
 *    timers are cancellable at will; a running [syncFn] never is. Cancelling a
 *    timer mid-flight would abandon an upload halfway through.
 * 2. **State is confined to the main dispatcher.** Every field below is read
 *    and written only from [control], which makes the busy check atomic: two
 *    concurrent triggers cannot both enter [syncFn]; the loser sets
 *    `pendingSync` and the winner's `finally` picks it up.
 *
 * Deviations from the PWA are declared in `specs/core-plumbing.md`: the poll
 * loop is sequential rather than fixed-rate, polling is foreground-gated, and
 * single-flight is enforced here rather than merely advised by [isSyncing].
 *
 * @param scope app-lived (survives backgrounding); tests pass a `TestScope`.
 * @param isSyncing the owning store's own notion of "busy", checked alongside
 *   this scheduler's internal flight.
 * @param isNetworkError decides silent-retry (network) vs [onServerError].
 */
class SyncScheduler(
    scope: CoroutineScope,
    private val name: String,
    private val syncFn: suspend () -> SyncResult,
    private val isSyncing: () -> Boolean,
    private val hasDirtyData: suspend () -> Boolean,
    private val isOnline: () -> Boolean,
    private val pollCheckFn: (suspend () -> Boolean)? = null,
    private val onServerError: (Throwable) -> Unit = {},
    private val isNetworkError: (Throwable) -> Boolean,
    private val uploadDebounceMs: Long = 2_500,
    private val pollIntervalMs: Long = 30_000,
    private val baseRetryMs: Long = 5_000,
    private val maxRetryMs: Long = 120_000,
    private val debugLog: DebugLog? = null,
) {
    private val control = scope + Dispatchers.Main.immediate
    private val logTag = "$name-scheduler"

    private var debounceJob: Job? = null
    private var retryJob: Job? = null
    private var pollJob: Job? = null
    private var retryAttempt = 0
    private var pendingSync = false
    private var syncInFlight = false
    private var stopped = false

    /** Called by mutators after marking data dirty. Resets the debounce timer. */
    fun scheduleUpload() {
        control.launch { armDebounce() }
    }

    /**
     * Sync now: drop the pending debounce and the pending retry *timer*, then
     * execute. The retry attempt counter survives — an immediate request is not
     * evidence that the server recovered.
     *
     * Returns the job that does it, as [stop] does and for a related reason: a
     * caller that asked for this sync on the user's behalf — the pull gesture —
     * has to be able to tell when it is over.
     *
     * **The job is not always the flight.** In the ordinary case it is: the body
     * awaits [runSync], which awaits [syncFn]. But the busy path returns as soon
     * as it has set `pendingSync` (the real flight belongs to another job), and
     * so does the offline check — so a caller waiting for "the sync finished"
     * must also wait for the owning store's own busy flag to clear. That flag is
     * observable on both stores precisely for this.
     *
     * @param trigger what to record in the debug log as the cause. Callers
     *   outside this class pass [TRIGGER_PULL]; everything else is internal.
     */
    fun requestSync(trigger: String = TRIGGER_REQUEST): Job = control.launch {
        if (stopped) return@launch
        cancelDebounce()
        cancelRetryTimer()
        runSync(trigger)
    }

    /** Clear retry state entirely, timer and counter. The force-sync hook. */
    fun resetRetry() {
        control.launch {
            if (stopped) return@launch
            retryAttempt = 0
            cancelRetryTimer()
        }
    }

    /**
     * Connectivity came back. Flushes dirty data even when backgrounded;
     * whether to also poll is the orchestrator's call.
     */
    fun onOnline() {
        requestSync()
    }

    /** Connectivity went away: stop polling and drop the timers (not the counter). */
    fun onOffline() {
        control.launch {
            cancelPolling()
            cancelDebounce()
            cancelRetryTimer()
        }
    }

    /** Begin the poll loop. Idempotent. Driven by the orchestrator. */
    fun startPolling() {
        control.launch {
            if (stopped || pollJob?.isActive == true) return@launch
            pollJob = control.launch {
                // Sequential by construction: the next wait starts only once the
                // previous cycle finished, so a slow pollCheckFn can never
                // overlap itself. Cadence drifts by the cycle duration.
                while (isActive) {
                    delay(pollIntervalMs)
                    pollOnce()
                }
            }
        }
    }

    /** Stop the poll loop. Idempotent. */
    fun stopPolling() {
        control.launch { cancelPolling() }
    }

    /**
     * Terminal and idempotent: every timer is cancelled and later calls no-op.
     * A [syncFn] already in flight runs to completion — we never leave an
     * upload half-applied.
     *
     * Returns the job that does it, because the work is dispatched to [control]
     * rather than performed here: the server switch has to *know* no new cycle
     * can be scheduled before it drains, and calling this from a background
     * thread only queues that onto the main dispatcher. Callers with no such
     * ordering requirement can ignore it.
     */
    fun stop(): Job = control.launch {
        stopped = true
        cancelPolling()
        cancelDebounce()
        cancelRetryTimer()
    }

    // --- Internal: all of the below runs on [control] ---

    /**
     * A fired timer is left in its field rather than nulled from inside the
     * coroutine: on an immediate dispatcher the body can run before the
     * assignment lands, and a self-clear would then be overwritten. Cancelling
     * an already-completed job is a no-op, so nothing depends on the difference.
     */
    private fun armDebounce() {
        if (stopped) return
        cancelDebounce()
        debounceJob = control.launch {
            delay(uploadDebounceMs)
            executeSync(TRIGGER_DEBOUNCE)
        }
    }

    /**
     * Start a sync in a job of its own. Timer coroutines call this and then
     * finish, so cancelling a timer can never reach into the flight.
     */
    private fun executeSync(trigger: String) {
        control.launch { runSync(trigger) }
    }

    private suspend fun runSync(trigger: String) {
        if (stopped) return
        if (!isOnline()) return

        // Busy check sits *before* the try/finally on purpose: this path must
        // not run the finally block. The flight that is actually running owns
        // the flag and consumes it when it completes.
        if (isSyncing() || syncInFlight) {
            pendingSync = true
            // …unless the flight is not ours. When the busy-ness comes from the
            // store's own flag — the flush Worker, the force-sync orchestrator
            // — no `finally` of this scheduler will ever run to consume the
            // latch, and the trigger would be dropped until some unrelated edit
            // came along. Arming the timer here is what that missing finally
            // would have done; it re-checks in one debounce and costs nothing
            // while the external syncer is still working.
            if (!syncInFlight) armDebounce()
            return
        }

        syncInFlight = true
        debugLog?.log(logTag, "sync triggered", buildJsonObject {
            put("trigger", trigger)
            put("retryAttempt", retryAttempt)
        })

        try {
            val result = syncFn()
            when (classifySyncOutcome(result)) {
                SyncOutcome.RESET -> {
                    retryAttempt = 0
                    cancelRetryTimer()
                }
                // Not an error, nothing to reset or retry. The finally still
                // runs, as it does for the JS `return` inside its try.
                SyncOutcome.SKIP -> return
                // ERROR is only classified when an error is present.
                SyncOutcome.ERROR -> handleError(requireNotNull(result.error))
                SyncOutcome.RETRY -> scheduleRetry()
            }
        } catch (cancellation: CancellationException) {
            // Never classified, never retried: cancellation is not a failure.
            throw cancellation
        } catch (error: Throwable) {
            handleError(error)
        } finally {
            syncInFlight = false
            if (pendingSync || hasDirtyDataQuietly()) {
                pendingSync = false
                armDebounce()
            }
        }
    }

    private fun handleError(error: Throwable) {
        val networkError = isNetworkError(error)
        // The class name, never `message`: a Ktor ResponseException carries the
        // whole response body in its message, and this log is shareable.
        debugLog?.log(logTag, "sync error", buildJsonObject {
            put("errorType", if (networkError) "network" else "server")
            put("error", error::class.simpleName ?: "error")
        })
        // Network errors stay silent — the status indicator already showed it.
        if (!networkError) onServerError(error)
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (stopped) return
        cancelRetryTimer()
        val delayMs = computeRetryDelay(retryAttempt, baseRetryMs, maxRetryMs)
        retryAttempt++
        debugLog?.log(logTag, "retry scheduled", buildJsonObject {
            put("delay", delayMs)
            put("attempt", retryAttempt)
        })
        retryJob = control.launch {
            delay(delayMs)
            executeSync(TRIGGER_RETRY)
        }
    }

    private suspend fun pollOnce() {
        if (!isOnline() || isSyncing() || syncInFlight) return

        val check = pollCheckFn
        if (check != null) {
            val shouldSync = try {
                check()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Poll check failed — skip this cycle silently. Not a sync
                // failure: no error surfaced, no retry armed.
                return
            }
            if (!shouldSync) return
        }

        executeSync(TRIGGER_POLL)
    }

    /**
     * A dirty-data probe that throws would escape the `finally` and take the
     * whole app scope with it. Failing closed just delays the next upload to
     * the following trigger.
     */
    private suspend fun hasDirtyDataQuietly(): Boolean = try {
        hasDirtyData()
    } catch (_: Throwable) {
        false
    }

    private fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
    }

    private fun cancelRetryTimer() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun cancelPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    companion object {
        /**
         * The pull gesture, and the only trigger name with a caller outside this
         * class. The other three describe timers nobody else can start, so they
         * stay private rather than widening the public surface for symmetry.
         */
        const val TRIGGER_PULL = "pull"

        private const val TRIGGER_DEBOUNCE = "debounce"
        private const val TRIGGER_REQUEST = "request"
        private const val TRIGGER_RETRY = "retry"
        private const val TRIGGER_POLL = "poll"
    }
}
