package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.data.coach.EXTRA_SESSION_KEY
import dev.jtiisto.wellness.core.data.coach.ExerciseGroup
import dev.jtiisto.wellness.core.data.coach.ExerciseProgress
import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.coach.HookButtonState
import dev.jtiisto.wellness.core.data.coach.PlanBlockDto
import dev.jtiisto.wellness.core.data.coach.PlanDto
import dev.jtiisto.wellness.core.data.coach.PlanExerciseDto
import dev.jtiisto.wellness.core.data.coach.RxToken
import dev.jtiisto.wellness.core.data.coach.SetColumn
import dev.jtiisto.wellness.core.data.coach.TallyMarks
import dev.jtiisto.wellness.core.data.coach.TYPE_CHECKLIST
import dev.jtiisto.wellness.core.data.coach.TYPE_CIRCUIT
import dev.jtiisto.wellness.core.data.coach.TYPE_DURATION
import dev.jtiisto.wellness.core.data.coach.TYPE_INTERVAL
import dev.jtiisto.wellness.core.data.coach.TYPE_STRENGTH
import dev.jtiisto.wellness.core.data.coach.TYPE_WEIGHTED_TIME
import dev.jtiisto.wellness.core.data.coach.WorkoutStatus
import dev.jtiisto.wellness.core.data.coach.array
import dev.jtiisto.wellness.core.data.coach.buildColumns
import dev.jtiisto.wellness.core.data.coach.buildPrescription
import dev.jtiisto.wellness.core.data.coach.exerciseTally
import dev.jtiisto.wellness.core.data.coach.findLastPerformance
import dev.jtiisto.wellness.core.data.coach.formatInterval
import dev.jtiisto.wellness.core.data.coach.formatSelectedDate
import dev.jtiisto.wellness.core.data.coach.formatShortDate
import dev.jtiisto.wellness.core.data.coach.formatTarget
import dev.jtiisto.wellness.core.data.coach.getExerciseProgress
import dev.jtiisto.wellness.core.data.coach.getWorkoutStatus
import dev.jtiisto.wellness.core.data.coach.groupExercises
import dev.jtiisto.wellness.core.data.coach.hasAnyProgress
import dev.jtiisto.wellness.core.data.coach.isDeletedEntry
import dev.jtiisto.wellness.core.data.coach.isExerciseCompleted
import dev.jtiisto.wellness.core.data.coach.parseName
import dev.jtiisto.wellness.core.data.coach.shortDatePattern
import dev.jtiisto.wellness.core.data.coach.supersetDisplayLabel
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.SyncStatus
import dev.jtiisto.wellness.core.ui.hr.HrCaptureDisplay
import dev.jtiisto.wellness.core.ui.hr.hrCaptureDisplay
import dev.jtiisto.wellness.feature.coach.guidance.GuidanceKey
import dev.jtiisto.wellness.feature.coach.guidance.GuidanceOverlayState
import dev.jtiisto.wellness.feature.coach.guidance.GuidanceRuns
import dev.jtiisto.wellness.feature.coach.guidance.guidanceTimeline
import dev.jtiisto.wellness.feature.coach.guidance.guideEyebrow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * Everything the Coach tab renders, derived in one pure pass.
 *
 * The composables downstream read fields and draw. Every decision — what is
 * editable, which widget an exercise gets, what the calendar dots say, whether
 * the hook controls appear — is made here, where it is testable without an
 * emulator.
 *
 * The raw plan and log are deliberately *not* exposed: [day] already carries
 * everything derived from them, and a second raw copy would invite a composable
 * to re-derive something differently. The ViewModel keeps the raw values for its
 * write path.
 */
