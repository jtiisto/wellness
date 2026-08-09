package dev.jtiisto.wellness.feature.trends

import dev.jtiisto.wellness.core.data.db.TrendsMetaDao
import dev.jtiisto.wellness.core.data.db.TrendsMetaEntity
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.trends.CardioDto
import dev.jtiisto.wellness.core.data.trends.CompositionDto
import dev.jtiisto.wellness.core.data.trends.ExerciseDetailDto
import dev.jtiisto.wellness.core.data.trends.ExerciseInfo
import dev.jtiisto.wellness.core.data.trends.ExercisesDto
import dev.jtiisto.wellness.core.data.trends.FetchResult
import dev.jtiisto.wellness.core.data.trends.LabsDto
import dev.jtiisto.wellness.core.data.trends.RecoveryDto
import dev.jtiisto.wellness.core.data.trends.TrackerDetailDto
import dev.jtiisto.wellness.core.data.trends.TrackersDto
import dev.jtiisto.wellness.core.data.trends.TrendsPrefs
import dev.jtiisto.wellness.core.data.trends.TrendsRepository
import dev.jtiisto.wellness.core.data.trends.VolumeDto
import dev.jtiisto.wellness.core.data.trends.WeightDto
import dev.jtiisto.wellness.core.data.trends.WeightPoint
import dev.jtiisto.wellness.feature.trends.chart.exerciseSummary
import dev.jtiisto.wellness.feature.trends.chart.labObs
import dev.jtiisto.wellness.feature.trends.chart.labPanel
import dev.jtiisto.wellness.feature.trends.chart.labTest
import dev.jtiisto.wellness.feature.trends.chart.tracker
import dev.jtiisto.wellness.feature.trends.chart.trackerDetail
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDate

