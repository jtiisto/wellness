package dev.jtiisto.wellness.hr

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState

/**
 * Turns capture-state transitions into the snackbar line that reports them.
 *
 * The service publishes a state, not events, so "something went wrong" has to be
 * *noticed* rather than received. This is the edge detector that does it, and it
 * is a class with memory rather than a function because the same failing state
 * is republished on every retry and every sample — posting one per emission
 * would bury the screen under a message the user already read.
 *
 * Pulled out of the collector for the usual reason: the collector is a
 * `LaunchedEffect` and the rule about *when* to speak is the part that can be
 * wrong.
 *
 * **The text is `HrCaptureState.detail`, which is
 * [dev.jtiisto.wellness.core.ble.connection.ConnectDiagnostics] output** —
 * authored sentences about a Bluetooth link, never a `Throwable.message`. That
 * matters: it is what keeps this inside the debug-log permitted-field policy,
 * where a Ktor exception message (a whole response body) would not be.
 */
class CaptureNotices {

    private var reported: String? = null

    /**
     * The message for this state, or null when there is nothing new to say.
     *
     * A capture that is not running clears the memory, so the same fault at the
     * start of the next session is reported again — it is news a second time.
     */
    fun noticeFor(state: HrCaptureState): String? {
        val detail = state.detail?.takeIf { state.isRunning && it.isNotBlank() }
        val changed = detail != null && detail != reported
        reported = detail
        return if (changed) "${CaptureNoticeCopy.PREFIX}$detail" else null
    }
}

/**
 * Why a start request never reached the capture service.
 *
 * The platform throws at the `startForegroundService` call site, before any of
 * the service's own handling can run, and the two throws mean different things
 * to the user: one is worth retrying as-is, the other needs a setting changed.
 */
enum class CaptureStartRefusal {

    /**
     * The platform refused the start — the app was no longer foreground by the
     * time the intent was dispatched. `ServiceStartNotAllowedException` and the
     * `ForegroundServiceStartNotAllowedException` under it.
     */
    NOT_ALLOWED,

    /**
     * The `connectedDevice` service type needs a Bluetooth grant, and it can be
     * revoked between the tap and the dispatch.
     */
    PERMISSION,
}

object CaptureNoticeCopy {

    /**
     * Names the subsystem, because the snackbar is shared with sync failures and
     * "Unable to connect after 15 attempts" says nothing about *to what*.
     */
    const val PREFIX = "Heart rate: "

    /**
     * What a refused start says.
     *
     * Authored text keyed off the *kind* of refusal, never the exception — same
     * rule [CaptureNotices] follows, and it is the rule that keeps a response
     * body out of a snackbar. Each one ends in what the user can do about it,
     * because a start that failed silently is the failure mode this whole change
     * exists to remove.
     */
    fun startRefused(refusal: CaptureStartRefusal): String = PREFIX + when (refusal) {
        CaptureStartRefusal.NOT_ALLOWED ->
            "could not start recording while the app was in the background. Try again."

        CaptureStartRefusal.PERMISSION ->
            "could not start recording — Bluetooth permission is off for this app."
    }
}
