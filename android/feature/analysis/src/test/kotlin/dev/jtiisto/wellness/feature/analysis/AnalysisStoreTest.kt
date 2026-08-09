package dev.jtiisto.wellness.feature.analysis

import dev.jtiisto.wellness.core.data.analysis.ActiveReport
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
import dev.jtiisto.wellness.core.data.network.AnalysisHttpException
import dev.jtiisto.wellness.core.data.sync.DebugLog
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The Analysis state machine, its poll, and the four races that make the
 * generation counter load-bearing.
 *
 * Virtual time throughout, driven with `runCurrent` and bounded `advanceTimeBy`.
 * `advanceUntilIdle` is never used and cannot be: the poll is an intentionally
 * infinite loop, and "idle" is a state it is designed never to reach.
 *
 * The race tests all share a shape. A poll tick is held mid-flight inside
 * `withContext(NonCancellable)`, which is what makes it model the failure the
 * generation counter exists for: a tick that has **already returned from Ktor**
 * has no suspension point left before its write, so cancelling its job cannot
 * stop it. Only a generation check can.
 */
class AnalysisStoreTest {

    private val repository = mockk<AnalysisRepository>()
    private val events = AnalysisEvents()
    private val debugLog = mockk<DebugLog>(relaxed = true)

    // Handlers the individual tests replace. Every one is a suspend function so
    // a test can hold a call open and drive the interleaving itself.
    private var onQueries: suspend () -> List<AnalysisQueryDto> = { emptyList() }
    private var onPending: suspend () -> List<ReportDetailDto> = { emptyList() }
    private var onReport: suspend (Long) -> ReportDetailDto = { error("no report handler") }
    private var reportRaw: (Long) -> String? = { RAW_BODY }
    private var onHistory: suspend () -> List<ReportSummaryDto> = { emptyList() }
    private var onSubmit: suspend (String, String?) -> SubmitResponseDto =
        { _, _ -> error("no submit handler") }
    private var onDelete: suspend (Long, List<ReportSummaryDto>) -> Unit = { _, _ -> }

    private var queriesCalls = 0
    private var pendingCalls = 0
    private var historyCalls = 0
    private val historyExclusions = mutableListOf<Set<Long>>()
    private val cachedReports = mutableListOf<Long>()
    private val reportCalls = mutableListOf<Long>()
    private val submitCalls = mutableListOf<Pair<String, String?>>()
    private val deleteCalls = mutableListOf<Long>()
    private val deleteSnapshots = mutableListOf<List<Long>>()

    @BeforeEach
    fun stubRepository() {
        coEvery { repository.queries() } coAnswers { queriesCalls++; onQueries() }
        coEvery { repository.pending() } coAnswers { pendingCalls++; onPending() }
        coEvery { repository.history(any()) } coAnswers {
            historyCalls++
            historyExclusions += firstArg<Set<Long>>()
            onHistory()
        }
        coEvery { repository.report(any()) } coAnswers {
            reportCalls += firstArg<Long>()
            FetchedReport(onReport(firstArg()), reportRaw(firstArg()))
        }
        coEvery { repository.cacheAcceptedReport(any(), any()) } coAnswers {
            cachedReports += firstArg<Long>()
        }
        coEvery { repository.submit(any(), any()) } coAnswers {
            submitCalls += firstArg<String>() to secondArg<String?>()
            onSubmit(firstArg(), secondArg())
        }
        coEvery { repository.delete(any(), any()) } coAnswers {
            deleteCalls += firstArg<Long>()
            deleteSnapshots += secondArg<List<ReportSummaryDto>>().map { it.id }
            onDelete(firstArg(), secondArg())
        }
    }

    // ---- harness -----------------------------------------------------------

    private fun TestScope.newStore(): AnalysisStore = AnalysisStore(
        repository = repository,
        events = events,
        debugLog = debugLog,
        scope = backgroundScope,
        controlContext = StandardTestDispatcher(testScheduler),
        now = { testScheduler.currentTime },
    )

    /**
     * A store that has already seen the process come to the foreground, which is
     * the real order: the lifecycle observer is registered at application start,
     * long before the tab is opened.
     */
    private fun TestScope.foregroundStore(): AnalysisStore = newStore().also {
        it.onForeground()
        runCurrent()
    }

    private fun TestScope.initialized(store: AnalysisStore) {
        launch { store.initialize() }
        runCurrent()
    }

    private fun TestScope.collectEvents(): List<AnalysisEvent> {
        val received = mutableListOf<AnalysisEvent>()
        backgroundScope.launch { events.events.collect { received += it } }
        runCurrent()
        return received
    }

    private fun detail(
        id: Long,
        status: String,
        markdown: String? = null,
        createdAt: String = CREATED,
        errorMessage: String? = null,
    ) = ReportDetailDto(
        id = id,
        queryId = "fixture-query",
        queryLabel = "Fixture Query",
        status = status,
        responseMarkdown = markdown,
        createdAt = createdAt,
        completedAt = if (status == "completed" || status == "failed") COMPLETED else null,
        errorMessage = errorMessage,
    )

    private fun summary(id: Long, status: String = "completed") = ReportSummaryDto(
        id = id,
        queryId = "fixture-query",
        queryLabel = "Fixture Query",
        status = status,
        createdAt = CREATED,
        completedAt = if (status == "completed" || status == "failed") COMPLETED else null,
    )

    private fun query(id: String, acceptsLocation: Boolean = false) = AnalysisQueryDto(
        id = id,
        label = "Label for $id",
        description = "Fixture description",
        acceptsLocation = acceptsLocation,
    )

    // ---- initialization ----------------------------------------------------

