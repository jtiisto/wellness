# Architecture

## Overview

Wellness is a modular, self-hosted health application with three independent modules sharing a unified backend and frontend shell. Each module owns its own SQLite database, API router, sync protocol, and frontend state.

```
┌─────────────────────────────────────────────────┐
│                  PWA Frontend                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
│  │ Journal  │  │  Coach   │  │   Analysis   │  │
│  │  Module  │  │  Module  │  │    Module     │  │
│  └────┬─────┘  └────┬─────┘  └──────┬───────┘  │
│       │              │               │          │
│  LocalForage    LocalForage     Fetch only      │
│  (IndexedDB)   (IndexedDB)    (no local state) │
└───────┼──────────────┼───────────────┼──────────┘
        │              │               │
   HTTP Sync      HTTP Sync      HTTP Submit
        │              │               │
┌───────┼──────────────┼───────────────┼──────────┐
│       ▼              ▼               ▼          │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
│  │ /api/    │  │ /api/    │  │ /api/        │  │
│  │ journal  │  │ coach    │  │ analysis     │  │
│  └────┬─────┘  └────┬─────┘  └──────┬───────┘  │
│       │              │               │          │
│  journal.db     coach.db      analysis.db       │
│                                      │          │
│                              Claude Code CLI    │
│                               ┌──────┴───────┐  │
│                               │  MCP Tools   │  │
│                               │ journal coach │  │
│                               │    garmin     │  │
│                               └──────────────┘  │
│              FastAPI + Uvicorn                   │
└─────────────────────────────────────────────────┘
```

## Design Principles

**Module isolation.** Each module has its own database, API prefix, frontend state, and sync logic. Modules share only the FastAPI process, static file serving, and frontend shell (tab navigation). A module can be disabled without affecting others via `WELLNESS_DISABLED_MODULES`. Data-layer isolation is **structural, not by-convention**: each router captures its own injected `DbAccessor` (no module-global DB path), so nothing at module scope can leak one module's — or one instance's — database into another's.

**Offline-first.** The entire app works offline after at least one online visit. The service worker precaches the app shell — HTML, CSS, and every JS module including the vendored runtime libraries (see Frontend below); the precache list is generated server-side by walking `public/`, so it never drifts from the real asset tree. There is no third-party CDN to be unreachable. Journal and Coach persist all data locally in IndexedDB via LocalForage. The modules list is cached in localStorage so the app shell loads offline. The Analysis module caches report history and individual reports in LocalForage for offline viewing; new queries require server connectivity and show a toast if unreachable. Sync happens automatically when the server is reachable.

**No build step.** The frontend uses Preact with HTM (tagged template literals) instead of JSX. ES6 modules are loaded directly by the browser with no bundler, transpiler, or build pipeline.

**No ORM.** All database access uses raw SQLite3 with parameterized queries and context managers. Schema migrations are handled defensively with `CREATE TABLE IF NOT EXISTS` and `ALTER TABLE ADD COLUMN` wrapped in try/except.

**AI as a service, not a dependency.** The Analysis module is the only component that depends on external AI. It invokes Claude Code CLI as a subprocess, meaning the rest of the app functions without any AI infrastructure.

## Sync

Both Journal and Coach modules use a shared `SyncScheduler` class (`public/js/shared/sync-scheduler.js`) that handles automatic synchronization. Each module creates its own scheduler instance with module-specific sync functions and state getters.

### SyncScheduler

The scheduler triggers sync automatically based on:

- **Edit debounce** — When local data changes, sync is scheduled after a 2.5s debounce window to batch rapid edits
- **Periodic polling** — Every 30s (Coach first polls the cheap `/plans-version` and full-syncs only on a version change or local dirty data; Journal has no pre-check and runs a full sync every tick)
- **Network restore** — Syncs immediately when the browser comes back online
- **Page visibility** — Re-syncs when the app regains focus after being backgrounded

Error handling uses exponential backoff (5s base, 120s max). Network errors retry silently; server errors show toast notifications. The scheduler pauses when the app is backgrounded or offline.

### Journal: Optimistic Concurrency on Opaque Timestamp Tokens

The Journal tracks fine-grained daily data (supplements taken, habits checked) for a single sequential client. Sync uses optimistic concurrency on an opaque server-issued timestamp token (`_baseLastModifiedAt`) — the client wall clock is never part of the server's comparator, so client clock skew cannot cause legitimate edits to be rejected.

**Protocol:**

1. **Client registers** with a unique ID (`POST /sync/register`) — debug breadcrumb only, no correctness dependency.
2. **Pull** changes since last sync (`GET /sync/delta?since=<timestamp>`). Omitting `since` returns the full current sync window — all active trackers plus the last 7 days of entries — for initial pull / post-reinstall. The response's `serverTime` (which the client stores and sends back as the next `since`) is a **watermark stamped before the reads and offset a couple of seconds into the past** (`db.sync_watermark`), and the two reads run in one snapshot (`db.read_transaction`). This closes a boundary race: stamping `serverTime` *after* the reads let a write that committed mid-read carry a timestamp below `serverTime` yet be unseen by the pull, then be skipped forever by the next `> since`. The small overlap re-delivers such a row on the next pull; re-delivery is harmless because the client applies non-dirty rows idempotently and skips rows it holds dirty. (Coach's `GET /sync` uses the same watermark.)
3. **Upload** changed records (`POST /sync/update`). Each record carries `_baseLastModifiedAt` — the last server-issued stamp the client observed for that row. Brand-new records omit the field; the server treats absence as "INSERT only if no row exists with this key".
4. **Per-record acceptance:** if `stored.last_modified_at <= incoming._baseLastModifiedAt`, the upload overwrites the stored row and the server stamps the new `last_modified_at`. Equal timestamps accept (the typical case: client pulled, then uploads an edit without any intervening server-side change).
5. **Per-record rejection:** if the stored timestamp is strictly newer than the client's base token, the record is rejected with `errorKind: "stale"` (or `"missing"` when the server has no row matching a base-token-bearing upload). The reject response carries the current `serverRow` so the client recovers in-cycle without waiting for the next delta pull. This is also how the lost-response case settles: a retry after a successful-but-unacknowledged upload arrives with a now-stale base token, the server returns the current row, and the client adopts it — same-content edits converge.

**Sync status:** green (clean), red (dirty data), gray (offline / never synced).

**Key design choices:**
- Single-client deployment — no multi-client conflict surface, no user-visible conflict UI
- Server is the only arbiter; the client never compares two client-generated timestamps
- Soft deletes via `deleted` flag — entries persist server-side even after their tracker is soft-deleted, so MCP queries can analyze historical data
- Tracker IDs are UUIDs (`crypto.randomUUID()`), so creating a new tracker with the same name as a deleted one mints a distinct row — old and new histories stay structurally separate
- Sync delta filters `JOIN trackers ON t.deleted = 0` so the client only sees entries for active trackers; MCP read tools intentionally return entries for deleted trackers
- Overwrites archive the prior row to `entries_archive` / `trackers_archive` with a 14-day retention window for manual SQL recovery
- LocalForage carries an `app_schema_version` so a future protocol change can detect stale local data and force a clean re-pull

