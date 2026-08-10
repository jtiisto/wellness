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
        ServerProfileEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class WellnessDatabase : RoomDatabase() {

    abstract fun debugLogDao(): DebugLogDao

    abstract fun payloadCacheDao(): PayloadCacheDao

    abstract fun journalDao(): JournalDao

    abstract fun coachDao(): CoachDao

    abstract fun trendsMetaDao(): TrendsMetaDao

    abstract fun serverProfilesDao(): ServerProfilesDao

    abstract fun serverSwitchDao(): ServerSwitchDao

    abstract fun exportDao(): ExportDao

    companion object {
        const val NAME = "wellness.db"
    }
}
