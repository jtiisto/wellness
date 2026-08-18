# Spec: Logbook Design System (Rounds 1–2: shell, Coach, Journal)

Status: **Round 1 (shell + Coach) shipped 2026-08-18** — foundation, dual-theme
shell, coach presentation state and rendering, five device-pass fix rounds all
landed and pushed on `feature/logbook-design`. **Round 2 (Journal) built
2026-08-18** — the user chose the pure-ink variant from two mockups
(`plans/logbook-design/journal-logbook-{ink,accent}.html`); the
"Components — Journal" section below is its committed form, and P1 (presentation
state) and P2 (composables, shell flip, launch window) are implemented and
green, awaiting the device acceptance pass. Trends, Analysis and Tools stay on
Graphite Signal until their rounds.

## Goal

Replace Graphite Signal as the app's design language with **Logbook** — a
training-log system whose governing metaphor is a coach's paper logbook: one
flat surface, tabular numerals, coach's notation, ink — with color reserved for
exactly one meaning. The language is destined for the whole app; this round
converts the **app shell and the Coach feature**. Journal, Trends, Analysis and
Tools migrate in later rounds and remain on Graphite Signal
([design-system.md](design-system.md)) until theirs.

Design source: `DESIGN-LANGUAGE.md` + `logbook-mockup.html` (past state) +
`logbook-scheduled.html` (scheduled state), local-only at the repo root's
`plans/logbook-design/`. This spec is their committed, Android-resolved form —
where they and this spec disagree, this spec wins.

Behavior contracts are untouched: every rule in [coach-ui.md](coach-ui.md)
(ports, gates, hook machine, set-grid write path, ghost lookup) stands. The
round adds pure presentation-derivation code (plate assignment, legend, eyebrow,
marks) to the tested state layer and restructures composables above it.

## Core principles

1. **One surface, no nested cards.** The screen background is the only surface.
   Grouping is whitespace and hairline rules — never card-in-card containers,
   tinted panels, or colored left rails.
2. **Color means tier, nothing else.** The only chromatic UI elements are
   plate-color dots encoding an exercise's tier. **Tier = `exposure`** (the
   free-string identity key on `PlanExerciseDto`). Colors are assigned
   **positionally**: each distinct exposure string in a workout takes the next
   color from the fixed palette (red → blue → yellow → green) in order of first
   appearance; a 5th+ distinct exposure renders a solid ink dot (colors never
   repeat). A legend under the header maps each dot to its string and is
   **load-bearing** — required on any screen showing tier dots.
3. **Numbers are mono, words are sans.** Every numeral (weights, reps, RPE,
   times, schemes like `3×6–9`) sets in IBM Plex Mono (tabular by default).
   Prose and names use Inter; display headings use Barlow Condensed caps.
4. **Grouping is drawn, not named.** Superset membership is a thin ink square
   bracket in the left gutter spanning the grouped rows. Straight sets leave the
   gutter empty. No "Superset A" labels, no A1/A2 letters.
5. **Completion is a tally, not a badge.** Small filled ink marks, one per
   completed set; outlined when incomplete. No `2/3` pills.
6. **At most one metadata cluster per row.** Collapsed row = dot · name ·
   scheme · tally. Everything else lives in the expanded state.

## Migration state (dual themes)

| Surface | System |
|---|---|
| Shell (nav bar, snackbar, `ServerRecoveryScreen`) | **Logbook** (Round 1) |
| Coach | **Logbook** (Round 1) |
| Journal (day view + tracker config) | **Logbook** (Round 2) |
| Trends, Analysis, Tools | Graphite Signal — [design-system.md](design-system.md) stays their authority |

Each nav destination is wrapped in its own theme; the Scaffold container color
follows the active destination's canvas. Phases land incrementally *inside*
each round: the shell moved first while Coach stayed wrapped in Graphite until
its rendering phases landed — flipping it earlier would render Graphite-styled
composables against Logbook locals, i.e. tokens that mean something else. The
shell test pins each destination's current system twice (once in the route
table, once in its own named case) so a flip is always a deliberate multi-place
edit. Launch-window `colors.xml` **flipped to Logbook paper in Round 2**, with
journal — it is the start destination, so it is the one whose canvas the launch
window has to match, and `ShellSystemTest.journalIsLogbook` is the pin on the
other half of that pair. Graphite tokens retire module-by-module; the Graphite
spec shrinks as modules leave it.

