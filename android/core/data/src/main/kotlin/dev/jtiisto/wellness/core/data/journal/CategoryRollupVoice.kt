package dev.jtiisto.wellness.core.data.journal

/**
 * A [CategoryRollup] read aloud.
 *
 * The marks a category head draws are geometry, and geometry is invisible to a
 * screen reader — so this sentence is the whole of what the cluster says to
 * anyone not looking at it. It lives here, beside the rollup it describes and
 * away from any Canvas, so the awkward phrasings (a lone avoidance, observations
 * with no habits beside them) are pinned by tests rather than by looking at a
 * screen.
 *
 * The signal ring this file used to hold retired with the journal's Logbook
 * round: the ring's arcs were replaced by flat state-sorted marks, whose order
 * `JournalNotation.rollupCluster` decides. The sentence outlived the drawing
 * unchanged, which is the point of having kept them apart.
 */

/**
 * The cluster read aloud: one sentence assembled from the classes that are
 * present, so a habits-only category never mentions avoidances it does not have.
 *
 * It mirrors the marks exactly, denominators included — observations spell out
 * their total only when they are the whole cluster, which is the one case the
 * diamond itself reads "n of m".
 */
fun describeCategoryRollup(rollup: CategoryRollup): String {
    val parts = mutableListOf<String>()
    if (rollup.habits > 0) {
        parts += "${rollup.habitsMet} of ${rollup.habits} done"
    }
    if (rollup.avoidances > 0) {
        parts += describeAvoidances(rollup.avoidances, rollup.avoidancesBroken)
    }
    if (rollup.observationsExpected > 0) {
        parts += if (parts.isEmpty()) {
            "${rollup.observationsNoted} of ${rollup.observationsExpected} noted"
        } else {
            "${rollup.observationsNoted} noted"
        }
    }
    return parts.joinToString(", ")
}

/** Broken is stated plainly but never as a failure — a slip is one day's news. */
private fun describeAvoidances(total: Int, broken: Int): String {
    val noun = if (total == 1) "avoidance" else "avoidances"
    return when (broken) {
        0 -> "$noun held"
        total -> "$noun broken"
        else -> "$broken of $total avoidances broken"
    }
}
