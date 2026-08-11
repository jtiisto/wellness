package dev.jtiisto.wellness.core.data.hr

import dev.jtiisto.wellness.core.data.db.HrSampleDao
import dev.jtiisto.wellness.core.data.db.HrSampleEntity
import dev.jtiisto.wellness.core.data.db.HrSessionDao
import dev.jtiisto.wellness.core.data.db.HrSessionEntity
import dev.jtiisto.wellness.core.data.db.SetEventDao
import dev.jtiisto.wellness.core.data.db.SetEventEntity
import dev.jtiisto.wellness.core.data.db.key
import dev.jtiisto.wellness.core.data.network.HrApi
import dev.jtiisto.wellness.core.data.network.describeForLog
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import dev.jtiisto.wellness.core.data.sync.SyncResult
import dev.jtiisto.wellness.core.data.sync.SyncSkipReason
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The heart-rate debug-log tag. */
private const val TAG = "hr-sync"

/**
 * The protocol's per-request row cap. Live flushes are far smaller (10 s / 200
 * samples); this only matters when a backlog drains.
 */
private const val BATCH_LIMIT = 1000

/** The 422 the bisect is a response to. Every other status means something else. */
private const val HTTP_UNPROCESSABLE = 422

/**
 * How many rows one run may give up on, and how many it may give up on before
 * anything at all has been accepted.
 *
 * The second breaker is the one that matters: a run that has never had a 2xx
 * cannot tell "three bad rows" from "this server rejects everything I send",
 * and bisecting a full batch under the second reading would quarantine a
 * thousand perfectly good rows one at a time.
 */
private const val MAX_QUARANTINES_PER_RUN = 10
private const val MAX_QUARANTINES_BEFORE_FIRST_SUCCESS = 3

private const val DAY_MS = 24L * 60L * 60L * 1000L

/** Synced samples outlive their upload by a week; there are a lot of them. */
internal const val SAMPLE_RETENTION_MS = 7L * DAY_MS

/** Synced events by sixty days — coach's horizon, and one row per set. */
internal const val EVENT_RETENTION_MS = 60L * DAY_MS

/**
 * A 4xx that is not a 422: the request is wrong in a way no smaller request
 * fixes, so the run stops instead of bisecting a thousand rows into it.
 *
 * Never rethrown out of a flush and never logged by message — the cause is a
 * Ktor exception carrying the whole response body, which is exactly what
 * [describeForLog] exists to keep out of a shareable log.
 */
internal class SystemicUploadException(
    val status: Int,
    cause: Throwable,
) : IllegalStateException("hr upload rejected with HTTP $status", cause)

/**
 * A circuit breaker tripped: too much of this run was poison. Which breaker it
 * was is the interesting part, so the message says — a run that never had a 2xx
 * is far more likely to have met a broken server than a thousand bad beats.
 */
internal class QuarantineLimitException(
    val quarantined: Int,
    val hadSuccess: Boolean,
) : IllegalStateException(
    "hr upload stopped after $quarantined quarantines" +
        if (hadSuccess) "" else " with nothing accepted in the run",
)

