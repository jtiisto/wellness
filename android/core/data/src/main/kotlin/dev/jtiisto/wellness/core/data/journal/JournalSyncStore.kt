package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.EntryAdoption
import dev.jtiisto.wellness.core.data.db.EntryDirtyClear
import dev.jtiisto.wellness.core.data.db.EntryRef
import dev.jtiisto.wellness.core.data.db.EntryStamp
import dev.jtiisto.wellness.core.data.db.JournalDao
import dev.jtiisto.wellness.core.data.db.JournalEntryEntity
import dev.jtiisto.wellness.core.data.db.JournalTrackerEntity
import dev.jtiisto.wellness.core.data.db.SettledDelete
import dev.jtiisto.wellness.core.data.db.TrackerAdoption
import dev.jtiisto.wellness.core.data.db.TrackerDirtyClear
import dev.jtiisto.wellness.core.data.db.TrackerStamp
import dev.jtiisto.wellness.core.data.db.UploadResponseApply
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.JournalApi
import dev.jtiisto.wellness.core.data.network.describeForLog
import dev.jtiisto.wellness.core.data.network.SyncStamp
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.DirtyState
import dev.jtiisto.wellness.core.data.sync.ForceSyncCounts
import dev.jtiisto.wellness.core.data.sync.ForceSyncModuleResult
import dev.jtiisto.wellness.core.data.sync.ForceSyncSkipReason
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import dev.jtiisto.wellness.core.data.sync.SyncResult
import dev.jtiisto.wellness.core.data.sync.SyncSkipReason
import dev.jtiisto.wellness.core.data.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.util.UUID

/** The journal debug-log tag. */
private const val TAG = "journal-sync"

/**
 * The journal's sync orchestration — a port of the `journal/store.js` sync
 * surface onto Room.
 *
 * The pure decisions live in `JournalSyncLogic`; this class is the read → call
 * → write wrapper around them, plus the ordering that makes the protocol safe.
 * Four things it must never get wrong:
 *
 * 1. **`dataJson` and its projected columns are written together.** Every path
 *    below goes through [trackerEntity], so a server stamp can never land in
 *    the column while the JSON keeps the old one — which would upload a stale
 *    base token on the next edit and get it rejected.
 * 2. **Generations are snapshotted before the first network call** and the
 *    dirty clear is guarded by them in SQL, so an edit made mid-flight stays
 *    dirty and uploads next cycle instead of being silently dropped.
 * 3. **Nothing read outside a transaction is written back into one.** Rows are
 *    re-read inside [JournalDao.applyUploadResponse]; what this class passes in
 *    are values and the generation each was uploaded at.
 * 4. **The POST's `serverTime` is never the pull watermark**, exactly as in
 *    coach. Only the delta's carries the server's two-second overlap, and that
 *    overlap is the whole defence against a concurrent writer's change falling
 *    between two incremental pulls.
 *
 * @param isOnline gates the whole cycle; offline is a skip, not a failure.
 * @param session the server-switch fence. Checked immediately before each of
 *   the two writes that turn a server response into rows, so a response that
 *   arrives after a switch has been confirmed cannot repopulate the wiped
 *   database with the previous server's data.
 * @param scheduleUpload the scheduler's debounce hook, called after every local
 *   mutation. Supplied as a lambda because the scheduler is built around this
 *   store and the two would otherwise be a construction cycle.
 */
