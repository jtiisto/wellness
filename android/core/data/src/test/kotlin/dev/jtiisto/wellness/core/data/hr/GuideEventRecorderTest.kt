package dev.jtiisto.wellness.core.data.hr

import dev.jtiisto.wellness.core.data.db.FakeGuideEventDao
import dev.jtiisto.wellness.core.data.db.GuideEventEntity
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What a guide action becomes once it is a row.
 *
 * The precondition — no capture, no record — is not asserted here and cannot be:
 * the session id is non-null on both entry points, so a caller without one
 * cannot reach this class at all. That rule is the ViewModel's, and
 * `CoachViewModelTest` is where it is pinned.
 */
class GuideEventRecorderTest {

    private val dao = FakeGuideEventDao()
    private val ids = ArrayDeque(listOf("guide-0001", "guide-0002", "guide-0003"))
    private var scheduled = 0

    private val recorder = GuideEventRecorder(
        dao = dao,
        session = ServerSessionGate(),
        newEventId = { ids.removeFirst() },
        scheduleUpload = { scheduled++ },
    )

    @Test
    @DisplayName("an anchor stores the instant, the key and the timeline it was guided against")
    fun startIsRecorded() = runTest {
        recorder.recordStart(
            date = DATE,
            exerciseKey = "ex_ride",
            sessionId = SESSION,
            clientTimestampMs = ANCHOR_MS,
            timelineJson = TIMELINE,
        )

        val row = dao.events.values.single()
        assertEquals("guide-0001", row.eventId)
        assertEquals(DATE, row.date)
        assertEquals("ex_ride", row.exerciseKey)
        assertEquals(GuideEventEntity.ACTION_START, row.action)
        assertEquals(ANCHOR_MS, row.clientTimestampMs)
        assertEquals(SESSION, row.sessionId)
        assertEquals(TIMELINE, row.timelineJson)
        // The two payload fields belong to one action each; a start carries no
        // extension, and an omitted field is what the wire rule wants to see.
        assertNull(row.extensionSec)
    }

    @Test
    @DisplayName("an extension stores the step it added, not the running total")
    fun extendIsRecorded() = runTest {
        recorder.recordExtend(
            date = DATE,
            exerciseKey = "ex_ride",
            sessionId = SESSION,
            clientTimestampMs = ANCHOR_MS + 900_000,
            extensionSec = 300,
        )
        recorder.recordExtend(
            date = DATE,
            exerciseKey = "ex_ride",
            sessionId = SESSION,
            clientTimestampMs = ANCHOR_MS + 1_200_000,
            extensionSec = 300,
        )

        // Two taps, two rows of 300 — the cumulative ten minutes is something a
        // consumer sums, and each row keeps the instant its five minutes landed.
        assertEquals(listOf(300, 300), dao.listAll().map { it.extensionSec })
        assertEquals(
            listOf(ANCHOR_MS + 900_000, ANCHOR_MS + 1_200_000),
            dao.listAll().map { it.clientTimestampMs },
        )
        assertEquals(listOf(null, null), dao.listAll().map { it.timelineJson })
    }

    @Test
    @DisplayName("every action mints a fresh id — the server dedupes on it")
    fun idsAreNeverReused() = runTest {
        recorder.recordStart(DATE, "ex_ride", SESSION, ANCHOR_MS, TIMELINE)
        recorder.recordExtend(DATE, "ex_ride", SESSION, ANCHOR_MS + 1, 300)

        assertEquals(listOf("guide-0001", "guide-0002"), dao.listAll().map { it.eventId })
    }

    @Test
    @DisplayName("a stored row is pending and unsynced, and the upload debounce is armed")
    fun storedRowsAreOfferedToTheUploader() = runTest {
        recorder.recordStart(DATE, "ex_ride", SESSION, ANCHOR_MS, TIMELINE)

        val row = dao.events.values.single()
        assertFalse(row.isSynced)
        assertFalse(row.isQuarantined)
        assertEquals(1, dao.countPending())
        assertEquals(1, scheduled, "the row is useless until it is offered")
    }

    @Test
    @DisplayName("two guides running on one day keep their own rows")
    fun eventsAreKeyedByExercise() = runTest {
        recorder.recordStart(DATE, "ex_ride", SESSION, ANCHOR_MS, TIMELINE)
        recorder.recordStart(DATE, "ex_row", SESSION, ANCHOR_MS + 60_000, "[]")

        assertEquals(listOf("ex_ride", "ex_row"), dao.listAll().map { it.exerciseKey })
    }

    private companion object {
        const val DATE = "2030-01-03"
        const val SESSION = "11111111-2222-3333-4444-555555555555"
        const val ANCHOR_MS = 1_893_955_260_000L
        const val TIMELINE = """[{"duration_sec":420,"hr_min":118,"hr_max":134,"label":"warmup"}]"""
    }
}
