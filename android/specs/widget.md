# Spec: Home-screen widget — "Today" (Glance)

Status: **approved 2026-08-28** (the widget plan was approved as a whole; approval of that plan is approval of this spec, per its Phase 0 gate) · **amended 2026-11-10**: the tally is drawn as tally marks, not dots (owner; §Open questions, item 4); the stacked page's heights are budgeted from measured lines, a 4×1 draws its headline at a compact size, the floors follow the font scale, and a font-scale change redraws (owner; §Open questions, items 5 to 8)

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

`SizeMode.Exact`. Each bucket is a **threshold on the widget's real size** (`widgetBucket`), and every bucket is verified to render whole at its own floor on the reference launcher's widths (§Vertical budgets names the narrow-width wraps the budgets do not count); real launchers give more room, and with Exact the composition *knows* it has more. The first cut used `SizeMode.Responsive` over the three floors, which hands the composition the matched **bucket** size rather than the real one — so a strip stretched to twice its floor still laid out (and fit its tally) as if it were 110×40 dp, drawing a lone fraction in a field of paper. First device report; the mode flipped, and the fit rule reads the truth. Content is vertically centred at every bucket (below); within the stacked page the lines read top-down in the card's order.

| Bucket | DpSize (min, font scale 1.0) | Cells on the reference launcher | Shows |
|---|---|---|---|
| STRIP | 110 × 40 | 2×1 (172 × 110) | tally only — or, with no tally to draw, the compact sleep row (below) |
| WIDE | 250 wide, under CARD height | a 4×1 whose row is shorter than the CARD floor, or a 4×1 once the scaled CARD floor passes its height (110.5 dp falls short just above font scale 1.0; at 1.3 the floor is 130.1 dp) | tally in its own column · vertical rule · compact sleep row, side by side |
| CARD | 180 × 110 | 4×1 (360 × 110), with the compact headline; a 3×2 at font scale 1.3 | tally · rule · eyebrow + judgment glyph · need headline · debt line |
| PAGE | 180 × 207.5 | 3×2 (266 × 237, the default placement) and up | everything, card-faithful: + strain and the honesty lines, headline stepped up to 32 sp, block gaps widened to 10 dp |

The reference launcher is the one the device checks run on: the API 35 emulator's, 420 dpi, where a cell is 94 dp wide and 126.5 dp tall less the launcher's insets. The Cells column is what those cells measure, not a promise about other launchers; the thresholds are what decides. Until 2026-11-10 this table called a 3×2 a CARD and gave PAGE a 170 dp floor; a real 3×2 is 237 dp tall and was always a PAGE, and the 170 dp floor could not hold the page it promised (§Vertical budgets).

Space is spent, not left over (the sixth device report asked where it all went): content is **vertically centred at every bucket** — launcher cells run taller than the floors, and a column pinned to the top leaves the surplus as a void under the last line. WIDE is the strip-height family using its width: the tally takes a fixed 55 % column (`wideTallyWidthDp`; the fit ladder is fitted to that column via `tallyFitWidthDp`, not to the page), a vertical hairline divides, and the compact sleep line takes the rest. WIDE with no tally drops the rule and gives the row to the sleep line alone — a rule may not separate a column from nothing. CARD height outranks WIDE: given the height to stack, the page stacks.

**Element priority.** Elements are added in this order as the bucket grows, and dropped in reverse as it shrinks. Nothing is ever shrunk to fit — a size that cannot hold an element omits it. **One exception, by the owner (2026-11-10):** the need headline at CARD is drawn at a compact size when the widget is too short for it at 24 sp (§Vertical budgets). The number is still the largest thing on the page, and every counted line of the block is still drawn whole at the reference widths (§Vertical budgets, *Width*, carries the two narrow-widget wraps that are not counted); the alternative was a 4×1 that showed the tally beside a one-line sleep row and never the debt.

| # | Element | STRIP | CARD | PAGE |
|---|---|---|---|---|
| 1 | Tracker tally (local Room, always fresh) | ✓ | ✓ | ✓ |
| 2 | Sleep: judgment glyph + `TONIGHT'S SLEEP NEED` + `needText` + `debtLine` | — | ✓ | ✓ |
| 3 | Strain (`strainLine`) | — | — | ✓ |
| 3 | Honesty lines (`freshnessLine`, `cachedLine`) | — | — | ✓ |

The honesty lines sit at PAGE only, and that is a **measured compromise, not an oversight**: at the 180 × 110 CARD floor the headline, the debt line and a words line cannot all fit, and the judgment glyph already *is* the caveat signal (`PARTIAL` is *defined* as "`cachedLine` or `freshnessLine` present"). At CARD the glyph carries it alone; the words wait one size up, or one tap away.

