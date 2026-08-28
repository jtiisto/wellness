package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.JournalDao
import dev.jtiisto.wellness.core.data.db.JournalDaySnapshot
import dev.jtiisto.wellness.core.data.db.JournalEntryEntity
import dev.jtiisto.wellness.core.data.db.JournalTrackerEntity
import dev.jtiisto.wellness.core.data.network.DateString
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The widget's tally, read off one DAO snapshot with no store in front of it.
 *
 * Two things are being pinned, and the second is the one that was wrong first
 * time round.
 *
 * **This class decides nothing.** Every assertion about the tally is written
 * twice over — once against [categoryRollup]'s own answer for the same inputs,
 * once against the literal counts — because the failure that matters is a peek
 * that quietly grows its own opinion of "done" and drifts from the Journal
 * screen it is echoing.
 *
 * **Null and a throw mean different things.** Null is *absence*: the day could
 * not be read, or it asks nothing of you. A throw is *corruption*, and it must
 * escape. The tempting version — skip the row that will not decode — shrinks
 * the denominator and turns "5 of 6 done" into "5 of 5 done", which is a
 * confident wrong answer on a surface the user only ever glances at.
 *
 * The DAO is a mock rather than a fake on purpose: a fake would re-implement
 * `deleted = 0` in Kotlin and then "prove" the filter by agreeing with itself.
 * Here the snapshot is handed over as data, so what is pinned is what this
 * class does with rows — including that it does **not** re-filter them.
 *
 * Dates follow the far-future `2030-01-*` fixture convention.
 */
class JournalDayPeekTest {

    private val json = WellnessJson
    private val dao = mockk<JournalDao>()
    private val peek = JournalDayPeek(journalDao = dao, json = json)

    private val today: DateString = "2030-01-06"

    // ---- delegation ---------------------------------------------------------

    @Test
    @DisplayName("the day is judged by categoryRollup, with the log keyed by tracker id")
    fun delegatesWithDayLogKeyedByTrackerId() = runTest {
        val alpha = tracker(id = "t-alpha", name = "Alpha", polarity = "positive")
        val bravo = tracker(id = "t-bravo", name = "Bravo", polarity = "positive")
        val charlie = tracker(id = "t-charlie", name = "Charlie", polarity = "positive")
        // Only Alpha is done. If the log were keyed by anything but tracker id,
        // Alpha's completion would leak onto its neighbours and read 3 of 3.
        snapshot(
            trackers = listOf(alpha, bravo, charlie),
            entries = listOf(entryRow("t-alpha", completed = true), entryRow("t-bravo", completed = false)),
        )

        val delegated = categoryRollup(
            listOf(alpha, bravo, charlie),
            today,
            mapOf("t-alpha" to entry(completed = true), "t-bravo" to entry(completed = false)),
        )

        assertEquals(delegated, peek.rollup(today))
        assertEquals(CategoryRollup(habitsMet = 1, habitsNotYet = 2), peek.rollup(today))
    }

    @Test
    @DisplayName("snapshot rows reach the rollup unmodified — this class filters nothing")
    fun snapshotRowsPassThroughUnmodified() = runTest {
        val water = tracker(id = "t-water", name = "Water", polarity = "positive")
        // A row the DAO would never emit (`deleted = 0` is in the SQL), handed
        // over anyway: the peek must pass it straight through. Re-filtering here
        // would be a second copy of a rule that already lives in one place, and
        // the copy is what drifts.
        val retired = tracker(id = "t-retired", name = "Retired", polarity = "positive")
        snapshot(
            trackers = listOf(water, retired),
            trackerRows = listOf(trackerRow(water), trackerRow(retired, deleted = true)),
            entries = listOf(entryRow("t-water", completed = true)),
        )

        val delegated = categoryRollup(
            listOf(water, retired),
            today,
            mapOf("t-water" to entry(completed = true)),
        )

        assertEquals(delegated, peek.rollup(today))
        assertEquals(CategoryRollup(habitsMet = 1, habitsNotYet = 1), peek.rollup(today))
    }

    @Test
    @DisplayName("an empty snapshot is null — the rollup's own answer for a day that asks nothing")
    fun emptySnapshotIsNull() = runTest {
        snapshot(trackers = emptyList(), entries = emptyList())

        assertNull(categoryRollup(emptyList(), today, emptyMap()))
        assertNull(peek.rollup(today))
    }

