# Spec: Journal Data + Sync (Phase 2)

Status: **v3 — spec + code both Codex-reviewed; implemented. User pre-authorized Phase 2 ("complete independently, same pipeline"); morning review pending**

> **v3 protocol clarifications** (from the code-review fix round):
> - **Stamps always land; adoptions are refused when the row moved on.** Accepted-stamp application touches only the token (in-transaction re-read, content and generation untouched) and is unconditional — refusing it would leave a stale base token and turn the next upload into a rejection that loses the mid-sync edit anyway (the PWA stamps unconditionally for this reason). Rejected-serverRow *adoptions* replace content, so they are generation-guarded and skipped when the row changed after the upload body was built.
> - **The adoption/settled-delete guard compares against the generation each row had at upload-body build time**, not the step-2 snapshot (a normalized-mid-cycle tracker is dirty at build but absent from the snapshot). The step-2 snapshot still solely governs dirty *clears*.
> - **Settled local deletes** (accepted, or rejected-with-deleted-serverRow) are cleaned up inside the response transaction; the step-8 standalone prune only ever removes `deleted=1 AND isDirty=0` rows.
> - **A rejected local delete drops the delete intent** — adoption takes the serverRow whole (`deleted=false`), exactly as the PWA replaces the object outright; preserving the flag would combine with the settled-prune to delete a tracker the server still has.

## Goal

The first real bidirectional sync: journal trackers and entries stored in Room with dirty-generation tracking, synced against the dev server (`http://pop-os.tailexample.ts.net:9001/wellness`, synthetic data — user-confirmed) via the PWA's exact protocol. Ends demonstrable: the Journal tab lists live trackers and an entry toggle round-trips to the server.

Porting sources (behavior is theirs):
- `~/dev/health/wellness/public/js/journal/sync-logic.js` (+ `test/js/journal-sync-logic.test.js` — transcribe 1:1)
- `~/dev/health/wellness/public/js/journal/store.js` — mutators (`addTracker`/`updateTracker`/`deleteTracker`/`updateEntry`), `pullServerChanges`, `triggerSync` (lines 610-719: the canonical order), `pruneOldLogs`, `dropDeletedTrackerIds`, `updateSyncStatus`
- `~/dev/health/wellness/public/js/journal/utils.js` — `normalizeTrackerSchedule` only (legacy `frequency`/`weeklyDay` → `scheduleHistory`); the rest of utils is Phase 3
- `~/dev/health/wellness/docs/ARCHITECTURE.md` — journal sync protocol section
- Server truth: `~/dev/health/wellness/src/modules/journal.py` (`_TRACKER_RESERVED_KEYS`, wire shapes, `sync_arbitration.py`)

Out of scope (deferred): `forceSync` (Phase 8 Tools), full journal UI (Phase 3), WorkManager background flush (revisit at Phase 2 close — see Open Points).

### Declared deviations from the PWA (intentional)
1. **Atomic token-apply + dirty-clear**: accepted/rejected application, dirty clearing (generation-checked), and the watermark advance happen in ONE Room transaction. The PWA orders separate IndexedDB writes to fail dirty-side; Room makes the crash window vanish. Strengthening, not drift.
2. **Delta application is one transaction** (config upsert + deletedTrackers cleanup + entry apply + watermark). Idempotent re-delivery (2 s watermark overlap) makes partial application safe anyway; the transaction is strictly cleaner.
3. **No Preact-signal write-minimization**: the JS returns same-references to skip signal writes; Room Flows conflate on their own. The pure ports keep the same return *contracts* (null = no change) so the JS tests transcribe cleanly.
4. **The 7-day prune never deletes dirty rows.** The PWA's `pruneOldLogs` deletes old entries unconditionally — a re-modified old entry could be pruned while still dirty (unreachable in the PWA UI, whose date strip can't edit >7-day-old days, but reachable in principle). We exclude `isDirty=1` rows from the prune; they get pruned on a later cycle once uploaded. Strictly safer.

## API / Interface

All in `:core:data` under `journal/` (`dev.jtiisto.wellness.core.data.journal.*`) unless noted.

### DTOs (`journal/JournalDtos.kt`, `journal/TrackerDto.kt`)

