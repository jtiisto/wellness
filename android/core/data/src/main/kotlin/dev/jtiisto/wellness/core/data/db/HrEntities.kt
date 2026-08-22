package dev.jtiisto.wellness.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.jtiisto.wellness.core.data.network.DateString

/**
 * One heart-rate capture session.
 *
 * The client is the only writer of a session — the strap feeds exactly one
 * device — so the server upsert is a full-row last-write-wins replace and
 * [isSynced] is the whole of the sync state. It is cleared whenever the row's
 * content changes (started → workout-anchored → ended), which is what makes the
 * three uploads happen and nothing more.
 *
 * [isQuarantined] is set by the 422 bisect, and it is cleared on a content
 * change for the same reason [isSynced] is: the row the server rejected is not
 * the row that replaced it, so a corrected session has to get another attempt.
 * That is the difference from samples and events, which are immutable once
 * written and therefore stay quarantined forever.
 *
 * Being rewritable is also why this row alone carries [dirtyGeneration], the
 * journal and coach guard. A session can be upserted while its own POST is in
 * flight, and without the guard the response would stamp the *new* row synced
 * against a server that only ever saw the old one — leaving the final state of
 * the session, the one naming the workout, permanently unuploaded.
 *
 * [endedAtMs] null is "still open", and it is load-bearing beyond display: the
 * capture service resumes the newest open session after a `START_STICKY`
 * restart, and a failed final flush deliberately leaves the session open rather
 * than finalizing it against a batch that never landed.
 *
 * Every millisecond value here is a **data value**, not a sync watermark — the
 * opaque-timestamp rule covers server-issued stamps, and this protocol has none.
 */
@Entity(tableName = "hr_sessions")
data class HrSessionEntity(
    @PrimaryKey val sessionId: String,
    val deviceId: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val workoutDate: DateString? = null,
    val workoutSessionId: Long? = null,
    /** Derived by [HrSessionDao.upsert] from what actually changed; never set by callers. */
    val isSynced: Boolean = false,
    /** Set by the 422 bisect, and cleared by [HrSessionDao.upsert] on a content change. */
    val isQuarantined: Boolean = false,
    /**
     * Bumped by [HrSessionDao.upsert] on every content change, and the guard an
     * upload's verdict has to match to land. Derived like the two flags — a
     * caller's value is ignored.
     */
    val dirtyGeneration: Long = 0,
)

/**
 * One RR interval, as the strap reported it.
 *
 * The primary key is `(deviceId, timestampMs, seq)` and [seq] exists only to
 * disambiguate beats that anchor to the same millisecond. Pulse-bridge bumped
 * such timestamps forward instead, which silently falsified the interval
 * spacing the analysis reads; a counter keeps the anchor honest and still gives
 * the server an idempotent `INSERT OR IGNORE` key.
 *
 * [rrIntervalMs] of 0 is a legal artifact sentinel, not missing data. Nothing in
 * this layer polices physiological ranges — artifacts are data, and quality
 * judgment belongs to the analysis stage.
 *
 * [isQuarantined] is set only by the 422 bisect, and a quarantined row is
 * skipped by uploads forever after; it is kept rather than deleted so a poison
 * row can still be looked at.
 */
@Entity(
    tableName = "hr_samples",
    primaryKeys = ["deviceId", "timestampMs", "seq"],
    // The uploader's two scans — pendingUpload and pruneSynced — are the only
    // queries that do not go through the primary key, and this is the biggest
    // table in the database. The server also indexes session_id; nothing on the
    // client reads samples by session yet, so that index would be write
    // amplification on the hottest insert path for no reader.
    indices = [Index(value = ["isSynced", "isQuarantined", "timestampMs"])],
)
data class HrSampleEntity(
    val deviceId: String,
    val timestampMs: Long,
    val seq: Int,
    val heartRateBpm: Int,
    val rrIntervalMs: Int,
    val isGapBefore: Boolean = false,
    val sessionId: String,
    val isSynced: Boolean = false,
    val syncedAt: Long? = null,
    val isQuarantined: Boolean = false,
)

