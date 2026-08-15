package dev.jtiisto.wellness.core.data.coach

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.CoachLogEdit
import dev.jtiisto.wellness.core.data.db.CoachLogEntity
import dev.jtiisto.wellness.core.data.db.SetEventEntity
import dev.jtiisto.wellness.core.data.network.CoachApi
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The completion-toggle dual-write: every tick appends a timestamped set event
 * in the same transaction as the coach blob it describes.
 *
 * Two properties are what these guard, and both are places where a plausible
 * implementation quietly lies to the correlation. **Atomicity**: an event
 * asserts a set was performed at an instant, so one that outlived a blob write
 * that never landed would claim work the screen says was never done.
 * **Change-only**: re-ticking a ticked set still rewrites the blob (unchanged
 * behaviour, deliberately) but must not invent a second moment of performance.
 *
 * The blob path itself is covered by `CoachSyncStoreTest`; nothing here may
 * change what it writes.
 */
class CoachSetEventTest {

    private val json = WellnessJson
    private val today = "2026-08-08"

    // ---- set ticks -------------------------------------------------------

    @Test
    @DisplayName("ticking a set appends a check event carrying the set number and no item key")
    fun aSetTickAppendsACheckEvent() = runTest {
        val world = World()

        world.tickSet("ex_1", setNum = 1, completed = true)

        val event = world.onlyEvent()
        assertEquals("event-1", event.eventId)
        assertEquals(today, event.date)
        assertEquals("ex_1", event.exerciseKey)
        assertEquals(1, event.setNum)
        assertNull(event.itemKey)
        assertEquals(SetEventEntity.ACTION_CHECK, event.action)
        assertEquals(NOW_MS, event.clientTimestampMs)
        assertNull(event.sessionId, "Phase 1 has no capture session")
        assertTrue(world.dao.logs.getValue(today).isDirty, "the blob write is unchanged")
    }

    @Test
    @DisplayName("unticking appends an uncheck event — undo is data, and nothing is deleted")
    fun untickingAppendsAnUncheckEvent() = runTest {
        val world = World()
        world.seedSet("ex_1", completed = true)

        world.tickSet("ex_1", setNum = 1, completed = false)

        val event = world.onlyEvent()
        assertEquals(SetEventEntity.ACTION_UNCHECK, event.action)
        assertEquals(1, event.setNum)
    }

    @Test
    @DisplayName("check then uncheck of the same set leaves two rows, in toggle order")
    fun checkThenUncheckLeavesBothRows() = runTest {
        val world = World()

        world.tickSet("ex_1", setNum = 1, completed = true)
        world.nowMs += 5_000
        world.tickSet("ex_1", setNum = 1, completed = false)

        assertEquals(
            listOf(SetEventEntity.ACTION_CHECK, SetEventEntity.ACTION_UNCHECK),
            world.dao.setEvents.map { it.action },
        )
        assertEquals(listOf("event-1", "event-2"), world.dao.setEvents.map { it.eventId })
        assertEquals(
            listOf(NOW_MS, NOW_MS + 5_000),
            world.dao.setEvents.map { it.clientTimestampMs },
        )
    }

    @Test
    @DisplayName("re-ticking an already ticked set rewrites the blob but says nothing new")
    fun reTickingAnAlreadyTickedSetAppendsNothing() = runTest {
        val world = World()
        world.seedSet("ex_1", completed = true)

        world.tickSet("ex_1", setNum = 1, completed = true)

        assertTrue(world.dao.setEvents.isEmpty(), "a no-op toggle is not a moment of performance")
        // The blob half is deliberately untouched by any of this: it still
        // writes, still dirties, still wakes the coach uploader.
        assertTrue(world.dao.logs.getValue(today).isDirty)
        assertEquals(1, world.scheduledUploads)
        assertEquals(0, world.flushes, "nothing was appended, so nothing to flush")
    }

    @Test
    @DisplayName("unticking a set that was never ticked appends nothing")
    fun untickingAnUntickedSetAppendsNothing() = runTest {
        val world = World()
        world.seedSet("ex_1", completed = false)

        world.tickSet("ex_1", setNum = 1, completed = false)

        assertTrue(world.dao.setEvents.isEmpty())
    }

