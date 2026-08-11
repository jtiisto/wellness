package dev.jtiisto.wellness.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.SyncStamp
import kotlinx.coroutines.flow.Flow

/** A dirty tracker's generation at snapshot time. */
data class TrackerGeneration(val id: String, val dirtyGeneration: Long)

/** A dirty entry's generation at snapshot time. */
data class EntryGeneration(val date: DateString, val trackerId: String, val dirtyGeneration: Long)

/** A tracker whose dirty flag may be cleared, guarded by its snapshot generation. */
data class TrackerDirtyClear(val id: String, val generation: Long)

/** An entry whose dirty flag may be cleared, guarded by its snapshot generation. */
data class EntryDirtyClear(val date: DateString, val trackerId: String, val generation: Long)

/** The new server token an accepted upload issued for one row. */
data class TrackerStamp(val id: String, val lastModifiedAt: SyncStamp)

data class EntryStamp(val date: DateString, val trackerId: String, val lastModifiedAt: SyncStamp)

/**
 * A rejected upload's server row, to overwrite the local one — but only while
 * that row still sits at [expectedGeneration] (the generation it had when the
 * upload body was built). `null` means we expect no row at all, so the adoption
 * is an insert.
 */
data class TrackerAdoption(val row: JournalTrackerEntity, val expectedGeneration: Long?)

data class EntryAdoption(val row: JournalEntryEntity, val expectedGeneration: Long?)

/** A local delete the server has settled, guarded the same way. */
data class SettledDelete(val id: String, val expectedGeneration: Long?)

/** One entry, by its composite key. */
data class EntryRef(val date: DateString, val trackerId: String)

/**
 * Everything one upload response changes, applied as a single transaction.
 *
 * Deliberately values and not pre-built rows for the parts that must merge with
 * live state: the rows to merge into are read *inside* the transaction, because
 * anything read before it may already be stale by the time it is written back.
 */
data class UploadResponseApply(
    val trackerStamps: List<TrackerStamp> = emptyList(),
    val entryStamps: List<EntryStamp> = emptyList(),
    val trackerAdoptions: List<TrackerAdoption> = emptyList(),
    val entryAdoptions: List<EntryAdoption> = emptyList(),
    val settledDeletes: List<SettledDelete> = emptyList(),
    /**
     * Rows the server answered `missing` for, dropped **unguarded**.
     *
     * Deliberately not [SettledDelete]s. A generation guard exists to protect a
     * mid-sync edit worth arbitrating, and there is nothing here to arbitrate
     * against: the server has no such row, and the local row carries a base
     * token the server will reject again for the same reason. Guarding would
     * only postpone the same deletion by a cycle — and a user editing every
     * cycle would keep the phantom, and its red indicator, indefinitely.
     */
    val missingTrackerIds: List<String> = emptyList(),
    val missingEntries: List<EntryRef> = emptyList(),
    val trackerClears: List<TrackerDirtyClear> = emptyList(),
    val entryClears: List<EntryDirtyClear> = emptyList(),
    val watermark: SyncStamp? = null,
)

/**
 * Journal storage.
 *
 * The dirty machinery of `shared/dirty-set.js` lives here as SQL: mark bumps
 * the generation, the snapshot reads it, and the clear only lands while the
 * generation is unchanged. That last `WHERE` is what makes an edit made *during*
 * an in-flight upload survive — and unlike the PWA's ordered IndexedDB writes,
 * it happens inside the same transaction as the token application, so there is
 * no crash window between the two.
 *
 * The rule that keeps that guarantee honest: **anything that merges with an
 * existing row reads it inside the transaction.** A row read beforehand and
 * written back wholesale would restore both stale content and a stale
 * generation, and the generation-guarded clear would then match its own
 * handiwork and drop the user's edit along with the evidence of it.
 *
 * An abstract class rather than an interface so the composed `@Transaction`
 * methods are real code a JVM fake inherits: tests override the primitives and
 * still exercise the ordering these methods pin.
 */
@Dao
abstract class JournalDao {

    // ---- meta ------------------------------------------------------------

    @Query("SELECT value FROM journal_meta WHERE key = :key")
    abstract suspend fun getMeta(key: String): String?

    /** The UI's own preferences live here too, under `ui.`-namespaced keys. */
    @Query("SELECT value FROM journal_meta WHERE key = :key")
    abstract fun observeMeta(key: String): Flow<String?>

