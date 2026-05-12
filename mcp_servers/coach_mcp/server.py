"""Coach MCP Server implementation.

Provides access to workout plans (read-write) and logs (read-only)
through the Model Context Protocol for LLM workout planning and analysis.
"""

import json
import os
import re
import sqlite3
from contextlib import contextmanager
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

# Legacy pair-suffix pattern: rejects names like "Bench Press (Pair A)" so that
# pair info is forced through the structured `superset_group` field instead of
# leaking into canonical_slug.
LEGACY_PAIR_SUFFIX_RE = re.compile(r"\((?:Pair|Superset|Triplet)\b[^)]*\)", re.IGNORECASE)


def _reject_legacy_pair_suffix(name: str, context: str = "") -> None:
    """Raise ValueError if name still uses the deprecated `(Pair X)` suffix."""
    if name and LEGACY_PAIR_SUFFIX_RE.search(name):
        prefix = f"{context}: " if context else ""
        raise ValueError(
            f"{prefix}Exercise name '{name}' uses the legacy pair suffix "
            f"convention. Put pair info in the structured `superset_group` "
            f"field instead (e.g. \"superset_group\": \"A\")."
        )

try:
    from fastmcp import FastMCP
except ImportError:
    raise ImportError(
        "FastMCP is required for MCP server functionality. "
        "Install with: pip install fastmcp"
    )

from .config import MCPConfig
from .exercise_registry import ExerciseRegistry, resolve_plan_exercises

# Default DB path: ../../data/coach.db relative to this file's directory
_DEFAULT_DB_PATH = Path(__file__).resolve().parent.parent.parent / "data" / "coach.db"


