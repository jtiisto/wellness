package dev.jtiisto.wellness.core.data.coach

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.CoachDao
import dev.jtiisto.wellness.core.data.db.CoachDirtyClear
import dev.jtiisto.wellness.core.data.db.CoachLogEntity
import dev.jtiisto.wellness.core.data.db.CoachPlanEntity
import dev.jtiisto.wellness.core.data.db.PendingUpload
import dev.jtiisto.wellness.core.data.network.CoachApi
import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.describeForLog
import dev.jtiisto.wellness.core.data.network.SyncStamp
import dev.jtiisto.wellness.core.data.sync.DebugLog
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The coach debug-log tag. */
private const val TAG = "coach-sync"

/**
 * Well-known log key for the ad-hoc "extra Zone 2" session on rest days.
 *
 * There is no plan for such a day — the key itself is the off-plan signal (the
 * server maps it to canonical_slug `zone_2`; see `AD_HOC_LOG_SLUGS` in
 * `src/modules/coach_logs.py` — keep the two in sync).
 */
const val EXTRA_SESSION_KEY: String = "extra_zone2"

/** The heading the ad-hoc session's card carries. */
const val EXTRA_SESSION_TITLE: String = "Zone 2 — extra session"

/**
 * Coach sync orchestration — a port of the `coach/store.js` sync surface onto
 * Room.
 *
 * The pure decisions live in [CoachSyncLogic][withBaseTokens]; this class is
 * the read → call → write wrapper around them plus the ordering that makes the
 * protocol safe. Coach differs from the journal in three structural ways, and
 * each one is load-bearing:
 *
 * 1. **Upload first, then pull.** The journal pulls first. Here the server is
 *    the only arbiter and it reconciles per record, so sending local edits
 *    before asking for changes means the answer already includes them.
 * 2. **The upload transaction never writes the pull watermark.** The POST's
 *    `serverTime` is an ordinary write timestamp; only the GET's carries the
 *    overlap that makes an incremental pull safe against concurrent writes.
 * 3. **The whole day is one record on the wire**, so nothing is normalized —
 *    what is stored is what gets uploaded, and the base-token protocol lives
 *    inside the day object.
 *
 * @param isOnline gates the whole cycle; offline is a skip, not a failure.
 * @param session the server-switch fence, checked immediately before each write
 *   that turns a server response into rows. See [ServerSessionGate].
 * @param clock supplies the advisory `_lastModifiedAt` stamps, and nothing
 *   else. It is never a source of sync ordering — the server's stamps are.
 * @param scheduleUpload the scheduler's debounce hook, called after every local
 *   mutation. A lambda because the scheduler is built around this store and the
 *   two would otherwise be a construction cycle.
 */
