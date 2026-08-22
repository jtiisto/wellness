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
- `role` *(added 2026-08-22, polish round)*: optional, one of `"warmup"`,
  `"work"`, `"cooldown"` — a closed enum, any other value rejected loudly
  (the unknown-key stance applied to values). **Absent means `work`**, so
  every pre-role plan keeps its exact behavior. Role is semantics, not
  display: neither client's static segments line changes. It drives the
  work-only auto-fill spans, the extend rules below, and (in the analysis
  round) per-segment metrics; the guide-events `timeline_json` snapshot
  carries it automatically, being the wire shape — with one deliberate
  asymmetry: an explicitly-authored `"work"` records in the snapshot as
  absence (same meaning, and it keeps a role-less ride's record
  byte-identical to pre-role ones), while the server stores what was
  authored.
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

- 30 s of history; the **now-line at exactly 2/3** of the width, future to
  the right, so an upcoming segment's band approaches visibly. Those two
  facts force the lookahead: 15 s. The approved mockup's `+10 s` marker is a
  **tick label inside the lookahead**, not its extent (its own geometry says
  so: now-line at 250/360 ≈ 2/3, the `+10 s` text at x≈330 of 360 — inside
  the plot, an implementation note P2b's exactness surfaced).
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

  *(Clarified 2026-08-21, P3b: "one type size per line" binds the line
  **roles** — context, numerals, labels — against the v1 jumble of mixed
  display/mono sizes sharing a line. The numeral line's subordinate **marks**
  ride its shared baseline at their own smaller sizes, exactly as the
  approved mockup draws them — hero 42px, unit 12px, and the bang likewise
  subordinate. A mark is not a number; the rule's teeth are the fixed
  columns and the shared baseline.)*
- Below the chart: the whole-session strip (segments as blocks — filled
  done, ink current, outlined ahead, dashed appended extension; cursor
  hairline) with a mono session summary line above it; footer: elapsed/total
  Start, `+ 5 MIN` ink-outline button End (Zone 2 only).

### The instrument, as built (P3b resolutions)

What building the header, the window and the strip settled that the section
above left open.

- **The readout is the newest *drawn* beat**, taken from the window model rather
  than from `HrCaptureState.bpm`, and it blanks to the chip's own `—` after
  three seconds of silence (`TRACE_GAP_THRESHOLD_MS`, the same threshold that
  breaks the trace). One rule, so the number, the open dot, the mono bang and
  the spoken verdict are four renderings of one beat instead of four
  computations. The stricter-than-the-watchdog blanking is deliberate: the
  capture stack's liveness timer asks whether the *link* died, while a number
  standing over a visible gap would be contradicted by the chart underneath it.
  The tone dot beside it still reports the link, unchanged.
- **`+ 5 MIN` is offered only after `START`** (`canOfferExtension`: not
  `READY`, plus the spec's steady-state shape). Anchoring a run discards its
  extension by design, so minutes added before `START` would be silently thrown
  away by the very next tap; `DONE` keeps the control, which is how a ride
  carries on past its plan. It sits in the **footer**, outside the capture gate,
  with the elapsed line it is a statement about — a rider whose belt slipped can
  still add five minutes.
- **Only the approaching band is captioned.** The band being held is named by
  the header's `TARGET` slot, and captioning it too would be the same
  instruction twice at two type sizes.
- **Marks are sparse**: an open dot for every out-of-band beat, one filled dot
  on the newest in-band beat, and the bang beside the newest beat while it is
  out. A dot per sample is a bead curtain to read a line through.
- **The strip and its caption are one semantics node**, carrying the caption's
  spoken twin: the strip is a picture of that sentence, and the only fact it
  adds — where the cursor stands — is already spoken by the footer.
- The header grammar, the window and the strip are all inside the **capture
  gate** (P3a's slot): every one of them is a reading of a stream, and the
  notice that replaces them says the timeline below keeps running.

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
- **Extend, revised by `role` (2026-08-22, polish round — supersedes the
  single-segment reading of "steady-state")**: eligible iff the timeline has
  **exactly one work-role segment** — warmup and cooldown around it welcome,
  which is the warmup/Z2/cooldown ride the size-1 rule wrongly excluded; a
  many-work-segment timeline (VO2) stays ineligible; a segmentless
  `duration` stays eligible. `+ 5 MIN` lengthens **the work segment**, not
  the last: the cooldown shifts later intact, the band stretches the work
  band, the strip's dashed appended block draws at the work segment's end
  (mid-strip when a cooldown follows), DONE still derives from the total.
  Role-less timelines are all-work by the absence rule, so their behavior
  is unchanged to the byte. The guide-events derivation follows the same
  rule — which segment absorbs each extend is read from the snapshot's
  roles.
- **The affordance is date-gated (2026-08-22, polish round)**: `GUIDE`
  shows and opens only when the selected day is today (the state's
  existing today/editability fact; both the affordance and the open action
  gate — defense in depth). The gate is on the OPEN only: an already-open
  guide survives the midnight rollover — a ride started at 23:50 must not
  slam shut at 00:00.

### Auto-fill from a guided ride (added 2026-08-22, polish round)

When the guide is dismissed after a run that **reached DONE** and a capture
session existed for it, the cardio entry's three manual fields fill
themselves — and only themselves, and only if empty:

- **`duration_min` = the guided timeline's total including extends**, never
  the elapsed clock: idling past DONE before dismissing must not inflate
  the log, and a run dismissed before DONE fills nothing (an early bail is
  the rider's to log, as today).
- **Average HR and max HR are both computed over the work-role spans
  only** — warmup and cooldown excluded, easy/recovery segments between
  efforts included (they are work-role; the protocol's rests are part of
  the work). The beats come from the phone's own Room store (the capture
  persists samples locally; no server round-trip), sliced by the run's
  absolute schedule: anchor + durations + extends, extends absorbed by the
  work segment per the rule above.
- Fills go through the **normal cardio-entry commit path** — the same one
  manual typing uses — so completion derivation, sync, and every
  downstream reader see an ordinary entry. A field the rider already
  typed is never overwritten.
- Unguided rides, strapless rides, DONE-less dismissals: nothing fills,
  manual entry exactly as before.

*(As built, 2026-08-22 — what implementing the section settled.)*

- **The presence test is beats inside the run's window**, not a session row:
  a strap that dropped out and reconnected mid-ride is two sessions and one
  ride, so the beats are read by time range (`HrBeatReader` over
  `hr_samples`, quarantined rows excluded) and both halves count. No beats in
  the window at all is the strapless case and fills nothing.
- **Beats present but none in the work spans** — the belt that came off after
  the warmup — fills `duration_min` alone and leaves both heart rates empty.
  The duration is a statement about the timeline, not about the beats.
- `duration_min` is the total **rounded to the nearest minute**, floor of one.
- The fill is **one write** through `editEntry` (the store transaction
  `commitCardioField` itself uses), with the per-field emptiness test made
  *inside* the transaction against the entry as stored — a check made outside
  it could overwrite a number typed while the beats were being read. Absent and
  explicit null are both empty; anything else, including a typed `0`, is kept.
- The entry gate still governs, which has one visible consequence: a ride
  dismissed **after midnight** fills nothing, because the day it belongs to is
  read-only by then — the rider could not type into it either.
- A `role` value no client knows (only reachable by hand-editing the database,
  since the server rejects it) **degrades to `work`** on the client rather than
  failing the decode: the display-layer leniency the zero-bound rule already
  established.

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

## Guide events in the HR record (added 2026-08-21, post-ship round)

A spec-time omission, owned as such: the guide's user actions belong in the
HR record the way set-completion checkboxes already do, so analysis can align
the guided timeline with the beats. User decisions (2026-08-21/22, settled
after two iterations): boundaries **derived, not materialized**; **no stop
event**; recorded **only while a capture session is running**, session-tied.
The rationale is the system's two-case model (user, 2026-08-22): either the
wellness system is the sole authority for a ride — strap capture AND guide
together, everything keyed by the session id — or the ride lives entirely on
the Garmin side and wellness records only the completion checkbox, exactly
as for strength. A strapless guided ride is not a case this system records
(that is a watch ride), so a guide event without a session would be dead
weight no session-keyed analysis could reach.

- **What is recorded**: the guide's two user actions only — `start` (the
  anchor instant) and `extend` (+300 s each). Every segment boundary is
  derivable offline from the anchor plus cumulative durations shifted by the
  timestamped extends — and derivation, unlike runtime boundary events, has
  no hole when the overlay is dismissed mid-ride (nothing ticks while it is
  not composed). DONE is a reading, not an event. A fresh run appends a
  second `start`; all events are retained and alignment policy (latest
  start wins) belongs to the analysis side.
- **Storage**: `guide_events` in hr.db, sibling of `set_events` on the same
  rails — client-minted `event_id` PK, `INSERT OR IGNORE` idempotency,
  Pydantic-validated so a violation is the 422 the client bisects on.
  Columns: `date` (local `YYYY-MM-DD`), `exercise_key`, `action`
  (`start`/`extend`), `client_timestamp_ms` (epoch ms — the same instant the
  overlay anchors or extends at), `session_id` (NOT NULL: the session is
  the analysis key, and its presence is the recording precondition),
  `extension_sec` (extend only), `timeline_json` (start only: the segments
  as guided at anchor time — hr.db stays self-contained; nothing on the
  analysis side reads coach.db), `received_at`.
- **Wire**: `POST /api/hr/guide-events/batch`, mirroring the set-events
  batch shape. Android: Room entity + DAO (schema version bump, exported
  schema committed), emitted from the ViewModel's start/extend actions iff
  the capture publishes a session id, uploaded on the existing HR sync
  cadence.
- **Analysis round, SHIPPED 2026-08-22**: `hr_analysis` is
  **session-id-driven** (user's simplified model). Retrieval is by session
  id always — the time-window (`start_ms`/`end_ms`) surface is retired
  from the CLI and MCP, and historical no-session-id beats stay stored but
  tool-unreachable. A session with guide events analyzes against the
  recorded timeline (latest `start` wins by timestamp then event id;
  snapshot + timestamped extends absorbed by the work segment; per-segment
  time-in-band against the recorded bounds, duration-weighted over the
  whole ride then partitioned at boundaries — a straddling beat's span is
  clipped, discrete facts stay with the beat's own segment); a session
  without them keeps supplied-`IntervalIntent` structure. Guided reports
  carry `structure` and omit `vo2`; ambiguous sessions return the
  guided-exercise list and ask for `exercise_key`. Garmin times
  participate in nothing — the no-capture ride lives entirely outside this
  system (watch + garmy), where wellness records only the completion
  checkbox. Contract detail: `docs/ARCHITECTURE.md` §HR.

## Open Questions

- None blocking P0. Deferred deliberately: sound/haptic out-of-band cues;
  interval-without-segments guide; any PWA live surface (no live HR there);
  Garmin-side lap markers. Revisit only on user ask.