```kotlin
@Serializable(with = TrackerDtoSerializer::class)
data class TrackerDto(
    val id: String,
    val name: String? = null,
    val category: String? = null,
    val type: String? = null,               // simple|quantifiable|evaluation|note (server default simple)
    val lastModifiedAt: SyncStamp? = null,
    val deleted: Boolean? = null,           // server-side flag (rejected serverRow only — delta config never contains deleted trackers; those arrive as deletedTrackers ids)
    val scheduleHistory: List<ScheduleSegmentDto>? = null,
    val polarity: String? = null,           // positive|negative|neutral
    val targetHistory: List<TargetSegmentDto>? = null,
    val extras: JsonObject = JsonObject(emptyMap()),  // meta_json passthrough: unit, defaultValue, accumulator, legacy frequency/weeklyDay, anything future
)
@Serializable data class ScheduleSegmentDto(val effectiveFrom: DateString, val days: List<Int>)  // 0=Sun..6=Sat; [] = paused
@Serializable data class TargetSegmentDto(val effectiveFrom: DateString, val target: TargetDto? = null)
@Serializable data class TargetDto(val min: Double? = null, val max: Double? = null)
```
**`TrackerDtoSerializer`** (hand-written): typed keys above map to fields; **every other key round-trips through `extras`** — EXCEPT the server's reserved set, which is dropped on decode and never emitted from extras: mirror `_TRACKER_RESERVED_KEYS` (`journal.py`): `id,name,category,type,lastModifiedAt,deleted,scheduleHistory,polarity,target,targetHistory,_version,_baseVersion,_baseLastModifiedAt,_lastModifiedBy,_lastModifiedAt,_deleted`. Encode writes only non-null typed fields then merges extras. Law test: decode→encode deep-equals every golden tracker (after reserved-key stripping is accounted for).

```kotlin
@Serializable
data class EntryDto(
    val value: JsonElement? = null,     // number | string | null — polymorphic, kept as JSON scalar
    val completed: Boolean? = null,
    val lastModifiedAt: SyncStamp? = null,
)

@Serializable data class DeltaResponseDto(
    val config: List<TrackerDto> = emptyList(),
    val days: Map<DateString, Map<String, EntryDto>> = emptyMap(),
    val deletedTrackers: List<String> = emptyList(),
    val serverTime: SyncStamp? = null,
)
@Serializable data class AcceptedTrackerDto(val id: String, val lastModifiedAt: SyncStamp)
@Serializable data class AcceptedEntryDto(val date: DateString, val trackerId: String, val lastModifiedAt: SyncStamp)
@Serializable data class RejectedTrackerDto(val id: String, val errorKind: String? = null, val serverRow: TrackerDto? = null)
@Serializable data class RejectedEntryDto(val date: DateString, val trackerId: String, val errorKind: String? = null, val serverRow: EntryDto? = null)
@Serializable data class UpdateResponseDto(
    val serverTime: SyncStamp? = null,
    val acceptedTrackers: List<AcceptedTrackerDto> = emptyList(),
    val acceptedEntries: List<AcceptedEntryDto> = emptyList(),
    val rejectedTrackers: List<RejectedTrackerDto> = emptyList(),
    val rejectedEntries: List<RejectedEntryDto> = emptyList(),
)
```
**Upload payload is built as `JsonObject`** (not typed DTOs): `{clientId, config: [tracker-with-_baseLastModifiedAt-minus-lastModifiedAt...], days: {date: {trackerId: {value, completed, _baseLastModifiedAt?}}}}`.
**Explicit-null discipline**: the PWA sends `"value": null` / `"completed": null` explicitly on entry uploads (`data = {value, completed}` survives JSON.stringify). Our upload constructs entry objects via `buildJsonObject` putting `value` and `completed` ALWAYS (as `JsonNull` when null) — `explicitNulls=false` must not silently omit them.
**Tracker upload construction**: start from the encoded server-form tracker (`dataJson`), remove top-level `lastModifiedAt` (protocol-reserved; never echo), add `_baseLastModifiedAt` iff a token exists, and **inject `"_deleted": true` when the entity's local `deleted` column is set** — `_deleted` lives ONLY in the entity column, never inside `dataJson`/extras, so without this injection a local delete would upload as a plain upsert (the server reads `item.get("_deleted", False)`).

### API (`network/JournalApi.kt` — extend)
```kotlin
suspend fun syncDelta(clientId: String, since: SyncStamp?): DeltaResponseDto
// GET /api/journal/sync/delta?client_id=…[&since=…]  — omit `since` entirely on first sync
// Request headers: Cache-Control: no-store, Pragma: no-cache  (closes the Phase 1 deferral)
suspend fun syncUpdate(payload: JsonObject): UpdateResponseDto
// POST /api/journal/sync/update
```

