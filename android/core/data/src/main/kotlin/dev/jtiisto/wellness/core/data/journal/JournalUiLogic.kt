package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.network.DateString
import java.time.LocalDate

/**
 * The rollup and grouping half of `public/js/journal/utils.js`: the collapsed
 * category badge, the 7-day dot row, and category grouping.
 *
 * The observation re-framing lives here, in the presentation helpers, and never
 * in [dayStatus] / [targetStatus] — those are the MCP parity pins.
 */

/** A dot-row day. Not [TargetState]: observations are logged-vs-quiet, not judged. */
enum class DotState { MET, PARTIAL, MISSED, OFF, NOTED, QUIET }

/** Colour of the collapsed-category badge. */
enum class SummaryTone { MET, NEUTRAL }

/**
 * A category's day rollup.
 *
 * [onTrack], [partial] and [notYet] sum to [actionable]; [observed] is counted
 * separately and has no denominator.
 */
data class CategorySummary(
    val actionable: Int = 0,
    val onTrack: Int = 0,
    val partial: Int = 0,
    val notYet: Int = 0,
    val observed: Int = 0,
)

/** The collapsed-header badge. */
data class CategoryBadge(val text: String, val tone: SummaryTone)

/** One dot of the recent-texture row. */
data class DayDot(val date: DateString, val state: DotState)

/**
 * Roll a category's trackers up for [dateStr]. Only trackers *expected* that day
 * count, so an off-schedule day is never a miss.
 *
 * A tracker is **actionable** when there is a goal to be on track against: a
 * non-neutral polarity, or a target in effect. Untargeted neutral trackers are
 * **observations** — judging a "Headache" log by its checkbox is noise, so they
 * are excluded from the fraction and tallied as [CategorySummary.observed]
 * instead.
 */
fun categorySummary(
    trackers: List<TrackerDto>?,
    dateStr: DateString,
    dayLog: Map<String, EntryDto>? = null,
): CategorySummary {
    var actionable = 0
    var onTrack = 0
    var partial = 0
    var notYet = 0
    var observed = 0
    for (tracker in trackers.orEmpty()) {
        if (!isExpectedOn(tracker, dateStr)) continue
        val status = dayStatus(tracker, dateStr, dayLog?.get(tracker.id))
        if (isActionable(tracker, status)) {
            actionable += 1
            when (status.state) {
                TargetState.MET -> onTrack += 1
                TargetState.PARTIAL -> partial += 1
                TargetState.MISSED -> notYet += 1
            }
        } else if (status.hasEntry) {
            observed += 1
        }
    }
    return CategorySummary(actionable, onTrack, partial, notYet, observed)
}

/**
 * The badge model, or null when there is nothing worth saying.
 *
 * With anything actionable the badge is the on-track fraction and observation
 * activity is deliberately dropped, so a mixed category stays compact. A
 * pure-observation category gets a denominator-free "K logged" — there is no
 * expectation to be a fraction of. An empty day is suppressed entirely.
 */
fun formatCategorySummary(summary: CategorySummary?): CategoryBadge? {
    if (summary == null) return null
    if (summary.actionable > 0) {
        val allMet = summary.onTrack == summary.actionable
        return CategoryBadge(
            text = if (allMet) "All on track" else "${summary.onTrack} of ${summary.actionable} on track",
            tone = if (allMet) SummaryTone.MET else SummaryTone.NEUTRAL,
        )
    }
    if (summary.observed > 0) return CategoryBadge("${summary.observed} logged", SummaryTone.NEUTRAL)
    return null
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