    @Upsert
    abstract suspend fun upsertMeta(row: JournalMetaEntity)

    @Query("INSERT OR IGNORE INTO journal_meta(key, value) VALUES(:key, :value)")
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

    // ---- tracker reads ---------------------------------------------------

    @Query("SELECT * FROM journal_trackers WHERE id = :id")
    abstract suspend fun getTracker(id: String): JournalTrackerEntity?

    /** Every tracker, locally-deleted ones included — normalization and prune inputs. */
    @Query("SELECT * FROM journal_trackers")
    abstract suspend fun listAllTrackers(): List<JournalTrackerEntity>

    @Query("SELECT * FROM journal_trackers WHERE isDirty = 1")
    abstract suspend fun listDirtyTrackers(): List<JournalTrackerEntity>

    @Query("SELECT id, dirtyGeneration FROM journal_trackers WHERE isDirty = 1")
    abstract suspend fun snapshotDirtyTrackers(): List<TrackerGeneration>

    /**
     * Local deletes with nothing left to upload. A tracker that went dirty
     * again — deleted after the upload body was built, say — is excluded: its
     * `_deleted` has not reached the server, and dropping it here would lose
     * the delete entirely.
     */
    @Query("SELECT id FROM journal_trackers WHERE deleted = 1 AND isDirty = 0")
    abstract suspend fun listSettledDeletedTrackerIds(): List<String>

    /** What the UI lists: pending deletes are already gone from the user's view. */
    @Query("SELECT * FROM journal_trackers WHERE deleted = 0 ORDER BY category, name")
    abstract fun observeTrackers(): Flow<List<JournalTrackerEntity>>

    // ---- entry reads -----------------------------------------------------

    @Query("SELECT * FROM journal_entries WHERE date = :date AND trackerId = :trackerId")
    abstract suspend fun getEntry(date: DateString, trackerId: String): JournalEntryEntity?

    @Query("SELECT * FROM journal_entries")
    abstract suspend fun listAllEntries(): List<JournalEntryEntity>

    @Query("SELECT * FROM journal_entries WHERE isDirty = 1")
    abstract suspend fun listDirtyEntries(): List<JournalEntryEntity>

    @Query("SELECT date, trackerId, dirtyGeneration FROM journal_entries WHERE isDirty = 1")
    abstract suspend fun snapshotDirtyEntries(): List<EntryGeneration>

    @Query("SELECT * FROM journal_entries WHERE date = :date")
    abstract fun observeDay(date: DateString): Flow<List<JournalEntryEntity>>

    /**
     * Every stored entry. The 7-day prune keeps this to a couple of hundred
     * rows, and the day view needs a whole window at once anyway: each row's
     * dot strip reaches seven days back from whichever date is selected.
     */
    @Query("SELECT * FROM journal_entries")
    abstract fun observeAllEntries(): Flow<List<JournalEntryEntity>>

    @Query(
        "SELECT (SELECT COUNT(*) FROM journal_trackers WHERE isDirty = 1) + " +
            "(SELECT COUNT(*) FROM journal_entries WHERE isDirty = 1)",
    )
    abstract suspend fun countDirty(): Int

    /**
     * Dirty *trackers* only — what the date strip's lock keys off. A pending
     * delete counts (it is a dirty tracker row); a dirty entry never does.
     */
    @Query("SELECT COUNT(*) FROM journal_trackers WHERE isDirty = 1")
    abstract suspend fun countDirtyTrackers(): Int

    @Query("SELECT COUNT(*) FROM journal_trackers WHERE isDirty = 1")
    abstract fun observeDirtyTrackerCount(): Flow<Int>

    // ---- primitive writes ------------------------------------------------

    @Upsert
    abstract suspend fun upsertTracker(row: JournalTrackerEntity)

    @Upsert
    abstract suspend fun upsertEntry(row: JournalEntryEntity)

    @Query("UPDATE journal_trackers SET isDirty = 1, dirtyGeneration = dirtyGeneration + 1 WHERE id = :id")
    abstract suspend fun markTrackerDirty(id: String)

    @Query(
        "UPDATE journal_entries SET isDirty = 1, dirtyGeneration = dirtyGeneration + 1 " +
            "WHERE date = :date AND trackerId = :trackerId",
    )
    abstract suspend fun markEntryDirty(date: DateString, trackerId: String)

