# Wellness

A personal health and fitness dashboard that unifies daily habit tracking, workout planning, and AI-powered analysis into a single self-hosted application.

## Modules

### Journal
Daily habit and health tracking with multi-device sync. Track supplements, habits, metrics, and any custom data points. Each tracker can be scheduled on specific weekdays (e.g. Mon–Fri instead of every day); schedule changes are effective-dated, so past days are always interpreted against the schedule that was in effect at the time. Trackers can also carry a polarity (positive/negative/neutral), and quantifiable trackers can carry a typed value target — a number or a range (e.g. "10" or "150-170") — that is likewise effective-dated. Features conflict-aware synchronization with per-record versioning so multiple devices stay in sync without data loss. Sync runs automatically via a shared scheduler that responds to edits, network changes, and page visibility.

### Coach
Workout planning and logging. Supports structured workout plans with blocks (warmup, strength, cardio), set-level tracking (weight, reps, RPE), and multiple exercise types including strength, cardio, duration, and checklists. Rest days can take an ad-hoc "extra" Zone 2 session (off-plan, deletable, reported separately from plan completion by the analysis tools). Plans are managed server-side; logs sync from clients with per-record server-token arbitration (the server is the only arbiter — client clock skew can never reject or overwrite a legitimate edit). Automatic sync with debounced uploads and periodic polling for plan changes. Configurable pre/post-workout hooks fire shell scripts to capture stats (e.g., Garmin training readiness) before exercise overwrites them.

### Trends
Read-only progress charts — the deterministic "what happened" counterpart to interactive LLM analysis. Per-exercise strength progression (top set + estimated 1RM with an RPE overlay), weekly tonnage, weekly Zone 2 minutes split planned-vs-extra, an aerobic-base proxy, journal value-vs-target charts with effective-dated target bands, weekly adherence ribbons with streaks, body weight from the Garmin sync DB, and an overview of headline tiles with PR detection. Hand-rolled SVG, offline-cached with staleness badges, zero LLM. Trends owns no database: it reads coach/journal/Garmin data through its own read-only accessors (a deliberate, documented exception to module DB isolation).

### Analysis (retired, dormant)
LLM-powered async reports, superseded by Trends (glanceable stats) and interactive Claude sessions (interpretation). Disabled in production via `WELLNESS_DISABLED_MODULES=analysis`; the code remains in the tree and under test. Submits structured prompts to Claude Code CLI with MCP data access; reports render as markdown with CLI execution metadata tracked per report.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Python, FastAPI, SQLite |
| Frontend | Preact, Signals, HTM (no build step) |
| State | Preact Signals + LocalForage (IndexedDB) |
| AI | Claude Code CLI with MCP tool access |
| MCP | FastMCP (Journal read-only, Coach read/write) |

## Quick Start

```bash
cd wellness
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
./bin/server.sh start
```

The app runs at `http://localhost:9000/wellness/` and works as a PWA on mobile devices. See [Installation Guide](docs/INSTALLATION.md) for Tailscale and production setup.

## Project Structure

```
wellness/
├── src/                    # FastAPI backend
│   ├── server.py           # Main app, static file serving
│   ├── config.py           # Module config, DB path resolution
│   └── modules/            # Journal, Coach, Trends, Analysis routers + shared domain
├── public/                 # PWA frontend (no build step)
│   ├── js/                 # Preact components per module
│   │   ├── shared/         # Sync scheduler, settings, debug log, data export
│   │   └── vendor/         # Vendored runtime libs (Preact, Signals, HTM, …) — no CDN
│   ├── styles.css          # Dark theme, responsive layout
│   ├── sw.js               # Service worker for offline
│   └── manifest.json       # PWA manifest
├── mcp_servers/            # MCP servers for AI data access
│   ├── journal_mcp/        # Read-only journal queries
│   └── coach_mcp/          # Read/write workout data
├── test/                   # Test suites
│   ├── test_*.py           # Top-level unit tests
│   ├── journal/, coach/    # Per-module unit + integration tests
│   ├── analysis/           # Analysis module tests
│   ├── integration/        # Cross-module integration tests
│   ├── e2e_browser/        # Playwright E2E browser tests (pages/ objects)
│   └── js/                 # node:test suites for client sync logic
├── bin/                    # Server control, deployment, and hook scripts
├── githooks/               # Shared git hooks (enable: git config core.hooksPath githooks)
├── data/                   # SQLite databases (runtime)
└── requirements.txt
```

## Documentation

- [Installation Guide](docs/INSTALLATION.md) - Setup, deployment, and MCP configuration
- [Architecture](docs/ARCHITECTURE.md) - Design decisions, sync protocols, and technical details

## Server Control

```bash
./bin/server.sh start       # Start on port 9000
./bin/server.sh stop        # Stop the server
./bin/server.sh restart     # Restart
./bin/server.sh status      # Check if running
./bin/server.sh logs        # Last 50 log lines
./bin/server.sh follow      # Tail logs in real-time
./bin/server.sh --test start  # Start on port 9001 (testing)
```

## Configuration

Modules can be disabled via environment variable:

```bash
WELLNESS_DISABLED_MODULES=analysis ./bin/server.sh start
```

Database paths are configurable per module:

```bash
JOURNAL_DB_PATH=/custom/path/journal.db
COACH_DB_PATH=/custom/path/coach.db
ANALYSIS_DB_PATH=/custom/path/analysis.db
```

Workout hooks fire shell scripts before/after workouts to capture stats:

```bash
PRE_WORKOUT_HOOK=/path/to/pre-workout-hook.sh
POST_WORKOUT_HOOK=/path/to/post-workout-hook.sh
```

Example scripts are included in `bin/`. If no env var is set, the defaults in `bin/` are used when present. See [Installation Guide](docs/INSTALLATION.md) for details.
