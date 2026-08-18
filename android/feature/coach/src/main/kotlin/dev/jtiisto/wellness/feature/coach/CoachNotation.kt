package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.data.coach.HookButtonState
import dev.jtiisto.wellness.core.data.coach.PlanExerciseDto
import dev.jtiisto.wellness.core.data.coach.WorkoutStatus
import dev.jtiisto.wellness.core.data.coach.shortDatePattern
import dev.jtiisto.wellness.core.data.network.DateString
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Logbook's coach notation, derived.
 *
 * The design language says a workout's state is drawn, not labelled: a tier is a
 * coloured plate dot with a legend under the header, a lifecycle is an eyebrow,
 * completion is a row of tally marks, a calendar status is an ink mark. Every one
 * of those is a decision, so every one of them is made here rather than inside a
 * composable — the same discipline the rest of `CoachUiState` already follows.
 *
 * Two string conventions live in here and they are **opposites**, so they are
 * spelled out on each type: eyebrow labels are natural-cased and uppercased by
 * the callsite (Logbook sets them mono-caps and Compose has no text-transform,
 * the same reason the superset label is already uppercased where it is drawn),
 * while exposure strings and the provenance footer are rendered verbatim.
 */

// ---- tier plates ---------------------------------------------------------------

/**
 * How many plate colours exist before the ink fallback.
 *
 * Mirrors `LogbookPalette.plates`; the notation test asserts the two are equal so
 * a fifth plate cannot be added to the palette without the assignment learning
 * about it.
 */
const val PLATE_COUNT = 4

/**
 * Which dot an exercise's tier draws.
 *
 * [Plate] indexes `LogbookPalette.plates` (red → blue → yellow → green). [Ink] is
 * the 5th and every further distinct exposure, drawn as a solid ink dot: colours
 * must never repeat, because two tiers sharing one colour would make the legend
 * a lie rather than merely a crowd.
 */
sealed interface PlateSlot {
    data class Plate(val index: Int) : PlateSlot

    data object Ink : PlateSlot
}

/** One legend row: the dot, and the exposure string it stands for, verbatim. */
data class TierLegendEntry(val slot: PlateSlot, val exposure: String)

/**
 * A workout's tier assignment: the dot per exposure, plus the legend that decodes
 * it.
 *
 * The two are built together and handed out together because the legend is
 * **load-bearing** — assignment is positional, so the same string is red in one
 * workout and blue in the next, and a dot without its legend says nothing at all.
 *
 * [legend] is empty exactly when the day's exercises carry no exposure between
 * them, which is how the header knows to leave the legend row out.
 */
data class TierPlates(
    val slots: Map<String, PlateSlot>,
    val legend: List<TierLegendEntry>,
) {
    /** The dot for one exercise's tier, or null when it has none. */
    fun slotFor(exposure: String?): PlateSlot? = exposure?.let { slots[it] }

    companion object {
        val EMPTY = TierPlates(emptyMap(), emptyList())
    }
}

/**
 * The tier this exercise is dotted for, or null when it carries none.
 *
 * Blank is the same as absent — an empty `exposure` is a server field nobody
 * filled in, and it must not take a plate colour away from a real tier.
 */
fun PlanExerciseDto.tier(): String? = exposure?.takeIf { it.isNotBlank() }

/**
 * Assign a plate to every distinct exposure in the day, in order of first
 * appearance.
 *
 * [exposures] is every exercise's tier in plan order (nulls and blanks welcome,
 * they are dropped here), so the first exercise carrying a tier owns the first
 * plate. Positional, never semantic: nothing about the string decides its colour.
 */
fun assignTierPlates(exposures: List<String?>): TierPlates {
    val slots = LinkedHashMap<String, PlateSlot>()
    for (exposure in exposures) {
        if (exposure.isNullOrBlank()) continue
        slots.getOrPut(exposure) {
            val next = slots.size
            if (next < PLATE_COUNT) PlateSlot.Plate(next) else PlateSlot.Ink
        }
    }
    return TierPlates(
        slots = slots,
        legend = slots.map { (exposure, slot) -> TierLegendEntry(slot, exposure) },
    )
}

// ---- the header eyebrow ----------------------------------------------------------

/**
 * The header's eyebrow — the whole of a workout's lifecycle in one line.
 *
 * Logbook retires the read-only banners and the semantic colours with them: the
 * eyebrow, the value colour and the mark fill are what say where a session is.
 *
 * [label] is natural-cased; the callsite uppercases it.
 */
sealed interface WorkoutEyebrow {

    val label: String

    /** A day that has been and gone. Nothing on it can be edited. */
    data object Past : WorkoutEyebrow {
        override val label: String get() = "Past workout · Read-only"
    }

