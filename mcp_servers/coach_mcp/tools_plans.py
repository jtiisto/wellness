"""Plan-family MCP tools for the Coach server.

get_workout_plan, set_workout_plan, ingest_training_program,
delete_workout_plan, update_plan_metadata.

Bodies moved verbatim from `server.py`; the only change is
rebinding the captured `db_manager`/`registry`/`config` to `self.*`.
"""

from datetime import datetime
from typing import Any, Dict, List, Optional

from modules.coach_plans import (
    needs_transform as _needs_transform,
    ensure_exercise_ids as _ensure_exercise_ids,
    transform_block_plan as _transform_block_plan,
    store_plan as _store_plan_to_db,
    validate_plan,
    validate_plan_structure,
)

from ._helpers import _assemble_plan_from_db, _assemble_log_from_db
from .database import get_utc_now
from .exercise_registry import resolve_plan_exercises


class PlanTools:
    def __init__(self, db_manager, registry, config):
        self.db_manager = db_manager
        self.registry = registry
        self.config = config

    def get_workout_plan(
        self,
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
            results = self.db_manager.execute_query("""
                SELECT id, date, last_modified FROM workout_sessions
                WHERE date >= ? AND date <= ?
                ORDER BY date
            """, [start_date, end_date])

            plans = []
            for row in results:
                with self.db_manager.get_connection(read_only=True) as conn:
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

    def set_workout_plan(
        self,
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

        # Block-level shape must hold before the transform can run; the full
        # validation (exercise fields, types, legacy suffixes) happens inside
        # store_plan so every write path — this tool, the bulk ingest, any
        # future caller — inherits it from one place.
        validate_plan_structure(plan)

        # Transform raw LLM format if needed, then backfill any missing ids.
        if _needs_transform(plan):
            plan = _transform_block_plan(plan)
        _ensure_exercise_ids(plan)

        # Ensure day_name exists
        if "day_name" not in plan:
            plan["day_name"] = plan.get("theme", "Workout")

        # Validate BEFORE resolve_plan_exercises touches the registry: an
        # invalid plan must fail with no side effects (a failed transaction
        # rolls back the exercises rows but not the in-memory registry).
        # store_plan re-validates as the enforcement backstop.
        validate_plan(plan)

        try:
            with self.db_manager.transaction() as cursor:
                # Resolve exercise names to canonical slugs
                resolution_report = resolve_plan_exercises(self.registry, plan, cursor)

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

    def ingest_training_program(
        self,
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

                # Transform if needed, then backfill any missing exercise ids.
                if transform_blocks and "blocks" in plan_data and _needs_transform(plan_data):
                    plan = _transform_block_plan(plan_data)
                else:
                    plan = plan_data
                _ensure_exercise_ids(plan)

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

                # Validate BEFORE resolve_plan_exercises touches the registry:
                # an invalid plan must fail with no side effects. (store_plan
                # re-validates as the enforcement backstop for any caller.)
                validate_plan(plan)

                with self.db_manager.transaction() as cursor:
                    resolve_plan_exercises(self.registry, plan, cursor)
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

    def delete_workout_plan(self, date: str) -> Dict[str, Any]:
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
            results = self.db_manager.execute_query(
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
            log_results = self.db_manager.execute_query(
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
            with self.db_manager.transaction() as cursor:
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

    def update_plan_metadata(
        self,
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
            with self.db_manager.transaction() as cursor:
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


def register(mcp, db_manager, registry, config):
    t = PlanTools(db_manager, registry, config)
    mcp.tool()(t.get_workout_plan)
    mcp.tool()(t.set_workout_plan)
    mcp.tool()(t.ingest_training_program)
    mcp.tool()(t.delete_workout_plan)
    mcp.tool()(t.update_plan_metadata)