## Tokens (`core/ui/theme/` — new `LogbookPalette.kt`, `LogbookType.kt`, `LogbookTheme.kt`; package stays `dev.jtiisto.wellness.core.ui.theme`, the Kover-excluded package)

### Color

| Token | Light | Dark | Use |
|---|---|---|---|
| `paper` | `#FBFAF7` | `#141517` | screen background — the only surface |
| `ink` | `#17191B` | `#EDECE7` | primary text, tally marks, strong rules |
| `inkSoft` | `#6A6D73` | `#7D8288` | secondary text, schemes, meta labels |
| `inkFaint` | `#A6A9AD` | `#55585D` | ghost values, empty states, table headers, unfilled marks |
| `rule` | `#E7E5DE` | `#2A2C2F` | hairline dividers between rows |
| `ruleStrong` | `#C7C5BB` | `#3A3C40` | group boundaries, marginalia rails, popup border |
| `plateRed` | `#B92D3A` | `#B92D3A` | tier dot, 1st distinct exposure |
| `plateBlue` | `#2A5FA8` | `#2F6BBC` | tier dot, 2nd |
| `plateYellow` | `#A87C1F` | `#C99A2A` | tier dot, 3rd |
| `plateGreen` | `#2E7D4F` | `#2E7D4F` | tier dot, 4th |

Contrast resolutions (dots are non-text UI: 3:1 floor on their paper). All
values below are measured in Phase 1 and pinned by `LogbookPaletteTest`:

- Light `plateYellow`: the design doc's `#C99A2A` measures 2.47:1 on light
  paper. Darkened within the hue family to `#A87C1F` — **3.61:1**.
- Dark `plateBlue`: the design doc's `#2A5FA8` measures 2.87:1 on dark paper.
  Lightened along its own hue to `#2F6BBC` — **3.44:1**, in the same band as
  the other dark plates rather than scraping the floor.
- Dark `plateRed` keeps `#B92D3A` at **3.045:1** — it passes, but it is the
  thinnest margin in the palette; treat it as pinned, not as headroom.
- Remaining plates: light red 5.75, light blue 6.10, light green 4.83; dark
  yellow 7.08, dark green 3.62.
- **`inkSoft` and `inkFaint` are cut per mode** — "the light value holds" did
  not survive measurement, and this is what the table's original *verify
  contrast* note was for. `#71757B` misses 4.5:1 in *both* modes (4.44 light,
  3.94 dark), so light darkens to `#6A6D73` (**4.97:1**) and dark lightens to
  `#7D8288` (**4.72:1**). Reusing light `inkFaint` `#A6A9AD` in dark measures
  7.74:1 — *brighter than the soft tier above it*, inverting the ramp — so dark
  gets `#55585D` (**2.56:1**). The palette test asserts the ordering
  ink > inkSoft > inkFaint on paper so the inversion cannot come back.
- **`inkFaint` is a documented WCAG exemption**: ghost values on paper measure
  ≈2.3:1 light / ≈2.6:1 dark — *by design intent* ("not yet yours" must
  recede). The palette test asserts a ≥2:1 floor with the rationale in KDoc,
  not the 3:1 text floor.

**No semantic success/warning/error tokens exist in Logbook.** Coach's current
semantic-color usages are re-expressed in ink (see Components). Two documented
exceptions carry color for live signal, not decoration: the **HR tone dot**
(instrument reading) and the sync status indicator's states (transient device
truth); both are quiet dots, never fills or text colors.

Both exceptions borrow Graphite's semantic tokens, and both are rendered inside
a Logbook subtree — where `LocalWellnessPalette` is **not provided** and answers
with its `DarkPalette` default. Reading the local would therefore have painted a
dark-theme green onto light paper. `HrTone.color()` and the sync indicator
resolve the Graphite pair from the system's dark-mode setting instead (which is
how `WellnessTheme` picks its own, so Graphite is unchanged), and
`HrTone.logbookColor()` resolves it from the Logbook palette's own `isDark`.
This is the shape of the trap for every later round: a shared `core/ui`
component that still reads Graphite locals silently degrades to the *dark*
palette the moment its host destination flips.

### Type

Bundled fonts (first font resources in the repo, `core/ui/src/main/res/font/`,
all SIL OFL — license files ship alongside; ≈1–1.5 MB APK cost):
Barlow Condensed (SemiBold), Inter (Regular/Medium/SemiBold + Italic),
IBM Plex Mono (Regular/Medium + Italic).

