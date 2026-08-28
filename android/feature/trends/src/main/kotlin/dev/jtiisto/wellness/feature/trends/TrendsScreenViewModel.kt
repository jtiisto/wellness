package dev.jtiisto.wellness.feature.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.trends.FetchResult
import dev.jtiisto.wellness.core.data.trends.GarminSyncStatus
import dev.jtiisto.wellness.core.data.trends.GarminSyncTrigger
import dev.jtiisto.wellness.core.data.trends.TrendsPrefs
import dev.jtiisto.wellness.core.data.trends.TrendsRepository
import dev.jtiisto.wellness.core.data.trends.describeFetchError
import dev.jtiisto.wellness.feature.trends.chart.rangeStart
import dev.jtiisto.wellness.feature.trends.chart.resolveSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * What the five Trends screens share: they fetch only while they are the screen
 * being looked at, they do not fetch twice for the same question, and a pull
 * refetches everything while asking the server for fresh Garmin data.
 *
 * The pairing of [onActive]/[onInactive] with a per-screen "already loaded"
 * key is what makes a rotation free. Leaving the composition cancels the
 * collector, but a load that had already *finished* leaves its key behind, so
 * coming straight back finds the answer already in the ViewModel and asks
 * nobody. A load cancelled mid-flight leaves no key, and is retried — which is
 * also correct, because its slice is still showing Loading.
 *
 * Every field below is read and written only from `viewModelScope`, which is
 * the main dispatcher — the same confinement argument [selectionGeneration]
 * already rests on, and what lets the load bookkeeping be a plain
 * read-modify-write.
 */
