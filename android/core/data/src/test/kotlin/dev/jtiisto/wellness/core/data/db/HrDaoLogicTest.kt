package dev.jtiisto.wellness.core.data.db

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The heart-rate DAOs' behaviour, headless.
 *
 * Two things are under test and they are not the same thing. The composed
 * `@Transaction` methods — [HrSessionDao.upsert], [HrSampleDao.markSynced],
 * [HrSampleDao.markQuarantined] — are real production code running here. The
 * query behaviours are running against the fakes, and `HrDaoTest` in
 * `androidTest` asserts every one of them again against the real schema, which
 * is what makes a fake that has drifted from its SQL fail somewhere.
 */
class HrDaoLogicTest {

    private val device = "AA:BB:CC:DD:EE:FF"
    private val session = "11111111-2222-3333-4444-555555555555"

    private fun sample(
        timestampMs: Long,
        seq: Int = 0,
        bpm: Int = 142,
        rr: Int = 423,
        gapBefore: Boolean = false,
        sessionId: String = session,
        deviceId: String = device,
        synced: Boolean = false,
        syncedAt: Long? = null,
        quarantined: Boolean = false,
    ) = HrSampleEntity(
        deviceId = deviceId,
        timestampMs = timestampMs,
        seq = seq,
        heartRateBpm = bpm,
        rrIntervalMs = rr,
        isGapBefore = gapBefore,
        sessionId = sessionId,
        isSynced = synced,
        syncedAt = syncedAt,
        isQuarantined = quarantined,
    )

    private fun event(
        eventId: String,
        at: Long,
        date: String = "2030-01-03",
        exerciseKey: String = "fixture-adhoc-lift",
        setNum: Int? = 1,
        itemKey: String? = null,
        action: String = SetEventEntity.ACTION_CHECK,
        sessionId: String? = null,
        synced: Boolean = false,
        quarantined: Boolean = false,
    ) = SetEventEntity(
        eventId = eventId,
        date = date,
        exerciseKey = exerciseKey,
        setNum = setNum,
        itemKey = itemKey,
        action = action,
        clientTimestampMs = at,
        sessionId = sessionId,
        isSynced = synced,
        isQuarantined = quarantined,
    )

    private fun openSession(
        sessionId: String = session,
        startedAtMs: Long = 1_769_999_990_000,
        workoutDate: String? = null,
        workoutSessionId: Long? = null,
        endedAtMs: Long? = null,
    ) = HrSessionEntity(
        sessionId = sessionId,
        deviceId = device,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        workoutDate = workoutDate,
        workoutSessionId = workoutSessionId,
    )

    /**
     * Mark synced at whatever generation the row currently holds — for the
     * cases where the guard is not what is under test. The guard's own cases
     * pass a generation explicitly, because there the mismatch is the point.
     */
    private suspend fun HrSessionDao.markSyncedNow(sessionId: String) =
        markSynced(sessionId, requireNotNull(find(sessionId)).dirtyGeneration)

    private suspend fun HrSessionDao.markQuarantinedNow(sessionId: String) =
        markQuarantined(sessionId, requireNotNull(find(sessionId)).dirtyGeneration)

    // ---- sessions --------------------------------------------------------

    @Test
    @DisplayName("a new session arrives pending, and the anchor and the close each make it pending again")
    fun sessionUpsertClearsSyncedOnEveryChange() = runTest {
        val dao = FakeHrSessionDao()

        dao.upsert(openSession())
        assertEquals(listOf(session), dao.needsUpload().map { it.sessionId })

        dao.markSyncedNow(session)
        assertTrue(dao.needsUpload().isEmpty())

        // started → workout-anchored: the row changed, so the server needs it again.
        dao.upsert(openSession(workoutDate = "2030-01-03", workoutSessionId = 42))
        assertEquals(listOf(session), dao.needsUpload().map { it.sessionId })

        dao.markSyncedNow(session)
        dao.upsert(openSession(workoutDate = "2030-01-03", workoutSessionId = 42, endedAtMs = 1_770_000_500_000))
        assertEquals(listOf(session), dao.needsUpload().map { it.sessionId })
        assertEquals(1, dao.countPending())
    }

