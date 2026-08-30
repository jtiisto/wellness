# Spec: Home-screen widget — "Today" (Glance)

Status: **approved 2026-08-28** (the widget plan was approved as a whole; approval of that plan is approval of this spec, per its Phase 0 gate)

> **Origin.** Phase 2 of the sleep-debt round (`specs/trends.md` §Sleep need promised it in writing) and the answer to the in-app Home screen question, which is **deferred**: the widget ships first and real use decides whether an in-app Home is ever needed. The code was pre-positioned for this — `SleepDebtLogic` / `SleepTonightModel` live in `:core:data` precisely so a surface with no feature module on its classpath can render them, and `categoryRollup` has lived there since the journal UI round for the same reason.

## Goal

One home-screen widget, `TodayWidget`, that answers "where is today at?" without opening the app: the day's positive-tracker tally, tonight's sleep need and debt, and today's strain — in that priority order, with the smaller sizes dropping elements off the bottom rather than shrinking them.

It is a **torn-off corner of the logbook page**, not an app icon with numbers on it: same paper, same ink, no chrome. The launcher's corner radius is the one shape the host imposes; everything inside is the Logbook's. Colour never encodes meaning here either — judgment and completion are carried by shape and fill, exactly as in the app.

Naming and homes:

| Piece | Where |
|---|---|
| `TodayWidget`, `TodayWidgetReceiver`, `TodayWidgetWorker`, `TodayWidgetContent`, `TodayWidgetLogic`, `TodayWidgetPalette` | `:app`, package `dev.jtiisto.wellness.widget` |
| `TrendsCachePeek`, `JournalDayPeek` | `:core:data` (`trends/`, `journal/`) — see §Data paths |
| `sleepTonightModel`, `hoursMinutes`, `TonightJudgment` | `:core:data` `trends/SleepDebtLogic.kt` — **unchanged**, shared with the in-app card |
| `categoryRollup`, `describeCategoryRollup` | `:core:data` `journal/JournalUiLogic.kt`, `journal/CategoryRollupVoice.kt` — **unchanged** |
| Picker label / description | "Today" / "Today's trackers and tonight's sleep need." |

## Size buckets

`SizeMode.Exact`. Each bucket is a **threshold on the widget's real size** (`widgetBucket`), and every bucket is verified to render whole at its own floor; real launchers give more room, and with Exact the composition *knows* it has more. The first cut used `SizeMode.Responsive` over the three floors, which hands the composition the matched **bucket** size rather than the real one — so a strip stretched to twice its floor still laid out (and fit its tally) as if it were 110×40 dp, drawing a lone fraction in a field of paper. First device report; the mode flipped, and the fit rule reads the truth. STRIP content is vertically centred (one line in a box usually taller than the line); CARD and PAGE read top-down like the card.

| Bucket | DpSize (min) | Cells | Shows |
|---|---|---|---|
| STRIP | 110 × 40 | 2×1 | tally only — or, with no tally to draw, the compact sleep row (below) |
| WIDE | 250 wide, under CARD height | 4×1-ish | tally in its own column · vertical rule · compact sleep row, side by side |
| CARD | 180 × 110 | 3×2 (default placement) | tally · rule · eyebrow + judgment glyph · need headline · debt line |
| PAGE | 180 × 170 | 3×3 and up | everything, card-faithful: + strain and the honesty lines, headline stepped up to 32 sp, block gaps widened to 10 dp |

Space is spent, not left over (the sixth device report asked where it all went): content is **vertically centred at every bucket** — launcher cells run taller than the floors, and a column pinned to the top leaves the surplus as a void under the last line. WIDE is the strip-height family using its width: the tally takes a fixed 55 % column (`wideTallyWidthDp`; the fit ladder is fitted to that column via `tallyFitWidthDp`, not to the page), a vertical hairline divides, and the compact sleep line takes the rest. WIDE with no tally drops the rule and gives the row to the sleep line alone — a rule may not separate a column from nothing. CARD height outranks WIDE: given the height to stack, the page stacks.

**Element priority.** Elements are added in this order as the bucket grows, and dropped in reverse as it shrinks. Nothing is ever shrunk to fit — a size that cannot hold an element omits it.

