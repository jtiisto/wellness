package dev.jtiisto.wellness.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The one database for every module. Coach tables arrive in Phase 4 as v3.
 *
 * Destructive migration is deliberately never enabled: dirty rows are the only
 * recovery source for edits that have not reached the server yet, so dropping
 * the database on a schema mismatch would silently lose user data.
 */
@Database(
    entities = [
        PayloadCacheEntity::class,
        DebugLogEntity::class,
        JournalTrackerEntity::class,
        JournalEntryEntity::class,
        JournalMetaEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class WellnessDatabase : RoomDatabase() {

    abstract fun debugLogDao(): DebugLogDao

    abstract fun payloadCacheDao(): PayloadCacheDao

    abstract fun journalDao(): JournalDao

    companion object {
        const val NAME = "wellness.db"
    }
}
