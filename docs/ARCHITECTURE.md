# Architecture

## Overview

Wellness is a modular, self-hosted health application with three independent modules sharing a unified backend and frontend shell. Each module owns its own SQLite database, API router, sync protocol, and frontend state.

```
┌─────────────────────────────────────────────────┐
│                  PWA Frontend                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
│  │ Journal  │  │  Coach   │  │   Analysis   │  │
│  │  Module  │  │  Module  │  │    Module     │  │
│  └────┬─────┘  └────┬─────┘  └──────┬───────┘  │
│       │              │               │          │
│  LocalForage    LocalForage     Fetch only      │
│  (IndexedDB)   (IndexedDB)    (no local state) │
└───────┼──────────────┼───────────────┼──────────┘
        │              │               │
   HTTP Sync      HTTP Sync      HTTP Submit
        │              │               │
┌───────┼──────────────┼───────────────┼──────────┐
│       ▼              ▼               ▼          │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
│  │ /api/    │  │ /api/    │  │ /api/        │  │
│  │ journal  │  │ coach    │  │ analysis     │  │
│  └────┬─────┘  └────┬─────┘  └──────┬───────┘  │
│       │              │               │          │
│  journal.db     coach.db      analysis.db       │
│                                      │          │
│                              Claude Code CLI    │
│                               ┌──────┴───────┐  │
│                               │  MCP Tools   │  │
│                               │ journal coach │  │
│                               │    garmin     │  │
│                               └──────────────┘  │
│              FastAPI + Uvicorn                   │
└─────────────────────────────────────────────────┘
```

## Design Principles

**Module isolation.** Each module has its own database, API prefix, frontend state, and sync logic. Modules share only the FastAPI process, static file serving, and frontend shell (tab navigation). A module can be disabled without affecting others via `HEALTH_DISABLED_MODULES`.

**Offline-first.** The Journal and Coach frontends persist all data locally in IndexedDB via LocalForage. The app works fully offline; sync happens automatically when the server is reachable.

**No build step.** The frontend uses Preact with HTM (tagged template literals) instead of JSX. ES6 modules are loaded directly by the browser with no bundler, transpiler, or build pipeline.

**No ORM.** All database access uses raw SQLite3 with parameterized queries and context managers. Schema migrations are handled defensively with `CREATE TABLE IF NOT EXISTS` and `ALTER TABLE ADD COLUMN` wrapped in try/except.

**AI as a service, not a dependency.** The Analysis module is the only component that depends on external AI. It invokes Claude Code CLI as a subprocess, meaning the rest of the app functions without any AI infrastructure.

## Sync

Both Journal and Coach modules use a shared `SyncScheduler` class (`public/js/shared/sync-scheduler.js`) that handles automatic synchronization. Each module creates its own scheduler instance with module-specific sync functions and state getters.

### SyncScheduler

The scheduler triggers sync automatically based on:

- **Edit debounce** — When local data changes, sync is scheduled after a 2.5s debounce window to batch rapid edits
- **Periodic polling** — Every 30s, checks for server-side changes (Coach polls `/plans-version`; Journal syncs if dirty)
- **Network restore** — Syncs immediately when the browser comes back online
- **Page visibility** — Re-syncs when the app regains focus after being backgrounded

Error handling uses exponential backoff (5s base, 120s max). Network errors retry silently; server errors show toast notifications. The scheduler pauses when the app is backgrounded or offline.

### Journal: Version-Based Conflict Detection

The Journal tracks fine-grained daily data (supplements taken, habits checked) across multiple devices, where conflicting edits to the same record are possible and must be surfaced to the user.

**Protocol:**

1. **Client registers** with a unique ID (`POST /sync/register`)
2. **Initial sync** fetches all tracker configs and 7 days of entries (`GET /sync/full`)
3. **Subsequent syncs** fetch only records modified since last sync (`GET /sync/delta?since=<timestamp>&client_id=<id>`)
4. **Client uploads** changed records with `_baseVersion` indicating the version the edit was based on (`POST /sync/update`)
5. **Conflict detection:** If `server_version > client_base_version`, the record is returned as a conflict instead of being applied
6. **Auto-merge:** Non-overlapping field changes are merged automatically (e.g., local value change + server completed change)
7. **Conflict resolution:** Overlapping changes require user choice via the UI (`POST /sync/resolve-conflict`)

**Sync status:** green (clean), red (dirty data), yellow (unresolved conflicts), gray (never synced).

**Key design choices:**
- Per-record versioning (each tracker and each entry has its own integer version)
- Conflicts are explicit - the user must choose which version to keep (unless auto-mergeable)
- Soft deletes via `_deleted` flag preserve version history
- Conflict audit trail stored in `sync_conflicts` table

