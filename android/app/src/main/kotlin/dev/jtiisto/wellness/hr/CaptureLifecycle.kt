package dev.jtiisto.wellness.hr

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.data.hr.CaptureStopResult

/**
 * The capture service's start/stop bookkeeping, kept out of the service so it
 * can be tested.
 *
 * Three loose booleans in a foreground service is how both of the failures this
 * replaces happened. A flag that a thrown exception left set refused every later
 * start, and a startup still suspended when a stop arrived went on to open a GATT
 * link that nothing was tracking. Neither is visible in a unit test of the
 * service — it is device-only glue and excluded from the coverage gate — so the
 * decisions live here, where a regression fails a test instead of a workout.
 *
 * **Main-thread confined.** Every caller is `onStartCommand`, `onDestroy`, or a
 * coroutine on the service's `Dispatchers.Main.immediate` scope, so the plain
 * fields need no synchronization and the claim methods are genuinely atomic
 * against each other.
 */
class CaptureLifecycle {

    private var starting = false
    private var stopping = false

    /**
     * Take ownership of a start.
     *
     * @return false when one is already in flight — the second request is a
     *   duplicate intent, not a second capture, and must do nothing at all
     */
    fun claimStart(): Boolean {
        if (starting) return false
        starting = true
        stopping = false
        return true
    }

    /**
     * Whether an asynchronous startup that has just resumed may keep going.
     *
     * Checked at every point the startup comes back from a suspension, and
     * immediately before it connects. Cancelling the startup job is the first
     * line of defence, but cancellation is only observed at suspension points and
     * the run-up to `connectGatt` has none — so a startup that was cancelled
     * mid-stretch would still open the link. This is what stops it.
     */
    fun mayContinueStartup(): Boolean = starting && !stopping

    /**
     * Take ownership of a stop.
     *
     * @return false when a teardown is already running. The second caller must
     *   not run one too: the sequence disconnects, closes the session and calls
     *   `stopSelf`, and doing that twice concurrently races the first one.
     */
    fun claimStop(): Boolean {
        if (stopping) return false
        stopping = true
        return true
    }

    /**
     * Teardown finished — the service is idle again.
     *
     * Reached from a `finally`, which is the entire point: leaving [stopping] set
     * because a disconnect threw is what made a leaked capture unrecoverable, as
     * every subsequent stop was refused as a duplicate.
     */
    fun released() {
        starting = false
        stopping = false
    }
}

/**
 * Whether the shared sample buffer's flush timer may stop now that a capture has
 * ended.
 *
 * Only when the buffer's last batch actually landed. After
 * [CaptureStopResult.LEFT_OPEN] the rows are still in memory and the buffer's own
 * app-lived retry is the only thing that will ever get them down — stopping its
 * timer there strands them, and with them the deferred close that is waiting on
 * them. [CaptureStopResult.NOT_RUNNING] means this service never had a capture to
 * flush, so whatever the timer is doing belongs to someone else.
 */
fun bufferTimerStops(result: CaptureStopResult): Boolean = when (result) {
    CaptureStopResult.CLOSED, CaptureStopResult.SESSION_GONE -> true
    CaptureStopResult.LEFT_OPEN, CaptureStopResult.NOT_RUNNING -> false
}

/**
 * Whether a publisher that has just written the shared state may also repaint the
 * notification.
 *
 * The notification trails the state and must never lead it. Two things can leave
 * a publisher holding a value that is no longer true by the time it reaches
 * `notify()`:
 *
 * - **Its write never landed.** A capture that has been torn down rejects further
 *   updates, and painting the state it *would* have written resurrects a finished
 *   session on screen.
 * - **A teardown ran in between.** The write landed, then the teardown reset the
 *   state and removed the notification — and this publisher, still holding the
 *   old value, would paint "capture running" over the removal. A ghost
 *   notification for a capture that has stopped, which no later event clears
 *   because nothing is running to send one.
 *
 * So the publisher paints only while the value it committed is still the live
 * one. A publisher superseded by a *newer* update also declines, which is
 * correct rather than merely safe: the newer one paints, and it is more current.
 *
 * @param committed whether this publisher's update was actually applied
 * @param published the value it produced
 * @param live the shared state as it stands now
 */
fun notificationFollows(committed: Boolean, published: HrCaptureState, live: HrCaptureState): Boolean =
    committed && published.isRunning && live === published

/**
 * After painting: whether what was just drawn has outlived the capture it
 * described, and has to be taken back down.
 *
 * [notificationFollows] is checked before `notify()`, and a teardown can still
 * land in the gap between that check and the call — resetting the state and
 * removing the notification, only for the call to put it back. The window is
 * tiny and the artifact is permanent: an ongoing notification for a capture that
 * has stopped, which nothing is left running to ever clear.
 *
 * So the paint is verified as well as guarded. The question afterwards is not
 * "is my value still live" — a *newer* publisher superseding this one is fine and
 * must not cancel anything — but the simpler "is anything capturing at all".
 */
fun notificationOutlivedCapture(live: HrCaptureState): Boolean = !live.isRunning
