package dev.jtiisto.wellness.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.SyncStamp

/**
 * One day's workout plan, exactly as the server sent it.
 *
 * Plans are server-authoritative and the client never writes one, so there is
 * no dirty state here — only the blob and the projection the sync path needs.
 * [lastModified] is a projection of the JSON's `_lastModified`; every upsert
 * rewrites the two together (the journal invariant), because the plan-version
 * poll compares against exactly that value.
 */
@Entity(tableName = "coach_plans")
data class CoachPlanEntity(
    @PrimaryKey val date: DateString,
    val planJson: String,
    val lastModified: SyncStamp?,
)

/**
 * One day's workout log, stored whole.
 *
 * **The wire unit is the day**, and [logJson] holds it verbatim: arbitrary
 * exercise keys, `session_feedback`, per-record `_lastModified` tokens, the
 * advisory `_lastModifiedAt`/`_lastModifiedBy` stamps, and any pending
 * `_deleted` tombstone. It is never normalized into rows — the server
 * arbitrates per record inside that object, and a client that split it apart
 * would have to reassemble a byte-identical day to upload it again.
 *
 * Dirty state is per date, and lives on the row rather than in a separate list
 * as the PWA's `dirtyDates` does. [dirtyGeneration] is bumped on every local
 * write so a clear can tell "still the day we uploaded" from "edited since".
 */
@Entity(tableName = "coach_logs")
data class CoachLogEntity(
    @PrimaryKey val date: DateString,
    val logJson: String,
    val isDirty: Boolean,
    val dirtyGeneration: Long,
)

/** Coach-scoped key/value: the client id, the sync watermark, the window start. */
@Entity(tableName = "coach_meta")
data class CoachMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
