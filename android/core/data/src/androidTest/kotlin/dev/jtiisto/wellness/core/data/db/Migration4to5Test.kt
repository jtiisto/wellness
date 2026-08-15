package dev.jtiisto.wellness.core.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
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
 * The v4 → v5 upgrade — the server address book — against the committed schemas.
 *
 * `runMigrationsAndValidate` compares the migrated database to the exported
 * `5.json`, so a hand-written `CREATE TABLE` that has drifted from the entity
 * fails here rather than at the user's next launch. The `DEFAULT 0` on
 * `isActive` is part of that comparison and is load-bearing: it is what makes an
 * upgraded install arrive with *no* active row, which resolves to the built-in
 * server the device has been using all along.
 */
@RunWith(AndroidJUnit4::class)
class Migration4to5Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WellnessDatabase::class.java,
    )

    @Test
    fun migratesAndKeepsEveryDirtyRow() {
        helper.createDatabase(TEST_DB, 4).use { db ->
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
            db.execSQL("INSERT INTO journal_meta (key, value) VALUES ('lastServerSyncTime', 'j1')")
            db.execSQL("INSERT INTO trends_meta (key, value) VALUES ('ui.range', '12w')")
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5).use { db ->
            // The unsent edits are the whole reason destructive migration is
            // never enabled; the address book is worth nothing beside them.
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
            db.query("SELECT value FROM journal_meta WHERE key = 'lastServerSyncTime'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("j1", cursor.getString(0))
            }
            db.query("SELECT value FROM trends_meta WHERE key = 'ui.range'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("12w", cursor.getString(0))
            }
            // Empty, which is exactly "no active profile" — the built-in server.
            db.query("SELECT COUNT(*) FROM server_profiles").use { cursor ->
                cursor.moveToFirst()
                assertEquals("an upgrade must not invent a server", 0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun isActiveDefaultsToZero() {
        helper.createDatabase(TEST_DB, 4).close()

        helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5).use { db ->
            // The default is what an insert that omits the column relies on, and
            // an accidental DEFAULT 1 would make every added profile active.
            db.execSQL("INSERT INTO server_profiles (nickname, url) VALUES ('Laptop', 'https://laptop/wellness')")
            db.query("SELECT isActive FROM server_profiles").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    /**
     * The whole chain an install from Phase 1 actually walks. Running only the
     * latest hop would miss an earlier statement that stopped composing with
     * what came after it.
     */
    @Test
    fun migratesTheWholeV1ToV5Chain() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO debug_log (ts, tag, message) VALUES (1, 'phase1', 'from v1')")
            db.execSQL(
                "INSERT INTO payload_cache (module, key, payloadJson, fetchedAt) " +
                    "VALUES ('analysis', 'latest', '{\"b\":2}', 9)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, *WELLNESS_MIGRATIONS).use { db ->
            db.query("SELECT message FROM debug_log").use { cursor ->
                cursor.moveToFirst()
                assertEquals("from v1", cursor.getString(0))
            }
            for (table in listOf("journal_trackers", "coach_plans", "trends_meta", "server_profiles")) {
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("$table should exist and be empty", 0, cursor.getInt(0))
                }
            }
        }
    }

    @Test
    fun theMigratedDatabaseServesTheProfilesDao() {
        helper.createDatabase(TEST_DB, 4).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            WellnessDatabase::class.java,
            TEST_DB,
        ).addMigrations(*WELLNESS_MIGRATIONS).build()
        helper.closeWhenFinished(db)

        runBlocking {
            val dao = db.serverProfilesDao()
            val first = dao.insert(ServerProfileEntity(nickname = "Laptop", url = "https://laptop/wellness"))
            val second = dao.insert(ServerProfileEntity(nickname = "Desk", url = "https://desk/wellness"))

            assertEquals(listOf("Laptop", "Desk"), dao.listAll().map { it.nickname })
            assertTrue(dao.listActive().isEmpty())

            dao.activate(second)
            assertEquals(listOf(second), dao.listActive().map { it.id })

            // The at-most-one invariant, in SQL: one CASE update moves the flag,
            // so there is never an instant with two active rows or none.
            dao.activate(first)
            assertEquals(listOf(first), dao.listActive().map { it.id })

            dao.clearActive()
            assertTrue(dao.listActive().isEmpty())

            dao.rename(first, "Renamed", "https://renamed/wellness")
            assertEquals("Renamed", dao.find(first)?.nickname)

            dao.delete(first)
            assertNull(dao.find(first))
            assertEquals(listOf("Desk"), dao.listAll().map { it.nickname })
        }
    }

    @Test
    fun theSwitchTransactionClearsExactlyTheServerTables() {
        helper.createDatabase(TEST_DB, 4).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            WellnessDatabase::class.java,
            TEST_DB,
        ).addMigrations(*WELLNESS_MIGRATIONS).build()
        helper.closeWhenFinished(db)

        runBlocking {
            val profiles = db.serverProfilesDao()
            val target = profiles.insert(ServerProfileEntity(nickname = "Laptop", url = "https://laptop/wellness"))

            // All EIGHT wiped tables are seeded. Seeding a subset would let a
            // missing `DELETE FROM` pass unnoticed for the tables nobody looked
            // at — and every one of them holds either the previous server's data
            // or the watermark that decides what gets pulled next.
            db.journalDao().upsertTracker(
                JournalTrackerEntity(
                    id = "t1", name = "Water", category = "Habits", type = "simple",
                    deleted = false, lastModifiedAt = "s1", dataJson = """{"id":"t1"}""",
                    isDirty = true, dirtyGeneration = 2,
                ),
            )
            db.journalDao().upsertEntry(
                JournalEntryEntity(
                    date = "2026-08-06", trackerId = "t1", valueJson = "3", completed = true,
                    lastModifiedAt = "s1", isDirty = true, dirtyGeneration = 1,
                ),
            )
            db.journalDao().getOrCreateClientId("journal-client")
            db.journalDao().upsertMeta(JournalMetaEntity("lastServerSyncTime", "journal-watermark"))
            db.coachDao().upsertPlan(CoachPlanEntity("2026-08-06", """{"_lastModified":"p"}""", "p"))
            db.coachDao().upsertLog(CoachLogEntity("2026-08-06", "{}", isDirty = true, dirtyGeneration = 1))
            db.coachDao().getOrCreateClientId("coach-client")
            db.coachDao().upsertMeta(CoachMetaEntity("lastServerSyncTime", "coach-watermark"))
            db.payloadCacheDao().upsert(PayloadCacheEntity("trends", "overview", "{}", 1))
            db.trendsMetaDao().put("ui.range", "12w")
            db.debugLogDao().insert(DebugLogEntity(ts = 1, tag = "journal-sync", message = "before"))

            db.serverSwitchDao().switchTo(
                targetId = target,
                boundary = DebugLogEntity(ts = 2, tag = "server", message = "server switch: Built-in → Laptop"),
            )

            // Gone: an empty watermark IS the request for a full pull, which is
            // how the new server's data arrives with no explicit resync step.
            assertTrue(db.journalDao().listAllTrackers().isEmpty())
            assertTrue(db.journalDao().listAllEntries().isEmpty())
            assertNull(db.journalDao().getMeta("clientId"))
            assertNull(db.journalDao().getMeta("lastServerSyncTime"))
            assertTrue(db.coachDao().listAllPlans().isEmpty())
            assertTrue(db.coachDao().listAllLogs().isEmpty())
            assertNull(db.coachDao().getMeta("clientId"))
            assertNull(db.coachDao().getMeta("lastServerSyncTime"))
            assertNull(db.payloadCacheDao().find("trends", "overview"))
            assertNull(db.trendsMetaDao().get("ui.range"))

            // Kept: the list the user just used, and the log they read after.
            assertEquals(listOf(target), profiles.listActive().map { it.id })
            assertEquals(
                listOf("before", "server switch: Built-in → Laptop"),
                db.debugLogDao().listSince(0).map { it.message },
            )
        }
    }

    private companion object {
        const val TEST_DB = "profiles-migration-test.db"
    }
}