    @Test
    @DisplayName("the stored value is read with the grid's own truthiness rule")
    fun theStoredTickIsComparedAsTheGridReadsIt() = runTest {
        val world = World()
        // `"true"` is a string, and the grid does not draw this set as ticked.
        // Treating it as ticked here would swallow the event that corrects it.
        world.seedLog("""{"session_feedback":{},"ex_1":{"sets":[{"set_num":1,"completed":"true"}]}}""")

        world.tickSet("ex_1", setNum = 1, completed = true)

        assertEquals(SetEventEntity.ACTION_CHECK, world.onlyEvent().action)
    }

    // ---- checklist items -------------------------------------------------

    @Test
    @DisplayName("a checklist toggle appends the item key and no set number, flipping check to uncheck")
    fun aChecklistToggleAppendsItsItemKey() = runTest {
        val world = World()

        world.toggleChecklistItem("ex_2", "Foam roll")
        world.toggleChecklistItem("ex_2", "Foam roll")

        assertEquals(2, world.dao.setEvents.size)
        val first = world.dao.setEvents.first()
        assertEquals("ex_2", first.exerciseKey)
        assertEquals("Foam roll", first.itemKey)
        assertNull(first.setNum)
        assertEquals(SetEventEntity.ACTION_CHECK, first.action)
        assertEquals(SetEventEntity.ACTION_UNCHECK, world.dao.setEvents.last().action)
        assertEquals("Foam roll", world.dao.setEvents.last().itemKey)
    }

    @Test
    @DisplayName("the checklist action is derived from the stored list, not from what the caller assumed")
    fun theChecklistActionComesFromStorage() = runTest {
        val world = World()
        world.seedLog("""{"session_feedback":{},"ex_2":{"completed_items":["Foam roll"]}}""")

        world.toggleChecklistItem("ex_2", "Foam roll")

        assertEquals(SetEventEntity.ACTION_UNCHECK, world.onlyEvent().action)
    }

    // ---- what must never emit --------------------------------------------

    @Test
    @DisplayName("value edits — weight, reps, duration — append nothing")
    fun valueEditsAppendNothing() = runTest {
        val world = World()

        world.store.transformLogEntry(today, "ex_1") {
            buildJsonObject { put("sets", JsonArray(listOf(world.obj("""{"set_num":1,"weight":60}""")))) }
        }
        world.store.transformLogEntry(today, "ex_3") { buildJsonObject { put("duration_min", 30) } }

        assertTrue(world.dao.setEvents.isEmpty())
        assertEquals(0, world.flushes)
        assertEquals(2, world.scheduledUploads, "the blob path still uploads")
    }

    @Test
    @DisplayName("a transform that writes nothing appends nothing and leaves no id burned")
    fun aTransformThatWritesNothingAppendsNothing() = runTest {
        val world = World()
        world.seedSet("ex_1", completed = false)
        val before = world.dao.logs.getValue(today)

        world.store.transformLogEntry(today, "ex_1", CompletionToggle.SetTick(1, completed = true)) { null }

        assertEquals(before, world.dao.logs.getValue(today), "the blob must be untouched")
        assertTrue(world.dao.setEvents.isEmpty())
        assertEquals(0, world.flushes)
        assertEquals(0, world.scheduledUploads)

        // The event id is minted inside the write, so a refused write does not
        // consume one — the next real toggle is still the first event.
        world.tickSet("ex_1", setNum = 1, completed = true)
        assertEquals("event-1", world.onlyEvent().eventId)
    }

    // ---- atomicity and the flush handoff ---------------------------------

    @Test
    @DisplayName("the event is inserted inside the blob's transaction, after the blob write")
    fun theEventLandsInsideTheBlobTransaction() = runTest {
        val world = World()

        world.tickSet("ex_1", setNum = 1, completed = true)

        assertEquals(listOf("tx:start", "insertSetEvent", "tx:end"), world.dao.calls)
    }

    @Test
    @DisplayName("the flush is armed once, and only after the transaction has committed")
    fun theFlushIsArmedAfterTheCommit() = runTest {
        val world = World()
        var flushesAtInsert = -1
        world.dao.onInsertSetEvent = { flushesAtInsert = world.flushes }

        world.tickSet("ex_1", setNum = 1, completed = true)

        assertEquals(0, flushesAtInsert, "the debounce must not wake an uploader mid-transaction")
        assertEquals(1, world.flushes)
    }

    // ---- the Phase 2 seam ------------------------------------------------

