# Spec: Trends (Phase 6)

Status: **approved 2026-08-09** (v2 after Codex review — 5 blockers + 12 majors + 2 minors folded; user-approved with mini-strip scrub ON (expanded hit area) and all-stale-slices Health badge)

> **v2 (Codex review fold):** (1) `weight()` gains `range`; authoritative cache-key table added. (2) `TrackerValue.value` is `JsonElement?` — a `Double?` would fail the whole decode on note-era legacy strings before `coerceNumeric` ran; `coerceNumeric` operates on `JsonElement?`. (3) The global staleness map is replaced by a per-call `FetchResult<T>(value, staleFetchedAt)` envelope — a shared cache key (`weight:{range}`) made one map incoherent across consumers. (4) Screen state is request-scoped: slices reset at fetch start, late completions for a superseded request are dropped. (5) All former "check at port time" items resolved: range selector on every screen (Codex-verified in PWA); Overview per-slice error rule defined below. (6) DTO section upgraded to compilable authority: wire names explicit, physiological measurements all `Double`. (7) fetchCached failure matrix completed (fresh-decode, cached-decode, DAO failures, cancellation, atomic updates). (8) HTTP error display mapper defined. (9) ViewModel lifecycle/supervision contract defined. (10) Room v4 fully specified; migration tests execute in the next emulator session (not before). (11) Scaling pipeline mandated: positions AND extents scale, ink doesn't; px anchors only. (12) Scrub state clears on data-identity change; pickers/range changes clear pins. (13) Union-anchor + tooltip null-formatting rules defined. (14) Health stale badge covers every rendered stale slice (declared deviation, default — user may pick PWA parity at the gate). (15) `jsNumberString` contract shared by `linePath` and labels. (16) CRITICAL note on rounding corrected: `roundToInt()` matches JS `Math.round`; `kotlin.math.round` (ties-to-even) is the one to avoid. (17) `all_time` nullability reframed as defensive forward-compat (server currently never emits null). (18) `TrendsApiTest` + device acceptance matrix added.

> **v2.1 (code-review fix round, 2026-08-09):** Codex on the working tree found 1 BLOCKER + 2 MAJOR + 2 MINOR; direct inspection added 1 MINOR + 1 NIT. All fixed: (a) **Selection discipline** — a shared in-memory `selection` flow leads and storage follows; fallback reconciliation consults storage only when nothing was chosen this session and re-checks after the suspending read; writes are generation-guarded under a mutex so a superseded fallback becomes a no-op, never a queued overwrite (the guard is defensive — no reachable interleaving pins it — and is documented as such). Selections that drive fetches are never seeded from storage before the confirming list arrives. (b) **Health per-slice load keys** — recovery/weight keyed by range, composition/labs by end date; a range change no longer refetches (or degrades) range-immune slices; Retry clears all four. (c) **Ink is typed `Dp`** on `PlotDot`/`PlotRect` radii — scaling ink through `LogicalScale` no longer compiles. (d) **Domain-bounded union anchors** — every date inside the plotted x domain carrying any rendered datum (raw value, rolling mean, band, score) gets exactly one anchor; dates outside the domain get neither marks nor anchors. (e) **labPanel fallback persists** via the same discipline, with one declared divergence: an EMPTY panel list keeps the remembered panel (nothing fetches on it; Strength/Journal clear on empty because a stale slug would otherwise be requested — and their clear is in-memory only, storage keeps the id for re-adoption). (f) Anchor-merge label fallback no longer throws on unlabeled contributions. Round-1 accepted choices stand: `dotsBelowLines` on RHR only; t-score null renders "—" (PWA prints literal "null" — a bug, not ported); third stack colour borrows Journal amber pending device review; `Cache-Control: no-store` headers on all trends GETs; `dateTicks` returns domain-space x.

## Goal

Port the PWA Trends module at behavioral parity: 5 read-only chart screens (Overview / Strength / Cardio / Journal / Health) over 11 GET endpoints, network-first fetching with `payload_cache` fallback and a staleness badge, the full `chart-logic.js` pure-geometry port (13 functions, 21 transcribed test cases), and a persisted range selector (4w/12w/6m/All) — all rendered through the Phase 5.5 Graphite Signal chart foundation (`core/ui/chart/`), which means **every major chart ships with scrub/pin/tooltip interactivity the PWA does not have** (plan-mandated native addition; the PWA has zero chart interactivity — verified).

Porting sources (behavior is theirs; PWA repo read-only):
- `public/js/trends/chart-logic.js` — all 13 pure functions + private `round2` (observable via `linePath` output)
- `public/js/trends/components/primitives.js` — `RANGES`, `rangeStart`, `spread`, StaleBadge rules, YAxis label-dedup
- `public/js/trends/store.js` — `fetchCached`, staleness, cache-key inventory, persistence keys
- `public/js/trends/components/{OverviewScreen,StrengthScreen,CardioScreen,JournalScreen,HealthScreen,LineChart,BarChartStacked,PillSelect}.js` + `TrendsView.js`
- Transcription authority: `test/js/trends-chart-logic.test.js` — **21 flat tests, no describe blocks, all-synthetic inline fixtures** (safe to transcribe verbatim)
- Server DTO authority: `src/modules/trends.py` + `trends_queries.py` (shapes documented below); exact-JSON server tests in `test/trends/` are the golden-fixture shape reference
- `docs/ARCHITECTURE.md` §Trends — conventions (Monday weeks, partial flag, kg cross-exercise, type-based Zone 2, no computed correlations, no visual test assertions)

## Declared deviations (Android-idiomatic / plan-mandated; all else 1:1)

