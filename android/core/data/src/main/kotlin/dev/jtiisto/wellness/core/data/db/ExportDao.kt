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
 *
 * `hr_samples` is the one table that would have broken that bound — an hour of
 * capture is thousands of RR rows — so it enters as [hrSampleSummaries],
 * aggregated in SQL. Sessions and set events are per-workout counts and come
 * through whole.
 */
data class ExportSnapshot(
    val trackers: List<JournalTrackerEntity> = emptyList(),
    val entries: List<JournalEntryEntity> = emptyList(),
    val journalMeta: Map<String, String> = emptyMap(),
    val plans: List<CoachPlanEntity> = emptyList(),
    val logs: List<CoachLogEntity> = emptyList(),
    val coachMeta: Map<String, String> = emptyMap(),
    val hrSessions: List<HrSessionEntity> = emptyList(),
    val hrSampleSummaries: List<HrSampleSummary> = emptyList(),
    val setEvents: List<SetEventEntity> = emptyList(),
)

/**
 * The export's read side: nine tables, **one transaction**.
 *
 * The atomicity is the point, and it is why this is a DAO of its own rather
 * than nine calls from the exporter. A sync landing between reading the trackers
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

    @Query("SELECT * FROM hr_sessions ORDER BY startedAtMs")
    abstract suspend fun hrSessions(): List<HrSessionEntity>

    /** Counts, never rows — see [ExportSnapshot] and [HR_SAMPLE_SUMMARY_SQL]. */
    @Query(HR_SAMPLE_SUMMARY_SQL)
    abstract suspend fun hrSampleSummaries(): List<HrSampleSummary>

    @Query("SELECT * FROM set_events ORDER BY clientTimestampMs, rowid")
    abstract suspend fun setEvents(): List<SetEventEntity>

    @Transaction
    open suspend fun snapshot(): ExportSnapshot = ExportSnapshot(
        trackers = trackers(),
        entries = entries(),
        journalMeta = journalMeta().associate { it.key to it.value },
        plans = plans(),
        logs = logs(),
        coachMeta = coachMeta().associate { it.key to it.value },
        hrSessions = hrSessions(),
        hrSampleSummaries = hrSampleSummaries(),
        setEvents = setEvents(),
    )
}