class SQLiteConnection:
    """SQLite connection context manager with configurable read/write mode."""

    def __init__(self, db_path: Path, read_only: bool = True):
        self.db_path = db_path
        self.read_only = read_only
        self.conn = None

    def __enter__(self):
        """Open SQLite connection."""
        if self.read_only:
            self.conn = sqlite3.connect(f"file:{self.db_path}?mode=ro", uri=True)
        else:
            self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA busy_timeout = 5000")
        self.conn.execute("PRAGMA foreign_keys = ON")
        return self.conn

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Close connection safely."""
        if self.conn:
            self.conn.close()


class DatabaseManager:
    """Manages database connections and operations."""

    def __init__(self, config: MCPConfig):
        self.config = config

    def get_connection(self, read_only: bool = True):
        """Get database connection."""
        return SQLiteConnection(self.config.db_path, read_only=read_only)

    def execute_query(
        self, query: str, params: Optional[List[Any]] = None, read_only: bool = True
    ) -> List[Dict[str, Any]]:
        """Execute a query and return results."""
        try:
            with self.get_connection(read_only=read_only) as conn:
                cursor = conn.cursor()
                cursor.execute(query, params or [])
                if not read_only:
                    conn.commit()
                results = [dict(row) for row in cursor.fetchall()]
                return results
        except sqlite3.Error as e:
            raise ValueError(f"Database error: {str(e)}")

    def execute_write(
        self, query: str, params: Optional[List[Any]] = None
    ) -> int:
        """Execute a write query and return rows affected."""
        try:
            with self.get_connection(read_only=False) as conn:
                cursor = conn.cursor()
                cursor.execute(query, params or [])
                conn.commit()
                return cursor.rowcount
        except sqlite3.Error as e:
            raise ValueError(f"Database error: {str(e)}")

    @contextmanager
    def transaction(self):
        """Get a cursor for multi-statement transactions."""
        with self.get_connection(read_only=False) as conn:
            cursor = conn.cursor()
            try:
                yield cursor
                conn.commit()
            except Exception:
                conn.rollback()
                raise


def get_utc_now() -> str:
    """Return current UTC time as ISO-8601 string."""
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


# ==================== Plan Storage Helpers ====================


def _insert_block(cursor, session_id, position, block):
    """Insert one block (with its exercises and checklist items) at ``position``.

    Exercises without an explicit ``id`` get a key derived from the block type
    and position; callers needing collision-free keys across an existing
    session should set ``id`` first. Returns the new ``session_blocks`` row id.
    """
    cursor.execute("""
        INSERT INTO session_blocks
        (session_id, position, block_type, title, duration_min, rest_guidance, rounds,
         work_duration_sec, rest_duration_sec)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, [
        session_id, position,
        block.get("block_type", ""),
        block.get("title"),
        block.get("duration_min"),
        block.get("rest_guidance", ""),
        block.get("rounds"),
        block.get("work_duration_sec"),
        block.get("rest_duration_sec"),
    ])
    block_id = cursor.lastrowid

    for j, ex in enumerate(block.get("exercises", [])):
        exercise_key = ex.get("id") or f"{block.get('block_type', 'ex')}_{position}_{j}"
        cursor.execute("""
            INSERT INTO planned_exercises
            (session_id, block_id, exercise_key, position, name, exercise_type,
             target_sets, target_reps, target_duration_min, target_duration_sec,
             rounds, work_duration_sec, rest_duration_sec,
             guidance_note, hide_weight, show_time, superset_group, extra, canonical_slug)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, [
            session_id, block_id, exercise_key, j,
            ex.get("name", "Unknown"),
            ex.get("type", "strength"),
            ex.get("target_sets"),
            ex.get("target_reps"),
            ex.get("target_duration_min"),
            ex.get("target_duration_sec"),
            ex.get("rounds"),
            ex.get("work_duration_sec"),
            ex.get("rest_duration_sec"),
            ex.get("guidance_note"),
            1 if ex.get("hide_weight") else 0,
            1 if ex.get("show_time") else 0,
            ex.get("superset_group"),
            json.dumps(ex["extra"]) if ex.get("extra") else None,
            ex.get("canonical_slug"),
        ])
        exercise_id = cursor.lastrowid

        # Checklist items
        if ex.get("type") == "checklist":
            for k, item in enumerate(ex.get("items", [])):
                cursor.execute("""
                    INSERT INTO checklist_items (exercise_id, position, item_text)
                    VALUES (?, ?, ?)
                """, [exercise_id, k, item])

    return block_id


def _store_plan_to_db(cursor, date_str, plan, modified_by="mcp"):
    """Store a plan dict into normalized tables. Returns session_id."""
    now = get_utc_now()

    # Guard: refuse to replace plans that have workout logs attached.
    # Use update_exercise/add_exercise/remove_exercise instead.
    log_row = cursor.execute(
        "SELECT id FROM workout_session_logs WHERE date = ?", [date_str]
    ).fetchone()
    if log_row:
        raise ValueError(
            f"Cannot replace plan for {date_str}: a workout log exists. "
            f"Use update_exercise, add_exercise, or remove_exercise to edit in place."
        )

    # Delete existing session for this date (CASCADE cleans blocks, exercises, checklist)
    cursor.execute("DELETE FROM workout_sessions WHERE date = ?", [date_str])
    # Clear any tombstone if re-creating a plan for a previously deleted date
    cursor.execute("DELETE FROM deleted_plans WHERE date = ?", [date_str])

    # Insert workout_sessions row
    cursor.execute("""
        INSERT INTO workout_sessions
        (date, day_name, location, phase, duration_min, last_modified, modified_by)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    """, [
        date_str,
        plan.get("day_name", "Workout"),
        plan.get("location"),
        plan.get("phase"),
        plan.get("total_duration_min"),
        now,
        modified_by,
    ])
    session_id = cursor.lastrowid

    # Insert blocks and exercises
    for i, block in enumerate(plan.get("blocks", [])):
        _insert_block(cursor, session_id, i, block)

    return session_id


def _assemble_plan_from_db(cursor, session_id):
    """Assemble a plan dict from relational tables."""
    cursor.execute("SELECT * FROM workout_sessions WHERE id = ?", [session_id])
    session = cursor.fetchone()
    if not session:
        return None

    cursor.execute("""
        SELECT * FROM session_blocks WHERE session_id = ? ORDER BY position
    """, [session_id])
    block_rows = cursor.fetchall()

    blocks = []
    for br in block_rows:
        cursor.execute("""
            SELECT * FROM planned_exercises WHERE block_id = ? ORDER BY position
        """, [br["id"]])
        ex_rows = cursor.fetchall()

        exercises = []
        for er in ex_rows:
            exercise = {
                "id": er["exercise_key"],
                "name": er["name"],
                "type": er["exercise_type"],
            }
            if er["target_sets"] is not None:
                exercise["target_sets"] = er["target_sets"]
            if er["target_reps"] is not None:
                exercise["target_reps"] = er["target_reps"]
            if er["target_duration_min"] is not None:
                exercise["target_duration_min"] = er["target_duration_min"]
            if er["target_duration_sec"] is not None:
                exercise["target_duration_sec"] = er["target_duration_sec"]
            if er["rounds"] is not None:
                exercise["rounds"] = er["rounds"]
            if er["work_duration_sec"] is not None:
                exercise["work_duration_sec"] = er["work_duration_sec"]
            if er["rest_duration_sec"] is not None:
                exercise["rest_duration_sec"] = er["rest_duration_sec"]
            if er["guidance_note"]:
                exercise["guidance_note"] = er["guidance_note"]
            if er["hide_weight"]:
                exercise["hide_weight"] = True
            if er["show_time"]:
                exercise["show_time"] = True
            if er["superset_group"]:
                exercise["superset_group"] = er["superset_group"]

            if er["canonical_slug"]:
                exercise["canonical_slug"] = er["canonical_slug"]

            if er["exercise_type"] == "checklist":
                cursor.execute("""
                    SELECT item_text FROM checklist_items
                    WHERE exercise_id = ? ORDER BY position
                """, [er["id"]])
                exercise["items"] = [r["item_text"] for r in cursor.fetchall()]

            exercises.append(exercise)

        blocks.append({
            "block_index": br["position"],
            "block_type": br["block_type"],
            "title": br["title"],
            "duration_min": br["duration_min"],
            "rest_guidance": br["rest_guidance"] or "",
            "rounds": br["rounds"],
            "work_duration_sec": br["work_duration_sec"],
            "rest_duration_sec": br["rest_duration_sec"],
            "exercises": exercises,
        })

    return {
        "day_name": session["day_name"],
        "location": session["location"],
        "phase": session["phase"],
        "total_duration_min": session["duration_min"],
        "blocks": blocks,
    }


def _needs_transform(plan):
    """Check if block plan has raw LLM exercises that need transformation."""
    for block in plan.get("blocks", []):
        for ex in block.get("exercises", []):
            if "id" not in ex or "type" not in ex:
                return True
    # Cardio blocks with instructions (no exercises) also need transform
    for block in plan.get("blocks", []):
        if "instructions" in block and "exercises" not in block:
            return True
    return False


def create_mcp_server(config: Optional[MCPConfig] = None) -> FastMCP:
    """Create and configure the Coach MCP server."""
    if config is None:
        db_path = Path(os.environ.get("COACH_DB_PATH", str(_DEFAULT_DB_PATH)))
        config = MCPConfig.from_db_path(db_path)

    config.validate()
    db_manager = DatabaseManager(config)
    mcp = FastMCP("Coach Workout Manager")

    # Initialize exercise registry
    registry = ExerciseRegistry()
    with db_manager.get_connection(read_only=True) as conn:
        registry.load(conn.cursor())

    @mcp.tool()
    def get_workout_plan(
        start_date: str,
        end_date: str
    ) -> List[Dict[str, Any]]:
        """WHEN TO USE: When you need to see what workouts are scheduled.

        Retrieves workout plans for the specified date range.

        Args:
            start_date: Start date (YYYY-MM-DD)
            end_date: End date (YYYY-MM-DD)

        Returns:
            List of plans with date and full plan structure including blocks and exercises
        """
        try:
            results = db_manager.execute_query("""
                SELECT id, date, last_modified FROM workout_sessions
                WHERE date >= ? AND date <= ?
                ORDER BY date
            """, [start_date, end_date])

            plans = []
            for row in results:
                with db_manager.get_connection(read_only=True) as conn:
                    cursor = conn.cursor()
                    plan_data = _assemble_plan_from_db(cursor, row["id"])

                plans.append({
                    "date": row["date"],
                    "last_modified": row["last_modified"],
                    "plan": plan_data
                })

            return plans
        except Exception as e:
            raise ValueError(f"Failed to get workout plans: {str(e)}")

    @mcp.tool()
    def get_workout_logs(
        start_date: str,
        end_date: str
    ) -> List[Dict[str, Any]]:
        """WHEN TO USE: When analyzing workout history or performance trends.

        Retrieves completed workout logs for the specified date range.
        This is READ-ONLY - logs are created by the user through the PWA.

        Args:
            start_date: Start date (YYYY-MM-DD)
            end_date: End date (YYYY-MM-DD)

        Returns:
            List of logs with date, exercise completion data, and
            pre/post workout stats (readiness metrics, recovery data, etc.)
            when available
        """
        try:
            results = db_manager.execute_query("""
                SELECT * FROM workout_session_logs
                WHERE date >= ? AND date <= ?
                ORDER BY date
            """, [start_date, end_date])

            logs = []
            for row in results:
                with db_manager.get_connection(read_only=True) as conn:
                    cursor = conn.cursor()
                    log_data = _assemble_log_from_db(
                        cursor, row["id"], session_id=row["session_id"]
                    )

                logs.append({
                    "date": row["date"],
                    "last_modified": row["last_modified"],
                    "log": log_data
                })

            return logs
        except Exception as e:
            raise ValueError(f"Failed to get workout logs: {str(e)}")

    @mcp.tool()
    def set_workout_plan(
        date: str,
        plan: Dict[str, Any]
    ) -> Dict[str, Any]:
        """WHEN TO USE: When creating or updating a workout plan for a specific date.

        Creates or replaces the workout plan for the given date. Plans must use
        block-based format with warmup/strength/cardio blocks containing exercises.

        Args:
            date: Target date (YYYY-MM-DD)
            plan: Plan object with ``blocks`` array. Can be raw LLM format
                  (exercises without id/type) or pre-transformed format.

                Block format:
                {
                    "day_name": "Upper Body Strength",
                    "location": "Gym",
                    "phase": "Building",
                    "blocks": [
                        {
                            "block_type": "warmup",
                            "title": "Warmup",
                            "exercises": [
                                {"name": "Arm Circles", "reps": 10}
                            ]
                        },
                        {
                            "block_type": "strength",
                            "title": "Main Lifts",
                            "rest_guidance": "Rest 2-3 min",
                            "exercises": [
                                {"name": "Bench Press", "sets": 4, "reps": "6-8"}
                            ]
                        }
                    ]
                }

        Returns:
            Success confirmation with the saved plan
        """
        # Validate date format
        try:
            datetime.strptime(date, "%Y-%m-%d")
        except ValueError:
            raise ValueError(f"Invalid date format: {date}. Use YYYY-MM-DD")

        # Validate plan structure
        if not isinstance(plan, dict):
            raise ValueError("Plan must be a dictionary")

        if "blocks" not in plan:
            raise ValueError("Plan must have 'blocks'")

        if not isinstance(plan["blocks"], list):
            raise ValueError("Plan blocks must be a list")

        # Validate blocks
        valid_block_types = ["warmup", "strength", "cardio", "circuit", "accessory", "power"]
        for i, block in enumerate(plan["blocks"]):
            if "block_type" not in block:
                raise ValueError(f"Block {i} missing 'block_type' field")
            if not isinstance(block["block_type"], str):
                raise ValueError(f"Block {i} 'block_type' must be a string")
            if block["block_type"] not in valid_block_types:
                raise ValueError(
                    f"Block {i} has invalid block_type: {block['block_type']}. "
                    f"Must be one of: {valid_block_types}"
                )
            if "exercises" not in block and "instructions" not in block:
                raise ValueError(f"Block {i} must have either 'exercises' or 'instructions'")

        # Transform raw LLM format if needed
        if _needs_transform(plan):
            plan = _transform_block_plan(plan)

        # Ensure day_name exists
        if "day_name" not in plan:
            plan["day_name"] = plan.get("theme", "Workout")

        # Validate exercises in blocks
        valid_types = ["strength", "duration", "checklist", "weighted_time", "interval", "circuit"]
        for block in plan.get("blocks", []):
            for i, exercise in enumerate(block.get("exercises", [])):
                if "id" not in exercise:
                    raise ValueError(f"Exercise {i} missing 'id' field")
                if "name" not in exercise:
                    raise ValueError(f"Exercise {i} missing 'name' field")
                if "type" not in exercise:
                    raise ValueError(f"Exercise {i} missing 'type' field")
                if exercise["type"] not in valid_types:
                    raise ValueError(
                        f"Exercise {i} has invalid type: {exercise['type']}. "
                        f"Must be one of: {valid_types}"
                    )
                _reject_legacy_pair_suffix(exercise["name"], f"Exercise {i}")

        try:
            with db_manager.transaction() as cursor:
                # Resolve exercise names to canonical slugs
                resolution_report = resolve_plan_exercises(registry, plan, cursor)

                _store_plan_to_db(cursor, date, plan, "mcp")

                # Assemble the saved plan for response
                cursor.execute("SELECT id FROM workout_sessions WHERE date = ?", [date])
                session = cursor.fetchone()
                saved_plan = _assemble_plan_from_db(cursor, session["id"])

            return {
                "success": True,
                "date": date,
                "last_modified": get_utc_now(),
                "plan": saved_plan,
                "exercise_resolution": resolution_report,
                "message": f"Workout plan for {date} saved successfully"
            }
        except Exception as e:
            raise ValueError(f"Failed to save workout plan: {str(e)}")

    @mcp.tool()
    def get_workout_summary(days: int = 30) -> Dict[str, Any]:
        """WHEN TO USE: When you want a quick overview of workout activity.

        Provides summary statistics about workout plans and completed logs.

        Args:
            days: Number of recent days to analyze (max 365, default: 30)

        Returns:
            Summary including planned vs completed workouts, exercise counts, etc.
        """
        if days > 365:
            raise ValueError("Days cannot exceed 365")

        try:
            start_date = (date.today() - timedelta(days=days)).isoformat()
            end_date = date.today().isoformat()

            # Count planned workouts
            plans_result = db_manager.execute_query("""
                SELECT COUNT(*) as count FROM workout_sessions
                WHERE date >= ? AND date <= ?
            """, [start_date, end_date])
            planned_count = plans_result[0]["count"] if plans_result else 0

            # Count completed workouts
            logs_result = db_manager.execute_query("""
                SELECT COUNT(*) as count FROM workout_session_logs
                WHERE date >= ? AND date <= ?
            """, [start_date, end_date])
            completed_count = logs_result[0]["count"] if logs_result else 0

            # Exercise type breakdown from recent plans
            exercise_types_result = db_manager.execute_query("""
                SELECT pe.exercise_type, COUNT(*) as count
                FROM planned_exercises pe
                JOIN workout_sessions ws ON pe.session_id = ws.id
                WHERE ws.date >= ? AND ws.date <= ?
                GROUP BY pe.exercise_type
                ORDER BY count DESC
                LIMIT 7
            """, [start_date, end_date])

            exercise_types = {}
            for row in exercise_types_result:
                exercise_types[row["exercise_type"]] = row["count"]

            # Recent plan dates
            recent_dates_result = db_manager.execute_query("""
                SELECT date FROM workout_sessions
                WHERE date >= ? AND date <= ?
                ORDER BY date DESC
                LIMIT 7
            """, [start_date, end_date])

            completion_rate = round(completed_count / planned_count * 100, 1) if planned_count > 0 else 0

            return {
                "analysis_period_days": days,
                "planned_workouts": planned_count,
                "completed_workouts": completed_count,
                "completion_rate_percent": completion_rate,
                "exercise_types_in_recent_plans": exercise_types,
                "recent_plan_dates": [row["date"] for row in recent_dates_result]
            }
        except Exception as e:
            raise ValueError(f"Failed to generate summary: {str(e)}")

    @mcp.tool()
    def list_scheduled_dates(
        start_date: Optional[str] = None,
        end_date: Optional[str] = None
    ) -> List[str]:
        """WHEN TO USE: When you need to see which dates have plans scheduled.

        Returns a list of dates that have workout plans.

        Args:
            start_date: Start date (YYYY-MM-DD), defaults to today
            end_date: End date (YYYY-MM-DD), defaults to 6 weeks from today

        Returns:
            List of dates (YYYY-MM-DD) that have plans
        """
        try:
            if not start_date:
                start_date = date.today().isoformat()
            if not end_date:
                end_date = (date.today() + timedelta(weeks=6)).isoformat()

            results = db_manager.execute_query("""
                SELECT date FROM workout_sessions
                WHERE date >= ? AND date <= ?
                ORDER BY date
            """, [start_date, end_date])

            return [row["date"] for row in results]
        except Exception as e:
            raise ValueError(f"Failed to list scheduled dates: {str(e)}")

    @mcp.tool()
    def ingest_training_program(
        plans: Dict[str, Dict[str, Any]],
        transform_blocks: bool = True
    ) -> Dict[str, Any]:
        """WHEN TO USE: When loading a complete training program with multiple workout dates.

        Bulk ingests multiple workout plans at once.

        Args:
            plans: Dictionary mapping dates (YYYY-MM-DD) to plan objects with blocks.
            transform_blocks: If True, transforms raw LLM block format.
                            Set to False if plans are already transformed.

        Returns:
            Summary of ingestion results with success/failure counts
        """
        results = {"success": [], "failed": [], "total": len(plans)}

        for date_str, plan_data in sorted(plans.items()):
            try:
                # Validate date format
                datetime.strptime(date_str, "%Y-%m-%d")

                # Transform if needed
                if transform_blocks and "blocks" in plan_data and _needs_transform(plan_data):
                    plan = _transform_block_plan(plan_data)
                else:
                    plan = plan_data

                # Ensure day_name exists
                if "day_name" not in plan:
                    plan["day_name"] = plan_data.get("theme", "Workout")

                if "blocks" not in plan or not plan["blocks"]:
                    raise ValueError("Plan must have blocks")

                # Check that blocks have exercises
                has_exercises = False
                for block in plan["blocks"]:
                    if block.get("exercises"):
                        has_exercises = True
                        break
                if not has_exercises:
                    raise ValueError("Plan must have exercises")

                with db_manager.transaction() as cursor:
                    resolve_plan_exercises(registry, plan, cursor)
                    _store_plan_to_db(cursor, date_str, plan, "mcp")
                results["success"].append(date_str)

            except Exception as e:
                results["failed"].append({"date": date_str, "error": str(e)})

        return {
            "message": f"Ingested {len(results['success'])} of {results['total']} plans",
            "success_count": len(results["success"]),
            "failed_count": len(results["failed"]),
            "success_dates": results["success"],
            "failed": results["failed"]
        }

    @mcp.tool()
    def update_exercise(
        date: str,
        exercise_id: str,
        updates: Dict[str, Any]
    ) -> Dict[str, Any]:
        """WHEN TO USE: When modifying a specific exercise within an existing plan.

        Updates fields of a specific exercise without replacing the entire plan.

        Args:
            date: Date of the plan (YYYY-MM-DD)
            exercise_id: Exercise key (e.g., "ex_1", "warmup_0")
            updates: Dictionary of fields to update. Can include:
                     name, type, target_sets, target_reps, target_duration_min,
                     guidance_note, items, hide_weight, show_time

        Returns:
            Updated exercise and confirmation
        """
        # Map update keys to column names
        column_map = {
            "name": "name",
            "type": "exercise_type",
            "target_sets": "target_sets",
            "target_reps": "target_reps",
            "target_duration_min": "target_duration_min",
            "target_duration_sec": "target_duration_sec",
            "rounds": "rounds",
            "work_duration_sec": "work_duration_sec",
            "rest_duration_sec": "rest_duration_sec",
            "guidance_note": "guidance_note",
            "hide_weight": "hide_weight",
            "show_time": "show_time",
            "superset_group": "superset_group",
        }

        if "name" in updates:
            _reject_legacy_pair_suffix(updates["name"])

        try:
            with db_manager.transaction() as cursor:
                # Find the exercise
                cursor.execute("""
                    SELECT pe.id, pe.session_id FROM planned_exercises pe
                    JOIN workout_sessions ws ON pe.session_id = ws.id
                    WHERE ws.date = ? AND pe.exercise_key = ?
                """, [date, exercise_id])
                row = cursor.fetchone()

                if not row:
                    raise ValueError(f"Exercise '{exercise_id}' not found in plan for {date}")

                pe_id = row["id"]
                session_id = row["session_id"]

                # Build UPDATE statement for mapped columns
                set_clauses = []
                params = []
                for key, value in updates.items():
                    if key == "items":
                        continue  # handled separately
                    col = column_map.get(key)
                    if col:
                        if key in ("hide_weight", "show_time"):
                            value = 1 if value else 0
                        set_clauses.append(f"{col} = ?")
                        params.append(value)

                # If name is changing, resolve new canonical slug
                if "name" in updates:
                    from .exercise_registry import generate_slug, _infer_equipment, _get_utc_now as _reg_utc_now
                    new_name = updates["name"]
                    slug, match_type = registry.resolve(new_name)
                    if match_type == "new":
                        slug = generate_slug(new_name)
                        now_reg = _reg_utc_now()
                        cursor.execute("""
                            INSERT OR IGNORE INTO exercises (slug, name, equipment, category, created_at, source)
                            VALUES (?, ?, ?, ?, ?, ?)
                        """, [slug, new_name, None, None, now_reg, "auto"])
                        registry.add(slug, new_name, None, None)
                    set_clauses.append("canonical_slug = ?")
                    params.append(slug)

                if set_clauses:
                    params.append(pe_id)
                    cursor.execute(
                        f"UPDATE planned_exercises SET {', '.join(set_clauses)} WHERE id = ?",
                        params
                    )

                # Update checklist items if provided
                if "items" in updates:
                    cursor.execute("DELETE FROM checklist_items WHERE exercise_id = ?", [pe_id])
                    for k, item in enumerate(updates["items"]):
                        cursor.execute("""
                            INSERT INTO checklist_items (exercise_id, position, item_text)
                            VALUES (?, ?, ?)
                        """, [pe_id, k, item])

                # Update session last_modified
                now = get_utc_now()
                cursor.execute("""
                    UPDATE workout_sessions SET last_modified = ?, modified_by = ?
                    WHERE id = ?
                """, [now, "mcp", session_id])

                # Assemble updated exercise for response
                updated = _assemble_plan_from_db(cursor, session_id)
                updated_exercise = None
                for block in updated.get("blocks", []):
                    for ex in block.get("exercises", []):
                        if ex["id"] == exercise_id:
                            updated_exercise = ex
                            break

            return {
                "success": True,
                "date": date,
                "exercise_id": exercise_id,
                "updated_exercise": updated_exercise,
                "message": f"Exercise '{exercise_id}' updated successfully"
            }
        except Exception as e:
            raise ValueError(f"Failed to update exercise: {str(e)}")

    @mcp.tool()
    def add_exercise(
        date: str,
        exercise: Dict[str, Any],
        block_position: int = 0,
        position: Optional[int] = None
    ) -> Dict[str, Any]:
        """WHEN TO USE: When adding a new exercise to an existing workout plan.

        Adds a new exercise to a specific block in the plan.

        Args:
            date: Date of the plan (YYYY-MM-DD)
            exercise: Exercise object with required fields (id, name, type)
            block_position: Which block to add to (0-indexed). Default: 0.
            position: Index within the block (0 = beginning). None = append to end.

        Returns:
            Confirmation with updated exercise count
        """
        # Validate exercise
        required = ["id", "name", "type"]
        for field in required:
            if field not in exercise:
                raise ValueError(f"Exercise missing required field: {field}")

        valid_types = ["strength", "duration", "checklist", "weighted_time", "interval", "circuit"]
        if exercise["type"] not in valid_types:
            raise ValueError(f"Invalid exercise type: {exercise['type']}")

        _reject_legacy_pair_suffix(exercise["name"])

        try:
            with db_manager.transaction() as cursor:
                # Get session
                cursor.execute("""
                    SELECT id FROM workout_sessions WHERE date = ?
                """, [date])
                session = cursor.fetchone()
                if not session:
                    raise ValueError(f"No plan found for date: {date}")
                session_id = session["id"]

                # Check for duplicate exercise key
                cursor.execute("""
                    SELECT id FROM planned_exercises
                    WHERE session_id = ? AND exercise_key = ?
                """, [session_id, exercise["id"]])
                if cursor.fetchone():
                    raise ValueError(f"Exercise ID '{exercise['id']}' already exists in plan")

                # Find the target block
                cursor.execute("""
                    SELECT id FROM session_blocks
                    WHERE session_id = ? AND position = ?
                """, [session_id, block_position])
                block = cursor.fetchone()
                if not block:
                    raise ValueError(f"Block at position {block_position} not found")
                block_id = block["id"]

                # Determine position
                if position is None:
                    cursor.execute("""
                        SELECT COALESCE(MAX(position), -1) + 1 as next_pos
                        FROM planned_exercises WHERE block_id = ?
                    """, [block_id])
                    position = cursor.fetchone()["next_pos"]
                else:
                    # Shift existing exercises at >= position
                    cursor.execute("""
                        UPDATE planned_exercises SET position = position + 1
                        WHERE block_id = ? AND position >= ?
                    """, [block_id, position])

                # Resolve canonical slug for the new exercise
                from .exercise_registry import generate_slug, _infer_equipment, _get_utc_now as _reg_utc_now
                slug, match_type = registry.resolve(exercise["name"])
                if match_type == "new":
                    slug = generate_slug(exercise["name"])
                    equip = _infer_equipment(exercise)
                    now_reg = _reg_utc_now()
                    cursor.execute("""
                        INSERT OR IGNORE INTO exercises (slug, name, equipment, category, created_at, source)
                        VALUES (?, ?, ?, ?, ?, ?)
                    """, [slug, exercise["name"], equip, None, now_reg, "auto"])
                    registry.add(slug, exercise["name"], equip, None)

                # Insert exercise
                cursor.execute("""
                    INSERT INTO planned_exercises
                    (session_id, block_id, exercise_key, position, name, exercise_type,
                     target_sets, target_reps, target_duration_min, target_duration_sec,
                     rounds, work_duration_sec, rest_duration_sec,
                     guidance_note, hide_weight, show_time, superset_group, canonical_slug)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, [
                    session_id, block_id, exercise["id"], position,
                    exercise["name"], exercise["type"],
                    exercise.get("target_sets"),
                    exercise.get("target_reps"),
                    exercise.get("target_duration_min"),
                    exercise.get("target_duration_sec"),
                    exercise.get("rounds"),
                    exercise.get("work_duration_sec"),
                    exercise.get("rest_duration_sec"),
                    exercise.get("guidance_note"),
                    1 if exercise.get("hide_weight") else 0,
                    1 if exercise.get("show_time") else 0,
                    exercise.get("superset_group"),
                    slug,
                ])
                exercise_id = cursor.lastrowid

                # Checklist items
                if exercise.get("type") == "checklist":
                    for k, item in enumerate(exercise.get("items", [])):
                        cursor.execute("""
                            INSERT INTO checklist_items (exercise_id, position, item_text)
                            VALUES (?, ?, ?)
                        """, [exercise_id, k, item])

                # Update session last_modified
                now = get_utc_now()
                cursor.execute("""
                    UPDATE workout_sessions SET last_modified = ?, modified_by = ?
                    WHERE id = ?
                """, [now, "mcp", session_id])

                # Count total exercises
                cursor.execute("""
                    SELECT COUNT(*) as count FROM planned_exercises
                    WHERE session_id = ?
                """, [session_id])
                total = cursor.fetchone()["count"]

            return {
                "success": True,
                "date": date,
                "added_exercise": exercise,
                "total_exercises": total,
                "message": f"Exercise '{exercise['id']}' added successfully"
            }
        except Exception as e:
            raise ValueError(f"Failed to add exercise: {str(e)}")

    @mcp.tool()
    def remove_exercise(
        date: str,
        exercise_id: str
    ) -> Dict[str, Any]:
        """WHEN TO USE: When removing an exercise from an existing workout plan.

        Removes an exercise by ID from the specified plan. CASCADE handles
        cleanup of associated checklist_items.

        Args:
            date: Date of the plan (YYYY-MM-DD)
            exercise_id: Exercise key to remove

        Returns:
            Confirmation with updated exercise count
        """
        try:
            with db_manager.transaction() as cursor:
                # Find the exercise
                cursor.execute("""
                    SELECT pe.id, pe.session_id FROM planned_exercises pe
                    JOIN workout_sessions ws ON pe.session_id = ws.id
                    WHERE ws.date = ? AND pe.exercise_key = ?
                """, [date, exercise_id])
                row = cursor.fetchone()

                if not row:
                    raise ValueError(f"Exercise '{exercise_id}' not found in plan for {date}")

                session_id = row["session_id"]

                # Delete exercise (CASCADE handles checklist_items)
                cursor.execute("DELETE FROM planned_exercises WHERE id = ?", [row["id"]])

                # Update session last_modified
                now = get_utc_now()
                cursor.execute("""
                    UPDATE workout_sessions SET last_modified = ?, modified_by = ?
                    WHERE id = ?
                """, [now, "mcp", session_id])

                # Count remaining exercises
                cursor.execute("""
                    SELECT COUNT(*) as count FROM planned_exercises
                    WHERE session_id = ?
                """, [session_id])
                remaining = cursor.fetchone()["count"]

            return {
                "success": True,
                "date": date,
                "removed_exercise_id": exercise_id,
                "remaining_exercises": remaining,
                "message": f"Exercise '{exercise_id}' removed successfully"
            }
        except Exception as e:
            raise ValueError(f"Failed to remove exercise: {str(e)}")

    @mcp.tool()
    def delete_workout_plan(date: str) -> Dict[str, Any]:
        """WHEN TO USE: When removing a workout plan entirely for a specific date.
        Only future/unlogged plans can be deleted. Plans with workout logs
        attached CANNOT be deleted — this is by design to preserve training
        history integrity.

        Deletes the entire workout plan for the specified date.
        CASCADE handles cleanup of blocks, exercises, and checklist items.

        Args:
            date: Date of the plan to delete (YYYY-MM-DD)

        Returns:
            Confirmation of deletion
        """
        try:
            # Validate date format
            datetime.strptime(date, "%Y-%m-%d")

            # Check if plan exists
            results = db_manager.execute_query(
                "SELECT id FROM workout_sessions WHERE date = ?", [date]
            )
            if not results:
                raise ValueError(f"No plan found for date: {date}")

            # Guard: refuse to delete plans that have workout logs attached.
            # Logs represent completed training data recorded by the user and
            # must never be orphaned. If the user wants to change a past
            # workout, edit the plan instead of deleting it. Do NOT attempt
            # to work around this by deleting the log first — the log is the
            # user's training record and is immutable.
            log_results = db_manager.execute_query(
                "SELECT id FROM workout_session_logs WHERE date = ?", [date]
            )
            if log_results:
                raise ValueError(
                    f"Cannot delete workout plan for {date}: a workout log "
                    f"exists for this date. Logs represent the user's completed "
                    f"training data and must be preserved. If the plan needs "
                    f"changes, use update_exercise, add_exercise, remove_exercise, "
                    f"or update_plan_metadata to edit it in place. Do NOT delete "
                    f"the log to work around this restriction."
                )

            # Delete plan and insert tombstone for incremental sync
            with db_manager.transaction() as cursor:
                cursor.execute(
                    "DELETE FROM workout_sessions WHERE date = ?", [date]
                )
                now = get_utc_now()
                cursor.execute(
                    "INSERT OR REPLACE INTO deleted_plans (date, deleted_at) VALUES (?, ?)",
                    [date, now]
                )

            return {
                "success": True,
                "date": date,
                "message": f"Workout plan for {date} deleted successfully"
            }
        except Exception as e:
            raise ValueError(f"Failed to delete workout plan: {str(e)}")

    @mcp.tool()
    def update_plan_metadata(
        date: str,
        updates: Dict[str, Any]
    ) -> Dict[str, Any]:
        """WHEN TO USE: When updating plan metadata without changing exercises.

        Updates plan-level fields like day_name, location, phase.

        Args:
            date: Date of the plan (YYYY-MM-DD)
            updates: Fields to update: day_name, location, phase, total_duration_min

        Returns:
            Updated plan metadata
        """
        column_map = {
            "day_name": "day_name",
            "location": "location",
            "phase": "phase",
            "total_duration_min": "duration_min",
        }

        allowed_fields = set(column_map.keys())
        invalid_fields = set(updates.keys()) - allowed_fields
        if invalid_fields:
            raise ValueError(f"Invalid metadata fields: {invalid_fields}. Allowed: {allowed_fields}")

        try:
            with db_manager.transaction() as cursor:
                cursor.execute("SELECT id FROM workout_sessions WHERE date = ?", [date])
                session = cursor.fetchone()
                if not session:
                    raise ValueError(f"No plan found for date: {date}")

                session_id = session["id"]

                # Build UPDATE
                set_clauses = []
                params = []
                for key, value in updates.items():
                    col = column_map[key]
                    set_clauses.append(f"{col} = ?")
                    params.append(value)

                now = get_utc_now()
                set_clauses.append("last_modified = ?")
                params.append(now)
                set_clauses.append("modified_by = ?")
                params.append("mcp")
                params.append(session_id)

                cursor.execute(
                    f"UPDATE workout_sessions SET {', '.join(set_clauses)} WHERE id = ?",
                    params
                )

                # Get exercise count
                cursor.execute("""
                    SELECT COUNT(*) as count FROM planned_exercises
                    WHERE session_id = ?
                """, [session_id])
                exercise_count = cursor.fetchone()["count"]

                # Get updated metadata
                cursor.execute("SELECT * FROM workout_sessions WHERE id = ?", [session_id])
                updated = cursor.fetchone()

            return {
                "success": True,
                "date": date,
                "updated_fields": list(updates.keys()),
                "plan_metadata": {
                    "day_name": updated["day_name"],
                    "location": updated["location"],
                    "phase": updated["phase"],
                    "total_duration_min": updated["duration_min"],
                    "exercise_count": exercise_count
                },
                "message": "Plan metadata updated successfully"
            }
        except Exception as e:
            raise ValueError(f"Failed to update plan metadata: {str(e)}")

    # ---- Block-level edits (in place; don't rebuild the plan) ----

    _VALID_BLOCK_TYPES = ["warmup", "strength", "cardio", "circuit", "accessory", "power"]
    _BLOCK_COLUMN_MAP = {
        "block_type": "block_type",
        "title": "title",
        "duration_min": "duration_min",
        "rest_guidance": "rest_guidance",
        "rounds": "rounds",
        "work_duration_sec": "work_duration_sec",
        "rest_duration_sec": "rest_duration_sec",
    }

    @mcp.tool()
    def update_block(
        date: str,
        block_position: int,
        updates: Dict[str, Any]
    ) -> Dict[str, Any]:
        """WHEN TO USE: When changing a block's settings — its type, title, total
        duration, rest guidance, or circuit/interval timing — without rebuilding
        the whole plan.

        Patches block-level fields of one block in an existing plan. Block-level
        timing (rounds / work_duration_sec / rest_duration_sec) is canonical here:
        for circuit and interval blocks edit it on the block, not on the
        individual exercises.

        Args:
            date: Date of the plan (YYYY-MM-DD)
            block_position: 0-indexed position of the block within the plan
            updates: Fields to change. Allowed: block_type, title, duration_min,
                     rest_guidance, rounds, work_duration_sec, rest_duration_sec.
                     Pass null to clear a nullable field.

        Returns:
            The updated block, reassembled from the database
        """
        if not updates:
            raise ValueError("No updates provided")
        invalid_fields = set(updates.keys()) - set(_BLOCK_COLUMN_MAP.keys())
        if invalid_fields:
            raise ValueError(
                f"Invalid block fields: {sorted(invalid_fields)}. "
                f"Allowed: {sorted(_BLOCK_COLUMN_MAP.keys())}"
            )
        if "block_type" in updates and updates["block_type"] not in _VALID_BLOCK_TYPES:
            raise ValueError(
                f"Invalid block_type: {updates['block_type']}. "
                f"Must be one of: {_VALID_BLOCK_TYPES}"
            )

        try:
            with db_manager.transaction() as cursor:
                cursor.execute("""
                    SELECT sb.id, sb.session_id FROM session_blocks sb
                    JOIN workout_sessions ws ON sb.session_id = ws.id
                    WHERE ws.date = ? AND sb.position = ?
                """, [date, block_position])
                row = cursor.fetchone()
                if not row:
                    raise ValueError(
                        f"No block at position {block_position} in plan for {date}"
                    )
                block_id = row["id"]
                session_id = row["session_id"]

                set_clauses = []
                params: List[Any] = []
                for key, value in updates.items():
                    set_clauses.append(f"{_BLOCK_COLUMN_MAP[key]} = ?")
                    params.append(value)
                params.append(block_id)
                cursor.execute(
                    f"UPDATE session_blocks SET {', '.join(set_clauses)} WHERE id = ?",
                    params,
                )

                now = get_utc_now()
                cursor.execute("""
                    UPDATE workout_sessions SET last_modified = ?, modified_by = ?
                    WHERE id = ?
                """, [now, "mcp", session_id])

                updated_plan = _assemble_plan_from_db(cursor, session_id)
                updated_block = next(
                    (b for b in updated_plan["blocks"]
                     if b["block_index"] == block_position),
                    None,
                )

            return {
                "success": True,
                "date": date,
                "block_position": block_position,
                "updated_fields": list(updates.keys()),
                "block": updated_block,
                "message": f"Block {block_position} updated successfully",
            }
        except Exception as e:
            raise ValueError(f"Failed to update block: {str(e)}")

    @mcp.tool()
    def add_block(
        date: str,
        block: Dict[str, Any],
        position: Optional[int] = None
    ) -> Dict[str, Any]:
        """WHEN TO USE: When inserting a new block into an existing plan — a
        finisher, an extra accessory block, a cardio block — without rebuilding
        the plan.

        The block may carry inline ``exercises`` (raw LLM or transformed form)
        or ``instructions`` (cardio text); they're normalized and stored the
        same way set_workout_plan does. An empty block (no exercises) is
        allowed — populate it later with add_exercise.

        Args:
            date: Date of the plan (YYYY-MM-DD)
            block: Block object. Requires ``block_type`` (warmup | strength |
                   cardio | circuit | accessory | power). Optional: title,
                   duration_min, rest_guidance, rounds, work_duration_sec,
                   rest_duration_sec, and exercises | instructions.
            position: 0-indexed insert position. None (default) appends to the
                      end; otherwise blocks at >= position shift down by one.

        Returns:
            Confirmation with the inserted block's index, the new block count,
            and the reassembled block (so you can see the final exercise ids)
        """
        if not isinstance(block, dict):
            raise ValueError("block must be a dictionary")
        if "block_type" not in block:
            raise ValueError("block missing 'block_type' field")
        if block["block_type"] not in _VALID_BLOCK_TYPES:
            raise ValueError(
                f"Invalid block_type: {block['block_type']}. "
                f"Must be one of: {_VALID_BLOCK_TYPES}"
            )

        # Normalize inline exercises/instructions through the same transform
        # set_workout_plan uses.
        fragment = {"blocks": [dict(block)]}
        if _needs_transform(fragment):
            fragment = _transform_block_plan(fragment)
        norm_block = fragment["blocks"][0]
        # The transform's full-plan wrapper keeps only the standard block
        # fields, so re-apply any block-level value the caller set explicitly.
        for key in ("title", "duration_min", "rest_guidance", "rounds",
                    "work_duration_sec", "rest_duration_sec"):
            if key in block and block[key] is not None:
                norm_block[key] = block[key]

        valid_ex_types = ["strength", "duration", "checklist",
                          "weighted_time", "interval", "circuit"]
        for i, ex in enumerate(norm_block.get("exercises", [])):
            for field in ("id", "name", "type"):
                if not ex.get(field):
                    raise ValueError(f"Exercise {i} in block missing '{field}'")
            if ex["type"] not in valid_ex_types:
                raise ValueError(
                    f"Exercise {i} has invalid type: {ex['type']}. "
                    f"Must be one of: {valid_ex_types}"
                )
            _reject_legacy_pair_suffix(ex["name"], f"Exercise {i}")

        try:
            with db_manager.transaction() as cursor:
                cursor.execute("SELECT id FROM workout_sessions WHERE date = ?", [date])
                session = cursor.fetchone()
                if not session:
                    raise ValueError(f"No plan found for date: {date}")
                session_id = session["id"]

                cursor.execute(
                    "SELECT COUNT(*) AS c FROM session_blocks WHERE session_id = ?",
                    [session_id],
                )
                block_count = cursor.fetchone()["c"]
                if position is None or position >= block_count:
                    insert_pos = block_count
                else:
                    insert_pos = max(0, position)
                    # Make room: walk top-down so each target slot is vacated
                    # before it's filled (session_blocks has UNIQUE(session_id,
                    # position)).
                    rows = cursor.execute("""
                        SELECT id, position FROM session_blocks
                        WHERE session_id = ? AND position >= ?
                        ORDER BY position DESC
                    """, [session_id, insert_pos]).fetchall()
                    for r in rows:
                        cursor.execute(
                            "UPDATE session_blocks SET position = ? WHERE id = ?",
                            [r["position"] + 1, r["id"]],
                        )

                # Keep new exercise keys collision-free within the session.
                existing_keys = {
                    r["exercise_key"] for r in cursor.execute(
                        "SELECT exercise_key FROM planned_exercises WHERE session_id = ?",
                        [session_id],
                    ).fetchall()
                }
                for ex in norm_block.get("exercises", []):
                    key = ex["id"]
                    if key in existing_keys:
                        n = 2
                        while f"{key}_{n}" in existing_keys:
                            n += 1
                        key = f"{key}_{n}"
                    ex["id"] = key
                    existing_keys.add(key)

                # Resolve names → canonical slugs (creates registry entries).
                resolve_plan_exercises(registry, {"blocks": [norm_block]}, cursor)

                _insert_block(cursor, session_id, insert_pos, norm_block)

                now = get_utc_now()
                cursor.execute(
                    "UPDATE workout_sessions SET last_modified = ?, modified_by = ? WHERE id = ?",
                    [now, "mcp", session_id],
                )

                updated_plan = _assemble_plan_from_db(cursor, session_id)
                inserted = next(
                    (b for b in updated_plan["blocks"]
                     if b["block_index"] == insert_pos),
                    None,
                )
                total_blocks = len(updated_plan["blocks"])

            return {
                "success": True,
                "date": date,
                "block_index": insert_pos,
                "total_blocks": total_blocks,
                "block": inserted,
                "message": f"Block added at position {insert_pos}",
            }
        except Exception as e:
            raise ValueError(f"Failed to add block: {str(e)}")

    @mcp.tool()
    def remove_block(
        date: str,
        block_position: int,
        force: bool = False
    ) -> Dict[str, Any]:
        """WHEN TO USE: When dropping a whole block from a plan.

        Removes the block at ``block_position`` and re-packs the remaining
        block positions. Refuses to remove a block that still has exercises
        unless ``force=True`` — pass force to drop the block and its exercises
        together. (As with remove_exercise, removing an exercise that has a
        workout log leaves that log entry without a matching plan exercise.)

        Args:
            date: Date of the plan (YYYY-MM-DD)
            block_position: 0-indexed position of the block to remove
            force: Required to remove a block that still contains exercises

        Returns:
            Confirmation with the removed exercise count and remaining block count
        """
        try:
            with db_manager.transaction() as cursor:
                cursor.execute("""
                    SELECT sb.id, sb.session_id FROM session_blocks sb
                    JOIN workout_sessions ws ON sb.session_id = ws.id
                    WHERE ws.date = ? AND sb.position = ?
                """, [date, block_position])
                row = cursor.fetchone()
                if not row:
                    raise ValueError(
                        f"No block at position {block_position} in plan for {date}"
                    )
                block_id = row["id"]
                session_id = row["session_id"]

                ex_count = cursor.execute(
                    "SELECT COUNT(*) AS c FROM planned_exercises WHERE block_id = ?",
                    [block_id],
                ).fetchone()["c"]
                if ex_count > 0 and not force:
                    raise ValueError(
                        f"Block {block_position} has {ex_count} exercise(s). "
                        f"Remove them first, or pass force=true to drop the block "
                        f"and its exercises together."
                    )

                # Delete the block (CASCADE drops its exercises + checklist items).
                cursor.execute("DELETE FROM session_blocks WHERE id = ?", [block_id])

                # Re-pack the positions of the blocks that came after it.
                rows = cursor.execute("""
                    SELECT id, position FROM session_blocks
                    WHERE session_id = ? AND position > ?
                    ORDER BY position ASC
                """, [session_id, block_position]).fetchall()
                for r in rows:
                    cursor.execute(
                        "UPDATE session_blocks SET position = ? WHERE id = ?",
                        [r["position"] - 1, r["id"]],
                    )

                now = get_utc_now()
                cursor.execute(
                    "UPDATE workout_sessions SET last_modified = ?, modified_by = ? WHERE id = ?",
                    [now, "mcp", session_id],
                )

                remaining = cursor.execute(
                    "SELECT COUNT(*) AS c FROM session_blocks WHERE session_id = ?",
                    [session_id],
                ).fetchone()["c"]

            return {
                "success": True,
                "date": date,
                "removed_block_position": block_position,
                "removed_exercises": ex_count,
                "remaining_blocks": remaining,
                "message": f"Block {block_position} removed",
            }
        except Exception as e:
            raise ValueError(f"Failed to remove block: {str(e)}")

    @mcp.tool()
    def search_exercises(
        query: str,
        equipment: Optional[str] = None,
        category: Optional[str] = None,
        limit: int = 20
    ) -> List[Dict[str, Any]]:
        """WHEN TO USE: When searching for exercises in the registry by name, equipment, or category.

        Searches the canonical exercise registry by name (fuzzy), optionally
        filtering by equipment or category. Returns matches with usage count.

        Args:
            query: Search term for exercise name
            equipment: Filter by equipment (e.g., "kettlebell", "bodyweight", "dumbbell")
            category: Filter by category (e.g., "mobility", "cardio")
            limit: Max results to return (default 20)

        Returns:
            List of matching exercises with slug, name, equipment, category, and usage count
        """
        try:
            # Build SQL query with optional filters
            conditions = ["1=1"]
            params = []

            if equipment:
                conditions.append("e.equipment = ?")
                params.append(equipment)
            if category:
                conditions.append("e.category = ?")
                params.append(category)

            where_clause = " AND ".join(conditions)

            results = db_manager.execute_query(f"""
                SELECT e.slug, e.name, e.equipment, e.category,
                       COUNT(pe.id) as usage_count
                FROM exercises e
                LEFT JOIN planned_exercises pe ON pe.canonical_slug = e.slug
                WHERE {where_clause}
                GROUP BY e.slug
                ORDER BY e.name
            """, params)

            # Apply fuzzy name filtering in Python
            if query.strip():
                from difflib import SequenceMatcher
                query_lower = query.lower()
                scored = []
                for row in results:
                    name_lower = row["name"].lower()
                    # Substring match gets high priority
                    if query_lower in name_lower:
                        score = 100
                    else:
                        score = SequenceMatcher(None, query_lower, name_lower).ratio() * 100
                    if score >= 50:
                        scored.append((score, row))
                scored.sort(key=lambda x: (-x[0], x[1]["name"]))
                results = [row for _, row in scored[:limit]]
            else:
                results = results[:limit]

            return results
        except Exception as e:
            raise ValueError(f"Failed to search exercises: {str(e)}")

    @mcp.tool()
    def get_exercise_history(
        exercise_slug: str,
        limit: int = 30
    ) -> Dict[str, Any]:
        """WHEN TO USE: When you want to see all logged sessions for a specific exercise across all dates.

        Returns workout log history for a canonical exercise. Self-contained —
        no plan join needed. Includes set details grouped by date.

        Args:
            exercise_slug: Canonical exercise slug (e.g., "kb_goblet_squat")
            limit: Max sessions to return (default 30)

        Returns:
            Exercise info and list of logged sessions with set data
        """
        try:
            # Get exercise info
            exercise_info = db_manager.execute_query(
                "SELECT * FROM exercises WHERE slug = ?", [exercise_slug]
            )
            if not exercise_info:
                raise ValueError(f"Exercise not found: {exercise_slug}")

            info = exercise_info[0]

            # Get all logged sessions for this exercise
            sessions = db_manager.execute_query("""
                SELECT
                    wsl.date,
                    el.completed,
                    el.user_note,
                    el.duration_min,
                    el.avg_hr,
                    el.max_hr,
                    el.id as exercise_log_id
                FROM exercise_logs el
                JOIN workout_session_logs wsl ON el.session_log_id = wsl.id
                WHERE el.canonical_slug = ?
                ORDER BY wsl.date DESC
                LIMIT ?
            """, [exercise_slug, limit])

            # For each session, get set data
            history = []
            for session in sessions:
                sets = db_manager.execute_query("""
                    SELECT set_num, weight, reps, rpe, unit, duration_sec, completed
                    FROM set_logs
                    WHERE exercise_log_id = ?
                    ORDER BY set_num
                """, [session["exercise_log_id"]])

                entry = {
                    "date": session["date"],
                    "completed": bool(session["completed"]),
                }
                if session["user_note"]:
                    entry["user_note"] = session["user_note"]
                if session["duration_min"] is not None:
                    entry["duration_min"] = session["duration_min"]
                if session["avg_hr"] is not None:
                    entry["avg_hr"] = session["avg_hr"]
                if session["max_hr"] is not None:
                    entry["max_hr"] = session["max_hr"]
                if sets:
                    entry["sets"] = sets

                history.append(entry)

            return {
                "exercise": {
                    "slug": info["slug"],
                    "name": info["name"],
                    "equipment": info["equipment"],
                    "category": info["category"],
                },
                "total_sessions": len(history),
                "history": history,
            }
        except Exception as e:
            raise ValueError(f"Failed to get exercise history: {str(e)}")

    @mcp.resource("file://exercise_registry_summary")
    def exercise_registry_summary() -> str:
        """Summary of all exercises in the registry, grouped by equipment."""
        try:
            results = db_manager.execute_query("""
                SELECT e.slug, e.name, e.equipment, e.category,
                       COUNT(pe.id) as usage_count
                FROM exercises e
                LEFT JOIN planned_exercises pe ON pe.canonical_slug = e.slug
                GROUP BY e.slug
                ORDER BY e.equipment, e.name
            """)

            if not results:
                return "# Exercise Registry\n\nNo exercises registered yet."

            lines = ["# Exercise Registry", ""]
            current_equip = None
            for row in results:
                equip = row["equipment"] or "unclassified"
                if equip != current_equip:
                    current_equip = equip
                    lines.append(f"## {equip.title()}")
                    lines.append("")

                cat_str = f" [{row['category']}]" if row["category"] else ""
                usage_str = f" (used {row['usage_count']}x)" if row["usage_count"] else ""
                lines.append(f"- **{row['name']}** (`{row['slug']}`){cat_str}{usage_str}")

            lines.append("")
            lines.append(f"Total: {len(results)} exercises")
            return "\n".join(lines)
        except Exception as e:
            return f"Error loading registry: {str(e)}"

    @mcp.resource("file://coach_plan_guide")
    def coach_plan_guide() -> str:
        """Complete guide to creating workout plans."""
        return _get_coach_plan_guide()

    return mcp


