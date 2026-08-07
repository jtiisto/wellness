package dev.jtiisto.wellness.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PayloadCacheDao {

    @Upsert
    suspend fun upsert(entry: PayloadCacheEntity)

    @Query("SELECT * FROM payload_cache WHERE module = :module AND key = :key")
    suspend fun find(module: String, key: String): PayloadCacheEntity?

    @Query("DELETE FROM payload_cache WHERE module = :module")
    suspend fun clearModule(module: String)
}
