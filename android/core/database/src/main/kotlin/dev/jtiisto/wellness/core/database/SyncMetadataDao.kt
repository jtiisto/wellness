package dev.jtiisto.wellness.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.jtiisto.wellness.core.database.entity.SyncMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncMetadataDao {

    @Query("SELECT * FROM sync_metadata WHERE moduleId = :moduleId")
    fun observe(moduleId: String): Flow<SyncMetadataEntity?>

    @Query("SELECT * FROM sync_metadata WHERE moduleId = :moduleId")
    suspend fun get(moduleId: String): SyncMetadataEntity?

    @Upsert
    suspend fun upsert(metadata: SyncMetadataEntity)
}
