package dev.jtiisto.wellness.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * The per-session count query, defined once because two DAOs run it.
 *
 * [HrSampleDao.summaries] serves the diagnostics sheet; [ExportDao.hrSampleSummaries]
 * runs the same statement inside the export's own transaction, as every other
 * query in that DAO does. A shared constant is what keeps them from drifting.
 */
internal const val HR_SAMPLE_SUMMARY_SQL =
    "SELECT sessionId, COUNT(*) AS total, " +
        "SUM(CASE WHEN isSynced = 0 AND isQuarantined = 0 THEN 1 ELSE 0 END) AS pending, " +
        "SUM(CASE WHEN isQuarantined = 1 THEN 1 ELSE 0 END) AS quarantined, " +
        "MIN(timestampMs) AS firstTimestampMs, MAX(timestampMs) AS lastTimestampMs " +
        "FROM hr_samples GROUP BY sessionId ORDER BY firstTimestampMs"

/**
 * One stored beat, projected down to what a reading needs: when, and how fast.
 *
 * A projection rather than the entity because the one caller — the guide's
 * auto-fill — wants a few thousand beats and none of the sync columns, and
 * because a query that selects two integers cannot accidentally start being used
 * as an upload path.
 */
data class HrBeatRow(val timestampMs: Long, val heartRateBpm: Int)

/** A session's sample counts, aggregated in SQL so the export never holds the rows. */
data class HrSampleSummary(
    val sessionId: String,
    val total: Int,
    val pending: Int,
    val quarantined: Int,
    val firstTimestampMs: Long,
    val lastTimestampMs: Long,
)

/**
 * RR intervals: the one table in this database that grows without a user
 * touching anything.
 *
 * The sync model is append-only plus [HrSampleEntity.isSynced] rather than the
 * journal's `isDirty`/`dirtyGeneration`. There is no server arbitration to lose
 * a race with — the client authors every row, the ingest is idempotent on the
 * primary key, and a retried batch therefore costs nothing. The generation
 * counter exists to protect edits made *during* an upload, and a sample is never
 * edited.
 *
 * Nothing prunes an unsynced row. Retention only ever removes what the server
 * already has.
 */
@Dao
abstract class HrSampleDao {

    /**
     * `INSERT OR IGNORE` on `(deviceId, timestampMs, seq)`: a re-delivered
     * notification, or a buffer flushed twice across a service restart, must not
     * duplicate beats. The returned row ids carry `-1` where a row already
     * existed, which is the only way to count what was dropped.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(rows: List<HrSampleEntity>): List<Long>

    /**
     * The next batch to upload, oldest first.
     *
     * Quarantined rows are excluded permanently — they are the poison the 422
     * bisect isolated, and re-offering them would spin the same batch forever.
     */
    @Query(
        "SELECT * FROM hr_samples WHERE isSynced = 0 AND isQuarantined = 0 " +
            "ORDER BY timestampMs, seq LIMIT :limit",
    )
    abstract suspend fun pendingUpload(limit: Int): List<HrSampleEntity>

    @Query("SELECT * FROM hr_samples ORDER BY timestampMs, seq")
    abstract suspend fun listAll(): List<HrSampleEntity>

    /**
     * Every beat stored inside a window, oldest first.
     *
     * The one read of this table that is not the uploader's, and it is
     * **time-ranged rather than session-scoped** on purpose: a strap that drops
     * out and reconnects mid-ride opens a second session, and both halves are
     * the same ride. The window is what the rider actually rode, so the window
     * is what the question asks about.
     *
     * The boundary is inclusive at both ends; the caller decides which spans
     * inside it count. Quarantined rows are excluded — a row the server refused
     * as invalid is exactly the one that would corrupt a maximum heart rate, and
     * nothing else in this window depends on it being there.
     *
     * There is no index for this and deliberately so. It is a full scan of the
     * biggest table, run **once, when a guide is dismissed** — never on the
     * capture path — while an index on `timestampMs` would be write
     * amplification on the hottest insert in the app, paid on every beat of
     * every session, for one query a day.
     */
    @Query(
        "SELECT timestampMs, heartRateBpm FROM hr_samples " +
            "WHERE isQuarantined = 0 AND timestampMs BETWEEN :fromMs AND :toMs " +
            "ORDER BY timestampMs, seq",
    )
    abstract suspend fun beatsBetween(fromMs: Long, toMs: Long): List<HrBeatRow>

    @Query("SELECT COUNT(*) FROM hr_samples WHERE isSynced = 0 AND isQuarantined = 0")
    abstract suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM hr_samples WHERE isQuarantined = 1")
    abstract suspend fun countQuarantined(): Int

    /**
     * Per-session counts for the export and the diagnostics sheet.
     *
     * Aggregated in SQL on purpose: the callers want to know how much is here
     * and whether it has left the device, and materializing tens of thousands of
     * RR rows to count them would put the export's memory bound at the mercy of
     * how long the user trains.
     */
    @Query(HR_SAMPLE_SUMMARY_SQL)
    abstract suspend fun summaries(): List<HrSampleSummary>

    @Query(
        "UPDATE hr_samples SET isSynced = 1, syncedAt = :syncedAt " +
            "WHERE deviceId = :deviceId AND timestampMs = :timestampMs AND seq = :seq",
    )
    abstract suspend fun markSyncedRow(deviceId: String, timestampMs: Long, seq: Int, syncedAt: Long)

    @Query(
        "UPDATE hr_samples SET isQuarantined = 1 " +
            "WHERE deviceId = :deviceId AND timestampMs = :timestampMs AND seq = :seq",
    )
    abstract suspend fun markQuarantinedRow(deviceId: String, timestampMs: Long, seq: Int)

    /**
     * Mark a whole uploaded batch, one primary-key update per row, in one
     * transaction.
     *
     * Row at a time because a composite key cannot be matched against a bound
     * list; acceptable because the protocol caps a batch at 1000 rows and live
     * flushes are 200. The transaction is the point: a crash halfway would
     * otherwise leave part of an accepted batch pending, and it would be
     * re-uploaded — harmless, since the ingest is idempotent, but the counts
     * would stop meaning anything.
     */
    @Transaction
    open suspend fun markSynced(keys: List<HrSampleKey>, syncedAt: Long) {
        for (key in keys) markSyncedRow(key.deviceId, key.timestampMs, key.seq, syncedAt)
    }

    @Transaction
    open suspend fun markQuarantined(keys: List<HrSampleKey>) {
        for (key in keys) markQuarantinedRow(key.deviceId, key.timestampMs, key.seq)
    }

    /**
     * Retention: synced rows older than [cutoffMs] go, and only those.
     *
     * The boundary is exclusive — a row stamped exactly [cutoffMs] survives —
     * and `isSynced = 1` is what makes this safe to run on any schedule at all:
     * an unsynced row is the only copy that exists anywhere.
     */
    @Query("DELETE FROM hr_samples WHERE isSynced = 1 AND timestampMs < :cutoffMs")
    abstract suspend fun pruneSynced(cutoffMs: Long): Int
}