data class CoachUiState(
    val selectedDate: DateString = "",
    val dateCaption: String = "",
    val selectedStatus: WorkoutStatus? = null,
    /** [selectedStatus] as the trigger row's ink mark — the same one its cell draws. */
    val selectedMark: DayMark = DayMark.NONE,
    val isEditable: Boolean = false,
    val calendar: CalendarState = CalendarState(),
    val day: WorkoutDayState = WorkoutDayState.Rest(showEmptyState = true, extra = null),
    val syncStatus: SyncStatus = SyncStatus.GRAY,
    val isSyncing: Boolean = false,
    /**
     * The live BPM chip, or null when nothing is capturing.
     *
     * Null *is* the visibility rule — the spec says there is no chip when idle,
     * and mapping "nothing to draw" onto "not drawn" leaves no second place for
     * the two to disagree. It is a plain function of the capture state, so a
     * screen opened halfway through a session renders it immediately.
     */
    val hr: HrCaptureDisplay? = null,
    /**
     * Whether the running capture is attached to the workout on screen. Null
     * exactly when [hr] is, so the sheet never has one without the other.
     */
    val hrLink: CaptureLink? = null,
    /**
     * The cardio guide, or null when it is closed — which is nearly always.
     *
     * Resolved against the plan on every pass rather than snapshotted when it
     * opens, so it is impossible for the overlay to outlive the exercise it
     * guides. See [GuidanceOverlayState].
     */
    val guide: GuidanceOverlayState? = null,
)

/**
 * What the day view is showing.
 *
 * Four cases rather than two, because "there is no plan" and "we cannot see the
 * plan" must not look the same. Presenting either of the latter as a rest day
 * would offer an ad-hoc Zone 2 session on a day that already has a workout on
 * it — and let the user log one.
 */
sealed interface WorkoutDayState {

    /** Nothing has arrived from storage yet. Distinct from an empty window. */
    data object Loading : WorkoutDayState

    /**
     * A plan is stored for this day but will not decode.
     *
     * The server changed a shape the client does not understand. Nothing is
     * offered and nothing is editable: whatever is in the log is intact, but
     * without the plan there is no way to say which exercise a set belongs to.
     */
    data class PlanUnavailable(val message: String) : WorkoutDayState

    /**
     * No plan for this day.
     *
     * No banners, no header, no session feedback — there is no session to
     * describe. [extra] is the ad-hoc Zone 2 card, absent on a past rest day
     * that has nothing logged.
     */
    data class Rest(
        val showEmptyState: Boolean,
        val extra: ExtraSessionState?,
    ) : WorkoutDayState

    data class Planned(
        val sessionId: Long,
        val dayName: String,
        val location: String?,
        val phase: String?,
        /** The lifecycle line under the header. Logbook's replacement for [banner]. */
        val eyebrow: WorkoutEyebrow,
        /**
         * What each plate dot in this day means, in assignment order. Empty when
         * no exercise carries a tier, which is how the header leaves the row out.
         */
        val legend: List<TierLegendEntry>,
        val banner: ReadOnlyBanner?,
        val controls: HookControlsState?,
        val gateSatisfied: Boolean,
        val editable: Boolean,
        val blocks: List<BlockState>,
        val feedback: SessionFeedbackState,
    ) : WorkoutDayState
}

/** The ad-hoc session card's two stored states. The draft is local to the UI. */
sealed interface ExtraSessionState {
    data class Saved(
        val durationText: String,
        val avgHrText: String,
        val maxHrText: String,
        val editable: Boolean,
    ) : ExtraSessionState

    /** Nothing logged yet, and the day is editable: offer the add button. */
    data object Idle : ExtraSessionState
}

/** Why a day is read-only. The wording explains the reason, not just the fact. */
data class ReadOnlyBanner(val text: String, val kind: Kind) {
    enum class Kind { PAST, FUTURE }
}

/** The collapsible Start/End controls. A null side is a hook the server lacks. */
data class HookControlsState(
    val start: HookButtonModel?,
    val end: HookButtonModel?,
)

/**
 * One hook button.
 *
 * [enabled] and [canFire] differ on purpose, exactly as the PWA's do: a FIRED
 * button stays enabled-looking but does nothing, because its Undo sits beside it
 * and greying the pair out would read as "this workout is over".
 */
data class HookButtonModel(
    val action: HookAction,
    val label: String,
    val state: HookButtonState,
    val enabled: Boolean,
    val canFire: Boolean,
    val canUndo: Boolean,
)

data class BlockState(
    val index: Int,
    val title: String,
    val timing: String,
    val restGuidance: String,
    val items: List<BlockItemState>,
)

sealed interface BlockItemState {
    data class Single(val exercise: ExerciseRowState) : BlockItemState

    data class Group(
        val label: String,
        val displayLabel: String,
        val exercises: List<ExerciseRowState>,
    ) : BlockItemState
}

