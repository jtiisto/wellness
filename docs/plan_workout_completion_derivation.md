# Plan: Derive workout completion from logged data

**Status:** Phase 1 in progress · **Created:** 2026-05-28
**Tracks:** [bug_workout_log_completed_flag.md](bug_workout_log_completed_flag.md)
**Touches** (per CLAUDE.md doc-sync rule): `docs/ARCHITECTURE.md` (data model, completion
semantics), `README.md` (no change expected).

> This document is the source of truth for a multi-session change. Phases 2–6 may be
> executed in later sessions; everything needed to resume is captured here.

---

## Context & confirmed root cause

The exercise-level `completed` flag read `false` on fully-logged workouts. Confirmed end to
end (code + production data `~/proj/health/.wellness/data/coach.db`, 44 sessions
2026-02-02 → 05-22):

- **Cause:** `completed` is a manual checkbox decoupled from data entry. For strength/checklist/
  duration the PWA header checkbox is *display-derived* (`isExerciseCompleted`, `public/js/coach/utils.js:183-199`)
  so it auto-shows checked and the user never toggles it → `logData.completed` is never written →
  server stores `exercise_logs.completed = 0` (`src/modules/coach.py:609`) → read tools faithfully
  return `false` (`mcp_servers/coach_mcp/server.py:1732`, `1870-1871`).
- **Set-level `set_logs.completed` is reliable** (652/653 = 1; zero data-bearing sets at 0). It is
  the per-set "Done" tick and is **kept** — it is an input to the derive, not part of the bug.
- **Evidence for the fix:** deriving completion from underlying data (sets / checklist items /
  duration) vs. the stored flag → **fixes 237 exercise rows**, risks exactly **1**
  (`exercise_log id 770`, an `interval` with no sets, no checklist items, no `duration_min` —
  the flag was its only signal).
- Type breakdown of the stored flag (completed=1 / completed=0): strength 13/187, checklist 3/39,
  duration 32/7, circuit 29/0, interval 1/0. The bug concentrates exactly in the *derived-display*
  types; the *explicit-flag* types (circuit/interval) were already correct — a clean natural control.

## Decisions (locked)

1. **Single source of truth = derive on read.** Completion is computed from logged data, never read
   from a stored exercise-level flag.
2. **Expose three fields per exercise:** `attempted`, `completed` (met target), `progress` ({done, target}).
3. **Drop `exercise_logs.completed`** (Phase 2). SQLite 3.50.6 supports `DROP COLUMN`; no index/view/
   trigger references it. **Keep `set_logs.completed`.** Rationale: a stored denormalized flag drifting
   from the data *is* this bug; do not keep a redundant, unsupported flag (the PWA has no working
   "mark complete despite missed set" affordance — the override use case does not exist).
4. **No bulk backfill needed** — derive reads the intact underlying data. The single
   `id 770` row (the only one where the flag was the sole completion signal) was reconciled by
   backfilling its real metrics from Garmin (see Phase 2), so no completion signal is lost.

## Derive rules

`set_has_data(s)` = any of weight/reps/rpe/duration_sec is non-null.

| type | `attempted` | `completed` (met target) | `progress` {done, target} |
|------|-------------|--------------------------|---------------------------|
| strength / circuit / weighted_time | ≥1 set with data | `done_sets ≥ target_sets` | done_sets / target_sets |
| checklist | ≥1 logged item | `logged_items ≥ planned_items` | logged / planned |
| duration / interval | `duration_min` present | `duration_min ≥ target_duration_min` | duration_min / target |

Rules common to all types:
- **Not attempted ⇒ `completed = False`.**
- **Attempted but target unknown** (e.g. no plan link, `target_*` null/0) **⇒ `completed = None`** (indeterminate).
- **Duration below target counts as `attempted` but NOT `completed`** (per user, 2026-05-28 — duration is
  target-aware, not mere presence).
- **Unknown/unlinked `exercise_type`:** infer from data (items→checklist, duration→duration, else strength).

**Session rollup:** `attempted` = any exercise attempted; `completed` = every *planned* exercise
completed (`completed_count ≥ planned_total`, where `planned_total` comes from `planned_exercises`
for the session; falls back to logged-exercise count when the log has no linked plan); `progress` =
completed / planned_total. A session with any unknown-target (`None`) exercise is conservatively *not*
fully completed.

## Output-shape changes (read tools)

- `get_exercise_history` history entries: replace `"completed": bool` with `attempted`, `completed`, `progress`.
- `get_workout_logs` per-exercise entries: replace conditional `"completed": true` with `attempted`,
  `completed`, `progress`; add a `session_completion` block to each log.
