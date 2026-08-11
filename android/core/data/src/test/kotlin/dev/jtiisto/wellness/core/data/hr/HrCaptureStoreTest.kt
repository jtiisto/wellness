package dev.jtiisto.wellness.core.data.hr

import dev.jtiisto.wellness.core.ble.buffer.BufferedSample
import dev.jtiisto.wellness.core.ble.capture.CaptureSession
import dev.jtiisto.wellness.core.data.db.FakeHrSampleDao
import dev.jtiisto.wellness.core.data.db.FakeHrSessionDao
import dev.jtiisto.wellness.core.data.db.HrSampleEntity
import dev.jtiisto.wellness.core.data.db.HrSessionEntity
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.ServerSessionClosedException
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

private const val NOW = 1_800_000_000_000L
private const val DEVICE = "AA:BB:CC:DD:EE:FF"
private const val OTHER_DEVICE = "11:22:33:44:55:66"

/**
 * A session DAO that lets a test drop another store call into the middle of a
 * write, which is the only way to reach the interleavings the two guarded
 * statements exist for.
 *
 * Each hook fires once and clears itself, so a hook that re-enters the DAO does
 * not recurse.
 */
private class HookedHrSessionDao : FakeHrSessionDao() {

    /** Runs *before* the next anchor statement evaluates its `endedAtMs IS NULL` guard. */
    var beforeAnchor: (suspend () -> Unit)? = null

    /** Runs *after* the next anchor lands, while the caller is still mid-call. */
    var afterAnchor: (suspend () -> Unit)? = null

    /** Runs *before* the next close statement — where a read-modify-write would have read. */
    var beforeClose: (suspend () -> Unit)? = null

    /**
     * Runs *after* the next open-session query has read its row but before the
     * caller sees it — so the caller comes back holding a genuinely stale answer,
     * which is the sticky-resume race.
     */
    var afterNewestOpen: (suspend () -> Unit)? = null

    override suspend fun newestOpen(): HrSessionEntity? {
        val row = super.newestOpen()
        afterNewestOpen?.let { afterNewestOpen = null; it() }
        return row
    }

    override suspend fun anchorWorkout(
        sessionId: String,
        workoutDate: DateString,
        workoutSessionId: Long?,
    ): Int {
        beforeAnchor?.let { beforeAnchor = null; it() }
        val rows = super.anchorWorkout(sessionId, workoutDate, workoutSessionId)
        afterAnchor?.let { afterAnchor = null; it() }
        return rows
    }

    override suspend fun closeSession(sessionId: String, endedAtMs: Long): Int {
        beforeClose?.let { beforeClose = null; it() }
        return super.closeSession(sessionId, endedAtMs)
    }
}

/**
 * One store, its two DAO fakes and a real [ServerSessionGate].
 *
 * The gate is the real one deliberately: "a refused write is a failure, not a
 * silent drop" is the property most worth pinning here, and a fake gate would
 * only assert that the fake refuses.
 */
private class World(val sessionDao: FakeHrSessionDao = FakeHrSessionDao()) {
    val sampleDao = FakeHrSampleDao()
    val gate = ServerSessionGate()

    /** Every armed upload debounce, so "only on success" is checkable. */
    var scheduled = 0

    var clock = NOW
    private var minted = 0

    val store = HrCaptureStore(
        sessionDao = sessionDao,
        sampleDao = sampleDao,
        session = gate,
        scheduleUpload = { scheduled++ },
        newSessionId = { "session-${++minted}" },
        now = { clock },
    )

    fun session(id: String): HrSessionEntity = sessionDao.sessions.getValue(id)

    fun sample(
        timestampMs: Long = NOW,
        seq: Int = 0,
        sessionId: String = "session-1",
    ) = BufferedSample(
        deviceId = DEVICE,
        timestampMs = timestampMs,
        seq = seq,
        heartRateBpm = 142,
        rrIntervalMs = 428,
        isGapBefore = false,
        sessionId = sessionId,
    )
}

/**
 * The capture session's life in Room, and the landing point for every RR
 * interval.
 *
 * Two properties carry most of this file. The first is that `endedAtMs` is a
 * *claim* — everything the session covers is stored — so a final flush that will
 * not land must leave the session open rather than finalize a truncated
 * recording. The second is that a write refused by a closed gate throws: the
 * sample buffer treats that as a failed flush and keeps its batch, and anything
 * quieter would lose beats to a server switch.
 */