# ==================== Log Assembly Helper ====================


def _get_workout_stats(cursor, session_id):
    """Fetch pre/post workout stats for a session from hook result tables.

    Returns a dict with 'pre' and/or 'post' keys, each containing
    fired_at timestamp, status, and collected data key/value pairs.
    Returns None if no stats exist for the session.
    """
    stats = {}
    for hook_type, label in (("pre", "pre"), ("post", "post")):
        cursor.execute(
            "SELECT id, fired_at, exit_code FROM workout_hook_results "
            "WHERE session_id = ? AND hook_type = ?",
            (session_id, hook_type),
        )
        row = cursor.fetchone()
        if not row:
            continue

        entry = {"fired_at": row["fired_at"]}
        if row["exit_code"] is not None:
            entry["status"] = "ok" if row["exit_code"] == 0 else "error"
        else:
            entry["status"] = "pending"

        cursor.execute(
            "SELECT key, value FROM workout_hook_data WHERE result_id = ?",
            (row["id"],),
        )
        data = {r["key"]: r["value"] for r in cursor.fetchall()}
        if data:
            entry["data"] = data

        stats[label] = entry

    return stats if stats else None


def _assemble_log_from_db(cursor, session_log_id, session_id=None):
    """Assemble log dict from relational tables."""
    cursor.execute("SELECT * FROM workout_session_logs WHERE id = ?", [session_log_id])
    log_row = cursor.fetchone()
    if not log_row:
        return {}

    log = {}

    # Pre/post workout stats (from hook results)
    if session_id is not None:
        workout_stats = _get_workout_stats(cursor, session_id)
        if workout_stats:
            log["workout_stats"] = workout_stats

    # Session feedback
    feedback = {}
    if log_row["pain_discomfort"]:
        feedback["pain_discomfort"] = log_row["pain_discomfort"]
    if log_row["general_notes"]:
        feedback["general_notes"] = log_row["general_notes"]
    log["session_feedback"] = feedback

    # Exercise logs
    cursor.execute("SELECT * FROM exercise_logs WHERE session_log_id = ?", [session_log_id])
    for el in cursor.fetchall():
        entry = {}
        if el["completed"]:
            entry["completed"] = True
        if el["user_note"]:
            entry["user_note"] = el["user_note"]
        if el["duration_min"] is not None:
            entry["duration_min"] = el["duration_min"]
        if el["avg_hr"] is not None:
            entry["avg_hr"] = el["avg_hr"]
        if el["max_hr"] is not None:
            entry["max_hr"] = el["max_hr"]

        # Sets
        cursor.execute("""
            SELECT * FROM set_logs WHERE exercise_log_id = ? ORDER BY set_num
        """, [el["id"]])
        sets = cursor.fetchall()
        if sets:
            entry["sets"] = []
            for s in sets:
                set_dict = {"set_num": s["set_num"]}
                if s["weight"] is not None:
                    set_dict["weight"] = s["weight"]
                if s["reps"] is not None:
                    set_dict["reps"] = s["reps"]
                if s["rpe"] is not None:
                    set_dict["rpe"] = s["rpe"]
                if s["unit"]:
                    set_dict["unit"] = s["unit"]
                if s["duration_sec"] is not None:
                    set_dict["duration_sec"] = s["duration_sec"]
                if s["completed"]:
                    set_dict["completed"] = True
                entry["sets"].append(set_dict)

        # Checklist items
        cursor.execute("""
            SELECT item_text FROM checklist_log_items WHERE exercise_log_id = ?
        """, [el["id"]])
        items = cursor.fetchall()
        if items:
            entry["completed_items"] = [r["item_text"] for r in items]

        log[el["exercise_key"]] = entry

    return log


