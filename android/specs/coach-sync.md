# Spec: Coach Data + Sync (Phase 4)

Status: **approved 2026-08-07** (v2 after Codex review; user-approved incl. dev-server fixture writes; pipeline running)

## Goal

Coach joins the sync engine: workout plans (client-read-only) and day logs stored in Room as whole-day JSON blobs, synced upload-first with per-record server-token arbitration, tombstones with the re-add/resurrection machinery, the 60-day window, and the cheap `plans-version` poll pre-check. Ends demonstrable: a debug Coach screen renders today's plan from the dev server and a set logged on the phone lands server-side.

Porting sources (behavior is theirs):
- `~/dev/health/wellness/public/js/coach/sync-logic.js` (273 lines, pure) + `test/js/coach-sync-logic.test.js` (**27 cases — transcribe 1:1**)
- `~/dev/health/wellness/public/js/coach/store.js` — mutators (`updateLog`, `deleteLogEntry`, `updateSessionFeedback`), `triggerSync` (upload-first, lines 304-472), pollCheck (274-289), dirty tracking, `updateSyncStatus`
- Server truth: `~/dev/health/wellness/src/modules/coach.py` (`_store_log` 550-727, arbitration + tombstone lifecycle + resurrection guard), `coach_logs.py` (`assemble_log` lean shape, `AD_HOC_LOG_SLUGS`), `coach_plans.py` (`assemble_plan`), `sync_arbitration.py`
- `docs/ARCHITECTURE.md` coach section (edge-case checklist; note its "30 days" is stale — code says `SYNC_WINDOW_DAYS = 60`)
- `public/js/coach/utils.js`: ONLY `EXTRA_SESSION_KEY = "extra_zone2"` (rest is Phase 5)

Out of scope: coach UI beyond the debug screen (Phase 5), workout hooks (Phase 5), `forceSync` (Phase 8), typed set-entry editing UX (Phase 5).

### Declared deviations (intentional; all else 1:1)
1. **Empty-upload guard**: the PWA's `triggerSync` POSTs `logs:{}` when every dirty date is unsatisfiable; its `forceSync` guards. We adopt the guarded form everywhere (skip the POST, still clear unsatisfiable dates).
2. **`updateSessionFeedback` sets `_lastModifiedBy` too** — the PWA sets only `_lastModifiedAt` there, unlike its other two mutators; both fields are advisory, we normalize.
3. **Atomic post-upload write**: adoption + generation-guarded dirty clears in ONE Room transaction (the PWA orders IndexedDB writes: logs before metadata). **The upload transaction NEVER writes the pull watermark** — POST `serverTime` is an ordinary write timestamp without the GET's 2 s overlap; persisting it could make the next incremental pull skip concurrent changes. Only `applyDownload` writes `lastServerSyncTime`, from GET `serverTime`. Timing note vs the PWA: because uploaded dates are cleared before the GET, they no longer count as dirty during the download merge (the PWA clears after); safe — the merged server day was just adopted — and pinned by a test.
4. **Re-modification checks compare transaction-current generations** (journal Phase 2 pattern), and the **upload is built atomically**: `PendingUpload` (each date's exact uploaded JSON + its generation + the exact `withBaseTokens` payload used for F4 verdict detection) comes from ONE Room read transaction — a JSON read and a generation read from different moments could pair an old blob with a new generation and adopt over a fresh edit. This is the native equivalent of the JS's uninterrupted single-threaded build block.
5. `lastKnownPlansVersion` stays **in-memory** (PWA parity): every cold start's first poll triggers one full sync — also a correctness safety net. Persisting is a possible later optimization, deliberately not taken.
6. **`deleteLogEntry` on a day whose entry key is missing is a full no-op** (no dirty, no stamps): the pure helper returns the same reference, and we respect that identity. The PWA's wrapper deep-clones first, so it always stamps and dirties when the day exists — ours is strictly cleaner.

## API / Interface

All in `:core:data` under `coach/` unless noted.

### Room v2 → v3 (migration + committed schema 3.json + instrumented migration test)
```kotlin
@Entity(tableName = "coach_plans")
data class CoachPlanEntity(@PrimaryKey val date: DateString, val planJson: String,
                           val lastModified: SyncStamp?)   // the plan's _lastModified
@Entity(tableName = "coach_logs")
data class CoachLogEntity(@PrimaryKey val date: DateString, val logJson: String,
                          val isDirty: Boolean, val dirtyGeneration: Long)
@Entity(tableName = "coach_meta")
data class CoachMetaEntity(@PrimaryKey val key: String, val value: String)
// keys: clientId, lastServerSyncTime, earliestDate
```
`logJson` holds the whole wire day (arbitrary exercise keys + `_`-meta + `session_feedback`) verbatim as `JsonObject` text — the wire unit IS the day; never normalized into rows. Plans are server-authoritative blobs; `lastModified` is a projection of the JSON's `_lastModified` (same-transaction invariant as journal's projections).

