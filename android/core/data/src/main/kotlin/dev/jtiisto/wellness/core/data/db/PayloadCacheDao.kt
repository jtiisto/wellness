package dev.jtiisto.wellness.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PayloadCacheDao {

    @Upsert
    suspend fun upsert(entry: PayloadCacheEntity)

    @Query("SELECT * FROM payload_cache WHERE module = :module AND key = :key")
    suspend fun find(module: String, key: String): PayloadCacheEntity?

    /**
     * [find], observed. The widget's live sleep peek collects this inside its
     * Glance session so a fetch landing in the cache — the hourly worker's or
     * the app's own — re-renders the home screen in place instead of waiting
     * for the next session start.
     */
    @Query("SELECT * FROM payload_cache WHERE module = :module AND key = :key")
    fun observe(module: String, key: String): Flow<PayloadCacheEntity?>

    /**
     * Drop one row. Used by Analysis to forget a deleted report and to prune
     * report bodies that have fallen out of the newest-50 history window.
     *
     * A `@Query` rather than `@Delete`: the composite key is what identifies the
     * row, and `@Delete` would need a whole entity — including a `payloadJson`
     * the caller does not have and would have to read back first.
     */
    @Query("DELETE FROM payload_cache WHERE module = :module AND key = :key")
    suspend fun delete(module: String, key: String)

    @Query("DELETE FROM payload_cache WHERE module = :module")
    suspend fun clearModule(module: String)
}