| Role | Face | Spec | Use |
|---|---|---|---|
| `display` | Barlow Condensed 600 caps | 34sp, tight leading | workout titles |
| `section` | Barlow Condensed 600 caps | 17sp, +6% tracking | section labels |
| `name` | Inter 500 | 14.5sp | exercise names |
| `body` | Inter 400 | 12.5–13sp | prose, user notes; italic variant = marginalia + empty states |
| `data` | IBM Plex Mono 400 | 12.5sp | set-table cells, schemes |
| `meta` | IBM Plex Mono 400/500 | 11.5sp | meta lines (values 500-weight ink, labels 400 ink-soft) |
| `eyebrow` | IBM Plex Mono 400 caps | 10.5sp, +12–14% tracking | eyebrows, legend, user-note labels, hints |
| `tableHeader` | IBM Plex Mono 500 caps | 10sp, +12% tracking | set-table column heads |

Plex Mono is tabular-figured natively — no `tnum` gymnastics needed. The M3
`Typography` and `ColorScheme` adapters fill **every** slot (incl. the `*Fixed`
roles) from Logbook tokens, same discipline as Graphite's adapter — baseline
purple must be unreachable.

### Spacing, rules, shape, interaction

- Base grid 4dp. Row padding 14dp vertical; 18dp around group boundaries.
- Hairlines 1dp `rule`; group boundaries 1dp `ruleStrong`; section underline
  1.5dp ink.
- Corner radius ≤2dp (marks only). The design is flat by intent: no drop
  shadows inside the screen, no tonal elevation.
- Touch targets ≥48dp everywhere (Android law beats paper flatness; the
  density-pass visual-vs-interactive split carries over unchanged).
- **Indication**: `LogbookTheme` replaces the M3 ripple with a quiet ink press
  overlay (low-alpha ink wash, no bounded ripple animation) via
  `LocalIndication` inside Logbook subtrees.
- Icons: trimmed set. Decorative glyphs are removed (prescription dumbbell,
  meta-row location/phase icons → text labels). Survivors (nav glyphs,
  chevrons, lock, calendar) render as outlined glyphs in ink. Eyebrow state
  glyphs are icon glyphs tinted ink — **never colored emoji**.

## Components — shell

- **Theme wiring**: `LogbookTheme {}` wraps the Scaffold + nav bar + coach
  destination; other destinations stay under `WellnessTheme` (each destination
  wrapped at the nav-graph dispatch). Scaffold container follows the active
  destination's canvas so tab switches never flash the wrong paper.
- **Bottom nav**: paper surface, 1dp `rule` top hairline, outlined ink glyphs;
  selected item = full-ink glyph + label with the existing underline bar drawn
  in ink (module accents leave the nav). Stock `NavigationBar` retained
  (accessibility surface stays stock), colors overridden.
- **Snackbar**: inverted paper/ink (dark ink surface, paper text in light mode
  and vice versa), radius ≤2dp.
- **`ServerRecoveryScreen`**: same treatment as coach empty states — display
  heading, body prose, ink outline button.

## Components — Coach

### Header block
Eyebrow · display title · meta line · legend, replacing the banner system.
The workout lifecycle expresses **only** through the eyebrow, value color, and
mark fill — `ReadOnlyBannerRow` and `SemanticBanner`-for-read-only retire.

Eyebrow states (mono caps, ink-soft, glyph in ink):

| State | Eyebrow |
|---|---|
| Past | `PAST WORKOUT · READ-ONLY` (lock glyph) |
| Future | `SCHEDULED · LOG ON <WEEKDAY, MON D>` (calendar glyph) |
| Today, nothing logged, start not fired | `TODAY · READY TO LOG` (calendar glyph) — *new state the mockups don't cover; wording open* |
| Today, started (start hook fired or any data logged) | `IN PROGRESS · STARTED <time>` when the start-hook time exists, else `IN PROGRESS` (filled-dot glyph) |

Legend: one mono line mapping each plate dot to its exposure string in palette
order, strings uppercased in the legend only, rendered verbatim otherwise.
Required whenever any dot is visible on screen.