`CoachDao` (abstract class, JVM-faked): upsert/observe/get for plans+logs, per-date dirty ops (mark = generation bump; snapshot; generation-guarded clear — the journal SQL pattern), `applyDownload(...)` transaction (plans overwrite, deletedPlanDates removal, log merge skipping dirty dates, earliestDate + watermark meta, prune `< earliestDate` both maps), `applyUploadResults(...)` transaction (see Behavior 4), meta get/upsert + race-safe `getOrCreateClientId`.

### DTOs / wire (`coach/CoachDtos.kt`)
```kotlin
@Serializable data class CoachSyncResponseDto(
    val plans: Map<DateString, JsonObject> = emptyMap(),
    val logs: Map<DateString, JsonObject> = emptyMap(),
    val serverTime: SyncStamp? = null,
    val earliestDate: DateString? = null,
    val deletedPlanDates: List<DateString> = emptyList(),
)
@Serializable data class CoachSyncPostResponseDto(
    val success: Boolean = false,
    val results: Map<DateString, JsonObject> = emptyMap(),
    val serverTime: SyncStamp? = null,
)
@Serializable data class PlansVersionDto(val version: String? = null)
```
Logs and plan blobs stay `JsonObject` end-to-end on the sync path — stored losslessly as opaque JSON, never normalized. **Typed plan rendering DTOs** (read-only, `ignoreUnknownKeys`, decode-on-read for UI): `PlanDto(sessionId, dayName?, location?, phase?, totalDurationMin?, blocks)`, `PlanBlockDto(blockIndex, blockType, title?, durationMin?, restGuidance, rounds?, workDurationSec?, restDurationSec?, exercises)`, `PlanExerciseDto(id, name, type, targetSets?, targetReps?, targetDurationMin?, targetDurationSec?, rounds?, workDurationSec?, restDurationSec?, guidanceNote?, hideWeight?, showTime?, supersetGroup?, exposure?, tempo?, targetRpe?, targetLoad?, canonicalSlug?, items?)` — never re-encoded, never uploaded. **The wire is snake_case** (`session_id`, `day_name`, `block_index`, `target_rpe`, …): every property needs `@SerialName` (WellnessJson has no naming strategy — without them required fields go missing and decode fails). `targetReps`, `targetLoad` AND **`targetRpe` are `String?`** (free-form server text like `"6-7"`).

### API (`network/CoachApi.kt`)
```kotlin
suspend fun sync(clientId: String, lastSyncTime: SyncStamp?): CoachSyncResponseDto
// GET /api/coach/sync?client_id=…[&last_sync_time=…] — no-store + no-cache request headers
suspend fun syncPost(clientId: String, logs: Map<DateString, JsonObject>): CoachSyncPostResponseDto
// POST /api/coach/sync  body {clientId, logs}
suspend fun plansVersion(): PlansVersionDto
// GET /api/coach/plans-version — no-store headers
```

### Pure logic (`coach/CoachSyncLogic.kt`) — 1:1 port of `sync-logic.js`, operating on `JsonObject`
All 13 exports with JS names + the module-private `advanceRecordTokens`:
- Predicates: `logHasExerciseContent` (skip `_lastModifiedAt`/`_lastModifiedBy`/`session_feedback` keys and non-object values; content = non-empty `sets` OR non-empty `completed_items` OR `duration_min != null` — note `0` counts), `isDeletedEntry`, `logHasPendingDeletions`, `hasFeedbackContent` (trimmed non-empty), `logHasUploadableContent`, `logIsSyncedToServer` (= has day `_lastModified`).
- `withEntryUpdated(log, key, data)`: normal entry → shallow merge preserving `_lastModified`; over a tombstone → `{...data, _readd: true, _lastModified?: kept}` (drop `_deleted`; `_readd` is transient, never persisted past upload).
- `withEntryDeleted(log, key)`: missing/non-object → SAME reference (identity no-op); stamped → `{_deleted:true, _lastModified: kept}`; unstamped → `{_deleted:true}`.
- `nextDirtyAfterApply` = Phase 1 `DirtySetLogic.clearApplied` (reuse, do not reimplement).
- `selectLogsToUpload(dirtyDates, logs)` → `(logsToUpload, uploadedDates, unsatisfiableDates)` — missing log OR (no uploadable content AND never synced AND no pending deletions) → unsatisfiable; else `withBaseTokens(log)`.
- `withBaseTokens(log)`: each entry-object with `_lastModified` gains `_baseLastModifiedAt`; day-level likewise; original `_lastModified` keys remain in the payload (server ignores the day-level one by its non-dict check).
- `adoptUploadResults(localLogs, results, snapshotGens, currentGens, uploadedLogs)`: per result date — not re-modified (current gen == snapshot gen) → wholesale adoption of the merged server day; re-modified → `advanceRecordTokens(local, serverRow, uploadedLog)`: day token advances; **tombstone verdict rule (F4)** — a local tombstone that WAS uploaded adopts the verdict (server key present → adopt surviving record = delete rejected; absent → remove key = delete accepted); a tombstone created mid-sync is kept verbatim; other entries keep local content and take the server `_lastModified` only. **Nullable semantics (explicit, each pinned by a Kotlin test)**: `snapshotGens == null` disables re-modification detection entirely (every non-null result adopts wholesale — an EMPTY map is NOT the same as null); `uploadedLogs == null` means no tombstone is known-uploaded, so re-modified tombstones are all treated as mid-sync (preserved), never as verdicts.
- `pruneOlderThan(map, cutoff)` (lexical `>=` keep), `maxPlanVersion(plans, currentMax)`.

