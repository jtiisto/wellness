package dev.jtiisto.wellness.feature.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.data.coach.CoachSyncStore
import dev.jtiisto.wellness.core.data.coach.EXTRA_SESSION_KEY
import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.coach.PlanDto
import dev.jtiisto.wellness.core.data.coach.array
import dev.jtiisto.wellness.core.data.coach.hasAnyProgress
import dev.jtiisto.wellness.core.data.network.CoachApi
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate

/**
 * The Coach tab.
 *
 * Thin, like the journal's: it subscribes, hands everything to
 * [buildCoachUiState], and turns taps into store writes through the pure helpers
 * in `SetGridLogic` and `ExtraSessionLogic`. The two judgments it makes itself
 * are *when* to read the clock — always at the moment of use, because this
 * screen can sit open across midnight — and which session the hook machine is
 * pointed at.
 */
class CoachViewModel(
    private val store: CoachSyncStore,
    private val scheduler: SyncScheduler,
    api: CoachApi,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private val selectedDate = MutableStateFlow(today().toString())
    private val viewMonth = MutableStateFlow(monthOf(selectedDate.value))
    private val expandedExercises = MutableStateFlow(emptySet<String>())

    /** Bumped on resume so a day rollover re-derives "is today". */
    private val refresh = MutableStateFlow(0)

    private val hooks = WorkoutHooks(api = api, scope = viewModelScope)

    // Eagerly, and null until the first emission lands: an empty window and a
    // window that has not loaded yet are different days on screen, and only the
    // second one may show a spinner.
    private val plans: StateFlow<Map<DateString, PlanDto?>?> = store.observeAllPlans()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val logs: StateFlow<Map<DateString, JsonObject>?> = store.observeAllLogs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val earliestDate: StateFlow<DateString?> = store.observeEarliestDate()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val viewInputs = combine(selectedDate, viewMonth, refresh) { date, month, _ -> date to month }

    private val storeInputs = combine(plans, logs, earliestDate) { plansByDate, logsByDate, earliest ->
        Triple(plansByDate, logsByDate, earliest)
    }

    private val syncInputs = combine(store.syncStatus, store.isSyncingFlow) { status, syncing ->
        status to syncing
    }

    val uiState: StateFlow<CoachUiState> = combine(
        viewInputs,
        storeInputs,
        syncInputs,
        hooks.state,
        expandedExercises,
    ) { (date, month), (plansByDate, logsByDate, earliest), (status, syncing), hooksState, expanded ->
        buildCoachUiState(
            selectedDate = date,
            viewMonth = month,
            plans = plansByDate.orEmpty(),
            logs = logsByDate.orEmpty(),
            earliestDate = earliest,
            today = today(),
            hooks = hooksState,
            expandedExercises = expanded,
            syncStatus = status,
            isSyncing = syncing,
            isLoading = plansByDate == null || logsByDate == null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = CoachUiState(selectedDate = selectedDate.value),
    )

    init {
        // The hook machine follows the session on screen. Distinct-until-changed
        // is what makes the fetch one-shot: recomposition, a log write, or a plan
        // pull that leaves the session alone must not re-request the status.
        viewModelScope.launch {
            combine(selectedDate, plans, refresh) { date, plansByDate, _ ->
                plansByDate?.get(date)?.sessionId to (date == today().toString())
            }.distinctUntilChanged().collect { (sessionId, editable) ->
                hooks.onSession(sessionId, editable)
            }
        }
        viewModelScope.launch {
            // Keyed by date as well as by the flag: a session change clears the
            // holder's copy, and two consecutive days that both have data would
            // otherwise be deduplicated away, leaving it stuck at false.
            combine(selectedDate, logs) { date, logsByDate -> date to hasAnyProgress(logsByDate?.get(date)) }
                .distinctUntilChanged()
                .collect { (_, exists) -> hooks.onDataExists(exists) }
        }
    }

    // ---- navigation ---------------------------------------------------------

    /**
     * Move to a day, re-homing the calendar's month onto it.
     *
     * Refused below the server's window start, which is also why the cells for
     * those days are drawn disabled — this is the authority, the disabled state
     * is the courtesy.
     */
    fun selectDate(date: DateString) {
        if (!canSelectDate(date, earliestDate.value)) return
        selectedDate.value = date
        viewMonth.value = monthOf(date)
        // Accordion state belongs to the day being looked at, as it does in the
        // PWA, where changing the date unmounts every exercise component.
        expandedExercises.value = emptySet()
    }

    fun goToToday() = selectDate(today().toString())

    /** Paging back stops at the window start; there is no forward limit. */
    fun previousMonth() {
        previousMonthOrNull(viewMonth.value, earliestDate.value)?.let { viewMonth.value = it }
    }

    fun nextMonth() {
        viewMonth.value = viewMonth.value.plusMonths(1)
    }

    /** Re-read the clock: the app outlives midnight, and "today" gates entry. */
    fun onScreenShown() {
        refresh.value += 1
    }

    fun syncNow() = scheduler.requestSync()

    fun toggleExercise(exerciseId: String) {
        expandedExercises.update { expanded ->
            if (exerciseId in expanded) expanded - exerciseId else expanded + exerciseId
        }
    }

    // ---- workout hooks --------------------------------------------------------

    fun fireHook(action: HookAction) = hooks.fire(action)

    fun undoHook(action: HookAction) = hooks.undo(action)

    // ---- entry writes ----------------------------------------------------------

    /**
     * A set cell commit (focus loss or IME Done).
     *
     * The cell mutation is handed to the store and applied **inside the write
     * transaction**, against the entry as stored at that instant. Building the
     * replacement array out here from the last emitted snapshot would lose the
     * race: two cells committed before Room re-emits would each rewrite the
     * whole array from the same stale copy, and the second would drop the first.
     */
    fun commitSetCell(exerciseId: String, index: Int, field: String, input: String) {
        if (!isEntryEditable()) return
        editEntry(exerciseId) { entry ->
            val sets = entry.setsArray()
            val current = (sets.getOrNull(index) as? JsonObject)?.get(field)
            val value = numericCellValue(current, input) ?: return@editEntry null
            buildJsonObject { put("sets", withSetCell(sets, index, field, value)) }
        }
    }

    /** The done tick, through the same pad-and-rewrite path as a value edit. */
    fun setSetCompleted(exerciseId: String, index: Int, completed: Boolean) {
        if (!isEntryEditable()) return
        editEntry(exerciseId) { entry ->
            buildJsonObject {
                put("sets", withSetCell(entry.setsArray(), index, "completed", JsonPrimitive(completed)))
            }
        }
    }

    fun commitCardioField(exerciseId: String, field: String, input: String) {
        if (!isEntryEditable()) return
        editEntry(exerciseId) { entry ->
            val value = numericCellValue(entry?.get(field), input) ?: return@editEntry null
            buildJsonObject { put(field, value) }
        }
    }

    /**
     * Toggle a checklist item.
     *
     * The item string is its own identity, so two identically-named items in one
     * checklist tick and untick together — the PWA's `includes` comparison has
     * the same property, and the plans do not produce duplicates.
     */
    fun toggleChecklistItem(exerciseId: String, item: String) {
        if (!isEntryEditable()) return
        editEntry(exerciseId) { entry ->
            val done = entry?.array("completed_items").orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }
            val next = if (item in done) done - item else done + item
            buildJsonObject { putJsonArray("completed_items") { next.forEach { add(it) } } }
        }
    }

    /**
     * The per-exercise note. Written per keystroke; the upload debounce absorbs it.
     *
     * Plain [CoachSyncStore.updateLog] rather than the transactional path: this
     * replaces one scalar key and reads nothing, so there is no earlier value for
     * a concurrent write to lose.
     */
    fun setExerciseNote(exerciseId: String, text: String) {
        if (!isEntryEditable()) return
        val date = selectedDate.value
        viewModelScope.launch {
            store.updateLog(date, exerciseId, buildJsonObject { put("user_note", text) })
        }
    }

    fun setFeedback(field: String, text: String) {
        if (!isEntryEditable()) return
        val date = selectedDate.value
        viewModelScope.launch {
            store.updateSessionFeedback(date, buildJsonObject { put(field, text) })
        }
    }

    // ---- the ad-hoc extra session ------------------------------------------------

    /** Adopt the draft in one write, so the entry never exists half-filled. */
    fun saveExtraSession(draft: ExtraSessionDraft) {
        if (!isRestDayEditable() || !draftCanSave(draft)) return
        val date = selectedDate.value
        viewModelScope.launch { store.updateLog(date, EXTRA_SESSION_KEY, draftPayload(draft)) }
    }

    fun commitExtraSessionField(field: String, input: String) {
        if (!isRestDayEditable()) return
        editEntry(EXTRA_SESSION_KEY) { entry ->
            val value = numericCellValue(entry?.get(field), input) ?: return@editEntry null
            buildJsonObject { put(field, value) }
        }
    }

    /** Tombstone the saved session. Recoverable by re-adding, so nothing to confirm. */
    fun deleteExtraSession() {
        if (!isRestDayEditable()) return
        val date = selectedDate.value
        viewModelScope.launch { store.deleteLogEntry(date, EXTRA_SESSION_KEY) }
    }

    // ---- plumbing -----------------------------------------------------------------

    /**
     * Apply a cell-level mutation to one entry, inside the store's transaction.
     *
     * [mutate] is handed the entry as stored and returns the content to merge,
     * or null to write nothing — which is how an unusable or unchanged field
     * commit ends up costing nothing at all.
     */
    private fun editEntry(exerciseId: String, mutate: (JsonObject?) -> JsonObject?) {
        val date = selectedDate.value
        viewModelScope.launch { store.transformLogEntry(date, exerciseId, mutate) }
    }

    private fun JsonObject?.setsArray(): JsonArray = this?.array("sets") ?: JsonArray(emptyList())

    /**
     * The gate, asked again at write time.
     *
     * The widgets are already disabled when it is closed; this catches the write
     * that a recomposition lag would otherwise let through onto a day that has
     * just stopped being today — or onto one whose plan turned out to be
     * unreadable.
     */
    private fun isEntryEditable(): Boolean = when (val day = uiState.value.day) {
        is WorkoutDayState.Planned -> day.editable
        is WorkoutDayState.Rest -> uiState.value.isEditable
        WorkoutDayState.Loading, is WorkoutDayState.PlanUnavailable -> false
    }

    /**
     * The ad-hoc session belongs to a rest day and nowhere else.
     *
     * Checking the day state rather than just the date is what keeps the write
     * path shut on a day whose plan exists but would not decode: the card is not
     * drawn there, and this makes sure it could not be saved to either.
     */
    private fun isRestDayEditable(): Boolean =
        uiState.value.isEditable && uiState.value.day is WorkoutDayState.Rest

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
