package dev.jtiisto.wellness.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.SyncStamp

/**
 * One journal tracker.
 *
 * **[dataJson] is the source of truth** — the full server-form tracker object,
 * `lastModifiedAt` and unknown-field passthrough included. [name], [category],
 * [type] and [lastModifiedAt] are projections of it, kept as columns only so
 * SQL can query and group. Every write path rewrites the JSON and all four
 * projections together, in one transaction: a stamp written to the column alone
 * would leave the next upload reading a stale base token out of [dataJson].
 *
 * [deleted] is a **local** pending delete, and lives here and nowhere else —
 * never inside [dataJson], never in the DTO's extras. The upload builder
 * injects it as `_deleted`. The row survives until the delete is confirmed
 * because the upload needs its base token.
 */
@Entity(tableName = "journal_trackers")
data class JournalTrackerEntity(
    @PrimaryKey val id: String,
    val name: String?,
    val category: String?,
    val type: String?,
    val deleted: Boolean,
    val lastModifiedAt: SyncStamp?,
    val dataJson: String,
    val isDirty: Boolean,
    val dirtyGeneration: Long,
)

/**
 * One tracker's value for one day.
 *
 * [valueJson] holds the JSON-encoded scalar rather than a typed column: the
 * field is a number for quantifiable trackers and a string for notes, and the
 * encoded form preserves both — plus the server's exact literal — losslessly.
 */
@Entity(tableName = "journal_entries", primaryKeys = ["date", "trackerId"])
data class JournalEntryEntity(
    val date: DateString,
    val trackerId: String,
    val valueJson: String?,
    val completed: Boolean?,
    val lastModifiedAt: SyncStamp?,
    val isDirty: Boolean,
    val dirtyGeneration: Long,
)

/** Journal-scoped key/value: the client id and the sync watermark. */
@Entity(tableName = "journal_meta")
data class JournalMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
