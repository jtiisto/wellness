package dev.jtiisto.wellness.core.ble.buffer

import dev.jtiisto.wellness.core.ble.BleLog
import dev.jtiisto.wellness.core.ble.model.HeartRateSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

private const val SESSION = "session-1"
private const val DEVICE = "AA:BB:CC:DD:EE:FF"

/** Records what was stored, and can be told to fail the next N stores. */
private class RecordingSink : HrSampleSink {
    val batches = mutableListOf<List<BufferedSample>>()
    var failures = 0
    var thrown: Exception = RuntimeException("database or disk is full")

    val rows: List<BufferedSample> get() = batches.flatten()

    override suspend fun store(samples: List<BufferedSample>) {
        if (failures > 0) {
            failures--
            throw thrown
        }
        batches.add(samples)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class IntervalBufferTest {

    private val sink = RecordingSink()

    private fun buffer(
        scope: CoroutineScope,
        config: BufferConfig = BufferConfig(flushInterval = 10.seconds, maxBufferSize = 5),
        log: BleLog = BleLog {},
    ) = IntervalBuffer(sink = sink, scope = scope, config = config, log = log)

    private fun sample(
        deviceId: String = DEVICE,
        receivedAtMs: Long = 1000L,
        heartRateBpm: Int = 72,
        rrIntervalsMs: List<Int> = listOf(833),
        isGapBefore: Boolean = false,
    ) = HeartRateSample(
        deviceId = deviceId,
        receivedAtMs = receivedAtMs,
        heartRateBpm = heartRateBpm,
        rrIntervalsMs = rrIntervalsMs,
        isGapBefore = isGapBefore,
    )

    // ---- buffering and flushing -------------------------------------------

    @Test
    fun `add accumulates rows without storing them`() = runTest {
        val buffer = buffer(backgroundScope)

        buffer.add(sample(receivedAtMs = 1000L), SESSION)
        buffer.add(sample(receivedAtMs = 2000L), SESSION)

        assertEquals(2, buffer.size)
        assertEquals(0, sink.batches.size)
    }

    @Test
    fun `flush hands the buffered rows to the sink`() = runTest {
        val buffer = buffer(backgroundScope)

        buffer.add(sample(receivedAtMs = 1000L), SESSION)
        buffer.add(sample(receivedAtMs = 2000L), SESSION)
        assertTrue(buffer.flush())

        assertEquals(0, buffer.size)
        assertEquals(1, sink.batches.size)
        assertEquals(2, sink.batches[0].size)
    }

    @Test
    fun `flush is a no-op when the buffer is empty`() = runTest {
        val buffer = buffer(backgroundScope)

        assertTrue(buffer.flush())

        assertEquals(0, sink.batches.size)
    }

    @Test
    fun `auto-flushes when maxBufferSize is reached`() = runTest {
        val buffer = buffer(backgroundScope, BufferConfig(maxBufferSize = 3, flushInterval = 60.seconds))

        buffer.add(sample(receivedAtMs = 1000L), SESSION)
        buffer.add(sample(receivedAtMs = 2000L), SESSION)
        assertEquals(0, sink.batches.size)

        buffer.add(sample(receivedAtMs = 3000L), SESSION)

        assertEquals(1, sink.batches.size)
        assertEquals(0, buffer.size)
    }

    @Test
    fun `timer flush triggers at the configured interval`() = runTest {
        val buffer = buffer(backgroundScope, BufferConfig(flushInterval = 5.seconds, maxBufferSize = 100))
        buffer.start()

        buffer.add(sample(receivedAtMs = 1000L), SESSION)
        assertEquals(0, sink.batches.size)

        advanceTimeBy(5_001)
        assertEquals(1, sink.batches.size)

        buffer.stop()
        buffer.add(sample(receivedAtMs = 2000L), SESSION)
        advanceTimeBy(20_000)
        assertEquals(1, sink.batches.size)
    }

    @Test
    fun `a failed flush keeps the batch for a later retry`() = runTest {
        sink.failures = 1
        val buffer = buffer(backgroundScope)

        buffer.add(sample(receivedAtMs = 1000L, rrIntervalsMs = listOf(800, 850)), SESSION)

        assertFalse(buffer.flush())
        assertEquals(0, sink.batches.size)
        assertEquals(2, buffer.size)

        assertTrue(buffer.flush())
        assertEquals(1, sink.batches.size)
        assertEquals(2, sink.batches[0].size)
        assertEquals(0, buffer.size)
    }

    @Test
    fun `a failed flush is reported to the log by exception type, never by message`() = runTest {
        sink.failures = 1
        // The log is a shareable dump, and the sink is on the other side of an
        // interface: what its exceptions say is not this module's to promise.
        // The type and the row count are what a diagnosis reads anyway.
        sink.thrown = IllegalStateException("FORBIDDEN-SINK-DETAIL")
        val logged = mutableListOf<String>()
        val buffer = buffer(backgroundScope, log = { logged += it })

        buffer.add(sample(), SESSION)
        buffer.flush()

        assertEquals(1, logged.size)
        assertTrue(logged[0].contains("keeping 1 rows"), logged[0])
        assertTrue(logged[0].contains("IllegalStateException"), logged[0])
        assertFalse(logged[0].contains("FORBIDDEN-SINK-DETAIL"), logged[0])
    }

    @Test
    fun `cancellation propagates and keeps the batch`() = runTest {
        sink.failures = 1
        sink.thrown = CancellationException("scope died mid-flush")
        val buffer = buffer(backgroundScope)

        buffer.add(sample(), SESSION)
        val failure = runCatching { buffer.flush() }.exceptionOrNull()

        // Swallowing it would report a durable write that never happened, and
        // the rows have to survive for whoever flushes next
        assertTrue(failure is CancellationException)
        assertEquals(1, buffer.size)
    }

    @Test
    fun `add returns the number of rows generated`() = runTest {
        val buffer = buffer(backgroundScope)

        assertEquals(2, buffer.add(sample(rrIntervalsMs = listOf(800, 850)), SESSION))
        assertEquals(1, buffer.add(sample(receivedAtMs = 5000L, rrIntervalsMs = emptyList()), SESSION))
    }

    // ---- anchoring ---------------------------------------------------------

    @Test
    fun `maps a single RR interval to a single row`() = runTest {
        val buffer = buffer(backgroundScope)

        buffer.add(sample(rrIntervalsMs = listOf(833)), SESSION)
        buffer.flush()

        assertEquals(1, sink.rows.size)
        assertEquals(
            BufferedSample(
                deviceId = DEVICE,
                timestampMs = 1000L,
                seq = 0,
                heartRateBpm = 72,
                rrIntervalMs = 833,
                isGapBefore = false,
                sessionId = SESSION,
            ),
            sink.rows[0],
        )
    }

    @Test
    fun `maps multiple RR intervals with the last beat anchored at receipt time`() = runTest {
        val buffer = buffer(backgroundScope)

        buffer.add(sample(receivedAtMs = 10_000L, rrIntervalsMs = listOf(800, 850, 820)), SESSION)
        buffer.flush()

        val rows = sink.rows
        assertEquals(3, rows.size)

        // Beats precede the notification: each is receipt time minus the RRs after it
        assertEquals(8_330L, rows[0].timestampMs) // 10000 - (850 + 820)
        assertEquals(800, rows[0].rrIntervalMs)

        assertEquals(9_180L, rows[1].timestampMs) // 10000 - 820
        assertEquals(850, rows[1].rrIntervalMs)

        assertEquals(10_000L, rows[2].timestampMs) // last beat = receipt time
        assertEquals(820, rows[2].rrIntervalMs)
    }

    @Test
    fun `no generated timestamp exceeds the notification receipt time`() = runTest {
        val buffer = buffer(backgroundScope, BufferConfig(maxBufferSize = 100))

        buffer.add(sample(receivedAtMs = 10_000L, rrIntervalsMs = listOf(700, 750, 800, 720)), SESSION)
        // Trailing zero-RR sentinels are the collision case that used to leak
        // into the future — they must also stay at or before receipt time
        buffer.add(sample(receivedAtMs = 20_000L, rrIntervalsMs = listOf(700, 750, 0, 0)), SESSION)
        buffer.flush()

        val rows = sink.rows
        assertTrue(rows.take(4).all { it.timestampMs <= 10_000L })
        assertTrue(rows.drop(4).all { it.timestampMs <= 20_000L })
    }

    @Test
    fun `zero RR sentinels are spread backward within the notification`() = runTest {
        val buffer = buffer(backgroundScope)

        buffer.add(sample(receivedAtMs = 1000L, rrIntervalsMs = listOf(800, 0, 0)), SESSION)
        buffer.flush()

        // Zero RRs collapse onto the anchor and are spread BACKWARD — the last
        // beat stays at receipt time and nothing lands in the future. They are
        // distinct milliseconds, so seq stays 0 throughout.
        assertEquals(listOf(998L, 999L, 1000L), sink.rows.map { it.timestampMs })
        assertEquals(listOf(0, 0, 0), sink.rows.map { it.seq })
        assertEquals(listOf(800, 0, 0), sink.rows.map { it.rrIntervalMs })
    }

    @Test
    fun `an all-zero RR array spreads backward from receipt time`() = runTest {
        val buffer = buffer(backgroundScope)

        // Every interval is the artifact sentinel, so every beat anchors to
        // receipt time and the whole group has to be spread apart backward
        buffer.add(sample(receivedAtMs = 1000L, rrIntervalsMs = listOf(0, 0, 0)), SESSION)
        buffer.flush()

        assertEquals(listOf(998L, 999L, 1000L), sink.rows.map { it.timestampMs })
        assertEquals(listOf(0, 0, 0), sink.rows.map { it.seq })
        assertEquals(listOf(0, 0, 0), sink.rows.map { it.rrIntervalMs })
    }

    @Test
    fun `an all-zero RR array landing on a used millisecond takes the next seq`() = runTest {
        val buffer = buffer(backgroundScope, BufferConfig(maxBufferSize = 100))

        buffer.add(sample(receivedAtMs = 1000L, rrIntervalsMs = listOf(800)), SESSION)
        buffer.add(sample(receivedAtMs = 1000L, rrIntervalsMs = listOf(0, 0, 0)), SESSION)
        buffer.flush()

        // The spread lands the last sentinel on the millisecond the earlier
        // notification already used; the two before it are fresh
        assertEquals(
            listOf(1000L to 0, 998L to 0, 999L to 0, 1000L to 1),
            sink.rows.map { it.timestampMs to it.seq },
        )
    }

    @Test
    fun `a pathological bundle is compressed into the collision window`() = runTest {
        val buffer = buffer(backgroundScope, BufferConfig(maxBufferSize = 100))

        // Three 60 s intervals: legal on the wire — the characteristic encodes
        // RR as 1/1024 s in 16 bits — and 2 minutes of reach if trusted, which
        // is further back than the seq memory remembers
        buffer.add(sample(receivedAtMs = 1_000_000L, rrIntervalsMs = listOf(60_000, 60_000, 60_000)), SESSION)
        buffer.flush()

        val floor = 1_000_000L - MAX_ANCHOR_REACH_MS
        assertTrue(sink.rows.all { it.timestampMs >= floor })
        // The two beyond the boundary sit on it, told apart by seq; the last
        // beat still lands at receipt time
        assertEquals(
            listOf(floor to 0, floor to 1, 1_000_000L to 0),
            sink.rows.map { it.timestampMs to it.seq },
        )
    }

    @Test
    fun `the clamp leaves a bundle that fits and compresses one that does not`() = runTest {
        val buffer = buffer(backgroundScope, BufferConfig(maxBufferSize = 100))
        val receipt = 1_000_000L
        val floor = receipt - MAX_ANCHOR_REACH_MS

        // One second inside the boundary: the true anchor is kept
        buffer.add(sample(deviceId = "AA:01", receivedAtMs = receipt, rrIntervalsMs = listOf(800, 54_000)), SESSION)
        // One second outside: compressed to the boundary, not to its own anchor
        buffer.add(sample(deviceId = "AA:02", receivedAtMs = receipt, rrIntervalsMs = listOf(800, 56_000)), SESSION)
        buffer.flush()

        val earliest = sink.rows.groupBy { it.deviceId }.mapValues { (_, rows) -> rows.minOf { it.timestampMs } }
        assertEquals(receipt - 54_000L, earliest["AA:01"])
        assertEquals(floor, earliest["AA:02"]) // and not receipt - 56_000
    }

    // ---- gap marking -------------------------------------------------------

    @Test
    fun `an HR-only notification becomes one sentinel row`() = runTest {
        val buffer = buffer(backgroundScope)

        buffer.add(sample(rrIntervalsMs = emptyList(), isGapBefore = true), SESSION)
        buffer.flush()

        val row = sink.rows[0]
        assertEquals(0, row.rrIntervalMs)
        assertEquals(1000L, row.timestampMs)
        assertTrue(row.isGapBefore)
    }

    @Test
    fun `the gap flag lands only on the first row of a multi-RR notification`() = runTest {
        val buffer = buffer(backgroundScope)

        buffer.add(sample(rrIntervalsMs = listOf(800, 850), isGapBefore = true), SESSION)
        buffer.flush()

        assertEquals(listOf(true, false), sink.rows.map { it.isGapBefore })
    }

    // ---- seq: what replaced pulse-bridge's monotonic timestamp bump --------
    // The collision memory is a 60 s window, which covers every reach a Heart
    // Rate Measurement can physically have. A notification reaching back
    // further than that would take seq 0 on an already-used millisecond and
    // lose the row to INSERT OR IGNORE — deliberately untested, because the
    // characteristic cannot carry enough intervals to produce it.

    @Test
    fun `notifications sharing a receipt millisecond keep it and take the next seq`() = runTest {
        val buffer = buffer(backgroundScope, BufferConfig(maxBufferSize = 100))

        // Pulse-bridge stored the second of these at 1001 to keep the PK
        // unique, inventing a 1 ms interval the strap never reported
        buffer.add(sample(receivedAtMs = 1000L, rrIntervalsMs = listOf(800)), SESSION)
        buffer.add(sample(receivedAtMs = 1000L, rrIntervalsMs = listOf(810)), SESSION)
        buffer.add(sample(receivedAtMs = 1000L, rrIntervalsMs = listOf(820)), SESSION)
        buffer.flush()

        assertEquals(listOf(1000L, 1000L, 1000L), sink.rows.map { it.timestampMs })
        assertEquals(listOf(0, 1, 2), sink.rows.map { it.seq })
        assertEquals(listOf(800, 810, 820), sink.rows.map { it.rrIntervalMs })
    }

    @Test
    fun `a late bundle reaching back onto a stored beat shares its millisecond`() = runTest {
        val buffer = buffer(backgroundScope, BufferConfig(maxBufferSize = 100))

        // Second notification's first beat anchors to 10_000 — exactly where
        // the first notification's last beat already sits
        buffer.add(sample(receivedAtMs = 10_000L, rrIntervalsMs = listOf(500, 500)), SESSION)
        buffer.add(sample(receivedAtMs = 10_800L, rrIntervalsMs = listOf(800, 800)), SESSION)
        buffer.flush()

        assertEquals(
            listOf(9_500L to 0, 10_000L to 0, 10_000L to 1, 10_800L to 0),
            sink.rows.map { it.timestampMs to it.seq },
        )
    }

    @Test
    fun `seq is counted per device`() = runTest {
        val buffer = buffer(backgroundScope, BufferConfig(maxBufferSize = 100))

        buffer.add(sample(deviceId = "AA:01", receivedAtMs = 1000L), SESSION)
        buffer.add(sample(deviceId = "AA:02", receivedAtMs = 1000L), SESSION)
        buffer.add(sample(deviceId = "AA:01", receivedAtMs = 1000L), SESSION)
        buffer.flush()

        // The primary key starts with deviceId, so two straps may share a
        // millisecond at seq 0; only the repeat on AA:01 advances
        assertEquals(
            listOf(Triple("AA:01", 1000L, 0), Triple("AA:02", 1000L, 0), Triple("AA:01", 1000L, 1)),
            sink.rows.map { Triple(it.deviceId, it.timestampMs, it.seq) },
        )
    }

    @Test
    fun `a long capture keeps every key unique without growing the seq memory`() = runTest {
        val buffer = buffer(backgroundScope, BufferConfig(maxBufferSize = 100))

        // Past the point where the collision memory prunes itself: distinct
        // milliseconds must still come back at seq 0, and nothing may collide
        val beats = MAX_TRACKED_MILLIS + 200
        repeat(beats) { index ->
            buffer.add(sample(receivedAtMs = 1000L + index * 800L), SESSION)
        }
        buffer.flush()

        val rows = sink.rows
        assertEquals(beats, rows.size)
        assertTrue(rows.all { it.seq == 0 })
        assertEquals(beats, rows.map { it.deviceId to it.timestampMs }.toSet().size)
    }

    @Test
    fun `a bundle reaching sixteen seconds back still finds the millisecond it collides with`() = runTest {
        val buffer = buffer(backgroundScope, BufferConfig(maxBufferSize = 10_000))

        // Fill the memory to exactly one past the prune threshold, so the scan
        // runs on the last of these beats and the assertion below is about what
        // the prune KEPT — under a 10 s window the target is dropped here and
        // the colliding beat comes back at seq 0, which is the regression.
        val spacing = 800L
        val base = 1_000_000L
        val beats = MAX_TRACKED_MILLIS + 1
        repeat(beats) { index ->
            buffer.add(sample(receivedAtMs = base + (index + 1) * spacing), SESSION)
        }
        val newest = base + beats * spacing
        val target = newest - 16_000L // 20 beats back: outside 10 s, inside 60 s

        // A bundle of nine 2100 ms intervals — the pathological shape the
        // window is dimensioned for — reaching 16.8 s back from a receipt one
        // beat-interval after the newest
        buffer.add(
            sample(receivedAtMs = newest + spacing, rrIntervalsMs = List(9) { 2100 }),
            SESSION,
        )
        buffer.flush()

        val atTarget = sink.rows.filter { it.timestampMs == target }
        assertEquals(listOf(0, 1), atTarget.map { it.seq })
        // and the eight beats of the bundle that landed on fresh milliseconds
        // are unaffected
        assertEquals(8, sink.rows.takeLast(9).count { it.seq == 0 })
    }

    @Test
    fun `session id is carried onto every row`() = runTest {
        val buffer = buffer(backgroundScope)

        buffer.add(sample(rrIntervalsMs = listOf(800, 850)), "workout-42")
        buffer.flush()

        assertTrue(sink.rows.all { it.sessionId == "workout-42" })
    }
}
