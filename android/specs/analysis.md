# Spec: Analysis (Phase 7)

Status: **approved 2026-08-09** (v2 after Codex review — 2 blockers + 12 majors + 3 minors folded; user-approved with commonmark-java 0.24.0 and pause-and-readopt background behavior, no notification)

> **v2 (Codex review fold — 2 BLOCKERs, 12 MAJORs, 3 MINORs):** (1) `ActiveReport` is a sealed UI type — the submit stub cannot inhabit `ReportDetailDto` (no `created_at` exists until the first poll tick). (2) Store concurrency is a serialized, generation-guarded contract — `Job.cancel()` alone cannot stop an already-returned tick from committing state; every mutation runs on a single-threaded control dispatcher and carries a generation check. (3) The view-transition matrix is total (all 4 views × all events). (4) Lifecycle ownership defined: store created eagerly in `Application.onCreate`, pre-init foreground calls only latch a flag, failed-init retry on foreground. (5) Poll termination policy: transient errors retry forever (deliberate, foreground-only); polled-id 404 stops; UNKNOWN status gets a 600 s ceiling — the Goal no longer overclaims. (6) Repository adopts the Trends five-row failure matrix (declared deviation from the PWA's catch-all fallback) + typed `AnalysisHttpException(status, detail)`. (7) Delete vs active poll defined; `PayloadCacheDao` gains `delete(module,key)` (no migration). (8) Event transport named: `AnalysisEvents` channel singleton, `SyncErrorEvents` precedent. (9) **Escaping contract corrected for Compose**: raw HTML nodes pass their raw lexeme through as inert `Text` — UNescaped; entity-escaping was an innerHTML-world requirement and would render visibly wrong here; the 4 JS vectors are translated semantically, not textually. (10) Render model is recursive and total: H1–H6 (the 4th JS vector pins H1), nested inlines, links keep their URL (`label (url)`), unknown nodes fall back to source-span text — nothing silently disappears. (11) commonmark-java license corrected to **BSD-2-Clause**, version pinned 0.24.0. (12) Location/retry UX made deterministic (field on the expanded card; retry never auto-submits for location queries). (13) Timestamp zone injectable (`ZoneId.systemDefault()` prod) + malformed-input policy. (14) Module-disabled = `/queries` 404 at init, exact mapping given. (15) Cache pruned to the newest-50 ids on each fresh history fetch (declared deviation from PWA's unbounded cache). (16) Test table extended with every race above + device acceptance matrix.

> **v2.1 (code-review fix round, 2026-08-09 — authoritative over any conflicting v2 cell):** Codex on the working tree: 1 BLOCKER + 3 MAJOR + 2 MINOR, all verified; the state model itself carried two defects the implementation faithfully reproduced. Amendments:
> **(a) `viewing: ReportDetailDto?` slot added.** REPORT renders `viewing`, never `active`. `openReport(terminal)` sets only `viewing` (poll and `active` untouched). `openReport(non-terminal)` clears `viewing`, adopts as `active`, → PROGRESS. Terminal tick from PROGRESS copies the result into both `active` and `viewing` → REPORT. Terminal tick while in REPORT (any id) leaves `viewing` unchanged; `active` still updates. Foreground's "terminal report displayed" check inspects `viewing`. Delete stops the poll **only when `pollTarget == id`**; `active` and `viewing` clear independently on id match; view → QUERIES only when the user is on REPORT viewing the deleted id (or on PROGRESS when the poll target was deleted).
> **(b) Open-request generation** (BLOCKER): `openReport` carries its own monotonic generation, captured with the requested id before the GET and re-checked before commit; invalidated by any newer `openReport`, submit/409/foreground adoption, navigation intent, or delete of its target. Never reuses `pollGeneration` (opening a history report must not disturb the background poll). Prevents: a deleted report resurrecting into view, and two rapid selections landing out of order.
> **(c) Repository cache writes require store acceptance**: `report(id)` no longer caches internally — it returns the DTO plus raw body; the store calls `cacheAcceptedReport(id, raw)` only after its generation check accepts a terminal result. Kills the delayed-tick-recreates-deleted-`report_{id}` race. The offline-fallback read path is unchanged.
> **(d) History operations serialize + tombstones**: all history refreshes and deletes run as whole operations under one in-store mutex (the remaining-list snapshot is computed after acquiring it), AND a session-lifetime tombstone set of successfully deleted ids is filtered from every history commit and cache rewrite — a GET that started before a delete can complete after it and must not resurrect the row. (Server ids are autoincrement; a deleted id never returns.)
> **(e) Init cannot override a user's navigation made during loading**: initialize()'s final view assignment is conditional on no newer view intent having occurred (view-intent generation).
> **(f) Events channel is `Channel(capacity = 64, DROP_OLDEST)` explicitly** — `Channel.BUFFERED` + DROP_OLDEST yields capacity 1 in kotlinx-coroutines 1.11, silently dropping queued snackbars.
> Implementer judgment calls 1–14 from the build round: all ACCEPTED, notably (6) the 201 submit adoption is deliberately NOT generation-guarded — a terminal tick bumping the generation is the ordinary precondition for submit succeeding; (7) delete ✕ hidden for exactly `pending`/`running` — UNKNOWN rows keep their delete affordance (server truth: only those two statuses 409); (8) UNKNOWN ceiling from first UNKNOWN observation, reset by recognized statuses.

