package dev.jtiisto.wellness.feature.journal

import dev.jtiisto.wellness.core.data.journal.ALL_DAYS
import dev.jtiisto.wellness.core.data.journal.FormSelections
import dev.jtiisto.wellness.core.data.journal.POLARITY_VALUES
import dev.jtiisto.wellness.core.data.journal.ParsedTarget
import dev.jtiisto.wellness.core.data.journal.TargetField
import dev.jtiisto.wellness.core.data.journal.TrackerDto
import dev.jtiisto.wellness.core.data.journal.TrackerSaveFields
import dev.jtiisto.wellness.core.data.journal.TrackerType
import dev.jtiisto.wellness.core.data.journal.buildTrackerSaveFields
import dev.jtiisto.wellness.core.data.journal.defaultValueOrNull
import dev.jtiisto.wellness.core.data.journal.formatJournalNumber
import dev.jtiisto.wellness.core.data.journal.formatTargetInput
import dev.jtiisto.wellness.core.data.journal.getScheduleDaysForDate
import dev.jtiisto.wellness.core.data.journal.isAccumulator
import dev.jtiisto.wellness.core.data.journal.journalNumberJson
import dev.jtiisto.wellness.core.data.journal.lastActiveScheduleDays
import dev.jtiisto.wellness.core.data.journal.parseTarget
import dev.jtiisto.wellness.core.data.journal.targetForDate
import dev.jtiisto.wellness.core.data.journal.trackerType
import dev.jtiisto.wellness.core.data.journal.unitOrNull
import dev.jtiisto.wellness.core.data.journal.withSaveFields
import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The tracker config form as data: seeding, live validation, and the submission
 * it assembles. No Compose, no ViewModel — the form's rules are the fiddly part
 * and they are all here.
 */
data class TrackerFormState(
    val name: String = "",
    val category: String = "",
    val newCategory: String = "",
    val useNewCategory: Boolean = false,
    val type: TrackerType = TrackerType.SIMPLE,
    val unit: String = "",
    val defaultValue: String = "",
    val accumulator: Boolean = false,
    val paused: Boolean = false,
    val days: List<Int> = ALL_DAYS,
    val polarity: String = "",
    val targetInput: String = "",
    /** Set on the first submit attempt: errors stay quiet until then. */
    val submitAttempted: Boolean = false,
) {
    /** The chosen category, whichever input it came from. */
    val resolvedCategory: String
        get() = if (useNewCategory) newCategory.trim() else category

    /** The live reading of the target text under the current polarity. */
    val targetParse: ParsedTarget
        get() = parseTarget(targetInput, polarity)

    /** Shown inline while typing — an empty field is not an error, just empty. */
    val targetError: String? get() = targetParse.error?.takeIf { targetInput.isNotBlank() }

    /** The default value as a usable number, or null when the field is unusable or blank. */
    val defaultValueNumber: Double?
        get() = defaultValue.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    /**
     * Blank is legitimate (no default). Anything else that will not parse to a
     * finite number is not: silently dropping it would save a tracker whose
     * default the user believes they set.
     */
    val defaultValueError: String?
        get() = if (defaultValue.isNotBlank() && defaultValueNumber == null) "Enter a number (e.g. 30)" else null

    fun toggleDay(day: Int): TrackerFormState =
        copy(days = if (day in days) days - day else (days + day).sorted())
}

/** Which fields are wrong. Null means fine. */
data class TrackerFormErrors(
    val name: String? = null,
    val category: String? = null,
    val target: String? = null,
    val defaultValue: String? = null,
) {
    val isValid: Boolean
        get() = name == null && category == null && target == null && defaultValue == null
}

/**
 * Everything a submit writes: the plain fields, the quantifiable extras when
 * the type has them, and the effective-dated patch from
 * [buildTrackerSaveFields].
 */
data class TrackerSubmission(
    val id: String,
    val name: String,
    val category: String,
    val type: TrackerType,
    val quantifiable: QuantifiableFields?,
    val saveFields: TrackerSaveFields,
)

/** The `meta_json` fields only a quantifiable tracker carries. */
data class QuantifiableFields(
    val unit: String,
    val defaultValue: Double?,
    val accumulator: Boolean,
)

/**
 * Seed the form from the tracker being edited, or from nothing for a new one.
 *
 * The weekday picker is the subtle part. A paused tracker's current schedule is
 * empty, so seeding the picker from it would show no days at all — and
 * unpausing without touching the picker would then coerce back to Daily and
 * lose the pre-pause selection. It is seeded from [lastActiveScheduleDays]
 * instead, and shown dimmed, so the user can see what resuming restores.
 */
