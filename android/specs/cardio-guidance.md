# Spec: Cardio Guidance — target-HR timelines and the live guide display

Status: **Designed 2026-08-21** — mockup `plans/cardio-guidance/cardio-guidance-mockup.html`
user-approved (Variant A, ink-only; the live-signal-color variant is the
kept-rejected option). This spec is the committed contract; implementation is
phased below and gated on it. Design decisions and their rationale:
`plans/cardio-guidance/plan.md` (local-only).

## Goal

A cardio plan can carry an **explicit target-HR timeline** — a time series of
segments, each with a duration and an HR constraint (floor, ceiling, or
range) — and the Android client turns it into a **live guidance instrument**
during a strap capture: a scrolling ~30 s window drawing the live HR trace
against the timeline's target band, with the upcoming segment's band visible
before it arrives. Guidance is an **optional instrument, never automation**:
capture works with zero app interaction; the guide is opened by hand from the
cardio exercise row and dismissed at will. Steady-state (Zone 2) sessions can
be **extended** mid-ride without touching the plan.

## API / Interface

### The wire: `segments` on a cardio exercise

A new optional field on planned exercises of type `duration` or `interval`,
following the coach wire's sparse-omit convention (absent means "no
timeline"; never null):

```json
"segments": [
  {"duration_sec": 300, "hr_min": 125, "hr_max": 140, "label": "warmup"},
  {"duration_sec": 180, "hr_min": 160, "hr_max": 175, "label": "hard"},
  {"duration_sec": 120, "hr_max": 150, "label": "easy"}
]
```

- `duration_sec`: required, integer ≥ 1. Booleans rejected (the
  `IntervalIntent` validation idiom).
- `hr_min` / `hr_max`: each optional, integer bpm ≥ 1; at least one must be
  present; when both, `hr_min <= hr_max`. Min-only = floor, max-only =
  ceiling, both = range. **Absolute bpm always** — nothing in the system
  resolves symbolic zones (survey-verified: no zone table, no stored HRmax);
  the authoring LLM computes bpm and may echo the zone name in `label`.
- `label`: optional display string (rendered mono-caps; keep short).
- The list is the timeline, in order, explicit and flat: a VO2 session's
  repeats are written out (warmup + hard/easy × N). **No rounds shorthand,
  no derivation from block `rounds`/`work_duration_sec`** — plans without
  `segments` have no timeline and render exactly as today (user decision:
  explicit only).
- Repeating structure stays authorable at the block level for display
  (`formatInterval` is untouched); `segments` is the guidance contract.

Storage: `planned_exercises.segments_json` TEXT (guarded `ALTER TABLE`
migration, mirrored nowhere else — plans have no archive). The journal's
`schedule_json` is the precedent: canonical column, emitted as a top-level
field by `assemble_plan` (sparse), validated in `validate_plan`'s
per-exercise loop (after the exposure block, the single point all three
write paths inherit), added to the MCP editors' `column_map` (unlisted keys
are silently dropped — this is the trap the survey named). Protocol home:
`docs/ARCHITECTURE.md` §Coach data model, updated in the protocol commit.

Both clients receive it for free (raw-blob storage); rendering:

- **PWA (static only — no live HR)**: one mono segments line in the cardio
  exercise body, e.g. `5:00 125–140 · 3:00 160–175 · 2:00 ≤150 · …`
  (formatter beside `formatInterval` in `coach/utils.js`; floor renders
  `≥N`, ceiling `≤N`, range `A–B`).
- **Android**: same static line in the expanded exercise detail (a
  `CoachNotation` formatter, tested), plus the guide affordance below.

### Android surfaces

- **The guide affordance**: a mono-caps underlined `GUIDE` on the cardio
  exercise row, present iff the exercise carries `segments` (or is a plain
  `duration` exercise — see Zone 2 note below). Manual open only; no
  auto-surface of any kind (user decision: the app cannot know when the
  strength block is done, and capture must never require the screen).
- **The guidance overlay**: full-canvas paper overlay mounted from
  `CoachContent` beside the existing sheets (no new nav route — the app has
  no non-tab destination and this does not create the first one). Dismiss
  (`✕` / back) returns to the day; reopening restores the same guidance
  state (state outlives the composable, keyed to the day + exercise).