/**
 * One exercise accordion.
 *
 * [completed] and [progress] come from two functions that disagree by design
 * (rows logged versus sets ticked) — see the notes on `isExerciseCompleted`.
 * [entry] is populated only while [expanded], which is what makes the ghost
 * lookup lazy: it walks the whole synced window and there is no point paying for
 * an accordion nobody opened.
 */
data class ExerciseRowState(
    val id: String,
    val name: String,
    val pills: List<String>,
    val exposure: String?,
    /** The tier dot, or null when this exercise carries no exposure to dot. */
    val plate: PlateSlot?,
    val target: String,
    val progress: ExerciseProgress?,
    /** The collapsed row's marks. Null where there is nothing countable to draw. */
    val tally: TallyMarks?,
    val completed: Boolean,
    val expanded: Boolean,
    val guidanceNote: String?,
    val prescription: List<RxToken>,
    /**
     * The cardio target-HR timeline as its one static line, or "" when the
     * exercise carries none — which is every strength row and most cardio ones.
     */
    val segments: String,
    /** Whether the row offers the `GUIDE` affordance. See [exerciseHasGuide]. */
    val hasGuide: Boolean,
    val note: String,
    val entry: EntryWidgetState?,
)

/** The four entry widgets, one per exercise family. */
sealed interface EntryWidgetState {
    data class Sets(
        val columns: List<SetColumn>,
        val rows: List<SetRowState>,
        /** The footer naming where the faint values came from; null when nothing matched. */
        val provenance: GhostProvenance?,
    ) : EntryWidgetState

    data class Cardio(
        val durationPlaceholder: String,
        val durationText: String,
        val avgHrText: String,
        val maxHrText: String,
    ) : EntryWidgetState

    data class Checklist(val items: List<ChecklistItemState>) : EntryWidgetState
}

/** Identity is the item string itself, so duplicate items collapse — PWA parity. */
data class ChecklistItemState(val item: String, val checked: Boolean)

data class SessionFeedbackState(
    val painDiscomfort: String,
    val generalNotes: String,
    val editable: Boolean,
)

/**
 * Derive the whole tab.
 *
 * [hooks] contributes only the gate and the controls; the editability of the
 * *date* is decided here so that a stale hook state can never make a past day
 * writable. Note that the gate is asked with this pass's own `dataExists`
 * rather than the hooks holder's copy — see [WorkoutHooksState].
 */
@Suppress("LongParameterList")
fun buildCoachUiState(
    selectedDate: DateString,
    viewMonth: YearMonth,
    plans: Map<DateString, PlanDto?>,
    logs: Map<DateString, JsonObject>,
    earliestDate: DateString?,
    today: LocalDate,
    hooks: WorkoutHooksState,
    expandedExercises: Set<String>,
    syncStatus: SyncStatus = SyncStatus.GRAY,
    isSyncing: Boolean = false,
    isLoading: Boolean = false,
    capture: HrCaptureState = HrCaptureState(),
    openGuide: GuidanceKey? = null,
    guidanceRuns: GuidanceRuns = GuidanceRuns(),
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): CoachUiState {
    val todayString = today.toString()
    val plan = plans[selectedDate]
    val log = logs[selectedDate]
    val isEditable = selectedDate == todayString
    val dataExists = hasAnyProgress(log)
    val selectedStatus = getWorkoutStatus(selectedDate, plans, logs, todayString)
    // One reading of the capture for both consumers: the chip and the guide must
    // never be able to say different things about the same session.
    val hrDisplay = hrCaptureDisplay(capture)

    return CoachUiState(
        selectedDate = selectedDate,
        dateCaption = formatSelectedDate(selectedDate, today, locale),
        selectedStatus = selectedStatus,
        selectedMark = dayMark(selectedStatus),
        isEditable = isEditable,
        calendar = buildCalendarState(
            viewMonth = viewMonth,
            selectedDate = selectedDate,
            today = todayString,
            plans = plans,
            logs = logs,
            earliestDate = earliestDate,
            locale = locale,
        ),
        day = if (isLoading) {
            WorkoutDayState.Loading
        } else if (plan == null) {
            // A key with no value is a plan that would not decode; no key at all
            // is a genuine rest day.
            if (plans.containsKey(selectedDate)) UNREADABLE_PLAN else buildRestDayState(log, isEditable)
        } else {
            buildPlannedDayState(
                date = selectedDate,
                plan = plan,
                log = log,
                plans = plans,
                logs = logs,
                isEditable = isEditable,
                dataExists = dataExists,
                hooks = hooks,
                expandedExercises = expandedExercises,
                today = todayString,
                locale = locale,
                zone = zone,
            )
        },
        syncStatus = syncStatus,
        isSyncing = isSyncing,
        hr = hrDisplay,
        hrLink = if (capture.isRunning) {
            WorkoutCapturePolicy.linkFor(
                anchor = capture.workoutAnchor(),
                onScreen = WorkoutAnchor(selectedDate, hooks.sessionId),
            )
        } else {
            null
        },
        guide = guidanceOverlay(
            key = openGuide,
            selectedDate = selectedDate,
            plan = plan,
            runs = guidanceRuns,
            capture = hrDisplay,
            locale = locale,
            zone = zone,
        ),
    )
}

