package dev.jtiisto.wellness.core.data.coach

import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The coach module's presentation rules, ported 1:1 from `coach/utils.js` plus
 * the pure helpers that lived inside its components (`CalendarPicker`'s status
 * matrix and date caption, `ExerciseItem.parseName`, `SetEntry.buildColumns`,
 * `WorkoutView.statusToState`).
 *
 * JS names are kept so the two clients stay diff-able against each other. Two
 * things are systematically different and both are declared deviations:
 *
 * - **Dates are formatted in the system UI locale.** The PWA mixes hardcoded
 *   `en-US` calls with default-locale ones; here every caption goes through one
 *   [Locale] so a device set to German never gets a half-English date.
 * - **Truthiness is spelled out.** JS `||`/`&&` guards treat `0` and `""` as
 *   absent, and several rules depend on exactly that. Each port states which
 *   test it is applying rather than relying on Kotlin's nullability alone.
 */

/** The multiplication sign the interval labels use (U+00D7), not the letter x. */
private const val TIMES = "×"

/** The tick the cardio progress pill shows (U+2713). */
private const val CHECK = "✓"

/** Bare letter/number superset labels ("A", "C2") get the "Superset" prefix. */
private val BARE_SUPERSET_LABEL = Regex("""^[A-Za-z]\d*$""")

/** Parenthesised or bracketed name fragments, lifted out as pills. */
private val NAME_PILL = Regex("""\s*[(\[](.*?)[)\]]""")

// ---- dates -------------------------------------------------------------------

/** A day cell caption: the short weekday name and the day of the month. */
data class DateShort(val day: String, val num: Int)

/**
 * "2026-06-01" -> ("Mon", 1).
 *
 * Unused by the Android UI — `DateSelector.js`, its only PWA consumer, is dead
 * code — but ported and pinned because the calendar's weekday header derives
 * from the same locale source.
 */
fun formatDateShort(date: DateString, locale: Locale = Locale.getDefault()): DateShort {
    val parsed = LocalDate.parse(date)
    return DateShort(
        day = parsed.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
        num = parsed.dayOfMonth,
    )
}

/** The `2 * daysAround + 1` dates centred on [center], oldest first. */
fun getDateRange(center: DateString, daysAround: Long = 3): List<DateString> {
    val parsed = LocalDate.parse(center)
    return (-daysAround..daysAround).map { parsed.plusDays(it).toString() }
}

/**
 * Lexical comparison against the device's today.
 *
 * "YYYY-MM-DD" sorts lexically the same way it sorts chronologically, which is
 * why the whole module compares date strings instead of parsing them.
 */
fun isToday(date: DateString, today: DateString = LocalDate.now().toString()): Boolean = date == today

fun isPast(date: DateString, today: DateString = LocalDate.now().toString()): Boolean = date < today

fun isFuture(date: DateString, today: DateString = LocalDate.now().toString()): Boolean = date > today

/**
 * The calendar trigger's caption: Today / Yesterday / Tomorrow, else a short
 * locale date.
 *
 * The three relative words are measured against the real [today], not against
 * [date] — "Yesterday" means the day before now, whatever day is selected.
 */
fun formatSelectedDate(
    date: DateString,
    today: LocalDate,
    locale: Locale = Locale.getDefault(),
): String = when (date) {
    today.toString() -> "Today"
    today.minusDays(1).toString() -> "Yesterday"
    today.plusDays(1).toString() -> "Tomorrow"
    else -> LocalDate.parse(date).format(shortDatePattern(locale))
}

/**
 * Weekday + short month + day, in [locale].
 *
 * The field ORDER is fixed rather than locale-derived: `java.time` has no
 * skeleton-to-pattern API (that lives in `android.icu`, which unit tests cannot
 * load), so the names localize but the arrangement stays "Mon, Jun 1".
 */