### Store (`coach/CoachSyncStore.kt`)
```kotlin
class CoachSyncStore(dao, api, isOnline, json, debugLog, scheduleUpload, clock, today) {
    val syncStatus: StateFlow<SyncStatus>   // offline→GRAY, dirty→RED, else GREEN (coach has no watermark condition — PWA parity)
    val isSyncing: Boolean; suspend fun hasDirtyData(): Boolean
    suspend fun pollCheck(): Boolean        // dirty→true; else GET plans-version; changed→record+true; failure→false — but CancellationException is RETHROWN, never swallowed to false
    fun observePlan(date): Flow<PlanDto?>   // decode-on-read
    fun observeLog(date): Flow<JsonObject?>
    // Mutators (each: transaction + mark date dirty + scheduleUpload):
    suspend fun updateLog(date, exerciseKey, data: JsonObject)      // absent day is created so the final object holds session_feedback:{}, the new entry, and BOTH advisory stamps; withEntryUpdated; stamps advisory _lastModifiedAt/_lastModifiedBy
    suspend fun deleteLogEntry(date, exerciseKey)                   // no-op if day absent; withEntryDeleted (identity no-op respected — no dirty)
    suspend fun updateSessionFeedback(date, feedback: JsonObject)   // shallow-merge into session_feedback; advisory stamps (both, deviation 2)
    suspend fun triggerSync(): SyncResult   // single-flight mutex, like journal
}
```
`EXTRA_SESSION_KEY = "extra_zone2"` constant lives here (with the server-twin comment). ClientId: race-safe get-or-create in `coach_meta`; `POST /register` never called (server registers inside POST /sync).

### Wiring
- Coach `SyncScheduler` (with `pollCheckFn = store::pollCheck`) built in Koin, registered with `SyncOrchestrator` alongside journal.
- Coach tab debug screen (app module): today's plan rendered from `PlanDto` (day name + blocks + exercise names/targets), the day's log entries as raw text, a scratch "log a set" button writing `{sets:[{set_num:1, weight:100, reps:5, completed:true}]}` to the first planned exercise (or `extra_zone2` `{duration_min:30}` on a rest day), sync dot, Sync now. Phase 5 replaces it.

## Behavior — `triggerSync` (upload-FIRST, exact order of `store.js:304-472`)

