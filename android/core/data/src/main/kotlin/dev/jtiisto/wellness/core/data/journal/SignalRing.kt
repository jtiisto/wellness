package dev.jtiisto.wellness.core.data.journal

import kotlin.math.min

/**
 * The signal ring's geometry and its spoken twin.
 *
 * The ring draws a [CategoryRollup]'s habits, one slice each, at a footprint
 * that never changes: a category with eight trackers has to read as a *day*,
 * not as a long list. Slices are not individually identifiable, so they are
 * sorted by state — a contiguous arc of settled work is what makes the shape
 * legible at 18dp.
 *
 * The maths live here, away from the Canvas, so the awkward counts (a lone
 * slice, no slices, a category with more habits than the circle has room for)
 * are pinned by tests rather than by looking at a screen.
 */

/** What a slice says. Declaration order is the drawing order: settled work first. */
enum class RingRole { MET, PARTIAL, NOT_YET }

/** One arc in `drawArc`'s own units: degrees, clockwise, 0° at 3 o'clock. */
data class RingSlice(val startDeg: Float, val sweepDeg: Float, val role: RingRole)

/** 12 o'clock, where the ring starts. */
private const val RING_START_DEG = -90f

/** The breathing room that keeps two adjacent slices from reading as one. */
private const val RING_GAP_DEG = 6f

private const val FULL_CIRCLE_DEG = 360f

/**
 * The ring's slices, state-sorted and evenly divided.
 *
 * A lone slice is a full circle: a gap needs two edges to sit between, and a
 * single arc with a bite out of it reads as an incomplete one.
 */
fun ringSlices(met: Int, partial: Int, notYet: Int): List<RingSlice> {
    val roles = buildList {
        repeat(met) { add(RingRole.MET) }
        repeat(partial) { add(RingRole.PARTIAL) }
        repeat(notYet) { add(RingRole.NOT_YET) }
    }
    if (roles.isEmpty()) return emptyList()
    if (roles.size == 1) return listOf(RingSlice(RING_START_DEG, FULL_CIRCLE_DEG, roles.first()))

    // Past 60 slices a fixed 6° gap would eat the whole circle. The gap yields
    // rather than the slice, so an absurdly large category still draws a ring.
    val gap = min(RING_GAP_DEG, FULL_CIRCLE_DEG / roles.size / 2f)
    val sweep = (FULL_CIRCLE_DEG - roles.size * gap) / roles.size
    return roles.mapIndexed { index, role ->
        RingSlice(RING_START_DEG + index * (sweep + gap), sweep, role)
    }
}

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
