package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.ble.capture.HrCaptureController
import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.device.KnownDeviceStorage
import dev.jtiisto.wellness.core.ble.device.KnownDeviceStore
import dev.jtiisto.wellness.core.ble.trace.HrTraceRing
import dev.jtiisto.wellness.core.ble.trace.TraceSample
import dev.jtiisto.wellness.core.data.coach.CoachSyncStore
import dev.jtiisto.wellness.core.data.coach.CompletionToggle
import dev.jtiisto.wellness.core.data.coach.EXTRA_SESSION_KEY
import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.coach.HookButtonState
import dev.jtiisto.wellness.core.data.coach.HookResultDto
import dev.jtiisto.wellness.core.data.coach.PlanDto
import dev.jtiisto.wellness.core.data.coach.PlanSegmentDto
import dev.jtiisto.wellness.core.data.coach.TYPE_DURATION
import dev.jtiisto.wellness.core.data.coach.WorkoutStatusDto
import dev.jtiisto.wellness.core.data.hr.GuideEventRecorder
import dev.jtiisto.wellness.core.data.hr.HrBeatReader
import dev.jtiisto.wellness.core.data.hr.HrCaptureStore
import dev.jtiisto.wellness.core.data.network.CoachApi
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.SyncErrorEvents
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import dev.jtiisto.wellness.core.data.sync.SyncStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The ViewModel's write path and its wiring to the hook machine.
 *
 * `viewModelScope` is hard-wired to `Dispatchers.Main`, so every test installs a
 * [StandardTestDispatcher] on the test's own scheduler: the VM's collectors, its
 * writes and the hook fetch then all advance on one clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachViewModelTest {

    private val today = LocalDate.parse("2026-08-08")
    private val todayString: DateString = today.toString()

    /**
     * The wall clock a guidance run is anchored at, moved by hand.
     *
     * Deliberately not the test scheduler's virtual time: the guide's clock is
     * the phone's, and a run anchored at "now" has to be assertable against a
     * number the test chose.
     */
    private var nowMs = 1_893_955_260_000L

    // Shared flows with replay, not state flows: a StateFlow always has a value,
    // which would make "storage has not answered yet" impossible to express —
    // and that is one of the states under test.
    private val plansFlow = MutableSharedFlow<Map<DateString, PlanDto?>>(replay = 1)
    private val logsFlow = MutableSharedFlow<Map<DateString, JsonObject>>(replay = 1)
    private val earliestFlow = MutableStateFlow<DateString?>(null)

    /** The last window published to the screen. See [concurrentCommitsBothSurvive]. */
    private var publishedLogs: Map<DateString, JsonObject> = emptyMap()

    private val store = mockk<CoachSyncStore>(relaxed = true)
    private val api = mockk<CoachApi>()

    /** The pull's two seams: connectivity read at the gesture, and the snackbar. */
    private var online = true
    private val errors = SyncErrorEvents()

    /** The store's busy flag, which the pull waits out after joining the job. */
    private val storeSyncing = MutableStateFlow(false)

    /** What the scheduler hands back; completed by default, as a quick sync is. */
    private var syncJob: Job = Job().apply { complete() }

    private val scheduler = mockk<SyncScheduler>(relaxed = true)

    /**
     * The day as the store holds it.
     *
     * Deliberately NOT wired back into [logsFlow]: that is what lets a test put
     * two writes through before the screen ever sees the first, which is the
     * exact window the lost-update race lived in.
     */
    private var storedDay: JsonObject = buildJsonObject { }

    /**
     * Every completion descriptor handed to the store, in call order — null for
     * an edit that is not a toggle. What the store then does with one is
     * `CoachSetEventTest`'s subject; what reaches it is this file's.
     */
    private val completions = mutableListOf<CompletionToggle?>()

    @BeforeEach
    fun installMainDispatcher() {
        every { store.observeAllPlans() } returns plansFlow
        every { store.observeAllLogs() } returns logsFlow
        every { store.observeEarliestDate() } returns earliestFlow
        every { store.syncStatus } returns MutableStateFlow(SyncStatus.GREEN)
        every { store.isSyncingFlow } returns storeSyncing
        every { scheduler.requestSync(any()) } answers { syncJob }
        coEvery { api.workoutStatus(any()) } returns WorkoutStatusDto()

        // Stand in for the real transaction: hand the transform the entry as
        // stored right now, then merge what it returns.
        coEvery { store.transformLogEntry(any(), any(), any(), any()) } answers {
            val key = secondArg<String>()
            completions += arg<CompletionToggle?>(2)
            @Suppress("UNCHECKED_CAST")
            val transform = arg<(JsonObject?) -> JsonObject?>(3)
            val data = transform(storedDay[key] as? JsonObject)
            if (data != null) {
                val merged = buildJsonObject {
                    (storedDay[key] as? JsonObject)?.forEach { (k, v) -> put(k, v) }
                    data.forEach { (k, v) -> put(k, v) }
                }
                storedDay = JsonObject(storedDay + (key to merged))
            }
        }
    }

    private fun storedEntry(exerciseId: String = "ex_1"): JsonObject =
        storedDay.getValue(exerciseId).jsonObject

    @AfterEach
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    // ---- heart-rate capture doubles -------------------------------------------
    //
    // Real KnownDeviceStore over an in-memory map: the ordering it publishes is
    // what decides which strap the Start Workout sheet offers, and a mock would
    // be asserting that decision away.

    private val strapMap = linkedMapOf<String, String>()

    private val knownStraps = KnownDeviceStore(
        storage = object : KnownDeviceStorage {
            override fun load(): Map<String, String> = strapMap
            override fun put(address: String, name: String) {
                strapMap[address] = name
            }

            override fun remove(address: String) {
                strapMap.remove(address)
            }
        },
        state = MutableStateFlow(emptyList()),
    )

    private val captureState = MutableStateFlow(HrCaptureState())

    private val captureStore = mockk<HrCaptureStore>(relaxed = true)

    /**
     * The real ring, not a mock: the ViewModel does nothing with it but publish
     * its flow, and a real one proves that flow is the ring's own.
     */
    private val traceRing = HrTraceRing()

    /** Records what the UI asked of the capture service, in order. */
    private class FakeCaptureController : HrCaptureController {
        var permitted = true

        /** What the platform makes of a start request. False = it refused. */
        var startSucceeds = true
        val calls = mutableListOf<String>()

        override fun canStart(): Boolean = permitted

        override fun start(
            address: String,
            name: String?,
            workoutDate: String?,
            workoutSessionId: Long?,
        ): Boolean {
            calls += "start:$address:$name:$workoutDate:$workoutSessionId"
            return startSucceeds
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private val capture = FakeCaptureController()

    /**
     * The guide's half of the heart-rate record.
     *
     * Mocked rather than run over a fake DAO: what this file decides is *whether*
     * an action is recorded and with what, and the row it becomes is
     * `GuideEventRecorderTest`'s subject one layer down.
     */
    private val guideEvents = mockk<GuideEventRecorder>(relaxed = true)

    /**
     * The stored beats a finished ride is described from.
     *
     * Relaxed, so it answers with no beats unless a test says otherwise — which
     * is the strapless ride, and the state every test that is not about the
     * auto-fill wants to be in.
     */
    private val beatReader = mockk<HrBeatReader>(relaxed = true)

    private fun kotlinx.coroutines.test.TestScope.viewModel() = CoachViewModel(
        store = store,
        scheduler = scheduler,
        api = api,
        captureState = captureState,
        knownStraps = knownStraps,
        capture = capture,
        captureStore = captureStore,
        guideEvents = guideEvents,
        beatReader = beatReader,
        traceRing = traceRing,
        isOnline = { online },
        errors = errors,
        today = { today },
        now = { nowMs },
        // The strap refresh is a SharedPreferences read in production; here it
        // has to land on the test's own clock like everything else.
        io = StandardTestDispatcher(testScheduler),
    )

    /**
     * A capture as the service publishes it, anchor and all.
     *
     * The anchor lives here and nowhere else — that is the point of the service
     * publishing it off the session row rather than the screen remembering it.
     */
    private fun givenRunningCapture(anchoredTo: WorkoutAnchor?) {
        captureState.value = HrCaptureState(
            isRunning = true,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            deviceName = "HRM-Pro",
            sessionId = "session-1",
            workoutDate = anchoredTo?.date,
            workoutSessionId = anchoredTo?.sessionId,
        )
    }

    /** A strap the app has connected to before, as the store would publish it. */
    private fun kotlinx.coroutines.test.TestScope.givenKnownStrap(
        address: String = "AA:BB:CC:DD:EE:FF",
        name: String = "HRM-Pro",
    ) {
        strapMap[address] = name
        knownStraps.refresh()
    }

    /** Publish a window to the screen, the way a Room emission would. */
    private fun publish(
        plans: Map<DateString, PlanDto?> = emptyMap(),
        logs: Map<DateString, JsonObject> = emptyMap(),
    ) {
        publishedLogs = logs
        plansFlow.tryEmit(plans)
        logsFlow.tryEmit(logs)
    }

    /**
     * Installs Main and keeps the ui state hot, the way the screen does.
     *
     * [loaded] false leaves both windows unpublished, which is the only way to
     * observe the loading state.
     */
    private fun runVmTest(
        loaded: Boolean = true,
        body: suspend kotlinx.coroutines.test.TestScope.(CoachViewModel) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        if (loaded) publish()
        val viewModel = viewModel()
        // backgroundScope, so runTest tears the collector down for us; runCurrent
        // still drains it, since every scope here shares one scheduler.
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()
        body(viewModel)
    }

    // ---- set entry ------------------------------------------------------------

    @Test
    @DisplayName("a set commit pads to the edited index and writes the whole array")
    fun setCommitPadsAndRewrites() = runVmTest { viewModel ->
        givenPlan()

        viewModel.commitSetCell("ex_1", index = 2, field = "weight", input = "60")
        runCurrent()

        val sets = storedEntry().getValue("sets").jsonArray
        assertEquals(3, sets.size)
        assertEquals("1", sets[0].jsonObject.getValue("set_num").jsonPrimitive.content)
        assertEquals("60", sets[2].jsonObject.getValue("weight").jsonPrimitive.content)
    }

    @Test
    @DisplayName("a commit merges into the entry as stored, keeping the other fields")
    fun setCommitReadsTheStoredDay() = runVmTest { viewModel ->
        givenPlan()
        storedDay = dayLog(
            "ex_1",
            buildJsonObject { put("sets", sets(loggedSet(setNum = 1, weight = 60.0, reps = 8))) },
        )

        viewModel.commitSetCell("ex_1", index = 0, field = "reps", input = "10")
        runCurrent()

        val row = storedEntry().getValue("sets").jsonArray.single().jsonObject
        assertEquals("60", row.getValue("weight").jsonPrimitive.content)
        assertEquals("10", row.getValue("reps").jsonPrimitive.content)
    }

    @Test
    @DisplayName("two commits landing before the screen sees either both survive")
    fun concurrentCommitsBothSurvive() = runVmTest { viewModel ->
        givenPlan()

        // Neither commit gets a re-emission of the log before the next one runs,
        // which is precisely the window where building the replacement array from
        // an observed snapshot used to drop the first edit.
        viewModel.commitSetCell("ex_1", index = 0, field = "weight", input = "60")
        viewModel.commitSetCell("ex_1", index = 1, field = "weight", input = "65")
        runCurrent()

        assertTrue(publishedLogs.isEmpty(), "the flow must not have re-emitted for this to prove anything")
        val sets = storedEntry().getValue("sets").jsonArray
        assertEquals(2, sets.size)
        assertEquals("60", sets[0].jsonObject.getValue("weight").jsonPrimitive.content)
        assertEquals("65", sets[1].jsonObject.getValue("weight").jsonPrimitive.content)
    }

    @Test
    @DisplayName("a tick and a value edit racing on the same row both land")
    fun tickAndValueRace() = runVmTest { viewModel ->
        givenPlan()

        viewModel.commitSetCell("ex_1", index = 0, field = "weight", input = "60")
        viewModel.setSetCompleted("ex_1", index = 0, completed = true)
        runCurrent()

        val row = storedEntry().getValue("sets").jsonArray.single().jsonObject
        assertEquals("60", row.getValue("weight").jsonPrimitive.content)
        assertEquals("true", row.getValue("completed").jsonPrimitive.content)
    }

    @Test
    @DisplayName("an unusable or unchanged commit writes nothing at all")
    fun noWriteForUnusableInput() = runVmTest { viewModel ->
        givenPlan()

        viewModel.commitSetCell("ex_1", index = 0, field = "weight", input = "abc")
        viewModel.commitSetCell("ex_1", index = 0, field = "weight", input = "")
        runCurrent()

        // The transform ran but returned null, so nothing was stored.
        assertTrue(storedDay.isEmpty())
    }

    @Test
    @DisplayName("the done tick goes through the same pad-and-rewrite path")
    fun doneTick() = runVmTest { viewModel ->
        givenPlan()

        viewModel.setSetCompleted("ex_1", index = 1, completed = true)
        runCurrent()

        val sets = storedEntry().getValue("sets").jsonArray
        assertEquals(2, sets.size)
        assertEquals("true", sets[1].jsonObject.getValue("completed").jsonPrimitive.content)
    }

    // ---- what the set-event log is told --------------------------------------------

    @Test
    @DisplayName("a set tick carries a one-based set number and the state it is asking for")
    fun setTickCarriesItsToggle() = runVmTest { viewModel ->
        givenPlan()

        viewModel.setSetCompleted("ex_1", index = 1, completed = true)
        viewModel.setSetCompleted("ex_1", index = 1, completed = false)
        runCurrent()

        assertEquals(
            listOf(
                CompletionToggle.SetTick(setNum = 2, completed = true),
                CompletionToggle.SetTick(setNum = 2, completed = false),
            ),
            completions,
        )
    }

    @Test
    @DisplayName("a checklist toggle carries the item string, which is the item's identity")
    fun checklistToggleCarriesItsItem() = runVmTest { viewModel ->
        givenPlan()

        viewModel.toggleChecklistItem("ex_1", "Foam roll")
        runCurrent()

        assertEquals(listOf(CompletionToggle.ChecklistItem("Foam roll")), completions)
    }

    @Test
    @DisplayName("value edits carry no toggle at all, so they can never be read as work performed")
    fun valueEditsCarryNoToggle() = runVmTest { viewModel ->
        givenPlan()

        viewModel.commitSetCell("ex_1", index = 0, field = "weight", input = "60")
        viewModel.commitCardioField("ex_1", field = "duration_min", input = "30")
        runCurrent()

        assertEquals(listOf(null, null), completions)
    }

    // ---- the other widgets --------------------------------------------------------

    @Test
    @DisplayName("clearing a cardio field stores an explicit null")
    fun cardioClear() = runVmTest { viewModel ->
        givenPlan()
        storedDay = dayLog("ex_1", buildJsonObject { put("duration_min", 30) })

        viewModel.commitCardioField("ex_1", field = "duration_min", input = "")
        runCurrent()

        assertEquals(JsonNull, storedEntry().getValue("duration_min"))
    }

    @Test
    @DisplayName("toggling a checklist item adds it, and toggling again takes it away")
    fun checklistToggle() = runVmTest { viewModel ->
        givenPlan()

        viewModel.toggleChecklistItem("ex_1", "Foam roll")
        runCurrent()
        assertEquals(
            listOf("Foam roll"),
            storedEntry().getValue("completed_items").jsonArray.map { it.jsonPrimitive.content },
        )

        // No re-emission in between: the untick still sees the tick.
        viewModel.toggleChecklistItem("ex_1", "Foam roll")
        runCurrent()
        assertTrue(storedEntry().getValue("completed_items").jsonArray.isEmpty())
    }

    @Test
    @DisplayName("two different items ticked in the same window both survive")
    fun checklistConcurrentTicks() = runVmTest { viewModel ->
        givenPlan()

        viewModel.toggleChecklistItem("ex_1", "Foam roll")
        viewModel.toggleChecklistItem("ex_1", "Band pulls")
        runCurrent()

        assertEquals(
            listOf("Foam roll", "Band pulls"),
            storedEntry().getValue("completed_items").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    @DisplayName("the exercise note and the session feedback each write their own field")
    fun notesAndFeedback() = runVmTest { viewModel ->
        givenPlan()
        val entry = slot<JsonObject>()
        coEvery { store.updateLog(any(), any(), capture(entry)) } returns Unit
        val feedback = slot<JsonObject>()
        coEvery { store.updateSessionFeedback(any(), capture(feedback)) } returns Unit

        viewModel.setExerciseNote("ex_1", "felt heavy")
        runCurrent()
        assertEquals("felt heavy", entry.captured.getValue("user_note").jsonPrimitive.content)

        viewModel.setFeedback("pain_discomfort", "left knee")
        runCurrent()
        assertEquals("left knee", feedback.captured.getValue("pain_discomfort").jsonPrimitive.content)
    }

    // ---- guards ------------------------------------------------------------------------

    @Test
    @DisplayName("a past day refuses every write, however it is reached")
    fun pastDayRefusesWrites() = runVmTest { viewModel ->
        publish(plans = mapOf("2026-08-01" to plan(blocks = listOf(block(exercises = listOf(exercise()))))))
        runCurrent()
        viewModel.selectDate("2026-08-01")
        runCurrent()

        viewModel.commitSetCell("ex_1", 0, "weight", "60")
        viewModel.setSetCompleted("ex_1", 0, true)
        viewModel.commitCardioField("ex_1", "duration_min", "30")
        viewModel.toggleChecklistItem("ex_1", "Foam roll")
        viewModel.setExerciseNote("ex_1", "note")
        viewModel.setFeedback("general_notes", "note")
        viewModel.saveExtraSession(ExtraSessionDraft(durationMin = "45"))
        viewModel.deleteExtraSession()
        runCurrent()

        coVerify(exactly = 0) { store.updateLog(any(), any(), any()) }
        coVerify(exactly = 0) { store.transformLogEntry(any(), any(), any(), any()) }
        coVerify(exactly = 0) { store.updateSessionFeedback(any(), any()) }
        coVerify(exactly = 0) { store.deleteLogEntry(any(), any()) }
    }

    @Test
    @DisplayName("an unsatisfied start gate refuses entry writes on today too")
    fun closedGateRefusesWrites() = runVmTest { viewModel ->
        coEvery { api.workoutStatus(any()) } returns WorkoutStatusDto(
            actionsAvailable = dev.jtiisto.wellness.core.data.coach.HookAvailabilityDto(start = true, end = true),
        )
        givenPlan()

        val day = viewModel.uiState.value.day as WorkoutDayState.Planned
        assertFalse(day.gateSatisfied)

        viewModel.commitSetCell("ex_1", 0, "weight", "60")
        runCurrent()

        coVerify(exactly = 0) { store.transformLogEntry(any(), any(), any(), any()) }
    }

    // ---- navigation -------------------------------------------------------------------------

    @Test
    @DisplayName("selecting a date re-homes the month and drops the open accordions")
    fun selectDateRehomes() = runVmTest { viewModel ->
        givenPlan()
        viewModel.toggleExercise("ex_1")
        runCurrent()
        assertTrue(expandedIds(viewModel).contains("ex_1"))

        viewModel.selectDate("2026-06-15")
        runCurrent()

        assertEquals("2026-06-15", viewModel.uiState.value.selectedDate)
        assertEquals(java.time.YearMonth.of(2026, 6), viewModel.uiState.value.calendar.viewMonth)
        assertTrue(expandedIds(viewModel).isEmpty())
    }

    @Test
    @DisplayName("a date below the window start is refused outright")
    fun selectionBelowTheFloorIsRefused() = runVmTest { viewModel ->
        earliestFlow.value = "2026-07-01"
        runCurrent()

        viewModel.selectDate("2026-06-15")
        runCurrent()

        assertEquals(todayString, viewModel.uiState.value.selectedDate)
    }

    @Test
    @DisplayName("month paging stops at the floor going back and never going forward")
    fun monthPaging() = runVmTest { viewModel ->
        earliestFlow.value = "2026-08-01"
        runCurrent()

        viewModel.previousMonth()
        runCurrent()
        assertEquals(java.time.YearMonth.of(2026, 8), viewModel.uiState.value.calendar.viewMonth)

        repeat(3) { viewModel.nextMonth() }
        runCurrent()
        assertEquals(java.time.YearMonth.of(2026, 11), viewModel.uiState.value.calendar.viewMonth)

        viewModel.goToToday()
        runCurrent()
        assertEquals(java.time.YearMonth.of(2026, 8), viewModel.uiState.value.calendar.viewMonth)
    }

    // ---- the pull gesture ---------------------------------------------------------------------
    //
    // The journal's twin, deliberately: the two tabs answer the same gesture and
    // must answer it identically, so these mirror `JournalViewModelTest`.

    @Test
    @DisplayName("a pull asks the scheduler for a sync, named as a pull")
    fun refreshRequestsAPullSync() = runVmTest { viewModel ->
        viewModel.refresh()
        advanceUntilIdle()

        verify(exactly = 1) { scheduler.requestSync(SyncScheduler.TRIGGER_PULL) }
    }

    @Test
    @DisplayName("the spinner is held past a sync that returns instantly")
    fun refreshHoldsTheMinimumVisibleFloor() = runVmTest { viewModel ->
        viewModel.refresh()
        runCurrent()
        assertTrue(viewModel.isRefreshing.value)

        advanceTimeBy(499)
        assertTrue(viewModel.isRefreshing.value, "the floor has not elapsed")

        advanceTimeBy(2)
        assertFalse(viewModel.isRefreshing.value)
    }

    @Test
    @DisplayName("a second pull while one is in flight is refused rather than queued")
    fun refreshIsNotReentrant() = runVmTest { viewModel ->
        viewModel.refresh()
        runCurrent()
        viewModel.refresh()
        advanceUntilIdle()

        verify(exactly = 1) { scheduler.requestSync(any()) }
    }

    @Test
    @DisplayName("the spinner outlives a job that completed instantly, waiting out somebody else's flight")
    fun refreshWaitsOutAnAttachedFlight() = runVmTest { viewModel ->
        // The scheduler's busy path: the job is done the moment it is handed
        // back, because the real flight belongs to a background flush.
        storeSyncing.value = true

        viewModel.refresh()
        advanceTimeBy(5_000)
        assertTrue(viewModel.isRefreshing.value, "the attached flight is still running")

        storeSyncing.value = false
        advanceUntilIdle()
        assertFalse(viewModel.isRefreshing.value)
    }

    @Test
    @DisplayName("an offline pull syncs nothing and says so, in authored words")
    fun offlineRefreshPostsAMessage() = runVmTest { viewModel ->
        online = false
        val messages = mutableListOf<String>()
        backgroundScope.launch { errors.messages.collect { messages += it } }

        viewModel.refresh()
        advanceUntilIdle()

        verify(exactly = 0) { scheduler.requestSync(any()) }
        assertEquals(
            listOf("Offline — nothing synced. Try again when you're connected."),
            messages,
        )
    }

    // ---- the extra session -------------------------------------------------------------------

    @Test
    @DisplayName("saving a draft writes every filled field in one call, and delete tombstones it")
    fun extraSessionRoundTrip() = runVmTest { viewModel ->
        val written = slot<JsonObject>()
        coEvery { store.updateLog(any(), any(), capture(written)) } returns Unit
        // A rest day: no plan for today at all.
        publish()
        runCurrent()

        viewModel.saveExtraSession(ExtraSessionDraft(durationMin = "45", avgHr = "128"))
        runCurrent()

        coVerify(exactly = 1) { store.updateLog(todayString, EXTRA_SESSION_KEY, any()) }
        assertEquals(setOf("duration_min", "avg_hr"), written.captured.keys)

        viewModel.deleteExtraSession()
        runCurrent()
        coVerify(exactly = 1) { store.deleteLogEntry(todayString, EXTRA_SESSION_KEY) }
    }

    @Test
    @DisplayName("a draft with no duration is never saved")
    fun draftWithoutDuration() = runVmTest { viewModel ->
        publish()
        runCurrent()

        viewModel.saveExtraSession(ExtraSessionDraft(avgHr = "128"))
        runCurrent()

        coVerify(exactly = 0) { store.updateLog(any(), any(), any()) }
    }

    // ---- the hook machine's wiring ---------------------------------------------------------------

    @Test
    @DisplayName("the status is fetched once for today's session and not again for the same one")
    fun oneShotStatusFetch() = runVmTest { viewModel ->
        givenPlan()

        // A log write moves the state but leaves the session alone.
        publish(plans = todaysPlan(), logs = mapOf(todayString to dayLog("ex_1", buildJsonObject { put("user_note", "x") })))
        runCurrent()

        coVerify(exactly = 1) { api.workoutStatus(1) }
    }

    @Test
    @DisplayName("a fired start locks even when the log lands before the plan at startup")
    fun lockSurvivesLogsBeforePlans() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        coEvery { api.workoutStatus(any()) } returns WorkoutStatusDto(
            start = HookResultDto(firedAt = "t1", exitCode = 0),
            actionsAvailable = dev.jtiisto.wellness.core.data.coach.HookAvailabilityDto(
                start = true,
                end = true,
            ),
        )
        // The device-found race (2026-08-17): the day's log — real data in it —
        // is published before the plan, the shape a cold start takes when Room
        // answers the smaller query first. Two independent collectors let
        // onSession's reset erase a dataExists the logs side had already
        // delivered and would never repeat; the fired Start then kept its Undo
        // with sets logged under it. One ordered collector makes this
        // impossible, and this test pins the exact sequence that broke.
        logsFlow.tryEmit(
            mapOf(
                todayString to dayLog(
                    "ex_1",
                    buildJsonObject {
                        put(
                            "sets",
                            buildJsonArray { add(buildJsonObject { put("set_num", 1); put("weight", 60) }) },
                        )
                    },
                ),
            ),
        )
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()

        plansFlow.tryEmit(todaysPlan())
        runCurrent()

        val day = viewModel.uiState.value.day as WorkoutDayState.Planned
        val start = day.controls?.start
        assertEquals(HookButtonState.LOCKED, start?.state, "fired + data must be locked")
        assertFalse(start?.canUndo == true, "a locked start offers no undo")
    }

    @Test
    @DisplayName("crossing midnight on resume re-derives the day as no longer today")
    fun midnightRollover() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var now = LocalDate.parse("2026-08-08")
        publish(plans = mapOf("2026-08-08" to plan(sessionId = 1)))
        val viewModel = CoachViewModel(
            store = store,
            scheduler = scheduler,
            api = api,
            captureState = captureState,
            knownStraps = knownStraps,
            capture = capture,
            captureStore = captureStore,
            guideEvents = guideEvents,
            beatReader = beatReader,
            traceRing = traceRing,
            isOnline = { online },
            errors = errors,
            today = { now },
            io = StandardTestDispatcher(testScheduler),
        )
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()

        assertTrue(viewModel.uiState.value.isEditable)
        coVerify(exactly = 1) { api.workoutStatus(1) }

        // The screen sat open across midnight; the selected date did not move.
        now = LocalDate.parse("2026-08-09")
        viewModel.onScreenShown()
        runCurrent()

        assertEquals("2026-08-08", viewModel.uiState.value.selectedDate)
        assertFalse(viewModel.uiState.value.isEditable)
        // Yesterday is read-only, so its hook controls are gone and nothing is
        // re-fetched for it.
        val day = viewModel.uiState.value.day as WorkoutDayState.Planned
        assertNull(day.controls)
        assertFalse(day.editable)
        coVerify(exactly = 1) { api.workoutStatus(1) }
    }

    // ---- heart-rate capture around a workout ---------------------------------------------

    @Test
    @DisplayName("Start Workout with a known strap asks first, and does not fire the hook yet")
    fun startWorkoutAsksAboutTheStrap() = runVmTest { viewModel ->
        givenPlan()
        givenKnownStrap()

        viewModel.fireHook(HookAction.START)
        runCurrent()

        assertEquals(StrapPrompt("AA:BB:CC:DD:EE:FF", "HRM-Pro"), viewModel.strapPrompt.value)
        // The hook is the *answer's* job. Firing here as well would start the
        // workout twice for anyone who then tapped Connect.
        coVerify(exactly = 0) { api.fireWorkoutHook(any(), any()) }
        assertTrue(capture.calls.isEmpty())
    }

    @Test
    @DisplayName("Connect starts capture anchored to the date and the hook session, then fires")
    fun connectStartsAnchoredCapture() = runVmTest { viewModel ->
        givenPlan()
        givenKnownStrap()
        viewModel.fireHook(HookAction.START)
        runCurrent()

        viewModel.connectStrap()
        runCurrent()

        assertNull(viewModel.strapPrompt.value)
        assertEquals(listOf("start:AA:BB:CC:DD:EE:FF:HRM-Pro:$todayString:1"), capture.calls)
        coVerify(exactly = 1) { api.fireWorkoutHook(1, HookAction.START) }
    }

    @Test
    @DisplayName("a start the platform refused still starts the workout")
    fun refusedCaptureStartStillStartsTheWorkout() = runVmTest { viewModel ->
        givenPlan()
        givenKnownStrap()
        viewModel.fireHook(HookAction.START)
        runCurrent()
        // The app was backgrounded between the tap and the dispatch, or the
        // Bluetooth grant went away. A strap problem must not cost the workout.
        capture.startSucceeds = false

        viewModel.connectStrap()
        runCurrent()

        assertNull(viewModel.strapPrompt.value)
        coVerify(exactly = 1) { api.fireWorkoutHook(1, HookAction.START) }
    }

    @Test
    @DisplayName("Skip starts the workout and records nothing — the hook fires either way")
    fun skipStartsTheWorkoutAnyway() = runVmTest { viewModel ->
        givenPlan()
        givenKnownStrap()
        viewModel.fireHook(HookAction.START)
        runCurrent()

        viewModel.skipStrap()
        runCurrent()

        assertNull(viewModel.strapPrompt.value)
        assertTrue(capture.calls.isEmpty())
        coVerify(exactly = 1) { api.fireWorkoutHook(1, HookAction.START) }
    }

    @Test
    @DisplayName("a second Skip cannot fire the hook again")
    fun skipIsIdempotent() = runVmTest { viewModel ->
        givenPlan()
        givenKnownStrap()
        viewModel.fireHook(HookAction.START)
        runCurrent()

        viewModel.skipStrap()
        viewModel.skipStrap()
        runCurrent()

        coVerify(exactly = 1) { api.fireWorkoutHook(1, HookAction.START) }
    }

    @Test
    @DisplayName("with no strap ever paired, Start Workout is exactly what it always was")
    fun noStrapNoSheet() = runVmTest { viewModel ->
        givenPlan()

        viewModel.fireHook(HookAction.START)
        runCurrent()

        assertNull(viewModel.strapPrompt.value)
        coVerify(exactly = 1) { api.fireWorkoutHook(1, HookAction.START) }
    }

    @Test
    @DisplayName("a strap the app can no longer connect to is not offered")
    fun revokedPermissionSuppressesTheSheet() = runVmTest { viewModel ->
        givenPlan()
        givenKnownStrap()
        // Nearby devices turned off in Settings since the strap was paired: the
        // service would refuse the start, so offering Connect would promise a
        // recording that never happens.
        capture.permitted = false

        viewModel.fireHook(HookAction.START)
        runCurrent()

        assertNull(viewModel.strapPrompt.value)
        coVerify(exactly = 1) { api.fireWorkoutHook(1, HookAction.START) }
    }

    @Test
    @DisplayName("a capture already running is anchored to the workout instead of being asked about")
    fun runningCaptureIsAnchoredNotAsked() = runVmTest { viewModel ->
        givenPlan()
        givenKnownStrap()
        captureState.value = HrCaptureState(isRunning = true, deviceAddress = "AA:BB:CC:DD:EE:FF")
        runCurrent()

        viewModel.fireHook(HookAction.START)
        runCurrent()

        assertNull(viewModel.strapPrompt.value)
        assertTrue(capture.calls.isEmpty())
        coVerify(exactly = 1) { captureStore.anchorToWorkout(todayString, 1) }
        coVerify(exactly = 1) { api.fireWorkoutHook(1, HookAction.START) }
    }

    @Test
    @DisplayName("End Workout stops the capture this workout started")
    fun endWorkoutStopsItsOwnCapture() = runVmTest { viewModel ->
        givenPlan()
        givenKnownStrap()
        viewModel.fireHook(HookAction.START)
        runCurrent()
        viewModel.connectStrap()
        // The service opened the session with the anchor it was handed and
        // published it; nothing on this side remembers it.
        givenRunningCapture(anchoredTo = WorkoutAnchor(todayString, sessionId = 1))
        runCurrent()

        viewModel.fireHook(HookAction.END)
        runCurrent()

        assertEquals("stop", capture.calls.last())
        coVerify(exactly = 1) { api.fireWorkoutHook(1, HookAction.END) }
    }

    @Test
    @DisplayName("End Workout still stops its capture after a process death took the ViewModel with it")
    fun endWorkoutStopsItsCaptureAcrossAProcessDeath() = runVmTest { _ ->
        givenPlan()
        givenKnownStrap()
        // START_STICKY restarted the service, which resumed the open session and
        // republished its anchor off the row. This ViewModel never started that
        // capture and has no memory of it — the state is the only witness.
        givenRunningCapture(anchoredTo = WorkoutAnchor(todayString, sessionId = 1))
        val rebuilt = viewModel()
        backgroundScope.launch { rebuilt.uiState.collect { } }
        runCurrent()

        rebuilt.fireHook(HookAction.END)
        runCurrent()

        assertEquals(listOf("stop"), capture.calls)
        coVerify(exactly = 1) { api.fireWorkoutHook(1, HookAction.END) }
    }

    @Test
    @DisplayName("a capture anchored to another day survives this workout's End")
    fun endWorkoutLeavesAnotherWorkoutsCapture() = runVmTest { viewModel ->
        givenPlan()
        // Yesterday's session, still open because its final flush never landed.
        givenRunningCapture(anchoredTo = WorkoutAnchor("2026-08-07", sessionId = 4))
        runCurrent()

        viewModel.fireHook(HookAction.END)
        runCurrent()

        assertTrue(capture.calls.isEmpty())
        coVerify(exactly = 1) { api.fireWorkoutHook(1, HookAction.END) }
    }

    @Test
    @DisplayName("End Workout leaves a capture it never anchored alone")
    fun endWorkoutLeavesAnUnanchoredCapture() = runVmTest { viewModel ->
        givenPlan()
        givenKnownStrap()
        // Started from the strap section, so the session row carries no anchor:
        // this workout has no claim on it.
        givenRunningCapture(anchoredTo = null)
        runCurrent()

        viewModel.fireHook(HookAction.END)
        runCurrent()

        assertTrue(capture.calls.isEmpty())
        coVerify(exactly = 1) { api.fireWorkoutHook(1, HookAction.END) }
    }

    @Test
    @DisplayName("a capture that ended on its own is not stopped a second time")
    fun endWorkoutAfterTheCaptureAlreadyStopped() = runVmTest { viewModel ->
        givenPlan()
        givenKnownStrap()
        viewModel.fireHook(HookAction.START)
        runCurrent()
        viewModel.connectStrap()
        givenRunningCapture(anchoredTo = WorkoutAnchor(todayString, sessionId = 1))
        runCurrent()
        // The five-minute inactivity net fired, or the strap died. Teardown
        // resets the whole state, anchor included.
        captureState.value = HrCaptureState()
        runCurrent()
        capture.calls.clear()

        viewModel.fireHook(HookAction.END)
        runCurrent()

        assertTrue(capture.calls.isEmpty())
        coVerify(exactly = 1) { api.fireWorkoutHook(1, HookAction.END) }
    }

    @Test
    @DisplayName("the status sheet's Stop ends the recording and nothing else")
    fun stopCaptureLeavesTheWorkoutAlone() = runVmTest { viewModel ->
        givenPlan()
        captureState.value = HrCaptureState(isRunning = true, deviceAddress = "AA:BB:CC:DD:EE:FF")
        runCurrent()

        viewModel.stopCapture()
        runCurrent()

        assertEquals(listOf("stop"), capture.calls)
        coVerify(exactly = 0) { api.fireWorkoutHook(any(), any()) }
    }

    @Test
    @DisplayName("the chip appears only while a capture is running")
    fun chipFollowsTheCapture() = runVmTest { viewModel ->
        givenPlan()
        assertNull(viewModel.uiState.value.hr)

        captureState.value = HrCaptureState(isRunning = true, bpm = 132, deviceName = "HRM-Pro")
        runCurrent()
        assertEquals("132", viewModel.uiState.value.hr?.bpmText)

        captureState.value = HrCaptureState()
        runCurrent()
        assertNull(viewModel.uiState.value.hr)
    }

    @Test
    @DisplayName("the remembered straps are re-read on every resume")
    fun strapsAreRefreshedOnResume() = runVmTest { viewModel ->
        givenPlan()
        // Paired from the Tools tab while the coach tab sat in the back stack:
        // without the resume refresh the sheet would never know about it.
        strapMap["AA:BB:CC:DD:EE:FF"] = "HRM-Pro"

        viewModel.fireHook(HookAction.START)
        runCurrent()
        assertNull(viewModel.strapPrompt.value)

        viewModel.onScreenShown()
        runCurrent()
        viewModel.fireHook(HookAction.START)
        runCurrent()

        assertEquals("HRM-Pro", viewModel.strapPrompt.value?.name)
    }

    // ---- the two states that are not a rest day -----------------------------------------

    @Test
    @DisplayName("nothing is shown, and nothing is editable, until storage has answered")
    fun loadingUntilStorageAnswers() = runVmTest(loaded = false) { viewModel ->
        // runVmTest has not published either flow yet.
        assertEquals(WorkoutDayState.Loading, viewModel.uiState.value.day)

        viewModel.saveExtraSession(ExtraSessionDraft(durationMin = "45"))
        viewModel.commitSetCell("ex_1", 0, "weight", "60")
        runCurrent()
        coVerify(exactly = 0) { store.updateLog(any(), any(), any()) }
        coVerify(exactly = 0) { store.transformLogEntry(any(), any(), any(), any()) }

        publish()
        runCurrent()

        assertTrue(viewModel.uiState.value.day is WorkoutDayState.Rest)
    }

    @Test
    @DisplayName("an unreadable plan is not a rest day: no ad-hoc session, and no way to save one")
    fun unreadablePlanRefusesTheExtraSession() = runVmTest { viewModel ->
        // A stored plan whose blob would not decode arrives as a null value.
        publish(plans = mapOf(todayString to null))
        runCurrent()

        assertTrue(viewModel.uiState.value.day is WorkoutDayState.PlanUnavailable)

        viewModel.saveExtraSession(ExtraSessionDraft(durationMin = "45"))
        viewModel.deleteExtraSession()
        viewModel.commitExtraSessionField("duration_min", "45")
        runCurrent()

        coVerify(exactly = 0) { store.updateLog(any(), any(), any()) }
        coVerify(exactly = 0) { store.transformLogEntry(any(), any(), any(), any()) }
        coVerify(exactly = 0) { store.deleteLogEntry(any(), any()) }
    }

    @Test
    @DisplayName("a past or future day never asks for hook status")
    fun noStatusForOtherDays() = runVmTest { viewModel ->
        publish(plans = mapOf("2026-08-01" to plan(sessionId = 9)))
        runCurrent()
        viewModel.selectDate("2026-08-01")
        runCurrent()

        coVerify(exactly = 0) { api.workoutStatus(any()) }
    }

    // ---- the cardio guide ---------------------------------------------------------
    //
    // The lifecycle the spec states in three sentences: a dismiss does not stop
    // the clock, reopening restores the position, and START after a finished run
    // starts a fresh one. The overlay that shows them has no test rig, so they
    // are asserted here, where they are decided.

    @Test
    @DisplayName("opening the guide surfaces the tapped exercise, un-started")
    fun openGuideSurfacesTheExercise() = runVmTest { viewModel ->
        givenCardioPlan()

        viewModel.openGuide("ex_ride")
        runCurrent()

        val guide = viewModel.uiState.value.guide
        assertEquals("Tempo Ride", guide?.title)
        assertEquals(todayString, guide?.key?.date)
        // Opening anchors nothing: the warmup must not burn while the strap goes on.
        assertNull(guide?.run?.startedAtMs)
    }

    @Test
    @DisplayName("the guide opens on today only — a tap on a stale row cannot open one for another day")
    fun openGuideIsDateGated() = runVmTest { viewModel ->
        publish(
            plans = cardioPlan() + mapOf("2026-08-07" to cardioPlan().getValue(todayString)),
        )
        runCurrent()
        viewModel.selectDate("2026-08-07")
        runCurrent()

        viewModel.openGuide("ex_ride")
        runCurrent()

        assertNull(viewModel.uiState.value.guide)

        // Back on today, the same tap opens it.
        viewModel.selectDate(todayString)
        runCurrent()
        viewModel.openGuide("ex_ride")
        runCurrent()

        assertNotNull(viewModel.uiState.value.guide)
    }

    @Test
    @DisplayName("a guide open across midnight stays open, though the affordance that opened it is gone")
    fun anOpenGuideSurvivesMidnight() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var day = today
        publish(plans = cardioPlan())
        val viewModel = CoachViewModel(
            store = store,
            scheduler = mockk(relaxed = true),
            api = api,
            captureState = captureState,
            knownStraps = knownStraps,
            capture = capture,
            captureStore = captureStore,
            guideEvents = guideEvents,
            beatReader = beatReader,
            traceRing = traceRing,
            isOnline = { online },
            errors = errors,
            today = { day },
            now = { nowMs },
            io = StandardTestDispatcher(testScheduler),
        )
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()
        val anchor = viewModel.uiState.value.guide?.run?.startedAtMs

        // 23:50 becomes 00:05. The screen re-reads the clock on the way back.
        day = LocalDate.parse("2026-08-09")
        nowMs += 900_000L
        viewModel.onScreenShown()
        runCurrent()

        assertFalse(viewModel.uiState.value.isEditable)
        assertNotNull(viewModel.uiState.value.guide)
        assertEquals(anchor, viewModel.uiState.value.guide?.run?.startedAtMs)
        // The row underneath it stopped offering to open another, which is the
        // gate doing its job on the half it governs.
        val rows = (viewModel.uiState.value.day as WorkoutDayState.Planned)
            .blocks.flatMap { it.items }.mapNotNull { (it as? BlockItemState.Single)?.exercise }
        assertFalse(rows.first { it.id == "ex_ride" }.hasGuide)
    }

    @Test
    @DisplayName("START anchors the run at the wall clock, and only the open guide's")
    fun startAnchorsTheOpenGuide() = runVmTest { viewModel ->
        givenCardioPlan()

        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()

        assertEquals(nowMs, viewModel.uiState.value.guide?.run?.startedAtMs)
    }

    @Test
    @DisplayName("dismissing closes the overlay and leaves the clock running")
    fun dismissPreservesTheRun() = runVmTest { viewModel ->
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()
        val anchor = viewModel.uiState.value.guide?.run?.startedAtMs

        viewModel.dismissGuide()
        runCurrent()
        assertNull(viewModel.uiState.value.guide)

        // Six minutes later the rider picks the phone back up.
        nowMs += 360_000L
        viewModel.openGuide("ex_ride")
        runCurrent()

        assertEquals(anchor, viewModel.uiState.value.guide?.run?.startedAtMs)
    }

    @Test
    @DisplayName("START on a finished run starts a fresh one rather than resuming it")
    fun restartDiscardsTheFinishedRun() = runVmTest { viewModel ->
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()

        // The ride ends and a second one begins on the same exercise.
        nowMs += 40 * 60_000L
        viewModel.startGuidance()
        runCurrent()

        assertEquals(nowMs, viewModel.uiState.value.guide?.run?.startedAtMs)
    }

    @Test
    @DisplayName("START mid-ride is a no-op — a double-tap cannot re-anchor a running timeline")
    fun startWhileRunningDoesNotReAnchor() = runVmTest { viewModel ->
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()
        val anchor = viewModel.uiState.value.guide?.run?.startedAtMs

        // Ten minutes into a thirty-minute ride, a stale or doubled tap lands.
        // The button is not on screen, but absence is not a guard — this is.
        nowMs += 600_000L
        viewModel.startGuidance()
        runCurrent()

        assertEquals(anchor, viewModel.uiState.value.guide?.run?.startedAtMs)
    }

    @Test
    @DisplayName("an exercise that leaves the plan and returns finds its never-dismissed overlay waiting")
    fun transientPlanInvalidityDoesNotForgetTheOpenGuide() = runVmTest { viewModel ->
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()
        val anchor = viewModel.uiState.value.guide?.run?.startedAtMs

        // A background sync rebuilds the day without the ride for a pass: the
        // overlay closes, but the open key and the run both stay.
        publish(plans = mapOf(todayString to plan(sessionId = 9)))
        runCurrent()
        assertNull(viewModel.uiState.value.guide)

        // The re-planned session arrives with the ride back in it: the guide
        // the rider never dismissed returns, clock intact. Deliberate — the
        // user's open is the standing consent and their dismiss the only
        // revocation; clearing the key on transient invalidity would dismiss
        // a live ride mid-sync.
        publish(plans = cardioPlan())
        runCurrent()
        assertEquals(anchor, viewModel.uiState.value.guide?.run?.startedAtMs)
    }

    @Test
    @DisplayName("two cardio exercises guided in one session keep their own runs")
    fun runsAreKeyedPerExercise() = runVmTest { viewModel ->
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()
        val ride = nowMs

        nowMs += 1_800_000L
        viewModel.openGuide("ex_row")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()

        assertEquals(nowMs, viewModel.uiState.value.guide?.run?.startedAtMs)

        viewModel.openGuide("ex_ride")
        runCurrent()
        assertEquals(ride, viewModel.uiState.value.guide?.run?.startedAtMs)
    }

    @Test
    @DisplayName("START with no guide open does nothing at all")
    fun startWithoutAGuideIsInert() = runVmTest { viewModel ->
        givenCardioPlan()

        viewModel.startGuidance()
        runCurrent()

        assertNull(viewModel.uiState.value.guide)

        viewModel.openGuide("ex_ride")
        runCurrent()
        assertNull(viewModel.uiState.value.guide?.run?.startedAtMs)
    }

    // ---- + 5 MIN ---------------------------------------------------------------------
    //
    // Extension is UI-only by design: the plan's target duration is what
    // completion is measured against, so raising it mid-ride would un-complete
    // an exercise the rider had already satisfied. Everything below asserts
    // that the minutes land on the run and nowhere else.

    @Test
    @DisplayName("+ 5 MIN appends to the live timeline, cumulatively, and writes nothing")
    fun extendAppendsToTheRun() = runVmTest { viewModel ->
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()

        viewModel.extendGuidance()
        viewModel.extendGuidance()
        runCurrent()

        val guide = viewModel.uiState.value.guide
        assertEquals(600, guide?.run?.extensionSec)
        // The plan's own timeline is untouched — half an hour, as authored.
        assertEquals(1_800, guide?.timeline?.plannedTotalSec)
        coVerify(exactly = 0) { store.transformLogEntry(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("+ 5 MIN before START does nothing — the anchor would discard it")
    fun extendBeforeStartIsInert() = runVmTest { viewModel ->
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()

        viewModel.extendGuidance()
        runCurrent()

        assertEquals(0, viewModel.uiState.value.guide?.run?.extensionSec)
    }

    @Test
    @DisplayName("a structured session cannot be extended, however hard the control is tapped")
    fun extendRefusesAStructuredSession() = runVmTest { viewModel ->
        publish(plans = intervalPlan())
        runCurrent()
        viewModel.openGuide("ex_intervals")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()

        viewModel.extendGuidance()
        runCurrent()

        assertEquals(0, viewModel.uiState.value.guide?.run?.extensionSec)
    }

    @Test
    @DisplayName("+ 5 MIN with no guide open does nothing at all")
    fun extendWithoutAGuideIsInert() = runVmTest { viewModel ->
        givenCardioPlan()

        viewModel.extendGuidance()
        runCurrent()

        viewModel.openGuide("ex_ride")
        runCurrent()
        assertEquals(0, viewModel.uiState.value.guide?.run?.extensionSec)
    }

    // ---- what the heart-rate record is told ---------------------------------------------
    //
    // The guide's two user actions are recorded ONLY while a capture is running:
    // without one there is nothing to align them to, the guide is a pure display,
    // and the exercise's completion state stays its only record. Every boundary
    // is derived from the anchor and these rows, so the anchor the record carries
    // has to be the same instant the run itself is anchored at.

    @Test
    @DisplayName("START during a capture records the anchor at the instant the run anchored")
    fun startIsRecordedWithTheRunsOwnInstant() = runVmTest { viewModel ->
        givenRunningCapture(anchoredTo = null)
        publish(plans = intervalPlan())
        runCurrent()
        viewModel.openGuide("ex_intervals")
        runCurrent()

        viewModel.startGuidance()
        runCurrent()

        // One read of the clock serves both, so the stored instant and the
        // timeline the rider is watching cannot disagree.
        assertEquals(nowMs, viewModel.uiState.value.guide?.run?.startedAtMs)
        coVerify(exactly = 1) {
            guideEvents.recordStart(
                date = todayString,
                exerciseKey = "ex_intervals",
                sessionId = "session-1",
                clientTimestampMs = nowMs,
                // The segments as guided, in the coach wire's own segment shape —
                // hr.db keeps its own copy rather than reading the plan back.
                timelineJson = """[{"duration_sec":420,"hr_min":118,"hr_max":134,"label":"warmup"},""" +
                    """{"duration_sec":180,"hr_min":156,"hr_max":174,"label":"hard"},""" +
                    """{"duration_sec":240,"hr_max":142,"label":"easy"}]""",
            )
        }
    }

    @Test
    @DisplayName("a ride with no authored timeline records an empty one, not a missing one")
    fun segmentlessStartRecordsAnEmptyTimeline() = runVmTest { viewModel ->
        givenRunningCapture(anchoredTo = null)
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()

        viewModel.startGuidance()
        runCurrent()

        coVerify(exactly = 1) {
            guideEvents.recordStart(todayString, "ex_ride", "session-1", nowMs, "[]")
        }
    }

    @Test
    @DisplayName("START with no capture running records nothing at all")
    fun startWithoutACaptureIsNotRecorded() = runVmTest { viewModel ->
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()

        viewModel.startGuidance()
        runCurrent()

        // The guide still runs — it is an instrument, not a recorder.
        assertEquals(nowMs, viewModel.uiState.value.guide?.run?.startedAtMs)
        coVerify(exactly = 0) { guideEvents.recordStart(any(), any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("a START that is refused records nothing — a no-op is not an action")
    fun refusedStartIsNotRecorded() = runVmTest { viewModel ->
        givenRunningCapture(anchoredTo = null)
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()

        // A doubled tap ten minutes in: refused by the phase guard, so the
        // record must not gain a second anchor the timeline never took.
        nowMs += 600_000L
        viewModel.startGuidance()
        runCurrent()

        coVerify(exactly = 1) { guideEvents.recordStart(any(), any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("starting again after a finished ride appends a second start row — the record keeps both")
    fun restartAppendsASecondStartEvent() = runVmTest { viewModel ->
        givenRunningCapture(anchoredTo = null)
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()
        val firstAnchor = nowMs

        // Forty minutes on a thirty-minute plan: DONE, then a second ride on
        // the same exercise. The log is append-only — the first start is not
        // rewritten, and analysis picks the latest start, discarding the run
        // it began.
        nowMs += 40 * 60_000L
        viewModel.startGuidance()
        runCurrent()

        coVerify(exactly = 1) {
            guideEvents.recordStart(any(), any(), any(), clientTimestampMs = firstAnchor, timelineJson = any())
        }
        coVerify(exactly = 1) {
            guideEvents.recordStart(any(), any(), any(), clientTimestampMs = nowMs, timelineJson = any())
        }
        coVerify(exactly = 2) { guideEvents.recordStart(any(), any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("+ 5 MIN during a capture records the step it added, at the tap's own instant")
    fun extensionIsRecorded() = runVmTest { viewModel ->
        givenRunningCapture(anchoredTo = null)
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()

        nowMs += 900_000L
        viewModel.extendGuidance()
        runCurrent()

        coVerify(exactly = 1) {
            guideEvents.recordExtend(
                date = todayString,
                exerciseKey = "ex_ride",
                sessionId = "session-1",
                clientTimestampMs = nowMs,
                extensionSec = 300,
            )
        }
    }

    @Test
    @DisplayName("three taps are three rows of five minutes, not one row of fifteen")
    fun eachExtensionTapIsItsOwnRow() = runVmTest { viewModel ->
        givenRunningCapture(anchoredTo = null)
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()

        repeat(3) { viewModel.extendGuidance() }
        runCurrent()

        assertEquals(900, viewModel.uiState.value.guide?.run?.extensionSec)
        coVerify(exactly = 3) {
            guideEvents.recordExtend(any(), any(), any(), any(), extensionSec = 300)
        }
    }

    @Test
    @DisplayName("a strap clipped on mid-ride records the extend and never back-fills the START")
    fun midRideConnectRecordsOnlyTheExtend() = runVmTest { viewModel ->
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        // START pressed before the strap is on: nothing to key a row by, so
        // nothing is written, and nothing goes back to fill it in later.
        viewModel.startGuidance()
        runCurrent()

        nowMs += 600_000L
        givenRunningCapture(anchoredTo = null)
        runCurrent()
        viewModel.extendGuidance()
        runCurrent()

        // The extend lands with the session that was running when it happened —
        // an orphan: a session carrying extends but no start. Analysis reads
        // such a session as unguided and ignores them, which is why the client
        // does not need to suppress it. Emission is all that is pinned here.
        coVerify(exactly = 0) { guideEvents.recordStart(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            guideEvents.recordExtend(todayString, "ex_ride", "session-1", nowMs, 300)
        }
    }

    @Test
    @DisplayName("+ 5 MIN with no capture running records nothing, and still extends")
    fun extensionWithoutACaptureIsNotRecorded() = runVmTest { viewModel ->
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()

        viewModel.extendGuidance()
        runCurrent()

        assertEquals(300, viewModel.uiState.value.guide?.run?.extensionSec)
        coVerify(exactly = 0) { guideEvents.recordExtend(any(), any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("an extension the timeline refuses records nothing")
    fun refusedExtensionIsNotRecorded() = runVmTest { viewModel ->
        givenRunningCapture(anchoredTo = null)
        publish(plans = intervalPlan())
        runCurrent()
        viewModel.openGuide("ex_intervals")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()

        // A structured session is not extensible, so the tap changes nothing —
        // and a record of five minutes nobody rode would be a lie in the data.
        viewModel.extendGuidance()
        runCurrent()

        coVerify(exactly = 0) { guideEvents.recordExtend(any(), any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("the record follows the guide's own key, not the strap's workout anchor")
    fun recordedKeyIsTheGuidesOwn() = runVmTest { viewModel ->
        // The capture is anchored to a different day's session entirely; what
        // identifies a guide action is the exercise it was taken on.
        captureState.value = HrCaptureState(
            isRunning = true,
            sessionId = "session-1",
            workoutDate = "2026-08-07",
            workoutSessionId = 7,
        )
        givenCardioPlan()
        viewModel.openGuide("ex_row")
        runCurrent()

        viewModel.startGuidance()
        runCurrent()

        coVerify(exactly = 1) {
            guideEvents.recordStart(todayString, "ex_row", "session-1", nowMs, any())
        }
    }

    // ---- what a finished ride writes into the log ---------------------------------------
    //
    // The fill is a convenience over the ordinary entry, never an authority: it
    // runs on a dismiss that follows DONE, it writes only into empty fields, and
    // it goes through the same store transaction a typed number does — so
    // completion derivation and sync cannot tell the two apart.

    /** Beats every ten minutes of a thirty-minute ride, climbing. */
    private fun rideBeats(anchorMs: Long) = listOf(0, 600, 1_200, 1_740)
        .mapIndexed { index, second -> TraceSample(anchorMs + second * 1_000L, 120 + index * 10) }

    private fun kotlinx.coroutines.test.TestScope.givenFinishedRide(
        viewModel: CoachViewModel,
        beats: (Long) -> List<TraceSample> = ::rideBeats,
    ): Long {
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()
        val anchor = nowMs
        coEvery { beatReader.beatsBetween(any(), any()) } returns beats(anchor)
        // Thirty-two minutes into a thirty-minute plan: DONE, and two minutes of
        // it spent putting the bike away.
        nowMs += 32 * 60_000L
        return anchor
    }

    @Test
    @DisplayName("dismissing a finished ride fills the entry from the timeline and the beats")
    fun dismissAfterDoneFillsTheEntry() = runVmTest { viewModel ->
        val anchor = givenFinishedRide(viewModel)

        viewModel.dismissGuide()
        runCurrent()

        val entry = storedEntry("ex_ride")
        // Thirty minutes: the timeline's length, not the thirty-two on the clock.
        assertEquals("30", entry.getValue("duration_min").jsonPrimitive.content)
        assertEquals("135", entry.getValue("avg_hr").jsonPrimitive.content)
        assertEquals("150", entry.getValue("max_hr").jsonPrimitive.content)
        // The beats asked for are exactly the ride: anchor to the timeline's end.
        coVerify(exactly = 1) { beatReader.beatsBetween(anchor, anchor + 1_800_000L) }
        // The same write path a typed field takes — no completion toggle, one
        // transaction, nothing that marks the entry as machine-written.
        assertEquals(listOf<CompletionToggle?>(null), completions)
    }

    @Test
    @DisplayName("a field the rider typed is never overwritten — the fill goes into the gaps")
    fun fillNeverOverwritesTypedValues() = runVmTest { viewModel ->
        storedDay = buildJsonObject {
            put(
                "ex_ride",
                buildJsonObject {
                    put("duration_min", JsonPrimitive(45))
                    // An explicit null is how a cleared field is stored, and it
                    // is as empty as a field that never existed.
                    put("avg_hr", JsonNull)
                },
            )
        }
        givenFinishedRide(viewModel)

        viewModel.dismissGuide()
        runCurrent()

        val entry = storedEntry("ex_ride")
        assertEquals("45", entry.getValue("duration_min").jsonPrimitive.content)
        assertEquals("135", entry.getValue("avg_hr").jsonPrimitive.content)
        assertEquals("150", entry.getValue("max_hr").jsonPrimitive.content)
    }

    @Test
    @DisplayName("a dismiss before the timeline ends fills nothing and reads no beats")
    fun dismissBeforeDoneFillsNothing() = runVmTest { viewModel ->
        givenCardioPlan()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()

        // Twelve minutes into a thirty-minute ride: an early bail is the
        // rider's to log, exactly as it was before the guide existed.
        nowMs += 12 * 60_000L
        viewModel.dismissGuide()
        runCurrent()

        assertTrue(storedDay.isEmpty())
        coVerify(exactly = 0) { beatReader.beatsBetween(any(), any()) }
    }

    @Test
    @DisplayName("a ride nobody wore a strap for fills nothing at all")
    fun straplessRideFillsNothing() = runVmTest { viewModel ->
        givenFinishedRide(viewModel) { emptyList() }

        viewModel.dismissGuide()
        runCurrent()

        assertTrue(storedDay.isEmpty())
        coVerify(exactly = 0) { store.transformLogEntry(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("a ride dismissed after its day has gone read-only fills nothing")
    fun fillRespectsTheEditableGate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var day = today
        publish(plans = cardioPlan())
        val viewModel = CoachViewModel(
            store = store,
            scheduler = mockk(relaxed = true),
            api = api,
            captureState = captureState,
            knownStraps = knownStraps,
            capture = capture,
            captureStore = captureStore,
            guideEvents = guideEvents,
            beatReader = beatReader,
            traceRing = traceRing,
            isOnline = { online },
            errors = errors,
            today = { day },
            now = { nowMs },
            io = StandardTestDispatcher(testScheduler),
        )
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()
        viewModel.openGuide("ex_ride")
        runCurrent()
        viewModel.startGuidance()
        runCurrent()
        coEvery { beatReader.beatsBetween(any(), any()) } returns rideBeats(nowMs)

        // The ride finishes after midnight. The day it belongs to is read-only
        // by then — the rider could not type into it either.
        day = LocalDate.parse("2026-08-09")
        nowMs += 32 * 60_000L
        viewModel.onScreenShown()
        runCurrent()
        viewModel.dismissGuide()
        runCurrent()

        assertTrue(storedDay.isEmpty())
    }

    @Test
    @DisplayName("the trace the guide draws is the ring's own window, published beside the state")
    fun traceSamplesArePublished() = runVmTest { viewModel ->
        val recorder = traceRing.beginSession()

        recorder.record(timestampMs = nowMs, bpm = 124)

        assertEquals(listOf(TraceSample(nowMs, 124)), viewModel.traceSamples.value)
    }

    @Test
    @DisplayName("changing the day closes the guide, and it does not spring back open")
    fun changingTheDayClosesTheGuide() = runVmTest { viewModel ->
        publish(plans = cardioPlan() + ("2026-08-07" to plan(sessionId = 9)))
        runCurrent()
        viewModel.openGuide("ex_ride")
        runCurrent()

        viewModel.selectDate("2026-08-07")
        runCurrent()
        assertNull(viewModel.uiState.value.guide)

        viewModel.selectDate(todayString)
        runCurrent()
        assertNull(viewModel.uiState.value.guide)
    }

    private fun cardioPlan(): Map<DateString, PlanDto?> = mapOf(
        todayString to plan(
            blocks = listOf(
                block(
                    blockType = "cardio",
                    exercises = listOf(
                        exercise(
                            id = "ex_ride",
                            name = "Tempo Ride",
                            type = TYPE_DURATION,
                            targetDurationMin = 30,
                        ),
                        exercise(
                            id = "ex_row",
                            name = "Row Intervals",
                            type = TYPE_DURATION,
                            targetDurationMin = 12,
                        ),
                    ),
                ),
            ),
        ),
    )

    /** A structured session: the shape the extension is deliberately not offered on. */
    private fun intervalPlan(): Map<DateString, PlanDto?> = mapOf(
        todayString to plan(
            blocks = listOf(
                block(
                    blockType = "cardio",
                    exercises = listOf(
                        exercise(
                            id = "ex_intervals",
                            name = "Bike Intervals",
                            type = TYPE_DURATION,
                            targetDurationMin = 24,
                            segments = listOf(
                                PlanSegmentDto(durationSec = 420, hrMin = 118, hrMax = 134, label = "warmup"),
                                PlanSegmentDto(durationSec = 180, hrMin = 156, hrMax = 174, label = "hard"),
                                PlanSegmentDto(durationSec = 240, hrMax = 142, label = "easy"),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun kotlinx.coroutines.test.TestScope.givenCardioPlan() {
        publish(plans = cardioPlan())
        runCurrent()
    }

    private fun expandedIds(viewModel: CoachViewModel): Set<String> =
        (viewModel.uiState.value.day as? WorkoutDayState.Planned)
            ?.blocks
            ?.flatMap { it.items }
            ?.mapNotNull { (it as? BlockItemState.Single)?.exercise }
            ?.filter { it.expanded }
            ?.map { it.id }
            ?.toSet()
            .orEmpty()

    private fun todaysPlan(): Map<DateString, PlanDto?> = mapOf(
        todayString to plan(blocks = listOf(block(exercises = listOf(exercise(targetSets = 3))))),
    )

    private fun kotlinx.coroutines.test.TestScope.givenPlan() {
        publish(plans = todaysPlan())
        runCurrent()
    }
}
