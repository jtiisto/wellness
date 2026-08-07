package dev.jtiisto.wellness.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The one database for every module. Journal and coach tables arrive in Phases
 * 2 and 4 as real migrations (v2+).
 *
 * Destructive migration is deliberately never enabled: dirty rows are the only
 * recovery source for edits that have not reached the server yet, so dropping
 * the database on a schema mismatch would silently lose user data.
 */
@Database(
    entities = [PayloadCacheEntity::class, DebugLogEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class WellnessDatabase : RoomDatabase() {

    abstract fun debugLogDao(): DebugLogDao

    abstract fun payloadCacheDao(): PayloadCacheDao

    companion object {
        const val NAME = "wellness.db"
    }
}