/**
 * A rest day: the empty state, and the ad-hoc session card when there is one to
 * show or a day to add one to.
 *
 * A tombstoned entry counts as absent — the delete is still on its way to the
 * server, but as far as this screen is concerned the session is gone.
 */
private fun buildRestDayState(log: JsonObject?, isEditable: Boolean): WorkoutDayState.Rest {
    val entry = log?.get(EXTRA_SESSION_KEY) as? JsonObject
    val hasExtra = entry != null && !isDeletedEntry(entry)
    return WorkoutDayState.Rest(
        showEmptyState = !hasExtra,
        extra = when {
            hasExtra -> ExtraSessionState.Saved(
                durationText = entry.get("duration_min").asFieldText(),
                avgHrText = entry.get("avg_hr").asFieldText(),
                maxHrText = entry.get("max_hr").asFieldText(),
                editable = isEditable,
            )

            isEditable -> ExtraSessionState.Idle
            else -> null
        },
    )
}

@Suppress("LongParameterList")
private fun buildPlannedDayState(
    date: DateString,
    plan: PlanDto,
    log: JsonObject?,
    plans: Map<DateString, PlanDto?>,
    logs: Map<DateString, JsonObject>,
    isEditable: Boolean,
    dataExists: Boolean,
    hooks: WorkoutHooksState,
    expandedExercises: Set<String>,
    today: DateString,
    locale: Locale,
    zone: ZoneId,
): WorkoutDayState.Planned {
    // The gate locks exercise entry and session feedback; the raw editability
    // governs the banners and the controls. `endState` gates nothing at all.
    val effectiveEditable = hooks.effectiveEditable(isEditable, dataExists)
    val feedback = log?.get("session_feedback") as? JsonObject
    // Plate order is the order the exercises are read in, and the blocks render
    // in the order they arrive: the first tier the user meets takes the first
    // colour, whichever block it is in.
    val tiers = assignTierPlates(plan.blocks.flatMap { it.exercises }.map { it.tier() })

    return WorkoutDayState.Planned(
        sessionId = plan.sessionId,
        dayName = plan.dayName?.takeIf { it.isNotBlank() } ?: "Workout",
        location = plan.location?.takeIf { it.isNotBlank() },
        phase = plan.phase?.takeIf { it.isNotBlank() },
        eyebrow = workoutEyebrow(
            date = date,
            today = today,
            hasProgress = dataExists,
            startState = hooks.buttonState(HookAction.START),
            startFiredAt = hooks.startFiredAt,
            locale = locale,
            zone = zone,
        ),
        legend = tiers.legend,
        banner = if (isEditable) null else readOnlyBanner(date, today, locale),
        controls = if (hooks.showControls(isEditable)) hookControls(hooks) else null,
        gateSatisfied = hooks.startGateSatisfied(dataExists),
        editable = effectiveEditable,
        blocks = plan.blocks.map { block ->
            buildBlockState(
                date = date,
                block = block,
                log = log,
                plans = plans,
                logs = logs,
                expandedExercises = expandedExercises,
                tiers = tiers,
                // The raw date fact, not `effectiveEditable`: the guide is an
                // instrument, not entry, and the hook gate has no say over it.
                isToday = isEditable,
                locale = locale,
            )
        },
        feedback = SessionFeedbackState(
            painDiscomfort = feedback?.get("pain_discomfort").asFieldText(),
            generalNotes = feedback?.get("general_notes").asFieldText(),
            editable = effectiveEditable,
        ),
    )
}