    @Test
    @DisplayName("re-upserting an identical row keeps it synced — a service restart is not a change")
    fun identicalUpsertDoesNotReopenTheUpload() = runTest {
        val dao = FakeHrSessionDao()
        dao.upsert(openSession(workoutDate = "2030-01-03"))
        dao.markSyncedNow(session)

        // START_STICKY hands the service the same open session on every process
        // death; treating that as an edit would post the same row forever.
        dao.upsert(openSession(workoutDate = "2030-01-03"))

        assertTrue(dao.needsUpload().isEmpty())
        assertTrue(dao.find(session)!!.isSynced)
    }

    @Test
    @DisplayName("the caller's upload flags are ignored — both are derived, never supplied")
    fun callerCannotDeclareASessionSyncedOrQuarantined() = runTest {
        val dao = FakeHrSessionDao()

        dao.upsert(openSession().copy(isSynced = true, isQuarantined = true))

        assertFalse(dao.find(session)!!.isSynced)
        assertFalse(dao.find(session)!!.isQuarantined)
        assertEquals(listOf(session), dao.needsUpload().map { it.sessionId })
    }

    @Test
    @DisplayName("a quarantined session is out of the upload, and out of the pending count too")
    fun quarantinedSessionIsExcluded() = runTest {
        val dao = FakeHrSessionDao()
        dao.upsert(openSession(sessionId = "poison", startedAtMs = 1_000))
        dao.upsert(openSession(sessionId = "fine", startedAtMs = 2_000))

        dao.markQuarantinedNow("poison")

        assertEquals(listOf("fine"), dao.needsUpload().map { it.sessionId })
        // The flush scheduler reads countPending to decide whether there is work
        // at all; counting a row it will never offer would keep it awake forever.
        assertEquals(1, dao.countPending())
        assertEquals(1, dao.countQuarantined())
    }

    @Test
    @DisplayName("a corrected session leaves quarantine by itself — the flag clears on a content change")
    fun quarantineClearsOnContentChange() = runTest {
        val dao = FakeHrSessionDao()
        dao.upsert(openSession())
        dao.markQuarantinedNow(session)
        assertTrue(dao.needsUpload().isEmpty())

        // The row the server rejected is not the row that replaced it. This is
        // the whole reason the column exists rather than an in-memory
        // abandon-for-this-run, which never self-heals.
        dao.upsert(openSession(workoutDate = "2030-01-03", workoutSessionId = 42))

        assertFalse(dao.find(session)!!.isQuarantined)
        assertEquals(listOf(session), dao.needsUpload().map { it.sessionId })
    }

    @Test
    @DisplayName("re-upserting an identical row keeps the quarantine — a restart is not a correction")
    fun identicalUpsertKeepsQuarantine() = runTest {
        val dao = FakeHrSessionDao()
        dao.upsert(openSession(workoutDate = "2030-01-03"))
        dao.markQuarantinedNow(session)

        dao.upsert(openSession(workoutDate = "2030-01-03"))

        assertTrue(dao.find(session)!!.isQuarantined)
        assertTrue(dao.needsUpload().isEmpty())
        assertEquals(0, dao.countPending())
    }

    // ---- the generation guard --------------------------------------------

    @Test
    @DisplayName("the generation bumps on every content change and holds still otherwise")
    fun generationBumpsOnlyOnContentChange() = runTest {
        val dao = FakeHrSessionDao()

        dao.upsert(openSession())
        assertEquals(1L, dao.find(session)!!.dirtyGeneration)

        // The service re-upserting the open session it resumed is not an edit.
        dao.upsert(openSession())
        assertEquals(1L, dao.find(session)!!.dirtyGeneration)

        dao.upsert(openSession(workoutDate = "2030-01-03"))
        assertEquals(2L, dao.find(session)!!.dirtyGeneration)

        dao.upsert(openSession(workoutDate = "2030-01-03", endedAtMs = 1_770_000_500_000))
        assertEquals(3L, dao.find(session)!!.dirtyGeneration)
    }

    @Test
    @DisplayName("the caller's generation is ignored, as both flags are")
    fun callerCannotDeclareAGeneration() = runTest {
        val dao = FakeHrSessionDao()

        dao.upsert(openSession().copy(dirtyGeneration = 99))

        assertEquals(1L, dao.find(session)!!.dirtyGeneration)
    }