### Sections
Display-caps label with a 1.5dp ink underline; optional mono hint right-aligned
on the same baseline. **The hint is the block's timing only** — short by
construction (`formatInterval`). Rest guidance is free coaching prose that can
run to sentences and cannot share a baseline with the label; it renders as a
section marginalia below the head (device-found 2026-08-17: joining it into
the hint collapsed the label into a one-letter-per-line column — the label
also measures first now, so no future hint can crush it). Section-level
execution notes render as marginalia directly under the head. `UnreadablePlanDay` keeps its
banner *content* but restyles as marginalia + ink glyph (no `band` fill, no
error rail).

### Exercise rows
- 16dp left gutter on all strength rows. Grouped rows share a drawn bracket:
  1.5dp ink vertical stroke with 6dp horizontal ticks top/bottom, spanning the
  group (including any expanded content). Straight sets leave the gutter empty.
  `SupersetGroup`'s accent rail, 6% wash, label chip, and A/B/C hue rotation
  retire; `groupExercises` grouping data is reused as-is
  (`supersetDisplayLabel` stays ported/tested but rendering stops using it).
  Group-level execution notes render as marginalia inside the bracket above the
  first row.
- Collapsed row: plate dot inline with the name (wraps with text) · scheme +
  tally right-aligned in mono. Parsed-name pills (`NeutralPill`) become plain
  ink-soft text after the name; `ExposureChip` retires (the dot + legend carry
  exposure). Completed-name success-green retires (the tally says it).
- Tally: 6×11dp ink marks, ≤2dp radius, one per set — filled when completed,
  outlined `inkFaint` when not. Checklist rows tally per item (`9 items
  ▮▮▮▮▮▮▮▮▮`); cardio/duration rows keep value + one mark.
- Expanded detail (indented under the name column):
  - Meta line (mono): values ink 500, labels ink-soft — e.g. `35 lb/hand ·
    target RPE 7 · tempo 3-1-1-0` (invented example). The load slot accepts
    arbitrary strings (`load light band assist`) — never assume numeric; label
    it explicitly when it is not a weight. Replaces `PrescriptionRow` + its
    dumbbell icon.
  - Set table: mono, right-aligned numeric columns, hairline `rule` row
    dividers only — **no cell backgrounds, no zebra striping**. Column heads in
    `tableHeader` ink-faint. Set index numbers stay ink in all states (they are
    structure, not data).
  - Set completion mark: custom ink square (filled ✓-less mark when logged,
    outlined when not) replacing the M3 `Checkbox`, inside the same 48dp
    target, writing `{completed}` through the unchanged path.
  - **Ghost vs logged is a color state, not a layout state**: ghost values
    render `inkFaint`; once a set is logged its values switch to ink and its
    mark fills. Row structure never changes. Footer names provenance:
    scheduled/today `Ghost values · last at this tier · <date>`; completed
    workouts `Last at this tier · <date>` (replaces the `Last · <date>` hint).
    Never label ghosts "planned" or "target".
  - Entry cells (editable states): `WellnessDenseField` gains a **`NAKED`
    skin** — bare mono text at rest (indistinguishable from the read-only
    table), 1dp ink underline + cursor when focused. 48dp targets, string-backed
    commit semantics untouched. A **read-only day draws plain text, not disabled
    fields**: "no layout change between ghost and logged" is a rule about one
    table, and reserving a 48dp target per cell on a day with nothing to touch
    doubles the height of every past workout. The two render identically
    otherwise, which is what the skin exists for — so a naked field's value is
    ink whether or not it is enabled, and only *absence* recedes.
  - The row **chevron survives** the icon trim (the mockups, being static, show
    none): it is the only affordance saying a row opens, and chevrons are on the
    survivor list. Drawn ink-faint so it recedes from the metadata cluster.
  - Execution note as marginalia; then the user-notes field.

### Notes taxonomy (two kinds, opposite directions, distinct treatments)
1. **Execution notes (program → user)** — *marginalia*: italic ink-soft body
   with a 2dp `ruleStrong` left border, indented to the level they attach to
   (section / group / exercise). Same look at every level = same meaning.
   Replaces `GuidanceNote`'s warning rail.
2. **User notes (user → log)** — mono eyebrow label (`YOUR NOTES`,
   `PAIN / DISCOMFORT`) above roman ink body text. Never italic, never a left
   rule. Empty states are italic ink-faint, phrased by workout state:
   read-only `None recorded` · scheduled `None yet — recorded after the
   session` / `Added when you log this session` (exercise level) · editable
   `Add a note`.