/**
 * Uploads heart-rate capture data: RR samples, set-completion events and the
 * sessions that group them.
 *
 * **This is not a sync store in the sense the other two are.** Nothing flows
 * back but counts, so there is no watermark, no arbitration and no
 * `isDirty`/`dirtyGeneration`: rows are appended by the client, pushed once,
 * and marked `isSynced`. The server's ingest is idempotent on each table's
 * natural key, which is what makes a retried batch free and a lost response
 * harmless.
 *
 * The three flows are one pipeline with three sets of lambdas. That is worth it
 * for more than brevity — the error contract below is subtle enough that a
 * second copy of it would be a second chance to get it wrong, and samples stay
 * dormant until Phase 2 while events are live now, so the dormant flow gets the
 * live one's testing for free.
 *
 * **The error contract, which is the whole design:**
 *
 * | Response | What happens |
 * |---|---|
 * | 2xx | mark the batch synced, continue draining |
 * | 422 | bisect the batch, quarantine only the rows that fail alone, resubmit the rest |
 * | other 4xx | systemic: the run stops, and WorkManager is told *not* to retry |
 * | 5xx / network | propagate untouched, so the backoff policy sees it |
 *
 * A tripped circuit breaker is classified by what the run had already achieved:
 * quarantines *after* something was accepted are a retryable failure, because
 * the server demonstrably takes good rows, while a run that reached the breaker
 * having had nothing accepted is treated exactly like a systemic 4xx — it has
 * most likely met a server refusing everything, and retrying that in the
 * background would quarantine good data three rows at a time.
 *
 * Only a 422 bisects. The server validates whole-request — one poison row 422s
 * the entire batch and nothing from it is stored — so bisection is the only way
 * to tell which row it was, and quarantining is how an invalid row stops
 * blocking the ones behind it. Quarantine is permanent for samples and events,
 * which are immutable once written, and provisional for a session, which is
 * rewritten as the capture progresses: `HrSessionDao.upsert` clears the flag on
 * a content change, so a corrected session heals itself.
 *
 * @param clientId the device's client identifier, the same one coach sends. It
 *   is a diagnostic in this protocol, not a credential, and minting a second
 *   one would make the two modules' uploads look like two devices.
 * @param session the server-switch fence. Every write that follows a network
 *   call takes a lease for the duration of the write, never a check in front of
 *   it — see [ServerSessionGate].
 * @param scheduleUpload the debounce hook, called by [scheduleFlush] after a
 *   local append. A lambda for the same reason coach's is: the scheduler is
 *   built around this store.
 */
