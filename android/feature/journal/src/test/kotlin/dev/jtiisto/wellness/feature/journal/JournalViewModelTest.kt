package dev.jtiisto.wellness.feature.journal

import dev.jtiisto.wellness.core.data.journal.EntryField
import dev.jtiisto.wellness.core.data.journal.EntryPatch
import dev.jtiisto.wellness.core.data.journal.JournalSyncStore
import dev.jtiisto.wellness.core.data.journal.JournalUiPrefs
import dev.jtiisto.wellness.core.data.journal.TrackerDto
import dev.jtiisto.wellness.core.data.journal.journalNumberJson
import dev.jtiisto.wellness.core.data.sync.SyncErrorEvents
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import dev.jtiisto.wellness.core.data.sync.SyncStatus
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The pull gesture, which is the only decision this ViewModel makes on its own.
 *
 * `viewModelScope` is hard-wired to `Dispatchers.Main`, so every test installs a
 * [StandardTestDispatcher] on the test's own scheduler — the refresh coroutine,
 * the minimum-visible timer and the sync it waits for then all advance on one
 * clock, which is what makes the floor assertable rather than approximate.
 *
 * Also the checkbox tap's write shape: the patch itself is decided (and
 * tested) in `JournalRowActions`; what is pinned here is that the tap issues
 * ONE `mergeEntry` and stamps only for a numeric part.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.parse("2026-08-08")

    private val store = mockk<JournalSyncStore>(relaxed = true)
    private val prefs = mockk<JournalUiPrefs>(relaxed = true)
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val errors = SyncErrorEvents()

    private val isSyncing = MutableStateFlow(false)
    private var online = true

    /** What the scheduler hands back; completed by default, as a real quick sync is. */
    private var syncJob: Job = Job().apply { complete() }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { store.observeTrackers() } returns flowOf(emptyList())
        every { store.observeEntriesByDate() } returns flowOf(emptyMap())
        every { store.syncStatus } returns MutableStateFlow(SyncStatus.GREEN)
        every { store.isSyncingFlow } returns isSyncing
        every { prefs.expandedCategories } returns flowOf(emptySet())
        every { prefs.valueUpdatedTimes } returns flowOf(emptyMap())
        every { scheduler.requestSync(any()) } answers { syncJob }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(today: LocalDate = this.today) = JournalViewModel(
        store = store,
        prefs = prefs,
        scheduler = scheduler,
        isOnline = { online },
        errors = errors,
        today = { today },
    )

    @Test
    @DisplayName("a pull asks the scheduler for a sync, named as a pull")
    fun refreshRequestsAPullSync() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.refresh()
        advanceUntilIdle()

        verify(exactly = 1) { scheduler.requestSync(SyncScheduler.TRIGGER_PULL) }
    }

    @Test
    @DisplayName("the spinner is held past a sync that returns instantly — a no-op still has to read as an answer")
    fun refreshHoldsTheMinimumVisibleFloor() = runTest(dispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()

        viewModel.refresh()
        runCurrent()
        assertTrue(viewModel.uiState.value.isRefreshing)

        advanceTimeBy(499)
        assertTrue(viewModel.uiState.value.isRefreshing, "the floor has not elapsed")

        advanceTimeBy(2)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    @DisplayName("a second pull while one is in flight is refused rather than queued")
    fun refreshIsNotReentrant() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.refresh()
        runCurrent()
        viewModel.refresh()
        advanceUntilIdle()

        verify(exactly = 1) { scheduler.requestSync(any()) }
    }

    @Test
    @DisplayName("a pull that finishes releases the guard: the next one runs")
    fun refreshRunsAgainOnceSettled() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.refresh()
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        verify(exactly = 2) { scheduler.requestSync(SyncScheduler.TRIGGER_PULL) }
    }

    @Test
    @DisplayName("the spinner outlives a job that completed instantly, waiting out somebody else's flight")
    fun refreshWaitsOutAnAttachedFlight() = runTest(dispatcher) {
        // The scheduler's busy path: pendingSync is set and the job is done,
        // while the real sync belongs to a background flush.
        isSyncing.value = true
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()

        viewModel.refresh()
        advanceTimeBy(5_000)
        assertTrue(viewModel.uiState.value.isRefreshing, "the attached flight is still running")

        isSyncing.value = false
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    @DisplayName("a flight that never ends still releases the spinner at the cap")
    fun refreshCapsTheWait() = runTest(dispatcher) {
        isSyncing.value = true
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()

        viewModel.refresh()
        advanceTimeBy(14_999)
        assertTrue(viewModel.uiState.value.isRefreshing)

        advanceTimeBy(2)
        assertFalse(viewModel.uiState.value.isRefreshing, "a wait with no end is a spinner with no end")
    }

    @Test
    @DisplayName("the spinner spans a slow sync rather than the floor alone")
    fun refreshSpansASlowSync() = runTest(dispatcher) {
        // The job the scheduler hands back, completed by hand from the test body
        // rather than from a background coroutine: `advanceUntilIdle` stops as
        // soon as no FOREGROUND work is left, so a completion parked in the
        // background scope would never run.
        val slow = Job()
        syncJob = slow
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()

        viewModel.refresh()
        advanceTimeBy(3_000)
        assertTrue(viewModel.uiState.value.isRefreshing, "the sync has not come back")

        slow.complete()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    @DisplayName("an offline pull syncs nothing and says so — silence is not an answer to a gesture")
    fun offlineRefreshPostsAMessage() = runTest(dispatcher) {
        online = false
        val messages = mutableListOf<String>()
        backgroundScope.launch { errors.messages.collect { messages += it } }
        val viewModel = viewModel()

        viewModel.refresh()
        advanceUntilIdle()

        verify(exactly = 0) { scheduler.requestSync(any()) }
        assertEquals(
            listOf("Offline — nothing synced. Try again when you're connected."),
            messages,
        )
    }

    @Test
    @DisplayName("the offline message is authored text, carrying nothing off a Throwable")
    fun offlineMessageLeaksNothing() = runTest(dispatcher) {
        online = false
        val messages = mutableListOf<String>()
        backgroundScope.launch { errors.messages.collect { messages += it } }

        viewModel().refresh()
        advanceUntilIdle()

        // The snackbar appears unasked over whatever tab is open; a Ktor
        // response exception's message is the whole response body.
        val message = messages.single()
        assertFalse(message.contains("http", ignoreCase = true), message)
        assertFalse(message.contains("Exception"), message)
    }

    @Test
    @DisplayName("the store's busy flag reaches the state, so the masthead dot can pulse")
    fun isSyncingReachesTheState() = runTest(dispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()

        assertFalse(viewModel.uiState.value.isSyncing)

        isSyncing.value = true
        runCurrent()
        assertTrue(viewModel.uiState.value.isSyncing)
    }

    // ---- the checkbox with pending numeric text (Behavior rule 4, 2026-11-10) --

    private val writeDay = LocalDate.parse("2030-01-10")
    private val glasses = TrackerDto(
        id = "glasses",
        name = "glasses",
        category = "Habits",
        type = "quantifiable",
        extras = JsonObject(mapOf("defaultValue" to JsonPrimitive(3))),
    )

    /** A view model whose tracker list is live, so a tap can find the tracker. */
    private fun TestScope.tapViewModel(): JournalViewModel {
        every { store.observeTrackers() } returns flowOf(listOf(glasses))
        val viewModel = viewModel(today = writeDay)
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()
        return viewModel
    }

    @Test
    @DisplayName("a tick with a typed number issues one write carrying the number and the tick, and stamps")
    fun tickWithPendingTextIsOneStampedWrite() = runTest(dispatcher) {
        val viewModel = tapViewModel()

        viewModel.setChecked("glasses", checked = true, displayedNumber = 3.0, pendingInput = "5")
        advanceUntilIdle()

        val expected = EntryPatch(value = EntryField.Set(journalNumberJson(5.0)), completed = EntryField.Set(true))
        coVerify(exactly = 1) { store.mergeEntry(any(), any(), any()) }
        coVerify(exactly = 1) { store.mergeEntry("2030-01-10", "glasses", expected) }
        coVerify(exactly = 1) { prefs.markValueUpdated("2030-01-10", "glasses") }
    }

    @Test
    @DisplayName("a tick with nothing pending is the write it always was, unstamped")
    fun tickWithNothingPendingIsUnchanged() = runTest(dispatcher) {
        val viewModel = tapViewModel()

        viewModel.setChecked("glasses", checked = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { store.mergeEntry(any(), any(), any()) }
        coVerify(exactly = 1) {
            store.mergeEntry("2030-01-10", "glasses", checkboxPatch(glasses, entry = null, checked = true))
        }
        coVerify(exactly = 0) { prefs.markValueUpdated(any(), any()) }
    }

    @Test
    @DisplayName("a tick whose pending text is a no-op writes the plain tick and does not stamp")
    fun tickWithNoOpPendingTextDoesNotStamp() = runTest(dispatcher) {
        val viewModel = tapViewModel()

        viewModel.setChecked("glasses", checked = true, displayedNumber = 3.0, pendingInput = "3.0")
        viewModel.setChecked("glasses", checked = true, displayedNumber = 3.0, pendingInput = "abc")
        advanceUntilIdle()

        coVerify(exactly = 2) {
            store.mergeEntry("2030-01-10", "glasses", checkboxPatch(glasses, entry = null, checked = true))
        }
        coVerify(exactly = 0) { prefs.markValueUpdated(any(), any()) }
    }
}
