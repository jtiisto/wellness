package dev.jtiisto.wellness.core.ble.capture

/**
 * The UI's handle on the capture service.
 *
 * The service is an application component and has to live in `:app`, but the two
 * screens that start captures live in `:app` and `:feature:coach`. This is the
 * seam that lets both reach it through the same three calls: neither has to know
 * what an `Intent` is, and the feature module does not gain a dependency on the
 * application module, which would point the graph backwards.
 *
 * It belongs here, beside [HrCaptureState], because the two are halves of one
 * contract — that type is what the service *tells* the UI, this is what the UI
 * *asks* of the service — and because a single implementation behind one
 * interface is what makes "every surface starts and stops capture the same way"
 * a fact rather than a convention.
 */
interface HrCaptureController {

    /**
     * Whether a start request would be honoured, as far as this side can see.
     *
     * `BLUETOOTH_CONNECT` only. The service re-checks it, along with the two
     * preconditions the UI has no view of, in its own start gate; this exists so
     * a surface can decline to *offer* a capture it already knows would be
     * refused rather than offering one that silently does nothing.
     */
    fun canStart(): Boolean

    /**
     * Start capturing from [address].
     *
     * [workoutDate] and [workoutSessionId] anchor the session to a workout and
     * are passed only by the Start Workout sheet. A capture started from the
     * strap settings passes neither and is a session in its own right — the
     * anchor is what a session gains, never what makes it valid.
     *
     * @return false when the platform refused the start outright, in which case
     *   the implementation has already told the user why. Callers use it to skip
     *   the follow-on work a capture would have justified — **not** to abandon
     *   what they were doing, because starting a workout never depended on a
     *   strap connecting. A `true` promises only that the service was asked; the
     *   capture can still fail afterwards, and that failure arrives on
     *   [HrCaptureState] like any other.
     */
    fun start(
        address: String,
        name: String?,
        workoutDate: String? = null,
        workoutSessionId: Long? = null,
    ): Boolean

    /** Stop the running capture. Doing nothing when none is running is correct. */
    fun stop()
}
