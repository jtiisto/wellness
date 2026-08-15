package dev.jtiisto.wellness.core.data.hr

import dev.jtiisto.wellness.core.ble.buffer.BufferedSample
import dev.jtiisto.wellness.core.ble.buffer.HrSampleSink
import dev.jtiisto.wellness.core.ble.capture.CaptureSession
import dev.jtiisto.wellness.core.data.db.HrSampleDao
import dev.jtiisto.wellness.core.data.db.HrSampleEntity
import dev.jtiisto.wellness.core.data.db.HrSessionDao
import dev.jtiisto.wellness.core.data.db.HrSessionEntity
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

private const val TAG = "hr-capture"

/**
 * How hard the stop path tries to get the last buffer out before it decides the
 * session cannot be closed honestly. Three attempts two seconds apart covers a
 * transient database lock and gives up long before the user notices.
 */
private const val FINAL_FLUSH_ATTEMPTS = 3
private const val FINAL_FLUSH_RETRY_MS = 2_000L

/**
 * Room for a handful of queued lifecycle events.
 *
 * Transitions happen a few times per capture and drain requests at most once per
 * buffer flush, so this is never close to full in practice. It exists so the one
 * event that is *sent without waiting* — the drain request, which the sample path
 * raises from inside the actor's own stop handling — can never block.
 */
private const val EVENT_BUFFER = 64

/** How [HrCaptureStore.stopSession] ended. Each outcome means something different downstream. */
enum class CaptureStopResult {
    /** The final buffer landed and `endedAtMs` is set. */
    CLOSED,

    /**
     * The final buffer would not persist, so the session was deliberately left
     * open — see [HrCaptureStore.stopSession].
     */
    LEFT_OPEN,

    /** The row is gone (a server switch wiped it). Nothing to close, nothing wrong. */
    SESSION_GONE,

    /** There was no capture session to stop. */
    NOT_RUNNING,
}

/**
 * The capture session's life in Room, and the landing point for every RR
 * interval the strap produces.
 *
 * This is the whole of the seam between `:core:ble` and persistence. The BLE
 * side knows an [HrSampleSink] and a session id string; this side knows Room,
 * the write lease and the upload debounce, and neither has to know the other
 * exists. `HrSyncStore` then picks the rows up on its own cadence — nothing here
 * ever talks to the network.
 *
 * ## The lifecycle is an actor
 *
 * Opening a session, resuming one, anchoring it, ending it and finishing a
 * deferred close are **events on a channel**, consumed one at a time by a single
 * coroutine that owns [owned] and [pendingCloses] outright. Callers send an event
 * and await its verdict; nothing outside the actor ever reads or writes that
 * state.
 *
 * This replaced a mutex, and not for elegance. Every one of those transitions is
 * a read of the current session, a decision, a suspending database write, and a
 * write back — and with a lock the correctness argument is about which *pairs* of
 * critical sections can interleave. Five review passes found five defects, each
 * one living in a gap *between* two guarded regions rather than inside either:
 *
 * - A stop registered its deferred close in one locked block and cleared the
 *   current session in a second. Between them the session was simultaneously
 *   "recording" and "waiting to be closed", so a drain that acquired the lock in
 *   the gap deleted the close as redundant — and nothing was recording.
 * - Swapping those two blocks stranded the entry instead: a drain landing in the
 *   new gap found nothing to do, and drains only ever arrive from a *successful*
 *   flush, which an emptied buffer never produces again.
 * - A stop whose caller was cancelled while waiting on that second lock never
 *   cleared the current session at all, and the store is app-lived, so it pointed
 *   at an ended session for the rest of the process.
 *
 * None of those is representable here. A stop is one event: read, flush, decide,
 * write, clear — with no other lifecycle event able to observe a half-finished
 * one. Cancelling a *caller* cancels its `await`, not the actor, so the state
 * transition completes either way. And "who owns this session" stopped being an
 * inference from a shared flow and became a variable in one coroutine.
 *
 * The actor is **app-lived and never stops**: it is fed by a scope that outlives
 * every capture, which is what lets a deferred close survive the service that
 * requested it.
 *
 * **Every write goes through [session].** These rows are server-scoped: they are
 * destined for one specific `hr` module, and a server switch wipes them. The
 * lease is what makes "the switch waits for writes already in flight" true for
 * a capture that is running while the user changes servers. A write refused by a
 * closed gate throws [dev.jtiisto.wellness.core.data.sync.ServerSessionClosedException]
 * out of [store], which the sample buffer treats as a failed flush and keeps its
 * batch — a failure, deliberately, rather than a silent drop. Inside the actor
 * the same refusal fails one event and leaves the actor running.
 *
 * @param scope app-lived. The actor is launched here and never completes.
 * @param scheduleUpload the upload debounce, armed after any write that leaves
 *   something pending. A lambda for the same reason `SetEventRecorder`'s is: the
 *   scheduler is built around `HrSyncStore`, so resolving it eagerly here would
 *   be a construction cycle.
 * @param newSessionId the client-minted session UUID, injected so tests can name
 *   the sessions they assert on.
 * @param now epoch milliseconds. A **data value** in this protocol, not a sync
 *   watermark — there are no server-issued stamps here for the opaque-timestamp
 *   rule to reach.
 */
