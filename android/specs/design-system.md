# Spec: Graphite Signal Design System (Phase 5.5)

Status: **approved 2026-08-08** (v2 after Codex review; user-approved incl. edge-to-edge chrome and the 5.5a icon mark; pipeline running)

## Goal

Replace the placeholder M3 theme with the app's own visual identity — **Graphite Signal**, chosen by the user from three direction boards (artifact `three-directions-v1`, 2026-08-08): cold neutral graphite surfaces where the chrome carries zero hue, so the four module accents and the semantic colors are the only color on screen. Dark is the flagship; light derives by rule. Restyle the shell + Journal + Coach on the new system so Phase 6 (Trends) builds on it natively, with the motion system and the interactive-chart foundation (the user's two chosen investments) established here.

**Hard rule: this phase changes presentation only.** No behavior, no sync logic, no state derivation, no `:core:data` changes (except moving/extending pure display components in `:core:ui`). The existing 660 unit tests stay green untouched; UI-state builders keep their contracts.

Principles carried from the PWA (its analysis is in the session record; the artifact board is the visual contract):
1. Four-layer surface stack with exactly ONE chromatic band.
2. Depth = tone + hairline; real shadows only where something floats.
3. Module accents spent only on meaning (tab indicator, selection, focus, primary action, module-scoped data marks).
4. Semantic honesty: avoided-is-grey, paused-is-muted, over-target-is-amber-never-red, no verdict where none exists.
5. Italic + 55% opacity = "not yet yours" (uncommitted values, ghost placeholders).

## Tokens (`core/ui/theme/` — `WellnessPalette.kt`, `WellnessTheme.kt`)

### Surfaces & text (dark flagship / light)
| Token | Dark | Light |
|---|---|---|
| `canvas` | `#0E0F12` | `#FAFBFC` |
| `chrome` (nav, headers, strips) | `#14171C` | `#F4F6F9` |
| `card` | `#1A1E25` | `#FFFFFF` |
| `band` (the ONE chromatic surface: welded-card headers, pills-at-rest, selected-adjacent) | `#232936` steel | `#E8EDF5` pale steel |
| `line` (hairline, always 1dp) | `#2A303C` | `#D9DEE7` |
| `input` | `#171B21` | `#FFFFFF` + line border |
| `textPrimary` | `#F2F4F7` @ 100% | `#171A1F` |
| `textSecondary` | primary @ 66% | `#171A1F` @ 66% |
| `textFaint` | primary @ 42% (3.77:1 on card — passes 3:1) | `#171A1F` @ **50%** (42-45% FAILS 3:1 on every light surface — Codex-computed; 50% ≈ 3.10:1 minimum) |
| `inkOnAccent` | `#0C0D10` | `#0C0D10` (accents stay saturated enough in both) |

Theme selection: follows system; both themes are first-class. No Material dynamic color.

### Module accents (`ModuleAccent` enum + `LocalModuleAccent` CompositionLocal, set at each tab's root)
| Module | base (dark) | on-light text/stroke variant | softFill | border |
|---|---|---|---|---|
| Journal | `#F59E0B` | `#B45309` | base @ 15% | base @ 40% |
| Coach | `#2DD4BF` | `#0F766E` | base @ 15% | base @ 40% |
| Trends | `#38BDF8` | `#0369A1` | base @ 15% | base @ 40% |
| Analysis | `#A78BFA` | `#6D28D9` | base @ 15% | base @ 40% |

Fills/borders use the base hue in both themes; the darker variant substitutes wherever the accent is TEXT or a thin stroke on light surfaces (contrast). One source of truth — the enum; the shell passes it down; components never hardcode a module color.

### Semantics (module-agnostic)
success `#4ADE80` / light `#15803D` · warning `#FBBF24` / light `#A16207` · error `#EF4444` / light `#B91C1C` · syncIdle `#6B7280`. Same honesty mapping as the PWA (avoided = `textSecondary`, paused = `textFaint`, over = warning).

