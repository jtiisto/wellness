package dev.jtiisto.wellness.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.jtiisto.wellness.core.data.network.ServerBootstrap
import dev.jtiisto.wellness.core.data.network.ServerResolution
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import dev.jtiisto.wellness.core.data.sync.SyncFlushWorker
import dev.jtiisto.wellness.core.data.trends.TrendsRepository
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * The only thing that puts *new* sleep and strain data on the home screen.
 *
 * Hourly, `CONNECTED`, one unique job whatever the widget count: the render path
 * is cache-only by design (see [TodayWidget]), so without this the surface would
 * age forever. The tally needs nothing from here — it is read from local Room at
 * every render and is the one element with no staleness story at all.
 *
 * **[TrendsRepository] is resolved inside [doWork], never as a field.** Its
 * `TrendsApi` wants a `ServerConfig`, which is `ServerBootstrap.requireConfig()`
 * and throws before the boot decision has been made; a Worker can be constructed
 * in a process where that has not happened, and [shouldFetch] may decide not to
 * fetch at all. A field would turn "no server yet" into a crash at construction
 * time, which is the exact failure the peeks exist to avoid.
 *
 * See `specs/widget.md` §Refresh model.
 */
class TodayWidgetWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    // Both are pre-resolution-safe and answer the one question asked before any
    // network object is touched.
    private val bootstrap: ServerBootstrap by inject()
    private val gate: ServerSessionGate by inject()

    override suspend fun doWork(): Result {
        val debugLog: DebugLog = get()

        if (shouldFetch(bootstrap.state.value is ServerResolution.Resolved, gate.isOpen)) {
            runCatching {
                val trends: TrendsRepository = get()
                val (start, end) = widgetFetchWindow(LocalDate.now())
                trends.healthSleep(start, end, WIDGET_RANGE)
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                debugLog.log("widget", "sleep fetch failed (${failure.javaClass.simpleName})")
            }
        }

        // Unconditional, and that is the offline story: cached-with-age is what
        // this surface shows when the network is gone, and re-rendering the same
        // copy is how its age advances on screen. A run that fetched nothing
        // still has something to say.
        runCatching { TodayWidget().updateAll(applicationContext) }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                debugLog.log("widget", "re-render failed (${failure.javaClass.simpleName})")
            }

        // Always success, deliberately unlike SyncFlushWorker — whose terminal
        // failures and backoff exist because a one-shot flush carries the user's
        // unsent writes and gets exactly one chance. Nothing here is unsent: the
        // render half already succeeded, the data is the server's and will still
        // be there in an hour, and **the hourly period is the retry**. A
        // `Result.retry()` would stack exponential backoff on top of a schedule
        // that already comes round, and a `Result.failure()` would say a
        // re-rendered widget was a failed run.
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "wellness-today-widget"

        /**
         * Assert the hourly job. `KEEP` is what makes re-asserting free, which
         * is why [TodayWidgetReceiver] may call this on every update without
         * resetting the period or losing the run that was already pending.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TodayWidgetWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    // The fetch is the whole reason to wake; running without a
                    // network would burn a wakeup to log a failure.
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            SyncFlushWorker.instanceOrNull(context)?.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** The last widget is gone: an hourly network job for nobody is a battery bug. */
        fun cancel(context: Context) {
            SyncFlushWorker.instanceOrNull(context)?.cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
