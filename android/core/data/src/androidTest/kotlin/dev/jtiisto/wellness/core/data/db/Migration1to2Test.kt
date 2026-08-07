package dev.jtiisto.wellness.core.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v1 → v2 upgrade, against the committed schemas.
 *
 * `runMigrationsAndValidate` is the real assertion: it compares the migrated
 * database to the exported `2.json`, so a hand-written `CREATE TABLE` that has
 * drifted from the entities fails here rather than at the user's next launch.
 * The Phase 1 rows are checked too — destructive migration is never an option
 * when dirty rows are the only copy of an unsent edit.
 */
@RunWith(AndroidJUnit4::class)
class Migration1to2Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WellnessDatabase::class.java,
    )

    @Test
    fun migratesAndKeepsPhase1Data() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO debug_log (ts, tag, message) VALUES (1, 'phase1', 'kept')")
            db.execSQL(
                "INSERT INTO payload_cache (module, key, payloadJson, fetchedAt) " +
                    "VALUES ('trends', 'overview', '{\"a\":1}', 7)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT message FROM debug_log").use { cursor ->
                cursor.moveToFirst()
                assertEquals("kept", cursor.getString(0))
            }
            db.query("SELECT payloadJson FROM payload_cache").use { cursor ->
                cursor.moveToFirst()
                assertEquals("""{"a":1}""", cursor.getString(0))
            }
        }
    }

    @Test
    fun theMigratedDatabaseServesTheJournalDao() {
        helper.createDatabase(TEST_DB, 1).close()

        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            WellnessDatabase::class.java,
            TEST_DB,
        ).addMigrations(*WELLNESS_MIGRATIONS).build()
        helper.closeWhenFinished(db)

        runBlocking {
            val dao = db.journalDao()
            dao.upsertTracker(
                JournalTrackerEntity(
                    id = "t1",
                    name = "Water",
                    category = "Habits",
                    type = "simple",
                    deleted = false,
                    lastModifiedAt = "s1",
                    dataJson = """{"id":"t1"}""",
                    isDirty = false,
                    dirtyGeneration = 0,
                ),
            )
            dao.markTrackerDirty("t1")

            assertEquals(listOf(TrackerGeneration("t1", 1)), dao.snapshotDirtyTrackers())
            assertEquals("c1", dao.getOrCreateClientId("c1"))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
