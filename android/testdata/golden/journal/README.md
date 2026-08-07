# Journal golden fixtures

Real payloads from the **dev** server (`localhost:9001/wellness`, synthetic
data), captured 2026-08-07 by `generate.py`. Pinned by
`JournalGoldenFixtureTest` in `:core:data`, which loads them off the unit-test
classpath as `/golden/journal/<name>.json`.

Hand-written test JSON pins what we think the protocol is; these pin what it is.

| File | What it is |
|---|---|
| `delta-full.json` | `GET /sync/delta` with no `since` |
| `delta-incremental.json` | `GET /sync/delta?since=<stamp before the fixture rows>` |
| `tracker-legacy.json` | one delta tracker still carrying `frequency`/`weeklyDay` |
| `update-request.json` | the exact body of an accepted `POST /sync/update` |
| `update-response-accepted.json` | that upload's response |
| `update-response-rejected.json` | the same body replayed, so every base token is stale |

## Provenance

Everything in these files is `fixture-`-prefixed content created for this
purpose. **`delta-full.json` is filtered**: the dev server holds 18 unrelated
tracker definitions whose names read as a real person's supplement and
medication regimen, so they are stripped from the committed copy. Nothing else
was altered, and no dev-server row outside the `fixture-` prefix was written,
modified, or deleted.

Fixtures are synthetic only. Never regenerate these against the production
server (ports 9000/9443), and never paste in real rows — a tracker name that
sounds medical is not the test, provenance is.

## Regenerating

```
python3 testdata/golden/journal/generate.py testdata/golden/journal
```

Not idempotent: the first upload it makes is an insert-if-absent, so the
`fixture-` rows have to be absent from the dev server for a clean run. The
script asserts that the incremental delta contains only `fixture-` ids, and
filters the full delta; re-check the diff by eye before committing, because
that filter only knows about the id prefix.
