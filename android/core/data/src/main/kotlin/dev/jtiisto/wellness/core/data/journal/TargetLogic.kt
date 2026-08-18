package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The typed-target half of `public/js/journal/utils.js`: parse/format, the
 * effective-dated `targetForDate` selection, the per-day judgment
 * ([targetStatus] / [dayStatus]), and the row's progress display model.
 *
 * [targetStatus] and [coerceNumericValue] are twins of `_target_status` and
 * `_coerce_numeric` in the server's `adherence.py`. They are kept faithful so
 * the day view and the MCP/coach can never disagree about whether a day was
 * met — change one only alongside the other.
 */

/** A day's judgment against a target. */
enum class TargetState { MET, PARTIAL, MISSED }

/** Row colour for [formatTargetProgress]. */
enum class ProgressTone { MET, PARTIAL, OVER, NEUTRAL }

/** [target] is null whenever [error] is set, and vice versa. */
data class ParsedTarget(val target: TargetDto? = null, val error: String? = null)

/** Everything the row and the dots need to know about one tracker on one day. */
data class DayStatus(
    val state: TargetState,
    val hasTarget: Boolean,
    val target: TargetDto?,
    val value: JsonElement?,
    val hasEntry: Boolean,
    val polarity: String?,
)

/** The inline target line: [fillPct] is 0..100 and only set for at-least targets. */
data class TargetProgress(val text: String, val tone: ProgressTone, val fillPct: Double?)

private const val RANGE_ERROR = "Range start must be ≤ end"
private const val PARSE_ERROR = "Enter a number (e.g. 10) or range (e.g. 150-170)"

private val RANGE_PATTERN = Regex("""^(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)$""")
private val SINGLE_PATTERN = Regex("""^(\d+(?:\.\d+)?)$""")

/** The target in effect on [dateStr], or null (absent history, or a cleared segment). */
fun targetForDate(tracker: TrackerDto?, dateStr: DateString): TargetDto? =
    selectSegmentForDate(tracker?.targetHistory, dateStr)?.target

/**
 * Parse the config form's free-text target.
 *
 * - blank → no target, no error
 * - `"150-170"` → a range
 * - `"10"` → polarity-defaulted: a ceiling for `negative`, a floor otherwise
 * - min > max, non-numeric, or negative → [ParsedTarget.error]
 */
fun parseTarget(str: String?, polarity: String?): ParsedTarget {
    val text = str?.trim() ?: return ParsedTarget()
    if (text.isEmpty()) return ParsedTarget()

    val range = RANGE_PATTERN.find(text)
    if (range != null) {
        val minimum = range.groupValues[1].toDouble()
        val maximum = range.groupValues[2].toDouble()
        if (minimum > maximum) return ParsedTarget(error = RANGE_ERROR)
        return ParsedTarget(target = TargetDto(min = minimum, max = maximum))
    }

    val single = SINGLE_PATTERN.find(text)
    if (single != null) {
        val number = single.groupValues[1].toDouble()
        return ParsedTarget(
            target = if (polarity == "negative") TargetDto(max = number) else TargetDto(min = number),
        )
    }

    return ParsedTarget(error = PARSE_ERROR)
}

/** "≥ 10 g", "≤ 2", "150–170 g", "8 g" (exact). Empty target → "". */
fun formatTarget(target: TargetDto?, unit: String? = null): String {
    val minimum = target?.min
    val maximum = target?.max
    if (minimum == null && maximum == null) return ""
    val suffix = if (unit.isNullOrEmpty()) "" else " $unit"
    if (minimum != null && maximum != null) {
        return if (minimum == maximum) {
            "${formatJournalNumber(minimum)}$suffix"
        } else {
            "${formatJournalNumber(minimum)}–${formatJournalNumber(maximum)}$suffix"
        }
    }
    if (minimum != null) return "≥ ${formatJournalNumber(minimum)}$suffix"
    return "≤ ${formatJournalNumber(maximum!!)}$suffix"
}

/**
 * The inverse of [parseTarget], for seeding the config input. Exact targets
 * render in range form (`"8-8"`) so an unedited save round-trips to a no-op
 * under the equality guard.
 */
fun formatTargetInput(target: TargetDto?): String {
    val minimum = target?.min
    val maximum = target?.max
    if (minimum == null && maximum == null) return ""
    if (minimum != null && maximum != null) {
        return "${formatJournalNumber(minimum)}-${formatJournalNumber(maximum)}"
    }
    return formatJournalNumber(minimum ?: maximum!!)
}

