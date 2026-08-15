# HR golden fixtures

**Copied byte-for-byte from the server repo's `test/hr/golden/`** — this is the
one fixture set that exists twice on purpose. The HR protocol was drafted
cross-repo, and the two halves are kept honest by holding the same bytes: the
server's pytest suite POSTs these files raw at the real endpoints and compares
responses separator-for-separator, while `HrGoldenFixtureTest` in `:core:data`
asserts the client reproduces the same requests off the unit-test classpath as
`/golden/hr/<name>.json`. A protocol change regenerates both copies in one
change set — never one alone.

The canonical payloads are also printed in `specs/hr-protocol.md`; the wire
contract's authoritative home is the server repo's `docs/ARCHITECTURE.md`
("HR: Idempotent Batch Ingestion").

| File | What it is |
|---|---|
| `samples-batch-request.json` | `POST /api/hr/samples/batch` body — three RR intervals |
| `set-events-batch-request.json` | `POST /api/hr/set-events/batch` body — a set tick, its undo, a checklist toggle |
| `sessions-batch-request.json` | `POST /api/hr/sessions/batch` body — one open, workout-anchored session |
| `ingest-response.json` | the response shared by the two `INSERT OR IGNORE` endpoints |
| `upsert-response.json` | the sessions endpoint's distinct response shape |

The two request files are wrapped across lines for review. JSON whitespace
*between* tokens is insignificant and no value here contains any, so the tests
compare against the fixture re-encoded compactly — key order, key names and
which optionals are present are all still pinned exactly.

One fixture serves both ingest endpoints because both canonical requests happen
to carry three rows. `GET /api/hr/status` has **no fixture**: the server repo
has none either, and inventing one here would break the byte-identical pact that
is the whole point of this directory. Its shape is pinned by a decode test
against the example in `ARCHITECTURE.md` instead.

## Provenance

Every value is synthetic and was authored in the protocol spec — no live
database was involved, here or on the server side. Note the deliberately
far-future calendar dates (2030): the server repo is public and its pre-commit
personal-data guard scans staged literals against the live health databases,
where any plausible *past* date can collide with a real lab, scan or workout
date. **Keep it that way when editing these payloads.**
