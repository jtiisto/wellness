package dev.jtiisto.wellness.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Every row the export writes, read at one instant.
 *
 * Bounded by construction: the journal window is seven days, the coach window
 * sixty, and both are pruned every cycle. That is what makes holding the whole
 * thing in memory acceptable — and it is held only long enough to stream it out
 * (see `DataExporter`).
 */
data class ExportSnapshot(
    val trackers: List<JournalTrackerEntity> = emptyList(),
    val entries: List<JournalEntryEntity> = emptyList(),
    val journalMeta: Map<String, String> = emptyMap(),
    val plans: List<CoachPlanEntity> = emptyList(),
    val logs: List<CoachLogEntity> = emptyList(),
    val coachMeta: Map<String, String> = emptyMap(),
)

/**
 * The export's read side: six tables, **one transaction**.
 *
 * The atomicity is the point, and it is why this is a DAO of its own rather
 * than six calls from the exporter. A sync landing between reading the trackers
 * and reading the entries would produce a file describing a state the device
 * was never in — entries stamped against tracker tokens that the same file says
 * are older. An export is a debugging artefact; one that lies about consistency
 * is worse than none.
 *
 * `server_profiles`, `debug_log` and `app_schema_version` are absent on
 * purpose — see `DataExporter`.
 */
@Dao
abstract class ExportDao {

    @Query("SELECT * FROM journal_trackers ORDER BY id")
    abstract suspend fun trackers(): List<JournalTrackerEntity>

    @Query("SELECT * FROM journal_entries ORDER BY date, trackerId")
    abstract suspend fun entries(): List<JournalEntryEntity>

    @Query("SELECT * FROM journal_meta ORDER BY `key`")
    abstract suspend fun journalMeta(): List<JournalMetaEntity>

    @Query("SELECT * FROM coach_plans ORDER BY date")
    abstract suspend fun plans(): List<CoachPlanEntity>

    @Query("SELECT * FROM coach_logs ORDER BY date")
    abstract suspend fun logs(): List<CoachLogEntity>

    @Query("SELECT * FROM coach_meta ORDER BY `key`")
    abstract suspend fun coachMeta(): List<CoachMetaEntity>

    @Transaction
    open suspend fun snapshot(): ExportSnapshot = ExportSnapshot(
        trackers = trackers(),
        entries = entries(),
        journalMeta = journalMeta().associate { it.key to it.value },
        plans = plans(),
        logs = logs(),
        coachMeta = coachMeta().associate { it.key to it.value },
    )
}
