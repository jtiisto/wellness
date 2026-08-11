# Coach Heart Rate Integration (pulse-bridge merge)

**Status: APPROVED — all three phases implemented (P1+P2 2026-08-11 this repo; P3 server-side by
the server session's plans/hr-module.md phases 3–5, parity note repointed 2026-08-11).**
Branch: `feature/coach-pulse`. Outstanding before checkoff: device acceptance of the end-to-end
flow (strap → capture → hr.db → analysis), the instrumented-test run next emulator session, and
the deferred items (ForceSync HR arm; hr_mcp client registration; pulse-bridge retirement — an
explicit later call).

## Goal

Fold pulse-bridge's live heart-rate capture into the coach feature so a workout can optionally record strap HR, show a compact live BPM readout, and — via a new timestamped set-completion event log — correlate heart rate with specific sets. Consolidate the two backends: the wellness FastAPI server gains an `hr` module (own endpoints, own `hr.db`), and pulse-bridge's analysis module + MCP server migrate to it. The standalone pulse-bridge app is superseded once this ships.

## Scope decisions (agreed 2026-08-09)

- **Garmin HRM live path only.** The Polar Verity Sense offline-sync path is not ported (avoids jitpack + RxJava3; that code was never device-verified).
- **Set events live in the hr module**, not the coach blob. The existing checkbox boolean and coach sync protocol are untouched.
- **HR wire protocol is re-specced to wellness conventions** (omit-never-null JSON; a `seq` PK column fixes pulse-bridge's known same-millisecond collision flaw). Pulse-bridge's golden fixtures are not carried over; new synthetic fixtures are authored.
- **Analysis (DFA α1, RMSSD, zones) + MCP server migrate in this feature**, repointed at `hr.db`.
- **Fresh start on data**: no import from the pulse-bridge server DB — it stays on disk as a frozen archive, still queryable manually. Analysis history restarts from the first capture through the new stack.
- **Headless PWA presence**: the `hr` module gets no PWA tab (direct mount or a `headless` registry flag — implementation's choice); analysis reports stay CLI/MCP.
- **Pulse-bridge app stays as a fallback for a while**: the old app and its server keep running in parallel until trust is built in the wellness path; retirement is a later, explicit call. Note the strap can only feed one app at a time.
- **Session anchoring**: `hr_sessions` stores `workoutDate` always, plus the coach workout-hook session id (nullable) when capture was started via the Start Workout sheet.
- **BPM chip is hidden when idle** — it appears only while a capture session is active.
- All Android work on `feature/coach-pulse` so `main` stays hotfixable.

## Architecture overview

```
strap ──GATT──▶ core/ble (parser → IntervalBuffer) ──▶ Room hr_samples ──batch──▶ POST /api/hr/samples/batch ──▶ hr.db
set checkbox ──▶ coach blob write (unchanged)                                                                      ▲
             └─▶ Room set_events (append-only) ──batch──▶ POST /api/hr/set-events/batch ──────────────────────────┘
                                                                          analysis + MCP read hr.db (read-only)
```

- **Android**: new `core/ble` module (ported pure logic + BLE primitives), a BLE capture foreground service, three new tables in the single Room DB, an append-only uploader, coach UI additions.
- **Server** (implemented in `~/dev/health/wellness` — this repo is Android-only): new `hr` module registered in `MODULES` config, `data/hr.db`, two batch-ingest endpoints, migrated analysis + MCP.

## Android

### New module `core/ble`

Uses the `wellness.android.library` convention plugin. A **leaf module**: no dependency on `core/data` (pulse-bridge's `core/ble → core/sync` edge is the thing we're *not* reproducing). Output reaches persistence via an injected sink interface; service↔UI state via `StateFlow` singletons with **distinct Koin qualifiers** (pulse-bridge's type-erasure lesson).

Ported essentially as-is, with their unit tests (pure Kotlin, injected clocks):
`HrmCharacteristicParser` · `IntervalBuffer` · `ReconnectionStrategy`/`ReconnectionConfig` (1 s × 2.0, cap 30 s, max 15 attempts) · `SignalQualityTracker` (needed for analysis trust) · model types (`HeartRateSample`, `ConnectionState`, `BleDevice`).

Ported with light rewiring (framework classes whose watchdog logic must not be rediscovered):
`GarminHrmConnection` (15 s connect watchdog, 10 s first-sample watchdog, advertising probe, every-failure-routes-to-disconnect) · `BleScanner` (unfiltered `SCAN_MODE_LOW_LATENCY` scan, callback-side filtering on HRM service UUID / name prefixes).

Not ported: `PriorityMultiplexer` (single-source now), tachogram UI, Polar everything, `ServerConfig` (wellness's Phase 8 server address book is the base URL).

### Capture foreground service

First foreground service / notification channel / runtime-permission flow in the app.

- `foregroundServiceType="connectedDevice"`, `START_STICKY`, resumes newest open session on null-intent restart.
- Notification channel `hr_capture`, `IMPORTANCE_LOW`, silent, ongoing; text = connection state + live BPM.
- `PARTIAL_WAKE_LOCK` for the session; **no `keepScreenOn`** (we show a number, not a tachogram).
- 5-minute inactivity auto-stop (armed on disconnect/reconnecting, cancelled on connect and on every sample) — bounded battery drain from a dead strap.
- Permissions: `BLUETOOTH_SCAN` (`neverForLocation`) + `BLUETOOTH_CONNECT` requested blocking at first pairing entry; `POST_NOTIFICATIONS` requested but a denial never blocks capture.

### Data model (Room v5 → v6, one migration)

| Table | Key | Notes |
|---|---|---|
| `hr_sessions` | `sessionId` (client UUID) | `deviceId`, `startedAtMs`, `endedAtMs` (null while open), optional `workoutDate` (`YYYY-MM-DD`), optional `workoutSessionId` (coach hook session id — a `Long`, set when started via the Start Workout sheet), `isSynced` (cleared on every content change so the session re-upserts), `isQuarantined` (422-bisect isolation, also cleared on content change so a corrected row re-attempts), `dirtyGeneration` (bumped on every content change; `markSynced`/`markQuarantined` are generation-guarded so a response that raced a newer write can never claim the newer row — coach's proven pattern, added 2026-08-11 after review caught the stale-response race) |
| `hr_samples` | PK (`deviceId`, `timestampMs`, `seq`) | one row per RR interval: `heartRateBpm`, `rrIntervalMs` (0 = artifact sentinel), `isGapBefore`, `sessionId`, `isSynced`, `syncedAt`, `isQuarantined` |
| `set_events` | `eventId` (client UUID) | `date`, `exerciseKey`, `setNum` (omitted for non-set widgets), `itemKey` (checklist toggles), `action` (`check` \| `uncheck`), `clientTimestampMs`, optional `sessionId`, `isSynced`, `isQuarantined` (poison rows isolated by the 422 bisect, same as samples) |

- **Sync model is append-only + `isSynced`** — deliberately *not* `isDirty`/`dirtyGeneration`. This is client-authored telemetry with no server arbitration: idempotent batch upload, mark synced on 2xx, retry safe by construction. Epoch-ms values here are *data*, not sync watermarks, so the opaque-timestamp rule doesn't apply.
- The `seq` column replaces pulse-bridge's monotonic-bump hack in `IntervalBuffer.nextTimestamp()`: same-millisecond samples get seq 0,1,2… instead of artificially shifted timestamps. Anchoring rules are otherwise preserved (last beat of a notification lands at receipt time, earlier beats placed backward by their RR durations, zero-RR sentinels spread backward, >3 s gap marks `isGapBefore`).
- Local retention: synced `hr_samples` pruned after 7 days; `set_events` after 60 (matches coach's prune horizon). Unsynced rows are never pruned.
- Cross-cutting obligations (enumerated by name, so listed here as a checklist): `WELLNESS_MIGRATIONS` + exported schema; `ServerSwitchDao` wipe list += 3 tables; `ExportDao` snapshot += 3 tables — `hr_samples` as per-session aggregates (counts + time bounds), not raw rows, so export size stays bounded by session count rather than capture length (blessed 2026-08-11; the export is diagnostic, not a recovery path); every write under `ServerSessionGate.withWriteLease`.

### Set-event dual-write

Every completion toggle appends an event **in the same Room transaction** as the existing coach-blob mutation (same DB, so this is cheap and atomic); the blob path itself is unchanged. Uncheck appends `action: "uncheck"` — undo is data, not deletion. Correlation folds the event stream to derive final state; the coach blob remains the display truth.

Covered toggles: per-set ticks (the priority) and checklist items. **Cardio has no completion
toggle to cover** (amended 2026-08-11 — implementation finding): cardio completion is *derived*
from `duration_min` presence, and value edits never emit events. The exercise-level event shape
(neither `setNum` nor `itemKey`) stays reserved in the protocol; wiring it is adding a
`CompletionToggle.Exercise` variant if a cardio tick UI ever exists.

### Upload path

`HrSyncStore` (in `core/data`): flush every 10 s or 200 buffered samples; a failed flush keeps the batch; the buffer runs on a Koin-owned `SupervisorJob` scope that outlives the service; a failed final flush leaves the session open rather than finalizing against stale data. Set events piggyback on the same cadence. `SyncFlushWorker` gains HR flushing so backgrounding drains it.

Quarantine: on a 422 the batch is recursively bisected to isolate poison rows (only 422 — other 4xx are systemic and rethrown); circuit breakers at 10 quarantines/run and 3 before first success. A breaker trip **before any success in the run is classified do-not-retry** (like a systemic 4xx): rows quarantined before the trip stay quarantined, and background retry against a server that rejects everything would erode the backlog 3 rows per cycle. A trip after successes stays retryable — those quarantines were legitimate discriminations. Residual foreground-retry erosion under a persistently broken server is accepted and bounded by the capped backoff; quarantined rows are never deleted and remain visible in the export.

### UX

- **Configuration screen — "Heart rate strap" section**: initial pairing lives here. Scan → unknown devices listed (name + MAC) with Connect; a successful connect saves it as the known device (SharedPreferences-equivalent MAC→name map; no OS bonding). Known device row shows name + Forget.
- **Start Workout**: when a known strap exists and capture isn't running, the Start tap first shows a small sheet — **"Connect HRM?" [Connect] [Skip]** — then proceeds with the hook either way. Asks every time (no sticky auto-connect in v1). Capture started here records `workoutDate` on the session.
- **Live BPM chip**: compact element in the coach top bar, visible only while a capture session is active — BPM number + connection-state color, nothing else. Tap opens a sheet: device, connection state, signal quality, Disconnect/Stop. When idle there is no chip; starting capture without a workout goes through the configuration screen's strap section.
- **End Workout** stops capture automatically (plus the 5-min inactivity net).
- Service-side capture errors surface via the existing snackbar event channel (fixing pulse-bridge's invisible-`state.error` gap).

### Quality gates

- Kover: carry pulse-bridge-style **named exclusions for device-only glue** (service, scanner, connection, notification builder, receiver) or the 85 gate breaks; ported pure logic stays covered.
- Golden fixtures for the new wire payloads in `testdata/golden/hr/` — synthetic only.
- Emulator has no BLE: BLE paths verified on the physical device via the APK-to-gdrive flow; everything below the BLE boundary (buffer, sync, events, UI state) is unit-tested headless.

## Server (planned and implemented in `~/dev/health/wellness`)

**Split out per cross-repo decision (2026-08-09):** the server-side plan lives in the server repo at
`plans/hr-module.md`; the wire contract both repos implement is **`specs/hr-protocol.md`** in this
repo (authoritative until the server work lands it in `docs/ARCHITECTURE.md`). Summary only here:

- New headless module `src/modules/hr.py` + `data/hr.db` (`headless` flag keeps it out of the PWA tab bar); backups/deploy pick the DB up automatically.
- Endpoints (no auth — `ClientGuardMiddleware` Tailscale allowlist, consistent with everything else):
  - `GET  /api/hr/status` → `{status, samplesCount, setEventsCount, sessionsCount}`
  - `POST /api/hr/samples/batch` → `{accepted, duplicates, totalReceived}` (`INSERT OR IGNORE` on PK)
  - `POST /api/hr/set-events/batch` → same shape, idempotent on `eventId`
  - `POST /api/hr/sessions/batch` → `{upserted, totalReceived}` — full-row upsert on `sessionId`, last write wins (single writer); the client re-upserts on start, workout-anchor change, and close
- JSON: wellness conventions — optional fields omitted, never null; **camelCase keys throughout** (deliberate choice recorded in the protocol spec).
- **Dropped from the pulse-bridge protocol**: `X-Environment` header (test isolation via the wellness server's existing conftest pattern), accelerometer endpoints (Polar-sourced), diagnostics upload (wellness has the debug-log share).
- **Analysis migration**: `server/analysis/` (DFA α1 rolling windows, RMSSD, duration-weighted zones, bout detection) moves into the wellness server repo, repointed at `hr.db` with the new column names. The Polar timestamp-shift branch is dropped with the Polar path. No historical import — analysis over `hr.db` starts from the first new-stack capture; the old pulse-bridge DB remains a frozen, manually-queryable archive. The Kotlin↔Python DFA **parity warning carries over**: `SignalQualityTracker` thresholds must match `quality.py`, still enforced only by comment.
- **MCP migration**: `mcp_servers/pulse_bridge_mcp/` moves across, read-only on `hr.db`.
- If HR analysis needs exercise names from coach data, it uses the sanctioned **read-only cross-DB accessor** pattern (as trends does) — never coach's own accessor.

### Phase 2 implementation notes (2026-08-11, as built)

- **Module shape**: `core/ble` is the leaf it was spec'd to be, and `core/data` gained an `api`
  dependency ON it (the sink/store wiring) — the dependency arrow points core/data → core/ble,
  never the reverse. `HrCaptureController` (the start/stop seam both UI surfaces use) lives in
  `core/ble` as the write half of the `HrCaptureState` contract; the service in `app/` implements it.
- **FGS-36 open item resolved**: `connectedDevice` type + `FOREGROUND_SERVICE_CONNECTED_DEVICE`
  is the compliant set (verified against the API-37 SDK jar); no `dataSync` type (uploads ride
  WorkManager); the handled exception family is `ServiceStartNotAllowedException` (+`SecurityException`
  for a revoked grant). Android 16's bond-loss re-pair dialog doesn't apply — pairing is a MAC→name
  map (SharedPreferences), no OS bonding.
- **Capture start is gated** (`CaptureStartGate`): refused without `BLUETOOTH_CONNECT` and also
  while the server is unresolved — the upload nudge would otherwise kill the service on its first
  flush while holding a wake lock. The strap is saved as known on first CONNECTED, not on attempt.
- **Permission UX split**: all Bluetooth-permission UI lives in the strap section (blocking, with
  distinct deny-once vs deny-permanently explanations); the coach sheet only ever offers a known
  strap, so `feature/coach` never requests permissions. POST_NOTIFICATIONS is requested after
  `start()`, fire-and-forget — a denial provably cannot block capture.
- **Capture errors** surface through `SyncErrorEvents.postMessage` (plain-message sibling of the
  sync-error channel, no "Sync Failed:" prefix), fed by a state-edge detector — fixing
  pulse-bridge's invisible-`state.error` gap as spec'd.
- **Tools shows the same live readout** as the coach chip via one shared `hrCaptureDisplay`
  mapping, so a running capture cannot describe itself two ways.
- **Samples flow**: `IntervalBuffer` (10 s / 200 rows; 60 s seq-collision window, MTU-derived) →
  `HrCaptureStore` (every write leased) → `hr_samples` → the Phase 1 upload pipeline's
  `scheduleFlush`. The buffer scope is Koin-owned and outlives the service.
- **Session close semantics (refined 2026-08-11 after review)**: a failed final flush leaves the
  session open, and the close then completes *retroactively* once a later flush proves the buffer
  drained — carrying the stop instant, not the flush instant. Backstop: a fresh capture force-closes
  any stale open session for the same device (`endedAtMs` = now, advisory — analysis reads sample
  timestamps). Live-session mutations (`anchorWorkout`, `closeSession`) are atomic single-statement
  DAO UPDATEs guarded `WHERE endedAtMs IS NULL` — reopening a closed session is structurally
  impossible, and rows-affected (not the session id) is the publish discriminator. The sticky
  resume publishes via compareAndSet so it can never displace a capture that started while its
  query was suspended.

## Behavior details

- Connection lifecycle, watchdogs, backoff, advertising-probe verdicts: as ported from pulse-bridge (see `core/ble` section).
- Sample overflow is recorded in the data (gap marker + cumulative counter), not just logged.
- Capture survives app death (`START_STICKY` + open-session resume); sync survives capture death (buffer scope is Koin-owned).

## Dependencies

- **Ordering decision (2026-08-09): the server side is implemented first** (`~/dev/health/wellness`, `plans/hr-module.md`), so all four `hr` endpoints exist before Android Phase 1 starts and every Android phase can verify end-to-end from day one.
- Physical-device sessions needed for BLE verification (emulator has no Bluetooth).

## Phases

1. **Set-event log (no BLE).** Room v6 with all three tables, dual-write on toggles, server `hr` module with the set-events endpoint, upload path. Fully verifiable against the existing manual checkboxes — HR correlation semantics proven before any Bluetooth exists.
2. **BLE capture + live BPM.** `core/ble` port, capture service, pairing UI in configuration, Start-workout sheet, BPM chip, samples endpoint + upload. Device-verified.
3. **Analysis + MCP migration** (server repo), pointed at `hr.db`. Pulse-bridge is *not* decommissioned here — it keeps running as a parallel fallback until an explicit later decision.

## Open questions

All five launch questions were resolved 2026-08-09 and folded into *Scope decisions* above (fresh start on data; headless PWA; pulse-bridge kept as fallback; date + hook-session-id anchoring; chip hidden when idle). Session upsert shape is resolved in `specs/hr-protocol.md` (`POST /api/hr/sessions/batch`, full-row last-write-wins). Remaining implementation-time item:

- Verify targetSdk-36 foreground-service-type requirements against current Android docs at Phase 2 start.