- **A new rolling-sample window**: a Koin-owned ring buffer fed from the
  existing `heartRateData` collector inside `HrCaptureService`, published as
  its own flow beside `HrCaptureState` — never folded into it
  (`HrCaptureState` is scalar-and-conflatable by contract, and a conflating
  StateFlow would drop beats). Capacity ~64 samples — one point per
  notification, not per RR interval (a notification reports a single BPM
  however many intervals ride with it), so at the strap's real 1–2 Hz the
  window holds ≥ 30 s even at the fast end. Epoch-ms timestamps (legal
  arithmetic in HR land — data values, not opaque watermarks). Writing is
  licensed per session (`beginSession()` → recorder handle): teardown cancels
  the collector without joining it, so a superseded recorder's tail write
  must no-op rather than land in the next session's window.

## Behavior

### The guide lifecycle

1. Opening the overlay shows it **READY**: band and trace already live (the
   capture stream draws regardless), timeline at `0:00`, a `START` ink
   button in the footer.
2. `START` anchors the timeline clock at `now` (epoch ms). The eyebrow
   records the wall-clock start (`GUIDE · STARTED <time>`, CoachNotation).
3. Dismiss does not stop the clock; reopen restores position. The guide for
   a (date, exercise) runs at most once — `START` after a completed run
   offers a fresh run (previous run's state discarded; the log, not the
   guide, is the record).
4. The timeline ending is quiet: the strip fills, the context line reads
   `DONE`, the window keeps scrolling (the ride may continue past the plan;
   the log records reality). No sound, no haptics (out of scope this round).
5. The overlay renders **only while the capture is delivering**: when
   `hrCaptureDisplay` returns null (`!isRunning`) the guide shows the
   connect/start-capture state instead of a dead chart; when the stream is
   stale (`isStreamStale`) the trace gap draws honestly (no line across the
   silence) and the tone dot behavior is the existing liveness contract.

### The window (the motion contract — this system's first)

- ~30 s of history; the **now-line at 2/3** of the width, future to the
  right, so an upcoming segment's band approaches visibly (`+10 s` of
  lookahead).
- The window advances **once per second** in discrete steps — a paper
  instrument ticks, it does not glide. One `LaunchedEffect` clock keyed on
  the overlay's composition (the `ElapsedClock` precedent); **no infinite
  animation may run while the guide is not composed** (the `SyncStatusDot`
  frame-clock warning), and composition itself is gated on the capture
  running.
- Geometry is pure: an `HrTraceModel` (ring snapshot + timeline + clock →
  logical points, band rects, now-line, tick labels) in a covered package
  with JVM tests; the composable is a thin painter (the `PlotModel` →
  `drawPlot` seam). `ChartScrubState` is explicitly not used — no scrub, no
  anchors, no tooltip on a live trace.
- Y-domain: fixed per session from the timeline's HR extent padded to round
  ticks (no per-tick rescaling — a jumping axis is motion the rule forbids).

### Ink, and the header grammar

- The target band is the established idiom: faint `rule` wash bounded by
  `ruleStrong` hairlines, stepping at segment boundaries; a floor-only band
  is open-topped, a ceiling-only band open-bottomed. Trace, grid, ticks,
  numerals: ink tiers only. Mono numerals, non-negotiable.
- **Out-of-band is ink-only (Variant A, user-chosen)**: a beat outside the
  segment's band draws as an open dot, with the mono `!` beside the trace
  and beside the BPM readout while out. The live-signal palette keeps its
  closed two-consumer set — the only color on the screen is the existing
  `HrToneDot` (strap liveness, unchanged meaning).
- **Header grammar** (user feedback, binding): between title and chart,
  one type size per line —
  1. mono-caps context line: segment identity Start (`HARD · 3 OF 4`),
     next-segment or extension note End (`NEXT · EASY 2:00` /
     `EXTENDED · +10:00`);
  2. the two live numbers on **one shared baseline** at fixed Start/End
     edges: BPM (tone dot · mono numeral · unit) Start, time remaining End —
     mono, so they tick in place; no number's position may depend on a
     neighbor's text width;
  3. their small mono-caps labels beneath, same columns:
     `TARGET <floor/ceiling/range>` · `REMAINING`.
