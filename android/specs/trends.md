# Spec: Trends (Phase 6)

Status: **approved 2026-08-09** (v2 after Codex review — 5 blockers + 12 majors + 2 minors folded; user-approved with mini-strip scrub ON (expanded hit area) and all-stale-slices Health badge)

> **v2 (Codex review fold):** (1) `weight()` gains `range`; authoritative cache-key table added. (2) `TrackerValue.value` is `JsonElement?` — a `Double?` would fail the whole decode on note-era legacy strings before `coerceNumeric` ran; `coerceNumeric` operates on `JsonElement?`. (3) The global staleness map is replaced by a per-call `FetchResult<T>(value, staleFetchedAt)` envelope — a shared cache key (`weight:{range}`) made one map incoherent across consumers. (4) Screen state is request-scoped: slices reset at fetch start, late completions for a superseded request are dropped. (5) All former "check at port time" items resolved: range selector on every screen (Codex-verified in PWA); Overview per-slice error rule defined below. (6) DTO section upgraded to compilable authority: wire names explicit, physiological measurements all `Double`. (7) fetchCached failure matrix completed (fresh-decode, cached-decode, DAO failures, cancellation, atomic updates). (8) HTTP error display mapper defined. (9) ViewModel lifecycle/supervision contract defined. (10) Room v4 fully specified; migration tests execute in the next emulator session (not before). (11) Scaling pipeline mandated: positions AND extents scale, ink doesn't; px anchors only. (12) Scrub state clears on data-identity change; pickers/range changes clear pins. (13) Union-anchor + tooltip null-formatting rules defined. (14) Health stale badge covers every rendered stale slice (declared deviation, default — user may pick PWA parity at the gate). (15) `jsNumberString` contract shared by `linePath` and labels. (16) CRITICAL note on rounding corrected: `roundToInt()` matches JS `Math.round`; `kotlin.math.round` (ties-to-even) is the one to avoid. (17) `all_time` nullability reframed as defensive forward-compat (server currently never emits null). (18) `TrendsApiTest` + device acceptance matrix added.

> **v2.1 (code-review fix round, 2026-08-09):** Codex on the working tree found 1 BLOCKER + 2 MAJOR + 2 MINOR; direct inspection added 1 MINOR + 1 NIT. All fixed: (a) **Selection discipline** — a shared in-memory `selection` flow leads and storage follows; fallback reconciliation consults storage only when nothing was chosen this session and re-checks after the suspending read; writes are generation-guarded under a mutex so a superseded fallback becomes a no-op, never a queued overwrite (the guard is defensive — no reachable interleaving pins it — and is documented as such). Selections that drive fetches are never seeded from storage before the confirming list arrives. (b) **Health per-slice load keys** — recovery/weight keyed by range, composition/labs by end date; a range change no longer refetches (or degrades) range-immune slices; Retry clears all four. (c) **Ink is typed `Dp`** on `PlotDot`/`PlotRect` radii — scaling ink through `LogicalScale` no longer compiles. (d) **Domain-bounded union anchors** — every date inside the plotted x domain carrying any rendered datum (raw value, rolling mean, band, score) gets exactly one anchor; dates outside the domain get neither marks nor anchors. (e) **labPanel fallback persists** via the same discipline, with one declared divergence: an EMPTY panel list keeps the remembered panel (nothing fetches on it; Strength/Journal clear on empty because a stale slug would otherwise be requested — and their clear is in-memory only, storage keeps the id for re-adoption). (f) Anchor-merge label fallback no longer throws on unlabeled contributions. Round-1 accepted choices stand: `dotsBelowLines` on RHR only; t-score null renders "—" (PWA prints literal "null" — a bug, not ported); third stack colour borrows Journal amber pending device review; `Cache-Control: no-store` headers on all trends GETs; `dateTicks` returns domain-space x.

