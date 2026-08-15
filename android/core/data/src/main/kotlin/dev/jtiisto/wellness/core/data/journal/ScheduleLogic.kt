package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import kotlin.math.floor

/**
 * The tracker-scheduling half of `public/js/journal/utils.js`: weekday
 * derivation, the effective-dated segment model, and the apply-from-today write
 * rule the schedule and target writers share.
 *
 * Pure — every function takes the date it should reason about. Nothing here
 * reads a clock, which is what lets `JournalScheduleLogicTest` transcribe the
 * JS suite as fixed-date assertions instead of TZ-pinned ones.
 */

/** Every weekday (Sun..Sat, 0..6) — "daily", and the default with no schedule. */
val ALL_DAYS: List<Int> = listOf(0, 1, 2, 3, 4, 5, 6)

/**
 * Tracker polarity values. Absent polarity is unspecified/neutral.
 */
val POLARITY_VALUES: List<String> = listOf("positive", "negative", "neutral")

private val DAY_SHORT_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/** One cell of the date strip. [dayOfWeek] is 0=Sun..6=Sat, as the schedule model uses. */
data class DayCell(
    val date: DateString,
    val dayName: String,
    val dayNum: Int,
    val isToday: Boolean,
    val dayOfWeek: Int,
)

/** A segment of an effective-dated history — see [selectSegmentForDate]. */
interface EffectiveDated {
    val effectiveFrom: DateString
}

/** The last [n] days ending on (and including) [today], oldest → newest. */
fun getLastNDays(today: LocalDate, n: Int = 7): List<DayCell> =
    (n - 1 downTo 0).map { back ->
        val date = today.minusDays(back.toLong())
        DayCell(
            date = date.toString(),
            dayName = DAY_SHORT_NAMES[dayOfWeekOf(date)],
            dayNum = date.dayOfMonth,
            isToday = back == 0,
            dayOfWeek = dayOfWeekOf(date),
        )
    }

/** The local weekday of a `YYYY-MM-DD` string, 0=Sun..6=Sat. */
fun getDayOfWeek(dateStr: DateString): Int = dayOfWeekOf(LocalDate.parse(dateStr))

private fun dayOfWeekOf(date: LocalDate): Int = date.dayOfWeek.value % 7

/**
 * Sorted, de-duplicated weekdays in 0..6. Anything out of range is ignored, so
 * a stray value can never widen or corrupt a schedule.
 *
 * The JS twin also coerces numeric strings (`'2'`); a Kotlin `List<Int>` cannot
 * carry one, so that branch has no representation here.
 */
fun normalizeDays(days: List<Int>?): List<Int> =
    days.orEmpty().filter { it in 0..6 }.distinct().sorted()

/**
 * The segment of [history] that applies on [dateStr]: the greatest
 * `effectiveFrom <= dateStr`, falling back to the earliest when [dateStr]
 * precedes them all. Dates compare as plain `YYYY-MM-DD` strings (chronological
 * for zero-padded ISO dates; no `Date`, no timezone), and the scan is
 * order-independent. Null for an absent/empty history.
 */
fun <T : EffectiveDated> selectSegmentForDate(history: List<T>?, dateStr: DateString): T? {
    if (history.isNullOrEmpty()) return null
    val chosen = history
        .filter { it.effectiveFrom <= dateStr }
        .maxByOrNull { it.effectiveFrom }
    // dateStr precedes every segment — use the earliest.
    return chosen ?: history.minByOrNull { it.effectiveFrom }
}

/**
 * The weekdays a tracker is scheduled on as of [dateStr]:
 *
 * 1. `scheduleHistory` (effective-dated segments) — see [selectSegmentForDate];
 * 2. legacy `frequency: "weekly"` → just `weeklyDay`;
 * 3. legacy `frequency: "daily"`, or nothing at all → every day.
 *
 * An empty set means paused.
 */
fun getScheduleDaysForDate(tracker: TrackerDto?, dateStr: DateString): Set<Int> {
    val segment = selectSegmentForDate(tracker?.scheduleHistory, dateStr)
    if (segment != null) return normalizeDays(segment.days).toSet()

    if (tracker?.legacyFrequency() == "weekly") {
        // Deliberately unvalidated, matching the JS `new Set([tracker.weeklyDay])`:
        // an out-of-range day yields a set nothing is ever expected on.
        return tracker.legacyWeeklyDay()?.let { setOf(it) } ?: emptySet()
    }

    return ALL_DAYS.toSet()
}

/**
 * The weekdays a paused tracker restores to when unpaused: the `days` of the
 * most recent `scheduleHistory` segment that actually has any (so the pause
 * segment's `[]` is skipped, as are older empty ones). Falls back to the legacy
 * weekly day, then to Daily — a tracker born paused resumes to Daily.
 */
fun lastActiveScheduleDays(tracker: TrackerDto?): List<Int> {
    val history = tracker?.scheduleHistory
    if (!history.isNullOrEmpty()) {
        val chosen = history
            .map { it.effectiveFrom to normalizeDays(it.days) }
            .filter { (_, days) -> days.isNotEmpty() }
            .maxByOrNull { (effectiveFrom, _) -> effectiveFrom }
        if (chosen != null) return chosen.second
    }
    val weeklyDay = tracker?.takeIf { it.legacyFrequency() == "weekly" }?.legacyWeeklyDay()
    if (weeklyDay != null && weeklyDay in 0..6) return listOf(weeklyDay)
    return ALL_DAYS
}

