package dev.jtiisto.wellness.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Ring-buffer access for the debug log.
 *
 * The retention predicates here mirror `DebugLogLogic` one for one — the pure
 * functions are what the tests pin, so the two must not drift.
 */
@Dao
abstract class DebugLogDao {

    @Insert
    abstract suspend fun insert(entry: DebugLogEntity)

    /** Mirrors `DebugLogLogic.isRetained`: a row survives while `ts > cutoff`. */
    @Query("DELETE FROM debug_log WHERE ts <= :cutoff")
    abstract suspend fun deleteExpired(cutoff: Long)

    /** Mirrors the cap half of `DebugLogLogic.prune`: keep the newest [max] ids. */
    @Query("DELETE FROM debug_log WHERE id NOT IN (SELECT id FROM debug_log ORDER BY id DESC LIMIT :max)")
    abstract suspend fun trimToCap(max: Int)

    /**
     * Unfiltered live rows (bounded by the write-side cap); [DebugLog.entries]
     * applies the TTL in memory with a fresh cutoff per emission — a SQL
     * cutoff would be frozen at collection time.
     */
    @Query("SELECT * FROM debug_log ORDER BY id DESC")
    abstract fun observeAll(): Flow<List<DebugLogEntity>>

    @Query("SELECT * FROM debug_log WHERE ts > :cutoff ORDER BY id ASC")
    abstract suspend fun listSince(cutoff: Long): List<DebugLogEntity>

    /**
     * Append and prune atomically. Concurrent fire-and-forget writers would
     * otherwise interleave insert and prune and overshoot the cap.
     */
    @Transaction
    open suspend fun insertAndPrune(entry: DebugLogEntity, cutoff: Long, max: Int) {
        insert(entry)
        deleteExpired(cutoff)
        trimToCap(max)
    }
}
