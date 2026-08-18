package dev.jtiisto.wellness.feature.journal

import dev.jtiisto.wellness.core.data.coach.shortDatePattern
import dev.jtiisto.wellness.core.data.journal.CategoryRollup
import dev.jtiisto.wellness.core.data.journal.DayDot
import dev.jtiisto.wellness.core.data.journal.DayStatus
import dev.jtiisto.wellness.core.data.journal.DotState
import dev.jtiisto.wellness.core.data.journal.TrackerDto
import dev.jtiisto.wellness.core.data.journal.isActionable
import dev.jtiisto.wellness.core.data.network.DateString
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Logbook's journal notation, derived.
 *
 * The journal draws **no colour at all** — the resolution the design system
 * settled on rather than spend a hue axis on adherence. Everything the retired
 * palette used to say is said by shape instead: a held avoidance is a hoop and
 * never a filled "success" dot, a slip is crossed through rather than alarmed,
 * an off-schedule day is a dash rather than a failure, and a day nobody has
 * ruled on yet is left open. Each of those is a **decision**, so each is made
 * here rather than inside a composable, exactly as `CoachNotation` does for the
 * other half of the app.
 *
 * The behavioural contracts this sits beside — `journal-ui.md`'s schedule,
 * target and entry-presence semantics, `dayStatus`, `categoryRollup`,
 * `recentDayStates` — are untouched. Nothing in here judges a day; it only
 * chooses how an already-made judgement is drawn.
 *
 * The one string convention here matches coach's: eyebrow labels are
 * natural-cased and the callsite uppercases them (Logbook sets the eyebrow
 * mono-caps and Compose has no text-transform), and the same goes for the
 * strip's day initial.
 */

// ---- the week-mark grammar -----------------------------------------------------

/**
 * One drawn mark — the journal's whole visual vocabulary, shared by a row's
 * seven-day run and by the section head's rollup cluster (one language, two
 * sizes).
 *
 * [OPEN_DOT] is load-bearing and easy to mistake for [SLASHED_DOT]: it means *no
 * verdict exists*, which is a different statement from a miss, and the two are
 * reached by different paths below.
 */
enum class WeekMark {
    FILLED_DOT,
    HALF_DOT,
    OPEN_DOT,
    SLASHED_DOT,
    DASH,
    HOOP,
    SLASHED_HOOP,
    FILLED_DIAMOND,
    OUTLINE_DIAMOND,
}

/**
 * What a tracker asks of you, which is what picks its marks.
 *
 * The same three classes `categoryRollup` splits a category into: [HABIT] is
 * something to do, [AVOIDANCE] something to hold, [OBSERVATION] something to
 * notice — logged, never judged. A row's class is resolved once, for the
 * selected day, because a tracker can change class over time (a target coming
 * into effect turns yesterday's observation into today's habit) and a row of
 * marks drawn in two vocabularies would read as noise.
 */
enum class TrackerClass { HABIT, AVOIDANCE, OBSERVATION }

/**
 * Which class a tracker is in on the day [status] describes.
 *
 * Deliberately the same branch order as `categoryRollup`, off the same
 * `isActionable` predicate: a negative polarity always makes a tracker
 * actionable, so an avoidance can never fall out as an observation, and the
 * cluster and the rows can never disagree about what a tracker *is*.
 */
fun trackerClass(tracker: TrackerDto, status: DayStatus): TrackerClass = when {
    !isActionable(tracker, status) -> TrackerClass.OBSERVATION
    tracker.polarity == "negative" -> TrackerClass.AVOIDANCE
    else -> TrackerClass.HABIT
}

/**
 * The mark one day's state draws on a row of this class.
 *
 * Three rules do all the work:
 *
 * An **avoidance uses hoops exclusively** — held or broken, never a filled dot,
 * because "did not drink" is not an achievement to celebrate in the same shape
 * as "went for a walk". The hoop grammar is two-state by construction and
 * collapses to its worst: a negative tracker carrying a *range* target can
 * still return [DotState.PARTIAL], and there is no half-hoop to spend on it —
 * the same collapse `categoryRollup` already makes for the same reason.
 *
 * **[DotState.QUIET] is ambiguous and the row's class resolves it.** On an
 * observation row it is the outline diamond — expected, not noted, which is a
 * complete and unalarming statement. On a habit or avoidance row it is the open
 * dot: the day was non-actionable *that day* (a day before the tracker's target
 * came into effect, per the per-day `isActionable` inside `recentDayStates`), so
 * it legitimately appears mid-row and must not turn a run of dots into a run of
 * diamonds.
 *
 * An observation row's judged states fall through to the **dot** vocabulary
 * rather than the diamonds. They are reachable — a target that was in effect
 * earlier in the week and has since been cleared leaves judged days behind it —
 * and on those days the tracker really was judged, so the dots tell the truth
 * about them.
 */