/** Why this day cannot be logged — a future day says when it can be. */
private fun readOnlyBanner(date: DateString, today: DateString, locale: Locale): ReadOnlyBanner =
    if (date > today) {
        val when_ = LocalDate.parse(date).format(shortDatePattern(locale))
        ReadOnlyBanner("Scheduled workout — come back on $when_ to log it.", ReadOnlyBanner.Kind.FUTURE)
    } else {
        ReadOnlyBanner("Past workout — read-only.", ReadOnlyBanner.Kind.PAST)
    }

private fun hookControls(hooks: WorkoutHooksState): HookControlsState = HookControlsState(
    start = if (hooks.actions.start) hookButton(hooks, HookAction.START) else null,
    end = if (hooks.actions.end) hookButton(hooks, HookAction.END) else null,
)

private fun hookButton(hooks: WorkoutHooksState, action: HookAction): HookButtonModel {
    val state = hooks.buttonState(action)
    val name = if (action == HookAction.START) "Start Workout" else "End Workout"
    return HookButtonModel(
        action = action,
        label = when {
            state == HookButtonState.PENDING -> "Working…"
            state == HookButtonState.LOCKED -> "$name (locked)"
            else -> name
        },
        state = state,
        enabled = state != HookButtonState.PENDING && state != HookButtonState.LOCKED,
        canFire = hooks.canFire(action),
        canUndo = hooks.canUndo(action),
    )
}

@Suppress("LongParameterList")
private fun buildBlockState(
    date: DateString,
    block: PlanBlockDto,
    log: JsonObject?,
    plans: Map<DateString, PlanDto?>,
    logs: Map<DateString, JsonObject>,
    expandedExercises: Set<String>,
    tiers: TierPlates,
    isToday: Boolean,
    locale: Locale,
): BlockState {
    fun row(exercise: PlanExerciseDto) = buildExerciseRowState(
        date = date,
        exercise = exercise,
        block = block,
        logData = log?.get(exercise.id) as? JsonObject,
        plans = plans,
        logs = logs,
        expanded = exercise.id in expandedExercises,
        tiers = tiers,
        isToday = isToday,
        locale = locale,
    )

    return BlockState(
        index = block.blockIndex,
        title = block.title?.takeIf { it.isNotBlank() } ?: block.blockType,
        // Circuit/interval timing is canonical at the BLOCK level, so the badge
        // reads the block even when its exercises carry their own.
        timing = formatInterval(block),
        restGuidance = block.restGuidance,
        items = groupExercises(block.exercises).map { group ->
            when (group) {
                is ExerciseGroup.Single -> BlockItemState.Single(row(group.exercise))
                is ExerciseGroup.Group -> BlockItemState.Group(
                    label = group.label,
                    displayLabel = supersetDisplayLabel(group.label),
                    exercises = group.exercises.map(::row),
                )
            }
        },
    )
}

@Suppress("LongParameterList")
private fun buildExerciseRowState(
    date: DateString,
    exercise: PlanExerciseDto,
    block: PlanBlockDto,
    logData: JsonObject?,
    plans: Map<DateString, PlanDto?>,
    logs: Map<DateString, JsonObject>,
    expanded: Boolean,
    tiers: TierPlates,
    isToday: Boolean,
    locale: Locale,
): ExerciseRowState {
    val parsed = parseName(exercise.name)
    val tier = exercise.tier()
    return ExerciseRowState(
        id = exercise.id,
        name = parsed.base,
        pills = parsed.pills,
        exposure = tier,
        plate = tiers.slotFor(tier),
        target = formatTarget(exercise, block),
        progress = getExerciseProgress(exercise, logData),
        tally = exerciseTally(exercise, logData),
        completed = isExerciseCompleted(exercise, logData),
        expanded = expanded,
        guidanceNote = exercise.guidanceNote?.takeIf { it.isNotBlank() },
        prescription = buildPrescription(exercise),
        segments = formatSegments(exercise.segments),
        hasGuide = exerciseHasGuide(exercise, isToday),
        note = logData?.get("user_note").asFieldText(),
        entry = if (expanded) {
            buildEntryWidget(date, exercise, logData, plans, logs, locale)
        } else {
            null
        },
    )
}

