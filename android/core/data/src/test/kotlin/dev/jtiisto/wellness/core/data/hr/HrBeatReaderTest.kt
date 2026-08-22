package dev.jtiisto.wellness.core.data.hr

import dev.jtiisto.wellness.core.ble.trace.TraceSample
import dev.jtiisto.wellness.core.data.db.FakeHrSampleDao
import dev.jtiisto.wellness.core.data.db.HrSampleEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The one read of the sample table, and the only thing it does beyond the
 * query: hand the rows back as the same `(when, how fast)` pair the live trace
 * is drawn from, so the guide's two readings of a heart rate cannot drift apart.
 */
class HrBeatReaderTest {

    private val device = "AA:BB:CC:DD:EE:FF"
    private val session = "11111111-2222-3333-4444-555555555555"

    private fun sample(timestampMs: Long, bpm: Int, quarantined: Boolean = false) = HrSampleEntity(
        deviceId = device,
        timestampMs = timestampMs,
        seq = 0,
        heartRateBpm = bpm,
        rrIntervalMs = 423,
        sessionId = session,
        isQuarantined = quarantined,
    )

    @Test
    @DisplayName("stored beats come back as trace samples, in order, windowed by the query")
    fun beatsBecomeTraceSamples() = runTest {
        val dao = FakeHrSampleDao()
        dao.insertAll(
            listOf(sample(1_000, 120), sample(2_000, 130), sample(9_000, 190)),
        )

        val beats = HrBeatReader(dao).beatsBetween(fromMs = 1_000, toMs = 2_000)

        assertEquals(listOf(TraceSample(1_000, 120), TraceSample(2_000, 130)), beats)
    }

    @Test
    @DisplayName("a ride nothing was recorded for reads back as no beats, not as an error")
    fun anEmptyWindowIsEmpty() = runTest {
        assertTrue(HrBeatReader(FakeHrSampleDao()).beatsBetween(0, 10_000).isEmpty())
    }
}
