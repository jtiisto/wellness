package dev.jtiisto.wellness.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2: the journal tables (Phase 2).
 *
 * Purely additive — nothing in `payload_cache` or `debug_log` moves — so an
 * install carrying an unsent Phase 1 debug log upgrades without losing it.
 *
 * The statements are transcribed verbatim from the generated
 * `schemas/…WellnessDatabase/2.json` `createSql` with `${TABLE_NAME}`
 * substituted. `Migration1to2Test` re-validates that against the real schema;
 * if the entities change, regenerate and copy again rather than editing by eye.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `journal_trackers` (" +
                "`id` TEXT NOT NULL, `name` TEXT, `category` TEXT, `type` TEXT, " +
                "`deleted` INTEGER NOT NULL, `lastModifiedAt` TEXT, `dataJson` TEXT NOT NULL, " +
                "`isDirty` INTEGER NOT NULL, `dirtyGeneration` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `journal_entries` (" +
                "`date` TEXT NOT NULL, `trackerId` TEXT NOT NULL, `valueJson` TEXT, " +
                "`completed` INTEGER, `lastModifiedAt` TEXT, `isDirty` INTEGER NOT NULL, " +
                "`dirtyGeneration` INTEGER NOT NULL, " +
                "PRIMARY KEY(`date`, `trackerId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `journal_meta` (" +
                "`key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                "PRIMARY KEY(`key`))",
        )
    }
}

/**
 * v2 → v3: the coach tables (Phase 4).
 *
 * Additive in exactly the same way, and for the same reason: an install
 * carrying unsent journal edits must upgrade with every dirty row and its
 * generation counter intact.
 *
 * Transcribed verbatim from `schemas/…WellnessDatabase/3.json` with
 * `${TABLE_NAME}` substituted; `Migration2to3Test` re-validates it against the
 * real schema, and also runs the whole v1 → v3 chain.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `coach_plans` (" +
                "`date` TEXT NOT NULL, `planJson` TEXT NOT NULL, `lastModified` TEXT, " +
                "PRIMARY KEY(`date`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `coach_logs` (" +
                "`date` TEXT NOT NULL, `logJson` TEXT NOT NULL, `isDirty` INTEGER NOT NULL, " +
                "`dirtyGeneration` INTEGER NOT NULL, " +
                "PRIMARY KEY(`date`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `coach_meta` (" +
                "`key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                "PRIMARY KEY(`key`))",
        )
    }
}

/** Every migration the app ships, in order. Never add destructive fallbacks. */
val WELLNESS_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
