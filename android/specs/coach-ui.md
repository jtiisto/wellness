# Spec: Coach UI (Phase 5)

Status: **approved 2026-08-07** (v2 after Codex review; user-approved with bounded hook re-check; pipeline running)

> **2026-08-17: visual clauses superseded** by
> [logbook-design-system.md](logbook-design-system.md) (banners → eyebrow,
> chips/pills → ink text + tally marks, superset label/rail → drawn bracket,
> calendar status colors → ink marks, `Last · <date>` hint → ghost provenance
> footer). Every behavioral clause here — ports, gates, hook machine, set-grid
> write path, ghost lookup — stands unchanged.

## Goal

Replace the Phase 4 debug screen with the full Coach feature at PWA parity: calendar-driven day selection with status dots, the workout day view (blocks, supersets, exercise accordions with prescriptions and ghost values, four entry-widget shapes, session feedback, extra Zone-2 sessions), and the workout Start/End hook machinery with its four-escape entry gate. Pure logic ported and pinned; ~43 transcribed JS/e2e-embedded cases plus new tests for previously-unpinned helpers.

Porting sources (behavior is theirs):
- `../../public/js/coach/utils.js` — everything except the already-ported `EXTRA_SESSION_KEY` (add `EXTRA_SESSION_TITLE`)
- `../../public/js/coach/last-performance.js` — the whole ghost-value mechanism
- Components: `public/js/coach/{CoachView,components/{CalendarPicker,WorkoutView,BlockView,SupersetGroup,ExerciseItem,SetEntry,CardioEntry,ChecklistEntry,SessionFeedback,ExtraSessionCard}}.js`. **`DateSelector.js` is dead code — do not port** (its two util consumers `formatDateShort`/`getDateRange` are ported for tests but unused by UI).
- Transcription authorities: `test/js/last-performance.test.js` (17), `test/js/prescription.test.js` (4), the pure-function cases embedded in `test/e2e_browser/test_coach_interval.py:104-243` (**19**: formatTarget 8, formatInterval 3, getExerciseProgress 3, isExerciseCompleted 5), and `test_coach_superset.py:68-131` (3 groupExercises cases) — **43 transcribed cases total**
- Behavioral checklists (read, don't port): `test_coach.py`, `test_coach_extra_session.py`, `test/e2e_browser/pages/coach.py`

### Declared deviations (Android-idiomatic; all else 1:1)
1. Numeric entry: journal Phase 3's field pattern (string-backed TextField, commit on focus-loss/IME-Done, invalid restores, local state while focused). RPE's `min=1/max=10/step=.5` stay advisory — never clamped (PWA parity: the browser didn't enforce them either).
2. Textarea writes (user_note, feedback) use journal's note-field pattern: local state per keystroke, store write per change (Room absorbs it; upload debounce 2.5 s). No extra field debounce.
3. Locale normalization: ALL date captions use the **system UI locale** via `DateTimeFormatter` (the PWA mixes hardcoded `en-US` with default-locale calls) — one locale source, consistent with the Today/Yesterday/Tomorrow strings. Weekday grid stays Sunday-first (PWA parity). `formatShortDate`'s hardcoded English month array is replaced by locale-aware formatting; its tests pin a fixed Locale.
4. Emoji/inline-SVG icons → Material icons with content descriptions; 48 dp touch targets everywhere (the PWA's 20 px set checkboxes and text-link Undo don't meet Android minimums).
5. Calendar popup → anchored `Popup`/dropdown-card with `onDismissRequest` + system-back dismissal (the PWA uses a document-level outside-click listener).
6. **No confirmation dialogs** on Delete session / Undo — PWA parity (the coach module has none; the delete is a recoverable tombstone). Recorded deliberately; revisit in Phase 8 polish if it bites.
7. Accordion expansion near the bottom brings itself into view (`bringIntoViewRequester`) — the PWA doesn't scroll and the IME covering the set grid is worse on a phone.
8. Hook status is **one-shot per session** exactly like the PWA (no polling; a `pending` hook is never re-checked and the button stays optimistically fired after a 2xx). `GET /workout/config` is NOT called — availability comes from the status response's `actions_available` (the PWA never calls config either; plan.md's mention of it is corrected by this spec).
9. `lbs` stays hardcoded as the set-grid weight unit (PWA parity; no unit setting exists anywhere).
10. **Bounded pending-hook re-check** (user-approved improvement over the PWA's one-shot): while a hook button is PENDING, re-fetch status every 15 s up to the server's 120 s hook deadline, plus one final fetch after it; stop as soon as the state leaves PENDING or the screen leaves the session. Never runs when nothing is pending. Failed re-checks are ignored (only the initial fetch carries the JS failure semantics); a re-check never clobbers an optimistic FIRED. Pinned by virtual-time tests.

> **v2.1 (code-review fix round):** (a) all entry writes go through in-transaction transforms (`CoachSyncStore.transformLogEntry`) — the ViewModel passes cell mutations, never prebuilt arrays; a snapshot-built array write loses rapid edits (mutation-verified). (b) Two new day states beyond the PWA: `Loading` (until the first plans+logs emissions) and `PlanUnavailable` (a stored plan blob that fails decode — banner, NO extra-session action, entries read-only; still earns its calendar dot). (c) Zero-truthiness parity: `target_sets: 0` defaults like the PWA's `||`; a zero cardio target hints nothing. (d) Hook `dataExists` resets on session change and the log collector keys on (date, progress) so same-boolean transitions republish.

## API / Interface

### Pure logic (`:core:data` `coach/CoachUiLogic.kt`, `coach/LastPerformance.kt`) — 1:1 ports, JS names kept
- Dates: `formatDateShort`, `getDateRange(center, daysAround=3)`, `isToday/isPast/isFuture` (lexical vs local today).
- `groupExercises(exercises: List<PlanExerciseDto>)` → `List<ExerciseGroup>` (`Single(exercise)` | `Group(label, exercises)`): unlabeled emits Single and RESETS the run; same-label-as-immediately-preceding-group appends; else new group. `A,∅,A` = two separate `A` groups; a lone labeled exercise is a group of one. Display label rule (component level): `^[A-Za-z]\d*$` → `"Superset X"`, else verbatim.
- `formatInterval(rounds?, workSec?, restSec?, durationMin?)` — the 7-branch precedence with TRUTHY guards (0 = absent), `M:SS` with `×` (U+00D7), `"N rounds"` (no singular), `"20 min"`.
- `formatTarget(exercise, block)` — per-type rules incl. lowercase `x` for strength (`"3 x 8"`), unguarded duration (`null` min renders as the PWA's `"undefined min"` → Kotlin: render empty instead — micro-deviation, noted), checklist `"N items"`, weighted_time default 60s, interval delegating to formatInterval with per-field `?:` block fallback (durationMin from the exercise ONLY).
- `buildPrescription(exercise)` → ordered rpe/load/tempo tokens, string-coerced, absent-skipped.
- `getExerciseProgress(exercise, log)` → `Progress(display, complete)?` — sets counted by `completed === true`; duration/interval → `"✓"` iff `duration_min` non-null AND not empty-string; null when no target.
- `isExerciseCompleted(exercise, log)` — checklist strict equality (0==0 → true); strength counts **set rows not ticks** (the documented divergence from getExerciseProgress — port faithfully, do not "fix"); duration `!= null` (empty string DOES count here); default any-content.
- `hasExerciseData(log)` / `hasAnyProgress(log)` (the WorkoutView/CalendarPicker twins) — port ONCE as **`hasAnyProgress(log: JsonObject?): Boolean`, null-safe, returning false for null**: the JS twins are NOT byte-identical (only `hasExerciseData` guards null), and the start-gate escape calls it with a potentially-absent log.
- `getWorkoutStatus(date, plans, logs)` → `COMPLETED|MISSED|SCHEDULED|null` — the calendar-dot matrix incl. the ad-hoc-extra-session-earns-completed rule.
- `formatSelectedDate(date, today)` → Today/Yesterday/Tomorrow/locale-short.
- `buildColumns(showWeight, showTime)` → the four set-grid shapes (weight+reps+rpe / reps+rpe / weight+time / time).
- `parseName(name)` → `(base, pills)` — strips `(...)`/`[...]` groups.
- `statusToState(result?)` → `DEFAULT|PENDING|FIRED|FAILED` (`exit_code` null→pending, 0→fired, else failed; -1/-2 not distinguished).
- `LastPerformance.kt`: `setHasData` (weight/reps/rpe/duration_sec, `0` counts), `findLastPerformance(canonicalSlug, refDate, plans, logs, exposure?)` — strictly-before dates newest-first, plan-exercise resolved per historical date's plan (first slug match), sets filtered by `setHasData`, exposure strict-match wins over newer any-exposure, no-exposure plan entries are fallback-only, immediate return on match; `formatShortDate` (locale-aware per deviation 3).

### Hooks API (`network/CoachApi.kt` — extend)
```kotlin
suspend fun workoutStatus(sessionId: Long): WorkoutStatusDto
// GET /api/coach/workout/{id}/status → {start: HookResultDto?, end: HookResultDto?, actions_available: {start, end}}
suspend fun fireWorkoutHook(sessionId: Long, action: HookAction)    // POST …/start|end
suspend fun undoWorkoutHook(sessionId: Long, action: HookAction)    // DELETE …/start|end
```
`HookResultDto(firedAt?, exitCode?, data?)` — snake_case `@SerialName`s. 404/400 surface as errors (the UI maps any failure per the state machine).

### ViewModels + screens (`:feature:coach`)
- `CoachViewModel`: `UiState(selectedDate, calendar: CalendarState(viewMonth, cells w/ status+disabled, earliestDate floor), plan: PlanDto?, log: JsonObject?, isEditable /* date == today */, syncStatus, isSyncing)`. Derivation in pure `buildCoachUiState(...)`; date selection + month paging actions; `earliestDate` floor rules (select refused below it; prev-month refused when target month's last day < floor; no upper bound).
- `WorkoutHooksViewModel` (or a state-holder inside CoachViewModel keyed by sessionId): the PWA's component-local machine made explicit — one-shot status fetch per (sessionId, isEditable); five button states; `showControls = isEditable && statusLoaded && (actions.start || actions.end)` with `statusLoaded=false` and availability RESET when a new session's fetch begins (stale availability must never expose the previous session's controls); `startGateSatisfied` with the four escapes verbatim (`!actions.start || startState != DEFAULT || hasAnyProgress(log) || statusFetchFailed`); `effectiveEditable = isEditable && startGateSatisfied`; **fired→locked promotion via BOTH paths the JS has** — reactive (a FIRED start locks when exercise data later appears) AND synchronous (a Start POST succeeding while data already exists resolves directly to LOCKED, never a transient FIRED/Undo frame); Undo flows (Start: undo visible only in FIRED; End: FIRED or FAILED; undo failure re-fetches status, double-failure falls back to DEFAULT); optimistic transitions exactly as the JS.
- Screens: `CoachScreen` (header w/ sync indicator incl. syncing pulse, calendar trigger + popup, workout content), `WorkoutDayView` (rest-day branch: empty state + ExtraSessionCard, NO banners/header/feedback; planned branch: collapsible controls header with auto-expand-once when gate unsatisfied, read-only banners for past/future — future uses locale date, blocks list, SessionFeedback), `BlockView` (title ?: block_type, timing badge from the BLOCK object, rest guidance), `SupersetGroup`, `ExerciseAccordion` (header: parsed name + pills + exposure chip (absent = nothing) + target + progress pill + chevron; body: guidance note, prescription row with dumbbell icon for load, entry widget, user-note field), `SetGrid` (always exactly `targetSets` rows — no add/remove; ghost placeholders matched by `set_num`; pad-to-index on edit with `{set_num}` fillers; whole-array rewrite per edit via `updateLog`), `CardioFields` (3 numeric fields, no constraints), `ChecklistItems` (identity = item string; duplicates collapse — parity), `SessionFeedbackFields`, `ExtraSessionCard` (saved/idle/draft states; draft is LOCAL state only; Save disabled until `duration_min != null`; save copies non-null fields in ONE `updateLog`; draft-Delete discards locally, saved-Delete tombstones without confirmation).
- `core/ui`: extend the sync indicator with a `syncing` pulse + label variant (PWA: "Syncing…"/"Pending"/"Synced"/"Offline").

## Behavior (load-bearing rules, each pinned)

1. **isEditable = selectedDate == today**, evaluated on state build (re-derived on date change and screen resume, same as journal's midnight handling). Past/future days NEVER fetch hook status.
2. **Gate composition**: `effectiveEditable` locks blocks AND session feedback; the raw `isEditable` governs banners, controls visibility, and the status fetch. `endState` never gates anything.
3. Calendar: 42-cell month grid; status dots per `getWorkoutStatus`; trigger shows the selected date's dot; Today footer button; legend row; external date changes re-home the view month.
4. Set grid column shapes from `hide_weight`/`show_time`; header row `#` + labels(+unit) + `✓`; done tick is a checkbox writing `{completed}` through the same pad-and-rewrite path.
5. Ghost values: computed lazily on accordion expand (slug present only), per-cell placeholders matched by `set_num` (placeholder ONLY — untouched cells log nothing), plus the `Last · Jun 1` hint whenever a match exists; exposure passed from the plan exercise (`?: null`). No ghosts for cardio/checklist.
6. Extra session renders on rest days only when `hasExtra || isEditable`; a tombstoned entry counts as absent (`isDeletedEntry`).
7. Hook buttons: `Working…` while pending; `(locked)` suffix once fired+data-exists; `canFire` only in DEFAULT/FAILED; POST failure → FAILED but the gate is already open (escape 1 = any click).
8. Accordion `completed` styling from `isExerciseCompleted`; progress pill from `getExerciseProgress` — including their documented divergence.
9. All hook/gate state is ViewModel-held per session and reset on session change (PWA: component-local; tab-away-and-back re-fetches, escape 2 keeps a mid-workout user unlocked).
10. **Pull to refresh** *(2026-08-27; native addition)*. `CoachViewModel.refresh()` is the **journal's twin down to the constants** — `requestSync(TRIGGER_PULL)`, join, then await `store.isSyncingFlow == false` capped at 15 s; a 500 ms minimum visible time; one pull at a time; offline answered with the same authored line through `SyncErrorEvents.postMessage`. The two tabs answer the same gesture and must answer it identically; the rationale lives in [journal-ui.md](journal-ui.md) rule 12 and is not restated. `syncNow()` retires into it. Three things are Coach's own:
    - **Only the day view is pulled.** A plain `Box` carrying `Modifier.pullToRefresh(...)` plus `PullToRefreshDefaults.Indicator` wraps `WorkoutDayView` (which is a `Column(verticalScroll)` in every branch, rest day included, so the gesture dispatches everywhere). The app bar and the calendar strip stay fixed — dragging them would move the control that says which day is on screen.
    - **`Box` rather than `PullToRefreshBox`, because of `enabled`.** `PullToRefreshBox` has **no `enabled` parameter** in material3 1.4.0; the plain form is exactly what it does internally, with the one knob it does not expose. `enabled = state.guide == null` is belt-and-braces: the guide runs in a `Dialog` window of its own, so the gesture cannot physically reach the Box behind it — the gate states the intent rather than enforcing it. (Verified against the resolved artifact: `PullToRefreshBox`, `pullToRefresh` and `rememberPullToRefreshState` are all **stable** in 1.4.0, so no `@OptIn` is needed for any of them, and `PullToRefreshDefaults.Indicator` is not experimental either — only the `shape`/`containerColor` properties, which are never named here.)
    - **`isRefreshing` is its own `StateFlow`, not a `CoachUiState` field** — beside `strapPrompt` and `traceSamples`, and for the same stated reason: it is transient gesture state that `buildCoachUiState` derives nothing from. The state build is already at five combined inputs, so a sixth would cost a new bundle type and a changed pure signature to carry a boolean the builder never reads. Journal puts both flags in its state instead, where the combine has room and the dot reads them off the state it is already handed; the asymmetry is deliberate and recorded on both sides.

## Tests

| Test class | Pins |
|---|---|
| `LastPerformanceTest` (:core:data) | 17 transcribed cases |
| `CoachFormattingTest` (:core:data) | prescription 4 + formatTarget 8 + formatInterval 3 + the `undefined min`→empty micro-deviation |
| `CoachProgressTest` (:core:data) | getExerciseProgress 3 + isExerciseCompleted 5 + BOTH named divergences: sets-vs-ticks, and empty-string duration (`getExerciseProgress == null` while `isExerciseCompleted == true`) |
| `GroupExercisesTest` (:core:data) | 3 transcribed + lone-labeled-is-group + display-label regex |
| `CoachUiHelpersTest` (:core:data) | NEW pins for previously-untested helpers: getWorkoutStatus matrix (incl. extra-session-completed, quiet-rest-day-blank, today-vs-past no-log), formatSelectedDate, buildColumns 4 shapes, parseName, statusToState, hasAnyProgress, date helpers |
| `CoachUiStateTest` (:feature:coach) | buildCoachUiState: calendar cells + earliestDate floor rules + month paging bounds; editable derivation |
| `WorkoutHooksTest` (:feature:coach) | the full state machine: one-shot fetch matrix (not-editable/no-session short-circuit; failure sets statusFetchFailed; session change resets statusLoaded+availability), 4 escapes each in isolation, BOTH fired→locked paths incl. "POST succeeds while data already present → immediate LOCKED, no transient FIRED", undo visibility asymmetry, undo-failure recovery chain, optimistic transitions |
| `SetGridLogicTest` (:feature:coach) | pad-to-index fillers, whole-array rewrite, ghost placeholder matching by set_num, column shapes wired |
| `ExtraSessionLogicTest` (:feature:coach) | draft state machine, duration-required save, non-null-only copy, tombstone-renders-as-absent |
| `CoachViewModelTest` — pull section (:feature:coach) | `requestSync(TRIGGER_PULL)`, the 500 ms floor, re-entrancy refused, the attached-flight wait, the offline message verbatim — the journal's five, on this ViewModel |

Instrumented: none new. Verification: `./gradlew build assembleDebugAndroidTest koverVerifyAggregated` green, coverage ≥ 85 gate; APK to Drive; manual checklist mirrors `test_coach.py` (log a set on today's real plan, start-gate behavior, calendar statuses, extra session on a rest day, feedback fields).

## Dependencies

None new expected (`:feature:coach` conventions already wire Compose/ViewModel/Koin; navigation not needed — single screen + popup).

## Open Questions

1. ~~Dev-server hook config~~ — **resolved**: `GET /workout/config` on dev returns `{start: true, end: true}`; the full state machine is live-testable.
2. ~~Pending-hook gap~~ — **resolved**: user approved bounded re-checking (deviation 10).