class HrSyncStore(
    private val sessionDao: HrSessionDao,
    private val sampleDao: HrSampleDao,
    private val eventDao: SetEventDao,
    private val api: HrApi,
    private val clientId: suspend () -> String,
    private val isOnline: () -> Boolean,
    private val session: ServerSessionGate,
    private val debugLog: DebugLog? = null,
    private val scheduleUpload: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Single-flight across every entry point. Two runs would bisect the same
     * batch against each other and double-count the quarantine budget.
     */
    private val flushMutex = Mutex()

    private val _isFlushing = MutableStateFlow(false)

    /** The scheduler's busy hook. */
    val isFlushing: Boolean get() = _isFlushing.value

    /**
     * Arm the upload debounce. Called after appending a set event (and, from
     * Phase 2, after a buffer flush) — the append itself is the caller's
     * transaction, and this is only the nudge that follows it.
     */
    fun scheduleFlush() {
        scheduleUpload()
    }

    /**
     * Whether anything is waiting to go out — the scheduler's dirty hook and
     * half of the backgrounding decision.
     *
     * Quarantined rows do not count, because nothing will offer them: a device
     * holding nothing but poison is not "dirty" and must not keep waking the
     * process to discover that. Each `countPending` carries the same exclusion
     * as its flow's `pendingUpload`, so this answer and the next batch can
     * never disagree. (A quarantined *session* becomes pending again if its
     * content changes — by that point it is a different row, and it should.)
     */
    suspend fun hasPendingData(): Boolean =
        eventDao.countPending() > 0 || sessionDao.countPending() > 0 || sampleDao.countPending() > 0

    /** Block until no flush is in flight — the server switch's drain. */
    suspend fun awaitQuiescence() {
        flushMutex.withLock { }
    }

    /**
     * Everything, in one run and on one quarantine budget: sessions, then
     * events, then samples.
     *
     * The order is a preference, not a requirement — the server enforces no
     * foreign keys between the three tables precisely because batches drain in
     * whatever order the client empties its queues. Sessions go first because
     * they are the cheapest and the most useful to have landed, samples last
     * because they are the only unbounded one.
     */
    suspend fun flush(): SyncResult = runFlush(listOf(sessionsFlow, eventsFlow, samplesFlow))

    /** The set-event log alone — what the debounce is armed for in Phase 1. */
    suspend fun flushEvents(): SyncResult = runFlush(listOf(eventsFlow))

    /** The RR stream alone — the capture service's 10 s / 200-sample cadence. */
    suspend fun flushSamples(): SyncResult = runFlush(listOf(samplesFlow))

    /** Session rows alone, re-upserted whenever one changes. */
    suspend fun flushSessions(): SyncResult = runFlush(listOf(sessionsFlow))

    /**
     * One run: drain each flow, then prune what the server now has.
     *
     * The outcomes map onto [SyncResult] deliberately, because two different
     * schedulers read it. A systemic 4xx returns a *reason* and no
     * error, which the foreground scheduler treats as a silent capped-backoff
     * retry and `SyncFlushWorker.needsRetry` treats as "do not retry" — a
     * background wakeup to re-send a request the server has already refused on
     * its merits buys nothing, while the foreground path costs nothing and
     * recovers by itself if the server is fixed under us.
     */
    private suspend fun runFlush(flows: List<UploadFlow<*, *>>): SyncResult {
        if (!flushMutex.tryLock()) {
            return SyncResult(success = false, reason = SyncSkipReason.ALREADY_SYNCING)
        }
        try {
            if (!isOnline()) return SyncResult(success = false, reason = SyncSkipReason.OFFLINE)
            _isFlushing.value = true
            val run = Run()
            try {
                for (flow in flows) flow.drain(run)
                for (flow in flows) flow.pruneSynced()
                if (run.uploaded > 0 || run.quarantined > 0) {
                    debugLog?.log(
                        TAG,
                        "flush complete",
                        buildJsonObject {
                            put("uploaded", run.uploaded)
                            put("quarantined", run.quarantined)
                        },
                    )
                }
                return SyncResult(success = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (systemic: SystemicUploadException) {
                debugLog?.log(
                    TAG,
                    "flush aborted: the server refused the request itself",
                    buildJsonObject {
                        put("status", systemic.status)
                        put("uploaded", run.uploaded)
                    },
                )
                return SyncResult(success = false, reason = SyncSkipReason.OTHER)
            } catch (limit: QuarantineLimitException) {
                if (limit.hadSuccess) {
                    // The server took other rows in this run, so the ones it
                    // refused really were bad and the quarantines stand. An
                    // ordinary retryable failure: the next run starts with a
                    // fresh budget and the poison is already out of the way.
                    debugLog?.log(
                        TAG,
                        "flush stopped: too many poison rows in one run",
                        buildJsonObject {
                            put("quarantined", limit.quarantined)
                            put("uploaded", run.uploaded)
                        },
                    )
                    return SyncResult(success = false, error = limit)
                }
                // Nothing was accepted, so "three bad rows" and "this server is
                // refusing everything" look identical from here — and the second
                // is far likelier. Classified like a systemic 4xx so the Worker
                // does not retry: waking up to bisect against a server that
                // rejects everything erodes the backlog three good rows at a
                // time, and quarantine on samples and events is permanent.
                debugLog?.log(
                    TAG,
                    "flush stopped: the server accepted nothing in the run",
                    buildJsonObject { put("quarantined", limit.quarantined) },
                )
                return SyncResult(success = false, reason = SyncSkipReason.OTHER)
            } catch (error: Throwable) {
                debugLog?.log(
                    TAG,
                    "flush error",
                    buildJsonObject {
                        put("error", describeForLog(error))
                        put("uploaded", run.uploaded)
                    },
                )
                return SyncResult(success = false, error = error)
            } finally {
                _isFlushing.value = false
            }
        } finally {
            flushMutex.unlock()
        }
    }

    // ---- the flows -------------------------------------------------------

    private val samplesFlow = UploadFlow(
        name = "samples",
        pending = sampleDao::pendingUpload,
        keyOf = HrSampleEntity::key,
        send = { id, rows ->
            val response = api.postSamples(SamplesBatchRequest(id, rows.map { it.toDto() }))
            reconcileIngest("samples", response, rows.size)
        },
        markSynced = { rows -> sampleDao.markSynced(rows.map { it.key() }, now()) },
        quarantine = { rows -> sampleDao.markQuarantined(rows.map { it.key() }) },
        prune = { sampleDao.pruneSynced(now() - SAMPLE_RETENTION_MS) },
    )

    private val eventsFlow = UploadFlow(
        name = "events",
        pending = eventDao::pendingUpload,
        keyOf = SetEventEntity::eventId,
        send = { id, rows ->
            val response = api.postSetEvents(SetEventsBatchRequest(id, rows.map { it.toDto() }))
            reconcileIngest("events", response, rows.size)
        },
        markSynced = { rows -> eventDao.markSynced(rows.map { it.eventId }) },
        quarantine = { rows -> eventDao.markQuarantined(rows.map { it.eventId }) },
        prune = { eventDao.pruneSynced(now() - EVENT_RETENTION_MS) },
    )

    /**
     * Sessions differ from the other two flows twice.
     *
     * **Quarantine is not permanent here.** A sample or an event is immutable
     * once written, so a row the server refused will be refused forever and
     * stays quarantined. A session is rewritten as the capture progresses
     * (started → workout-anchored → ended), and the row that replaced the
     * rejected one deserves its own attempt — so `HrSessionDao.upsert` clears
     * the flag on any content change, and a corrected session comes back
     * through [HrSessionDao.needsUpload] without this store remembering
     * anything.
     *
     * **And there is no prune.** A session is a handful of rows that make tens
     * of thousands of samples interpretable; the samples go first and the row
     * that names them stays.
     *
     * Both marks carry the `dirtyGeneration` the row was read at, so a verdict
     * computed against the session as it was cannot land on the session as it
     * now is. The generation rides out of `needsUpload` on the entity itself and
     * is read straight off it here — nothing threads it through the pipeline,
     * because only this table has one. A miss leaves the rewritten row pending,
     * which is the correct outcome and the one the drain's key identity below is
     * shaped to allow.
     *
     * Marking is one statement per row rather than one over a bound list, as
     * `hr_sessions` has no batch update. A batch is a capture or two.
     */
    private val sessionsFlow = UploadFlow(
        name = "sessions",
        pending = { limit -> sessionDao.needsUpload().take(limit) },
        keyOf = { it.sessionId to it.dirtyGeneration },
        send = { id, rows ->
            val response = api.postSessions(SessionsBatchRequest(id, rows.map { it.toDto() }))
            reconcileUpsert(response, rows.size)
        },
        markSynced = { rows -> rows.forEach { sessionDao.markSynced(it.sessionId, it.dirtyGeneration) } },
        quarantine = { rows -> rows.forEach { sessionDao.markQuarantined(it.sessionId, it.dirtyGeneration) } },
        prune = null,
    )

    /**
     * The reconciliation promise, checked and never enforced.
     *
     * `accepted + duplicates == totalReceived` holds by construction on the
     * server — the counts come from its change counter, not from `len(rows)` —
     * so a mismatch means the two sides disagree about what a batch was, which
     * is worth a line in the log and worth nothing as a failure: the rows are
     * stored either way and the 2xx already said so.
     */
    private fun reconcileIngest(flow: String, response: IngestResponseDto, sent: Int) {
        val consistent = response.accepted + response.duplicates == response.totalReceived &&
            response.totalReceived == sent
        if (consistent) return
        debugLog?.log(
            TAG,
            "ingest counts do not reconcile",
            buildJsonObject {
                put("flow", flow)
                put("sent", sent)
                put("accepted", response.accepted)
                put("duplicates", response.duplicates)
                put("totalReceived", response.totalReceived)
            },
        )
    }

    /**
     * The upsert's weaker promise: [UpsertResponseDto.upserted] counts distinct
     * sessions written and may legitimately be lower than what was sent, so
     * only the received count is compared.
     */
    private fun reconcileUpsert(response: UpsertResponseDto, sent: Int) {
        if (response.totalReceived == sent && response.upserted <= response.totalReceived) return
        debugLog?.log(
            TAG,
            "upsert counts do not reconcile",
            buildJsonObject {
                put("sent", sent)
                put("upserted", response.upserted)
                put("totalReceived", response.totalReceived)
            },
        )
    }

    /** One flush run's shared budget. Not thread-safe; [flushMutex] is why. */
    private class Run {
        var uploaded = 0
        var quarantined = 0

        /** Whether any request in this run has been accepted. */
        var hadSuccess = false
    }

    /**
     * One table's upload, as a set of lambdas over the shared pipeline.
     *
     * [prune] is null for the one table with no retention rule, which is
     * sessions. Everything else about the three is the same shape, which is the
     * point: the error contract is subtle enough that a second copy of it would
     * be a second chance to get it wrong.
     */
    private inner class UploadFlow<R, K>(
        private val name: String,
        private val pending: suspend (Int) -> List<R>,
        private val keyOf: (R) -> K,
        private val send: suspend (String, List<R>) -> Unit,
        private val markSynced: suspend (List<R>) -> Unit,
        private val quarantine: suspend (List<R>) -> Unit,
        private val prune: (suspend () -> Int)?,
    ) {

        /**
         * Upload until nothing is pending.
         *
         * The loop terminates because every iteration removes its whole batch
         * from the pending set: each row is either marked synced or marked
         * quarantined, and both flags are excluded by the query that offers the
         * next batch.
         *
         * The head-of-batch check backs that reasoning with a fact, and
         * [keyOf] is what makes it able to tell the two head-repeats apart:
         *
         * - A **session re-dirtied while its batch was in flight** comes back at
         *   the head with a *new* generation. Its verdict missed on purpose, the
         *   row genuinely still needs uploading, and the key differs — so it is
         *   re-offered, which is the whole point of the guarded marks.
         * - A **marking call that stopped removing rows** produces the same id
         *   at the same generation, the key is identical, and the flow fails its
         *   run rather than re-sending the same batch until the battery is gone.
         *
         * Samples and events are immutable once written, so for them the row's
         * identity is the whole key and the second case is the only one.
         */
        suspend fun drain(run: Run) {
            val id = clientId()
            var previousHead: K? = null
            while (true) {
                val batch = pending(BATCH_LIMIT)
                if (batch.isEmpty()) return
                val head = keyOf(batch.first())
                check(head != previousHead) { "hr $name upload made no progress on $head" }
                previousHead = head
                upload(id, batch, run)
            }
        }

        /** Retention, after a run that got everything out. */
        suspend fun pruneSynced() {
            val prune = this.prune ?: return
            val removed = session.withWriteLease { prune() }
            if (removed > 0) {
                debugLog?.log(
                    TAG,
                    "pruned synced rows",
                    buildJsonObject {
                        put("flow", name)
                        put("rows", removed)
                    },
                )
            }
        }

        /**
         * Send one batch and act on the answer.
         *
         * Recursive with [bisect]: a 422 splits the batch and each half comes
         * straight back here, so the halves get the identical treatment of
         * every status — which is what keeps a systemic 4xx systemic and a 5xx
         * retryable even when it arrives four levels into a bisection.
         */
        private suspend fun upload(id: String, batch: List<R>, run: Run) {
            try {
                send(id, batch)
            } catch (rejected: ClientRequestException) {
                val status = rejected.response.status.value
                if (status != HTTP_UNPROCESSABLE) throw SystemicUploadException(status, rejected)
                bisect(id, batch, run)
                return
            }
            run.hadSuccess = true
            run.uploaded += batch.size
            // The lease covers the write itself, not a check in front of it: a
            // switch confirmed while this coroutine was awaiting the response
            // must wait for the mark, or find it refused.
            session.withWriteLease { markSynced(batch) }
        }

        /**
         * Split a rejected batch until the poison rows stand alone.
         *
         * Halves rather than a linear scan: a batch of a thousand with one bad
         * row costs about twenty requests instead of a thousand, and the common
         * case (one bad row) is the one that gets the most out of it.
         */
        private suspend fun bisect(id: String, batch: List<R>, run: Run) {
            if (batch.size == 1) {
                isolate(batch, run)
                return
            }
            val middle = batch.size / 2
            upload(id, batch.subList(0, middle), run)
            upload(id, batch.subList(middle, batch.size), run)
        }

        /** A row that fails alone. The breakers are checked before giving up on it. */
        private suspend fun isolate(row: List<R>, run: Run) {
            if (run.quarantined >= MAX_QUARANTINES_PER_RUN ||
                (!run.hadSuccess && run.quarantined >= MAX_QUARANTINES_BEFORE_FIRST_SUCCESS)
            ) {
                throw QuarantineLimitException(run.quarantined, run.hadSuccess)
            }
            run.quarantined++
            // Under the lease like every other write that follows a response,
            // and it is what takes the row out of the pending set — both for
            // the rest of this drain loop and for every later run.
            session.withWriteLease { quarantine(row) }
            debugLog?.log(
                TAG,
                "row quarantined",
                buildJsonObject {
                    put("flow", name)
                    put("quarantinedThisRun", run.quarantined)
                },
            )
        }
    }
}
