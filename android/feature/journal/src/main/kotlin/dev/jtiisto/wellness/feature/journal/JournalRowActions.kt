package dev.jtiisto.wellness.feature.journal

import dev.jtiisto.wellness.core.data.journal.EntryDto
import dev.jtiisto.wellness.core.data.journal.EntryField
import dev.jtiisto.wellness.core.data.journal.EntryPatch
import dev.jtiisto.wellness.core.data.journal.TrackerDto
import dev.jtiisto.wellness.core.data.journal.TrackerType
import dev.jtiisto.wellness.core.data.journal.defaultValueOrNull
import dev.jtiisto.wellness.core.data.journal.isValueAbsent
import dev.jtiisto.wellness.core.data.journal.journalNumberJson
import dev.jtiisto.wellness.core.data.journal.trackerType

/**
 * What each widget writes. Pure, so the awkward cases — the checkbox that also
 * seeds a default, the blur that echoes back the same number — are decided
 * where they can be tested rather than inside a callback.
 *
 * A null result means "write nothing", which is a real outcome for three of
 * these and not an error in any of them.
 */

/**
 * The checkbox.
 *
 * Ticking a quantifiable or evaluation tracker that has **no value yet** also
 * seeds its default, as one write: the row would otherwise read as logged while
 * showing a value nothing had stored. "No value yet" means genuinely absent —
 * an explicitly stored null is a value the user (or the server) put there, and
 * overwriting it would undo that.
 */
fun checkboxPatch(tracker: TrackerDto, entry: EntryDto?, checked: Boolean): EntryPatch {
    if (!checked || !entry.isValueAbsent()) return EntryPatch.completed(checked)
    val default = when (tracker.trackerType()) {
        TrackerType.QUANTIFIABLE -> tracker.defaultValueOrNull()
        TrackerType.EVALUATION -> tracker.defaultValueOrNull() ?: EVALUATION_DEFAULT
        else -> null
    } ?: return EntryPatch.completed(checked)
    return EntryPatch(
        value = EntryField.Set(journalNumberJson(default)),
        completed = EntryField.Set(true),
    )
}

/**
 * A numeric field commit (focus loss or IME Done). Null = leave the entry
 * alone and restore the displayed text.
 *
 * Three cases collapse to "no write": input that will not parse, input that
 * parses to the number already displayed, and an emptied field on a row that
 * had nothing to clear. The equality check is against the **displayed** value
 * rather than the stored one, so simply tabbing through a field with a default
 * cannot create an entry that flips the day's judgment.
 */
fun numericCommitPatch(displayedNumber: Double?, input: String): EntryPatch? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        // Emptying the field clears the value back to absent, which is exactly
        // the state it had before anything was ever logged.
        return if (displayedNumber == null) null else EntryPatch(value = EntryField.Set(null))
    }
    val parsed = trimmed.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    if (parsed == displayedNumber) return null
    return EntryPatch.numeric(parsed)
}

/**
 * The accumulator's "+" sheet. Adds to whatever is displayed (treating a
 * missing or non-numeric value as zero), allows negatives so a mis-entry can be
 * taken back, and writes nothing for zero or unparseable input — the sheet just
 * closes, as the PWA's does.
 */
fun accumulatorPatch(displayedNumber: Double?, input: String): EntryPatch? {
    val increment = input.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    if (increment == 0.0) return null
    return EntryPatch.numeric((displayedNumber ?: 0.0) + increment)
}

/** The evaluation slider. Writes the value only — never the checkbox. */
fun sliderPatch(value: Float): EntryPatch = EntryPatch.numeric(value.toDouble())

/** Whether a write to this widget should stamp "last updated". */
fun stampsLastUpdated(type: TrackerType): Boolean = type == TrackerType.QUANTIFIABLE