class HrCaptureStore(
    private val sessionDao: HrSessionDao,
    private val sampleDao: HrSampleDao,
    private val session: ServerSessionGate,
    scope: CoroutineScope,
    private val scheduleUpload: () -> Unit = {},
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val debugLog: DebugLog? = null,
) : HrSampleSink {

    /**
     * A session whose final flush would not land, and the instant it must carry
     * once it does.
     *
     * The spec's rule is that `endedAtMs` may only be written when everything the
     * session covers is durable, so a failed final flush leaves the row open.
     * Nothing used to end that sentence: the sample buffer's app-lived retry would
     * eventually persist the rows and the row would stay open for ever. This is
     * the other half — the close, held until the data it claims to cover exists.
     * [endedAtMs] is the moment capture *stopped*, not the moment the flush
     * finally succeeded, because that is the instant the session actually ended.
     */
    private data class PendingClose(val sessionId: String, val endedAtMs: Long)

    /**
     * What the actor accepts. Each event that has an answer carries the
     * [CompletableDeferred] its caller is waiting on.
     */
    private sealed interface Event {

        data class Start(
            val deviceId: String,
            val workoutDate: DateString?,
            val workoutSessionId: Long?,
            val verdict: CompletableDeferred<String>,
        ) : Event

        data class Resume(val verdict: CompletableDeferred<HrSessionEntity?>) : Event

        data class Anchor(
            val workoutDate: DateString,
            val workoutSessionId: Long?,
            val verdict: CompletableDeferred<Boolean>,
        ) : Event

        /**
         * The flush runs **inside** the actor, which is what makes the whole stop
         * — verdict, deferred close, and releasing the session — one indivisible
         * step.
         */
        data class Stop(
            val finalFlush: suspend () -> Boolean,
            val verdict: CompletableDeferred<CaptureStopResult>,
        ) : Event

        data class Abandon(val sessionId: String, val done: CompletableDeferred<Unit>) : Event

        /** A batch of samples landed; any close waiting on durable data can finish. */
        data object Drained : Event

        /** Answers as soon as everything queued ahead of it has been handled. */
        data class Barrier(val done: CompletableDeferred<Unit>) : Event
    }

    private val events = Channel<Event>(EVENT_BUFFER)

    /**
     * The session this store believes is recording. **Actor-owned**: read and
     * written only inside the consumer coroutine, which is why no transition here
     * needs a lock or a compare-and-set.
     */
    private var owned: CaptureSession? = null

    /**
     * Deferred closes by session id. **Actor-owned**, like [owned].
     *
     * A map rather than a single slot, because a single slot silently loses one.
     * Two straps are enough: capture A strands its close, capture B on a different
     * device starts — the stale-session backstop is per-device, so it does not
     * touch A — and B then strands its own close, overwriting A's. A would stay
     * open for ever, which is the exact failure the deferred close was built to
     * prevent.
     *
     * Bounded by construction: an entry is added only by a failed final flush and
     * removed on completion, on a resume reattaching to that session, or by the
     * backstop force-closing it.
     */
    private val pendingCloses = mutableMapOf<String, PendingClose>()

    private val _current = MutableStateFlow<CaptureSession?>(null)

    /**
     * The capture in progress — its id **and its workout anchor** — or null when
     * nothing is capturing. Published by the actor, so it is always a value the
     * actor has already committed to.
     *
     * The anchor is published from here because this side is the one that can
     * still answer after a process death: it comes off the session row, which
     * outlives every ViewModel that might otherwise have remembered it. The
     * capture service mirrors this into
     * [dev.jtiisto.wellness.core.ble.capture.HrCaptureState], and the screens
     * read it there.
     */
    val current: StateFlow<CaptureSession?> = _current.asStateFlow()

    /**
     * The live capture session id, or null when nothing is capturing.
     *
     * This is what `SetEventRecorder.captureSessionId` reads: a set ticked while
     * a strap is recording carries the session, and one ticked without a strap
     * carries nothing. A plain non-suspending read of what the actor last
     * published — the recorder asks inside a write transaction and cannot suspend.
     */
    val currentSessionId: String? get() = _current.value?.sessionId

    init {
        scope.launch {
            // Single consumer, for the life of the process. There is no shutdown:
            // a deferred close has to outlive the capture service that deferred
            // it, and the scope that owns this outlives every one of them.
            for (event in events) handle(event)
        }
    }

    /**
     * Open a session and make it current.
     *
     * The row is written before capture starts rather than after it ends, so a
     * process death mid-session leaves something [resumeOpenSession] can find.
     * [workoutDate] and [workoutSessionId] are set when capture was started from
     * the Start Workout sheet and absent when it was started from the strap
     * settings; the session is meaningful either way.
     *
     * Any session for [deviceId] that nobody closed is force-closed first — see
     * [closeStaleSessions]. One strap feeds one capture, so a second open row for
     * it is always wreckage.
     *
     * @return the new session id, already published as [currentSessionId]
     */
    suspend fun startSession(
        deviceId: String,
        workoutDate: DateString? = null,
        workoutSessionId: Long? = null,
    ): String = ask { Event.Start(deviceId, workoutDate, workoutSessionId, it) }

    /**
     * Reattach to the newest session nobody closed, and make it current.
     *
     * This is the `START_STICKY` path: the service was killed, the system
     * restarted it with a null intent, and the open row is the only record of
     * what was being captured — including which strap, which is why the entity
     * is returned rather than just the id.
     *
     * Newest rather than only: a crash between finishing one session and closing
     * it can leave two open, and the recent one is the one the strap is feeding.
     *
     * The **workout anchor comes back with it**, off the row. That is the whole
     * reason the anchor is stored rather than held by whichever screen started
     * the capture: this is the one place that still knows, once the process that
     * remembered it is gone.
     *
     * **It never displaces a capture that is already running.** Ownership is a
     * variable in the actor, checked in the same step that would publish, so a
     * start and a resume cannot both believe they own the strap — whichever event
     * the actor takes first wins, and the other is told there was nothing to do.
     *
     * @return the resumed session, or null when there was nothing open **or a
     *   capture already owns the store**. The caller must treat the two alike: in
     *   both cases this restart has no capture to run.
     */
    suspend fun resumeOpenSession(): HrSessionEntity? = ask { Event.Resume(it) }

    /**
     * Give up a session that was published but never recorded into.
     *
     * The resume path publishes before it knows whether a capture will actually
     * start, and the start can still be refused — a permission revoked while the
     * process was dead, a server that has not resolved — or fail outright. The
     * published session would then stay current with nothing feeding it: set
     * events would carry a dead session id, and the next sticky restart would
     * reattach to a capture that ended during the failed resume.
     *
     * Matched on the id, so a session someone else has since started can never be
     * cleared by a late abandon. No database write: this is a failure path, and
     * the row is retired by [closeStaleSessions] on the next capture for that
     * device.
     */
    suspend fun abandonSession(sessionId: String) {
        ask<Unit> { Event.Abandon(sessionId, it) }
    }

    /**
     * Attach the running session to a workout after the fact.
     *
     * The protocol lists three moments a session is re-upserted — start, anchor
     * change, and close — and this is the middle one. It exists for the cases the
     * Start Workout sheet cannot cover: capture already running when a workout
     * begins, and a start intent that arrives while another capture is still
     * getting going.
     *
     * One `UPDATE` touching only the two anchor columns, and guarded on the
     * session still being open, so it can never carry a stale null `endedAtMs`
     * back over a close and reopen a finished session.
     *
     * @return false when nothing is capturing, or when the session is no longer
     *   open. **That second case is a normal outcome, not an error**: End Workout
     *   racing its own capture's close is exactly what it looks like, and the
     *   session ended correctly as itself.
     */
    suspend fun anchorToWorkout(workoutDate: DateString, workoutSessionId: Long? = null): Boolean =
        ask { Event.Anchor(workoutDate, workoutSessionId, it) }

    /**
     * End the capture: last buffer out, then close the row — **in that order,
     * and only in that order**.
     *
     * A session's `endedAtMs` is the claim that everything it covers is stored.
     * Setting it while rows are still stuck in memory would make that claim
     * false, and the analysis reading the session would silently be reading a
     * truncated recording. So a final flush that will not land leaves the session
     * **open** instead ([CaptureStopResult.LEFT_OPEN]) and the close is *deferred*
     * rather than abandoned: the next landed batch finishes it, carrying the
     * instant capture stopped rather than the instant the flush eventually landed.
     *
     * Registering that deferred close and releasing the session happen in the same
     * indivisible step, which is what stops a drain from seeing a session that is
     * both recording and waiting to be closed.
     *
     * The session is released whatever happens — including when the closing write
     * throws, and including when **the caller is cancelled while waiting**. The
     * work runs in the actor, not in the caller, so a service being destroyed
     * mid-stop cannot leave this store pointing at a session that has ended.
     *
     * @param finalFlush the buffer's flush, returning whether it fully persisted
     */
    suspend fun stopSession(finalFlush: suspend () -> Boolean): CaptureStopResult =
        ask { Event.Stop(finalFlush, it) }

    /**
     * Persist one buffer's worth of RR intervals.
     *
     * All-or-nothing as [HrSampleSink] requires: the insert is a single
     * statement, and anything that stops it — a closed gate most of all — throws
     * rather than returning, so the buffer keeps the batch and retries. Returning
     * normally after a partial write would silently lose beats.
     *
     * `INSERT OR IGNORE` on `(deviceId, timestampMs, seq)` makes a batch flushed
     * twice across a service restart free; the ignored count is logged because a
     * non-zero one is the fingerprint of exactly that, and worth being able to
     * see afterwards.
     *
     * Samples do not go through the actor — they are the high-frequency path and
     * they touch no lifecycle state. All this raises is a *request* to drain the
     * deferred closes, and that request is deliberately not awaited: this method
     * is itself called from inside the actor's stop handling, so waiting on the
     * actor here would be waiting on the caller.
     *
     * **A deferred close therefore completes shortly after this returns, not
     * within it.** Nothing in production depends on the difference — the buffer
     * has no interest in the close, and the actor takes the request as its next
     * event — but a test that flushes and immediately reads `endedAtMs` has to
     * let the actor run first.
     */
    override suspend fun store(samples: List<BufferedSample>) {
        if (samples.isEmpty()) return
        val ignored = session.withWriteLease {
            sampleDao.insertAll(samples.map(BufferedSample::toEntity)).count { it == IGNORED_ROW_ID }
        }
        if (ignored > 0) {
            debugLog?.log(
                TAG,
                "duplicate samples ignored",
                buildJsonObject {
                    put("rows", ignored)
                    put("ofBatch", samples.size)
                },
            )
        }
        scheduleUpload()
        // A full buffer means a drain is already queued, and draining is
        // idempotent — so dropping this one costs nothing.
        events.trySend(Event.Drained)
    }

    /**
     * Suspend until the actor has handled everything queued before this call.
     *
     * An ordinary event carrying no work, so the channel's order is the whole
     * mechanism: it cannot be answered until every event ahead of it has been.
     *
     * It exists because [store] raises its drain request **without waiting** — it
     * has to, or a stop's own final flush would be waiting on the actor that is
     * running it. Anything that needs the deferred close to have actually landed
     * asks for it here instead. Tests are the caller today; a switch wanting the
     * capture side quiesced before a wipe is the obvious second one.
     */
    suspend fun awaitQuiescence() {
        ask<Unit> { Event.Barrier(it) }
    }

    // ---- the actor -------------------------------------------------------

    /** Send an event and wait for its answer. */
    private suspend fun <T> ask(event: (CompletableDeferred<T>) -> Event): T {
        val verdict = CompletableDeferred<T>()
        events.send(event(verdict))
        return verdict.await()
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Start -> event.verdict.fulfil { onStart(event) }
            is Event.Resume -> event.verdict.fulfil { onResume() }
            is Event.Anchor -> event.verdict.fulfil { onAnchor(event) }
            is Event.Stop -> event.verdict.fulfil { onStop(event) }
            is Event.Abandon -> event.done.fulfil { onAbandon(event.sessionId) }
            is Event.Barrier -> event.done.fulfil { }
            Event.Drained -> onDrained()
        }
    }

    /**
     * Answer one event, and let a failure be *that event's* failure.
     *
     * The caller sees the exception its own call produced, and the actor goes on
     * to the next event — a closed gate refusing one write must not take the
     * lifecycle of every later capture with it. Cancellation is the exception:
     * that is the scope going away, and it belongs to the actor.
     */
    private suspend fun <T> CompletableDeferred<T>.fulfil(block: suspend () -> T) {
        try {
            complete(block())
        } catch (cancellation: CancellationException) {
            completeExceptionally(cancellation)
            throw cancellation
        } catch (error: Throwable) {
            completeExceptionally(error)
        }
    }

    private suspend fun onStart(event: Event.Start): String {
        closeStaleSessions(event.deviceId)
        val id = newSessionId()
        session.withWriteLease {
            sessionDao.upsert(
                HrSessionEntity(
                    sessionId = id,
                    deviceId = event.deviceId,
                    startedAtMs = now(),
                    workoutDate = event.workoutDate,
                    workoutSessionId = event.workoutSessionId,
                ),
            )
        }
        publish(CaptureSession(id, event.workoutDate, event.workoutSessionId))
        debugLog?.log(
            TAG,
            "capture session started",
            buildJsonObject {
                put("anchored", event.workoutDate != null)
                put("hookSession", event.workoutSessionId != null)
            },
        )
        scheduleUpload()
        return id
    }

    private suspend fun onResume(): HrSessionEntity? {
        val open = sessionDao.newestOpen() ?: return null
        if (owned != null) {
            debugLog?.log(TAG, "resume skipped: a capture is already running")
            return null
        }
        publish(CaptureSession(open.sessionId, open.workoutDate, open.workoutSessionId))
        // A session being captured into again must not be closed behind the
        // capture's back by a deferred close it left behind earlier. The resume
        // *is* the reattachment the deferred close was standing in for, and only
        // one of them may finish the session.
        pendingCloses.remove(open.sessionId)
        debugLog?.log(
            TAG,
            "resumed open capture session",
            buildJsonObject { put("anchored", open.workoutDate != null) },
        )
        return open
    }

    private suspend fun onAnchor(event: Event.Anchor): Boolean {
        val id = owned?.sessionId ?: return false
        val rows = session.withWriteLease {
            sessionDao.anchorWorkout(id, event.workoutDate, event.workoutSessionId)
        }
        if (rows == 0) {
            debugLog?.log(TAG, "workout anchor skipped: the session was no longer open")
            return false
        }
        publish(CaptureSession(id, event.workoutDate, event.workoutSessionId))
        scheduleUpload()
        return true
    }

    private suspend fun onStop(event: Event.Stop): CaptureStopResult {
        val id = owned?.sessionId ?: return CaptureStopResult.NOT_RUNNING
        // Read before the retries, which can take seconds: this is when capture
        // ended, and it is what the row will carry however late the close lands.
        val endedAtMs = now()
        try {
            var flushed = event.finalFlush()
            var attempts = 1
            while (!flushed && attempts < FINAL_FLUSH_ATTEMPTS) {
                delay(FINAL_FLUSH_RETRY_MS)
                flushed = event.finalFlush()
                attempts++
            }
            if (!flushed) {
                pendingCloses[id] = PendingClose(id, endedAtMs)
                debugLog?.log(
                    TAG,
                    "final flush failed — close deferred until the buffer drains",
                    buildJsonObject { put("attempts", attempts) },
                )
                return CaptureStopResult.LEFT_OPEN
            }
            return closeSessionRow(id, endedAtMs)
        } finally {
            // In the actor, so a cancelled *caller* cannot skip it. Guarded on the
            // id for the same reason a compare-and-set was: nothing else can have
            // taken ownership mid-event, but saying so costs one comparison.
            if (owned?.sessionId == id) publish(null)
        }
    }

    private fun onAbandon(sessionId: String): Unit {
        if (owned?.sessionId != sessionId) return
        publish(null)
        debugLog?.log(TAG, "abandoned a session no capture started for")
    }

    /**
     * Finish the closes that were waiting for their data to become durable.
     *
     * Drains **every** pending close, not one: two straps can each strand one, and
     * the buffer they share drains for both at once. A close whose session is
     * recording again has been superseded rather than delayed — the capture that
     * owns it now will close it — so it is dropped.
     *
     * A failure leaves the entry in the map for the next landed batch, and never
     * propagates: this runs on the actor, and the sink's promise about the samples
     * was already kept before the request was raised.
     */
    private suspend fun onDrained() {
        if (pendingCloses.isEmpty()) return
        val live = owned?.sessionId
        for (pending in pendingCloses.values.toList()) {
            if (pending.sessionId == live) {
                pendingCloses.remove(pending.sessionId)
                debugLog?.log(TAG, "deferred close dropped: its session is recording again")
                continue
            }
            try {
                val result = closeSessionRow(pending.sessionId, pending.endedAtMs)
                pendingCloses.remove(pending.sessionId)
                debugLog?.log(
                    TAG,
                    "deferred close completed once the buffer drained",
                    buildJsonObject { put("result", result.name) },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                debugLog?.log(
                    TAG,
                    "deferred close failed, still pending",
                    buildJsonObject { put("error", error.javaClass.simpleName) },
                )
            }
        }
    }

    private fun publish(value: CaptureSession?) {
        owned = value
        _current.value = value
    }

    /**
     * Force-close every still-open session for [deviceId] before a new one opens.
     *
     * The backstop under the deferred close. That path finishes a session once its
     * rows become durable, but it cannot cover a process that died before the
     * retry ever ran, or a buffer that never drained — and a permanently open row
     * makes [resumeOpenSession] reattach to a capture that ended days ago.
     *
     * **The boundary written here is advisory and knowingly late**: `now()` is
     * when the *next* capture started, which overstates the old session's span by
     * however long the device sat idle. That is acceptable because nothing reads
     * `endedAtMs` as a measurement — the analysis derives a recording's extent
     * from its sample timestamps, and this column exists to say "not still
     * running". Deriving a true boundary would mean aggregating the sample table
     * on every capture start, which is a real cost for a field nothing measures.
     *
     * Scoped to one device on purpose: another strap's open session is not this
     * capture's to end — and neither is its deferred close.
     */
    private suspend fun closeStaleSessions(deviceId: String) {
        val stale = sessionDao.listAll().filter { it.endedAtMs == null && it.deviceId == deviceId }
        if (stale.isEmpty()) return
        val closedAt = now()
        session.withWriteLease {
            for (row in stale) sessionDao.closeSession(row.sessionId, closedAt)
        }
        for (row in stale) pendingCloses.remove(row.sessionId)
        debugLog?.log(
            TAG,
            "force-closed sessions left open by an earlier capture",
            buildJsonObject { put("rows", stale.size) },
        )
        scheduleUpload()
    }

    /**
     * Write `endedAtMs` on a still-open session, and classify what happened.
     *
     * `closeSession` is a single guarded `UPDATE`, so it touches nothing but the
     * one column — a close cannot erase an anchor that landed while it was being
     * decided. The guard also makes it idempotent: a second close matches nothing
     * rather than moving the boundary later and claiming coverage of samples that
     * were never stored.
     *
     * **Zero rows means "not open", which is two different things**, and only a
     * read tells them apart. The distinction is worth exactly one log line: both
     * mean the capture is over and nothing here has to finish it, so both are
     * reported to the caller as a session that no longer needs closing.
     */
    private suspend fun closeSessionRow(id: String, endedAtMs: Long): CaptureStopResult {
        val rows = session.withWriteLease { sessionDao.closeSession(id, endedAtMs) }
        if (rows > 0) {
            debugLog?.log(TAG, "capture session closed")
            scheduleUpload()
            return CaptureStopResult.CLOSED
        }
        return if (sessionDao.find(id) == null) {
            debugLog?.log(TAG, "session row gone before it could be closed")
            CaptureStopResult.SESSION_GONE
        } else {
            debugLog?.log(TAG, "session was already closed")
            CaptureStopResult.CLOSED
        }
    }

    private companion object {
        /** What `INSERT OR IGNORE` reports for a row the key already held. */
        const val IGNORED_ROW_ID = -1L
    }
}

/**
 * The sink's type maps onto the entity one-for-one — that is the point of it
 * being a separate type at all. The three sync columns are not the buffer's to
 * know: a fresh sample is unsynced, unstamped and unquarantined by definition.
 */
private fun BufferedSample.toEntity(): HrSampleEntity = HrSampleEntity(
    deviceId = deviceId,
    timestampMs = timestampMs,
    seq = seq,
    heartRateBpm = heartRateBpm,
    rrIntervalMs = rrIntervalMs,
    isGapBefore = isGapBefore,
    sessionId = sessionId,
)