    @Test
    @DisplayName("init loads the query list and lands on the query grid when nothing is running")
    fun initLandsOnQueries() = runTest {
        onQueries = { listOf(query("fixture-a"), query("fixture-b")) }
        val store = foregroundStore()

        initialized(store)

        val state = store.state.value
        assertEquals(listOf("fixture-a", "fixture-b"), state.queries.map { it.id })
        assertEquals(AnalysisView.QUERIES, state.view)
        assertFalse(state.isLoading)
        assertNull(state.queriesError)
        assertNull(state.active)
    }

    @Test
    @DisplayName("init adopts a running report: progress screen, poll started")
    fun initAdoptsPendingReport() = runTest {
        onQueries = { listOf(query("fixture-a")) }
        onPending = { listOf(detail(7, "running")) }
        onReport = { detail(7, "running") }
        val store = foregroundStore()

        initialized(store)

        assertEquals(AnalysisView.PROGRESS, store.state.value.view)
        assertEquals(7L, store.state.value.active?.id)
        assertEquals(listOf(7L), reportCalls, "the poll fires its first tick immediately")
    }

    @Test
    @DisplayName("a pending row that has somehow already finished lands on the report, not the spinner")
    fun initAdoptsTerminalPendingRow() = runTest {
        onPending = { listOf(detail(7, "completed", markdown = "# done")) }
        val store = foregroundStore()

        initialized(store)

        assertEquals(AnalysisView.REPORT, store.state.value.view)
        assertTrue(reportCalls.isEmpty(), "a finished report has nothing left to poll")
    }

    @Test
    @DisplayName("two initialize calls await one deferred rather than fetching twice")
    fun initializeIsMemoized() = runTest {
        onQueries = { listOf(query("fixture-a")) }
        val store = foregroundStore()

        launch { store.initialize() }
        launch { store.initialize() }
        runCurrent()
        launch { store.initialize() }
        runCurrent()

        assertEquals(1, queriesCalls)
        assertEquals(1, pendingCalls)
    }

    @Test
    @DisplayName("offline with a cached history, init lands on History — PWA parity")
    fun initOfflineWithCacheLandsOnHistory() = runTest {
        onQueries = { throw IOException("offline") }
        onPending = { throw IOException("offline") }
        onHistory = { listOf(summary(41)) }
        val store = foregroundStore()

        initialized(store)

        val state = store.state.value
        assertEquals(AnalysisView.HISTORY, state.view)
        assertEquals(listOf(41L), state.history.map { it.id })
        assertEquals(AnalysisStore.QUERIES_OFFLINE, state.queriesError)
        assertFalse(state.isLoading)
    }

    @Test
    @DisplayName("offline with nothing cached, init lands on the query grid and says why")
    fun initOfflineBareLandsOnQueries() = runTest {
        onQueries = { throw IOException("offline") }
        onPending = { throw IOException("offline") }
        onHistory = { throw IOException("offline") }
        val store = foregroundStore()

        initialized(store)

        assertEquals(AnalysisView.QUERIES, store.state.value.view)
        assertEquals(AnalysisStore.QUERIES_OFFLINE, store.state.value.queriesError)
    }

    @Test
    @DisplayName("a 404 on /queries is the module being switched off, and gets no cache fallback")
    fun initModuleDisabled() = runTest {
        onQueries = { throw AnalysisHttpException(404, "Not Found") }
        onPending = { throw AnalysisHttpException(404, "Not Found") }
        onHistory = { listOf(summary(41)) }
        val store = foregroundStore()

        initialized(store)

        assertEquals(AnalysisStore.MODULE_DISABLED, store.state.value.queriesError)
        assertEquals(AnalysisView.QUERIES, store.state.value.view)
        assertEquals(0, historyCalls, "a disabled module has no offline story to tell")
    }

    @Test
    @DisplayName("any other server error surfaces its own detail on the query grid")
    fun initServerErrorShowsDetail() = runTest {
        onQueries = { throw AnalysisHttpException(500, "upstream exploded") }
        onPending = { throw AnalysisHttpException(500, "upstream exploded") }
        val store = foregroundStore()

        initialized(store)

        assertEquals("upstream exploded", store.state.value.queriesError)
        assertEquals(AnalysisView.QUERIES, store.state.value.view)
    }

    @Test
    @DisplayName("a foreground after a transient init failure re-runs the whole init, queries included")
    fun foregroundRetriesFailedInit() = runTest {
        onQueries = { throw IOException("offline") }
        onPending = { throw IOException("offline") }
        onHistory = { throw IOException("offline") }
        val store = foregroundStore()
        initialized(store)
        assertEquals(1, queriesCalls)

        onQueries = { listOf(query("fixture-a")) }
        onPending = { emptyList() }
        store.onBackground()
        store.onForeground()
        runCurrent()

        assertEquals(2, queriesCalls, "the PWA memoized this forever; one offline launch emptied the tab")
        assertEquals(listOf("fixture-a"), store.state.value.queries.map { it.id })
        assertNull(store.state.value.queriesError)
    }

    @Test
    @DisplayName("a disabled module is not retried on every foreground — it is not going to change")
    fun foregroundDoesNotRetryDisabledModule() = runTest {
        onQueries = { throw AnalysisHttpException(404, "Not Found") }
        onPending = { throw AnalysisHttpException(404, "Not Found") }
        val store = foregroundStore()
        initialized(store)

        store.onForeground()
        runCurrent()

        assertEquals(1, queriesCalls)
    }