/** Whether the tracker's schedule as of [dateStr] includes that date's weekday. */
fun isExpectedOn(tracker: TrackerDto?, dateStr: DateString): Boolean =
    getDayOfWeek(dateStr) in getScheduleDaysForDate(tracker, dateStr)

/**
 * Whether a tracker appears on [dateStr]: expected that day, OR already carrying
 * an entry for it. The predicate is **row existence** in [dayLog] — even an
 * entry with `completed = false` and no value keeps an off-schedule row visible,
 * so unchecking one mid-edit does not make it vanish. With [dayLog] omitted this
 * reduces to pure expectation.
 */
fun shouldShowTracker(
    tracker: TrackerDto?,
    dateStr: DateString,
    dayLog: Map<String, EntryDto>? = null,
): Boolean {
    if (isExpectedOn(tracker, dateStr)) return true
    return tracker != null && dayLog?.containsKey(tracker.id) == true
}

/** [changed] false means the caller must not write, nor mark the tracker dirty. */
data class SegmentEditResult<T : EffectiveDated>(val changed: Boolean, val history: List<T>?)

/**
 * The apply-from-today write rule shared by the schedule and target histories.
 *
 * - no-op (values equal, no future segments) → `changed = false`, [history]
 *   returned by the same reference;
 * - first edit (no history) → a genesis segment carrying the old value plus a
 *   `today` segment carrying the new one;
 * - otherwise → drop every segment dated today or later and append a `today`
 *   segment. That single rule covers both the same-day re-edit (replace) and
 *   the supersede of a future-dated segment left by clock skew — which would
 *   otherwise silently override this edit the day it came into effect. An edit
 *   made today supersedes them **including a value-equal one**: the user just
 *   confirmed today's value, so the pending flip must not survive it.
 */
private fun <V, T : EffectiveDated> applySegmentEdit(
    history: List<T>?,
    currentValue: V,
    newValue: V,
    today: DateString,
    equals: (V, V) -> Boolean,
    makeSegment: (DateString, V) -> T,
): SegmentEditResult<T> {
    val existing = history?.takeIf { it.isNotEmpty() }
    val hasFuture = existing?.any { it.effectiveFrom > today } == true
    if (equals(newValue, currentValue) && !hasFuture) {
        return SegmentEditResult(changed = false, history = history)
    }
    if (existing == null) {
        return SegmentEditResult(
            changed = true,
            history = listOf(
                makeSegment(SCHEDULE_GENESIS_DATE, currentValue),
                makeSegment(today, newValue),
            ),
        )
    }
    return SegmentEditResult(
        changed = true,
        history = existing.filter { it.effectiveFrom < today } + makeSegment(today, newValue),
    )
}

/** The tracker's next `scheduleHistory` after the user picks [newDays]. */
fun computeScheduleHistoryUpdate(
    tracker: TrackerDto?,
    newDays: List<Int>,
    today: DateString,
): SegmentEditResult<ScheduleSegmentDto> = applySegmentEdit(
    history = tracker?.scheduleHistory,
    currentValue = normalizeDays(getScheduleDaysForDate(tracker, today).toList()),
    newValue = normalizeDays(newDays),
    today = today,
    equals = { a, b -> a == b },
    makeSegment = { effectiveFrom, days -> ScheduleSegmentDto(effectiveFrom, days) },
)

/**
 * The tracker's next `targetHistory` after the user sets [newTarget] (null =
 * cleared, which is recorded as a `target: null` segment so past target-based
 * adherence is preserved).
 */
fun computeTargetHistoryUpdate(
    tracker: TrackerDto?,
    newTarget: TargetDto?,
    today: DateString,
): SegmentEditResult<TargetSegmentDto> = applySegmentEdit(
    history = tracker?.targetHistory,
    currentValue = targetForDate(tracker, today),
    newValue = newTarget,
    today = today,
    equals = ::targetsEqual,
    makeSegment = { effectiveFrom, target -> TargetSegmentDto(effectiveFrom, target) },
)

/**
 * A weekday set for the config list: "Paused" (empty — see
 * [buildTrackerSaveFields]), "Daily", "Mon–Fri", or a slash-joined short list.
 */
fun formatScheduleSummary(daysInput: Collection<Int>?): String {
    val days = normalizeDays(daysInput?.toList())
    if (days.isEmpty()) return "Paused"
    if (days == ALL_DAYS) return "Daily"
    if (days == listOf(1, 2, 3, 4, 5)) return "Mon–Fri"
    return days.joinToString("/") { DAY_SHORT_NAMES[it] }
}

private fun TrackerDto.legacyFrequency(): String? =
    (extras["frequency"] as? JsonPrimitive)?.contentOrNull

/** The legacy `weeklyDay`, as any integer — range validation is the caller's. */
private fun TrackerDto.legacyWeeklyDay(): Int? {
    val number = (extras["weeklyDay"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
    if (!number.isFinite() || number != floor(number)) return null
    return number.toInt()
}
