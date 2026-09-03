package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.device.KnownDevice
import dev.jtiisto.wellness.core.data.network.DateString

/**
 * The workout a capture session is attached to.
 *
 * Both halves travel together because both are written to the session row: the
 * date is what the analysis groups by, the hook session id is what ties the
 * recording to one specific logged workout on that date.
 */
data class WorkoutAnchor(val date: DateString, val sessionId: Long?)

/**
 * The running capture's anchor, or null when it belongs to no workout.
 *
 * Read from the published state rather than remembered by whoever started the
 * capture, which is the whole point of the service publishing it: a ViewModel
 * does not survive a process death and the session row does, so an anchor held
 * in a field would go missing mid-workout and End Workout would then decline to
 * stop the very capture it started.
 *
 * The date is the anchor's identity — a session with a hook id and no date
 * cannot exist, because both are written together.
 */
fun HrCaptureState.workoutAnchor(): WorkoutAnchor? =
    workoutDate?.let { WorkoutAnchor(date = it, sessionId = workoutSessionId) }

/** How a running capture relates to the workout currently on screen. */
enum class CaptureLink {
    /** Its samples will correlate with the sets being ticked here. */
    THIS_WORKOUT,

    /** Anchored, but to something else — a session left open from another day. */
    OTHER_WORKOUT,

    /** Started from the strap settings and never attached to anything. */
    UNANCHORED,
}

/**
 * The "Connect HRM?" sheet, and the straps it is offering.
 *
 * Every remembered strap, in the store's stable name order — which strap is
 * being worn is known only at the tap, so none of them is the privileged one.
 *
 * [straps] is non-empty by construction: the sheet exists only on
 * [StartCaptureAction.PROMPT], and that branch is taken only when a strap is
 * known.
 */
data class StrapPrompt(val straps: List<KnownDevice>)

/** What a Start Workout tap has to settle about heart rate before the hook fires. */
enum class StartCaptureAction {

    /** Ask: a strap is known, nothing is recording, and it could connect. */
    PROMPT,

    /** Already recording — attach the running session to this workout instead. */
    ANCHOR,

    /** Nothing to offer. Start the workout and say nothing about straps. */
    NONE,
}

/**
 * The rules tying a capture to a workout.
 *
 * Kept apart from the ViewModel because both of them decide something that is
 * silent when it goes wrong: a sheet that fails to appear costs the recording,
 * and an End Workout that stops the wrong capture ends a session the user is
 * still wearing.
 */
object WorkoutCapturePolicy {

    /**
     * What Start Workout does about the strap.
     *
     * Asked **every time** — there is no remembered "always connect". The strap
     * is a separate object the user has to have actually put on, and a silent
     * auto-connect would spend fifteen connect attempts on a strap sitting in a
     * drawer.
     *
     * A capture already running takes the [ANCHOR] branch and no sheet: the
     * question "connect?" has no meaning when the answer is already yes, and the
     * thing that is actually missing is the session's link to this workout.
     *
     * [connectPermitted] is `BLUETOOTH_CONNECT`. Without it the sheet would
     * offer a Connect that the capture service refuses — worse than not asking,
     * because the user would believe heart rate was being recorded. Re-granting
     * it belongs to the strap section, which is the only surface that asks.
     */
    fun startAction(
        hasKnownStrap: Boolean,
        captureRunning: Boolean,
        connectPermitted: Boolean,
    ): StartCaptureAction = when {
        captureRunning -> StartCaptureAction.ANCHOR
        !hasKnownStrap || !connectPermitted -> StartCaptureAction.NONE
        else -> StartCaptureAction.PROMPT
    }

    /**
     * Whether End Workout stops the capture.
     *
     * Only the workout that owns the recording may end it. A capture the user
     * started from the strap section — or one anchored to yesterday's session,
     * still open because its final flush never landed — is nobody else's to
     * stop, and stopping it would close a session against a workout it has
     * nothing to do with.
     *
     * [anchor] comes off the capture state, so this holds across a process death
     * as well: the resumed session carries its anchor back from the row.
     */
    fun stopsCapture(anchor: WorkoutAnchor?, ending: WorkoutAnchor, captureRunning: Boolean): Boolean =
        captureRunning && anchor != null && anchor == ending

    /**
     * What the capture sheet says about the recording's attachment.
     *
     * Worth a line of its own because it is the question the whole feature
     * exists to answer — whether these beats will correlate with these sets —
     * and nothing else on the sheet reveals it. A recording anchored to another
     * day looks identical to one anchored to this one.
     */
    fun linkFor(anchor: WorkoutAnchor?, onScreen: WorkoutAnchor): CaptureLink = when (anchor) {
        null -> CaptureLink.UNANCHORED
        onScreen -> CaptureLink.THIS_WORKOUT
        else -> CaptureLink.OTHER_WORKOUT
    }
}
