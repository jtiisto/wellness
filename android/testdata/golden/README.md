# Golden fixtures

Contract fixtures for the four modules, loaded off the `:core:data` unit-test
classpath as `/golden/<module>/<name>.json`. Each module's README records its
own provenance; every value is either captured from the **dev** server against
synthetic `fixture-`-prefixed rows or invented outright. Nothing here is ever
copied from a live database.

## Shared

| File | What it is |
|---|---|
| `error-envelope.json` | FastAPI's error shape, `{"detail": "..."}` |

`error-envelope.json` is shared because all four modules receive it and only one
does anything with it: Analysis extracts `detail` into `AnalysisHttpException`,
while journal, coach and trends only propagate the non-2xx. One fixture asserted
two different ways is the point — it pins that the difference is deliberate.

## What does *not* get a fixture

Endpoints whose success body the client **ignores** get no success-body fixture:
`POST`/`DELETE /workout/{id}/start|end` and the analysis report delete. A fixture
for a body nothing decodes would pin a contract we do not depend on and would
have to be maintained anyway. Those are tested by asserting method, path and a
2xx instead. Their *error* fixtures stay, because failures are handled.
