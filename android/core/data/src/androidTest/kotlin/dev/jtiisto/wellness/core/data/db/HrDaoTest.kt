package dev.jtiisto.wellness.core.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SQL half of the heart-rate DAOs, against the real Room schema.
 *
 * Every case here has a twin in `HrDaoLogicTest`, which runs the same
 * expectations against the in-memory fakes the upload store is tested with.
 * That is the entire point of the duplication: the fakes transcribe these
 * queries, and a transcription nobody checks is a transcription that drifts.
 *
 * Runs on the emulator (`/adb-*` sessions), never in git hooks.
 */
@RunWith(AndroidJUnit4::class)
class HrDaoTest {

    private lateinit var db: WellnessDatabase
    private lateinit var sessions: HrSessionDao
    private lateinit var samples: HrSampleDao
    private lateinit var events: SetEventDao

    private val device = "AA:BB:CC:DD:EE:FF"
    private val session = "11111111-2222-3333-4444-555555555555"

    @Before
    fun openDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WellnessDatabase::class.java,
        ).build()
        sessions = db.hrSessionDao()
        samples = db.hrSampleDao()
        events = db.setEventDao()
    }

    @After
    fun closeDb() = db.close()

    private fun sample(
        timestampMs: Long,
        seq: Int = 0,
        bpm: Int = 142,
        rr: Int = 423,
        sessionId: String = session,
        synced: Boolean = false,
        syncedAt: Long? = null,
        quarantined: Boolean = false,
    ) = HrSampleEntity(
        deviceId = device,
        timestampMs = timestampMs,
        seq = seq,
        heartRateBpm = bpm,
        rrIntervalMs = rr,
        sessionId = sessionId,
        isSynced = synced,
        syncedAt = syncedAt,
        isQuarantined = quarantined,
    )

    private fun event(
        eventId: String,
        at: Long,
        setNum: Int? = 1,
        itemKey: String? = null,
        action: String = SetEventEntity.ACTION_CHECK,
        synced: Boolean = false,
        quarantined: Boolean = false,
    ) = SetEventEntity(
        eventId = eventId,
        date = "2030-01-03",
        exerciseKey = "fixture-adhoc-lift",
        setNum = setNum,
        itemKey = itemKey,
        action = action,
        clientTimestampMs = at,
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

    /** Mark at the row's current generation — for cases where the guard is not the subject. */
    private suspend fun markSyncedNow(sessionId: String) =
        sessions.markSynced(sessionId, requireNotNull(sessions.find(sessionId)).dirtyGeneration)

    private suspend fun markQuarantinedNow(sessionId: String) =
        sessions.markQuarantined(sessionId, requireNotNull(sessions.find(sessionId)).dirtyGeneration)

    // ---- sessions --------------------------------------------------------

    @Test
    fun sessionUpsertClearsSyncedOnEveryChange() = runBlocking {
        sessions.upsert(openSession())
        assertEquals(listOf(session), sessions.needsUpload().map { it.sessionId })

        markSyncedNow(session)
        assertTrue(sessions.needsUpload().isEmpty())

        sessions.upsert(openSession(workoutDate = "2030-01-03", workoutSessionId = 42))
        assertEquals(listOf(session), sessions.needsUpload().map { it.sessionId })

        markSyncedNow(session)
        sessions.upsert(
            openSession(workoutDate = "2030-01-03", workoutSessionId = 42, endedAtMs = 1_770_000_500_000),
        )
        assertEquals(1, sessions.countPending())
    }

    @Test
    fun identicalUpsertDoesNotReopenTheUpload() = runBlocking {
        sessions.upsert(openSession(workoutDate = "2030-01-03"))
        markSyncedNow(session)

        sessions.upsert(openSession(workoutDate = "2030-01-03"))

        assertTrue(sessions.needsUpload().isEmpty())
        assertTrue(sessions.find(session)!!.isSynced)
    }

    @Test
    fun callerCannotDeclareASessionSyncedOrQuarantined() = runBlocking {
        sessions.upsert(openSession().copy(isSynced = true, isQuarantined = true))

        assertFalse(sessions.find(session)!!.isSynced)
        assertFalse(sessions.find(session)!!.isQuarantined)
    }

    @Test
    fun quarantinedSessionIsExcluded() = runBlocking {
        sessions.upsert(openSession(sessionId = "poison", startedAtMs = 1_000))
        sessions.upsert(openSession(sessionId = "fine", startedAtMs = 2_000))

        markQuarantinedNow("poison")

        assertEquals(listOf("fine"), sessions.needsUpload().map { it.sessionId })
        assertEquals(1, sessions.countPending())
        assertEquals(1, sessions.countQuarantined())
    }

    @Test
    fun quarantineClearsOnContentChange() = runBlocking {
        sessions.upsert(openSession())
        markQuarantinedNow(session)
        assertTrue(sessions.needsUpload().isEmpty())

        sessions.upsert(openSession(workoutDate = "2030-01-03", workoutSessionId = 42))

        assertFalse(sessions.find(session)!!.isQuarantined)
        assertEquals(listOf(session), sessions.needsUpload().map { it.sessionId })
    }

    @Test
    fun identicalUpsertKeepsQuarantine() = runBlocking {
        sessions.upsert(openSession(workoutDate = "2030-01-03"))
        markQuarantinedNow(session)

        sessions.upsert(openSession(workoutDate = "2030-01-03"))

        assertTrue(sessions.find(session)!!.isQuarantined)
        assertEquals(0, sessions.countPending())
    }

    @Test
    fun newestOpenSession() = runBlocking {
        sessions.upsert(openSession(sessionId = "old", startedAtMs = 1_000, endedAtMs = 2_000))
        sessions.upsert(openSession(sessionId = "stale-open", startedAtMs = 3_000))
        sessions.upsert(openSession(sessionId = "current", startedAtMs = 4_000))

        assertEquals("current", sessions.newestOpen()?.sessionId)

        sessions.upsert(openSession(sessionId = "current", startedAtMs = 4_000, endedAtMs = 5_000))
        assertEquals("stale-open", sessions.newestOpen()?.sessionId)

        sessions.upsert(openSession(sessionId = "stale-open", startedAtMs = 3_000, endedAtMs = 5_000))
        assertNull(sessions.newestOpen())
    }

    @Test
    fun sessionRoundTripsEveryColumn() = runBlocking {
        val row = openSession(
            workoutDate = "2030-01-03",
            workoutSessionId = 42,
            endedAtMs = 1_770_000_500_000,
        ).copy(isSynced = true, isQuarantined = true, dirtyGeneration = 7)

        // The raw upsert, not the composed one: `upsert` derives both flags, so
        // it could never write the combination this asserts persists.
        sessions.upsertRow(row)

        assertEquals(row, sessions.find(session))
    }

    @Test
    fun generationBumpsOnlyOnContentChange() = runBlocking {
        sessions.upsert(openSession())
        assertEquals(1L, sessions.find(session)!!.dirtyGeneration)

        sessions.upsert(openSession())
        assertEquals(1L, sessions.find(session)!!.dirtyGeneration)

        sessions.upsert(openSession(workoutDate = "2030-01-03"))
        assertEquals(2L, sessions.find(session)!!.dirtyGeneration)
    }

    @Test
    fun aVerdictForAnOlderRowCannotLand() = runBlocking {
        sessions.upsert(openSession())
        val uploaded = sessions.needsUpload().single()

        sessions.upsert(openSession(workoutDate = "2030-01-03", endedAtMs = 1_770_000_500_000))
        sessions.markSynced(uploaded.sessionId, uploaded.dirtyGeneration)

        assertFalse(sessions.find(session)!!.isSynced)
        val stillPending = sessions.needsUpload().single()
        assertEquals(2L, stillPending.dirtyGeneration)
        assertEquals(1_770_000_500_000L, stillPending.endedAtMs)

        sessions.markSynced(stillPending.sessionId, stillPending.dirtyGeneration)
        assertTrue(sessions.needsUpload().isEmpty())
    }

    @Test
    fun aStaleQuarantineCannotLand() = runBlocking {
        sessions.upsert(openSession())
        val uploaded = sessions.needsUpload().single()

        sessions.upsert(openSession(workoutDate = "2030-01-03"))
        sessions.markQuarantined(uploaded.sessionId, uploaded.dirtyGeneration)

        assertFalse(sessions.find(session)!!.isQuarantined)

        sessions.markQuarantined(session, generation = 2)
        assertTrue(sessions.find(session)!!.isQuarantined)
    }

    // ---- samples ---------------------------------------------------------

    @Test
    fun insertAllIgnoresDuplicateKeys() = runBlocking {
        samples.insertAll(listOf(sample(1_000, seq = 0, bpm = 140)))

        val ids = samples.insertAll(
            listOf(
                sample(1_000, seq = 0, bpm = 199),
                sample(1_000, seq = 1, rr = 0),
                sample(1_400, seq = 0),
            ),
        )

        assertEquals(-1L, ids[0])
        assertTrue(ids[1] > 0 && ids[2] > 0)
        assertEquals(3, samples.listAll().size)
        assertEquals(140, samples.listAll().first().heartRateBpm)
    }

    @Test
    fun sampleRoundTripsEveryColumn() = runBlocking {
        val row = HrSampleEntity(
            deviceId = device,
            timestampMs = 1_770_000_004_100,
            seq = 2,
            heartRateBpm = 141,
            // Zero is the artifact sentinel, and it has to survive as a value
            // rather than degrade into "missing".
            rrIntervalMs = 0,
            isGapBefore = true,
            sessionId = session,
            isSynced = true,
            syncedAt = 1_770_000_010_000,
            isQuarantined = true,
        )

        samples.insertAll(listOf(row))

        assertEquals(row, samples.listAll().single())
    }

    @Test
    fun pendingUploadOrdersByTimestampThenSeq() = runBlocking {
        samples.insertAll(listOf(sample(1_400, seq = 0), sample(1_000, seq = 1), sample(1_000, seq = 0)))

        assertEquals(
            listOf(1_000L to 0, 1_000L to 1, 1_400L to 0),
            samples.pendingUpload(10).map { it.timestampMs to it.seq },
        )
        assertEquals(
            listOf(1_000L to 0, 1_000L to 1),
            samples.pendingUpload(2).map { it.timestampMs to it.seq },
        )
    }

    @Test
    fun pendingUploadExcludesSyncedAndQuarantined() = runBlocking {
        samples.insertAll(
            listOf(
                sample(1_000, synced = true, syncedAt = 9_000),
                sample(2_000, quarantined = true),
                sample(3_000),
            ),
        )

        assertEquals(listOf(3_000L), samples.pendingUpload(10).map { it.timestampMs })
        assertEquals(1, samples.countPending())
        assertEquals(1, samples.countQuarantined())
    }

    @Test
    fun markSyncedTouchesOnlyTheBatch() = runBlocking {
        samples.insertAll(listOf(sample(1_000), sample(2_000), sample(3_000)))
        val batch = samples.pendingUpload(2)

        samples.markSynced(batch.map { it.key() }, syncedAt = 7_777)

        val byTime = samples.listAll().associateBy { it.timestampMs }
        assertEquals(listOf(true, true, false), listOf(1_000L, 2_000L, 3_000L).map { byTime.getValue(it).isSynced })
        assertEquals(7_777L, byTime.getValue(1_000L).syncedAt)
        assertNull(byTime.getValue(3_000L).syncedAt)
        assertEquals(listOf(3_000L), samples.pendingUpload(10).map { it.timestampMs })
    }

    @Test
    fun markSyncedIgnoresVanishedRows() = runBlocking {
        samples.insertAll(listOf(sample(1_000)))

        samples.markSynced(listOf(HrSampleKey(device, 5_000, 0)), syncedAt = 7_777)

        assertEquals(listOf(1_000L), samples.listAll().map { it.timestampMs })
    }

    @Test
    fun markQuarantinedIsolatesRows() = runBlocking {
        samples.insertAll(listOf(sample(1_000), sample(2_000), sample(3_000)))

        samples.markQuarantined(listOf(HrSampleKey(device, 2_000, 0)))

        assertEquals(listOf(1_000L, 3_000L), samples.pendingUpload(10).map { it.timestampMs })
        assertTrue(samples.listAll().single { it.timestampMs == 2_000L }.isQuarantined)
    }

    @Test
    fun samplePruneIsSyncedOnlyAndExclusive() = runBlocking {
        samples.insertAll(
            listOf(
                sample(1_000, synced = true, syncedAt = 1),
                sample(2_000, synced = true, syncedAt = 1),
                sample(2_500, synced = true, syncedAt = 1),
                sample(900),
            ),
        )

        assertEquals(1, samples.pruneSynced(cutoffMs = 2_000))

        assertEquals(listOf(900L, 2_000L, 2_500L), samples.listAll().map { it.timestampMs })
    }

    @Test
    fun summariesAggregatePerSession() = runBlocking {
        samples.insertAll(
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
            samples.summaries(),
        )
    }

    /**
     * The marking calls are one transaction over per-row updates, so a failure
     * partway has to leave the whole batch pending rather than half-stamped.
     */
    @Test
    fun markSyncedIsAllOrNothing() = runBlocking {
        samples.insertAll(listOf(sample(1_000), sample(2_000)))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER abort_probe BEFORE UPDATE ON hr_samples WHEN NEW.timestampMs = 2000 " +
                "BEGIN SELECT RAISE(ABORT, 'abort_probe'); END",
        )

        runCatching { samples.markSynced(samples.pendingUpload(10).map { it.key() }, syncedAt = 7_777) }

        assertEquals(2, samples.countPending())
    }

    // ---- set events ------------------------------------------------------

    @Test
    fun setEventsUploadInToggleOrder() = runBlocking {
        events.insert(event("e-late", at = 2_000))
        events.insert(event("e-tie-first", at = 1_000))
        events.insert(event("e-tie-second", at = 1_000, action = SetEventEntity.ACTION_UNCHECK))

        assertEquals(
            listOf("e-tie-first", "e-tie-second", "e-late"),
            events.pendingUpload(10).map { it.eventId },
        )
        assertEquals(listOf("e-tie-first", "e-tie-second"), events.pendingUpload(2).map { it.eventId })
    }

    @Test
    fun setEventRoundTripsEveryColumn() = runBlocking {
        val row = SetEventEntity(
            eventId = "e1",
            date = "2030-01-04",
            exerciseKey = "fixture-adhoc-checklist",
            setNum = null,
            itemKey = "fixture-item-a",
            action = SetEventEntity.ACTION_UNCHECK,
            clientTimestampMs = 1_770_000_020_000,
            sessionId = session,
            isSynced = true,
            isQuarantined = true,
        )

        events.insert(row)

        assertEquals(row, events.listAll().single())
    }

    @Test
    fun duplicateEventIdIsRejected() {
        runBlocking { events.insert(event("e1", at = 1_000)) }

        // Conflict is abort, not ignore: the id is a UUID minted per toggle, so
        // a collision is a caller bug and not a duplicate delivery.
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { events.insert(event("e1", at = 2_000)) }
        }
    }

    @Test
    fun setEventPendingExcludesSyncedAndQuarantined() = runBlocking {
        events.insert(event("e1", at = 1_000))
        events.insert(event("e2", at = 2_000))
        events.insert(event("e3", at = 3_000))

        events.markSynced(listOf("e1"))
        events.markQuarantined(listOf("e2"))

        assertEquals(listOf("e3"), events.pendingUpload(10).map { it.eventId })
        assertEquals(1, events.countPending())
        assertEquals(1, events.countQuarantined())
    }

    @Test
    fun setEventPruneBoundaries() = runBlocking {
        events.insert(event("old-synced", at = 1_000, synced = true))
        events.insert(event("boundary-synced", at = 2_000, synced = true))
        events.insert(event("old-pending", at = 900))

        assertEquals(1, events.pruneSynced(cutoffMs = 2_000))

        assertEquals(listOf("old-pending", "boundary-synced"), events.listAll().map { it.eventId })
    }

    /**
     * The dual-write's precondition: a coach blob write and the event that
     * describes it are two tables in one database, so one transaction can cover
     * both and a failing event insert takes the blob down with it.
     *
     * This is the capability, not the production route. Stage C composes the two
     * in a DAO-level `@Transaction` method — Room cannot call across DAOs, so
     * the composing DAO declares its own `@Insert` for [SetEventEntity] — which
     * keeps the composition testable against the JVM fakes. `withTransaction`
     * here only needs a real database, which this suite has.
     */
    @Test
    fun anEventAndACoachBlobWriteCanBeMadeAtomic() = runBlocking {
        db.withTransaction {
            db.coachDao().upsertLog(CoachLogEntity("2030-01-03", """{"ex_1":{}}""", false, 0))
            events.insert(event("e1", at = 1_000))
        }

        assertEquals(1, events.countPending())
        assertEquals("""{"ex_1":{}}""", db.coachDao().getLog("2030-01-03")?.logJson)

        runCatching {
            db.withTransaction {
                db.coachDao().upsertLog(CoachLogEntity("2030-01-04", """{"ex_2":{}}""", false, 0))
                // Same id as above: the insert aborts, and the day must not
                // survive an event log that has no record of the toggle.
                events.insert(event("e1", at = 2_000))
            }
        }

        assertNull(db.coachDao().getLog("2030-01-04"))
        assertEquals(1, events.countPending())
    }
}
