package dev.jtiisto.wellness.feature.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.trends.TrendsPrefs
import dev.jtiisto.wellness.core.data.trends.TrendsRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.time.LocalDate

/**
 * The Trends tab's ViewModels.
 *
 * All five follow the same shape: a `_state` the screen renders, one or two key
 * streams collected with `collectLatest` so a new range cancels the work the
 * old one started, and a "loaded" key per independent fetch. Nothing here
 * decides what a chart looks like — that is the pure builders' job — so these
 * stay about *when* to ask and *what to do when the answer does not come*.
 */

/** The toolbar's own state: which range, which sub-screen. */
class TrendsShellViewModel(private val prefs: TrendsPrefs) : ViewModel() {

    val uiState: StateFlow<TrendsShellState> =
        combine(prefs.range, prefs.screen) { range, screen -> TrendsShellState(range, screen) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TrendsShellState())

    fun setRange(id: String) {
        viewModelScope.launch { prefs.setRange(id) }
    }

    fun setScreen(id: String) {
        viewModelScope.launch { prefs.setScreen(id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

// ---- Overview --------------------------------------------------------------

/**
 * Headline tiles plus the weight chart.
 *
 * `/overview` takes no parameters — the server evaluates it against its own
 * calendar — so it is fetched exactly once and survives every range change,
 * while weight refetches per range. The two are independent: a weight failure
 * costs its card, and the tiles carry on.
 */
class OverviewViewModel(
    private val repository: TrendsRepository,
    prefs: TrendsPrefs,
    debugLog: DebugLog,
    today: () -> LocalDate = LocalDate::now,
) : TrendsScreenViewModel(prefs, debugLog, today) {

    private val _state = MutableStateFlow(OverviewUiState())
    val uiState: StateFlow<OverviewUiState> = _state.asStateFlow()

    private var loadedOverview = false
    private var loadedWeightRange: String? = null

    override fun forgetLoaded() {
        loadedOverview = false
        loadedWeightRange = null
    }

    override suspend fun collect() = coroutineScope {
        launch {
            if (!loadedOverview) {
                loadedOverview = loadSlice(
                    name = "overview",
                    assign = { slice -> _state.update { it.copy(overview = slice) } },
                    fetch = { repository.overview() },
                )
            }
        }
        launch {
            prefs.range.distinctUntilChanged().collectLatest { range ->
                _state.update { it.copy(range = range) }
                if (range == loadedWeightRange) return@collectLatest
                loadedWeightRange = null
                val window = window(range)
                val settled = loadSlice(
                    name = "weight",
                    assign = { slice -> _state.update { it.copy(weight = slice) } },
                    fetch = { repository.weight(window.start, window.end, range) },
                )
                if (settled) loadedWeightRange = range
            }
        }
        Unit
    }
}

// ---- Strength --------------------------------------------------------------

/**
 * Exercise progression, weekly volume, and the record board.
 *
 * The exercise list and the volume chart move with the range; the progression
 * chart moves with the range *and* the picked exercise, and which exercise that
 * is can only be settled once the list has arrived.
 */
class StrengthViewModel(
    private val repository: TrendsRepository,
    prefs: TrendsPrefs,
    debugLog: DebugLog,
    today: () -> LocalDate = LocalDate::now,
) : TrendsScreenViewModel(prefs, debugLog, today) {

    private val _state = MutableStateFlow(StrengthUiState())
    val uiState: StateFlow<StrengthUiState> = _state.asStateFlow()

    private var loadedListsRange: String? = null
    private var loadedDetailKey: Pair<String, String>? = null

    override fun forgetLoaded() {
        loadedListsRange = null
        loadedDetailKey = null
    }

    fun selectExercise(slug: String) {
        _state.update { it.copy(selected = slug) }
        select(slug, prefs::setExercise)
    }

    fun toggleRpe() {
        _state.update { it.copy(showRpe = !it.showRpe) }
    }

    override suspend fun collect() = coroutineScope {
        launch {
            prefs.range.distinctUntilChanged().collectLatest { range ->
                _state.update { it.copy(range = range) }
                if (range == loadedListsRange) return@collectLatest
                loadedListsRange = null
                loadLists(range)
            }
        }
        launch {
            combine(prefs.range.distinctUntilChanged(), selection) { range, slug -> range to slug }
                .distinctUntilChanged()
                .collectLatest { (range, slug) ->
                    if (slug == null) return@collectLatest
                    val key = range to slug
                    if (key == loadedDetailKey) return@collectLatest
                    loadedDetailKey = null
                    val window = window(range)
                    val settled = loadSlice(
                        name = "strength/$slug",
                        assign = { slice -> _state.update { it.copy(detail = slice) } },
                        fetch = { repository.strengthExercise(slug, window.start, window.end, range) },
                    )
                    if (settled) loadedDetailKey = key
                }
        }
        Unit
    }

    private suspend fun loadLists(range: String) {
        val window = window(range)
        supervisorScope {
            launch {
                loadSlice(
                    name = "strength/exercises",
                    assign = { slice ->
                        _state.update { it.copy(exercises = slice.map { dto -> dto.exercises }) }
                    },
                    fetch = { repository.strengthExercises(window.start, window.end, range) },
                )
            }
            launch {
                loadSlice(
                    name = "volume",
                    assign = { slice -> _state.update { it.copy(volume = slice) } },
                    fetch = { repository.strengthVolume(window.start, window.end, range) },
                )
            }
        }
        applySelection(_state.value.exercises.valueOrNull.orEmpty().map { it.slug })
        loadedListsRange = range
    }

    /**
     * Settle which exercise the progression chart shows.
     *
     * A remembered slug that is no longer in the list falls back to the first
     * one silently. An *empty* list clears the selection instead of keeping the
     * stale slug: there is no first item to fall back to, and asking the server
     * for the missing one would turn an empty range into an error banner.
     *
     * This runs after the *volume* slice settles too, which is well after the
     * picker became tappable — so it reconciles against [selection], never
     * against a re-read of storage. See [reconcileSelection].
     */
    private suspend fun applySelection(available: List<String>) {
        val resolved = reconcileSelection(available, { prefs.exercise.first() }, prefs::setExercise)
        _state.update { it.copy(selected = resolved) }
    }
}

// ---- Cardio ----------------------------------------------------------------

/** One payload carries both cards, so this screen has a single slice. */
class CardioViewModel(
    private val repository: TrendsRepository,
    prefs: TrendsPrefs,
    debugLog: DebugLog,
    today: () -> LocalDate = LocalDate::now,
) : TrendsScreenViewModel(prefs, debugLog, today) {

    private val _state = MutableStateFlow(CardioUiState())
    val uiState: StateFlow<CardioUiState> = _state.asStateFlow()

    private var loadedRange: String? = null

    override fun forgetLoaded() {
        loadedRange = null
    }

    override suspend fun collect() {
        prefs.range.distinctUntilChanged().collectLatest { range ->
            _state.update { it.copy(range = range) }
            if (range == loadedRange) return@collectLatest
            loadedRange = null
            val window = window(range)
            val settled = loadSlice(
                name = "cardio",
                assign = { slice -> _state.update { it.copy(cardio = slice) } },
                fetch = { repository.cardio(window.start, window.end, range) },
            )
            if (settled) loadedRange = range
        }
    }
}

// ---- Journal ---------------------------------------------------------------

/**
 * A tracker's values, adherence and usage.
 *
 * The tracker list is range-independent — it describes what exists, not what
 * happened in a window — so it is fetched once, like Overview's tiles.
 */
class JournalTrendsViewModel(
    private val repository: TrendsRepository,
    prefs: TrendsPrefs,
    debugLog: DebugLog,
    today: () -> LocalDate = LocalDate::now,
) : TrendsScreenViewModel(prefs, debugLog, today) {

    private val _state = MutableStateFlow(JournalTrendsUiState())
    val uiState: StateFlow<JournalTrendsUiState> = _state.asStateFlow()

    private var loadedTrackers = false
    private var loadedDetailKey: Pair<String, String>? = null

    override fun forgetLoaded() {
        loadedTrackers = false
        loadedDetailKey = null
    }

    fun selectTracker(id: String) {
        _state.update { it.copy(selected = id) }
        select(id, prefs::setTracker)
    }

    override suspend fun collect() = coroutineScope {
        launch {
            if (!loadedTrackers) {
                val settled = loadSlice(
                    name = "journal/trackers",
                    assign = { slice ->
                        _state.update { it.copy(trackers = slice.map { dto -> dto.trackers }) }
                    },
                    fetch = { repository.journalTrackers() },
                )
                applySelection(_state.value.trackers.valueOrNull.orEmpty().map { it.id })
                loadedTrackers = settled
            }
        }
        launch {
            combine(prefs.range.distinctUntilChanged(), selection) { range, id -> range to id }
                .distinctUntilChanged()
                .collectLatest { (range, id) ->
                    _state.update { it.copy(range = range) }
                    if (id == null) return@collectLatest
                    val key = range to id
                    if (key == loadedDetailKey) return@collectLatest
                    loadedDetailKey = null
                    val window = window(range)
                    val settled = loadSlice(
                        name = "journal/$id",
                        assign = { slice -> _state.update { it.copy(detail = slice) } },
                        fetch = { repository.journalTracker(id, window.start, window.end, range) },
                    )
                    if (settled) loadedDetailKey = key
                }
        }
        Unit
    }

    private suspend fun applySelection(available: List<String>) {
        val resolved = reconcileSelection(available, { prefs.tracker.first() }, prefs::setTracker)
        _state.update { it.copy(selected = resolved) }
    }
}

// ---- Health ----------------------------------------------------------------

/**
 * Five independent sources on one screen, and only one of them is load-bearing.
 *
 * Recovery is the screen; the sleep ledger, weight, composition and labs are
 * extras that hide when their source is unavailable, which is the normal state
 * for a phone that has never synced a DEXA scan — or, for the ledger, for any
 * machine without the fitted parameters. Only recovery's failure becomes a
 * visible error.
 */
class HealthViewModel(
    private val repository: TrendsRepository,
    prefs: TrendsPrefs,
    debugLog: DebugLog,
    today: () -> LocalDate = LocalDate::now,
) : TrendsScreenViewModel(prefs, debugLog, today) {

    private val _state = MutableStateFlow(HealthUiState())
    val uiState: StateFlow<HealthUiState> = _state.asStateFlow()

    // One key per slice, because the five do not all move with the range.
    // Composition and labs are end-only requests: a range change does not
    // change what was asked for, so it must not throw away an answer that is
    // already on screen — which is exactly what a refetch would do to a card
    // whose earlier cache write had failed and whose network is now gone.
    private var loadedRecoveryRange: String? = null
    private var loadedSleepKey: String? = null
    private var loadedWeightRange: String? = null
    private var loadedCompositionEnd: String? = null
    private var loadedLabsEnd: String? = null

    override fun forgetLoaded() {
        loadedRecoveryRange = null
        loadedSleepKey = null
        loadedWeightRange = null
        loadedCompositionEnd = null
        loadedLabsEnd = null
    }

    fun selectLabPanel(name: String) {
        _state.update { it.copy(labPanel = name) }
        select(name, prefs::setLabPanel)
    }

    override suspend fun collect() {
        seedSelection { prefs.labPanel.first() }
        _state.update { it.copy(labPanel = selection.value) }
        prefs.range.distinctUntilChanged().collectLatest { range ->
            _state.update { it.copy(range = range) }
            loadAll(range)
        }
    }

    private suspend fun loadAll(range: String) {
        val window = window(range)
        supervisorScope {
            if (range != loadedRecoveryRange) {
                launch {
                    val settled = loadSlice(
                        name = "health/recovery",
                        assign = { slice -> _state.update { it.copy(recovery = slice) } },
                        fetch = { repository.healthRecovery(window.start, window.end, range) },
                    )
                    if (settled) loadedRecoveryRange = range
                }
            }
            // Keyed by range AND end date, unlike the sibling slices: the
            // payload's `tonight` is a fact about a calendar day, so a retained
            // ViewModel reactivated tomorrow must refetch even at the same
            // range — recovery can afford yesterday's copy, tonight cannot.
            val sleepKey = "$range|${window.end}"
            if (sleepKey != loadedSleepKey) {
                launch {
                    // The ledger is range-keyed like recovery even though the
                    // arithmetic behind it is not: `start` clips the rows that
                    // come back, and the history panel is those rows.
                    val settled = loadSlice(
                        name = "health/sleep",
                        assign = { slice -> _state.update { it.copy(sleep = slice) } },
                        fetch = { repository.healthSleep(window.start, window.end, range) },
                    )
                    if (settled) loadedSleepKey = sleepKey
                }
            }
            if (range != loadedWeightRange) {
                launch {
                    val settled = loadSlice(
                        name = "weight",
                        assign = { slice -> _state.update { it.copy(weight = slice) } },
                        fetch = { repository.weight(window.start, window.end, range) },
                    )
                    if (settled) loadedWeightRange = range
                }
            }
            if (window.end != loadedCompositionEnd) {
                launch {
                    val settled = loadSlice(
                        name = "health/composition",
                        assign = { slice -> _state.update { it.copy(composition = slice) } },
                        fetch = { repository.healthComposition(window.end) },
                    )
                    if (settled) loadedCompositionEnd = window.end
                }
            }
            if (window.end != loadedLabsEnd) {
                launch {
                    val settled = loadSlice(
                        name = "health/labs",
                        assign = { slice -> _state.update { it.copy(labs = slice) } },
                        fetch = { repository.healthLabs(window.end) },
                    )
                    applyLabPanel(_state.value.labs.valueOrNull?.panels.orEmpty().map { it.name })
                    if (settled) loadedLabsEnd = window.end
                }
            }
        }
    }

    /**
     * Settle which lab panel is shown, and remember it.
     *
     * The composable resolves a fallback of its own so the first frame is never
     * blank, but only this write makes the choice survive a restart — without
     * it a panel that has since disappeared would sit in storage forever, ready
     * to steal the selection back the day it reappears.
     */
    private suspend fun applyLabPanel(available: List<String>) {
        // No panels is not the same question as "your panel is gone". Labs may
        // simply be unavailable on this install, and the section is not drawn at
        // all — so there is nothing to reconcile, and forgetting the remembered
        // panel would only lose it for the day the reports arrive. Strength and
        // Journal clear on empty because they would otherwise go on asking the
        // server for an exercise that is not there; nothing is fetched here.
        if (available.isEmpty()) return
        val resolved = reconcileSelection(available, { prefs.labPanel.first() }, prefs::setLabPanel)
        _state.update { it.copy(labPanel = resolved) }
    }
}

/** Map a ready slice's payload, leaving loading and error states alone. */
private fun <T, R> Slice<T>.map(transform: (T) -> R): Slice<R> = when (this) {
    is Slice.Ready<T> -> Slice.Ready(transform(value), staleFetchedAt)
    is Slice.Error -> this
    Slice.Loading -> Slice.Loading
}
