package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.data.journal.journalNumberJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * The ad-hoc Zone 2 card's draft rules — a port of `ExtraSessionCard.js`.
 *
 * Draft-then-adopt: before the first Save the fields exist only on screen.
 * Nothing is in the log store, nothing is dirty, nothing syncs. That matters
 * because a duration-less cardio entry carries no uploadable content, so its
 * dirty date would be dropped as unsatisfiable — hence Save requiring a
 * duration, and hence the draft not existing in the store until it has one.
 */

/** The three fields, as typed. Strings because the widgets are text fields. */
data class ExtraSessionDraft(
    val durationMin: String = "",
    val avgHr: String = "",
    val maxHr: String = "",
)

/** A field's parsed value, or null when it is blank or unusable. */
private fun parseField(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

/** Save stays disabled until there is a duration — see the file note. */
fun draftCanSave(draft: ExtraSessionDraft): Boolean = parseField(draft.durationMin) != null

/**
 * The single write Save makes: the fields that have a value, and only those.
 *
 * One `updateLog` call rather than three, so the entry appears complete in one
 * emission and one upload rather than growing a field at a time.
 */
fun draftPayload(draft: ExtraSessionDraft): JsonObject = buildJsonObject {
    parseField(draft.durationMin)?.let { put("duration_min", journalNumberJson(it)) }
    parseField(draft.avgHr)?.let { put("avg_hr", journalNumberJson(it)) }
    parseField(draft.maxHr)?.let { put("max_hr", journalNumberJson(it)) }
}
