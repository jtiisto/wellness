# Wellness

A personal health and fitness dashboard that unifies daily habit tracking, workout planning, progress charts, and AI-powered analysis into a single self-hosted application. One FastAPI server, two full clients: an offline-capable PWA served from `public/`, and a native Android app in `android/`. Both speak the same sync protocols against the same server — a protocol change lands as one atomic commit across server, PWA, and Android, and the git hooks run both toolchains' test gates on such a commit.

## The two clients

| | PWA | Android |
|---|---|---|
| Stack | Preact + Signals + HTM — no build step | Kotlin, Jetpack Compose, Room, Ktor |
| Works offline | Yes (service worker + IndexedDB) | Yes (Room, same sync semantics) |
| Tabs | Journal · Coach · Trends · Analysis, plus a Tools menu (force sync, data export, debug log) | The same four, plus a **Tools** tab: the menu's functions plus a server address book and HR-strap pairing |
| Heart-rate capture | — | Yes: Garmin chest strap over BLE, uploaded to the headless HR module |
| Visual design | Dark-theme CSS | The **Logbook** design language — paper and ink, color reserved for meaning (`android/specs/logbook-design-system.md`) |

Sync behavior is identical by construction: the pure logic is ported function-for-function with mirrored test suites, and shared wire contracts are pinned by golden fixtures both test suites read. `docs/ARCHITECTURE.md` is the authority for every protocol; `android/CLAUDE.md` covers the Android build and workflow.

## Modules

### Journal
Daily habit and health tracking with multi-device sync. Track supplements, habits, metrics, and any custom data points. Each tracker can be scheduled on specific weekdays (e.g. Mon–Fri instead of every day); schedule changes are effective-dated, so past days are always interpreted against the schedule that was in effect at the time. Trackers can also carry a polarity (positive/negative/neutral), and quantifiable trackers can carry a typed value target — a number or a range (e.g. "10" or "150-170") — that is likewise effective-dated. Features conflict-aware synchronization with per-record versioning so multiple devices stay in sync without data loss. Sync runs automatically via a shared scheduler that responds to edits, network changes, and page visibility.

### Coach
Workout planning and logging. Supports structured workout plans with blocks (warmup, strength, cardio), set-level tracking (weight, reps, RPE), and multiple exercise types including strength, cardio, duration, and checklists. Rest days can take an ad-hoc "extra" Zone 2 session (off-plan, deletable, reported separately from plan completion by the analysis tools). Plans are managed server-side; logs sync from clients with per-record server-token arbitration (the server is the only arbiter — client clock skew can never reject or overwrite a legitimate edit). Automatic sync with debounced uploads and periodic polling for plan changes. Configurable pre/post-workout hooks fire shell scripts to capture stats (e.g., Garmin training readiness) before exercise overwrites them.

### Trends
Read-only progress charts — the deterministic "what happened" counterpart to interactive LLM analysis. Per-exercise strength progression (top set + estimated 1RM with an RPE overlay), weekly tonnage, weekly Zone 2 minutes split planned-vs-extra, an aerobic-base proxy, journal value-vs-target charts with effective-dated target bands, weekly adherence ribbons with streaks, body weight from the Garmin sync DB, a Health tab with recovery signals (HRV against Garmin's own baseline band, resting HR, sleep) plus DEXA body composition (scan markers on the weight chart, lean/fat/VAT small-multiples, bone density) from the BodySpec sync DB and lab results with reference-range bands from the Quest sync DB, and an overview of headline tiles with PR detection and rule-based plateau / adherence-drop callouts. Hand-rolled SVG, offline-cached with staleness badges, zero LLM. Trends owns no database: it reads coach/journal/Garmin data through its own read-only accessors (a deliberate, documented exception to module DB isolation).

### Analysis
LLM-powered async reports. Submits structured prompts to Claude Code CLI with MCP data access; reports render as markdown with CLI execution metadata tracked per report. Enabled by default like every module; a deployment can switch it off with `WELLNESS_DISABLED_MODULES=analysis` (e.g. where Trends plus interactive Claude sessions cover the same need). The module stays maintained and tested either way.

### HR (headless)
Heart-rate ingestion from the native Android client: RR intervals off a Garmin chest strap, the set-completion toggles that tie them to a coach workout, and the capture sessions grouping them. Three idempotent batch endpoints, so a retried upload can never double-count, and a whole-request `422` on any bad row — the client's cue to bisect the batch and quarantine the poison rows. Headless means API-only: no PWA tab, no client-side state, nothing synced back. Reading happens out of band, through a read-only CLI (`python -m hr_analysis` — DFA α1, RMSSD, duration-weighted HR and zones, work/rest bouts) and the HR MCP server.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Python, FastAPI, SQLite |
| Frontend | Preact, Signals, HTM (no build step) |
| State | Preact Signals + LocalForage (IndexedDB) |
| AI | Claude Code CLI with MCP tool access |
| MCP | FastMCP (Journal read-only, Coach read/write, HR read-only) |
| Android client | Kotlin, Jetpack Compose, Room, Ktor — `android/` (see `android/CLAUDE.md`) |

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
│   ├── modules/            # Journal, Coach, Trends, Analysis, HR routers + shared domain
│   └── hr_analysis/        # Heart-rate analysis CLI (python -m hr_analysis), not part of the app
├── public/                 # PWA frontend (no build step)
│   ├── js/                 # Preact components per module
│   │   ├── shared/         # Sync scheduler, settings, debug log, data export
│   │   └── vendor/         # Vendored runtime libs (Preact, Signals, HTM, …) — no CDN
│   ├── styles.css          # Dark theme, responsive layout
│   ├── sw.js               # Service worker for offline
│   └── manifest.json       # PWA manifest
├── mcp_servers/            # MCP servers for AI data access
│   ├── journal_mcp/        # Read-only journal queries
│   ├── coach_mcp/          # Read/write workout data
│   └── hr_mcp/             # Read-only heart-rate sessions + analysis
├── test/                   # Test suites
│   ├── test_*.py           # Top-level unit tests
│   ├── journal/, coach/    # Per-module unit + integration tests
│   ├── analysis/           # Analysis module tests
│   ├── trends/             # Trends module tests
│   ├── hr/                 # HR endpoints, golden payloads, analysis CLI
│   ├── integration/        # Cross-module integration tests
│   ├── e2e_browser/        # Playwright E2E browser tests (pages/ objects)
│   └── js/                 # node:test suites for client sync logic
├── bin/                    # Server control, deployment, and hook scripts
│   ├── deploy.manifest     # Single source of truth for what ships to prod
│   └── scan_personal_data.py  # Personal-data guard (this repo is public)
├── githooks/               # Shared git hooks, path-scoped per toolchain (enable: git config core.hooksPath githooks)
├── android/                # Native Android client — Kotlin/Compose, own Gradle tree, specs/, testdata/golden/
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
HR_DB_PATH=/custom/path/hr.db
```

`HR_DB_PATH` repoints the server, the `hr_analysis` CLI, and the HR MCP server together. Trends owns no database of its own, so it has no entry here.

Workout hooks fire shell scripts before/after workouts to capture stats:

```bash
PRE_WORKOUT_HOOK=/path/to/pre-workout-hook.sh
POST_WORKOUT_HOOK=/path/to/post-workout-hook.sh
```

Example scripts are included in `bin/`. If no env var is set, the defaults in `bin/` are used when present. See [Installation Guide](docs/INSTALLATION.md) for details.