> **v2.2 (sleep need / sleep debt, 2026-08-26):** a twelfth endpoint, `GET /api/trends/health/sleep`, and the first thing in this module the PWA does not also render. Three amendments to the text above: the endpoint count is **12**; the "single omitted key is `weekly_usage`" claim is superseded (sleep omits `as_of`, `tonight`, `gap`, `strain_partial`); and the Health screen gains a headline card above the charts. The card's rules and its `hoursMinutes` formatter live in **`:core:data`**, not `:feature:trends` — a home-screen widget is the next phase and cannot depend on a feature module. Unlike the four v2.1 load keys, the ledger's loaded key is **range AND end date** (`"$range|$end"`): `tonight` is a fact about a calendar day, so a retained ViewModel reactivated after midnight refetches at the same range where recovery would not; the model itself is computed at every composition (never `remember`ed) so the cached-copy age and the today comparison cannot freeze on a stale clock. Full rules in §Sleep need.

> **v2.3 (sleep debt flipped to on-waking, 2026-08-26):** `days[].debt_min` now carries the debt **on waking** from that night — the night's own product — where v2.2 carried the debt *entering* it. The wire field keeps its name (the feature is hours old and this client is its only consumer). The need arithmetic is untouched: a night's need still spends the debt it *entered* on, which on consecutive rows is simply the **previous** row's `debt_min`. The flip buys one property the incoming side could not have: on the app's own `end=today` requests, **`tonight.debt_min` equals the last emitted row's `debt_min`** whenever that row is today's or yesterday's, so the headline card and the last point of the debt chart are the same number instead of two numbers a night apart (they diverge — card 0 — only past the server's carry window, when the last scored night is older than yesterday; a clipped `end` would hide newer rows from `days` while `tonight`, which ignores the range, still carries them — server-pinned as deliberate, and unreachable from this client). Consequences here: the scrub row reads **`woke with`** and the debt legend reads **`debt on waking`**; the gap rule is unchanged in behaviour but not in meaning — a `gap` row still breaks the line and takes the WARN ring, now because the night before it was never observed, **not** because its debt is zero (it usually is not). `debtLine` on the card is unchanged.

> **v2.4 (pull to refresh + on-demand Garmin sync, 2026-08-27):** every sub-screen gains a pull
> gesture that refetches the screen **and** asks the server to sync Garmin now, over a new headless
> server module at `/api/garmin`. Three amendments to the text above: the module is no longer
> **read-only** (`POST /api/garmin/sync` is a command, and the first write anything in Trends makes);
> deviation 14's "every slice resets to Loading when its fetch starts" gains one stated exception, a
> refresh, which keeps what is drawn; and the §API "Omitted keys" inventory stays a closed statement
> about the twelve `/api/trends` endpoints because the garmin payloads carry their own note. Full
> rules in §Pull to refresh.

## Goal

Port the PWA Trends module at behavioral parity: 5 read-only chart screens (Overview / Strength / Cardio / Journal / Health) over 12 GET endpoints (11 ported; `/health/sleep` is native-first — see §Sleep need), network-first fetching with `payload_cache` fallback and a staleness badge, the full `chart-logic.js` pure-geometry port (13 functions, 21 transcribed test cases), and a persisted range selector (4w/12w/6m/All) — all rendered through the Phase 5.5 Graphite Signal chart foundation (`core/ui/chart/`), which means **every major chart ships with scrub/pin/tooltip interactivity the PWA does not have** (plan-mandated native addition; the PWA has zero chart interactivity — verified).

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
| `healthSleep(start?, end, range)` | `health/sleep:{range}` | `start?`, `end` |
| `healthComposition(end)` | `health/composition` — date-less, overwritten daily | `end` only |
| `healthLabs(end)` | `health/labs` — date-less, overwritten daily | `end` only |

## API / Interface

### `:core:data` — `trends/` vertical

