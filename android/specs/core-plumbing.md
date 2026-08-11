# Spec: Core Plumbing (Phase 1)

Status: **approved 2026-08-06** (v2 after Codex review; open questions resolved below)

## Goal

Stand up the headless foundation every feature builds on: the HTTP client, the Room database scaffold, the debug log, and the sync engine's module-agnostic machinery (dirty tracking, scheduling, orchestration) — ported from the PWA where the PWA has pinned behavior. Ends with a demonstrable slice: the Tools screen pings `GET /api/journal/sync/status` against the real server and shows the debug log.

Porting sources (behavior is theirs, not ours to redesign):
- `~/dev/health/wellness/public/js/shared/dirty-set.js` (+ `test/js/dirty-set.test.js`)
- `~/dev/health/wellness/public/js/shared/sync-scheduler-logic.js` (+ `test/js/sync-scheduler-logic.test.js`)
- `~/dev/health/wellness/public/js/shared/sync-scheduler.js`
- `~/dev/health/wellness/public/js/shared/debug-log.js`

### Declared deviations from the PWA (intentional, all else is 1:1)
1. **Sequential poll loop** — `while { delay(30 s); pollOnce() }` between *completed* cycles instead of `setInterval`'s fixed-rate ticks. No overlapping poll checks; cadence may drift by cycle duration. (JS could overlap a slow `pollCheckFn` with the next tick; we forbid it.)
2. **Foreground-gated polling** — polling runs iff `foreground && online`. The literal PWA starts polling on an `online` event even while hidden; we do not. An online-transition while backgrounded still fires one `requestSync()` (dirty-data flush parity).
3. **Single-flight is enforced internally** (serialized scheduler state), not just advised by the caller's `isSyncing()` — see Behavior §2.
4. **A trigger refused because an *external* syncer is busy arms the debounce** rather than latching `pendingSync` with no timer — see Behavior §1.2 for why the parity behavior does not transfer.

## API / Interface

All in `:core:data` (`dev.jtiisto.wellness.core.data.*`). Nothing here imports Compose; everything except `ConnectivityMonitor` and the Room DAO edges is JVM-unit-testable.

### Serialization (`WellnessJson.kt`)
```kotlin
val WellnessJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}
```

### Network (`network/`)
```kotlin
data class ServerConfig(val baseUrl: String)
// baseUrl includes the /wellness path prefix; trailing slash normalized away.

fun buildHttpClient(config: ServerConfig, json: Json, debugLog: DebugLog): HttpClient
// OkHttp engine (no disk cache configured); ContentNegotiation(json);
// Accept: application/json; connect timeout 10 s, request timeout 30 s;
// Ktor Logging at level INFO (no bodies, no headers) routed into DebugLog.

@Serializable
data class JournalSyncStatusDto(val lastModified: SyncStamp? = null)

class JournalApi(client: HttpClient, config: ServerConfig) {
    suspend fun syncStatus(): JournalSyncStatusDto
    // GET <baseUrl>/api/journal/sync/status
}
```
- **URL joining rule**: endpoint paths are *appended to* the base URL's path, never replace it. Canonical result: `http://<host>:9000/wellness/api/journal/sync/status`. `JournalApiTest` asserts this exact URL (Ktor's leading-slash semantics would silently drop `/wellness` — this is the trap the test pins).
- Base URL from `BuildConfig.WELLNESS_BASE_URL` on `:core:data` (`buildFeatures.buildConfig = true`), read from `local.properties` key `wellness.baseUrl`, default `https://pop-os.tailexample.ts.net:9443/wellness` (the tailscale-serve endpoint, verified live 2026-08-06). `usesCleartextTraffic` stays for now to allow an http:// override in dev; removing it is a Phase 8 cleanup.
- `typealias SyncStamp = String` — opaque, lexically compared, never parsed. Local clocks (`ts`, `fetchedAt`) are epoch millis `Long` — the two must not be conflated.

### Room (`db/`)
```kotlin
@Database(version = 1, exportSchema = true,
    entities = [PayloadCacheEntity::class, DebugLogEntity::class])
abstract class WellnessDatabase : RoomDatabase()   // filename "wellness.db"

@Entity(tableName = "payload_cache", primaryKeys = ["module", "key"])
data class PayloadCacheEntity(val module: String, val key: String,
                              val payloadJson: String, val fetchedAt: Long /* epoch ms */)

@Entity(tableName = "debug_log")
data class DebugLogEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
                          val ts: Long /* epoch ms */, val tag: String, val message: String,
                          val dataJson: String? = null)
```
- Schemas exported and committed under `core/data/schemas/` from v1 on.
- **Destructive migration is forbidden** (no `fallbackToDestructiveMigration`). Journal/coach tables arrive in Phases 2/4 as real migrations (v2+); the migration-test baseline starts at v1→v2.
- DB built on `Dispatchers.IO`-backed executors (Room defaults); no main-thread queries (`allowMainThreadQueries` never enabled).

