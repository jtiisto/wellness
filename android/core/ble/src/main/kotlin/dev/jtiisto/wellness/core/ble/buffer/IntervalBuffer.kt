package dev.jtiisto.wellness.core.ble.buffer

import dev.jtiisto.wellness.core.ble.BleLog
import dev.jtiisto.wellness.core.ble.model.HeartRateSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How far back a millisecond stays worth remembering for [IntervalBuffer.nextSeq].
 *
 * This is the invariant the constant protects: a notification reaches backwards
 * exactly as far as the intervals it carries, so the memory must outlast the
 * longest reach any single notification can have. At the default 23-byte ATT
 * MTU a Heart Rate Measurement has 20 payload bytes — flags plus an 8-bit BPM
 * leave 18, so nine 16-bit RR intervals — and even a pathological interval a
 * strap will report stays under ~2.5 s. That puts the worst case near 22 s, and
 * sixty seconds clears it with room for a negotiated larger MTU.
 *
 * Bundle arithmetic is not the only way a millisecond gets revisited: a
 * real-time clock correction stepping backwards returns receipt times to
 * milliseconds already used, with no bundle reaching anywhere. That makes the
 * memory worth more than the reach calculation alone suggests, and it is why
 * the prune below measures its cutoff from the newest millisecond seen rather
 * than from the one being written.
 *
 * A collision older than this is not prevented: the beat takes seq 0 again and
 * the duplicate key is dropped by the `INSERT OR IGNORE` downstream. That needs
 * a notification reaching back a full minute, which is beyond what the
 * characteristic can physically carry.
 */
internal const val COLLISION_MEMORY_MS = 60_000L

/**
 * Amortizes the prune scan: it runs once a device's memory exceeds this. Purely
 * a cost knob — correctness belongs to [COLLISION_MEMORY_MS], and holding more
 * entries than that window needs only wastes a few kilobytes. A minute of beats
 * is about 180 entries at a normal rate, so the scan is rare.
 */
internal const val MAX_TRACKED_MILLIS = 1536

/**
 * Turns HRM notifications into one row per RR interval and hands them to [sink]
 * in batches.
 *
 * ## Anchoring
 *
 * Ported from pulse-bridge, where it was proven over months of captures. The
 * beats in a notification all happened *before* it arrived, so the last one
 * lands at [HeartRateSample.receivedAtMs] and the earlier ones are placed
 * backward by the intervals that follow them. Zero-RR sentinels carry no
 * duration and collapse onto the anchor; they are spread backward a
 * millisecond at a time, never forward, because a beat dated after the
 * notification that reported it would collide with the next real one. A
 * discontinuity ([HeartRateSample.isGapBefore], set by the connection on a
 * >3 s silence or a dropped notification) marks the first row of the group
 * only — it describes the boundary, not every beat behind it.
 *
 * ## seq — the one deliberate change from pulse-bridge
 *
 * There the primary key was (`deviceId`, `timestampDevice`) and uniqueness was
 * bought by bumping a colliding beat one millisecond past the last one stored.
 * That falsified the inter-beat spacing the DFA analysis measures straight off
 * those timestamps; pulse-bridge's `CLAUDE.md:185` records it as a known,
 * deferred flaw needing "a sequence component in the PK". Phase 1's schema is
 * that fix: beats that genuinely share a millisecond are stored as seq 0, 1,
 * 2… *at* that millisecond, and no timestamp is ever moved. The server reads
 * beats back `ORDER BY timestamp_ms, seq`, which is why emission order is the
 * order seq is handed out in.
 *
 * ## Durability
 *
 * A failed flush keeps its batch and reports `false`, because clearing before
 * the write is confirmed loses beats to a transient database error. The buffer
 * is a plain in-memory list: it does not survive process death, which is what
 * bounds the loss to one flush interval.
 *
 * @param scope owns the flush timer; it must outlive the capture service so a
 *   crash mid-capture still drains what was buffered.
 */
