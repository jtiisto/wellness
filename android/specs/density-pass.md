# Spec: Compact Density Pass (Phase 6.5)

Status: **approved 2026-08-09** (user-selected "now, as a 6.5 mini-round" at the in-session gate, scope as described there; motivated by device feedback — entry fields large, fonts blocky vs the PWA's compact layout; reference screenshot `Screenshot_20260809-081928` of the Coach set grid)

## Goal

Bring Journal + Coach data-entry surfaces to a density closer to the PWA without touching any logic: today every entry cell is a stock M3 `OutlinedTextField` (56 dp min box, 16 sp inner text, big padding), so a 3-set grid fills half a screen. Presentation-only — the string-backed field pattern (local state while focused, commit on focus-loss/IME-Done, invalid restores) and every existing test stay byte-untouched.

## Scope (all call sites of the stock field in `:feature:journal` + `:feature:coach`)

- Coach set grid (weight/reps/RPE cells), cardio fields, session-feedback fields, user-note / "No notes" textarea, extra-session fields.
- Journal entry widgets (quantifiable value field, note field) and JournalConfigScreen form fields.
- Set-grid column headers and checkbox, grid row spacing.

## Changes

1. **`WellnessDenseField`** (new, `core/ui`): `BasicTextField` in a custom decoration box — Graphite `WellnessShape.input`, **36 dp visual height** in grid cells (40 dp for standalone fields), inner text **15 sp** (`type.label` basis; tabular numerals for numeric cells, centered), horizontal padding 10 dp. Single-line and multi-line variants (multi-line min height ≈ 72 dp, 15 sp, top-aligned — the notes fields). Placeholder + enabled/disabled states match current styling. Cursor/selection colors from the palette.
   > **v1.1 (PWA reference screenshot `Screenshot_20260809-082157`, Coach set grid):** the PWA's cells are ~30–32 dp **borderless filled** boxes — no outline — which is a large share of the perceived weight difference. Grid cells and the in-card notes textareas therefore use a **quiet filled variant** (fill from the palette's input/card role, no border at rest; the 2 dp accent/error focus border stays); standalone fields keep the outlined Graphite look. Column headers sit above a **hairline rule** like the PWA grid; they stay uppercase Graphite taxonomy but at micro size.
   > **v1.2 (implementation rulings):** painted box stays **40 dp** everywhere — the 48 dp interactive minimum (upheld; a deliberate Phase 5 decision) reserves a 48 dp row slot regardless, so a 36 dp box would only widen gutters. Real outcome vs the goal line: row pitch 60→48 dp + fill-not-outline + 16→15 sp, not "≈ half height" (that line was miscalibrated — `minimumInteractiveComponentSize` reserves layout, not just hit area). Explicit grid row spacing 0 (the 48 dp reservations already leave an 8 dp gutter). Checkbox stays the design-system 20 dp visual. Labels sit above boxes in uppercase micro (no floating label — it would need the 56 dp box this component exists to avoid). Numerics `label`-basis 15 sp/500 centred tabular; prose `body` 15 sp/450. `columnHeaderStyle()` lives in `core/ui/theme` for reuse (Trends tables). Dead `WellnessDefaults.textFieldColors()` removed.
2. **Touch targets stay legal**: every field and the checkbox keep a **≥ 48 dp hit area** via `minimumInteractiveComponentSize`/padding around a smaller visual box — visual ≠ interactive size, deliberately.
3. **Set grid**: row vertical spacing tightened to 4–6 dp; checkbox visual ~22 dp inside its 48 dp target; column headers drop from the tracked-uppercase label to the **micro style** (smaller, lighter, tracking reduced) — still uppercase, still Graphite taxonomy, just quieter.
4. **No other type changes**: card titles, section headers, body text untouched (they're the accepted Graphite ramp; the complaint localizes to entry surfaces and grid headers).
5. Swap all in-scope call sites to the dense component. No new colors, no new tokens beyond the field metrics; everything resolves from the existing palette/shape/type locals.

## Constraints

- Zero logic changes: ViewModels, stores, focus/commit semantics, validation, tests — untouched. `git diff` on `*/src/test/*` must be empty.
- Kover: new composable lines are UI-thin (decoration only); gate 80 must still pass on the full invocation. Any layout wrapper taking a capturing lambda is `inline` (CLAUDE.md kover gotcha).
- Accessibility: content descriptions and semantics preserved; 48 dp minimum interactive size everywhere.

## Verification

`./gradlew build koverVerifyAggregated` full invocation, exit 0, tests all green, `*/src/test/*` diff empty. Device check (APK `-density` tag): set grid ≈ half its former height, numerals legible, fat-finger entry still comfortable, notes field compact, Journal value/note/config fields match, nothing clipped in either theme.
