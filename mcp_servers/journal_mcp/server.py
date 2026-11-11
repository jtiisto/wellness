"""Journal MCP Server implementation.

Provides secure, read-only access to journal tracking data
through the Model Context Protocol with tools for LLM understanding.
"""

import json
import os
import re
import sqlite3
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional

try:
    from fastmcp import FastMCP
except ImportError:
    raise ImportError(
        "FastMCP is required for MCP server functionality. "
        "Install with: pip install fastmcp"
    )

from .adherence import ENTRY_PRESENT_SQL, compute_adherence, entry_present_sql
from .config import MCPConfig

# Default DB path: ../../data/journal.db relative to this file's directory
_DEFAULT_DB_PATH = Path(__file__).resolve().parent.parent.parent / "data" / "journal.db"

# Server instructions: sent in the initialize handshake and loaded by the
# client at session start, before any tool description. Rules that hold for
# every tool live here; the guard test holds them under the client's
# 4,096-char cut.
INSTRUCTIONS = """\
Journal data: trackers (habits, supplements, metrics; a checkbox or a numeric
value) and their daily entries, with each tracker's weekday schedule, polarity
and value target. Use these tools for tracker entries, habit completion,
schedule adherence and trends. Read-only.

- Start with list_trackers, get_entries, get_journal_summary;
  get_schedule_adherence for schedule-aware adherence; execute_sql_query
  (SELECT/WITH only) for anything else. Unsure of columns: get_table_details,
  never guess.
- Dates are YYYY-MM-DD; weekdays 0=Sun..6=Sat.
- Trackers are soft-deleted (deleted = 1): hidden from the UI, entries kept;
  for history do not filter deleted = 0. Tracker ids are UUIDs, so a
  re-created name is a distinct tracker.
- Completion and adherence are separate axes: get_journal_summary's
  completion rate is entries-based; schedule adherence (scheduled vs
  logged/done days) comes from get_schedule_adherence.
- An entry is present iff completed = 1 OR value IS NOT NULL; an emptied row
  counts as no entry.
- Schedules, polarity and targets have data epochs: read the guide's Data
  epochs before comparing across them.
- Full guide: resource file://journal_data_guide/.
"""


class SQLiteConnection:
    """Secure SQLite connection context manager for read-only access."""

    def __init__(self, db_path: Path):
        self.db_path = db_path
        self.conn = None

    def __enter__(self):
        """Open read-only SQLite connection."""
        self.conn = sqlite3.connect(f"file:{self.db_path}?mode=ro", uri=True)
        self.conn.row_factory = sqlite3.Row
        # Under WAL even a reader can hit a transient SQLITE_BUSY (e.g. during
        # a checkpoint by the live server); wait instead of failing — same
        # setting as db.get_db and the coach MCP.
        self.conn.execute("PRAGMA busy_timeout = 5000")
        return self.conn

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Close connection safely."""
        if self.conn:
            self.conn.close()


class QueryValidator:
    """SQL query validation and sanitization for read-only access."""

    ALLOWED_STATEMENTS = ("select", "with")
    FORBIDDEN_KEYWORDS = {
        "insert",
        "update",
        "delete",
        "drop",
        "create",
        "alter",
        "pragma",
        "attach",
        "detach",
        "vacuum",
        "analyze",
    }

    @classmethod
    def validate_query(cls, query: str) -> None:
        """Validate SQL query for read-only access."""
        if not query or not query.strip():
            raise ValueError("Query cannot be empty")

        query_lower = query.lower().strip()

        if not any(query_lower.startswith(prefix) for prefix in cls.ALLOWED_STATEMENTS):
            allowed = ", ".join(cls.ALLOWED_STATEMENTS).upper()
            raise ValueError(f"Only {allowed} queries are allowed for security")

        # Scan keywords OUTSIDE string literals only: a legitimate SELECT can
        # mention 'update' or 'delete' inside quoted data (e.g. a tracker named
        # 'Update meds'). The connection is opened ?mode=ro, so this validator
        # is defense-in-depth, not the enforcement boundary — false positives
        # cost usability without adding safety.
        stripped = cls._strip_string_literals(query_lower)
        query_words = set(re.findall(r"\b\w+\b", stripped))
        forbidden_found = query_words.intersection(cls.FORBIDDEN_KEYWORDS)
        if forbidden_found:
            raise ValueError(f"Forbidden keywords found: {', '.join(forbidden_found)}")

        if cls._contains_multiple_statements(query):
            raise ValueError("Multiple statements not allowed")

    @staticmethod
    def _strip_string_literals(sql: str) -> str:
        """Replace the contents of '...'/"..." literals with spaces, honoring
        SQL's doubled-quote escaping, so the keyword scan only sees real SQL."""
        out = []
        quote = None
        for char in sql:
            if quote:
                if char == quote:
                    quote = None
                    out.append(char)
                else:
                    out.append(" ")
            elif char in ("'", '"'):
                quote = char
                out.append(char)
            else:
                out.append(char)
        return "".join(out)

    @staticmethod
    def _contains_multiple_statements(sql: str) -> bool:
        """Check if SQL contains multiple statements."""
        in_single_quote = False
        in_double_quote = False

        for char in sql:
            if char == "'" and not in_double_quote:
                in_single_quote = not in_single_quote
            elif char == '"' and not in_single_quote:
                in_double_quote = not in_double_quote
            elif char == ";" and not in_single_quote and not in_double_quote:
                return True

        return False

    @staticmethod
    def add_row_limit(query: str, limit: int = 1000) -> str:
        """Add LIMIT clause if not present."""
        query_lower = query.lower()
        if "limit" not in query_lower:
            return f"{query.rstrip(';')} LIMIT {limit}"
        return query


