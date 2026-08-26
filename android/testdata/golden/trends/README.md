# Trends golden fixtures

Hand-authored payloads for the twelve `GET /api/trends/*` endpoints, pinned by
`TrendsDtoTest` in `:core:data`, which loads them off the unit-test classpath as
`/golden/trends/<name>.json`.

| File | What it pins |
|---|---|
| `overview.json` | the full overview: both tiles, focus rows of each `metric_kind`, a PR |
| `overview-empty.json` | every nullable tile field null, no PRs, no focus rows |
| `weight.json` / `weight-unavailable.json` | the series, and the `available: false` degradation |
| `strength-exercises.json` | `in_range: null` (the All range), an assisted entry, two entries sharing a display name |
| `strength-exercise.json` | sessions with and without RPE, an off-plan one |
| `strength-volume.json` | four slugs in one week (so `other` folding has something to fold), a partial week |
| `cardio.json` / `cardio-empty.json` | weekly Zone 2 + steady sessions, and the empty range |
| `journal-trackers.json` | a quantifiable/actionable tracker and a neutral one |
| `journal-tracker-actionable.json` | **no `weekly_usage` key**, `completed` as 1/0/null, a note-era string value, a one-sided target segment, a paused week with `rate: null` |
| `journal-tracker-neutral.json` | `weekly_usage` **present** — the API's only omitted key, in its present form |
| `health-recovery.json` / `-unavailable.json` | `hrv_band` nesting incl. a null `low_floor`, an all-null day |
| `health-sleep.json` | the ledger: `as_of`, a `tonight` with `strain_partial: true`, one `gap: true` night and four with the key **omitted** |
| `health-sleep-unavailable.json` | the degradation — `available: false`, no `as_of`, no `tonight`, empty `days` |
| `health-composition.json` / `-unavailable.json` | all eight nullable scan metrics, a scan missing bone data |
| `health-labs.json` / `-unavailable.json` | a chartable test, a text-only test, a `prefix`/`flag` test, a second panel |

Every `Double` field appears somewhere in an **integer** wire form and somewhere
in a **decimal** one (`"avg_hr": 132` beside `"avg_hr": 128.5`), because the
server computes these from sources that flip between the two and an `Int`
property would fail the whole decode the first time it happened.

## Provenance

**Every value in these files is invented.** Nothing was read from, copied out
of, or derived from any live or dev database — not a date, not a measurement,
not a name. Ids, slugs, tracker names, exercise names, lab panel names and lab
test names all carry the `fixture-`/`Fixture ` prefix, so a value that leaked in
from somewhere real would be visible on sight.

That prefix rule is the point. A fixture full of medical-sounding strings is not
automatically safe and a bare-looking number is not automatically invented;
provenance is what makes a fixture safe, and these have none to trace.

**Dates.** New fixtures use the far-future `2030-01-*` convention (CLAUDE.md), so
a date here can never collide with a real one. The files written before that
convention landed still carry `2026-07-*` dates; they are equally invented, and
they are left alone rather than rewritten, because every assertion in
`TrendsDtoTest` and `CardModelsTest` names them.

There is **no generator script** here, deliberately: the journal and coach
fixtures were captured from a seeded dev server and needed filtering afterwards,
which is exactly the step that can go wrong. These were written by hand against
the endpoint shapes in `specs/trends.md` and cross-checked against the server's
exact-JSON tests (`test/trends/test_*_endpoint*.py` in the PWA repo) for shape
only.
