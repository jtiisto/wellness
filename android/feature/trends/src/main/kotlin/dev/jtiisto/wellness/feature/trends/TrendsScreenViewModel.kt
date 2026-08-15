package dev.jtiisto.wellness.feature.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.trends.FetchResult
import dev.jtiisto.wellness.core.data.trends.TrendsPrefs
import dev.jtiisto.wellness.core.data.trends.describeFetchError
import dev.jtiisto.wellness.feature.trends.chart.rangeStart
import dev.jtiisto.wellness.feature.trends.chart.resolveSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * What the five Trends screens share: they fetch only while they are the screen
 * being looked at, and they do not fetch twice for the same question.
 *
 * The pairing of [onActive]/[onInactive] with a per-screen "already loaded"
 * key is what makes a rotation free. Leaving the composition cancels the
 * collector, but a load that had already *finished* leaves its key behind, so
 * coming straight back finds the answer already in the ViewModel and asks
 * nobody. A load cancelled mid-flight leaves no key, and is retried — which is
 * also correct, because its slice is still showing Loading.
 */
abstract class TrendsScreenViewModel(
    protected val prefs: TrendsPrefs,
    private val debugLog: DebugLog,
    protected val today: () -> LocalDate,
) : ViewModel() {

    private var job: Job? = null

    /** The screen came into view. Idempotent: a second call while running is a no-op. */
    fun onActive() {
        if (job?.isActive == true) return
        job = viewModelScope.launch { collect() }
    }

    /** The screen went away. Structured concurrency cancels everything below. */
    fun onInactive() {
        job?.cancel()
        job = null
    }

    /**
     * Re-run every fetch on this screen, short-circuit and all — the way out of
     * an error state, which by design survives a screen switch.
     */
    fun retry() {
        forgetLoaded()
        onInactive()
        onActive()
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
        assign(Slice.Loading)
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
        }
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
    }
}
