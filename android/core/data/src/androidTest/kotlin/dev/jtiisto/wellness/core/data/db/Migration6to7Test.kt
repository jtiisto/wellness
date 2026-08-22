package dev.jtiisto.wellness.core.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v6 → v7 upgrade — the cardio guide's action log — against the committed
 * schemas.
 *
 * `runMigrationsAndValidate` compares the migrated database to the exported
 * `7.json`, index included: a hand-written `CREATE INDEX` that has drifted from
 * the entity fails here rather than as a slow uploader six months from now.
 *
 * Runs on the emulator (`/adb-*` sessions), never in git hooks.
 */
@RunWith(AndroidJUnit4::class)
class Migration6to7Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WellnessDatabase::class.java,
    )

    @Test
    fun migratesAndKeepsEveryUnsentRow() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO journal_trackers " +
                    "(id, name, category, type, deleted, lastModifiedAt, dataJson, isDirty, dirtyGeneration) " +
                    "VALUES ('t1', 'Water', 'Habits', 'simple', 0, 's1', '{\"id\":\"t1\"}', 1, 4)",
            )
            // Telemetry that has not uploaded is the only copy that exists; the
            // rule against destructive migration bites hardest on these rows.
            db.execSQL(
                "INSERT INTO hr_samples " +
                    "(deviceId, timestampMs, seq, heartRateBpm, rrIntervalMs, isGapBefore, sessionId, " +
                    "isSynced, isQuarantined) VALUES ('dev', 1000, 0, 142, 423, 0, 's1', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO set_events " +
                    "(eventId, date, exerciseKey, setNum, itemKey, action, clientTimestampMs, sessionId, " +
                    "isSynced, isQuarantined) " +
                    "VALUES ('e1', '2030-01-03', 'fixture-adhoc-lift', 1, NULL, 'check', 1500, 's1', 0, 0)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7).use { db ->
            db.query("SELECT isDirty, dirtyGeneration FROM journal_trackers WHERE id = 't1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(4, cursor.getInt(1))
            }
            for (table in listOf("hr_samples", "set_events")) {
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("$table should have kept its row", 1, cursor.getInt(0))
                }
            }
            db.query("SELECT COUNT(*) FROM guide_events").use { cursor ->
                cursor.moveToFirst()
                assertEquals("guide_events should exist and be empty", 0, cursor.getInt(0))
            }

            // Column by column: the two payload columns are what tell the two
            // actions apart, and a migration that dropped one would still pass
            // every other assertion in this file.
            assertEquals(
                listOf(
                    "eventId", "date", "exerciseKey", "action", "clientTimestampMs",
                    "sessionId", "extensionSec", "timelineJson", "isSynced", "isQuarantined",
                ),
                db.columnsOf("guide_events"),
            )
        }
    }

    private fun SupportSQLiteDatabase.columnsOf(table: String): List<String> =
        query("PRAGMA table_info($table)").use { cursor ->
            buildList {
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
        }

    /**
     * `sessionId` is NOT NULL here and nullable on `set_events`, deliberately: a
     * guide action is recorded only while a capture is running, so a row without
     * one is a row that should never have been written.
     */
    @Test
    fun sessionIsRequiredOnAGuideEvent() {
        helper.createDatabase(TEST_DB, 6).close()

        helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7).use { db ->
            var rejected = false
            try {
                db.execSQL(
                    "INSERT INTO guide_events " +
                        "(eventId, date, exerciseKey, action, clientTimestampMs, sessionId, " +
                        "isSynced, isQuarantined) " +
                        "VALUES ('g-null', '2030-01-03', 'ex_ride', 'start', 1000, NULL, 0, 0)",
                )
            } catch (expected: android.database.sqlite.SQLiteConstraintException) {
                rejected = true
            }
            assertTrue("a guide event without a session must not be storable", rejected)
        }
    }

    /**
     * The whole chain an install from Phase 1 actually walks. Running only the
     * latest hop would miss an earlier statement that stopped composing with
     * what came after it.
     */
    @Test
    fun migratesTheWholeV1ToV7Chain() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO debug_log (ts, tag, message) VALUES (1, 'phase1', 'from v1')")
        }

        helper.runMigrationsAndValidate(TEST_DB, 7, true, *WELLNESS_MIGRATIONS).use { db ->
            db.query("SELECT message FROM debug_log").use { cursor ->
                cursor.moveToFirst()
                assertEquals("from v1", cursor.getString(0))
            }
            val tables = listOf(
                "journal_trackers", "coach_plans", "trends_meta", "server_profiles",
                "hr_sessions", "hr_samples", "set_events", "guide_events",
            )
            for (table in tables) {
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("$table should exist and be empty", 0, cursor.getInt(0))
                }
            }
        }
    }

    @Test
    fun theMigratedDatabaseServesTheGuideEventDao() {
        helper.createDatabase(TEST_DB, 6).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            WellnessDatabase::class.java,
            TEST_DB,
        ).addMigrations(*WELLNESS_MIGRATIONS).build()
        helper.closeWhenFinished(db)

        runBlocking {
            val dao = db.guideEventDao()
            dao.insert(
                GuideEventEntity(
                    eventId = "g-start", date = "2030-01-03", exerciseKey = "ex_ride",
                    action = GuideEventEntity.ACTION_START, clientTimestampMs = 1_000,
                    sessionId = "s1", timelineJson = """[{"duration_sec":1800,"hr_min":122}]""",
                ),
            )
            dao.insert(
                GuideEventEntity(
                    eventId = "g-extend", date = "2030-01-03", exerciseKey = "ex_ride",
                    action = GuideEventEntity.ACTION_EXTEND, clientTimestampMs = 2_000,
                    sessionId = "s1", extensionSec = 300,
                ),
            )

            assertEquals(2, dao.countPending())
            assertEquals(listOf("g-start", "g-extend"), dao.pendingUpload(10).map { it.eventId })
            // Round trip: each action carries its own payload and neither gains
            // the other's on the way through the real schema.
            val stored = dao.listAll().associateBy { it.eventId }
            assertTrue(stored.getValue("g-start").timelineJson!!.contains("duration_sec"))
            assertNull(stored.getValue("g-start").extensionSec)
            assertEquals(300, stored.getValue("g-extend").extensionSec)
            assertNull(stored.getValue("g-extend").timelineJson)

            dao.markSynced(listOf("g-start"))
            dao.markQuarantined(listOf("g-extend"))
            assertEquals(0, dao.countPending())
            assertEquals(1, dao.countQuarantined())
            // Synced only, and the cutoff is exclusive.
            assertEquals(0, dao.pruneSynced(cutoffMs = 1_000))
            assertEquals(1, dao.pruneSynced(cutoffMs = 1_001))
        }
    }

    /**
     * The wipe list is enumerated by table name, so a table added to the schema
     * and forgotten there fails silently and forever. This is the only assertion
     * that catches it against the real database.
     */
    @Test
    fun theSwitchTransactionClearsTheGuideEventsToo() {
        helper.createDatabase(TEST_DB, 6).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            WellnessDatabase::class.java,
            TEST_DB,
        ).addMigrations(*WELLNESS_MIGRATIONS).build()
        helper.closeWhenFinished(db)

        runBlocking {
            val target = db.serverProfilesDao()
                .insert(ServerProfileEntity(nickname = "Laptop", url = "https://laptop/wellness"))
            db.guideEventDao().insert(
                GuideEventEntity(
                    eventId = "g-start", date = "2030-01-03", exerciseKey = "ex_ride",
                    action = GuideEventEntity.ACTION_START, clientTimestampMs = 1_000,
                    sessionId = "s1", timelineJson = "[]",
                ),
            )

            db.serverSwitchDao().switchTo(
                targetId = target,
                boundary = DebugLogEntity(ts = 2, tag = "server", message = "server switch: Built-in → Laptop"),
            )

            // The row names a session only the server being left has ever heard
            // of, so it goes with the rest of that server's telemetry.
            assertTrue(db.guideEventDao().listAll().isEmpty())
        }
    }

    private companion object {
        const val TEST_DB = "guide-migration-test.db"
    }
}