class HrCaptureStoreTest {

    // ---- starting and resuming -------------------------------------------

    @Test
    @DisplayName("starting writes an open session and makes it current")
    fun startWritesTheRow() = runTest {
        val world = World()

        val id = world.store.startSession(DEVICE, workoutDate = "2026-08-10", workoutSessionId = 42L)

        assertEquals("session-1", id)
        val row = world.session(id)
        assertEquals(DEVICE, row.deviceId)
        assertEquals(NOW, row.startedAtMs)
        assertNull(row.endedAtMs)
        assertEquals("2026-08-10", row.workoutDate)
        assertEquals(42L, row.workoutSessionId)
        assertEquals(id, world.store.currentSessionId)
        // The anchor is published beside the id, so a screen that did not start
        // this capture — or one recreated after a process death — can still tell
        // which workout it belongs to.
        assertEquals(CaptureSession(id, workoutDate = "2026-08-10", workoutSessionId = 42L), world.store.current.value)
        assertEquals(1, world.scheduled)
    }

    @Test
    @DisplayName("a new session is pending upload at generation 1")
    fun startIsPendingUpload() = runTest {
        val world = World()

        val id = world.store.startSession(DEVICE)

        val row = world.session(id)
        assertFalse(row.isSynced)
        assertFalse(row.isQuarantined)
        assertEquals(1L, row.dirtyGeneration)
        assertEquals(listOf(row), world.sessionDao.needsUpload())
    }

    @Test
    @DisplayName("capture started outside a workout records no anchor")
    fun startWithoutAWorkout() = runTest {
        val world = World()

        val id = world.store.startSession(DEVICE)

        assertNull(world.session(id).workoutDate)
        assertNull(world.session(id).workoutSessionId)
        // Published as absent too, not as some placeholder: a capture from the
        // strap settings belongs to no workout, and End Workout must not
        // recognise it as one it started.
        assertEquals(CaptureSession(id), world.store.current.value)
    }

    @Test
    @DisplayName("a restart resumes the newest session nobody closed")
    fun resumePicksTheNewestOpenSession() = runTest {
        val world = World()
        world.sessionDao.upsert(HrSessionEntity("older", DEVICE, startedAtMs = NOW - 20_000))
        world.sessionDao.upsert(HrSessionEntity("newer", DEVICE, startedAtMs = NOW - 10_000))
        world.sessionDao.upsert(
            HrSessionEntity("closed", DEVICE, startedAtMs = NOW, endedAtMs = NOW + 1),
        )

        val resumed = world.store.resumeOpenSession()

        // Newest rather than only: a crash between finishing one session and
        // closing it can leave two open.
        assertEquals("newer", resumed?.sessionId)
        assertEquals(DEVICE, resumed?.deviceId)
        assertEquals("newer", world.store.currentSessionId)
    }

    @Test
    @DisplayName("a resumed session brings its workout anchor back off the row")
    fun resumeRepublishesTheAnchor() = runTest {
        val world = World()
        world.sessionDao.upsert(
            HrSessionEntity(
                sessionId = "open",
                deviceId = DEVICE,
                startedAtMs = NOW - 10_000,
                workoutDate = "2026-08-10",
                workoutSessionId = 7L,
            ),
        )

        world.store.resumeOpenSession()

        // This is the whole point of persisting the anchor. The ViewModel that
        // started the capture died with the process; the row did not, so End
        // Workout can still recognise the capture as its own.
        assertEquals(
            CaptureSession("open", workoutDate = "2026-08-10", workoutSessionId = 7L),
            world.store.current.value,
        )
    }

    @Test
    @DisplayName("a resumed unanchored session comes back unanchored")
    fun resumeWithoutAnAnchor() = runTest {
        val world = World()
        world.sessionDao.upsert(HrSessionEntity("open", DEVICE, startedAtMs = NOW - 10_000))

        world.store.resumeOpenSession()

        assertEquals(CaptureSession("open"), world.store.current.value)
    }