    @Test
    @DisplayName("an event carries the capture session when one is running")
    fun theCaptureSessionIsStamped() = runTest {
        val world = World()
        world.captureSessionId = "11111111-2222-3333-4444-555555555555"

        world.tickSet("ex_1", setNum = 1, completed = true)

        assertEquals("11111111-2222-3333-4444-555555555555", world.onlyEvent().sessionId)
    }

    @Test
    @DisplayName("a tombstoned entry reads as unticked, so re-adding and ticking is a check")
    fun aReAddedEntryTicksAsACheck() = runTest {
        val world = World()
        world.seedLog(
            """{"session_feedback":{},"extra_zone2":{"_deleted":true,"_lastModified":"srv-1",""" +
                """"sets":[{"set_num":1,"completed":true}]}}""",
        )

        world.tickSet("extra_zone2", setNum = 1, completed = true)

        // The deleted entry's leftovers are hidden from the transform, so they
        // must be hidden from the comparison too — otherwise the tick that
        // rebuilds the entry would look like a no-op and go unrecorded.
        assertEquals(SetEventEntity.ACTION_CHECK, world.onlyEvent().action)
    }

    // ---- rig -------------------------------------------------------------

    /** Records the transaction boundary, so a test can see what happened inside it. */
    private class TransactionSpyDao : FakeCoachDao() {

        var onInsertSetEvent: () -> Unit = {}

        override suspend fun updateLogAndMarkDirty(
            date: DateString,
            transform: (String?) -> CoachLogEdit?,
        ): Boolean {
            calls += "tx:start"
            return super.updateLogAndMarkDirty(date, transform).also { calls += "tx:end" }
        }

        override suspend fun insertSetEvent(event: SetEventEntity) {
            super.insertSetEvent(event)
            onInsertSetEvent()
        }
    }

    private inner class World {

        val dao = TransactionSpyDao()

        var nowMs = NOW_MS
        var captureSessionId: String? = null
        var flushes = 0
        var scheduledUploads = 0

        private var mintedEvents = 0

        val store = CoachSyncStore(
            dao = dao,
            api = mockk<CoachApi>(relaxed = true),
            isOnline = { true },
            session = ServerSessionGate(),
            json = json,
            scheduleUpload = { scheduledUploads++ },
            setEvents = SetEventRecorder(
                captureSessionId = { captureSessionId },
                newEventId = { "event-${++mintedEvents}" },
                now = { nowMs },
                scheduleUpload = { flushes++ },
            ),
            clock = { "client-clock" },
            today = { LocalDate.parse(today) },
            newClientId = { "fixed-client" },
        )

        fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

        fun seedLog(logJson: String) {
            dao.logs[today] = CoachLogEntity(today, logJson, isDirty = false, dirtyGeneration = 0)
        }

        fun seedSet(exerciseKey: String, completed: Boolean) {
            seedLog(
                """{"session_feedback":{},"$exerciseKey":{"sets":[{"set_num":1,"completed":$completed}]}}""",
            )
        }

        fun onlyEvent(): SetEventEntity {
            assertEquals(1, dao.setEvents.size, "expected exactly one event: ${dao.setEvents}")
            return dao.setEvents.single()
        }

        /** The ViewModel's set tick, one layer down: the same pad-and-rewrite plus its toggle. */
        suspend fun tickSet(exerciseKey: String, setNum: Int, completed: Boolean) {
            store.transformLogEntry(
                today,
                exerciseKey,
                CompletionToggle.SetTick(setNum = setNum, completed = completed),
            ) { entry ->
                val rows = (entry?.get("sets") as? JsonArray).orEmpty()
                    .map { it as? JsonObject ?: JsonObject(emptyMap()) }
                    .toMutableList()
                while (rows.size < setNum) rows += buildJsonObject { put("set_num", rows.size + 1) }
                rows[setNum - 1] = JsonObject(rows[setNum - 1] + ("completed" to JsonPrimitive(completed)))
                buildJsonObject { put("sets", JsonArray(rows)) }
            }
        }

        /** The ViewModel's checklist flip, likewise. */
        suspend fun toggleChecklistItem(exerciseKey: String, item: String) {
            store.transformLogEntry(today, exerciseKey, CompletionToggle.ChecklistItem(item)) { entry ->
                val done = (entry?.get("completed_items") as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.content }
                val next = if (item in done) done - item else done + item
                buildJsonObject { putJsonArray("completed_items") { next.forEach { add(it) } } }
            }
        }
    }

    private companion object {
        const val NOW_MS = 1_770_000_000_000L
    }
}
