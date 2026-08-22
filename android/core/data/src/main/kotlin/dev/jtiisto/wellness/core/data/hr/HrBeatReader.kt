package dev.jtiisto.wellness.core.data.hr

import dev.jtiisto.wellness.core.ble.trace.TraceSample
import dev.jtiisto.wellness.core.data.db.HrSampleDao

/**
 * Reads beats back out of the phone's own store.
 *
 * The capture side of the heart-rate module writes and uploads; this is the one
 * thing that reads, and it exists for the cardio guide's auto-fill: when a
 * guided ride finishes, the log's duration and heart rates are computed from
 * beats that are already here. **No server round trip** — the rows the strap
 * produced are on the device whether or not they have uploaded yet, and a fill
 * that waited for a sync would be a fill that did not happen on a train.
 *
 * It is deliberately its own class rather than a method on
 * [HrCaptureStore]: that store is a lifecycle actor with a write lease, and
 * nothing about reading a finished ride's beats belongs inside it.
 *
 * The rows come back as [TraceSample] — the same `(when, how fast)` pair the
 * live window is drawn from, because that is exactly what a beat is on either
 * side of the store, and one type means the guide's two readings of a heart rate
 * cannot drift apart.
 */
class HrBeatReader(private val dao: HrSampleDao) {

    /**
     * Beats stamped within `[fromMs, toMs]`, oldest first.
     *
     * Milliseconds are **data values** here, as everywhere in this protocol —
     * the phone's own wall clock at the moment the strap spoke, not a
     * server-issued watermark — so comparing them is legal.
     */
    suspend fun beatsBetween(fromMs: Long, toMs: Long): List<TraceSample> =
        dao.beatsBetween(fromMs, toMs).map { TraceSample(it.timestampMs, it.heartRateBpm) }
}