    /** A day still ahead, told when it opens. [logOn] is already locale-formatted. */
    data class Scheduled(val logOn: String) : WorkoutEyebrow {
        override val label: String get() = "Scheduled · Log on $logOn"
    }

    /** Today, with nothing done to it yet. */
    data object TodayReady : WorkoutEyebrow {
        override val label: String get() = "Today · Ready to log"
    }

    /**
     * Today, under way.
     *
     * [startedAt] is the start hook's wall-clock time when the server has one and
     * it could be read; absent otherwise, which is also the honest state for a
     * workout that started by someone logging a set rather than by pressing Start.
     */
    data class InProgress(val startedAt: String?) : WorkoutEyebrow {
        override val label: String
            get() = if (startedAt == null) "In progress" else "In progress · Started $startedAt"
    }
}

/**
 * Which eyebrow a planned day wears.
 *
 * "Started" means the start hook actually **fired** (FIRED, or LOCKED once data
 * follows) or something is already logged. A pressed-but-FAILED start and a
 * still-PENDING one are not a session under way — the entry gate opens on any
 * press, but an open, empty session is exactly what "Ready to log" describes,
 * and claiming IN PROGRESS off a hook that failed would caption a workout
 * nothing happened to. The first logged set flips it regardless.
 *
 * An offline day whose status fetch failed stays [WorkoutEyebrow.TodayReady]
 * until something is logged: the gate opens for it, but nothing has started.
 *
 * [locale] and [zone] are required rather than defaulted: the one caller already
 * takes both from the state build, and a silent `systemDefault()` in here would
 * be a second clock nobody could pin from a test.
 */
@Suppress("LongParameterList")
fun workoutEyebrow(
    date: DateString,
    today: DateString,
    hasProgress: Boolean,
    startState: HookButtonState,
    startFiredAt: String?,
    locale: Locale,
    zone: ZoneId,
): WorkoutEyebrow = when {
    date > today -> WorkoutEyebrow.Scheduled(LocalDate.parse(date).format(shortDatePattern(locale)))
    date < today -> WorkoutEyebrow.Past
    startState == HookButtonState.FIRED || startState == HookButtonState.LOCKED || hasProgress ->
        WorkoutEyebrow.InProgress(startedAt = wallClock(startFiredAt, locale, zone))

    else -> WorkoutEyebrow.TodayReady
}

/**
 * A hook's `fired_at` as a wall-clock caption, or null when there is nothing
 * readable to show.
 *
 * The one place this client parses a server timestamp, and it is **presentation
 * only**. The protocol rule stands untouched: stamps are opaque strings compared
 * lexically, and nothing derived here is ever compared, stored or uploaded. A
 * value that will not parse simply drops the time, leaving the eyebrow reading
 * `IN PROGRESS` — exactly what it reads when the server recorded no time at all,
 * so a format the client does not know degrades instead of crashing.
 *
 * The instant is UTC (`get_utc_now()` server-side) and the caption is the
 * device's wall clock in [zone], formatted in [locale] per coach-ui deviation 3.
 */
private fun wallClock(firedAt: String?, locale: Locale, zone: ZoneId): String? {
    val instant = firedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(instant.atZone(zone))
}

// ---- calendar marks ---------------------------------------------------------------

/**
 * A calendar day's ink mark.
 *
 * Logbook has no semantic colours, so a day's status is drawn as notation: filled
 * for a session that happened, outlined for one still ahead, slashed for one that
 * did not. [NONE] is the meaningful fourth state — a day the programme never
 * asked anything of.
 */
enum class DayMark { FILLED, OUTLINED, SLASHED, NONE }

fun dayMark(status: WorkoutStatus?): DayMark = when (status) {
    WorkoutStatus.COMPLETED -> DayMark.FILLED
    WorkoutStatus.SCHEDULED -> DayMark.OUTLINED
    WorkoutStatus.MISSED -> DayMark.SLASHED
    null -> DayMark.NONE
}

// ---- ghost provenance -------------------------------------------------------------

/**
 * The set table's footer, naming where its faint values came from.
 *
 * Replaces the `Last · <date>` hint. Ghost values are never labelled "planned" or
 * "target" — they are what this exercise did last time at this tier, and the
 * footer says so.
 *
 * [ghostsShowing] is what picks the wording, and it means literally what it says:
 * at least one cell on screen is still showing a ghost instead of a logged value.
 * That is derivable from the table itself, needs no second notion of "logged",
 * and keeps the line honest in the case a state-based rule gets wrong — a
 * half-filled session, where naming the faint numbers is the whole point. A fully
 * logged workout has no ghosts left to name, so it gets the bare provenance.
 *
 * [label] renders **verbatim**, unlike the eyebrow.
 */
data class GhostProvenance(val date: String, val ghostsShowing: Boolean) {
    val label: String
        get() = if (ghostsShowing) "Ghost values · last at this tier · $date" else "Last at this tier · $date"
}
