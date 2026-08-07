package dev.jtiisto.wellness.ui.coach

import app.cash.turbine.test
import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.coach.CoachSyncStore
import dev.jtiisto.wellness.core.data.coach.EXTRA_SESSION_KEY
import dev.jtiisto.wellness.core.data.coach.PlanDto
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import dev.jtiisto.wellness.core.data.sync.SyncStatus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The Phase 4 debug tab's ViewModel: what it shows and what its buttons do. */
class CoachDebugViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = mockk<CoachSyncStore>()
    private val scheduler = mockk<SyncScheduler>()

    private val planJson = """{"session_id":1,"day_name":"Push","blocks":[
        {"block_index":0,"block_type":"strength","exercises":[
          {"id":"s1","name":"Bench","type":"strength"}]}]}"""

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { store.todayDate() } returns TODAY
        every { store.syncStatus } returns MutableStateFlow(SyncStatus.GREEN)
        every { scheduler.requestSync() } just Runs
        coEvery { store.updateLog(any(), any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(plan: PlanDto?, log: JsonObject? = null): CoachDebugViewModel {
        every { store.observePlan(TODAY) } returns flowOf(plan)
        every { store.observeLog(TODAY) } returns flowOf(log)
        return CoachDebugViewModel(store, scheduler)
    }

    @Test
    @DisplayName("the state combines today's plan, today's log and the sync dot")
    fun stateCombinesEverything() = runTest(dispatcher) {
        val log = WellnessJson.parseToJsonElement("""{"session_feedback":{},"s1":{"sets":[]}}""").jsonObject
        val model = viewModel(plan(planJson), log)

        model.uiState.test {
            assertEquals(CoachDebugUiState(date = TODAY), awaitItem())
            val loaded = awaitItem()
            assertEquals(TODAY, loaded.date)
            assertEquals("Push", loaded.plan?.dayName)
            assertEquals(setOf("session_feedback", "s1"), loaded.log?.keys)
            assertEquals(SyncStatus.GREEN, loaded.syncStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    @DisplayName("Sync now asks the scheduler rather than calling the store directly")
    fun syncNowGoesThroughTheScheduler() = runTest(dispatcher) {
        // Through the scheduler so the retry/debounce state stays consistent
        // with every other trigger.
        viewModel(plan = null).syncNow()

        verify(exactly = 1) { scheduler.requestSync() }
    }

    @Test
    @DisplayName("the scratch button writes a set against the planned exercise once the plan has loaded")
    fun scratchSetTargetsThePlannedExercise() = runTest(dispatcher) {
        val model = viewModel(plan(planJson))
        model.uiState.test {
            skipItems(1)
            awaitItem()

            model.logScratchSet()
            runCurrent()

            assertEquals("Bench", model.scratchTarget().label)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) {
            store.updateLog(
                TODAY,
                "s1",
                match { it.toString() == """{"sets":[{"set_num":1,"weight":100,"reps":5,"completed":true}]}""" },
            )
        }
    }

    @Test
    @DisplayName("with no plan the scratch button logs an ad-hoc Zone 2 session")
    fun scratchSetFallsBackOnARestDay() = runTest(dispatcher) {
        val model = viewModel(plan = null)

        model.logScratchSet()
        runCurrent()

        assertEquals(EXTRA_SESSION_KEY, model.scratchTarget().exerciseKey)
        coVerify(exactly = 1) {
            store.updateLog(TODAY, EXTRA_SESSION_KEY, match { it.toString() == """{"duration_min":30}""" })
        }
    }

    private fun plan(text: String): PlanDto = WellnessJson.decodeFromString(PlanDto.serializer(), text)

    private companion object {
        const val TODAY = "2026-08-06"
    }
}
