package dev.jtiisto.wellness.core.ble.capture

/**
 * The capture in progress: which session it is, and which workout it belongs to.
 *
 * One value rather than an id and an anchor kept side by side, because the two
 * are only ever meaningful together — a reader that saw a new session id with
 * the previous session's anchor would attribute a recording to the wrong
 * workout, and separate flows make that window reachable.
 *
 * It lives in this module, not beside the Room entity that persists it, for the
 * same reason [dev.jtiisto.wellness.core.ble.buffer.BufferedSample] does: this
 * is the shape the BLE and UI sides need, and the entity is a storage detail on
 * the other side of the seam. `HrCaptureStore` publishes this; the capture
 * service folds it into [HrCaptureState] with [withCaptureSession].
 *
 * [workoutDate] is a local `YYYY-MM-DD` string and [workoutSessionId] the coach
 * workout-hook session id. Both are absent for a capture started from the strap
 * settings — the anchor is what a session *gains*, never what makes it valid.
 */
data class CaptureSession(
    val sessionId: String,
    val workoutDate: String? = null,
    val workoutSessionId: Long? = null,
)