fun weekMarkFor(state: DotState, trackerClass: TrackerClass): WeekMark = when (state) {
    DotState.OFF -> WeekMark.DASH
    DotState.NOTED -> WeekMark.FILLED_DIAMOND
    DotState.QUIET -> if (trackerClass == TrackerClass.OBSERVATION) {
        WeekMark.OUTLINE_DIAMOND
    } else {
        WeekMark.OPEN_DOT
    }

    DotState.MET -> if (trackerClass == TrackerClass.AVOIDANCE) WeekMark.HOOP else WeekMark.FILLED_DOT
    DotState.PARTIAL ->
        if (trackerClass == TrackerClass.AVOIDANCE) WeekMark.SLASHED_HOOP else WeekMark.HALF_DOT

    DotState.MISSED ->
        if (trackerClass == TrackerClass.AVOIDANCE) WeekMark.SLASHED_HOOP else WeekMark.SLASHED_DOT
}

/** One position in a row's seven-day run. [ringed] is the strip's "you are here". */
data class RowMark(val date: DateString, val mark: WeekMark, val ringed: Boolean)

/** The states a day can be *judged* into — the three the today rule suspends. */
private val JUDGED = setOf(DotState.MET, DotState.PARTIAL, DotState.MISSED)

/**
 * A row's seven-day run, drawn.
 *
 * [dots] is `recentDayStates` untouched; it ends on the **selected** date, so
 * the last mark is the one the ring goes on — browsing back a day moves the ring
 * with the row rather than leaving it stranded on today.
 *
 * The honesty rule this exists for: when the run ends on the real [today] and
 * the tracker has **no entry row** for it, a habit or avoidance row draws the
 * open dot in place of the judged mark. The state model has to answer for every
 * day, so it says MISSED for an unlogged habit and held for an unlogged
 * avoidance — but the day is not over, and neither verdict has been earned yet.
 * This is the same principle the app already applies elsewhere (a streak resets
 * only on a day that can no longer be fixed); [hasEntry] flips it back the
 * moment anything is written, because then there *is* something to judge.
 *
 * Observation rows are exempt: their outline diamond already says "expected, not
 * noted", which is precisely the statement the open dot is borrowed to make.
 * Off-schedule and already-noted days are exempt too — only a judged mark can be
 * suspended, and a dash or a filled diamond was never a verdict.
 *
 * Because the run ends on the selected date, a dot can only carry today's date
 * when the selected day *is* today: a browsed past day draws judged marks
 * throughout, ring included.
 */
fun rowMarks(
    dots: List<DayDot>,
    trackerClass: TrackerClass,
    hasEntry: Boolean,
    today: DateString,
): List<RowMark> {
    val suspendVerdict = !hasEntry && trackerClass != TrackerClass.OBSERVATION
    return dots.mapIndexed { index, dot ->
        val mark = if (suspendVerdict && dot.date == today && dot.state in JUDGED) {
            WeekMark.OPEN_DOT
        } else {
            weekMarkFor(dot.state, trackerClass)
        }
        RowMark(date = dot.date, mark = mark, ringed = index == dots.lastIndex)
    }
}

/**
 * What one drawn mark says. Every mark is geometry to a screen reader, and the
 * journal has retired the colours a reader also could not see — so these words
 * are now the only channel the mark grammar has besides shape. The vocabulary
 * deliberately matches the cluster's spoken twin (`describeCategoryRollup`):
 * held, broken, noted — one language whether a category or a single day is
 * speaking.
 *
 * [WeekMark.OPEN_DOT] says "open", not "missed": the whole point of the
 * today-open rule is that those are different statements, and a spoken twin
 * that collapsed them would undo the rule for exactly the users who cannot see
 * the difference drawn.
 */