### Calendar
- Trigger row: paper + bottom hairline (chrome fill retires).
- Popup: paper surface, 1dp `ruleStrong` border, **no shadow**, radius ≤2dp.
- Day status marks in ink: filled dot = completed, outlined = scheduled,
  slashed = missed (`statusColor` semantic mapping retires). Selected day =
  ink fill with paper numeral; today = ink underline under the numeral.
  Legend row updates to the mark language.

### Controls
- Hook buttons: Start = ink-filled (paper text); End = ink outline; PENDING =
  outline + `WORKING…` mono label; FIRED/LOCKED = ink-filled + ✓ glyph;
  FAILED = outline + ✗ glyph + `FAILED` mono label — **no semantic colors**.
  Undo stays a text button in ink. State machine untouched.
- `HrBpmChip` → bare mono value + tone dot, no container (tone colors kept —
  documented exception above). Capture sheets: paper container, 1dp `ruleStrong`
  top edge, drag handle restyled to a short ink rule.
- `ExtraSessionCard` → flat section treatment (rule-bounded, no card fill);
  buttons per hook-button language. The `off-plan` chip becomes an eyebrow
  label.
- Loading: M3 spinner tinted ink (unchanged mechanics).
- Session feedback: section header + mono labels per user-notes treatment.

## Components — Journal (Round 2; mockup: `journal-logbook-ink.html`)

**Journal renders no chromatic elements at all** — the pure-ink resolution the
user chose over an adherence color axis (2026-08-18). Principle 2 survives
unamended: color means tier, and journal has no tiers, so the page is entirely
ink. The PWA's semantic-honesty rules (avoided never success-styled, a slip is
crossed out rather than alarmed, no verdict where none exists) are carried by
**shape**, not hue. Behavioral contracts in [journal-ui.md](journal-ui.md) —
ports, schedule/target/rollup logic, entry presence semantics, `mergeEntry`,
stamping — are untouchable; this section replaces only its visual clauses.

### The week-mark grammar (one shape language for rows and rollups)
Drawn marks, ≤2dp radius where square, sized ~8dp in rows / 9dp in rollups:

| State (`recentDayStates` / `dayStatus`) | Mark |
|---|---|
| met (habit) | filled ink dot |
| partial | half-filled dot (left half ink, hairline outline) |
| open / quiet (on schedule, nothing logged) | ink-faint outline dot |
| missed | outline dot with a diagonal slash |
| off-schedule | short faint dash (a non-day, not a failure) |
| avoidance held | the **hoop**: ink-soft ring with a center point |
| avoidance broken | hoop with a diagonal slash |
| observation noted | filled ink diamond |
| observation expected, not noted | outline diamond |

