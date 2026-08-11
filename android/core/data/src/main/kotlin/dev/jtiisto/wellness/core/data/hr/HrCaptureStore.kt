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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
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

/** How [stopSession] ended. Each outcome means something different downstream. */
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
 * **Every write goes through [session].** These rows are server-scoped: they are
 * destined for one specific `hr` module, and a server switch wipes them. The
 * lease is what makes "the switch waits for writes already in flight" true for
 * a capture that is running while the user changes servers. A write refused by a
 * closed gate throws [dev.jtiisto.wellness.core.data.sync.ServerSessionClosedException]
 * out of [store], which the sample buffer treats as a failed flush and keeps its
 * batch — a failure, deliberately, rather than a silent drop.
 *
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
    private val scheduleUpload: () -> Unit = {},
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val debugLog: DebugLog? = null,
) : HrSampleSink {

    private val _current = MutableStateFlow<CaptureSession?>(null)

    /**
     * A session whose final flush would not land, and the instant it must carry
     * once it does.
     *
     * The spec's rule is that `endedAtMs` may only be written when everything the
     * session covers is durable, so a failed final flush leaves the row open.
     * Nothing used to end that sentence: the sample buffer's own app-lived retry
     * would eventually persist the rows and the row would stay open for ever.
     * This is the other half — the close, held until the data it claims to cover
     * exists. [endedAtMs] is the moment capture *stopped*, not the moment the
     * flush finally succeeded, because that is the instant the session actually
     * ended.
     */
    private data class PendingClose(val sessionId: String, val endedAtMs: Long)

    private val _pendingClose = MutableStateFlow<PendingClose?>(null)

    /**
     * The capture in progress — its id **and its workout anchor** — or null when
     * nothing is capturing.
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
     * carries nothing. Deliberately a plain read of the current value — the
     * recorder asks inside a write transaction and cannot suspend.
     */
    val currentSessionId: String? get() = _current.value?.sessionId

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
    ): String {
        closeStaleSessions(deviceId)
        val id = newSessionId()
        session.withWriteLease {
            sessionDao.upsert(
                HrSessionEntity(
                    sessionId = id,
                    deviceId = deviceId,
                    startedAtMs = now(),
                    workoutDate = workoutDate,
                    workoutSessionId = workoutSessionId,
                ),
            )
        }
        _current.value = CaptureSession(
            sessionId = id,
            workoutDate = workoutDate,
            workoutSessionId = workoutSessionId,
        )
        debugLog?.log(
            TAG,
            "capture session started",
            buildJsonObject {
                put("anchored", workoutDate != null)
                put("hookSession", workoutSessionId != null)
            },
        )
        scheduleUpload()
        return id
    }

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
     * **It never displaces a capture that is already running.** The row query is
     * a suspension point, and a real start intent can be served across it — so a
     * resume that published unconditionally would overwrite the live session with
     * the stale one it read before. The samples would then be recorded under the
     * new session while this store, and every stop that reads it, believed the
     * old one was running: the old session would be closed on the new one's
     * behalf and the new one would never be closed at all. The publish is
     * therefore a compare-and-set against "nothing is capturing", which is the
     * only state a resume is allowed to act on.
     *
     * @return the resumed session, or null when there was nothing open **or a
     *   capture claimed the store while this was reading**. The caller must treat
     *   the two alike: in both cases this restart has no capture to run.
     */
    suspend fun resumeOpenSession(): HrSessionEntity? {
        val open = sessionDao.newestOpen() ?: return null
        val resumed = CaptureSession(
            sessionId = open.sessionId,
            workoutDate = open.workoutDate,
            workoutSessionId = open.workoutSessionId,
        )
        if (!_current.compareAndSet(null, resumed)) {
            debugLog?.log(TAG, "resume skipped: a capture is already running")
            return null
        }
        // A session being captured into again must not be closed behind the
        // capture's back by a deferred close it left behind earlier. The resume
        // *is* the reattachment the deferred close was standing in for, and only
        // one of them may finish the session.
        _pendingClose.update { pending -> pending?.takeIf { it.sessionId != open.sessionId } }
        debugLog?.log(
            TAG,
            "resumed open capture session",
            buildJsonObject { put("anchored", open.workoutDate != null) },
        )
        return open
    }

    /**
     * Force-close every still-open session for [deviceId] before a new one opens.
     *
     * The backstop under [stopSession]'s deferred close. That path finishes a
     * session once its rows become durable, but it cannot cover a process that
     * died before the retry ever ran, or a buffer that never drained — and a
     * permanently open row makes [resumeOpenSession] reattach to a capture that
     * ended days ago.
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
     * capture's to end.
     */
    private suspend fun closeStaleSessions(deviceId: String) {
        val stale = sessionDao.listAll().filter { it.endedAtMs == null && it.deviceId == deviceId }
        if (stale.isEmpty()) return
        val closedAt = now()
        session.withWriteLease {
            for (row in stale) sessionDao.closeSession(row.sessionId, closedAt)
        }
        // Anything a deferred close was waiting to finish is finished now.
        _pendingClose.update { pending -> pending?.takeIf { p -> stale.none { it.sessionId == p.sessionId } } }
        debugLog?.log(
            TAG,
            "force-closed sessions left open by an earlier capture",
            buildJsonObject { put("rows", stale.size) },
        )
        scheduleUpload()
    }

    /**
     * Attach the running session to a workout after the fact.
     *
     * The protocol lists three moments a session is re-upserted — start, anchor
     * change, and close — and this is the middle one. It exists for the case the
     * Start Workout sheet cannot cover: capture already running when a workout
     * begins, where the session would otherwise never learn which workout it
     * belongs to.
     *
     * One `UPDATE` touching only the two anchor columns, and guarded on the
     * session still being open. A read-modify-write here could carry a stale null
     * `endedAtMs` back over a close that committed in between and silently
     * reopen a finished session — the write lease does not prevent that, because
     * it fences against a server switch rather than against other writers.
     *
     * @return false when nothing is capturing, or when the session is no longer
     *   open. **That second case is a normal outcome, not an error**: End Workout
     *   racing its own capture's close is exactly what it looks like, and the
     *   session ended correctly as itself.
     */
    suspend fun anchorToWorkout(workoutDate: DateString, workoutSessionId: Long? = null): Boolean {
        val id = _current.value?.sessionId ?: return false
        val rows = session.withWriteLease { sessionDao.anchorWorkout(id, workoutDate, workoutSessionId) }
        if (rows == 0) {
            debugLog?.log(TAG, "workout anchor skipped: the session was no longer open")
            return false
        }
        // Guarded on the id: a capture that stopped, or a second one that
        // started, while the write was in flight must not inherit this anchor.
        _current.update { live ->
            if (live?.sessionId == id) {
                live.copy(workoutDate = workoutDate, workoutSessionId = workoutSessionId)
            } else {
                live
            }
        }
        scheduleUpload()
        return true
    }

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
        completeDeferredClose()
    }

    /**
     * Finish a close [stopSession] had to defer, now that a flush has landed.
     *
     * Called after **any** successful [store], which is stronger than it looks
     * rather than laxer: the sample buffer is one shared list flushed
     * all-or-nothing, so a batch landing proves the buffer was emptied — and that
     * includes every row the deferred session left in it. Matching the batch's
     * session ids would be no safer and would miss the ordinary case where a
     * stopped session's leftovers go out in the same batch as a newer capture's.
     *
     * A failure here must not read as a failed flush. The samples are durable and
     * that is the whole of what [HrSampleSink] promises the buffer; the close is
     * put back and the next landed batch tries it again.
     */
    private suspend fun completeDeferredClose() {
        val pending = _pendingClose.getAndUpdate { null } ?: return
        try {
            val result = closeSessionRow(pending.sessionId, pending.endedAtMs)
            debugLog?.log(
                TAG,
                "deferred close completed once the buffer drained",
                buildJsonObject { put("result", result.name) },
            )
        } catch (cancellation: CancellationException) {
            _pendingClose.compareAndSet(null, pending)
            throw cancellation
        } catch (error: Throwable) {
            // compareAndSet, not a plain write: a newer deferred close set while
            // this one was in flight is the one that should survive.
            _pendingClose.compareAndSet(null, pending)
            debugLog?.log(
                TAG,
                "deferred close failed, still pending",
                buildJsonObject { put("error", error.javaClass.simpleName) },
            )
        }
    }

    /**
     * End the capture: last buffer out, then close the row — **in that order,
     * and only in that order**.
     *
     * A session's `endedAtMs` is the claim that everything it covers is stored.
     * Setting it while rows are still stuck in memory would make that claim
     * false, and the analysis reading the session would silently be reading a
     * truncated recording. So a final flush that will not land leaves the session
     * **open** instead ([CaptureStopResult.LEFT_OPEN]) and the close is *deferred*
     * rather than abandoned: [completeDeferredClose] finishes it the moment the
     * buffer's own app-lived retry gets those rows down, carrying the instant
     * capture stopped rather than the instant the flush eventually landed.
     *
     * The current session id is cleared either way, including when the write
     * throws: capture is over from this device's point of view whatever happened
     * to the row, and set events ticked afterwards must not claim a session that
     * is no longer recording.
     *
     * @param finalFlush the buffer's flush, returning whether it fully persisted
     */
    suspend fun stopSession(finalFlush: suspend () -> Boolean): CaptureStopResult {
        val id = _current.value?.sessionId ?: return CaptureStopResult.NOT_RUNNING
        // Read before the retries, which can take seconds: this is when capture
        // ended, and it is what the row will carry however late the close lands.
        val endedAtMs = now()
        try {
            var flushed = finalFlush()
            var attempts = 1
            while (!flushed && attempts < FINAL_FLUSH_ATTEMPTS) {
                delay(FINAL_FLUSH_RETRY_MS)
                flushed = finalFlush()
                attempts++
            }
            if (!flushed) {
                _pendingClose.value = PendingClose(id, endedAtMs)
                debugLog?.log(
                    TAG,
                    "final flush failed — close deferred until the buffer drains",
                    buildJsonObject { put("attempts", attempts) },
                )
                return CaptureStopResult.LEFT_OPEN
            }
            return closeSessionRow(id, endedAtMs)
        } finally {
            _current.value = null
        }
    }

    /**
     * Write `endedAtMs` on a still-open session, and classify what happened.
     *
     * `closeSession` is a single guarded `UPDATE`, so it touches nothing but the
     * one column — a close can no longer erase an anchor that landed while it was
     * being decided. The guard also makes it idempotent: a second close matches
     * nothing rather than moving the boundary later and claiming coverage of
     * samples that were never stored.
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
