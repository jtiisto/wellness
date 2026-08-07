package dev.jtiisto.wellness.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.data.journal.EntryDto
import dev.jtiisto.wellness.core.data.journal.JournalSyncStore
import dev.jtiisto.wellness.core.data.journal.TrackerDto
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import dev.jtiisto.wellness.core.data.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class JournalDebugUiState(
    val date: DateString = "",
    val trackers: List<TrackerDto> = emptyList(),
    val entries: Map<String, EntryDto> = emptyMap(),
    val status: SyncStatus = SyncStatus.GRAY,
)

/**
 * The Phase 2 journal tab: enough UI to prove the sync round trip — the
 * server's trackers listed, today's checkbox writing back. Phase 3 replaces it
 * with the real day view.
 *
 * "Today" is read at the moment it is needed, never cached: this screen can sit
 * open across midnight, and a stale date would file the tick under yesterday.
 */
class JournalDebugViewModel(
    private val store: JournalSyncStore,
    private val scheduler: SyncScheduler,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val date = MutableStateFlow(currentDate())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<JournalDebugUiState> = combine(
        store.observeTrackers(),
        date.flatMapLatest { store.observeDay(it) },
        store.syncStatus,
        date,
    ) { trackers, entries, status, date ->
        JournalDebugUiState(date = date, trackers = trackers, entries = entries, status = status)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = JournalDebugUiState(date = date.value),
    )

    /**
     * Toggle today's completion. Only `completed` is sent: the store keeps the
     * entry's value, so a tap cannot write back a value the UI read before the
     * last pull.
     */
    fun setCompleted(trackerId: String, completed: Boolean) {
        val target = currentDate().also { date.value = it }
        viewModelScope.launch {
            store.setEntryCompleted(target, trackerId, completed)
        }
    }

    fun syncNow() {
        date.value = currentDate()
        scheduler.requestSync()
    }

    private fun currentDate(): DateString = today().toString()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
