package dev.jtiisto.wellness.core.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SQL half of the dirty machinery, against the real Room schema. The pure
 * `DirtySetLogic` twins are pinned by JVM tests; this suite proves the two do
 * not drift. Runs on the emulator (`/adb-*` sessions), never in git hooks.
 */
@RunWith(AndroidJUnit4::class)
class JournalDaoTest {

    private lateinit var db: WellnessDatabase
    private lateinit var dao: JournalDao

    private fun tracker(
        id: String,
        name: String = "Water",
        deleted: Boolean = false,
        isDirty: Boolean = false,
        generation: Long = 0,
    ) = JournalTrackerEntity(
        id = id,
        name = name,
        category = "Habits",
        type = "simple",
        deleted = deleted,
        lastModifiedAt = "s1",
        dataJson = """{"id":"$id","name":"$name","lastModifiedAt":"s1"}""",
        isDirty = isDirty,
        dirtyGeneration = generation,
    )

    private fun entry(
        date: String,
        trackerId: String,
        isDirty: Boolean = false,
        generation: Long = 0,
    ) = JournalEntryEntity(
        date = date,
        trackerId = trackerId,
        valueJson = null,
        completed = true,
        lastModifiedAt = "s1",
        isDirty = isDirty,
        dirtyGeneration = generation,
    )