abstract class TrendsScreenViewModel(
    protected val repository: TrendsRepository,
    protected val prefs: TrendsPrefs,
    private val debugLog: DebugLog,
    protected val today: () -> LocalDate,
) : ViewModel() {

    private var job: Job? = null
    private var refreshJob: Job? = null
    private var pollJob: Job? = null
    private var completionJob: Job? = null

    /**
     * The screen's fetch bookkeeping: how many [loadSlice] calls have ever
     * started, and how many of those have not finished.
     *
     * The spinner cannot key off [collect] finishing, because it never does —
     * every screen ends in an infinite `collectLatest` over the range. This is
     * the thing that *does* settle.
     *
     * [started] is monotonic and is what makes waiting on it correct rather than
     * merely usually correct. A `StateFlow` conflates, so a refetch that begins
     * and completes between two turns of the main dispatcher shows an observer
     * nothing but the 0 it started from — waiting for the count to *rise* and
     * then fall would hang until the cap on exactly the case that matters most,
     * a fully cached screen. Comparing against a start count taken beforehand
     * asks a question the current value can always answer.
     */
    private data class SliceLoads(val started: Long = 0, val inFlight: Int = 0)

    private val loads = MutableStateFlow(SliceLoads())

    /**
     * Set for the duration of one refresh: [loadSlice] keeps what is on screen
     * instead of blanking it to Loading.
     */
    private var keepValues = false

    private val _isRefreshing = MutableStateFlow(false)

    /** The pull spinner. Covers phase one only; phase two speaks through [syncBanner]. */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _syncBanner = MutableStateFlow<String?>(null)

    /** What the server-side Garmin sync is doing, in one caption's worth of words. */
    val syncBanner: StateFlow<String?> = _syncBanner.asStateFlow()

    /** The screen came into view. Idempotent: a second call while running is a no-op. */
    fun onActive() {
        if (job?.isActive == true) return
        job = viewModelScope.launch { collect() }
    }

    /**
     * The screen went away. Structured concurrency cancels everything below —
     * and this is the **only** thing that stops a Garmin watch.
     *
     * That the watch dies with the screen is accepted rather than solved: it
     * lives in this ViewModel's scope, and switching sub-screens mid-sync
     * abandons this screen's poll and its banner. The data still lands on the
     * server; the next pull on whichever screen the user is looking at picks it
     * up. See `specs/trends.md`.
     */
    fun onInactive() {
        job?.cancel()
        job = null
        refreshJob?.cancel()
        refreshJob = null
        pollJob?.cancel()
        pollJob = null
        // The watch's completion refetch is tracked for exactly this line: an
        // untracked sibling could outlive the screen and relaunch the collector
        // on a disposed surface (review HIGH).
        completionJob?.cancel()
        completionJob = null
        keepValues = false
        _isRefreshing.value = false
        _syncBanner.value = null
    }

    /**
     * Re-run every fetch on this screen, short-circuit and all — the way out of
     * an error state, which by design survives a screen switch.
     *
     * Cycles the collector directly rather than going through
     * [onInactive]/[onActive]: those now tear down a running Garmin watch, and a
     * Retry tap on one failed card has no business ending a sync the user asked
     * for a moment ago.
     */
    fun retry() {
        forgetLoaded()
        cycleCollect()
    }

    /** Collect the screen's key streams. Cancelled wholesale by [onInactive]. */
    protected abstract suspend fun collect()

    /** Drop every "already loaded" key so [retry] fetches again. */
    protected abstract fun forgetLoaded()

    /**
     * Run one fetch into one slice, and say whether it settled.
     *
     * Every failure lands in the slice rather than propagating, so screens with
     * several slices degrade card by card. Cancellation is the one thing that
     * passes through: a superseded request has no business writing a state.
     */
    protected suspend fun <T> loadSlice(
        name: String,
        assign: (Slice<T>) -> Unit,
        fetch: suspend () -> FetchResult<T>,
    ): Boolean {
        // A refresh keeps the answer that is already drawn; every other load
        // blanks, so a slice is never rendered under a toolbar state it does not
        // match. Blanking on a pull is what made the charts flash empty for the
        // length of a round trip.
        if (!keepValues) assign(Slice.Loading)
        loads.update { it.copy(started = it.started + 1, inFlight = it.inFlight + 1) }
        return try {
            val result = fetch()
            assign(Slice.Ready(result.value, result.staleFetchedAt))
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val described = describeFetchError(error)
            debugLog.log(TAG, "$name failed: $described")
            assign(Slice.Error(described))
            true
        } finally {
            // Decrement only. keepValues is cleared by [refetchKeepingValues]
            // once the WHOLE cycle settles — clearing it here on a transient
            // zero blanked and un-spun a dependent second wave (Strength's
            // detail after its list) before it ever started (review finding).
            loads.update { it.copy(inFlight = it.inFlight - 1) }
        }
    }

    // ---- pull to refresh ----------------------------------------------------

    /**
     * The pull gesture: refetch this screen, and ask the server for fresh Garmin
     * data while we are at it.
     *
     * **Phase one** — the trigger POST and a full refetch, run together. The
     * spinner belongs to the refetch: the POST only starts a subprocess on the
     * server, and holding a spinner over a fifteen-second garmy run would be a
     * lie about what the user is waiting for. A minimum visible time keeps a
     * cached no-op from reading as "nothing happened".
     *
     * **Phase two** — only when the server says it started one (or was already
     * running): watch until it finishes, say so in [syncBanner], then quietly
     * refetch again so the new data lands in place. Cooldown, unconfigured, and
     * an unreachable server all end the gesture at phase one.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _isRefreshing.value = true
            // Fired alongside the refetch, not before it: the POST is bounded
            // only by the client's request timeout, and a slow server must not
            // hold the spinner over data that is already back.
            val trigger = async { triggerGarminSync() }
            // A timer started with the spinner and joined before it is taken
            // away — no clock to inject, and it advances on virtual time.
            val floor = launch { delay(MIN_VISIBLE_MS) }
            refetchKeepingValues()
            floor.join()
            _isRefreshing.value = false
            when (trigger.await()?.status) {
                STATUS_STARTED, STATUS_RUNNING -> watchGarminSync()
                else -> Unit
            }
        }
    }

    /**
     * Refetch every slice without blanking any of them, and wait for the last
     * one to land.
     *
     * The ordering is the whole correctness of [keepValues], and it is not
     * interchangeable. `cancelAndJoin` — not `cancel` — because cancellation is
     * only *marked* synchronously: the cancelled [loadSlice] bodies run their
     * `finally` on a later main-dispatcher turn, which would drain the counter
     * to zero and clear a flag raised before them, and the new cycle would then
     * blank every slice. Joining drains them first, so the flag is raised on a
     * counter that is provably zero.
     */
    private suspend fun refetchKeepingValues() {
        // A disposed screen must not be revived by a completion refetch that
        // slipped past its cancellation point: no collector, no refetch.
        if (job?.isActive != true) return
        job?.cancelAndJoin()
        val startedBefore = loads.value.started
        keepValues = true
        forgetLoaded()
        job = viewModelScope.launch { collect() }
        try {
            awaitSlicesSettled(startedBefore)
        } finally {
            // Cleared HERE, after the whole cycle (all waves) settles — not in
            // loadSlice on a transient zero. The finally keeps the invariant a
            // later range change depends on: outside a refresh, blanking runs.
            keepValues = false
        }
    }

    /**
     * Wait for this refetch's fetches to start and then finish, or give up and
     * stop spinning. Asked against a start count taken before the cycle, so a
     * refetch that is over before this even runs answers immediately instead of
     * waiting out the cap. A zero is believed only after it survives a short
     * quiet window: a dependent second wave (Strength's detail fetch, launched
     * only once its list landed) starts across exactly such a gap, and ending
     * the spinner on the first zero blanked it (review finding).
     */
    private suspend fun awaitSlicesSettled(startedBefore: Long) {
        withTimeoutOrNull(SETTLE_CAP_MS) {
            var seen = startedBefore
            while (true) {
                seen = loads.first { it.started > seen && it.inFlight == 0 }.started
                delay(SETTLE_QUIET_MS)
                val now = loads.value
                if (now.started == seen && now.inFlight == 0) break
            }
        }
    }

    /**
     * Phase two, in the ViewModel's own scope rather than the refresh's.
     *
     * A sibling, so [refreshJob] completes as soon as the spinner does and a
     * second pull is not refused for the length of a garmy run. Only
     * [onInactive] cancels this.
     */
    private fun watchGarminSync() {
        _syncBanner.value = BANNER_SYNCING
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val outcome = pollUntilIdle()
            // Launched beside the poll rather than awaited inside it: the
            // refetch cycles the collect job and waits on the counter, and none
            // of that work belongs to the watch's lifetime. TRACKED, so
            // onInactive can cancel it — an orphan here could relaunch the
            // collector on a disposed screen (review HIGH).
            completionJob = viewModelScope.launch { refetchKeepingValues() }
            if (outcome == OUTCOME_FAILED) {
                _syncBanner.value = BANNER_FAILED
                delay(FAILED_BANNER_MS)
            }
            _syncBanner.value = null
        }
    }

    /**
     * Poll until the server says it is idle, or until the cap.
     *
     * Returns the outcome the server reported, or null if it never stopped
     * running inside the cap — the refetch happens either way, because a sync
     * that outran the cap has probably still written something.
     */
    private suspend fun pollUntilIdle(): String? {
        var waited = 0L
        while (waited < POLL_CAP_MS) {
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
            // A failed poll skips its cycle rather than ending the watch: one
            // dropped request says nothing about the sync.
            val status = garminStatusQuietly() ?: continue
            if (!status.running) return status.lastOutcome
        }
        return null
    }

    private suspend fun triggerGarminSync(): GarminSyncTrigger? = try {
        repository.garminSyncTrigger()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        // Described, never `error.message`: a Ktor response exception's message
        // is the whole response body, and this log is shareable.
        debugLog.log(TAG, "garmin sync trigger failed: ${describeFetchError(error)}")
        null
    }

    private suspend fun garminStatusQuietly(): GarminSyncStatus? = try {
        repository.garminSyncStatus()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        debugLog.log(TAG, "garmin sync status failed: ${describeFetchError(error)}")
        null
    }

    private fun cycleCollect() {
        job?.cancel()
        job = viewModelScope.launch { collect() }
    }

    // ---- picker selection --------------------------------------------------

    /**
     * What this screen's picker has settled on.
     *
     * This flow leads, and the stored preference follows it — never the other
     * way around. A fallback computed from a list that has only just arrived
     * has to reconcile against what the user tapped a moment ago, and storage
     * may not have caught up yet: re-reading the preference at that moment is
     * precisely how an older fallback comes to overwrite a newer choice.
     *
     * The state is confined to the main dispatcher — `viewModelScope` and the
     * UI both run there — so [selectionGeneration] needs no synchronization of
     * its own. [selectionMutex] orders the *writes*, and the generation check
     * inside it is what turns a superseded write into a no-op instead of
     * queueing it behind the newer one.
     */
    protected val selection = MutableStateFlow<String?>(null)

    private val selectionMutex = Mutex()
    private var selectionGeneration = 0L

    /**
     * Adopt the remembered choice before anything is fetched, so the first
     * frame shows the picker the user left it on. Never writes: nothing has
     * been decided here that storage does not already know.
     *
     * Only for a selection that does **not** drive a fetch. Arming a fetcher
     * with a remembered id before the list that would confirm it exists has
     * arrived is how a deleted exercise gets requested, and a 404 is not a
     * fallback.
     */
    protected suspend fun seedSelection(persisted: suspend () -> String?) {
        if (selection.value != null) return
        val remembered = persisted() ?: return
        // The read suspended; a tap during it is newer than what came back.
        if (selection.value == null) selection.value = remembered
    }

    /** The user picked [id]. */
    protected fun select(id: String, persist: suspend (String) -> Unit) {
        val generation = ++selectionGeneration
        selection.value = id
        viewModelScope.launch { commitSelection(id, generation, persist) }
    }

    /**
     * Settle the selection against a list that has just arrived, and report
     * what it settled on.
     *
     * [persisted] is consulted only when nothing has been chosen yet this
     * session, and the answer is re-checked afterwards for the same reason
     * [seedSelection] re-checks: the read suspends. A selection that is still
     * in the list is left exactly as it is, so the common case writes nothing.
     */
    protected suspend fun reconcileSelection(
        available: List<String>,
        persisted: suspend () -> String?,
        persist: suspend (String) -> Unit,
    ): String? {
        val current = selection.value ?: persisted()
        val latest = selection.value ?: current
        val resolved = resolveSelection(latest, available)
        if (resolved == selection.value) return resolved
        val generation = ++selectionGeneration
        selection.value = resolved
        if (resolved != null) commitSelection(resolved, generation, persist)
        return resolved
    }

    /** Write [id], unless a newer choice has been made since this one was decided. */
    private suspend fun commitSelection(
        id: String,
        generation: Long,
        persist: suspend (String) -> Unit,
    ) {
        selectionMutex.withLock {
            if (generation != selectionGeneration) return
            persist(id)
        }
    }

    /** The query window for [range], measured against the **device's** today. */
    protected fun window(range: String): Window {
        val end = today().toString()
        return Window(start = rangeStart(range, end), end = end)
    }

    /** A `start` (absent for All) and the `end` the client always sends. */
    data class Window(val start: DateString?, val end: DateString)

    companion object {
        const val TAG = "trends"

        /** What the banner says while the server is syncing Garmin, and when it failed. */
        const val BANNER_SYNCING = "syncing Garmin…"
        const val BANNER_FAILED = "Garmin sync failed"

        /** The trigger statuses that mean a sync is actually happening. */
        private const val STATUS_STARTED = "started"
        private const val STATUS_RUNNING = "running"
        private const val OUTCOME_FAILED = "failed"

        /** A no-op refresh still has to read as an answer rather than as nothing. */
        private const val MIN_VISIBLE_MS = 500L

        /** The spinner gives up long before the requests do; they carry on landing. */
        private const val SETTLE_CAP_MS = 15_000L

        /** How long a zero must hold before it counts as settled — the gap a
         *  dependent second wave launches across. */
        private const val SETTLE_QUIET_MS = 200L

        private const val POLL_INTERVAL_MS = 3_000L
        private const val POLL_CAP_MS = 60_000L
        private const val FAILED_BANNER_MS = 6_000L
    }
}
