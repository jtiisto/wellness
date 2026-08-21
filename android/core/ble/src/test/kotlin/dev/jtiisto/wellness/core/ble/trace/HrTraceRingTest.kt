package dev.jtiisto.wellness.core.ble.trace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier

/** A far-future base, so no value here can be mistaken for a real capture. */
private const val T0 = 1_893_456_000_000L

/**
 * The rolling window the guidance display draws from.
 *
 * Three properties carry this file. The first is that the window is a *display*
 * and therefore bounded: it drops its oldest sample rather than growing, and
 * what it drops is never the newest. The second is that every value it publishes
 * is a complete, immutable window — which is what lets a reader on another
 * thread hold one while the capture keeps recording. The third is that writing
 * is licensed per session: a recorder superseded by [HrTraceRing.beginSession]
 * writes nothing, which is what keeps a cancelled-but-still-running collector's
 * tail out of the next capture's window.
 */
class HrTraceRingTest {

    @Test
    @DisplayName("a fresh window is empty, which is what tells the display there is nothing to draw")
    fun startsEmpty() {
        assertEquals(emptyList<TraceSample>(), HrTraceRing().samples.value)
    }

    @Test
    @DisplayName("samples are kept in arrival order, exactly as recorded")
    fun keepsArrivalOrder() {
        val ring = HrTraceRing(capacity = 4)
        val trace = ring.beginSession()

        trace.record(T0, bpm = 118)
        trace.record(T0 + 1_000, bpm = 121)
        trace.record(T0 + 2_000, bpm = 119)

        assertEquals(
            listOf(
                TraceSample(T0, 118),
                TraceSample(T0 + 1_000, 121),
                TraceSample(T0 + 2_000, 119),
            ),
            ring.samples.value,
        )
    }

    @Test
    @DisplayName("overflow drops the oldest sample and nothing else")
    fun overflowDropsTheOldest() {
        val ring = HrTraceRing(capacity = 3)
        val trace = ring.beginSession()

        repeat(5) { index -> trace.record(T0 + index * 1_000L, bpm = 130 + index) }

        // The two oldest are gone; the three newest are in the order they
        // arrived. A window that dropped the newest, or reordered on eviction,
        // would draw a trace that never happened.
        assertEquals(
            listOf(
                TraceSample(T0 + 2_000, 132),
                TraceSample(T0 + 3_000, 133),
                TraceSample(T0 + 4_000, 134),
            ),
            ring.samples.value,
        )
    }

    @Test
    @DisplayName("the window never grows past its capacity, however long the capture runs")
    fun staysBounded() {
        val ring = HrTraceRing(capacity = 8)
        val trace = ring.beginSession()

        // Far more than a 30 s window holds: the memory cost of a two-hour ride
        // has to be the window, not the ride.
        repeat(5_000) { index -> trace.record(T0 + index * 1_000L, bpm = 140) }

        assertEquals(8, ring.samples.value.size)
        assertEquals(T0 + 4_999_000L, ring.samples.value.last().timestampMs)
    }

    @Test
    @DisplayName("the default capacity holds the spec's ~30 s window at any real notification density")
    fun defaultCapacityIsTheSpecWindow() {
        assertEquals(64, TRACE_WINDOW_CAPACITY)

        val ring = HrTraceRing()
        val trace = ring.beginSession()
        repeat(TRACE_WINDOW_CAPACITY + 10) { index -> trace.record(T0 + index * 1_000L, bpm = 150) }

        assertEquals(TRACE_WINDOW_CAPACITY, ring.samples.value.size)
    }

    @Test
    @DisplayName("a window that keeps nothing is not a window")
    fun capacityMustBePositive() {
        // Constructed once, from DI, so failing loudly at construction is the
        // cheap end of this: a zero-capacity ring would silently publish an
        // empty window for the whole of every capture.
        assertThrows(IllegalArgumentException::class.java) { HrTraceRing(capacity = 0) }
        assertThrows(IllegalArgumentException::class.java) { HrTraceRing(capacity = -1) }
    }

    @Test
    @DisplayName("a value already read is not changed by later samples")
    fun publishedWindowsAreImmutable() {
        val ring = HrTraceRing(capacity = 4)
        val trace = ring.beginSession()
        trace.record(T0, bpm = 112)

        val held = ring.samples.value
        trace.record(T0 + 1_000, bpm = 115)

        // The whole thread-safety argument in one assertion: the reader is on
        // another thread than the collector, and what it holds is a value, not a
        // view of a buffer being written.
        assertEquals(listOf(TraceSample(T0, 112)), held)
        assertEquals(2, ring.samples.value.size)
    }

    @Test
    @DisplayName("a new session opens on an empty window, so it cannot inherit the previous one's tail")
    fun beginSessionEmptiesTheWindow() {
        val ring = HrTraceRing(capacity = 4)
        val first = ring.beginSession()
        first.record(T0, bpm = 128)
        first.record(T0 + 1_000, bpm = 131)

        val second = ring.beginSession()

        assertEquals(emptyList<TraceSample>(), ring.samples.value)

        // And it is a new session, not a teardown: the next capture records into
        // the same instance under its own licence.
        second.record(T0 + 600_000, bpm = 96)
        assertEquals(listOf(TraceSample(T0 + 600_000, 96)), ring.samples.value)
    }

