package dev.jtiisto.wellness.feature.analysis

import app.cash.turbine.test
import dev.jtiisto.wellness.core.data.analysis.AnalysisEvent
import dev.jtiisto.wellness.core.data.analysis.AnalysisEvents
import dev.jtiisto.wellness.core.data.analysis.AnalysisQueryDto
import dev.jtiisto.wellness.core.data.analysis.AnalysisRepository
import dev.jtiisto.wellness.core.data.analysis.AnalysisStore
import dev.jtiisto.wellness.core.data.analysis.AnalysisView
import dev.jtiisto.wellness.core.data.analysis.FetchedReport
import dev.jtiisto.wellness.core.data.analysis.ReportDetailDto
import dev.jtiisto.wellness.core.data.analysis.ReportSummaryDto
import dev.jtiisto.wellness.core.data.analysis.SubmitResponseDto
import dev.jtiisto.wellness.core.data.sync.DebugLog
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * The ViewModel, which is meant to be thin.
 *
 * What it does own is the part of the Analysis UI that must **not** outlive the
 * screen: which card is open and what was typed into it. A location remembered
 * across sessions would quietly change what a query means.
 *
 * `viewModelScope` is hard-wired to `Dispatchers.Main`, so every test installs
 * the test scheduler there — without it `initialize()` would run on whatever
 * dispatcher happened to be free and the assertions would race it.
 */
class AnalysisViewModelTest {

    private val repository = mockk<AnalysisRepository>()
    private val events = AnalysisEvents()
    private val debugLog = mockk<DebugLog>(relaxed = true)

    private val submitted = mutableListOf<Pair<String, String?>>()

    @AfterEach
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(
        queries: List<AnalysisQueryDto> = emptyList(),
    ): AnalysisViewModel {
        coEvery { repository.queries() } returns queries
        coEvery { repository.pending() } returns emptyList<ReportDetailDto>()
        coEvery { repository.history(any()) } returns emptyList<ReportSummaryDto>()
        coEvery { repository.submit(any(), any()) } coAnswers {
            submitted += firstArg<String>() to secondArg<String?>()
            SubmitResponseDto(45, "pending")
        }
        coEvery { repository.report(any()) } returns FetchedReport(
            ReportDetailDto(45, "fixture-a", "Label", "pending", null, CREATED, null, null),
            raw = null,
        )

        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = AnalysisStore(
            repository = repository,
            events = events,
            debugLog = debugLog,
            scope = backgroundScope,
            controlContext = StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
        )
        store.onForeground()
        runCurrent()
        return AnalysisViewModel(store, events, ZoneId.of("UTC")).also {
            it.initialize()
            runCurrent()
        }
    }

    private fun query(id: String, acceptsLocation: Boolean = false) =
        AnalysisQueryDto(id, "Label for $id", "Fixture description", acceptsLocation = acceptsLocation)

    @Test
    @DisplayName("a card with no location runs on tap; one with a location opens instead")
    fun tapBehaviourDependsOnLocation() = runTest {
        val plain = query("fixture-a")
        val located = query("fixture-b", acceptsLocation = true)
        val viewModel = viewModel(queries = listOf(plain, located))

        viewModel.onQueryTap(located)
        runCurrent()
        assertEquals("fixture-b", viewModel.ui.value.expandedQueryId)
        assertTrue(submitted.isEmpty(), "a location query must not run before it has been given one")

        viewModel.onQueryTap(plain)
        runCurrent()
        assertEquals(listOf("fixture-a" to null), submitted)
    }

    @Test
    @DisplayName("the typed location reaches the submit, and a blank one is simply no location")
    fun runPassesTypedLocation() = runTest {
        val located = query("fixture-b", acceptsLocation = true)
        val viewModel = viewModel(queries = listOf(located))

        viewModel.onLocationChange("fixture-b", "Fixture City, FS")
        viewModel.onRun(located)
        runCurrent()
        assertEquals(listOf("fixture-b" to "Fixture City, FS"), submitted)
        assertNull(viewModel.ui.value.expandedQueryId, "running closes the card")

        submitted.clear()
        viewModel.onLocationChange("fixture-b", "   ")
        viewModel.onRun(located)
        runCurrent()
        assertEquals(listOf("fixture-b" to null), submitted)
    }

    @Test
    @DisplayName("Try Again on a location query opens its card and submits nothing")
    fun tryAgainNeedsLocationOpensTheCard() = runTest {
        val viewModel = viewModel(queries = listOf(query("fixture-b", acceptsLocation = true)))
        viewModel.openHistory()
        runCurrent()

        viewModel.onTryAgain("fixture-b")
        runCurrent()

        assertEquals(AnalysisView.QUERIES, viewModel.state.value.view)
        assertEquals("fixture-b", viewModel.ui.value.expandedQueryId)
        assertTrue(submitted.isEmpty(), "the PWA resubmitted location-less here, producing a different report")
    }

    @Test
    @DisplayName("Try Again on a plain query resubmits it, and on a vanished one does nothing")
    fun tryAgainOtherModes() = runTest {
        val viewModel = viewModel(queries = listOf(query("fixture-a")))

        viewModel.onTryAgain("fixture-a")
        runCurrent()
        assertEquals(listOf("fixture-a" to null), submitted)

        submitted.clear()
        viewModel.onTryAgain("fixture-gone")
        runCurrent()
        assertTrue(submitted.isEmpty())
    }

    @Test
    @DisplayName("delete asks first, and dismissing it deletes nothing")
    fun deleteIsConfirmed() = runTest {
        coEvery { repository.delete(any(), any()) } returns Unit
        val viewModel = viewModel()

        viewModel.askDelete(41)
        assertEquals(41L, viewModel.ui.value.confirmDeleteId)

        viewModel.dismissDelete()
        assertNull(viewModel.ui.value.confirmDeleteId)

        viewModel.confirmDelete()
        runCurrent()
        assertNull(viewModel.ui.value.confirmDeleteId, "a dismissed dialog leaves nothing to confirm")
    }

    @Test
    @DisplayName("store events arrive as snackbar text, once each")
    fun eventsBecomeMessages() = runTest {
        val viewModel = viewModel()

        viewModel.messages.test {
            events.post(AnalysisEvent.DeleteSuccess)
            events.post(AnalysisEvent.SubmitError("Unknown query_id: fixture-gone"))
            assertEquals("Report deleted.", awaitItem())
            assertEquals("Unknown query_id: fixture-gone", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val CREATED = "2031-03-04T09:15:00.000000Z"
    }
}
