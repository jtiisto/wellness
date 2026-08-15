package dev.jtiisto.wellness.core.data.coach

import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * The previous time an exercise was actually performed — a port of
 * `coach/last-performance.js`.
 *
 * Matching is by `canonical_slug` across ANY past workout in the synced window,
 * optionally narrowed to one `exposure` (HEAVY / LIGHT / …, since several
 * exposures of a movement deliberately share a slug). The exposure is read from
 * each past date's **historical plan**, never from log data: plans in the window
 * carry it, while the lean log shape stays wire-invariant.
 *
 * Dates are "YYYY-MM-DD" strings throughout, so lexicographic comparison is both
 * correct and timezone-safe.
 */

/** The set fields that make a logged set count as performed. */
private val SET_DATA_FIELDS = listOf("weight", "reps", "rpe", "duration_sec")

/** A past session's contribution: the day it happened and the sets that carry data. */
data class LastPerformance(val date: DateString, val sets: List<JsonObject>)

/**
 * A set counts as performed once it carries any real metric.
 *
 * `0` is real data — a zero-weight set is a bodyweight set, not a blank row —
 * so this asks whether the key is present and non-null, never whether it is
 * truthy. `set_num` and `completed` are bookkeeping and do not count.
 */
fun setHasData(set: JsonElement?): Boolean {
    val obj = set as? JsonObject ?: return false
    return SET_DATA_FIELDS.any { obj[it].isPresent() }
}

/** The plan entry whose slug matches, as its log key plus the exposure it was planned at. */
private data class PlanExerciseRef(val id: String, val exposure: String?)

private fun planExerciseForSlug(plan: PlanDto?, canonicalSlug: String): PlanExerciseRef? {
    if (plan == null) return null
    for (block in plan.blocks) {
        for (exercise in block.exercises) {
            if (exercise.canonicalSlug == canonicalSlug) {
                return PlanExerciseRef(exercise.id, exercise.exposure)
            }
        }
    }
    return null
}

/**
 * The most recent session strictly before [refDate] where this exercise was
 * logged with real set data, or null.
 *
 * With an [exposure], the newest SAME-exposure session wins even over a newer
 * session of another exposure — progression chains run per exposure. The newest
 * any-exposure session is kept as a fallback so that a brand-new exposure key,
 * and pre-epoch history that carries none at all, still shows something; the
 * date hint beside the ghosts supplies the missing context. A plan entry with no
 * exposure therefore never *matches* a requested one, but is fallback-eligible.
 *
 * Both sides are server-normalized, so exact string equality is the right test.
 */
fun findLastPerformance(
    canonicalSlug: String?,
    refDate: DateString?,
    plans: Map<DateString, PlanDto?>,
    logs: Map<DateString, JsonObject>,
    exposure: String? = null,
): LastPerformance? {
    if (canonicalSlug.isNullOrEmpty() || refDate.isNullOrEmpty()) return null

    var fallback: LastPerformance? = null
    for (date in plans.keys.filter { it < refDate }.sortedDescending()) {
        val planExercise = planExerciseForSlug(plans[date], canonicalSlug) ?: continue
        val entry = logs[date]?.get(planExercise.id) as? JsonObject
        val sets = entry?.array("sets").orEmpty()
            .filterIsInstance<JsonObject>()
            .filter(::setHasData)
        if (sets.isEmpty()) continue

        // Returning on the first match is what makes "newest wins" hold: the
        // dates are walked newest-first.
        if (exposure.isNullOrEmpty() || planExercise.exposure == exposure) {
            return LastPerformance(date, sets)
        }
        if (fallback == null) fallback = LastPerformance(date, sets)
    }
    return fallback
}

/**
 * "2026-06-01" -> "Jun 1", for the `Last · <date>` hint under the set grid.
 *
 * Parsed from the string's parts rather than through a `Date`, so no timezone
 * can shift the day. The month name is localized (deviation 3) where the PWA
 * indexes a hardcoded English array. Anything unparseable is echoed back
 * verbatim — a wrong-looking date is more useful than a blank hint.
 */
fun formatShortDate(isoDate: String?, locale: Locale = Locale.getDefault()): String {
    if (isoDate.isNullOrEmpty()) return ""
    val parts = isoDate.split("-")
    val month = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 } ?: return isoDate
    val day = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it != 0 } ?: return isoDate
    return "${Month.of(month).getDisplayName(TextStyle.SHORT, locale)} $day"
}
