package dev.jtiisto.wellness.core.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.await
import dev.jtiisto.wellness.core.data.coach.CoachSyncStore
import dev.jtiisto.wellness.core.data.journal.JournalSyncStore
import dev.jtiisto.wellness.core.data.network.ServerBootstrap
import dev.jtiisto.wellness.core.data.network.ServerResolution
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Decides whether backgrounding is worth a Worker.
 *
 * Pure of Android so the rule itself is testable: enqueue **only** when
 * something is actually waiting to go out. An unconditional enqueue on every
 * backgrounding would wake the process on a schedule the user's battery pays
 * for, to discover there was nothing to send.
 */
class SyncFlushScheduler(
    private val hasDirtyData: suspend () -> Boolean,
    private val enqueue: () -> Unit,
) {
    /**
     * A dirty probe that throws must not propagate: this runs on the
     * orchestrator's control dispatcher, where an exception would take the app
     * scope with it. Failing closed costs one deferred flush, and the next
     * foreground sync makes it anyway.
     */
    suspend fun onBackgrounded() {
        val dirty = try {
            hasDirtyData()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
        if (dirty) enqueue()
    }
}

/**
 * The durable half of sync: one flush of both modules after the app has gone
 * away, so an edit made just before the process dies is not stranded until the
 * next launch.
 *
 * This was in the plan from the beginning and was never built — process-lifetime
 * scheduling covered everything *except* the case it was written for.
 *
 * **One combined Worker, one unique name.** Two per-module Workers would double
 * the wakeups to halve the work each time, and the two modules are dirty at the
 * same moments in practice (a session logged in Coach usually comes with journal
 * entries). `ExistingWorkPolicy.KEEP` is the dedup: repeated backgroundings
 * while one is already pending change nothing.
 *
 * It runs the **ordinary** `triggerSync()`, never a force cycle: this is a
 * flush, not a reconciliation, and each store's `tryLock()` makes it safe
 * against a foreground sync that started first — the racing call skips rather
 * than interleaving, and skipping is a success here because the sync it would
 * have done is already happening.
 */
class SyncFlushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val bootstrap: ServerBootstrap by inject()
    private val gate: ServerSessionGate by inject()
    private val coach: CoachSyncStore by inject()
    private val journal: JournalSyncStore by inject()

    override suspend fun doWork(): Result {
        // Neither is retryable by waiting. An unresolved server means the app is
        // sitting on its recovery screen; a closed gate means a switch has been
        // confirmed and this process is finishing.
        if (bootstrap.state.value !is ServerResolution.Resolved) return Result.failure()
        if (!gate.isOpen) return Result.failure()

        // The whole cycle runs under a write lease, so a switch confirmed while
        // the Worker is mid-flush waits for it rather than wiping underneath it.
        // Bounded by the HTTP client's timeouts; a nested lease taken after the
        // close is refused, which unwinds this one and fails the flush safely.
        val results = try {
            gate.withWriteLease { listOf(coach.triggerSync(), journal.triggerSync()) }
        } catch (_: ServerSessionClosedException) {
            return Result.failure()
        }
        return if (needsRetry(results)) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "wellness-sync-flush"

        /**
         * Whether the flush is worth another attempt.
         *
         * The two skips are **not** the same answer, and treating them alike
         * abandoned the only durable retry this app has. `ALREADY_SYNCING`
         * means a foreground sync won the `tryLock()` race and is doing the
         * work right now, so there is nothing to retry. `OFFLINE` means the
         * opposite: WorkManager's `CONNECTED` constraint is satisfied by a
         * captive or unvalidated Wi-Fi association, where this app's
         * validated-internet check correctly reports offline — so the Worker
         * wakes, does nothing, and would report success while the dirty rows
         * sit waiting for the user to open the app again. That earns a backoff.
         */
        fun needsRetry(results: List<SyncResult>): Boolean =
            results.any { it.error != null || it.reason == SyncSkipReason.OFFLINE }

        /**
         * `WorkManager.getInstance` throws when the process never initialized
         * it. Every caller here treats that as "there is no background work",
         * which is exactly true.
         */
        fun instanceOrNull(context: Context): WorkManager? = try {
            WorkManager.getInstance(context)
        } catch (_: IllegalStateException) {
            null
        }

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncFlushWorker>()
                .setConstraints(
                    // The whole job is a network call; running without one would
                    // burn a wakeup to fail.
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()
            instanceOrNull(context)?.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** The server switch's step 4: cancel, and **wait for it to be cancelled**. */
        fun canceller(context: Context): BackgroundWorkCanceller = BackgroundWorkCanceller {
            instanceOrNull(context)?.cancelUniqueWork(UNIQUE_NAME)?.await()
        }
    }
}
