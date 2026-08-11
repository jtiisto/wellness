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
 * The invariant: a beat may only be dated inside this window, so a later
 * notification can never anchor onto a millisecond the memory has forgotten.
 * [MAX_ANCHOR_REACH_MS] is what makes that true — the reach is clamped rather
 * than assumed, because nothing upstream bounds it. The characteristic encodes
 * each RR as a 16-bit count of 1/1024 s, so a single interval can legally
 * arrive at nearly 64 s and a nine-interval bundle at nearly ten minutes; the
 * parser passes those through, as it should, since they are what the strap
 * said.
 *
 * Bundle arithmetic is not the only way a millisecond gets revisited: a
 * real-time clock correction stepping backwards returns receipt times to
 * milliseconds already used, with no bundle reaching anywhere. That is why the
 * prune below measures its cutoff from the newest millisecond seen rather than
 * from the one being written.
 *
 * **Caveat, accepted**: a large *forward* clock step is the one case this does
 * not cover. It drags the cutoff past entries that are still current, pruning
 * live milliseconds, and when the clock is corrected back a beat can land on
 * one of them at seq 0 — a duplicate key the `INSERT OR IGNORE` downstream
 * drops. Bounded to one row per collision, and it takes a clock jump of a
 * minute or more to reach at all.
 */
internal const val COLLISION_MEMORY_MS = 60_000L

/**
 * The furthest back a notification may date its own beats, five seconds inside
 * [COLLISION_MEMORY_MS]. Beats anchored past it compress onto the boundary,
 * where seq keeps them distinct and ordered.
 *
 * This costs nothing real: reaching it needs intervals summing to 55 s in one
 * notification, which is a strap reporting minutes of beats in a single packet,
 * not a heart. What it buys is that the memory window's invariant holds by
 * construction instead of by an assumption about how strap firmware behaves.
 */
internal const val MAX_ANCHOR_REACH_MS = 55_000L

/**
 * When the prune scan runs: once a device's memory holds more entries than
 * this. It is a trigger, not a cap — nothing is evicted for being numerous,
 * only for being older than [COLLISION_MEMORY_MS], so a burst of genuinely
 * recent milliseconds can carry the map past this number and keep it there.
 * The real bound on size is the window: beats per second times sixty, about
 * 180 entries at a normal rate, and every one of them is a `Long` and an `Int`.
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
 * The one addition to those rules is [MAX_ANCHOR_REACH_MS]: a notification may
 * not date a beat further back than that, and anything beyond compresses onto
 * the boundary. Pulse-bridge trusted the arithmetic, which is safe for every
 * bundle a heart produces and unsafe for the ones the wire format permits.
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
     *
     * The result is then floored at [MAX_ANCHOR_REACH_MS] before receipt. Since
     * the placements are strictly increasing, that affects a prefix of them and
     * collapses it onto the boundary, where [nextSeq] keeps the beats distinct
     * and in order — the same mechanism that handles two notifications sharing
     * a receipt millisecond.
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
        val earliestAllowed = receivedAtMs - MAX_ANCHOR_REACH_MS
        for (i in timestamps.indices) {
            if (timestamps[i] >= earliestAllowed) break
            timestamps[i] = earliestAllowed
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
