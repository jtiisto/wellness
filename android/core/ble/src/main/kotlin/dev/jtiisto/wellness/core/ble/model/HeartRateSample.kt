package dev.jtiisto.wellness.core.ble.model

/**
 * One Heart Rate Measurement notification, as it arrived.
 *
 * [receivedAtMs] is the phone's wall clock at the moment the notification was
 * delivered, and it is the only time reference the strap gives us — the beats
 * it carries all happened *before* it. Placing them is
 * [dev.jtiisto.wellness.core.ble.buffer.IntervalBuffer]'s job.
 *
 * [rrIntervalsMs] is empty for an HR-only notification (the strap reports a BPM
 * without an interval), and an individual entry may legitimately be 0 — a
 * sensor artifact the strap chose to report rather than drop. Nothing in this
 * module polices physiological ranges; artifacts are data, and judging them
 * belongs to [dev.jtiisto.wellness.core.ble.quality.SignalQualityTracker] live
 * and to the server's analysis offline.
 *
 * [isGapBefore] means the stream is discontinuous here: the connection saw more
 * than three seconds without a notification, or dropped one under back
 * pressure. It travels on the sample so that whatever recorded the
 * discontinuity — the connection, not this type — is the thing that decides.
 */
data class HeartRateSample(
    val deviceId: String,
    val receivedAtMs: Long,
    val heartRateBpm: Int,
    val rrIntervalsMs: List<Int>,
    val isGapBefore: Boolean = false,
)
