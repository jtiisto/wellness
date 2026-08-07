package dev.jtiisto.wellness.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.data.coach.CoachSyncStore
import dev.jtiisto.wellness.core.data.coach.PlanDto
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import dev.jtiisto.wellness.core.data.sync.SyncStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class CoachDebugUiState(
    val date: DateString = "",
    val plan: PlanDto? = null,
    val log: JsonObject? = null,
    val syncStatus: SyncStatus = SyncStatus.GRAY,
)

/**
 * The Phase 4 debug Coach tab: today's plan as the server sent it, today's log
 * as it is stored, and the two buttons that prove the round trip.
 *
 * Deliberately thin and disposable — Phase 5 replaces the whole tab with the
 * real workout UI. What it exists to demonstrate is that a plan renders through
 * [PlanDto] and that a set logged here reaches the server.
 */
class CoachDebugViewModel(
    private val store: CoachSyncStore,
    private val scheduler: SyncScheduler,
) : ViewModel() {

    private val date = store.todayDate()

    val uiState: StateFlow<CoachDebugUiState> = combine(
        store.observePlan(date),
        store.observeLog(date),
        store.syncStatus,
    ) { plan, log, status ->
        CoachDebugUiState(date = date, plan = plan, log = log, syncStatus = status)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = CoachDebugUiState(date = date),
    )

    fun syncNow() = scheduler.requestSync()

    /** Log the scratch set. The store marks the day dirty and debounces the upload. */
    fun logScratchSet() {
        val write = scratchWriteFor(uiState.value.plan)
        viewModelScope.launch { store.updateLog(date, write.exerciseKey, write.data) }
    }

    /** What [logScratchSet] would write, for the button's label. */
    fun scratchTarget(): ScratchWrite = scratchWriteFor(uiState.value.plan)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
