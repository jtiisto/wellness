package dev.jtiisto.wellness.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The one database for every module.
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
        CoachPlanEntity::class,
        CoachLogEntity::class,
        CoachMetaEntity::class,
        TrendsMetaEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class WellnessDatabase : RoomDatabase() {

    abstract fun debugLogDao(): DebugLogDao

    abstract fun payloadCacheDao(): PayloadCacheDao

    abstract fun journalDao(): JournalDao

    abstract fun coachDao(): CoachDao

    abstract fun trendsMetaDao(): TrendsMetaDao

    companion object {
        const val NAME = "wellness.db"
    }
}
