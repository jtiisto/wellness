package dev.jtiisto.wellness.core.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SQL half of the coach dirty machinery, against the real Room schema. The
 * pure twins are pinned by JVM tests; this suite proves the two do not drift,
 * and that the two composed transactions really are all-or-nothing.
 *
 * Runs on the emulator (`/adb-*` sessions), never in git hooks.
 */
@RunWith(AndroidJUnit4::class)
class CoachDaoTest {

    private lateinit var db: WellnessDatabase
    private lateinit var dao: CoachDao

    private fun log(
        date: String,
        json: String = """{"session_feedback":{}}""",
        isDirty: Boolean = false,
        generation: Long = 0,
    ) = CoachLogEntity(date = date, logJson = json, isDirty = isDirty, dirtyGeneration = generation)

    private fun plan(date: String, stamp: String? = "p1") = CoachPlanEntity(
        date = date,
        planJson = """{"session_id":1,"_lastModified":"$stamp"}""",
        lastModified = stamp,
    )

    @Before
    fun openDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WellnessDatabase::class.java,
        ).build()
        dao = db.coachDao()
    }

    @After
    fun closeDb() = db.close()

    /**
     * Make one specific statement fail, so a transaction can be caught
     * half-applied.
     *
     * A SQLite trigger rather than a fake DAO, because the point is to test the
     * *generated* `@Transaction` against the real schema. It is deliberately
     * attached to a plain `@Query` DELETE/UPDATE and never to an `@Upsert`:
     * Room's upsert catches `SQLiteConstraintException` to run its
     * insert-then-update fallback, so an abort raised there would be swallowed
     * instead of unwinding the transaction.
     */
    private fun abortOn(triggerCondition: String) {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER abort_probe $triggerCondition " +
                "BEGIN SELECT RAISE(ABORT, 'abort_probe'); END",
        )
    }

    private fun jsonOf(text: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject

    // ---- dirty machinery -------------------------------------------------

    @Test
    fun markBumpsGenerationAndSnapshotReportsIt() = runBlocking {
        dao.upsertLog(log("2026-08-06"))

        dao.markLogDirty("2026-08-06")
        dao.markLogDirty("2026-08-06")

        assertEquals(listOf(CoachDateGeneration("2026-08-06", 2)), dao.snapshotDirtyLogs())
        assertEquals(1, dao.countDirty())
    }

    @Test
    fun clearOnlyLandsWhileTheGenerationIsUnchanged() = runBlocking {
        dao.upsertLog(log("2026-08-06"))
        dao.markLogDirty("2026-08-06")

        // A mid-sync edit bumps past the snapshot, so the clear must miss.
        dao.markLogDirty("2026-08-06")
        dao.clearLogDirty("2026-08-06", generation = 1)
        assertTrue(dao.getLog("2026-08-06")!!.isDirty)

        dao.clearLogDirty("2026-08-06", generation = 2)
        assertFalse(dao.getLog("2026-08-06")!!.isDirty)
    }

    @Test
    fun updateLogAndMarkDirtyCreatesTheDayAndPreservesTheCounter() = runBlocking {
        val created = dao.updateLogAndMarkDirty("2026-08-06") { stored ->
            assertNull(stored)
            CoachLogEdit("""{"ex_1":{"reps":5}}""")
        }

        assertTrue(created)
        assertEquals(1L, dao.getLog("2026-08-06")!!.dirtyGeneration)

        // A caller cannot reset the counter the mid-sync check depends on.
        dao.updateLogAndMarkDirty("2026-08-06") { CoachLogEdit("""{"ex_1":{"reps":6}}""") }
        assertEquals(2L, dao.getLog("2026-08-06")!!.dirtyGeneration)
    }

    @Test
    fun updateLogAndMarkDirtyReturningNullWritesNothing() = runBlocking {
        dao.upsertLog(log("2026-08-06", """{"ex_1":{"reps":5}}"""))

        val changed = dao.updateLogAndMarkDirty("2026-08-06") { null }

        assertFalse(changed)
        val row = dao.getLog("2026-08-06")!!
        assertFalse(row.isDirty)
        assertEquals(0L, row.dirtyGeneration)
        assertEquals("""{"ex_1":{"reps":5}}""", row.logJson)
    }

    @Test
    fun getOrCreateClientIdIsRaceSafe() = runBlocking {
        val ids = listOf("a", "b", "c", "d")
            .map { candidate -> async { dao.getOrCreateClientId(candidate) } }
            .awaitAll()

        assertEquals("concurrent first access minted more than one id: $ids", 1, ids.toSet().size)
        assertEquals(ids.first(), dao.getMeta(CoachDao.KEY_CLIENT_ID))
    }

    // ---- applyUploadResults ---------------------------------------------

    @Test
    fun applyUploadResultsAdoptsAndClearsGuarded() = runBlocking {
        dao.upsertLog(log("2026-08-05", """{"a":1}""", isDirty = true, generation = 1))
        dao.upsertLog(log("2026-08-06", """{"b":1}""", isDirty = true, generation = 1))
        // 08-06 was edited after the payload was built.
        dao.markLogDirty("2026-08-06")

        dao.applyUploadResults(
            dates = listOf("2026-08-05", "2026-08-06"),
            clears = listOf(CoachDirtyClear("2026-08-05", 1), CoachDirtyClear("2026-08-06", 1)),
            adopt = { rows -> rows.map { it.copy(logJson = """{"adopted":"${it.date}"}""") } },
        )

        assertEquals("""{"adopted":"2026-08-05"}""", dao.getLog("2026-08-05")!!.logJson)
        assertFalse(dao.getLog("2026-08-05")!!.isDirty)
        // The guarded clear misses the re-modified row; the write still lands,
        // so the next upload echoes a fresh base rather than a stale one.
        assertTrue(dao.getLog("2026-08-06")!!.isDirty)
        assertEquals(2L, dao.getLog("2026-08-06")!!.dirtyGeneration)
    }

    @Test
    fun applyUploadResultsWritesNothingWhenTheAdoptionItselfThrows() = runBlocking {
        dao.upsertLog(log("2026-08-05", """{"a":1}""", isDirty = true, generation = 1))

        // A malformed stored blob blows the decode up while the adoption is
        // still being computed — before any row has been touched. Weak by
        // construction (there is nothing to roll back yet); the real
        // transaction test is the one below.
        val failed = runCatching {
            dao.applyUploadResults(
                dates = listOf("2026-08-05"),
                clears = listOf(CoachDirtyClear("2026-08-05", 1)),
                adopt = { error("malformed stored day") },
            )
        }

        assertTrue(failed.isFailure)
        assertEquals("""{"a":1}""", dao.getLog("2026-08-05")!!.logJson)
        assertTrue(dao.getLog("2026-08-05")!!.isDirty)
    }

    @Test
    fun applyUploadResultsRollsBackTheAdoptionWhenAClearFails() = runBlocking {
        dao.upsertLog(log("2026-08-05", """{"a":1}""", isDirty = true, generation = 1))
        dao.upsertLog(log("2026-08-06", """{"b":1}""", isDirty = true, generation = 1))
        // Fail the dirty CLEAR specifically, which runs after every adoption
        // has already been written. The condition matches only the clear
        // (dirty -> clean); the adoption upserts leave isDirty untouched.
        abortOn("BEFORE UPDATE ON coach_logs WHEN OLD.isDirty = 1 AND NEW.isDirty = 0")

        val failed = runCatching {
            dao.applyUploadResults(
                dates = listOf("2026-08-05", "2026-08-06"),
                clears = listOf(CoachDirtyClear("2026-08-05", 1), CoachDirtyClear("2026-08-06", 1)),
                adopt = { rows -> rows.map { it.copy(logJson = """{"adopted":"${it.date}"}""") } },
            )
        }

        assertTrue(failed.isFailure)
        // Both adoptions had landed before the clear blew up. If the
        // transaction did not unwind them, the days would carry fresh server
        // tokens while still being dirty — and the next upload would echo a
        // base the server has already consumed.
        assertEquals("""{"a":1}""", dao.getLog("2026-08-05")!!.logJson)
        assertEquals("""{"b":1}""", dao.getLog("2026-08-06")!!.logJson)
        assertTrue(dao.getLog("2026-08-05")!!.isDirty)
        assertTrue(dao.getLog("2026-08-06")!!.isDirty)
    }

    // ---- applyDownload ---------------------------------------------------

    @Test
    fun applyDownloadOverwritesPlansMergesCleanLogsAndPrunes() = runBlocking {
        dao.upsertPlan(plan("2026-05-01"))
        dao.upsertPlan(plan("2026-08-01", stamp = "old"))
        dao.upsertLog(log("2026-05-01"))
        dao.upsertLog(log("2026-08-06", """{"local":true}""", isDirty = true, generation = 3))
        dao.upsertLog(log("2026-08-05", """{"local":true}"""))

        dao.applyDownload(
            plans = listOf(plan("2026-08-01", stamp = "new")),
            deletedPlanDates = listOf("2026-05-01"),
            logs = listOf(
                log("2026-08-06", """{"server":true}"""),
                log("2026-08-05", """{"server":true}"""),
            ),
            earliestDate = "2026-06-08",
            watermark = "s-pull",
        )

        // Plans: server-authoritative, tombstoned dates removed, window pruned.
        assertEquals(setOf("2026-08-01"), dao.listAllPlans().map { it.date }.toSet())
        assertEquals("new", dao.getPlan("2026-08-01")!!.lastModified)
        // Logs: the dirty day keeps its local content AND its generation.
        assertEquals("""{"local":true}""", dao.getLog("2026-08-06")!!.logJson)
        assertEquals(3L, dao.getLog("2026-08-06")!!.dirtyGeneration)
        assertEquals("""{"server":true}""", dao.getLog("2026-08-05")!!.logJson)
        assertNull(dao.getLog("2026-05-01"))
        assertEquals("s-pull", dao.getMeta(CoachDao.KEY_LAST_SERVER_SYNC_TIME))
        assertEquals("2026-06-08", dao.getMeta(CoachDao.KEY_EARLIEST_DATE))
    }

    @Test
    fun applyDownloadRollsBackWhollyWhenItFails() = runBlocking {
        dao.upsertLog(log("2026-05-01", """{"stale":true}"""))
        dao.upsertLog(log("2026-08-05", """{"before":true}"""))

        // Fail the window prune, the LAST step: by then the plan, the merged
        // logs, the window start and the watermark have all been written, so
        // this is the widest possible rollback the method has to unwind. Its
        // own @Transaction is what is under test — nothing wraps the call.
        abortOn("BEFORE DELETE ON coach_logs WHEN OLD.date = '2026-05-01'")

        val failed = runCatching {
            dao.applyDownload(
                plans = listOf(plan("2026-08-06")),
                deletedPlanDates = emptyList(),
                logs = listOf(log("2026-08-05", """{"after":true}""")),
                earliestDate = "2026-06-08",
                watermark = "s-pull",
            )
        }

        assertTrue(failed.isFailure)
        assertNull("the plan write survived a failed pull", dao.getPlan("2026-08-06"))
        assertEquals("""{"before":true}""", dao.getLog("2026-08-05")!!.logJson)
        // The watermark above all: advancing it for a pull that never applied
        // would make the next incremental sync skip those changes for good.
        assertNull(dao.getMeta(CoachDao.KEY_LAST_SERVER_SYNC_TIME))
        assertNull(dao.getMeta(CoachDao.KEY_EARLIEST_DATE))
    }

    // ---- the atomic upload snapshot --------------------------------------

    @Test
    fun buildPendingUploadReadsTheDirtySetExactlyOnce() = runBlocking {
        dao.upsertLog(log("2026-08-05", """{"a":1}"""))
        dao.upsertLog(log("2026-08-06", """{"b":1}"""))
        dao.markLogDirty("2026-08-05")
        dao.markLogDirty("2026-08-06")
        dao.markLogDirty("2026-08-06")
        dao.upsertLog(log("2030-01-04", """{"clean":1}"""))

        var reads = 0
        val snapshot = dao.buildPendingUpload { rows ->
            reads++
            PendingUpload(
                generations = rows.associate { it.date to it.dirtyGeneration },
                payload = rows.associate { it.date to jsonOf(it.logJson) },
            )
        }

        // ONE read is the whole point: a JSON read and a generation read taken
        // at different moments could pair an old blob with a new generation,
        // and the adoption would then overwrite a fresh edit.
        assertEquals(1, reads)
        assertEquals(setOf("2026-08-05", "2026-08-06"), snapshot.generations.keys)
        assertEquals(1L, snapshot.generations.getValue("2026-08-05"))
        assertEquals(2L, snapshot.generations.getValue("2026-08-06"))
        assertEquals("""{"a":1}""", snapshot.payload.getValue("2026-08-05").toString())

        // And what it returned is a value snapshot, not a live view: a write
        // afterwards cannot retroactively skew the generation the adoption
        // guard will compare against.
        dao.markLogDirty("2026-08-05")
        assertEquals(1L, snapshot.generations.getValue("2026-08-05"))
        assertEquals(2L, dao.getLog("2026-08-05")!!.dirtyGeneration)
    }

    // ---- observation -----------------------------------------------------

    @Test
    fun observersSeeWritesAndAbsence() = runBlocking {
        assertNull(dao.observePlan("2026-08-06").first())
        assertNull(dao.observeLog("2026-08-06").first())

        dao.upsertPlan(plan("2026-08-06"))
        dao.upsertLog(log("2026-08-06", """{"ex_1":{"reps":5}}""", isDirty = true, generation = 1))

        assertEquals("p1", dao.observePlan("2026-08-06").first()!!.lastModified)
        assertEquals("""{"ex_1":{"reps":5}}""", dao.observeLog("2026-08-06").first()!!.logJson)
        assertEquals(1, dao.observeDirtyCount().first())
    }

    // ---- the completion-event dual-write ---------------------------------

    @Test
    fun aCompletionEventLandsWithTheBlobThatEarnedIt() = runBlocking {
        val event = setEvent("event-1")

        val changed = dao.updateLogAndMarkDirty("2026-08-06") {
            CoachLogEdit("""{"ex_1":{"sets":[{"set_num":1,"completed":true}]}}""", event)
        }

        assertTrue(changed)
        assertEquals(listOf(event), db.setEventDao().listAll())
        assertTrue(dao.getLog("2026-08-06")!!.isDirty)
    }

    @Test
    fun aFailedEventInsertTakesTheBlobWriteDownWithIt() = runBlocking {
        dao.upsertLog(log("2026-08-06", """{"ex_1":{"sets":[]}}"""))
        db.setEventDao().insert(setEvent("event-1"))

        // A reused id is the one way this insert can fail, and the point is
        // what it does to the write beside it: an event that describes a
        // mutation must never outlive the mutation, and a mutation must never
        // land claiming an event that was refused.
        runCatching {
            dao.updateLogAndMarkDirty("2026-08-06") {
                CoachLogEdit("""{"ex_1":{"sets":[{"set_num":1,"completed":true}]}}""", setEvent("event-1"))
            }
        }

        val row = dao.getLog("2026-08-06")!!
        assertEquals("""{"ex_1":{"sets":[]}}""", row.logJson)
        assertFalse(row.isDirty)
        assertEquals(1, db.setEventDao().listAll().size)
    }

    private fun setEvent(eventId: String) = SetEventEntity(
        eventId = eventId,
        date = "2026-08-06",
        exerciseKey = "ex_1",
        setNum = 1,
        action = SetEventEntity.ACTION_CHECK,
        clientTimestampMs = 1_770_000_000_000L,
    )

    @Test
    fun theStoredDaySurvivesArbitraryKeysVerbatim() = runBlocking {
        // The wire unit is the day: arbitrary exercise keys, `_`-meta and all.
        // Anything that normalized it could not upload it back unchanged.
        val day = """{"session_feedback":{"general_notes":"n"},"extra_zone2":{"duration_min":30},""" +
            """"weird key/with.chars":{"sets":[]},"_lastModifiedAt":"t","_lastModified":"s"}"""
        dao.upsertLog(log("2026-08-06", day))

        assertEquals(day, dao.getLog("2026-08-06")!!.logJson)
        assertEquals(
            setOf("session_feedback", "extra_zone2", "weird key/with.chars", "_lastModifiedAt", "_lastModified"),
            (kotlinx.serialization.json.Json.parseToJsonElement(day) as JsonObject).keys,
        )
    }
}
