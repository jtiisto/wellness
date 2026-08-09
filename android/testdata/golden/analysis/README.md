# Analysis golden fixtures

Hand-authored payloads for the six `/api/analysis/*` endpoints, pinned by
`AnalysisDtoTest` in `:core:data` and by `AnalysisMarkdownTest` in
`:feature:analysis`, both of which load them off the unit-test classpath as
`/golden/analysis/<name>.json`.

| File | What it pins |
|---|---|
| `queries.json` | the **omitted**-optional shape: one entry with `icon` and no `accepts_location`, one with both, one with neither |
| `report-completed.json` | a terminal row with a body exercising the whole markdown construct set (see below) |
| `report-pending.json` | the non-terminal row: `response_markdown`, `completed_at` and `error_message` all **JSON null**, not absent |
| `report-failed.json` | the other terminal status, carrying `error_message` |
| `report-unknown-status.json` | a status string this build has never heard of (`"queued"`), which must decode to `UNKNOWN` rather than fail |
| `reports-history.json` | the six-column projection, newest first, with `completed_at: null` on the non-terminal row |
| `pending.json` | `GET /reports/pending` — full rows, not the projection |
| `submit-response.json` | the 201 envelope |
| `empty-list.json` | the empty array every list endpoint can return |

Report rows carry `prompt_sent` and `cli_metadata` on purpose: they are the
fields the client deliberately does **not** model, and their presence is what
proves `ignoreUnknownKeys` is carrying them past the decoder.

Note the asymmetry these files encode. `/queries` **omits** absent optional keys
(the server builds each entry with a dict comprehension); every report shape
sends **JSON null** instead, because those come straight off `SELECT *`. Both
have to work, and a fixture that only exercised one of them would let the other
regress silently.

## The markdown body

`report-completed.json` is also the markdown pipeline's fixture. Its
`response_markdown` contains headings at three levels, bold, emphasis, inline
code, a soft break, a link, a GFM table with a bold cell and a code cell, a
nested bullet list, an ordered list, a block quote, a fenced code block, a
thematic break, an image, both orders of the status-collapse pair
(`🟢 OK` and `RED 🔴`), and **a raw-HTML attack string in both block and inline
position** (`<img src=x onerror=…>` / `<b onmouseover=…>`).

That last one is the point of the file. The renderer has no HTML path at all —
raw HTML becomes inert `Text` carrying its own source lexeme — and this fixture
is what proves a stored report cannot smuggle markup into the render tree.

## Provenance

**Every value in these files is invented.** Nothing was read from, copied out
of, or derived from any live or dev database — not a date, not an id, not a
label, and above all not a report body. The local dev database contains one real
report; none of it appears here or anywhere else in this repository.

Ids, labels and query ids all carry the `fixture-`/`Fixture ` prefix so a value
that leaked in from somewhere real would be visible on sight. Timestamps are all
in 2031, which no real row can be. Shapes were cross-checked against the
server's own suites (`test/analysis/*` in the PWA repo) and the row columns in
`src/modules/analysis_db.py` — for **shape only**, never for content.

There is deliberately no generator script: a script that reads a database to
produce a fixture is exactly the step that leaks.