    /**
     * Write an accepted entry's token without touching its payload. A whole-row
     * write would carry back whatever value was read before the transaction.
     */
    @Query(
        "UPDATE journal_entries SET lastModifiedAt = :stamp " +
            "WHERE date = :date AND trackerId = :trackerId",
    )
    abstract suspend fun stampEntry(date: DateString, trackerId: String, stamp: SyncStamp)

    /** Clears only while the generation is untouched — a mid-sync edit keeps it dirty. */
    @Query("UPDATE journal_trackers SET isDirty = 0 WHERE id = :id AND dirtyGeneration = :generation")
    abstract suspend fun clearTrackerDirty(id: String, generation: Long)

    @Query(
        "UPDATE journal_entries SET isDirty = 0 " +
            "WHERE date = :date AND trackerId = :trackerId AND dirtyGeneration = :generation",
    )
    abstract suspend fun clearEntryDirty(date: DateString, trackerId: String, generation: Long)

    @Query("UPDATE journal_trackers SET deleted = 1 WHERE id = :id")
    abstract suspend fun markTrackerDeleted(id: String)

    @Query("DELETE FROM journal_trackers WHERE id = :id")
    abstract suspend fun deleteTracker(id: String)

    @Query("DELETE FROM journal_entries WHERE trackerId = :trackerId")
    abstract suspend fun deleteEntriesOf(trackerId: String)

    @Query("DELETE FROM journal_entries WHERE date = :date AND trackerId = :trackerId")
    abstract suspend fun deleteEntry(date: DateString, trackerId: String)

    /**
     * The 7-day window prune. Dirty rows are excluded: the PWA deletes old days
     * unconditionally, which could drop a re-modified old entry before it ever
     * uploaded. They get pruned on a later cycle, once clean.
     */
    @Query("DELETE FROM journal_entries WHERE isDirty = 0 AND date < :windowStart")
    abstract suspend fun pruneEntriesBefore(windowStart: DateString)

    // ---- composed writes -------------------------------------------------

    /**
     * A local tracker edit: rewrite the row and mark it dirty. The generation
     * and current dirty flag are read back from storage rather than taken from
     * [row] so a caller cannot accidentally reset the counter the mid-sync
     * check depends on.
     */
    @Transaction
    open suspend fun upsertTrackerAndMarkDirty(row: JournalTrackerEntity) {
        val existing = getTracker(row.id)
        upsertTracker(
            row.copy(
                isDirty = existing?.isDirty ?: false,
                dirtyGeneration = existing?.dirtyGeneration ?: 0L,
            ),
        )
        markTrackerDirty(row.id)
    }

    /**
     * Edit a tracker in place. [transform] runs *inside* the transaction on the
     * row as it stands, so a concurrent write cannot be read-modify-written
     * away.
     *
     * A transform that changes nothing is not a write. Comparing the whole row
     * — `dataJson`, the projections and the delete flag, with the dirty fields
     * held equal so they cannot skew it — is what stops opening the config
     * form and saving without edits from marking the tracker dirty. That would
     * cost an empty upload and, worse, lock every past day in the date strip
     * until it landed.
     *
     * Returns false when there was nothing to do: the tracker is gone, or the
     * edit was a no-op. Either way the caller must not treat it as a change.
     */
    @Transaction
    open suspend fun updateTrackerAndMarkDirty(
        id: String,
        transform: (JournalTrackerEntity) -> JournalTrackerEntity,
    ): Boolean {
        val existing = getTracker(id) ?: return false
        val next = transform(existing).copy(
            isDirty = existing.isDirty,
            dirtyGeneration = existing.dirtyGeneration,
        )
        if (next == existing) return false
        upsertTracker(next)
        markTrackerDirty(id)
        return true
    }

    /**
     * Hand every tracker to [selectRewrites] and write back whatever it returns,
     * marking each dirty. Read and write share the transaction so a tracker
     * edited between the two cannot be reverted. Returns the number rewritten.
     */
    @Transaction
    open suspend fun rewriteTrackers(
        selectRewrites: (List<JournalTrackerEntity>) -> List<JournalTrackerEntity>,
    ): Int {
        val rewrites = selectRewrites(listAllTrackers())
        for (row in rewrites) upsertTrackerAndMarkDirty(row)
        return rewrites.size
    }

