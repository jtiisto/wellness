package dev.jtiisto.wellness.core.data.hr

import dev.jtiisto.wellness.core.data.db.GuideEventDao
import dev.jtiisto.wellness.core.data.db.GuideEventEntity
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import java.util.UUID

/**
 * Appends the cardio guide's two user actions to the heart-rate record.
 *
 * The counterpart of
 * [SetEventRecorder][dev.jtiisto.wellness.core.data.coach.SetEventRecorder], and
 * the same idea: the plan says what was asked for, the log says what was done,
 * and this says *when* the rider anchored or lengthened the timeline they rode
 * to. The plan and the day log are untouched by both calls here — raising a
 * plan's target mid-ride would un-complete an exercise the rider had already
 * satisfied, which is the recorded trap that made the extension UI-only.
 *
 * **No capture, no record**, and that precondition is a *type* here rather than a
 * check: [sessionId] is non-null, so a caller with no running capture cannot
 * reach this at all. The rule is enforced exactly once, at the tap, where the
 * live session is read.
 *
 * The reason is the system's two-case model, not squeamishness about nulls.
 * Either wellness owns a ride end to end — capture and guide together, keyed by
 * one session — or the ride lives on the Garmin side and wellness records only
 * the completion checkbox, as it does for strength work. A strapless guided ride
 * is a watch ride, so a row written for one would be dead weight: the analysis
 * that reads this table finds rows by session id, and a row without one is
 * unreachable by construction.
 *
 * Unlike its sibling this one writes rather than mints: a set tick has a coach
 * blob write to land with atomically, while a guide action has nothing to
 * compose with, so the insert stands alone and the upload debounce is armed
 * after it.
 *
 * @param dao the append-only log.
 * @param session the server-switch gate every server-scoped write holds. A tap
 *   that races a switch must not recreate an old session's row AFTER the wipe —
 *   the row would name a session only the old server knows and upload to the
 *   new one (the deep-review find). Held for the insert only; the debounce is
 *   armed outside it, like the sibling store's own writes.
 * @param newEventId the idempotency key the server dedupes on — a fresh UUID per
 *   action, never reused.
 * @param scheduleUpload the debounce hook, armed after the row is stored.
 */
class GuideEventRecorder(
    private val dao: GuideEventDao,
    private val session: ServerSessionGate,
    private val newEventId: () -> String = { UUID.randomUUID().toString() },
    private val scheduleUpload: () -> Unit = {},
) {

    /**
     * The timeline was anchored at [clientTimestampMs] — **the same instant the
     * run itself is anchored at**, read once by the caller and handed to both,
     * because a record of when the clock started that disagrees with the clock
     * would be worse than no record.
     *
     * [timelineJson] is the segments as the guide will draw them, serialized by
     * the caller in the coach wire's segment shape. It is copied rather than
     * referenced so that a plan edited after the ride cannot change what the
     * ride was guided against.
     */
    suspend fun recordStart(
        date: DateString,
        exerciseKey: String,
        sessionId: String,
        clientTimestampMs: Long,
        timelineJson: String,
    ) = record(
        date = date,
        exerciseKey = exerciseKey,
        sessionId = sessionId,
        action = GuideEventEntity.ACTION_START,
        clientTimestampMs = clientTimestampMs,
        timelineJson = timelineJson,
    )

    /**
     * [extensionSec] more seconds were appended at [clientTimestampMs].
     *
     * One row per tap, carrying the step rather than the running total: the taps
     * are what happened, and a consumer that wants the cumulative figure sums
     * the rows it can see the timestamps of.
     */
    suspend fun recordExtend(
        date: DateString,
        exerciseKey: String,
        sessionId: String,
        clientTimestampMs: Long,
        extensionSec: Int,
    ) = record(
        date = date,
        exerciseKey = exerciseKey,
        sessionId = sessionId,
        action = GuideEventEntity.ACTION_EXTEND,
        clientTimestampMs = clientTimestampMs,
        extensionSec = extensionSec,
    )

    private suspend fun record(
        date: DateString,
        exerciseKey: String,
        sessionId: String,
        action: String,
        clientTimestampMs: Long,
        extensionSec: Int? = null,
        timelineJson: String? = null,
    ) {
        session.withWriteLease {
            dao.insert(
                GuideEventEntity(
                    eventId = newEventId(),
                    date = date,
                    exerciseKey = exerciseKey,
                    action = action,
                    clientTimestampMs = clientTimestampMs,
                    sessionId = sessionId,
                    extensionSec = extensionSec,
                    timelineJson = timelineJson,
                ),
            )
        }
        scheduleUpload()
    }
}
