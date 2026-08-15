package dev.jtiisto.wellness.core.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v2 → v3 upgrade, against the committed schemas.
 *
 * `runMigrationsAndValidate` is the real assertion: it compares the migrated
 * database to the exported `3.json`, so a hand-written `CREATE TABLE` that has
 * drifted from the entities fails here rather than at the user's next launch.
 *
 * The pre-existing rows are checked with the journal tables **populated and
 * dirty**, because that is the case destructive migration would destroy: a
 * dirty row is the only copy of an edit that has not reached the server.
 */
@RunWith(AndroidJUnit4::class)
class Migration2to3Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WellnessDatabase::class.java,
    )

    @Test
    fun migratesAndKeepsPopulatedJournalAndDebugRows() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL("INSERT INTO debug_log (ts, tag, message) VALUES (1, 'phase1', 'kept')")
            db.execSQL(
                "INSERT INTO payload_cache (module, key, payloadJson, fetchedAt) " +
                    "VALUES ('trends', 'overview', '{\"a\":1}', 7)",
            )
            db.execSQL(
                "INSERT INTO journal_trackers " +
                    "(id, name, category, type, deleted, lastModifiedAt, dataJson, isDirty, dirtyGeneration) " +
                    "VALUES ('t1', 'Water', 'Habits', 'simple', 0, 's1', '{\"id\":\"t1\"}', 1, 4)",
            )
            db.execSQL(
                "INSERT INTO journal_entries " +
                    "(date, trackerId, valueJson, completed, lastModifiedAt, isDirty, dirtyGeneration) " +
                    "VALUES ('2026-08-06', 't1', '3', 1, 's1', 1, 2)",
            )
            db.execSQL("INSERT INTO journal_meta (key, value) VALUES ('lastServerSyncTime', 's1')")
        }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT message FROM debug_log").use { cursor ->
                cursor.moveToFirst()
                assertEquals("kept", cursor.getString(0))
            }
            db.query("SELECT payloadJson FROM payload_cache").use { cursor ->
                cursor.moveToFirst()
                assertEquals("""{"a":1}""", cursor.getString(0))
            }
            // The dirty flags and their generation counters are the part that
            // matters: losing either silently drops an unsent edit.
            db.query("SELECT isDirty, dirtyGeneration FROM journal_trackers WHERE id = 't1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(4, cursor.getInt(1))
            }
            db.query("SELECT isDirty, dirtyGeneration FROM journal_entries WHERE trackerId = 't1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(2, cursor.getInt(1))
            }
            db.query("SELECT value FROM journal_meta WHERE key = 'lastServerSyncTime'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("s1", cursor.getString(0))
            }
            // The new tables exist and are empty.
            for (table in listOf("coach_plans", "coach_logs", "coach_meta")) {
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("$table should be empty", 0, cursor.getInt(0))
                }
            }
        }
    }

    /**
     * The whole chain an install from Phase 1 actually walks. Running only the
     * latest hop would miss a v1 → v2 statement that stopped composing with
     * what came after it.
     */
    @Test
    fun migratesTheWholeV1ToV3Chain() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO debug_log (ts, tag, message) VALUES (1, 'phase1', 'from v1')")
            db.execSQL(
                "INSERT INTO payload_cache (module, key, payloadJson, fetchedAt) " +
                    "VALUES ('analysis', 'latest', '{\"b\":2}', 9)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, *WELLNESS_MIGRATIONS).use { db ->
            db.query("SELECT message FROM debug_log").use { cursor ->
                cursor.moveToFirst()
                assertEquals("from v1", cursor.getString(0))
            }
            db.query("SELECT payloadJson FROM payload_cache").use { cursor ->
                cursor.moveToFirst()
                assertEquals("""{"b":2}""", cursor.getString(0))
            }
        }
    }

    @Test
    fun theMigratedDatabaseServesTheCoachDao() {
        helper.createDatabase(TEST_DB, 2).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            WellnessDatabase::class.java,
            TEST_DB,
        ).addMigrations(*WELLNESS_MIGRATIONS).build()
        helper.closeWhenFinished(db)

        runBlocking {
            val dao = db.coachDao()
            dao.upsertLog(
                CoachLogEntity(
                    date = "2026-08-06",
                    logJson = """{"session_feedback":{}}""",
                    isDirty = false,
                    dirtyGeneration = 0,
                ),
            )
            dao.markLogDirty("2026-08-06")

            assertEquals(listOf(CoachDateGeneration("2026-08-06", 1)), dao.snapshotDirtyLogs())
            assertEquals("c1", dao.getOrCreateClientId("c1"))

            dao.upsertPlan(
                CoachPlanEntity("2026-08-06", """{"session_id":1,"_lastModified":"p1"}""", "p1"),
            )
            assertTrue(dao.listAllPlans().isNotEmpty())
            // The journal DAO still works off the same upgraded database.
            assertEquals("c1-journal", db.journalDao().getOrCreateClientId("c1-journal"))
        }
    }

    private companion object {
        const val TEST_DB = "coach-migration-test.db"
    }
}
