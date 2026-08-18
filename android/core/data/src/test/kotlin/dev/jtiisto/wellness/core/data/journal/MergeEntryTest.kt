package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.JournalEntryEntity
import dev.jtiisto.wellness.core.data.db.JournalTrackerEntity
import dev.jtiisto.wellness.core.data.network.JournalApi
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * [JournalSyncStore.mergeEntry] — the widgets' one write path.
 *
 * Two things these guard that nothing else does. First, presence: a patch that
 * leaves a field alone must not overwrite it, and one that sets it to null must.
 * Second, the difference between an **absent** value (`valueJson` SQL NULL) and
 * an **explicitly null** one (the string `"null"`), which is what the checkbox's
 * "only write a default when there is no value" rule turns on.
 */
class MergeEntryTest {

    private val date = "2026-08-06"
    private val trackerId = "t1"

    // ---- presence matrix: value × completed --------------------------------

    /**
     * All nine `(Unchanged | Set(v) | Set(null))²` combinations against a row
     * holding `42` / `false`. The rule under test is uniform but easy to break
     * one cell at a time: a field is written when — and only when — its patch
     * says so, and everything else survives untouched.
     */
    @ParameterizedTest(name = "existing row: {0}")
    @MethodSource("existingRowCases")
    fun mergeMatrixOnAnExistingRow(case: MergeCase) = runTest {
        val world = World()
        world.seedEntry(valueJson = "42", completed = false)

        world.store.mergeEntry(date, trackerId, case.patch)

        assertEquals(case.expectedValueJson, world.row()?.valueJson, "value")
        assertEquals(case.expectedCompleted, world.row()?.completed, "completed")
        assertEquals(if (case.writes) 1 else 0, world.dao.entryWrites, "one upsert per merge, never two")
        assertEquals(if (case.writes) 1 else 0, world.dao.countDirty(), "dirty")
        assertEquals(if (case.writes) 1 else 0, world.scheduledUploads, "scheduled uploads")
    }

    /**
     * The same patches against a row that does not exist yet. "Unchanged" has
     * nothing to preserve here, so it leaves the column null — but it still
     * does not, on its own, bring a row into being.
     */
    @ParameterizedTest(name = "missing row: {0}")
    @MethodSource("missingRowCases")
    fun mergeMatrixOnAMissingRow(case: MergeCase) = runTest {
        val world = World()

        world.store.mergeEntry(date, trackerId, case.patch)

        if (!case.writes) {
            assertNull(world.row(), "nothing was set, so no row should exist")
            assertEquals(0, world.dao.countDirty())
            return@runTest
        }
        assertEquals(case.expectedValueJson, world.row()?.valueJson, "value")
        assertEquals(case.expectedCompleted, world.row()?.completed, "completed")
        assertEquals(1, world.dao.countDirty())
    }

    @Test
    @DisplayName("both Unchanged leaves the stored row byte-for-byte alone")
    fun bothUnchangedTouchesNothing() = runTest {
        val world = World()
        world.seedEntry(valueJson = "1", completed = true, stamp = "s3", isDirty = true, generation = 2)
        val before = world.row()

        world.store.mergeEntry(date, trackerId, EntryPatch())

        assertEquals(before, world.row())
        assertEquals(0, world.dao.entryWrites)
        assertEquals(0, world.scheduledUploads)
    }

    // ---- absent vs explicit null -------------------------------------------

    @Test
    @DisplayName("absent and explicitly-null values are stored, and read back, distinctly")
    fun absentVersusExplicitNull() = runTest {
        val world = World()

        world.store.mergeEntry(date, trackerId, EntryPatch(value = EntryField.Set(JsonNull)))
        assertEquals("null", world.row()?.valueJson, "an explicit JSON null is the literal 'null'")
        assertEquals(JsonNull, world.entry()?.value)
        assertFalse(world.entry().hasValue())

        world.store.mergeEntry(date, trackerId, EntryPatch(value = EntryField.Set(null)))
        assertNull(world.row()?.valueJson, "clearing writes SQL NULL — absent")
        assertNull(world.entry()?.value)
        assertFalse(world.entry().hasValue())
    }

    @Test
    @DisplayName("an empty row stays visible but claims nothing — visibility and judgment part ways")
    fun explicitNullRowIsVisibleButNotLogged() = runTest {
        val world = World()

        world.store.mergeEntry(date, trackerId, EntryPatch(value = EntryField.Set(JsonNull)))

        val day = world.store.observeDay(date).first()
        assertTrue(day.containsKey(trackerId), "visibility keys off row existence, and still does")
        assertFalse(day[trackerId].countsAsLogged(), "no value, not completed: nothing asserted")
        // The retraction rule: an uncheck leaves this row behind, so judging it
        // as a lapse would make the avoidance impossible to take back.
        assertEquals(
            TargetState.MET,
            dayStatus(tracker(id = trackerId, polarity = "negative"), date, day[trackerId]).state,
        )
    }

    @Test
    @DisplayName("a written value keeps the row judged even once the box is cleared")
    fun aValueOutlivesItsCheckbox() = runTest {
        val world = World()
        world.seedEntry(valueJson = "3", completed = true)

        world.store.mergeEntry(date, trackerId, EntryPatch(completed = EntryField.Set(false)))

        val entry = world.entry()
        assertTrue(entry.countsAsLogged(), "the value is the assertion, not the checkbox")
        assertEquals(
            TargetState.MISSED,
            dayStatus(tracker(id = trackerId, polarity = "negative"), date, entry).state,
        )
    }

    // ---- dirty accounting ---------------------------------------------------