**Data model:**
```
trackers (id, name, category, type, meta_json, last_modified_at, deleted)
entries  (date, tracker_id, value, completed, last_modified_at)
         PRIMARY KEY (date, tracker_id)

entries_archive  (id, date, tracker_id, value, completed, last_modified_at, superseded_at)
trackers_archive (id, tracker_id, name, category, type, meta_json, deleted, last_modified_at, superseded_at)
```

The `trackers.version`, `trackers.last_modified_by`, `entries.version`, `entries.last_modified_by` columns and the `sync_conflicts` table remain in the schema as vestigial artifacts of the previous protocol — they are no longer read or written, and the physical drop is deferred indefinitely to avoid a risky `CREATE NEW + INSERT SELECT + DROP + RENAME` recreate cycle.

**Tracker `meta_json` — free-form settings passthrough.** Any tracker field the sync protocol doesn't own (`_TRACKER_RESERVED_KEYS` in `journal.py`) is serialized into `meta_json` on upload and merged back onto the tracker on read. The server does not interpret most of it — `unit`, `defaultValue`, `accumulator`, and the legacy `frequency`/`weeklyDay` ride this passthrough with full optimistic-concurrency arbitration, archiving, and delta for free, because the tracker row is the sync unit. (`scheduleHistory` and `polarity` are **no longer** in `meta_json` — they are reserved keys stored in dedicated columns; see **Canonical storage** below.) New settings are additive: no wire/schema change and no LocalForage schema bump.

**Tracker scheduling (`scheduleHistory`).** A tracker is scheduled on a set of weekdays (integers `0`=Sun..`6`=Sat). The schedule is *effective-dated* so a later change never rewrites the past: `meta_json.scheduleHistory` is an array of segments `{ effectiveFrom: 'YYYY-MM-DD', days: [0..6] }`. "Which days is this tracker expected on, as of date D" picks the segment with the greatest `effectiveFrom <= D` (falling back to the earliest segment when D precedes all of them). Absence of `scheduleHistory` means "daily" (all days) — the derivation also honors the legacy `frequency: 'weekly' + weeklyDay` shape, so pre-existing trackers need no data migration. As a one-time cleanup the client *normalizes* legacy trackers on load and on delta apply (`normalizeTrackerSchedule` / `computeNormalizedConfig`): a legacy weekly tracker becomes a single genesis segment `[weeklyDay]`, a daily/absent one just drops the fields (absence == daily), and changed trackers are marked dirty so the cleaned shape uploads and the server converges. It is idempotent (a no-op once converged) and the legacy derivation fallback is retained, so an un-normalized tracker keeps working until it does.

Writes follow an **apply-from-today** rule set (all client-side, in `public/js/journal/utils.js` `computeScheduleHistoryUpdate`): a no-op guard (an unchanged day-set writes nothing); the first edit of a legacy/daily tracker materializes a genesis segment carrying the old schedule from a far-past sentinel (`SCHEDULE_GENESIS_DATE = '0000-01-01'`, which sorts below any real date) plus the new schedule effective today; a same-day re-edit replaces the latest segment in place (keeping `effectiveFrom` strictly increasing); any later change appends a new segment. Past segments are immutable, so history stays interpreted against the schedule that was in effect at the time.