    @Test
    @DisplayName("a superseded recorder writes nothing — the old collector's tail cannot resurrect the old trace")
    fun supersededRecorderIsANoOp() {
        val ring = HrTraceRing(capacity = 4)
        val old = ring.beginSession()
        old.record(T0, bpm = 128)

        // Teardown cancels the collector without joining it, so `old` may still
        // be executing after the next capture has begun. Its write must land
        // nowhere: not in the new window, and not as a resurrection of the old.
        val fresh = ring.beginSession()
        old.record(T0 + 1_000, bpm = 133)

        assertEquals(emptyList<TraceSample>(), ring.samples.value)

        // The licensed recorder is unaffected before and after the stale write.
        fresh.record(T0 + 2_000, bpm = 97)
        old.record(T0 + 3_000, bpm = 134)
        assertEquals(listOf(TraceSample(T0 + 2_000, 97)), ring.samples.value)
    }

    @Test
    @DisplayName("what the strap said is stored verbatim — no sorting, no dedup, no repair")
    fun storesWhatItIsGiven() {
        val ring = HrTraceRing(capacity = 8)
        val trace = ring.beginSession()

        // Two notifications inside one millisecond, and a stamp that goes
        // backwards under a clock correction. Both are things the display should
        // be able to show; deciding what they mean is the geometry's job, and
        // silently repairing them here would hide the evidence.
        trace.record(T0, bpm = 144)
        trace.record(T0, bpm = 144)
        trace.record(T0 - 5_000, bpm = 143)

        assertEquals(
            listOf(
                TraceSample(T0, 144),
                TraceSample(T0, 144),
                TraceSample(T0 - 5_000, 143),
            ),
            ring.samples.value,
        )
    }

    @Test
    @DisplayName("a gap in the stream is a gap in the timestamps, not a missing sample")
    fun silenceIsVisibleInTheStamps() {
        val ring = HrTraceRing(capacity = 8)
        val trace = ring.beginSession()

        trace.record(T0, bpm = 137)
        // The strap goes quiet for half a minute — a dead subscription, skin
        // contact lost — and the next beat simply lands where it lands. Nothing
        // is inserted to represent the silence: the consumer reads it off the
        // interval.
        trace.record(T0 + 30_000, bpm = 129)

        val window = ring.samples.value
        assertEquals(2, window.size)
        assertEquals(30_000L, window[1].timestampMs - window[0].timestampMs)
    }

    @Test
    @DisplayName("the flow publishes every window the recorder commits")
    fun theFlowFollowsTheWindow() {
        val ring = HrTraceRing(capacity = 3)
        val trace = ring.beginSession()

        // `samples` is the published half and `record` the written one; they are
        // the same value, which is what makes collecting the flow equivalent to
        // asking for a snapshot.
        trace.record(T0, bpm = 101)
        assertEquals(ring.samples.value, listOf(TraceSample(T0, 101)))

        trace.record(T0 + 1_000, bpm = 104)
        assertEquals(ring.samples.value, listOf(TraceSample(T0, 101), TraceSample(T0 + 1_000, 104)))
    }

    @Test
    @DisplayName("concurrent recorders lose nothing and tear nothing")
    fun concurrentWritesAreAtomic() {
        val writers = 8
        val each = 50
        // Deliberately roomier than the records about to be made, so the final
        // size counts arrivals rather than measuring the eviction rule again.
        val ring = HrTraceRing(capacity = writers * each + 100)
        val trace = ring.beginSession()

        // Production has one recorder, but the display reads from another thread
        // and the compare-and-set is what makes that safe rather than a comment
        // saying so. Plain threads on purpose — a coroutine dispatcher would cap
        // the parallelism at the machine's core count and a barrier could then
        // starve it into deadlock — and a start barrier on purpose too: without
        // it the writers can run one after another, and the test passes over an
        // implementation with no atomicity at all.
        val startTogether = CyclicBarrier(writers)
        (0 until writers)
            .map { writer ->
                Thread {
                    startTogether.await()
                    repeat(each) { index ->
                        trace.record(T0 + index * 1_000L, bpm = 100 + writer)
                    }
                }.apply { start() }
            }
            .forEach { it.join() }

        val window = ring.samples.value
        // Every record landed. A read-modify-write that was not atomic would
        // drop the losers of each race and leave this short — which is the whole
        // reason the window is replaced by compare-and-set rather than mutated.
        assertEquals(writers * each, window.size)
        // And every entry is one somebody recorded — nothing half-written, no
        // sample built from two writers' fields.
        assertTrue(window.all { it.bpm in 100 until 100 + writers })
        assertTrue(window.all { it.timestampMs in T0 until T0 + each * 1_000L })
    }
}
