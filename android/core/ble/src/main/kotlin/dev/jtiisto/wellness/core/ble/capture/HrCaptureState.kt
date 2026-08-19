package dev.jtiisto.wellness.core.ble.capture

import dev.jtiisto.wellness.core.ble.model.ConnectionState
import dev.jtiisto.wellness.core.ble.quality.SignalQuality

/**
 * Everything the capture service tells the UI, and nothing more.
 *
 * The service and the screens never touch each other: the service writes this
 * into a Koin-owned `MutableStateFlow` and whatever is on screen collects it.
 * That is what lets capture keep running with no Activity alive, and lets a
 * screen opened halfway through a session render the right thing immediately.
 *
 * Deliberately no sample counter. The spec's surfaces are a BPM chip, a sheet
 * showing device / connection / signal quality, and a notification showing
 * connection state and BPM — a running row count appears on none of them, and
 * keeping one here would mean either a per-resume database scan or a number that
 * silently restarts. Counts go to the debug log, where they are diagnostics.
 *
 * [detail] carries the connect-progress and failure text from
 * [dev.jtiisto.wellness.core.ble.connection.ConnectDiagnostics]; null means
 * nothing is wrong.
 *
 * [isStreamStale] is deliberately not folded into [connectionState]. A strap can
 * stop sending while the link stays up, which is the failure the two fields exist
 * to tell apart: [connectionState] answers "is there a link", this answers "are
 * beats arriving", and only the second one licenses drawing [bpm] as a current
 * reading. See
 * [dev.jtiisto.wellness.core.ble.connection.StreamLivenessPolicy].
 *
 * [workoutDate] and [workoutSessionId] are the running session's workout anchor,
 * and they are published here rather than remembered by whoever started the
 * capture **because a ViewModel does not survive a process death and the session
 * row does**. A screen that held the anchor in a field would silently lose it
 * mid-workout, and End Workout would then decline to stop the very capture it
 * started. Read from here, the anchor comes back with the resumed session.
 */
data class HrCaptureState(
    val isRunning: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val bpm: Int? = null,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val sessionId: String? = null,
    val workoutDate: String? = null,
    val workoutSessionId: Long? = null,
    val signalQuality: SignalQuality = SignalQuality(),
    val detail: String? = null,
    val isStreamStale: Boolean = false,
)

/**
 * Fold the store's view of the running session — id and anchor together — into
 * the published state.
 *
 * A null [session] deliberately **changes nothing**. It means the capture has
 * ended, and the teardown that follows resets the whole state a moment later;
 * clearing the anchor here instead would open a window in which the state still
 * says `isRunning` but has forgotten which workout it belongs to, which is the
 * exact reading that makes End Workout skip a capture it ought to stop.
 *
 * The no-resurrection rule lives one level up, in the service's `publish`: every
 * update is dropped once the capture is no longer running, so a late emission
 * cannot bring a finished session back.
 */
fun HrCaptureState.withCaptureSession(session: CaptureSession?): HrCaptureState =
    if (session == null) {
        this
    } else {
        copy(
            sessionId = session.sessionId,
            workoutDate = session.workoutDate,
            workoutSessionId = session.workoutSessionId,
        )
    }