    /**
     * A local entry edit. [valueJson] and [completed] replace what was there;
     * `lastModifiedAt` is preserved, because it is the base token the upload
     * still needs.
     */
    @Transaction
    open suspend fun upsertEntryAndMarkDirty(
        date: DateString,
        trackerId: String,
        valueJson: String?,
        completed: Boolean?,
    ) {
        val existing = getEntry(date, trackerId)
        upsertEntry(
            JournalEntryEntity(
                date = date,
                trackerId = trackerId,
                valueJson = valueJson,
                completed = completed,
                lastModifiedAt = existing?.lastModifiedAt,
                isDirty = existing?.isDirty ?: false,
                dirtyGeneration = existing?.dirtyGeneration ?: 0L,
            ),
        )
        markEntryDirty(date, trackerId)
    }

    /**
     * A **presence-aware** entry edit: the single write path behind every
     * widget. Each field is written only when its `set` flag says so, and the
     * other keeps whatever the stored row holds — read inside this transaction,
     * because what the screen last rendered may already be a pull behind.
     *
     * The two nullabilities are different things and both are meaningful:
     * `setValue = false` leaves the column alone, while `setValue = true` with
     * a null [valueJson] clears it back to *absent* (SQL NULL). An explicitly
     * null entry value is the string `"null"` and arrives here as such. The
     * checkbox's "write the default" rule depends on telling those apart.
     *
     * Marks the **entry** dirty, never the tracker: entry edits must not lock
     * the date strip.
     */
    @Transaction
    open suspend fun mergeEntryAndMarkDirty(
        date: DateString,
        trackerId: String,
        setValue: Boolean,
        valueJson: String?,
        setCompleted: Boolean,
        completed: Boolean?,
    ) {
        if (!setValue && !setCompleted) return
        val existing = getEntry(date, trackerId)
        upsertEntry(
            JournalEntryEntity(
                date = date,
                trackerId = trackerId,
                valueJson = if (setValue) valueJson else existing?.valueJson,
                completed = if (setCompleted) completed else existing?.completed,
                lastModifiedAt = existing?.lastModifiedAt,
                isDirty = existing?.isDirty ?: false,
                dirtyGeneration = existing?.dirtyGeneration ?: 0L,
            ),
        )
        markEntryDirty(date, trackerId)
    }

    /**
     * Tick or untick a day, leaving the entry's value alone. A caller that read
     * the value from the UI and wrote it back would undo whatever the last pull
     * delivered in between; the current row is the only safe source.
     */
    @Transaction
    open suspend fun setEntryCompletedAndMarkDirty(
        date: DateString,
        trackerId: String,
        completed: Boolean?,
    ) {
        val existing = getEntry(date, trackerId)
        upsertEntryAndMarkDirty(date, trackerId, existing?.valueJson, completed)
    }

    /**
     * A local delete: flag it and mark dirty, keeping `dataJson` and
     * `lastModifiedAt` intact — the deletion upload arbitrates on that token.
     */
    @Transaction
    open suspend fun softDeleteTrackerAndMarkDirty(id: String) {
        markTrackerDeleted(id)
        markTrackerDirty(id)
    }

    /** Tracker + its entries. No FK cascade is declared, so this is explicit. */
    @Transaction
    open suspend fun deleteTrackersWithEntries(ids: List<String>) {
        for (id in ids) {
            deleteEntriesOf(id)
            deleteTracker(id)
        }
    }

    /**
     * Drop the local deletes the server has settled. Reads the id list inside
     * the transaction so a tracker deleted moments ago — still dirty, its
     * `_deleted` not yet uploaded — is never caught by it.
     */
    @Transaction
    open suspend fun pruneSettledDeletedTrackers(): List<String> {
        val ids = listSettledDeletedTrackerIds()
        deleteTrackersWithEntries(ids)
        return ids
    }

    /**
     * Apply a delta pull in one transaction.
     *
     * The locally-dirty check happens *here*, not in the caller: a row that
     * went dirty after the caller read its dirty set would otherwise have the
     * user's unsent edit overwritten by the server's version. Non-dirty rows
     * keep their generation counter — resetting it is harmless today but the
     * counter is cheap to preserve and load-bearing the moment it is dirty.
     */
    @Transaction
    open suspend fun applyDelta(
        trackers: List<JournalTrackerEntity>,
        entries: List<JournalEntryEntity>,
        deletedTrackerIds: List<String>,
        watermark: SyncStamp?,
    ) {
        for (tracker in trackers) {
            val existing = getTracker(tracker.id)
            if (existing != null && existing.isDirty) continue
            upsertTracker(tracker.copy(dirtyGeneration = existing?.dirtyGeneration ?: 0L))
        }
        // Before the entries: a server-side delete also drops their rows, and
        // clears the dirty state that would otherwise wedge the client red.
        deleteTrackersWithEntries(deletedTrackerIds)
        for (entry in entries) {
            val existing = getEntry(entry.date, entry.trackerId)
            if (existing != null && existing.isDirty) continue
            upsertEntry(entry.copy(dirtyGeneration = existing?.dirtyGeneration ?: 0L))
        }
        if (watermark != null) upsertMeta(JournalMetaEntity(KEY_LAST_SERVER_SYNC_TIME, watermark))
    }