**Pausing.** A tracker is *paused* by writing an **empty-days** segment (`days: []`) — an effective-dated schedule change like any other, so it flows through the same write rules (pause appends a today `[]` segment; a same-day unpause replaces it; a new tracker can be born paused with a single genesis `[]` segment). Because the data model already treats empty scheduled-days as "no days", pause is entirely UI-level and needs no protocol change: an empty set makes `isExpectedOn` false so the grid hides the tracker (`shouldShowTracker`) and the 7-day dots render `off`, and the MCP counts zero scheduled days so every adherence rate is null ("nothing to measure"). Prior segments are untouched, so pre-pause history stays fully interpreted and the pause window is self-documenting (the `[]` segment's `effectiveFrom` is the pause date). The config UI seeds the weekday picker for a paused tracker from `lastActiveScheduleDays` (the most recent non-empty segment) so unpausing restores the pre-pause days rather than snapping to Daily. This empty-days escape hatch is why the `buildTrackerSaveFields` empty→Daily coercion (a footgun-guard against saving a tracker onto *no* days) applies **only to non-paused saves** — `paused: true` bypasses it.

**Local-date rule.** Day-of-week is derived from the *local* calendar date (`parseLocalDate(dateStr).getDay()`), and segment selection is a plain `YYYY-MM-DD` string comparison — no `Date`, no timezone. `new Date('YYYY-MM-DD')` (UTC-midnight parse) is deliberately avoided because it shifts the weekday in negative-offset timezones; a pinned unit test runs under `TZ=America/Los_Angeles` to guard this.

**Grid visibility.** A tracker shows on a date when it is *expected on that date* **or** *already has a log entry that date* (`shouldShowTracker` = `isExpectedOn` OR entry-exists). The entry-exists predicate is simply presence of a record for the tracker in that day's log — even `completed: false` — so an exceptional off-schedule entry (e.g. a weekday-only supplement taken on a weekend) stays visible and editable. This visibility rule is deliberately separate from any goal/completion semantics.

Segments are objects (not bare arrays) so a future `kind` discriminator (rolling multi-week cycles, weekly counts) can be added without another migration.

**Canonical storage.** `scheduleHistory` and `polarity` live in **protocol-owned columns** — `trackers.schedule_json` (the segments array as JSON) and `trackers.polarity` (added by migration 3, mirrored on `trackers_archive`). The server captures them from the tracker upload into the columns on every upsert, archives them with the prior row, and emits them as top-level `scheduleHistory` / `polarity` fields on read — so the **sync wire shape is unchanged** (they were already top-level in the tracker dict; only the storage location moved). They are `_TRACKER_RESERVED_KEYS`, so they are **the single source of truth and are no longer written into `meta_json`**; migration 4 stripped the transitional copies out of every live and archived `meta_json` blob (lifting into the column first for any row whose column was NULL). Rollback to a pre-columns revision therefore requires a data step: run `bin/reverse_backfill_schedule_polarity.py` to re-embed the column values into `meta_json` before reverting the code. This makes the schedule and polarity canonical server data so the journal MCP can compute schedule adherence server-side (and `list_trackers` merges the columns into its `metadata` dict, so its consumer shape is unchanged). Adherence is still deliberately kept out of `get_journal_summary`, whose completion rate stays entries-based.

**Tracker polarity.** `polarity` (the canonical `trackers.polarity` column) is an optional `'positive' | 'negative' | 'neutral'` label (absent = unspecified/neutral) describing a tracker's valence — e.g. a habit to build (`positive`), a behavior to avoid (`negative`), or a neutral measurement. The collapsed-category summary and the expanded row's 7-day dots now read it (below), and the MCP adherence tool is the other consumer; it stays out of the checkbox/completion logic. It is kept orthogonal to scheduling (meaning vs. routine).

**Tracker targets.** A quantifiable tracker can carry a typed value target — `{min?, max?}` numbers (min-only = at-least, max-only = at-most, both = range, `min == max` = exact). Like the schedule, it is **effective-dated** so a goal change never rewrites past adherence: `targetHistory` is an array of `{ effectiveFrom: 'YYYY-MM-DD', target: {min?,max?}|null }` segments, selected per date by the same shared segment rule (greatest `effectiveFrom <= D`, earliest fallback, genesis sentinel `'0000-01-01'`; a `target: null` segment records a target removed effective-dated). It is stored in the canonical **`trackers.target_json`** column — a **brand-new field, single-source from day one** (`target` / `targetHistory` are `_TRACKER_RESERVED_KEYS`, never in `meta_json`; migration 5 just adds the column, no backfill; mirrored on `trackers_archive`; emitted top-level on read, wire-invariant). The client parses a single text input ("10" → polarity-defaulted min/max, "150-170" → range) into the typed shape (`parseTarget`/`formatTarget`) and writes `targetHistory` with the same apply-from-today rules as the schedule (shared `selectSegmentForDate` on read, shared segment-edit core on write). Adherence's target-aware "met" evaluation lives in the MCP (`compute_adherence`); the journal grid additionally shows a **single-day** status inline (progress/headroom/range line per row) via a pure JS twin (`targetStatus`/`dayStatus` in `public/js/journal/utils.js`) kept faithful to `adherence.py` by a mirrored assertion table — windowed adherence math is deliberately never duplicated client-side.

**Collapsed-category summary.** Each collapsed category pill carries a schedule- and polarity-aware rollup (`categorySummary` / `formatCategorySummary` in `public/js/journal/utils.js`): among trackers *expected* that day (not-expected ≠ missed), only **actionable** ones — a non-neutral polarity (build/avoid) or a target in effect — are judged, bucketed by single-day `dayStatus`, so a checked positive habit, an avoided (un-logged) negative, and a value meeting its target all read "on track" ("3 of 4 on track" / "All on track"). Untargeted **neutral** trackers are *observations* (e.g. a "Headache" log), not goals: they are excluded from the on-track fraction and, when a category is pure observation, shown as denominator-free activity ("2 logged"); an all-observation day with no entries is suppressed. It is a single-day view (no naive completion count); windowed adherence stays in the MCP. Each expanded row also carries a **7-day dot row** (`recentDayStates`): the same single-day predicate repeated across the last seven local days (off-schedule days muted, today marked) — actionable trackers show met/partial/missed, observations show noted/quiet — giving recent texture without duplicating windowed adherence, which remains MCP-only.

### Coach: Per-Record Server-Token Arbitration

The Coach module handles workout plans (authored server-side, typically by AI) and workout logs (written by the user during a session). Plans flow one-way from server to client. Log writes use **per-record optimistic concurrency** — full journal parity (R1 + R3): a server-issued token per *record* (one feedback record + N exercise records per day), so client clock skew can never reject a legitimate edit, and a partial/multi-device payload can never destroy un-mentioned records. The client echoes the last server `_lastModified` it saw for each record as that record's `_baseLastModifiedAt`; the server compares its own *stored* stamp against that *server-issued* base, never the client's wall clock, and applies each record independently via **upsert** (never a whole-day delete-and-rebuild).

**Protocol:**

1. **Client registers** (`POST /register`)
2. **Sync pull** fetches plans (all or since last sync) and logs (30 days or since last sync) (`GET /sync?client_id=<id>&last_sync_time=<timestamp>`)
3. **Log upload** sends workout logs (`POST /sync`); the day carries a `_baseLastModifiedAt` for its feedback record and each exercise carries its own. The server arbitrates each record independently (no existing row or NULL stamp → insert; `stored.last_modified <= base` → update, replacing that exercise's sets/items; stale base → keep the server's record; **hard cutover** — a token-absent write to an existing record is rejected). Un-mentioned exercises are never touched. There is **no whole-upload reject**: the server returns the reconciled day per uploaded date in `results[date]` (the merged `serverRow`, carrying each record's `_lastModified`), which the client adopts under its generation check. The existing day is archived to `*_archive` before mutation (14-day recovery). (`_lastModifiedAt` is retained as an advisory/display field only — never the arbiter.)
4. **Change detection** via `GET /plans-version`, which returns the latest timestamp across plan edits (`workout_sessions.last_modified`), plan deletions (`deleted_plans.deleted_at`), **log writes** (`workout_session_logs.last_modified`), and **log-entry deletions** (`deleted_exercise_logs.deleted_at`) — so another device's logged sets reach a continuously-visible client on the next 30s poll, not only on a refocus. The scheduler polls this endpoint every 30 seconds, triggering a full sync when the version changes (the poll records the version it saw, so a version moved by a log stamp doesn't re-trigger every tick).
5. **Plan deletion propagation** — When a plan is deleted via MCP, a tombstone is written to `deleted_plans`. Incremental sync includes a `deletedPlanDates` array for tombstones newer than `last_sync_time`. The client removes those dates from local storage. Tombstones are pruned automatically when they age out of the sync window. Only future/unlogged plans can be deleted — plans with workout logs are immutable.
6. **Log-entry deletion propagation** — The client deletes one exercise entry (today: the ad-hoc extra session's Delete button) by replacing it with a local tombstone `{"_deleted": true, "_lastModified": <last server stamp>}`; an UNSTAMPED entry (never adopted a sync response) becomes a stampless tombstone — it uploads, is rejected against an existing row (hard cutover), and the adopted server row lets the user re-delete with a fresh stamp, instead of the old remove-the-key behavior that silently resurrected the server copy. A day carrying a pending tombstone always uploads (`logHasPendingDeletions`), even contentless and never-synced. The tombstone rides the normal upload (`withBaseTokens` echoes its stamp as `_baseLastModifiedAt`) and is arbitrated with the same `should_accept_log_write` predicate: accepted → the server hard-DELETEs the `exercise_logs` row (children removed explicitly; the day was already archived) and records `(date, exercise_key, deleted_at)` in `deleted_exercise_logs`; stale base (a remote edit won) → rejected, the surviving row returns in `results[date]` and the deleting client re-adopts it (server-wins); row already gone (retry) → idempotent tombstone refresh. On result adoption, an uploaded tombstone is always resolved by its verdict — even on a date re-modified mid-sync, where other records keep local content: `serverRow` carries the key → the delete lost, adopt the surviving record; key absent → drop the tombstone. (Merely advancing the tombstone's token there turned a REJECTED delete into an accepted one on retry, destroying the other client's newer edit.) The tombstone then guards against **resurrection**: a later edit that echoes a base token for the deleted record is rejected (delete wins — the token proves it edits the deleted row), while a token-less create is a deliberate re-add and clears the tombstone. **Re-add over a pending local tombstone** (delete → immediately add again, before the delete synced): `withEntryUpdated` drops `_deleted`, KEEPS the stamp, and marks the entry `_readd: true` — against a still-live server row the kept stamp wins as a normal update; against an already-deleted row the `_readd` marker tells the resurrection guard this client authored the delete itself, so the insert is accepted and the tombstone cleared. (`_readd` is transient — never stored or echoed by the server.) Other clients converge because the incremental log query re-delivers a day whose tombstone is newer than `last_sync_time` (a hard DELETE leaves no child stamp to move the day) — the adopted server day simply lacks the key. Deleting the last entry keeps the (empty) `workout_session_logs` row, matching the existing emptied-synced-day behavior. Tombstones are pruned with `deleted_plans` when they age out of the sync window.

**Sync safety layers:**
- **HTTP cache prevention** — `GET /sync` returns `Cache-Control: no-cache, no-store, must-revalidate`; client fetch calls use `cache: 'no-store'`. Prevents stale sync responses from being served from browser HTTP cache (e.g., after Android storage clear).
- **Per-record arbitration** — `_store_log()` arbitrates the feedback record and each exercise record via the pure `coach_logs.should_accept_log_write(stored, base)`: server-stamp vs server-issued base token, no client clock. Accepted records upsert; stale records keep the server's version. The reconciled day is returned in `results[date]` for the client to adopt.
- **Upsert, not delete-rebuild** — exercises absent from a payload are never touched, so a partial or multi-device upload (`{ex1,ex3}` over server `{ex1,ex2}`) merges to `{ex1,ex2,ex3}` instead of dropping `ex2`. (The R3 successor to the old whole-day delete-and-rebuild and its zero-exercise content guard, both removed — feedback-only uploads are now safe and accepted.)
- **Batch transaction wrapping** — `workout_sync_post` wraps the multi-date `_store_log` loop in a `BEGIN IMMEDIATE` transaction with auto-rollback on failure (atomic across dates, and across the coach MCP writer process).
- **Soft-delete archive** — Before mutating an existing day, all its data (session log, exercise logs, set logs) is copied to `*_archive` with a `superseded_at` timestamp (defensive, so any overwrite is recoverable). Archives older than 14 days are purged during sync.
- **Client upload selection** — The upload phase sends a dirty date's log when it carries content **or** when the server already knows the day (a token-bearing *empty* log is a deletion update: each emptied record echoes its base token, the server clears its copy under normal per-record arbitration). Dirty dates that can never upload — the log was pruned out of the sync window, or is empty and was never synced — are dropped from the dirty set with a debug breadcrumb instead of wedging the client red forever. The set of dates whose dirty flag clears via the generation check is exactly the set sent. Both `triggerSync` and `forceSync` echo per-record tokens and adopt the reconciled `results` day.

**Sync status:** green (clean), red (dirty logs), gray (offline).

**Key design choices:**
- Plans are read-only from the client's perspective (created via MCP or direct DB access)
- Logs use per-record server-token arbitration (R1 + R3): a server-issued token per feedback/exercise record applied via upsert, never the client clock and never a whole-day rebuild — full journal parity (un-mentioned records preserved; conflicting records resolve server-wins)
- Relational plan structure: session -> blocks -> exercises -> checklist items
- Relational log structure: session log -> exercise logs -> set logs
- Canonical exercise slugs link planned exercises to logged exercises and the exercise registry
- Plans with logs cannot be deleted — the MCP delete tool enforces this and directs the caller to use edit tools instead

**Data model (plans):**
```
workout_sessions   (id, date, day_name, location, phase, duration_min)
session_blocks     (id, session_id, position, block_type, title, duration_min,
                    rest_guidance, rounds, work_duration_sec, rest_duration_sec)
planned_exercises  (id, session_id, block_id, exercise_key, name, exercise_type,
                    targets..., superset_group, tempo, target_rpe, target_load,
                    canonical_slug)
checklist_items    (id, exercise_id, position, item_text)
deleted_plans      (date, deleted_at)  -- tombstones for incremental sync
```

`rounds` / `work_duration_sec` / `rest_duration_sec` on `session_blocks` are
the canonical home for circuit and interval timing — it describes the whole
block, not a single exercise (do all the block's exercises, that's one round,
repeat). They were added by migration (`ALTER TABLE` in `init_database`).

`superset_group` is a free-form text label scoped per block — consecutive
exercises sharing the same label render as a single visual group in the UI
(e.g. antagonist pair, triplet). Plan authors set it via the structured field;
encoding pair info in the exercise `name` (e.g. `"Bench Press (Pair A)"`) is
rejected by the server because the suffix would leak into `canonical_slug`
and break cross-session comparison.

`tempo`, `target_rpe`, and `target_load` are the optional strength-prescription
*modifiers*: all free-form TEXT (`target_rpe` may be a range like `"6-7"`;
`target_load` is free-form, e.g. `"70%"` / `"24kg"` / `"level 5"`), display-only,
and server-authoritative. They ride the existing plan-sync payload —
`assemble_plan` (the one reader for both the sync GET and the MCP read tools)
emits them, so no extra endpoint or client-store change is needed. `tempo` was
added by migration 4; `target_rpe` / `target_load` by migration 5 (guarded
`ALTER TABLE planned_exercises`). They superseded folding cues into
`guidance_note` (tempo's `"Tempo X"` substring; the `load_guide` cue → now
`target_load`); historical notes are left as-is (no backfill).

Progression is tracked through *logs*, not plans, so these prescription fields
are intentionally free-form (not numerically modelled). The mandatory targets
(sets × reps, or duration) render in the always-visible exercise header
(`formatTarget`); these optional modifiers render together in one compact
"prescription line" in the expanded body (`buildPrescription` → RPE · load ·
tempo, omitting absent tokens). Per-set nuance ("last set RPE 9", drop sets)
stays in `guidance_note`.

**Ingest & transform pipeline:**

`ingest_training_program` (coach MCP) accepts plans in two shapes and routes them
through `_transform_block_plan`:

- **Raw LLM block format** — blocks contain either `instructions: [...]` text
  (cardio) or `exercises: [...]` whose entries lack a `type`.
  `_transform_block_plan` walks each block and calls
  `_transform_block_to_exercises` to derive types from `block_type`,
  aggregate raw warmup movements into a checklist, and split cardio
  `instructions` into a single exercise with `type: duration` (Zone 2) or
  `type: interval` (when the text mentions VO2/HARD).
- **Transformed format** — exercises already carry a `type`. Blocks in this
  shape pass through verbatim, so re-ingesting an existing plan is a no-op.

`type` — not `id` — is the marker of an already-formed exercise: the per-block
predicate is "every exercise has a `type`" (matching `_needs_transform`), so a
plan mixing raw and transformed blocks only transforms the raw ones. A missing
`id` does **not** by itself force a block through the transform; ids are
backfilled afterwards by `_ensure_exercise_ids`, an idempotent pass that
assigns a deterministic `{block_type}_{block}_{n}` id to any exercise lacking
one. This split keeps the transform lossless for a pre-formed exercise that is
merely missing its id — `_transform_block_to_exercises` copies each input
exercise and only *fills* absent canonical fields, and the warmup branch
preserves a pre-built checklist's `items` instead of rebuilding them from the
exercise name.

For interval/circuit blocks the LLM emits structured timing fields at the
**block** level:

```json
{
  "block_type": "cardio",
  "duration_min": 20,
  "rounds": 4,
  "work_duration_sec": 180,
  "rest_duration_sec": 120,
  "instructions": ["4 x (3 min HARD / 2 min easy)", "HR 160-175"]
}
```

These stay on the block — `_transform_block_to_exercises` does **not** copy
them onto the synthesized cardio exercise. `BlockView` renders a compact
timing badge in the block header (`formatInterval` → `4 × 3:00/2:00`), and an
interval exercise's `formatTarget` falls back to the block's timing when it
doesn't carry its own. (Pre-canonical-block plans that stored timing on the
exercise still render, because `formatTarget` checks the exercise first.)

Likewise, a block's `rest_guidance` text stays on the block — it is **not**
folded into exercise `guidance_note` fields. An exercise's `guidance_note`
carries only exercise-specific cues (`load_guide`, `notes`); `tempo` is a
structured field of its own and is no longer folded into the note.

**Editing plans in place:**

`set_workout_plan` / `ingest_training_program` replace a whole plan (delete +
re-insert, blocked when a workout log exists). For everything short of that,
the coach MCP exposes granular editors that mutate the relational rows
directly and bump `workout_sessions.last_modified` so the next sync picks the
change up:

| Level | Tools |
|-------|-------|
| Plan metadata | `update_plan_metadata` (day_name, location, phase, total_duration_min) |
| Exercise | `update_exercise`, `add_exercise`, `remove_exercise` |
| Block | `update_block`, `add_block`, `remove_block`, `reorder_blocks` |

Block tools address a block by its 0-indexed `position`. `update_block` patches
`block_type` / `title` / `duration_min` / `rest_guidance` and the canonical
timing fields (`rounds` / `work_duration_sec` / `rest_duration_sec`).
`add_block` inserts a block (appending or shifting later blocks down) and runs
any inline `exercises` / `instructions` through the same transform
`set_workout_plan` uses, keeping new exercise keys unique within the session;
the block-write path (`_insert_block`) is shared with `_store_plan_to_db`.
`remove_block` refuses to drop a non-empty block unless `force=true` and
re-packs positions; `reorder_blocks` takes a permutation of `0..N-1` and
rewrites positions in a two-phase update (park in `N..2N-1`, then settle) to
respect `UNIQUE(session_id, position)`. None of the block tools are
log-guarded — like `remove_exercise`, removing an exercise that has a log
entry leaves that entry without a matching plan exercise.

**Data model (logs):**
```
workout_session_logs  (id, session_id, date, pain_discomfort, general_notes)
exercise_logs         (id, session_log_id, exercise_id, exercise_key, user_note, duration_min, avg_hr, max_hr)
set_logs              (id, exercise_log_id, set_num, weight, reps, rpe, unit, duration_sec, completed)
checklist_log_items   (id, exercise_log_id, item_text)
deleted_exercise_logs (date, exercise_key, deleted_at)  -- log-entry tombstones (migration 6)
```

**Completion is derived, not stored.** There is no exercise- or session-level `completed`
column. An exercise's completion is computed at read time from its logged data
(`src/modules/coach_completion.py`): the read tools return `attempted` (any data logged),
`completed` (the planned target met — `None` when the target is unknown), and `progress`
(`{done, target}`). Rules by type: strength/circuit/weighted_time → done-sets vs `target_sets`;
checklist → logged items vs planned items; duration/interval → `duration_min` vs
`target_duration_min`. Sessions roll up to "fully completed" when every planned exercise is
completed. The legacy per-exercise `completed` flag was dropped (2026-05) because it was a manual
PWA checkbox decoupled from data entry and read false on real, fully-logged work; only the
per-set `set_logs.completed` "done" tick is retained, as an input to the derivation.

**Off-plan (extra) sessions.** The PWA's rest-day empty state (today only —
the standard `isEditable` rule) offers an **"Add Zone 2 session"** button:
draft duration / avg HR / max HR fields held in component state until Save
(Save requires a duration, since a duration-less cardio entry carries no
uploadable content), which commits the entry to the log store under the
well-known key `extra_zone2` (`EXTRA_SESSION_KEY` in `public/js/coach/utils.js`);
from then on it edits/auto-saves like planned cardio, and Delete tombstones it
(protocol point 6). There is **no explicit flag**: an entry is off-plan when
`exercise_logs.exercise_id` is NULL AND (the day has no plan —
`workout_session_logs.session_id` NULL — OR the key is a well-known ad-hoc key;
`is_off_plan_entry` in `src/modules/coach_logs.py` is the single definition).
The ad-hoc-key arm keeps the label correct when a plan is authored AFTER the
extra synced (the next upload relinks the day's `session_id` — `store_plan`'s
log guard is scoped to *replacing* a plan, so creating the first plan on an
extra-session date is allowed), and keeps orphaned logs of removed planned
exercises from reading as extras. The server maps ad-hoc keys to registry
slugs (`AD_HOC_LOG_SLUGS` in `coach_logs.py`, `extra_zone2` → `zone_2`,
self-healing the registry row) so extras appear in `get_exercise_history`
alongside planned Zone 2 work. Read surfaces mark the signal explicitly:
`assemble_log` (rich/MCP shape only) and `exercise_history` emit
`off_plan: true` per entry, `get_workout_logs` marks the day wrapper
(`session_id` NULL), and `get_workout_summary` reports extras separately as
`extra_sessions` / `extra_session_dates` (dates with ≥1 content-bearing
off-plan entry) while `completed_workouts` counts only plan-linked days
carrying feedback or plan-linked exercise content — so rates can't exceed
100%, an emptied husk row counts nowhere, and an extra-only relinked day is
not a "completed planned workout". The analysis prompt schema hint
(`_COACH_SCHEMA` in `src/modules/analysis_queries.py`) documents the
convention so SQL-writing analyses credit extras as additional volume, never
plan adherence. A rest day whose log carries content earns the calendar's
`completed` dot.