### Room v1 → v2 (migration + committed schema 2.json + instrumented migration test)
```kotlin
@Entity(tableName = "journal_trackers")
data class JournalTrackerEntity(
    @PrimaryKey val id: String,
    val name: String?, val category: String?, val type: String?,   // projections for querying/grouping
    val deleted: Boolean,          // LOCAL soft-delete pending upload (_deleted)
    val lastModifiedAt: SyncStamp?,
    val dataJson: String,          // canonical server-form tracker JSON incl. lastModifiedAt + extras (source of truth)
    val isDirty: Boolean, val dirtyGeneration: Long,
)
@Entity(tableName = "journal_entries", primaryKeys = ["date", "trackerId"])
data class JournalEntryEntity(
    val date: DateString, val trackerId: String,
    val valueJson: String?,        // JSON-encoded scalar: preserves number|string|null
    val completed: Boolean?, val lastModifiedAt: SyncStamp?,
    val isDirty: Boolean, val dirtyGeneration: Long,
)
@Entity(tableName = "journal_meta")
data class JournalMetaEntity(@PrimaryKey val key: String, val value: String)  // clientId, lastServerSyncTime
```
**Consistency invariant (load-bearing)**: `dataJson` includes `lastModifiedAt`; the `lastModifiedAt`/`name`/`category`/`type` columns are projections. EVERY write — server upsert, accepted-stamp application, rejected-serverRow adoption, normalization, local mutation — rewrites `dataJson` AND all projected columns in the same transaction. A stamp written only to the column would leave the next upload reading a stale base token out of `dataJson`. `_deleted` never appears inside `dataJson`; local soft-delete lives in the `deleted` column only (injected into the upload payload at build time).

**journal_meta bootstrap**: `getOrCreateClientId()` is a `@Transaction` insert-if-absent (`INSERT OR IGNORE` then `SELECT`) so a raced first access can never mint two ids; the watermark key starts absent (first sync = full pull, `since` omitted).