### Type (`WellnessType.kt`)
Face: **Roboto Flex** (system variable font — zero APK cost, indistinguishable from Inter at UI sizes; bundling Inter is the noted alternative if the user ever wants exact parity). Ramp:
- `display` 32sp / 750 / −1% tracking — hero numerals
- `stat` 26sp / 750 / tabular — tile values
- `headline` 24sp / 700 — day names
- `title` 17sp / 650 — card/section titles
- `body` 15sp / 450
- `secondary` 14sp / 450
- `label` 13sp / 500 — buttons, chips
- `micro` 11sp / 650 / UPPERCASE / +6% tracking — the taxonomy dialect (exposure, superset, rx labels) — NEVER decorative
- **Tabular numerals (`tnum`) are the default for every numeric slot** — set grids, targets, progress pills, tiles, captions.

### Shape & space
Radii: 8dp default (cards, buttons, rows, tiles) · 6dp inputs · 12dp floating layers (sheets, popovers) · full pills. Crisp — no radius above 12.
Spacing: 4/8/16/24/32 (PWA scale, kept); card padding 16; band padding 8×16; screen padding 16; touch targets ≥48dp.
Elevation: tonal only at rest. Real shadows exactly three levels: floating popover (8dp), modal sheet (16dp scrim + 12dp), snackbar (6dp).

## Component specs (restyle map — structure/behavior untouched)

- **Shell**: nav bar on `chrome` + top hairline; active tab = icon+label tinted with THAT module's accent + a 3×14dp underline bar (no M3 secondaaryContainer blob); inactive `textSecondary`. Each tab root provides `LocalModuleAccent`.
- **Headers**: module title `headline` on `canvas`; `SyncStatusIndicator` unchanged in behavior, dot colors from semantics, label `secondary`.
- **Date strip (journal)**: items min 48dp, radius 8, 2dp transparent border reserved; selected = accent softFill + 2dp accent border; today = 14×2dp accent bar under the numeral; locked = 55% opacity + lock glyph 10dp top-right. Strip sits on `chrome` with bottom hairline.
- **Welded category card (journal)**: header band on `band` (radius 8 top, hairline sides), rows on `card` stitched below (hairline sides+bottom, last row radius 8 bottom); collapsed = standalone band pill (all corners 8) with right-aligned rollup (`secondary`; success color when all-met); chevron 14dp rotating.
- **Tracker rows**: 20dp checkbox (accent fill when checked, `inkOnAccent` check); ghost values italic @55%; dot row = 8dp dots, 5dp gap, indented past the checkbox column; dot encoding exactly as the PWA incl. avoided-grey and the today double-ring (1.5dp card + 3dp faint).
- **Target line**: `secondary` text + 3dp progress bar (max 220dp) accent-filled; tones met=success, partial=accent, over=warning, neutral=faint.
- **Config forms**: inputs on `input` radius 6 with hairline; weekday picker chips = band at rest / accent softFill+border selected; paused dims the picker to 45%.
- **Coach workout header**: replaces the PWA gradient with `band`; meta row icons 14dp `textSecondary`; hook buttons full-width stacked (kept), Start filled accent (`inkOnAccent` text), End outlined accent; fired/locked = success fill; failed = error fill; Undo `TextButton` accent.
- **Superset group**: 3dp accent rail + `radius 0 8 8 0`, background accent @6%; label chip `micro` in accent @12% fill; concurrent-group hue rotation kept (B→sky, C→violet).
- **Exercise accordion**: header `title`-weight name + neutral pills (band fill) + exposure chip (`micro`, accent soft) + progress pill (band; success fill + `inkOnAccent` when complete, tabular); expanded body on `card` with guidance note (3dp warning rail, italic), rx tokens (`micro` accent labels, dumbbell icon for load, faint `·` separators).
- **Set grid**: header `micro`-style column labels with units in `textFaint`; cells on `input`; ghost placeholders italic @55%; done checkbox 20dp visual in a 48dp target.
- **Calendar popover**: floats (8dp shadow, radius 12) on `card`; day cells radius 6; status dots 6dp (success/error/warning); selected = accent fill + `inkOnAccent` numeral + inverted dot; today = accent numeral 700.
- **Stat tiles**: `card`, no border in dark (hairline in light), radius 8, label `secondary` → value `stat` with **count-up** → caption `textFaint` tabular; sparkline 1.5dp accent stroke @85%.
- **Snackbar/toasts, empty states, banners**: banners = `band` fill + 3dp semantic rail (no gradients); empty states swap emoji for 40dp `textFaint` Material icons.