fun WeekMark.a11yLabel(): String = when (this) {
    WeekMark.FILLED_DOT -> "done"
    WeekMark.HALF_DOT -> "partial"
    WeekMark.OPEN_DOT -> "open"
    WeekMark.SLASHED_DOT -> "missed"
    WeekMark.DASH -> "off schedule"
    WeekMark.HOOP -> "held"
    WeekMark.SLASHED_HOOP -> "broken"
    WeekMark.FILLED_DIAMOND -> "noted"
    WeekMark.OUTLINE_DIAMOND -> "not noted"
}

/**
 * A row's seven-day run, read aloud, or null when there is no run to speak of.
 *
 * The run renders as one semantics node (it is one glance, not seven stops),
 * so everything it draws has to be in this one sentence — the same reason
 * coach's collapsed row spells its tally out. Each day is named by its own
 * weekday: a seven-day run holds each weekday once, so the short name is
 * unambiguous, and the ringed mark is *not* called "today" — the ring marks the
 * selected day, which on a browsed row is not today at all, and the eyebrow
 * already says which day is on screen.
 *
 * It speaks the **drawn** marks, not the underlying states, which is what makes
 * it impossible for the voice and the ink to disagree: a suspended verdict was
 * already an open dot before it arrived here.
 */
fun describeRowMarks(marks: List<RowMark>, locale: Locale): String? {
    if (marks.isEmpty()) return null
    val days = marks.joinToString(", ") { mark ->
        val weekday = LocalDate.parse(mark.date).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        "$weekday ${mark.mark.a11yLabel()}"
    }
    return "Last ${marks.size} days: $days"
}

// ---- the rollup cluster --------------------------------------------------------

/** The cluster's observation half: the diamond, and the count set beside it. */
data class ObservationCluster(val mark: WeekMark, val noted: Int)

/**
 * A section head's rollup, drawn: one flat cluster in the week-mark language,
 * replacing the signal ring.
 *
 * The classes render in this fixed order with a wider gap between them, and they
 * **degrade by subtraction** — a category with no avoidances simply has no hoop,
 * rather than a placeholder for one. [habits] is empty and the other two null
 * exactly when the category asks nothing of that class.
 */
data class RollupCluster(
    val habits: List<WeekMark>,
    val avoidance: WeekMark?,
    val observation: ObservationCluster?,
)

/**
 * Build the cluster, or null when there is nothing to draw and the head stays
 * bare.
 *
 * Two decisions worth spelling out:
 *
 * A not-yet habit draws the **open dot**, never the slashed one. The cluster is
 * a glance at the day as it stands, not a report card on it, and the day is
 * still running — the slash detail belongs on the rows, where the run of days
 * gives it context. This is why the spoken twin (`describeCategoryRollup`, left
 * untouched) says "N of M done" and has no word for missed.
 *
 * The avoidance hoop is a **worst-state collapse** over the whole class: any
 * broken one slashes the single hoop. It counts the rollup exactly as
 * `categoryRollup` reported it — the rows' today-open rule deliberately does not
 * propagate here, because the cluster speaks for a category rather than for the
 * one tracker whose day is still open.
 */
fun rollupCluster(rollup: CategoryRollup?): RollupCluster? {
    if (rollup == null) return null
    val habits = buildList {
        repeat(rollup.habitsMet) { add(WeekMark.FILLED_DOT) }
        repeat(rollup.habitsPartial) { add(WeekMark.HALF_DOT) }
        repeat(rollup.habitsNotYet) { add(WeekMark.OPEN_DOT) }
    }
    val avoidance = when {
        rollup.avoidances <= 0 -> null
        rollup.avoidancesBroken > 0 -> WeekMark.SLASHED_HOOP
        else -> WeekMark.HOOP
    }
    val observation = if (rollup.observationsExpected > 0) {
        ObservationCluster(
            mark = if (rollup.observationsNoted >= 1) WeekMark.FILLED_DIAMOND else WeekMark.OUTLINE_DIAMOND,
            noted = rollup.observationsNoted,
        )
    } else {
        null
    }
    // `categoryRollup` already returns null rather than an empty rollup, so this
    // only catches a hand-built one — but an empty cluster would draw as a stray
    // gap in the head, and no caller should have to know that.
    if (habits.isEmpty() && avoidance == null && observation == null) return null
    return RollupCluster(habits = habits, avoidance = avoidance, observation = observation)
}