/**
 * A day's logged value as a number, or null when absent/non-numeric.
 *
 * Entries share one `value` field across tracker types, so a tracker converted
 * to or from `note` can carry free text — a targeted comparison must read that
 * as "no usable value" rather than let NaN comparisons (all false) pass for
 * in-range. Twin of `adherence.py`'s `_coerce_numeric`.
 */
fun coerceNumericValue(value: JsonElement?): Double? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    // A JSON boolean is not a number and not a string: neither JS branch takes it.
    if (!primitive.isString && primitive.booleanOrNull != null) return null
    if (primitive.isString && primitive.content.isBlank()) return null
    val number = primitive.content.trim().toDoubleOrNull() ?: return null
    return if (number.isFinite()) number else null
}

/**
 * Per-day target judgment.
 *
 * The polarity no-entry gate is applied **first**, before any bound check, for
 * every target kind: with no entry a negative-polarity tracker is [MET]
 * (absence = avoided) and anything else is [MISSED]. Without that ordering a
 * positive at-most tracker would read "no value ≤ max" as met.
 *
 * With an entry and a usable value: at-least is met at or above `min` and
 * partial only above zero; at-most is met at or below `max` and **never
 * partial**; a range is met inside, partial below, missed above.
 */
fun targetStatus(
    target: TargetDto?,
    value: JsonElement?,
    hasEntry: Boolean,
    polarity: String?,
): TargetState {
    if (!hasEntry) return if (polarity == "negative") TargetState.MET else TargetState.MISSED
    val number = coerceNumericValue(value) ?: return TargetState.MISSED
    val minimum = target?.min
    val maximum = target?.max
    if (minimum != null && maximum != null) {
        return when {
            number < minimum -> TargetState.PARTIAL
            number > maximum -> TargetState.MISSED
            else -> TargetState.MET
        }
    }
    if (minimum != null) {
        if (number >= minimum) return TargetState.MET
        return if (number > 0) TargetState.PARTIAL else TargetState.MISSED
    }
    if (maximum != null) {
        return if (number <= maximum) TargetState.MET else TargetState.MISSED
    }
    return TargetState.MISSED
}

/**
 * Whether an entry asserts anything — the judgment-time presence rule.
 *
 * Unchecking a box does not delete the row: every client writes
 * `{completed: false}` and keeps it (the wire has no entry delete). If judgment
 * keyed off row existence, an uncheck could never be retracted — the day would
 * read "broken" forever. So an entry counts as logged iff it *says* something:
 * the box is ticked, or a value was written. An all-empty row judges exactly
 * like no row at all.
 *
 * A written value keeps the row counted even with the box cleared. The value is
 * the assertion; blanking the field is its retraction.
 *
 * This is presence for JUDGMENT only. Presence for VISIBILITY stays raw row
 * existence ([shouldShowTracker]) — an off-schedule row that has been written to
 * has to stay on screen, or the entry could never be found and cleared. The
 * storage distinction between an absent and an explicitly-null value is likewise
 * untouched; both simply read as "no value" here.
 */
fun EntryDto?.countsAsLogged(): Boolean = this?.completed == true || hasValue()

/**
 * A tracker's status on one date, from that day's entry.
 *
 * - Targeted: delegate to [targetStatus].
 * - Untargeted: strict checkbox parity — positive/neutral is met iff the
 *   checkbox is set; negative is met iff there is **no entry** (avoided). A
 *   value with no checkbox is not met.
 *
 * "No entry" is [countsAsLogged]'s answer, not the row's existence: a blank
 * [EntryDto] left behind by an uncheck judges as nothing logged.
 */
fun dayStatus(tracker: TrackerDto?, dateStr: DateString, entry: EntryDto?): DayStatus {
    val target = targetForDate(tracker, dateStr)
    val hasTarget = target != null
    val hasEntry = entry.countsAsLogged()
    val value = entry?.value
    val completed = entry?.completed == true
    val polarity = tracker?.polarity
    val state = when {
        hasTarget -> targetStatus(target, value, hasEntry, polarity)
        polarity == "negative" -> if (hasEntry) TargetState.MISSED else TargetState.MET
        else -> if (completed) TargetState.MET else TargetState.MISSED
    }
    return DayStatus(state, hasTarget, target, value, hasEntry, polarity)
}

