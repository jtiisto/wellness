package dev.jtiisto.wellness.core.data.analysis

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.jtiisto.wellness.core.data.network.AnalysisHttpException
import dev.jtiisto.wellness.core.data.network.isNetworkError
import dev.jtiisto.wellness.core.data.sync.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Which of the four Analysis surfaces is on screen. Store state, never a nav route. */
enum class AnalysisView { QUERIES, PROGRESS, REPORT, HISTORY }

/**
 * The report the module is currently *working on*.
 *
 * Two shapes, because for a few hundred milliseconds after a submit there is a
 * report id and nothing else: `POST /reports` answers `{id, status}`, and
 * `created_at` — which the progress clock counts from — does not exist on the
 * client until the first poll tick brings the full row back. A [Stub] is that
 * gap, and it renders an elapsed time of exactly `0s`, as the PWA does.
 *
 * This is deliberately **not** what the report screen renders; see
 * [AnalysisState.viewing].
 */
sealed interface ActiveReport {
    val id: Long
    val queryId: String
    val queryLabel: String

    /** Post-submit, pre-first-tick. */
    data class Stub(
        override val id: Long,
        override val queryId: String,
        override val queryLabel: String,
    ) : ActiveReport

    /** A full row from the server. */
    data class Loaded(val detail: ReportDetailDto) : ActiveReport {
        override val id: Long get() = detail.id
        override val queryId: String get() = detail.queryId
        override val queryLabel: String get() = detail.queryLabel
    }
}

/**
 * Everything the Analysis tab renders.
 *
 * [active] and [viewing] are two slots on purpose, and conflating them was a
 * real defect: the report being polled and the report being read are not the
 * same report. Opening a finished report from History while a query runs used to
 * put it in the one slot the poll also wrote to, so when the query finished its
 * tick swapped the page out from under whoever was reading it. The progress
 * screen watches [active]; the report screen renders [viewing]; neither can
 * disturb the other.
 *
 * [unknownStalled] is the one state a poll can end in that is neither a report
 * nor an error: the server kept answering with a status this build does not
 * recognise until the ceiling ran out. The progress screen says so and offers a
 * re-check rather than spinning forever.
 */
data class AnalysisState(
    val view: AnalysisView = AnalysisView.QUERIES,
    val queries: List<AnalysisQueryDto> = emptyList(),
    val active: ActiveReport? = null,
    val viewing: ReportDetailDto? = null,
    val history: List<ReportSummaryDto> = emptyList(),
    val isLoading: Boolean = true,
    val queriesError: String? = null,
    val submitInFlight: Boolean = false,
    val unknownStalled: Boolean = false,
)

/**
 * The Analysis module's one piece of mutable state, and the poll that drives it.
 *
 * **Concurrency contract.** Every mutation runs on one injected single-threaded
 * control context, and every asynchronous commit additionally carries a
 * generation. Serialization alone is not enough: a request that has already
 * returned from Ktor has no suspension point left before its write, so
 * cancelling its job cannot stop it. There are three counters, because there are
 * three independent things a late answer can be wrong about:
 *
 * - [pollGeneration] — which report the poll is following. Moved by any poll
 *   start, stop, adoption or delete-invalidation.
 * - [openGeneration] — which report the user asked to read. Moved by a newer
 *   open, any adoption, any navigation, or a delete of the report being opened.
 *   Deliberately separate from the poll's: opening a History row must not
 *   disturb a query running in the background.
 * - [viewIntentGeneration] — whether the user has navigated since. It is what
 *   stops a slow `initialize()` from yanking the screen back to its own idea of
 *   where the user should be.
 *
 * History reads and deletes are serialized further, under [historyMutex], and
 * every list that reaches state or disk is filtered through [deletedIds]. Two
 * deletes each holding a pre-computed "remaining" list would otherwise write
 * each other's row back, and a refresh that started before a delete would
 * resurrect it on landing.
 *
 * **The poll belongs here, not to a screen.** Navigating between the four
 * sub-views, or to another tab entirely, leaves it running; only Cancel abandons
 * a report. The PWA killed the poll on every navigation, which orphaned any
 * report the user walked away from. Backgrounding pauses it (there is nobody to
 * show a result to) and returning resumes with an immediate tick.
 *
 * @param controlContext single-threaded. Production hands it a dedicated
 *   executor; tests hand it the test scheduler, which is what makes the races
 *   below assertable rather than hopeful.
 * @param now epoch millis, for the unknown-status ceiling. Injected so virtual
 *   time drives it.
 */
