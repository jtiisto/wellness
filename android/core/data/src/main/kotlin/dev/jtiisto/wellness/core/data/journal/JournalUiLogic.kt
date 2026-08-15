package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.network.DateString
import java.time.LocalDate

/**
 * The rollup and grouping half of `public/js/journal/utils.js`: the category
 * band's rollup, the 7-day dot row, and category grouping.
 *
 * The observation re-framing lives here, in the presentation helpers, and never
 * in [dayStatus] / [targetStatus] — those are the MCP parity pins.
 */

/** A dot-row day. Not [TargetState]: observations are logged-vs-quiet, not judged. */
enum class DotState { MET, PARTIAL, MISSED, OFF, NOTED, QUIET }

/**
 * A category's day, split by what each tracker asks of you.
 *
 * The three classes are the signal ring's three marks: **habits** are things to
 * do (the ring, one slice each), **avoidances** are things to hold (the centre),
 * **observations** are things to notice (the diamond) — logged, never judged.
 *
 * Habits carry all three verdicts; avoidances only two, because the centre mark
 * collapses to its worst state and an at-most target never returns partial.
 * Observations count what was *expected* as well as what was noted: an unnoted
 * observation is not a miss, but it is still something the day asked about.
 */
data class CategoryRollup(
    val habitsMet: Int = 0,
    val habitsPartial: Int = 0,
    val habitsNotYet: Int = 0,
    val avoidances: Int = 0,
    val avoidancesBroken: Int = 0,
    val observationsExpected: Int = 0,
    val observationsNoted: Int = 0,
) {
    /** The ring's slice count. */
    val habits: Int get() = habitsMet + habitsPartial + habitsNotYet
}

/** One dot of the recent-texture row. */
data class DayDot(val date: DateString, val state: DotState)

/**
 * Roll a category's trackers up for [dateStr], or null when the day asked
 * nothing of it. Only trackers *expected* that day count, so an off-schedule
 * day is never a miss — and a category holding nothing but off-schedule rows
 * leaves the band bare rather than claiming a perfect day.
 *
 * A tracker is **actionable** when there is a goal to be on track against: a
 * non-neutral polarity, or a target in effect. Untargeted neutral trackers are
 * observations — judging a "Headache" log by its checkbox is noise.
 */
fun categoryRollup(
    trackers: List<TrackerDto>?,
    dateStr: DateString,
    dayLog: Map<String, EntryDto>? = null,
): CategoryRollup? {
    var met = 0
    var partial = 0
    var notYet = 0
    var avoidances = 0
    var broken = 0
    var observed = 0
    var noted = 0
    for (tracker in trackers.orEmpty()) {
        if (!isExpectedOn(tracker, dateStr)) continue
        val status = dayStatus(tracker, dateStr, dayLog?.get(tracker.id))
        when {
            !isActionable(tracker, status) -> {
                observed += 1
                if (status.hasEntry) noted += 1
            }
            // Held or not, never partly: a negative tracker carrying a range
            // target can still return PARTIAL, and the centre mark has no third
            // state to spend on it.
            tracker.polarity == "negative" -> {
                avoidances += 1
                if (status.state != TargetState.MET) broken += 1
            }
            else -> when (status.state) {
                TargetState.MET -> met += 1
                TargetState.PARTIAL -> partial += 1
                TargetState.MISSED -> notYet += 1
            }
        }
    }
    if (met + partial + notYet + avoidances + observed == 0) return null
    return CategoryRollup(met, partial, notYet, avoidances, broken, observed, noted)
}

/**
 * The last [n] days ending on [endDateStr] (oldest → newest) with each day's
 * state for [tracker] — the recent-texture dot row. Purely the single-day
 * predicate repeated: no streaks, no rates.
 *
 * Days the tracker is not expected on are [DotState.OFF] (off-schedule is not a
 * miss), and so are days before [earliestKnownDate]: their logs have been
 * pruned locally, so absence there means "unknown", and judging them would
 * fabricate a miss out of missing data.
 */
fun recentDayStates(
    tracker: TrackerDto,
    endDateStr: DateString,
    logs: Map<DateString, Map<String, EntryDto>>?,
    n: Int = 7,
    earliestKnownDate: DateString? = null,
): List<DayDot> {
    val end = LocalDate.parse(endDateStr)
    return (n - 1 downTo 0).map { back ->
        val dateStr = end.minusDays(back.toLong()).toString()
        val state = when {
            earliestKnownDate != null && dateStr < earliestKnownDate -> DotState.OFF
            !isExpectedOn(tracker, dateStr) -> DotState.OFF
            else -> {
                val status = dayStatus(tracker, dateStr, logs?.get(dateStr)?.get(tracker.id))
                if (isActionable(tracker, status)) {
                    when (status.state) {
                        TargetState.MET -> DotState.MET
                        TargetState.PARTIAL -> DotState.PARTIAL
                        TargetState.MISSED -> DotState.MISSED
                    }
                } else {
                    if (status.hasEntry) DotState.NOTED else DotState.QUIET
                }
            }
        }
        DayDot(dateStr, state)
    }
}

/**
 * Trackers by category, categories and names both sorted by plain
 * [String.compareTo]. The PWA sorted category keys that way already and tracker
 * names by `localeCompare`; unifying them is a declared deviation (it only
 * moves non-ASCII names).
 */
fun groupByCategory(trackers: List<TrackerDto>): Map<String, List<TrackerDto>> =
    trackers
        .groupBy { it.category ?: UNCATEGORIZED }
        .toSortedMap()
        .mapValues { (_, group) -> group.sortedBy { it.name.orEmpty() } }

/** The distinct categories in use, sorted. Blank categories do not count. */
fun getCategories(trackers: List<TrackerDto>): List<String> =
    trackers.mapNotNull { it.category?.takeIf(String::isNotEmpty) }.distinct().sorted()

/**
 * Whether a day is editable: today always, and any other day only while **no
 * tracker** is dirty. Dirty entries never lock the strip — only a pending
 * tracker config change (a delete included), whose upload could re-arbitrate
 * what the older day even contains.
 */
fun isDayEditable(dateStr: DateString, today: DateString, dirtyTrackerCount: Int): Boolean =
    dateStr == today || dirtyTrackerCount == 0

/** The category a tracker with none falls into. */
const val UNCATEGORIZED = "Uncategorized"

private fun isActionable(tracker: TrackerDto?, status: DayStatus): Boolean {
    val polarity = tracker?.polarity
    return (polarity != null && polarity.isNotEmpty() && polarity != "neutral") || status.hasTarget
}
