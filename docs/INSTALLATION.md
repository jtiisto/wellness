# Installation Guide

## Prerequisites

- Python 3.11+
- pip
- Claude Code CLI (for the Analysis module)
- FastMCP (`pip install fastmcp`) for MCP servers

## Development Setup

### 1. Clone and install

```bash
cd wellness
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### 2. Install Playwright browsers (for E2E tests)

```bash
playwright install chromium
```

### 3. Start the server

```bash
./bin/server.sh start
```

The server starts on port 9000. Open `http://localhost:9000/wellness/` in a browser or add it to your phone's home screen as a PWA.

The `/wellness` prefix is baked into all frontend paths and handled by the `StripPrefixMiddleware` in the server, so it works with or without a reverse proxy.

### 4. Run tests

```bash
pytest                      # All tests (except E2E browser)
pytest test/journal/        # Journal tests only
pytest -m unit              # Unit tests only
pytest -m integration       # Integration tests only
pytest -m e2e               # End-to-end tests only
pytest test/e2e_browser/    # Playwright E2E browser tests
```

## Production Deployment

### Using the deploy script

```bash
./bin/deploy-prod.sh /path/to/production [/path/to/llm-directory]
```

This copies `src/`, `public/`, `mcp/`, `data/`, and `bin/` to the production directory. The optional LLM directory argument specifies where Claude Code CLI runs analysis queries (it must contain `CLAUDE.md` and `.claude/` with MCP configs).

### Manual production setup

```bash
cd /path/to/production
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
./bin/server.sh start
```

### Tailscale Setup (with Share)