class CoachSyncStore(
    private val dao: CoachDao,
    private val api: CoachApi,
    private val isOnline: () -> Boolean,
    private val session: ServerSessionGate,
    private val json: Json = WellnessJson,
    private val debugLog: DebugLog? = null,
    private val scheduleUpload: () -> Unit = {},
    private val clock: () -> SyncStamp = { Instant.now().toString() },
    private val today: () -> LocalDate = LocalDate::now,
    private val newClientId: () -> String = { UUID.randomUUID().toString() },
) {

    private val _syncStatus = MutableStateFlow(SyncStatus.GRAY)

    /**
     * GREEN clean / RED dirty / GRAY offline.
     *
     * Deliberately without the journal's never-synced condition: the PWA's
     * coach dot is green as soon as nothing is pending, and the two clients
     * must not disagree about the same server state.
     */
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)

    /** The scheduler's busy hook. Set for the whole of [triggerSync]. */
    val isSyncing: Boolean get() = _isSyncing.value

    /**
     * The same flag as [isSyncing], observable.
     *
     * The scheduler polls the boolean; the header's indicator needs to be told,
     * so both read one source rather than two that can disagree.
     */
    val isSyncingFlow: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /**
     * Single-flight, independent of who is calling. Two overlapping cycles
     * would each clear dirty dates the other still needs.
     */
    private val syncMutex = Mutex()

    private val clientIdMutex = Mutex()

    @Volatile
    private var cachedClientId: String? = null

    /**
     * The latest plan version this process has **consumed** — that is, seen and
     * then successfully pulled. Kept in memory only (PWA parity): every cold
     * start's first poll therefore triggers one full sync, which doubles as a
     * correctness safety net, and is why persisting it is a deliberate
     * non-optimization.
     */
    @Volatile
    private var lastKnownPlansVersion: String? = null

    /**
     * A version [pollCheck] has probed but no sync has consumed yet.
     *
     * The probe must not retire the version on its own: if the sync it asked
     * for then fails, a version recorded at probe time would make every later
     * poll answer "nothing changed" for data this client never pulled. So the
     * probe parks it here and only a successful [downloadServerChanges] commits
     * it — until then [pollCheck] keeps comparing against the consumed value
     * and keeps saying yes.
     */
    @Volatile
    private var pendingPlansVersion: String? = null

    // ---- reads -----------------------------------------------------------

    /** Today, as the device's calendar reckons it. */
    fun todayDate(): DateString = today().toString()

    /**
     * The day's plan, decoded on read. A blob that fails to decode yields null
     * rather than throwing: this flow feeds the UI, and a server field that
     * changed shape must not take the screen down with it.
     */
    fun observePlan(date: DateString): Flow<PlanDto?> =
        dao.observePlan(date).map { row -> row?.let { decodePlan(it.planJson, date) } }

    /** The day's log, exactly as stored — arbitrary keys and all. */
    fun observeLog(date: DateString): Flow<JsonObject?> =
        dao.observeLog(date).map { row -> row?.let { parseObject(it.logJson) } }

    /**
     * Every plan in the window, keyed by date.
     *
     * The calendar and the last-performance lookup are both whole-window
     * readers, so they take the window rather than subscribing per day.
     *
     * A blob that will not decode stays in the map as a **null value**, which is
     * not the same as being absent: the day genuinely has a plan, and dropping
     * the key would present a reshaped payload as a rest day — offering an
     * ad-hoc session on a day that already has a workout on it.
     */
    fun observeAllPlans(): Flow<Map<DateString, PlanDto?>> = dao.observeAllPlans().map { rows ->
        rows.associate { row -> row.date to decodePlan(row.planJson, row.date) }
    }

    /** Every stored day, keyed by date. See [observeAllPlans]. */
    fun observeAllLogs(): Flow<Map<DateString, JsonObject>> = dao.observeAllLogs().map { rows ->
        rows.associate { row -> row.date to parseObject(row.logJson) }
    }

    /**
     * The server's window start, or null before the first pull.
     *
     * The calendar refuses selection below it: there is no data down there and
     * the server would not accept a write for it either.
     */
    fun observeEarliestDate(): Flow<DateString?> = dao.observeMeta(CoachDao.KEY_EARLIEST_DATE)

    /** The scheduler's dirty hook. */
    suspend fun hasDirtyData(): Boolean = dao.countDirty() > 0

    /**
     * The client identity, minted once and cached for the process. The mutex
     * only serializes the first access; the DAO makes the insert race-safe even
     * across processes. `POST /register` is never called — the server registers
     * the client inside `POST /sync`.
     */
    suspend fun clientId(): String = cachedClientId ?: clientIdMutex.withLock {
        cachedClientId ?: dao.getOrCreateClientId(newClientId()).also { cachedClientId = it }
    }

    /**
     * The poll pre-check: is a full sync worth running?
     *
     * Dirty data short-circuits to yes. Otherwise one cheap `plans-version`
     * probe, and a version that is both present and different from the last one
     * **consumed** means something moved. A failed probe is a "no" — the next
     * tick tries again — but cancellation is **rethrown**, never swallowed into
     * a "no": that would report a scheduler shutdown as a healthy poll.
     *
     * The probed version is parked in [pendingPlansVersion], never retired
     * here. Answering a poll is not the same as having pulled the data.
     */
    suspend fun pollCheck(): Boolean {
        if (dao.countDirty() > 0) return true
        return try {
            val version = api.plansVersion().version
            if (!version.isNullOrEmpty() && version != lastKnownPlansVersion) {
                pendingPlansVersion = version
                true
            } else {
                false
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
    }

    // ---- mutators --------------------------------------------------------

    /**
     * Write content into one exercise entry of [date]'s log, creating the day
     * if it does not exist yet.
     *
     * The merge is tombstone-aware (see [withEntryUpdated]): writing over a
     * pending delete is a deliberate re-add and must discard the tombstone, or
     * the server would process the new data as a deletion.
     */
    suspend fun updateLog(date: DateString, exerciseKey: String, data: JsonObject) {
        mutateDay(date) { current -> withEntryUpdated(current, exerciseKey, data) }
    }

    /**
     * Read-modify-write one exercise entry, **inside the write transaction**.
     *
     * [updateLog] is only safe for writes that do not depend on what is already
     * there, because its content replaces whole keys. Anything that edits a
     * collection — one cell of the `sets` array, one item of `completed_items` —
     * has to read the current value first, and reading it from an observed
     * snapshot loses the race: two edits committed before Room re-emits both
     * build their replacement from the same stale array, and the second one
     * silently discards the first.
     *
     * [transform] therefore receives the entry **as stored at this instant** and
     * returns the content to merge, or null to write nothing at all. A pending
     * delete is presented as absent, matching what the screen shows for one; the
     * merge itself is still tombstone-aware and records the write as a re-add.
     */
    suspend fun transformLogEntry(
        date: DateString,
        exerciseKey: String,
        transform: (JsonObject?) -> JsonObject?,
    ) {
        mutateDay(date) { current ->
            val existing = (current[exerciseKey] as? JsonObject)?.takeUnless(::isDeletedEntry)
            val data = transform(existing) ?: return@mutateDay current
            withEntryUpdated(current, exerciseKey, data)
        }
    }

    /**
     * Turn one exercise entry into a pending-delete tombstone.
     *
     * A day that does not exist, or an entry key that is not in it, is a **full
     * no-op**: no write, nothing dirtied, no upload scheduled. The pure helper
     * signals "nothing to delete" by returning the same log instance and this
     * respects that. (The PWA deep-clones before calling, so it always stamps
     * and dirties an existing day even when the key was absent.)
     */
    suspend fun deleteLogEntry(date: DateString, exerciseKey: String) {
        mutateDay(date, createIfAbsent = false) { current -> withEntryDeleted(current, exerciseKey) }
    }

    /** Shallow-merge [feedback] into the day's `session_feedback`. */
    suspend fun updateSessionFeedback(date: DateString, feedback: JsonObject) {
        mutateDay(date) { current ->
            val merged = buildJsonObject {
                (current["session_feedback"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
                feedback.forEach { (key, value) -> put(key, value) }
            }
            JsonObject(current + ("session_feedback" to merged))
        }
    }

    /**
     * The one local-write path: transform the stored day, stamp it, mark the
     * date dirty and kick the scheduler.
     *
     * [transform] runs inside the write transaction on the day as stored.
     * Returning the same instance it was given means "nothing changed" and
     * skips the whole write, which is what makes [deleteLogEntry]'s no-op free.
     *
     * Both advisory stamps are written on every path, [updateSessionFeedback]
     * included — the PWA sets only `_lastModifiedAt` there, inconsistently with
     * its other two mutators. Neither field is arbitrated (the server ignores
     * both), so normalizing costs nothing and removes a trap.
     */
    private suspend fun mutateDay(
        date: DateString,
        createIfAbsent: Boolean = true,
        transform: (JsonObject) -> JsonObject,
    ) {
        // The lease opens BEFORE clientId(). That call is a DAO read and so a
        // suspension point: a mutation parked there while the switch closed
        // would otherwise resume and commit a dirty row into the wiped
        // database, belonging to the server the user just left.
        val changed = session.withWriteLease {
            val clientId = clientId()
            val stamp = clock()
            dao.updateLogAndMarkDirty(date) { storedJson ->
                val current = when {
                    storedJson != null -> parseObject(storedJson)
                    createIfAbsent -> emptyDay(stamp, clientId)
                    else -> return@updateLogAndMarkDirty null
                }
                val next = transform(current)
                if (next === current) return@updateLogAndMarkDirty null
                encode(stamped(next, stamp, clientId))
            }
        }
        if (!changed) return
        updateSyncStatus()
        scheduleUpload()
    }

    // ---- the sync cycle --------------------------------------------------

    /**
     * One full sync: upload what is dirty, then pull. The scheduler's `syncFn`.
     *
     * The step order is `store.js`'s and is not free to rearrange.
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
     * Full reconciliation: the ordinary upload-first cycle with a **pull that
     * carries no watermark**, applied as an authoritative snapshot.
     *
     * A separate entry point rather than a flag on [triggerSync] — see
     * `JournalSyncStore.forceSync` for why. Upload still goes first: the server
     * is the only arbiter, so sending local edits before asking for changes
     * means the answer already contains them, and the full pull that follows
     * can then be applied as truth without discarding anything unsent.
     *
     * Watermark regression is accepted and safe here for the same reason it is
     * on the journal side: a lower stamp only replays deliveries the protocol
     * already applies idempotently.
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
                val clientId = clientId()
                debugLog?.log(
                    TAG,
                    "force sync start",
                    buildJsonObject { put("dirtyDates", dao.countDirty()) },
                )

                val uploaded = uploadDirtyLogs(clientId)
                downloadServerChanges(clientId, watermark = null, fullSnapshot = true)

                debugLog?.log(TAG, "force sync complete", buildJsonObject { put("uploaded", uploaded) })
                updateSyncStatus()
                return ForceSyncModuleResult.Success(ForceSyncCounts.Coach(uploaded))
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

    /**
     * Block until no sync cycle is in flight — the server switch's drain. See
     * `JournalSyncStore.awaitQuiescence`.
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
            val watermark = dao.getMeta(CoachDao.KEY_LAST_SERVER_SYNC_TIME)
            debugLog?.log(
                TAG,
                "sync start",
                buildJsonObject {
                    put("dirtyDates", dao.countDirty())
                    put("hasWatermark", watermark != null)
                },
            )

            uploadDirtyLogs(clientId)
            downloadServerChanges(clientId, watermark)

            // Dirty-aware, not unconditionally green: a date re-modified
            // mid-sync is still pending and the dot has to say so.
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
     * Step 1: send the dirty days. Returns how many dates actually went on the
     * wire — the number the force-sync dialog reports.
     *
     * The payload, the generations it was built at, and the exact JSON each day
     * held all come out of one read transaction — see [PendingUpload] for why
     * they cannot be gathered separately.
     */
    private suspend fun uploadDirtyLogs(clientId: String): Int {
        val pending = dao.buildPendingUpload(::buildPendingUpload)
        if (pending.generations.isEmpty()) return 0

        // Dates that can never upload are dropped from the dirty set BEFORE the
        // POST. Leaving them would wedge the client red and re-sync every poll.
        if (pending.unsatisfiableDates.isNotEmpty()) {
            debugLog?.log(
                TAG,
                "dropping unsatisfiable dirty dates",
                buildJsonObject { putJsonArray("dates") { pending.unsatisfiableDates.forEach { add(it) } } },
            )
            dao.clearDirty(clearsFor(pending.unsatisfiableDates, pending))
        }

        // Every dirty date was unsatisfiable: there is nothing to say. The PWA's
        // `triggerSync` still POSTs an empty `logs` map here; its own forceSync
        // guards against it, and we adopt the guarded form everywhere.
        if (pending.payload.isEmpty()) return 0

        debugLog?.log(
            TAG,
            "upload attempt",
            buildJsonObject { put("dates", pending.payload.size) },
        )
        val response = api.syncPost(clientId, pending.payload)
        applyUploadResults(response, pending)
        return pending.payload.size
    }

    /** Runs inside the DAO's read transaction. See [PendingUpload]. */
    private fun buildPendingUpload(rows: List<CoachLogEntity>): PendingUpload {
        val logs = rows.associate { it.date to parseObject(it.logJson) }
        val selected = selectLogsToUpload(rows.map { it.date }, logs)
        return PendingUpload(
            generations = rows.associate { it.date to it.dirtyGeneration },
            payload = selected.logsToUpload,
            unsatisfiableDates = selected.unsatisfiableDates,
        )
    }

    /**
     * Adopt the reconciled days and clear the dates that were actually sent —
     * one transaction.
     *
     * Whether a date is adopted wholesale or only has its tokens advanced is
     * decided against the generations read *inside* that transaction, compared
     * to the ones the payload was built at. A date edited in the meantime keeps
     * its content.
     */
    private suspend fun applyUploadResults(response: CoachSyncPostResponseDto, pending: PendingUpload) {
        val resultDates = response.results.keys.toList()
        // The lease spans the write; see [ServerSessionGate].
        session.withWriteLease {
            dao.applyUploadResults(
                dates = resultDates,
                clears = clearsFor(pending.payload.keys, pending),
                adopt = { currentRows ->
                    val next = adoptUploadResults(
                        localLogs = currentRows.associate { it.date to parseObject(it.logJson) },
                        results = response.results,
                        snapshotGens = pending.generations,
                        dirtyDateGenerations = currentRows.associate { it.date to it.dirtyGeneration },
                        uploadedLogs = pending.payload,
                    )
                    resultDates.mapNotNull { date -> next[date]?.let { logEntity(date, it) } }
                },
            )
        }
        debugLog?.log(TAG, "upload applied", buildJsonObject { put("dates", resultDates.size) })
    }

    /**
     * Which of [appliedDates] may have their dirty flag cleared, and at which
     * generation. The pure rule picks the candidates; the SQL guard inside the
     * transaction decides atomically whether each one still qualifies.
     */
    private fun clearsFor(
        appliedDates: Collection<DateString>,
        pending: PendingUpload,
    ): List<CoachDirtyClear> {
        val remaining = nextDirtyAfterApply(
            appliedDates = appliedDates,
            snapshotGens = pending.generations,
            dirtyDates = pending.generations.keys.toList(),
            dirtyDateGenerations = pending.generations,
        )
        return (pending.generations.keys - remaining.keys.toSet())
            .map { CoachDirtyClear(it, pending.generations.getValue(it)) }
    }

    /**
     * Step 2: pull, and apply in one transaction.
     *
     * Uploaded dates were cleared a moment ago, so they no longer count as
     * dirty here and the server's copy of them wins — which is exactly right,
     * because that copy is the merged day this cycle just adopted. (The PWA
     * clears after the pull instead; the outcome is the same and the ordering
     * is pinned by a test.)
     *
     * [fullSnapshot] switches the plan half from incremental to authoritative.
     * The server only computes `deletedPlanDates` relative to a `last_sync_time`
     * it was given; a watermark-free pull therefore reports no deletions at
     * all, and a plan the server dropped would survive every "full
     * reconciliation" the user ever ran. So on the force path the response's
     * plan set *is* the truth and anything else local is removed. Logs are not
     * treated that way and must not be: a locally-created dirty day has no
     * server copy yet by definition.
     */
    private suspend fun downloadServerChanges(
        clientId: String,
        watermark: SyncStamp?,
        fullSnapshot: Boolean = false,
    ) {
        val data = api.sync(clientId, watermark)
        debugLog?.log(
            TAG,
            "download success",
            buildJsonObject {
                put("plans", data.plans.size)
                put("logs", data.logs.size)
                put("deletedPlans", data.deletedPlanDates.size)
                put("fullSnapshot", fullSnapshot)
            },
        )

        session.withWriteLease {
            dao.applyDownload(
                plans = data.plans.map { (date, plan) -> planEntity(date, plan) },
                deletedPlanDates = data.deletedPlanDates,
                logs = data.logs.map { (date, log) -> logEntity(date, log) },
                earliestDate = data.earliestDate,
                watermark = requireWatermark(data.serverTime),
                fullSnapshotPlans = fullSnapshot,
            )
        }

        // Consume the version only now that the pull has landed. Both halves
        // matter: the plans in this response, and whatever `plans-version` the
        // poll probed — that endpoint aggregates log writes and log-entry
        // deletions too, which no plan `_lastModified` can account for, so
        // committing only the plan-derived max would leave the probed value
        // unretired and re-sync on every tick.
        lastKnownPlansVersion = laterStamp(
            maxPlanVersion(data.plans, lastKnownPlansVersion),
            pendingPlansVersion,
        )
        pendingPlansVersion = null
    }

    private suspend fun updateSyncStatus() {
        val dirty = dao.countDirty()
        _syncStatus.value = when {
            !isOnline() -> SyncStatus.GRAY
            dirty > 0 -> SyncStatus.RED
            else -> SyncStatus.GREEN
        }
    }

    // ---- row <-> JSON ----------------------------------------------------

    /**
     * The single place a plan row is built, which is what keeps [planJson] and
     * its projected `lastModified` column in step. The column is what the
     * version comparison reads; a JSON stamp that had moved without it would
     * silently stop triggering pulls.
     */
    private fun planEntity(date: DateString, plan: JsonObject): CoachPlanEntity = CoachPlanEntity(
        date = date,
        planJson = encode(plan),
        lastModified = (plan["_lastModified"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
    )

    private fun logEntity(date: DateString, log: JsonObject): CoachLogEntity = CoachLogEntity(
        date = date,
        logJson = encode(log),
        isDirty = false,
        dirtyGeneration = 0L,
    )

    /** A day that exists but holds nothing yet — the shape every mutator starts from. */
    private fun emptyDay(stamp: SyncStamp, clientId: String): JsonObject = buildJsonObject {
        put("session_feedback", JsonObject(emptyMap()))
        put("_lastModifiedAt", stamp)
        put("_lastModifiedBy", clientId)
    }

    /** The advisory "who touched this day, and when" stamps. Never arbitrated. */
    private fun stamped(log: JsonObject, stamp: SyncStamp, clientId: String): JsonObject = JsonObject(
        log + ("_lastModifiedAt" to JsonPrimitive(stamp)) + ("_lastModifiedBy" to JsonPrimitive(clientId)),
    )

    private fun parseObject(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun encode(value: JsonObject): String = json.encodeToString(JsonObject.serializer(), value)

    /**
     * Narrow on purpose: only a malformed or reshaped blob yields null. A blanket
     * `Exception` catch would also swallow cancellation and stall the collector.
     */
    private fun decodePlan(text: String, date: DateString): PlanDto? = try {
        json.decodeFromString(PlanDto.serializer(), text)
    } catch (_: SerializationException) {
        debugLog?.log(TAG, "plan decode failed", buildJsonObject { put("date", date) })
        null
    }

    /**
     * The pull's watermark, or the cycle fails.
     *
     * There is deliberately no device-clock fallback. The watermark is a
     * *server* instant that the next pull uses as `last_modified > since`; a
     * device clock running ahead would advance it past changes the server has
     * not delivered yet, and those changes would never be pulled again — silent
     * data loss, dressed up as a successful sync. Failing instead costs one
     * retry: the watermark stays put, and the server's equality-accepting
     * arbitration makes the eventual re-upload a no-op.
     */
    private fun requireWatermark(serverTime: SyncStamp?): SyncStamp = serverTime
        ?: error("coach pull response had no serverTime")

    /** The later of two server stamps, compared lexically. */
    private fun laterStamp(first: SyncStamp?, second: SyncStamp?): SyncStamp? = when {
        first == null -> second
        second == null -> first
        else -> if (first >= second) first else second
    }
}
