package dev.jtiisto.wellness.core.data.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Owns when each module's [SyncScheduler] polls, and fans process-level events
 * (foreground, connectivity) out to all of them.
 *
 * Created once at application start — the analog of the PWA initializing every
 * module store at boot. This is **process-resident scheduling only**: process
 * death loses every timer. Dirty rows in Room, not scheduler state, are the
 * recovery source; durable pickup arrives in Phase 2 with the first dirty data.
 */
class SyncOrchestrator(
    private val scope: CoroutineScope,
    private val connectivity: ConnectivityMonitor,
    onBackground: suspend () -> Unit = {},
) {
    private val core = SyncOrchestratorCore(scope, onBackground)
    private var started = false

    /** Late registration is fine: the scheduler is handed current state at once. */
    fun register(scheduler: SyncScheduler) {
        core.register(scheduler)
    }

    /** Idempotent; call from `Application.onCreate` (main thread). */
    fun start() {
        if (started) return
        started = true

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = core.setForeground(true)
                override fun onStop(owner: LifecycleOwner) = core.setForeground(false)
            },
        )

        scope.launch(Dispatchers.Main.immediate) {
            connectivity.isOnline.collect(core::setOnline)
        }
    }
}

/**
 * The orchestrator's state machine, with no Android in it so it can be driven
 * directly by unit tests.
 *
 * One rule decides everything: **polling runs iff foreground && online**.
 * Uploads are not gated that way — a connectivity change flushes dirty data
 * even while backgrounded, matching the PWA, which is why the schedulers live
 * on an app-lived scope.
 *
 * Every transition is serialized on the main dispatcher, so no two events can
 * fan out concurrently and a registration can never race a state change.
 */
internal class SyncOrchestratorCore(
    scope: CoroutineScope,
    private val onBackground: suspend () -> Unit = {},
) {

    private val control = scope + Dispatchers.Main.immediate
    private val schedulers = mutableListOf<SyncScheduler>()
    private var foreground = false
    private var online = false
    private var polling = false

    fun register(scheduler: SyncScheduler) {
        control.launch {
            if (schedulers.contains(scheduler)) return@launch
            schedulers += scheduler
            if (polling) scheduler.startPolling()
        }
    }

    fun setForeground(value: Boolean) {
        control.launch {
            if (foreground == value) return@launch
            foreground = value
            // Coming back to the foreground online: catch up immediately rather
            // than waiting out a poll interval.
            if (value && online) schedulers.forEach(SyncScheduler::requestSync)
            applyPolling()
            // Last, and only on the way out: hand anything still dirty to
            // WorkManager, which is the only part of this that survives the
            // process being killed. Polling is already stopped by here, so the
            // dirty check cannot race a cycle this class just started.
            if (!value) onBackground()
        }
    }

    fun setOnline(value: Boolean) {
        control.launch {
            if (online == value) return@launch
            online = value
            if (value) {
                schedulers.forEach(SyncScheduler::onOnline)
            } else {
                schedulers.forEach(SyncScheduler::onOffline)
            }
            applyPolling()
        }
    }

    private fun applyPolling() {
        val wanted = foreground && online
        if (wanted == polling) return
        polling = wanted
        schedulers.forEach { if (wanted) it.startPolling() else it.stopPolling() }
    }
}
