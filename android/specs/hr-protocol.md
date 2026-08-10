# HR Wire Protocol v1 (cross-repo contract)

**Status: DRAFT — awaiting approval.**

The wire contract between the wellness Android client (this repo, `feature/coach-pulse`) and the
wellness server's `hr` module (implemented in `~/dev/health/wellness`, planned in that repo's
`plans/hr-module.md`). Parent feature spec: `specs/coach-heart-rate.md`.

**This file is the authoritative contract** until the server work lands, at which point the server
repo's `docs/ARCHITECTURE.md` gains an HR section and takes over (matching how every other protocol
is documented). Golden fixtures pin the byte-level shapes in **both** repos — Android
`testdata/golden/hr/`, server pytest fixtures — and any protocol change updates both in the same
breath. Fixtures are synthetic only, never copied from live data.

## Conventions

- **Keys are camelCase throughout** — request and response, envelope and rows. Rationale: this is a
  new protocol authored by the Kotlin client (zero `@SerialName` noise), and it matches the existing
  envelope precedent (`clientId`, `serverTime`); the server side uses a Pydantic camelCase alias
  generator. (Coach's snake_case row fields are the preserved PWA-era blob format, not a convention
  for new protocols.)
- **Optional fields are omitted, never null** (client uses the shared wellness
  `Json { explicitNulls = false; encodeDefaults = false }`). Booleans defaulting to false and
  strings defaulting to their documented default are omitted on the wire.
- **Epoch-ms integers are data values**, not sync watermarks — the opaque-timestamp rule does not
  apply to them. There are no server-issued watermarks anywhere in this protocol.
- Unversioned paths (`/api/hr/...`), matching every other wellness module.
- **No auth, no `X-Environment` header.** Transport security is the existing
  `ClientGuardMiddleware` Tailscale allowlist; test isolation is the server repo's conftest
  env-var DB-path pattern.
- `clientId` in every POST envelope is the same self-asserted client identifier the coach/journal
  sync sends — diagnostic, not a credential.

## Endpoints

### `GET /api/hr/status`

