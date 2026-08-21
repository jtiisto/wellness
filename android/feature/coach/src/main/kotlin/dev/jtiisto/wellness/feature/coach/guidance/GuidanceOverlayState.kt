package dev.jtiisto.wellness.feature.coach.guidance

import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.ui.hr.HrCaptureDisplay

/**
 * The guide as a *surface*: which run is on screen, what the shell draws around
 * the instrument, and where the run itself is kept.
 *
 * `GuidanceTimeline` answers what the plan asked for and `guidanceStatus`
 * answers where the clock has got to; neither of them knows whether anyone is
 * looking. This file is that missing half — the overlay's own state, owned by
 * the ViewModel so it outlives the composable, which is what makes the spec's
 * three lifecycle promises expressible at all: dismissing does not stop the
 * clock, reopening restores the position, and the guide for a (day, exercise)
 * runs at most once.
 *
 * Everything here is a value with no clock of its own. The instant a run is
 * anchored at is handed in, the same way [guidanceStatus] is handed `nowMs`.
 */

/**
 * Which guide a run belongs to: the day and the exercise, together.
 *
 * The date is half the identity rather than an afterthought. The same exercise
 * id can appear on two days' plans, and a run anchored on Tuesday's Zone 2 ride
 * must not be the run that Wednesday's opens with — it would open showing a
 * timeline that finished yesterday.
 */
data class GuidanceKey(val date: DateString, val exerciseId: String)

/**
 * Every guidance run the process is holding, **at most one per key**.
 *
 * There is no history here and there is not meant to be: the record of a session
 * is the log, and the guide is an instrument the rider reads while it happens.
 * That is the whole content of "the guide runs at most once" — a second START
 * replaces the first run rather than filing it somewhere.
 *
 * A key that has never been started reads as a fresh [GuidanceRun], so an
 * unopened guide and a dismissed-before-START one are the same state, which is
 * true of them.
 *
 * The map is only ever added to, and only by opening a guide and pressing START,
 * so it holds at most one entry per cardio exercise the user has actually
 * guided. It dies with the process, like the accordion's expansion set.
 */
data class GuidanceRuns(private val runs: Map<GuidanceKey, GuidanceRun> = emptyMap()) {

    /** This key's run, or the un-anchored one it starts life as. */
    operator fun get(key: GuidanceKey): GuidanceRun = runs[key] ?: GuidanceRun()

    /**
     * Anchor [key]'s timeline at [nowMs].
     *
     * One transition for both the first START and the fresh run offered after a
     * completed one, because [GuidanceRun.start] already is one: it re-anchors
     * and drops the old extension, so the previous run's state is discarded here
     * exactly as the spec says it is. Nothing else in the map is touched — two
     * exercises guided in the same session keep their own clocks.
     */
    fun started(key: GuidanceKey, nowMs: Long): GuidanceRuns =
        GuidanceRuns(runs + (key to this[key].start(nowMs)))

    /**
     * Append another five minutes to [key]'s live timeline.
     *
     * Cumulative, and a pure map update like [started] — **the eligibility rule
     * is not here**. Whether a run may be extended at all is a question about
     * the phase and the shape of the timeline ([canOfferExtension]), and the
     * ViewModel asks it against resolved state on every tap, exactly as it asks
     * the phase before anchoring. Policing it in two places is how the two
     * answers start to differ.
     */
    fun extended(key: GuidanceKey): GuidanceRuns =
        GuidanceRuns(runs + (key to this[key].extend()))
}

/**
 * The guidance overlay, or **null when it is closed**.
 *
 * Null is the visibility rule, the same way it is for the BPM chip: there is one
 * fact — is there a guide to draw — and making absence the fact leaves no second
 * place for the state and the composition to disagree.
 *
 * The overlay is resolved against the plan on every pass rather than snapshotted
 * when it opens, so a guide whose exercise leaves the day (a re-planned session
 * arriving mid-ride) closes rather than going on drawing a timeline the plan no
 * longer contains.
 *
 * [capture] is `hrCaptureDisplay`'s answer, and its null carries the same
 * meaning here as everywhere else — nothing is recording. The overlay stays open
 * across that: a strap that drops out mid-ride leaves the guide up and swaps the
 * instrument for a notice, and a strap that connects afterwards puts it back.
 */