## Motion system (`core/ui/motion/WellnessMotion.kt`)

Tokens: `springGentle` (dampingRatio .85, stiffness medium-low) for expansion/layout; `standard` 250ms `FastOutSlowIn`; `fast` 150ms; `draw` 500ms `LinearOutSlowIn`.
- Accordion + category expand: `animateContentSize(springGentle)` + chevron `animateFloatAsState` rotation.
- Tab switch: fade-through 200ms (outgoing 90ms fade + incoming fade/scale 95→100%).
- Date selection: soft-fill/border animate 150ms; strip lock state crossfade.
- Calendar popover: scale 95→100 + fade from the anchor, 200ms; scrim fade.
- Stat count-up: 600ms decelerate, once per screen entry, skipped entirely under reduced motion.
- Dot rows: 30ms/dot stagger fade-in on first composition only.
- Chart draw-in (foundation, used in Phase 6): path-trim 500ms + endpoint dot pop.
- **Reduced motion**: all of the above collapse to instant/crossfade when the system animator scale is 0 (Compose honors scale for animations driven by `animate*`; the count-up and stagger check it explicitly).

## Interactive-chart foundation (`core/ui/chart/` — consumed by Phase 6)

Built now so Trends ships interactive from day one:
- `ChartTheme` tokens: line 1.5dp accent, alt series violet 1.25dp @90%, grid 0.5dp `line` @60%, ticks 11sp `textFaint` tabular, band fills accent @12%, dashed guides 5-3.
- `ChartScrubState` + `Modifier.chartScrub(...)`: horizontal drag scrub with snap-to-point, a floating tooltip (card surface, 12 radius, shadowed) showing x-label + series values, haptic-free (haptics deferred per user's investment choices), tap-to-pin/tap-out.
- Draw-in animation hook per the motion system.
No chart data logic here — geometry stays in Phase 6's `ChartGeometry` port.

## Delivery (three commits, each buildable + APK-able)

1. **5.5a — Tokens + theme + shell**: palette/type/shape/motion files, `WellnessTheme` rewrite (both themes), nav bar + headers + `SyncStatusIndicator` restyle, `LocalModuleAccent` wiring. App-wide surfaces flip immediately.
2. **5.5b — Journal restyle**: strip, welded cards, rows, dots, targets, config forms.
3. **5.5c — Coach restyle + chart foundation**: workout header/hooks, supersets, accordions, set grid, calendar popover; `core/ui/chart` primitives with a demo scrub on a synthetic sparkline in Tools (proves the contract pre-Phase-6).

## Tests

- All 660 existing tests stay green UNMODIFIED (presentation-only guarantee — any test edit beyond imports is a spec violation to flag).
- New `WellnessPaletteTest` (:core:ui): programmatic contrast assertions — textPrimary/Secondary on every surface ≥ 4.5:1 (both themes), textFaint ≥ 3:1, accent-as-text uses the on-light variant on light surfaces (each pair ≥ 4.5:1), `inkOnAccent` ≥ 4.5:1 on all four accents + success.
- New `ChartScrubStateTest` (:core:ui): snap-to-nearest-point math, pin/unpin, empty-series no-op.
- Visual verification: APK per delivery commit; user reviews on device (the design's actual acceptance gate).

## v2 resolutions (Codex review — all five blockers)

**A. Hard-rule scope, precisely stated**: no domain, navigation, persistence, sync, or existing-screen *interaction* changes. The one exception: the chart foundation's scrub demo lives in an isolated Tools card (transient, no persistence) — permitted explicitly.

**B. Hybrid color migration** — a coherently populated M3 `ColorScheme` adapts stock components, AND `LocalWellnessPalette`/`LocalModuleAccent` are read explicitly at every bespoke semantic callsite. `primary` cannot mean four module accents at once. Base M3 mapping: `background/surface→canvas`, `onSurface→textPrimary`, `onSurfaceVariant→textSecondary`, `outline/outlineVariant→line`, `error→error`, surface-containers→chrome/card/band coherently. Component-role table (binding):
| Current usage | Graphite resolution |
|---|---|
| `UnreadablePlanDay` errorContainer | `band` + 3dp `error` rail (explicit rewrite) |
| off-plan chip (secondaryContainer) | `band` + `textSecondary` (not warning-semantic) |
| exposure pill (tertiaryContainer) | `accent.softFill` + theme-appropriate accent text |
| read-only banner / superset bg / progress track / dots (all surfaceVariant today) | `band` / accent@6% / `line` / explicit dot tokens — no shared slot |
| avoided-met dot | **`avoided = syncIdle #6B7280`** (a real token — never alpha text colors for solid dots) |
| dividers | `outlineVariant → line` |
| Snackbar inverse slots | explicitly populated (light inverse surface + dark text in dark theme, graphite inverse in light) |
| AlertDialog | `card` surface, `textPrimary`, 12dp, scrim — never `canvas` |
| FilterChip selected | `accent.softFill` + `accent.border` + accent label; rest = `band` |
| Accumulator tonal icon button | `accent.softFill` container + accent content (follows `LocalModuleAccent`) |
| Ripple | default M3 ripple accepted (outside the design contract) |

**C. Animation strategy** — accordions KEEP `AnimatedVisibility` (restyle enter/exit specs only; `animateContentSize` changes composition lifecycle and breaks the bring-into-view contract). Journal category rows stay separate stably-keyed lazy items animated via `Modifier.animateItem(...)`. `bringIntoView()` fires from an expansion-complete signal, not from the expanded flag. The coach `WorkoutHeader` wrapper is untouched. CalendarCard: 16dp→12dp radius, tonalElevation 0, shadow 8dp, `card` surface (the current 16dp violates the shape rule).

**D. Reduced motion, correct mechanism**: Compose's own `animate*`/`AnimatedVisibility` honor the coroutine-context `MotionDurationScale` (scale 0 → target on next frame). Custom count-up and dot-stagger read `currentCoroutineContext()[MotionDurationScale]?.scaleFactor` — emit final state immediately at 0, scale their own delays otherwise. Never read `Settings.Global` directly. `WellnessMotionTest` pins: scale 0 → final-only emission, no delays; scale 1 → 600ms/30ms-per-dot; a fake `MotionDurationScale(0f)` proves a built-in animation snaps.

**E. Chart foundation contract** (geometry-independent, index-based):
```kotlin
@Stable class ChartScrubState {
    val activeIndex: Int?; val pinnedIndex: Int?
    fun updateAnchors(xPositionsPx: FloatArray); fun scrubTo(xPx: Float)
    fun endScrub(); fun togglePinAt(xPx: Float); fun clearPin()
}
```
Anchors monotonic; empty = no-op + clear; out-of-range snaps to endpoint; midpoint ties → lowest index (pinned by test, plus duplicate-x and single-point cases). ONE `pointerInput` with `awaitEachGesture`: horizontal-slop → scrub (consume horizontal only); release-before-slop → pin toggle; cancel clears scrub, keeps pin. Tooltip = `Surface(card, 12dp, shadowElevation 8dp)` inside a padded focusable `Popup` (`PopupProperties` has no elevation), `onDismissRequest → clearPin()`.

**F. Nav indicator**: keep `NavigationBar`/`NavigationBarItem` with `selectedIndicatorColor = Transparent`; the 14dp-wide × 3dp-high accent bar draws via a modifier overlay on the item (never a custom nav component — accessibility surface stays stock). Tools tab gets a neutral accent: `slate #94A3B8` / on-light `#475569` (it hosts the chart demo and needs one).

**G. 5.5a gate**: `:app` has no meaningful JVM coverage, so delivery 1 requires `:app:assembleDebug` + full 660-suite + on-device review before 5.5b starts. Contrast tests composite alpha per sRGB channel BEFORE luminance (never multiply luminance by alpha). Accordion spring (stiffness 200 / damping .85 ≈ 333ms settle, <1% overshoot) confirmed intended.

## Resolved Questions (user decisions, 2026-08-08)

1. **System chrome**: transparent edge-to-edge — the graphite canvas runs full-bleed behind translucent bars, insets respected.
2. **App icon**: a quick graphite + amber adaptive mark ships in 5.5a (considered icon still Phase 8 scope).
