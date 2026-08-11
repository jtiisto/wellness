package dev.jtiisto.wellness.core.ble.buffer

/**
 * One RR interval, placed in time and ready to persist.
 *
 * The fields are the `hr_samples` columns a capture produces, but this is not
 * that entity: Room lives in `:core:data` and this module is a leaf, so
 * [HrSampleSink] owns the mapping. The primary key over there is
 * (`deviceId`, `timestampMs`, `seq`) — see [IntervalBuffer] for what makes the
 * three of them unique.
 *
 * [rrIntervalMs] is 0 for the row an HR-only notification produces, matching
 * the sentinel the schema documents. [sessionId] is not nullable because a
 * sample only exists inside a capture session.
 */
data class BufferedSample(
    val deviceId: String,
    val timestampMs: Long,
    val seq: Int,
    val heartRateBpm: Int,
    val rrIntervalMs: Int,
    val isGapBefore: Boolean,
    val sessionId: String,
)
