# Bug: `completed` flag reads false on fully-logged workouts

**Status:** Resolved (server-side) 2026-05-29 — root cause confirmed on production data; fix implemented (Phases 1–2). See **[plan_workout_completion_derivation.md](plan_workout_completion_derivation.md)**.
**Component:** Coach MCP server (`mcp_servers/coach_mcp/server.py`) + log storage (`src/modules/coach.py`) + PWA (`public/js/coach/`)
**Severity:** Medium — corrupts adherence/completion reporting; no data loss (set data itself is stored correctly).

## Resolution

Confirmed root cause on production data: `completed` is a manual PWA checkbox decoupled from
data entry; for strength/checklist/duration the header checkbox is display-derived, so it auto-shows
checked and the flag was never written — the server stored `completed=0` on real, fully-logged work
(237 of 317 exercise rows; the explicit-flag types circuit/interval were the natural control). The
PWA's set-level `set_logs.completed` was reliable (652/653).

Fix: **completion is now derived from logged data** at read time (`attempted` / `completed` /
`progress`; see `mcp_servers/coach_mcp/completion.py`), and the unreliable `exercise_logs.completed`
column was **dropped**. The one row whose flag was the sole completion signal (`id 770`, a Garmin-tracked
VO2 interval) was backfilled from Garmin (20 min / avg 157 / max 174), so no signal was lost.
Set-level `set_logs.completed` is retained as a derivation input. Remaining: PWA cosmetic (circuit
header checkmarks) and cross-repo handoffs — tracked in the plan doc.

## Summary

Workout logs that are fully recorded (every set has weight / reps / RPE) come back from
the Coach MCP read tools with `completed: false`. Completion is stored as an **explicit
per-exercise / per-set flag** that is decoupled from whether real set data exists, and the
flag is apparently never set true on the normal PWA logging path. There is **no
session-level completion field at all**. Net effect: any consumer that trusts `completed`
under-reports what was actually trained and must instead infer completion from the presence
of logged sets.

## Observed behavior

While loading/reviewing real logs (May 2026 sessions), every logged exercise/session came
back with `completed: false` even though weight, reps, and RPE were all present and correct.
Adherence had to be judged by "does this exercise have logged sets?" rather than by the
`completed` field.

- `get_exercise_history(slug)` → each history entry has `"completed": false` despite a full
  `sets` array with real weight/reps/rpe.
- `get_workout_logs(start, end)` → assembled `log` entries likewise don't surface
  `completed: true` for exercises that were clearly performed.

## Expected behavior

A workout/exercise/set that has real logged data (weight/reps/RPE, or duration for cardio)
should report as completed — either via an explicit flag that the logging path actually
sets, or by deriving completion from the presence of logged data. A consumer should be able
to ask "was this session done?" and get a correct answer from the `completed` field.

## Root-cause analysis (server side — confirmed in code)

1. **No session-level completion column.** `workout_session_logs` is inserted with only
   `session_id, date, pain_discomfort, general_notes, last_modified, modified_by`
   (`src/modules/coach.py:577`). There is no `completed` (or `completed_at`) column on the
   session row, so there is no canonical "this session was completed" signal — it can only
   ever be *inferred* (e.g. from row existence or from child logs).

2. **Exercise/set `completed` is an explicit flag, defaulting to 0.** The columns exist on
   `exercise_logs` and `set_logs` (`coach.py` schema, `completed INTEGER DEFAULT 0`), and
   `_store_log` writes them straight from the incoming payload:
   - exercise: `1 if exercise_data.get("completed") else 0` (`src/modules/coach.py:609`)
   - set: from `s.get("completed")` (`src/modules/coach.py:~624`)
   So if the payload omits `completed` (or sends it falsy), the row is stored `completed=0`
   **regardless of whether weight/reps/RPE were provided.**

3. **Read path faithfully returns the stored 0.**
   - `get_exercise_history`: `"completed": bool(session["completed"])` where
     `session["completed"]` is `el.completed` (`mcp_servers/coach_mcp/server.py:1732`,
     query at `:1704–1718`).
   - `get_workout_logs` assembler: only promotes `completed: true` when the stored value is
     truthy (`server.py:1870–1871` for exercises, `:1900–1901` for sets); otherwise it stays
     false/absent.

   The read side is behaving correctly given the stored data — it is faithfully reporting
   `completed=0`.

**Conclusion:** the stored `completed` flag is `0` on real, fully-logged work. Because the
server defaults it to 0 and only flips it from the payload, the most likely origin is the
**PWA log-save payload not setting `completed: true`** when the user records sets. (Not yet
read — confirm in `public/js/`.) The alternative framing is that "explicit completed flag"
is the wrong model and completion should be **derived from logged data**.

## Inconsistency worth noting

`get_workout_summary` counts "completed workouts" by the **presence** of `workout_session_logs`
rows (`server.py:586–591`), i.e. presence-based — which is reasonable and *disagrees* with
the per-exercise/per-set `completed` flag. So the codebase already has two different,
inconsistent notions of "completed": presence-based (summary) vs explicit-flag-based
(exercise/set). Worth unifying on one.

## Suggested directions (pick one; not prescriptive)

- **A — Fix the writer (PWA).** If the PWA is the origin, have it send `completed: true`
  per exercise/set when the user logs them (or on session submit). Smallest change if the
  flag model is kept. Confirm the payload shape in `public/js/` first.
- **B — Derive completion server-side.** Treat an exercise/set as completed when it has the
  data that defines it (weight+reps for strength, duration for cardio), instead of relying on
  an explicit flag. Compute it in the read path / `_store_log`. More robust to PWA drift.
- **C — Add a real session-level completion signal.** Add `completed` (or `completed_at`) to
  `workout_session_logs` and set it on submit, so "was this session done?" has a first-class
  answer rather than being inferred. Pairs well with B.

## Current workaround (downstream)

Consumers (including the health-analysis corpus that drives planning) currently **infer
completion from the presence of logged sets**, not from the `completed` field. This is being
documented in that corpus's `MCP_PATTERNS.md` so adherence isn't mis-read from the flag.

## Unknowns / to confirm

- **PWA payload not yet inspected** (`public/js/`). The "PWA doesn't set completed" claim is
  inferred from the server defaulting to 0 + the all-false observation; confirm by checking
  the actual log-save payload the PWA POSTs.
- Whether any UI currently depends on the explicit `completed` flag (would inform A vs B/C).
