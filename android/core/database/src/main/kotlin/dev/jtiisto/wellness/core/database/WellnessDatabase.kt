package dev.jtiisto.wellness.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.jtiisto.wellness.core.database.entity.SyncMetadataEntity

@Database(
    entities = [
        SyncMetadataEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class WellnessDatabase : RoomDatabase() {
    abstract fun syncMetadataDao(): SyncMetadataDao
}