**Data model (log archives — 14-day retention):**
```
workout_session_logs_archive  (id, original_id, date, superseded_at, superseded_by, ...)
exercise_logs_archive         (id, original_id, session_log_id, exercise_key, ...)
set_logs_archive              (id, original_id, exercise_log_id, set_num, weight, reps, ...)
```

**Data model (hooks):**
```
workout_hook_results  (id, session_id, hook_type, fired_at, exit_code)
                      UNIQUE(session_id, hook_type)
workout_hook_data     (id, result_id, key, value)
                      UNIQUE(result_id, key)
```

### Workout Hooks

The Coach module supports configurable pre/post-workout hooks — shell scripts that fire when the user taps Start/End Workout. Hook script paths are resolved via `PRE_WORKOUT_HOOK` / `POST_WORKOUT_HOOK` env vars, falling back to example scripts in `bin/`.

**Execution flow:**

1. Client sends `POST /api/coach/workout/{session_id}/start` (or `/end`)
2. Server upserts a `workout_hook_results` row (exit_code = NULL, indicating pending)
3. Server spawns the hook script via `asyncio.create_subprocess_exec` and returns immediately
4. Client shows the button as green based on the HTTP 200, not script completion
5. When the script finishes: exit code is stored; stdout is parsed as JSON and key/value pairs are stored in `workout_hook_data`
6. Undo deletes the result row (cascade deletes data); retry upserts and re-fires