/**
 * When the five screens fetch, and what they do when a fetch does not come back.
 *
 * `viewModelScope` is hard-wired to `Dispatchers.Main`, so every test installs a
 * [StandardTestDispatcher] on the test's own scheduler — the collectors, the
 * fetches and the delays they simulate then all advance on one clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val today = { LocalDate.parse("2026-08-08") }

    private val repository = mockk<TrendsRepository>()
    private val debugLog = mockk<DebugLog>(relaxed = true)
    private val dao = FakeTrendsMetaDao()
    private val prefs = TrendsPrefs(dao)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- Overview ----------------------------------------------------------

    private fun overviewViewModel() = OverviewViewModel(repository, prefs, debugLog, today)

    @Test
    @DisplayName("activating a screen fetches each of its slices exactly once")
    fun overviewFetchesOnce() = runTest(dispatcher) {
        stubOverview()
        val viewModel = overviewViewModel()

        viewModel.onActive()
        advanceUntilIdle()

        assertEquals(WEIGHT, viewModel.uiState.value.weight.valueOrNull)
        coVerify(exactly = 1) { repository.overview() }
        coVerify(exactly = 1) { repository.weight(any(), any(), any()) }
    }

    @Test
    @DisplayName("a rotation does not refetch what is already loaded")
    fun overviewSurvivesConfigRecreation() = runTest(dispatcher) {
        stubOverview()
        val viewModel = overviewViewModel()
        viewModel.onActive()
        advanceUntilIdle()

        // Leaving the composition and coming straight back is what a rotation
        // looks like from here; the ViewModel itself survived it.
        viewModel.onInactive()
        viewModel.onActive()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.overview() }
        coVerify(exactly = 1) { repository.weight(any(), any(), any()) }
    }

    @Test
    @DisplayName("a load cancelled mid-flight IS retried when the screen comes back")
    fun overviewRetriesAnInterruptedLoad() = runTest(dispatcher) {
        stubOverview(weightDelayMs = 1_000)
        val viewModel = overviewViewModel()
        viewModel.onActive()
        advanceTimeBy(200)
        runCurrent()

        viewModel.onInactive()
        viewModel.onActive()
        advanceUntilIdle()

        // Twice: the first attempt never finished, and its slice was still
        // showing Loading, so leaving it there would have been a dead screen.
        coVerify(exactly = 2) { repository.weight(any(), any(), any()) }
        assertEquals(WEIGHT, viewModel.uiState.value.weight.valueOrNull)
    }

    @Test
    @DisplayName("a range change refetches weight and leaves the parameterless overview alone")
    fun overviewRangeChange() = runTest(dispatcher) {
        stubOverview()
        val viewModel = overviewViewModel()
        viewModel.onActive()
        advanceUntilIdle()

        prefs.setRange("4w")
        advanceUntilIdle()

        assertEquals("4w", viewModel.uiState.value.range)
        coVerify(exactly = 1) { repository.overview() }
        coVerify(exactly = 1) { repository.weight("2026-07-11", "2026-08-08", "4w") }
        coVerify(exactly = 1) { repository.weight("2026-05-16", "2026-08-08", "12w") }
    }

    @Test
    @DisplayName("flipping the range mid-flight cancels the fetch it superseded")
    fun overviewDropsSupersededFetch() = runTest(dispatcher) {
        var twelveWeekStarted = false
        var twelveWeekCompleted = false
        coEvery { repository.overview() } returns FetchResult(OVERVIEW, null)
        coEvery { repository.weight(any(), any(), "12w") } coAnswers {
            twelveWeekStarted = true
            delay(1_000)
            twelveWeekCompleted = true
            FetchResult(WeightDto(available = true, series = listOf(WeightPoint("2026-07-01", 12.0))), null)
        }
        coEvery { repository.weight(any(), any(), "4w") } returns FetchResult(WEIGHT, null)

        val viewModel = overviewViewModel()
        viewModel.onActive()
        advanceTimeBy(200)
        runCurrent()
        assertTrue(twelveWeekStarted, "sanity: the 12-week fetch has to be in flight to be superseded")

        prefs.setRange("4w")
        advanceUntilIdle()

        // `collectLatest` cancels the block a new key supersedes, so the old
        // fetch never reaches its assignment. A plain `collect` would let it run
        // to completion and write 12-week data under a toolbar saying 4w.
        assertFalse(twelveWeekCompleted, "a superseded fetch ran to completion")
        assertEquals("4w", viewModel.uiState.value.range)
        assertEquals(WEIGHT, viewModel.uiState.value.weight.valueOrNull)
    }

    @Test
    @DisplayName("a slice goes back to Loading the moment its own fetch starts")
    fun overviewSliceResetsToLoading() = runTest(dispatcher) {
        stubOverview(weightDelayMs = 1_000)
        val viewModel = overviewViewModel()
        viewModel.onActive()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.weight is Slice.Ready)

        prefs.setRange("4w")
        advanceTimeBy(200)
        runCurrent()

        assertEquals(Slice.Loading, viewModel.uiState.value.weight)
    }

    @Test
    @DisplayName("the overview tiles are the screen: their failure is the screen's")
    fun overviewFailureIsTheScreensFailure() = runTest(dispatcher) {
        coEvery { repository.overview() } throws IOException("offline")
        coEvery { repository.weight(any(), any(), any()) } returns FetchResult(WEIGHT, null)

        val viewModel = overviewViewModel()
        viewModel.onActive()
        advanceUntilIdle()

        assertEquals(Slice.Error("Offline — check connection"), viewModel.uiState.value.overview)
        // The weight card is unaffected — the two slices are independent.
        assertEquals(WEIGHT, viewModel.uiState.value.weight.valueOrNull)
    }

    @Test
    @DisplayName("a weight failure costs its card and nothing else")
    fun overviewWeightFailureIsSwallowed() = runTest(dispatcher) {
        coEvery { repository.overview() } returns FetchResult(OVERVIEW, null)
        coEvery { repository.weight(any(), any(), any()) } throws IOException("offline")

        val viewModel = overviewViewModel()
        viewModel.onActive()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.overview is Slice.Ready)
        assertTrue(viewModel.uiState.value.weight is Slice.Error)
    }

    @Test
    @DisplayName("retry re-runs every fetch on the screen, error state and all")
    fun overviewRetry() = runTest(dispatcher) {
        coEvery { repository.overview() } throws IOException("offline")
        coEvery { repository.weight(any(), any(), any()) } returns FetchResult(WEIGHT, null)
        val viewModel = overviewViewModel()
        viewModel.onActive()
        advanceUntilIdle()

        coEvery { repository.overview() } returns FetchResult(OVERVIEW, null)
        viewModel.retry()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.overview is Slice.Ready)
        coVerify(exactly = 2) { repository.overview() }
        coVerify(exactly = 2) { repository.weight(any(), any(), any()) }
    }

    @Test
    @DisplayName("a cached slice reports the age of the copy it is holding")
    fun overviewCarriesStaleness() = runTest(dispatcher) {
        coEvery { repository.overview() } returns FetchResult(OVERVIEW, null)
        coEvery { repository.weight(any(), any(), any()) } returns FetchResult(WEIGHT, 12_345L)

        val viewModel = overviewViewModel()
        viewModel.onActive()
        advanceUntilIdle()

        assertEquals(listOf(12_345L), staleStamps(viewModel.uiState.value.overview, viewModel.uiState.value.weight))
    }

    // ---- Strength ----------------------------------------------------------

    private fun strengthViewModel() = StrengthViewModel(repository, prefs, debugLog, today)

    @Test
    @DisplayName("the exercise list settles the selection, and the choice is remembered")
    fun strengthResolvesSelection() = runTest(dispatcher) {
        stubStrength()
        val viewModel = strengthViewModel()

        viewModel.onActive()
        advanceUntilIdle()

        assertEquals("fixture-press", viewModel.uiState.value.selected)
        assertEquals("fixture-press", dao.rows[TrendsPrefs.KEY_EXERCISE])
        coVerify(exactly = 1) { repository.strengthExercise("fixture-press", any(), any(), "12w") }
    }

    @Test
    @DisplayName("a pick made while a list is still settling is never undone by the fallback")
    fun strengthFallbackNeverOverwritesANewerPick() = runTest(dispatcher) {
        // The setup is the whole bug: the remembered exercise has fallen out of
        // range, so the fallback WILL want to move the selection — and the user
        // gets there first, while their write is still in flight.
        dao.rows[TrendsPrefs.KEY_EXERCISE] = "fixture-squat"
        dao.writeDelayMs = 2_000
        coEvery { repository.strengthExercises(any(), any(), any()) } returns FetchResult(
            ExercisesDto(
                listOf(
                    exerciseSummary("fixture-deadlift", "Fixture Deadlift"),
                    exerciseSummary("fixture-press", "Fixture Press"),
                ),
            ),
            null,
        )
        coEvery { repository.strengthVolume(any(), any(), any()) } coAnswers {
            delay(1_000)
            FetchResult(VolumeDto(emptyList()), null)
        }
        coEvery { repository.strengthExercise(any(), any(), any(), any()) } returns
            FetchResult(EXERCISE_DETAIL, null)

        val viewModel = strengthViewModel()
        viewModel.onActive()
        // The exercise list has landed, so the picker is tappable — but volume
        // has not, so the fallback has not run yet.
        advanceTimeBy(200)
        runCurrent()
        viewModel.selectExercise("fixture-press")
        advanceUntilIdle()

        assertEquals("fixture-press", viewModel.uiState.value.selected)
        assertEquals("fixture-press", dao.rows[TrendsPrefs.KEY_EXERCISE])
        coVerify(exactly = 0) { repository.strengthExercise("fixture-deadlift", any(), any(), any()) }
    }

    @Test
    @DisplayName("a pick the new range no longer offers still falls back")
    fun strengthFallbackStillRunsWhenTheSelectionIsGone() = runTest(dispatcher) {
        dao.rows[TrendsPrefs.KEY_EXERCISE] = "fixture-retired"
        stubStrength()
        val viewModel = strengthViewModel()

        viewModel.onActive()
        advanceUntilIdle()

        // Reconciling against the in-memory selection must not mean never
        // reconciling: the remembered slug is genuinely absent, so the first
        // item wins and the replacement is written down.
        assertEquals("fixture-press", viewModel.uiState.value.selected)
        assertEquals("fixture-press", dao.rows[TrendsPrefs.KEY_EXERCISE])
    }

    @Test
    @DisplayName("a selection that is still on offer is left alone, and nothing is written")
    fun strengthLeavesAValidSelectionUntouched() = runTest(dispatcher) {
        dao.rows[TrendsPrefs.KEY_EXERCISE] = "fixture-squat"
        stubStrength()
        val viewModel = strengthViewModel()
        viewModel.onActive()
        advanceUntilIdle()

        prefs.setRange("4w")
        advanceUntilIdle()

        assertEquals("fixture-squat", viewModel.uiState.value.selected)
    }

    @Test
    @DisplayName("a remembered exercise survives; one that fell out of range falls back silently")
    fun strengthSelectionFallback() = runTest(dispatcher) {
        stubStrength()
        dao.rows[TrendsPrefs.KEY_EXERCISE] = "fixture-squat"
        val kept = strengthViewModel()
        kept.onActive()
        advanceUntilIdle()
        assertEquals("fixture-squat", kept.uiState.value.selected)
        kept.onInactive()

        dao.rows[TrendsPrefs.KEY_EXERCISE] = "fixture-deleted"
        val fallen = strengthViewModel()
        fallen.onActive()
        advanceUntilIdle()

        assertEquals("fixture-press", fallen.uiState.value.selected)
        assertTrue(fallen.uiState.value.detail is Slice.Ready)
    }

    @Test
    @DisplayName("an empty exercise list selects nothing rather than asking for a missing one")
    fun strengthEmptyList() = runTest(dispatcher) {
        stubStrength(exercises = emptyList())
        dao.rows[TrendsPrefs.KEY_EXERCISE] = "fixture-deleted"
        val viewModel = strengthViewModel()

        viewModel.onActive()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selected)
        coVerify(exactly = 0) { repository.strengthExercise(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("picking an exercise refetches only its progression")
    fun strengthSelectRefetchesDetailOnly() = runTest(dispatcher) {
        stubStrength()
        val viewModel = strengthViewModel()
        viewModel.onActive()
        advanceUntilIdle()

        viewModel.selectExercise("fixture-squat")
        advanceUntilIdle()

        assertEquals("fixture-squat", viewModel.uiState.value.selected)
        coVerify(exactly = 1) { repository.strengthExercises(any(), any(), any()) }
        coVerify(exactly = 1) { repository.strengthVolume(any(), any(), any()) }
        coVerify(exactly = 1) { repository.strengthExercise("fixture-squat", any(), any(), any()) }
    }

    @Test
    @DisplayName("one failed slice never takes its siblings down with it")
    fun strengthSlicesAreIsolated() = runTest(dispatcher) {
        stubStrength()
        coEvery { repository.strengthVolume(any(), any(), any()) } throws IOException("offline")
        val viewModel = strengthViewModel()

        viewModel.onActive()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.volume is Slice.Error)
        assertTrue(viewModel.uiState.value.exercises is Slice.Ready)
        // The selection still settled, so the progression chart still loaded.
        assertTrue(viewModel.uiState.value.detail is Slice.Ready)
    }

    @Test
    @DisplayName("the RPE toggle is a view state, not a fetch")
    fun strengthRpeToggle() = runTest(dispatcher) {
        stubStrength()
        val viewModel = strengthViewModel()
        viewModel.onActive()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showRpe)

        viewModel.toggleRpe()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showRpe)
        coVerify(exactly = 1) { repository.strengthExercise(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("a range change refetches the lists AND the progression")
    fun strengthRangeChange() = runTest(dispatcher) {
        stubStrength()
        val viewModel = strengthViewModel()
        viewModel.onActive()
        advanceUntilIdle()

        prefs.setRange("6m")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.strengthExercises(any(), any(), "6m") }
        coVerify(exactly = 1) { repository.strengthExercise("fixture-press", any(), any(), "6m") }
    }

    // ---- Cardio ------------------------------------------------------------

    @Test
    @DisplayName("cardio is one payload, fetched once per range")
    fun cardioFetches() = runTest(dispatcher) {
        coEvery { repository.cardio(any(), any(), any()) } returns FetchResult(CARDIO, null)
        val viewModel = CardioViewModel(repository, prefs, debugLog, today)

        viewModel.onActive()
        advanceUntilIdle()
        prefs.setRange("all")
        advanceUntilIdle()

        assertEquals("all", viewModel.uiState.value.range)
        // The All range sends no lower bound at all.
        coVerify(exactly = 1) { repository.cardio(null, "2026-08-08", "all") }
        coVerify(exactly = 1) { repository.cardio("2026-05-16", "2026-08-08", "12w") }
    }

    @Test
    @DisplayName("cardio surfaces its failure and recovers on retry")
    fun cardioRetry() = runTest(dispatcher) {
        coEvery { repository.cardio(any(), any(), any()) } throws IOException("offline")
        val viewModel = CardioViewModel(repository, prefs, debugLog, today)
        viewModel.onActive()
        advanceUntilIdle()
        assertEquals(Slice.Error("Offline — check connection"), viewModel.uiState.value.cardio)

        coEvery { repository.cardio(any(), any(), any()) } returns FetchResult(CARDIO, null)
        viewModel.retry()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.cardio is Slice.Ready)
    }

    // ---- Journal -----------------------------------------------------------

    @Test
    @DisplayName("the tracker list is range-independent and is fetched once")
    fun journalTrackersFetchedOnce() = runTest(dispatcher) {
        stubJournal()
        val viewModel = JournalTrendsViewModel(repository, prefs, debugLog, today)
        viewModel.onActive()
        advanceUntilIdle()

        prefs.setRange("4w")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.journalTrackers() }
        coVerify(exactly = 1) { repository.journalTracker("fixture-tracker", any(), any(), "4w") }
        coVerify(exactly = 1) { repository.journalTracker("fixture-tracker", any(), any(), "12w") }
    }

    @Test
    @DisplayName("picking a tracker remembers it and reloads only its detail")
    fun journalSelectTracker() = runTest(dispatcher) {
        stubJournal()
        val viewModel = JournalTrendsViewModel(repository, prefs, debugLog, today)
        viewModel.onActive()
        advanceUntilIdle()

        viewModel.selectTracker("fixture-other")
        advanceUntilIdle()

        assertEquals("fixture-other", dao.rows[TrendsPrefs.KEY_TRACKER])
        coVerify(exactly = 1) { repository.journalTrackers() }
        coVerify(exactly = 1) { repository.journalTracker("fixture-other", any(), any(), any()) }
    }

    // ---- Health ------------------------------------------------------------

    @Test
    @DisplayName("all four Health sources load together, each independent of the rest")
    fun healthLoadsEverything() = runTest(dispatcher) {
        stubHealth()
        val viewModel = HealthViewModel(repository, prefs, debugLog, today)

        viewModel.onActive()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.recovery is Slice.Ready)
        assertTrue(state.weight is Slice.Ready)
        assertTrue(state.composition is Slice.Ready)
        assertTrue(state.labs is Slice.Ready)
        // Composition and labs are range-immune: an end date and nothing else.
        coVerify(exactly = 1) { repository.healthComposition("2026-08-08") }
        coVerify(exactly = 1) { repository.healthLabs("2026-08-08") }
    }

    @Test
    @DisplayName("three of the four sources can fail and the screen still works")
    fun healthSwallowsTheOptionalSources() = runTest(dispatcher) {
        stubHealth()
        coEvery { repository.weight(any(), any(), any()) } throws IOException("offline")
        coEvery { repository.healthComposition(any()) } throws IOException("offline")
        coEvery { repository.healthLabs(any()) } throws IOException("offline")
        val viewModel = HealthViewModel(repository, prefs, debugLog, today)

        viewModel.onActive()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.recovery is Slice.Ready, "the one that matters survived its siblings")
        assertTrue(state.weight is Slice.Error)
        assertTrue(state.composition is Slice.Error)
        assertTrue(state.labs is Slice.Error)
    }

    @Test
    @DisplayName("the stale badge counts every cached slice the screen is showing")
    fun healthBadgeCoversEverySlice() = runTest(dispatcher) {
        coEvery { repository.healthRecovery(any(), any(), any()) } returns FetchResult(RECOVERY, 900L)
        coEvery { repository.weight(any(), any(), any()) } returns FetchResult(WEIGHT, null)
        coEvery { repository.healthComposition(any()) } returns FetchResult(COMPOSITION, 500L)
        coEvery { repository.healthLabs(any()) } returns FetchResult(LABS, 700L)
        val viewModel = HealthViewModel(repository, prefs, debugLog, today)

        viewModel.onActive()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // The PWA badges recovery alone; here composition's older stamp is the
        // one the badge will report, which is the honest answer.
        assertEquals(
            listOf(900L, 500L, 700L),
            staleStamps(state.recovery, state.weight, state.composition, state.labs),
        )
    }

    @Test
    @DisplayName("a range change refetches only the slices the range actually selects")
    fun healthRangeChangeSparesTheEndOnlySlices() = runTest(dispatcher) {
        stubHealth()
        val viewModel = HealthViewModel(repository, prefs, debugLog, today)
        viewModel.onActive()
        advanceUntilIdle()

        prefs.setRange("4w")
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.healthRecovery(any(), any(), any()) }
        coVerify(exactly = 2) { repository.weight(any(), any(), any()) }
        // End-only requests: the range is not part of the question they ask.
        coVerify(exactly = 1) { repository.healthComposition(any()) }
        coVerify(exactly = 1) { repository.healthLabs(any()) }
    }

    @Test
    @DisplayName("a range change never knocks a rendered DEXA card off the screen")
    fun healthRangeChangeKeepsEndOnlyCardsOnScreen() = runTest(dispatcher) {
        stubHealth()
        val viewModel = HealthViewModel(repository, prefs, debugLog, today)
        viewModel.onActive()
        advanceUntilIdle()
        val rendered = viewModel.uiState.value.composition
        assertTrue(rendered is Slice.Ready)

        // The network is gone, and the earlier composition write never reached
        // the cache — which the repository contract permits. Changing the range
        // must not turn a card that is on screen and correct into an error.
        coEvery { repository.healthRecovery(any(), any(), any()) } throws IOException("offline")
        coEvery { repository.healthComposition(any()) } throws IOException("offline")
        coEvery { repository.healthLabs(any()) } throws IOException("offline")
        prefs.setRange("4w")
        advanceUntilIdle()

        assertEquals(rendered, viewModel.uiState.value.composition)
        assertTrue(viewModel.uiState.value.recovery is Slice.Error)
    }

    @Test
    @DisplayName("retry re-runs every Health slice, end-only ones included")
    fun healthRetryClearsEveryKey() = runTest(dispatcher) {
        stubHealth()
        val viewModel = HealthViewModel(repository, prefs, debugLog, today)
        viewModel.onActive()
        advanceUntilIdle()

        viewModel.retry()
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.healthRecovery(any(), any(), any()) }
        coVerify(exactly = 2) { repository.healthComposition(any()) }
        coVerify(exactly = 2) { repository.healthLabs(any()) }
    }

    @Test
    @DisplayName("a lab panel that no longer exists is replaced AND the replacement is remembered")
    fun healthPersistsTheResolvedLabPanel() = runTest(dispatcher) {
        dao.rows[TrendsPrefs.KEY_LAB_PANEL] = "Fixture Panel Gone"
        stubHealth(labs = TWO_PANELS)
        val viewModel = HealthViewModel(repository, prefs, debugLog, today)

        viewModel.onActive()
        advanceUntilIdle()

        assertEquals("Fixture Panel One", viewModel.uiState.value.labPanel)
        // Displaying the fallback is not enough: left unwritten, the dead name
        // sits in storage ready to steal the selection back.
        assertEquals("Fixture Panel One", dao.rows[TrendsPrefs.KEY_LAB_PANEL])
    }

    @Test
    @DisplayName("a lab-panel pick made while labs are still settling survives the fallback")
    fun healthLabPanelPickBeatsTheFallback() = runTest(dispatcher) {
        dao.rows[TrendsPrefs.KEY_LAB_PANEL] = "Fixture Panel Gone"
        dao.writeDelayMs = 2_000
        stubHealth(labs = TWO_PANELS, labsDelayMs = 1_000)
        val viewModel = HealthViewModel(repository, prefs, debugLog, today)
        viewModel.onActive()
        advanceTimeBy(200)
        runCurrent()

        viewModel.selectLabPanel("Fixture Panel Two")
        advanceUntilIdle()

        assertEquals("Fixture Panel Two", viewModel.uiState.value.labPanel)
        assertEquals("Fixture Panel Two", dao.rows[TrendsPrefs.KEY_LAB_PANEL])
    }

    @Test
    @DisplayName("labs being unavailable does not forget which panel to open next time")
    fun healthKeepsTheRememberedPanelWhenLabsAreAbsent() = runTest(dispatcher) {
        dao.rows[TrendsPrefs.KEY_LAB_PANEL] = "Fixture Panel Two"
        stubHealth()
        val viewModel = HealthViewModel(repository, prefs, debugLog, today)

        viewModel.onActive()
        advanceUntilIdle()

        assertEquals("Fixture Panel Two", viewModel.uiState.value.labPanel)
        assertEquals("Fixture Panel Two", dao.rows[TrendsPrefs.KEY_LAB_PANEL])
    }

    @Test
    @DisplayName("the remembered lab panel arrives from storage")
    fun healthLabPanelFromPrefs() = runTest(dispatcher) {
        stubHealth()
        dao.rows[TrendsPrefs.KEY_LAB_PANEL] = "Fixture Panel Two"
        val viewModel = HealthViewModel(repository, prefs, debugLog, today)

        viewModel.onActive()
        advanceUntilIdle()

        assertEquals("Fixture Panel Two", viewModel.uiState.value.labPanel)

        viewModel.selectLabPanel("Fixture Panel One")
        advanceUntilIdle()
        assertEquals("Fixture Panel One", dao.rows[TrendsPrefs.KEY_LAB_PANEL])
    }

    @Test
    @DisplayName("an inactive screen stops reacting to range changes entirely")
    fun inactiveScreensDoNotFetch() = runTest(dispatcher) {
        stubHealth()
        val viewModel = HealthViewModel(repository, prefs, debugLog, today)
        viewModel.onActive()
        advanceUntilIdle()

        viewModel.onInactive()
        prefs.setRange("4w")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.healthRecovery(any(), any(), any()) }
    }

    // ---- Shell -------------------------------------------------------------

    @Test
    @DisplayName("the shell writes the range and the screen through to storage")
    fun shellPersistsToolbarState() = runTest(dispatcher) {
        val viewModel = TrendsShellViewModel(prefs)

        viewModel.setRange("6m")
        viewModel.setScreen("health")
        advanceUntilIdle()

        assertEquals("6m", dao.rows[TrendsPrefs.KEY_RANGE])
        assertEquals("health", dao.rows[TrendsPrefs.KEY_SCREEN])
    }

    // ---- stubs -------------------------------------------------------------

    private fun stubOverview(weightDelayMs: Long = 0) {
        coEvery { repository.overview() } returns FetchResult(OVERVIEW, null)
        coEvery { repository.weight(any(), any(), any()) } coAnswers {
            if (weightDelayMs > 0) delay(weightDelayMs)
            FetchResult(WEIGHT, null)
        }
    }

    private fun stubStrength(
        exercises: List<dev.jtiisto.wellness.core.data.trends.ExerciseSummary> = listOf(
            exerciseSummary("fixture-press", "Fixture Press"),
            exerciseSummary("fixture-squat", "Fixture Squat"),
        ),
    ) {
        coEvery { repository.strengthExercises(any(), any(), any()) } returns
            FetchResult(ExercisesDto(exercises), null)
        coEvery { repository.strengthVolume(any(), any(), any()) } returns
            FetchResult(VolumeDto(emptyList()), null)
        coEvery { repository.strengthExercise(any(), any(), any(), any()) } returns
            FetchResult(EXERCISE_DETAIL, null)
    }

    private fun stubJournal() {
        coEvery { repository.journalTrackers() } returns FetchResult(
            TrackersDto(listOf(tracker(id = "fixture-tracker"), tracker(id = "fixture-other"))),
            null,
        )
        coEvery { repository.journalTracker(any(), any(), any(), any()) } returns
            FetchResult(TRACKER_DETAIL, null)
    }

    private fun stubHealth(labs: LabsDto = LABS, labsDelayMs: Long = 0) {
        coEvery { repository.healthRecovery(any(), any(), any()) } returns FetchResult(RECOVERY, null)
        coEvery { repository.weight(any(), any(), any()) } returns FetchResult(WEIGHT, null)
        coEvery { repository.healthComposition(any()) } returns FetchResult(COMPOSITION, null)
        coEvery { repository.healthLabs(any()) } coAnswers {
            if (labsDelayMs > 0) delay(labsDelayMs)
            FetchResult(labs, null)
        }
    }

    private companion object {
        val OVERVIEW = dev.jtiisto.wellness.feature.trends.chart.overviewDto(
            lastWeek = 150.0,
            avg = 120.0,
            soFar = 42.0,
        )
        val WEIGHT = WeightDto(available = true, series = listOf(WeightPoint("2026-07-01", 80.0)))
        val CARDIO = CardioDto(weeks = emptyList(), steadySessions = emptyList())
        val RECOVERY = RecoveryDto(available = true, days = emptyList())
        val COMPOSITION = CompositionDto(available = false, scans = emptyList())
        val LABS = LabsDto(available = false, panels = emptyList())
        val TWO_PANELS = LabsDto(
            available = true,
            panels = listOf(
                labPanel(
                    "Fixture Panel One",
                    listOf(labTest("Fixture Test Alpha", "ng/mL", listOf(labObs("2026-07-11", value = 42.0)))),
                ),
                labPanel(
                    "Fixture Panel Two",
                    listOf(labTest("Fixture Test Beta", "%", listOf(labObs("2026-07-11", value = 5.2)))),
                ),
            ),
        )
        val EXERCISE_DETAIL = ExerciseDetailDto(
            exercise = ExerciseInfo("fixture-press", "Fixture Press", "barbell", null),
            unit = "kg",
            sessions = emptyList(),
        )
        val TRACKER_DETAIL: TrackerDetailDto = trackerDetail()
    }
}

/** In-memory stand-in: Room itself only ever runs in the instrumented tests. */
internal class FakeTrendsMetaDao : TrendsMetaDao() {

    val rows = mutableMapOf<String, String>()

    /**
     * How long a write takes to land. Zero by default; the tests that care
     * about a fallback racing a user's pick set it, because the whole hazard
     * lives in the window where storage has not caught up yet.
     */
    var writeDelayMs: Long = 0

    private val revisions = MutableStateFlow(0)

    override fun observe(key: String): Flow<String?> = revisions.map { rows[key] }

    override suspend fun get(key: String): String? = rows[key]

    override suspend fun upsert(row: TrendsMetaEntity) {
        if (writeDelayMs > 0) delay(writeDelayMs)
        rows[row.key] = row.value
        revisions.value += 1
    }
}
