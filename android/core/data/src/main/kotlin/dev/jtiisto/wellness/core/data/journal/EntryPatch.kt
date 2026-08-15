package dev.jtiisto.wellness.core.data.journal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * A presence-aware edit to one journal entry — the argument to
 * [JournalSyncStore.mergeEntry].
 *
 * The PWA merges `{...existingEntry, ...data}`, so a key the widget omitted
 * keeps its stored value while a key set to null genuinely clears it. A plain
 * nullable field cannot express both, and the difference is load-bearing here:
 * the checkbox writes a default value only when the entry's value is *absent*,
 * which an explicit null is not.
 */
data class EntryPatch(
    val value: EntryField<JsonElement?> = EntryField.Unchanged,
    val completed: EntryField<Boolean?> = EntryField.Unchanged,
) {
    companion object {
        /** A numeric value write. Never touches the checkbox. */
        fun numeric(value: Double): EntryPatch =
            EntryPatch(value = EntryField.Set(journalNumberJson(value)))

        /** The note widget's atomic write: text plus the checkbox it implies. */
        fun note(text: String): EntryPatch = EntryPatch(
            value = EntryField.Set(JsonPrimitive(text)),
            completed = EntryField.Set(text.isNotBlank()),
        )

        /** Tick or untick without touching the value. */
        fun completed(completed: Boolean): EntryPatch =
            EntryPatch(completed = EntryField.Set(completed))
    }
}

/** Either "leave this field as stored" or "write exactly this". */
sealed interface EntryField<out T> {
    data object Unchanged : EntryField<Nothing>

    /**
     * For the value field the payload's own nullability is meaningful too:
     * `Set(null)` clears the column back to *absent*, while `Set(JsonNull)`
     * stores an explicit JSON null.
     */
    data class Set<out T>(val value: T) : EntryField<T>
}

/** Whether the entry carries a usable value — absent and explicit null do not. */
fun EntryDto?.hasValue(): Boolean {
    val stored = this?.value ?: return false
    return stored !is JsonNull
}

/**
 * Whether the entry has **no value field at all** — no row, or a `valueJson` of
 * SQL NULL. An explicitly stored null is *not* absent, and the distinction is
 * the checkbox's: it writes a default value only into a genuinely empty entry.
 */
fun EntryDto?.isValueAbsent(): Boolean = this?.value == null
