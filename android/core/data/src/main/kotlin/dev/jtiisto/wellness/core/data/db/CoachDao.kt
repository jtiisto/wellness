package dev.jtiisto.wellness.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.SyncStamp
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** A dirty date's generation at snapshot time. */
data class CoachDateGeneration(val date: DateString, val dirtyGeneration: Long)

/** A date whose dirty flag may clear, guarded by the generation it was uploaded at. */
data class CoachDirtyClear(val date: DateString, val generation: Long)

/**
 * Everything one upload needs to know about the dirty set, read in a **single**
 * transaction.
 *
 * Both maps have to come from the same instant. Pairing a day's JSON with a
 * generation read a moment later would let an edit that landed in between be
 * adopted over: the payload would carry the old blob while the generation said
 * "unchanged since we sent it", and the post-upload adoption would overwrite
 * the fresh edit with the server's answer to the stale one. This is the native
 * equivalent of the PWA's uninterrupted single-threaded build block.
 *
 * [payload] is what actually goes on the wire (`withBaseTokens` applied), and
 * it is kept rather than rebuilt because the tombstone-verdict rule has to ask
 * "was *this* entry in the upload?" against the exact object that was sent.
 */
data class PendingUpload(
    val generations: Map<DateString, Long> = emptyMap(),
    val payload: Map<DateString, JsonObject> = emptyMap(),
    val unsatisfiableDates: List<DateString> = emptyList(),
)

/**
 * A day's next JSON, and the completion event that day's edit earned.
 *
 * The two are one decision rather than two, and that is the whole point of the
 * type: an event is a claim that a set was ticked at a moment, and a claim whose
 * blob write then failed to land would be a set the correlation says was
 * performed and the screen says never was. [setEvent] is null for every edit
 * that is not a completion toggle, which is most of them.
 */
data class CoachLogEdit(val logJson: String, val setEvent: SetEventEntity? = null)

/**
 * Coach storage: server-authoritative plans, whole-day log blobs, and the
 * per-date dirty machinery as SQL.
 *
 * Same three rules as [JournalDao], and for the same reasons:
 *
 * 1. **Mark bumps, snapshot reads, clear only lands while unchanged.** The
 *    generation `WHERE` is what makes an edit during an in-flight upload
 *    survive.
 * 2. **Anything that merges with an existing row reads it inside the
 *    transaction.** A row read beforehand and written back restores stale
 *    content *and* a stale generation, and the guarded clear would then match
 *    its own handiwork.
 * 3. **The upload transaction never writes the pull watermark.** Only
 *    [applyDownload] does. See the note there.
 *
 * An abstract class rather than an interface so the composed `@Transaction`
 * methods are real code a JVM fake inherits.
 */
@Dao
abstract class CoachDao {

    // ---- meta ------------------------------------------------------------

    @Query("SELECT value FROM coach_meta WHERE key = :key")
    abstract suspend fun getMeta(key: String): String?

    @Query("SELECT value FROM coach_meta WHERE key = :key")
    abstract fun observeMeta(key: String): Flow<String?>

    @Upsert
    abstract suspend fun upsertMeta(row: CoachMetaEntity)

    @Query("INSERT OR IGNORE INTO coach_meta(key, value) VALUES(:key, :value)")
    abstract suspend fun insertMetaIfAbsent(key: String, value: String)

    /**
     * The client id, minted on first use. Insert-if-absent then read, in one
     * transaction: two callers racing the very first access must not mint two
     * ids — the loser's insert is ignored and it reads the winner's.
     */
    @Transaction
    open suspend fun getOrCreateClientId(candidate: String): String {
        insertMetaIfAbsent(KEY_CLIENT_ID, candidate)
        return requireNotNull(getMeta(KEY_CLIENT_ID)) { "client id vanished after insert-if-absent" }
    }

    // ---- plans -----------------------------------------------------------

    @Query("SELECT * FROM coach_plans WHERE date = :date")
    abstract suspend fun getPlan(date: DateString): CoachPlanEntity?

    @Query("SELECT * FROM coach_plans WHERE date = :date")
    abstract fun observePlan(date: DateString): Flow<CoachPlanEntity?>

    @Query("SELECT * FROM coach_plans ORDER BY date")
    abstract suspend fun listAllPlans(): List<CoachPlanEntity>

    /**
     * Every plan in the window. The calendar's status dots and the
     * last-performance lookup both need the whole window, not one day, and the
     * window is bounded at 60 days by the prune.
     */
    @Query("SELECT * FROM coach_plans ORDER BY date")
    abstract fun observeAllPlans(): Flow<List<CoachPlanEntity>>

    @Upsert
    abstract suspend fun upsertPlan(row: CoachPlanEntity)

    @Query("DELETE FROM coach_plans WHERE date = :date")
    abstract suspend fun deletePlan(date: DateString)

    // ---- logs ------------------------------------------------------------

    @Query("SELECT * FROM coach_logs WHERE date = :date")
    abstract suspend fun getLog(date: DateString): CoachLogEntity?

