package dev.jtiisto.wellness.feature.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.ble.capture.HrCaptureController
import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.device.KnownDevice
import dev.jtiisto.wellness.core.ble.device.KnownDeviceStore
import dev.jtiisto.wellness.core.ble.trace.HrTraceRing
import dev.jtiisto.wellness.core.ble.trace.TraceSample
import dev.jtiisto.wellness.core.data.coach.CoachSyncStore
import dev.jtiisto.wellness.core.data.coach.CompletionToggle
import dev.jtiisto.wellness.core.data.coach.EXTRA_SESSION_KEY
import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.coach.PlanDto
import dev.jtiisto.wellness.core.data.coach.array
import dev.jtiisto.wellness.core.data.coach.hasAnyProgress
import dev.jtiisto.wellness.core.data.hr.GuideEventRecorder
import dev.jtiisto.wellness.core.data.hr.HrBeatReader
import dev.jtiisto.wellness.core.data.hr.HrCaptureStore
import dev.jtiisto.wellness.core.data.journal.journalNumberJson
import dev.jtiisto.wellness.core.data.network.CoachApi
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.SyncErrorEvents
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import dev.jtiisto.wellness.feature.coach.guidance.EXTENSION_STEP_SEC
import dev.jtiisto.wellness.feature.coach.guidance.GuidanceKey
import dev.jtiisto.wellness.feature.coach.guidance.GuidancePhase
import dev.jtiisto.wellness.feature.coach.guidance.GuidanceRun
import dev.jtiisto.wellness.feature.coach.guidance.GuidanceRuns
import dev.jtiisto.wellness.feature.coach.guidance.GuidanceStatus
import dev.jtiisto.wellness.feature.coach.guidance.GuidanceTimeline
import dev.jtiisto.wellness.feature.coach.guidance.GuidedRideFill
import dev.jtiisto.wellness.feature.coach.guidance.MILLIS_PER_SECOND
import dev.jtiisto.wellness.feature.coach.guidance.canOfferExtension
import dev.jtiisto.wellness.feature.coach.guidance.guidanceStatus
import dev.jtiisto.wellness.feature.coach.guidance.guidedRideFill
import dev.jtiisto.wellness.feature.coach.guidance.guidedSegmentsJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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
    /**
     * The guide's half of the heart-rate record: START and `+ 5 MIN`, appended
     * only while a capture is running. It writes nothing else — the plan and the
     * day log are untouched by both actions.
     */
    private val guideEvents: GuideEventRecorder,
    /**
     * The beats a finished guided ride is described from — read from Room on
     * dismissal, never from the live ring, which holds thirty seconds and is
     * empty by the time a strap comes off.
     */
    private val beatReader: HrBeatReader,
    traceRing: HrTraceRing,
    /**
     * Read at the moment of the pull, not observed — the seam every other
     * consumer of `ConnectivityMonitor` takes, and what keeps this class
     * testable without an Android framework double.
     */
    private val isOnline: () -> Boolean,
    private val errors: SyncErrorEvents,
    private val today: () -> LocalDate = LocalDate::now,
    /**
     * The wall clock a guidance run is anchored at.
     *
     * Epoch milliseconds, and legal arithmetic for the reason the whole HR stack
     * records: this is a data value off the phone's own clock, not a server
     * watermark. Injected for the same reason [today] is — a clock a test cannot
     * name is a clock a test cannot assert against.
     */
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val selectedDate = MutableStateFlow(today().toString())
    private val viewMonth = MutableStateFlow(monthOf(selectedDate.value))
    private val expandedExercises = MutableStateFlow(emptySet<String>())

    /**
     * Which guide is open, and every run this process is holding.
     *
     * Two facts, kept apart because they have different lifetimes and that
     * difference *is* the spec: the open key is what a dismiss clears, and the
     * runs are what a dismiss must not touch. Both live here rather than in the
     * overlay so the clock survives the composable — the guide is dismissed and
     * reopened mid-ride, and it comes back where it was.
     */
    private val guideKey = MutableStateFlow<GuidanceKey?>(null)

    private val guidanceRuns = MutableStateFlow(GuidanceRuns())

    /**
     * The live trace, straight from the ring — **not** part of [uiState].
     *
     * A window of beats arrives once or twice a second, and folding it into the
     * state would rebuild the whole Coach tab at that rate to move one polyline
     * inside an overlay that is usually closed. So it is published beside the
     * state, for the same reason the overlay's per-second instant deliberately
     * never enters it, and the guide is the only thing that ever collects it —
     * inside the composition that draws it, so a closed guide collects nothing.
     */
    val traceSamples: StateFlow<List<TraceSample>> = traceRing.samples

    private val _strapPrompt = MutableStateFlow<StrapPrompt?>(null)

    /**
     * The "Connect HRM?" sheet, open only between a Start Workout tap and its
     * answer. Deliberately not part of [uiState]: it is a transient question, not
     * anything derived from the stores.
     */
    val strapPrompt: StateFlow<StrapPrompt?> = _strapPrompt.asStateFlow()

    /** Bumped on resume so a day rollover re-derives "is today". */
    private val refresh = MutableStateFlow(0)

    private val _isRefreshing = MutableStateFlow(false)

    /**
     * The pull spinner — **deliberately not part of [uiState]**, beside
     * [strapPrompt] and [traceSamples] and for the same reason: it is transient
     * gesture state, not anything [buildCoachUiState] derives or decides with.
     * The state build is at five combined inputs; a sixth would cost a new
     * bundle and a changed pure signature to carry a boolean the builder never
     * reads.
     */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var refreshJob: Job? = null

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

    // Bundled to keep the state build at five inputs: these three are all
    // "what has the user opened", and none of them comes from storage.
    private val surfaceInputs = combine(
        expandedExercises,
        guideKey,
        guidanceRuns,
    ) { expanded, key, runs ->
        Triple(expanded, key, runs)
    }

    val uiState: StateFlow<CoachUiState> = combine(
        viewInputs,
        storeInputs,
        syncInputs,
        hooks.state,
        surfaceInputs,
    ) { (date, month), (plansByDate, logsByDate, earliest), (status, syncing, hrState), hooksState,
        (expanded, openGuide, runs),
        ->
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
            openGuide = openGuide,
            guidanceRuns = runs,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = CoachUiState(selectedDate = selectedDate.value),
    )

    init {
        // The hook machine follows the session on screen. ONE collector feeds it
        // both the session and the data flag, in that order, because they used
        // to arrive on two independent collectors — and on a cold start the logs
        // half could land first, after which onSession's reset wiped dataExists
        // while the logs side, deduplicated on an unchanged (date, exists) pair,
        // never spoke again. The visible symptom was a fired Start that kept its
        // Undo after sets were logged: the fired→locked promotion had lost its
        // reactive half to the race (device-found 2026-08-17).
        //
        // The session pair is deduplicated by hand rather than by the flow —
        // that is what keeps the status fetch one-shot: recomposition, a log
        // write, or a plan pull that leaves the session alone must not
        // re-request the status. The data flag rides every emission; the holder
        // absorbs repeats.
        viewModelScope.launch {
            var lastSession: Pair<Long?, Boolean>? = null
            combine(selectedDate, plans, refresh, logs) { date, plansByDate, _, logsByDate ->
                Triple(
                    plansByDate?.get(date)?.sessionId,
                    date == today().toString(),
                    hasAnyProgress(logsByDate?.get(date)),
                )
            }.distinctUntilChanged().collect { (sessionId, editable, dataExists) ->
                val session = sessionId to editable
                if (session != lastSession) {
                    lastSession = session
                    hooks.onSession(sessionId, editable)
                }
                hooks.onDataExists(dataExists)
            }
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
        // So does the open guide — and it is *closed*, not merely hidden. The
        // key carries its date, so a stale one would already refuse to draw
        // here; leaving it set would make the overlay spring back open the
        // moment the user navigated to the day it belongs to. The run itself
        // survives, which is what makes coming back and reopening restore it.
        guideKey.value = null
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

    /**
     * The pull gesture: force a real round trip and *show* that it happened.
     *
     * The same shape as the journal's, deliberately down to the constants — the
     * two tabs answer the same gesture and must answer it identically. The
     * indicator in the app bar is the visible half; the minimum visible time is
     * what stops a fast no-op from reading as a gesture that never registered.
     *
     * The scheduler's job is joined **and then** the store's busy flag is waited
     * out, because that job completes at once when the flight belongs to
     * somebody else. Offline is answered here rather than in the scheduler,
     * which treats it as a silent skip.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _isRefreshing.value = true
            val floor = launch { delay(MIN_VISIBLE_MS) }
            if (isOnline()) {
                scheduler.requestSync(SyncScheduler.TRIGGER_PULL).join()
                withTimeoutOrNull(SYNC_WAIT_CAP_MS) { store.isSyncingFlow.first { !it } }
            } else {
                // Authored text, never a Throwable's message — the debug-log
                // permitted-field policy applies to the snackbar too.
                errors.postMessage(OFFLINE_MESSAGE)
            }
            floor.join()
            _isRefreshing.value = false
        }
    }

    fun toggleExercise(exerciseId: String) {
        expandedExercises.update { expanded ->
            if (exerciseId in expanded) expanded - exerciseId else expanded + exerciseId
        }
    }

    // ---- the cardio guide -------------------------------------------------------

    /**
     * Open the guide for a cardio exercise **on today**.
     *
     * The date is the one thing checked here, and it is checked here as well as
     * at the affordance because the two can disagree for a moment: a tap can
     * land on a row drawn before midnight, and the guide is an instrument for a
     * ride happening now. Everything else is resolved against the plan on every
     * state build, so an exercise that carries no guide — or none at all —
     * yields no overlay rather than an empty one.
     *
     * The gate is on **opening**. A guide already open goes on running across
     * midnight; see the resolver.
     *
     * Opening does not start anything. The timeline is anchored by START and by
     * nothing else, deliberately, so that clipping the strap on does not burn
     * the warmup.
     */
    fun openGuide(exerciseId: String) {
        val date = selectedDate.value
        if (date != today().toString()) return
        guideKey.value = GuidanceKey(date = date, exerciseId = exerciseId)
    }

    /**
     * Close the guide — **the clock keeps running** — and, if the ride finished
     * under it, write what it saw into the log.
     *
     * A dismiss is usually the rider putting the phone down mid-interval rather
     * than the ride ending: the run stays in [guidanceRuns] and reopening
     * restores its position. But a dismiss *after* the timeline has run out is
     * the one moment the app knows a whole cardio session end to end — how long
     * it was, and what the heart did through the work of it — and typing that
     * back in from memory is the thing this round removes. So the fill runs
     * here, from resolved state read before the overlay closes.
     *
     * Nothing fills unless the phase has actually reached DONE. An early bail is
     * the rider's to log, exactly as before.
     */
    fun dismissGuide() {
        val guide = uiState.value.guide
        guideKey.value = null
        if (guide == null) return
        val status = guidanceStatus(guide.timeline, guide.run, now())
        if (status.phase != GuidancePhase.DONE) return
        autoFillFromRide(guide.key.exerciseId, status, guide.timeline, guide.run)
    }

    /**
     * Fill the cardio entry from the ride the guide just watched.
     *
     * The beats come off the phone's own store rather than the live ring: the
     * ring holds thirty seconds, the ride was an hour, and — the case that makes
     * this the only workable source — a dismiss routinely happens with the
     * capture already stopped, the strap already unclipped and the ring long
     * since emptied. The rows are there either way, uploaded or not.
     *
     * Everything is swallowed on failure, as the guide-event record is and for
     * the same reason: the fill is a convenience laid on top of an action that
     * has already succeeded, and a database read that failed must not take the
     * dismiss down with it. The rider is left with the manual fields they had
     * before, which is where every ride started until this round.
     */
    private fun autoFillFromRide(
        exerciseId: String,
        status: GuidanceStatus,
        timeline: GuidanceTimeline,
        run: GuidanceRun,
    ) {
        val totalSec = status.totalSec ?: return
        val endMs = status.anchorMs + totalSec * MILLIS_PER_SECOND
        viewModelScope.launch {
            runCatching {
                val beats = withContext(io) { beatReader.beatsBetween(status.anchorMs, endMs) }
                guidedRideFill(beats, timeline, run)
            }.getOrNull()?.let { fillCardioEntry(exerciseId, it) }
        }
    }

    /**
     * Anchor the open guide's timeline at now.
     *
     * Serves both the first START and the fresh run offered after a completed
     * one — [GuidanceRuns.started] discards whatever the key held, so a second
     * ride does not inherit the first one's anchor or its appended minutes.
     * Nothing is written to the log or the plan: raising a plan's target
     * mid-session would un-complete an exercise the rider had already satisfied.
     */
    fun startGuidance() {
        // Resolved state, not the raw key: a key whose overlay no longer
        // resolves (exercise gone, date moved on) must not anchor anything.
        val guide = uiState.value.guide ?: return
        val nowMs = now()
        // The button is absent while a run is under way, but absence is not a
        // guard: a double-tap can land after the first START and before the
        // recomposition that removes the control, and a re-anchor here is the
        // one tap that silently discards a timeline mid-ride (the deep-review
        // find). READY and DONE both legitimately anchor; RUNNING never does.
        val phase = guidanceStatus(guide.timeline, guide.run, nowMs).phase
        if (phase == GuidancePhase.RUNNING) return
        guidanceRuns.update { it.started(guide.key, nowMs) }
        // The anchor and its record are the same read of the clock, deliberately:
        // a stored instant that disagreed with the clock the rider is watching
        // would misplace every derived boundary by the difference.
        recordGuideEvent { sessionId ->
            guideEvents.recordStart(
                date = guide.key.date,
                exerciseKey = guide.key.exerciseId,
                sessionId = sessionId,
                clientTimestampMs = nowMs,
                timelineJson = guide.timeline.guidedSegmentsJson(),
            )
        }
    }

    /**
     * Append five minutes to the open guide's live timeline — and to nothing
     * else.
     *
     * The plan's `target_duration_min` is untouched, deliberately: raising it
     * mid-ride would un-complete an exercise the rider had already satisfied,
     * which is the recorded trap that made the extension UI-only. The log
     * records the minutes actually ridden, as it always has.
     *
     * Guarded exactly as [startGuidance] is, and for the same reason — the
     * button's absence is not a guard. [canOfferExtension] is asked against
     * resolved state and this pass's clock, so a tap that lands after the
     * timeline stopped being extensible does nothing.
     */
    fun extendGuidance() {
        val guide = uiState.value.guide ?: return
        val nowMs = now()
        val status = guidanceStatus(guide.timeline, guide.run, nowMs)
        if (!canOfferExtension(status)) return
        guidanceRuns.update { it.extended(guide.key) }
        recordGuideEvent { sessionId ->
            guideEvents.recordExtend(
                date = guide.key.date,
                exerciseKey = guide.key.exerciseId,
                sessionId = sessionId,
                clientTimestampMs = nowMs,
                // The step, not the running total: each tap is its own fact, and
                // a consumer that wants the cumulative figure sums the rows.
                extensionSec = EXTENSION_STEP_SEC,
            )
        }
    }

    /**
     * Append a guide action to the heart-rate record — **iff a capture is
     * running**.
     *
     * The session is read here, at the instant of the tap, rather than inside the
     * coroutine: what licenses the recording is that a capture was running when
     * the rider acted, and a strap that drops out between the tap and the write
     * does not un-happen it. A null session is not an error — it is the ordinary
     * case of a guide used without a strap, where the guide is a pure display and
     * the log's completion state is the only record, exactly as for strength.
     *
     * The run itself is already updated by the time this is called. The record is
     * a consequence of the action, never a condition of it: a failed insert must
     * not leave the rider looking at a timeline that refused to start.
     */
    private fun recordGuideEvent(record: suspend (String) -> Unit) {
        val sessionId = captureState.value.sessionId ?: return
        viewModelScope.launch {
            // Swallowed on purpose: the record is a consequence of the action,
            // never a condition of it, and an audit write that failed must not
            // crash the guide the rider is mid-ride on (the deep-review find —
            // an unguarded launch propagates to the scope and takes the UI
            // down). A persistent storage failure surfaces through the upload
            // pipeline's own diagnostics, not here.
            runCatching { record(sessionId) }
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
        // The sheet is the selector, so it is handed every remembered strap, in
        // the order the store publishes them.
        val straps = knownStraps.devices.value
        when (
            WorkoutCapturePolicy.startAction(
                hasKnownStrap = straps.isNotEmpty(),
                captureRunning = captureState.value.isRunning,
                connectPermitted = capture.canStart(),
            )
        ) {
            // The hook waits for the answer — see [connectStrap] / [skipStrap].
            StartCaptureAction.PROMPT -> {
                _strapPrompt.value = StrapPrompt(straps)
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
     * A tap on one of the sheet's straps: record this workout from [strap],
     * then start it.
     *
     * The tap is answered against the **prompt**, never against the store. The
     * prompt's list is the question the user was shown, so it is the only thing
     * that can say whether [strap] is one of the answers — a store refreshed
     * between render and tap would otherwise let this start a strap the sheet
     * never offered, under a name it never displayed. A [strap] the prompt does
     * not hold is therefore no answer at all: the question stays open, the hook
     * waits, and nothing records.
     *
     * The anchor travels with the start intent and comes back on the published
     * state once the session row exists; nothing is remembered here — neither
     * the anchor nor which strap was chosen.
     *
     * A refused start is **deliberately ignored**. The workout starts either way
     * — that is the sheet's whole contract, and the same reason Skip fires the
     * hook — and the refusal has already put its own message on the snackbar.
     * Abandoning the Start here would turn a strap problem into a lost workout.
     */
    fun connectStrap(strap: KnownDevice) {
        // Also the idempotence guard: an answered sheet leaves no prompt, so a
        // second tap resolves to nothing and cannot start the workout twice.
        val chosen = _strapPrompt.value
            ?.straps
            ?.firstOrNull { it.address == strap.address }
            ?: return
        _strapPrompt.value = null
        val anchor = currentAnchor()
        capture.start(
            address = chosen.address,
            name = chosen.name,
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
     * Write a finished guided ride's numbers into the entry — **into whichever
     * of the three fields are empty, and no others**.
     *
     * The same write path a typed field takes: [editEntry], so the store's
     * transaction applies it and completion derivation, the upload debounce and
     * every downstream reader see an ordinary cardio entry with no idea a guide
     * produced it. One write rather than three, because the three numbers
     * describe one ride.
     *
     * The emptiness test is made **inside the transaction**, against the entry
     * as stored — the same discipline the set-cell commit records: a check made
     * out here against the last emitted snapshot could overwrite a number the
     * rider typed while the beats were being read.
     *
     * [isEntryEditable] still governs. A ride that runs past midnight and is
     * dismissed on the next day fills nothing, because by then the day it
     * belongs to is read-only — the rider could not type into it either, and the
     * fill is not entitled to more than they are.
     */
    private fun fillCardioEntry(exerciseId: String, fill: GuidedRideFill) {
        if (!isEntryEditable()) return
        editEntry(exerciseId) { entry ->
            buildJsonObject {
                fillIfEmpty(entry, "duration_min", fill.durationMin)
                fillIfEmpty(entry, "avg_hr", fill.avgHr)
                fillIfEmpty(entry, "max_hr", fill.maxHr)
            }.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * One number into one field, iff the field is empty and the number exists.
     *
     * Absent and explicit null are both empty — the second is how a cleared
     * field is recorded — and anything else, including a `0` the rider typed, is
     * a value the guide has no business replacing.
     */
    private fun JsonObjectBuilder.fillIfEmpty(entry: JsonObject?, field: String, value: Int?) {
        if (value == null) return
        val stored = entry?.get(field)
        if (stored != null && stored != JsonNull) return
        put(field, journalNumberJson(value.toDouble()))
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

        /** Long enough for a no-op sync to read as an answer. Journal's twin. */
        const val MIN_VISIBLE_MS = 500L

        /** A spinner waiting on somebody else's flight still has to end. */
        const val SYNC_WAIT_CAP_MS = 15_000L

        const val OFFLINE_MESSAGE = "Offline — nothing synced. Try again when you're connected."
    }
}