data class GuidanceOverlayState(
    val key: GuidanceKey,
    /** The exercise's name, pills stripped — the overlay's title. */
    val title: String,
    /** `Guide · Started 6:41 pm`, or just `Guide` before the clock is anchored. */
    val eyebrow: GuidanceLine,
    val timeline: GuidanceTimeline,
    val run: GuidanceRun,
    val capture: HrCaptureDisplay?,
)

/**
 * The footer's START button, or **null while a run is under way**.
 *
 * The two states that offer it offer different things, and the label says which:
 * [GuidancePhase.READY] starts the timeline, while [GuidancePhase.DONE] starts a
 * second one over the top of a finished ride. Calling both "Start" would leave
 * the DONE case looking like a control that had failed to do anything the first
 * time.
 *
 * [GuidancePhase.RUNNING] offers nothing. A restart mid-ride is a real thing to
 * want, but it is also the one tap that silently throws away a timeline the
 * rider is in the middle of, and nothing in this round asks for it.
 */
fun startButtonLabel(phase: GuidancePhase): String? = when (phase) {
    GuidancePhase.READY -> "Start"
    GuidancePhase.RUNNING -> null
    GuidancePhase.DONE -> "Start again"
}

/**
 * Whether the footer offers `+ 5 MIN`.
 *
 * Two conditions, and the interesting one is the phase.
 *
 * **Shape** is the spec's rule, and it is [GuidanceStatus.canExtend]'s: the
 * steady-state ride — one continuous band, or none at all — with a length to
 * append to. A structured session is a plan someone wrote in parts, and
 * stretching its last part mid-ride is not what "five more minutes" means to
 * the rider looking at four hard intervals.
 *
 * **Phase** is this function's own addition, and it excludes
 * [GuidancePhase.READY] for a reason the value types make plain:
 * [GuidanceRun.start] discards the extension along with the old anchor, so five
 * minutes added *before* START would be silently thrown away by the very next
 * tap the rider is expected to make. A control whose effect the next control
 * erases is worse than an absent one. [GuidancePhase.DONE] keeps it: appending
 * to a finished timeline is exactly how a Zone 2 ride carries on past its plan,
 * and because DONE is derived rather than latched, the timeline simply becomes
 * unfinished again.
 */
fun canOfferExtension(status: GuidanceStatus): Boolean =
    status.phase != GuidancePhase.READY && status.canExtend

/** The overlay shell's own words, gathered where a test can hold them. */
object GuidanceOverlayCopy {

    /** The row affordance, drawn mono-caps and underlined by the callsite. */
    const val GUIDE = "Guide"

    /** The affordance is one word on a crowded row; the spoken name says which. */
    fun openDescription(exerciseName: String): String = "Open the guide for $exerciseName"

    const val DISMISS = "Close guide"

    /**
     * The extension control, drawn mono-caps by the button.
     *
     * Says what it does, not what it produces: the cumulative total lives in
     * the context line's `EXTENDED · +N:00`, so the button can stay the same
     * five minutes on the third tap as on the first.
     */
    const val EXTEND = "+ 5 min"

    /**
     * What stands where the instrument would be when nothing is recording.
     *
     * The guide cannot start a capture itself and does not pretend to: the strap
     * is offered by Start Workout and by the Tools tab, both of which own the
     * permission and pairing flows this overlay has no business duplicating. So
     * the notice says where the switch is, and says that the guide is still
     * running underneath — the timeline in the footer keeps its clock, which is
     * the difference between a paused instrument and a dead one.
     */
    const val CAPTURE_IDLE =
        "No heart rate is arriving. Connect the strap from Start Workout, or from " +
            "the heart-rate section of the Tools tab — the trace picks up as soon as " +
            "beats do. The timeline below keeps running either way."
}
