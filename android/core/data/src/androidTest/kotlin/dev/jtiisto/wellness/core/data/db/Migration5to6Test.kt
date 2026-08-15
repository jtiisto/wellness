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
 * The v5 → v6 upgrade — the heart-rate tables — against the committed schemas.
 *
 * `runMigrationsAndValidate` compares the migrated database to the exported
 * `6.json`, and that comparison covers the two indices as well as the columns:
 * a hand-written `CREATE INDEX` that has drifted from the entity fails here
 * rather than as a slow uploader six months from now.
 *
 * Runs on the emulator (`/adb-*` sessions), never in git hooks.
 */
@RunWith(AndroidJUnit4::class)
class Migration5to6Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WellnessDatabase::class.java,
    )

    @Test
    fun migratesAndKeepsEveryDirtyRow() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL("INSERT INTO debug_log (ts, tag, message) VALUES (1, 'phase1', 'kept')")
            db.execSQL(
                "INSERT INTO journal_trackers " +
                    "(id, name, category, type, deleted, lastModifiedAt, dataJson, isDirty, dirtyGeneration) " +
                    "VALUES ('t1', 'Water', 'Habits', 'simple', 0, 's1', '{\"id\":\"t1\"}', 1, 4)",
            )
            db.execSQL(
                "INSERT INTO coach_logs (date, logJson, isDirty, dirtyGeneration) " +
                    "VALUES ('2026-08-06', '{\"session_feedback\":{}}', 1, 3)",
            )
            db.execSQL(
                "INSERT INTO server_profiles (nickname, url, isActive) " +
                    "VALUES ('Laptop', 'https://laptop/wellness', 1)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6).use { db ->
            db.query("SELECT isDirty, dirtyGeneration FROM journal_trackers WHERE id = 't1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(4, cursor.getInt(1))
            }
            db.query("SELECT isDirty, dirtyGeneration FROM coach_logs WHERE date = '2026-08-06'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(3, cursor.getInt(1))
            }
            // The server the device is pointed at has to survive too: an upgrade
            // that reset it would upload the kept dirty rows somewhere else.
            db.query("SELECT nickname FROM server_profiles WHERE isActive = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Laptop", cursor.getString(0))
            }
            for (table in listOf("hr_sessions", "hr_samples", "set_events")) {
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("$table should exist and be empty", 0, cursor.getInt(0))
                }
            }

            // Column by column, because all three tables carry the same two
            // upload flags and a migration that dropped one from a single table
            // would still validate every other assertion in this file.
            assertEquals(
                listOf(
                    "sessionId", "deviceId", "startedAtMs", "endedAtMs",
                    "workoutDate", "workoutSessionId", "isSynced", "isQuarantined",
                    "dirtyGeneration",
                ),
                db.columnsOf("hr_sessions"),
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
     * The composite key is the whole idempotency story — the server's
     * `INSERT OR IGNORE` keys on the same three columns — so it is asserted in
     * SQL rather than trusted to the entity declaration.
     */
    @Test
    fun sampleKeyIsDeviceTimestampAndSeq() {
        helper.createDatabase(TEST_DB, 5).close()

        helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6).use { db ->
            val insert = "INSERT OR IGNORE INTO hr_samples " +
                "(deviceId, timestampMs, seq, heartRateBpm, rrIntervalMs, isGapBefore, sessionId, " +
                "isSynced, isQuarantined) VALUES "
            db.execSQL("$insert ('dev', 1000, 0, 142, 423, 0, 's1', 0, 0)")
            // Same millisecond, next beat: a new row, not a shifted timestamp.
            db.execSQL("$insert ('dev', 1000, 1, 142, 0, 0, 's1', 0, 0)")
            // The re-delivered notification: ignored, and the stored bpm stands.
            db.execSQL("$insert ('dev', 1000, 0, 199, 999, 0, 's1', 0, 0)")

            db.query("SELECT COUNT(*) FROM hr_samples").use { cursor ->
                cursor.moveToFirst()
                assertEquals(2, cursor.getInt(0))
            }
            db.query("SELECT heartRateBpm FROM hr_samples WHERE seq = 0").use { cursor ->
                cursor.moveToFirst()
                assertEquals(142, cursor.getInt(0))
            }
        }
    }

    /**
     * The whole chain an install from Phase 1 actually walks. Running only the
     * latest hop would miss an earlier statement that stopped composing with
     * what came after it.
     */
    @Test
    fun migratesTheWholeV1ToV6Chain() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO debug_log (ts, tag, message) VALUES (1, 'phase1', 'from v1')")
            db.execSQL(
                "INSERT INTO payload_cache (module, key, payloadJson, fetchedAt) " +
                    "VALUES ('analysis', 'latest', '{\"b\":2}', 9)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 6, true, *WELLNESS_MIGRATIONS).use { db ->
            db.query("SELECT message FROM debug_log").use { cursor ->
                cursor.moveToFirst()
                assertEquals("from v1", cursor.getString(0))
            }
            val tables = listOf(
                "journal_trackers", "coach_plans", "trends_meta", "server_profiles",
                "hr_sessions", "hr_samples", "set_events",
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
    fun theMigratedDatabaseServesTheHeartRateDaos() {
        helper.createDatabase(TEST_DB, 5).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            WellnessDatabase::class.java,
            TEST_DB,
        ).addMigrations(*WELLNESS_MIGRATIONS).build()
        helper.closeWhenFinished(db)

        runBlocking {
            db.hrSessionDao().upsert(
                HrSessionEntity(sessionId = "s1", deviceId = "dev", startedAtMs = 1_000),
            )
            db.hrSampleDao().insertAll(
                listOf(
                    HrSampleEntity(
                        deviceId = "dev", timestampMs = 1_000, seq = 0,
                        heartRateBpm = 142, rrIntervalMs = 423, sessionId = "s1",
                    ),
                ),
            )
            db.setEventDao().insert(
                SetEventEntity(
                    eventId = "e1", date = "2030-01-03", exerciseKey = "fixture-adhoc-lift",
                    setNum = 1, action = SetEventEntity.ACTION_CHECK, clientTimestampMs = 1_500,
                ),
            )

            assertEquals(listOf("s1"), db.hrSessionDao().needsUpload().map { it.sessionId })
            assertEquals(1, db.hrSampleDao().countPending())
            assertEquals(1, db.setEventDao().countPending())

            // Quarantine takes the session out of the upload, and a content
            // change puts it back — the round trip the column exists for.
            db.hrSessionDao().markQuarantined("s1", generation = 1)
            assertTrue(db.hrSessionDao().needsUpload().isEmpty())
            db.hrSessionDao().upsert(
                HrSessionEntity(
                    sessionId = "s1", deviceId = "dev", startedAtMs = 1_000,
                    workoutDate = "2030-01-03",
                ),
            )
            assertEquals(listOf("s1"), db.hrSessionDao().needsUpload().map { it.sessionId })
        }
    }

    /**
     * The wipe list is enumerated by table name, so a table added to the schema
     * and forgotten here fails silently and forever. This is the only assertion
     * that catches it against the real database.
     */
    @Test
    fun theSwitchTransactionClearsTheHeartRateTablesToo() {
        helper.createDatabase(TEST_DB, 5).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            WellnessDatabase::class.java,
            TEST_DB,
        ).addMigrations(*WELLNESS_MIGRATIONS).build()
        helper.closeWhenFinished(db)

        runBlocking {
            val target = db.serverProfilesDao()
                .insert(ServerProfileEntity(nickname = "Laptop", url = "https://laptop/wellness"))
            db.hrSessionDao().upsert(
                HrSessionEntity(sessionId = "s1", deviceId = "dev", startedAtMs = 1_000),
            )
            db.hrSampleDao().insertAll(
                listOf(
                    HrSampleEntity(
                        deviceId = "dev", timestampMs = 1_000, seq = 0,
                        heartRateBpm = 142, rrIntervalMs = 423, sessionId = "s1",
                    ),
                ),
            )
            db.setEventDao().insert(
                SetEventEntity(
                    eventId = "e1", date = "2030-01-03", exerciseKey = "fixture-adhoc-lift",
                    setNum = 1, action = SetEventEntity.ACTION_CHECK, clientTimestampMs = 1_500,
                ),
            )

            db.serverSwitchDao().switchTo(
                targetId = target,
                boundary = DebugLogEntity(ts = 2, tag = "server", message = "server switch: Built-in → Laptop"),
            )

            // Unsynced telemetry is the only copy that exists anywhere, and it
            // still goes: it was destined for the server being left.
            assertTrue(db.hrSessionDao().listAll().isEmpty())
            assertTrue(db.hrSampleDao().listAll().isEmpty())
            assertTrue(db.setEventDao().listAll().isEmpty())
            assertNull(db.hrSessionDao().newestOpen())
        }
    }

    private companion object {
        const val TEST_DB = "hr-migration-test.db"
    }
}