- `get_workout_summary`: keep `completed_workouts` (presence, backward-compat) and add
  `sessions_fully_completed` + `fully_completed_rate_percent`.
- Adding keys is backward-compatible; the **semantic** change is that `completed` now means met-target.

---

## Phases

### Phase 1 — Server read path (non-destructive, reversible) — **DONE (2026-05-28, uncommitted)**
- [x] `mcp_servers/coach_mcp/completion.py` — pure `derive_exercise_completion()` / `derive_session_completion()` / `set_has_data()`.
- [x] `get_exercise_history` — join targets, derive per entry (drops `el.completed`).
- [x] `_assemble_log_from_db` — join targets, derive per exercise, add `session_completion`.
- [x] `get_workout_summary` — add `sessions_fully_completed` + `full_completion_rate_percent` (kept `completed_workouts` presence count).
- [x] Unit tests (`test/test_coach_completion.py`, 26) + integration tests (`test/test_coach_mcp.py`). **144 passed.**
- [x] Verified against a copy of production `coach.db`: exercise verdicts now `(True,True)=305, (True,None)=6, (True,False)=4, (False,False)=2`; the 237 false-negatives fixed; `2026-05-22` fully completed; `id 770` → `(attempted=False, completed=False)`; 36/44 sessions fully completed.
- **Note:** This phase fixes the user-facing bug with zero destructive change. Reads still ignore (but do not remove) the stored column.