    @Query("SELECT * FROM coach_logs WHERE date = :date")
    abstract fun observeLog(date: DateString): Flow<CoachLogEntity?>

    @Query("SELECT * FROM coach_logs ORDER BY date")
    abstract suspend fun listAllLogs(): List<CoachLogEntity>

    /** Every stored day. See [observeAllPlans] — the same two readers need both. */
    @Query("SELECT * FROM coach_logs ORDER BY date")
    abstract fun observeAllLogs(): Flow<List<CoachLogEntity>>

    @Query("SELECT * FROM coach_logs WHERE isDirty = 1 ORDER BY date")
    abstract suspend fun listDirtyLogs(): List<CoachLogEntity>

    @Query("SELECT date, dirtyGeneration FROM coach_logs WHERE isDirty = 1 ORDER BY date")
    abstract suspend fun snapshotDirtyLogs(): List<CoachDateGeneration>

    @Query("SELECT COUNT(*) FROM coach_logs WHERE isDirty = 1")
    abstract suspend fun countDirty(): Int

    @Query("SELECT COUNT(*) FROM coach_logs WHERE isDirty = 1")
    abstract fun observeDirtyCount(): Flow<Int>

    @Upsert
    abstract suspend fun upsertLog(row: CoachLogEntity)

    @Query("UPDATE coach_logs SET isDirty = 1, dirtyGeneration = dirtyGeneration + 1 WHERE date = :date")
    abstract suspend fun markLogDirty(date: DateString)

    /** Clears only while the generation is untouched — a mid-sync edit keeps it dirty. */
    @Query("UPDATE coach_logs SET isDirty = 0 WHERE date = :date AND dirtyGeneration = :generation")
    abstract suspend fun clearLogDirty(date: DateString, generation: Long)

    /**
     * The 60-day window prune, applied to both maps.
     *
     * Unconditional — dirty rows included, unlike the journal's prune. The
     * PWA's `pruneOlderThan` is unconditional too, and here the dirty flag
     * lives *on* the row: dropping the row drops the marker with it, which is
     * the same clean end state the PWA reaches one cycle later by clearing the
     * date as unsatisfiable. Sparing dirty rows instead would keep re-uploading
     * days the window has already closed on.
     *
     * The accepted cost: an edit made to a closed-window day mid-cycle is
     * discarded unsent, in that same cycle rather than the next. The server
     * cannot reconcile that date any more either way — pinned by
     * `windowPruneDropsAStillDirtyOldDay`.
     */
    @Query("DELETE FROM coach_logs WHERE date < :cutoff")
    abstract suspend fun pruneLogsBefore(cutoff: DateString)

    @Query("DELETE FROM coach_plans WHERE date < :cutoff")
    abstract suspend fun prunePlansBefore(cutoff: DateString)

    // ---- composed writes -------------------------------------------------

    /**
     * The completion log's insert.
     *
     * Declared here rather than reached through [SetEventDao] because Room
     * cannot call across DAOs, and a completion event has to land in the same
     * transaction as the blob write it describes. `SetEventDao.insert` remains
     * the standalone path for the uploader's own callers.
     */
    @Insert
    abstract suspend fun insertSetEvent(event: SetEventEntity)

    /**
     * A local log edit. [transform] is handed the stored day's JSON (null when
     * the day does not exist yet) and returns the next one plus any event it
     * earned, or **null to do nothing at all** — no write, no dirty mark, no
     * event, no upload.
     *
     * It runs inside the transaction so it always sees the day as stored; a
     * caller that read the day first and wrote it back would revert whatever a
     * pull delivered in between. `isDirty`/`dirtyGeneration` are read back from
     * storage rather than taken from the caller, so nothing can reset the
     * counter the mid-sync check depends on.
     *
     * [CoachLogEdit.setEvent] is inserted last and only on the writing path, so
     * an event exists if and only if the mutation it describes was applied.
     */
    @Transaction
    open suspend fun updateLogAndMarkDirty(
        date: DateString,
        transform: (String?) -> CoachLogEdit?,
    ): Boolean {
        val existing = getLog(date)
        val edit = transform(existing?.logJson) ?: return false
        upsertLog(
            CoachLogEntity(
                date = date,
                logJson = edit.logJson,
                isDirty = existing?.isDirty ?: false,
                dirtyGeneration = existing?.dirtyGeneration ?: 0L,
            ),
        )
        markLogDirty(date)
        edit.setEvent?.let { insertSetEvent(it) }
        return true
    }

    /**
     * Snapshot the dirty set and build the upload from it, atomically.
     *
     * [build] receives every dirty row — JSON and generation together — and
     * returns the whole [PendingUpload]. This is also the pre-network
     * generation snapshot: it is taken before the POST and nothing else is read
     * for it afterwards.
     */
    @Transaction
    open suspend fun buildPendingUpload(build: (List<CoachLogEntity>) -> PendingUpload): PendingUpload =
        build(listDirtyLogs())

