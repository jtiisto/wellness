package dev.jtiisto.wellness.feature.trends.chart

import dev.jtiisto.wellness.core.ui.theme.WeekMark
import dev.jtiisto.wellness.core.ui.theme.a11yLabel

/**
 * Logbook's trends notation, derived.
 *
 * Everything a Trends screen *decides* about how something reads — what a run of
 * adherence days is called out loud, how a range is said in words, which mark a
 * focus day earns — is decided here rather than inside a composable, the same
 * split `CoachNotation` and `JournalNotation` make. The charts themselves were
 * already pure; this is the chrome catching up with them. The series vocabulary
 * itself lives next door in [ChartInk], which is keyed by chart because plates
 * are assigned per chart.
 *
 * Nothing here fetches, judges or measures. The judgments arrived from the
 * server and the geometry from the builders; this only chooses how an
 * already-made statement is drawn and spoken.
 */

// ---- Header --------------------------------------------------------------------

/** The window, in words — the eyebrow says what the whole page is measured over. */
fun rangeCaption(rangeId: String): String = when (rangeId) {
    "4w" -> "Last 4 weeks"
    "12w" -> "Last 12 weeks"
    "6m" -> "Last 6 months"
    "all" -> "All time"
    // A preference written by a future build reads as its own id rather than as
    // a wrong window: `rangeStart` already treats an unknown id as All, and
    // saying so in the eyebrow would be the one place the page disagreed.
    else -> RANGES.firstOrNull { it.id == rangeId }?.label ?: rangeId
}

/** The page's display title: which of the five is on screen. */
fun screenTitle(screenId: String): String =
    TREND_SCREENS.firstOrNull { it.id == screenId }?.label ?: "Trends"

/** `Trends · Last 12 weeks` — natural-cased; the header uppercases it. */
fun trendsEyebrow(rangeId: String): String = "Trends · ${rangeCaption(rangeId)}"

// ---- Streaks -------------------------------------------------------------------

/**
 * The streaks, in mono.
 *
 * The flame retires with the rest of the colour emoji: a run is a number, and
 * a number in this system is set in Plex Mono and says what it counts.
 */
fun streakLine(current: Int, best: Int): String = "run $current · best $best"

/**
 * The PR badge without its trophy.
 *
 * `prTileModel` is a pinned builder — its badge string is asserted verbatim by
 * `CardModelsTest` — so the emoji comes off at the one place that renders it
 * rather than at the one place that builds it. Logbook allows no colour emoji
 * anywhere on the page; retiring it in the builder is a one-line follow-up for
 * whenever that test is next open.
 */
fun prBadgeText(badge: String): String = badge.removePrefix("🏆 ")

// ---- Overview focus ribbons ----------------------------------------------------

/**
 * A focus day's mark, in the journal's own vocabulary.
 *
 * Overview's ribbon answers the same question a journal row's week does — did
 * this day meet its target — so it is drawn in the same nine-shape grammar
 * rather than in a second one invented for a chart. `off` is a dash: nothing
 * was asked that day, which is not a failure. A status this build does not know
 * draws the open dot: no verdict is the honest answer to a word we cannot read.
 */
fun focusMark(status: String): WeekMark = when (status) {
    "met" -> WeekMark.FILLED_DOT
    "partial" -> WeekMark.HALF_DOT
    "missed" -> WeekMark.SLASHED_DOT
    "off" -> WeekMark.DASH
    else -> WeekMark.OPEN_DOT
}

/**
 * The ribbon, read aloud, or null when there is nothing in it.
 *
 * Tallied rather than enumerated, which is where this parts company with the
 * journal's seven-day run: a focus ribbon is a fortnight, and fourteen weekday
 * names would repeat themselves and say nothing a count does not. The words are
 * the marks' own ([a11yLabel]), so the sentence cannot drift from the drawing,
 * and the order is the vocabulary's own so two rows always tally alike.
 */
fun describeFocusRibbon(days: List<String>): String? {
    if (days.isEmpty()) return null
    val marks = days.map(::focusMark)
    val tally = WeekMark.entries
        .mapNotNull { mark ->
            val count = marks.count { it == mark }
            if (count == 0) null else "$count ${mark.a11yLabel()}"
        }
        .joinToString(", ")
    return "Last ${days.size} days: $tally"
}

// ---- Labs ----------------------------------------------------------------------

/**
 * Whether a mini-strip's latest observation is one the lab flagged.
 *
 * Read off the plot rather than carried on the model: the dots are built in
 * observation order and a flagged one is exactly a [PlotTone.WARN] dot, so the
 * chart and the `!` beside the value cannot disagree about which reading is
 * being called out. `MiniLabModel` is a pinned builder and gains no field.
 */
fun latestIsFlagged(plot: PlotModel): Boolean =
    plot.dots.lastOrNull()?.tone == PlotTone.WARN

/** The attention mark. Mono, ink, and the only thing standing in for a colour. */
const val FLAG_GLYPH = "!"