**DTOs** (`TrendsDtos.kt`, kotlinx.serialization, shared `WellnessJson` — which has **no naming strategy**, so every multi-word field carries `@SerialName` with the exact wire name shown below; golden-fixture decode tests enforce completeness). Typing rules: **every physiological/measurement field is `Double`** (`avg_hr`, `rhr`, `sleep_score` included — the wire flips int/float freely); `Int` only for true counts/ordinals (`reps`, `set_count`, `session_count`, `count_30d`, `hard_sets`, `interval_sessions`, `scheduled_days`, `met`, `partial_days`, `missed`, `count`, streaks). Nullability mirrors the wire exactly.

**Omitted keys** (every one carries a default here; everything else arrives as a value, an explicit null, or an empty list): `weekly_usage` on `/journal/tracker/{id}`, and — added with the sleep ledger — `as_of`, `tonight`, `gap` and `strain_partial` on `/health/sleep`. *(Before the ledger this section read "the API's single omitted key is `weekly_usage`"; that claim is superseded, and `weekly_usage` remains the only omitted key on the eleven original endpoints.)*

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

// GET /health/sleep
SleepDebtDto(available: Boolean, @SerialName("as_of") asOf: DateString? = null,
             tonight: SleepTonight? = null, days: List<SleepDebtDay>)
  SleepTonight(date: DateString, @SerialName("need_min") needMin: Double,
               @SerialName("debt_min") debtMin: Double, @SerialName("strain_est") strainEst: Double,
               @SerialName("strain_partial") strainPartial: Boolean = false)   // always true on the wire
  SleepDebtDay(date: DateString, @SerialName("need_min") needMin: Double,
               @SerialName("slept_min") sleptMin: Double, @SerialName("debt_min") debtMin: Double,
               @SerialName("strain_est") strainEst: Double, gap: Boolean = false)  // omitted when false

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