| # | Element | STRIP | CARD | PAGE |
|---|---|---|---|---|
| 1 | Tracker tally (local Room, always fresh) | ✓ | ✓ | ✓ |
| 2 | Sleep: judgment glyph + `TONIGHT'S SLEEP NEED` + `needText` + `debtLine` | — | ✓ | ✓ |
| 3 | Strain (`strainLine`) | — | — | ✓ |
| 3 | Honesty lines (`freshnessLine`, `cachedLine`) | — | — | ✓ |

The honesty lines sit at PAGE only, and that is a **measured compromise, not an oversight**: at the 180 × 110 CARD floor the headline, the debt line and a words line cannot all fit, and the judgment glyph already *is* the caveat signal (`PARTIAL` is *defined* as "`cachedLine` or `freshnessLine` present"). At CARD the glyph carries it alone; the words wait one size up, or one tap away.

**STRIP with no tally** — the one day the strip's own element has nothing to draw, because no habits are expected — hands its line to the next element in priority order, in the only form the floor holds: the **judgment glyph + `needText`** (or `-:--` in ink-faint when pending), meta-ink, no eyebrow and no `h:mm` unit. `TONIGHT'S SLEEP NEED` alone measures ≈126 dp against 86 dp of content and would wrap; the glyph carries what that label would have said, which is what the mark vocabulary is for. The decision is `showsCompactSleep(bucket, hasTally)` in `TodayWidgetLogic`, not a branch invented in the composable. An empty strip is never an option — it reads as broken.

Vertical budgets at the floors: CARD = 12+14+6+1+6+13+2+26+2+15+12 = **109 dp ≤ 110**; PAGE normal = **152 dp**, gap-night worst case (the debt line wraps to two lines) = **167 dp ≤ 170**. Width at 180 dp: the widest headline `! 8:05 h:mm` measures 89–105 dp; the 31-character gap-night debt line wraps under `maxLines = 2`.

```
STRIP (2×1)              CARD (3×2, default)             PAGE (3×3, gap night)
┌────────────────┐  ┌───────────────────────────┐  ┌───────────────────────────┐
│ ●●●●◐○         │  │ ●●●◐◐○        3 OF 6 DONE │  │ ●●●◐◐○        3 OF 6 DONE │
└────────────────┘  │ ───────────────────────── │  │ ───────────────────────── │
                    │ ◐ TONIGHT'S SLEEP NEED    │  │ ○ TONIGHT'S SLEEP NEED    │
                    │ 8:05 h:mm                 │  │ ! 8:11 h:mm               │
                    │ debt 1:11                 │  │ debt 1:33 · reset —       │
                    └───────────────────────────┘  │ missing night             │
                                                   │ strain 12.6 · so far      │
                                                   │ CACHED · 3H AGO           │
                                                   └───────────────────────────┘
```

### States

| State | Glyph | Tally | Sleep block |
|---|---|---|---|
| Fresh | ● SETTLED | live | the number, no caveat |
| Cached (> 90 min) / server behind | ◐ PARTIAL | live | glyph only at CARD; `CACHED · NH AGO` / `for <date>` at PAGE |
| Gap night | ○ ATTENTION + `!` bang | live | `debt … · reset — missing night` (wraps at PAGE) |
| No data yet | ○ PENDING (ink-faint) | omitted when no trackers | `-:--` · "no data yet" |

**The tally is never stale** — it is read from local Room at render time — and only the sleep block ever confesses age. "No data yet" is a *pending* state, never an error state: nothing on a launcher should look broken.

## Canvas, grid, type, colour

| Token | Value | Note |
|---|---|---|
| Padding | 12 dp all sides | 3× the 4 dp grid; the app's 20 dp page margin scaled to a small page |
| Keyline | single left keyline at the padding edge | every row starts on it, exactly as the card does |
| Row gaps | 2 dp within a block; 6 dp · 1 dp rule · 6 dp between blocks | the card's own rhythm |
| Background | paper, full-bleed | the only surface; grouping is whitespace and one hairline rule |
| Corner radius | launcher-controlled | no inner rounding of our own |

Type, faithful to `LogbookType` with the two Glance losses named in §Accepted deviations:

| Role | Face | Size / weight | Colour | Used for |
|---|---|---|---|---|
| headline | `FontFamily.Monospace` | 24 sp / Medium (26 sp line) — **32 sp at PAGE**, where the card's size read as a note in a margin | ink | `needText`, the `!` bang |
| meta-ink | Monospace | 11.5 sp / Normal | ink | `debtLine` |
| meta-soft | Monospace | 11.5 sp / Normal | inkSoft | the `h:mm` unit, `strainLine`, `freshnessLine` |
| eyebrow | Monospace | 10.5 sp / Normal, caps | inkSoft | `TONIGHT'S SLEEP NEED`, the tally count, `cachedLine` (uppercased) |