    /**
     * The blocker this column exists for, end to end at the DAO level.
     *
     * Before the guard, the last line of this test failed: the session was
     * marked synced against a server that had only ever received the open row,
     * and the version naming the workout never uploaded again.
     */
    @Test
    @DisplayName("a session rewritten mid-POST is not marked synced by the old row's response")
    fun aVerdictForAnOlderRowCannotLand() = runTest {
        val dao = FakeHrSessionDao()
        dao.upsert(openSession())

        // What the uploader took: the open session at generation 1.
        val uploaded = dao.needsUpload().single()
        assertEquals(1L, uploaded.dirtyGeneration)

        // While the POST is in flight, End Workout closes the session.
        dao.upsert(openSession(workoutDate = "2030-01-03", endedAtMs = 1_770_000_500_000))

        dao.markSynced(uploaded.sessionId, uploaded.dirtyGeneration)

        assertFalse(dao.find(session)!!.isSynced)
        val stillPending = dao.needsUpload().single()
        assertEquals(2L, stillPending.dirtyGeneration)
        assertEquals(1_770_000_500_000L, stillPending.endedAtMs)

        // And the next pass, echoing the generation it actually read, lands.
        dao.markSynced(stillPending.sessionId, stillPending.dirtyGeneration)
        assertTrue(dao.needsUpload().isEmpty())
    }

    @Test
    @DisplayName("a stale quarantine misses too — it would strand a row the server never rejected")
    fun aStaleQuarantineCannotLand() = runTest {
        val dao = FakeHrSessionDao()
        dao.upsert(openSession())
        val uploaded = dao.needsUpload().single()

        dao.upsert(openSession(workoutDate = "2030-01-03"))
        dao.markQuarantined(uploaded.sessionId, uploaded.dirtyGeneration)

        assertFalse(dao.find(session)!!.isQuarantined)
        assertEquals(listOf(session), dao.needsUpload().map { it.sessionId })

        // The matching generation still works, so nothing is unreachable.
        dao.markQuarantined(session, generation = 2)
        assertTrue(dao.find(session)!!.isQuarantined)
    }

    @Test
    @DisplayName("the resumed session is the newest still-open one, and a closed one is never offered")
    fun newestOpenSession() = runTest {
        val dao = FakeHrSessionDao()
        dao.upsert(openSession(sessionId = "old", startedAtMs = 1_000, endedAtMs = 2_000))
        dao.upsert(openSession(sessionId = "stale-open", startedAtMs = 3_000))
        dao.upsert(openSession(sessionId = "current", startedAtMs = 4_000))

        assertEquals("current", dao.newestOpen()?.sessionId)

        dao.upsert(openSession(sessionId = "current", startedAtMs = 4_000, endedAtMs = 5_000))
        assertEquals("stale-open", dao.newestOpen()?.sessionId)

        dao.upsert(openSession(sessionId = "stale-open", startedAtMs = 3_000, endedAtMs = 5_000))
        assertNull(dao.newestOpen())
    }

    // ---- atomic mutations of a live session -------------------------------

    @Test
    @DisplayName("anchoring a closed session no-ops rather than reopening it")
    fun anchorAfterCloseCannotReopen() = runTest {
        val dao = FakeHrSessionDao()
        dao.upsert(openSession())
        dao.closeSession(session, endedAtMs = 1_770_000_500_000)
        val closed = dao.find(session)!!

        // The read-copy-upsert this replaced would have written back the null
        // endedAtMs it read before the close, resurrecting a finished session.
        assertEquals(0, dao.anchorWorkout(session, workoutDate = "2030-01-03", workoutSessionId = 42))

        assertEquals(closed, dao.find(session), "a no-op must leave every column alone")
        assertNull(dao.find(session)!!.workoutDate)
        assertEquals(1_770_000_500_000L, dao.find(session)!!.endedAtMs)
    }

    @Test
    @DisplayName("closing a session keeps an anchor that landed while the stop was in flight")
    fun closeAfterAnchorPreservesTheAnchor() = runTest {
        val dao = FakeHrSessionDao()
        dao.upsert(openSession())

        dao.anchorWorkout(session, workoutDate = "2030-01-03", workoutSessionId = 42)
        assertEquals(1, dao.closeSession(session, endedAtMs = 1_770_000_500_000))

        // The stale full-row write erased exactly this.
        val row = dao.find(session)!!
        assertEquals("2030-01-03", row.workoutDate)
        assertEquals(42L, row.workoutSessionId)
        assertEquals(1_770_000_500_000L, row.endedAtMs)
    }