    @Test
    @DisplayName("a foreground before init only latches the flag; it starts no work of its own")
    fun preInitForegroundOnlyLatches() = runTest {
        val store = newStore()

        store.onForeground()
        runCurrent()

        assertEquals(0, queriesCalls)
        assertEquals(0, pendingCalls)
        assertTrue(store.state.value.isLoading)

        // And the latch is what makes the adopted report start polling at once.
        onPending = { listOf(detail(7, "running")) }
        onReport = { detail(7, "running") }
        initialized(store)
        assertEquals(listOf(7L), reportCalls)
    }

    // ---- submit ------------------------------------------------------------

    @Test
    @DisplayName("a 201 seats a stub, shows the progress screen and starts polling")
    fun submitAdoptsStub() = runTest {
        val firstTick = CompletableDeferred<Unit>()
        onQueries = { listOf(query("fixture-a")) }
        onSubmit = { _, _ -> SubmitResponseDto(45, "pending") }
        onReport = {
            withContext(NonCancellable) { firstTick.await() }
            detail(45, "pending")
        }
        val store = foregroundStore()
        initialized(store)

        store.submit("fixture-a", null)
        runCurrent()

        // The stub is the gap between the 201 and the first tick: there is an id
        // and a label, and no created_at for the clock to count from yet.
        val pending = store.state.value
        assertEquals(AnalysisView.PROGRESS, pending.view)
        val stub = pending.active as ActiveReport.Stub
        assertEquals(45L, stub.id)
        assertEquals("fixture-a", stub.queryId)
        assertEquals("Label for fixture-a", stub.queryLabel)
        assertFalse(pending.submitInFlight)
        assertEquals(listOf(45L), reportCalls, "the poll fires its first tick immediately")

        firstTick.complete(Unit)
        runCurrent()

        assertEquals(45L, (store.state.value.active as ActiveReport.Loaded).detail.id)
    }

    @Test
    @DisplayName("the location the user typed reaches the wire")
    fun submitPassesLocation() = runTest {
        onQueries = { listOf(query("fixture-a", acceptsLocation = true)) }
        onSubmit = { _, _ -> SubmitResponseDto(45, "pending") }
        onReport = { detail(45, "pending") }
        val store = foregroundStore()
        initialized(store)

        store.submit("fixture-a", "Fixture City, FS")
        runCurrent()

        assertEquals(listOf("fixture-a" to "Fixture City, FS"), submitCalls)
    }

    @Test
    @DisplayName("a 409 adopts the report that was already running instead of dead-ending")
    fun submitConflictAdoptsRunningReport() = runTest {
        val received = collectEvents()
        onSubmit = { _, _ -> throw AnalysisHttpException(409, "A query is already in progress.") }
        onPending = { listOf(detail(7, "running")) }
        onReport = { detail(7, "running") }
        val store = foregroundStore()

        store.submit("fixture-a", null)
        runCurrent()

        assertEquals(AnalysisView.PROGRESS, store.state.value.view)
        assertEquals(7L, store.state.value.active?.id)
        assertEquals(listOf(7L), reportCalls)
        assertTrue(received.contains(AnalysisEvent.AdoptedRunning))
    }

    @Test
    @DisplayName("a 409 with nothing pending — it just finished — refreshes History and says so")
    fun submitConflictWithEmptyPending() = runTest {
        val received = collectEvents()
        onSubmit = { _, _ -> throw AnalysisHttpException(409, "A query is already in progress.") }
        onPending = { emptyList() }
        onHistory = { listOf(summary(41)) }
        val store = foregroundStore()

        store.submit("fixture-a", null)
        runCurrent()

        assertEquals(AnalysisView.QUERIES, store.state.value.view)
        assertEquals(listOf(41L), store.state.value.history.map { it.id })
        assertEquals(
            listOf(AnalysisEvent.SubmitError("A query is already in progress.")),
            received,
        )
    }

    @Test
    @DisplayName("offline, a submit says so and leaves the view where it was")
    fun submitOffline() = runTest {
        val received = collectEvents()
        onSubmit = { _, _ -> throw IOException("offline") }
        val store = foregroundStore()

        store.submit("fixture-a", null)
        runCurrent()

        assertEquals(listOf(AnalysisEvent.SubmitOffline), received)
        assertEquals(AnalysisView.QUERIES, store.state.value.view)
        assertFalse(store.state.value.submitInFlight)
    }

    @Test
    @DisplayName("an unknown query id surfaces the server's own message")
    fun submitUnknownQueryId() = runTest {
        val received = collectEvents()
        onSubmit = { _, _ -> throw AnalysisHttpException(404, "Unknown query_id: fixture-gone") }
        val store = foregroundStore()

        store.submit("fixture-gone", null)
        runCurrent()

        assertEquals(listOf(AnalysisEvent.SubmitError("Unknown query_id: fixture-gone")), received)
    }

    @Test
    @DisplayName("two taps before the first recomposition still submit once")
    fun submitGuardIsAtomic() = runTest {
        val gate = CompletableDeferred<Unit>()
        onSubmit = { _, _ ->
            withContext(NonCancellable) { gate.await() }
            SubmitResponseDto(45, "pending")
        }
        onReport = { detail(45, "pending") }
        val store = foregroundStore()

        store.submit("fixture-a", null)
        store.submit("fixture-a", null)
        runCurrent()
        assertTrue(store.state.value.submitInFlight)

        gate.complete(Unit)
        runCurrent()

        assertEquals(1, submitCalls.size, "the guard lives in the store; disabling a card is too late")
        assertFalse(store.state.value.submitInFlight)
    }

    // ---- polling -----------------------------------------------------------