class JournalSyncStore(
    private val dao: JournalDao,
    private val api: JournalApi,
    private val isOnline: () -> Boolean,
    private val session: ServerSessionGate,
    private val json: Json = WellnessJson,
    private val debugLog: DebugLog? = null,
    private val scheduleUpload: () -> Unit = {},
    private val today: () -> LocalDate = LocalDate::now,
    private val newClientId: () -> String = { UUID.randomUUID().toString() },
) {

    private val _syncStatus = MutableStateFlow(SyncStatus.GRAY)

    /** GREEN clean / RED dirty / GRAY offline-or-never-synced. */
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)

    /** The scheduler's busy hook. Set for the whole of [triggerSync]. */
    val isSyncing: Boolean get() = _isSyncing.value

    /**
     * The same flag as [isSyncing], observable.
     *
     * The scheduler polls the boolean; the masthead's dot and the pull gesture
     * need to be told — and the gesture needs it for a reason the dot does not
     * have. `SyncScheduler.requestSync` hands back a job that completes at once
     * when the flight is somebody else's, so this flow is the only honest answer
     * to "is a sync still running". One source, so the two can never disagree.
     */
    val isSyncingFlow: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /**
     * Single-flight, independent of who is calling. The scheduler already
     * serializes its own triggers, but it is not the only possible caller — a
     * background flush would otherwise interleave two cycles and have each
     * clear dirty flags the other still needs.
     */
    private val syncMutex = Mutex()

    private val clientIdMutex = Mutex()

    @Volatile
    private var cachedClientId: String? = null

    // ---- reads -----------------------------------------------------------

    /** The user's trackers: decoded from `dataJson`, pending deletes excluded. */
    fun observeTrackers(): Flow<List<TrackerDto>> =
        dao.observeTrackers().map { rows -> rows.map(::decodeTracker) }

    fun observeDay(date: DateString): Flow<Map<String, EntryDto>> =
        dao.observeDay(date).map { rows -> rows.associate { it.trackerId to it.toDto() } }

    /**
     * Every stored day, keyed by date then tracker id — the shape the pure
     * logic takes. One flow rather than one per date: the day view needs the
     * selected day *and* a week of history behind every row's dot strip.
     */
    fun observeEntriesByDate(): Flow<Map<DateString, Map<String, EntryDto>>> =
        dao.observeAllEntries().map(::groupByDate)

    /** The scheduler's dirty hook. */
    suspend fun hasDirtyData(): Boolean = dao.countDirty() > 0

    /**
     * The client identity, minted once and cached for the process. The mutex
     * only serializes the first access; the DAO makes the insert race-safe even
     * across processes.
     *
     * The mint is a write to a wiped table, so it takes a lease like any other
     * — the drain that surrounds every caller of this is a one-shot wait, not a
     * fence, and a first sync starting just after it would otherwise seed
     * `journal_meta` with a row belonging to the server being left. A refusal
     * fails the cycle, which is the right answer: there is no identity to sync
     * under.
     */
    suspend fun clientId(): String = cachedClientId ?: clientIdMutex.withLock {
        cachedClientId ?: session.withWriteLease { dao.getOrCreateClientId(newClientId()) }.also {
            cachedClientId = it
        }
    }

    // ---- mutators --------------------------------------------------------

    /**
     * A brand-new tracker. No base token is written, so the server treats the
     * upload as "insert only if this id is free".
     */
    suspend fun addTracker(tracker: TrackerDto) {
        session.withWriteLease { dao.upsertTrackerAndMarkDirty(trackerEntity(tracker, deleted = false)) }
        afterLocalChange()
    }

    /**
     * Edit a tracker in place. [transform] is handed the decoded current row —
     * the Kotlin stand-in for the JS partial-merge object — and must return the
     * full next value. It runs inside the write transaction, so it always sees
     * the row as stored rather than a copy that may already be stale.
     *
     * A transform that produces an identical row writes nothing and schedules
     * nothing: saving the config form without edits must not dirty a tracker.
     */
    suspend fun updateTracker(id: String, transform: (TrackerDto) -> TrackerDto) {
        val updated = session.withWriteLease {
            dao.updateTrackerAndMarkDirty(id) { existing ->
                trackerEntity(transform(decodeTracker(existing)), deleted = existing.deleted)
            }
        }
        if (updated) afterLocalChange()
    }

    /** Soft delete: the row stays until the server confirms, base token and all. */
    suspend fun deleteTracker(id: String) {
        session.withWriteLease { dao.softDeleteTrackerAndMarkDirty(id) }
        afterLocalChange()
    }

    suspend fun updateEntry(date: DateString, trackerId: String, value: JsonElement?, completed: Boolean?) {
        session.withWriteLease {
            dao.upsertEntryAndMarkDirty(date, trackerId, value?.let(::encodeValue), completed)
        }
        afterLocalChange()
    }

    /**
     * The widgets' write path: a **merge**, one field at a time, in a single
     * transaction.
     *
     * [EntryPatch] is presence-aware because the PWA's `{...entry, ...data}`
     * spread is: a field the widget did not touch keeps whatever is stored, and
     * that has to be decided against the row as it stands, not against what the
     * screen last rendered. A patch that sets neither field is a no-op — no
     * write, nothing marked dirty, no upload scheduled — which is what makes
     * the numeric field's "blur echoed the same value" case free.
     */
    suspend fun mergeEntry(date: DateString, trackerId: String, patch: EntryPatch) {
        val value = patch.value
        val completed = patch.completed
        if (value !is EntryField.Set && completed !is EntryField.Set) return
        session.withWriteLease {
            dao.mergeEntryAndMarkDirty(
                date = date,
                trackerId = trackerId,
                setValue = value is EntryField.Set,
                valueJson = (value as? EntryField.Set)?.value?.let(::encodeValue),
                setCompleted = completed is EntryField.Set,
                completed = (completed as? EntryField.Set)?.value,
            )
        }
        afterLocalChange()
    }

    /**
     * Tick or untick a day without touching the entry's value. Separate from
     * [updateEntry] because a caller cannot supply the current value safely: by
     * the time a screen reacts to a tap, a pull may have replaced it.
     */
    suspend fun setEntryCompleted(date: DateString, trackerId: String, completed: Boolean?) {
        session.withWriteLease { dao.setEntryCompletedAndMarkDirty(date, trackerId, completed) }
        afterLocalChange()
    }

    private suspend fun afterLocalChange() {
        updateSyncStatus()
        scheduleUpload()
    }

    // ---- the sync cycle --------------------------------------------------

    /**
     * One full sync: pull, then upload whatever is dirty, then prune. The
     * scheduler's `syncFn`.
     *
     * The step order is `store.js`'s and is not free to rearrange — most of all
     * the generation snapshot, which must be taken before any network call.
     */
    suspend fun triggerSync(): SyncResult {
        if (!syncMutex.tryLock()) {
            return SyncResult(success = false, reason = SyncSkipReason.ALREADY_SYNCING)
        }
        try {
            return runSyncCycle()
        } finally {
            syncMutex.unlock()
        }
    }

    /**
     * Full reconciliation: a pull with **no watermark at all**, then the
     * ordinary dirty upload.
     *
     * A separate entry point rather than a flag on [triggerSync], because that
     * method reads the stored watermark and there is no honest way for it to
     * do otherwise — a "force" flag would be a second meaning for the same
     * code path, and the one thing the user reaches for this button to fix is a
     * watermark that has got ahead of the data.
     *
     * Ordering follows the PWA's `forceSync`, which is the journal's ordinary
     * order too: pull first, then upload. The pull leaves locally-dirty rows
     * untouched, so nothing unsent is lost by going first, and the upload that
     * follows arbitrates them against a server view this cycle just refreshed.
     *
     * **The watermark may go backwards here, and that is safe.** The server
     * stamps its `serverTime` at wall-clock minus two seconds; a full pull's
     * stamp can therefore land below the one already stored. The only
     * consequence is that the next incremental pull re-delivers a few
     * deliveries it already made, and application is idempotent by protocol —
     * every row carries the token that decides whether it changes anything.
     */
    suspend fun forceSync(): ForceSyncModuleResult {
        if (!syncMutex.tryLock()) {
            return ForceSyncModuleResult.Skipped(ForceSyncSkipReason.BUSY)
        }
        try {
            if (!isOnline()) {
                _syncStatus.value = SyncStatus.GRAY
                return ForceSyncModuleResult.Skipped(ForceSyncSkipReason.OFFLINE)
            }
            _isSyncing.value = true
            try {
                return runForceCycle()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                debugLog?.log(
                    TAG,
                    "force sync error",
                    buildJsonObject { put("error", describeForLog(error)) },
                )
                _syncStatus.value = SyncStatus.RED
                return ForceSyncModuleResult.Failed(describeForLog(error))
            } finally {
                _isSyncing.value = false
            }
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun runForceCycle(): ForceSyncModuleResult {
        val clientId = clientId()

        // Before any network call, exactly as in the ordinary cycle: an edit
        // made during the upload advances past this snapshot and stays dirty.
        val snapshotTrackerGens = dao.snapshotDirtyTrackers().associate { it.id to it.dirtyGeneration }
        val snapshotEntryGens = dao.snapshotDirtyEntries()
            .associate { entryKey(it.date, it.trackerId) to it.dirtyGeneration }

        debugLog?.log(
            TAG,
            "force sync start",
            buildJsonObject {
                put("dirtyTrackers", snapshotTrackerGens.size)
                put("dirtyEntries", snapshotEntryGens.size)
            },
        )

        // The whole point: null, never the stored watermark.
        pullServerChanges(clientId, null)

        if (dao.countDirty() == 0) {
            debugLog?.log(TAG, "force sync complete (no upload)")
            updateSyncStatus()
            return ForceSyncModuleResult.Success(ForceSyncCounts.Journal(accepted = 0, conflicts = 0))
        }

        val pending = buildUpload(clientId)
        val response = api.syncUpdate(pending.payload.payload)
        applyUploadResponse(response, pending, snapshotTrackerGens, snapshotEntryGens)

        pruneLocalData()

        val accepted = response.acceptedTrackers.size + response.acceptedEntries.size
        val conflicts = response.rejectedTrackers.size + response.rejectedEntries.size
        logRejections(response)
        debugLog?.log(
            TAG,
            "force sync complete",
            buildJsonObject {
                put("accepted", accepted)
                put("conflicts", conflicts)
            },
        )
        updateSyncStatus()
        return ForceSyncModuleResult.Success(ForceSyncCounts.Journal(accepted, conflicts))
    }

    /**
     * Block until no sync cycle is in flight — the server switch's drain.
     *
     * Taking the lock is the wait: a cycle already running keeps the mutex
     * until it finishes, by design (an upload is never abandoned halfway). Its
     * writes are already fenced by [session], so what this buys is the
     * certainty that nothing is still between a response and a write when the
     * wipe transaction opens.
     */
    suspend fun awaitQuiescence() {
        syncMutex.withLock { }
    }

    private suspend fun runSyncCycle(): SyncResult {
        if (!isOnline()) {
            _syncStatus.value = SyncStatus.GRAY
            return SyncResult(success = false, reason = SyncSkipReason.OFFLINE)
        }

        _isSyncing.value = true
        try {
            val clientId = clientId()
            val watermark = dao.getMeta(JournalDao.KEY_LAST_SERVER_SYNC_TIME)

            // Before any network call: an edit made during the upload advances
            // its generation past this snapshot and so stays dirty.
            val snapshotTrackerGens = dao.snapshotDirtyTrackers().associate { it.id to it.dirtyGeneration }
            val snapshotEntryGens = dao.snapshotDirtyEntries()
                .associate { entryKey(it.date, it.trackerId) to it.dirtyGeneration }

            debugLog?.log(
                TAG,
                "sync start",
                buildJsonObject {
                    put("dirtyTrackers", snapshotTrackerGens.size)
                    put("dirtyEntries", snapshotEntryGens.size)
                    put("hasWatermark", watermark != null)
                },
            )

            pullServerChanges(clientId, watermark)

            if (dao.countDirty() == 0) {
                debugLog?.log(TAG, "sync complete (no upload)")
                updateSyncStatus()
                return SyncResult(success = true)
            }

            // Rebuilt from CURRENT rows: the pull may have advanced tokens on
            // everything that was not dirty.
            val pending = buildUpload(clientId)
            debugLog?.log(
                TAG,
                "upload attempt",
                buildJsonObject {
                    put("trackerCount", pending.payload.dirtyTrackerIds.size)
                    put("entryCount", pending.payload.dirtyEntryKeys.size)
                },
            )

            val response = api.syncUpdate(pending.payload.payload)
            applyUploadResponse(response, pending, snapshotTrackerGens, snapshotEntryGens)

            pruneLocalData()

            logRejections(response)
            updateSyncStatus()
            return SyncResult(success = true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            debugLog?.log(
                TAG,
                "sync error",
                buildJsonObject { put("error", describeForLog(error)) },
            )
            _syncStatus.value = SyncStatus.RED
            return SyncResult(success = false, error = error)
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Pull and apply. The server is authoritative for everything that is not
     * locally dirty; dirty rows are left alone and arbitrated by the upload.
     * A server-side delete wins outright — the user cannot go on editing a
     * tracker the source of truth has removed.
     */
    private suspend fun pullServerChanges(clientId: String, since: SyncStamp?) {
        val delta = api.syncDelta(clientId, since)

        val trackers = delta.config.map { trackerEntity(it, deleted = false) }
        val entries = delta.days.flatMap { (date, day) ->
            day.map { (trackerId, entry) -> entryEntity(date, trackerId, entry) }
        }

        // The lease spans the write itself. A check in front of it would be a
        // check-then-act: admitted, suspended, wiped, and then landing anyway.
        session.withWriteLease {
            dao.applyDelta(trackers, entries, delta.deletedTrackers, requireWatermark(delta.serverTime))
        }

        // Normalize whatever legacy shapes the server just handed us. Changed
        // trackers upload in THIS cycle but only clear next: normalization runs
        // after the generation snapshot, so the snapshot-gated clear skips them.
        // One harmless re-upload, exactly as in the PWA.
        normalizeLegacyTrackers()
    }

    /**
     * Migrate legacy schedules. The whole read → normalize → write runs in one
     * transaction: computing outside it and writing back would revert any edit
     * that landed in between.
     */
    private suspend fun normalizeLegacyTrackers() {
        // Under the lease: the rows this rewrites came from the server that is
        // being left, and the transaction is a read-modify-write of the tracker
        // table the wipe is about to empty.
        val count = session.withWriteLease {
            dao.rewriteTrackers { rows ->
                val normalized = computeNormalizedConfig(rows.map(::decodeTracker), locallyDeletedIds(rows))
                    ?: return@rewriteTrackers emptyList()
                val byId = rows.associateBy { it.id }
                normalized.config
                    .filter { it.id in normalized.changedIds }
                    .mapNotNull { tracker ->
                        val existing = byId[tracker.id] ?: return@mapNotNull null
                        trackerEntity(tracker, deleted = existing.deleted)
                    }
            }
        }
        if (count > 0) {
            debugLog?.log(TAG, "normalized legacy trackers", buildJsonObject { put("count", count) })
        }
    }

    /** An upload body plus the rows, and generations, it was built from. */
    private class PendingUpload(
        val payload: UploadPayloadResult,
        val trackers: List<JournalTrackerEntity>,
        val entries: List<JournalEntryEntity>,
    )

    private suspend fun buildUpload(clientId: String): PendingUpload {
        val dirtyTrackers = dao.listDirtyTrackers()
        val dirtyEntries = dao.listDirtyEntries()

        val payload = computeUploadPayload(
            meta = UploadMeta(
                clientId = clientId,
                dirtyTrackers = dirtyTrackers.map { it.id },
                dirtyEntries = dirtyEntries.map { entryKey(it.date, it.trackerId) },
            ),
            trackerConfig = dirtyTrackers.map {
                UploadTracker(id = it.id, data = parseObject(it.dataJson), locallyDeleted = it.deleted)
            },
            dailyLogs = groupByDate(dirtyEntries),
        )
        return PendingUpload(payload, dirtyTrackers, dirtyEntries)
    }

    /**
     * Turn the upload response into exactly one write.
     *
     * Nothing here reads a row to write it back. What goes into the transaction
     * is values — new tokens, adopted server rows — each tagged with the
     * generation its row had when the body was built, so the transaction can
     * tell whether the row it finds is still the one that was uploaded.
     */
    private suspend fun applyUploadResponse(
        response: UpdateResponseDto,
        pending: PendingUpload,
        snapshotTrackerGens: Map<String, Long>,
        snapshotEntryGens: Map<String, Long>,
    ) {
        val builtTrackerGens = pending.trackers.associate { it.id to it.dirtyGeneration }
        val builtEntryGens = pending.entries.associate { entryKey(it.date, it.trackerId) to it.dirtyGeneration }
        val deletedAtBuild = pending.trackers.filter { it.deleted }.map { it.id }.toSet()

        // The pure port decides what a rejection means: a live serverRow is an
        // adoption, a soft-deleted one is a deletion, and a `missing` one is an
        // instruction to drop the local row. Given empty local state it yields
        // precisely those sets, with no stale rows mixed in.
        val rejected = computeRejectedApply(
            response.rejectedTrackers,
            response.rejectedEntries,
            emptyList(),
            emptyMap(),
        )

        // Which of the tracker deletions were phantoms. The pure state puts them
        // in one list, as the PWA's single cleanup path does, but the two arrive
        // at that list for different reasons and are applied under different
        // guards — so the flag on the wire, not the list, is what separates them.
        val missingTrackerIds = response.rejectedTrackers.filter { it.deleted }.map { it.id }
        val missingEntries = response.rejectedEntries
            .filter { it.deleted }
            .map { EntryRef(it.date, it.trackerId) }

        // Adoption takes the server's row whole, local delete flag included —
        // the PWA replaces the tracker object outright, so a delete the server
        // refused is dropped rather than silently retried against a row that
        // has since changed underneath it.
        val trackerAdoptions = rejected.trackerConfig.map { tracker ->
            TrackerAdoption(
                row = trackerEntity(tracker, deleted = false),
                expectedGeneration = builtTrackerGens[tracker.id],
            )
        }
        val entryAdoptions = rejected.dailyLogs.flatMap { (date, day) ->
            day.map { (trackerId, entry) ->
                EntryAdoption(
                    row = entryEntity(date, trackerId, entry),
                    expectedGeneration = builtEntryGens[entryKey(date, trackerId)],
                )
            }
        }

        // A local delete is settled once the server has answered for it. Keyed
        // off what was *uploaded*, so a tracker deleted after the body was
        // built is not swept up by an acceptance that never carried its
        // `_deleted`.
        val settledDeletes = buildList {
            for (accepted in response.acceptedTrackers) {
                if (accepted.id in deletedAtBuild) {
                    add(SettledDelete(accepted.id, builtTrackerGens[accepted.id]))
                }
            }
            for (id in rejected.trackerIdsToDelete) {
                // The phantoms go through the unguarded path instead.
                if (id in missingTrackerIds) continue
                add(SettledDelete(id, builtTrackerGens[id]))
            }
        }

        val resolvedTrackerIds =
            response.acceptedTrackers.map { it.id } + response.rejectedTrackers.map { it.id }
        val resolvedEntryKeys =
            response.acceptedEntries.map { entryKey(it.date, it.trackerId) } +
                response.rejectedEntries.map { entryKey(it.date, it.trackerId) }

        // Which resolved keys are even candidates for clearing is the pure
        // rule (a key that went dirty only after the snapshot — a tracker
        // normalized mid-cycle, say — is not in it and stays dirty). Whether a
        // candidate still qualifies is then decided atomically by the SQL
        // generation guard inside the transaction.
        val cleared = computeClearedDirtyState(
            trackers = DirtyState(snapshotTrackerGens.keys.toList(), snapshotTrackerGens),
            entries = DirtyState(snapshotEntryGens.keys.toList(), snapshotEntryGens),
            uploadedTrackerIds = resolvedTrackerIds,
            uploadedEntryKeys = resolvedEntryKeys,
            snapshotTrackerGens = snapshotTrackerGens,
            snapshotEntryGens = snapshotEntryGens,
        )

        // See [pullServerChanges]: the lease spans the write.
        session.withWriteLease {
            dao.applyUploadResponse(
                plan = UploadResponseApply(
                    trackerStamps = response.acceptedTrackers.map { TrackerStamp(it.id, it.lastModifiedAt) },
                    entryStamps = response.acceptedEntries.map { EntryStamp(it.date, it.trackerId, it.lastModifiedAt) },
                    trackerAdoptions = trackerAdoptions,
                    entryAdoptions = entryAdoptions,
                    settledDeletes = settledDeletes,
                    missingTrackerIds = missingTrackerIds,
                    missingEntries = missingEntries,
                    trackerClears = (snapshotTrackerGens.keys - cleared.trackers.keys.toSet())
                        .map { TrackerDirtyClear(it, snapshotTrackerGens.getValue(it)) },
                    entryClears = (snapshotEntryGens.keys - cleared.entries.keys.toSet())
                        .map { EntryDirtyClear(entryKeyDate(it), entryKeyTrackerId(it), snapshotEntryGens.getValue(it)) },
                    // Deliberately no watermark: only the DELTA's serverTime
                    // advances the pull cursor. The server stamps a delta two
                    // seconds behind wall clock precisely so an incremental pull
                    // overlaps the writes that were in flight during the last
                    // one; the POST's stamp is an ordinary write timestamp with
                    // no such margin, and storing it discarded the overlap — a
                    // change another client made in those two seconds would
                    // never be delivered. Redelivery is the cost, and every row
                    // carries the token that makes re-application a no-op.
                    watermark = null,
                ),
                restampTracker = { current, stamp ->
                    trackerEntity(
                        tracker = decodeTracker(current).copy(lastModifiedAt = stamp),
                        deleted = current.deleted,
                        isDirty = current.isDirty,
                        dirtyGeneration = current.dirtyGeneration,
                    )
                },
            )
        }
    }

    /**
     * Retention, once the response has been applied: the seven-day entry window
     * and the settled-delete backstop.
     *
     * One lease over both, taken before the first read either of them depends
     * on. They are deletes rather than inserts, so a stray one cannot leave the
     * previous server's rows behind — but it can still run its transaction
     * against a database the wipe has already emptied, and the settled-delete
     * half decides what to delete from rows it read outside that transaction.
     */
    private suspend fun pruneLocalData() = session.withWriteLease {
        dao.pruneEntriesBefore(localDataWindowStart(today()))
        pruneSettledDeletedTrackers()
    }

    /**
     * The backstop for local deletes the response transaction did not settle
     * (a rejected delete whose row had moved on, or a crash mid-cycle). The DAO
     * re-reads the id list inside its own transaction; the check here only
     * decides whether there is anything to do.
     */
    private suspend fun pruneSettledDeletedTrackers() {
        val rows = dao.listAllTrackers()
        val settledIds = rows.filter { it.deleted && !it.isDirty }.map { it.id }.toSet()
        computePruneDeletedTrackers(
            trackerConfig = rows.map(::decodeTracker),
            locallyDeletedIds = settledIds,
            dailyLogs = groupByDate(dao.listAllEntries()),
        ) ?: return
        dao.pruneSettledDeletedTrackers()
    }

    /**
     * Rejections are recovered in-cycle, so they are not errors — but a run of
     * them is the signature of a client stuck arbitrating, which is worth
     * being able to see. Identity and `errorKind` only: the `serverRow` that
     * came with each is journal content and never goes near the log.
     */
    private fun logRejections(response: UpdateResponseDto) {
        val rejectedCount = response.rejectedTrackers.size + response.rejectedEntries.size
        if (rejectedCount == 0) return
        debugLog?.log(
            TAG,
            "upload had rejections (recovered in-cycle)",
            buildJsonObject {
                put("rejectedCount", rejectedCount)
                putJsonArray("trackers") {
                    for (rejection in response.rejectedTrackers) {
                        add(
                            buildJsonObject {
                                put("id", rejection.id)
                                put("errorKind", rejection.errorKind)
                            },
                        )
                    }
                }
                putJsonArray("entries") {
                    for (rejection in response.rejectedEntries) {
                        add(
                            buildJsonObject {
                                put("key", entryKey(rejection.date, rejection.trackerId))
                                put("errorKind", rejection.errorKind)
                            },
                        )
                    }
                }
            },
        )
    }

    private suspend fun updateSyncStatus() {
        val dirty = dao.countDirty()
        val watermark = dao.getMeta(JournalDao.KEY_LAST_SERVER_SYNC_TIME)
        _syncStatus.value = when {
            !isOnline() -> SyncStatus.GRAY
            dirty > 0 -> SyncStatus.RED
            watermark != null -> SyncStatus.GREEN
            else -> SyncStatus.GRAY
        }
    }

    // ---- row <-> DTO -----------------------------------------------------

    /**
     * The single place a tracker row is built. `dataJson` and every projected
     * column come from the same DTO here, which is what keeps them in step.
     */
    private fun trackerEntity(
        tracker: TrackerDto,
        deleted: Boolean,
        isDirty: Boolean = false,
        dirtyGeneration: Long = 0L,
    ): JournalTrackerEntity = JournalTrackerEntity(
        id = tracker.id,
        name = tracker.name,
        category = tracker.category,
        type = tracker.type,
        deleted = deleted,
        lastModifiedAt = tracker.lastModifiedAt,
        dataJson = json.encodeToString(
            JsonObject.serializer(),
            TrackerDtoSerializer.toJson(tracker, json),
        ),
        isDirty = isDirty,
        dirtyGeneration = dirtyGeneration,
    )

    private fun entryEntity(
        date: DateString,
        trackerId: String,
        entry: EntryDto,
        isDirty: Boolean = false,
        dirtyGeneration: Long = 0L,
    ): JournalEntryEntity = JournalEntryEntity(
        date = date,
        trackerId = trackerId,
        valueJson = entry.value?.let(::encodeValue),
        completed = entry.completed,
        lastModifiedAt = entry.lastModifiedAt,
        isDirty = isDirty,
        dirtyGeneration = dirtyGeneration,
    )

    // The decode direction moved to `JournalDecode.kt` when the widget's
    // `JournalDayPeek` became its second consumer — it reads the same rows from
    // the same DAO and must read them the same way. These two bind this store's
    // own `json` so the call sites stay as they were; the rules live over there.

    private fun decodeTracker(row: JournalTrackerEntity): TrackerDto = decodeTracker(row, json)

    private fun JournalEntryEntity.toDto(): EntryDto = toDto(json)

    private fun groupByDate(rows: Collection<JournalEntryEntity>): Map<DateString, Map<String, EntryDto>> =
        rows.groupBy { it.date }.mapValues { (_, day) -> day.associate { it.trackerId to it.toDto() } }

    private fun locallyDeletedIds(rows: List<JournalTrackerEntity>): Set<String> =
        rows.filter { it.deleted }.map { it.id }.toSet()

    private fun encodeValue(value: JsonElement): String = json.encodeToString(JsonElement.serializer(), value)

    private fun parseObject(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    /**
     * The delta's watermark, or the cycle fails.
     *
     * There is deliberately no device-clock fallback. The watermark is a
     * *server* instant that the next delta uses as its `since`; a device clock
     * running ahead would advance it past changes the server has not delivered
     * yet, and those changes would never be pulled again — silent data loss,
     * dressed up as a successful sync. Failing instead costs one retry: the
     * watermark stays put, and the server's equality-accepting arbitration
     * makes the eventual re-upload a no-op.
     *
     * Only the pull calls this, which is why it names one. The upload
     * response's `serverTime` is never a watermark, so its absence is not an
     * error — see [applyUploadResponse].
     */
    private fun requireWatermark(serverTime: SyncStamp?): SyncStamp = serverTime
        ?: error("journal delta response had no serverTime")
}