**STRIP with no tally** — the one day the strip's own element has nothing to draw, because no habits are expected — hands its line to the next element in priority order, in the only form the floor holds: the **judgment glyph + `needText`** (or `-:--` in ink-faint when pending), meta-ink, no eyebrow and no `h:mm` unit. `TONIGHT'S SLEEP NEED` alone measures ≈126 dp against 86 dp of content and would wrap; the glyph carries what that label would have said, which is what the mark vocabulary is for. The decision is `showsCompactSleep(bucket, hasTally)` in `TodayWidgetLogic`, not a branch invented in the composable. An empty strip is never an option — it reads as broken.

### Vertical budgets

Glance sets no line height, so a line of text is as tall as the font's own metrics make it, and the first budget here was written from guessed lines (a 26 dp headline, 15 dp captions) that the device did not honour: the CARD block was budgeted at 109 dp and measured 118, so a 4×1 at 110.5 dp clipped its last line by 7 dp. The budgets below are **measured** (2026-11-10, API 35 emulator, 420 dpi, system mono, `uiautomator` bounds), and each constant is the measured height rounded up to the next half dp:

| Line | Size | Measured at font scale 1.0 | At 1.3 | Budgeted |
|---|---|---|---|---|
| eyebrow, tally count, cached badge | 10.5 sp | 38 px = 14.48 dp | 48 px = 18.29 dp | 14.5 dp |
| meta (debt, `h:mm`, strain, freshness) | 11.5 sp | 41 px = 15.62 dp | 53 px = 20.19 dp | 16 dp |
| headline, compact | 16 sp | 57 px = 21.71 dp (computed) | not measured | 22 dp |
| headline, CARD | 24 sp | 85 px = 32.38 dp | 93 px = 35.43 dp (computed; 92 px were visible before the widget's edge clipped the line) | 32.5 dp |
| headline, PAGE | 32 sp | 112 px = 42.67 dp | 112 px = 42.67 dp | 43 dp |

The 16 sp row is computed from the font's own vertical metrics, with the formula that reproduces the other four measurements to the pixel (line px = ⌈0.271 × size px⌉ + ⌈1.056 × size px⌉ for the system mono), and is confirmed on the device with the build that draws it.

The stacked page is fixed dp plus lines of text, and only the text grows with the font scale:

| Stack | Fixed dp | Text at font scale 1.0 | Needs at 1.0 | Floor (needs + 2 dp to spare) |
|---|---|---|---|---|
| CARD, compact headline | 12 + (6+1+6) + 2 + 2 + 12 = 41 | 14.5 + 14.5 + 22 + 16 = 67 | 108 | **110** |
| CARD, 24 sp headline | 41 | 14.5 + 14.5 + 32.5 + 16 = 77.5 | 118.5 | **120.5** |
| PAGE, worst case | 12 + (10+1+10) + 5 × 2 + 12 = 55 | 14.5 + 14.5 + 43 + 16 + 16 + 16 + 16 + 14.5 = 150.5 | 205.5 | **207.5** |

CARD counts the tally row, the eyebrow, the headline and one debt line. PAGE's worst case is a gap night seen through a stale cache: the debt line wrapped to two lines, the strain line, and both honesty lines, each of those three counted as one line. The 2 dp to spare covers the pixel rounding of four to eight lines on a density other than the measured one.

`stackHeightDp(stack, fontScale)` = fixed + text × max(fontScale, 1) + 2. The scale is applied linearly, the estimate style the tally's fit rule already uses, and it is an **upper bound**: Android scales large text by less than the nominal factor (the 32 sp headline does not grow at all at 1.3, the 24 sp one grows by 9 %), so the estimate can send a widget one bucket down that would have fitted, and never draws one whose counted lines clip. What it bounds is how tall the counted lines grow, not how many there are: a line that wraps where the budget counts one is the limit named under *Width* below. A font scale below 1.0 does not lower a floor.

**The floors follow the font scale** (`widgetBucket(size, fontScale)`): PAGE needs 180 dp of width and `stackHeightDp(PAGE)`; CARD needs 180 dp and `stackHeightDp(CARD compact)`; anything shorter is WIDE at 250 dp of width, else STRIP. A widget too short for the stack at the reader's font scale falls to the next bucket down instead of clipping its last line. On the reference launcher: at 1.0 a 4×1 is CARD and a 3×2 is PAGE; at 1.3 a 4×1 needs 130.1 dp and falls to WIDE, and a 3×2 needs 252.65 dp for PAGE and is a CARD with the 24 sp headline. That last step is the upper bound at work (the page would have measured about 233 dp); it is accepted, because the cost is two context lines at a non-default font scale and the alternative is a per-size copy of the platform's scaling tables.

**The headline step** (`headlineStep(bucket, heightDp, fontScale)`): PAGE draws 32 sp. CARD draws 24 sp when the widget's real height reaches `stackHeightDp(CARD 24 sp)`, otherwise the **compact 16 sp**. 16 sp is the largest whole size that fits the 110 dp floor with 2 dp to spare: 17 sp computes to 61 px (23.24 dp) and leaves less than 2. At 1.0 the step falls at 120.5 dp: a 110.5 dp 4×1 is compact, and any CARD from 120.5 dp up is 24 sp. The block gap is `blockGapDp(bucket)`: 10 dp at PAGE, 6 dp elsewhere. Neither is decided in the composable.

Width at 180 dp: the widest headline `! 8:05 h:mm` measures 89–105 dp at 24 sp; the 31-character gap-night debt line wraps under `maxLines = 2`. At CARD the budget counts one debt line: a gap night on a CARD narrower than about 240 dp wraps to two, and at exactly the floor height the second is clipped. That case is known and outside this round (no cell on the reference launcher is both that narrow and that short while still a CARD). PAGE has the same limit in the lines it counts once: the longest freshness line, the 23-character `data through 2030-01-01`, measures 158.7 dp against the 156 dp of content a 180 dp widget has, so a PAGE narrower than 183 dp wraps it, and a larger font scale moves that width up (230 dp at 1.3). At exactly the floor height the last line is then clipped (Codex, 2026-11-10). Known and outside this round for the same reason: the reference launcher's PAGE cells are 266 dp wide, where every counted line holds at 1.0 and at 1.3. Counting lines from the width as well as the font scale would close both cases; it is proposed, not adopted (§Open questions).

```
STRIP (2×1)         CARD (4×1 is wider)              PAGE (3×2 and up, gap night)
┌────────────────┐  ┌─────────────────────────────┐  ┌─────────────────────────────┐
│ ┃┃╏│╱ ││││╱ ││ │  │ ┃┃╏│╱ ││││╱ ││ 2 OF 12 DONE │  │ ┃┃╏│╱ ││││╱ ││ 2 OF 12 DONE │
└────────────────┘  │ ─────────────────────────── │  │ ─────────────────────────── │
                    │ ◐ TONIGHT'S SLEEP NEED      │  │ ○ TONIGHT'S SLEEP NEED      │
                    │ 8:05 h:mm                   │  │ ! 8:11 h:mm                 │
                    │ debt 1:11                   │  │ debt 1:33 · reset —         │
                    └─────────────────────────────┘  │ missing night               │
                                                     │ strain 12.6 · so far        │
                                                     │ CACHED · 3H AGO             │
                                                     └─────────────────────────────┘
