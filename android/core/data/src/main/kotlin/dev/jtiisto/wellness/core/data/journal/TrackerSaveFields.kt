package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.network.DateString

/**
 * The config form's chosen weekdays, polarity and target, mapped to the tracker
 * fields to persist — a port of `buildTrackerSaveFields`.
 *
 * The result is a **patch**: a field is present only when it must actually be
 * written, so an unedited save marks nothing dirty. Two of the three fields are
 * genuinely tri-state, because "leave whatever is there" and "clear it back to
 * unspecified" are different writes and a plain nullable cannot tell them
 * apart. [scheduleHistory] needs only two states — a schedule is never written
 * as null — so it stays a nullable list.
 */

/** A target write: leave the history alone, or set it (null = clear the goal). */
sealed interface TargetField {
    data object Unchanged : TargetField

    data class Set(val value: TargetDto?) : TargetField
}

/** A polarity write: leave it alone, or set it (null = clear to unspecified). */
sealed interface PolarityField {
    data object Unchanged : PolarityField

    data class Set(val value: String?) : PolarityField
}

/**
 * What the form chose.
 *
 * [days] is ignored when [paused] — the empty schedule is what pauses a
 * tracker. [polarity] is the raw select value, `""` for unspecified.
 * [target] is [TargetField.Unchanged] for non-quantifiable types, where the
 * form shows no target input at all.
 */
data class FormSelections(
    val days: List<Int>,
    val polarity: String,
    val target: TargetField = TargetField.Unchanged,
    val paused: Boolean = false,
)

/** The patch to merge into the tracker. Every field defaults to "do not write". */
data class TrackerSaveFields(
    val scheduleHistory: List<ScheduleSegmentDto>? = null,
    val polarity: PolarityField = PolarityField.Unchanged,
    val targetHistory: List<TargetSegmentDto>? = null,
)

/**
 * Map the form's selections to the fields to persist.
 *
 * - **Schedule.** A new tracker left at Daily writes nothing (the common case
 *   stays clean); a narrower new tracker gets a single genesis segment, since a
 *   tracker created today has no past to preserve. Editing delegates to
 *   [computeScheduleHistoryUpdate], so an unchanged day-set writes nothing.
 * - **Empty selection coerces to Daily** — a guard against saving a tracker
 *   onto no days at all. [FormSelections.paused] is the deliberate opt-out and
 *   the only thing that bypasses it, saving an empty-days schedule.
 * - **Polarity.** A valid value is written; choosing "unspecified" on a tracker
 *   that had one clears it explicitly.
 * - **Target.** Quantifiable only. A new tracker with a target gets a genesis
 *   segment; editing delegates to [computeTargetHistoryUpdate], and clearing
 *   writes a `target: null` segment rather than deleting the history.
 */
fun buildTrackerSaveFields(
    existingTracker: TrackerDto?,
    form: FormSelections,
    today: DateString,
): TrackerSaveFields {
    val chosen = if (form.paused) {
        emptyList()
    } else {
        normalizeDays(form.days.ifEmpty { ALL_DAYS })
    }

    val scheduleHistory = if (existingTracker == null) {
        // Both branches reduce a brand-new empty schedule to one genesis `[]`
        // segment, since `[] != ALL_DAYS`.
        if (chosen != ALL_DAYS) listOf(ScheduleSegmentDto(SCHEDULE_GENESIS_DATE, chosen)) else null
    } else {
        computeScheduleHistoryUpdate(existingTracker, chosen, today)
            .takeIf { it.changed }
            ?.history
    }

    val polarity = when {
        form.polarity in POLARITY_VALUES -> PolarityField.Set(form.polarity)
        existingTracker?.polarity != null -> PolarityField.Set(null)
        else -> PolarityField.Unchanged
    }

    val targetHistory = when (val target = form.target) {
        TargetField.Unchanged -> null
        is TargetField.Set ->
            if (existingTracker == null) {
                target.value?.let { listOf(TargetSegmentDto(SCHEDULE_GENESIS_DATE, it)) }
            } else {
                computeTargetHistoryUpdate(existingTracker, target.value, today)
                    .takeIf { it.changed }
                    ?.history
            }
    }

    return TrackerSaveFields(scheduleHistory, polarity, targetHistory)
}

/** Merge a [TrackerSaveFields] patch into a tracker, leaving unwritten fields as they are. */
fun TrackerDto.withSaveFields(fields: TrackerSaveFields): TrackerDto = copy(
    scheduleHistory = fields.scheduleHistory ?: scheduleHistory,
    polarity = when (val next = fields.polarity) {
        PolarityField.Unchanged -> polarity
        is PolarityField.Set -> next.value
    },
    targetHistory = fields.targetHistory ?: targetHistory,
)