**`TrendsApi`** (JournalApi conventions): 12 suspend functions returning **raw body text** (`bodyAsText()`), nullable `start`/`end` appended only when non-null — plus, since v2.4, the two `/api/garmin` commands, which are neither aggregates nor reads (see §Pull to refresh). `/overview` and `/journal/trackers` take no params; `/health/composition` + `/health/labs` take `end` only; `/health/sleep` takes `start?` + `end` like `/health/recovery`. Slug/id path segments URL-encoded. Pinned by `TrendsApiTest` (MockEngine): every path, query omission-vs-presence, URL encoding of a slug needing it.

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
    suspend fun healthSleep(start: DateString?, end: DateString, range: String): FetchResult<SleepDebtDto>
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
- **Health**: order down the page is **tonight card → HRV → Resting HR → Sleep → Sleep need → Body → Composition → Labs** (see §Sleep need for the first and the fifth). HrvCard: band via `dailyBandSegments` over `hrvBand` (Garmin's own baseline — never recomputed), y includes band bounds + `lowFloor`, x domain `+1`, **dot warn-tone iff `lowFloor != null && hrv < lowFloor`**. RhrCard: x domain NOT extended, dots under lines, 7d + 28d means. SleepCard: y-max **floored at 9h**, bars via `stackedBarLayout` with per-index closure mapping index→`dayIndex`→x, fixed 8h guide, sleep-score dots on fixed [0,100] right axis with literal "100"/"0" labels. BodyCard: weight + 7d mean + DEXA scans filtered **lexically** to the weight series' date span with `totalKg != null`; rings + connecting line when ≥2; empty-filter legend note "no scans in range — see composition below". CompositionCard: **range-immune, always all scans**; 5 fixed-order MiniMetric strips (Lean kg / Fat kg / Body fat % / VAT kg / A/G ratio) + shared `YY-MM` axis + whole-body BMD table (`bmdTotal != null` rows). LabsSection: panel picker (persisted, fallback-to-first); chartable/tabular partition; origin = lexically smallest date across ALL panel tests; MiniLab: reference band from the **latest observation only** (one-sided ranges clamp via `steppedBandRects` null handling), **warn-tone iff the obs carries `flag`** — never a recomputed range comparison; `prefix` charts at the numeric value but displays with prefix; table rows `text ?? (prefix + value ?? '—')` + unit, flagged rows toned, sub-label `"{date} · ref {refText}"`.

### Sleep need (native-first, added 2026-08-26)

`GET /api/trends/health/sleep?start&end` is the one endpoint here the PWA does **not** render — the server computes a causal sleep-need / sleep-debt ledger and the native client is its first consumer. Two surfaces, and the split between them is deliberate.

**Where the logic lives.** `sleepTonightModel` / `hoursMinutes` / `TonightJudgment` / `SleepTonightModel` sit in **`:core:data`** (`trends/SleepDebtLogic.kt`), not beside the chart builders, and the card composable sits in **`:core:ui`** (`SleepTonightCard.kt`). The card is planned to move to a start screen and to a Glance widget rendered by a `CoroutineWorker` with no `:feature:trends` on its classpath; everything it needs is therefore already on a module a widget can reach. `JournalUiLogic` is the precedent for pure display rules living in `:core:data`.

**Tonight card** — directly under `RangeToolbar`, above the recovery sections. Absent (not an error, not a placeholder) when the slice is unavailable, `available: false`, or carries no `tonight`. Model rules, each pinned verbatim by `SleepDebtLogicTest`:

| Field | Rule |
|---|---|
| `needText` | `hoursMinutes(tonight.need_min)` — `H:MM`, one `roundToInt` on the **total** minutes (so 59.6 → `1:00`, never `0:60`), negatives clamped to zero |
| `debtLine` | `no sleep debt` when `debt_min == 0`, else `debt H:MM`; ` · reset — missing night` appended when `days.last().gap` |
| `strainLine` | `strain N.N` — fixed one decimal (an instrument reading on a 0–21 scale, deliberately **not** the integer-collapsing `jsNumberString` rule), plus ` · so far` when `strain_partial` |
| `freshnessLine` | precedence, not concatenation: `tonight.date != today` → `for <date>`; else `as_of` absent → `no scored nights yet`; else `as_of != today` → `data through <as_of>`; else null |
| `cachedLine` | `cached · Nm/Nh ago` — the **same wording rule** as `TrendsScreenLogic.staleBadgeText`, deliberately duplicated in `:core:data` because a widget cannot depend on a feature module. Comments on both sides say so |
| `judgment` | trailing `gap` → `ATTENTION` + `flagged` (the mono `!`, never a colour); else stale cache **or** any `freshnessLine` → `PARTIAL`; else `SETTLED` |

`today` and `now` are parameters, never read inside — the function stays pure, and a widget rendering at 03:00 gets the same answer as a test.

**Sleep-need panel** — `sleepDebtSection(days, tonight)` in `feature/trends/.../chart/SleepDebtModels.kt` (its own file; `HealthModels.kt` already carries six cards). Rendered after the recovery sections and before Body, as `LogbookSection("Sleep need", sub "h · need vs slept", trailing = latest)`. Null when `days` is empty.

- **Need vs slept**: `slept_min/60` as daily bars via `stackedBarLayout` with the index→`dayIndex`→x closure, exactly as `sleepCardModel`; `need_min/60` as a `PlotTone.SECONDARY` polyline via `seriesToPoints`. y-max = `max(slept.max, need.max, 9.0) * 1.05` — the **9h floor is shared with `SleepCard`** so a night is the same size on both. **No 8h guide**: the need line *is* the guide, and a second reference would invite reading a personal target against a generic one.
- **Debt on waking**: `debt_min/60` in `PlotTone.SCAN` — each night's own product (v2.3), so the last point equals what the tonight card says. **Split into one `PlotLine` per continuous run** — a `gap` row *begins* its run, so no segment is ever drawn across the unobserved night that precedes it — with a `PlotTone.WARN` ring on each gap day. The ring marks a break in the *record*, not a zero: a flagged row's own debt is plotted like any other. Own canvas rather than a second axis (a different quantity in the same unit). Null under two rows. Debt y-max floors at 1h, so a debt-free fortnight does not get scaled to look like a bad one.
- Both charts share **one x scale and one anchor list**, so a scrub reads the same date on either.
- `ChartInk.SLEEP_NEED`: `PRIMARY` = ink-soft BAR (as `SLEEP`'s hours), `SECONDARY` = plate 1 **LINE** — solid, because a computed target is not an annotation of the bars beneath it and a dash would read as "rolling mean". Legends mandatory on both charts (`slept`/`need`, `debt on waking`/`reset`); the debt canvas takes no plan and so resolves through the default.

**Wiring.** `HealthUiState.sleep: Slice<SleepDebtDto>`; `HealthViewModel` keys it on `loadedSleepRange` (cleared in `forgetLoaded`) as a fifth `loadSlice` branch under the same `supervisorScope`. Its failure is **swallowed** — recovery stays the screen's only `ScreenError` — and its `staleFetchedAt` joins `staleStamps`. Cache key `health/sleep:{range}`: the ledger is range-independent but the *response* is clipped, so a 4-week copy served to an All request would silently shorten the panel. No Room migration (`payload_cache` is generic), no Koin change.

**Contract guarantees relied on** (server-stated): `days` always present; `debt_min` never negative and measured on **waking** (v2.3); `strain_est` on every row; optional fields omitted, never null; debt independent of the requested `start`; on `end=today` requests (this client's only shape), `tonight.debt_min == days.last().debt_min` while the last row is today's or yesterday's. Rows are keyed by **wake** date, so they overlay `SleepCard` 1:1. Full server-side definition in `../../docs/ARCHITECTURE.md` (Phase 4 — sleep need / debt), which this section defers to.

### Pull to refresh, and the on-demand Garmin sync behind it (added 2026-08-27)

A pull on any of the five sub-screens refetches that screen **and** asks the server to sync Garmin
now — the first thing in this module that writes anything anywhere. Fresh Garmin data otherwise
arrives only on the 9am/2pm/10pm cron, so a user looking at a recovery chart had no way to ask for
today's night.

**Two phases, because they answer on different timescales.**

- **Phase one** — `POST /api/garmin/sync` fired *alongside* a full refetch (an `async`, not a
  sequential call: the POST is bounded only by the client's request timeout, and a slow server must
  not hold a spinner over data that is already back). The spinner belongs to the **refetch**, with a
  500 ms minimum visible time; holding it over a fifteen-second garmy run would be a lie about what
  the user is waiting for.
- **Phase two**, entered only on `status` `started` or `running` — poll `GET /api/garmin/sync/status`
  every **3 s** to a **60 s** cap, say so in `syncBanner`, then one silent refetch so the new data
  lands in place. `cooldown`, `unconfigured`, and an unreachable server all end the gesture at phase
  one; the local refetch happened either way, because the pull was also a refresh. A poll request
  that fails skips its cycle rather than ending the watch — one dropped request says nothing about
  the sync — and a watch that hits the cap refetches anyway, because a long sync has probably still
  written something.

**Refresh keeps values.** `loadSlice` gains a `keepValues` mode that **skips the `Slice.Loading`
assignment**, so a pull never blanks a chart (deviation 14 stands for every other load: a slice is
still never rendered under a toolbar state it does not match). The flag is raised for one refetch and
cleared by the refetch itself (in a `finally`) once the WHOLE cycle settles — **not** on a transient
zero of the in-flight counter, which occurs in the gap before a dependent second wave and would blank
it (Codex review). Left standing past the cycle, the next range change would skip its own blanking
and show the old range's charts under the new range's toolbar — the `finally` keeps that invariant
on every exit path.

**Three internals are load-bearing, and each is a fixed bug rather than a preference.**

1. **The spinner keys off a load counter, not off `collect()`.** `collect()` never settles — every
   screen ends in an infinite `collectLatest` over the range. `loadSlice` maintains
   `SliceLoads(started, inFlight)` instead: `started` is **monotonic**, and the refresh waits for
   `started > (the count taken before the cycle) && inFlight == 0` — and then believes the zero only
   after it survives a **200 ms quiet window** (re-checked, looping): Strength's detail fetch launches
   only once its list has landed, across exactly such a zero, and the first-zero version ended the
   spinner and blanked the second wave (Codex review). Waiting for the count to *rise*
   and then fall is the version that does not work: a `StateFlow` conflates, so a fully cached
   refetch that begins and ends between two turns of the main dispatcher shows the observer nothing
   but the 0 it started from, and the spinner hangs until the cap on exactly the case that should be
   fastest.
2. **The internal refetch cycles the collect job directly** — never `onInactive(); onActive()`. The
   phase-two watch runs in the ViewModel's scope, and `onInactive()` cancels it: a completion refetch
   routed that way would cancel *itself* at the `onInactive()` line and never reach the relaunch,
   leaving the collector dead and every later range change silently fetching nothing. `retry()` moved
   off that pair for the same reason (a Retry tap on one failed card has no business ending a sync),
   and the real `onInactive()` — screen dispose — remains the only thing that stops a watch.
   Ordering inside the refetch is not interchangeable either: `cancelAndJoin()` **before** raising
   `keepValues`, because cancellation is only *marked* synchronously and the cancelled `finally`
   blocks would otherwise run afterwards, drain the counter to zero and clear the flag.
3. **The completion refetch is a sibling `launch`, TRACKED as `completionJob`**, and the watch itself
   is a sibling of the refresh job — so the refresh completes with its spinner and a second pull is
   not refused for the length of a garmy run. `onInactive()` cancels the tracked sibling, and the
   refetch's entry guard (`job` must be active) refuses to run on a disposed screen — an untracked
   sibling could otherwise slip past dispose and relaunch the collector on a dead surface, leaving it
   fetching and range-watching forever (Codex review HIGH).

**Phase-two scope is PER SUB-SCREEN, and that is ACCEPTED.** The watch and the banner live in the
ViewModel of the screen that was pulled, so switching sub-screens mid-sync abandons both. The data
still lands on the server; the newly-viewed screen shows its cached slices until the user pulls
there, and pulling again is the recovery. No cross-ViewModel shared state is introduced for it.

**`syncBanner: StateFlow<String?>`** — `"syncing Garmin…"` while phase two runs, `"Garmin sync
failed"` transiently (6 s) when the server reports `last_outcome: "failed"`, null otherwise.
Rendered by `SyncBanner` directly under the `RangeToolbar` in the **`StaleCaption` idiom** — same
mono eyebrow, same ink-soft, uppercased at render, absent rather than blank when there is nothing to
say. Deliberately the same voice: both are footnotes about where the numbers came from, and a second
visual language for "the data may be about to change" would be two ways of saying one thing.

**Endpoints and DTOs** (`core/data/trends/GarminSyncDtos.kt`; the API methods sit on `TrendsApi` and
the passthroughs on `TrendsRepository`, because Trends is their only consumer — a `GarminApi` split
for two methods would cost a Koin registration to say the same thing, and is the right move the day a
second consumer appears):

```kotlin
// POST /api/garmin/sync   → 200 even for "already running" (a pull treats it as success-shaped)
GarminSyncTrigger(status: String,                                   // started|running|cooldown|unconfigured
                  @SerialName("retry_in_sec") retryInSec: Double? = null)

// GET /api/garmin/sync/status
GarminSyncStatus(running: Boolean,
                 @SerialName("last_finished_at") lastFinishedAt: Long? = null,   // epoch ms
                 @SerialName("last_outcome") lastOutcome: String? = null,        // ok|failed
                 @SerialName("last_synced_at") lastSyncedAt: Long? = null)       // epoch ms
```

**Omitted keys — the garmin module's own note.** All four optionals above are **omitted when absent,
never null**, so each default is what the key's *absence* means. This is scoped here deliberately:
the §API "Omitted keys" inventory is a closed statement about the **twelve `/api/trends` endpoints**,
and it stays literally true because these two are not among them. `retryInSec` is a `Double` (a
remainder off a clock, not a count — an `Int` would fail the whole payload the first time the server
emitted `540.3`, taking the `status` the client actually acts on down with it); the two epoch-ms
fields are `Long`, which the server confirms it emits as integers.

**No caching, in either direction.** The two calls bypass `fetchCached` — not merely because there is
nothing worth storing, but because a cached `"started"` is a lie the moment it is written and a cache
*read* would answer a command with a stale success. An unreachable server means the sync did not
start, which is exactly what the caller needs to hear, so the exception propagates and the ViewModel
decides. They therefore have no row in the cache-key inventory above, which remains a complete list
of what this module stores.

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
| Sleep-need + sleep-debt charts | wake date (**one anchor list, shared by both**) | `slept` / `need` / `woke with` in `h:mm` (the third is the debt on waking, v2.3); plus a `reset` → `missing night` row on a gap day, explaining the *need* above it |

The scrub modifier must not steal vertical scroll (guaranteed by `chartScrub`'s slop logic — device-verified in Phase 5.5, re-verified in this phase's device matrix).

## CRITICAL porting notes (numbered for the impl brief)

1. `stackedBarLayout`/`ribbonCells` take an **INDEX-based xScale** `(Int) -> Double`. SleepCard exploits this with a closure mapping index→dayIndex→x. Keep the signature or Sleep geometry silently changes.
2. `steppedBandRects.x1` is exclusive; server `target_segments` are inclusive both ends → `+1` on conversion AND x-domain `xMax+1` on band-bearing charts (HRV, ValueTarget). RHR/Body do NOT extend. Dots sit left-of-center on band charts — intentional.
3. `dailyBandSegments` merges runs **across missing days**; only a null/incomplete band breaks (test #20).
4. `rollingMean` output: same length as input, nulls preserved, callers filter. Window is trailing-inclusive; gaps don't dilute.
5. `weekly_usage` is the **only omitted key on the eleven ported endpoints** → default-null property; everything else there arrives as null/[]/value. `/health/sleep` adds four more (`as_of`, `tonight`, `gap`, `strain_partial`), each with a default whose value is what the key's *absence* means — `gap = false`, `strainPartial = false` — rather than what the server happens to send (it always sends `strain_partial: true`).
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
| `TrendsApiTest` (MockEngine) — all 12 paths, query omission vs presence, end-only endpoints, slug/id URL encoding | :core:data | new (~12) |
| `SleepDebtLogicTest` — `hoursMinutes` rounding table (incl. 59.6 → `1:00`), null-model matrix, judgment matrix (settled / stale / `as_of` lag / date mismatch / gap), every line string verbatim | :core:data | new (~16) |
| `SleepDebtModelsTest` — empty → null, bar x through the index closure at a pinned frame, the 9h floor and the above-floor case, need tone SECONDARY, debt split at a gap into two `PlotLine`s + the WARN ring, shared anchors and their `h:mm` rows, legends, `latest` | :feature:trends | new (~14) |
| `TrendsRepositoryTest` — full failure matrix rows 1–5 (incl. upsert-throw, cache-read-throw, fresh-decode failure), staleness stamp = served copy's, concurrent shared-key fetches (Overview + Health interleavings), cancellation propagation, cache-key inventory | :core:data | new (~16) |
| `TrendsDtoTest` — golden fixtures decode; weekly_usage present/absent; completed 1/0/null; in_range null; hrv_band nesting; mixed numeric/note-string tracker values; every Double field decoded from integer AND decimal wire forms; available:false variants | :core:data | new (~16) |
| `TrendsPrefsTest` — defaults, write-through, ui.-prefix isolation | :core:data | new (~6) |
| Per-screen ViewModel tests — single initial fetch, no config-recreation refetch, rapid 4w→12w flip (late completion dropped), error-clear-at-start, supervisorScope isolation (Health 3-of-4 swallow, Overview weight-slice rule), retry, slice request-keying | :feature:trends | new (~24) |
| Pull-to-refresh section of `TrendsViewModelTest` — refresh keeps Ready values (the blanking regression); keeps-values dies with the cycle so the next range change DOES blank; spinner lifecycle incl. the floor; re-entrancy; `started` → 3 s polls → completion refetch; **the completion refetch does not kill its own collector** (the self-cancellation regression, asserted by a range change fetching afterwards); **the spinner holds through a dependent second wave** (Strength's detail; the transient-zero regression); **a Garmin watch cannot revive a disposed screen** (tracked completion job + entry guard, probed by a post-return range change answered once); cooldown and unconfigured skip phase two; a failing trigger still refetches; the failed banner is transient; `onInactive` stops the watch; the 60 s cap still refetches; a failed poll skips its cycle | :feature:trends | new (12) |
| `GarminSyncDtoTest` — four trigger statuses, `retry_in_sec` present/absent/fractional, a status with only `running` (all three optionals defaulting to null), a full status, a failed outcome, unknown key ignored. **Inline JSON, not goldens**: two payloads of four scalar keys each would need five fixture files to cover the omitted-key matrix, and `TrendsApiTest` in the same module already asserts against inline bodies | :core:data | new (7) |
| `TrendsApiTest` — the two garmin paths under `/api/garmin`, POST vs GET, no-cache headers, no query | :core:data | +3 |
| `TrendsRepositoryTest` — both passthroughs decode; neither reads nor writes `payload_cache`; an unreachable server fails the trigger rather than serving a cached one | :core:data | +3 |
| Migrations 3→4 and 1→4 chain | instrumented | 2 (written now, executed next emulator session) |

**Device acceptance matrix** (ship checklist for the APK; not JVM-claimable): scrub vs vertical scroll on every chart type; pin → open picker → pin cleared → back closes sheet once → chart still scrollable; pin → rotate → no stale tooltip; airplane-mode cache fallback + stale badge on each screen; range flip mid-flight shows Loading not stale-range data; MiniMetric/MiniLab scrub ergonomics (per gate decision); migration runs clean on a v3 install.

Golden fixtures (`testdata/golden/trends/`): hand-authored synthetic JSON per endpoint incl. `available:false` variants, the weekly_usage present/absent pair, and a mixed numeric/note-string tracker-values fixture — shapes cross-checked against the server's exact-JSON tests (`test/trends/test_*_endpoint*.py`), values invented, `fixture-` prefix rule for ids/slugs/names per the existing README. **Never copied from any live or dev database.**

Kover: gate stays at 85; full-invocation-only rule applies (CLAUDE.md kover gotcha). Chart composables follow the Phase 5.5 pattern (geometry pure and tested; Canvas draw lambdas thin).

## Dependencies

- Existing: `payload_cache` (Phase 1), `ChartScrubState`/`chartScrub`/`ChartScrubTooltip`/`ChartTheme` (Phase 5.5), `isNetworkError`, `ServerConfig`, `WellnessJson`, `DebugLog`, JournalUiPrefs pattern, Graphite Signal tokens.
- New: Room v4 (`trends_meta` — plan §4 amended at approval commit), `:feature:trends` implementation (module skeleton exists), Koin wiring (repository + prefs + 5 ViewModels), nav registration of the Trends tab content (replacing the placeholder).
- No new third-party libraries.

## Open questions

None. Resolved at the 2026-08-09 approval gate:
1. **MiniMetric/MiniLab scrub: ON** with vertically-expanded gesture area (plan-faithful "every chart interactive"); ergonomics reviewed on device at ship time.
2. **Health stale-badge scope: all rendered stale slices** (deviation 9 stands as the default improvement over the PWA's recovery-only badge).