1. Offline → GRAY, `SyncResult(OFFLINE)`. Single-flight mutex → `ALREADY_SYNCING`.
2. Snapshot per-date dirty generations (before any network).
3. **Upload** (only if dirty dates exist): `selectLogsToUpload`; unsatisfiable dates are cleared (generation-guarded) BEFORE the POST with a debug breadcrumb; if `logsToUpload` is empty skip the POST (deviation 1). Else `POST /sync`; adopt `results` per `adoptUploadResults` with in-transaction current generations (deviation 4) — adoption + `uploadedDates` dirty clear + logs persist happen in ONE transaction (deviation 3). The response `serverTime` is NOT the pull watermark (the download's is).
4. **Download**: `GET /sync?client_id[&last_sync_time=watermark]`. Apply in one transaction: `deletedPlanDates` removed; plans overwritten/merged unconditionally (no dirty concept for plans); `lastKnownPlansVersion = maxPlanVersion(plans, current)` (in-memory); logs merged per date ONLY where not currently dirty (re-checked in-transaction); `earliestDate` + watermark ← `serverTime` stored; both maps pruned to `>= earliestDate`.
5. Status update (dirty-aware — not unconditional green); errors → RED + `SyncResult(error)`; CancellationException rethrown; `isSyncing` cleared in finally.

**Server contract notes the implementation must respect** (from `coach.py`): arbitration accepts `stored <= base` (equality = idempotent retry) and rejects token-less writes to existing rows; the resurrection guard rejects post-delete edits carrying a base token unless `_readd`; delete of an absent row still refreshes the server tombstone; `results[date]` is the merged server day (lean `assemble_log` shape — fields omitted when null/falsy, `completed: false` dropped from sets); day-level `_lastModified` is the FEEDBACK record's token, per-exercise `_lastModified` are separate; `earliestDate` is date-only and compared lexically.

## Golden fixtures (`testdata/golden/coach/`)

Generated against the dev server (seeded synthetic — user-confirmed 2026-08-07; the generator still asserts and eyeballs before commit, per the standing provenance rule). Constraints: fixture dates must sit **inside the 60-day window** or a full GET won't return them; assert the chosen dates carry **no pre-existing log** before writing (POST responses return the whole merged day — an occupied date would capture unrelated seeded entries); the stale-base fixture is built by updating a token-bearing row and re-sending its OLD base (a token-LESS resend tests hard cutover, a different rule). Ad-hoc logging needs no plan rows: use `extra_zone2` plus `fixture-`-prefixed ad-hoc exercise keys. Capture: `sync-get-full.json`, `sync-get-incremental.json`, `sync-post-request.json`, `sync-post-response.json` (accepted), `sync-post-response-rejected.json` (stale base), `sync-post-tombstone-roundtrip.json` (delete upload + verdict), `plans-version.json`, `plan-day.json` — from the dev server if it has plan rows, otherwise a **deterministic server-shaped synthetic fixture is mandatory** (covering every typed field incl. snake_case names and `"target_rpe":"6-7"`); the PlanDto decode test is never conditional. Tests: decode; `withBaseTokens`/`selectLogsToUpload` regenerate the request byte-identically; PlanDto decodes the plan fixture.

## Tests

| Test class | Pins |
|---|---|
| `CoachSyncLogicTest` (:core:data) | all 27 `coach-sync-logic.test.js` cases 1:1 (fixtures in the JS tests are synthetic — transcribe verbatim) |
| `CoachSyncStoreTest` (JVM, fake DAO + MockEngine) | upload-first order (POST before GET, asserted on the engine's request log); unsatisfiable cleared pre-POST + empty-POST skipped; adoption wholesale vs re-modified (edit lands between build and adopt → content kept, tokens advanced); F4 tombstone verdicts (rejected adopts surviving record; accepted drops key; mid-sync tombstone untouched) at the store level; download merge skips currently-dirty dates; deletedPlanDates; earliestDate prune both maps; watermark from download only; pollCheck matrix (dirty short-circuit, version unchanged/changed/null, throw→false, cold-start null baseline→true); mutator advisory stamps; deleteLogEntry identity no-op ≠ dirty; day-creation shape `{session_feedback:{}}` |
| `CoachApiTest` | URLs incl. `/wellness` prefix; `last_sync_time` omitted on first sync; no-store headers on both GETs; exact POST body + Content-Type asserted (not just decode); DTO decodes |
| Native race boundaries (in `CoachSyncStoreTest`) | edit between dirty discovery and payload build (atomic PendingUpload — the pairing can't skew); POST ok then GET fails → watermark untouched by POST serverTime; delete uploaded then re-added mid-flight → `_readd` survives into the next upload; nullable adoption semantics (snapshotGens null vs empty; uploadedLogs null); plan `_lastModified` JSON→column projection on every upsert |
| Fixture suite | as above |
| `CoachDaoTest` + `Migration2to3Test` (instrumented, emulator sessions) | dirty SQL ops on coach_logs; migration 2→3 with POPULATED journal/debug tables + a full v1→v3 chain; transaction rollback for applyUploadResults and applyDownload |

## Dependencies

None new. Consumes Phase 1 scheduler/dirty-set/debug-log and the Phase 2 store patterns (single-flight mutex, in-transaction generation guards, race-safe client id).

## Open Questions

1. **Debug-screen write target**: log the scratch set to the first planned strength exercise when a plan exists, else `extra_zone2` — confirm that's acceptable dev-server pollution (all `fixture-`-adjacent writes are real coach rows the dev PWA will show).
2. `lastKnownPlansVersion` persistence (deviation 5 says in-memory/PWA parity) — flag if you'd rather persist it now.