    /** Generation-guarded dirty clears as one transaction. */
    @Transaction
    open suspend fun clearDirty(clears: List<CoachDirtyClear>) {
        for (clear in clears) clearLogDirty(clear.date, clear.generation)
    }

    /**
     * Adopt an upload's results and clear the dirty flags it settled — one
     * transaction, so a crash can never leave dirty cleared against a day whose
     * fresh base tokens were not written.
     *
     * [adopt] runs inside the transaction on the rows as they stand right now:
     * it is handed the current rows for [dates] (content *and* generation) and
     * returns the rows to write. The generations it sees are what decides
     * wholesale adoption versus token-advancement, so they must not be read
     * anywhere else.
     *
     * **No watermark is written here.** The POST's `serverTime` is an ordinary
     * write timestamp with none of the GET watermark's overlap, so persisting
     * it as the pull cursor would let the next incremental pull skip changes
     * another device committed during this one.
     */
    @Transaction
    open suspend fun applyUploadResults(
        dates: List<DateString>,
        clears: List<CoachDirtyClear>,
        adopt: (List<CoachLogEntity>) -> List<CoachLogEntity>,
    ) {
        for (row in adopt(dates.mapNotNull { getLog(it) })) {
            val existing = getLog(row.date)
            upsertLog(
                row.copy(
                    isDirty = existing?.isDirty ?: false,
                    dirtyGeneration = existing?.dirtyGeneration ?: 0L,
                ),
            )
        }
        for (clear in clears) clearLogDirty(clear.date, clear.generation)
    }

    /**
     * Apply a pull in one transaction.
     *
     * Plans are overwritten unconditionally — the server owns them and there is
     * no local edit to protect. Logs are merged only where the date is **not
     * currently dirty**, and that check happens here rather than in the caller:
     * a date that went dirty since the caller looked would otherwise have the
     * user's unsent edit overwritten by the server's copy.
     *
     * This is the only place `lastServerSyncTime` is written, and it comes from
     * the GET's `serverTime` — the one stamp offset into the past far enough to
     * re-deliver a write that committed during the pull.
     *
     * [fullSnapshotPlans] is the force-sync path's one difference, and it is a
     * correctness fix rather than an optimization. `deletedPlanDates` is
     * computed by the server *relative to the `last_sync_time` it was given*, so
     * a watermark-free pull reports no deletions at all. Without this, a plan
     * the server has removed would survive every full reconciliation forever —
     * the pull would simply never mention it. When set, the response's plan set
     * is authoritative and any local plan missing from it is deleted, before the
     * upserts and inside the same transaction.
     *
     * Logs are deliberately never treated this way. A day the user created
     * locally and has not uploaded yet is absent from the server's response by
     * definition, and deleting it would destroy unsent work.
     *
     * The snapshot is trusted for the deletion but **not for the watermark**.
     * "Every local plan removed by a response that carried none of its own" is
     * a legitimate empty schedule and also exactly what a server answering from
     * an empty or half-restored database looks like; nothing in a 200 tells the
     * two apart. So the deletion stands — the rule is right, and re-deriving it
     * later is impossible once the cursor has moved — while the cursor stays
     * put, leaving one ordinary incremental pull able to restore whatever the
     * server really has.
     */
    @Transaction
    open suspend fun applyDownload(
        plans: List<CoachPlanEntity>,
        deletedPlanDates: List<DateString>,
        logs: List<CoachLogEntity>,
        earliestDate: DateString?,
        watermark: SyncStamp?,
        fullSnapshotPlans: Boolean = false,
    ) {
        var wipedOnAnEmptySnapshot = false
        if (fullSnapshotPlans) {
            val present = plans.mapTo(mutableSetOf()) { it.date }
            val stored = listAllPlans()
            for (row in stored) {
                if (row.date !in present) deletePlan(row.date)
            }
            wipedOnAnEmptySnapshot = plans.isEmpty() && stored.isNotEmpty()
        }
        for (date in deletedPlanDates) deletePlan(date)
        for (plan in plans) upsertPlan(plan)

        for (log in logs) {
            val existing = getLog(log.date)
            if (existing != null && existing.isDirty) continue
            upsertLog(log.copy(dirtyGeneration = existing?.dirtyGeneration ?: 0L))
        }

        if (earliestDate != null) upsertMeta(CoachMetaEntity(KEY_EARLIEST_DATE, earliestDate))
        if (watermark != null && !wipedOnAnEmptySnapshot) {
            upsertMeta(CoachMetaEntity(KEY_LAST_SERVER_SYNC_TIME, watermark))
        }

        // After the writes, never before: the server can legitimately deliver a
        // day that sits exactly on the boundary.
        if (earliestDate != null) {
            prunePlansBefore(earliestDate)
            pruneLogsBefore(earliestDate)
        }
    }

    companion object {
        const val KEY_CLIENT_ID = "clientId"
        const val KEY_LAST_SERVER_SYNC_TIME = "lastServerSyncTime"
        const val KEY_EARLIEST_DATE = "earliestDate"
    }
}