    @Test
    @DisplayName("closing twice is idempotent — the second close cannot move endedAtMs")
    fun doubleCloseNoOps() = runTest {
        val dao = FakeHrSessionDao()
        dao.upsert(openSession())

        assertEquals(1, dao.closeSession(session, endedAtMs = 1_770_000_500_000))
        val afterFirst = dao.find(session)!!

        // A later endedAtMs would claim coverage of samples that were never
        // stored, so the guard has to reject it rather than take the newer time.
        assertEquals(0, dao.closeSession(session, endedAtMs = 1_770_009_999_000))

        assertEquals(afterFirst, dao.find(session))
    }

    @Test
    @DisplayName("both mutations bump the generation and clear the flags, as a content change must")
    fun atomicMutationsCarryTheUploadSemantics() = runTest {
        val dao = FakeHrSessionDao()
        dao.upsert(openSession())
        dao.markSyncedNow(session)
        dao.markQuarantinedNow(session)
        assertEquals(1L, dao.find(session)!!.dirtyGeneration)

        assertEquals(1, dao.anchorWorkout(session, workoutDate = "2030-01-03", workoutSessionId = 42))
        var row = dao.find(session)!!
        assertEquals(2L, row.dirtyGeneration)
        assertFalse(row.isSynced)
        assertFalse(row.isQuarantined)
        assertEquals(listOf(session), dao.needsUpload().map { it.sessionId })

        dao.markSyncedNow(session)
        assertEquals(1, dao.closeSession(session, endedAtMs = 1_770_000_500_000))
        row = dao.find(session)!!
        assertEquals(3L, row.dirtyGeneration)
        assertFalse(row.isSynced)
        assertEquals(listOf(session), dao.needsUpload().map { it.sessionId })
    }

    @Test
    @DisplayName("a verdict for the pre-mutation row cannot land on either mutation's result")
    fun atomicMutationsInvalidateAnInFlightVerdict() = runTest {
        val dao = FakeHrSessionDao()
        dao.upsert(openSession())
        val uploaded = dao.needsUpload().single()

        dao.anchorWorkout(session, workoutDate = "2030-01-03", workoutSessionId = 42)
        dao.markSynced(uploaded.sessionId, uploaded.dirtyGeneration)

        assertFalse(dao.find(session)!!.isSynced, "the anchor's bump must strand the old verdict")
    }

    @Test
    @DisplayName("neither mutation resurrects a row the wipe removed")
    fun mutationsOnAMissingRowReportZero() = runTest {
        val dao = FakeHrSessionDao()

        assertEquals(0, dao.anchorWorkout(session, workoutDate = "2030-01-03", workoutSessionId = 42))
        assertEquals(0, dao.closeSession(session, endedAtMs = 1_770_000_500_000))

        assertTrue(dao.listAll().isEmpty())
    }

    // ---- samples ---------------------------------------------------------

    @Test
    @DisplayName("the composite key dedupes, and the row already stored wins")
    fun insertAllIgnoresDuplicateKeys() = runTest {
        val dao = FakeHrSampleDao()
        dao.insertAll(listOf(sample(1_000, seq = 0, bpm = 140)))

        val ids = dao.insertAll(
            listOf(
                sample(1_000, seq = 0, bpm = 199),
                // Same millisecond, next beat: seq is what keeps this a new row
                // instead of the shifted timestamp pulse-bridge would have written.
                sample(1_000, seq = 1, rr = 0),
                sample(1_400, seq = 0),
            ),
        )

        // -1 marks the row the key already held; it is the only way to count
        // what a re-delivered notification dropped.
        assertEquals(-1L, ids[0])
        assertTrue(ids[1] > 0 && ids[2] > 0)
        assertEquals(3, dao.listAll().size)
        assertEquals(140, dao.listAll().first().heartRateBpm)
    }