**Data model:**
```
trackers (id, name, category, type, meta_json, version, last_modified_by, last_modified_at, deleted)
entries  (date, tracker_id, value, completed, version, last_modified_by, last_modified_at)
         PRIMARY KEY (date, tracker_id)
```

### Coach: Last-Write-Wins

The Coach module handles workout plans (authored server-side, typically by AI) and workout logs (written by the user during a session). Plans flow one-way from server to client. Logs flow from client to server with last-write-wins semantics.

**Protocol:**

1. **Client registers** (`POST /register`)
2. **Sync pull** fetches plans (all or since last sync) and logs (30 days or since last sync) (`GET /sync?client_id=<id>&last_sync_time=<timestamp>`)
3. **Log upload** sends completed workout logs; the server replaces any existing log for that date (`POST /sync`)
4. **Plan change detection** via `GET /plans-version`, which returns `MAX(last_modified)` from `workout_sessions`. The scheduler polls this endpoint every 30 seconds, triggering a full sync when the version changes.

**Sync status:** green (clean), red (dirty logs), gray (offline).

**Key design choices:**
- Plans are read-only from the client's perspective (created via MCP or direct DB access)
- Logs use last-write-wins because only one device logs a workout at a time
- Relational plan structure: session -> blocks -> exercises -> checklist items
- Relational log structure: session log -> exercise logs -> set logs
- Canonical exercise slugs link planned exercises to logged exercises and the exercise registry

**Data model (plans):**
```
workout_sessions   (id, date, day_name, location, phase, duration_min)
session_blocks     (id, session_id, position, block_type, title)
planned_exercises  (id, session_id, block_id, exercise_key, name, exercise_type, targets...)
checklist_items    (id, exercise_id, position, item_text)
```

**Data model (logs):**
```
workout_session_logs  (id, session_id, date, pain_discomfort, general_notes)
exercise_logs         (id, session_log_id, exercise_id, exercise_key, completed, user_note)
set_logs              (id, exercise_log_id, set_num, weight, reps, rpe, unit, duration_sec)
checklist_log_items   (id, exercise_log_id, item_text)
```

### Force Sync

Both modules support force sync (accessible from the settings menu) which performs a full bidirectional reconciliation by timestamp comparison. Force sync reports per-module counts of uploaded and accepted records. The Journal module accepts server versions on conflict during force sync rather than prompting the user.

### Analysis: No Sync

The Analysis module has no client-side state and no sync protocol. The frontend submits a query, polls for completion, and displays the result. All state lives on the server.

**Flow:**
1. User selects a pre-built query from the UI
2. Server creates a report record (status: pending) and launches Claude Code CLI as an async subprocess
3. Frontend polls `GET /reports/pending` until the report completes
4. Claude Code CLI runs with MCP tool access, generating a markdown report
5. Report is stored in `analysis.db` and displayed in the UI

## Shared Frontend Utilities

The `public/js/shared/` directory contains cross-module utilities:

- **`sync-scheduler.js`** — `SyncScheduler` class used by both Journal and Coach stores (see above)
- **`settings.js`** — Settings modal with debug log download, data export, and force sync
- **`debug-log.js`** — Fire-and-forget logging to IndexedDB (max 500 entries, 1-hour TTL) for sync troubleshooting
- **`data-export.js`** — Exports all LocalForage data (journal, coach, app state) as a timestamped JSON file
- **`force-sync.js`** — Orchestrates force sync across both modules and aggregates results
- **`header.js`** — Shared app header with sync status indicator and settings gear

## Technical Stack

### Backend

**FastAPI** serves as the unified web framework. Each module contributes an `APIRouter` via a factory function (`create_router(db_path)`) that initializes its database and returns the router. The main `server.py` mounts them at `/api/journal`, `/api/coach`, and `/api/analysis`.

**SQLite** is used directly (no ORM) with one database file per module. This keeps modules fully isolated at the data layer and simplifies deployment (no database server required). Foreign key constraints are enforced via `PRAGMA foreign_keys = ON` in the Coach module where relational integrity matters.

**Uvicorn** runs the ASGI application. The server control script (`bin/server.sh`) manages the process via PID files and port detection.

### Frontend

**Preact** (10.19.3) with **Signals** for reactive state management. Components are written using **HTM** tagged template literals, eliminating the need for JSX and any build tooling.

Each module follows a consistent pattern:
- `View.js` - Root component with initialization logic
- `store.js` - Preact Signals state (reactive variables and computed values)
- `components/` - UI components
- `utils.js` - Helper functions