    @Test
    @DisplayName("an entry edit marks the entry dirty and never the tracker")
    fun marksOnlyTheEntryDirty() = runTest {
        val world = World()
        world.seedTracker()

        world.store.mergeEntry(date, trackerId, EntryPatch(completed = EntryField.Set(true)))

        assertTrue(world.row()!!.isDirty)
        assertFalse(
            world.dao.trackers.getValue(trackerId).isDirty,
            "an entry edit is not a config edit — the upload queues them separately",
        )
    }

    @Test
    @DisplayName("the base token survives a merge — the upload still needs it")
    fun preservesTheBaseToken() = runTest {
        val world = World()
        world.seedEntry(valueJson = "1", completed = true, stamp = "s7")

        world.store.mergeEntry(date, trackerId, EntryPatch(value = EntryField.Set(JsonPrimitive(2))))

        assertEquals("s7", world.row()?.lastModifiedAt)
    }

    @Test
    @DisplayName("a merge bumps the dirty generation rather than resetting it")
    fun bumpsGeneration() = runTest {
        val world = World()
        world.seedEntry(valueJson = "1", completed = true, isDirty = true, generation = 4)

        world.store.mergeEntry(date, trackerId, EntryPatch(completed = EntryField.Set(false)))

        assertEquals(5L, world.row()?.dirtyGeneration, "the mid-sync clear guard depends on this counter")
    }

    /** One cell of the presence matrix. [writes] false means the merge is a no-op. */
    data class MergeCase(
        private val label: String,
        val patch: EntryPatch,
        val expectedValueJson: String?,
        val expectedCompleted: Boolean?,
        val writes: Boolean = true,
    ) {
        override fun toString(): String = label
    }

    private inner class World {
        val dao = CountingJournalDao()
        var scheduledUploads = 0

        val store = JournalSyncStore(
            session = ServerSessionGate(),
            dao = dao,
            api = mockk<JournalApi>(relaxed = true),
            isOnline = { true },
            json = WellnessJson,
            scheduleUpload = { scheduledUploads++ },
            today = { java.time.LocalDate.parse("2026-08-06") },
        )

        suspend fun row(): JournalEntryEntity? = dao.getEntry(date, trackerId)

        suspend fun entry(): EntryDto? = store.observeDay(date).first()[trackerId]

        fun seedEntry(
            valueJson: String? = null,
            completed: Boolean? = null,
            stamp: String? = null,
            isDirty: Boolean = false,
            generation: Long = 0,
        ) {
            dao.entries["$date|$trackerId"] = JournalEntryEntity(
                date = date,
                trackerId = trackerId,
                valueJson = valueJson,
                completed = completed,
                lastModifiedAt = stamp,
                isDirty = isDirty,
                dirtyGeneration = generation,
            )
        }

        fun seedTracker(isDirty: Boolean = false) {
            val dto = TrackerDto(id = trackerId, name = "Water", type = "simple")
            dao.trackers[trackerId] = JournalTrackerEntity(
                id = trackerId,
                name = "Water",
                category = "Habits",
                type = "simple",
                deleted = false,
                lastModifiedAt = "s0",
                dataJson = TrackerDtoSerializer.toJson(dto, WellnessJson).toString(),
                isDirty = isDirty,
                dirtyGeneration = 0,
            )
        }
    }

    companion object {
        private val seven = EntryField.Set<JsonElement?>(JsonPrimitive(7))
        private val clearValue = EntryField.Set<JsonElement?>(null)
        private val done = EntryField.Set<Boolean?>(true)
        private val clearCompleted = EntryField.Set<Boolean?>(null)

        /** Against a stored `42` / `false`. */
        @JvmStatic
        fun existingRowCases(): List<MergeCase> = listOf(
            MergeCase("Unchanged / Unchanged", EntryPatch(), "42", false, writes = false),
            MergeCase("Unchanged / Set(true)", EntryPatch(completed = done), "42", true),
            MergeCase("Unchanged / Set(null)", EntryPatch(completed = clearCompleted), "42", null),
            MergeCase("Set(7) / Unchanged", EntryPatch(value = seven), "7", false),
            MergeCase("Set(7) / Set(true)", EntryPatch(seven, done), "7", true),
            MergeCase("Set(7) / Set(null)", EntryPatch(seven, clearCompleted), "7", null),
            MergeCase("Set(null) / Unchanged", EntryPatch(value = clearValue), null, false),
            MergeCase("Set(null) / Set(true)", EntryPatch(clearValue, done), null, true),
            MergeCase("Set(null) / Set(null)", EntryPatch(clearValue, clearCompleted), null, null),
        )

        /** Against no row at all: "Unchanged" has nothing to preserve. */
        @JvmStatic
        fun missingRowCases(): List<MergeCase> = listOf(
            MergeCase("Unchanged / Unchanged", EntryPatch(), null, null, writes = false),
            MergeCase("Unchanged / Set(true)", EntryPatch(completed = done), null, true),
            MergeCase("Unchanged / Set(null)", EntryPatch(completed = clearCompleted), null, null),
            MergeCase("Set(7) / Unchanged", EntryPatch(value = seven), "7", null),
            MergeCase("Set(7) / Set(true)", EntryPatch(seven, done), "7", true),
            // A row that exists holding nothing is still a row, and that is the
            // point: it keeps an off-schedule tracker on screen.
            MergeCase("Set(null) / Set(null)", EntryPatch(clearValue, clearCompleted), null, null),
        )
    }
}

/** [FakeJournalDao] plus a count of entry upserts, to pin "one write, not two". */
internal class CountingJournalDao : FakeJournalDao() {
    var entryWrites = 0
        private set

    override suspend fun upsertEntry(row: JournalEntryEntity) {
        entryWrites += 1
        super.upsertEntry(row)
    }
}