### Debug log (`sync/DebugLog.kt`) — port of `debug-log.js`
```kotlin
class DebugLog(dao: DebugLogDao, scope: CoroutineScope /* SupervisorJob + Dispatchers.IO */) {
    fun log(tag: String, message: String, data: JsonElement? = null)  // fire-and-forget, never throws
    fun entries(): Flow<List<DebugLogEntity>>  // newest first, TTL re-applied per DB change + 30 s tick
    suspend fun dump(): String                 // deterministic "ts tag message dataJson" lines
}
```
- Retention: cap **500 rows**, TTL **1 h** (constants from `debug-log.js`; boundary follows the JS: retained while `ts > now − 1 h`, strict). Insert+prune run in **one Room transaction**, serialized by a per-instance mutex, so concurrent fire-and-forget writes can't overshoot the cap. `dump()` filters with a fresh cutoff per call; `entries()` re-applies the filter on every DB change and every 30 s tick, so an entry expiring while the screen is open disappears within one tick even if no write pruned it. *(v2.1: was "filter in SQL at collection time" — a Room Flow freezes its bind args, so a collection-time cutoff goes stale.)*
- `data` that fails to serialize is replaced by a `"<unserializable>"` marker; the log call still succeeds. A failed DB write is swallowed (supervisor scope — never cancels sibling work).
- **Privacy**: HTTP logging records method + URL + status only — never request/response bodies or headers (journal/coach payloads are personal data). This policy is load-bearing for all later phases.

