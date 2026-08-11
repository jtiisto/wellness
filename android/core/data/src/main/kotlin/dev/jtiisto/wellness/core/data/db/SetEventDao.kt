package dev.jtiisto.wellness.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * The completion-toggle log.
 *
 * Same append-only + `isSynced` model as [HrSampleDao], and nothing here is ever
 * updated in place except those two flags: an uncheck is a new row.
 *
 * [insert] is a plain `@Insert` with no transaction of its own so a caller can
 * compose it — the coach's blob write and the event that describes it have to
 * land together or not at all, and both tables live in the one database. Room
 * cannot call across DAOs, so the composing `@Transaction` method declares its
 * own `@Insert` for [SetEventEntity]; that keeps the composition JVM-fakeable,
 * which `RoomDatabase.withTransaction` would not.
 */
@Dao
abstract class SetEventDao {

    /**
     * Conflict is abort, not ignore: [SetEventEntity.eventId] is a UUID minted
     * per toggle, so a collision means the caller reused one.
     */
    @Insert
    abstract suspend fun insert(event: SetEventEntity)

    /**
     * The next batch to upload, in toggle order.
     *
     * `rowid` breaks ties rather than `eventId`, so two toggles inside the same
     * millisecond upload in the order they were made rather than in the order
     * their random UUIDs happen to sort.
     */
    @Query(
        "SELECT * FROM set_events WHERE isSynced = 0 AND isQuarantined = 0 " +
            "ORDER BY clientTimestampMs, rowid LIMIT :limit",
    )
    abstract suspend fun pendingUpload(limit: Int): List<SetEventEntity>

    @Query("SELECT * FROM set_events ORDER BY clientTimestampMs, rowid")
    abstract suspend fun listAll(): List<SetEventEntity>

    @Query("SELECT COUNT(*) FROM set_events WHERE isSynced = 0 AND isQuarantined = 0")
    abstract suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM set_events WHERE isQuarantined = 1")
    abstract suspend fun countQuarantined(): Int

    /** [eventIds] is one batch, so it is bounded by the protocol's 1000-row cap. */
    @Query("UPDATE set_events SET isSynced = 1 WHERE eventId IN (:eventIds)")
    abstract suspend fun markSynced(eventIds: List<String>)

    @Query("UPDATE set_events SET isQuarantined = 1 WHERE eventId IN (:eventIds)")
    abstract suspend fun markQuarantined(eventIds: List<String>)

    /**
     * Retention: synced events older than [cutoffMs], exclusive on the boundary,
     * matching [HrSampleDao.pruneSynced]. The horizon is coach's sixty days
     * rather than the samples' seven — an event is one row per set, and it is
     * the half of the correlation a person might still want to look at.
     */
    @Query("DELETE FROM set_events WHERE isSynced = 1 AND clientTimestampMs < :cutoffMs")
    abstract suspend fun pruneSynced(cutoffMs: Long): Int
}