/**
 * The inline target-progress model for a quantifiable row. Presentation only —
 * the semantics are [dayStatus]'s. Null when no target is in effect.
 *
 * - at-least: progress, `"120 / ≥ 150 g"` plus a fill ratio;
 * - at-most: headroom, `"1 of ≤ 2 · 1 left"`; over the ceiling reads as a calm
 *   warning, never an error;
 * - range: membership, `"160 in 150–170 g"`.
 *
 * The maths run on the coerced value while the raw one is still displayed: an
 * entry whose value is absent or non-numeric has no usable value and must
 * render neutral rather than contradict the day dot.
 */
fun formatTargetProgress(status: DayStatus?, unit: String? = null): TargetProgress? {
    val target = status?.target ?: return null
    if (!status.hasTarget) return null

    val label = formatTarget(target, unit)
    val minimum = target.min
    val maximum = target.max
    val isAtLeast = minimum != null && maximum == null
    val isAtMost = maximum != null && minimum == null

    if (!status.hasEntry) {
        if (status.polarity == "negative") {
            return TargetProgress("$label · avoided", ProgressTone.MET, null)
        }
        return TargetProgress(label, ProgressTone.NEUTRAL, if (isAtLeast) 0.0 else null)
    }

    val number = coerceNumericValue(status.value)
    val shown = displayEntryValue(status.value)

    if (isAtLeast) {
        val floorValue = minimum
        val fillPct = if (number != null && floorValue > 0) {
            max(0.0, min(1.0, number / floorValue)) * 100
        } else {
            0.0
        }
        return TargetProgress("$shown / $label", boundedTone(status.state), fillPct)
    }
    if (isAtMost) {
        val ceiling = maximum
        if (number == null) return TargetProgress("$shown of $label", ProgressTone.NEUTRAL, null)
        val over = number > ceiling
        val suffix = if (over) {
            " · over by ${formatJournalNumber(number - ceiling)}"
        } else {
            " · ${formatJournalNumber(ceiling - number)} left"
        }
        return TargetProgress(
            text = "$shown of $label$suffix",
            tone = if (over) ProgressTone.OVER else ProgressTone.MET,
            fillPct = null,
        )
    }
    // Range: above the ceiling reads 'over' rather than the generic neutral.
    val tone = when {
        status.state != TargetState.MISSED -> boundedTone(status.state)
        number != null && maximum != null && number > maximum -> ProgressTone.OVER
        else -> ProgressTone.NEUTRAL
    }
    return TargetProgress("$shown in $label", tone, null)
}

private fun boundedTone(state: TargetState): ProgressTone = when (state) {
    TargetState.MET -> ProgressTone.MET
    TargetState.PARTIAL -> ProgressTone.PARTIAL
    TargetState.MISSED -> ProgressTone.NEUTRAL
}

/** Two targets are equal when both bounds are. Null and an empty target differ. */
fun targetsEqual(a: TargetDto?, b: TargetDto?): Boolean {
    if (a == null && b == null) return true
    if (a == null || b == null) return false
    return a.min == b.min && a.max == b.max
}

/**
 * An entry's value as the row shows it: the raw stored literal, or an em dash
 * when there is nothing to show. Absent and explicitly-null both read as "—".
 */
fun displayEntryValue(value: JsonElement?): String = when {
    value == null || value is JsonNull -> "—"
    value is JsonPrimitive -> value.content
    else -> value.toString()
}

/**
 * A number the way JavaScript prints it: `150`, not `150.0`. Every target and
 * entry value in this app came from — and goes back to — a `Number`, so a
 * Kotlin-flavoured `.0` would both read wrong and rewrite stored JSON.
 *
 * Past 1e15 a `Double` can no longer name every integer, so anything that large
 * keeps its decimal form rather than gaining false precision.
 */
fun formatJournalNumber(value: Double): String =
    if (value.isFinite() && value == floor(value) && abs(value) < 1e15) {
        value.toLong().toString()
    } else {
        value.toString()
    }

/** The JSON literal for a numeric entry value, integer-collapsed like the PWA's. */
fun journalNumberJson(value: Double): JsonPrimitive =
    if (value.isFinite() && value == floor(value) && abs(value) < 1e15) {
        JsonPrimitive(value.toLong())
    } else {
        JsonPrimitive(value)
    }
