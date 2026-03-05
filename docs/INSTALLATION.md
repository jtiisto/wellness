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

The server starts on port 9000. Open `http://localhost:9000` in a browser or add it to your phone's home screen as a PWA.

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

### LLM directory configuration

The Analysis module invokes Claude Code CLI in a specific working directory so it has access to MCP server configurations. This directory is resolved in order:

1. `ANALYSIS_LLM_DIR` environment variable
2. `.llm-dir` file in the wellness project root (written by the deploy script)
3. Falls back to the parent of the wellness directory

---

## MCP Server Setup

The application includes two MCP servers that provide AI tools with structured access to wellness data:

- **Journal MCP** (`mcp/journal_mcp/`) - Read-only SQL access to journal tracking data
- **Coach MCP** (`mcp/coach_mcp/`) - Read/write access to workout plans, read-only access to logs

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
| `get_workout_logs_range` | Logs for a date range |
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