Health/counts probe (used by the config screen's reachability check and by tests).

```json
200 → {"status": "ok", "samplesCount": 12345, "setEventsCount": 210, "sessionsCount": 17}
```

### `POST /api/hr/samples/batch`

```json
{"clientId": "fixture-client-hr-0001", "samples": [Sample, ...]}
→ 200 {"accepted": 198, "duplicates": 2, "totalReceived": 200}
```

Idempotent: `INSERT OR IGNORE` on PK (`deviceId`, `timestampMs`, `seq`); a retried batch double-counts
nothing. `accepted + duplicates == totalReceived` always.

### `POST /api/hr/set-events/batch`

```json
{"clientId": "fixture-client-hr-0001", "events": [SetEvent, ...]}
→ 200 {"accepted": 5, "duplicates": 0, "totalReceived": 5}
```

Idempotent on `eventId`.

### `POST /api/hr/sessions/batch`

```json
{"clientId": "fixture-client-hr-0001", "sessions": [Session, ...]}
→ 200 {"upserted": 1, "totalReceived": 1}
```

**Upsert, not insert**: full-row replace keyed on `sessionId`, last write wins. Safe because a
session has exactly one writer (the capturing device); the client re-uploads a session whenever its
row changes (started → workout-anchored → ended). Order-independent with respect to samples/events —
no foreign-key enforcement, a session row may arrive before or after rows referencing it.

## Row shapes

### Sample — one row per RR interval

| Field | Type | Notes |
|---|---|---|
| `deviceId` | string | strap MAC |
| `timestampMs` | int | epoch ms, receipt-anchored per `IntervalBuffer` rules (last beat of a notification at receipt time, earlier beats placed backward by their RR durations) |
| `seq` | int ≥ 0 | disambiguates same-millisecond samples; replaces pulse-bridge's monotonic-bump hack |
| `heartRateBpm` | int | as reported by the strap for the carrying notification |
| `rrIntervalMs` | int ≥ 0 | `0` is a legal artifact sentinel |
| `isGapBefore` | bool | omitted when false; set on the first row after a >3 s notification gap or a buffer overflow |
| `sessionId` | string | UUID of the owning capture session |
| `sensorType` | string | omitted ⇒ `"garmin_hrm"` (the only value in v1; column exists for future sources) |

### SetEvent — one row per completion toggle

| Field | Type | Notes |
|---|---|---|
| `eventId` | string | client-generated UUID (the idempotency key) |
| `date` | string | local `YYYY-MM-DD` — the coach day the toggle belongs to |
| `exerciseKey` | string | the entry key in the coach day-log JSON (e.g. `"extra_zone2"`, the ad-hoc/plan exercise key) — joins to coach data by `(date, exerciseKey)` |
| `setNum` | int ≥ 1 | set ticks only; omitted for checklist/cardio toggles |
| `itemKey` | string | checklist-item toggles only (the `completed_items` string); omitted otherwise |
| `action` | string | `"check"` \| `"uncheck"` — uncheck is undo-as-data, nothing is deleted |
| `clientTimestampMs` | int | epoch ms at the moment of the toggle |
| `sessionId` | string | omitted when no capture session was active |

A toggle carries at most one of `setNum` / `itemKey`; neither present means an exercise-level toggle
(cardio completed). Correlation folds the event stream in `clientTimestampMs` order (ties broken by
arrival) to derive final state; the coach blob remains the display truth.

### Session

| Field | Type | Notes |
|---|---|---|
| `sessionId` | string | client-generated UUID |
| `deviceId` | string | strap MAC |
| `startedAtMs` | int | epoch ms |
| `endedAtMs` | int | omitted while the session is open |
| `workoutDate` | string | local `YYYY-MM-DD`; omitted for captures not tied to a workout |
| `workoutSessionId` | int | coach workout-hook session id (numeric); present when capture was started via the Start Workout sheet |

## Error semantics and client interaction contract

| Server response | Client behavior |
|---|---|
| 2xx | mark the batch's rows `isSynced` (samples/events) / clear the session's needs-upload flag |
| **422** (any row failed validation) | **bisect**: recursively split the batch to isolate poison rows, quarantine only those, resubmit the valid remainder. Only 422 triggers bisection. Circuit breakers: max 10 quarantines per run, max 3 before any request in the run has succeeded |
| other 4xx | systemic — no bisect; the sync run fails fast (`Result.failure()`, no backoff spin on a poison batch) |
| 5xx / network error | keep the batch, retry with exponential backoff (WorkManager policy) |

Server-side validation is deliberately shallow: Pydantic types, the `action` enum, `date` /
`workoutDate` format `^\d{4}-\d{2}-\d{2}$`, non-negative integers. No physiological range policing —
artifacts are data and the analysis layer owns quality judgment.

Batch sizing: the client caps at **1000 rows per request** (live flushes are ~10 s / 200-row
increments; the cap matters when draining a backlog). The server imposes no hard limit.

Upload cadence (client): buffer flush every 10 s or at 200 samples; a failed flush keeps the batch;
set events and session upserts ride the same cadence; `SyncFlushWorker` drains everything on app
backgrounding. A failed final flush leaves the session open (`endedAtMs` absent) rather than
finalizing against stale data.

## Server storage (hr.db, informative)

Columns are snake_case mirrors of the wire fields. `intervals` PK
(`device_id`, `timestamp_ms`, `seq`); `set_events` PK `event_id`; `sessions` PK `session_id`.
DDL and migrations live in the server plan (`~/dev/health/wellness/plans/hr-module.md`).

## Canonical example payloads

Checked in as golden fixtures in both repos; shown here for review. All values synthetic.

`samples-batch-request.json`
```json
{"clientId":"fixture-client-hr-0001","samples":[
  {"deviceId":"AA:BB:CC:DD:EE:FF","timestampMs":1770000000000,"seq":0,"heartRateBpm":142,"rrIntervalMs":423,"sessionId":"11111111-2222-3333-4444-555555555555"},
  {"deviceId":"AA:BB:CC:DD:EE:FF","timestampMs":1770000000000,"seq":1,"heartRateBpm":142,"rrIntervalMs":0,"sessionId":"11111111-2222-3333-4444-555555555555"},
  {"deviceId":"AA:BB:CC:DD:EE:FF","timestampMs":1770000004100,"seq":0,"heartRateBpm":141,"rrIntervalMs":431,"isGapBefore":true,"sessionId":"11111111-2222-3333-4444-555555555555"}]}
```

`set-events-batch-request.json`
```json
{"clientId":"fixture-client-hr-0001","events":[
  {"eventId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","date":"2030-01-03","exerciseKey":"fixture-adhoc-lift","setNum":1,"action":"check","clientTimestampMs":1770000010000,"sessionId":"11111111-2222-3333-4444-555555555555"},
  {"eventId":"ffffffff-0000-1111-2222-333333333333","date":"2030-01-03","exerciseKey":"fixture-adhoc-lift","setNum":1,"action":"uncheck","clientTimestampMs":1770000015000,"sessionId":"11111111-2222-3333-4444-555555555555"},
  {"eventId":"44444444-5555-6666-7777-888888888888","date":"2030-01-04","exerciseKey":"fixture-adhoc-checklist","itemKey":"fixture-item-a","action":"check","clientTimestampMs":1770000020000}]}
```

`sessions-batch-request.json`
```json
{"clientId":"fixture-client-hr-0001","sessions":[
  {"sessionId":"11111111-2222-3333-4444-555555555555","deviceId":"AA:BB:CC:DD:EE:FF","startedAtMs":1769999990000,"workoutDate":"2030-01-03","workoutSessionId":42}]}
```

`batch-response.json`
```json
{"accepted":3,"duplicates":0,"totalReceived":3}
```

## Open questions

None. (Casing choice — camelCase throughout — is called out above as a deliberate deviation from
coach's snake_case rows; veto here if wrong.)
