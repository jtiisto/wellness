package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.data.coach.LastPerformance
import dev.jtiisto.wellness.core.data.coach.SetColumn
import dev.jtiisto.wellness.core.data.journal.journalNumberJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * The set grid's data rules — a port of `SetEntry.js`'s write path plus the
 * ghost placeholders that sit behind it.
 *
 * The grid always shows exactly `target_sets` rows. There is no add or remove:
 * the plan says how many sets there are, and a row the user never touches logs
 * nothing at all.
 */

/** One row of the grid. [index] is zero-based; the visible number is `index + 1`. */
data class SetRowState(
    val index: Int,
    val cells: List<SetCellState>,
    val completed: Boolean,
)

/**
 * One cell. [ghost] is last time's value shown as a placeholder — never as the
 * value, so an untouched cell still writes nothing.
 */
data class SetCellState(
    val key: String,
    val text: String,
    val ghost: String?,
) {
    /**
     * Whether last time's value is the one on screen right now.
     *
     * In Logbook a ghost is a colour state rather than a layout one — the cell
     * sets in ink-faint until it holds something of its own — and the table's
     * provenance footer changes wording on whether any cell is still in that
     * state. Both read this rather than re-testing the pair.
     */
    val showsGhost: Boolean get() = text.isEmpty() && ghost != null
}

/**
 * Rows for one exercise: always [targetSets] of them, padded with blanks beyond
 * whatever has been logged, each carrying the matching set's ghosts.
 */
fun buildSetRows(
    columns: List<SetColumn>,
    sets: JsonArray,
    targetSets: Int,
    lastPerformance: LastPerformance?,
): List<SetRowState> = (0 until targetSets).map { index ->
    val logged = sets.getOrNull(index) as? JsonObject
    val ghost = ghostSetFor(lastPerformance, index + 1)
    SetRowState(
        index = index,
        cells = columns.map { column ->
            SetCellState(
                key = column.key,
                text = logged?.get(column.key).asFieldText(),
                ghost = (ghost?.get(column.key))?.takeIf { it !is JsonNull }?.jsonText(),
            )
        },
        completed = (logged?.get("completed") as? JsonPrimitive)
            ?.let { !it.isString && it.content == "true" } == true,
    )
}

/**
 * Last time's set with this number, or null.
 *
 * Matched by the logged `set_num` rather than by position: a past session whose
 * first set was left blank has its data filtered out, so its remaining sets keep
 * the numbers they were performed under.
 */
fun ghostSetFor(lastPerformance: LastPerformance?, setNumber: Int): JsonObject? =
    lastPerformance?.sets?.firstOrNull { set ->
        (set["set_num"] as? JsonPrimitive)?.content?.toIntOrNull() == setNumber
    }

/**
 * The whole `sets` array after one cell edit.
 *
 * Editing set 3 of an empty grid has to materialize sets 1 and 2 as well —
 * arrays have no holes — so the gap is filled with `{set_num}` placeholders.
 * They carry no metrics, which is exactly why `setHasData` ignores them and a
 * later reader does not mistake them for performed work.
 *
 * The array is rewritten whole because the day's log is one JSON document: there
 * is no per-set patch to send.
 */
fun withSetCell(sets: JsonArray, index: Int, field: String, value: JsonElement): JsonArray {
    val rows = sets.map { it as? JsonObject ?: JsonObject(emptyMap()) }.toMutableList()
    while (rows.size <= index) {
        rows += buildJsonObject { put("set_num", rows.size + 1) }
    }
    rows[index] = JsonObject(rows[index] + (field to value))
    return JsonArray(rows)
}

/**
 * What a numeric field commit should write, or null for "write nothing".
 *
 * Journal's field rule (deviation 1), which differs from the PWA's in one
 * deliberate way: the PWA's input fires on blur unconditionally, so tabbing
 * through an empty cell of set 3 materializes three set rows and quietly makes
 * the exercise look complete. Writing only on an actual change avoids that
 * without changing what a real edit does.
 */
fun numericCellValue(current: JsonElement?, input: String): JsonElement? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        // Clearing a cell stores an explicit null, which is how the PWA records
        // "this was emptied" — except when there was nothing there to clear.
        return if (current == null || current is JsonNull) null else JsonNull
    }
    val parsed = trimmed.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    if (parsed == (current as? JsonPrimitive)?.doubleOrNull) return null
    return journalNumberJson(parsed)
}

/** A stored value as field text. Absent and null both show as empty. */
fun JsonElement?.asFieldText(): String = when {
    this == null || this is JsonNull -> ""
    else -> jsonText()
}

/**
 * A JSON scalar as the text a field shows.
 *
 * `content` rather than `toString()`: the former gives `24` and `Zone 2`, the
 * latter gives `24` and `"Zone 2"`.
 */
private fun JsonElement.jsonText(): String = (this as? JsonPrimitive)?.content ?: toString()