    @Test
    @DisplayName("the poll ticks every three seconds and updates the active report only")
    fun pollUpdatesActiveOnly() = runTest {
        onPending = { listOf(detail(7, "pending")) }
        var tick = 0
        onReport = { if (tick++ == 0) detail(7, "pending") else detail(7, "running") }
        val store = foregroundStore()
        initialized(store)
        store.openQueries()
        runCurrent()

        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(2, reportCalls.size)
        assertEquals("running", (store.state.value.active as ActiveReport.Loaded).detail.status)
        assertEquals(AnalysisView.QUERIES, store.state.value.view, "a non-terminal tick moves nobody")
    }

    @Test
    @DisplayName("a terminal tick on the progress screen advances to the report and refreshes History")
    fun terminalTickFromProgress() = runTest {
        onPending = { listOf(detail(7, "running")) }
        var tick = 0
        onReport = { if (tick++ == 0) detail(7, "running") else detail(7, "completed", "# body") }
        onHistory = { listOf(summary(7)) }
        val store = foregroundStore()
        initialized(store)

        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(AnalysisView.REPORT, store.state.value.view)
        assertEquals("# body", (store.state.value.active as ActiveReport.Loaded).detail.responseMarkdown)
        assertEquals(
            "# body",
            store.state.value.viewing?.responseMarkdown,
            "auto-advancing from the spinner is the one case that fills the reading slot",
        )
        assertEquals(listOf(7L), store.state.value.history.map { it.id })
        assertEquals(1, historyCalls)
        assertEquals(listOf(7L), cachedReports)
    }

    @Test
    @DisplayName("a terminal tick anywhere else leaves the view alone — auto-advance is the spinner's job")
    fun terminalTickElsewhereDoesNotNavigate() = runTest {
        onPending = { listOf(detail(7, "running")) }
        var tick = 0
        onReport = { if (tick++ == 0) detail(7, "running") else detail(7, "completed", "# body") }
        onHistory = { listOf(summary(7)) }
        val store = foregroundStore()
        initialized(store)
        store.openHistory()
        runCurrent()

        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(AnalysisView.HISTORY, store.state.value.view)
        assertEquals("completed", (store.state.value.active as ActiveReport.Loaded).detail.status)
        assertEquals(listOf(7L), store.state.value.history.map { it.id })
    }

    @Test
    @DisplayName("a transient poll failure never stops the poll — foreground-only is what bounds it")
    fun transientPollFailureKeepsPolling() = runTest {
        onPending = { listOf(detail(7, "running")) }
        var tick = 0
        onReport = {
            when (tick++) {
                0 -> detail(7, "running")
                1 -> throw IOException("dropped")
                2 -> throw AnalysisHttpException(500, "upstream")
                else -> detail(7, "completed", "# body")
            }
        }
        val store = foregroundStore()
        initialized(store)

        advanceTimeBy(9_003)
        runCurrent()

        assertEquals(4, reportCalls.size)
        assertEquals(AnalysisView.REPORT, store.state.value.view)
    }

    @Test
    @DisplayName("a polled report deleted elsewhere stops the poll and returns from the spinner")
    fun pollNotFoundFromProgress() = runTest {
        val received = collectEvents()
        onPending = { listOf(detail(7, "running")) }
        var tick = 0
        onReport = {
            if (tick++ == 0) detail(7, "running") else throw AnalysisHttpException(404, "Report not found")
        }
        val store = foregroundStore()
        initialized(store)

        advanceTimeBy(3_001)
        runCurrent()
        val callsAtStop = reportCalls.size
        advanceTimeBy(9_000)
        runCurrent()

        assertEquals(AnalysisView.QUERIES, store.state.value.view)
        assertNull(store.state.value.active)
        assertTrue(received.contains(AnalysisEvent.ReportDeletedRemotely))
        assertEquals(callsAtStop, reportCalls.size, "the poll really stopped")
    }

    @Test
    @DisplayName("the same 404 from History clears the report but leaves the user where they are")
    fun pollNotFoundElsewherePreservesView() = runTest {
        onPending = { listOf(detail(7, "running")) }
        var tick = 0
        onReport = {
            if (tick++ == 0) detail(7, "running") else throw AnalysisHttpException(404, "Report not found")
        }
        val store = foregroundStore()
        initialized(store)
        store.openHistory()
        runCurrent()

        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(AnalysisView.HISTORY, store.state.value.view)
        assertNull(store.state.value.active)
    }

    @Test
    @DisplayName("an unrecognised status polls on, then gives up at the ceiling with a re-check offered")
    fun unknownStatusCeiling() = runTest {
        onPending = { listOf(detail(7, "queued")) }
        onReport = { detail(7, "queued") }
        val store = foregroundStore()
        initialized(store)

        // Well past any legitimate query, still short of the ceiling.
        advanceTimeBy(500_000)
        runCurrent()
        assertFalse(store.state.value.unknownStalled)
        val callsBefore = reportCalls.size

        advanceTimeBy(120_000)
        runCurrent()

        assertTrue(store.state.value.unknownStalled)
        assertEquals(AnalysisView.PROGRESS, store.state.value.view, "the spinner becomes the explanation")
        assertEquals(7L, store.state.value.active?.id, "the report is not thrown away, only stopped")
        val callsAtStall = reportCalls.size
        assertTrue(callsAtStall > callsBefore)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(callsAtStall, reportCalls.size, "the poll really stopped")
    }

    @Test
    @DisplayName("a status the client understands resets the unknown clock rather than accumulating")
    fun knownStatusResetsUnknownClock() = runTest {
        onPending = { listOf(detail(7, "queued")) }
        var tick = 0
        onReport = { if (tick++ == 100) detail(7, "running") else detail(7, "queued") }
        val store = foregroundStore()
        initialized(store)

        advanceTimeBy(700_000)
        runCurrent()

        assertFalse(
            store.state.value.unknownStalled,
            "a recognised status in the middle means the server is alive; the count starts over",
        )
    }