class IntervalBuffer(
    private val sink: HrSampleSink,
    private val scope: CoroutineScope,
    private val config: BufferConfig = BufferConfig(),
    private val log: BleLog = BleLog {},
) {
    private val buffer = mutableListOf<BufferedSample>()
    private val mutex = Mutex()
    private var flushJob: Job? = null

    /** Per device, the next free seq for each recently used millisecond. */
    private val recentSeqByDevice = mutableMapOf<String, MutableMap<Long, Int>>()

    val size: Int get() = buffer.size

    fun start() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (true) {
                delay(config.flushInterval)
                flush()
            }
        }
    }

    fun stop() {
        flushJob?.cancel()
        flushJob = null
    }

    /** @return the number of rows [sample] produced */
    suspend fun add(sample: HeartRateSample, sessionId: String): Int = mutex.withLock {
        val rows = mapToRows(sample, sessionId)
        buffer.addAll(rows)
        if (buffer.size >= config.maxBufferSize) {
            flushLocked()
        }
        rows.size
    }

    /** @return true when the buffer is fully persisted (or was already empty) */
    suspend fun flush(): Boolean = mutex.withLock { flushLocked() }

    private suspend fun flushLocked(): Boolean {
        if (buffer.isEmpty()) return true
        val batch = buffer.toList()
        try {
            sink.store(batch)
        } catch (e: CancellationException) {
            throw e // batch kept; the buffer's scope outlives the capture
        } catch (e: Exception) {
            // The sink is local persistence, so the message is a database
            // error and never a server body — safe to log, unlike the upload
            // path's, and the whole point of logging this at all.
            log.log("interval buffer flush FAILED, keeping ${batch.size} rows: ${e.message}")
            return false
        }
        buffer.clear()
        return true
    }

    private fun mapToRows(sample: HeartRateSample, sessionId: String): List<BufferedSample> {
        val rrs = sample.rrIntervalsMs
        if (rrs.isEmpty()) {
            // HR-only notification: one sentinel row, so that the strap having
            // reported without an interval is in the data rather than lost.
            return listOf(
                row(sample, sample.receivedAtMs, rrMs = 0, isGapBefore = sample.isGapBefore, sessionId),
            )
        }
        val timestamps = anchorBackward(sample.receivedAtMs, rrs)
        return rrs.mapIndexed { index, rrMs ->
            row(sample, timestamps[index], rrMs, isGapBefore = sample.isGapBefore && index == 0, sessionId)
        }
    }

    /**
     * Places each beat of a notification: the last at [receivedAtMs], every
     * earlier one that far back minus the intervals after it. Zero-length
     * intervals leave beats sharing a millisecond, and those are pushed
     * backward so the sequence stays strictly increasing without anything
     * landing past receipt.
     */
    private fun anchorBackward(receivedAtMs: Long, rrs: List<Int>): LongArray {
        val timestamps = LongArray(rrs.size) { index ->
            val remainingMs = rrs.subList(index + 1, rrs.size).sumOf { it.toLong() }
            receivedAtMs - remainingMs
        }
        for (i in timestamps.size - 2 downTo 0) {
            if (timestamps[i] >= timestamps[i + 1]) {
                timestamps[i] = timestamps[i + 1] - 1
            }
        }
        return timestamps
    }

    private fun row(
        sample: HeartRateSample,
        timestampMs: Long,
        rrMs: Int,
        isGapBefore: Boolean,
        sessionId: String,
    ) = BufferedSample(
        deviceId = sample.deviceId,
        timestampMs = timestampMs,
        seq = nextSeq(sample.deviceId, timestampMs),
        heartRateBpm = sample.heartRateBpm,
        rrIntervalMs = rrMs,
        isGapBefore = isGapBefore,
        sessionId = sessionId,
    )

    /**
     * The seq for [timestampMs] on [deviceId]: 0 the first time that
     * millisecond is used, 1 for the next beat that lands on it, and so on.
     * Two notifications sharing a receipt millisecond are the common case; a
     * late bundle reaching back over a beat already stored is the other.
     *
     * The memory is bounded by [COLLISION_MEMORY_MS] rather than kept for the
     * whole capture — remembering every millisecond of a two-hour session would
     * grow without limit to protect against collisions that can no longer
     * happen. Must be called under [mutex], in emission order.
     */
    private fun nextSeq(deviceId: String, timestampMs: Long): Int {
        val used = recentSeqByDevice.getOrPut(deviceId) { mutableMapOf() }
        val seq = used[timestampMs] ?: 0
        used[timestampMs] = seq + 1
        if (used.size > MAX_TRACKED_MILLIS) {
            // Off the newest millisecond seen, not the one just written: a
            // backward clock step must not throw away live entries.
            val cutoff = used.keys.max() - COLLISION_MEMORY_MS
            used.keys.retainAll { it >= cutoff }
        }
        return seq
    }
}