**API endpoints:**

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/coach/workout/{session_id}/start` | Notify server that workout is starting |
| `POST` | `/api/coach/workout/{session_id}/end` | Notify server that workout has ended |
| `DELETE` | `/api/coach/workout/{session_id}/start` | Undo a workout start |
| `DELETE` | `/api/coach/workout/{session_id}/end` | Undo a workout end |
| `GET` | `/api/coach/workout/{session_id}/status` | Get workout status for a session |
| `GET` | `/api/coach/workout/config` | Get available workout actions |

The API is modeled around user actions (start/end workout), not the underlying hook mechanism. The frontend doesn't need to know that hooks exist — it just tells the server the workout is starting or ending, and the server decides what to do.

**Frontend behavior:**

The workout day header becomes collapsible when workout actions are available. Expanding it reveals Start/End Workout buttons. The Start button locks (no undo) once exercise data is entered. When no actions are configured, the header displays normally with no collapsible behavior.

**Exercise entry gating:**

When a Start Workout action is configured, exercises are read-only until the user taps Start at least once. This ensures pre-workout hooks (e.g., Garmin stats snapshot) aren't forgotten. The gate unlocks when any of these conditions is true:

1. The user clicked Start (any outcome — success, failure, or pending counts)
2. Exercise data already exists in the log (crash recovery / returning to a workout in progress)
3. The status fetch failed (offline fallback — never lock the user out)
4. No Start action is configured (no gate, behaves as before)

This means the pre-workout hook won't fire when offline, which is intentional: the hook captures live pre-workout stats that aren't available without server connectivity.

### Force Sync

Both modules support force sync (accessible from the settings menu). Force sync reports per-module counts of uploaded and accepted records.

Both modules' force sync is server-arbitrated — the normal sync flow with a full pull; the client never decides a winner by comparing client-generated timestamps.

Coach's force sync uploads the dirty set through the same per-record base-token contract as normal sync (`selectLogsToUpload`/`withBaseTokens`), adopts the server's reconciled `results`, then downloads the full window (no `last_sync_time`) and applies it to non-dirty dates only. Dirty flags clear only for dates actually sent whose generation counter is unchanged, so an edit made mid-force-sync stays dirty for the next cycle.

Journal's force sync does a full pull (delta with no `since`), then re-uploads all currently dirty records through the same `_baseLastModifiedAt` optimistic-concurrency contract used by normal sync. Generation counters are snapshotted before the upload so concurrent edits during the force sync stay dirty and get picked up by the next cycle.

### Analysis: Offline Cache (No Sync)

The Analysis module has no bidirectional sync protocol — all authoritative state lives on the server. However, the frontend caches data in LocalForage for offline access:

- **Report history list** — cached after each successful `loadHistory()` call. When offline, the History tab shows the cached list.
- **Individual reports** — cached after each successful `loadReport()` call (completed/failed reports only). Cached reports are viewable offline.
- **Submitting new queries** requires server connectivity. If the server is unreachable, a toast notifies the user.
- **Initialization** — if `loadQueries()`/`checkPending()` fail on init (server unreachable), the module opens to History view with cached data rather than showing an error.

**Stale report recovery** has two layers:

- **Startup recovery** — any reports left in `status='running'` or `status='pending'` from a previous server crash are marked `status='failed'`. This runs once at startup, before the server accepts requests, so a non-terminal row is necessarily orphaned (no async task survives a restart) — including the case where the crash landed in the brief `create_report`→`update_report_running` window, leaving a stuck `pending` row.
- **Runtime reaper** — new reports are created via `create_report_if_idle` (`analysis_db.py`), a single `BEGIN IMMEDIATE` transaction that first fails any pending/running report older than the largest registered query timeout plus a grace period, then inserts the new report only if no live one remains (returning `None` → the caller answers 409). The atomicity replaced the racy `has_active_report` + `create_report` two-step (a double-tap could launch two CLI subprocesses); the age-gated reap covers the residual case where a report's terminal write itself failed, which under startup-only recovery wedged the 409 guard until the next restart. The age gate is what makes the sweep safe — a legitimately long-running report is never reaped.

**Flow:**
1. User selects a pre-built query from the UI
2. Server creates a report record (status: pending) and launches Claude Code CLI as an async subprocess
3. Frontend polls `GET /reports/pending` until the report completes
4. Claude Code CLI runs with MCP tool access, generating a markdown report
5. Report is stored in `analysis.db` and displayed in the UI

**Runtime toggle.** Analysis is enabled by default, like every module. A
deployment that prefers Trends (below) for glanceable stats and interactive
LLM sessions for interpretation can switch it off with
`WELLNESS_DISABLED_MODULES=analysis`, which unmounts its routes (API 404s),
drops it from `/api/modules` (no tab), and skips its startup recovery. The
module stays maintained and tested regardless of the toggle.

### Trends: Read-Only Cross-Module Aggregates

Trends is the "what happened" surface: deterministic chart aggregates over
coach + journal + Garmin data — zero LLM, no prose insights (interpretation
lives in interactive Claude sessions against the MCP servers). Product spec
and decisions: `plans/trends-module-feature-set.md` (local-only).

**The deliberate exception to module DB isolation.** Trends owns NO database.
Its registry entry has no `db_env`/`db_default`; `create_app` calls its
factory with no argument, and the factory builds its OWN **read-only**
accessors (`DbAccessor(..., read_only=True)`, sqlite URI `mode=ro`) to the
coach/journal paths via `config.get_module_db_path` — honoring the same env
vars the owners use, which is also what isolates tests for free — plus the
external Garmin health DB (`GARMIN_DB_PATH`, default `~/.garmy/health.db`,
written by the user's sync job). `mode=ro` refuses writes AND refuses to
create missing files; a missing/unmigrated source surfaces as 503 on coach/
journal endpoints and as `{"available": false}` on `/weight` (an absent
Garmin DB is a supported state — the chart hides). Note: reading a WAL DB via
`mode=ro` needs `-shm` file access, so reader and writer must be the same
user (they are: one server process; the Garmin job runs as the user).
Multi-SELECT aggregates run inside `read_transaction` for a consistent
snapshot while the owning modules write. Trends never writes, runs no
migrations, and must NEVER borrow the owning module's accessor.

**Aggregation conventions** (`src/modules/trends_queries.py`): local calendar
dates; ISO Monday weeks (weekly aggregates floor `start` to Monday, the week
containing today is `"partial": true` so it isn't compared against complete
weeks); per-exercise weights in the exercise's dominant logged unit,
cross-exercise sums in kg; Epley e1RM with the true-single special case;
qualifying set = logged weight+reps (the legacy per-set `completed` tick is
ignored); Zone 2 is exercise-TYPE-based (`duration`) because prod slugs are
fragmented; off-plan attribution reuses `coach_logs.is_off_plan_entry`
semantics with keys from `AD_HOC_LOG_SLUGS`; ASSISTED exercises (registry
`exercises.equipment='assisted'` — the logged weight is machine assistance,
more = easier) are scored by EFFECTIVE load = Garmin body weight (nearest
sample at-or-before the session, else earliest after) minus assistance across
every strength aggregate (series, bests, PRs, tonnage), with the raw
assistance echoed as `assistance`; when no body weight is resolvable those
sets DROP OUT of the aggregates rather than being ranked as if the assistance
were lifted (the classification is registry data — ingest infers `assisted`
from the name as a default, but the math never parses names); journal weekly
adherence = one
`compute_adherence` call per week bucket (the shared function stays
untouched — it lives in `modules/journal_adherence.py`, extracted from the
journal MCP which now re-exports); NEUTRAL (non-actionable) trackers get
`weekly_usage` entries-per-week buckets instead of an adherence ribbon —
for episodic observations (an as-needed med) the trend is frequency, not
the often-constant value; streaks count scheduled days only (pause
windows transparent; an unmet TODAY doesn't break the current streak unless
the miss is already irreversible — a negative-polarity lapse entry or an
at-most target exceeded); PRs =
session e1RM strictly above the slug's prior all-time max (first session is
baseline). Overview thresholds are config-free constants in
`trends_queries.py`.

**Health tab (v2 Phase 1).** Non-training body signals over the same range
selector: HRV (last-night avg, dots below Garmin's low-zone ceiling in the
warning tone) charted against **Garmin's own baseline band**
(`hrv_baseline_balanced_low/upper`, rendered via the stepped-band primitive
grouped by `dailyBandSegments` — no invented thresholds), resting HR with
7d/28d rolling means, sleep hours with the score on a fixed right-hand scale
and an 8h guide, and compact weekly tonnage / Zone 2 strips for
training-load context (deliberately NO computed correlations — aligned axes
only). One endpoint, `/api/trends/health/recovery`, reads
`daily_health_metrics` through the existing Garmin accessor with the same
degradation contract as `/weight` (absent DB / missing table →
`{"available": false}`, never a 500); per-field nulls pass through with no
imputation. **Phase 2 — body composition:** `/api/trends/health/composition`
reads the BodySpec DEXA DB (`BODYSPEC_DB_PATH`, default
`~/.bodyspecy/bodyspec.db` — a second external read-only source with the
same degradation contract, tolerant of the sync tool rewriting the file
mid-read). It returns ALL scans up to `end` (scans are months apart; the
range selector doesn't apply — the weight-chart overlay filters
client-side). The Health tab renders total-mass scan RINGS on the body-weight
chart (the scale-vs-DEXA sanity check — lean/fat deliberately do NOT share
that axis, which would flatten the weight trend) plus composition
small-multiples (lean/fat/bf%/VAT/A-G) and a per-scan whole-body BMD table.
Design record: `plans/trends-v2-design-2026-07-09.md` (local).

**Frontend** (`public/js/trends/`): hand-rolled SVG charts — all
data→geometry math is PURE in `chart-logic.js` (node:test target, the
sync-logic purity pattern); components are thin htm/preact consumers styled
via CSS variables. Offline: `store.js fetchCached` is network-first with a
LocalForage write-through carrying `fetchedAt`; on network failure the cached
payload serves and a stale badge shows its age (the Analysis cache pattern
plus the staleness stamp it lacked). Range selector 4w/12w/6m/All is shared
across screens.

**Testing pattern** (product decision): endpoints tested hard (exact JSON,
`test/trends/`), chart geometry as pure node tests
(`test/js/trends-chart-logic.test.js`), e2e minimal and structural (tab
renders, picker flow, offline cache + stale badge) — NO pixel/visual
assertions; the user reviews visuals directly. Both test harnesses pin
`GARMIN_DB_PATH` to a nonexistent temp path so no test ever reads the real
`~/.garmy/health.db`.

## Shared Frontend Utilities

The `public/js/shared/` directory contains cross-module utilities:

- **`sync-scheduler.js`** — `SyncScheduler` class used by both Journal and Coach stores (see above)
- **`dirty-set.js`** — The one implementation of the dirty-tracking generation machinery (mark → snapshot → clear-if-generation-unchanged) used by journal trackers, journal entries, and coach dates
- **`settings.js`** — Settings modal with debug log download, data export, and force sync
- **`debug-log.js`** — Fire-and-forget logging to IndexedDB (max 500 entries, 1-hour TTL) for sync troubleshooting
- **`data-export.js`** — Exports all LocalForage data (journal, coach, app state) as a timestamped JSON file
- **`force-sync.js`** — Orchestrates force sync across both modules and aggregates results
- **`header.js`** — Shared app header with sync status indicator and settings gear

## Technical Stack

### Backend

**FastAPI** serves as the unified web framework. Each module contributes an `APIRouter` via a factory function (`create_router(db_path)`) that initializes its database and returns the router. The factory builds a `DbAccessor` (Journal/Coach) or captures the db_path (Analysis) and binds the route handlers to it as closures — the module holds **no mutable global DB path**, so two routers for the same module can target different databases in one process (proven by `test/test_module_isolation.py`). `server.create_app(db_path_overrides=None)` builds the inner ASGI app and mounts every enabled module's router at `/api/journal`, `/api/coach`, and `/api/analysis`; production builds it once at the server entrypoint (`python src/server.py`), while tests call it per-test with temp-path overrides to get fully isolated app+DB instances without poking module state. Importing the `server` module is **side-effect-free** — no app is constructed at import time, so the module migrations and the analysis stale-report recovery run only on an actual server start, never as a side effect of a test or tool importing `server`.

**Path-based routing.** The app runs under a `/wellness` URL prefix (`BASE_PATH` in `server.py`). All frontend paths are prefixed (e.g., `/wellness/api/journal/sync`), while backend routes stay at root (`/api/journal/sync`). A `StripPrefixMiddleware` ASGI wrapper strips the prefix from incoming requests, enabling the app to work both behind Tailscale `serve --set-path /wellness` (which also strips the prefix) and via direct access at `localhost:9000/wellness/`. The server injects `$BASE_PATH$` into `sw.js` at serve time for service worker path matching.

**SQLite** is used directly (no ORM) with one database file per module. This keeps modules fully isolated at the data layer and simplifies deployment (no database server required). Foreign key constraints are enforced via `PRAGMA foreign_keys = ON` in the Coach module where relational integrity matters (the Coach `DbAccessor` is constructed with `foreign_keys=True`). Both the shared `db.get_db` (used directly and through each module's `DbAccessor`) and Coach MCP configure `PRAGMA busy_timeout = 5000` (5 seconds) to handle concurrent database access gracefully instead of immediately throwing `SQLITE_BUSY`.

**Uvicorn** runs the ASGI application. The server control script (`bin/server.sh`) manages the process via PID files and port detection.

### Frontend

**Preact** (10.19.3) with **Signals** for reactive state management. Components are written using **HTM** tagged template literals, eliminating the need for JSX and any build tooling.

The six runtime libraries (Preact, Preact Hooks, Signals, HTM, LocalForage, marked) are **vendored same-origin** under `public/js/vendor/` rather than loaded from a CDN, so the offline PWA has no third-party runtime dependency and the service-worker install can never fail on an unreachable CDN. The import map in `index.html` maps the bare specifiers to those files. `preact/hooks` and `@preact/signals` import the bare `preact` specifier, which the import map resolves to the single vendored `preact.js` — preserving the one-preact-instance invariant they require. See `public/js/vendor/README.md` for provenance and how to regenerate/upgrade.

Each module's `initializeStore()` is **idempotent** — switching tabs remounts the view but never re-runs the full store load + sync; the `SyncScheduler` started on first init keeps that module synced thereafter.

Each module follows a consistent pattern:
- `View.js` - Root component with initialization logic
- `store.js` - Preact Signals state (reactive variables and computed values)
- `components/` - UI components
- `utils.js` - Helper functions

**LocalForage** provides persistent client-side storage backed by IndexedDB. Journal and Coach modules store all data locally and sync to the server, enabling full offline operation.

**Service Worker** (`sw.js`) and **PWA Manifest** enable installation on mobile devices and offline access to the shell. The SW serves the app shell network-first with a cache fallback, and `/api/*` network-only (LocalForage owns offline data). Its precache list (`APP_SHELL_URLS`) is **injected at serve time** by `server._app_shell_urls()`, which walks `public/` for every JS module (vendored libraries included) plus the shell assets — so a newly added component or library is precached automatically with no hand-maintained list to drift. `$SERVER_VERSION$` (derived from the committed build stamp) ties the cache lifetime to the build.

### MCP Servers

Two **FastMCP** servers expose wellness data to LLMs:

- **Journal MCP** - Strictly read-only. Opens SQLite in read-only mode (`?mode=ro`). Validates all queries to ensure only SELECT/WITH statements run. Auto-applies row limits. Exposes `get_schedule_adherence`, which computes schedule adherence server-side (in Python, over the canonical `schedule_json` / `polarity` / `target_json` columns): per tracker over a window it counts scheduled vs. logged/done days and reports a per-polarity metric (`adherence` / `avoidance` / `coverage`). When a tracker has a **target** in effect on a day, "done" for that day is whether the day's *value* meets the effective-dated target (not the `completed` checkbox — this fixes the accumulator undercount, where value logging never sets the checkbox); the result then adds `target` (echoed as of window end), `target_met_days`, `target_partial_days` (both targeted-day-only), and `blended_met_days` — the per-polarity rate's numerator, which on days *before* a target took effect falls back to that day's untargeted criterion (positive → checkbox, negative → no-entry avoided), so a window spanning the target's introduction isn't misread as failed. Weekly Trends buckets display `blended_met_days` for the same reason. **No-entry rule:** a scheduled day with no entry counts as *met* for negative-polarity trackers (absence = avoided) and *missed* for positive/neutral. Non-numeric values (a tracker converted from/to type `note` shares the `entries.value` column) coerce to "no usable value" → *missed*, mirrored in the client twin (`_coerce_numeric` / `coerceNumericValue` — a raw NaN comparison silently read as in-range). An un-normalized legacy weekly tracker (`schedule_json` NULL, `meta_json` `frequency`/`weeklyDay`) is judged weekly via the same fallback the client uses, not daily. The default window is `days` calendar days inclusive of both ends (days=7 → today + 6 prior, matching the PWA dot row); a tracker whose entries all precede the window is still reported (an abandoned habit at 0% is signal), while never-used / first-active-after-window trackers are omitted. `get_journal_summary` is unchanged and stays entries-based — completion and adherence are deliberately separate.
- **Coach MCP** - Read-only for queries and logs. Write access for workout plan management (creating/updating/deleting plans). Deleting a plan is guarded: plans with workout logs attached cannot be deleted, preserving training history integrity. Uses a mode-switching connection manager. Workout logs include pre/post workout stats (readiness metrics, recovery data) when available.

Both servers run over stdio transport when invoked by Claude Code CLI. They can also be configured for HTTP/SSE transport.

### Analysis Pipeline

The Analysis module bridges the web app with Claude Code CLI:

1. Pre-configured query templates define the prompt and allowed MCP tools
2. `asyncio.create_subprocess_exec` launches `claude -p` with `--verbose --output-format stream-json --model sonnet`
3. The CLI runs in a configurable working directory where MCP servers are configured
4. Output is parsed from the stream-json format; the final result object contains both the response text and execution metadata
5. CLI metadata (`duration_ms`, `duration_api_ms`, `num_turns`, `total_cost_usd`, `mcp_servers`) is stored alongside the report in `analysis.db`
6. The full stream is saved to `.wellness/data/last_stream.jsonl` for debugging
7. Queries time out after 180 seconds by default; a query can register its own `timeout` (the built-in weekly review uses 400s)

Default allowed tools for analysis queries: `mcp__journal-localdb__*`, `mcp__coach-localdb__*`, `mcp__garmy-localdb__*`, `Read`, `Glob`, `Grep`. Individual queries can grant additional tools via `extra_allowed_tools`.

### Custom Analysis Queries

Analysis queries are split into two files in `src/modules/`:

- **`analysis_queries.py`** - Generic queries safe for version control (post-workout, pre-workout, weekly review)
- **`user_queries.py`** - Personal queries that may contain sensitive data (gitignored)

User queries are loaded first and merged with the built-in queries. To add custom queries, copy the example file and edit:

```bash
cp src/modules/user_queries.example.py src/modules/user_queries.py
```

Each query is a dict with these fields:

| Field | Required | Description |
|-------|----------|-------------|
| `id` | yes | Unique identifier, used in API calls |
| `label` | yes | Display name shown in the UI |
| `description` | yes | Short description shown below the label |
| `prompt_template` | yes | The prompt sent to Claude Code CLI |
| `icon` | no | Icon name rendered on the query card. Known names: `dumbbell`, `zap`, `calendar`, `heart-pulse`, `trending-up`. Unknown or omitted values fall back to a neutral document glyph. |
| `accepts_location` | no | If `true`, the UI shows a location input field |
| `extra_allowed_tools` | no | Additional tools beyond the defaults (e.g., `["WebSearch", "WebFetch"]`) |
| `timeout` | no | Per-query timeout in seconds (default 180) |

Two template variables are available in `prompt_template`:

- `{arguments}` - Replaced with the user-provided location/arguments, or `"(none)"`
- `{current_time}` - Replaced with the current date and time string

Example custom query:

```python
QUERIES = [
    {
        "id": "migraine_check",
        "label": "Migraine Risk Check",
        "description": "Assess migraine triggers for today or tomorrow",
        "accepts_location": True,
        "extra_allowed_tools": ["WebSearch", "WebFetch"],
        "prompt_template": (
            "Run a migraine trigger check.\n\n"
            "**Arguments:** {arguments}\n"
            "**Current time:** {current_time}\n\n"
            "Use Journal MCP for supplement and symptom data, "
            "Coach MCP for workout schedule, "
            "and WebFetch for barometric pressure from NOAA.\n\n"
            "Evaluate triggers and report risk level."
        ),
    },
]
```

Custom queries appear in the Analysis UI alongside built-in queries. The server must be restarted after adding or modifying queries.

## Testing

**Pytest** with `pytest-asyncio` for async test support. Tests are organized by module:

- **Unit tests** (`test/test_*.py`, `test/*/unit/`) — Isolated function and class tests (top-level files plus per-module `unit/` dirs)
- **Integration tests** (`test/integration/`, `test/*/integration/`) — API and cross-component tests with temp databases
- **E2E browser tests** (`test/e2e_browser/`) — Playwright tests that run against a real server with seeded databases, covering navigation, sync, offline behavior, and responsive layout
- **JS unit tests** (`test/js/`) — `node:test` suites for the pure client sync-logic modules; run with `node --test test/js/*.test.js`

Key testing patterns:
- Each test gets isolated temporary databases via fixtures
- `test_app` fixture creates a FastAPI app with temp DB paths
- `client` fixture wraps it in a `TestClient`
- Analysis tests mock the Claude CLI subprocess
- E2E tests start a real uvicorn server on a dynamic port with seeded journal and coach data
- Cross-module integration tests verify module discovery and coexistence