Wellness and [Share](https://github.com/jtiisto/share) are designed to run side-by-side on the same Tailscale hostname using path-based routing. Both PWAs get non-overlapping scopes (`/wellness/` and `/share/`) so Chrome on Android treats them as separate installable apps.

**1. Start both servers:**

```bash
# Wellness — port 9000
./bin/server.sh start

# Share — port 9100 (separate project)
```

**2. Configure Tailscale path-based routing:**

```bash
sudo tailscale serve --https 9443 --set-path /wellness --bg http://localhost:9000
sudo tailscale serve --https 9443 --set-path /share --bg http://localhost:9100
```

**3. Verify the configuration:**

```bash
sudo tailscale serve status
```

**4. Access the apps:**

```
https://<tailscale-hostname>:9443/wellness/
https://<tailscale-hostname>:9443/share/
```

On Android, "Add to Home Screen" installs each as an independent PWA.

### Without Tailscale

The app works without Tailscale for local development and testing:

```
http://localhost:9000/wellness/
```

The `StripPrefixMiddleware` in `server.py` strips the `/wellness` prefix from incoming requests so backend routes stay at root (`/api/journal/sync`, `/api/coach/sync`, etc.) while the frontend uses prefixed paths (`/wellness/api/journal/sync`). This means the same server works both behind Tailscale (which also strips the prefix) and via direct access.

### Workout hooks

The Coach module supports pre- and post-workout hooks — shell scripts that fire when you tap Start/End Workout in the UI. The primary use case is capturing stats (training readiness, HRV, body battery) before exercise overwrites them on your fitness device.

**Configuration:**

| Variable | Default | Description |
|----------|---------|-------------|
| `PRE_WORKOUT_HOOK` | `bin/pre-workout-hook.sh` (if exists) | Script to run before a workout |
| `POST_WORKOUT_HOOK` | `bin/post-workout-hook.sh` (if exists) | Script to run after a workout |

If no env var is set, the server falls back to the example scripts in `bin/`. If neither exists, the hook buttons are hidden in the UI.

**Script contract:**

- Exit code 0 = success, non-zero = failure
- Stdout = flat JSON object with string/number/boolean/null values (optional)
- Stderr is ignored (use it for logging)

Example output:

```json
{
  "training_readiness": 70,
  "hrv_status": "balanced",
  "body_battery": 85,
  "sleep_score": 82
}
```

Hook results (exit code + parsed key/value data) are stored in `coach.db` and linked to the workout session. The example scripts in `bin/` output hardcoded sample data — replace them with real data collection (e.g., query the garmy database, call an API).

### LLM directory configuration

The Analysis module invokes Claude Code CLI in a specific working directory so it has access to MCP server configurations. This directory is resolved in order:

1. `ANALYSIS_LLM_DIR` environment variable
2. `.llm-dir` file in the wellness project root (written by the deploy script)
3. Falls back to the parent of the wellness directory

---

## MCP Server Setup

The application includes two MCP servers that provide AI tools with structured access to wellness data:

- **Journal MCP** (`mcp_servers/journal_mcp/`) - Read-only SQL access to journal tracking data
- **Coach MCP** (`mcp_servers/coach_mcp/`) - Read/write access to workout plans, read-only access to logs

Both servers use the FastMCP framework and communicate over stdio transport.

### Claude Code

Add the MCP servers to your Claude Code settings file at `.claude/settings.local.json` in the directory where Claude Code will run:

```json
{
  "mcpServers": {
    "journal-localdb": {
      "command": "python3",
      "args": ["-m", "journal_mcp"],
      "cwd": "/absolute/path/to/wellness/mcp",
      "env": {
        "JOURNAL_DB_PATH": "/absolute/path/to/wellness/data/journal.db"
      }
    },
    "coach-localdb": {
      "command": "python3",
      "args": ["-m", "coach_mcp"],
      "cwd": "/absolute/path/to/wellness/mcp",
      "env": {
        "COACH_DB_PATH": "/absolute/path/to/wellness/data/coach.db"
      }
    }
  }
}
```

The `cwd` must point to the `mcp/` directory so Python can resolve the module packages. The `env` overrides are optional - both servers default to `../../data/<module>.db` relative to their own location.

#### Verifying MCP tools

After configuring, start Claude Code in the configured directory and check that the tools are available:

```
claude
> /mcp
```

You should see `journal-localdb` and `coach-localdb` listed with their tools (e.g., `execute_sql_query`, `list_trackers`, `get_entries`).

### Gemini CLI

Add MCP servers to your Gemini CLI settings file at `~/.gemini/settings.json`:

```json
{
  "mcpServers": {
    "journal-localdb": {
      "command": "python3",
      "args": ["-m", "journal_mcp"],
      "cwd": "/absolute/path/to/wellness/mcp",
      "env": {
        "JOURNAL_DB_PATH": "/absolute/path/to/wellness/data/journal.db"
      }
    },
    "coach-localdb": {
      "command": "python3",
      "args": ["-m", "coach_mcp"],
      "cwd": "/absolute/path/to/wellness/mcp",
      "env": {
        "COACH_DB_PATH": "/absolute/path/to/wellness/data/coach.db"
      }
    }
  }
}
```

The configuration format is the same as Claude Code. Gemini CLI will discover and expose the MCP tools automatically.

### MCP Server Details

#### Journal MCP tools

| Tool | Description |
|------|-------------|
| `explore_database_structure` | List tables with row counts and descriptions |
| `get_table_details` | Column schema and sample data for a table |
| `execute_sql_query` | Run arbitrary SELECT/WITH queries (read-only) |
| `list_trackers` | List tracker definitions, optionally by category |
| `get_entries` | Get journal entries by date range or tracker name |
| `get_journal_summary` | Summary statistics (completion rates, active days) |

All queries are validated to be read-only (SELECT/WITH only). A row limit is automatically applied to prevent runaway queries.

#### Coach MCP tools

| Tool | Description |
|------|-------------|
| `explore_database_structure` | List tables with row counts |
| `get_table_details` | Column schema and sample data |
| `execute_sql_query` | Run SELECT/WITH queries (read-only) |
| `get_workout_plan` | Get a specific day's plan with exercises |
| `get_workout_plans_range` | Plans for a date range |
| `get_workout_log` | Get a specific day's log with sets |
| `get_workout_logs_range` | Logs for a date range (includes pre/post workout stats) |
| `get_exercise_history` | Historical logs for a specific exercise |
| `save_workout_plan` | Create or update a workout plan (write) |
| `lookup_exercise` | Search the exercise registry |

The Coach MCP opens the database in read-only mode for queries and read-write mode only for plan modifications.

### Environment Variables

Both MCP servers accept environment variables to override database paths:

| Variable | Default | Description |
|----------|---------|-------------|
| `JOURNAL_DB_PATH` | `mcp/../data/journal.db` | Path to journal SQLite database |
| `COACH_DB_PATH` | `mcp/../data/coach.db` | Path to coach SQLite database |

---

## Troubleshooting

### Server won't start

- Check if port 9000 is already in use: `lsof -i :9000`
- Check logs: `./bin/server.sh logs`
- Try the test port: `./bin/server.sh --test start` (uses port 9001)

### PWA shows wrong app or "already installed"

Chrome on Android identifies PWAs by scope on the same origin. If Wellness and Share don't have non-overlapping scopes, Chrome may conflate them. Both apps must be on subpaths (`/wellness/` and `/share/`) — never at root (`/`). Check `manifest.json` for correct `scope`, `start_url`, and `id` fields.

### Tailscale routing not working

- Verify config: `sudo tailscale serve status`
- Both apps must be running on their respective ports before configuring Tailscale
- Tailscale `--set-path` strips the prefix before forwarding — the apps handle this via `StripPrefixMiddleware`

### MCP server errors

- Ensure `fastmcp` is installed: `pip install fastmcp`
- Verify the database files exist in `data/` (they are created on first server start)
- Check that `cwd` in MCP config points to the `mcp/` directory, not the individual server directory
- Test a server directly: `cd mcp && python3 -m journal_mcp`

### Analysis module not working

- Ensure Claude Code CLI is installed and accessible as `claude` in PATH
- Verify the LLM directory has `.claude/settings.local.json` with MCP server configs
- Check that the LLM directory is correctly configured (env var, `.llm-dir` file, or default)
- Analysis queries have a 180-second timeout