### Phase 2 — Writer + schema drop (IRREVERSIBLE) — **DONE in code (2026-05-29, uncommitted; applies to prod on next deploy/restart)**
- [x] `id 770` reconciled by **backfilling real Garmin data** (not left as not-completed): the 2026-03-02 "VO2 Max Intervals" = Garmin "Indoor Cycling" 24 min / avg 157 / max 174. Stored `duration_min=20` (planned; Garmin's 24 includes warmup), `avg_hr=157`, `max_hr=174`; `user_note` retained ("Average HR on Garmin data includes warm up"). Now derives `(attempted=True, completed=True)`. Production backup at `data/backups/coach.pre-770-backfill.<ts>.db`.
- [x] `_store_log` — dropped `completed` from the `exercise_logs` INSERT. Kept `set_logs.completed` INSERT.
- [x] `_archive_existing_log` — stopped writing `ex["completed"]`; `exercise_logs_archive.completed` column left for historical rows (defaults 0 going forward).
- [x] PWA sync-download assembler (`src/modules/coach.py`, the `_assemble_log` loop) — **additional reader found by grep**; removed the `el["completed"]` read so it tolerates the dropped column.
- [x] `analysis_queries.py` schema-hint prompt — **additional reader**; removed `completed` from the `exercise_logs` column list and noted completion is derived (kept `set_logs.completed`). Prevents the analysis LLM from generating SQL against the dropped column.
- [x] `CREATE TABLE exercise_logs` — removed the `completed` column.
- [x] Idempotent migration in `init_database` — `try: ALTER TABLE exercise_logs DROP COLUMN completed; except sqlite3.OperationalError: pass`.
- [x] Tests: repurposed the stale-flag test → asserts the column is gone + reads still derive; added `TestCompletedColumnMigration` (fresh-DB + idempotent-drop). **172 unit/integration + 44 coach e2e all pass.**
- [x] Migration dry-run on a copy of production `coach.db`: column dropped, `set_logs.completed` retained, **all row counts unchanged**, `id 770` intact, end-to-end derive `(T,T)=306 (T,None)=6 (T,F)=4 (F,F)=1`, 37/44 sessions fully completed.
- **Deploy note:** the live prod schema changes only when the new code is deployed to `~/proj/health/.wellness` and the service restarts (migration runs on startup, synchronized with the new writer). Do **not** hand-drop the prod column out of band.
- **Interim cosmetic gap (until Phase 3):** circuit/weighted_time header checkmarks in the PWA won't light (they still read `logData.completed`); progress pills and all data are unaffected. No e2e test depends on it.

### Phase 3 — PWA — **DONE (2026-05-29)**
- [x] `public/js/coach/utils.js` (`isExerciseCompleted`) — circuit/weighted_time now derive from sets (folded into the strength case); the `default` branch derives from any logged data (sets/duration/items) instead of `logData.completed`.
- [x] `public/js/coach/components/ExerciseItem.js` — header checkbox is now a read-only derived indicator (`disabled`, no `onChange`); removed the dead `handleCompletedChange` writer. Per-set ✓ in `SetEntry.js` kept (feeds `set_logs.completed`).
- [x] `public/js/coach/store.js` (`logHasExerciseContent`) — dropped the dead `val.completed` term; content gated by sets/items/duration.
- [x] Tests: added e2e `isExerciseCompleted` cases for circuit/weighted_time/unknown-type. **47 coach e2e pass.**
- [x] Cleanup: removed the now-dead `entry.completed ||` terms from `CalendarPicker.js` (`getWorkoutStatus`) and `WorkoutView.js` (`hasExerciseData`); both gate on sets/items/duration only.
- **UX consequence to note:** there is no longer a manual "tick exercise complete" affordance — completion follows logged data. A workout tracked only on Garmin (no in-app metrics, like the old `id 770`) now needs a `duration_min`/set logged in the PWA to read as completed.

### Phase 4 — Docs & cross-repo handoffs
- [x] `docs/ARCHITECTURE.md` — data model (column dropped), derived-completion semantics, set-level retained. (committed eb90ec2)
- [x] `docs/bug_workout_log_completed_flag.md` — marked Resolved with confirmed findings + chosen approach. (committed eb90ec2)
- [ ] **Cross-repo handoffs — READY TO APPLY, not yet applied** (kept here per the user's "don't touch other repos unprompted" instruction). Content below; apply in the respective repo when approved.

**Handoff A — analysis corpus (`~/proj/health/MCP_PATTERNS.md`):** Update the Coach MCP section. The read tools now return, per exercise, `attempted` (any data logged), `completed` (planned target met; `null` if target unknown), and `progress` (`{done, target}`). `get_workout_logs` adds a session-level `session_completion`. `get_workout_summary` adds `sessions_fully_completed` + `full_completion_rate_percent` (`completed_workouts` stays = logged/presence count). **The per-exercise `completed` column was dropped** — do NOT reference `exercise_logs.completed` in `execute_sql_query` (it no longer exists); `set_logs.completed` still exists. Retire any "infer completion from set presence" guidance — trust `attempted`/`completed` now.

**Handoff B — native app (`~/dev/native/wellness/`):** The coach exercise-log model should drop the exercise-level `completed: Boolean` field (server no longer sends it via sync, and the MCP read tools replaced it with `attempted`/`completed`/`progress`). Either consume the new MCP fields or derive locally (sets ≥ target / duration present / items checked), mirroring the PWA's `isExerciseCompleted`. Set-level `completed` on `set_logs` is unchanged. Journal `completed` fields are unrelated and unchanged.

### Phase 5 — Tests (developed alongside each phase; zero-tolerance for failures)
- [ ] Unit: derive helpers across all types incl. partial sets, below-target duration, null target, checklist, unlinked.
- [ ] Integration: `get_exercise_history` / `get_workout_logs` / `get_workout_summary` against the seeded DB, incl. a **no-`completed`-flag** log proving derivation.
- [ ] Writer test: `_store_log` no longer inserts `completed` (Phase 2).
- [ ] Migration idempotency test (Phase 2): init twice; init over a DB that still has the column.
- [ ] Follow project e2e/integration conventions (session-scoped fixture pollution awareness; local-time dates).

### Phase 6 — Verify + commit
- [ ] Migration dry-run on a copy of production `coach.db`; re-run the diagnostic SQL.
- [ ] Commit via `bin/git-commit-push.sh` only after user approval (sensitive-content scan first).

---

## Diagnostic SQL (re-usable verification)

Stored flag vs derived (expect: deriving fixes the 0/1 mismatches):
```sql
WITH d AS (
  SELECT el.completed AS stored_flag,
    CASE WHEN (SELECT COUNT(*) FROM set_logs s WHERE s.exercise_log_id=el.id
                AND (s.weight IS NOT NULL OR s.reps IS NOT NULL OR s.rpe IS NOT NULL OR s.duration_sec IS NOT NULL))>0
              OR (SELECT COUNT(*) FROM checklist_log_items c WHERE c.exercise_log_id=el.id)>0
              OR el.duration_min IS NOT NULL
         THEN 1 ELSE 0 END AS derived
  FROM exercise_logs el
)
SELECT stored_flag, derived, COUNT(*) FROM d GROUP BY stored_flag, derived;
```

## Open items / future refinements
- Session rollup currently treats unknown-target exercises as not-completed. Revisit if too strict.
- Consider surfacing `progress` for duration in minutes-with-unit if consumers want it.
- `id 770` semantics: if "done without metrics" should count, the derive (not the data) must change.