```

Twelve trackers, two met and one partial: `┃` a met post, `╏` a partial one, `│` not yet, `╱` the gate that is each group's fifth mark (here not yet, so drawn faint behind its posts). The strip is at its 110 dp floor, where the marks stand alone; with 30 dp more it adds `2/12` (§Tally semantics).

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
| Row gaps | 2 dp within a block; 6 dp · 1 dp rule · 6 dp between blocks, 10 dp · rule · 10 dp at PAGE (`blockGapDp`) | the card's own rhythm |
| Background | paper, full-bleed | the only surface; grouping is whitespace and one hairline rule |
| Corner radius | launcher-controlled | no inner rounding of our own |

Type, faithful to `LogbookType` with the two Glance losses named in §Accepted deviations:

| Role | Face | Size / weight | Colour | Used for |
|---|---|---|---|---|
| headline | `FontFamily.Monospace` | 24 sp / Medium — **32 sp at PAGE**, where the card's size read as a note in a margin, and **16 sp at a CARD too short for 24** (§Vertical budgets). Lines are as tall as the font makes them; the measured heights are in that section | ink | `needText`, the `!` bang |
| meta-ink | Monospace | 11.5 sp / Normal | ink | `debtLine` |
| meta-soft | Monospace | 11.5 sp / Normal | inkSoft | the `h:mm` unit, `strainLine`, `freshnessLine` |
| eyebrow | Monospace | 10.5 sp / Normal, caps | inkSoft | `TONIGHT'S SLEEP NEED`, the tally count, `cachedLine` (uppercased) |

Colour is five tokens, hardcoded as day/night `ColorProvider` pairs because Glance cannot read `LogbookTheme`, and **pin-tested against `LogbookLight`/`LogbookDark` so drift fails the build**:

| Token | Widget use |
|---|---|
| paper | background |
| ink | headline, debt, bang, met tally strokes and the done half of a partial one, the judgment glyph in every *judged* state |
| inkSoft | eyebrow, unit, strain, freshness, cached |
| inkFaint | not-yet tally strokes and the whole stroke under a partial one, the PENDING glyph, `-:--` |
| rule | the 1 dp block separator |