    @Test
    @DisplayName("re-check restarts a stalled poll from scratch")
    fun recheckRestartsStalledPoll() = runTest {
        onPending = { listOf(detail(7, "queued")) }
        onReport = { detail(7, "queued") }
        val store = foregroundStore()
        initialized(store)
        advanceTimeBy(620_000)
        runCurrent()
        assertTrue(store.state.value.unknownStalled)
        val callsAtStall = reportCalls.size

        store.recheck()
        runCurrent()

        assertFalse(store.state.value.unknownStalled)
        assertEquals(callsAtStall + 1, reportCalls.size)
    }

    // ---- lifecycle ---------------------------------------------------------

    @Test
    @DisplayName("backgrounding pauses the poll; returning resumes it with an immediate tick")
    fun backgroundPausesAndForegroundResumes() = runTest {
        onPending = { listOf(detail(7, "running")) }
        onReport = { detail(7, "running") }
        val store = foregroundStore()
        initialized(store)
        assertEquals(1, reportCalls.size)

        store.onBackground()
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, reportCalls.size, "nothing to show a result to")

        store.onForeground()
        runCurrent()

        assertEquals(2, reportCalls.size, "no waiting out an interval on return")
    }

    @Test
    @DisplayName("returning to the foreground with nothing running adopts whatever the server started")
    fun foregroundAdoptsPendingFromQueries() = runTest {
        onQueries = { listOf(query("fixture-a")) }
        val store = foregroundStore()
        initialized(store)
        assertEquals(AnalysisView.QUERIES, store.state.value.view)

        onPending = { listOf(detail(7, "running")) }
        onReport = { detail(7, "running") }
        store.onBackground()
        store.onForeground()
        runCurrent()

        assertEquals(AnalysisView.PROGRESS, store.state.value.view)
        assertEquals(7L, store.state.value.active?.id)
    }

    @Test
    @DisplayName("the same adoption from History starts the poll without moving the user")
    fun foregroundAdoptionFromHistoryStaysPut() = runTest {
        onHistory = { listOf(summary(41)) }
        val store = foregroundStore()
        initialized(store)
        store.openHistory()
        runCurrent()

        onPending = { listOf(detail(7, "running")) }
        onReport = { detail(7, "running") }
        store.onBackground()
        store.onForeground()
        runCurrent()

        assertEquals(AnalysisView.HISTORY, store.state.value.view)
        assertEquals(listOf(7L), reportCalls, "the poll started even though the view did not move")
    }

    @Test
    @DisplayName("a finished report on screen is not interrupted by a foreground pending check")
    fun foregroundSkipsCheckWhileReadingAReport() = runTest {
        onReport = { detail(41, "completed", "# body") }
        val store = foregroundStore()
        initialized(store)
        store.openReport(41)
        runCurrent()
        assertEquals(AnalysisView.REPORT, store.state.value.view)
        val pendingBefore = pendingCalls

        store.onBackground()
        store.onForeground()
        runCurrent()

        assertEquals(pendingBefore, pendingCalls)
    }

    @Test
    @DisplayName("a foreground while a poll is already running asks the server nothing extra")
    fun foregroundSkipsCheckWhilePolling() = runTest {
        onPending = { listOf(detail(7, "running")) }
        onReport = { detail(7, "running") }
        val store = foregroundStore()
        initialized(store)
        val pendingBefore = pendingCalls

        store.onBackground()
        store.onForeground()
        runCurrent()

        assertEquals(pendingBefore, pendingCalls)
        assertEquals(2, reportCalls.size, "it resumed instead")
    }

    // ---- navigation --------------------------------------------------------

    @Test
    @DisplayName("navigating between sub-views never stops the poll — the PWA's orphaning bug, fixed")
    fun navigationLeavesThePollAlone() = runTest {
        onPending = { listOf(detail(7, "running")) }
        onReport = { detail(7, "running") }
        onHistory = { listOf(summary(41)) }
        val store = foregroundStore()
        initialized(store)

        store.openHistory()
        runCurrent()
        store.openQueries()
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(2, reportCalls.size)
        assertEquals(AnalysisView.QUERIES, store.state.value.view)
    }

    @Test
    @DisplayName("opening a finished report puts it in the reading slot and leaves the working one alone")
    fun openTerminalReport() = runTest {
        onReport = { detail(41, "completed", "# body") }
        val store = foregroundStore()
        initialized(store)

        store.openReport(41)
        runCurrent()

        assertEquals(AnalysisView.REPORT, store.state.value.view)
        assertEquals(41L, store.state.value.viewing?.id)
        assertNull(store.state.value.active, "reading a report is not adopting it")
        assertEquals(listOf(41L), cachedReports, "a terminal report the store accepted is cached")
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(1, reportCalls.size, "a finished report is not polled")
    }

    @Test
    @DisplayName("opening a still-running report adopts it and starts polling")
    fun openNonTerminalReportStartsPoll() = runTest {
        onReport = { detail(9, "running") }
        val store = foregroundStore()
        initialized(store)

        store.openReport(9)
        runCurrent()

        assertEquals(AnalysisView.PROGRESS, store.state.value.view)
        assertEquals(9L, store.state.value.active?.id)
        assertNull(store.state.value.viewing, "adopting a running report clears whatever was being read")
        // The open itself, then the poll's immediate first tick: the poll
        // contract is the same however it was started, and one duplicate GET
        // costs less than a second code path.
        assertEquals(listOf(9L, 9L), reportCalls)

        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(listOf(9L, 9L, 9L), reportCalls)
    }

    @Test
    @DisplayName("a report that is neither reachable nor cached leaves the view alone and says so")
    fun openReportOfflineWithoutCache() = runTest {
        val received = collectEvents()
        onHistory = { listOf(summary(41)) }
        onReport = { throw IOException("offline") }
        val store = foregroundStore()
        initialized(store)
        store.openHistory()
        runCurrent()

        store.openReport(41)
        runCurrent()

        assertEquals(AnalysisView.HISTORY, store.state.value.view)
        assertTrue(received.contains(AnalysisEvent.ReportUnavailableOffline))
    }

    @Test
    @DisplayName("a report the server no longer has reports its own words")
    fun openReportNotFound() = runTest {
        val received = collectEvents()
        onReport = { throw AnalysisHttpException(404, "Report not found") }
        val store = foregroundStore()
        initialized(store)

        store.openReport(41)
        runCurrent()

        assertEquals(listOf(AnalysisEvent.ReportError("Report not found")), received)
        assertEquals(AnalysisView.QUERIES, store.state.value.view)
    }

    @Test
    @DisplayName("cancel abandons the report client-side and returns to the query grid")
    fun cancelActiveStopsEverything() = runTest {
        onPending = { listOf(detail(7, "running")) }
        onReport = { detail(7, "running") }
        val store = foregroundStore()
        initialized(store)

        store.cancelActive()
        runCurrent()
        advanceTimeBy(9_000)
        runCurrent()

        assertEquals(AnalysisView.QUERIES, store.state.value.view)
        assertNull(store.state.value.active)
        assertEquals(1, reportCalls.size)
    }

    // ---- delete ------------------------------------------------------------

    @Test
    @DisplayName("deleting the report on screen clears it and returns to the query grid")
    fun deleteViewedReport() = runTest {
        val received = collectEvents()
        onReport = { detail(41, "completed", "# body") }
        onHistory = { listOf(summary(41), summary(40)) }
        val store = foregroundStore()
        initialized(store)
        store.openHistory()
        runCurrent()
        store.openReport(41)
        runCurrent()

        store.delete(41)
        runCurrent()

        assertEquals(AnalysisView.QUERIES, store.state.value.view)
        assertNull(store.state.value.viewing)
        assertEquals(listOf(40L), store.state.value.history.map { it.id })
        assertTrue(received.contains(AnalysisEvent.DeleteSuccess))
    }

    @Test
    @DisplayName("deleting from History filters the list and leaves the user on it")
    fun deleteFromHistoryStaysPut() = runTest {
        onHistory = { listOf(summary(41), summary(40)) }
        val store = foregroundStore()
        initialized(store)
        store.openHistory()
        runCurrent()

        store.delete(40)
        runCurrent()

        assertEquals(AnalysisView.HISTORY, store.state.value.view)
        assertEquals(listOf(41L), store.state.value.history.map { it.id })
        assertEquals(listOf(40L), deleteCalls)
    }

    @Test
    @DisplayName("deleting something other than the report on screen leaves that report alone")
    fun deleteOtherReportKeepsTheOneBeingRead() = runTest {
        onReport = { detail(41, "completed", "# body") }
        onHistory = { listOf(summary(41), summary(40)) }
        val store = foregroundStore()
        initialized(store)
        store.openHistory()
        runCurrent()
        store.openReport(41)
        runCurrent()

        store.delete(40)
        runCurrent()

        assertEquals(AnalysisView.REPORT, store.state.value.view)
        assertEquals(41L, store.state.value.viewing?.id)
    }

    @Test
    @DisplayName("the server's refusal to delete a running report is shown verbatim, poll untouched")
    fun deleteConflictSurfacesDetail() = runTest {
        val received = collectEvents()
        onPending = { listOf(detail(7, "running")) }
        onReport = { detail(7, "running") }
        onDelete = { _, _ ->
            throw AnalysisHttpException(
                409,
                "Report is still running — wait for it to finish (or time out) before deleting.",
            )
        }
        val store = foregroundStore()
        initialized(store)

        store.delete(7)
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(
            listOf(
                AnalysisEvent.DeleteError(
                    "Report is still running — wait for it to finish (or time out) before deleting.",
                ),
            ),
            received,
        )
        assertEquals(AnalysisView.PROGRESS, store.state.value.view)
        assertEquals(2, reportCalls.size, "a refused delete must not disturb the poll")
    }

    // ---- the races the generation counter exists for ------------------------

    @Test
    @DisplayName("a tick that returns after Cancel commits nothing")
    fun supersededTickAfterCancelCommitsNothing() = runTest {
        val gate = CompletableDeferred<Unit>()
        onPending = { listOf(detail(7, "running")) }
        onReport = {
            // Already back from Ktor: no suspension point is left before the
            // write, so cancelling the job cannot help. Only the generation can.
            withContext(NonCancellable) { gate.await() }
            detail(7, "completed", "# body")
        }
        val store = foregroundStore()
        initialized(store)

        store.cancelActive()
        runCurrent()
        assertEquals(AnalysisView.QUERIES, store.state.value.view)

        gate.complete(Unit)
        runCurrent()

        assertNull(store.state.value.active, "a cancelled report must not come back from the dead")
        assertEquals(AnalysisView.QUERIES, store.state.value.view)
        assertEquals(0, historyCalls, "and it must not trigger the terminal-tick side effects either")
    }

    @Test
    @DisplayName("an old report's terminal tick cannot displace one adopted after it")
    fun supersededTickCannotDisplaceNewAdoption() = runTest {
        val gate = CompletableDeferred<Unit>()
        onPending = { listOf(detail(7, "running")) }
        onReport = { id ->
            when (id) {
                7L -> {
                    withContext(NonCancellable) { gate.await() }
                    detail(7, "completed", "# stale body")
                }
                else -> detail(9, "running")
            }
        }
        val store = foregroundStore()
        initialized(store)

        store.openReport(9)
        runCurrent()
        assertEquals(9L, store.state.value.active?.id)

        gate.complete(Unit)
        runCurrent()

        assertEquals(9L, store.state.value.active?.id, "report 7 finished, but nobody is watching it now")
        assertEquals(AnalysisView.PROGRESS, store.state.value.view)
    }

    @Test
    @DisplayName("a background-era tick landing after the foreground one does not win on age")
    fun backgroundEraTickCannotOverwriteFreshOne() = runTest {
        val gate = CompletableDeferred<Unit>()
        onPending = { listOf(detail(7, "running")) }
        var tick = 0
        onReport = {
            if (tick++ == 0) {
                withContext(NonCancellable) { gate.await() }
                detail(7, "completed", "# body from the background era")
            } else {
                detail(7, "running")
            }
        }
        val store = foregroundStore()
        initialized(store)

        store.onBackground()
        runCurrent()
        store.onForeground()
        runCurrent()
        assertEquals(2, reportCalls.size)
        assertEquals("running", (store.state.value.active as ActiveReport.Loaded).detail.status)

        gate.complete(Unit)
        runCurrent()

        assertEquals(
            "running",
            (store.state.value.active as ActiveReport.Loaded).detail.status,
            "resuming starts a new generation, so the paused tick's answer is stale by construction",
        )
        assertEquals(AnalysisView.PROGRESS, store.state.value.view)
    }

    @Test
    @DisplayName("a delete invalidates the generation before a tick in flight can resurrect the report")
    fun deleteInvalidatesInFlightTick() = runTest {
        val gate = CompletableDeferred<Unit>()
        onPending = { listOf(detail(7, "running")) }
        onReport = {
            withContext(NonCancellable) { gate.await() }
            detail(7, "completed", "# body")
        }
        onHistory = { listOf(summary(7, "completed")) }
        val store = foregroundStore()
        initialized(store)

        store.delete(7)
        runCurrent()
        assertEquals(AnalysisView.QUERIES, store.state.value.view)

        gate.complete(Unit)
        runCurrent()

        assertNull(store.state.value.active, "a deleted report cannot be walked back onto")
        assertEquals(AnalysisView.QUERIES, store.state.value.view)
    }

    // ---- the reading slot ---------------------------------------------------

    @Test
    @DisplayName("a query finishing in the background never swaps the report being read")
    fun terminalTickDoesNotSwapTheReportBeingRead() = runTest {
        onPending = { listOf(detail(7, "running")) }
        var tick = 0
        onReport = { id ->
            when {
                id == 41L -> detail(41, "completed", "# the one being read")
                tick++ == 0 -> detail(7, "running")
                else -> detail(7, "completed", "# the one that just finished")
            }
        }
        onHistory = { listOf(summary(41)) }
        val store = foregroundStore()
        initialized(store)
        store.openReport(41)
        runCurrent()
        assertEquals(AnalysisView.REPORT, store.state.value.view)

        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(AnalysisView.REPORT, store.state.value.view)
        assertEquals(
            41L,
            store.state.value.viewing?.id,
            "one slot for both reports is what used to pull the page out from under the reader",
        )
        assertEquals(7L, store.state.value.active?.id, "the finished query is still the active one")
    }

    @Test
    @DisplayName("deleting the report being read leaves an unrelated poll running")
    fun deletingTheReportBeingReadSparesTheRunningQuery() = runTest {
        onPending = { listOf(detail(7, "running")) }
        onReport = { id -> if (id == 41L) detail(41, "completed", "# body") else detail(7, "running") }
        onHistory = { listOf(summary(41), summary(7, "running")) }
        val store = foregroundStore()
        initialized(store)
        store.openReport(41)
        runCurrent()
        val ticksBefore = reportCalls.count { it == 7L }

        store.delete(41)
        runCurrent()

        assertNull(store.state.value.viewing)
        assertEquals(AnalysisView.QUERIES, store.state.value.view)
        assertEquals(7L, store.state.value.active?.id)
        advanceTimeBy(3_001)
        runCurrent()
        assertTrue(
            reportCalls.count { it == 7L } > ticksBefore,
            "deleting what you were reading must not abandon what you were running",
        )
    }

    // ---- the open request's own generation -----------------------------------

    @Test
    @DisplayName("a report deleted while its fetch is in flight never arrives into view")
    fun deleteInvalidatesAnOpenRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        onHistory = { listOf(summary(41), summary(40)) }
        onReport = {
            withContext(NonCancellable) { gate.await() }
            detail(41, "completed", "# body")
        }
        val store = foregroundStore()
        initialized(store)
        store.openHistory()
        runCurrent()

        store.openReport(41)
        runCurrent()
        store.delete(41)
        runCurrent()

        gate.complete(Unit)
        runCurrent()

        assertNull(store.state.value.viewing, "nothing else invalidates this — it is neither active nor polled")
        assertEquals(AnalysisView.HISTORY, store.state.value.view)
        assertTrue(cachedReports.isEmpty(), "and the deleted body must not reach the cache either")
    }

    @Test
    @DisplayName("two quick selections land in the order they were made, not the order they answer in")
    fun laterOpenWinsWhateverOrderTheAnswersArriveIn() = runTest {
        val slow = CompletableDeferred<Unit>()
        onReport = { id ->
            if (id == 41L) {
                withContext(NonCancellable) { slow.await() }
                detail(41, "completed", "# first pick")
            } else {
                detail(40, "completed", "# second pick")
            }
        }
        val store = foregroundStore()
        initialized(store)

        store.openReport(41)
        runCurrent()
        store.openReport(40)
        runCurrent()
        assertEquals(40L, store.state.value.viewing?.id)

        slow.complete(Unit)
        runCurrent()

        assertEquals("# second pick", store.state.value.viewing?.responseMarkdown)
    }

    @Test
    @DisplayName("a superseded open fails silently — the user already asked for something else")
    fun supersededOpenFailureRaisesNoEvent() = runTest {
        val received = collectEvents()
        val gate = CompletableDeferred<Unit>()
        onReport = { id ->
            if (id == 41L) {
                withContext(NonCancellable) { gate.await() }
                throw AnalysisHttpException(404, "Report not found")
            } else {
                detail(40, "completed", "# body")
            }
        }
        val store = foregroundStore()
        initialized(store)

        store.openReport(41)
        runCurrent()
        store.openReport(40)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue(received.isEmpty(), "a snackbar about the report they navigated away from is noise")
        assertEquals(40L, store.state.value.viewing?.id)
    }

    @Test
    @DisplayName("opening a report is not a poll event: an adoption invalidates it, a running poll does not")
    fun adoptionInvalidatesAnOpenRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        onPending = { listOf(detail(7, "running")) }
        onReport = { id ->
            if (id == 41L) {
                withContext(NonCancellable) { gate.await() }
                detail(41, "completed", "# body")
            } else {
                detail(7, "running")
            }
        }
        onSubmit = { _, _ -> SubmitResponseDto(45, "pending") }
        val store = foregroundStore()
        initialized(store)
        store.cancelActive()
        runCurrent()

        store.openReport(41)
        runCurrent()
        store.submit("fixture-a", null)
        runCurrent()

        gate.complete(Unit)
        runCurrent()

        assertEquals(AnalysisView.PROGRESS, store.state.value.view)
        assertNull(store.state.value.viewing, "the adoption is newer than the open it superseded")
    }

    // ---- history serialization and tombstones --------------------------------

    @Test
    @DisplayName("two deletes in flight at once cannot write each other's row back")
    fun concurrentDeletesCannotResurrectEachOther() = runTest {
        val gate = CompletableDeferred<Unit>()
        onHistory = { listOf(summary(41), summary(40), summary(39)) }
        onDelete = { id, _ -> if (id == 41L) withContext(NonCancellable) { gate.await() } }
        val store = foregroundStore()
        initialized(store)
        store.openHistory()
        runCurrent()

        store.delete(41)
        runCurrent()
        store.delete(40)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(39L), store.state.value.history.map { it.id })
        assertEquals(listOf(41L, 40L), deleteCalls)
        assertEquals(
            listOf(listOf(40L, 39L), listOf(39L)),
            deleteSnapshots,
            "the second delete's remaining-list must be computed after the first one landed — " +
                "computed before, it still contains 41 and rewrites that row back into the cache",
        )
    }

    @Test
    @DisplayName("a deleted row is filtered out of every later list, whatever source it came from")
    fun deletedRowsAreTombstoned() = runTest {
        // A stale source: the cached list the repository would serve offline
        // still has the row, because the delete's cache rewrite is best-effort.
        onHistory = { listOf(summary(41), summary(40)) }
        val store = foregroundStore()
        initialized(store)
        store.openHistory()
        runCurrent()

        store.delete(41)
        runCurrent()
        store.openHistory()
        runCurrent()

        assertEquals(listOf(40L), store.state.value.history.map { it.id })
        assertTrue(
            historyExclusions.last().contains(41L),
            "and the repository is told, so the row cannot reach the cache either",
        )
    }

    // ---- init versus the user ------------------------------------------------

    @Test
    @DisplayName("a choice made while the tab is loading survives init finishing")
    fun navigationDuringLoadingSurvivesInit() = runTest {
        val gate = CompletableDeferred<Unit>()
        onQueries = {
            withContext(NonCancellable) { gate.await() }
            listOf(query("fixture-a"))
        }
        onHistory = { listOf(summary(41)) }
        val store = foregroundStore()
        launch { store.initialize() }
        runCurrent()
        assertTrue(store.state.value.isLoading)

        store.openHistory()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(AnalysisView.HISTORY, store.state.value.view)
        assertFalse(store.state.value.isLoading)
        assertEquals(listOf("fixture-a"), store.state.value.queries.map { it.id })
        assertEquals(listOf(41L), store.state.value.history.map { it.id })
    }

    @Test
    @DisplayName("init still adopts and polls a running report it finds, it just does not navigate")
    fun initAdoptsWithoutNavigatingAfterUserChoice() = runTest {
        val gate = CompletableDeferred<Unit>()
        onQueries = {
            withContext(NonCancellable) { gate.await() }
            emptyList()
        }
        onPending = { listOf(detail(7, "running")) }
        onReport = { detail(7, "running") }
        onHistory = { listOf(summary(41)) }
        val store = foregroundStore()
        launch { store.initialize() }
        runCurrent()

        store.openHistory()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(AnalysisView.HISTORY, store.state.value.view)
        assertEquals(7L, store.state.value.active?.id, "the running report is picked up regardless")
        assertEquals(listOf(7L), reportCalls, "and polled")
    }

    private companion object {
        const val CREATED = "2031-03-04T09:15:00.000000Z"
        const val COMPLETED = "2031-03-04T09:18:00.000000Z"
        const val RAW_BODY = """{"id":0,"status":"completed"}"""
    }
}