// ---- the cardio guide ------------------------------------------------------------

/**
 * Whether the row draws its `GUIDE` affordance: the right shape of exercise,
 * **on today**.
 *
 * The date gate is the whole difference between this and [exerciseOffersGuide],
 * and it is on the *opening* only. A guide is an instrument for a ride happening
 * now — there is no strap trace to draw against a Tuesday in the past, and a
 * future day's ride has not been ridden — so the affordance stays off every day
 * but today, and [CoachViewModel.openGuide] asks the same question again at the
 * tap. What the gate deliberately does not touch is the resolver: an already-open
 * guide survives the midnight rollover, because a ride started at 23:50 must not
 * slam shut at 00:00.
 *
 * [isToday] is the state's own editability fact — `selectedDate == today` —
 * rather than a second clock: two readings of "now" in one pass could disagree.
 * It is deliberately the *raw* date fact and not the hook gate, which can close
 * entry after End Workout: the guide starts and stops no capture and writes
 * nothing on its own, so a workout the rider has ended is no reason to take the
 * instrument away mid-cooldown.
 */
internal fun exerciseHasGuide(exercise: PlanExerciseDto, isToday: Boolean): Boolean =
    isToday && exerciseOffersGuide(exercise)

/**
 * Whether an exercise is the *shape* that offers the live guide, date aside.
 *
 * The rule is the spec's, and its asymmetry is deliberate. A `duration` exercise
 * gets the guide **with or without segments**: with none there is no band to
 * draw, but the timer, the trace and the extension are useful on their own — a
 * Zone 2 ride is exactly that case, and it is the one the feature is used for
 * most. An `interval` exercise gets it **only with segments**: its structure
 * lives in prose and block `rounds`/`work_duration_sec`, nothing derives a
 * timeline from those (the explicit-only rule), so a guide there would be a
 * stopwatch beside a plan it could not read. Authoring segments is the upgrade
 * path, and the affordance appearing is what says they landed.
 *
 * Every other type has no timeline to guide against at all.
 */
internal fun exerciseOffersGuide(exercise: PlanExerciseDto): Boolean = when (exercise.type) {
    TYPE_DURATION -> true
    TYPE_INTERVAL -> !exercise.segments.isNullOrEmpty()
    else -> false
}

/**
 * The overlay for the guide that is open, or null when none is.
 *
 * Resolved from the plan rather than from what the affordance was showing when
 * it was tapped, which is what makes three separate things impossible rather
 * than merely unlikely: a guide surviving onto another day (the key carries its
 * date, and a mismatch closes it), a guide surviving its exercise leaving the
 * plan, and a guide for an exercise that never offered one —
 * [exerciseOffersGuide] is asked again here, so the overlay and the affordance
 * cannot disagree even if a stale key reached this far.
 *
 * The **shape** predicate, not the dated one: the affordance's today gate is on
 * opening a guide, and an open one has to survive the midnight rollover. A ride
 * anchored at 23:50 goes on drawing at 00:05, against the day it was opened
 * from — which is still the day the key names, so nothing else here moves either.
 */
private fun guidanceOverlay(
    key: GuidanceKey?,
    selectedDate: DateString,
    plan: PlanDto?,
    runs: GuidanceRuns,
    capture: HrCaptureDisplay?,
    locale: Locale,
    zone: ZoneId,
): GuidanceOverlayState? {
    // Returning null closes the overlay but deliberately does NOT clear the
    // ViewModel's open key. Invalidity here can be transient — a background
    // plan sync rebuilding the day's rows mid-ride — and clearing the key on
    // it would dismiss a guide the rider never dismissed. The user's open is
    // the standing consent and their dismiss the only revocation: an exercise
    // that leaves the plan and returns finds its never-dismissed overlay
    // waiting (pinned by test; the deep review read this as a bug, and the
    // sync-transient case is why it is not).
    if (key == null || key.date != selectedDate || plan == null) return null
    val exercise = plan.blocks.asSequence()
        .flatMap { it.exercises.asSequence() }
        .firstOrNull { it.id == key.exerciseId }
        ?: return null
    if (!exerciseOffersGuide(exercise)) return null
    val run = runs[key]
    return GuidanceOverlayState(
        key = key,
        title = parseName(exercise.name).base,
        eyebrow = guideEyebrow(run, locale, zone),
        timeline = exercise.guidanceTimeline(),
        run = run,
        capture = capture,
    )
}

