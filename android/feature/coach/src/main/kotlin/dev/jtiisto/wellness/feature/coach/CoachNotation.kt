package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.coach.HookButtonState
import dev.jtiisto.wellness.core.data.coach.PlanExerciseDto
import dev.jtiisto.wellness.core.data.coach.PlanSegmentDto
import dev.jtiisto.wellness.core.data.coach.TallyMarks
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

/**
 * The mark an eyebrow carries in front of its words.
 *
 * Named rather than drawn here so the choice is pinned in a JVM test: the header
 * turns [LOCK] and [CALENDAR] into outlined ink icons and [DOT] into a filled ink
 * disc. Never a coloured emoji — the mockups use one and the design system
 * overrules them, because an emoji is the one glyph on the page whose colour the
 * theme does not own.
 */
enum class EyebrowGlyph { LOCK, CALENDAR, DOT }

/**
 * Which glyph a lifecycle wears.
 *
 * A day still ahead and a day whose work has not begun get the same calendar:
 * both are appointments, and the words after the glyph are what separate them.
 */
fun WorkoutEyebrow.glyph(): EyebrowGlyph = when (this) {
    WorkoutEyebrow.Past -> EyebrowGlyph.LOCK
    is WorkoutEyebrow.Scheduled, WorkoutEyebrow.TodayReady -> EyebrowGlyph.CALENDAR
    is WorkoutEyebrow.InProgress -> EyebrowGlyph.DOT
}

// ---- tally marks --------------------------------------------------------------------

/**
 * One entry per mark a collapsed row draws: true is inked, false is outlined.
 *
 * The list *is* the drawing instruction, which is what keeps a `for` loop with an
 * index comparison out of a composable. Both counts are clamped on the way
 * through: a log that ticked more sets than the plan now asks for — an exercise
 * edited down after the fact — would otherwise ink more marks than it draws, and
 * `List(-1)` is an exception rather than a quiet nothing.
 */
fun tallyMarkFills(tally: TallyMarks): List<Boolean> {
    val total = tally.total.coerceAtLeast(0)
    val filled = tally.filled.coerceIn(0, total)
    return List(total) { index -> index < filled }
}

/**
 * What a screen reader says about a whole collapsed exercise row.
 *
 * The row is one tappable node, so it announces itself as one: everything drawn
 * on it has to be in this sentence or it is not read at all. That is why the
 * tally is spelled out — it is the only place completion is shown now that the
 * progress pill has retired, and marks are geometry to a screen reader.
 *
 * A cardio row's single mark says "done" or nothing rather than "1 of 1", which
 * is the same thing the pill said about a duration it could not count.
 */
fun exerciseRowDescription(
    name: String,
    exposure: String?,
    scheme: String,
    tally: TallyMarks?,
    expanded: Boolean,
): String {
    val action = if (expanded) "Collapse" else "Expand"
    val tier = exposure?.takeIf { it.isNotBlank() }?.let { "$it tier" }
    val marks = tally?.let {
        val total = it.total.coerceAtLeast(0)
        val filled = it.filled.coerceIn(0, total)
        when {
            total == 0 -> null
            total == 1 -> if (filled == 1) "done" else "not done"
            else -> "$filled of $total done"
        }
    }
    // The tier rides the sentence for the same reason the tally does: the plate
    // dot is geometry, and the legend maps colours globally, not to this row.
    return listOfNotNull("$action $name", tier, scheme.takeIf { it.isNotBlank() }, marks)
        .joinToString(", ")
}

// ---- the prescription meta line -----------------------------------------------------

/**
 * Whether a load has to be introduced by the word `load`.
 *
 * The slot holds free coaching text, not a number: `35 lb/hand` reads as a
 * weight on its own, while `light band assist` reads as nothing at all
 * without a word in front of it. A leading digit is the whole test — it is what
 * separates "a measurement" from "an instruction" here, and it never assumes the
 * string is numeric, which is the trap this exists to avoid.
 */
fun loadNeedsLabel(value: String): Boolean = value.trimStart().firstOrNull()?.isDigit() != true

// ---- the cardio target-HR timeline ------------------------------------------------------

/** What joins two segments on the static line (U+00B7), matching the PWA's. */
private const val SEGMENT_SEPARATOR = " · "

