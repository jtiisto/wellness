package dev.jtiisto.wellness.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * The cardio-guide action log.
 *
 * The same append-only + `isSynced` model as [SetEventDao], statement for
 * statement, because it is the same kind of table: rows are minted by the
 * screen, pushed once, and never edited afterwards.
 *
 * Unlike its sibling this one is not composed into anything. A set tick has a
 * coach blob write to land with, atomically; a guide action has none — the plan
 * is deliberately untouched by START and by `+ 5 MIN` — so the insert stands
 * alone and [GuideEventRecorder][dev.jtiisto.wellness.core.data.hr.GuideEventRecorder]
 * is all the composition there is.
 */
@Dao
abstract class GuideEventDao {

    /**
     * Conflict is abort, not ignore: [GuideEventEntity.eventId] is a UUID minted
     * per action, so a collision means the caller reused one.
     */
    @Insert
    abstract suspend fun insert(event: GuideEventEntity)

    /**
     * The next batch to upload, in the order the actions were taken.
     *
     * `rowid` breaks ties rather than `eventId`, so a START and an extend landing
     * in the same millisecond upload in the order they happened rather than in
     * the order their random UUIDs happen to sort.
     */
    @Query(
        "SELECT * FROM guide_events WHERE isSynced = 0 AND isQuarantined = 0 " +
            "ORDER BY clientTimestampMs, rowid LIMIT :limit",
    )
    abstract suspend fun pendingUpload(limit: Int): List<GuideEventEntity>

    @Query("SELECT * FROM guide_events ORDER BY clientTimestampMs, rowid")
    abstract suspend fun listAll(): List<GuideEventEntity>

    @Query("SELECT COUNT(*) FROM guide_events WHERE isSynced = 0 AND isQuarantined = 0")
    abstract suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM guide_events WHERE isQuarantined = 1")
    abstract suspend fun countQuarantined(): Int

    /** [eventIds] is one batch, so it is bounded by the protocol's 1000-row cap. */
    @Query("UPDATE guide_events SET isSynced = 1 WHERE eventId IN (:eventIds)")
    abstract suspend fun markSynced(eventIds: List<String>)

    @Query("UPDATE guide_events SET isQuarantined = 1 WHERE eventId IN (:eventIds)")
    abstract suspend fun markQuarantined(eventIds: List<String>)

    /**
     * Retention: coach's sixty days, exclusive on the boundary, the same horizon
     * the set-event log keeps. A ride produces a handful of these rows, and they
     * are the half of the correlation a person might still want to look at.
     */
    @Query("DELETE FROM guide_events WHERE isSynced = 1 AND clientTimestampMs < :cutoffMs")
    abstract suspend fun pruneSynced(cutoffMs: Long): Int
}