    @Test
    @DisplayName("a resume holding a stale row refuses to displace the capture that started under it")
    fun resumeNeverDisplacesARunningCapture() = runTest {
        val dao = HookedHrSessionDao()
        val world = World(dao)
        // Left open by an earlier capture. The sticky query reads exactly this
        // row and then, before the resume can act on it, a real start intent is
        // served — on another strap, so the stale-session backstop does not
        // retire it and the resume really is holding a live-looking stale row.
        world.sessionDao.upsert(HrSessionEntity("stale", DEVICE, startedAtMs = NOW - 100_000))
        dao.afterNewestOpen = { world.store.startSession(OTHER_DEVICE) }

        val resumed = world.store.resumeOpenSession()

        assertNull(resumed)
        // The published session is the live one, not the stale one the query
        // returned. Publishing "stale" here would record samples under the new
        // session while every stop read the old — closing the wrong one and
        // leaving the right one open for ever.
        assertEquals("session-1", world.store.currentSessionId)
        assertEquals(CaptureSession("session-1"), world.store.current.value)
        assertNull(world.session("stale").endedAtMs)
    }

    @Test
    @DisplayName("a session published by a resume can be handed back when no capture follows")
    fun abandonReleasesAResumedSession() = runTest {
        val world = World()
        world.sessionDao.upsert(HrSessionEntity("open", DEVICE, startedAtMs = NOW - 10_000))
        assertEquals("open", world.store.resumeOpenSession()?.sessionId)

        // The service's start was refused — a permission revoked while the
        // process was dead, or an unresolved server.
        world.store.abandonSession("open")

        assertNull(world.store.currentSessionId)
        // No write: the row stays open for the backstop to retire, because a
        // refusal is the wrong moment to be closing anything.
        assertNull(world.session("open").endedAtMs)
    }

    @Test
    @DisplayName("abandoning a session someone else has since started is a no-op")
    fun abandonCannotClobberANewerCapture() = runTest {
        val world = World()
        val live = world.store.startSession(DEVICE)

        world.store.abandonSession("some-older-session")

        assertEquals(live, world.store.currentSessionId)
    }

    @Test
    @DisplayName("a restart with nothing open resumes nothing")
    fun resumeWithNothingOpen() = runTest {
        val world = World()
        world.sessionDao.upsert(HrSessionEntity("closed", DEVICE, startedAtMs = NOW, endedAtMs = NOW + 1))

        assertNull(world.store.resumeOpenSession())
        assertNull(world.store.currentSessionId)
        assertNull(world.store.current.value)
    }

    // ---- the workout anchor ----------------------------------------------