// ---- the header eyebrow --------------------------------------------------------

/**
 * The eyebrow's tally: how much of the day has been written down.
 *
 * [logged] counts **entry presence** — a row exists for the tracker on the
 * selected day — and deliberately not `completed`. Entry presence is the
 * journal's load-bearing semantic everywhere else (visibility, `dayStatus`, the
 * observation split), and a note typed then cleared, or a value logged as zero,
 * is still something the day has to say for itself. [total] is what is *visible*
 * on the day, so an off-schedule tracker is not counted as an omission.
 */
data class LoggedTally(val logged: Int, val total: Int) {
    val label: String get() = "$logged of $total logged"
}

/** The tally over an already-built day: every visible row, in every group. */
fun loggedTally(groups: List<CategoryGroupState>): LoggedTally {
    val rows = groups.flatMap { it.trackers }
    return LoggedTally(logged = rows.count { it.hasEntry }, total = rows.size)
}

/**
 * The header's eyebrow — which day is on screen and how much of it is written.
 *
 * [label] is natural-cased; the callsite uppercases it.
 *
 * A day that asks nothing at all drops the tally rather than announcing
 * "0 of 0 logged": the screen below is already an empty state, and a count of
 * nothing is noise in a line this quiet.
 */
sealed interface JournalEyebrow {

    val tally: LoggedTally

    val label: String

    /** The tally segment, or nothing on a day with no visible trackers. */
    val tallyTail: String get() = if (tally.total == 0) "" else " · ${tally.label}"

    /** The live day. Always editable, so it is never the locked variant. */
    data class Today(override val tally: LoggedTally) : JournalEyebrow {
        override val label: String get() = "Today$tallyTail"
    }

    /** A day being browsed, still writable. [day] is already locale-formatted. */
    data class Browsing(val day: String, override val tally: LoggedTally) : JournalEyebrow {
        override val label: String get() = "$day$tallyTail"
    }

    /**
     * A day being browsed that cannot be written to.
     *
     * Worth its own voice: the rows below are all disabled and the reason is
     * nowhere near them — a tracker config change is still waiting to upload, and
     * until it does the server has not arbitrated what an older day contains.
     * The strip draws a lock on those columns; this says the same thing about
     * the one being looked at.
     */
    data class Locked(val day: String, override val tally: LoggedTally) : JournalEyebrow {
        override val label: String get() = "$day$tallyTail · Locked"
    }
}

/**
 * Which eyebrow the header wears.
 *
 * [editable] is `isDayEditable`'s answer, not the calendar's, and today is
 * editable by definition — so [JournalEyebrow.Locked] describes a browsed day
 * only, which is the only day the dirty-tracker rule can lock.
 *
 * The date reuses coach's `shortDatePattern` rather than growing a second one:
 * two eyebrows on two tabs naming a day differently would read as two apps.
 * [locale] is required rather than defaulted, for the reason coach's is — a
 * silent `getDefault()` in here would be a formatting decision no test could
 * pin.
 */
fun journalEyebrow(
    selectedDate: DateString,
    today: DateString,
    tally: LoggedTally,
    editable: Boolean,
    locale: Locale,
): JournalEyebrow {
    if (selectedDate == today) return JournalEyebrow.Today(tally)
    val day = LocalDate.parse(selectedDate).format(shortDatePattern(locale))
    return if (editable) JournalEyebrow.Browsing(day, tally) else JournalEyebrow.Locked(day, tally)
}

// ---- the date strip ------------------------------------------------------------

/**
 * The single letter the Logbook strip draws over a day's number.
 *
 * Taken from the locale's own narrow weekday rather than by slicing
 * [DateCellState.dayName]: that field is a hard-coded English abbreviation, so
 * its first character is not a localized initial at all — while CLDR's narrow
 * form is exactly this one-letter shape, and is the right letter (or glyph) in
 * every locale.
 *
 * Natural-cased, like the eyebrow: the callsite sets it mono-caps and does the
 * uppercasing, which is the app's one casing convention.
 */
fun DateCellState.dayInitial(locale: Locale): String =
    LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.NARROW, locale)