/**
 * The entry widget for one exercise, ghosts included.
 *
 * The last-performance lookup runs only for set-based types: cardio and
 * checklists have nothing per-set to hint at, and the PWA shows no ghosts for
 * them either.
 */
private fun buildEntryWidget(
    date: DateString,
    exercise: PlanExerciseDto,
    logData: JsonObject?,
    plans: Map<DateString, PlanDto?>,
    logs: Map<DateString, JsonObject>,
    locale: Locale,
): EntryWidgetState? {
    fun sets(targetSets: Int, showWeight: Boolean, showTime: Boolean): EntryWidgetState.Sets {
        val lastPerformance = findLastPerformance(
            canonicalSlug = exercise.canonicalSlug,
            refDate = date,
            plans = plans,
            logs = logs,
            exposure = exercise.exposure,
        )
        val columns = buildColumns(showWeight = showWeight, showTime = showTime)
        val rows = buildSetRows(
            columns = columns,
            sets = logData?.array("sets") ?: EMPTY_ARRAY,
            targetSets = targetSets,
            lastPerformance = lastPerformance,
        )
        return EntryWidgetState.Sets(
            columns = columns,
            rows = rows,
            provenance = lastPerformance?.let {
                GhostProvenance(
                    date = formatShortDate(it.date, locale),
                    // A cell shows its ghost only while it has no value of its
                    // own, so this is exactly "are any faint numbers on screen"
                    // — which is the question the footer's wording answers.
                    ghostsShowing = rows.any { row -> row.cells.any { cell -> cell.showsGhost } },
                )
            },
        )
    }

    return when (exercise.type) {
        // `target_sets || 3`: JS-truthy, so an explicit 0 defaults too rather
        // than rendering a grid with no rows in it.
        TYPE_STRENGTH, TYPE_CIRCUIT -> sets(
            targetSets = exercise.targetSets.orDefault(DEFAULT_TARGET_SETS),
            showWeight = exercise.hideWeight != true,
            showTime = exercise.showTime == true,
        )

        // A weighted hold is always weight-and-time, and one set unless told
        // otherwise — the PWA hardcodes both.
        TYPE_WEIGHTED_TIME -> sets(
            targetSets = exercise.targetSets.orDefault(1),
            showWeight = true,
            showTime = true,
        )

        TYPE_DURATION, TYPE_INTERVAL -> EntryWidgetState.Cardio(
            // `placeholder={targetMin || ''}`: a zero target hints nothing.
            durationPlaceholder = exercise.targetDurationMin?.takeIf { it != 0 }?.toString().orEmpty(),
            durationText = logData?.get("duration_min").asFieldText(),
            avgHrText = logData?.get("avg_hr").asFieldText(),
            maxHrText = logData?.get("max_hr").asFieldText(),
        )

        TYPE_CHECKLIST -> {
            // Membership by string, so two identically-named items tick together.
            // The PWA's `completedItems.includes(item)` behaves the same way.
            val done = logData?.array("completed_items").orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }
                .toSet()
            EntryWidgetState.Checklist(
                items = exercise.items.orEmpty().map { ChecklistItemState(it, it in done) },
            )
        }

        else -> null
    }
}

private val EMPTY_ARRAY = JsonArray(emptyList())

/** `target_sets || 3` in the PWA: a strength exercise with no count gets three. */
private const val DEFAULT_TARGET_SETS = 3

/** JS `||` for a set count: null and 0 both fall through to the default. */
private fun Int?.orDefault(fallback: Int): Int = this?.takeIf { it != 0 } ?: fallback

private val UNREADABLE_PLAN = WorkoutDayState.PlanUnavailable(
    "This day's plan could not be read. It will reappear once the next sync " +
        "delivers a version this app understands.",
)