fun seedTrackerForm(
    tracker: TrackerDto?,
    existingCategories: List<String>,
    today: DateString,
): TrackerFormState {
    if (tracker == null) {
        return TrackerFormState(
            days = ALL_DAYS,
            useNewCategory = existingCategories.isEmpty(),
        )
    }
    val scheduled = getScheduleDaysForDate(tracker, today)
    val paused = scheduled.isEmpty()
    return TrackerFormState(
        name = tracker.name.orEmpty(),
        category = tracker.category.orEmpty(),
        useNewCategory = existingCategories.isEmpty(),
        type = tracker.trackerType(),
        unit = tracker.unitOrNull().orEmpty(),
        defaultValue = tracker.defaultValueOrNull()?.let(::formatJournalNumber).orEmpty(),
        accumulator = tracker.isAccumulator(),
        paused = paused,
        days = if (paused) lastActiveScheduleDays(tracker) else scheduled.sorted(),
        polarity = tracker.polarity?.takeIf { it in POLARITY_VALUES }.orEmpty(),
        targetInput = formatTargetInput(targetForDate(tracker, today)),
    )
}

fun validateTrackerForm(state: TrackerFormState): TrackerFormErrors {
    // Only quantifiable trackers render the unit, default and target fields, so
    // only they can be blocked by one.
    val quantifiable = state.type == TrackerType.QUANTIFIABLE
    return TrackerFormErrors(
        name = if (state.name.isBlank()) "Name is required" else null,
        category = if (state.resolvedCategory.isBlank()) "Category is required" else null,
        target = if (quantifiable) state.targetError else null,
        defaultValue = if (quantifiable) state.defaultValueError else null,
    )
}

/**
 * Assemble the submission, or null when the form does not validate.
 *
 * The type-change guard is the case worth spelling out: leaving the
 * quantifiable type while a target is in effect clears the target explicitly.
 * Only quantifiable trackers render the target field, so a target left behind
 * would stay in force forever — judging every checkbox-only day missed — with
 * no way to reach it.
 */
fun buildTrackerSubmission(
    existing: TrackerDto?,
    state: TrackerFormState,
    today: DateString,
    newId: () -> String,
): TrackerSubmission? {
    if (!validateTrackerForm(state).isValid) return null

    val quantifiable = state.type == TrackerType.QUANTIFIABLE
    val target = when {
        quantifiable -> TargetField.Set(state.targetParse.target)
        existing != null && targetForDate(existing, today) != null -> TargetField.Set(null)
        else -> TargetField.Unchanged
    }

    return TrackerSubmission(
        id = existing?.id ?: newId(),
        name = state.name.trim(),
        category = state.resolvedCategory,
        type = state.type,
        quantifiable = if (quantifiable) {
            QuantifiableFields(
                unit = state.unit,
                defaultValue = state.defaultValueNumber,
                accumulator = state.accumulator,
            )
        } else {
            null
        },
        saveFields = buildTrackerSaveFields(
            existingTracker = existing,
            form = FormSelections(
                days = state.days,
                polarity = state.polarity,
                target = target,
                paused = state.paused,
            ),
            today = today,
        ),
    )
}

/**
 * Merge the submission into the stored tracker. Anything the form does not own
 * — unread `meta_json` keys, the sync token — is left exactly as it was.
 */
fun TrackerSubmission.applyTo(existing: TrackerDto): TrackerDto = existing
    .copy(
        name = name,
        category = category,
        type = type.wire,
        extras = quantifiable?.let { existing.extras.withQuantifiable(it) } ?: existing.extras,
    )
    .withSaveFields(saveFields)

fun TrackerSubmission.toNewTracker(): TrackerDto = TrackerDto(
    id = id,
    name = name,
    category = category,
    type = type.wire,
    extras = quantifiable?.let { JsonObject(emptyMap()).withQuantifiable(it) } ?: JsonObject(emptyMap()),
).withSaveFields(saveFields)

/**
 * A blank default-value field is written as an explicit null rather than
 * dropped, matching what the PWA's form stores — the accessors read both as
 * "no default", and keeping the shape identical avoids a pointless rewrite of
 * every tracker the two clients take turns editing.
 */
private fun JsonObject.withQuantifiable(fields: QuantifiableFields): JsonObject = JsonObject(
    this + mapOf(
        "unit" to JsonPrimitive(fields.unit),
        "defaultValue" to (fields.defaultValue?.let(::journalNumberJson) ?: JsonNull),
        "accumulator" to JsonPrimitive(fields.accumulator),
    ),
)