1. **Chart interactivity added**: scrub + tap-to-pin + tooltip via `ChartScrubState`/`chartScrub`/`ChartScrubTooltip` on every major chart (contract in §Interactivity). Excluded: the two Overview sparkline StatTiles (tap = navigate, as PWA) and the FocusCard day-dot ribbons (too small; PWA had only `title=` attrs). MiniMetric/MiniLab strips: **user decision at the approval gate** — default proposal is scrub ON with the gesture area vertically expanded beyond the 56 dp visual strip (plan says *every* chart ships interactive); alternative is omitting scrub there (Codex's ergonomic recommendation).
2. **Rendering through Logbook** (Round 3, 2026-08-18) — not the PWA's `.trends-*` CSS, and no longer Graphite Signal. Geometry parity, visuals native. **The colour vocabulary is [logbook-design-system.md](logbook-design-system.md) §Components — Trends and nothing here**: `PlotTone` resolves in one place (`PlotColors`, pinned by `PlotColorsTest`) under two rules — a judgment never takes a hue, and a plate identifies a *series*, positionally, decoded by a mandatory legend. `ChartTheme` answers from the Logbook palette for the same reason. This clause governs colour only; every geometric statement elsewhere in this spec is untouched by the re-theme, and the two that named specific hues are superseded with it: the v2.1 note's "third stack colour borrows Journal amber" (stacks are plates 1–3 with an ink-faint remainder) and the warn tone (a flagged observation or a below-floor morning draws an **open dot** — paper fill, ink outline — with a mono `!` beside the value, never a colour). The 🔥 streak glyph retires: streaks read `run N · best M` in mono.
3. **Coordinate system**: geometry functions emit the PWA's 360-wide logical units (so `ChartGeometryTest` numbers match the JS suite exactly). One mandated transform pipeline: **ALL logical geometry coordinates AND extents** (x, y, w, h, every path vertex, bar widths, band heights, anchor positions) multiply by `scale = widthPx / 360`; chart height = `heightPx = H * scale` (aspect preserved via the same factor). **Ink attributes** (stroke widths, dot radii, text sizes, corner radii) come from `ChartTheme` dp/sp and do NOT scale. `ChartScrubState` receives **px anchors only** (its documented FloatArray contract); pointer input is never divided back to logical. Pure transform tests at 180/360/720 px cover bar centers, path vertices, and tooltip anchors.
4. **Persistence**: localStorage → new `trends_meta(key PK, value)` Room table (v3→v4 migration, schema exported), keys `ui.range`, `ui.screen`, `ui.exercise`, `ui.tracker`, `ui.labPanel` — the Phase 3 `JournalUiPrefs` pattern (Flow reads, mutex-serialized writes). Defaults: range `12w`, screen `overview`; the rest default to first-in-list after fetch.
5. **Cache**: LocalForage `TrendsApp/trends_cache` → existing `payload_cache` table, `module = "trends"`, **key strings identical to the PWA inventory** (table below), `payloadJson` = raw response text, `fetchedAt` = epoch ms (injected clock).
6. **`isNetworkError`**: reuse `core/data/network/NetworkErrors.kt` (type-based: walks causes for `IOException`). The PWA's `HTTP 5` message-prefix check becomes `is ServerResponseException` (Ktor `expectSuccess=true`; 4xx = `ClientRequestException` → never falls back, matching the PWA).
7. **PRBoard `all_time` guard**: decoded nullable and null-guarded in UI (row renders name/slug only when null). *Defensive forward-compat*: the current server never emits `all_time: null` for a listed exercise (exercises exist only when sets exist) — the guard is tolerance, not current behavior.
8. **PillSelect** bottom sheet → M3 `ModalBottomSheet` (system back + scrim dismiss for free). **Opening any picker clears all chart pins on that screen** (a focusable pinned `Popup` and a sheet would otherwise contend for z-order and system back); range and screen changes clear pins too.
9. **StaleBadge**: same text rules (`cached · {m}m ago` / `· {h}h ago`, oldest stamp wins, hidden when no stamps); recomputed per recomposition, not on a timer — PWA parity (it wasn't reactive either). **Badge input = the `staleFetchedAt` stamps of every stale slice the screen currently renders** — on Health this covers weight/composition/labs too, an improvement over the PWA (which badges only recovery; user may choose strict parity at the gate).
10. **`spread` ported as index selection**: pick indices `round(i*(n-1)/(k-1))`, dedupe **indices**, then map. The JS dedupes object references; `distinct()` on value-equal Kotlin data classes would silently drop legitimate ticks.
11. **Number display — `jsNumberString`**: one helper renders numbers the way JS stringifies: integral values without decimal part (`1.0` → `"1"`), `-0.0` → `"0"`, `'.'` separator always, plain decimal (no scientific notation in our bounded ranges). Used by **`linePath`/`sparklinePoints` serialization AND tick labels, legends, tooltip values** (`formatNum` is an alias/thin wrapper). Pinned by tests incl. integral, fractional, negative-half, and `-0.0` cases.
12. **Retry affordance**: error states add a Retry button (PWA relies on switching range/screen — a dead end on mobile). Retry re-runs all of the current screen's fetches.
13. **Error display mapper** `describeFetchError(e)`: `ResponseException` → `"HTTP ${response.status.value}"` (matching the PWA's `HTTP {status}` text); network errors (`isNetworkError`) → "Offline — check connection"; `SerializationException` → "Unexpected server response". Classification stays type-based; error bodies are never parsed (the server's polymorphic 422 `detail` is never decoded).
14. **Request-scoped screen state** (native correctness deviation): every range/selection-dependent UI slice is keyed by its request identity and **resets to Loading when its fetch starts**; a slice never renders under a toolbar state it doesn't match (the PWA can briefly show 12w data under a 4w toolbar; we don't).

## Cache-key inventory (authoritative)

Slug/id appear **raw** in cache keys, URL-encoded in request paths (PWA parity).

| Repository method | Cache key | Params |
|---|---|---|
| `overview()` | `overview` | none — server-local evaluation |
| `weight(start?, end, range)` | `weight:{range}` — **shared Overview↔Health** | `start?`, `end` |
| `strengthExercises(start?, end, range)` | `strength/exercises:{range}` | `start?`, `end` |
| `strengthExercise(slug, start?, end, range)` | `strength/{rawSlug}:{range}` | `start?`, `end` |
| `strengthVolume(start?, end, range)` | `volume:{range}` | `start?`, `end` |
| `cardio(start?, end, range)` | `cardio:{range}` | `start?`, `end` |
| `journalTrackers()` | `journal/trackers` | none |
| `journalTracker(id, start?, end, range)` | `journal/{rawId}:{range}` | `start?`, `end` |
| `healthRecovery(start?, end, range)` | `health/recovery:{range}` | `start?`, `end` |
| `healthComposition(end)` | `health/composition` — date-less, overwritten daily | `end` only |
| `healthLabs(end)` | `health/labs` — date-less, overwritten daily | `end` only |

## API / Interface

### `:core:data` — `trends/` vertical

**DTOs** (`TrendsDtos.kt`, kotlinx.serialization, shared `WellnessJson` — which has **no naming strategy**, so every multi-word field carries `@SerialName` with the exact wire name shown below; golden-fixture decode tests enforce completeness). Typing rules: **every physiological/measurement field is `Double`** (`avg_hr`, `rhr`, `sleep_score` included — the wire flips int/float freely); `Int` only for true counts/ordinals (`reps`, `set_count`, `session_count`, `count_30d`, `hard_sets`, `interval_sessions`, `scheduled_days`, `met`, `partial_days`, `missed`, `count`, streaks). Nullability mirrors the wire exactly; the API's **single omitted key** is `weekly_usage` → default-null property.

```kotlin
// GET /overview
OverviewDto(zone2: Zone2Tile, tonnage: TonnageTile,
            @SerialName("adherence_focus") adherenceFocus: List<FocusRow>, prs: PrSummary)
  Zone2Tile(@SerialName("this_week_min") thisWeekMin: Double, @SerialName("last_week_min") lastWeekMin: Double?,
            @SerialName("four_week_avg_min") fourWeekAvgMin: Double?, sparkline: List<Zone2Week>)
  Zone2Week(@SerialName("week_start") weekStart: DateString, @SerialName("planned_min") plannedMin: Double,
            @SerialName("extra_min") extraMin: Double)
  TonnageTile(@SerialName("this_week_kg") thisWeekKg: Double, @SerialName("last_week_kg") lastWeekKg: Double?,
              @SerialName("four_week_avg_kg") fourWeekAvgKg: Double?, sparkline: List<TonnageWeek>)
  TonnageWeek(@SerialName("week_start") weekStart: DateString, @SerialName("tonnage_kg") tonnageKg: Double)
  FocusRow(@SerialName("tracker_id") trackerId: String, name: String,
           @SerialName("metric_kind") metricKind: String /* "adherence"|"avoidance" */,
           rate: Double, dropping: Boolean, ribbon: List<RibbonDay>)
  RibbonDay(date: DateString, status: String /* met|partial|missed|off */)
  PrSummary(@SerialName("count_30d") count30d: Int, latest: PrLatest?)
  PrLatest(slug: String, name: String, date: DateString, e1rm: Double, weight: Double, reps: Int, unit: String)

// GET /weight
WeightDto(available: Boolean, series: List<WeightPoint>)
  WeightPoint(date: DateString, kg: Double)

// GET /strength/exercises
ExercisesDto(exercises: List<ExerciseSummary>)
  ExerciseSummary(slug: String, name: String, equipment: String?, @SerialName("last_used") lastUsed: DateString,
                  @SerialName("session_count") sessionCount: Int, unit: String,
                  @SerialName("all_time") allTime: Best?, @SerialName("in_range") inRange: Best?, plateau: Boolean)
  Best(@SerialName("best_weight") bestWeight: BestWeight, @SerialName("best_e1rm") bestE1rm: BestE1rm)
  BestWeight(weight: Double, reps: Int, date: DateString, assistance: Double?)
  BestE1rm(value: Double, weight: Double, reps: Int, date: DateString, assistance: Double?)

// GET /strength/exercise/{slug}
ExerciseDetailDto(exercise: ExerciseInfo, unit: String, sessions: List<SessionPoint>)
  ExerciseInfo(slug: String, name: String, equipment: String?, category: String?)
  SessionPoint(date: DateString, @SerialName("top_set") topSet: TopSet, e1rm: Double,
               @SerialName("top_set_rpe") topSetRpe: Double?, @SerialName("set_count") setCount: Int,
               @SerialName("off_plan") offPlan: Boolean)
  TopSet(weight: Double, reps: Int, assistance: Double?)

// GET /strength/volume
VolumeDto(weeks: List<VolumeWeek>)
  VolumeWeek(@SerialName("week_start") weekStart: DateString, partial: Boolean,
             @SerialName("tonnage_kg") tonnageKg: Double, @SerialName("hard_sets") hardSets: Int,
             @SerialName("by_exercise") byExercise: List<SlugTonnage>)
  SlugTonnage(slug: String, name: String, @SerialName("tonnage_kg") tonnageKg: Double, @SerialName("hard_sets") hardSets: Int)

// GET /cardio
CardioDto(weeks: List<CardioWeek>, @SerialName("steady_sessions") steadySessions: List<SteadySession>)
  CardioWeek(@SerialName("week_start") weekStart: DateString, partial: Boolean,
             @SerialName("zone2_planned_min") zone2PlannedMin: Double,
             @SerialName("zone2_extra_min") zone2ExtraMin: Double,
             @SerialName("interval_sessions") intervalSessions: Int)
  SteadySession(date: DateString, @SerialName("avg_hr") avgHr: Double,
                @SerialName("duration_min") durationMin: Double, @SerialName("off_plan") offPlan: Boolean)

// GET /journal/trackers
TrackersDto(trackers: List<TrackerSummary>)
  TrackerSummary(id: String, name: String?, type: String?, unit: String?, polarity: String?,
                 actionable: Boolean, @SerialName("has_target") hasTarget: Boolean,
                 @SerialName("first_entry") firstEntry: DateString, @SerialName("last_entry") lastEntry: DateString)

// GET /journal/tracker/{id}
TrackerDetailDto(tracker: TrackerSummary, values: List<TrackerValue>,
                 @SerialName("target_segments") targetSegments: List<TargetSegment>,
                 @SerialName("weekly_adherence") weeklyAdherence: List<AdherenceWeek>, streaks: Streaks,
                 @SerialName("weekly_usage") weeklyUsage: List<UsageWeek>? = null)  // THE omitted key
  TrackerValue(date: DateString, value: JsonElement?, completed: Int?)
  //           ^^^ JsonElement — note-era rows carry legacy strings; a Double? here fails the WHOLE
  //               decode before coerceNumeric can run. completed is 1|0|null — Int?, NEVER Boolean.
  TargetSegment(start: DateString, end: DateString, min: Double?, max: Double?)   // INCLUSIVE both ends
  AdherenceWeek(@SerialName("week_start") weekStart: DateString, partial: Boolean, paused: Boolean,
                @SerialName("scheduled_days") scheduledDays: Int, met: Int,
                @SerialName("partial_days") partialDays: Int, missed: Int,
                rate: Double?, @SerialName("metric_kind") metricKind: String)
  Streaks(current: Int, best: Int)
  UsageWeek(@SerialName("week_start") weekStart: DateString, partial: Boolean, count: Int)

// GET /health/recovery
RecoveryDto(available: Boolean, days: List<RecoveryDay>)
  RecoveryDay(date: DateString, rhr: Double?, hrv: Double?, @SerialName("hrv_band") hrvBand: HrvBand?,
              @SerialName("sleep_hours") sleepHours: Double?, @SerialName("sleep_score") sleepScore: Double?)
  HrvBand(low: Double, high: Double, @SerialName("low_floor") lowFloor: Double?)

// GET /health/composition
CompositionDto(available: Boolean, scans: List<Scan>)
  Scan(date: DateString, @SerialName("lean_kg") leanKg: Double?, @SerialName("fat_kg") fatKg: Double?,
       @SerialName("total_kg") totalKg: Double?, @SerialName("body_fat_pct") bodyFatPct: Double?,
       @SerialName("vat_kg") vatKg: Double?, @SerialName("ag_ratio") agRatio: Double?,
       @SerialName("bmd_total") bmdTotal: Double?, @SerialName("t_score_total") tScoreTotal: Double?)  // 8 nullable metrics

// GET /health/labs
LabsDto(available: Boolean, panels: List<LabPanel>)
  LabPanel(name: String, tests: List<LabTest>)
  LabTest(name: String, unit: String?, observations: List<LabObs>)
  LabObs(date: DateString, value: Double?, text: String?, prefix: String?, flag: String?,
         @SerialName("ref_low") refLow: Double?, @SerialName("ref_high") refHigh: Double?,
         @SerialName("ref_text") refText: String?)
```

**`TrendsApi`** (JournalApi conventions): 11 suspend functions returning **raw body text** (`bodyAsText()`), nullable `start`/`end` appended only when non-null. `/overview` and `/journal/trackers` take no params; `/health/composition` + `/health/labs` take `end` only. Slug/id path segments URL-encoded. Pinned by `TrendsApiTest` (MockEngine): every path, query omission-vs-presence, URL encoding of a slug needing it.

**`TrendsRepository`** (Koin singleton):
```kotlin
data class FetchResult<T>(val value: T, val staleFetchedAt: Long?)   // null = fresh from network

class TrendsRepository(api: TrendsApi, cacheDao: PayloadCacheDao, json: Json = WellnessJson,
                       debugLog: DebugLog, clock: () -> Long) {
    suspend fun overview(): FetchResult<OverviewDto>
    suspend fun weight(start: DateString?, end: DateString, range: String): FetchResult<WeightDto>
    suspend fun strengthExercises(start: DateString?, end: DateString, range: String): FetchResult<ExercisesDto>
    suspend fun strengthExercise(slug: String, start: DateString?, end: DateString, range: String): FetchResult<ExerciseDetailDto>
    suspend fun strengthVolume(start: DateString?, end: DateString, range: String): FetchResult<VolumeDto>
    suspend fun cardio(start: DateString?, end: DateString, range: String): FetchResult<CardioDto>
    suspend fun journalTrackers(): FetchResult<TrackersDto>
    suspend fun journalTracker(id: String, start: DateString?, end: DateString, range: String): FetchResult<TrackerDetailDto>
    suspend fun healthRecovery(start: DateString?, end: DateString, range: String): FetchResult<RecoveryDto>
    suspend fun healthComposition(end: DateString): FetchResult<CompositionDto>
    suspend fun healthLabs(end: DateString): FetchResult<LabsDto>
}
```
There is **no repository-global staleness state**: the envelope is the single staleness carrier, and each ViewModel keeps `staleFetchedAt` on the payload slice it renders (this is what makes the shared `weight:{range}` key coherent — each consumer knows the freshness of the copy *it* holds).

Internal core:
```kotlin
private suspend fun <T> fetchCached(cacheKey: String, deserializer: DeserializationStrategy<T>,
                                    fetch: suspend () -> String): FetchResult<T>
```
Failure matrix (each row pinned by a test):
1. **Success**: `text = fetch()` → decode `T` → `cacheDao.upsert("trends", key, text, clock())` → return `FetchResult(value, null)`. If the **upsert throws**: log to DebugLog, still return the fresh result (a sick cache must not fail a good fetch).
2. **Fresh-body decode failure**: no cache read, no cache write (old copy survives), `SerializationException` propagates.
3. **Network error (`isNetworkError`) or 5xx (`ServerResponseException`)**: read cache. Hit + cached copy decodes → return `FetchResult(cachedValue, cached.fetchedAt)`. Hit + cached copy fails decode → DebugLog, rethrow the **original** network/5xx error (cache row retained). Miss → rethrow. If the **cache read itself throws** → DebugLog, treat as miss, rethrow original error.
4. **4xx (`ClientRequestException`) or anything else**: propagate. Never reads cache.
5. **`CancellationException`** is rethrown before any classification, at every point.

### `:core:data` — prefs & Room v4

**`TrendsPrefs`** (JournalUiPrefs pattern: Flow reads, mutex-serialized writes): `range: Flow<String>` (default `12w`), `screen: Flow<String>` (default `overview`), `exercise: Flow<String?>`, `tracker: Flow<String?>`, `labPanel: Flow<String?>`, matching suspend setters. Keys `ui.range`, `ui.screen`, `ui.exercise`, `ui.tracker`, `ui.labPanel`. Values are the PWA's ids: range `4w|12w|6m|all`, screen `overview|strength|cardio|journal|health`.

**Room v4**: `TrendsMetaEntity(key: String @PrimaryKey, value: String)` on table `trends_meta`; `TrendsMetaDao` (`observe(key): Flow<String?>`, `get(key)`, `put(key, value)` upsert); `WellnessDatabase.version = 4`, entity registered; `MIGRATION_3_4` = `CREATE TABLE IF NOT EXISTS trends_meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)`; appended to the migrations array; schema exported to `core/data/schemas/`. Instrumented tests: direct 3→4 (data preservation across existing tables) AND the full 1→4 chain — **written this phase, executed in the next emulator session** (emulator currently unavailable; same standing caveat as the other 31 instrumented tests). `plan.md` §4 gains the `trends_meta` row at approval commit.

### `:feature:trends` — pure logic

**`ChartGeometry.kt`** — 1:1 port, JS names kept, `Double` in/out (Compose converts at the transform step):
```kotlin
linearScale(d0, d1, r0, r1): (Double) -> Double     // degenerate domain -> constant (r0+r1)/2, unclamped
coerceNumeric(value: JsonElement?): Double?          // truth table below — the ONLY entry point for tracker values
dayIndex(date: DateString, origin: DateString): Int  // LocalDate.toEpochDay() subtraction — NEVER Instant/Duration
niceTicks(min, max, targetCount = 4): List<Double>   // 1-2-5 mantissa, +step/1e6 epsilon, per-tick 10-dp re-round
seriesToPoints(records, xValue, accessor, xScale, yScale): List<ChartPoint>  // skips null accessor; output NOT index-aligned with input
linePath(points): String                              // '' under 2 pts; "M x y L x y" via jsNumberString(round2(v)) — test #8 pins the exact string
steppedBandRects(segments, xStart, xEnd, xScale, yScale, yTopEdge, yBotEdge): List<BandRect>  // null max -> top edge; x1 EXCLUSIVE; min/max swap tolerates inverted scales
stackedBarLayout(weeks, keys, xScale: (Int) -> Double, yScale, barWidth): List<BarColumn>     // INDEX-based xScale; v <= 0 segments dropped; bottom-up
ribbonCells(weeks, xScale: (Int) -> Double, cellWidth): List<RibbonCell>                      // INDEX-based; muted when paused || scheduled==0
dailyBandSegments(items, xOf, bandOf): List<BandSegment>  // runs MERGE ACROSS MISSING DAYS; only a null/incomplete band breaks a run
sparklinePoints(values: List<Double?>, w, h): String  // x spans FULL length incl. nulls; '' under 2 present; jsNumberString coords
rollingMean(daily: List<DatedValue>, windowDays): List<DatedValue>  // SAME length as input, value nullable; trailing window [end-(w-1), end]; gaps don't dilute
dotSizeScale(minR, maxR, values): (Double) -> Double  // sqrt-area scaling; empty -> minR; constant -> midpoint
round2(v): Double                                     // JS Math.round semantics — see CRITICAL 9
```
`coerceNumeric(JsonElement?)` truth table: numeric primitive → value iff finite (NaN/Inf → null); string primitive → trim, null if blank, else `toDoubleOrNull()` **followed by an `isFinite` check** (Kotlin parses `"Infinity"`; JS `Number('Infinity')` is likewise non-finite → null — the finite check makes them agree); boolean primitive → null; `JsonNull`/null/arrays/objects → null.

`ChartPoint` carries `muted` (and any per-record payload) **on the point** — never re-read by index from the source list (the JS `LineChart.toPlot` index-coupling is a trap, not a contract).

**`ChartPrimitives.kt`**: `RANGES` (4w/28, 12w/84, 6m/182, all/null), `rangeStart(rangeId, today): DateString?` (`LocalDate.minusDays`; unknown id or null days → null), `spread(size: Int, n: Int): List<Int>` (index form, deviation 10), `jsNumberString`/`formatNum` (deviation 11), YAxis tick-label dedup rule (suppress label when formatted text equals previous tick's; gridline still draws).

**`TrendsScreenLogic.kt`** — the PWA's inlined-but-pure screen rules, extracted for tests:
`statTileDelta(value: Double?, avg: Double?): Int?` (null unless avg truthy && value != null; `round((value/avg - 1) * 100)` with JS-round semantics) · `foldVolumeStacks(weeks, topN=3)` (top-3 slugs by summed tonnage + synthetic `other`; stack order [top0,top1,top2,other]; "other" legend only when some week's other > 0) · `pickerLabels(items)` (name; ` (slug)` suffix only on duplicate display names) · `constantSeriesNote(values)` (single-distinct-value collapse + note text: n=1 "the only entry in range" / n>1 "same value for all {n} entries in range") · `labsPartition(tests)` (chartable = ≥2 numeric obs via coerceNumeric; tabular = rest; complete partition) · `dayChart(days, valueOf)` (null when no value present; origin = FULL array's first date, xMin/xMax from present only) · `dateTicks(...)` (`spread` n=5, `MM-DD`) · adherence three-rect layering (missed full-height, partial from `1-met-partial`, met from `1-met`, in-progress overlay) · selection fallback (persisted id not in fetched list → first item) · logical→px transform helpers (deviation 3) · scrub-anchor merge (§Interactivity).

### `:feature:trends` — UI

- **`TrendsScreen`** (tab root): internal 5-screen switcher (persisted `ui.screen`), Graphite pill-row idiom; **the toolbar (screen switcher + range selector + StaleBadge slot + screen-specific pickers) renders unconditionally on every screen and in every state** — the range selector is hidden nowhere (Codex-verified PWA behavior). StatTile taps navigate to Cardio/Strength and persist the screen change.
- **Per-screen ViewModels** (`OverviewViewModel`, `StrengthViewModel`, `CardioViewModel`, `JournalTrendsViewModel`, `HealthViewModel`), MVI `StateFlow<UiState>`. Lifecycle/supervision contract:
  - **Only the active screen fetches.** Screen activation subscribes; deactivation cancels via structured concurrency.
  - Fetch triggers: the combined `(range, selection)` key stream, collected with **`collectLatest`** — a new key cancels in-flight work, so late completions of a superseded request are dropped by construction. The initial emission triggers exactly one fetch.
  - **Config recreation does not refetch** an already-loaded identical key (state lives in the VM; the key comparison short-circuits).
  - Each slice = `Loading | Error(text) | Ready(value, staleFetchedAt)`, tagged with its request key; **slices reset to Loading when their fetch starts** (deviation 14).
  - Multi-fetch screens run slices under **`supervisorScope`** — one slice's failure never cancels siblings.
  - **Overview rule** (resolves former open question): `/overview` and `/weight` are independent slices. Overview-slice failure → screen error state (tiles are the screen's core; toolbar stays). Weight-slice failure (after cache fallback) → WeightCard absent, error logged, tiles unaffected.
  - **Health rule**: only the recovery slice's failure surfaces as screen error; weight/composition/labs failures are swallowed (cards absent) — PWA parity. Badge covers all rendered stale slices per deviation 9.
  - **Retry** re-runs all of the current screen's fetches (bypasses the identical-key short-circuit).
- **Chart composables** (all through the shared transform + `ChartTheme` + scrub): `StatTile`, `PrTile`, `FocusCard`, `WeightCard`, `LineChartCard` (progression), `StackedBarCard`, `AerobicProxyCard`, `ValueTargetCard`, `UsageCard`, `AdherenceCard`, `HrvCard`, `RhrCard`, `SleepCard`, `BodyCard`, `CompositionCard` (+`MiniMetric`), `LabsSection` (+`MiniLab`).

## Behavior

### Ranges & queries
- Range window: `start = rangeStart(range, today)` (null for All), `end = today` — **today is DEVICE-local** (`LocalDate.now()` injected). The client **always sends explicit `end`** (PWA invariant; the server would otherwise default to server-local today). Query shape: `?start=…&end=…` or `?end=…` for All.
- `/overview` sends **no params at all** (server-local evaluation — parity). `/health/composition` + `/health/labs` send **`end` only, no start, range-immune**.
- Range/screen/exercise/tracker/labPanel changes write through to `trends_meta` immediately.

### Screens (rules the implementation must honor; file:line authority in the PWA)
- **Overview**: StatTiles (Zone2 sparkline sums `planned+extra` per week; Tonnage uses `tonnage_kg`; headline `lastWeek ?? 0`; delta per `statTileDelta`; "this week so far" line always; tap navigates + persists). PRTile only when `count_30d > 0`; latest line only when `latest != null`. FocusCard only when `adherence_focus` non-empty (dot per ribbon day, status-toned; "↓ dropping" chip). WeightCard only when `available && series.isNotEmpty()`: origin = first date, y-pad `(max-min)*0.15 || 0.5`, 28d mean (alt) + 7d mean (accent) + dots, y labels 1-decimal, 5 x-ticks via spread, `MM-DD`.
- **Strength**: exercise picker (persisted `ui.exercise`, fallback-to-first when stale; duplicate-name suffix rule). ProgressionCard: primary = top-set weight, second = e1RM, optional RPE series on fixed right-axis domain [5,10] (toggle; only sessions with `topSetRpe != null`); `muted = offPlan` carried on points; assisted-equipment subtitle variant (`"effective load (bw − assist) · e1RM ({unit})"`). VolumeCard: `foldVolumeStacks`, y formatter `v >= 1000 ? round(v/100)/10 + "t" : formatNum(v)`, partial-week visual distinction. PRBoard: per exercise — name, plateau chip, slug, best e1RM + `weight×reps (assist N) · date` detail, `allTime` null-guarded (deviation 7).
- **Cardio**: weekly stacked bars keys `[planned, extra]`; interval legend line when summed `intervalSessions > 0`. AerobicProxyCard: HR y-axis **fixed pad 4 bpm** (not proportional), dot radius `dotSizeScale(2.5, 7, durations)`, off-plan muted, empty text "No steady sessions with HR in range".
- **Journal**: tracker picker (persisted, fallback-to-first; label `"{name} ({unit})"` when unit present). Card visibility: ValueTarget iff `type == "quantifiable"`; Adherence iff `actionable`; Usage iff `weeklyUsage != null` (the omitted key). ValueTargetCard: `coerceNumeric` BEFORE filtering (F1); constant-series collapse to text; y domain includes non-null band bounds; **x domain `[xMin, xMax+1]`** (F15); segments `x1 = dayIndex(end)+1` (inclusive wire → exclusive geometry); draw order: band rects → 7d mean → dots. UsageCard: single-key bars, height 120, integer y labels. AdherenceCard: 360×64, index xScale with `max(n-0.5, 0.5)` guard, three-rect painter's layering, in-progress overlay, title `Weekly {metricKind}` (fallback "adherence"), 🔥 current/best streaks.
- **Health**: HrvCard: band via `dailyBandSegments` over `hrvBand` (Garmin's own baseline — never recomputed), y includes band bounds + `lowFloor`, x domain `+1`, **dot warn-tone iff `lowFloor != null && hrv < lowFloor`**. RhrCard: x domain NOT extended, dots under lines, 7d + 28d means. SleepCard: y-max **floored at 9h**, bars via `stackedBarLayout` with per-index closure mapping index→`dayIndex`→x, fixed 8h guide, sleep-score dots on fixed [0,100] right axis with literal "100"/"0" labels. BodyCard: weight + 7d mean + DEXA scans filtered **lexically** to the weight series' date span with `totalKg != null`; rings + connecting line when ≥2; empty-filter legend note "no scans in range — see composition below". CompositionCard: **range-immune, always all scans**; 5 fixed-order MiniMetric strips (Lean kg / Fat kg / Body fat % / VAT kg / A/G ratio) + shared `YY-MM` axis + whole-body BMD table (`bmdTotal != null` rows). LabsSection: panel picker (persisted, fallback-to-first); chartable/tabular partition; origin = lexically smallest date across ALL panel tests; MiniLab: reference band from the **latest observation only** (one-sided ranges clamp via `steppedBandRects` null handling), **warn-tone iff the obs carries `flag`** — never a recomputed range comparison; `prefix` charts at the numeric value but displays with prefix; table rows `text ?? (prefix + value ?? '—')` + unit, flagged rows toned, sub-label `"{date} · ref {refText}"`.

### fetchCached & staleness
Per the repository contract above. Badge text: oldest `staleFetchedAt` among the screen's rendered stale slices; `max(1, round((now-oldest)/60000))` minutes; `<60` → `cached · {m}m ago`, else `cached · {round(m/60)}h ago`; tooltip/contentDescription "Offline — showing cached data". No HTTP-layer caching exists (no Ktor cache plugin) — keep it that way or the fallback semantics double up.

### Interactivity (native addition — per-chart contract)
One `ChartScrubState` per chart. **Anchor construction**: one anchor per date/week key — series are merged into a lexically-sorted map keyed by date (or week start), each key yielding exactly one merged row model and one px anchor (duplicate anchors are forbidden: `ChartScrubState`'s nearest-index tie rule would strand all but the lowest). `updateAnchors` on every (re)layout. **Data identity**: each chart derives a stable identity from its request key (endpoint + range + selection); when identity changes, the composable calls `endScrub()` + `clearPin()` **before** `updateAnchors` — a pin on date A must never silently become date B's tooltip. Empty series clears everything (existing `updateAnchors` behavior). Pins also clear when a picker sheet opens or range/screen changes (deviation 8). Scrub state is never persisted.

Tooltip label = the anchor's date (`MM-DD`) or week label. Null formatting: a row with no value at that anchor is **omitted**; band rows render two-sided `low–high`, low-only `≥ low`, high-only `≤ high`, absent band → row omitted; nullable adherence `rate` → row omitted when null; lab rows show `{prefix}{jsNumberString(value)} {unit}` + flag chip when flagged.

| Chart | Anchor unit | Tooltip rows |
|---|---|---|
| WeightCard | day | kg, 7d mean, 28d mean |
| ProgressionCard | session | top set `weight×reps`, e1RM, RPE, "off-plan" marker |
| VolumeCard / Cardio bars / UsageCard | week (bar center) | per-key values + total (volume: top-3 slug names + other) |
| AerobicProxyCard | session | avg HR, duration min |
| ValueTargetCard | day | value + unit, 7d mean, target band |
| AdherenceCard | week cell | met/partial/missed of scheduled, rate % |
| HrvCard / RhrCard | day | hrv + band / rhr + means |
| SleepCard | day | hours, score |
| BodyCard | day + scan dates | kg, 7d mean; DEXA total on scan dates (same-date weight AND scan → both rows) |
| MiniMetric / MiniLab *(pending gate decision)* | scan / observation | value + unit (+ prefix, flag, ref band for labs) |

The scrub modifier must not steal vertical scroll (guaranteed by `chartScrub`'s slop logic — device-verified in Phase 5.5, re-verified in this phase's device matrix).

## CRITICAL porting notes (numbered for the impl brief)

1. `stackedBarLayout`/`ribbonCells` take an **INDEX-based xScale** `(Int) -> Double`. SleepCard exploits this with a closure mapping index→dayIndex→x. Keep the signature or Sleep geometry silently changes.
2. `steppedBandRects.x1` is exclusive; server `target_segments` are inclusive both ends → `+1` on conversion AND x-domain `xMax+1` on band-bearing charts (HRV, ValueTarget). RHR/Body do NOT extend. Dots sit left-of-center on band charts — intentional.
3. `dailyBandSegments` merges runs **across missing days**; only a null/incomplete band breaks (test #20).
4. `rollingMean` output: same length as input, nulls preserved, callers filter. Window is trailing-inclusive; gaps don't dilute.
5. `weekly_usage` is the API's **only omitted key** → default-null property. Everything else arrives as null/[]/value.
6. `completed` is `1|0|null` → `Int?`. `in_range` is null whenever `start` wasn't sent (All range!) — UI treats null as "no in-range block". `TrackerValue.value` is `JsonElement?` — never a numeric type.
7. Staleness exists only in `FetchResult.staleFetchedAt` — set when a cached copy was actually served (its stored stamp, not now), null on fresh success. No global staleness state.
8. `weight:{range}` cache entry is shared Overview↔Health; each consumer's freshness is its own envelope. Composition/labs keys are date-less on purpose.
9. JS `Math.round` rounds half **up toward +∞** (matters for negative values in `round2` and `statTileDelta`). Kotlin `Double.roundToInt()` / `java.lang.Math.round` have the SAME semantics and are fine; **`kotlin.math.round` (ties-to-even) is the one to avoid**. `floor(x + 0.5)` is an equivalent formulation. Pin with negative-half tests (`-2.5 → -2`, `-1.5 → -1`, `-0.5 → 0`).
10. `dayIndex` via `LocalDate.toEpochDay()` subtraction only.
11. `niceTicks`: the `+ step/1e6` epsilon and per-tick re-round are load-bearing; ticks stay inside `[min, max]`; `!(max > min)` → `[min]`.
12. `coerceNumeric(JsonElement?)`: booleans → null explicitly; blank strings → null; string parse via trim + `toDoubleOrNull()` + **isFinite check** (Kotlin parses `"Infinity"`, JS treats it as non-finite too — both must null it); NaN/Inf numerics → null.
13. Selection fallback everywhere (exercise/tracker/panel): stale persisted id → first item, silently, never an error.
14. Every slice resets to Loading at fetch start; toolbar renders above error/loading on all screens; `collectLatest` drops superseded completions.
15. YAxis: suppress a tick's LABEL when its formatted text equals the previous tick's; still draw the gridline.
16. `spread`: first + last always included; index-dedupe (deviation 10).
17. Zero weeks render as bars-with-no-segments (v ≤ 0 dropped) but keep their x-slot; `BarChartStacked` y domain `[0, max(totals, 1) * 1.05]`.
18. `linePath` exact output pinned (test #8): `"M 1 2 L 3 4.57"` — jsNumberString coords (integral → no decimal), single spaces, no trailing separator.
19. LineChart x domain spans points AND first/last xTicks; y-pad `(yMax-yMin)*0.08 || yMax*0.05 || 1` — JS `||` falls through on zero (port as "if zero, next").
20. Decode every measurement as Double (incl. avg_hr/rhr/sleep_score); never trust int-looking wire values.
21. The logical→px transform scales positions AND extents by `widthPx/360`; ink (strokes, radii, text) is dp/sp; `ChartScrubState` gets px anchors, pointer input is never divided.

## Testing

| Suite | Module | Cases |
|---|---|---|
| `ChartGeometryTest` — transcribed 1:1 from `trends-chart-logic.test.js` | :feature:trends | 21 + negative-coordinate round2 pins |
| `ChartPrimitivesTest` — rangeStart (4 ranges + unknown id), spread (index dedupe, ends included, n≥size), jsNumberString (integral / fractional / negative-half / `-0.0`), YAxis dedup rule | :feature:trends | new (~14) |
| `TrendsScreenLogicTest` — statTileDelta (incl. negative-half rounding), foldVolumeStacks, pickerLabels, constantSeriesNote, labsPartition, dayChart/dateTicks, adherence layering, selection fallback, logical→px transform at 180/360/720 px (bar centers, vertices, anchors), anchor merge (dedupe, sort, same-date weight+scan) | :feature:trends | new (~30) |
| `TrendsApiTest` (MockEngine) — all 11 paths, query omission vs presence, end-only endpoints, slug/id URL encoding | :core:data | new (~12) |
| `TrendsRepositoryTest` — full failure matrix rows 1–5 (incl. upsert-throw, cache-read-throw, fresh-decode failure), staleness stamp = served copy's, concurrent shared-key fetches (Overview + Health interleavings), cancellation propagation, cache-key inventory | :core:data | new (~16) |
| `TrendsDtoTest` — golden fixtures decode; weekly_usage present/absent; completed 1/0/null; in_range null; hrv_band nesting; mixed numeric/note-string tracker values; every Double field decoded from integer AND decimal wire forms; available:false variants | :core:data | new (~16) |
| `TrendsPrefsTest` — defaults, write-through, ui.-prefix isolation | :core:data | new (~6) |
| Per-screen ViewModel tests — single initial fetch, no config-recreation refetch, rapid 4w→12w flip (late completion dropped), error-clear-at-start, supervisorScope isolation (Health 3-of-4 swallow, Overview weight-slice rule), retry, slice request-keying | :feature:trends | new (~24) |
| Migrations 3→4 and 1→4 chain | instrumented | 2 (written now, executed next emulator session) |

**Device acceptance matrix** (ship checklist for the APK; not JVM-claimable): scrub vs vertical scroll on every chart type; pin → open picker → pin cleared → back closes sheet once → chart still scrollable; pin → rotate → no stale tooltip; airplane-mode cache fallback + stale badge on each screen; range flip mid-flight shows Loading not stale-range data; MiniMetric/MiniLab scrub ergonomics (per gate decision); migration runs clean on a v3 install.

Golden fixtures (`testdata/golden/trends/`): hand-authored synthetic JSON per endpoint incl. `available:false` variants, the weekly_usage present/absent pair, and a mixed numeric/note-string tracker-values fixture — shapes cross-checked against the server's exact-JSON tests (`test/trends/test_*_endpoint*.py`), values invented, `fixture-` prefix rule for ids/slugs/names per the existing README. **Never copied from any live or dev database.**

Kover: gate stays at 80; full-invocation-only rule applies (CLAUDE.md kover gotcha). Chart composables follow the Phase 5.5 pattern (geometry pure and tested; Canvas draw lambdas thin).

## Dependencies

- Existing: `payload_cache` (Phase 1), `ChartScrubState`/`chartScrub`/`ChartScrubTooltip`/`ChartTheme` (Phase 5.5), `isNetworkError`, `ServerConfig`, `WellnessJson`, `DebugLog`, JournalUiPrefs pattern, Graphite Signal tokens.
- New: Room v4 (`trends_meta` — plan §4 amended at approval commit), `:feature:trends` implementation (module skeleton exists), Koin wiring (repository + prefs + 5 ViewModels), nav registration of the Trends tab content (replacing the placeholder).
- No new third-party libraries.

## Open questions

None. Resolved at the 2026-08-09 approval gate:
1. **MiniMetric/MiniLab scrub: ON** with vertically-expanded gesture area (plan-faithful "every chart interactive"); ergonomics reviewed on device at ship time.
2. **Health stale-badge scope: all rendered stale slices** (deviation 9 stands as the default improvement over the PWA's recovery-only badge).