**DAO surface** (`JournalDao` — abstract class, faked in JVM tests like `DebugLogTest`'s pattern):
- mark: `UPDATE … SET isDirty=1, dirtyGeneration=dirtyGeneration+1 WHERE id=:id` (tracker) / `WHERE date=:date AND trackerId=:trackerId` (entry) — same transaction as the row upsert.
- snapshot: `SELECT id, dirtyGeneration` / `SELECT date, trackerId, dirtyGeneration` `WHERE isDirty=1`.
- clear-if-unchanged: per-key `UPDATE … SET isDirty=0 WHERE key=:key AND dirtyGeneration=:snapshotGen`, executed **only for resolved keys present in the snapshot** (a resolved key absent from the snapshot — e.g. normalized mid-cycle — is skipped and stays dirty; never pass a default generation).
- reads the store needs: dirty tracker rows (full: `deleted`, `dataJson`, `lastModifiedAt`, generation), dirty entry rows (full payload fields + generation), all trackers incl. locally-deleted (normalization + prune inputs), single entry / day map, meta get/upsert (+ `getOrCreateClientId` transaction).
- writes: transactional server-config upsert, tracker+entries+dirty deletion (explicit DAO deletes — no FK cascade is declared), entry prune (`WHERE isDirty=0 AND date NOT IN window`), response application (stamps + rejected rows + clears + watermark in one `@Transaction`).
- Flows: `observeTrackers()` (non-deleted, for UI), `observeDay(date)`, `observeDirtyCounts()` (for status).

### Pure logic (`journal/JournalSyncLogic.kt`) — 1:1 port of `sync-logic.js`
Operates on lists/maps of DTO-level values (`TrackerDto`, entry maps keyed `"date|trackerId"`), mirroring the JS signatures so `journal-sync-logic.test.js` transcribes directly:
- `computeNormalizedConfig(config, locallyDeletedIds: Set<String>)` → `NormalizedConfig(config, changedIds)?` (null = converged). The JS skips `_deleted` trackers; our local-delete state lives in the entity column, so it arrives as the explicit `locallyDeletedIds` parameter — DTOs alone cannot express it. Includes the `normalizeTrackerSchedule` port: only touches trackers with legacy `frequency`/`weeklyDay` **in extras**; removes both; adds genesis `scheduleHistory=[{effectiveFrom: "0000-01-01", days:[weeklyDay]}]` iff no existing history AND frequency=="weekly" AND weeklyDay ∈ 0..6.
- `computeUploadPayload(clientId, dirtyTrackers, dirtyEntries)` → `(payload: JsonObject, dirtyTrackerIds, dirtyEntryKeys)` — skips dirty ids with no local row (JS `find` miss ⇒ `continue`).
- `computeAcceptedApply`, `computeRejectedApply` (returns `trackerIdsToDelete` for soft-deleted serverRows — routed through the drop-deleted cleanup), `computeDropDeletedTrackers` (incl. dirty purge + generation cleanup, `logsChanged`/`dirtyChanged` contract), `computePruneDeletedTrackers(config, locallyDeletedIds, logs)` (null = nothing to prune; the JS filters `t._deleted` — our flag is the entity column, passed in explicitly, same as normalization).
- `computeClearedDirtyState` reuses Phase 1 `DirtySetLogic.clearApplied` twice (trackers + entries) — do not reimplement.
Entry keys use the PWA's `"date|trackerId"` composite format (split on first `|`; tracker ids never contain `|` — generated UUIDs).

### Store (`journal/JournalSyncStore.kt`) — port of the store.js sync surface
```kotlin
class JournalSyncStore(dao, api, debugLog, json, scope, isOnline: () -> Boolean, clock: () -> SyncStamp /* advisory fallback only */) {
    val syncStatus: StateFlow<SyncStatus>            // GREEN/RED/GRAY per updateSyncStatus port
    val isSyncing: Boolean                            // scheduler's isSyncing hook
    suspend fun hasDirtyData(): Boolean               // scheduler's dirty hook
    fun observeTrackers(): Flow<List<TrackerDto>>     // decoded, non-deleted
    fun observeDay(date: DateString): Flow<Map<String, EntryDto>>
    // Mutators (each: Room transaction upsert+markDirty, then scheduler.scheduleUpload()):
    suspend fun addTracker(tracker: TrackerDto); suspend fun updateTracker(id, updates)
    suspend fun deleteTracker(id: String)             // soft-delete: deleted=1 + dirty
    suspend fun updateEntry(date, trackerId, value: JsonElement?, completed: Boolean?)
    suspend fun triggerSync(): SyncResult             // the scheduler's syncFn
}
```
`clientId`: `getOrCreateClientId()` (race-safe, see journal_meta bootstrap). `/sync/register` is never called (PWA parity).
**Mutator transactions** (each one Room transaction, then `scheduleUpload()`): `addTracker`/`updateTracker` re-encode the DTO → `dataJson` + all projected columns + mark-dirty; `updateEntry` upserts `valueJson`/`completed` (existing `lastModifiedAt` preserved) + mark-dirty; `deleteTracker` sets `deleted=1` + mark-dirty and **keeps `dataJson` and `lastModifiedAt` intact** — the deletion upload needs the base token.

### Wiring
- `SyncOrchestrator` gains the journal `SyncScheduler` (built in Koin: `syncFn = store::triggerSync`, no pollCheck — journal full-syncs each tick, PWA parity) — registered at `WellnessApplication` startup.
- Journal tab (app module, `ui/journal/`): Phase 2 **debug screen**, not the real UI — tracker list (name, category, type), today's `completed` checkbox per tracker (→ `updateEntry`), the sync status dot, a manual "Sync now" button (→ `scheduler.requestSync()`). Phase 3 replaces it.
- `core/ui`: `SyncStatusDot(status)` composable (GREEN/RED/GRAY) — shared with Phase 3+.

## Behavior — `triggerSync` (exact order of store.js:610-719)

1. If offline → status GRAY, return `SyncResult(success=false, reason=OFFLINE)`.
2. Set syncing; **snapshot dirty generations BEFORE any network call** (SQL snapshot).
3. **Pull** `syncDelta(clientId, lastServerSyncTime)` (absent watermark ⇒ no `since` param ⇒ full pull). Apply in one transaction:
   - config: upsert each server tracker UNLESS locally dirty (dirty rows untouched — server-newer edits lose to local dirt until upload arbitration);
   - `deletedTrackers` → drop trackers + their entries + their dirty state and generations (never wedge red);
   - entries: apply each server entry UNLESS its `date|trackerId` is locally dirty;
   - watermark ← `serverTime`. (The server always emits `serverTime`; the client-clock fallback exists ONLY as malformed-response recovery, never expected operation.)
   Then run `computeNormalizedConfig` over the fresh config; changed trackers are marked dirty. **They upload this same cycle, but their dirty flags clear only NEXT cycle**: normalization runs after the step-2 snapshot, so the resolved-key clear (snapshot-gated) skips them — one harmless re-upload, PWA-identical semantics.
4. If no dirty rows now → status update, return success (no upload).
5. **Upload**: rebuild payload from CURRENT rows (post-pull tokens), `syncUpdate(payload)`.
6. **Pure computation only — no Room writes**: compute accepted-stamp application and rejected-serverRow adoption (upsert; soft-deleted serverRow → collect for drop-deleted cleanup). `errorKind` is logged, not branched on.
7. Resolved set = accepted ∪ rejected (both are settled). **The single mutating transaction** (deviation 1): stamped rows + rejected adoptions + soft-delete cleanup (trackers, entries, dirty state) + per-key dirty clear iff generation == snapshot (snapshot-present keys only) + watermark ← upload `serverTime`.
8. Prune: non-dirty entries outside the 7-day window (port `isWithinLastNDays` from `shared/utils.js` VERBATIM; boundary pinned by test — never re-derive); locally-soft-deleted trackers (+ their entries).
9. Update status; return success. Any thrown error → status RED, return `SyncResult(success=false, error=e)` (scheduler classifies network-vs-server via `isNetworkError` = Ktor IO/timeout exceptions).

Status derivation (`updateSyncStatus`): offline → GRAY; dirty>0 → RED; watermark present → GREEN; else GRAY.

## Golden fixtures (`testdata/golden/journal/`)

Generated from the dev server (localhost:9001 — synthetic data, user-confirmed 2026-08-06). Create fixture rows via the API itself with ids/names prefixed `fixture-` (never touch existing dev rows beyond reading). Capture verbatim JSON:
`delta-full.json`, `delta-incremental.json`, `update-request.json`, `update-response-accepted.json`, `update-response-rejected.json` (rejected = re-send a stale `_baseLastModifiedAt`), plus `tracker-legacy.json` — the legacy fixture is NOT hand-written: upload a legacy-shaped tracker (`frequency`/`weeklyDay` in the body) to the dev server and capture its delta response, so the fixture is what the server actually round-trips.
Tests: every fixture decodes; every tracker in them round-trips decode→encode deep-equal (modulo documented reserved-key drops); the update-request fixture regenerates byte-identically from `computeUploadPayload` given the fixture inputs.

## Tests

| Test class | Pins |
|---|---|
| `JournalSyncLogicTest` (:core:data, JVM) | `journal-sync-logic.test.js` transcribed 1:1 (all compute* functions incl. normalize cases) |
| `TrackerDtoSerializerTest` | reserved-key handling, extras passthrough, round-trip law over golden fixtures, legacy-fields-in-extras |
| `JournalSyncStoreTest` (JVM, fake DAO + MockEngine) | full triggerSync flows: first-sync full pull; incremental; dirty-skip on pull; deletedTrackers purge incl. dirty; upload accepted (tokens stamped into dataJson AND column, dirty cleared); mid-sync edit keeps dirty (generation check); rejected serverRow adoption; rejected soft-delete cleanup removes tracker + its entries + tracker AND entry dirty state; no-dirty short-circuit; offline result; upload JSON literally contains `"value": null` / `"completed": null` (raw body assertion, not decode-back); local delete uploads `"_deleted": true` with base token; dirty old entry SURVIVES the 7-day prune; normalized-mid-cycle tracker stays dirty after same-cycle acceptance and clears next cycle; `_deleted` prune after sync |
| `JournalApiTest` (extend) | delta URL with/without `since` (+ no-store headers), update POST decode, exact URL prefix |
| `JournalDaoTest` (instrumented, emulator sessions) | dirty SQL ops: mark bumps generation; snapshot; clear-iff-generation-match; migration 1→2 (`MigrationTestHelper`, committed schemas) |
| Fixture suite | decode + round-trip laws as above |

## Dependencies

No new libraries. Consumes Phase 1: `SyncScheduler`, `DirtySetLogic`, `DebugLog`, `WellnessJson`, `SyncOrchestrator`, `ConnectivityMonitor`, `ServerConfig`/`buildHttpClient`.

## Open Points (decided autonomously; user review in the morning)

1. **WorkManager one-shot flush on backgrounding-with-dirty-data** — plan.md assigns it "Phase 2, when dirty data first exists". Deferred to Phase 2b/3: the debounce (2.5 s) makes the window tiny and the morning demo doesn't exercise process death. Flagged so it isn't forgotten.
2. Journal scheduler has **no pollCheck** (PWA parity — journal full-syncs every 30 s poll). Accepted; the delta with `since` is cheap.
3. Server error toasts remain debug-log-only until Phase 3 (per Phase 1 decision).