    /**
     * The one mutating transaction of the upload half: server-stamped tokens,
     * adopted server rows, settled-delete cleanup, the generation-guarded dirty
     * clears, and the watermark — all or nothing.
     *
     * The two kinds of write are guarded differently on purpose:
     *
     * - **Stamps** touch only the token and always land, on the row as it reads
     *   right now. An edit that arrived mid-flight keeps its content and its
     *   dirty flag, but must still take the new token: the server has moved
     *   past the old one, and re-uploading against it would come back rejected
     *   and overwrite the edit with the server's copy.
     * - **Adoptions** replace content, so they are refused once the row has
     *   moved past the generation the upload was built from. The newer edit
     *   stays dirty and the next cycle arbitrates it properly.
     *
     * [restampTracker] rewrites a row's token in both the JSON and the column;
     * it is a parameter because that encoding belongs to the serializer, not to
     * storage, and it has to run inside this transaction.
     */
    @Transaction
    open suspend fun applyUploadResponse(
        plan: UploadResponseApply,
        restampTracker: (JournalTrackerEntity, SyncStamp) -> JournalTrackerEntity,
    ) {
        for (stamp in plan.trackerStamps) {
            val current = getTracker(stamp.id) ?: continue
            upsertTracker(restampTracker(current, stamp.lastModifiedAt))
        }
        for (stamp in plan.entryStamps) {
            stampEntry(stamp.date, stamp.trackerId, stamp.lastModifiedAt)
        }

        for (adoption in plan.trackerAdoptions) {
            val current = getTracker(adoption.row.id)
            if (current?.dirtyGeneration != adoption.expectedGeneration) continue
            upsertTracker(
                adoption.row.copy(
                    isDirty = current?.isDirty ?: false,
                    dirtyGeneration = current?.dirtyGeneration ?: 0L,
                ),
            )
        }
        for (adoption in plan.entryAdoptions) {
            val current = getEntry(adoption.row.date, adoption.row.trackerId)
            if (current?.dirtyGeneration != adoption.expectedGeneration) continue
            upsertEntry(
                adoption.row.copy(
                    isDirty = current?.isDirty ?: false,
                    dirtyGeneration = current?.dirtyGeneration ?: 0L,
                ),
            )
        }

        // Inside the transaction, not after it: a crash between the dirty clear
        // and a later cleanup would leave a clean, hidden, never-again-uploaded
        // deleted row — invisible to the user and to every future sync.
        for (delete in plan.settledDeletes) {
            val current = getTracker(delete.id)
            if (current?.dirtyGeneration != delete.expectedGeneration) continue
            deleteEntriesOf(delete.id)
            deleteTracker(delete.id)
        }

        // The phantom rows, dropped whatever their generation says. See
        // [UploadResponseApply.missingTrackerIds] for why no guard belongs here.
        // Deleting the row takes its dirty flag with it — the flag is a column,
        // not a separate set — so the purge the protocol asks for is the delete.
        for (id in plan.missingTrackerIds) {
            deleteEntriesOf(id)
            deleteTracker(id)
        }
        for (entry in plan.missingEntries) {
            deleteEntry(entry.date, entry.trackerId)
        }

        for (clear in plan.trackerClears) clearTrackerDirty(clear.id, clear.generation)
        for (clear in plan.entryClears) clearEntryDirty(clear.date, clear.trackerId, clear.generation)
        if (plan.watermark != null) {
            upsertMeta(JournalMetaEntity(KEY_LAST_SERVER_SYNC_TIME, plan.watermark))
        }
    }

    companion object {
        const val KEY_CLIENT_ID = "clientId"
        const val KEY_LAST_SERVER_SYNC_TIME = "lastServerSyncTime"
    }
}