**LocalForage** provides persistent client-side storage backed by IndexedDB. Journal and Coach modules store all data locally and sync to the server, enabling full offline operation.

**Service Worker** (`sw.js`) and **PWA Manifest** enable installation on mobile devices and offline access to the shell.

### MCP Servers

Two **FastMCP** servers expose wellness data to LLMs:

- **Journal MCP** - Strictly read-only. Opens SQLite in read-only mode (`?mode=ro`). Validates all queries to ensure only SELECT/WITH statements run. Auto-applies row limits.
- **Coach MCP** - Read-only for queries and logs. Write access for workout plan management (creating/updating plans). Uses a mode-switching connection manager.

Both servers run over stdio transport when invoked by Claude Code CLI. They can also be configured for HTTP/SSE transport.

### Analysis Pipeline

The Analysis module bridges the web app with Claude Code CLI:

1. Pre-configured query templates define the prompt and allowed MCP tools
2. `asyncio.create_subprocess_exec` launches `claude -p` with `--verbose --output-format stream-json --model sonnet`
3. The CLI runs in a configurable working directory where MCP servers are configured
4. Output is parsed from the stream-json format; the final result object contains both the response text and execution metadata
5. CLI metadata (`duration_ms`, `duration_api_ms`, `num_turns`, `total_cost_usd`, `mcp_servers`) is stored alongside the report in `analysis.db`
6. The full stream is saved to `.wellness/data/last_stream.jsonl` for debugging
7. Queries time out after 180 seconds

Default allowed tools for analysis queries: `mcp__journal-localdb__*`, `mcp__coach-localdb__*`, `mcp__garmy-localdb__*`, `Read`, `Glob`, `Grep`. Individual queries can grant additional tools via `extra_allowed_tools`.

### Custom Analysis Queries

Analysis queries are split into two files in `src/modules/`:

- **`analysis_queries.py`** - Generic queries safe for version control (post-workout, pre-workout, weekly review)
- **`user_queries.py`** - Personal queries that may contain sensitive data (gitignored)

User queries are loaded first and merged with the built-in queries. To add custom queries, copy the example file and edit:

```bash
cp src/modules/user_queries.example.py src/modules/user_queries.py
```

Each query is a dict with these fields:

| Field | Required | Description |
|-------|----------|-------------|
| `id` | yes | Unique identifier, used in API calls |
| `label` | yes | Display name shown in the UI |
| `description` | yes | Short description shown below the label |
| `prompt_template` | yes | The prompt sent to Claude Code CLI |
| `accepts_location` | no | If `true`, the UI shows a location input field |
| `extra_allowed_tools` | no | Additional tools beyond the defaults (e.g., `["WebSearch", "WebFetch"]`) |

Two template variables are available in `prompt_template`:

- `{arguments}` - Replaced with the user-provided location/arguments, or `"(none)"`
- `{current_time}` - Replaced with the current date and time string

Example custom query:

```python
QUERIES = [
    {
        "id": "migraine_check",
        "label": "Migraine Risk Check",
        "description": "Assess migraine triggers for today or tomorrow",
        "accepts_location": True,
        "extra_allowed_tools": ["WebSearch", "WebFetch"],
        "prompt_template": (
            "Run a migraine trigger check.\n\n"
            "**Arguments:** {arguments}\n"
            "**Current time:** {current_time}\n\n"
            "Use Journal MCP for supplement and symptom data, "
            "Coach MCP for workout schedule, "
            "and WebFetch for barometric pressure from NOAA.\n\n"
            "Evaluate triggers and report risk level."
        ),
    },
]
```

Custom queries appear in the Analysis UI alongside built-in queries. The server must be restarted after adding or modifying queries.

## Testing

**Pytest** with `pytest-asyncio` for async test support. Tests are organized by module:

- **Unit tests** (`test/unit/`, `test/*/unit/`) — Isolated function and class tests
- **Integration tests** (`test/integration/`, `test/*/integration/`) — API and cross-component tests with temp databases
- **E2E browser tests** (`test/e2e_browser/`) — Playwright tests that run against a real server with seeded databases, covering navigation, sync, offline behavior, and responsive layout

Key testing patterns:
- Each test gets isolated temporary databases via fixtures
- `test_app` fixture creates a FastAPI app with temp DB paths
- `client` fixture wraps it in a `TestClient`
- Analysis tests mock the Claude CLI subprocess
- E2E tests start a real uvicorn server on a dynamic port with seeded journal and coach data
- Cross-module integration tests verify module discovery and coexistence
