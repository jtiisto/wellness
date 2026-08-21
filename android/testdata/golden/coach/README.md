# Coach golden fixtures

Real payloads from the **dev** server (`localhost:9001/wellness`, synthetic
data), captured 2026-08-07 by `generate.py`. Pinned by
`CoachGoldenFixtureTest` in `:core:data`, which loads them off the unit-test
classpath as `/golden/coach/<name>.json`.

Hand-written test JSON pins what we think the protocol is; these pin what it is.

| File | What it is |
|---|---|
| `sync-get-full.json` | `GET /sync` with no `last_sync_time` |
| `sync-get-incremental.json` | `GET /sync?last_sync_time=<stamp before the fixture writes>` |
| `sync-post-request.json` | the exact body of an accepted `POST /sync` |
| `sync-post-response.json` | that upload's response |
| `sync-post-response-rejected.json` | changed content re-sent with the now-stale bases |
| `sync-post-tombstone-roundtrip.json` | `{request, response}` for an accepted entry delete |
| `plans-version.json` | `GET /plans-version` |
| `plan-day.json` | one plan day — **hand-built, see below** |

`sync-post-tombstone-roundtrip.json` is the one file holding a request and its
response together: an accepted delete shows up as the *absence* of the key from
the merged day, which is unreadable without the request that asked for it.
| `workout-status-idle-both-available.json` | hooks configured, neither fired |
| `workout-status-start-fired.json` | start succeeded (`exit_code: 0`), with a payload |
| `workout-status-start-failed.json` | start ran and failed (`exit_code: 3`) |
| `workout-status-end-pending.json` | end fired, still running (`exit_code: null`) |
| `workout-status-none-available.json` | no hooks configured on the server |


The five `workout-status-*` files are an availability x result matrix. A null
`exit_code` is the server's marker for a hook still running, not "unknown", so
it needs a fixture of its own; the values are invented (`fixture-` prefixed)
because hook payloads are free-form.


## Provenance

Everything in these files is content this generator authored: `fixture-`
prefixed keys and notes, plus `extra_zone2` (the server's well-known ad-hoc
key), with invented round numbers. No dev-server row outside the two fixture
dates was written, modified, or deleted.

The dev server held **one** unrelated coach log, dated 2026-04-17 — outside the
60-day window, so no captured payload could reach it. `generate.py` filters
every committed payload to the two fixture dates anyway and then asserts the
result, because filtering only what you thought of is how a leak gets committed.

**`plan-day.json` is hand-built.** The dev server's 31 `workout_sessions` rows
are all dated Feb–Apr 2026, i.e. **outside the 60-day window**, so no pull can
return one: the full `GET /sync` filters them out by date, and the incremental
path that would reach them (`last_sync_time` older than 2026-04-18) currently
answers **HTTP 500** — see the note in the phase report. There was therefore no
plan to capture, and the spec makes a deterministic server-shaped synthetic
fixture mandatory in that case, covering every typed field including the
snake_case wire names, the free-form `"target_rpe":"6-7"`, and the cardio
`segments` timeline (whose three segments deliberately cover a range, a range,
and a bare ceiling — which bound is absent is the field's whole meaning).

`generate.py` asserts that premise — no plan inside the window — and fails
loudly if the dev server ever grows one, at which point capture a real plan only
after re-checking its provenance.

Fixtures are synthetic only. Never regenerate these against the production
server (ports 9000/9443), and never paste in real rows — a workout that sounds
plausible is not the test, provenance is.

## Regenerating

```
python3 testdata/golden/coach/generate.py testdata/golden/coach
```

Not idempotent: the first upload is an insert against empty dates, so the two
fixture dates have to carry no log for a clean run (the script asserts this).
Re-check the diff by eye before committing — the filter only knows about dates
and the `fixture-` key prefix.
