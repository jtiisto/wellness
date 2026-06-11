"""Exercise-family MCP tools for the Coach server.

update_exercise, add_exercise, remove_exercise, search_exercises,
get_exercise_history.

Bodies moved verbatim from `server.py`; the only change is
rebinding the captured `db_manager`/`registry`/`config` to `self.*`.
"""

from typing import Any, Dict, List, Optional

from modules import coach_queries

from ._helpers import _assemble_plan_from_db, _reject_legacy_pair_suffix
from .database import get_utc_now
from .exercise_registry import resolve_or_create_exercise, resolve_plan_exercises


class ExerciseTools:
    def __init__(self, db_manager, registry, config):
        self.db_manager = db_manager
        self.registry = registry
        self.config = config

    def update_exercise(
        self,
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
            with self.db_manager.transaction() as cursor:
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

                # If name is changing, resolve new canonical slug via the one
                # shared resolve-or-create (collision suffixing + inference +
                # registry self-heal included — the old inline copy had none).
                if "name" in updates:
                    slug, _ = resolve_or_create_exercise(
                        self.registry, cursor, updates["name"],
                        exercise={**updates, "name": updates["name"]},
                    )
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

    def add_exercise(
        self,
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
            with self.db_manager.transaction() as cursor:
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

                # Resolve canonical slug via the one shared resolve-or-create
                # (collision suffixing + inference + registry self-heal).
                slug, _ = resolve_or_create_exercise(
                    self.registry, cursor, exercise["name"], exercise=exercise,
                )

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

    def remove_exercise(
        self,
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
            with self.db_manager.transaction() as cursor:
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

                # Orphan any log rows first: exercise_logs.exercise_id has a
                # plain (NO ACTION) FK, so deleting a logged exercise would
                # otherwise die with a raw 'FOREIGN KEY constraint failed'.
                # The log keeps its identity via exercise_key/canonical_slug —
                # the documented orphaned-log semantics.
                cursor.execute(
                    "UPDATE exercise_logs SET exercise_id = NULL WHERE exercise_id = ?",
                    [row["id"]],
                )

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

    def search_exercises(
        self,
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
            return coach_queries.search_exercises(
                self.db_manager, query=query, equipment=equipment, category=category, limit=limit
            )
        except Exception as e:
            raise ValueError(f"Failed to search exercises: {str(e)}")

    def get_exercise_history(
        self,
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
            return coach_queries.exercise_history(
                self.db_manager, exercise_slug=exercise_slug, limit=limit
            )
        except Exception as e:
            raise ValueError(f"Failed to get exercise history: {str(e)}")


def register(mcp, db_manager, registry, config):
    t = ExerciseTools(db_manager, registry, config)
    mcp.tool()(t.update_exercise)
    mcp.tool()(t.add_exercise)
    mcp.tool()(t.remove_exercise)
    mcp.tool()(t.search_exercises)
    mcp.tool()(t.get_exercise_history)
