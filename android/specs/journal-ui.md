# Spec: Journal UI (Phase 3)

Status: **approved 2026-08-07** (v2 after Codex review; user-approved, pipeline running)

## Goal

Replace the Phase 2 debug screen with the full Journal feature at PWA parity: the daily tracker view (7-day date strip, category grouping with collapsible summary pills, four entry-widget types, target status lines, 7-day dot rows) and the tracker config screen (full CRUD form with weekday picker, pause, polarity, free-text targets). Plus the global snackbar host (deferred here from Phase 1) and the Kover gate baseline. All UI logic that the PWA keeps pure gets ported pure and pinned by the transcribed JS suites (~124 cases across 5 files).

Porting sources (behavior is theirs):
- `~/dev/health/wellness/public/js/journal/utils.js` (925 lines, 29 exports) — the entire pure layer
- `~/dev/health/wellness/public/js/journal/components/{TrackerList,TrackerItem,ConfigScreen,Header}.js`, `JournalView.js`
- `~/dev/health/wellness/public/js/journal/store.js` — UI-facing surface (`isDayEditable`, `updateEntry` merge semantics, `toggleCategoryExpanded`, `markValueUpdated`)
- Test suites to transcribe 1:1: `test/js/journal-schedule.test.js` (44), `journal-targets.test.js` (35), `journal-summary.test.js` (11), `journal-recent.test.js` (10), `journal-config-mapping.test.js` (24)
- Behavioral checklist (not ported code): `test/e2e_browser/test_journal.py`

