# HR golden fixtures — the shared wire contract

**One directory, two readers.** The server's pytest suite
(`test/hr/integration/test_golden_payloads.py`) POSTs these files raw at the
real endpoints and compares responses separator-for-separator, and the Android
`HrGoldenFixtureTest` (`:core:data`) reads the *same files* off its unit-test
classpath — a Gradle `Sync` task in `android/core/data/build.gradle.kts` stages
this directory to `/golden/hr/<name>.json`. A protocol change edits one file
and both suites see it in the same commit; there is no second copy to keep in
step. (Before the 2026-08 monorepo merge these existed twice, held
byte-identical by convention — that pact is retired.)

The canonical payloads are also printed in `android/specs/hr-protocol.md`; the
wire contract's authoritative home is `docs/ARCHITECTURE.md`
("HR: Idempotent Batch Ingestion").

| File | What it is |
|---|---|
| `samples-batch-request.json` | `POST /api/hr/samples/batch` body — three RR intervals |
| `set-events-batch-request.json` | `POST /api/hr/set-events/batch` body — a set tick, its undo, a checklist toggle |
| `sessions-batch-request.json` | `POST /api/hr/sessions/batch` body — one open, workout-anchored session |
| `ingest-response.json` | the response shared by the two `INSERT OR IGNORE` endpoints |
| `upsert-response.json` | the sessions endpoint's distinct response shape |

The two request files are wrapped across lines for review. JSON whitespace
*between* tokens is insignificant and no value here contains any, so the Android
test compares against the fixture re-encoded compactly — key order, key names
and which optionals are present are all still pinned exactly.

One fixture serves both ingest endpoints because both canonical requests happen
to carry three rows. `GET /api/hr/status` has **no fixture** on purpose: its
shape is pinned by a decode test against the example in `ARCHITECTURE.md`.

## Provenance

Every value is synthetic and was authored in the protocol spec — no live
database was involved. Note the deliberately far-future calendar dates (2030):
this repo is public and its pre-commit personal-data guard scans staged
literals against the live health databases, where any plausible *past* date
can collide with a real lab, scan or workout date. **Keep it that way when
editing these payloads.**