class AnalysisStore(
    private val repository: AnalysisRepository,
    private val events: AnalysisEvents,
    private val debugLog: DebugLog,
    scope: CoroutineScope,
    private val controlContext: CoroutineContext,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val control = scope + controlContext

    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    // Everything below is confined to the control context.
    private var initDeferred: Deferred<Unit>? = null
    private var initTransientFailure = false
    private var isForeground = false
    private var pollGeneration = 0L
    private var pollTarget: Long? = null
    private var pollJob: Job? = null
    private var unknownSince: Long? = null
    private var openGeneration = 0L
    private var openTarget: Long? = null
    private var viewIntentGeneration = 0L

    /** Whole history operations, never halves of them. */
    private val historyMutex = Mutex()

    /**
     * Reports this session has deleted.
     *
     * Session-lifetime and never pruned: server ids are autoincrement, so an id
     * that has been deleted can never legitimately come back, and the set is
     * bounded by how many reports one person deletes between launches.
     */
    private val deletedIds = mutableSetOf<Long>()

    /** Touched only from `Application.onCreate`, on the main thread. */
    private var lifecycleRegistered = false

    /**
     * Register the process-lifecycle observer. Idempotent; call from
     * `Application.onCreate`, as `SyncOrchestrator.start()` is called.
     *
     * Wiring this at process start rather than when the tab first opens is what
     * lets a pre-init foreground event be *latched* instead of lost — the flag
     * it sets is what [initialize] reads to decide whether to start polling an
     * adopted report straight away.
     */
    fun start() {
        if (lifecycleRegistered) return
        lifecycleRegistered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = onForeground()
                override fun onStop(owner: LifecycleOwner) = onBackground()
            },
        )
    }

    // ---- entry points ------------------------------------------------------

    /**
     * Load the query list and adopt whatever is already running.
     *
     * Memoized: the screen calls this on every composition of the tab, and all
     * of them await the same [Deferred] rather than re-running the two fetches.
     * The memo is cleared only by a foreground retry after a *transient*
     * failure — the PWA memoized unconditionally, so one offline launch left the
     * query list empty for the lifetime of the page.
     */
    suspend fun initialize() {
        val deferred = withContext(controlContext) {
            initDeferred ?: control.async { runInitialize() }.also { initDeferred = it }
        }
        deferred.await()
    }

    fun onForeground() {
        control.launch {
            isForeground = true
            val init = initDeferred
            // Nothing has been loaded yet: latch the flag and let initialize()
            // consume it. Starting work here would race the first init.
            if (init == null || !init.isCompleted) return@launch

            if (initTransientFailure) {
                retryInitialize()
                return@launch
            }

            val target = pollTarget
            if (target != null) {
                // Paused by backgrounding. A new generation supersedes any tick
                // left over from the background era, so its answer — which may
                // be older than the one we are about to ask for — cannot land
                // after the fresh one.
                if (pollJob == null) {
                    pollGeneration += 1
                    pollJob = launchPollLoop(target, pollGeneration)
                }
                return@launch
            }

            checkPendingOnForeground()
        }
    }

    fun onBackground() {
        control.launch {
            isForeground = false
            // Pause, not stop: [pollTarget] and the generation stay, so coming
            // back resumes the same report rather than losing it.
            pollJob?.cancel()
            pollJob = null
        }
    }

    /**
     * Run [queryId], optionally with a location the user typed.
     *
     * The double-tap guard is read and written inside the control context, not
     * in the UI: disabling a card only takes effect at the next recomposition,
     * and two taps fit comfortably before one of those.
     */
    fun submit(queryId: String, location: String?) {
        control.launch {
            if (_state.value.submitInFlight) return@launch
            _state.update { it.copy(submitInFlight = true) }
            try {
                val response = try {
                    repository.submit(queryId, location)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    handleSubmitFailure(error)
                    return@launch
                }
                // Deliberately *not* generation-guarded. A running report
                // finishing during this round trip is the ordinary way a submit
                // succeeds at all, and it moves the poll generation — dropping
                // the adoption here would strand the report we just created.
                val label = _state.value.queries.firstOrNull { it.id == queryId }?.label.orEmpty()
                adopt(ActiveReport.Stub(response.id, queryId, label))
                startPoll(response.id)
            } finally {
                _state.update { it.copy(submitInFlight = false) }
            }
        }
    }

    /**
     * Show report [id], polling it instead if the server says it is still running.
     *
     * The open carries its own generation. Without one, a report deleted while
     * its `GET` was in flight would be installed into view by the answer that
     * arrived afterwards — nothing else invalidates it, because a report merely
     * being read is neither the poll's target nor `active`. It also makes two
     * quick taps land in the order they were made rather than the order the
     * server happened to answer in.
     *
     * A running poll is left alone unless this call adopts a different report:
     * opening an old report from History while a query runs must not abandon the
     * query, and must not put that query's result on screen either.
     */
    fun openReport(id: Long) {
        control.launch {
            viewIntentGeneration += 1
            openGeneration += 1
            val generation = openGeneration
            openTarget = id

            val fetched = try {
                repository.report(id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // A superseded open fails silently: the user has already asked
                // for something else, and a snackbar about the thing they left
                // is noise.
                if (generation != openGeneration) return@launch
                openTarget = null
                events.post(describeReportFailure(error))
                return@launch
            }
            if (generation != openGeneration) return@launch
            openTarget = null

            val detail = fetched.detail
            if (detail.reportStatus.isTerminal) {
                // Only the reading slot moves. Whatever the poll is doing, it
                // keeps doing it.
                _state.update { it.copy(viewing = detail, view = AnalysisView.REPORT) }
                fetched.raw?.let { raw -> cacheReport(detail.id, raw) }
                return@launch
            }
            _state.update {
                it.copy(viewing = null, active = ActiveReport.Loaded(detail), view = AnalysisView.PROGRESS)
            }
            if (pollTarget != detail.id) startPoll(detail.id)
        }
    }

    /** The poll is untouched: a list is a place to look, not a reason to stop working. */
    fun openHistory() {
        control.launch {
            navigationIntent()
            _state.update { it.copy(view = AnalysisView.HISTORY) }
            loadHistory()
        }
    }

    fun openQueries() {
        control.launch {
            navigationIntent()
            _state.update { it.copy(view = AnalysisView.QUERIES) }
        }
    }

    /**
     * Abandon the running report client-side.
     *
     * The server task carries on and the finished report turns up in History
     * later; this is the escape hatch from the progress screen, not a cancel the
     * server would honour — it has no such endpoint.
     */
    fun cancelActive() {
        control.launch {
            navigationIntent()
            stopPoll()
            _state.update {
                it.copy(active = null, view = AnalysisView.QUERIES, unknownStalled = false)
            }
        }
    }

    /** Re-check a report whose status stopped making sense. */
    fun recheck() {
        control.launch {
            val id = _state.value.active?.id ?: return@launch
            startPoll(id)
        }
    }

    /**
     * Delete [id] on the server, then everywhere else.
     *
     * The whole operation holds [historyMutex], and the remaining-list snapshot
     * is taken *after* acquiring it. Computed before, two concurrent deletes
     * would each hold a list still containing the other's row and the second
     * writer would put the first one back.
     */
    fun delete(id: Long) {
        control.launch {
            historyMutex.withLock {
                val remaining = _state.value.history.filterNot { it.id == id }
                try {
                    repository.delete(id, remaining)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    events.post(AnalysisEvent.DeleteError(describe(error)))
                    return@withLock
                }

                deletedIds += id
                // Invalidate before anything else. A tick already in flight for
                // this id would otherwise come back and re-seat a report that no
                // longer exists — and, if it read terminal, walk the user onto it.
                val pollWasTarget = pollTarget == id
                if (pollWasTarget) stopPoll()
                if (openTarget == id) invalidateOpenRequest()

                _state.update { current ->
                    current.copy(
                        history = remaining.filterNot { it.id in deletedIds },
                        // Two independent slots, two independent id matches.
                        active = if (current.active?.id == id) null else current.active,
                        viewing = if (current.viewing?.id == id) null else current.viewing,
                        view = when {
                            current.view == AnalysisView.REPORT && current.viewing?.id == id ->
                                AnalysisView.QUERIES
                            current.view == AnalysisView.PROGRESS &&
                                (pollWasTarget || current.active?.id == id) -> AnalysisView.QUERIES
                            else -> current.view
                        },
                        unknownStalled = if (current.active?.id == id) false else current.unknownStalled,
                    )
                }
                events.post(AnalysisEvent.DeleteSuccess)
            }
        }
    }

    // ---- initialization ----------------------------------------------------

    private suspend fun runInitialize() {
        initTransientFailure = false
        // Everything init decides about *where the user should be* defers to a
        // navigation the user makes while it is still loading.
        val intent = viewIntentGeneration
        _state.update { it.copy(isLoading = true) }

        var queriesFailure: Throwable? = null
        try {
            val queries = repository.queries()
            _state.update { it.copy(queries = queries, queriesError = null) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            queriesFailure = error
            noteQueriesFailure(error)
        }

        val adopted = try {
            repository.pending().firstOrNull()?.also { adoptOnInit(it, intent) } != null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (isRetryable(error)) initTransientFailure = true
            debugLog.log(TAG, "pending check failed (${error.javaClass.simpleName})")
            false
        }

        if (!adopted) {
            // PWA parity: offline with something cached is better spent on the
            // history list than on an empty query grid.
            val landedOnHistory =
                queriesFailure != null && isNetworkError(queriesFailure) && landOnCachedHistory(intent)
            if (!landedOnHistory) setViewIfUnnavigated(intent, AnalysisView.QUERIES)
        }
        _state.update { it.copy(isLoading = false) }
    }

    /** Re-run the whole init, query list included, after a transient first failure. */
    private suspend fun retryInitialize() {
        val deferred = control.async { runInitialize() }
        initDeferred = deferred
        deferred.await()
    }

    private fun noteQueriesFailure(error: Throwable) {
        val http = error as? AnalysisHttpException
        val message = when {
            // Deviation 11: `/queries` 404 is the module being switched off
            // server-side, not a missing report. No cache, no snackbar — the tab
            // simply says so.
            http?.status == HTTP_NOT_FOUND -> MODULE_DISABLED
            isNetworkError(error) -> QUERIES_OFFLINE
            else -> describe(error)
        }
        initTransientFailure = isRetryable(error)
        _state.update { it.copy(queriesError = message) }
    }

    private suspend fun landOnCachedHistory(intent: Long): Boolean {
        if (!loadHistory()) return false
        setViewIfUnnavigated(intent, AnalysisView.HISTORY)
        return true
    }

    /**
     * Adoption is unconditional; **navigating** to it is not.
     *
     * A report that is already running has to be picked up and polled whatever
     * the user is looking at. Where they are looking is their decision if they
     * have made one since init started.
     */
    private fun adoptOnInit(row: ReportDetailDto, intent: Long) {
        val terminal = row.reportStatus.isTerminal
        val navigable = intent == viewIntentGeneration
        _state.update {
            it.copy(
                active = ActiveReport.Loaded(row),
                viewing = if (terminal && navigable) row else it.viewing,
                view = when {
                    !navigable -> it.view
                    terminal -> AnalysisView.REPORT
                    else -> AnalysisView.PROGRESS
                },
            )
        }
        if (!terminal) startPoll(row.id)
    }

    private suspend fun checkPendingOnForeground() {
        val current = _state.value
        // What is *displayed* is the reading slot, not the polling one.
        val terminalOnScreen = current.view == AnalysisView.REPORT &&
            current.viewing?.reportStatus?.isTerminal == true
        if (terminalOnScreen) return

        val generation = pollGeneration
        val row = try {
            repository.pending().firstOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            debugLog.log(TAG, "foreground pending check failed (${error.javaClass.simpleName})")
            return
        }
        if (generation != pollGeneration || row == null) return

        adopt(ActiveReport.Loaded(row), navigateFromQueriesOnly = true)
        startPoll(row.id)
    }

    /**
     * Seat a report as the one being worked on.
     *
     * [navigateFromQueriesOnly] is the foreground case: adopting silently while
     * the user reads something is right, hijacking them out of it is not.
     */
    private fun adopt(report: ActiveReport, navigateFromQueriesOnly: Boolean = false) {
        invalidateOpenRequest()
        viewIntentGeneration += 1
        _state.update {
            it.copy(
                active = report,
                view = if (!navigateFromQueriesOnly || it.view == AnalysisView.QUERIES) {
                    AnalysisView.PROGRESS
                } else {
                    it.view
                },
            )
        }
    }

    // ---- submit failures ---------------------------------------------------

    private suspend fun handleSubmitFailure(error: Throwable) {
        val http = error as? AnalysisHttpException
        if (http?.status == HTTP_CONFLICT) {
            adoptAfterConflict(http)
            return
        }
        events.post(
            if (isNetworkError(error)) {
                AnalysisEvent.SubmitOffline
            } else {
                AnalysisEvent.SubmitError(describe(error))
            },
        )
    }

    /**
     * A 409 means one is already running — so show it.
     *
     * The PWA raised a toast and left the user on a query grid whose every card
     * would 409 again; the running report was unreachable until a page reload.
     */
    private suspend fun adoptAfterConflict(conflict: AnalysisHttpException) {
        val generation = pollGeneration
        val pending = try {
            repository.pending()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            events.post(
                if (isNetworkError(error)) {
                    AnalysisEvent.SubmitOffline
                } else {
                    AnalysisEvent.SubmitError(describe(error))
                },
            )
            return
        }
        // Something adopted or abandoned a report while we were asking; that
        // decision is newer than this one.
        if (generation != pollGeneration) return

        val row = pending.firstOrNull()
        if (row == null) {
            // It finished between the 409 and the pending call. Nothing to adopt
            // — but History now has a row the user has not seen.
            events.post(AnalysisEvent.SubmitError(conflict.detail ?: CONFLICT_FALLBACK))
            loadHistory()
            return
        }
        adopt(ActiveReport.Loaded(row))
        startPoll(row.id)
        events.post(AnalysisEvent.AdoptedRunning)
    }

    // ---- polling -----------------------------------------------------------

    private fun startPoll(id: Long) {
        pollGeneration += 1
        pollTarget = id
        unknownSince = null
        _state.update { it.copy(unknownStalled = false) }
        pollJob?.cancel()
        pollJob = if (isForeground) launchPollLoop(id, pollGeneration) else null
    }

    private fun stopPoll() {
        pollGeneration += 1
        pollTarget = null
        unknownSince = null
        pollJob?.cancel()
        pollJob = null
    }

    private fun launchPollLoop(id: Long, generation: Long): Job = control.launch {
        while (true) {
            pollTick(id, generation)
            if (generation != pollGeneration) return@launch
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun pollTick(id: Long, generation: Long) {
        val fetched = try {
            repository.report(id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (generation != pollGeneration || pollTarget != id) return
            if (error is AnalysisHttpException && error.status == HTTP_NOT_FOUND) {
                onPolledReportGone(id)
                return
            }
            // Transient failures never stop the poll. That is PWA parity and it
            // is deliberate: polling only runs in the foreground, so "forever"
            // is bounded by the user standing here watching it.
            debugLog.log(TAG, "poll tick failed for $id (${error.javaClass.simpleName})")
            return
        }
        // No suspension point between here and the commit — this check is what
        // makes a superseded tick harmless.
        if (generation != pollGeneration || pollTarget != id) return
        val terminal = applyTick(fetched.detail)
        // Only now, and only for a result this store accepted, does the body
        // reach disk.
        if (terminal) fetched.raw?.let { raw -> cacheReport(fetched.detail.id, raw) }
    }

    /** Returns whether the tick was terminal, and therefore worth caching. */
    private fun applyTick(detail: ReportDetailDto): Boolean {
        val status = detail.reportStatus
        if (status.isTerminal) {
            stopPoll()
            _state.update { current ->
                val onProgress = current.view == AnalysisView.PROGRESS
                current.copy(
                    active = ActiveReport.Loaded(detail),
                    // Auto-advance belongs to the progress screen alone: it is
                    // the one place the user is waiting for this result. From
                    // anywhere else — including a *different* report already
                    // open — the reading slot is left exactly as it was.
                    viewing = if (onProgress) detail else current.viewing,
                    view = if (onProgress) AnalysisView.REPORT else current.view,
                )
            }
            refreshHistoryAsync()
            return true
        }

        _state.update { it.copy(active = ActiveReport.Loaded(detail)) }

        if (status != ReportStatus.UNKNOWN) {
            unknownSince = null
            return false
        }
        val since = unknownSince
        if (since == null) {
            unknownSince = now()
            return false
        }
        if (now() - since < UNKNOWN_CEILING_MS) return false
        // Longer than any registered query plus the server's reaper grace. The
        // row is not coming back; say so instead of polling into the evening.
        stopPoll()
        _state.update { it.copy(unknownStalled = true) }
        debugLog.log(TAG, "unknown status ceiling reached for ${detail.id}")
        return false
    }

    private fun onPolledReportGone(id: Long) {
        stopPoll()
        _state.update { current ->
            val wasViewingIt = current.viewing?.id == id
            current.copy(
                active = if (current.active?.id == id) null else current.active,
                viewing = if (wasViewingIt) null else current.viewing,
                view = when {
                    current.view == AnalysisView.PROGRESS -> AnalysisView.QUERIES
                    current.view == AnalysisView.REPORT && wasViewingIt -> AnalysisView.QUERIES
                    else -> current.view
                },
                unknownStalled = false,
            )
        }
        events.post(AnalysisEvent.ReportDeletedRemotely)
    }

    // ---- history -----------------------------------------------------------

    /**
     * Refresh the list. Silent, as the PWA is: a stale list is not worth a
     * snackbar.
     *
     * Runs as a whole operation under [historyMutex] so it cannot interleave
     * with a delete, and every row is filtered through [deletedIds] — a response
     * that left the server before a delete can arrive after it.
     */
    private suspend fun loadHistory(): Boolean = historyMutex.withLock {
        try {
            val history = repository.history(deletedIds.toSet())
            _state.update { it.copy(history = history.filterNot { row -> row.id in deletedIds }) }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            debugLog.log(TAG, "history refresh failed (${error.javaClass.simpleName})")
            false
        }
    }

    private fun refreshHistoryAsync() {
        control.launch { loadHistory() }
    }

    /**
     * Write an accepted report body, unless the report has since been deleted.
     *
     * Takes [historyMutex] so the check and the write cannot straddle a delete:
     * inside the lock, [deletedIds] is settled, and a delete waiting behind us
     * removes the row after we wrote it rather than before.
     */
    private suspend fun cacheReport(id: Long, raw: String) {
        historyMutex.withLock {
            if (id in deletedIds) return@withLock
            repository.cacheAcceptedReport(id, raw)
        }
    }

    // ---- generations -------------------------------------------------------

    /** The user going somewhere: nothing in flight gets to move them afterwards. */
    private fun navigationIntent() {
        viewIntentGeneration += 1
        invalidateOpenRequest()
    }

    private fun invalidateOpenRequest() {
        openGeneration += 1
        openTarget = null
    }

    private fun setViewIfUnnavigated(intent: Long, view: AnalysisView) {
        if (intent != viewIntentGeneration) return
        _state.update { it.copy(view = view) }
    }

    // ---- error copy --------------------------------------------------------

    /** Worth retrying the whole init on the next foreground. A 404 is not. */
    private fun isRetryable(error: Throwable): Boolean =
        isNetworkError(error) || (error as? AnalysisHttpException)?.isServerError == true

    private fun describeReportFailure(error: Throwable): AnalysisEvent {
        val http = error as? AnalysisHttpException
        return when {
            http == null || http.isServerError -> AnalysisEvent.ReportUnavailableOffline
            else -> AnalysisEvent.ReportError(http.detail ?: "HTTP ${http.status}")
        }
    }

    private fun describe(error: Throwable): String = when {
        error is AnalysisHttpException -> error.detail ?: "HTTP ${error.status}"
        isNetworkError(error) -> "Server unreachable"
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    companion object {
        const val TAG = "analysis"

        /** PWA parity, with the first tick fired immediately rather than after 3s. */
        const val POLL_INTERVAL_MS = 3_000L

        /**
         * How long an unrecognised status may persist before the poll gives up.
         *
         * The longest registered query may run 400s and the server's stale-report
         * reaper adds 120s of grace on top, so 520s is the worst case in which a
         * live report is still legitimately non-terminal. 600s is that with
         * headroom. Statuses this build *does* know have no ceiling at all — the
         * reaper guarantees they reach a terminal value.
         */
        const val UNKNOWN_CEILING_MS = 600_000L

        const val MODULE_DISABLED = "Analysis is disabled on the server"
        const val QUERIES_OFFLINE = "Server unreachable — queries unavailable offline."

        private const val CONFLICT_FALLBACK = "A query is already in progress."
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
    }
}