### Dirty tracking (`sync/DirtySetLogic.kt`) — 1:1 port of `dirty-set.js`
```kotlin
data class DirtyState(val keys: List<String>, val generations: Map<String, Long>)

object DirtySetLogic {
    fun markDirty(state: DirtyState, key: String): DirtyState
    // membership ensured; generation bump UNCONDITIONAL (detects mid-sync re-modification)

    fun clearApplied(state: DirtyState, appliedKeys: Collection<String>,
                     snapshotGens: Map<String, Long>?): DirtyState
    // clears applied keys whose generation equals the snapshot value;
    // re-modified keys stay dirty; generations of cleared keys are dropped;
    // snapshotGens == null disables the re-modification check.
    // Missing-entry semantics (mirrors JS property lookup): an applied key absent
    // from a non-null snapshot compares generation != null -> stays dirty.
}
```
Generations are `Long` (JS numbers don't wrap at 32 bits; a hot key must not overflow).

### Scheduler logic (`sync/SyncSchedulerLogic.kt`) — 1:1 port
```kotlin
data class SyncResult(val success: Boolean, val reason: SyncSkipReason? = null,
                      val error: Throwable? = null)
enum class SyncSkipReason { CONFLICTS, OFFLINE, ALREADY_SYNCING, OTHER }
// OTHER = the JS "unknown reason" case; internal only, never serialized.
enum class SyncOutcome { RESET, SKIP, ERROR, RETRY }

fun computeRetryDelay(attempt: Int, baseMs: Long, maxMs: Long): Long
// min(baseMs * 2^attempt, maxMs), SATURATING: attempt >= 62 or any overflow -> maxMs.

fun classifySyncOutcome(result: SyncResult): SyncOutcome
// success || CONFLICTS -> RESET; OFFLINE || ALREADY_SYNCING -> SKIP;
// error != null -> ERROR; else (incl. OTHER) -> RETRY
```

### Scheduler (`sync/SyncScheduler.kt`) — coroutine port of `sync-scheduler.js`
```kotlin
class SyncScheduler(
    scope: CoroutineScope,               // APP-LIVED (survives activity backgrounding); tests: TestScope
    name: String,
    syncFn: suspend () -> SyncResult,
    isSyncing: () -> Boolean,            // owning store's notion of "busy" (Phase 2+)
    hasDirtyData: suspend () -> Boolean,
    isOnline: () -> Boolean,
    pollCheckFn: (suspend () -> Boolean)? = null,
    onServerError: (Throwable) -> Unit = {},
    isNetworkError: (Throwable) -> Boolean,
    uploadDebounceMs: Long = 2_500, pollIntervalMs: Long = 30_000,
    baseRetryMs: Long = 5_000, maxRetryMs: Long = 120_000,
) {
    fun scheduleUpload()   // reset debounce timer job
    fun requestSync()      // cancel debounce + retry TIMER (attempt counter untouched), execute now
    fun resetRetry()       // zero BOTH retry timer and attempt counter (force-sync hook)
    fun onOnline()         // requestSync(); orchestrator decides about polling separately
    fun onOffline()        // stop polling; cancel debounce + retry timer (counter untouched)
    fun startPolling(); fun stopPolling()   // driven by orchestrator's (foreground && online) state
    fun stop()             // terminal + idempotent: cancel timers/polling; an in-flight syncFn RUNS TO COMPLETION
}
```

### Connectivity (`sync/ConnectivityMonitor.kt`)
```kotlin
class ConnectivityMonitor(context: Context) {
    val isOnline: StateFlow<Boolean>
    fun start(); fun stop()   // idempotent; stop() unregisters the callback
}
```
- "Online" = a network with `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED`.
- Initial value computed synchronously from `activeNetwork` at `start()` — not "wait for first callback".
- Tracks networks by id: losing one network while another validated network remains does **not** report offline. Emissions are distinct-until-changed (StateFlow conflation).

### Orchestrator (`sync/SyncOrchestrator.kt`)
```kotlin
class SyncOrchestrator(scope: CoroutineScope, connectivity: ConnectivityMonitor) {
    fun register(scheduler: SyncScheduler)  // late registration receives current state immediately
    fun start()  // idempotent; called once from WellnessApplication.onCreate
}
```
**State model** (the single source of truth for polling): `polling := foreground && online`.
- foreground-transition (ProcessLifecycleOwner ON_START) → each scheduler: `requestSync()` if online; recompute polling.
- background-transition (ON_STOP) → recompute polling (stops it). **Debounce and retry timers keep running** — backgrounding stops only polling (PWA parity); the app-lived scope makes that possible.
- online-transition → each scheduler: `onOnline()` (one requestSync, even if backgrounded — dirty-flush parity); recompute polling.
- offline-transition → each scheduler: `onOffline()`; recompute polling.
- All orchestrator event handling is serialized on the main dispatcher — no concurrent fan-out, no duplicate delivery races. Registering after `start()` delivers the current state to the new scheduler.
- This is **process-resident scheduling only** — process death loses all timers. Durable pickup (WorkManager one-shot flush on backgrounding-with-dirty-data) is deliberately Phase 2, when dirty data first exists; dirty rows in Room are the recovery source, not scheduler state.

### Koin + app wiring
`coreDataModule` singletons: `Json`, `ServerConfig`, `HttpClient`, `WellnessDatabase`, DAOs, `DebugLog`, `ConnectivityMonitor`, `SyncOrchestrator`, `JournalApi`, plus an app-lived `CoroutineScope(SupervisorJob() + Dispatchers.Default)` qualified `"appScope"`. `WellnessApplication` calls `connectivity.start()` + `orchestrator.start()`.

### Tools screen (app module, `ui/tools/`)
MVI ViewModel + screen replacing the "Tools" stub:
- **Server status** row: button fires `JournalApi.syncStatus()`; shows round-trip result (`lastModified` or the error string). This is the Phase 1 demo.
- **Debug log** list: `DebugLog.entries()` newest-first, monospace.
- Placeholder rows for Force Sync / Export (disabled — Phase 8).

## Behavior

1. **Execution flow** (`sync-scheduler.js` `_executeSync`, order preserved exactly):
   1. If `!isOnline()` → return (nothing scheduled).
   2. **Busy check happens BEFORE the try/finally**: if `isSyncing()` (or an internal flight is active) → set `pendingSync = true` and return — this path must NOT run the finally-block (the running invocation's finally consumes the flag). **Declared deviation (Phase 1 coach-pulse):** when the busy-ness comes from `isSyncing()` alone, with no scheduler-owned flight, the debounce timer is armed as the finally would have armed it. The PWA left the flag latched with no timer because it had no external syncers; this app has two (`SyncFlushWorker`, `ForceSyncOrchestrator`), and neither runs a finally of this scheduler's — so the parity behavior dropped the trigger outright instead of merely deferring it.
   3. Run `syncFn()`; classify: RESET → zero retry timer + attempt counter; SKIP → nothing; ERROR → network errors silent / server errors via `onServerError`, then schedule retry; RETRY → silent retry. A **thrown** exception = ERROR path — except `CancellationException`, which is rethrown, never classified, never retried.
   4. `finally`: if `pendingSync || hasDirtyData()` → clear flag, `scheduleUpload()`.
2. **Cancellation discipline & single-flight**: timer jobs (debounce, retry, poll) and the in-flight sync are **separate Jobs**. Cancelling a timer never aborts a running `syncFn`; `scheduleUpload()`/`requestSync()`/`onOffline()`/`stop()` never cancel an in-flight sync. Scheduler state is confined to a single-threaded context (main-immediate dispatcher; tests: the TestScope's scheduler), making the busy-check atomic — two concurrent triggers cannot both enter `syncFn`; the loser takes the `pendingSync` path.
3. **Dual timers coexist** (PWA parity): after ERROR/RETRY, a retry timer is pending; the finally-block may *also* arm a debounce. A firing debounce does not clear the retry timer — only `requestSync()` does. A retry firing while a flight is active sets `pendingSync` like any trigger.
4. **Offline race**: `onOffline()` during an active sync cancels timers but not the flight; the flight's finally may then arm a fresh debounce, which fires later and exits at the online check. Accepted (PWA has the same shape); pinned by test.
5. **Poll cycle**: skip if offline or syncing; `pollCheckFn` returning false **or throwing** skips the cycle silently (no `onServerError`, no retry).
6. **DebugLog and HTTP behavior** as specified in API sections above.

## Dependencies

- Uses (already in catalog): Ktor client stack, Room, Koin, kotlinx.serialization/coroutines, lifecycle-process (add to `:core:data`). WorkManager is deliberately **not declared as a dependency** until Phase 2 — merely declaring it runs its `Initializer` at app startup. *(v2.1 precision.)*
- Depended on by: every later phase. Phase 2 consumes `SyncScheduler`, `DirtySetLogic`, `DebugLog`, `JournalApi`, the DB.

## Tests (JVM unit tests; virtual time via `kotlinx-coroutines-test`)

| Test class | Pins |
|---|---|
| `DirtySetLogicTest` | `dirty-set.test.js` transcribed 1:1 + missing-snapshot-entry case + Long generations |
| `SyncSchedulerLogicTest` | `sync-scheduler-logic.test.js` transcribed 1:1 + OTHER→RETRY + outcome precedence (success+error → RESET; CONFLICTS+error → RESET) + saturating delay at huge attempts |
| `SyncSchedulerTest` | debounce coalescing; poll cadence (sequential, no overlap, slow pollCheck > 30 s); backoff 5→10→20→…→120 cap; pendingSync: busy-path skips finally, running flight's finally re-arms; dual timers after ERROR + dirty; retry fires during flight → pendingSync; requestSync/onOffline cancel retry timer but NOT attempt counter; RESET zeroes both; SKIP touches neither; in-flight sync survives scheduleUpload/requestSync/onOffline/stop; CancellationException never classified; two concurrent triggers → one flight; pollCheck throw → no onServerError, no retry; stop() idempotent, post-stop calls no-op |
| `SyncOrchestratorTest` | polling == foreground && online; online-while-backgrounded → requestSync only; late register gets current state; repeated identical events idempotent; flapping connectivity |
| `DebugLogLogicTest` | 500-cap boundary, 1 h TTL boundary, prune predicate, unserializable data marker |
| `DebugLogTest` | live-view TTL: expired entry drops on a tick with no write; fresh entries survive ticks |
| `DebugLogDaoTest` *(instrumented, emulator sessions only)* | the SQL twins of the retention rules: TTL boundary, cap keeps newest, concurrent insertAndPrune ≤ cap |
| `JournalApiTest` | exact URL `…/wellness/api/journal/sync/status` (with/without trailing slash on base); status decode; unknown fields ignored; non-2xx and malformed JSON surface as errors |

Verification: `./gradlew build` green (hooks enforce on commit); APK to `gdrive:Wellness/APKs`; manual check on the tailnet — Tools ping returns `lastModified`, debug log shows the request line.

## Resolved Questions (user decisions, 2026-08-06)

1. **Base URL default** — the tailscale-serve HTTPS endpoint `https://pop-os.tailexample.ts.net:9443/wellness` (overridable via `local.properties` `wellness.baseUrl`).
2. **Server-error surfacing** — Phase 1 logs to DebugLog only; global snackbar host lands in Phase 3.
3. **Deviations 1–3** (sequential poll loop; foreground-gated polling; internal single-flight) — accepted.
4. **Deviation 4** (external-busy trigger arms the debounce) — added in the Phase 1 coach-pulse review round; the PWA had no external syncers for the parity to be about.
