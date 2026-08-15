package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.coach.HookAvailabilityDto
import dev.jtiisto.wellness.core.data.coach.HookButtonState
import dev.jtiisto.wellness.core.data.coach.HookResultDto
import dev.jtiisto.wellness.core.data.coach.WorkoutStatusDto
import dev.jtiisto.wellness.core.data.network.CoachApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The Start/End hook machine.
 *
 * The holder runs on the test's own [TestScope], so its coroutines and its
 * timers share one virtual clock: `runCurrent()` drains the work a call
 * scheduled, and `advanceTimeBy` moves the bounded re-check forward exactly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutHooksTest {

    private val api = mockk<CoachApi>()

    private fun TestScope.hooks() = WorkoutHooks(api = api, scope = this)

    private fun status(
        start: HookResultDto? = null,
        end: HookResultDto? = null,
        startAvailable: Boolean = true,
        endAvailable: Boolean = true,
    ) = WorkoutStatusDto(
        start = start,
        end = end,
        actionsAvailable = HookAvailabilityDto(start = startAvailable, end = endAvailable),
    )

    private val fired = HookResultDto(firedAt = "t1", exitCode = 0)
    private val pending = HookResultDto(firedAt = "t1", exitCode = null)
    private val failed = HookResultDto(firedAt = "t1", exitCode = 3)

    // ---- the one-shot fetch -------------------------------------------------

    @Test
    @DisplayName("an editable session fetches its status once and adopts both phases")
    fun fetchesOnce() = runTest {
        coEvery { api.workoutStatus(7) } returns status(start = fired, end = null)
        val hooks = hooks()

        hooks.onSession(sessionId = 7, isEditable = true)
        runCurrent()

        val state = hooks.state.value
        assertTrue(state.statusLoaded)
        assertFalse(state.statusFetchFailed)
        assertEquals(HookAvailability(start = true, end = true), state.actions)
        assertEquals(HookButtonState.FIRED, state.startState)
        assertEquals(HookButtonState.DEFAULT, state.endState)
    }

    @Test
    @DisplayName("a non-editable day never asks the server and leaves the gate open")
    fun pastDayShortCircuits() = runTest {
        val hooks = hooks()

        hooks.onSession(sessionId = 7, isEditable = false)
        runCurrent()

        val state = hooks.state.value
        assertTrue(state.statusLoaded)
        assertEquals(HookAvailability(), state.actions)
        assertFalse(state.showControls(isEditable = false))
        // Escape 1: with no Start action configured there is nothing to gate.
        assertTrue(state.startGateSatisfied(dataExists = false))
    }

    @Test
    @DisplayName("a rest day with no session never asks the server either")
    fun noSessionShortCircuits() = runTest {
        val hooks = hooks()

        hooks.onSession(sessionId = null, isEditable = true)
        runCurrent()

        assertTrue(hooks.state.value.statusLoaded)
        assertEquals(HookAvailability(), hooks.state.value.actions)
    }

    @Test
    @DisplayName("a failed fetch hides the controls and opens the gate — offline must not lock anyone out")
    fun failedFetchOpensTheGate() = runTest {
        coEvery { api.workoutStatus(7) } throws IOException("offline")
        val hooks = hooks()

        hooks.onSession(sessionId = 7, isEditable = true)
        runCurrent()

        val state = hooks.state.value
        assertTrue(state.statusFetchFailed)
        assertTrue(state.statusLoaded)
        assertEquals(HookAvailability(), state.actions)
        assertFalse(state.showControls(isEditable = true))
        assertTrue(state.startGateSatisfied(dataExists = false))
    }

    @Test
    @DisplayName("a new session resets availability and button state before its fetch lands")
    fun sessionChangeResets() = runTest {
        coEvery { api.workoutStatus(7) } returns status(start = fired)
        coEvery { api.workoutStatus(8) } returns status(startAvailable = false, endAvailable = false)
        val hooks = hooks()

        hooks.onSession(sessionId = 7, isEditable = true)
        runCurrent()
        assertEquals(HookButtonState.FIRED, hooks.state.value.startState)

        hooks.onSession(sessionId = 8, isEditable = true)

        // Mid-flight: nothing of session 7 survives, so its controls cannot be
        // shown against session 8, and the gate stays open while we wait.
        val midFlight = hooks.state.value
        assertFalse(midFlight.statusLoaded)
        assertEquals(HookAvailability(), midFlight.actions)
        assertEquals(HookButtonState.DEFAULT, midFlight.startState)
        assertFalse(midFlight.showControls(isEditable = true))
        assertTrue(midFlight.startGateSatisfied(dataExists = false))

        runCurrent()
        assertTrue(hooks.state.value.statusLoaded)
    }

    @Test
    @DisplayName("controls appear only when the day is editable, the status is in, and a hook exists")
    fun showControlsMatrix() {
        val loaded = WorkoutHooksState(statusLoaded = true, actions = HookAvailability(start = true))

        assertTrue(loaded.showControls(isEditable = true))
        assertFalse(loaded.showControls(isEditable = false))
        assertFalse(loaded.copy(statusLoaded = false).showControls(isEditable = true))
        assertFalse(loaded.copy(actions = HookAvailability()).showControls(isEditable = true))
        assertTrue(
            loaded.copy(actions = HookAvailability(start = false, end = true)).showControls(isEditable = true),
        )
    }

    // ---- the four gate escapes, each alone --------------------------------------

    @Test
    @DisplayName("escape 1: no Start action configured")
    fun escapeNoStartAction() {
        val state = WorkoutHooksState(statusLoaded = true, actions = HookAvailability(start = false, end = true))

        assertTrue(state.startGateSatisfied(dataExists = false))
    }

    @Test
    @DisplayName("escape 2: Start was clicked, whatever came of it")
    fun escapeStartClicked() {
        val gated = WorkoutHooksState(statusLoaded = true, actions = HookAvailability(start = true))

        assertFalse(gated.startGateSatisfied(dataExists = false))
        for (state in listOf(
            HookButtonState.PENDING,
            HookButtonState.FIRED,
            HookButtonState.FAILED,
            HookButtonState.LOCKED,
        )) {
            assertTrue(
                gated.copy(startState = state).startGateSatisfied(dataExists = false),
                "$state should satisfy the gate",
            )
        }
    }

    @Test
    @DisplayName("escape 3: exercise data already exists")
    fun escapeDataExists() {
        val gated = WorkoutHooksState(statusLoaded = true, actions = HookAvailability(start = true))

        assertTrue(gated.startGateSatisfied(dataExists = true))
    }

    @Test
    @DisplayName("escape 4: the status fetch failed")
    fun escapeFetchFailed() {
        val gated = WorkoutHooksState(
            statusLoaded = true,
            actions = HookAvailability(start = true),
            statusFetchFailed = true,
        )

        assertTrue(gated.startGateSatisfied(dataExists = false))
    }

    @Test
    @DisplayName("effectiveEditable needs the day AND the gate")
    fun effectiveEditable() {
        val open = WorkoutHooksState(statusLoaded = true, actions = HookAvailability(start = false))
        val shut = WorkoutHooksState(statusLoaded = true, actions = HookAvailability(start = true))

        assertTrue(open.effectiveEditable(isEditable = true, dataExists = false))
        assertFalse(open.effectiveEditable(isEditable = false, dataExists = false))
        assertFalse(shut.effectiveEditable(isEditable = true, dataExists = false))
    }

    // ---- firing -------------------------------------------------------------------

    @Test
    @DisplayName("a fire goes PENDING then FIRED, and the gate opens on the click alone")
    fun fireSucceeds() = runTest {
        coEvery { api.workoutStatus(7) } returns status()
        coEvery { api.fireWorkoutHook(7, HookAction.START) } returns Unit
        val hooks = hooks()
        hooks.onSession(7, isEditable = true)
        runCurrent()

        hooks.fire(HookAction.START)
        assertEquals(HookButtonState.PENDING, hooks.state.value.startState)
        assertTrue(hooks.state.value.startGateSatisfied(dataExists = false))

        runCurrent()
        assertEquals(HookButtonState.FIRED, hooks.state.value.startState)
        assertTrue(hooks.state.value.canUndo(HookAction.START))
    }

    @Test
    @DisplayName("a failed fire lands on FAILED, but the gate is already open")
    fun fireFails() = runTest {
        coEvery { api.workoutStatus(7) } returns status()
        coEvery { api.fireWorkoutHook(7, HookAction.START) } throws IOException("no route")
        val hooks = hooks()
        hooks.onSession(7, isEditable = true)
        runCurrent()

        hooks.fire(HookAction.START)
        runCurrent()

        assertEquals(HookButtonState.FAILED, hooks.state.value.startState)
        assertTrue(hooks.state.value.startGateSatisfied(dataExists = false))
        // A failed Start offers a retry rather than an undo.
        assertTrue(hooks.state.value.canFire(HookAction.START))
        assertFalse(hooks.state.value.canUndo(HookAction.START))
    }

    // ---- fired -> locked, by both paths ------------------------------------------------

    @Test
    @DisplayName("reactive promotion: a fired Start locks once exercise data appears")
    fun reactivePromotion() = runTest {
        coEvery { api.workoutStatus(7) } returns status()
        coEvery { api.fireWorkoutHook(7, HookAction.START) } returns Unit
        val hooks = hooks()
        hooks.onSession(7, isEditable = true)
        runCurrent()

        hooks.fire(HookAction.START)
        runCurrent()
        assertEquals(HookButtonState.FIRED, hooks.state.value.startState)

        hooks.onDataExists(true)

        assertEquals(HookButtonState.LOCKED, hooks.state.value.startState)
        assertFalse(hooks.state.value.canUndo(HookAction.START))
    }

    @Test
    @DisplayName("synchronous promotion: a Start that succeeds over existing data never renders a FIRED frame")
    fun synchronousPromotionNeverShowsUndo() = runTest {
        coEvery { api.workoutStatus(7) } returns status()
        coEvery { api.fireWorkoutHook(7, HookAction.START) } returns Unit
        val hooks = hooks()
        hooks.onSession(7, isEditable = true)
        runCurrent()
        hooks.onDataExists(true)

        // Collected UNCONFINED on purpose. A StateFlow conflates, so a collector
        // on the standard dispatcher would simply never be resumed between two
        // successive assignments — and would report "no FIRED frame" even if the
        // implementation went through one. An unconfined collector is resumed
        // inside each assignment, so it sees every value the flow takes on, and
        // a two-step FIRED-then-LOCKED implementation fails this test.
        val frames = mutableListOf<HookButtonState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            hooks.state.collect { frames += it.startState }
        }
        runCurrent()

        hooks.fire(HookAction.START)
        runCurrent()

        assertFalse(HookButtonState.FIRED in frames, "a FIRED frame would render an undoable mid-workout Start: $frames")
        assertTrue(HookButtonState.PENDING in frames, "the optimistic transition is still expected: $frames")
        assertEquals(HookButtonState.LOCKED, frames.last())
    }

    @Test
    @DisplayName("the unconfined collector really does catch a two-step transition")
    fun theFrameCollectorIsNotVacuous() = runTest {
        // Guards the test above: if StateFlow conflation hid intermediate values
        // from this collector, the FIRED assertion there would pass for free.
        val flow = kotlinx.coroutines.flow.MutableStateFlow(HookButtonState.DEFAULT)
        val frames = mutableListOf<HookButtonState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { frames += it }
        }
        runCurrent()

        flow.value = HookButtonState.FIRED
        flow.value = HookButtonState.LOCKED

        assertEquals(
            listOf(HookButtonState.DEFAULT, HookButtonState.FIRED, HookButtonState.LOCKED),
            frames,
        )
    }

    @Test
    @DisplayName("a session change clears dataExists, so the old day's sets cannot lock the new Start")
    fun sessionChangeClearsDataExists() = runTest {
        coEvery { api.workoutStatus(7) } returns status(start = fired)
        val hooks = hooks()

        // Yesterday's workout was logged; today's Start has already fired.
        hooks.onDataExists(true)
        hooks.onSession(7, isEditable = true)
        runCurrent()

        // Promotion is one-way, so inheriting the old day's flag would take the
        // Undo away on a workout where nothing has been logged yet.
        assertFalse(hooks.state.value.dataExists)
        assertEquals(HookButtonState.FIRED, hooks.state.value.startState)
        assertTrue(hooks.state.value.canUndo(HookAction.START))
    }

    @Test
    @DisplayName("once this day's log publishes, the fired Start locks")
    fun promotionAfterTheLogArrives() = runTest {
        coEvery { api.workoutStatus(7) } returns status(start = fired)
        val hooks = hooks()

        hooks.onSession(7, isEditable = true)
        runCurrent()
        assertEquals(HookButtonState.FIRED, hooks.state.value.startState)

        // What the ViewModel's log collector republishes for the new date.
        hooks.onDataExists(true)

        assertEquals(HookButtonState.LOCKED, hooks.state.value.startState)
    }

    // ---- undo --------------------------------------------------------------------------

    @Test
    @DisplayName("undo visibility is asymmetric: Start only when FIRED, End when FIRED or FAILED")
    fun undoVisibility() {
        fun withStates(start: HookButtonState, end: HookButtonState) =
            WorkoutHooksState(startState = start, endState = end)

        assertTrue(withStates(HookButtonState.FIRED, HookButtonState.DEFAULT).canUndo(HookAction.START))
        assertFalse(withStates(HookButtonState.FAILED, HookButtonState.DEFAULT).canUndo(HookAction.START))
        assertFalse(withStates(HookButtonState.LOCKED, HookButtonState.DEFAULT).canUndo(HookAction.START))
        assertFalse(withStates(HookButtonState.PENDING, HookButtonState.DEFAULT).canUndo(HookAction.START))

        assertTrue(withStates(HookButtonState.DEFAULT, HookButtonState.FIRED).canUndo(HookAction.END))
        assertTrue(withStates(HookButtonState.DEFAULT, HookButtonState.FAILED).canUndo(HookAction.END))
        assertFalse(withStates(HookButtonState.DEFAULT, HookButtonState.DEFAULT).canUndo(HookAction.END))
    }

    @Test
    @DisplayName("a successful undo returns the button to DEFAULT")
    fun undoSucceeds() = runTest {
        coEvery { api.workoutStatus(7) } returns status(start = fired)
        coEvery { api.undoWorkoutHook(7, HookAction.START) } returns Unit
        val hooks = hooks()
        hooks.onSession(7, isEditable = true)
        runCurrent()

        hooks.undo(HookAction.START)
        assertEquals(HookButtonState.PENDING, hooks.state.value.startState)
        runCurrent()

        assertEquals(HookButtonState.DEFAULT, hooks.state.value.startState)
    }

    @Test
    @DisplayName("a failed undo re-reads the status and shows what the server actually holds")
    fun undoFailureRecovers() = runTest {
        coEvery { api.workoutStatus(7) } returnsMany listOf(status(start = fired), status(start = failed))
        coEvery { api.undoWorkoutHook(7, HookAction.START) } throws IOException("gone")
        val hooks = hooks()
        hooks.onSession(7, isEditable = true)
        runCurrent()

        hooks.undo(HookAction.START)
        runCurrent()

        assertEquals(HookButtonState.FAILED, hooks.state.value.startState)
    }

    @Test
    @DisplayName("an undo whose recovery read also fails falls back to DEFAULT rather than sticking on PENDING")
    fun undoDoubleFailure() = runTest {
        coEvery { api.workoutStatus(7) } returns status(start = fired) andThenThrows IOException("still gone")
        coEvery { api.undoWorkoutHook(7, HookAction.START) } throws IOException("gone")
        val hooks = hooks()
        hooks.onSession(7, isEditable = true)
        runCurrent()

        hooks.undo(HookAction.START)
        runCurrent()

        // DEFAULT offers the action again, and firing twice is idempotent
        // server-side, so this is the safe landing.
        assertEquals(HookButtonState.DEFAULT, hooks.state.value.startState)
    }

    // ---- the bounded pending re-check (deviation 10) ---------------------------------------

    @Test
    @DisplayName("a pending hook is re-read every 15s and settles as soon as the server resolves it")
    fun pendingRecheckResolves() = runTest {
        coEvery { api.workoutStatus(7) } returnsMany listOf(
            status(start = pending),
            status(start = pending),
            status(start = fired),
        )
        val hooks = hooks()

        hooks.onSession(7, isEditable = true)
        runCurrent()
        assertEquals(HookButtonState.PENDING, hooks.state.value.startState)

        advanceTimeBy(15_001)
        assertEquals(HookButtonState.PENDING, hooks.state.value.startState)

        advanceTimeBy(15_000)
        assertEquals(HookButtonState.FIRED, hooks.state.value.startState)

        // Resolved: nothing further is asked, however long we wait.
        advanceTimeBy(600_000)
        coVerify(exactly = 3) { api.workoutStatus(7) }
    }

    @Test
    @DisplayName("the re-check stops one interval past the server's 120s hook deadline")
    fun pendingRecheckIsBounded() = runTest {
        coEvery { api.workoutStatus(7) } returns status(start = pending)
        val hooks = hooks()

        hooks.onSession(7, isEditable = true)
        runCurrent()

        // Eight reads up to the deadline, then one final read after it — the
        // server kills the hook at 120s, so its exit code lands just past that.
        advanceTimeBy(600_000)

        coVerify(exactly = 10) { api.workoutStatus(7) }
        assertEquals(HookButtonState.PENDING, hooks.state.value.startState)
    }

    @Test
    @DisplayName("nothing pending means no re-check at all")
    fun noRecheckWhenNothingPending() = runTest {
        coEvery { api.workoutStatus(7) } returns status(start = fired)
        val hooks = hooks()

        hooks.onSession(7, isEditable = true)
        runCurrent()
        advanceTimeBy(600_000)

        coVerify(exactly = 1) { api.workoutStatus(7) }
    }

    @Test
    @DisplayName("leaving the session stops the re-check")
    fun recheckStopsOnSessionChange() = runTest {
        coEvery { api.workoutStatus(7) } returns status(start = pending)
        coEvery { api.workoutStatus(8) } returns status(start = fired)
        val hooks = hooks()

        hooks.onSession(7, isEditable = true)
        runCurrent()
        advanceTimeBy(16_000)

        hooks.onSession(8, isEditable = true)
        runCurrent()
        advanceTimeBy(600_000)

        // Two reads for session 7 (the initial one and one re-check), then the
        // single read the new session made.
        coVerify(exactly = 2) { api.workoutStatus(7) }
        coVerify(exactly = 1) { api.workoutStatus(8) }
    }

    @Test
    @DisplayName("a failed re-check is a blip: the controls stay put and the loop keeps trying")
    fun recheckFailureIsIgnored() = runTest {
        coEvery { api.workoutStatus(7) } returns status(start = pending) andThenThrows
            IOException("blip") andThen status(start = fired)
        val hooks = hooks()

        hooks.onSession(7, isEditable = true)
        runCurrent()

        advanceTimeBy(15_001)
        // The dropped read changed nothing — availability and the gate survive.
        assertEquals(HookButtonState.PENDING, hooks.state.value.startState)
        assertFalse(hooks.state.value.statusFetchFailed)
        assertEquals(HookAvailability(start = true, end = true), hooks.state.value.actions)

        advanceTimeBy(15_000)
        assertEquals(HookButtonState.FIRED, hooks.state.value.startState)
    }

    @Test
    @DisplayName("a re-check never overwrites a button that is not pending")
    fun recheckOnlyResolvesPendingButtons() = runTest {
        coEvery { api.workoutStatus(7) } returnsMany listOf(
            status(start = fired, end = pending),
            // The server has forgotten the start (it was undone elsewhere); the
            // re-check must not act on that, only on the pending End.
            status(start = null, end = fired),
        )
        val hooks = hooks()

        hooks.onSession(7, isEditable = true)
        runCurrent()
        advanceTimeBy(15_001)

        assertEquals(HookButtonState.FIRED, hooks.state.value.startState)
        assertEquals(HookButtonState.FIRED, hooks.state.value.endState)
    }

    // ---- button affordances -----------------------------------------------------------------

    @Test
    @DisplayName("canFire is true only from DEFAULT and FAILED")
    fun canFireStates() {
        val states = mapOf(
            HookButtonState.DEFAULT to true,
            HookButtonState.FAILED to true,
            HookButtonState.PENDING to false,
            HookButtonState.FIRED to false,
            HookButtonState.LOCKED to false,
        )

        for ((state, expected) in states) {
            assertEquals(
                expected,
                WorkoutHooksState(startState = state).canFire(HookAction.START),
                "canFire($state)",
            )
        }
    }

    @Test
    @DisplayName("firing with no session in scope does nothing")
    fun fireWithoutASession() = runTest {
        val hooks = hooks()

        hooks.fire(HookAction.START)
        hooks.undo(HookAction.END)
        runCurrent()

        assertEquals(HookButtonState.DEFAULT, hooks.state.value.startState)
        assertEquals(HookButtonState.DEFAULT, hooks.state.value.endState)
    }
}