/** Range dash (U+2013), floor (U+2265) and ceiling (U+2264) — never `-`/`>=`.
 * Private on purpose: the shapes travel only as [hrBoundsToken]'s output, so
 * a consumer cannot assemble a fourth spelling from the raw symbols. */
private const val RANGE_DASH = "–"
private const val AT_LEAST = "≥"
private const val AT_MOST = "≤"

/**
 * A segment's HR constraint as one token — `A–B`, `≥N`, `≤N` — or null when it
 * carries no bound at all.
 *
 * The single authority for the three shapes, because the static plan line and
 * the live guide's `TARGET` slot have to say the same thing about the same
 * segment: two formatters would be two chances for the day view and the overlay
 * to disagree about what the plan asked for.
 *
 * A **zero bound is absent**, not a bound of zero. The guard is JS-truthy in the
 * PWA twin (`CoachUiLogic`'s spelled-out-truthiness rule) and the server's floor
 * is 1, so a `0` can only be a hand-edited row — reading it as "no bound" beats
 * drawing `≥0`.
 */
internal fun hrBoundsToken(hrMin: Int?, hrMax: Int?): String? {
    val min = hrMin?.takeIf { it != 0 }
    val max = hrMax?.takeIf { it != 0 }
    return when {
        min != null && max != null -> "$min$RANGE_DASH$max"
        min != null -> "$AT_LEAST$min"
        max != null -> "$AT_MOST$max"
        else -> null
    }
}

/**
 * A cardio exercise's whole target-HR timeline as one line:
 * `"5:00 125–140 · 3:00 160–175 · 2:00 ≤150"`.
 *
 * One token per segment — its duration as `M:SS`, then its HR constraint, whose
 * *shape* is its meaning: a range as `A–B`, a floor (min only) as `≥N`, a
 * ceiling (max only) as `≤N`. The server guarantees one bound at least, so a
 * segment carrying neither can only come from a hand-edited row; it degrades to
 * its duration rather than drawing a token with a missing number.
 *
 * The twin of `formatSegments` in the PWA's `coach/utils.js`, asserted against
 * the same strings on both stacks. Its bound guards are JS-truthy, so a **zero
 * is absent** here too (`CoachUiLogic`'s spelled-out-truthiness rule): the
 * server's floor is 1, so a `0` can only be a hand-edited row, and reading it as
 * "no bound" beats drawing `≥0`. [label] is deliberately not on this line: the
 * static summary is durations and bpm, and the names belong beside their segment
 * in the live guide.
 *
 * Returns `""` when there is no timeline, which is most plans.
 */
fun formatSegments(segments: List<PlanSegmentDto>?): String {
    if (segments.isNullOrEmpty()) return ""
    return segments.joinToString(SEGMENT_SEPARATOR) { segment ->
        val time = segmentClock(segment.durationSec)
        val bounds = hrBoundsToken(segment.hrMin, segment.hrMax)
        if (bounds == null) time else "$time $bounds"
    }
}

/**
 * `M:SS`, minutes un-padded and seconds padded — `CoachUiLogic`'s interval clock,
 * which is private to `:core:data` and so cannot be shared across the module
 * line. A negative duration cannot reach here (the server's floor is 1) and is
 * clamped rather than rendered as `-1:-5`.
 *
 * Shared with the live guide, which counts in the same notation the plan line
 * reads in — an hour-long ride reads `61:30` on both, never `1:01:30`, because a
 * format that grew a field mid-session would shift a mono column that ticks in
 * place.
 */