    @Test
    @DisplayName("pendingUpload is oldest-first with seq breaking the tie, and honours the limit")
    fun pendingUploadOrdersByTimestampThenSeq() = runTest {
        val dao = FakeHrSampleDao()
        dao.insertAll(
            listOf(
                sample(1_400, seq = 0),
                sample(1_000, seq = 1),
                sample(1_000, seq = 0),
            ),
        )

        assertEquals(
            listOf(1_000L to 0, 1_000L to 1, 1_400L to 0),
            dao.pendingUpload(10).map { it.timestampMs to it.seq },
        )
        assertEquals(listOf(1_000L to 0, 1_000L to 1), dao.pendingUpload(2).map { it.timestampMs to it.seq })
    }

    @Test
    @DisplayName("synced and quarantined rows are both out of the upload, for different reasons")
    fun pendingUploadExcludesSyncedAndQuarantined() = runTest {
        val dao = FakeHrSampleDao()
        dao.insertAll(
            listOf(
                sample(1_000, synced = true, syncedAt = 9_000),
                sample(2_000, quarantined = true),
                sample(3_000),
            ),
        )

        assertEquals(listOf(3_000L), dao.pendingUpload(10).map { it.timestampMs })
        assertEquals(1, dao.countPending())
        assertEquals(1, dao.countQuarantined())
    }

    @Test
    @DisplayName("markSynced stamps exactly the batch it was given, and nothing else")
    fun markSyncedTouchesOnlyTheBatch() = runTest {
        val dao = FakeHrSampleDao()
        dao.insertAll(listOf(sample(1_000), sample(2_000), sample(3_000)))
        val batch = dao.pendingUpload(2)

        dao.markSynced(batch.map { it.key() }, syncedAt = 7_777)

        val byTime = dao.listAll().associateBy { it.timestampMs }
        assertEquals(listOf(true, true, false), listOf(1_000L, 2_000L, 3_000L).map { byTime.getValue(it).isSynced })
        assertEquals(7_777L, byTime.getValue(1_000L).syncedAt)
        assertNull(byTime.getValue(3_000L).syncedAt)
        assertEquals(listOf(3_000L), dao.pendingUpload(10).map { it.timestampMs })
    }

    @Test
    @DisplayName("marking a row the prune has already removed is a no-op, not a resurrection")
    fun markSyncedIgnoresVanishedRows() = runTest {
        val dao = FakeHrSampleDao()
        dao.insertAll(listOf(sample(1_000)))

        dao.markSynced(listOf(HrSampleKey(device, 5_000, 0)), syncedAt = 7_777)

        assertEquals(listOf(1_000L), dao.listAll().map { it.timestampMs })
    }

    @Test
    @DisplayName("quarantine is per row: the poison drops out and its neighbours still upload")
    fun markQuarantinedIsolatesRows() = runTest {
        val dao = FakeHrSampleDao()
        dao.insertAll(listOf(sample(1_000), sample(2_000), sample(3_000)))

        dao.markQuarantined(listOf(HrSampleKey(device, 2_000, 0)))

        assertEquals(listOf(1_000L, 3_000L), dao.pendingUpload(10).map { it.timestampMs })
        assertTrue(dao.listAll().single { it.timestampMs == 2_000L }.isQuarantined)
    }

    @Test
    @DisplayName("the prune takes synced rows strictly older than the cutoff and leaves the rest")
    fun samplePruneIsSyncedOnlyAndExclusive() = runTest {
        val dao = FakeHrSampleDao()
        dao.insertAll(
            listOf(
                sample(1_000, synced = true, syncedAt = 1),
                // Exactly on the boundary: kept. The cutoff is exclusive, pinned here.
                sample(2_000, synced = true, syncedAt = 1),
                sample(2_500, synced = true, syncedAt = 1),
                // Never uploaded, and older than the cutoff: this row is the only
                // copy that exists anywhere, so retention must not touch it.
                sample(900),
            ),
        )

        assertEquals(1, dao.pruneSynced(cutoffMs = 2_000))

        assertEquals(listOf(900L, 2_000L, 2_500L), dao.listAll().map { it.timestampMs })
    }