### Declared deviations (Android-idiomatic; all else 1:1)
1. `window.confirm`/`alert` → Material `AlertDialog` (delete confirm) and inline `TextField` `isError`/`supportingText` (form validation — name, category, target parse error; the PWA's target field already has the inline error path).
2. Bottom-sheet form → `ModalBottomSheet`. PWA parity on dismissal: overlay/swipe dismisses with **no unsaved-changes guard** (the PWA has none).
3. Config screen becomes a real nav destination inside the Journal tab (`journal` / `journal/config`) with system-back support; the header gear/back toggle is preserved visually.
4. Evaluation slider → `Slider(valueRange 0f..100f, steps = 3)` (the PWA's `step=25` = 5 stops); guard against scroll-drag conflicts inside the list.
5. `NumericInput`'s string-state machinery is a browser workaround — replaced by a string-backed `TextField(KeyboardType.Decimal)`; its two *semantic* rules are kept: blur-echo of the displayed default is a no-op (no phantom entry), and displayed-default ≠ stored value.
6. The PWA's storage-upgrade recovery screen (`initError`) is **omitted** — it exists only for the IndexedDB wipe strategy; Room migrates.
7. Emoji glyphs → Material icons, each with a content description (lock badge, chevron, delete, checkbox state, slider value — the emoji carried textual cues we must not lose). Category sort unified to plain `compareTo` at BOTH levels (the PWA's category keys already sort UTF-16 lexicographic via comparator-free `Array.sort()`; only tracker *names* used `localeCompare` — the unification changes name ordering for non-ASCII names; pinned by a test with an accented name).
8. Accumulator modal keeps the PWA's silent-close on 0/invalid input; IME "Done" submits.
9. **`valueUpdatedTimes` is pruned** to the local data window on write (the PWA never prunes its map — unbounded growth). Retention matches entries: a stamp is kept while its date `>= localDataWindowStart(7)` = today−7 (same inclusive cutoff as the entry prune, pinned by one shared test).
10. *(v3, from implementation)* **Untouched tracker save is a true no-op**: `updateTrackerAndMarkDirty` compares the transformed row to the stored one and skips write + dirty when identical. The PWA always writes and dirties, which phantom-locks past dates behind an empty upload. One documented wrinkle: a pre-accumulator-era tracker's first untouched save legitimately writes (the form fills in missing quantifiable keys) — every save after is a no-op.
11. *(v3, from implementation)* **Blank numeric input clears the value to ABSENT** (`Set(null)` → SQL NULL), not the PWA's explicit null — re-ticking the checkbox then reseeds the default. Unparseable non-blank input restores the displayed value with no write. Upload serialization is identical either way (`"value": null`).

## API / Interface

### Pure logic (`:core:data` `journal/JournalUiLogic.kt` + `journal/TargetLogic.kt` + `journal/ScheduleLogic.kt`)
Direct ports, JS names kept (grouped into files by topic; all operate on `TrackerDto`/`EntryDto`/plain values, zero Android deps):
- Constants: `ALL_DAYS`, `SCHEDULE_GENESIS_DATE`, `POLARITY_VALUES`.
- Dates: `getLastNDays(n=7, today)` → `List<DayCell(date, dayName, dayNum, isToday, dayOfWeek)>`; `getDayOfWeek(dateStr)` (LocalDate.parse → 0=Sun); `localDataWindowStart(days=7, today)`; `isWithinLastNDays` (already ported Phase 2 — reuse).
- Schedule: `normalizeDays`, `selectSegmentForDate` (greatest `effectiveFrom <= date` by string compare; earliest as pre-history fallback; order-independent), `getScheduleDaysForDate` (segment → legacy weekly → ALL_DAYS; empty segment days = paused), `lastActiveScheduleDays`, `isExpectedOn`, `shouldShowTracker(tracker, date, dayEntries?)` (visible if expected OR an entry key exists — even a `completed=false` entry).
- Effective-dated writes: `applySegmentEdit` core (no-op on equal-and-no-future; genesis split on first edit; filter `< today` + append = same-day replace AND future-segment supersede), `computeScheduleHistoryUpdate`, `computeTargetHistoryUpdate`.
- Targets: `targetForDate`, `parseTarget(str, polarity)` → `ParsedTarget(target?, error?)` (range regex, min>max error, single number → min unless negative polarity → max, negatives rejected), `formatTarget` (en-dash ranges, ≥/≤, unit suffix), `formatTargetInput` (inverse, ASCII hyphen), `targetStatus` (**no-entry polarity gate FIRST**, then coercion, range/at-least/at-most rules — at-most never partial), `coerceNumericValue` (twin of server `_coerce_numeric`), `dayStatus` (entry nullability matters: pass null, not `{}`), `formatTargetProgress` → `TargetProgress(text, tone, fillPct?)`.
- Rollups: `categorySummary` (actionable = polarity-non-neutral OR has target; off-schedule excluded; observations counted separately), `formatCategorySummary` → `(text, tone)?` ("All on track" / "N of M on track" / "N logged" / null), `recentDayStates(tracker, endDate, logs, n=7, earliestKnownDate)` → states `met|partial|missed|off|noted|quiet` (before-window and off-schedule = `off`), `groupByCategory` (missing category → "Uncategorized"), `getCategories`.
- Form assembly: `buildTrackerSaveFields(existing?, FormSelections, today)` → a presence-aware patch (keys written only when they must be). ALL tri-state fields use the same sealed shape: `TargetField { Unchanged | Set(TargetDto?) }` AND `PolarityField { Unchanged | Set(String?) }` — a plain `String?` cannot express "unspecified, leave existing" vs "clear existing to undefined" (the PWA writes `polarity: undefined` as an explicit merge-clear only when the existing tracker had one). Core rules IN THIS SPEC, not just in ported tests: chosen days = `paused ? [] : normalizeDays(days.ifEmpty { ALL_DAYS })` (empty selection coerces to Daily unless paused); new tracker writes a genesis segment only when chosen ≠ ALL_DAYS (Daily writes nothing, paused writes genesis `[]`); type-change away from quantifiable with a live target → `TargetField.Set(null)` (clears), distinct from `Unchanged`.

### Store additions (`JournalSyncStore` + a new thin `JournalUiPrefs`)
- **`mergeEntry(date, trackerId, patch: EntryPatch)`** — the widgets' single write path. `EntryPatch` is presence-aware per field: `value: Field<JsonElement?> { Unchanged | Set(v) }`, `completed: Field<Boolean?>` — `Set(null)` is an explicit clear, `Unchanged` preserves the stored column; both-`Unchanged` is a no-op (no write, no dirty). Implemented as ONE atomic DAO transaction (read-current + apply + mark **entry** dirty — never the tracker; `isDayEditable` and the strip lock key off dirty *trackers* only), then `afterLocalChange()`. Phase 2's `updateEntry`/`setEntryCompleted` remain but the checkbox's "check + write default" case MUST be one `mergeEntry` call (`{value: Set(default), completed: Set(true)}`), not two.
- **Entry presence semantics (load-bearing)**: the PWA distinguishes `entry.value === undefined` (absent) from explicit `null`. Room mirrors it: `valueJson` SQL NULL = absent; `valueJson = "null"` (encoded JsonNull) = explicitly null. `hasEntry`/visibility/`dayStatus` key off **row existence**, never value non-nullness — an all-null entry row still shows an off-schedule tracker and still counts as an entry.
- **Widget-field accessors on `TrackerDto`** (extension helpers, since these live in `extras`): `unitOrNull(): String?`, `defaultValueOrNull(): Double?` (numeric primitive or numeric string; JsonNull/malformed → null), `isAccumulator(): Boolean` (`extras["accumulator"]` boolean-true only). All coercion via `coerceNumericValue` semantics; pinned by tests over missing/JsonNull/malformed/string-number cases.
- `isDayEditable(date)`: today always; other days only when **zero dirty trackers** (dirty entries do not lock — PWA exactness). Store tests pin: today editable with dirty trackers; prior day locked by any dirty tracker incl. a pending delete; dirty entries alone never lock.
- `updateTracker` merge-patch semantics already exist via transform.
- `JournalUiPrefs` (backed by `journal_meta` keys namespaced `ui.` — `ui.expandedCategories`, `ui.valueUpdatedTimes` — which the upload builder can never touch: it reads only tracker/entry tables, pinned by a test asserting no `ui.`-keyed data appears in any upload body): `expandedCategories: Flow<Set<String>>` + `toggleCategoryExpanded(name)` (read-modify-write serialized by a mutex; stale names from renamed categories are accepted, harmless); `valueUpdatedTimes: Flow<Map<String,String>>` + `markValueUpdated(date, trackerId)` — key format `"YYYY-MM-DD|trackerId"`, value = UTC ISO instant, pruned per deviation 9.
- **Stamping rules** (exactly these actions stamp `markValueUpdated`): a successful quantifiable numeric commit and a successful accumulator addition. Not stamped: equal-value blur echo (no write happens), invalid/zero accumulator input, checkbox, evaluation slider, note edits. Caption display: parse the UTC instant, compare its **local calendar date** to the selected date — same day → time only ("3:42 PM"), else short date + time ("Jul 3, 3:42 PM").

### ViewModels + screens (`:feature:journal` — the feature module finally gets sources)
MVI with StateFlow:
- `JournalViewModel`: `UiState(dateStrip: List<DayCell>, selectedDate, dayLocked: Boolean per non-today cell, groups: List<CategoryGroup(name, expanded, summary?, trackers: List<TrackerRowState>)>, syncStatus)` — `TrackerRowState` carries everything a row renders (widget type + current/displayed value + committed flag + editable + targetProgress? + lastUpdatedCaption? + dotRow states). Derivation is a pure `buildJournalUiState(...)` function over (trackers, day entries, dirty-tracker count, selected date, prefs, now) — unit-testable without Compose.
- `TrackerFormViewModel`: seeds per PWA rules (weekday picker from today's schedule / `lastActiveScheduleDays` when paused / all days when new), live target validation, submit assembly incl. the **type-change guard** (leaving quantifiable with a live target writes `target=null`).
- Screens: `JournalScreen` (strip + groups + rows + widgets + accumulator sheet), `JournalConfigScreen` (grouped list + form sheet + delete dialog), wired as nested nav destinations; the app's Journal tab hosts them.

### Snackbar host (app module, Phase 1 deferral lands here)
Shared `SnackbarHostState` in the app scaffold; the schedulers' `onServerError` posts "Sync Failed: <message>" (5 s). Network errors stay silent (PWA parity). Wire for journal now; coach reuses it in Phase 5.

### Kover gate baseline
After the phase is green: run `koverHtmlReportAggregated`, read the aggregated line %, set `minBound(<measured> − 2)` in root `build.gradle.kts` (pulse-bridge's measure-then-gate pattern), and record the number + date in a comment.

## Behavior (the load-bearing rules, each pinned by a ported test or a new UI-state test)

1. **Date strip**: fixed trailing 7 days ending today, today always tappable; other days disabled + lock badge while ANY tracker is dirty. Selection is UI state only (survives via ViewModel, not persisted).
2. **Row visibility**: `!deleted && shouldShowTracker(...)`; two distinct empty states (no trackers at all vs none scheduled today), date strip always rendered.
3. **Categories collapsed by default**; expanded set persisted by name; summary pill only when collapsed; chevron rotates, no height animation.
4. **Widgets**: checkbox for all types except note; checking writes the default value **only when the entry's value is ABSENT** (`valueJson` SQL NULL or no row — an explicit stored null does not trigger it; PWA: `entry.value === undefined`), as one atomic `mergeEntry` (quantifiable: `defaultValue`; evaluation: `defaultValue ?? 50`). Quantifiable numeric field: commit on focus-loss or IME Done; parse the string — invalid input restores the displayed value (no write, no error state); numeric equality after parse (`"1"` == `1.0` == displayed default) is a no-op with no stamp; a real change writes `{value: Set}` only (never touches `completed`) + stamps. Accumulator adds to `(coerced displayed value || 0)`, allows negatives, silently closes on 0/invalid, writes `{value: Set}` + stamps. Evaluation slider: writes on every `onValueChange` step (live, PWA `onInput` parity — the 2.5 s scheduler debounce absorbs the burst; single-flight already guards overlap), `{value: Set}` only. Note textarea: each edit writes `{value: Set(text), completed: Set(text.isNotBlank())}` atomically — typing commits, clearing uncommits.
5. **Uncommitted styling** (`completed !== true`): value controls ghosted, dot row NOT ghosted. Non-editable days: whole row disabled — every control gets `enabled=false` explicitly (no pointer-events analog in Compose).
6. **Target line** only for quantifiable-with-target; fill bar only when `fillPct != null` (at-least targets). Last-updated caption only for quantifiable with a stamp: same-day → time only, else short date + time.
7. **Dot row**: `recentDayStates(tracker, selectedDate, …, localDataWindowStart(7))` — ends on the SELECTED date; last dot ringed; negative-polarity rows recolor met-dots neutral (avoid semantics).
8. **Config form**: field order and semantics per `ConfigScreen.js`; category dropdown ↔ new-category text swap; weekday picker Monday-first display (`1..6,0`), Daily/Weekdays presets, disabled+dimmed while paused (seeded from `lastActiveScheduleDays` so unpause restores); polarity select with explicit-clear; target free-text with live inline error/preview; submit assembles base fields + `buildTrackerSaveFields` patch and merge-patches via `updateTracker`.
9. **Delete**: AlertDialog confirm → soft delete (Phase 2 machinery).
10. **Server-error snackbar** fires only from the scheduler's server-classified errors, modeled as a **consumable one-shot event** (a Channel/`SharedFlow(replay=0)` bridge) — one failure produces exactly one snackbar; recomposition/rotation never re-shows it.
11. **Dismissal paths for the form sheet** (all guard-free, PWA parity): scrim tap, swipe-down, system back, navigation away — every path discards silently.

## Tests

| Test class (:core:data unless noted) | Pins |
|---|---|
| `JournalScheduleLogicTest` | `journal-schedule.test.js` 44 cases 1:1 (incl. the TZ-pinned weekday regression as fixed-date assertions) |
| `JournalTargetLogicTest` | `journal-targets.test.js` 35 cases 1:1 |
| `JournalSummaryLogicTest` | `journal-summary.test.js` 11 cases 1:1 |
| `JournalRecentLogicTest` | `journal-recent.test.js` 10 cases 1:1 |
| `TrackerSaveFieldsTest` | `journal-config-mapping.test.js` 24 cases 1:1 |
| `JournalUiStateTest` (:feature:journal) | `buildJournalUiState`: strip lock rule, visibility + empty states, group/summary assembly, row state derivation (displayed-value fallback chain, committed flag, caption formatting), dot-row wiring |
| `TrackerFormLogicTest` (:feature:journal) | picker seeding rules, live validation, submit assembly incl. type-change target clear, category new/existing swap |
| `MergeEntryTest` (store addition) | every `EntryPatch` combination (Unchanged/Set/Set(null) × both fields) on existing and missing rows; absent-vs-explicit-null distinction survives Room round-trip; both-Unchanged = no write, no dirty; entry dirty never marks the tracker |
| `TrackerWidgetFieldsTest` | `unitOrNull`/`defaultValueOrNull`/`isAccumulator` extraction: missing, JsonNull, malformed, numeric-string cases |
| `JournalUiPrefsTest` | `ui.` keys never reach an upload body; toggle serialization; stamp pruning at the inclusive cutoff; caption local-date formatting incl. a UTC-midnight-crossing case |

Instrumented: none new (UI is JVM-logic-tested; screens exercised on device). Existing 15 instrumented tests still pending an emulator session.

## Dependencies

No new libraries expected. `:feature:journal` gets its first sources (the feature convention plugin already wires Compose + ViewModel + Koin). If the form needs `navigation-compose` inside the feature, add `libs.androidx.navigation.compose` to it (catalog entry exists).

## Open Questions

1. **Accumulator + slider haptics/polish** — deferred to Phase 8 unless trivial.
2. **`updateEntry` widening**: Phase 2's signature overwrites both fields; this spec adds `mergeEntry` for PWA merge parity — flagging that the debug screen's `setEntryCompleted` path stays valid.
3. Anything in the form worth diverging from PWA parity (e.g. unsaved-changes guard on swipe-dismiss) — current spec says parity (no guard); cheap to add later if it annoys in practice.