No other colour appears on this surface.

## Mark vocabulary

Glance has no `Canvas`, so every mark on this surface is a **vector drawable**. There are two families, and they share nothing but the rules at the end of this section.

**The judgment glyph** — `InkJudgment`'s circle, ported at viewport 12 and drawn at 9 dp:

| Drawable | Geometry | Judgment |
|---|---|---|
| `widget_mark_filled` | filled circle r = 6 | SETTLED (ink) |
| `widget_mark_half` | left half-disc fill + filled even-odd annulus, outer 6 / inner 4.15 | PARTIAL (ink) |
| `widget_mark_hollow` | filled even-odd annulus, outer 6 / inner 4.15 | ATTENTION (ink) · PENDING (inkFaint) |

Until 2026-11-10 the same three drawables also served as 8 dp tally dots (the 1.85/12 ring sits within 0.05 dp of both `InkJudgment`'s 1.4 dp/9 dp and `WeekMarkGlyph`'s 1.2 dp/8 dp, which is why one set could do both). The tally no longer draws circles; the geometry is unchanged.

**The tally strokes** — the Logbook's tally marks (`../../docs/conventions/logbook-design.md` names them as the way progress is carried), at the size a launcher cell affords. Four drawables, two shapes each whole and halved:

| Drawable | Viewport | Geometry |
|---|---|---|
| `widget_tally_post` | 2 × 12 | the whole box: a 2 dp upright stroke 12 dp tall |
| `widget_tally_post_half` | 2 × 12 | the bottom half of the post, y 6…12 |
| `widget_tally_gate` | 21 × 12 | a 2 dp thick diagonal rising left to right across the whole box: `M0,9.41 L21,0.41 L21,2.59 L0,11.59 Z` (centreline (0, 10.5) → (21, 1.5), 23.2°; the ends are cut upright so the stroke stays inside its box) |
| `widget_tally_gate_half` | 21 × 12 | the left half of the gate, x 0…10.5 |

The half drawables keep the **whole viewport** of the stroke they halve, so a half layered over its whole registers without any alignment arithmetic in the layout.

Rules for both families:

- **Every path is a fill — no vector strokes.** Kept as belt-and-braces, with its history told straight: the first device build showed only the filled marks, this was diagnosed as stroke paths dying in the launcher's RemoteViews pipeline, and the marks were re-authored as fills (the annulus is the stroked ring's geometry restated — centreline 5.075, thickness 1.85, outer edge 6, inner 4.15). The second device build showed the *same* symptom, which broke the theory: the real killer was the **ten-child container budget** (§Glance constraints) dropping every mark after the fifth — the drawables were never given the chance to render. Fill-only stays because it is the more conservative authoring and the fills are proven on-device; the stroke variant was never exonerated. The tally strokes are polygons for the same reason.
- **Tint at the use site.** Authored fill colours in the XML are irrelevant; every draw applies `ColorFilter.tint(ColorProvider)`. This *is* the one-ink rule — the same drawable is ink in one state and ink-faint in another, and no drawable ever carries a hue.
- **A partial mark never reads as a smaller mark.** The half circle keeps its closing outline ("the missing half reads as unfinished rather than as a smaller dot" — `WeekMarkGlyph`'s own words). A partial tally stroke keeps its whole length for the same reason: the whole stroke in ink-faint with its first half in ink over it — two layers of one shape, each tinted at the use site. It is the one mark on this surface drawn in two inks, and both are tokens of the five-token palette (owner, 2026-11-10).

## Tally semantics

The tally element is a **rendering of `categoryRollup`, and owns no judgment of its own.** Every question about what counts as met, partial, not-yet, expected-today, avoidance or observation is already answered by `categoryRollup(trackers, today, dayLog)` in `:core:data` and its tested rules (`specs/journal-ui.md`); this spec adds nothing to them and must never be read as re-stating them. The widget passes the whole tracker list — not a category's — so the rollup is the *day's*.

- **Marks**: one stroke per positive tracker, on the keyline, ordered met → partial → not-yet, that is `habitsMet` then `habitsPartial` then `habitsNotYet`. Done reads left-to-right, the way tallies fill a line. Avoidances and observations get **no mark**: the ring in the app gives them their own, and a strip 110 dp wide has room for one idea. The tally is strokes at **every** count, including the small ones a row of dots could still hold — one vocabulary, so adding a tracker never changes what the widget looks like (owner, 2026-11-10).
- **Groups of five.** The marks are taken five at a time. The first four of a group are upright **posts**; the fifth is the **gate**, the diagonal struck across them. A trailing group of one to four marks is posts only. The gate is a tracker like any other: it carries the state of its group's fifth mark, and because the marks are state-sorted it is never more done than the posts it crosses.
- **States**: met is the stroke in ink; not-yet is the stroke in ink-faint; partial is the ink-faint stroke with its **first half** in ink over it — the bottom half of a post, the left half of a gate (§Mark vocabulary). Ink-faint gate layers are drawn **under** the posts and ink gate layers **over** them, so a gate that is not yet met never cuts a post that is.
- **Geometry**, in whole dp (`TodayWidgetLogic` owns every number; the composable multiplies nothing):

  | Measure | dp |
  |---|---|
  | post | 2 wide × 12 tall |
  | gap between posts | 3 (four posts: 17) |
  | gated group | a 21 × 12 box: the posts inset 2 from each side, the gate spanning the box, so it overhangs the posts by 2 |
  | gap before a gated group | 4 |
  | gap before a trailing ungated group | 6 |

  Both gaps put **8 dp between one cluster of posts and the next** (2 + 4 + 2, and 2 + 6). The row is `21·g + 4·(g − 1)` for `g` gated groups, plus, for `r` trailing posts, `2·r + 3·(r − 1)` and the 6 dp gap when a gated group precedes them: 4 marks 17 dp, 5 marks 21, 8 marks 39, 12 marks 59, 20 marks 96, 30 marks 146, 50 marks 246. The marks are 12 dp tall inside the 14 dp eyebrow line, so no vertical budget in §Size buckets moves.
- **Count**: `describeCategoryRollup`'s sentence ("5 of 8 done"), uppercased into the eyebrow style — `5 OF 8 DONE` — right-aligned on the marks' line; or its compact form `5/8` where the ladder says so.
- **Fit rule** — pure, width-driven, and **never silent**. A five-rung ladder, each rung a whole layout rather than a shrunken version of the one above it:

  | # | Rung | Taken when |
  |---|---|---|
  | 1 | marks + `5 OF 8 DONE` | both fit the content width, with an 8 dp gap between them |
  | 2 | marks + `5/8` | the sentence does not fit beside the marks but the fraction does |
  | 3 | marks alone | neither count fits beside the marks, but the marks do |
  | 4 | `5 OF 8 DONE` alone | the marks do not fit but the sentence does |
  | 5 | `5/8` | the sentence does not fit either |

  One ladder for every bucket, walked against the widget's **real** content width (`SizeMode.Exact`). The first cut froze a "≤ 7 dots" rule onto STRIP — the arithmetic evaluated at the 110 dp floor — because under `Responsive` the width handed to the rule *was* the floor and the freeze at least made that honest. With the width telling the truth the freeze became the bug (a 220 dp strip refusing dots it could hold), so the rule retired and plain width arithmetic took over.

  Rung 2 arrived with the tally marks (owner, 2026-11-10). The design language prefers marks to `2/3` counters, and this rung does not contradict it: the fraction is never drawn *instead of* marks while marks fit — it is the count that still fits beside them on a strip too narrow for the sentence, and marks past the second group are slower to count than a fraction is to read.

  **Width is not the only budget.** A Glance container renders at most **ten children** and silently drops the rest (§Glance constraints), and a dropped child is a truncated tally — the confident wrong answer the ladder exists to prevent, and exactly what the second device build showed (five met dots survived, every half and open mark after them vanished). So a rung is only open if the row can legally render it. The row's children are the **groups**, not the marks: rungs 1 and 2 spend one child per group plus the weight spacer plus the count (groups + 2 ≤ 10 → at most 8 groups, 40 trackers, with a count), rung 3 one per group (at most 10 groups, 50 trackers, alone); above that the ladder falls to the sentence, which states the full tally in one child. Counting in groups is what the tally marks bought: as dots, the same budget capped the row at 8 trackers with a count and 10 without, and the owner's twelfth tracker pushed every size down to the sentence.

  Rung 5 is the **floor of the never-truncate rule, not an exception to it**: `N/M` is the shortest true statement of a tally, so it is drawn even where the estimate says it will clip, because there is nothing left to fall back to. The mark list is never shortened at any rung — a truncated row of marks is a confident wrong answer about the day.

  The measurement is an **estimate**, stated as one: Glance measures on the device and the widget cannot, so a count's width is taken as 6.3 dp per character (10.5 sp mono at ≈0.6 em) **multiplied by the system font scale**, handed to the composition as state (§Refresh model) so that a change redraws. The counts are sized in `sp` and the marks in `dp`, so at a font scale of 1.3 the words grow by a third and the marks do not — a layout chosen without the scale would overflow the row it was fitted to. Mark widths are unaffected by it.

  At the floors, with twelve trackers at font scale 1.0 (59 dp of marks, 75.6 dp of sentence, 25.2 dp of fraction): the 110 dp strip has 86 dp of content and draws the marks alone; a 140 dp strip draws marks + `2/12`; WIDE at 250 dp has a 124.3 dp column and draws marks + `2/12`; CARD and PAGE at 180 dp have 156 dp and draw marks + the sentence. At font scale 1.3 CARD falls one rung, to marks + `2/12`.

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
| System font scale changed (`ComponentCallbacks.onConfigurationChanged` on the application, registered beside the `ON_STOP` hook) | writes the new scale to a process-wide state the composition collects, then `updateAll()` in the app scope | the bucket, the headline step and the tally's rung are all chosen from the font scale; without a redraw the launcher re-inflates the old choice with bigger text and clips it. **Best effort, see below** |

Rules the worker obeys:

- **`updateAll()` runs whether or not the fetch succeeded.** Cached-with-age *is* the offline story, and re-rendering an older copy is how the age advances on screen.
- **Always `Result.success()`.** The hourly period is the retry; a `Result.retry()` would stack backoff on top of a schedule that already comes round.
- **`TrendsRepository` is resolved lazily *inside* `doWork`**, never in a field — the worker may be constructed in a process where resolution has not happened, and the guard (`shouldFetch`) may decide not to fetch at all.
- Failures go to `DebugLog` under the tag `"widget"`, **never payload bodies** — the standing rule for this log.
- `updatePeriodMillis = "0"` in the widget info XML: the AppWidget framework's own update alarm is not used, because it cannot be made conditional on connectivity and would fight the worker.

**The font scale is state, not a read.** The composition takes the font scale from a process-wide state primed from the configuration when the session starts, never from the render context at draw time: a read is frozen until something else recomposes, and nothing else does. Whether a configuration change is a font-scale change is a pure function (`fontScaleChanged`), so a rotation or a dark-mode switch schedules nothing; a scale that is not finite and positive (an undefined configuration field reads 0) is never a change and is never adopted, and the holder never starts from one (an unusable initial value is replaced by the default, 1.0). **The limit:** the callback is delivered only while the app's process is **running**. Glance 1.2's receiver handles `LOCALE_CHANGED` and nothing else, and a manifest receiver cannot be sent `CONFIGURATION_CHANGED`, so a font-scale change wakes nothing: with the process dead, or alive but cached and frozen (which is what Android does to a backgrounded app within seconds), the widget draws its old choice at the new text size. A frozen process is handed the pending configuration the moment anything next runs it, and redraws then; a dead one is corrected by the next render (the hourly worker when online, or leaving the app), which reads the new scale. Measured on the emulator, 2026-11-10 (API 35, dev variant): process `cached`, `isFrozen=true`, font scale 1.0 → 1.3, no redraw after 11 s and the 4×1's last line clipped; an unrelated broadcast to the package unfroze it and the widget redrew as WIDE at once; with the process awake the change back to 1.0 redrew immediately without opening the app. Best effort was owner-accepted 2026-11-10 as "immediate while the process is alive"; the frozen case is narrower than that wording and is recorded here as measured (§Open questions, item 7).

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
7. **The tally is not designed for right-to-left.** The rows inherit the layout direction, so under an RTL locale the groups run right to left, while the half marks keep filling their physical left half. Not a Glance limit but a decision (owner, 2026-11-10): the app ships in English only and the dot row inherited direction the same way. If an RTL locale ever ships, the choice is between forcing the tally left-to-right and mirroring it whole with `autoMirrored` gates.

## Glance constraints

Platform facts that shaped code, found the hard way and load-bearing enough to name:

- **Ten children per container, silently enforced.** A Glance `Row`/`Column`/`Box` renders at most ten children; the eleventh onward is dropped at render with no error surfaced to the app. Discovered on-device as "only completed trackers show": five dots plus their five gap `Spacer`s consumed the whole budget and every later mark vanished. Two consequences in code: gaps are **padding on the views, never `Spacer` children** (a spacer is a child too — padding sits inside a view's bounds, so a post with a 3 dp gap before it is sized 5×12 to keep its 2×12 canvas), and the child budget is a **term of the tally fit ladder** (§Tally semantics), so a row that cannot legally render its marks falls to the one-child sentence instead of being truncated. Audited ceilings: tally row ≤ 10 (8 groups + spacer + count, or 10 groups alone), a tally group's box ≤ 3 (ink-faint gate · the posts' row · ink gate), its posts' row ≤ 4, a partial post's box 2, sleep block 6, every other container ≤ 5.
- **`SizeMode.Responsive` reports the matched bucket size, not the real one.** Any logic that consumes `LocalSize` arithmetically (the fit ladder, the bucket thresholds) needs `SizeMode.Exact`. §Size buckets carries the post-mortem.
- **A session recomposes `provideContent` without re-running `provideGlance`.** Anything a widget must show *current* has to be a Flow collected inside the composition; values computed in `provideGlance` are frozen until the session dies. §Refresh model carries the post-mortem.
- **Text has no line height.** Glance's `TextStyle` cannot set one, so a line is as tall as the font's metrics at the reader's font scale, and a vertical budget is only true if it was measured. §Vertical budgets carries the numbers and the 4×1 that clipped.
- **A configuration change does not recompose.** The receiver reacts to a locale change only; a font-scale change reaches a session through state it collects, or not at all. §Refresh model carries the trigger and its limit.
- **Vector marks are authored fill-only** — §Mark vocabulary carries that history, including the misdiagnosis.

## Testing & Kover posture

Everything that can be *wrong* is a pure function, and the untestable shells are the ones that cannot execute off a device.

| Suite | Module | Covers |
|---|---|---|
| `TrendsCachePeekTest` | `:core:data` | first-key hit; first-key miss → second key served; decode failure on the first falls through to the second **and never deletes or writes**; all-miss → null; a DAO read that throws → null; `fetchedAt` propagated verbatim |
| `JournalDayPeekTest` | `:core:data` | delegation to `categoryRollup` (asserted against its own output for the same inputs) with the day log keyed by tracker id — an entry for A must not judge B; a corrupt tracker row **throws**; a corrupt entry value **throws**; a DAO read that throws → null; `CancellationException` rethrown; an empty snapshot pins `categoryRollup`'s own empty answer; snapshot rows reach the rollup unmodified |
| existing `JournalSyncStoreTest` | `:core:data` | the decoder promotion, by staying green — the promoted functions are the same ones the sync path uses |
| `TodayWidgetLogicTest` | `:app` | peek-key order; the 7-day fetch window on far-future fixture dates; the 90-minute boundary at / below / above; the `shouldFetch` matrix; bucket → elements per size, `showsCompactSleep` included; the bucket thresholds at font scale 1.0 and 1.3 on the four reference cells (2×1, 4×1, 3×2, 3×3) and just under and exactly at each floor, with a font scale under 1.0 leaving the floors where they are; `stackHeightDp` pinned to the three budget rows; the headline step at 110, 120, 120.5 and 121 dp and at PAGE, and at font scale 1.3; a 4×1 at font scale 1.3 end to end (WIDE, its tally fitted to the WIDE column); `blockGapDp`; `fontScaleChanged` for an unchanged scale, a changed one and an unusable one (zero, negative, not finite); the font-scale holder reporting only changes; the tally's grouping in fives and the gate taking its group's fifth state; the mark-row width table and every gap; the five-rung fit ladder walked rung by rung, at font scale 1.0 and 1.3, down to the `N/M` floor, with the twelve-tracker answers at the 110 / 140 / 180 dp floors; the child budget at 40, 41, 50 and 51 trackers; the never-truncate sweep over 1 to 60 trackers, six widths and three font scales, counting the words and their 8 dp of separation as well as the marks; stroke layers, their drawables, and which gate layers go under or over the posts; the spoken tally and the spoken sleep sentence; judgment drawable + tint mapping |
| `TodayWidgetPaletteTest` | `:app` | every widget `Color` val equals its `LogbookLight` / `LogbookDark` field — the drift pin; each named tint (`widgetTintColor`) resolves to its own provider, so the content holds no colour decision |

Fixtures are **synthetic only**, dates on the far-future `2030-01-*` convention (this repo is public; the rule is in `../CLAUDE.md` and is not negotiable for a widget either).

**Kover** — the floor stays **85**, and the exclusions are:

- `TodayWidgetContent` needs **no by-name entry**: the root report already excludes `annotatedBy @Composable`, and every function in it is one. (The standing gotcha applies: a capturing lambda passed to a non-inline composable wrapper escapes that filter — layout wrappers here are `inline`, as Compose's own `Box`/`Row` are.)
- `TodayWidget`, `TodayWidgetReceiver`, `TodayWidgetWorker` (+ their `$*` synthetics) and the background re-render observer (which now also carries the font-scale callback) are excluded **by name, with a justification comment in the established voice** (precedent: `HrCaptureService`): none can execute off a device — they need a `GlanceId`, an AppWidget host, or WorkManager's runtime — and each is a thin shell over logic that *is* counted. The rule this follows is the module's standing one: a by-name exclusion is a claim that the file holds no decisions, and every decision these four would make has been pushed into `TodayWidgetLogic` or the two peeks.

**Device acceptance** (not JVM-claimable, checked on the emulator against §Size buckets): pending state on a fresh install before the app is opened; the fallback key after opening Trends → Health once; the tally updating on background after logging a tracker; resize through 2×1 / 4×1 / 3×2 / 3×3; a 4×1 at font scale 1.0 drawing all three sleep lines whole, in light and dark; the font scale set to 1.3 with the app's process running (awake, not cached and frozen) redrawing every size without the app being opened, nothing clipped, and back again at 1.0; the tally with twelve trackers at each of those sizes — every mark present, a partial one reading as half, a gate legible across its posts in light and dark; cached-with-age past 90 minutes with the server unreachable; dark-mode toggle; the `dev` variant installing its own independent widget.

## Dependencies

- **New**: `androidx.glance:glance-appwidget` 1.2.0 (no `glance-material3` — the colours are hardcoded), `androidx.work:work-runtime` as a direct `:app` dependency (it is not transitive from `:core:data`), the receiver + `res/xml/today_widget_info.xml` + three judgment-mark drawables + four tally-stroke drawables (2026-11-10) + label/description strings.
- **Existing, reused unchanged**: `SleepDebtLogic`, `JournalUiLogic.categoryRollup`, `CategoryRollupVoice`, `TrendsRepository.sleepKey` / `MODULE` / `healthSleep`, `TrendsPrefs`, `SyncFlushWorker`'s guard pattern and its public `instanceOrNull`, `payload_cache`, `LogbookPalette` (as the pin source), `InkJudgment` (as the judgment drawables' geometry source; `WeekMarkGlyph` was the dots' until the tally became strokes).
- **New in `:core:data`**: `TrendsCachePeek`, `JournalDayPeek`, the promoted journal decoders, two Koin registrations. No Room migration — `payload_cache` is generic and the widget's key is just another row.

## Open questions

One, proposed and not adopted (2026-11-10): **line counts that follow the width.** The vertical budgets count lines from the font scale alone, so a line that wraps on a narrow widget is not counted (§Vertical budgets, *Width*: the gap-night debt line at CARD, the freshness line at PAGE). A worst-case line count per width and font scale, in `stackHeightDp`, would make a narrow widget fall a bucket instead of clipping. No cell on the reference launcher is affected.

Resolved at the 2026-08-28 plan-approval gate:

1. **In-app Home screen** — deferred pending real experience with this widget; it is not a dependency in either direction.
2. **Deep links** — out of scope for v1; the whole widget opens the app on its normal start tab.
3. **Glance generated previews** (1.2's `previewLayout` successor) — follow-up; v1 ships the static RemoteViews mock.

Resolved 2026-11-10, by the owner:

4. **A tally that outgrew its dots.** With twelve positive trackers the dot row could not be rendered at any size — ten children per container is the limit, and twelve dots are 140 dp wide — so every bucket fell to `2 OF 12 DONE`. Five alternatives were mocked (dots in fives, tally marks, the rule as a measure, a segmented ring, dots by category) and the owner chose **tally marks**. Decided with it, each stated in the section it governs: strokes at every count, not only past ten (§Tally semantics, Marks); the `marks + N/M` rung (§Tally semantics, Fit rule); the partial stroke in two inks (§Mark vocabulary); whole-dp geometry, with a 2 dp gate overhang where the mock drew 2.5 (§Tally semantics, Geometry).

5. **A 4×1 that clipped its last line.** The CARD stack measured 118 dp against a budget of 109, so the reference launcher's 110.5 dp 4×1 cut the debt line in half. Offered: drop a line at that height, or keep all three. The owner chose to **keep the stacked block and draw the sleep-need number smaller** where 24 sp does not fit; the size is 16 sp, by measurement (§Vertical budgets). This is the one dated exception to "nothing is ever shrunk to fit".
6. **Floors that follow the font scale.** Approved with the plan: a bucket's floor is its measured stack at the reader's font scale, PAGE's floor moves from 170 dp to its measured worst case (207.5 dp), and a widget too short for a stack falls a bucket instead of clipping.
7. **A font-scale change redraws, best effort.** Approved with the plan, limit included: immediate while the app's process is alive, otherwise corrected at the next render (§Refresh model). The device run then measured a narrower limit than the approved wording: immediate only while the process is running, because a cached, frozen process is not handed the change until it next runs. Recorded as measured, 2026-11-10; a trigger that works with the process frozen or dead (a WorkManager job on the `Settings.System.FONT_SCALE` content URI) is proposed and not adopted, and would be a new row in §Refresh model.
8. **Right-to-left is left alone**, and recorded as an accepted limit (§Accepted deviations, item 7).
