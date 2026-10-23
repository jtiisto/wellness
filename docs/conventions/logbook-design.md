# Logbook visual design

The design language of the native apps that adopt it (wellness, share). The governing
metaphor is a paper logbook: one flat surface, ink, tabular numerals, notation in
place of decoration, and colour kept for a single meaning. Imported by every project
whose native UI draws in Logbook. Each project's spec names its colour meaning and
its own components. Wellness's `android/specs/logbook-design-system.md` is the
reference implementation and holds the measurements quoted below.

## Principles
1. **One surface, no nested cards.** The screen background is the only surface.
   Grouping is whitespace and hairline rules: never card-in-card containers, tinted
   panels or coloured left rails.
2. **Colour means one thing per app.** The only chromatic UI elements are small dots
   encoding that one meaning (wellness: exercise tier; share: category). Colour is
   never a fill, a text colour or a status. A screen showing coloured dots also shows
   what they mean (a legend, or the label beside each dot).
3. **Numbers are mono, words are sans.** Every numeral (sizes, dates, counts, times,
   weights) is set in IBM Plex Mono; prose and names in Inter; display headings in
   Barlow Condensed caps.
4. **Grouping is drawn, not named**, and **state is a mark, not a badge**: brackets,
   tally marks, and filled or outlined glyphs, instead of labelled pills or `2/3`
   counters.
5. **At most one metadata cluster per collapsed row.** Everything else lives in the
   expanded state.
6. **Judgment stays ink.** Good, bad, done and failed are shown by shape and fill
   (open dot, strike, `!`), never by red or green.

## Tokens

| Token | Light | Dark | Use |
|---|---|---|---|
| `paper` | `#FBFAF7` | `#141517` | the only surface |
| `ink` | `#17191B` | `#EDECE7` | primary text, marks, strong rules |
| `inkSoft` | `#6A6D73` | `#7D8288` | secondary text, meta labels |
| `inkFaint` | `#A6A9AD` | `#55585D` | ghost values, empty states, table heads, unfilled marks |
| `rule` | `#E7E5DE` | `#2A2C2F` | hairlines between rows |
| `ruleStrong` | `#C7C5BB` | `#3A3C40` | group boundaries, popup borders |
| `plateRed` | `#B92D3A` | `#B92D3A` | colour-meaning dot, 1st |
| `plateBlue` | `#2A5FA8` | `#2F6BBC` | 2nd |
| `plateYellow` | `#A87C1F` | `#C99A2A` | 3rd |
| `plateGreen` | `#2E7D4F` | `#2E7D4F` | 4th; a 5th or later value renders as an ink dot |

- Both modes follow the system setting. Every value is measured, not eyeballed: ink
  and inkSoft ≥ 4.5:1 on paper, plate dots ≥ 3:1 (non-text UI), inkFaint ≥ 2:1 (a
  documented exemption: ghost values must recede), with the ramp ink > inkSoft >
  inkFaint in both modes. A palette contrast test pins all of it; a project that
  changes a token re-measures it and updates the test.
- No success, warning or error tokens. The one exception is **live signal**: a quiet
  dot for transient device truth (sync status, a sensor reading), never a fill or a
  text colour.
- The Material 3 `ColorScheme` and `Typography` adapters fill every slot from Logbook
  tokens, so baseline purple is unreachable. No dynamic colour.

## Type

Bundled OFL fonts, with their licence files beside them: Barlow Condensed SemiBold;
Inter Regular, Medium, SemiBold and Italic; IBM Plex Mono Regular, Medium and Italic.

| Role | Face | Size | Use |
|---|---|---|---|
| `display` | Barlow Condensed 600 caps | 34sp, tight | screen titles |
| `section` | Barlow Condensed 600 caps | 17sp, +6% tracking | section labels |
| `name` | Inter 500 | 14.5sp | item names |
| `body` | Inter 400 | 12.5–13sp | prose; italic for marginalia and empty states |
| `data` | IBM Plex Mono 400 | 12.5sp | table cells, values |
| `meta` | IBM Plex Mono 400/500 | 11.5sp | meta lines (values 500 ink, labels 400 inkSoft) |
| `eyebrow` | IBM Plex Mono 400 caps | 10.5sp, +12–14% | eyebrows, legends, hints, tab labels |
| `tableHeader` | IBM Plex Mono 500 caps | 10sp, +12% | column heads |

A size the ramp doesn't name is a `copy()` at the call site, not a new role.

## Space, shape, interaction
- 4dp grid; row padding 14dp vertical, 18dp around group boundaries.
- Hairlines 1dp `rule`, group boundaries 1dp `ruleStrong`, section underline 1.5dp ink.
- Corner radius ≤ 2dp. No shadows and no tonal elevation inside a screen.
- Touch targets ≥ 48dp even where the drawn mark is smaller (visual vs. interactive
  size).
- Press indication is a low-alpha ink wash in place of the M3 ripple.
- Icons are a trimmed set of outlined glyphs in ink; decorative glyphs become text
  labels; never coloured emoji.

## Shell
- **Bottom nav**: paper, a 1dp `rule` top hairline, outlined ink glyphs; the selected
  item is full ink with an ink underline bar. The stock `NavigationBar` is kept for
  accessibility, with its colours overridden.
- **Snackbar**: inverted (ink surface, paper text), radius ≤ 2dp.
- **Empty and error states**: display heading, body prose, an ink outline button.
- **Viewer and WebView content** (Markdown, code, reports) use the same tokens and
  font files. Code highlighting uses ink weights and inkSoft only, never syntax
  colours.

## Motion
- Only transitions the user asked for (expand, sheet) animate.
- A live display advances in discrete one-second steps from one clock per surface,
  with no interpolation; the clock lives only while the surface is composed.
- A live chart's axis never re-fits mid-session.

## Launcher icon
- Light `paper` background, the mark in `ink`, at most one plate-colour accent, with
  everything inside the 66dp safe zone of the 108dp adaptive canvas.
- A monochrome layer for themed icons: the ink mark only, no accent.
- When the app has a PWA, one SVG source generates its icons too (regular and
  maskable).
