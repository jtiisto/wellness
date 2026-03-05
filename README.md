# Wellness

A personal health and fitness dashboard that unifies daily habit tracking, workout planning, and AI-powered analysis into a single self-hosted application.

## Modules

### Journal
Daily habit and health tracking with multi-device sync. Track supplements, habits, metrics, and any custom data points. Features conflict-aware synchronization with per-record versioning so multiple devices stay in sync without data loss. Sync runs automatically via a shared scheduler that responds to edits, network changes, and page visibility.

### Coach
Workout planning and logging. Supports structured workout plans with blocks (warmup, strength, cardio), set-level tracking (weight, reps, RPE), and multiple exercise types including strength, cardio, duration, and checklists. Plans are managed server-side; logs sync from clients using last-write-wins. Automatic sync with debounced uploads and periodic polling for plan changes.

### Analysis
LLM-powered health insights. Submits structured prompts to Claude Code CLI with access to all data via MCP servers. Includes pre-built queries for post-workout analysis, pre-workout readiness checks, and weekly performance reviews. Custom queries can be added in `src/modules/user_queries.py` (gitignored) for personal or sensitive analysis like migraine trigger assessments. Reports are generated asynchronously using stream-json output and rendered as markdown, with CLI execution metadata (cost, duration, turns) tracked per report.

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

The app runs at `http://localhost:9000` and works as a PWA on mobile devices.

## Project Structure

```
wellness/
├── src/                    # FastAPI backend
│   ├── server.py           # Main app, static file serving
│   ├── config.py           # Module config, DB path resolution
│   └── modules/            # Journal, Coach, Analysis routers
├── public/                 # PWA frontend (no build step)
│   ├── js/                 # Preact components per module
│   │   └── shared/         # Sync scheduler, settings, debug log, data export
│   ├── styles.css          # Dark theme, responsive layout
│   ├── sw.js               # Service worker for offline
│   └── manifest.json       # PWA manifest
├── mcp/                    # MCP servers for AI data access
│   ├── journal_mcp/        # Read-only journal queries
│   └── coach_mcp/          # Read/write workout data
├── test/                   # Pytest suite
│   ├── unit/               # Unit tests
│   ├── integration/        # Integration tests
│   └── e2e_browser/        # Playwright E2E browser tests
├── bin/                    # Server control & deployment scripts
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
HEALTH_DISABLED_MODULES=analysis ./bin/server.sh start
```

Database paths are configurable per module:

```bash
JOURNAL_DB_PATH=/custom/path/journal.db
COACH_DB_PATH=/custom/path/coach.db
ANALYSIS_DB_PATH=/custom/path/analysis.db
```