    @Before
    fun openDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WellnessDatabase::class.java,
        ).build()
        dao = db.journalDao()
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun markBumpsGenerationAndSnapshotReportsIt() = runBlocking {
        dao.upsertTracker(tracker("t1"))
        dao.upsertEntry(entry("2026-08-06", "t1"))

        dao.markTrackerDirty("t1")
        dao.markTrackerDirty("t1")
        dao.markEntryDirty("2026-08-06", "t1")

        assertEquals(listOf(TrackerGeneration("t1", 2)), dao.snapshotDirtyTrackers())
        assertEquals(listOf(EntryGeneration("2026-08-06", "t1", 1)), dao.snapshotDirtyEntries())
        assertEquals(2, dao.countDirty())
    }

    @Test
    fun clearLandsOnlyWhileTheGenerationIsUnchanged() = runBlocking {
        dao.upsertTracker(tracker("t1"))
        dao.upsertEntry(entry("2026-08-06", "t1"))
        dao.markTrackerDirty("t1")
        dao.markEntryDirty("2026-08-06", "t1")

        val trackerSnapshot = dao.snapshotDirtyTrackers().single().dirtyGeneration
        val entrySnapshot = dao.snapshotDirtyEntries().single().dirtyGeneration

        // The user edits again while the upload is in flight.
        dao.markTrackerDirty("t1")

        dao.clearTrackerDirty("t1", trackerSnapshot)
        dao.clearEntryDirty("2026-08-06", "t1", entrySnapshot)

        assertTrue("re-edited tracker must stay dirty", dao.getTracker("t1")!!.isDirty)
        assertFalse("untouched entry should have cleared", dao.getEntry("2026-08-06", "t1")!!.isDirty)
        assertEquals(1, dao.countDirty())
    }

    @Test
    fun softDeleteKeepsTheRowAndItsBaseToken() = runBlocking {
        dao.upsertTracker(tracker("t1"))

        dao.softDeleteTrackerAndMarkDirty("t1")

        val row = dao.getTracker("t1")!!
        assertTrue(row.deleted)
        assertTrue(row.isDirty)
        assertEquals("s1", row.lastModifiedAt)
        assertTrue(row.dataJson.contains("s1"))
        assertTrue("a pending delete is hidden from the UI", dao.observeTrackersOnce().isEmpty())
    }

    @Test
    fun deltaApplySkipsDirtyRowsAndStampsTheWatermark() = runBlocking {
        dao.upsertTracker(tracker("t1", name = "local", isDirty = true, generation = 4))
        dao.upsertTracker(tracker("t2", name = "clean"))
        dao.upsertEntry(entry("2026-08-06", "t1", isDirty = true, generation = 2))

        dao.applyDelta(
            trackers = listOf(tracker("t1", name = "server"), tracker("t2", name = "server")),
            entries = listOf(entry("2026-08-06", "t1").copy(completed = false)),
            deletedTrackerIds = emptyList(),
            watermark = "s9",
        )

        assertEquals("local", dao.getTracker("t1")!!.name)
        assertEquals(4L, dao.getTracker("t1")!!.dirtyGeneration)
        assertEquals("server", dao.getTracker("t2")!!.name)
        assertEquals(true, dao.getEntry("2026-08-06", "t1")!!.completed)
        assertEquals("s9", dao.getMeta(JournalDao.KEY_LAST_SERVER_SYNC_TIME))
    }

    @Test
    fun deltaApplyDropsDeletedTrackersWithTheirEntriesAndDirtyState() = runBlocking {
        dao.upsertTracker(tracker("t1", isDirty = true, generation = 1))
        dao.upsertEntry(entry("2026-08-06", "t1", isDirty = true, generation = 1))
        dao.upsertTracker(tracker("t2"))
        dao.upsertEntry(entry("2026-08-06", "t2"))

        dao.applyDelta(emptyList(), emptyList(), listOf("t1"), watermark = "s9")

        assertNull(dao.getTracker("t1"))
        assertNull(dao.getEntry("2026-08-06", "t1"))
        assertEquals(0, dao.countDirty())
        assertEquals(1, dao.listAllTrackers().size)
    }

    @Test
    fun pruneSparesDirtyRowsAndFutureDates() = runBlocking {
        dao.upsertEntry(entry("2026-07-01", "t1"))
        dao.upsertEntry(entry("2026-07-02", "t1", isDirty = true, generation = 1))
        dao.upsertEntry(entry("2026-08-06", "t1"))
        dao.upsertEntry(entry("2026-09-01", "t1"))

        dao.pruneEntriesBefore("2026-07-30")

        assertEquals(
            listOf("2026-07-02", "2026-08-06", "2026-09-01"),
            dao.listAllEntries().map { it.date }.sorted(),
        )
    }

    @Test
    fun settledDeletesExcludeRowsWithAnUnsentDelete() = runBlocking {
        dao.upsertTracker(tracker("settled", deleted = true))
        dao.upsertTracker(tracker("pending", deleted = true, isDirty = true, generation = 1))
        dao.upsertTracker(tracker("alive"))

        assertEquals(listOf("settled"), dao.listSettledDeletedTrackerIds())

        dao.pruneSettledDeletedTrackers()

        assertEquals(listOf("alive", "pending"), dao.listAllTrackers().map { it.id }.sorted())
    }

    @Test
    fun stampingAnEntryLeavesItsPayloadAlone() = runBlocking {
        dao.upsertEntry(entry("2026-08-06", "t1").copy(valueJson = "42", isDirty = true, dirtyGeneration = 3))

        dao.stampEntry("2026-08-06", "t1", "s9")

        val row = dao.getEntry("2026-08-06", "t1")!!
        assertEquals("s9", row.lastModifiedAt)
        assertEquals("42", row.valueJson)
        assertTrue(row.isDirty)
        assertEquals(3L, row.dirtyGeneration)
    }

    @Test
    fun responseApplyStampsCurrentContentButRefusesAStaleAdoption() = runBlocking {
        dao.upsertTracker(tracker("stamped", name = "edited after upload", isDirty = true, generation = 5))
        dao.upsertTracker(tracker("adopted", name = "edited after upload", isDirty = true, generation = 5))

        dao.applyUploadResponse(
            plan = UploadResponseApply(
                trackerStamps = listOf(TrackerStamp("stamped", "s9")),
                trackerAdoptions = listOf(
                    // Uploaded at generation 4; the row is at 5, so the server's
                    // copy must not overwrite what the user has since typed.
                    TrackerAdoption(tracker("adopted", name = "server copy"), expectedGeneration = 4),
                ),
                trackerClears = listOf(TrackerDirtyClear("stamped", 4)),
                watermark = "s9",
            ),
            restampTracker = { row, stamp -> row.copy(lastModifiedAt = stamp) },
        )

        val stamped = dao.getTracker("stamped")!!
        assertEquals("s9", stamped.lastModifiedAt)
        assertEquals("edited after upload", stamped.name)
        assertTrue("a clear guarded by a stale generation must not land", stamped.isDirty)
        assertEquals("edited after upload", dao.getTracker("adopted")!!.name)
        assertEquals("s9", dao.getMeta(JournalDao.KEY_LAST_SERVER_SYNC_TIME))
    }

    @Test
    fun clientIdIsMintedOnceUnderConcurrentFirstAccess() = runBlocking {
        val minted = (0 until 20).map { i -> async { dao.getOrCreateClientId("candidate-$i") } }.awaitAll()

        assertEquals(1, minted.toSet().size)
        assertEquals(minted.first(), dao.getMeta(JournalDao.KEY_CLIENT_ID))
    }

    private suspend fun JournalDao.observeTrackersOnce(): List<JournalTrackerEntity> = observeTrackers().first()
}