- Below the chart: the whole-session strip (segments as blocks — filled
  done, ink current, outlined ahead, dashed appended extension; cursor
  hairline) with a mono session summary line above it; footer: elapsed/total
  Start, `+ 5 MIN` ink-outline button End (Zone 2 only).

### Zone 2 and extension

- A plain `duration` exercise with a single-segment timeline (or a
  `duration` exercise whose author supplied one segment) is the steady-state
  case: one continuous band, context line `STEADY`.
- **`+ 5 MIN` appends five minutes to the live timeline only** — the
  overlay's state, never the plan (`target_duration_min` is untouched:
  raising it mid-session would un-complete a satisfied exercise — the
  completion trap). The strip draws appended time dashed; the context line's
  End slot reads `EXTENDED · +N:00` cumulatively. The log records actual
  `duration_min` as today; completion and Trends read logged reality.
- Whether a `duration` exercise WITHOUT segments gets a guide (floor/ceiling
  unknown → windowed trace with no band, timer + extend only): YES — the
  timer and extension are useful alone; the band simply doesn't draw. The
  `GUIDE` affordance therefore appears on every cardio (`duration` /
  `interval`) exercise; `interval` without segments gets no guide (its
  structure is prose — authoring segments is the upgrade path).

### Keep-screen-on (user requirement)

`FLAG_KEEP_SCREEN_ON` is held **exactly while the guidance overlay is
composed** — ready, running, or done — and released on dismiss. The capture
itself keeps its PARTIAL_WAKE_LOCK-only posture (screen-off capture stays
first-class). This deliberately amends the manifest's recorded
no-keepScreenOn rationale and `coach-heart-rate.md`'s echo of it: the
guidance display is exactly the "live tachogram" that rationale said did not
exist. Both comments update in the same commit that introduces the flag.

## Dependencies

- The strap capture stack (unchanged): `HrCaptureService`, the liveness
  watchdog, `hrCaptureDisplay`, `HrToneDot`. The guide is a consumer.
- `HrCaptureStore` actor discipline: the ring feed lives in the service's
  existing sample collector; it must not suspend on the actor and must not
  throw (a throw reads as a failed flush and retries forever).
- Plan sync: raw-blob storage on both clients means the field arrives
  everywhere at decode cost only. Coach goldens are **not shared** —
  `android/testdata/golden/coach/` and server fixtures update independently
  in the same commit.
- Authoring: `coach_plan_guide.md` gains the segments contract (bpm
  literals, floor/ceiling/range, labels; HR prose stays out of
  `guidance_note` when segments exist). Prompt-repo handoff if its plan
  authoring instructions duplicate the guide.

## Phases

- **P0** — this spec (docs gate).
- **P1 — the protocol commit (atomic: server + PWA + Android)**:
  `segments_json` column + validation + emit + MCP column_map +
  `coach_plan_guide.md`; PWA segments line; Android DTO field + static
  segments line (CoachNotation + tests); goldens both sides;
  `docs/ARCHITECTURE.md` §Coach; `public/version.json` bump rides the hook.
- **P2 — the instrument's data (Android, pure + tested)**: the rolling ring
  + published flow; capture `startedAtMs` published on
  `CaptureSession`/`HrCaptureState`; `HrTraceModel` + guidance state machine
  (READY/RUNNING/DONE, segment resolution, extension, out-of-band
  predicate) as pure notation with JVM tests.
- **P3 — the overlay (Android composables)**: guide affordance, overlay +
  header grammar, window painter, session strip, extend control,
  keep-screen-on + manifest/spec amendments, a11y spoken twins for every
  drawn state (the notation pattern).
- **P4 — device pass** (dev APK channel; both themes; the motion contract
  judged on hardware) + fix rounds.
- **P5 — batch push** after acceptance.

Per phase: Opus implements → Codex verifies read-only → main agent judges +
applies small fixes → commit daemonized (setsid + Monitor). Kover: the gate
is 85; `HrTraceModel`/notation are counted code and ship with tests;
composable wrappers taking composable lambdas are `inline`; never
module-scoped or `--tests`-filtered runs.

## Open Questions

- None blocking P0. Deferred deliberately: sound/haptic out-of-band cues;
  interval-without-segments guide; any PWA live surface (no live HR there);
  Garmin-side lap markers. Revisit only on user ask.