internal fun segmentClock(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

// ---- the set table ---------------------------------------------------------------------

/**
 * What a screen reader says about one read-only set cell.
 *
 * An editable cell is a text field and announces its own value, so it needs only
 * a name. A logged one is plain text, and a bare `20` in a four-column row says
 * nothing about which column it is — so the name and the value are read
 * together.
 *
 * A ghost is named as what it is. It looks faint on screen, which a screen
 * reader cannot see, and announcing last session's number as though it were this
 * session's would be the one mistake the ghost convention exists to prevent.
 */
fun setCellDescription(setNumber: Int, key: String, value: String, isGhost: Boolean): String = when {
    value.isEmpty() -> "Set $setNumber $key, not logged"
    isGhost -> "Set $setNumber $key, $value last time"
    else -> "Set $setNumber $key, $value"
}

// ---- hook buttons ---------------------------------------------------------------------

/** The mark on a hook button: a settled hook ticks, a failed one crosses. */
enum class HookGlyph { NONE, CHECK, CROSS }

/**
 * How a hook button is drawn — the whole of what Logbook replaced the semantic
 * colours with.
 *
 * [note] is a mono word set after the label; it exists for FAILED alone, where
 * the cross needs a word beside it. The label itself is untouched — it comes off
 * `HookButtonModel` and is pinned by the hook tests.
 */
data class HookButtonSkin(
    val filled: Boolean,
    val glyph: HookGlyph,
    val note: String?,
    /**
     * What the button's state sounds like. The fill and the glyph are geometry
     * to a screen reader, and FIRED/LOCKED carry no visible note — without this
     * a fired Start is indistinguishable from an idle one by ear.
     */
    val a11yState: String?,
)

/**
 * Ink fill or ink outline, and which mark rides with it.
 *
 * State outranks action, and the order below is the precedence: a hook still
 * working outlines whichever phase it belongs to, a settled one fills, a failed
 * one outlines and says so. Only when the state says nothing does the action
 * decide — Start is the day's one filled call to action, End sits behind it.
 * No colour is spent at any point.
 */
fun hookButtonSkin(action: HookAction, state: HookButtonState): HookButtonSkin = when (state) {
    HookButtonState.PENDING ->
        HookButtonSkin(filled = false, glyph = HookGlyph.NONE, note = null, a11yState = "working")

    HookButtonState.FIRED ->
        HookButtonSkin(filled = true, glyph = HookGlyph.CHECK, note = null, a11yState = "fired")

    HookButtonState.LOCKED ->
        HookButtonSkin(filled = true, glyph = HookGlyph.CHECK, note = null, a11yState = "locked")

    HookButtonState.FAILED ->
        HookButtonSkin(filled = false, glyph = HookGlyph.CROSS, note = "Failed", a11yState = "failed")

    HookButtonState.DEFAULT ->
        HookButtonSkin(filled = action == HookAction.START, glyph = HookGlyph.NONE, note = null, a11yState = null)
}

// ---- empty notes -------------------------------------------------------------------------

/** Which of the two note fields is empty — they are asked at different levels. */
enum class NoteScope { EXERCISE, SESSION }

/**
 * How an empty note speaks about itself.
 *
 * The three voices are the three honest things there are to say: you may write
 * here, you may write here *later*, or nobody wrote here and nobody will. Which
 * one applies is a property of the whole day, so it is resolved once per screen
 * rather than re-derived per field.
 */
enum class NoteVoice { EDITABLE, SCHEDULED, RECORDED }

/**
 * The day's note voice.
 *
 * [editable] is the gate's answer rather than the calendar's, so a today whose
 * start hook has not been pressed speaks in the past tense — which is true:
 * nothing can be written until the gate opens. A future day is the one case
 * where "not yet" is worth saying, and [WorkoutEyebrow.Scheduled] is exactly
 * that day.
 */
fun noteVoice(eyebrow: WorkoutEyebrow, editable: Boolean): NoteVoice = when {
    editable -> NoteVoice.EDITABLE
    eyebrow is WorkoutEyebrow.Scheduled -> NoteVoice.SCHEDULED
    else -> NoteVoice.RECORDED
}

/**
 * What an empty note field reads, set italic in ink-faint by the callsite.
 *
 * A scheduled day is the one voice that changes with [scope]: the session's
 * feedback is recorded after the session, while an exercise's note is added as
 * it is logged, and saying either sentence in the other's place would describe a
 * moment that does not exist.
 */
fun NoteVoice.emptyNote(scope: NoteScope): String = when (this) {
    NoteVoice.EDITABLE -> "Add a note"
    NoteVoice.SCHEDULED -> when (scope) {
        NoteScope.EXERCISE -> "Added when you log this session"
        NoteScope.SESSION -> "None yet — recorded after the session"
    }

    NoteVoice.RECORDED -> "None recorded"
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

/**
 * What a drawn day mark tells a screen reader. [DayMark.NONE] draws nothing and
 * says nothing; the other three are pure geometry without this.
 */
fun DayMark.a11yLabel(): String? = when (this) {
    DayMark.FILLED -> "Completed"
    DayMark.OUTLINED -> "Scheduled"
    DayMark.SLASHED -> "Missed"
    DayMark.NONE -> null
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
