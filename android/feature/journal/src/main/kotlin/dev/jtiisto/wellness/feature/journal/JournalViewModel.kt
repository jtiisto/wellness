package dev.jtiisto.wellness.feature.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.data.journal.EntryDto
import dev.jtiisto.wellness.core.data.journal.EntryPatch
import dev.jtiisto.wellness.core.data.journal.JournalSyncStore
import dev.jtiisto.wellness.core.data.journal.JournalUiPrefs
import dev.jtiisto.wellness.core.data.journal.TrackerDto
import dev.jtiisto.wellness.core.data.journal.TrackerType
import dev.jtiisto.wellness.core.data.journal.getLastNDays
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.SyncErrorEvents
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import dev.jtiisto.wellness.core.data.sync.SyncStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * The journal day view.
 *
 * Thin by design: it subscribes, hands the raw state to [buildJournalUiState],
 * and turns taps into [EntryPatch]es via the pure builders in
 * `JournalRowActions`. The only judgment it makes on its own is *when* to read
 * the clock — always at the moment of use, never cached, because this screen
 * can sit open across midnight and a stale "today" would file an edit under
 * yesterday.
 */
class JournalViewModel(
    private val store: JournalSyncStore,
    private val prefs: JournalUiPrefs,
    private val scheduler: SyncScheduler,
    /**
     * Read at the moment of the pull, not observed.
     *
     * A lambda rather than the `ConnectivityMonitor` itself, exactly as every
     * other consumer of it takes the seam — which is also what keeps this class
     * testable without an Android framework double.
     */
    private val isOnline: () -> Boolean,
    private val errors: SyncErrorEvents,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val selectedDate = MutableStateFlow(today().toString())

    /** Bumped whenever the screen is shown, so a day rollover re-derives the strip. */
    private val refresh = MutableStateFlow(0)

    private val isRefreshing = MutableStateFlow(false)

    private var refreshJob: Job? = null

    private val trackers: StateFlow<List<TrackerDto>> = store.observeTrackers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val entriesByDate: StateFlow<Map<DateString, Map<String, EntryDto>>> =
        store.observeEntriesByDate()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    private val preferences = combine(
        prefs.expandedCategories,
        prefs.valueUpdatedTimes,
    ) { expanded, stamps -> expanded to stamps }

    /** What the day view needs that is neither a tracker nor an entry. */
    private data class ViewInputs(
        val date: DateString,
        val status: SyncStatus,
        val syncing: Boolean,
        val refreshing: Boolean,
    )

    private val viewInputs = combine(
        selectedDate,
        store.syncStatus,
        store.isSyncingFlow,
        isRefreshing,
        refresh,
    ) { date, status, syncing, refreshing, _ -> ViewInputs(date, status, syncing, refreshing) }

    val uiState: StateFlow<JournalUiState> = combine(
        trackers,
        entriesByDate,
        preferences,
        viewInputs,
    ) { allTrackers, entries, (expanded, stamps), inputs ->
        buildJournalUiState(
            trackers = allTrackers,
            entriesByDate = entries,
            selectedDate = inputs.date,
            today = today(),
            expandedCategories = expanded,
            valueUpdatedTimes = stamps,
            syncStatus = inputs.status,
            isSyncing = inputs.syncing,
            isRefreshing = inputs.refreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = JournalUiState(selectedDate = selectedDate.value),
    )

    /** Re-read the clock. The strip is a rolling window and the app outlives midnight. */
    fun onScreenShown() {
        refresh.value += 1
    }

    fun selectDate(date: DateString) {
        selectedDate.value = date
    }

    fun toggleCategory(category: String) {
        viewModelScope.launch { prefs.toggleCategoryExpanded(category) }
    }

    /**
     * The pull gesture: force a real round trip and *show* that it happened.
     *
     * The dot is a pure function of dirty count, watermark and connectivity, so
     * a successful no-op sync changes nothing on screen — which is precisely the
     * thing the user pulls to find out. Hence the minimum visible time: half a
     * second of spinner and a pulsing dot is the answer, and without it a fast
     * no-op is indistinguishable from a gesture that did not register.
     *
     * The scheduler's job is joined **and then** the store's own busy flag is
     * waited out: the job completes at once when the flight belongs to somebody
     * else (a background flush, a force sync), and the spinner would otherwise
     * retract while the sync it attached to was still running. The cap is there
     * because a wait with no end is a spinner with no end.
     *
     * Offline is answered here rather than in the scheduler, which treats it as
     * a silent skip. A pull is a question, and "nothing happened" is not an
     * answer to it.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            isRefreshing.value = true
            val floor = launch { delay(MIN_VISIBLE_MS) }
            if (isOnline()) {
                scheduler.requestSync(SyncScheduler.TRIGGER_PULL).join()
                withTimeoutOrNull(SYNC_WAIT_CAP_MS) { store.isSyncingFlow.first { !it } }
            } else {
                // Authored text, never a Throwable's message: the snackbar
                // appears unasked over whatever the user is looking at, and a
                // Ktor exception's message is the whole response body.
                errors.postMessage(OFFLINE_MESSAGE)
            }
            floor.join()
            isRefreshing.value = false
        }
    }

    // ---- widget writes -----------------------------------------------------

    fun setChecked(trackerId: String, checked: Boolean) {
        val tracker = trackerOf(trackerId) ?: return
        write(trackerId, checkboxPatch(tracker, entryOf(trackerId), checked), stamp = false)
    }

    /**
     * Commit a numeric field. A patch of null means the input was unusable or
     * unchanged, and the field simply snaps back to what it was showing.
     */
    fun commitNumeric(trackerId: String, displayedNumber: Double?, input: String) {
        write(trackerId, numericCommitPatch(displayedNumber, input), stamp = true)
    }

    fun addToAccumulator(trackerId: String, displayedNumber: Double?, input: String) {
        write(trackerId, accumulatorPatch(displayedNumber, input), stamp = true)
    }

    fun setSlider(trackerId: String, value: Float) {
        write(trackerId, sliderPatch(value), stamp = false)
    }

    fun setNote(trackerId: String, text: String) {
        write(trackerId, EntryPatch.note(text), stamp = false)
    }

    /**
     * One write path for every widget. [stamp] follows the tracker's type as
     * well as the caller's intent: only a quantifiable value carries a "last
     * updated" caption, so stamping anything else would be dead data.
     */
    private fun write(trackerId: String, patch: EntryPatch?, stamp: Boolean) {
        if (patch == null) return
        val date = currentDate()
        val type = trackerOf(trackerId)?.let { TrackerType.fromWire(it.type) } ?: TrackerType.SIMPLE
        val stamps = stamp && stampsLastUpdated(type)
        viewModelScope.launch {
            store.mergeEntry(date, trackerId, patch)
            if (stamps) prefs.markValueUpdated(date, trackerId)
        }
    }

    /**
     * The date a write lands on, clamped into the strip exactly as
     * [buildJournalUiState] clamps it — a tap on a screen left open past
     * midnight must not write to a day the strip no longer offers.
     */
    private fun currentDate(): DateString {
        val strip = getLastNDays(today()).map { it.date }
        return selectedDate.value.takeIf { it in strip } ?: strip.last()
    }

    private fun trackerOf(id: String): TrackerDto? = trackers.value.firstOrNull { it.id == id }

    private fun entryOf(id: String): EntryDto? = entriesByDate.value[currentDate()]?.get(id)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** Long enough for a no-op sync to read as an answer. */
        const val MIN_VISIBLE_MS = 500L

        /** A spinner waiting on somebody else's flight still has to end. */
        const val SYNC_WAIT_CAP_MS = 15_000L

        const val OFFLINE_MESSAGE = "Offline — nothing synced. Try again when you're connected."
    }
}
