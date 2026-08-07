package dev.jtiisto.wellness.core.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jtiisto.wellness.core.data.sync.DebugLogLogic
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the SQL half of the debug-log retention rules against the real Room
 * schema — the pure `DebugLogLogic` twins are pinned by JVM tests, and this
 * suite proves the two do not drift. Runs on the emulator (`/adb-*` sessions),
 * never in git hooks.
 */
@RunWith(AndroidJUnit4::class)
class DebugLogDaoTest {

    private lateinit var db: WellnessDatabase
    private lateinit var dao: DebugLogDao

    private fun entry(ts: Long, message: String = "m") =
        DebugLogEntity(ts = ts, tag = "test", message = message)

    @Before
    fun openDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WellnessDatabase::class.java,
        ).build()
        dao = db.debugLogDao()
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun ttlBoundary_rowAtCutoffDeleted_rowJustInsideRetained() = runBlocking {
        val cutoff = 1_000_000L
        dao.insert(entry(ts = cutoff, message = "exactly at cutoff"))
        dao.insert(entry(ts = cutoff + 1, message = "just inside"))

        dao.insertAndPrune(entry(ts = cutoff + 2, message = "new"), cutoff, DebugLogLogic.MAX_ENTRIES)

        val kept = dao.listSince(0).map(DebugLogEntity::message)
        assertEquals(listOf("just inside", "new"), kept)
    }

    @Test
    fun capKeepsTheNewestRows() = runBlocking {
        repeat(DebugLogLogic.MAX_ENTRIES + 10) { i ->
            dao.insertAndPrune(entry(ts = i.toLong(), message = "e$i"), cutoff = -1, max = DebugLogLogic.MAX_ENTRIES)
        }

        val kept = dao.listSince(-1)
        assertEquals(DebugLogLogic.MAX_ENTRIES, kept.size)
        assertEquals("e10", kept.first().message)
        assertEquals("e${DebugLogLogic.MAX_ENTRIES + 9}", kept.last().message)
    }

    @Test
    fun concurrentInsertAndPruneNeverOvershootsTheCap() = runBlocking {
        val writers = (0 until 200).map { i ->
            async {
                dao.insertAndPrune(entry(ts = i.toLong()), cutoff = -1, max = 50)
            }
        }
        writers.awaitAll()

        assertTrue("cap overshot: ${dao.listSince(-1).size} rows", dao.listSince(-1).size <= 50)
    }
}
