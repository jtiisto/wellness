package dev.jtiisto.wellness.core.data.coach

import dev.jtiisto.wellness.core.data.db.SetEventEntity
import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * A completion toggle, as the screen made it.
 *
 * This is the *intent*; whether it changes anything is decided against the
 * entry as stored, inside the write transaction — see [SetEventRecorder].
 *
 * There is deliberately no exercise-level variant. `specs/coach-heart-rate.md`
 * lists cardio completion as a covered toggle, but this app has no such widget:
 * cardio reads as done when `duration_min` is present (`isExerciseCompleted`),
 * and a duration commit is a value edit, which must never emit an event. The
 * wire shape carries that case already — an event with neither `setNum` nor
 * `itemKey` — so a future cardio tick needs a variant here and nothing else.
 */
sealed interface CompletionToggle {

    /** A per-set tick. [setNum] is one-based, as it is in the log and on the wire. */
    data class SetTick(val setNum: Int, val completed: Boolean) : CompletionToggle

    /**
     * A checklist item, identified by its own string.
     *
     * No target state: the widget flips whatever is stored, so the action is
     * derived rather than declared. Two identically-named items are one item
     * here, exactly as they are to the screen.
     */
    data class ChecklistItem(val itemKey: String) : CompletionToggle
}

/**
 * Mints the set-completion events the coach write path appends beside its blob.
 *
 * The events are the correlation half of the heart-rate feature: the coach blob
 * says a set is ticked, this log says *when* it was ticked, and a capture
 * session's samples are matched against that. The blob remains display truth and
 * nothing here feeds it back.
 *
 * Append-only, so an undo is an `uncheck` row rather than a deletion, and only a
 * genuine state change is recorded at all — re-ticking an already-ticked set
 * still rewrites the blob (unchanged behaviour) but says nothing new about when
 * the set was performed.
 *
 * @param captureSessionId the Phase 2 seam. Phase 1 has no BLE capture, so this
 *   returns null and every event is written without a session; pointing it at
 *   the live session is the whole of what Phase 2 changes here.
 * @param newEventId the idempotency key the server dedupes on — a fresh UUID
 *   per toggle, never reused.
 * @param now epoch milliseconds at the moment of the toggle. A **data value**,
 *   not a sync watermark: this protocol has no server-issued stamps, so the
 *   opaque-timestamp rule does not reach it.
 * @param scheduleUpload the debounce hook, armed after the transaction commits.
 */
class SetEventRecorder(
    private val captureSessionId: () -> String? = { null },
    private val newEventId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    private val scheduleUpload: () -> Unit = {},
) {

    /**
     * The event [toggle] earns against [entry] **as stored**, or null when the
     * toggle asks for the state the entry already holds.
     *
     * [entry] must be the row read inside the write transaction. Comparing
     * against a snapshot read outside it loses the race that matters: two taps
     * on one checkbox would both see "unticked" and both claim a check.
     */
    fun eventFor(
        date: DateString,
        exerciseKey: String,
        toggle: CompletionToggle,
        entry: JsonObject?,
    ): SetEventEntity? {
        val action = actionFor(toggle, entry) ?: return null
        return SetEventEntity(
            eventId = newEventId(),
            date = date,
            exerciseKey = exerciseKey,
            setNum = (toggle as? CompletionToggle.SetTick)?.setNum,
            itemKey = (toggle as? CompletionToggle.ChecklistItem)?.itemKey,
            action = action,
            clientTimestampMs = now(),
            sessionId = captureSessionId(),
        )
    }

    /** Arm the upload debounce. Called after the event's transaction has committed. */
    fun scheduleFlush() {
        scheduleUpload()
    }
}

/**
 * `check`, `uncheck`, or null for "this changes nothing".
 *
 * The set comparison is the grid's own truthiness rule ([isJsonTrue]) so that
 * "already ticked" means here exactly what it means on screen. A checklist flip
 * always changes something by construction, so it always earns a row.
 */
internal fun actionFor(toggle: CompletionToggle, entry: JsonObject?): String? = when (toggle) {
    is CompletionToggle.SetTick -> {
        val stored = (entry?.array("sets")?.getOrNull(toggle.setNum - 1) as? JsonObject)?.get("completed")
        when {
            stored.isJsonTrue() == toggle.completed -> null
            toggle.completed -> SetEventEntity.ACTION_CHECK
            else -> SetEventEntity.ACTION_UNCHECK
        }
    }

    is CompletionToggle.ChecklistItem -> {
        val done = entry?.array("completed_items").orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content }
        if (toggle.itemKey in done) SetEventEntity.ACTION_UNCHECK else SetEventEntity.ACTION_CHECK
    }
}