/** A sample's primary key, for the marking calls that work on a whole batch. */
data class HrSampleKey(val deviceId: String, val timestampMs: Long, val seq: Int)

/** The key of the row this is, so an uploaded batch can be marked from what it sent. */
fun HrSampleEntity.key(): HrSampleKey = HrSampleKey(deviceId, timestampMs, seq)

/**
 * One completion toggle, appended beside the coach blob write it accompanies.
 *
 * Append-only: an uncheck is an [ACTION_UNCHECK] row, never a delete. Undo is
 * data — correlation folds the stream in [clientTimestampMs] order to derive
 * final state, and the coach blob stays the display truth either way.
 *
 * A toggle carries at most one of [setNum] (per-set ticks) and [itemKey]
 * (checklist items); neither means an exercise-level toggle, which is how cardio
 * completion arrives. [sessionId] is absent when no capture was running — most
 * events, since the log is useful without a strap.
 */
@Entity(
    tableName = "set_events",
    // As on hr_samples: the uploader's scans are the only non-key reads. The
    // server's (date, exercise_key) index serves correlation queries that run
    // there, not here — the coach UI reads the blob, never this table.
    indices = [Index(value = ["isSynced", "isQuarantined", "clientTimestampMs"])],
)
data class SetEventEntity(
    @PrimaryKey val eventId: String,
    val date: DateString,
    val exerciseKey: String,
    val setNum: Int? = null,
    val itemKey: String? = null,
    val action: String,
    val clientTimestampMs: Long,
    val sessionId: String? = null,
    val isSynced: Boolean = false,
    val isQuarantined: Boolean = false,
) {
    companion object {
        const val ACTION_CHECK = "check"
        const val ACTION_UNCHECK = "uncheck"
    }
}

/**
 * One cardio-guide action, appended when the rider anchors or extends a guided
 * timeline **while a capture is running**.
 *
 * The sibling of [SetEventEntity] and the same kind of record: not what the plan
 * asked for, but what the rider did and when, so a beat stream can be read
 * against the timeline it was ridden to.
 *
 * Only two actions exist. [ACTION_START] is the instant the timeline was
 * anchored; [ACTION_EXTEND] is five appended minutes ([extensionSec]). Segment
 * boundaries are **derived** from the anchor plus the durations in
 * [timelineJson], shifted by the timestamped extends — recording them at runtime
 * would leave holes, because the overlay ticks only while it is composed and is
 * routinely dismissed mid-ride. There is no stop event: a guide stops being
 * read, which is not something that happens.
 *
 * [sessionId] is **not nullable here**, where the set-event log's is, and the
 * system's two-case model is the reason. Either wellness owns a ride end to end
 * — strap capture and guide used together, every row keyed by one session id —
 * or the ride lives entirely on the Garmin side and wellness records nothing but
 * the completion checkbox, exactly as for strength work. There is no third case:
 * a strapless guided ride is a watch ride. So a guide event with no session
 * would be dead weight that no session-keyed analysis could reach, and its
 * presence is the recording precondition rather than a detail of the row.
 *
 * [timelineJson] is the segments as the guide drew them, serialized in the coach
 * wire's own segment shape. It is copied rather than referenced so the record
 * survives a plan edited after the ride.
 */
@Entity(
    tableName = "guide_events",
    // As on set_events: the uploader's scans are the only non-key reads. The
    // server's (date, exercise_key) index serves the correlation queries, which
    // run there and never here — nothing on this client reads this table back.
    indices = [Index(value = ["isSynced", "isQuarantined", "clientTimestampMs"])],
)
data class GuideEventEntity(
    @PrimaryKey val eventId: String,
    val date: DateString,
    val exerciseKey: String,
    val action: String,
    val clientTimestampMs: Long,
    val sessionId: String,
    /** Extends only: the step in seconds, never zero. */
    val extensionSec: Int? = null,
    /** Starts only: the guided segments, in the coach wire's segment shape. */
    val timelineJson: String? = null,
    val isSynced: Boolean = false,
    val isQuarantined: Boolean = false,
) {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_EXTEND = "extend"
    }
}