> **v2.2 (fix-round dispositions, 2026-08-09):** all six v2.1 findings fixed with deterministic race pins + mutation checks (1109 tests, 88.44%). Three implementer additions beyond the letter, all accepted: (a) `cacheAcceptedReport` takes the history mutex and re-checks the tombstone set **inside** it — generation acceptance alone left a window where the accepted write could suspend, a delete land, and the write resurrect the row one step later; (b) a poll 404 (`onPolledReportGone`) is treated as a remote delete under the `viewing` model — clears `viewing` only on id match, never yanks a user reading a different report; (c) the init view-intent guard also covers `adoptOnInit`'s navigation (same bug as the final assignment). Also: a superseded `openReport`'s failure raises no event (noise about an abandoned request); `repository.history(deletedIds)` filters the cache rewrite too, caching the verbatim body when nothing was filtered. Out-of-module: `SyncErrorEvents` carried the identical `Channel.BUFFERED`+DROP_OLDEST capacity-1 bug — fixed to capacity 64 in the main session (no spec of its own governs it; recorded here).

## Goal

Port the PWA Analysis module: query cards → submit → 3 s poll → rendered markdown report, plus history with delete, offline read of cached reports, and pending-report adoption. The PWA client is 602 lines with exactly one tested function — this spec pins everything fresh. Two PWA traps are fixed as declared improvements (the dead-end submit-409 and the poll orphaned by tab navigation); transient-error retry-forever is **retained deliberately** (foreground-only polling bounds it), with new termination rules only for cases the PWA never handled (polled-id 404, unknown status).

Porting sources (PWA repo read-only):
- `public/js/analysis/{store,utils,AnalysisView}.js` + `components/{QueryList,ProgressView,ReportView,HistoryView}.js`
- Transcription authority: `test/js/analysis-markdown.test.js` — 4 sanitizer cases, translated **semantically** to render-model assertions (§Markdown)
- Server authority: `src/modules/{analysis,analysis_db,analysis_queries}.py`; tests `test/analysis/*`; synthetic shapes from `test/conftest.py:369-383`
- `docs/ARCHITECTURE.md` §§Analysis — two doc defects NOT inherited: the client polls `GET /reports/{id}` (not `/reports/pending`), and the PWA has no location input field (it silently geolocates)

## Declared deviations

