package dev.jtiisto.wellness.feature.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.ble.capture.HrCaptureController
import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.device.KnownDeviceStore
import dev.jtiisto.wellness.core.data.coach.CoachSyncStore
import dev.jtiisto.wellness.core.data.coach.CompletionToggle
import dev.jtiisto.wellness.core.data.coach.EXTRA_SESSION_KEY
import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.coach.PlanDto
import dev.jtiisto.wellness.core.data.coach.array
import dev.jtiisto.wellness.core.data.coach.hasAnyProgress
import dev.jtiisto.wellness.core.data.hr.HrCaptureStore
import dev.jtiisto.wellness.core.data.network.CoachApi
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
@Suppress("LongParameterList")
class CoachViewModel(
    private val store: CoachSyncStore,
    private val scheduler: SyncScheduler,
    api: CoachApi,
    private val captureState: StateFlow<HrCaptureState>,
    private val knownStraps: KnownDeviceStore,
    private val capture: HrCaptureController,
    private val captureStore: HrCaptureStore,
    private val today: () -> LocalDate = LocalDate::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val selectedDate = MutableStateFlow(today().toString())
    private val viewMonth = MutableStateFlow(monthOf(selectedDate.value))
    private val expandedExercises = MutableStateFlow(emptySet<String>())

    private val _strapPrompt = MutableStateFlow<StrapPrompt?>(null)

    /**
     * The "Connect HRM?" sheet, open only between a Start Workout tap and its
     * answer. Deliberately not part of [uiState]: it is a transient question, not
     * anything derived from the stores.
     */
    val strapPrompt: StateFlow<StrapPrompt?> = _strapPrompt.asStateFlow()

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

    private val syncInputs = combine(
        store.syncStatus,
        store.isSyncingFlow,
        captureState,
    ) { status, syncing, capture ->
        Triple(status, syncing, capture)
    }

    val uiState: StateFlow<CoachUiState> = combine(
        viewInputs,
        storeInputs,
        syncInputs,
        hooks.state,
        expandedExercises,
    ) { (date, month), (plansByDate, logsByDate, earliest), (status, syncing, hrState), hooksState, expanded ->
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
            capture = hrState,
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

    /**
     * Re-read the clock: the app outlives midnight, and "today" gates entry.
     *
     * Also the moment the remembered straps are (re-)read. [KnownDeviceStore]
     * does not load its map on construction, so without this the Start Workout
     * sheet would never appear in a process where the strap section was not
     * opened first — and the read is `SharedPreferences`, hence off the main
     * thread.
     */
    fun onScreenShown() {
        refresh.value += 1
        viewModelScope.launch { withContext(io) { knownStraps.refresh() } }
    }

    fun syncNow() = scheduler.requestSync()

    fun toggleExercise(exerciseId: String) {
        expandedExercises.update { expanded ->
            if (exerciseId in expanded) expanded - exerciseId else expanded + exerciseId
        }
    }

    // ---- workout hooks --------------------------------------------------------

    /**
     * Fire a hook, settling the heart-rate question around it.
     *
     * The hook machine's own semantics are untouched: whatever happens here,
     * [WorkoutHooks.fire] is called exactly once per tap — except on the one
     * path that asks a question first, where it is called once per *answer*
     * instead. Skipping the strap still starts the workout, which is the whole
     * point of the sheet being a question and not a gate.
     */
    fun fireHook(action: HookAction) {
        when (action) {
            HookAction.START -> startWorkout()
            HookAction.END -> endWorkout()
        }
    }

    fun undoHook(action: HookAction) = hooks.undo(action)

    // ---- heart-rate capture around a workout ------------------------------------

    private fun startWorkout() {
        val strap = knownStraps.devices.value.firstOrNull()
        when (
            WorkoutCapturePolicy.startAction(
                hasKnownStrap = strap != null,
                captureRunning = captureState.value.isRunning,
                connectPermitted = capture.canStart(),
            )
        ) {
            // The hook waits for the answer — see [connectStrap] / [skipStrap].
            StartCaptureAction.PROMPT -> {
                _strapPrompt.value = StrapPrompt(requireNotNull(strap).address, strap.name)
                return
            }

            StartCaptureAction.ANCHOR -> anchorRunningCapture()
            StartCaptureAction.NONE -> Unit
        }
        hooks.fire(HookAction.START)
    }

    /**
     * End the workout, and the recording it owns with it.
     *
     * The anchor is read off the capture state, which the service publishes from
     * the session row — so this holds after a process death mid-workout, where a
     * remembered anchor would have gone missing and left the capture running.
     *
     * The stop is requested before the hook fires so the session closes against
     * the workout that is ending rather than a moment after it. The two are
     * independent either way: a capture that cannot be stopped does not stop the
     * hook, and vice versa.
     */
    private fun endWorkout() {
        val live = captureState.value
        if (WorkoutCapturePolicy.stopsCapture(live.workoutAnchor(), currentAnchor(), live.isRunning)) {
            capture.stop()
        }
        hooks.fire(HookAction.END)
    }

    /**
     * The sheet's [Connect]: record this workout, then start it.
     *
     * The anchor travels with the start intent and comes back on the published
     * state once the session row exists; nothing is remembered here.
     *
     * A refused start is **deliberately ignored**. The workout starts either way
     * — that is the sheet's whole contract, and the same reason Skip fires the
     * hook — and the refusal has already put its own message on the snackbar.
     * Abandoning the Start here would turn a strap problem into a lost workout.
     */
    fun connectStrap() {
        val prompt = _strapPrompt.value ?: return
        _strapPrompt.value = null
        val anchor = currentAnchor()
        capture.start(
            address = prompt.address,
            name = prompt.name,
            workoutDate = anchor.date,
            workoutSessionId = anchor.sessionId,
        )
        hooks.fire(HookAction.START)
    }

    /** The sheet's [Skip], and its dismissal. The workout starts either way. */
    fun skipStrap() {
        if (_strapPrompt.value == null) return
        _strapPrompt.value = null
        hooks.fire(HookAction.START)
    }

    /** The status sheet's Stop, which ends the recording but never the workout. */
    fun stopCapture() = capture.stop()

    /**
     * Attach an already-running capture to this workout.
     *
     * The case the sheet cannot cover: the user started recording from the strap
     * section and then started a workout, where the session would otherwise
     * never learn which workout it belongs to. The store republishes the anchor
     * onto the capture state, so End Workout will recognise it as its own.
     */
    private fun anchorRunningCapture() {
        val anchor = currentAnchor()
        viewModelScope.launch { captureStore.anchorToWorkout(anchor.date, anchor.sessionId) }
    }

    private fun currentAnchor(): WorkoutAnchor =
        WorkoutAnchor(date = selectedDate.value, sessionId = hooks.state.value.sessionId)

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

    /**
     * The done tick, through the same pad-and-rewrite path as a value edit.
     *
     * The only difference is the [CompletionToggle], which appends a timestamped
     * row to the set-event log in the same transaction. The blob write is
     * unconditional as it always was; the event is not, and the store decides
     * that against the set as stored.
     */
    fun setSetCompleted(exerciseId: String, index: Int, completed: Boolean) {
        if (!isEntryEditable()) return
        editEntry(exerciseId, CompletionToggle.SetTick(setNum = index + 1, completed = completed)) { entry ->
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
        editEntry(exerciseId, CompletionToggle.ChecklistItem(item)) { entry ->
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
     *
     * [completion] is set only by the two toggles that record one; a value edit
     * leaves it null and appends nothing to the set-event log.
     */
    private fun editEntry(
        exerciseId: String,
        completion: CompletionToggle? = null,
        mutate: (JsonObject?) -> JsonObject?,
    ) {
        val date = selectedDate.value
        viewModelScope.launch { store.transformLogEntry(date, exerciseId, completion, mutate) }
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