fun shortDatePattern(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d", locale)

// ---- superset grouping -------------------------------------------------------

/** One entry in a block's rendered list: a lone exercise, or a labeled run of them. */
sealed interface ExerciseGroup {
    data class Single(val exercise: PlanExerciseDto) : ExerciseGroup

    data class Group(val label: String, val exercises: List<PlanExerciseDto>) : ExerciseGroup
}

/**
 * Fold consecutive exercises sharing a `superset_group` label into one wrapper.
 *
 * A run is broken by a different label **or by a label-less exercise**, so
 * `A, ∅, A` yields two separate `A` groups rather than one merged pair. The
 * server should re-label such a plan; the client must not paper over it. A
 * single labeled exercise is a group of one.
 */
fun groupExercises(exercises: List<PlanExerciseDto>): List<ExerciseGroup> {
    val items = mutableListOf<ExerciseGroup>()
    var openLabel: String? = null
    var openMembers: MutableList<PlanExerciseDto>? = null

    // Closing the run before appending anything else is what preserves the JS
    // ordering: there, the group object is pushed when it opens and mutated
    // afterwards, so a group always precedes whatever follows it.
    fun closeRun() {
        val members = openMembers ?: return
        items += ExerciseGroup.Group(checkNotNull(openLabel), members.toList())
        openLabel = null
        openMembers = null
    }

    for (exercise in exercises) {
        val label = exercise.supersetGroup?.takeIf { it.isNotEmpty() }
        when {
            label == null -> {
                closeRun()
                items += ExerciseGroup.Single(exercise)
            }

            label == openLabel -> checkNotNull(openMembers) += exercise

            else -> {
                closeRun()
                openLabel = label
                openMembers = mutableListOf(exercise)
            }
        }
    }
    closeRun()
    return items
}

/**
 * The label a superset wrapper displays. Bare labels ("A", "C2") are terse
 * plan shorthand and get spelled out; compound ones ("Triplet A") already read
 * as a title and are shown verbatim.
 */
fun supersetDisplayLabel(label: String): String =
    if (BARE_SUPERSET_LABEL.matches(label)) "Superset $label" else label

// ---- targets and prescriptions -----------------------------------------------

/**
 * Circuit/interval timing as a compact label: `"4 × 0:40/0:20"`, `"4 × 3:00"`,
 * `"0:40/0:20"`, `"4 rounds"`, `"20 min"`, or `""`.
 *
 * Every guard is JS-truthy, so a **zero is absent**, not a value: a block with
 * `rest_duration_sec: 0` reads as work-only rather than as "work then no rest".
 */
fun formatInterval(
    rounds: Int? = null,
    workSec: Int? = null,
    restSec: Int? = null,
    durationMin: Int? = null,
): String {
    val roundCount = rounds.truthy()
    val work = workSec.truthy()
    val rest = restSec.truthy()
    val minutes = durationMin.truthy()
    return when {
        roundCount != null && work != null && rest != null ->
            "$roundCount $TIMES ${clock(work)}/${clock(rest)}"

        roundCount != null && work != null -> "$roundCount $TIMES ${clock(work)}"
        roundCount != null -> "$roundCount rounds"
        work != null && rest != null -> "${clock(work)}/${clock(rest)}"
        work != null -> clock(work)
        minutes != null -> "$minutes min"
        else -> ""
    }
}

/** A block's own timing badge — the canonical source for circuit/interval work. */
fun formatInterval(block: PlanBlockDto): String = formatInterval(
    rounds = block.rounds,
    workSec = block.workDurationSec,
    restSec = block.restDurationSec,
    durationMin = null,
)

/**
 * The exercise header's target text.
 *
 * [block] supplies circuit/interval timing that an interval exercise falls back
 * to **per field** when it carries none of its own; `target_duration_min` is
 * deliberately NOT part of that fallback (only the exercise's own counts).
 *
 * Micro-deviation: a `duration` exercise with no `target_duration_min` renders
 * as empty here. The PWA's unguarded template literal prints the string
 * `"undefined min"`, which is a defect worth not reproducing.
 */
fun formatTarget(exercise: PlanExerciseDto, block: PlanBlockDto? = null): String =
    when (exercise.type) {
        TYPE_STRENGTH, TYPE_CIRCUIT -> {
            val sets = exercise.targetSets.truthy()
            val reps = exercise.targetReps?.takeIf { it.isNotEmpty() }
            when {
                sets != null && reps != null -> "$sets x $reps"
                reps != null -> reps
                sets != null -> sets.toString()
                else -> ""
            }
        }

        TYPE_DURATION -> exercise.targetDurationMin?.let { "$it min" }.orEmpty()
        TYPE_CHECKLIST -> "${exercise.items?.size ?: 0} items"
        TYPE_WEIGHTED_TIME -> "${exercise.targetDurationSec.truthy() ?: DEFAULT_WEIGHTED_TIME_SEC}s"
        TYPE_INTERVAL -> formatInterval(
            rounds = exercise.rounds ?: block?.rounds,
            workSec = exercise.workDurationSec ?: block?.workDurationSec,
            restSec = exercise.restDurationSec ?: block?.restDurationSec,
            durationMin = exercise.targetDurationMin,
        )

        else -> ""
    }

/** Which prescription modifier a token carries. `load` renders as an icon, not a word. */
enum class RxKind { RPE, LOAD, TEMPO }

data class RxToken(val kind: RxKind, val value: String)

/**
 * The optional modifiers shown in the expanded body, in a fixed order: RPE,
 * then load, then tempo. The mandatory sets × reps stays in the header.
 *
 * Structured rather than pre-joined so the load token can draw a dumbbell.
 * The PWA's `String(...)` coercion is subsumed by the DTO typing these three
 * fields as strings — the server stores free-form coaching text in them.
 */
fun buildPrescription(exercise: PlanExerciseDto): List<RxToken> = buildList {
    exercise.targetRpe?.takeIf { it.isNotEmpty() }?.let { add(RxToken(RxKind.RPE, it)) }
    exercise.targetLoad?.takeIf { it.isNotEmpty() }?.let { add(RxToken(RxKind.LOAD, it)) }
    exercise.tempo?.takeIf { it.isNotEmpty() }?.let { add(RxToken(RxKind.TEMPO, it)) }
}

// ---- progress and completion --------------------------------------------------

/** The header's progress pill: "2/3" or a tick, and whether the target is met. */
data class ExerciseProgress(val display: String, val complete: Boolean)

/**
 * The tally a collapsed row draws: [filled] marks inked of [total] drawn, one
 * per unit of work the plan asks for.
 *
 * Logbook renders completion as notation rather than as an `N/M` pill, so the
 * count has to be structural — and it is the same count [getExerciseProgress]
 * formats, which is what keeps the marks and the pill from ever disagreeing
 * about a log they are both looking at.
 *
 * Cardio is the one type this expresses and the pill cannot: a duration exercise
 * has no target to count against, so it draws a single mark, filled once a
 * usable `duration_min` exists. The pill states that same fact as
 * tick-or-nothing, which is why a tally exists where the pill is null.
 * Everywhere else null means the same in both — there is nothing to count.
 */
data class TallyMarks(val filled: Int, val total: Int)

/**
 * The tally for one exercise, or null when its type has nothing countable.
 *
 * The single counting authority: set types count **ticked** sets
 * (`completed === true`), which is the documented divergence from
 * [isExerciseCompleted] — that one counts set ROWS. Both are ported as they
 * are; the pair is pinned by name in the tests.
 */
fun exerciseTally(exercise: PlanExerciseDto, log: JsonObject?): TallyMarks? {
    val data = log ?: JsonObject(emptyMap())
    return when (exercise.type) {
        TYPE_STRENGTH, TYPE_CIRCUIT, TYPE_WEIGHTED_TIME -> {
            val total = exercise.targetSets.truthy() ?: return null
            TallyMarks(
                filled = data.array("sets").count { (it as? JsonObject)?.get("completed").isJsonTrue() },
                total = total,
            )
        }

        TYPE_CHECKLIST -> {
            val total = exercise.items?.size?.takeIf { it != 0 } ?: return null
            TallyMarks(filled = data.array("completed_items").size, total = total)
        }

        TYPE_DURATION, TYPE_INTERVAL ->
            TallyMarks(filled = if (data.hasNonEmptyDuration()) 1 else 0, total = 1)

        else -> null
    }
}

/**
 * The progress pill, or null when the type has no meaningful "N of M".
 *
 * The counts come from [exerciseTally], so exactly one function decides what
 * "done" means per type.
 *
 * Cardio needs a `duration_min` that is neither absent nor the empty string:
 * an emptied field leaves `""` behind and must not read as logged. It shows a
 * tick rather than a count, and nothing at all when unlogged — where the tally
 * still draws its one empty mark.
 */
fun getExerciseProgress(exercise: PlanExerciseDto, log: JsonObject?): ExerciseProgress? {
    val tally = exerciseTally(exercise, log) ?: return null
    return when (exercise.type) {
        TYPE_DURATION, TYPE_INTERVAL -> if (tally.filled > 0) ExerciseProgress(CHECK, complete = true) else null
        else -> ExerciseProgress("${tally.filled}/${tally.total}", complete = tally.filled >= tally.total)
    }
}

/**
 * Whether the exercise reads as done, derived entirely from what was logged —
 * there is no stored `completed` flag any more.
 *
 * Three deliberate divergences from [getExerciseProgress], all ported as-is:
 * set types count set ROWS rather than ticks; a checklist demands strict
 * equality (so a target of 0 items is complete); and cardio accepts an
 * empty-string `duration_min`, which the progress pill rejects.
 */
fun isExerciseCompleted(exercise: PlanExerciseDto, log: JsonObject?): Boolean {
    if (log == null) return false
    return when (exercise.type) {
        TYPE_CHECKLIST -> log.array("completed_items").size == (exercise.items?.size ?: 0)
        TYPE_STRENGTH, TYPE_CIRCUIT, TYPE_WEIGHTED_TIME ->
            log.array("sets").size >= (exercise.targetSets.truthy() ?: 1)

        TYPE_DURATION, TYPE_INTERVAL -> log["duration_min"].isPresent()
        else -> log.array("sets").isNotEmpty() ||
            log["duration_min"].isPresent() ||
            log.array("completed_items").isNotEmpty()
    }
}

/**
 * Does this day's log hold any exercise content at all?
 *
 * The merged form of the PWA's two near-identical twins — `WorkoutView`'s
 * `hasExerciseData` (null-guarded) and `CalendarPicker`'s `hasAnyProgress`
 * (not). Both call sites need the guard: the calendar asks about days that may
 * have no log, and the start gate asks before the day exists.
 *
 * `session_feedback` and the underscore-prefixed sync bookkeeping are not
 * exercise content, and a `_deleted` tombstone holds none either.
 */
fun hasAnyProgress(log: JsonObject?): Boolean {
    if (log == null) return false
    return log.any { (key, value) ->
        if (key == "session_feedback" || key.startsWith("_")) return@any false
        val entry = value as? JsonObject ?: return@any false
        entry.array("sets").isNotEmpty() ||
            entry.array("completed_items").isNotEmpty() ||
            entry["duration_min"].isPresent()
    }
}

// ---- calendar ------------------------------------------------------------------

/** A calendar day's dot. Absence of a dot is a meaningful fourth state. */
enum class WorkoutStatus { COMPLETED, MISSED, SCHEDULED }

/**
 * The calendar dot for one date.
 *
 * A rest day (no plan) normally stays blank, but an **ad-hoc off-plan session**
 * logged on it still earns the completed dot — that is how an extra Zone 2 shows
 * up. A planned day with no log is "scheduled" only while it is still today;
 * once the day is past, nothing logged means missed.
 */
fun getWorkoutStatus(
    date: DateString,
    plans: Map<DateString, PlanDto?>,
    logs: Map<DateString, JsonObject>,
    today: DateString,
): WorkoutStatus? {
    val log = logs[date]
    if (!plans.containsKey(date)) {
        return if (log != null && hasAnyProgress(log)) WorkoutStatus.COMPLETED else null
    }
    if (date > today) return WorkoutStatus.SCHEDULED
    if (log != null) {
        return if (hasAnyProgress(log)) WorkoutStatus.COMPLETED else WorkoutStatus.MISSED
    }
    return if (date == today) WorkoutStatus.SCHEDULED else WorkoutStatus.MISSED
}

// ---- set grid ---------------------------------------------------------------------

/** One value column of the set grid. [unit] renders as a parenthesised suffix. */
data class SetColumn(val key: String, val label: String, val unit: String? = null)

/**
 * The four grid shapes, from the exercise's `hide_weight` / `show_time` flags:
 * weight+reps+RPE (normal lifting), reps+RPE (bodyweight), weight+time
 * (weighted isometric), or time only.
 *
 * `lbs` is hardcoded exactly as the PWA hardcodes it — no unit setting exists
 * anywhere in the system to read instead.
 */
fun buildColumns(showWeight: Boolean, showTime: Boolean): List<SetColumn> = buildList {
    if (showWeight) add(SetColumn(COL_WEIGHT, "Weight", "lbs"))
    if (showTime) {
        add(SetColumn(COL_DURATION_SEC, "Time", "sec"))
    } else {
        add(SetColumn(COL_REPS, "Reps"))
        add(SetColumn(COL_RPE, "RPE"))
    }
}

// ---- exercise names -----------------------------------------------------------------

/** An exercise name split into its title and its parenthesised qualifiers. */
data class ParsedName(val base: String, val pills: List<String>)

/**
 * "Goblet Squat (KB) [tempo]" -> base "Goblet Squat", pills ["KB", "tempo"].
 *
 * Bracket styles are interchangeable, matching the PWA's character classes: an
 * unbalanced `(x]` is lifted out too rather than left in the title.
 */
fun parseName(name: String): ParsedName {
    val pills = mutableListOf<String>()
    val base = NAME_PILL.replace(name) { match ->
        pills += match.groupValues[1]
        ""
    }.trim()
    return ParsedName(base, pills)
}

// ---- workout hooks -------------------------------------------------------------------

/**
 * A Start/End button's state.
 *
 * [LOCKED] is client-only: a fired Start whose undo has been taken away because
 * exercise data now exists. The server has no such concept.
 */
enum class HookButtonState { DEFAULT, PENDING, FIRED, FAILED, LOCKED }

/**
 * A hook phase's server record read as a button state.
 *
 * A missing `exit_code` means the hook is still running. The server's -1 and -2
 * sentinels (spawn failure, timeout) are not distinguished from an ordinary
 * non-zero exit — all three are "it failed", and the UI offers the same retry.
 */
fun statusToState(result: HookResultDto?): HookButtonState = when {
    result == null -> HookButtonState.DEFAULT
    result.exitCode == null -> HookButtonState.PENDING
    result.exitCode == 0 -> HookButtonState.FIRED
    else -> HookButtonState.FAILED
}

// ---- shared JSON helpers ---------------------------------------------------------------

/** JSON `null` and an absent key are the same thing to every rule here. */
fun JsonElement?.isPresent(): Boolean = this != null && this !is JsonNull

/** The array at [key], or an empty one — a non-array value reads as absent. */
fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

/**
 * JS `=== true`: only the JSON boolean counts. The string `"true"` and the
 * number `1` are not a ticked set.
 */
internal fun JsonElement?.isJsonTrue(): Boolean {
    val primitive = this as? JsonPrimitive ?: return false
    return !primitive.isString && primitive.content == "true"
}

/** `duration_min != null && duration_min !== ''` — an emptied field is not a log. */
private fun JsonObject.hasNonEmptyDuration(): Boolean {
    val value = this["duration_min"]
    if (!value.isPresent()) return false
    val primitive = value as? JsonPrimitive ?: return true
    return !(primitive.isString && primitive.content.isEmpty())
}

/** JS truthiness for a number: null and 0 are both "not set". */
private fun Int?.truthy(): Int? = this?.takeIf { it != 0 }

/** Seconds as `M:SS`. */
private fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

// ---- wire vocabulary ---------------------------------------------------------------------

const val TYPE_STRENGTH = "strength"
const val TYPE_CIRCUIT = "circuit"
const val TYPE_DURATION = "duration"
const val TYPE_INTERVAL = "interval"
const val TYPE_CHECKLIST = "checklist"
const val TYPE_WEIGHTED_TIME = "weighted_time"

const val COL_WEIGHT = "weight"
const val COL_REPS = "reps"
const val COL_RPE = "rpe"
const val COL_DURATION_SEC = "duration_sec"

/** A `weighted_time` hold with no prescribed length defaults to a minute. */
private const val DEFAULT_WEIGHTED_TIME_SEC = 60