The selected/today mark carries an offset outline ring (the strip's "you are
here"). Negative-polarity rows use hoop marks exclusively — a held avoidance
is never a filled "success" dot. A mono **shape legend** renders once under
the header (as mocked); it is one quiet line and it is what makes the grammar
self-describing.

**Three rules the table above cannot express** (found while deriving it, pinned
in `JournalNotationTest`; the mockup shows all three):

1. **Row class, not tracker type, picks the vocabulary.** A row is a habit, an
   avoidance or an observation *on the selected day* — the same three-way split
   `categoryRollup` makes, off the same `isActionable` predicate — because a
   tracker changes class over time (a goal added today leaves last week's days
   unjudged) and a row drawn in two vocabularies reads as noise.
2. **`quiet` is ambiguous and the row class resolves it**: outline diamond on an
   observation row, ink-faint open dot on a habit or avoidance row. Quiet appears
   legitimately mid-row on habit rows — a day before the tracker's target came
   into effect was non-actionable *that day* — and must not turn a run of dots
   into a run of diamonds.
3. **The today-open honesty rule.** When the run ends on the real today and the
   tracker has **no entry row** for it, a habit or avoidance row draws the open
   dot in place of the judged mark. The state model must answer for every day, so
   it says missed for an unlogged habit and held for an unlogged avoidance — but
   the day is not over and neither verdict is earned (the same principle as the
   app's irreversible-today streak rule). Any entry restores the judged mark.
   Observation rows are exempt: the outline diamond already says exactly this.
   Off-schedule and noted days are exempt too — only a verdict can be suspended.
   The rule **does not propagate to the rollup cluster**, which counts the
   category as `categoryRollup` reported it: the cluster speaks for a category,
   not for the one tracker whose day is still open.

### Screen structure
- Header: eyebrow (`TODAY · N OF M LOGGED` — derived, mono caps) · display-caps
  `JOURNAL` · the 7-day strip. **N counts entry *presence* for judgment** —
  `countsAsLogged`, never bare `completed` and not raw row existence
  (resolution 9): a row an uncheck emptied counts as nothing logged, while a
  written value counts even with the box cleared. **M is what is visible** that
  day, so an off-schedule tracker is not an omission. A browsed day swaps the
  date in for `TODAY`; a day with nothing visible drops the tally rather than
  reading `0 OF 0 LOGGED`. There is no third, un-writable voice — every day is
  editable (resolution 10).
- **Date strip**: 7 mono columns (day initial in eyebrow style over day number
  in data mono — the initial is the *locale's* narrow weekday, not a slice of
  the hard-coded English `dayName`), selected day = 2dp ink underline + ink
  numeral; **every column is tappable, always** (resolution 10 — the lock glyph
  and the faded rendering retire with the rule).
- **Categories become sections**: display-caps head + 1.5dp ink underline; the
  welded card, band, and `Modifier.welded()` retire (JournalScreen is their
  only consumer). Collapsed/expanded state and its persistence are unchanged —
  the chevron leads, rows show/hide, the head and rollup always render.
- **Rollup cluster** (replaces the Graphite signal ring; same
  `categoryRollup`/`describeCategoryRollup` state): one flat cluster,
  right-aligned in the head, classes in fixed order with a wider gap between
  classes — habit marks one-per-habit, **state-sorted** met → partial → open;
  then ONE avoidance hoop as worst-state collapse (any broken → slashed);
  then diamond + mono count of noted. Classes degrade by subtraction; nothing
  expected today → bare head. a11y: the existing `describeCategoryRollup`
  string on the merged head node.

### Rows
- Anatomy: ink entry mark (the coach set-mark component, same 48dp target,
  same `mergeEntry` write path) · name in `type.name` · value right-aligned in
  mono (`6 / 8 glasses`, `✓`, `70`) — ink when committed, ink-faint ghost when
  uncommitted or defaulted; an off-schedule-but-logged row carries a mono
  eyebrow label (`off`-style) instead of any tint.
- Second line, indented past the mark column: the target bar (3dp hairline
  track in `rule`, ink fill, only when `fillPct != null` — at-most targets get
  no bar, exactly as today) and the 7-day week marks right-aligned. The run is
  **one spoken node**: `describeRowMarks` names each day's drawn mark aloud
  (`Last 7 days: Mon done, Tue missed, …`), with each shape's word pinned by
  `a11yLabel` in the cluster's vocabulary (held/broken/noted). It speaks the
  *drawn* marks, not the states, so the today-open suspension survives aloud by
  construction — an open day is never read as "missed" to the one audience that
  cannot see the difference.
- Third line when present: the last-updated caption in faint mono.
- Widgets keep their contracts: quantifiable = NAKED numeric field, **End-aligned
  — the table convention** (the device pass overruled this spec's original
  "form-aligned Start": the rows stack into a value column read down the page,
  and Start left a gulf between each number and its own unit; End welds the
  pair into the one right-aligned token the mockups draw. An accumulator's `+`
  *leads* its cluster for the same reason — trailing, it pushed only its own
  row's numbers out of the column). Note = quiet multiline with the mono-caps
  label voice,
  evaluation = M3 Slider restyled to ink track/thumb via explicit colors,
  accumulator = paper sheet in the capture-sheet treatment. Checkbox semantics
  (default-write on ABSENT only) untouched.

### Config screen and chrome
- Config list + form follow the established Logbook form patterns: sections,
  mono-caps field labels, NAKED/quiet fields, ink buttons with explicit
  `LogbookShapes.soft`, marginalia for inline guidance. The weekday picker
  becomes seven mono day-initials over toggleable ink squares (the mark
  language, not FilterChips); paused dims the picker to ink-faint as today.
  Target parse errors render as ink text with a `!` glyph — no error color
  exists in Logbook.
- Delete confirm stays an `AlertDialog` — it inherits the Logbook M3 mapping
  (paper, 2dp, ink) with no per-callsite color work.
- `SyncStatusDot` (journal header) keeps its status colors — the documented
  live-signal exception. The bare dot draws no text, so unlike coach's
  labelled `SyncStatusIndicator` there are no `textStyle`/`labelColor` params
  to pass (implementation resolution 6 below).
- The launch-window `colors.xml` **flips to Logbook paper in this round** —
  journal is the start destination; this is the recorded trigger. Shell flip:
  journal → LOGBOOK in the destination table + `ShellSystemTest`'s pinned map
  + a `journalIsLogbook`-style pin (the deliberate multi-place edit).

### Round 2 implementation resolutions (2026-08-18)

Everything the section above did not settle, decided while building it.

1. **Three components hoisted into `core/ui/theme/`.** Features never depend on
   each other, and journal needed three things coach had written first: the
   set-completion mark (`InkMark` + `InkMarkToggle`), the button pair
   (`InkButton`/`InkOutlineButton`, now taking `label`/`glyph`/`note`/
   `stateDescription` rather than coach's `HookButtonSkin`), and the sheet's
   drawn top edge (`LogbookSheetHandle`). Coach consumes the hoisted versions;
   its hook-state machine keeps `HookGlyph`/`HookButtonSkin` and maps them onto
   the neutral `InkGlyph` at one callsite, so `CoachNotation`'s pins are
   untouched. **These are the design system's furniture, not coach's** — a
   second copy in another feature is how a language starts to drift.
2. **`WellnessDenseField.italic` → `ghostValue`.** The flag always meant "this
   value is a default, not yet the user's own"; each system now says it in its
   own voice — Graphite leans the value into italics, `NAKED` fades it to
   `inkFaint`. NAKED had ignored `italic` outright, which left journal's
   uncommitted values with no way to recede once its fields went bare.
3. **The row's three lines, resolved.** The mockup is a static rendering, so its
   "value" column holds text the live journal spends on a widget. Line 1 is
   mark · name (+ `OFF` label) · **widget**; line 2 is the target — its sentence
   over its bar — at the start, with the week-mark run right-aligned; line 3 is
   the last-updated caption alone. The target *text* had nowhere else to go
   (an at-most target draws no bar at all, so the bar cannot carry it), and
   stacking it over the bar keeps the run of marks measured first — the run is
   bounded at seven by construction, the target sentence is not.
4. **A simple row's value column stays empty.** The mockup draws `✓` beside a
   filled entry mark; the mark already said it, and the row's one metadata
   cluster is worth more than the repetition.
5. **The strip marks the selected day and nothing else.** It is a trailing
   window that *ends* today, so the last column is today by construction — a
   badge saying so is the page repeating its own shape back at itself. (Coach's
   calendar keeps its today underline because a month grid has no such
   guarantee.)
6. **`SyncStatusDot` takes no `textStyle`/`labelColor`** — unlike coach's
   `SyncStatusIndicator`, the bare dot draws no text, so there is no Graphite
   typography to override. Its status colours already resolve from the system's
   dark-mode setting rather than from `LocalWellnessPalette`, so the
   composition-local trap is closed for it. The bare dot (not the labelled
   indicator) stays journal's, for the reason its KDoc gives: the header line is
   the crowded one.
7. **The shape legend names seven of the nine marks**, in row-reading order, each
   beside the word `a11yLabel` already gives it. The omitted two are the negated
   forms of marks it does name — the slashed hoop and the hollow diamond — and
   both modifiers are taught on dots earlier in the same line. Pinned by
   `JournalNotationTest`, so a tenth mark cannot appear without a word.
8. **Section-head measurement order is the reverse of coach's.** Coach measures
   the *label* first because its sibling is free-running prose; here the sibling
   is the rollup cluster, whose width is bounded by the category's own tracker
   count — so the cluster measures first and the category name is the one that
   wraps. The readable failure of the two.
9. **An empty entry judges as no entry** *(user decision, device pass — all
   three stacks)*. Unchecking a negative tracker left the day reading "broken"
   forever: the uncheck writes `{completed: false}` and **keeps the row** (there
   is no entry delete on the wire), and every judgment site keyed off row
   existence. That is faithful PWA parity and it is wrong — **retraction must
   work**. For judgment, an entry counts as present iff `completed == true` OR
   its value is non-null; an all-empty row judges exactly like no row. A written
   value keeps the row judged even with the box cleared — the value is the
   assertion, and blanking the field is its retraction. One predicate per stack
   (`countsAsLogged` / `entryCountsAsLogged` / `entry_present`, plus a SQL form
   for the server aggregates that never load a row). **Row *visibility* is
   untouched** and still keys off raw existence: an emptied row has to stay on
   screen or the entry could never be reached to clear it. Write paths,
   tombstones and the absent-vs-explicit-null storage distinction are all
   unchanged. The rule **composes with the today-open honesty rule**: emptying
   today's row makes `hasEntry` false again, so today's mark returns to the
   open dot rather than a judged one — the retraction restores "no verdict
   yet", which is the point. Second device pass: **the uncheck also retracts
   the checkbox's own seeded default** — the seed outliving the uncheck kept
   every quantifiable-with-default tracker (most medications and supplements)
   reading noted/broken, the exact symptom the rule was built to fix; a value
   that differs from the seed still survives the uncheck. Full rule in
   docs/ARCHITECTURE.md.
10. **The day lock retires; every day in the strip is editable** *(user
   decision, device pass — both clients)*. Past days locked on the device. The
   cause was the dirty-tracker rule doing exactly what it was designed to do —
   a pending tracker config edit locks all non-today days until it uploads —
   but with the phone offline that leaves a read-only week, and **fixing a
   forgotten log has to stay possible**. The whole rule goes: `isDayEditable`
   (both clients and the store query behind it), `DateCellState.enabled` and
   its lock glyph, `TrackerRowState.editable`, `JournalUiState.dayEditable`
   (the addendum's own addition, retired with what it explained),
   `JournalEyebrow.Locked`, the PWA's disabled rows and lock badge, and the
   now-orphaned `.date-lock` CSS and dirty-tracker DAO counts. Entry writes
   still mark only the entry dirty — that half fed the sync engine, not the
   lock, and is untouched.

## Behavior

- All new rendering decisions (plate assignment, legend order, eyebrow state,
  tally counts, calendar marks, ghost footer strings) derive in **pure state
  code** (`CoachUiState` + helpers), unit-tested — composables stay thin.
- Plate assignment is per-workout and positional: distinct exposure strings in
  order of first appearance across the day's exercises.
- Any new layout-wrapper composable is **`inline`** (Kover capturing-lambda
  trap; `CardSurface` precedent).
- No sync, store, gate, or hook logic changes. Existing behavioral tests stay
  green unmodified.

## Tests

- **`LogbookPaletteTest`** (`:core:ui`): programmatic contrast, both modes —
  ink ≥4.5:1 and inkSoft ≥4.5:1 on paper; inkFaint ≥2:1 (documented ghost
  exemption); every plate dot ≥3:1 on its paper (final token values pinned
  here); all tokens fully opaque.
- **Coach state pins** (implemented across `CoachNotationTest`,
  `CoachUiStateTest`, `WorkoutHooksTest` and `:core:data`'s
  `CoachProgressTest`): positional plate assignment (order of first
  appearance, repeat exposures share a dot, 5th+ → ink, legend order with
  **verbatim strings** — all uppercasing happens at render, one casing
  convention for eyebrow and legend alike, per `CoachNotation`'s KDoc),
  eyebrow state matrix (past/future/today-ready/in-progress × hook state —
  only FIRED/LOCKED or logged data count as started — × hook-time
  present/absent/unparseable), tally-mark counts per widget type, calendar
  ink-mark mapping, ghost-footer provenance strings keyed on whether any
  ghost is still showing (so a half-logged past table still names its faint
  values), start-time bookkeeping (adopted only with the state it arrived
  with; cleared on retry, undo, session change).
- Gate: `./gradlew testDebugUnitTest koverVerifyAggregated` (≥85);
  `./gradlew build assembleDebugAndroidTest` separately. Never module-scoped
  or `--tests`-filtered runs.
- Visual acceptance: APK on device per delivery phase (the design's actual
  gate), both themes.

## Dependencies

Three bundled OFL font families (resources only — no new libraries). Nothing
else new.

## Open Questions

1. Today-not-started eyebrow wording — `TODAY · READY TO LOG` proposed above.
2. ~~Exact adjusted plate token values (light yellow, dark blue)~~ — **resolved
   in Phase 1**: light `plateYellow` `#A87C1F` (3.61:1), dark `plateBlue`
   `#2F6BBC` (3.44:1). Measurement also forced two tokens the table had not
   flagged: `inkSoft` and `inkFaint` are now cut per mode (see Contrast
   resolutions). All four are pinned by `LogbookPaletteTest`.