    @Test
    @DisplayName("summaries group by session and count what has and has not left the device")
    fun summariesAggregatePerSession() = runTest {
        val dao = FakeHrSampleDao()
        dao.insertAll(
            listOf(
                sample(1_000, sessionId = "a", synced = true, syncedAt = 1),
                sample(1_500, sessionId = "a"),
                sample(2_000, sessionId = "a", quarantined = true),
                sample(9_000, sessionId = "b"),
            ),
        )

        assertEquals(
            listOf(
                HrSampleSummary(
                    sessionId = "a", total = 3, pending = 1, quarantined = 1,
                    firstTimestampMs = 1_000, lastTimestampMs = 2_000,
                ),
                HrSampleSummary(
                    sessionId = "b", total = 1, pending = 1, quarantined = 0,
                    firstTimestampMs = 9_000, lastTimestampMs = 9_000,
                ),
            ),
            dao.summaries(),
        )
    }

    // ---- set events ------------------------------------------------------

    @Test
    @DisplayName("events upload in toggle order, with same-millisecond ties keeping the order they were made")
    fun setEventsUploadInToggleOrder() = runTest {
        val dao = FakeSetEventDao()
        dao.insert(event("e-late", at = 2_000))
        dao.insert(event("e-tie-first", at = 1_000))
        dao.insert(event("e-tie-second", at = 1_000, action = SetEventEntity.ACTION_UNCHECK))

        assertEquals(
            listOf("e-tie-first", "e-tie-second", "e-late"),
            dao.pendingUpload(10).map { it.eventId },
        )
        assertEquals(listOf("e-tie-first", "e-tie-second"), dao.pendingUpload(2).map { it.eventId })
    }

    @Test
    @DisplayName("an uncheck is a row of its own — nothing is ever deleted to undo a tick")
    fun uncheckIsAppended() = runTest {
        val dao = FakeSetEventDao()
        dao.insert(event("e1", at = 1_000, action = SetEventEntity.ACTION_CHECK))
        dao.insert(event("e2", at = 2_000, action = SetEventEntity.ACTION_UNCHECK))

        assertEquals(
            listOf(SetEventEntity.ACTION_CHECK, SetEventEntity.ACTION_UNCHECK),
            dao.listAll().map { it.action },
        )
    }

    @Test
    @DisplayName("reusing an event id aborts: the id is the server's idempotency key")
    fun duplicateEventIdIsRejected() = runTest {
        val dao = FakeSetEventDao()
        dao.insert(event("e1", at = 1_000))

        assertThrows<IllegalStateException> { dao.insert(event("e1", at = 2_000)) }
    }

    @Test
    @DisplayName("synced and quarantined events are both out of the upload")
    fun setEventPendingExcludesSyncedAndQuarantined() = runTest {
        val dao = FakeSetEventDao()
        dao.insert(event("e1", at = 1_000))
        dao.insert(event("e2", at = 2_000))
        dao.insert(event("e3", at = 3_000))

        dao.markSynced(listOf("e1"))
        dao.markQuarantined(listOf("e2"))

        assertEquals(listOf("e3"), dao.pendingUpload(10).map { it.eventId })
        assertEquals(1, dao.countPending())
        assertEquals(1, dao.countQuarantined())
    }

    @Test
    @DisplayName("the event prune matches the sample prune: synced only, cutoff exclusive")
    fun setEventPruneBoundaries() = runTest {
        val dao = FakeSetEventDao()
        dao.insert(event("old-synced", at = 1_000, synced = true))
        dao.insert(event("boundary-synced", at = 2_000, synced = true))
        dao.insert(event("old-pending", at = 900))

        assertEquals(1, dao.pruneSynced(cutoffMs = 2_000))

        assertEquals(listOf("old-pending", "boundary-synced"), dao.listAll().map { it.eventId })
    }

    @Test
    @DisplayName("a checklist toggle carries itemKey instead of setNum, and both may be absent")
    fun setEventShapes() = runTest {
        val dao = FakeSetEventDao()
        dao.insert(event("set", at = 1_000, setNum = 3))
        dao.insert(event("item", at = 2_000, setNum = null, itemKey = "fixture-item-a"))
        // Cardio completion: an exercise-level toggle, neither set nor item.
        dao.insert(event("cardio", at = 3_000, setNum = null))

        val rows = dao.listAll().associateBy { it.eventId }
        assertEquals(3, rows.getValue("set").setNum)
        assertNull(rows.getValue("set").itemKey)
        assertEquals("fixture-item-a", rows.getValue("item").itemKey)
        assertNull(rows.getValue("item").setNum)
        assertNull(rows.getValue("cardio").setNum)
        assertNull(rows.getValue("cardio").itemKey)
    }
}