    // ---- corruption escapes -------------------------------------------------

    @Test
    @DisplayName("a tracker row that will not decode throws — it must not shrink the denominator")
    fun corruptTrackerRowThrows() = runTest {
        val water = tracker(id = "t-water", name = "Water", polarity = "positive")
        coEvery { dao.daySnapshot(today) } returns JournalDaySnapshot(
            trackers = listOf(trackerRow(water), rawTrackerRow(id = "t-torn", dataJson = "{ never JSON")),
            entries = listOf(entryRow("t-water", completed = true)),
        )

        // Skipping it would report "1 of 1 done" on a day that expected two.
        assertThrows<Exception> { peek.rollup(today) }
    }

    @Test
    @DisplayName("a tracker row with no id throws too — the other way a stored config goes bad")
    fun idlessTrackerRowThrows() = runTest {
        coEvery { dao.daySnapshot(today) } returns JournalDaySnapshot(
            trackers = listOf(rawTrackerRow(id = "t-ghost", dataJson = """{"name":"Ghost","polarity":"positive"}""")),
            entries = emptyList(),
        )

        assertThrows<Exception> { peek.rollup(today) }
    }

    @Test
    @DisplayName("a corrupt entry value throws — a dropped entry mis-judges its tracker as not-yet")
    fun corruptEntryValueThrows() = runTest {
        val water = tracker(id = "t-water", name = "Water", polarity = "positive")
        coEvery { dao.daySnapshot(today) } returns JournalDaySnapshot(
            trackers = listOf(trackerRow(water)),
            entries = listOf(entryRow("t-water", completed = true, valueJson = "{ never JSON")),
        )

        assertThrows<Exception> { peek.rollup(today) }
    }

    // ---- unavailability is quiet --------------------------------------------

    @Test
    @DisplayName("a DAO that throws is null — a launcher render has no error surface")
    fun readFailureIsNull() = runTest {
        coEvery { dao.daySnapshot(today) } throws RuntimeException("database is not openable")

        assertNull(peek.rollup(today))
    }

    @Test
    @DisplayName("a cancelled render is not a failed one — CancellationException is rethrown")
    fun cancellationIsRethrown() = runTest {
        coEvery { dao.daySnapshot(today) } throws CancellationException("render cancelled")

        assertThrows<CancellationException> { peek.rollup(today) }
    }

    // ---- fixtures -----------------------------------------------------------

    /**
     * Stub the snapshot from [trackers] (encoded as rows), unless [trackerRows]
     * supplies the rows directly.
     */
    private fun snapshot(
        trackers: List<TrackerDto>,
        entries: List<JournalEntryEntity>,
        trackerRows: List<JournalTrackerEntity> = trackers.map { trackerRow(it) },
    ) {
        coEvery { dao.daySnapshot(today) } returns JournalDaySnapshot(trackerRows, entries)
    }

    private fun trackerRow(tracker: TrackerDto, deleted: Boolean = false): JournalTrackerEntity =
        JournalTrackerEntity(
            id = tracker.id,
            name = tracker.name,
            category = tracker.category,
            type = tracker.type,
            deleted = deleted,
            lastModifiedAt = tracker.lastModifiedAt,
            dataJson = json.encodeToString(
                JsonObject.serializer(),
                TrackerDtoSerializer.toJson(tracker, json),
            ),
            isDirty = false,
            dirtyGeneration = 0L,
        )

    /** A row whose stored config is whatever [dataJson] says, valid or not. */
    private fun rawTrackerRow(id: String, dataJson: String): JournalTrackerEntity =
        JournalTrackerEntity(
            id = id,
            name = id,
            category = null,
            type = null,
            deleted = false,
            lastModifiedAt = null,
            dataJson = dataJson,
            isDirty = false,
            dirtyGeneration = 0L,
        )

    private fun entryRow(
        trackerId: String,
        completed: Boolean? = null,
        valueJson: String? = null,
    ): JournalEntryEntity = JournalEntryEntity(
        date = today,
        trackerId = trackerId,
        valueJson = valueJson,
        completed = completed,
        lastModifiedAt = null,
        isDirty = false,
        dirtyGeneration = 0L,
    )
}