1. **Manual location field, no geolocation** (plan-mandated). A query card with `accepts_location: true` renders an optional single-line `WellnessDenseField` inline on the expanded card; the ViewModel owns per-query field text (never persisted). Blank → key **omitted** from the POST body. No location permission, no Nominatim. (PWA: silent geolocation; the field matches `ARCHITECTURE.md:689`'s documented intent.)
2. **409-on-submit adopts the running report**: `GET /reports/pending` → adopt `pending[0]` (active + poll + PROGRESS) + info event ("A query was already running — showing it."). Empty pending (just finished) → refresh history + plain error event. (PWA: toast dead end until page reload.)
3. **Poll survives navigation, never hijacks it**: the poll belongs to the store; no sub-view or tab switch stops it. Terminal auto-advance happens **only from PROGRESS** (full matrix below). (PWA: any nav kills the poll, orphaning the report.)
4. **Poll lifecycle**: 3 s cadence, immediate first tick; transient errors (network/5xx) never stop it (parity); backgrounding pauses (ProcessLifecycleOwner), foreground resumes with an immediate tick. **New termination rules** (beyond PWA): polled-id **404** → stop, clear active, event "Report was deleted on the server", QUERIES if the user was on PROGRESS (else view preserved); status **UNKNOWN** persisting past **600 s** (> 400 s max query + 120 s reaper grace + headroom) → stop, PROGRESS shows a "status unknown — check History later" state with a manual re-check action. Known non-terminal statuses have NO overall ceiling: the server's reaper (≈520 s worst case) guarantees a terminal transition, verified.
5. **Pending-check re-runs**: on first Analysis entry per process AND on app-foreground when no poll is active and no terminal report is displayed. Additionally (init-recovery): if init failed for a transient reason (offline first launch), foreground retries the whole init — queries included, not just pending. (PWA: memoized once per page session; queries stay empty forever after an offline first load.)
6. **Submit double-tap guard**: atomic in-store (`if (submitInFlight) return` inside the serialized control context) — UI disabling alone cannot prevent two entries before recomposition. Cards also disable visually.
7. **"Try Again" semantics, deterministic**: query still present in loaded `queries` and `accepts_location` → navigate to QUERIES with that card expanded/highlighted, **never auto-submit** (the user re-enters location). Query present, no location → immediate resubmit. Query absent from the loaded list (removed server-side, or queries never loaded) → button disabled with copy "Query no longer available". (PWA: always resubmits location-less — bug, not ported.)
8. **Markdown = commonmark-java + Compose render model** (plan §8 decision): `org.commonmark:commonmark:0.24.0` + `org.commonmark:commonmark-ext-gfm-tables:0.24.0` (**BSD-2-Clause**, pure-JVM parser, versions pinned in the catalog — the project's first library beyond the established stack, gate item). No WebView anywhere. Full contract in §Markdown.
9. **Elapsed clock clamps at ≥0**; 1 s tick; `"{n}s"` / `"{m}m {s}s"`, no hour case (90 min = `"90m 0s"`, parity). `ActiveReport.Stub` renders exactly `0s` (parity: the PWA shows 0 until the first tick supplies `created_at`).
10. **Delete confirmation** = M3 AlertDialog, destructive-styled.
11. **Module-disabled mapping**: `initialize()`'s `/queries` call returning **404** → `queriesError = "Analysis is disabled on the server"`, no cache fallback, no snackbar, tab renders that state. (Report-level 404s keep their own semantics: "Report not found" / poll-stop rule.) The tab itself stays in the fixed 5-tab shell.
12. **Timestamps parsed here** (documented exception; sync-token rule governs sync tokens, Analysis has none): `Instant.parse` → `atZone(zone)` with `zone: ZoneId` injected (prod `ZoneId.systemDefault()`), en-US `MMM d, yyyy, h:mm a` (PWA hardcodes en-US; kept — report bodies are en-US LLM prose; revisit at Phase 8). **Malformed input policy**: `formatTimestamp` → `""` + DebugLog; `elapsedSeconds` → `0` + DebugLog (corrupted-cache tolerance — the History screen must never crash on a bad string).
13. **Repository failure matrix = the Trends five-row contract**, a declared deviation from the PWA's catch-every-failure fallback: cache fallback only on network error / 5xx; 4xx propagates (with `detail`); decode-before-cache-write; upsert failure logged + fresh value returned; corrupted cached copy retained + original error rethrown; CancellationException always first.
14. **Cache pruned to the newest 50**: after each **fresh** history fetch, `report_{id}` keys not in the fetched id set are deleted (the fetched list always contains the active report's id, so an active poll's cache row is never pruned). PWA parity would grow unbounded; 50 × ~5 KB makes this cheap insurance, declared.

## API / Interface

### `:core:data` — `analysis/` vertical

**DTOs** (`AnalysisDtos.kt`). Wire contract is **null-not-omitted** for report-row fields (raw sqlite rows) — the documented exception to the project convention; only `/queries` has omitted-optional keys:

```kotlin
AnalysisQueryDto(id: String, label: String, description: String,
                 icon: String? = null,                                        // omitted when absent
                 @SerialName("accepts_location") acceptsLocation: Boolean = false)  // omitted unless literal true

ReportSummaryDto(id: Long, @SerialName("query_id") queryId: String,
                 @SerialName("query_label") queryLabel: String, status: String,
                 @SerialName("created_at") createdAt: String,
                 @SerialName("completed_at") completedAt: String?)            // JSON null until terminal

ReportDetailDto(id: Long, @SerialName("query_id") queryId: String,
                @SerialName("query_label") queryLabel: String, status: String,
                @SerialName("response_markdown") responseMarkdown: String?,
                @SerialName("created_at") createdAt: String,
                @SerialName("completed_at") completedAt: String?,
                @SerialName("error_message") errorMessage: String?)
                // prompt_sent / cli_metadata deliberately unmodeled (ignoreUnknownKeys); raw cache keeps them

SubmitResponseDto(id: Long, status: String)

enum ReportStatus { PENDING, RUNNING, COMPLETED, FAILED, UNKNOWN }  // "completed" NOT "complete";
                                                                    // UNKNOWN = non-terminal for polling, subject to the 600 s ceiling
```

**`AnalysisApi`**: `queries(): String`, `submit(queryId, location: String?): SubmitResponseDto` (`location` key only when non-blank), `reports(): String`, `pending(): String`, `report(id: Long): String`, `delete(id: Long)`. No `client_id` (verified absent from this module — do not add). **Non-2xx handling is typed**: the API layer converts `ResponseException` into `AnalysisHttpException(status: Int, detail: String?)` where `detail` is extracted only when the body is a JSON object with a string `detail` (the 409/404 texts are product copy shown verbatim); the 422 array shape is never parsed. 409-detection anywhere upstream is `status == 409` — never string matching.

**`AnalysisRepository`** (Koin singleton; `payload_cache` module `"analysis"`, keys `report_history` / `report_{id}`; **no Room migration** — but `PayloadCacheDao` gains `@Query`-based `delete(module: String, key: String)`):
- `queries()`: network only (PWA parity — never cached).
- `history()`: network-first; failure → cached copy served **silently** (parity); success → write-through **then prune per deviation 14**.
- `report(id)`: network-first; **cache write only when status is terminal** (the PWA's regression-guarded predicate; UNKNOWN is not terminal → never cached); failure per the five-row matrix; no cache on fallback → rethrow (UI: "Report not available offline", view unchanged).
- `pending()`, `submit(...)`: network only.
- `delete(id)`: network; on 200 → remove `report_{id}` + rewrite `report_history` from the **in-memory filtered list** (no refetch), both best-effort (logged, never failing the delete).

**`AnalysisStore`** (Koin singleton, created eagerly in `Application.onCreate` — the `SyncOrchestrator` precedent — which also registers its ProcessLifecycleOwner observer):

```kotlin
sealed interface ActiveReport {
    val id: Long
    data class Stub(override val id: Long, val queryId: String, val queryLabel: String) : ActiveReport   // post-submit, pre-first-tick; elapsed renders 0s
    data class Loaded(val detail: ReportDetailDto) : ActiveReport
}
data class AnalysisState(
    val view: AnalysisView = QUERIES,          // QUERIES | PROGRESS | REPORT | HISTORY
    val queries: List<AnalysisQueryDto> = emptyList(),
    val active: ActiveReport? = null,
    val history: List<ReportSummaryDto> = emptyList(),
    val isLoading: Boolean = true,
    val queriesError: String? = null,
    val submitInFlight: Boolean = false,
)
```

**Concurrency contract (the round's BLOCKER-2 fix, binding):** every state mutation executes on one injected **single-threaded control dispatcher** (prod: a dedicated single-thread executor context; tests: the test scheduler) — cancellation alone is never trusted to prevent a late commit, because a tick that has already returned from Ktor has no suspension point before its write. On top of serialization, **monotonic generations**: `pollGeneration` increments *before* any poll start/stop/adoption/delete-invalidation; every poll tick, foreground resume tick, and 409-adoption captures its generation at launch and its target report id, and commits only if both still match. `initialize()` is a memoized `Deferred` (duplicate calls await the same instance). The submit guard is atomic in-store (deviation 6). Pre-init `onForeground()/onBackground()` only latch an `isForeground` flag that `initialize()` consumes.

**View-transition matrix (total — every event × every view):**

| Event \ current view | QUERIES | PROGRESS | REPORT | HISTORY |
|---|---|---|---|---|
| `initialize` adoption of pending | → PROGRESS (init always lands the adopted view) | — | — | — |
| submit 201 | → PROGRESS | n/a (guard) | → PROGRESS | → PROGRESS |
| submit 409 → adoption | → PROGRESS | n/a | → PROGRESS | → PROGRESS |
| submit other failure | stay (event) | n/a | stay (event) | stay (event) |
| poll tick, non-terminal | update `active` only | update | update | update |
| poll tick, terminal | stay; `active` updates; history refresh | **→ REPORT** | stay (already viewing some report; `active` updates) | stay; refreshed list shows the row |
| poll 404 (deviation 4) | stay; clear active; event | → QUERIES; clear; event | stay; clear; event | stay; clear; event |
| foreground pending-adoption | → PROGRESS | — (poll already active) | stay; poll starts | **stay on HISTORY**; poll starts |
| `openReport(id)`, fetched row terminal | → REPORT | → REPORT | → REPORT | → REPORT |
| `openReport(id)`, fetched row non-terminal | → PROGRESS + adopt & poll it | → PROGRESS (re-adopt if different id) | → PROGRESS + adopt | → PROGRESS + adopt |
| `openReport` offline no-cache | stay + event | stay + event | stay + event | stay + event |
| `openHistory` | → HISTORY (poll untouched) | → HISTORY (poll untouched) | → HISTORY | — |
| `openQueries` | — | → QUERIES (poll untouched) | → QUERIES | → QUERIES |
| `cancelActive` | n/a (no affordance) | stop poll, clear, → QUERIES | n/a | n/a |
| `delete` 200, id == active | invalidate generation, clear active; stay | → QUERIES | → QUERIES if viewing that id, else stay | stay; list filtered |
| `delete` 200, id ≠ active | stay; list filtered | stay | stay | stay; list filtered |
| `delete` 409/404 | stay + event (detail verbatim); poll untouched | stay | stay | stay |
| UNKNOWN ceiling (600 s) | active marked unknown; stay | stays PROGRESS in "unknown" state | stay | stay |

System back: PROGRESS → `openQueries()` (poll continues; only Cancel abandons); REPORT → QUERIES; HISTORY → QUERIES; QUERIES → default tab back. Sub-view state is store state, never nav routes, never persisted.

**Events** (`AnalysisEvents`, Koin singleton, `Channel<AnalysisEvent>(BUFFERED)` — the `SyncErrorEvents` precedent; collected by the feature screen into the shared snackbar host). Enumerated copies: `SubmitOffline("Server unreachable — new queries unavailable offline.")` · `SubmitError(detail)` · `AdoptedRunning("A query was already running — showing it.")` · `DeleteSuccess("Report deleted.")` · `DeleteError(detail)` · `ReportUnavailableOffline("Report not available offline.")` · `ReportDeletedRemotely("Report was deleted on the server.")`.

### `:feature:analysis` — Markdown pipeline (§ the round's corrected contract)

Pipeline: `collapseStatusText` (1:1 regex port — word set `OK|RED|YELLOW|GREEN|PASS|FAIL`, dot set 🟢🟡🔴✅❌⚠️, both orders, case-insensitive, global, prose and table cells) → commonmark-java parse (gfm-tables, **source spans enabled**) → recursive render model:

```kotlin
sealed ReportBlock { Heading(level: Int /*1..6*/, inlines), Paragraph(inlines), BulletList(items: List<List<ReportBlock>>),
                     OrderedList(start: Int, items: List<List<ReportBlock>>), Quote(children: List<ReportBlock>),
                     CodeBlock(text), Table(header: List<List<ReportInline>>, rows: List<List<List<ReportInline>>>), Rule }
sealed ReportInline { Text(text), Code(text), Strong(children: List<ReportInline>), Emphasis(children: List<ReportInline>),
                      Link(children: List<ReportInline>, destination: String) }
```

Mapping rules, exhaustive:
- **Raw HTML (`HtmlBlock`/`HtmlInline`) → `ReportInline.Text(rawLexeme)` — UNESCAPED.** Compose `Text` has no HTML interpretation, so the raw source (`<img onerror=…>`) renders as inert visible text. Entity-escaping (`&lt;img`) was the PWA's innerHTML-world mechanism and would display literally wrong here. The 4 JS vectors translate **semantically**: (a/b/c) no HTML render node exists anywhere in the model and the original tag text appears as inert `Text`; (d) H1/strong/code/table still produce their typed nodes.
- Headings **H1–H6** all render (the 4th JS vector pins H1 — v1's H2/H3-only contradicted it).
- `Link` keeps its destination; rendered as `label (destination)` in secondary tone — inert, not clickable, but the URL is never silently dropped (health reports may carry meaningful references). Images → `Text("[image: {alt}] ({destination})")`.
- Soft break → space; hard break → newline. Nested lists nest (items are block lists). Strikethrough (if the extension ever emits it) and **any unknown node type** → `Text` of the node's **source-span substring** — nothing can silently disappear.
- Tables render in a `horizontalScroll` container (the `table-wrap` equivalent), cells are inline lists (nested strong/code inside cells pinned by test).

Pure helpers: `formatTimestamp(iso, zone)` / `elapsedSeconds(createdAt, now)` / `formatElapsed(seconds)` per deviations 9/12.

Composables: `ReportBody(blocks)`, `QueryCard` (icon map, inline location field, disabled on `submitInFlight`), `ProgressView` (spinner, label, 1 s clock keyed on the Loaded `createdAt` — Stub renders 0s; Cancel; the UNKNOWN-ceiling state with re-check), `ReportViewScreen` (FAILED shows `errorMessage` + Try Again per deviation 7), `HistoryList` (delete ✕ hidden for non-terminal rows — parity; confirm dialog).

## Behavior — protocol facts (verified)

1. `GET /queries` · `POST /reports` (201 `{id,status}`; 404 `"Unknown query_id: <id>"`; 409 `"A query is already in progress."`) · `GET /reports` (six-field projection, `ORDER BY created_at DESC LIMIT 50` hardcoded, no pagination — don't invent any) · `GET /reports/pending` (full rows, 0-or-1 in practice) · `GET /reports/{id}` (full row; 404 `"Report not found"`) · `DELETE /reports/{id}` (`{"deleted": true}`; 409 `"Report is still running — wait for it to finish (or time out) before deleting."`; status check precedes existence check).
2. Status strings `pending|running|completed|failed`; unknown values possible (out-of-repo `user_queries.py`) → UNKNOWN.
3. Poll must tolerate: 400 s weekly-review runs (~135 ticks); server restart → `FAILED ("Server restarted during execution")`; reaper → `FAILED ("Reaped: …")` at ≈520 s worst case. All ordinary terminal outcomes.
4. Null-vs-omitted exactly as the DTO section. No retention server-side; history caps at 50 newest.
5. Timestamps: Z-suffixed microsecond ISO-8601 UTC.

## Testing

| Suite | Module | Cases |
|---|---|---|
| `AnalysisMarkdownTest` — 4 JS vectors translated semantically (no HTML node in model + raw lexeme inert + H1/strong/code/table nodes present); collapseStatusText matrix; link URL preservation; image fallback; nested strong/code inside table cells; multi-level lists; blockquote; unknown-node source-span fallback; soft/hard breaks | :feature:analysis | ~22 |
| `AnalysisFormatTest` — timestamp (valid/fractional/null/blank/**malformed**, DST boundary, non-UTC injected zone); elapsed (0/59/60/61/3600/5400, negative-skew clamp, malformed→0) | :feature:analysis | ~12 |
| `AnalysisStoreTest` (virtual time; injected single-thread control context = test scheduler) — the FULL matrix above, plus the race pins: superseded tick after `cancelActive` commits nothing; old-report terminal tick vs newly adopted report (generation mismatch); foreground immediate tick vs background-era completion ordering; duplicate `initialize()` awaits one Deferred; atomic submit guard under two rapid calls; `openReport(nonterminal)` → PROGRESS + poll; delete-vs-in-flight-tick (generation invalidated first); terminal tick in QUERIES and in REPORT; poll-404 stop; UNKNOWN ceiling; pause/resume immediate tick; init offline-with-cache → HISTORY / offline-bare → QUERIES+error; foreground re-init after failed init; pre-init foreground latch. `runCurrent`/bounded `advanceTimeBy` only — `advanceUntilIdle` cannot settle an infinite poll loop | :feature:analysis | ~34 |
| `AnalysisRepositoryTest` — five-row matrix per call; terminal-only cache predicate incl. UNKNOWN-not-cached; history silent fallback; prune-to-50 (active id survives); delete cache maintenance best-effort (DAO throw doesn't fail delete); `AnalysisHttpException` detail extraction (string / non-JSON / 422-array → null detail); corrupted cache rethrows original | :core:data | ~18 |
| `AnalysisDtoTest` — golden fixtures: queries ± optional keys; detail pending(nulls)/completed/failed; summary list; submit envelope; pending list; empty arrays; UNKNOWN decode | :core:data | ~10 |
| `AnalysisApiTest` (MockEngine) — 6 paths; submit body location omitted/present; no client_id; 409/404 → typed exception with exact detail | :core:data | ~10 |
| ViewModel thin-layer + event routing to snackbar | :feature:analysis | ~5 |

**Device acceptance matrix** (not JVM-claimable): background mid-poll → foreground resumes with immediate tick; process-kill mid-poll → relaunch adopts via pending-check; tab-switch keeps poll (report completes while on Journal, visible on return); back from each sub-view; raw-HTML fixture report shows literal `<tag>` text (not blank, not `&lt;tag&gt;`); location field on an `accepts_location` query card; delete dialog; long report scroll + wide-table horizontal scroll; module-disabled state (if testable against dev server config).

Golden fixtures (`testdata/golden/analysis/`): hand-authored synthetic (`fixture-` prefix rule), shapes cross-checked against `test/analysis/test_server.py` assertions and the `mock_claude_cli` metadata dict; report bodies are synthetic markdown exercising the full construct set incl. a raw-HTML attack string. **The local dev DB contains one real report body — nothing from it may appear in fixtures.**

Kover: gate 80; full invocations only.

## Dependencies

- Existing: `payload_cache`/`PayloadCacheDao` (+ new `delete(module,key)` query method), `isNetworkError`, `ServerConfig`, `WellnessJson`, `DebugLog`, `SyncErrorEvents` pattern, snackbar host, `WellnessDenseField`, ProcessLifecycleOwner precedent, design tokens.
- **New**: `org.commonmark:commonmark:0.24.0` + `org.commonmark:commonmark-ext-gfm-tables:0.24.0` (BSD-2-Clause; parser only; pinned in the version catalog). Gate item.
- No Room migration. No WorkManager change. No location permission.

## Open questions (for the approval gate)

1. **commonmark-java** (deviation 8): approve the two pinned parser artifacts (BSD-2-Clause)? Hand-rolled alternative rejected in-spec: GFM tables are the hard requirement and hand-rolled table parsing silently mangles health data.
2. **Background completion UX** (deviation 4): proposed = pause poll on background, re-adopt on foreground, no notification when a report finishes while backgrounded. Alternative (WorkManager + notification) deliberately out of scope; Phase 8 candidate.
