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
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val selectedDate = MutableStateFlow(today().toString())

    /** Bumped whenever the screen is shown, so a day rollover re-derives the strip. */
    private val refresh = MutableStateFlow(0)

    private val trackers: StateFlow<List<TrackerDto>> = store.observeTrackers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val entriesByDate: StateFlow<Map<DateString, Map<String, EntryDto>>> =
        store.observeEntriesByDate()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    private val preferences = combine(
        prefs.expandedCategories,
        prefs.valueUpdatedTimes,
    ) { expanded, stamps -> expanded to stamps }

    private val viewInputs = combine(
        selectedDate,
        store.syncStatus,
        refresh,
    ) { date, status, _ -> date to status }

    val uiState: StateFlow<JournalUiState> = combine(
        trackers,
        entriesByDate,
        preferences,
        viewInputs,
    ) { allTrackers, entries, (expanded, stamps), (date, status) ->
        buildJournalUiState(
            trackers = allTrackers,
            entriesByDate = entries,
            selectedDate = date,
            today = today(),
            expandedCategories = expanded,
            valueUpdatedTimes = stamps,
            syncStatus = status,
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

    fun syncNow() {
        scheduler.requestSync()
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
    }
}