Colour is five tokens, hardcoded as day/night `ColorProvider` pairs because Glance cannot read `LogbookTheme`, and **pin-tested against `LogbookLight`/`LogbookDark` so drift fails the build**:

| Token | Widget use |
|---|---|
| paper | background |
| ink | headline, debt, bang, filled/half marks, the judgment glyph in every *judged* state |
| inkSoft | eyebrow, unit, strain, freshness, cached |
| inkFaint | open (not-yet) dots, the PENDING glyph, `-:--` |
| rule | the 1 dp block separator |

No other colour appears on this surface.

## Mark vocabulary

Glance has no `Canvas`, so the two mark families the app draws — `InkJudgment`'s glyph and `WeekMarkGlyph`'s dots — become **three vector drawables** whose geometry is ported from those two files at viewport 12. Stroke 1.85/12 sits within 0.05 dp of *both* source ratios (1.4 dp/9 dp for the judgment glyph, 1.2 dp/8 dp for the dots), which is what lets one drawable set serve both sizes.

| Drawable | Geometry | As 9 dp judgment glyph | As 8 dp tally dot |
|---|---|---|---|
| `widget_mark_filled` | filled circle r = 6 | SETTLED (ink) | met (ink) |
| `widget_mark_half` | left half-disc fill + filled even-odd annulus, outer 6 / inner 4.15 | PARTIAL (ink) | partial (ink) |
| `widget_mark_hollow` | filled even-odd annulus, outer 6 / inner 4.15 | ATTENTION (ink) · PENDING (inkFaint) | not-yet (inkFaint) |

**Every path is a fill — no vector strokes.** Kept as belt-and-braces, with its history told straight: the first device build showed only the filled marks, this was diagnosed as stroke paths dying in the launcher's RemoteViews pipeline, and the marks were re-authored as fills (the annulus is the stroked ring's geometry restated — centreline 5.075, thickness 1.85, outer edge 6, inner 4.15). The second device build showed the *same* symptom, which broke the theory: the real killer was the **ten-child container budget** (§Glance constraints) dropping every mark after the fifth — the drawables were never given the chance to render. Fill-only stays because it is the more conservative authoring and the fills are now proven on-device; the stroke variant was never exonerated.

Two rules govern their use:

- **Tint at the use site.** Authored fill/stroke colours in the XML are irrelevant; every draw applies `ColorFilter.tint(ColorProvider)`. This *is* the one-ink rule — the same drawable is ink in a judged state and ink-faint in a pending one, and no drawable ever carries a hue.
- **The half mark keeps its closing outline**, so "the missing half reads as unfinished rather than as a smaller dot" (`WeekMarkGlyph`'s own words). It is not a 50%-scaled filled dot.

## Tally semantics

The tally element is a **rendering of `categoryRollup`, and owns no judgment of its own.** Every question about what counts as met, partial, not-yet, expected-today, avoidance or observation is already answered by `categoryRollup(trackers, today, dayLog)` in `:core:data` and its tested rules (`specs/journal-ui.md`); this spec adds nothing to them and must never be read as re-stating them. The widget passes the whole tracker list — not a category's — so the rollup is the *day's*.

- **Dots**: 8 dp, 4 dp gaps, on the keyline, ordered filled → half → hollow, that is `habitsMet` then `habitsPartial` then `habitsNotYet`. Done reads left-to-right, the way tallies fill a line. Avoidances and observations get **no dot**: the ring in the app gives them their own marks, and a strip 110 dp wide has room for one idea.
- **Count**: `describeCategoryRollup`'s sentence ("5 of 8 done"), uppercased into the eyebrow style — `5 OF 8 DONE` — right-aligned on the dots' baseline.
- **Fit rule** — pure, width-driven, and **never silent**. A four-rung ladder, each rung a whole layout rather than a shrunken version of the one above it:

  | # | Rung | Taken when |
  |---|---|---|
  | 1 | dots + `5 OF 8 DONE` | both fit the content width, with an 8 dp gap between them |
  | 2 | dots alone | the pair does not fit but the dots do |
  | 3 | `5 OF 8 DONE` alone | the dots do not fit but the sentence does |
  | 4 | `5/8` | the sentence does not fit either |

  One ladder for every bucket, walked against the widget's **real** content width (`SizeMode.Exact`). The first cut froze a "≤ 7 dots" rule onto STRIP — rung 2's arithmetic evaluated at the 110 dp floor — because under `Responsive` the width handed to the rule *was* the floor and the freeze at least made that honest. With the width telling the truth the freeze became the bug (a 220 dp strip refusing dots it could hold), so the rule retired; at the 110 dp floor the arithmetic still answers exactly as the frozen rule did, and the test suite pins that.

  **Width is not the only budget.** A Glance container renders at most **ten children** and silently drops the rest (§Glance constraints), and a dropped child is a truncated dot row — the confident wrong answer the ladder exists to prevent, and exactly what the second device build showed (five met dots survived, every half and open mark after them vanished). So a rung is only open if the row can legally render it: rung 1 spends one child per dot plus the weight spacer plus the count (dots + 2 ≤ 10 → at most 8 dots with text), rung 2 one per dot (at most 10 dots alone); above that the ladder falls to the sentence, which states the full tally in one child.

  Rung 4 is the **floor of the never-truncate rule, not an exception to it**: `N/M` is the shortest true statement of a tally, so it is drawn even where the estimate says it will clip, because there is nothing left to fall back to. The dot list is never shortened at any rung — a truncated row of dots is a confident wrong answer about the day.

  The measurement is an **estimate**, stated as one: Glance measures on the device and the widget cannot, so a count's width is taken as 6.3 dp per character (10.5 sp mono at ≈0.6 em) **multiplied by the system font scale**, read from the render context. The counts are sized in `sp` and the marks in `dp`, so at a font scale of 1.3 the words grow by a third and the dots do not — a layout chosen without the scale would overflow the row it was fitted to. Dot widths are unaffected by it.

  There is no state in which a tally that exists renders as nothing.
- **No trackers expected today** (the rollup returns null, or holds no habits) → the element is **omitted entirely, with its separator rule** — the same "absent, not empty" posture the tonight card takes.
- **Accessibility always speaks the whole sentence**, whatever the fit rule drew: "5 of 8 positive trackers done, 2 partial".

The tally reads local Room and is therefore **always live** — it is the one element that has no staleness story, and the reason element 1 outranks the network-fed ones.

## Sleep model rules

**Every rule for the sleep block is `specs/trends.md` §"Sleep need", and this spec deliberately does not restate them.** `needText`, `debtLine`, `strainLine`, `freshnessLine`, `cachedLine`, the judgment matrix and the `flagged` bang all come from the one `sleepTonightModel` the in-app `SleepTonightCard` uses, pinned verbatim by `SleepDebtLogicTest`. A second copy of that table here is exactly the drift this arrangement exists to prevent; the widget's job is to *lay the model out*, not to re-derive it.

What is widget-specific, and therefore lives here:

- `today` and `now` are computed **at render time inside `provideGlance`, never remembered** — the same clock rule the card follows. A widget that has been on a home screen since yesterday must not answer with yesterday's `today`.
- `staleFetchedAt` handed to the model is **not** `FetchResult.staleFetchedAt` (there is no fetch on this path) but the peeked row's own `fetchedAt`, passed through `widgetStaleFetchedAt` — see §Data paths.
- Which of the model's lines are drawn at which bucket is the §Size buckets priority table, not a model concern.
- A null model (nothing peeked, `available: false`, or no `tonight`) renders the **pending floor**: hollow glyph in ink-faint, `-:--` in ink-faint, "no data yet" in meta-soft.

## Data paths

Nothing on the render path touches the network, and nothing on it can throw a `requireConfig()`.

**Sleep — `TrendsCachePeek.sleepFlow(keys)`**, cache-only and **live**: a `combine` over one Room observation per key, emitting the **freshest decodable copy** among them, from module `"trends"`:

1. `health/sleep:widget` — the worker's own key. `WIDGET_RANGE = "widget"`.
2. `health/sleep:{userRange}` — whatever range the user last chose in Trends (`TrendsPrefs.range`, default `12w`).

**Freshest wins, not first** — the fourth device report's fix. The first cut preferred key 1 by order, which let the widget keep drawing its hourly copy for up to an hour after the app fetched a newer one under key 2: a home screen contradicting the app it belongs to. Comparing `fetchedAt` ends that; key 2 also covers the window between a widget being placed and the worker's first run. Watching key 2 at all is sound because **`tonight` is range-independent** — the server computes the ledger over its own history and clips only `days`, which this surface never draws — so a 12-week copy and a 7-day copy carry the identical `tonight`. (The same fact `specs/trends.md` §Sleep need states when it explains why the cache key carries a range at all.)

**Freshness — `widgetStaleFetchedAt(fetchedAt, now)`, a 90-minute window.** Every widget render is by definition from cache, so passing the raw stamp through would brand *every* render `cached · Nm ago`, including one drawn ninety seconds after a successful fetch — the badge would stop meaning anything. The rule: within 90 minutes of `fetchedAt` the copy is treated as **fresh** (null passed to the model, SETTLED possible); past it the real stamp goes through and the model does its own arithmetic. 90 minutes is the hourly refresh period plus half of one, so a single missed worker run does not flip the badge but two consecutive ones do.

**Tally — `JournalDayPeek.rollup(today)`**: one `JournalDao.daySnapshot(today)` — a `@Transaction` read composing `listActiveTrackers()` (`deleted = 0`, the documented twin of `observeTrackers`' SQL) with `listEntriesForDate(today)` — decoded through the **shared** `decodeTracker` / `JournalEntryEntity.toDto` (promoted out of `JournalSyncStore` for exactly this; a forked entity→DTO seam would be the place the two silently diverge), keyed into a `Map<String, EntryDto>` by tracker id, and handed to `categoryRollup`.

The transaction is not decoration. Read as two separate queries, a sync commit landing between them yields a rollup assembled from two different generations of the same day — a tracker list from before an edit judged by an entry list from after it — and **nothing in the resulting tally would look wrong**. It is the only read in that DAO whose two halves are compared against each other rather than merely displayed side by side, and the only one that therefore needs one.

**The live twin — `JournalDayPeek.rollupFlow(today)`**: the same decode and the same delegation over `combine(observeTrackers(), observeDay(today))`, collected inside the widget's composition so a tracker ticked in Journal redraws the home screen the moment the row lands in Room (§Refresh model). Room flows cannot share a transaction, so an emission *can* transiently pair rows from either side of a sync commit — accepted, because the next emission self-corrects in the same breath and the Journal screen itself lives on the identical combine. The transactional one-shot remains the primer for the session's first frame.

**Why two standalone peek classes and not repository methods.** `TrendsRepository` is **unconstructible before the server has resolved**: Koin builds it with `api = get()`, `TrendsApi` takes a `ServerConfig`, and that single is `get<ServerBootstrap>().requireConfig()`, which **throws** by design — asking which server this process talks to before the boot decision has been made is a bug worth crashing on. `JournalSyncStore` is poisoned the same way through `JournalApi`. A widget, though, is rendered by the launcher in a process that may have been created *for the widget*, with no Activity and no boot behind it. So the render path resolves only singles whose whole dependency graph is pre-resolution-safe — a DAO and the shared `Json` — and both peeks are registered in `CoreDataModule` alongside `TrendsPrefs` with a comment saying so. Adding `sleep()` to `TrendsRepository` would have been fewer lines and a crash on a cold launcher.

**Failure posture — null means absent, a throw means corrupt.** A launcher render has no error surface, so *unavailability* is never an exception: a thrown one is a blank box on someone's home screen, not a stack trace anyone reads. But silence is only the right answer when there is genuinely nothing to say, and the two peeks draw the line in the same place:

| | `TrendsCachePeek.sleepFlow(keys)` | `JournalDayPeek.rollup(today)` / `rollupFlow(today)` |
|---|---|---|
| **DAO read throws** | errors the flow → caught at the widget's collection seam: logged (class name only) and rendered as the element's absence | one-shot: null · flow: errors, same seam |
| **Nothing stored** | emits null | null via `categoryRollup` — a day expecting nothing has no tally |
| **Stored data will not decode** | that copy is skipped and its row **stays**; an older decodable copy still serves | **throws**, through flow and one-shot alike |

`CancellationException` always propagates untouched (`Flow.catch` rethrows it by contract) — a cancelled render is not a failed one.

The one asymmetry in that table is deliberate. A sleep payload that will not decode has a *next key* to try and a defensible reason to keep the row (the `serveCached` philosophy: what this build cannot read, the next one may). A tracker row has neither, and skipping it would shrink the **denominator** — a corrupt row that was expected today silently turns "5 of 6 done" into "5 of 5 done", a confident wrong answer, which is the one thing a glanceable surface must never produce. Entry rows propagate for the same reason (a dropped entry mis-judges its tracker as not-yet). **Honest absence beats wrong presence**: the widget's own `runCatching` floor renders the throw as pending, which is exactly the honest thing to show.

**Server switch.** No change to `ServerSwitcher`: the `health/sleep:widget` row rides `module = "trends"` and dies in the cache wipe with everything else, so the widget correctly renders pending on the new server until the next fetch. A fetch racing the switch is double-fenced already (the worker's gate check, plus `withWriteLease` refusing the write).

## Refresh model

**The session rule comes first, because everything else was misread without it (third device report): a Glance session recomposes `provideContent` without re-running `provideGlance`.** Values computed before `provideContent` are frozen for the session's whole life, so an `updateAll` landing on a live session redrew the same captured day — a tally that ignored the app until the session happened to die and reload. The data is therefore **collected as Flows inside the composition** (`collectAsState`): Room re-emits the moment a tracker is ticked or a fetch lands in the cache, and the widget follows the app in place. The session's first frame is primed with each flow's current value so a session start never flashes pending on the way to the truth. The triggers below matter for what they still own — fetching, and waking sessions the launcher has let die:

| Trigger | What it does | Why |
|---|---|---|
| `TodayWidgetWorker`, hourly periodic, `CONNECTED`, unique **KEEP** | fetch `healthSleep(start, end, WIDGET_RANGE)` over a 7-day window ending today, then `TodayWidget().updateAll()` | the only thing that puts new sleep/strain data on the surface |
| `TodayWidgetReceiver.onEnabled` / `onUpdate` | schedule the worker | `onEnabled` is the first placement; `onUpdate` heals the case where app data was cleared while a widget stayed on the home screen. KEEP makes re-asserting free |
| `TodayWidgetReceiver.onDisabled` | cancel the worker | no widgets left; an hourly network job for nobody is a battery bug |
| App backgrounded (`ProcessLifecycleOwner`, `ON_STOP`) | `updateAll()` in the app scope | wakes a session the launcher has let die; a live one already followed the Room emission. Local data only — no fetch |

Rules the worker obeys:

- **`updateAll()` runs whether or not the fetch succeeded.** Cached-with-age *is* the offline story, and re-rendering an older copy is how the age advances on screen.
- **Always `Result.success()`.** The hourly period is the retry; a `Result.retry()` would stack backoff on top of a schedule that already comes round.
- **`TrendsRepository` is resolved lazily *inside* `doWork`**, never in a field — the worker may be constructed in a process where resolution has not happened, and the guard (`shouldFetch`) may decide not to fetch at all.
- Failures go to `DebugLog` under the tag `"widget"`, **never payload bodies** — the standing rule for this log.
- `updatePeriodMillis = "0"` in the widget info XML: the AppWidget framework's own update alarm is not used, because it cannot be made conditional on connectivity and would fight the worker.

**The midnight problem is bounded by one period.** `today` is fixed per *session* (it keys the flows), and the hourly worker restarts dead sessions, so a stale calendar day survives at most one period past midnight; the ON_STOP hook usually beats it. `now` is read at every recomposition — the card's own clock rule. A midnight alarm was considered and rejected: an exact alarm for a cosmetic flip is not a fair use of the user's battery, and the failure mode is a tally that is one hour late on a day boundary.

## Interaction & accessibility

- The **whole widget is one tap target** → `actionStartActivity<MainActivity>()`, opening the app on its normal start tab. No deep link in v1. Default RemoteViews press feedback; no custom ripple.
- **Two merged semantics nodes**: the tally sentence (§Tally semantics), and the card's existing spoken form for the sleep block ("Tonight's sleep need 8h05m. debt 1:11. …").
- **The sleep block speaks its whole sentence at every bucket, including the lines that bucket does not draw** — the strain and honesty lines at CARD, and everything but the number on the compact strip row. This is deliberate, not an oversight: **a bucket decides how much fits, never how much is true**, and the reader who cannot see the judgment glyph is exactly the one who needs the caveat spelled out. The sentence is `sleepSpoken` in `TodayWidgetLogic`, shared by both surfaces and pinned by test.
- **Dark mode** swaps through the day/night `ColorProvider` pairs without a recomposition.
- **Picker**: label "Today", description "Today's trackers and tonight's sleep need.", plus a static `previewLayout` RemoteViews mock (two TextViews on paper). Glance 1.2's generated previews are noted as a follow-up, not a v1 requirement.

## Accepted deviations

Each is a Glance platform limit, taken deliberately rather than worked around:

1. **Generic `FontFamily.Monospace` instead of IBM Plex Mono.** Glance cannot load font resources into a RemoteViews tree. The widget is therefore in the *system's* mono, not the Logbook's — accepted because every string on this surface is a number or the label of a number, and the alternative (a proportional face) would break the digit alignment that makes `h:mm` readable at a glance.
2. **No letter-spacing on the eyebrow.** Glance's `TextStyle` has no letter-spacing property. The app's eyebrows are tracked out; the widget's are not, and at 10.5 sp caps the loss is small.
3. **Generic `FontFamily.SansSerif` where a sans is needed at all.** Same cause as (1). In practice nothing on this surface needs it — Inter is not used here, because every string is numeric or labels a number and the in-app card already sets them all in mono.
4. **Fractional sp survives** (11.5 sp, 10.5 sp) and is rounded to whole sp **only if a device renders it badly** — a device-check item, not a pre-emptive change.
5. **Launcher-controlled corner radius**, with no inner rounding of our own: the host's shape is the one piece of chrome we do not get to author, and drawing a second radius inside it would read as a card floating on a card.
6. **Honesty lines at PAGE only** — the measured compromise stated in §Size buckets.

## Glance constraints

Platform facts that shaped code, found the hard way and load-bearing enough to name:

- **Ten children per container, silently enforced.** A Glance `Row`/`Column`/`Box` renders at most ten children; the eleventh onward is dropped at render with no error surfaced to the app. Discovered on-device as "only completed trackers show": five dots plus their five gap `Spacer`s consumed the whole budget and every later mark vanished. Two consequences in code: gaps are **padding on the views, never `Spacer` children** (a spacer is a child too — padding sits inside a view's bounds, so a padded 8 dp mark is sized 12×8 to keep its 8×8 canvas), and the child budget is a **term of the tally fit ladder** (§Tally semantics), so a row that cannot legally render its dots falls to the one-child sentence instead of being truncated. Audited ceilings: tally row ≤ 10 (8 dots + spacer + count, or 10 dots alone), sleep block 6, every other container ≤ 5.
- **`SizeMode.Responsive` reports the matched bucket size, not the real one.** Any logic that consumes `LocalSize` arithmetically (the fit ladder, the bucket thresholds) needs `SizeMode.Exact`. §Size buckets carries the post-mortem.
- **A session recomposes `provideContent` without re-running `provideGlance`.** Anything a widget must show *current* has to be a Flow collected inside the composition; values computed in `provideGlance` are frozen until the session dies. §Refresh model carries the post-mortem.
- **Vector marks are authored fill-only** — §Mark vocabulary carries that history, including the misdiagnosis.

## Testing & Kover posture

Everything that can be *wrong* is a pure function, and the untestable shells are the ones that cannot execute off a device.

| Suite | Module | Covers |
|---|---|---|
| `TrendsCachePeekTest` | `:core:data` | first-key hit; first-key miss → second key served; decode failure on the first falls through to the second **and never deletes or writes**; all-miss → null; a DAO read that throws → null; `fetchedAt` propagated verbatim |
| `JournalDayPeekTest` | `:core:data` | delegation to `categoryRollup` (asserted against its own output for the same inputs) with the day log keyed by tracker id — an entry for A must not judge B; a corrupt tracker row **throws**; a corrupt entry value **throws**; a DAO read that throws → null; `CancellationException` rethrown; an empty snapshot pins `categoryRollup`'s own empty answer; snapshot rows reach the rollup unmodified |
| existing `JournalSyncStoreTest` | `:core:data` | the decoder promotion, by staying green — the promoted functions are the same ones the sync path uses |
| `TodayWidgetLogicTest` | `:app` | peek-key order; the 7-day fetch window on far-future fixture dates; the 90-minute boundary at / below / above; the `shouldFetch` matrix; bucket → elements per size, `showsCompactSleep` included; the fit ladder walked rung by rung, at font scale 1.0 and 1.3, down to the `N/M` floor; the spoken tally and the spoken sleep sentence; drawable + tint mapping |
| `TodayWidgetPaletteTest` | `:app` | every widget `Color` val equals its `LogbookLight` / `LogbookDark` field — the drift pin |

Fixtures are **synthetic only**, dates on the far-future `2030-01-*` convention (this repo is public; the rule is in `../CLAUDE.md` and is not negotiable for a widget either).

**Kover** — the floor stays **85**, and the exclusions are:

- `TodayWidgetContent` needs **no by-name entry**: the root report already excludes `annotatedBy @Composable`, and every function in it is one. (The standing gotcha applies: a capturing lambda passed to a non-inline composable wrapper escapes that filter — layout wrappers here are `inline`, as Compose's own `Box`/`Row` are.)
- `TodayWidget`, `TodayWidgetReceiver`, `TodayWidgetWorker` (+ their `$*` synthetics) and the background re-render observer are excluded **by name, with a justification comment in the established voice** (precedent: `HrCaptureService`): none can execute off a device — they need a `GlanceId`, an AppWidget host, or WorkManager's runtime — and each is a thin shell over logic that *is* counted. The rule this follows is the module's standing one: a by-name exclusion is a claim that the file holds no decisions, and every decision these four would make has been pushed into `TodayWidgetLogic` or the two peeks.

**Device acceptance** (not JVM-claimable, checked on the emulator against §Size buckets): pending state on a fresh install before the app is opened; the fallback key after opening Trends → Health once; the tally updating on background after logging a tracker; resize through 2×1 / 3×2 / 3×3; cached-with-age past 90 minutes with the server unreachable; dark-mode toggle; the `dev` variant installing its own independent widget.

## Dependencies

- **New**: `androidx.glance:glance-appwidget` 1.2.0 (no `glance-material3` — the colours are hardcoded), `androidx.work:work-runtime` as a direct `:app` dependency (it is not transitive from `:core:data`), the receiver + `res/xml/today_widget_info.xml` + three mark drawables + label/description strings.
- **Existing, reused unchanged**: `SleepDebtLogic`, `JournalUiLogic.categoryRollup`, `CategoryRollupVoice`, `TrendsRepository.sleepKey` / `MODULE` / `healthSleep`, `TrendsPrefs`, `SyncFlushWorker`'s guard pattern and its public `instanceOrNull`, `payload_cache`, `LogbookPalette` (as the pin source), `WeekMarkGlyph` + `InkJudgment` (as the drawables' geometry source).
- **New in `:core:data`**: `TrendsCachePeek`, `JournalDayPeek`, the promoted journal decoders, two Koin registrations. No Room migration — `payload_cache` is generic and the widget's key is just another row.

## Open questions

None. Resolved at the 2026-08-28 plan-approval gate:

1. **In-app Home screen** — deferred pending real experience with this widget; it is not a dependency in either direction.
2. **Deep links** — out of scope for v1; the whole widget opens the app on its normal start tab.
3. **Glance generated previews** (1.2's `previewLayout` successor) — follow-up; v1 ships the static RemoteViews mock.