class DatabaseManager:
    """Manages database connections and basic operations."""

    def __init__(self, config: MCPConfig):
        self.config = config
        self.validator = QueryValidator()

    def get_connection(self):
        """Get read-only database connection."""
        return SQLiteConnection(self.config.db_path)

    def execute_safe_query(
        self, query: str, params: Optional[List[Any]] = None
    ) -> List[Dict[str, Any]]:
        """Execute validated query with safety checks."""
        if self.config.strict_validation:
            self.validator.validate_query(query)

        query = self.validator.add_row_limit(query, self.config.max_rows)

        try:
            with self.get_connection() as conn:
                cursor = conn.cursor()
                cursor.execute(query, params or [])
                results = [dict(row) for row in cursor.fetchall()]
                return results
        except sqlite3.Error as e:
            raise ValueError(f"Database error: {str(e)}")


def create_mcp_server(config: Optional[MCPConfig] = None) -> FastMCP:
    """Create and configure the Journal MCP server."""
    if config is None:
        db_path = Path(os.environ.get("JOURNAL_DB_PATH", str(_DEFAULT_DB_PATH)))
        config = MCPConfig.from_db_path(db_path)

    config.validate()
    db_manager = DatabaseManager(config)
    mcp = FastMCP("Journal Data Explorer", instructions=INSTRUCTIONS)

    @mcp.tool()
    def explore_database_structure() -> Dict[str, Any]:
        """WHEN TO USE: When you need to understand what journal data is available.

        Starting point for journal data: call first to see the available tables,
        before running specific queries.

        Result: the complete database structure, with table descriptions and row
        counts.
        """
        try:
            tables_query = """
                SELECT name FROM sqlite_master
                WHERE type='table' AND name NOT LIKE 'sqlite_%'
                ORDER BY name
            """
            tables = db_manager.execute_safe_query(tables_query)
            table_names = [row["name"] for row in tables]

            table_info = {}
            for table_name in table_names:
                count_query = f"SELECT COUNT(*) as count FROM {table_name}"
                count_result = db_manager.execute_safe_query(count_query)

                table_info[table_name] = {
                    "row_count": count_result[0]["count"],
                    "description": _get_table_description(table_name),
                }

            return {
                "available_tables": table_info,
                "usage_tip": "Use 'list_trackers' to see available trackers, 'get_entries' to get journal entries, or 'execute_sql_query' for custom queries",
            }
        except Exception as e:
            raise ValueError(f"Failed to explore database: {str(e)}")

    @mcp.tool()
    def get_table_details(table_name: str) -> Dict[str, Any]:
        """WHEN TO USE: When you need to see the structure and sample data of a specific table.

        Use after explore_database_structure to see a table's columns and examples
        of the actual data.

        Result: the table's columns, data types and sample records.

        Args:
            table_name: Name of the table (e.g., 'trackers', 'entries')
        """
        if not table_name or not table_name.strip():
            raise ValueError("Table name cannot be empty")

        if not re.match(r"^[a-zA-Z_][a-zA-Z0-9_]*$", table_name):
            raise ValueError("Invalid table name format")

        try:
            check_query = """
                SELECT name FROM sqlite_master
                WHERE type='table' AND name=?
            """
            check_result = db_manager.execute_safe_query(check_query, [table_name])

            if not check_result:
                available_tables = db_manager.execute_safe_query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
                )
                table_list = [row["name"] for row in available_tables]
                raise ValueError(
                    f"Table '{table_name}' does not exist. Available tables: {', '.join(table_list)}"
                )

            schema_query = f"PRAGMA table_info({table_name})"
            with db_manager.get_connection() as conn:
                cursor = conn.cursor()
                cursor.execute(schema_query)
                columns = cursor.fetchall()

            column_info = [
                {
                    "name": col[1],
                    "type": col[2],
                    "required": bool(col[3]),
                    "is_primary_key": bool(col[5]),
                }
                for col in columns
            ]

            sample_query = f"SELECT * FROM {table_name} ORDER BY rowid DESC LIMIT 3"
            sample_data = db_manager.execute_safe_query(sample_query)

            return {
                "table_name": table_name,
                "columns": column_info,
                "sample_data": sample_data,
                "description": _get_table_description(table_name),
            }

        except Exception as e:
            raise ValueError(f"Failed to get table details: {str(e)}")

    @mcp.tool()
    def execute_sql_query(
        query: str, params: Optional[List[Any]] = None
    ) -> List[Dict[str, Any]]:
        """WHEN TO USE: When you need to get specific data using SQL queries.

        The main tool for querying any data: run SELECT queries to analyze
        trackers, entries or find patterns.
        ONLY SELECT and WITH queries are allowed, for security.
        Unsure of column names? Call get_table_details(table_name); do not guess.
        Trackers are soft-deleted (deleted = 1). Filter deleted = 0 only for the
        current UI view, never for history. tracker_id is a UUID, so same-named
        re-created trackers are distinct rows.

        Sample queries:
        - All trackers: SELECT id, name, category, type, deleted FROM trackers
        - Entries for a date: SELECT * FROM entries WHERE date = '2030-01-22'
        - Join trackers and entries: SELECT t.name, e.date, e.value, e.completed FROM entries e JOIN trackers t ON e.tracker_id = t.id

        Result: list of matching records as dicts.

        Args:
            query: SQL SELECT query
            params: Optional list of parameters for ? placeholders in query
        """
        if not query or not query.strip():
            raise ValueError("Query cannot be empty")

        try:
            return db_manager.execute_safe_query(query, params)
        except Exception as e:
            raise ValueError(f"Query execution failed: {str(e)}")

    @mcp.tool()
    def list_trackers(
        category: Optional[str] = None, include_deleted: bool = False
    ) -> List[Dict[str, Any]]:
        """WHEN TO USE: When you want to see what trackers are available for journaling.

        Lists the journal's trackers (habits, metrics, etc.): simple checkboxes or
        quantifiable values. Tracker IDs are UUIDs, so a re-created tracker with a
        deleted one's display name is a distinct row; include_deleted=True shows
        both.

        Result: list of trackers, each with `deleted` (bool) and `metadata` (dict
        parsed from `meta_json`; may include `scheduleHistory`, the effective-dated
        weekday schedule, and `polarity`; see the data guide). Entries of deleted
        trackers stay queryable via get_entries or raw SQL: deletion is purely a
        UI/sync-visibility flag.

        Args:
            category: Optional filter by category (e.g., 'Supplements', 'Habits')
            include_deleted: False (default): only active trackers. True: also
                soft-deleted trackers, so historical entries can be attributed to
                the right (possibly retired) tracker.
        """
        try:
            query = """
                SELECT id, name, category, type, meta_json, schedule_json,
                       polarity, target_json, deleted
                FROM trackers
                WHERE 1=1
            """
            params = []

            if not include_deleted:
                query += " AND deleted = 0"

            if category:
                query += " AND category = ?"
                params.append(category)

            query += " ORDER BY category, name"

            results = db_manager.execute_safe_query(query, params)

            for row in results:
                row["deleted"] = bool(row.get("deleted"))
                metadata = {}
                if row.get("meta_json"):
                    try:
                        metadata = json.loads(row["meta_json"])
                    except json.JSONDecodeError:
                        metadata = {}
                # scheduleHistory / polarity / targetHistory are canonical columns
                # (no longer in meta_json); merge them into `metadata` so the
                # consumer-facing shape is unchanged (metadata.scheduleHistory /
                # metadata.polarity / metadata.targetHistory).
                if row.get("schedule_json"):
                    try:
                        metadata["scheduleHistory"] = json.loads(row["schedule_json"])
                    except json.JSONDecodeError:
                        pass
                if row.get("polarity") is not None:
                    metadata["polarity"] = row["polarity"]
                if row.get("target_json"):
                    try:
                        metadata["targetHistory"] = json.loads(row["target_json"])
                    except json.JSONDecodeError:
                        pass
                row["metadata"] = metadata
                row.pop("meta_json", None)
                row.pop("schedule_json", None)
                row.pop("polarity", None)
                row.pop("target_json", None)

            return results
        except Exception as e:
            raise ValueError(f"Failed to list trackers: {str(e)}")

    @mcp.tool()
    def get_entries(
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        tracker_name: Optional[str] = None,
        days: int = 7,
    ) -> List[Dict[str, Any]]:
        """WHEN TO USE: When you want to see journal entries for specific dates or trackers.

        Journal entries with tracker information: what was tracked on given days,
        habits, progress. Includes entries of soft-deleted trackers.

        Result: list of entries with tracker name, date, value, completion status
        and `tracker_deleted` (bool): true when the entry's tracker has since been
        soft-deleted, so "B12 (current)" and "B12 (retired)" stay distinct when
        the same name was reused with a different ID.

        Args:
            start_date: Start date in YYYY-MM-DD format (default: days ago from today)
            end_date: End date in YYYY-MM-DD format (default: today)
            tracker_name: Optional filter by tracker name (partial match supported)
            days: Number of days to look back if start_date not specified (default: 7)
        """
        try:
            if not end_date:
                end_date = date.today().isoformat()
            if not start_date:
                start_date = (date.today() - timedelta(days=days)).isoformat()

            query = """
                SELECT
                    e.date,
                    t.id as tracker_id,
                    t.name as tracker_name,
                    t.category,
                    t.type as tracker_type,
                    t.deleted as tracker_deleted,
                    e.value,
                    e.completed
                FROM entries e
                JOIN trackers t ON e.tracker_id = t.id
                WHERE e.date >= ? AND e.date <= ?
            """
            params = [start_date, end_date]

            if tracker_name:
                query += " AND t.name LIKE ?"
                params.append(f"%{tracker_name}%")

            query += " ORDER BY e.date DESC, t.category, t.name"

            results = db_manager.execute_safe_query(query, params)
            for row in results:
                row["tracker_deleted"] = bool(row.get("tracker_deleted"))
            return results
        except Exception as e:
            raise ValueError(f"Failed to get entries: {str(e)}")

    @mcp.tool()
    def get_journal_summary(days: int = 30) -> Dict[str, Any]:
        """WHEN TO USE: When you want a quick overview of journal activity without writing SQL.

        Summary statistics of journal entries and tracker usage over a period.

        Result: total entries, completion rates, most used trackers, active days.
        `completion_rate_percent` is entries-based: completed vs total *logged*
        entries. It is NOT schedule adherence: it ignores a tracker's
        `scheduleHistory` and does not count unlogged scheduled days as misses.

        Args:
            days: Number of recent days to analyze (max 365, default: 30)
        """
        if days > 365:
            raise ValueError("Days cannot exceed 365")

        try:
            start_date = (date.today() - timedelta(days=days)).isoformat()

            total_query = f"""
                SELECT COUNT(*) as total_entries
                FROM entries
                WHERE date >= ? AND {ENTRY_PRESENT_SQL}
            """
            total_result = db_manager.execute_safe_query(total_query, [start_date])
            total_entries = total_result[0]["total_entries"] if total_result else 0

            completed_query = """
                SELECT COUNT(*) as completed
                FROM entries
                WHERE date >= ? AND completed = 1
            """
            completed_result = db_manager.execute_safe_query(completed_query, [start_date])
            completed = completed_result[0]["completed"] if completed_result else 0

            days_query = f"""
                SELECT COUNT(DISTINCT date) as active_days
                FROM entries
                WHERE date >= ? AND {ENTRY_PRESENT_SQL}
            """
            days_result = db_manager.execute_safe_query(days_query, [start_date])
            active_days = days_result[0]["active_days"] if days_result else 0

            category_query = f"""
                SELECT t.category, COUNT(*) as entry_count
                FROM entries e
                JOIN trackers t ON e.tracker_id = t.id
                WHERE e.date >= ? AND {entry_present_sql('e')}
                GROUP BY t.category
                ORDER BY entry_count DESC
            """
            categories = db_manager.execute_safe_query(category_query, [start_date])

            top_trackers_query = f"""
                SELECT t.name, t.deleted as tracker_deleted, COUNT(*) as entry_count,
                       SUM(CASE WHEN e.completed = 1 THEN 1 ELSE 0 END) as completed_count
                FROM entries e
                JOIN trackers t ON e.tracker_id = t.id
                WHERE e.date >= ? AND {entry_present_sql('e')}
                GROUP BY t.id, t.name, t.deleted
                ORDER BY entry_count DESC
                LIMIT 10
            """
            top_trackers = db_manager.execute_safe_query(top_trackers_query, [start_date])
            for row in top_trackers:
                row["tracker_deleted"] = bool(row.get("tracker_deleted"))

            completion_rate = round(completed / total_entries * 100, 1) if total_entries > 0 else 0

            return {
                "analysis_period_days": days,
                "total_entries": total_entries,
                "completed_entries": completed,
                "completion_rate_percent": completion_rate,
                "active_days": active_days,
                "entries_by_category": categories,
                "top_trackers": top_trackers,
            }
        except Exception as e:
            raise ValueError(f"Failed to generate summary: {str(e)}")

    @mcp.tool()
    def get_schedule_adherence(
        days: int = 30,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        tracker_name: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """WHEN TO USE: to measure how well *scheduled* trackers were actually
        followed over a period; the schedule-aware counterpart to
        `get_journal_summary`'s entries-based completion rate.

        Per day in the window: reads the tracker's effective-dated weekday schedule
        (`scheduleHistory`, canonical column `schedule_json`) to decide whether it
        was scheduled and whether logged/done; then rolls up per tracker.

        Window (dates YYYY-MM-DD):
        - default: the last `days` calendar days ending today, inclusive (days=7
          = today and the 6 days before, matching the PWA's 7-day dot row)
        - override with `start_date` / `end_date`
        - per tracker, the start is clamped to its first-ever entry date: no
          misses for days before it was first used
        - omitted: a tracker with no entries, or whose first activity is after
          the window (nothing to measure); deleted trackers
        - still reported: a tracker whose entries all PRECEDE the window; an
          abandoned, still-scheduled habit at 0% is exactly what this tool
          surfaces

        Rates (polarity picks the headline, `metric_kind`):
        - `positive` → `adherence_rate`; `negative` → `avoidance_rate`;
          `neutral`/unspecified → `coverage_rate` only
        - `coverage_rate` (logged/scheduled) is always included
        - WITHOUT a target: positive numerator = `done_days` (`completed == 1`);
          negative numerator = scheduled − logged
        - WITH a `targetHistory`: a targeted day is "done" when its VALUE meets
          the in-effect target; the rate numerator is `blended_met_days`. Days
          before the target took effect fall back to the untargeted criterion
          (positive/neutral → checkbox, negative → no-entry avoided). The result
          then also carries `target`, `target_met_days` and `target_partial_days`
          (targeted-day-only counts).
        - off-schedule entries are excluded from the denominator and surfaced as
          `off_schedule_entries`
        - every rate is null when `scheduled_days` is 0

        Result: one dict per tracker with scheduled/logged/done/missed day counts,
        `off_schedule_entries`, `metric_kind` and the applicable rate(s).

        Args:
            days: Window length in days when start_date is omitted (max 366, default 30)
            start_date: Window start (YYYY-MM-DD); defaults to `days` before end_date
            end_date: Window end (YYYY-MM-DD); defaults to today
            tracker_name: Optional partial-match filter on tracker name
        """
        if days > 366:
            raise ValueError("Days cannot exceed 366")
        try:
            end = end_date or date.today().isoformat()
            # days-1: the window is inclusive of both ends, so `days` calendar
            # days ending at `end` start days-1 before it (days=7 → today and
            # the 6 preceding days, matching the client's 7-day dot row).
            start = start_date or (
                date.fromisoformat(end) - timedelta(days=days - 1)
            ).isoformat()

            tracker_query = (
                "SELECT id, name, schedule_json, polarity, type, target_json, meta_json "
                "FROM trackers WHERE deleted = 0"
            )
            params: List[Any] = []
            if tracker_name:
                tracker_query += " AND name LIKE ?"
                params.append(f"%{tracker_name}%")
            trackers = db_manager.execute_safe_query(tracker_query, params)

            results: List[Dict[str, Any]] = []
            for tracker in trackers:
                first = db_manager.execute_safe_query(
                    "SELECT MIN(date) AS first_date FROM entries "
                    f"WHERE tracker_id = ? AND {ENTRY_PRESENT_SQL}",
                    [tracker["id"]],
                )
                first_date = first[0]["first_date"] if first else None
                if not first_date:
                    continue  # nothing ever asserted → nothing to measure
                eff_start = max(start, first_date)
                if eff_start > end:
                    continue  # first activity is after the window → nothing to measure

                rows = db_manager.execute_safe_query(
                    "SELECT date, completed, value FROM entries "
                    "WHERE tracker_id = ? AND date >= ? AND date <= ?",
                    [tracker["id"], eff_start, end],
                )
                entries = {row["date"]: row["completed"] for row in rows}
                values = {row["date"]: row["value"] for row in rows}

                metrics = compute_adherence(
                    tracker["schedule_json"], tracker["polarity"],
                    tracker["type"], entries, eff_start, end,
                    target_json=tracker["target_json"], values=values,
                    meta_json=tracker["meta_json"],
                )
                results.append({
                    "tracker": tracker["name"],
                    "tracker_id": tracker["id"],
                    **metrics,
                })
            return results
        except ValueError:
            raise
        except Exception as e:
            raise ValueError(f"Failed to compute schedule adherence: {str(e)}")

    @mcp.resource("file://journal_data_guide")
    def journal_data_guide() -> str:
        """Complete guide to understanding and querying journal data."""
        return _get_journal_data_guide()

    return mcp


def _get_table_description(table_name: str) -> str:
    """Get human-readable description for table."""
    descriptions = {
        "trackers": "Tracker definitions including habits, supplements, metrics with their categories and types. `deleted=1` means the tracker is hidden from the UI but its entries are kept for historical analysis.",
        "entries": "Daily journal entries recording tracker values and completion status. Entries persist even after their tracker is soft-deleted.",
        "clients": "Client devices that sync with the journal (debug breadcrumb only)",
        "meta_sync": "Sync metadata for client synchronization",
        "entries_archive": "Snapshots of overwritten entry rows, retained 14 days for manual recovery",
        "trackers_archive": "Snapshots of overwritten tracker rows, retained 14 days for manual recovery",
        "sync_conflicts": "Vestigial — no longer written. Predates the optimistic-concurrency sync protocol",
    }
    return descriptions.get(table_name, "Journal data table")


def _get_journal_data_guide() -> str:
    """Get comprehensive guide for journal data analysis."""
    return """
# Journal Data Analysis Guide

## Quick Start
1. Use `list_trackers` to see what habits/metrics are being tracked
2. Use `get_entries` to see recent journal entries
3. Use `get_journal_summary` for a quick overview (entries-based)
4. Use `get_schedule_adherence` for schedule-aware adherence (scheduled vs.
   logged/done per tracker, respecting each tracker's weekday schedule)
5. Use `execute_sql_query` for custom analysis

## Tracker Lifecycle and Historical Data

Tracker IDs are UUIDs. When a tracker is "deleted" it is soft-deleted
(`deleted=1`) — the UI hides it and the sync delta stops sending it to the
client, but the row and all of its entries remain in the database. This
means:

- A user who deletes "B12" and later creates a new "B12" creates two
  distinct tracker rows with the same display name. Their entries are
  linked by UUID, not by name — histories stay structurally separate.
- Historical analysis queries should generally NOT filter `deleted = 0`
  unless you specifically want only currently-active trackers. The
  `get_entries`, `get_journal_summary`, and `execute_sql_query` tools
  return entries for deleted trackers by default.
- `list_trackers` filters deleted trackers by default — pass
  `include_deleted=True` when correlating historical entries with their
  defining tracker row.

## Main Data Tables

### trackers
**WHAT**: Definitions of things being tracked
**COLUMNS**:
- id: Unique identifier (UUID, stable across renames)
- name: Display name (e.g., "Vitamin D3", "Exercise")
- category: Grouping category (e.g., "Supplements", "Habits")
- type: "simple" (checkbox) or "quantifiable" (has a value)
- meta_json: Additional free-form per-tracker settings as JSON — unit,
  defaultValue, accumulator (schedule/polarity are NOT here — see below)
- schedule_json: Canonical weekday schedule (`scheduleHistory` segments as JSON)
- polarity: Canonical `'positive' | 'negative' | 'neutral'` (or NULL)
- target_json: Canonical typed value target (`targetHistory` segments as JSON;
  each segment's `target` is `{min?, max?}` or null)
- deleted: Soft delete flag — 1 means hidden from UI but retained for history
- last_modified_at: Server-stamped timestamp (opaque sync token)

### entries
**WHAT**: Daily tracking records. Persist even after their tracker is deleted.
**COLUMNS**:
- date: The date of the entry (YYYY-MM-DD)
- tracker_id: Links to trackers table (foreign key by UUID)
- value: Numeric value for quantifiable trackers (NULL for simple)
- completed: 1 if completed/checked, 0 otherwise
- last_modified_at: Server-stamped timestamp (opaque sync token)

## Tracker Scheduling, Polarity & Targets (canonical columns)

Three optional per-tracker fields are stored in dedicated, protocol-owned
columns (`trackers.schedule_json`, `trackers.polarity`, `trackers.target_json`)
— **not** in `meta_json`. `list_trackers` still merges them into the returned
`metadata` dict (`metadata.scheduleHistory` / `metadata.polarity` /
`metadata.targetHistory`), so the consumer shape is unchanged; querying the raw
table, read the columns directly:

- `scheduleHistory`: which weekdays a tracker is part of the routine on, as an
  effective-dated list of segments — `[{ "effectiveFrom": "YYYY-MM-DD",
  "days": [0..6] }]`, where 0=Sun..6=Sat. Absent means "daily" (every day). The
  schedule in effect on date D is the segment with the greatest
  `effectiveFrom <= D` (the earliest segment when D precedes all of them). It is
  effective-dated so past days keep the schedule that was in effect then — a
  later change never rewrites history. A segment with an **empty** `days` (`[]`)
  means the tracker was **paused** from that date forward (zero scheduled days →
  hidden in the UI and null adherence rates); treat such a window as an
  intentional pause, not missed days. Interpreting it in SQL needs JSON1
  (`json_extract` / `json_each`).
- `polarity`: `"positive"` (a habit to build), `"negative"` (a behavior to
  avoid), or `"neutral"` (a plain measurement). Absent = unspecified/neutral.
- `targetHistory` (quantifiable trackers): a typed value target, effective-dated
  like the schedule — `[{ "effectiveFrom": "YYYY-MM-DD", "target": {min?, max?} }]`
  (numbers; min-only = at-least, max-only = at-most, both = range, `min==max` =
  exact). A `target: null` segment records a target removed from that date
  forward; absent = no target.

**IMPORTANT — completion vs. adherence:** `get_journal_summary`'s
`completion_rate` (and any completed-over-entries ratio you write — gate its
denominator on entry presence, see Common Queries) is **entries-based** — the
fraction of *present entries* marked completed. It is
**not** schedule adherence, and for value trackers it systematically
**undercounts** (value logging never sets the `completed` checkbox — see Data
epochs). For schedule adherence, use the **`get_schedule_adherence`** tool — it
interprets each tracker's effective-dated weekday schedule per date and reports
scheduled vs. logged/done days, per-polarity (`adherence` / `avoidance` /
`coverage`). When a tracker has a target in effect on a day, "done" for that day
is whether the day's **value** meets the target (not the checkbox), and the
result adds `target` (as of window end), `target_met_days`,
`target_partial_days` (targeted-day-only), and `blended_met_days`; the
per-polarity rate's numerator is `blended_met_days` — on days before the
target took effect it falls back to that day's untargeted criterion
(positive→`adherence_rate`, negative→`avoidance_rate`; neutral keeps
`coverage_rate` = logged/scheduled). No-entry counts as MET for negative
trackers (absence = avoided) and MISSED for positive/neutral. `get_journal_summary`
stays entries-based; keep "scheduled days" and "completion" as separate axes.

## Data epochs

These signals became real on specific dates; do NOT trust comparisons that reach
before them:
- **Weekday schedules (`scheduleHistory`) + polarity:** data exists from
  **2026-07-03**. Before that every tracker was implicitly daily and
  unclassified.
- **Typed targets + target-aware adherence:** from **2026-07-06**.
- **Pre-epoch caveat:** before these dates "done" is only the manual checkbox, so
  completed-based metrics (`completion_rate`, `SUM(completed)`) systematically
  **undercount** accumulator/value trackers (value logging never set the
  checkbox). Treat pre-epoch adherence/completion comparisons accordingly.
- The data is also self-describing temporally: effective-dated genesis splits
  mean a target (or schedule) added later carries a genesis segment for the past
  (a `target: null` genesis → checkbox semantics before it), and adherence
  windows clamp to a tracker's first entry — so a tracker never accrues misses
  before it was in use.

## Tracker Types
- **simple**: Binary yes/no tracking (e.g., "Did I take my vitamins?")
- **quantifiable**: Numeric value tracking (e.g., "How many mg of Zinc?")

## Common Queries

**Gate every judgment on entry presence.** Unchecking a box does not delete the
entry row — the client writes `{completed: 0}` and keeps it, and clearing a
value column leaves the row behind too — so a row can exist while asserting
nothing. An entry counts as present iff `completed = 1 OR value IS NOT NULL`;
an all-empty row must judge exactly like no row. `COUNT(*)` over raw `entries`
therefore over-counts denominators (this is why the tools below build their
counts with that predicate). Raw *listings* are a different question and stay
raw — every stored row is real data to show.

### Active trackers by category (UI-visible only)
```sql
SELECT category, name, type FROM trackers
WHERE deleted = 0 ORDER BY category, name
```

### All trackers including soft-deleted ones
```sql
SELECT category, name, type, deleted FROM trackers
ORDER BY category, name
```

### Completion rate for a tracker across its entire history,
### even if it has been retired
```sql
SELECT t.name, t.deleted,
       COUNT(*) as logged_days,
       SUM(CASE WHEN e.completed = 1 THEN 1 ELSE 0 END) as completed_days,
       ROUND(100.0 * SUM(CASE WHEN e.completed = 1 THEN 1 ELSE 0 END)
             / COUNT(*), 1) as completion_rate
FROM entries e JOIN trackers t ON e.tracker_id = t.id
WHERE t.name = 'Exercise'
  AND (e.completed = 1 OR e.value IS NOT NULL)   -- entry present; see above
GROUP BY t.id, t.name, t.deleted
```
The denominator counts *present* entries, not stored rows. `completed` is
nullable, so it is always compared explicitly (`completed = 1`) rather than
truth-tested or summed bare — in SQLite `NULL OR …` is not false, which is the
trap the presence predicate above is written around. Still entries-based, not
schedule adherence — for that, use `get_schedule_adherence`.

### Daily summary for a date (active trackers only)
```sql
SELECT t.category, t.name, e.completed, e.value
FROM entries e JOIN trackers t ON e.tracker_id = t.id
WHERE e.date = '2026-01-22' AND t.deleted = 0
ORDER BY t.category, t.name
```

### Find a tracker that was renamed via delete-and-recreate
```sql
SELECT id, name, deleted, last_modified_at
FROM trackers
WHERE name LIKE '%B12%'
ORDER BY last_modified_at
```

## Tips
- Join entries with trackers to get meaningful names
- Default to INCLUDING deleted trackers for historical analysis;
  filter to `deleted = 0` only when you need the current UI view
- Tracker IDs are UUIDs — same name twice means two distinct rows
- Use date ranges to analyze trends over time
- Group by category for category-level analysis
    """.strip()


def main():
    """Main entry point for the Journal MCP server."""
    try:
        mcp = create_mcp_server()
        mcp.run()
    except Exception as e:
        print(f"Failed to start MCP server: {e}")
        raise


if __name__ == "__main__":
    main()