# ==================== Transform Functions ====================


def _is_bodyweight_or_band(name: str) -> bool:
    """Check if an exercise is bodyweight or band-based (no meaningful weight)."""
    keywords = [
        "push-up", "pushup", "push up", "bodyweight", "band pull",
        "banded", "jump squat", "plank", "dead hang", "wall sit",
        "glute bridge",
    ]
    lower = name.lower()
    return any(kw in lower for kw in keywords)


def _transform_block_to_exercises(block: dict, block_index: int) -> list:
    """Transform a block into a list of exercises with proper IDs and types."""
    exercises = []
    block_type = block.get("block_type", "")
    title = block.get("title", "")
    rest_guidance = block.get("rest_guidance", "")
    duration = block.get("duration_min", 0)

    # Handle warmup blocks specially - aggregate into single checklist
    if block_type == "warmup" and "exercises" in block:
        items = []
        for ex in block["exercises"]:
            name = ex.get("name", "Unknown")
            reps = ex.get("reps", "")
            if reps:
                items.append(f"{name} x{reps}" if isinstance(reps, int) else f"{name} {reps}")
            else:
                items.append(name)

        exercise = {
            "id": f"warmup_{block_index}",
            "name": title or "Warmup",
            "type": "checklist",
            "items": items
        }
        exercises.append(exercise)

    # Handle blocks with exercises list (non-warmup)
    elif "exercises" in block:
        block_rounds = block.get("rounds")

        for i, ex in enumerate(block["exercises"]):
            exercise_id = f"{block_type}_{block_index}_{i+1}"

            # Determine exercise type based on block type
            if ex.get("type"):
                ex_type = ex["type"]
            elif block_type in ["circuit", "power"]:
                ex_type = "circuit"
            elif block_type == "cardio":
                ex_type = "duration"
            elif block_type in ["strength", "accessory"]:
                ex_type = "strength"
            else:
                ex_type = "strength"

            exercise = {
                "id": exercise_id,
                "name": ex.get("name", "Unknown"),
                "type": ex_type,
            }

            for key in ("target_duration_min", "target_duration_sec",
                        "rounds", "work_duration_sec", "rest_duration_sec"):
                if ex.get(key) is not None:
                    exercise[key] = ex[key]

            if ex.get("sets"):
                exercise["target_sets"] = ex["sets"] if isinstance(ex["sets"], int) else 3
            elif block_rounds:
                exercise["target_sets"] = block_rounds
            if ex.get("reps"):
                reps_str = str(ex["reps"])
                exercise["target_reps"] = reps_str
                if "sec" in reps_str.lower():
                    exercise["show_time"] = True

            # Hide weight for bodyweight/band exercises
            equipment = ex.get("equipment")
            if equipment:
                if equipment in ("bodyweight", "band"):
                    exercise["hide_weight"] = True
            elif _is_bodyweight_or_band(ex.get("name", "")):
                exercise["hide_weight"] = True

            # Build guidance note
            notes = []
            if ex.get("tempo"):
                notes.append(f"Tempo {ex['tempo']}")
            if ex.get("load_guide"):
                notes.append(ex["load_guide"])
            if ex.get("notes"):
                notes.append(ex["notes"])
            if rest_guidance and block_type == "strength":
                notes.append(rest_guidance)

            if notes:
                exercise["guidance_note"] = ". ".join(notes)

            if ex.get("superset_group"):
                exercise["superset_group"] = ex["superset_group"]

            exercises.append(exercise)

    # Handle blocks with instructions (cardio blocks)
    elif "instructions" in block:
        exercise_id = f"{block_type}_{block_index}_1"
        instructions_text = " ".join(block["instructions"])

        if "VO2" in instructions_text or "HARD" in instructions_text:
            ex_type = "interval"
            name = "VO2 Max Intervals"
        else:
            ex_type = "duration"
            name = title or "Zone 2 Cardio"

        # Block-level rounds/work/rest timing stays on the block (see
        # _transform_block_plan); the synthesized exercise only carries the
        # cardio-specific fields it owns.
        exercise = {
            "id": exercise_id,
            "name": name,
            "type": ex_type,
            "target_duration_min": duration,
            "guidance_note": " | ".join(block["instructions"])
        }
        exercises.append(exercise)

    return exercises


