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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.YearMonth
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
    val target: String,
    val progress: ExerciseProgress?,
    val completed: Boolean,
    val expanded: Boolean,
    val guidanceNote: String?,
    val prescription: List<RxToken>,
    val note: String,
    val entry: EntryWidgetState?,
)

/** The four entry widgets, one per exercise family. */
sealed interface EntryWidgetState {
    data class Sets(
        val columns: List<SetColumn>,
        val rows: List<SetRowState>,
        val lastPerformanceHint: String?,
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
    locale: Locale = Locale.getDefault(),
): CoachUiState {
    val todayString = today.toString()
    val plan = plans[selectedDate]
    val log = logs[selectedDate]
    val isEditable = selectedDate == todayString
    val dataExists = hasAnyProgress(log)

    return CoachUiState(
        selectedDate = selectedDate,
        dateCaption = formatSelectedDate(selectedDate, today, locale),
        selectedStatus = getWorkoutStatus(selectedDate, plans, logs, todayString),
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
            )
        },
        syncStatus = syncStatus,
        isSyncing = isSyncing,
        hr = hrCaptureDisplay(capture),
        hrLink = if (capture.isRunning) {
            WorkoutCapturePolicy.linkFor(
                anchor = capture.workoutAnchor(),
                onScreen = WorkoutAnchor(selectedDate, hooks.sessionId),
            )
        } else {
            null
        },
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
): WorkoutDayState.Planned {
    // The gate locks exercise entry and session feedback; the raw editability
    // governs the banners and the controls. `endState` gates nothing at all.
    val effectiveEditable = hooks.effectiveEditable(isEditable, dataExists)
    val feedback = log?.get("session_feedback") as? JsonObject

    return WorkoutDayState.Planned(
        sessionId = plan.sessionId,
        dayName = plan.dayName?.takeIf { it.isNotBlank() } ?: "Workout",
        location = plan.location?.takeIf { it.isNotBlank() },
        phase = plan.phase?.takeIf { it.isNotBlank() },
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
    locale: Locale,
): ExerciseRowState {
    val parsed = parseName(exercise.name)
    return ExerciseRowState(
        id = exercise.id,
        name = parsed.base,
        pills = parsed.pills,
        exposure = exercise.exposure?.takeIf { it.isNotBlank() },
        target = formatTarget(exercise, block),
        progress = getExerciseProgress(exercise, logData),
        completed = isExerciseCompleted(exercise, logData),
        expanded = expanded,
        guidanceNote = exercise.guidanceNote?.takeIf { it.isNotBlank() },
        prescription = buildPrescription(exercise),
        note = logData?.get("user_note").asFieldText(),
        entry = if (expanded) {
            buildEntryWidget(date, exercise, logData, plans, logs, locale)
        } else {
            null
        },
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
        return EntryWidgetState.Sets(
            columns = columns,
            rows = buildSetRows(
                columns = columns,
                sets = logData?.array("sets") ?: EMPTY_ARRAY,
                targetSets = targetSets,
                lastPerformance = lastPerformance,
            ),
            lastPerformanceHint = lastPerformance?.let { "Last · ${formatShortDate(it.date, locale)}" },
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