    @Test
    @DisplayName("anchoring a running session rewrites it and re-arms the upload")
    fun anchorRewritesTheSession() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)
        world.sessionDao.markSynced(id, generation = 1L)

        assertTrue(world.store.anchorToWorkout("2026-08-10", workoutSessionId = 7L))

        val row = world.session(id)
        assertEquals("2026-08-10", row.workoutDate)
        assertEquals(7L, row.workoutSessionId)
        // A content change means it has to go out again.
        assertFalse(row.isSynced)
        assertEquals(2L, row.dirtyGeneration)
        assertEquals(2, world.scheduled)
        // And the live value moves with the row, so a capture anchored after it
        // started is recognised by End Workout exactly like one anchored at the
        // start.
        assertEquals(
            CaptureSession(id, workoutDate = "2026-08-10", workoutSessionId = 7L),
            world.store.current.value,
        )
    }

    @Test
    @DisplayName("a workout with no hook session anchors on the date alone")
    fun anchorWithoutAHookSession() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)

        assertTrue(world.store.anchorToWorkout("2026-08-10"))

        assertEquals(CaptureSession(id, workoutDate = "2026-08-10"), world.store.current.value)
    }

    @Test
    @DisplayName("an anchor the statement refused publishes nothing, even to the session it named")
    fun refusedAnchorPublishesNothing() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)
        // Closed by something that is not this store's stop path — the stale
        // backstop, a sticky restart's cleanup — so `current` still holds the
        // very session the anchor is about to name.
        world.sessionDao.closeSession(id, NOW)

        assertFalse(world.store.anchorToWorkout("2026-08-10", workoutSessionId = 7L))

        // An id guard would match here, because the id genuinely does. The
        // statement's rows-affected is the discriminator that does not: zero
        // means the session was not open, and an anchor that never landed must
        // not be published as though it had.
        assertEquals(CaptureSession(id), world.store.current.value)
        assertNull(world.session(id).workoutDate)
    }

    @Test
    @DisplayName("there is nothing to anchor when nothing is capturing")
    fun anchorWithoutACapture() = runTest {
        val world = World()

        assertFalse(world.store.anchorToWorkout("2026-08-10"))
        assertEquals(0, world.scheduled)
        assertNull(world.store.current.value)
    }

    @Test
    @DisplayName("anchoring a wiped session changes nothing")
    fun anchorAfterAWipe() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)
        world.sessionDao.delete(id)

        assertFalse(world.store.anchorToWorkout("2026-08-10"))
        assertTrue(world.sessionDao.sessions.isEmpty())
        assertEquals(CaptureSession(id), world.store.current.value)
    }

    @Test
    @DisplayName("a close that wins the race is not undone — the anchor no-ops, the session stays closed")
    fun closeBeatsTheAnchor() = runTest {
        val dao = HookedHrSessionDao()
        val world = World(dao)
        val id = world.store.startSession(DEVICE)
        // End Workout lands between the anchor being decided and its statement
        // running. A read-modify-write would carry the row's old null endedAtMs
        // back over the close and silently reopen a finished session.
        dao.beforeAnchor = { world.store.stopSession { true } }

        assertFalse(world.store.anchorToWorkout("2026-08-10", workoutSessionId = 7L))

        val row = world.session(id)
        assertEquals(NOW, row.endedAtMs)
        assertNull(row.workoutDate)
        assertNull(world.store.current.value)
    }

    @Test
    @DisplayName("an anchor landing mid-close survives it — a close touches only endedAtMs")
    fun anchorSurvivesAConcurrentClose() = runTest {
        val dao = HookedHrSessionDao()
        val world = World(dao)
        val id = world.store.startSession(DEVICE)
        // The mirror case: the anchor commits where a read-modify-write close
        // would already have read the row, so writing the whole row back would
        // erase it.
        dao.beforeClose = { world.store.anchorToWorkout("2026-08-10", workoutSessionId = 7L) }

        assertEquals(CaptureStopResult.CLOSED, world.store.stopSession { true })

        val row = world.session(id)
        assertEquals("2026-08-10", row.workoutDate)
        assertEquals(7L, row.workoutSessionId)
        assertEquals(NOW, row.endedAtMs)
    }

    @Test
    @DisplayName("an anchor whose write raced a stop does not republish a finished capture")
    fun anchorLosesToAStopInFlight() = runTest {
        val dao = HookedHrSessionDao()
        val world = World(dao)
        val id = world.store.startSession(DEVICE)
        // The anchor lands, and only then does the capture end — so the row is
        // rightly anchored while the live value must not say anything is running.
        dao.afterAnchor = { world.store.stopSession { true } }

        assertTrue(world.store.anchorToWorkout("2026-08-10", workoutSessionId = 7L))

        assertEquals("2026-08-10", world.session(id).workoutDate)
        assertNull(world.store.current.value)
    }

    // ---- the sink --------------------------------------------------------

    @Test
    @DisplayName("a buffered sample becomes its row field for field")
    fun sinkMapsOneForOne() = runTest {
        val world = World()

        world.store.store(
            listOf(
                BufferedSample(
                    deviceId = DEVICE,
                    timestampMs = NOW + 137,
                    seq = 2,
                    heartRateBpm = 151,
                    rrIntervalMs = 0,
                    isGapBefore = true,
                    sessionId = "session-9",
                ),
            ),
        )

        assertEquals(
            HrSampleEntity(
                deviceId = DEVICE,
                timestampMs = NOW + 137,
                seq = 2,
                heartRateBpm = 151,
                // A zero interval is the artifact sentinel, not missing data.
                rrIntervalMs = 0,
                isGapBefore = true,
                sessionId = "session-9",
                // The three sync columns are not the buffer's to know.
                isSynced = false,
                syncedAt = null,
                isQuarantined = false,
            ),
            world.sampleDao.samples.values.single(),
        )
    }

    @Test
    @DisplayName("a whole batch lands in one insert")
    fun sinkStoresTheWholeBatch() = runTest {
        val world = World()

        world.store.store((0 until 5).map { world.sample(timestampMs = NOW + it) })

        assertEquals(5, world.sampleDao.samples.size)
        assertEquals(1, world.scheduled)
    }

    @Test
    @DisplayName("an empty batch writes nothing and arms nothing")
    fun sinkIgnoresAnEmptyBatch() = runTest {
        val world = World()

        world.store.store(emptyList())

        assertTrue(world.sampleDao.samples.isEmpty())
        assertEquals(0, world.scheduled)
    }

    @Test
    @DisplayName("a batch flushed twice is stored once — the key is the dedup")
    fun sinkIsIdempotent() = runTest {
        val world = World()
        val batch = listOf(world.sample(), world.sample(seq = 1))

        world.store.store(batch)
        world.store.store(batch)

        // The service-restart case: rows in the buffer at the kill are replayed.
        assertEquals(2, world.sampleDao.samples.size)
    }

    // ---- the debounce ----------------------------------------------------

    @Test
    @DisplayName("the upload debounce is armed after a successful flush, and only then")
    fun scheduleFiresOnlyOnSuccess() = runTest {
        val world = World()

        world.store.store(listOf(world.sample()))
        assertEquals(1, world.scheduled)

        world.gate.close()
        val failure = runCatching { world.store.store(listOf(world.sample(timestampMs = NOW + 1))) }

        assertTrue(failure.isFailure)
        assertEquals(1, world.scheduled)
    }

    // ---- the write lease -------------------------------------------------

    @Test
    @DisplayName("a write refused by a closed gate is a failure, never a silent drop")
    fun closedGateRefusesSamples() = runTest {
        val world = World()
        world.gate.close()

        val error = runCatching { world.store.store(listOf(world.sample())) }.exceptionOrNull()

        // The buffer reads a throw as "keep the batch"; returning normally here
        // would drop the beats on the floor.
        assertTrue(error is ServerSessionClosedException, "expected a refusal, got $error")
        assertTrue(world.sampleDao.samples.isEmpty())
    }

    @Test
    @DisplayName("a session cannot be opened against a closed gate either")
    fun closedGateRefusesSessionStart() = runTest {
        val world = World()
        world.gate.close()

        val error = runCatching { world.store.startSession(DEVICE) }.exceptionOrNull()

        assertTrue(error is ServerSessionClosedException, "expected a refusal, got $error")
        assertTrue(world.sessionDao.sessions.isEmpty())
        assertNull(world.store.currentSessionId)
    }

    @Test
    @DisplayName("a refused close still ends the capture from this device's side")
    fun closedGateDuringStop() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)
        world.gate.close()

        val error = runCatching { world.store.stopSession { true } }.exceptionOrNull()

        assertTrue(error is ServerSessionClosedException, "expected a refusal, got $error")
        // Capture is over whatever happened to the row: a set ticked afterwards
        // must not claim a session that is no longer recording.
        assertNull(world.store.currentSessionId)
        assertNull(world.session(id).endedAtMs)
    }

    // ---- stopping --------------------------------------------------------

    @Test
    @DisplayName("a landed final flush closes the session and sends it out again")
    fun stopClosesTheSession() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)
        world.sessionDao.markSynced(id, generation = 1L)
        assertTrue(world.session(id).isSynced)
        world.clock = NOW + 3_600_000

        val result = world.store.stopSession { true }

        assertEquals(CaptureStopResult.CLOSED, result)
        val row = world.session(id)
        assertEquals(NOW + 3_600_000, row.endedAtMs)
        // Content changed, so the row re-uploads: generation bumped past the
        // one the last upload was marked against, isSynced cleared.
        assertEquals(2L, row.dirtyGeneration)
        assertFalse(row.isSynced)
        assertEquals(listOf(row), world.sessionDao.needsUpload())
        assertNull(world.store.currentSessionId)
        // The anchor goes with the session. Nothing is capturing, so there is no
        // workout for a capture to belong to.
        assertNull(world.store.current.value)
        assertEquals(2, world.scheduled)
    }

    @Test
    @DisplayName("a final flush that will not land leaves the session open")
    fun failedFinalFlushLeavesTheSessionOpen() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)
        var attempts = 0

        val result = world.store.stopSession { attempts++; false }

        assertEquals(CaptureStopResult.LEFT_OPEN, result)
        assertEquals(3, attempts)
        // endedAtMs is a claim that everything the session covers is stored.
        // Rows are still in memory, so the claim would be false.
        val row = world.session(id)
        assertNull(row.endedAtMs)
        assertEquals(1L, row.dirtyGeneration)
        assertNull(world.store.currentSessionId)
        // Nothing new became pending, so nothing was armed.
        assertEquals(1, world.scheduled)
    }

    @Test
    @DisplayName("a flush that lands on a retry still closes the session")
    fun retriedFinalFlushStillCloses() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)
        var attempts = 0

        val result = world.store.stopSession { ++attempts >= 2 }

        assertEquals(CaptureStopResult.CLOSED, result)
        assertEquals(2, attempts)
        assertEquals(NOW, world.session(id).endedAtMs)
    }

    // ---- the deferred close ----------------------------------------------

    @Test
    @DisplayName("the deferred close lands as soon as the buffer's retry gets the rows down")
    fun deferredCloseCompletesOnALaterFlush() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)
        assertEquals(CaptureStopResult.LEFT_OPEN, world.store.stopSession { false })
        assertNull(world.session(id).endedAtMs)
        // The service is gone by now; the store and the sample buffer are
        // app-lived, and this is the buffer's own retry finally succeeding.
        assertNull(world.store.current.value)
        world.clock = NOW + 60_000

        world.store.store(listOf(world.sample(sessionId = id)))

        // The instant capture stopped, not the instant the flush landed — the
        // session did not go on recording for that minute.
        assertEquals(NOW, world.session(id).endedAtMs)
        assertNotNull(world.sessionDao.sessions.getValue(id).endedAtMs)
    }

    @Test
    @DisplayName("a deferred close fires once and does not re-close on the next flush")
    fun deferredCloseIsClaimedOnce() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)
        world.store.stopSession { false }
        world.store.store(listOf(world.sample(sessionId = id)))
        val closedGeneration = world.session(id).dirtyGeneration

        world.clock = NOW + 120_000
        world.store.store(listOf(world.sample(timestampMs = NOW + 1, sessionId = id)))

        // Re-closing would move the boundary later and claim coverage of samples
        // that were never part of the session.
        assertEquals(NOW, world.session(id).endedAtMs)
        assertEquals(closedGeneration, world.session(id).dirtyGeneration)
    }

    @Test
    @DisplayName("a resumed session cancels the deferred close that was waiting for it")
    fun resumeCancelsTheDeferredClose() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)
        world.store.stopSession { false }

        // The service came back and reattached: this session is recording again,
        // and the close it left behind must not fire underneath it.
        assertEquals(id, world.store.resumeOpenSession()?.sessionId)
        world.store.store(listOf(world.sample(sessionId = id)))

        assertNull(world.session(id).endedAtMs)
    }

    @Test
    @DisplayName("a deferred close does not fire on a session that is recording again")
    fun deferredCloseYieldsToAResume() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)
        world.store.stopSession { false }
        // The service came back and reattached between the close being deferred
        // and the buffer draining. Claiming the close first and only then taking
        // the write lease is what used to let it land on the live capture — and
        // `endedAtMs IS NULL` cannot catch that, because the session is
        // legitimately open again.
        assertEquals(id, world.store.resumeOpenSession()?.sessionId)

        world.store.store(listOf(world.sample(sessionId = id)))

        assertNull(world.session(id).endedAtMs)
        assertEquals(id, world.store.currentSessionId)
    }

    @Test
    @DisplayName("two straps each strand a close, and both complete")
    fun everyStrandedCloseCompletes() = runTest {
        val world = World()
        val first = world.store.startSession(DEVICE)
        world.store.stopSession { false }
        world.clock = NOW + 30_000
        // A different strap, so the per-device backstop leaves the first
        // session's deferred close alone. A single slot would have lost it here.
        val second = world.store.startSession(OTHER_DEVICE)
        world.store.stopSession { false }
        assertNull(world.session(first).endedAtMs)
        assertNull(world.session(second).endedAtMs)

        world.clock = NOW + 90_000
        world.store.store(listOf(world.sample(sessionId = second)))

        // Each carries the instant its own capture stopped.
        assertEquals(NOW, world.session(first).endedAtMs)
        assertEquals(NOW + 30_000, world.session(second).endedAtMs)
    }

    @Test
    @DisplayName("a deferred close for a wiped session resolves rather than lingering")
    fun deferredCloseAfterAWipe() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)
        world.store.stopSession { false }
        world.sessionDao.sessions.clear()

        world.store.store(listOf(world.sample(sessionId = id)))

        assertTrue(world.sessionDao.sessions.isEmpty())
    }

    // ---- the stale-session backstop --------------------------------------

    @Test
    @DisplayName("a new capture force-closes a session the same strap left open")
    fun startClosesAStaleSession() = runTest {
        val world = World()
        world.sessionDao.upsert(HrSessionEntity("stale", DEVICE, startedAtMs = NOW - 100_000))

        val id = world.store.startSession(DEVICE)

        // Advisory and knowingly late: nothing measures endedAtMs, and leaving
        // the row open would make every later resume reattach to a capture that
        // ended days ago.
        assertEquals(NOW, world.session("stale").endedAtMs)
        assertNull(world.session(id).endedAtMs)
    }

    @Test
    @DisplayName("the backstop leaves another strap's open session alone")
    fun startLeavesOtherDevicesAlone() = runTest {
        val world = World()
        world.sessionDao.upsert(HrSessionEntity("other", "ZZ:ZZ", startedAtMs = NOW - 100_000))

        world.store.startSession(DEVICE)

        assertNull(world.session("other").endedAtMs)
    }

    @Test
    @DisplayName("the backstop retires the deferred close it just made redundant")
    fun startClearsADeferredCloseItHonoured() = runTest {
        val world = World()
        val stale = world.store.startSession(DEVICE)
        world.store.stopSession { false }
        world.clock = NOW + 60_000

        val fresh = world.store.startSession(DEVICE)
        // The buffer's retry finally lands, well after the backstop closed the
        // old session. It must not re-close it.
        world.store.store(listOf(world.sample(sessionId = stale)))

        assertEquals(NOW + 60_000, world.session(stale).endedAtMs)
        assertNull(world.session(fresh).endedAtMs)
    }

    @Test
    @DisplayName("a first capture on a clean database closes nothing")
    fun startWithNothingStale() = runTest {
        val world = World()

        val id = world.store.startSession(DEVICE)

        assertEquals(setOf(id), world.sessionDao.sessions.keys)
        assertEquals(1, world.scheduled)
    }

    @Test
    @DisplayName("a session left open is what a later restart reattaches to")
    fun aLeftOpenSessionIsResumable() = runTest {
        val world = World()
        val id = world.store.startSession(DEVICE)
        world.store.stopSession { false }

        val resumed = world.store.resumeOpenSession()

        assertEquals(id, resumed?.sessionId)
        assertEquals(id, world.store.currentSessionId)
    }

    @Test
    @DisplayName("stopping when nothing is capturing does not even flush")
    fun stopWithoutACapture() = runTest {
        val world = World()
        var flushed = false

        val result = world.store.stopSession { flushed = true; true }

        assertEquals(CaptureStopResult.NOT_RUNNING, result)
        assertFalse(flushed)
        assertEquals(0, world.scheduled)
    }

    @Test
    @DisplayName("a session wiped mid-capture leaves nothing to close, and that is not a failure")
    fun stopAfterAWipe() = runTest {
        val world = World()
        world.store.startSession(DEVICE)
        // What a server switch does to these rows.
        world.sessionDao.sessions.clear()

        val result = world.store.stopSession { true }

        assertEquals(CaptureStopResult.SESSION_GONE, result)
        assertTrue(world.sessionDao.sessions.isEmpty())
        assertNull(world.store.currentSessionId)
        assertEquals(1, world.scheduled)
    }

    // ---- the coach seam --------------------------------------------------

    @Test
    @DisplayName("the current session id is what a set event carries, and only while capturing")
    fun currentSessionIdTracksTheCapture() = runTest {
        val world = World()
        assertNull(world.store.currentSessionId)

        val id = world.store.startSession(DEVICE)
        assertEquals(id, world.store.currentSessionId)

        world.store.stopSession { true }
        assertNull(world.store.currentSessionId)
    }
}