def _transform_block_plan(plan_data: dict) -> dict:
    """Transform block-based plan to include blocks with transformed exercises."""
    blocks = []

    for i, block in enumerate(plan_data.get("blocks", [])):
        existing = block.get("exercises")
        if existing and all("id" in ex and "type" in ex for ex in existing):
            block_exercises = existing
        else:
            block_exercises = _transform_block_to_exercises(block, i)

        transformed_block = {
            "block_index": i,
            "block_type": block.get("block_type", ""),
            "title": block.get("title", ""),
            "duration_min": block.get("duration_min"),
            "rest_guidance": block.get("rest_guidance", ""),
            "rounds": block.get("rounds"),
            "work_duration_sec": block.get("work_duration_sec"),
            "rest_duration_sec": block.get("rest_duration_sec"),
            "exercises": block_exercises
        }
        blocks.append(transformed_block)

    return {
        "day_name": plan_data.get("theme", plan_data.get("day_name", "Workout")),
        "location": plan_data.get("location", "Home"),
        "phase": plan_data.get("phase", "Foundation"),
        "total_duration_min": plan_data.get("total_duration_min", 60),
        "blocks": blocks,
    }


def _get_coach_plan_guide() -> str:
    """Get comprehensive guide for creating workout plans."""
    return """
# Coach Workout Plan Guide

## Quick Start
1. Use `list_scheduled_dates` to see what's already planned
2. Use `get_workout_plan` to see existing plan structures
3. Use `set_workout_plan` to create new plans (block format required)
4. Use `get_workout_logs` to analyze past performance

## Plan Structure

Each workout plan uses block-based format:
- `blocks`: Array of typed groups (warmup, strength, cardio, circuit, accessory, power)
- Each block contains exercises appropriate to its type

## Block Types

### warmup
Exercises are aggregated into a single checklist.

### strength / accessory
Individual exercises with sets/reps.

### circuit / power
Exercises with rounds (from block-level `rounds` field).

### cardio
Can use `instructions` array or `exercises` list.

## Exercise Types

### strength
```json
{"id": "ex_1", "name": "KB Goblet Squat", "type": "strength",
 "target_sets": 3, "target_reps": "10", "guidance_note": "Tempo 3-1-1"}
```

### duration
```json
{"id": "cardio_1", "name": "Zone 2 Bike", "type": "duration",
 "target_duration_min": 15, "guidance_note": "HR 135-148"}
```

### checklist
```json
{"id": "warmup_0", "name": "Stability Start", "type": "checklist",
 "items": ["Cat-Cow x10", "Bird-Dog x5/side"]}
```

### weighted_time
```json
{"id": "ex_5", "name": "Farmer's Carry", "type": "weighted_time",
 "target_duration_sec": 60}
```

### interval
```json
{"id": "hiit_1", "name": "Bike Intervals", "type": "interval",
 "rounds": 4, "work_duration_sec": 30, "rest_duration_sec": 90}
```

## Antagonist Pairs / Supersets

Group two or more exercises into a superset using the `superset_group` field.
Exercises in the same block that share the same group label are rendered
together in the UI. The label is free-form: `"A"`, `"B"`, `"Triplet A"`, etc.

```json
{"id": "ex_1", "name": "Bench Press", "type": "strength",
 "target_sets": 3, "target_reps": "8", "superset_group": "A"},
{"id": "ex_2", "name": "Bent Row", "type": "strength",
 "target_sets": 3, "target_reps": "8", "superset_group": "A"}
```

**Do NOT** put pair info in the exercise `name` (e.g. `"Bench Press (Pair A)"`).
Names like that are rejected by the server because the suffix would leak into
the canonical slug and break cross-session comparison.

## Example: Block-Based Plan

```json
{
    "day_name": "Lower Body + Conditioning",
    "location": "Home",
    "phase": "Foundation",
    "blocks": [
        {
            "block_type": "warmup",
            "title": "Stability Start",
            "exercises": [
                {"id": "warmup_0", "name": "Stability Start", "type": "checklist",
                 "items": ["Cat-Cow x10", "Bird-Dog x5/side", "Dead Bug x10"]}
            ]
        },
        {
            "block_type": "strength",
            "title": "Main Lifts",
            "rest_guidance": "Rest until HR <= 130",
            "exercises": [
                {"id": "ex_1", "name": "KB Goblet Squat", "type": "strength",
                 "target_sets": 3, "target_reps": "10", "guidance_note": "Tempo 3-1-1"},
                {"id": "ex_2", "name": "DB Romanian Deadlift", "type": "strength",
                 "target_sets": 3, "target_reps": "10"}
            ]
        },
        {
            "block_type": "cardio",
            "title": "Zone 2 Cooldown",
            "exercises": [
                {"id": "cardio_1", "name": "Zone 2 Bike", "type": "duration",
                 "target_duration_min": 15, "guidance_note": "HR 135-148"}
            ]
        }
    ]
}
```

## Exercise Registry

Exercises are automatically registered with canonical slugs (e.g., `kb_goblet_squat`)
when plans are created. This enables cross-session queries.

### Available Tools
- `search_exercises(query)` — find exercises by name, equipment, or category
- `get_exercise_history(exercise_slug)` — view all logged sessions for an exercise

### Available Resources
- `exercise_registry_summary` — full list of registered exercises grouped by equipment

### How It Works
- When you create a plan, exercise names are automatically resolved to canonical slugs
- Fuzzy matching handles minor name variations (e.g., "KB Goblet Squat" vs "Kettlebell Goblet Squat")
- New exercises are auto-registered; equipment is inferred from the name
- Use `search_exercises` to check what exercises already exist before creating plans

## Best Practices

1. **Block grouping**: Group exercises by type (warmup, strength, cardio)
2. **Unique IDs**: Each exercise needs a unique `id` within the plan
3. **Guidance Notes**: Include tempo, rest periods, HR targets
4. **Progressive Overload**: Increase volume/intensity across phases
5. **Consistent Names**: Use `search_exercises` to find existing exercise names
    """.strip()


def main():
    """Main entry point for the Coach MCP server."""
    try:
        mcp = create_mcp_server()
        mcp.run()
    except Exception as e:
        print(f"Failed to start MCP server: {e}")
        raise


if __name__ == "__main__":
    main()
