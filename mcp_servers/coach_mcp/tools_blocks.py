"""Block-family MCP tools for the Coach server.

update_block, add_block, remove_block, reorder_blocks (in-place block edits).

Bodies moved verbatim from `server.py`; the only changes are
rebinding the captured `db_manager`/`registry`/`config` to `self.*` and the
two closure-level locals `_VALID_BLOCK_TYPES` / `_BLOCK_COLUMN_MAP` to `self.*`.
"""

from typing import Any, Dict, List, Optional

from modules.coach_plans import (
    needs_transform as _needs_transform,
    ensure_exercise_ids as _ensure_exercise_ids,
    transform_block_plan as _transform_block_plan,
    insert_block as _insert_block,
)

from ._helpers import _assemble_plan_from_db, _reject_legacy_pair_suffix
from .database import get_utc_now
from .exercise_registry import resolve_plan_exercises


class BlockTools:
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

    def __init__(self, db_manager, registry, config):
        self.db_manager = db_manager
        self.registry = registry
        self.config = config

    def update_block(
        self,
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
        invalid_fields = set(updates.keys()) - set(self._BLOCK_COLUMN_MAP.keys())
        if invalid_fields:
            raise ValueError(
                f"Invalid block fields: {sorted(invalid_fields)}. "
                f"Allowed: {sorted(self._BLOCK_COLUMN_MAP.keys())}"
            )
        if "block_type" in updates and updates["block_type"] not in self._VALID_BLOCK_TYPES:
            raise ValueError(
                f"Invalid block_type: {updates['block_type']}. "
                f"Must be one of: {self._VALID_BLOCK_TYPES}"
            )

        try:
            with self.db_manager.transaction() as cursor:
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
                    set_clauses.append(f"{self._BLOCK_COLUMN_MAP[key]} = ?")
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

    def add_block(
        self,
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
        if block["block_type"] not in self._VALID_BLOCK_TYPES:
            raise ValueError(
                f"Invalid block_type: {block['block_type']}. "
                f"Must be one of: {self._VALID_BLOCK_TYPES}"
            )

        # Normalize inline exercises/instructions through the same transform
        # set_workout_plan uses.
        fragment = {"blocks": [dict(block)]}
        if _needs_transform(fragment):
            fragment = _transform_block_plan(fragment)
        _ensure_exercise_ids(fragment)
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
            with self.db_manager.transaction() as cursor:
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
                resolve_plan_exercises(self.registry, {"blocks": [norm_block]}, cursor)

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

    def remove_block(
        self,
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
            with self.db_manager.transaction() as cursor:
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

                # Orphan any log rows referencing this block's exercises first
                # (exercise_logs.exercise_id is a NO ACTION FK — see
                # remove_exercise); log identity survives via exercise_key.
                cursor.execute(
                    "UPDATE exercise_logs SET exercise_id = NULL WHERE exercise_id IN "
                    "(SELECT id FROM planned_exercises WHERE block_id = ?)",
                    [block_id],
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

    def reorder_blocks(
        self,
        date: str,
        order: List[int]
    ) -> Dict[str, Any]:
        """WHEN TO USE: When changing the order of a plan's blocks — e.g. moving
        a cardio block ahead of strength — without rebuilding the plan.

        Args:
            date: Date of the plan (YYYY-MM-DD)
            order: The blocks' current 0-indexed positions in their new order — a
                   permutation of 0..N-1. Example: [2, 0, 1] moves the block
                   currently at position 2 to the front.

        Returns:
            Confirmation with the block titles in their new order
        """
        if not isinstance(order, list) or not all(isinstance(p, int) for p in order):
            raise ValueError("order must be a list of integers")

        try:
            with self.db_manager.transaction() as cursor:
                cursor.execute("SELECT id FROM workout_sessions WHERE date = ?", [date])
                session = cursor.fetchone()
                if not session:
                    raise ValueError(f"No plan found for date: {date}")
                session_id = session["id"]

                blocks = cursor.execute("""
                    SELECT id, position FROM session_blocks
                    WHERE session_id = ? ORDER BY position
                """, [session_id]).fetchall()
                n = len(blocks)
                if sorted(order) != list(range(n)):
                    raise ValueError(
                        f"order must be a permutation of 0..{n - 1} "
                        f"(got {order} for a plan with {n} block(s))"
                    )

                id_by_pos = {b["position"]: b["id"] for b in blocks}
                # Two-phase update: park every block in a disjoint range
                # (n..2n-1), then settle into the target positions, so the
                # UNIQUE(session_id, position) constraint never trips.
                for b in blocks:
                    cursor.execute(
                        "UPDATE session_blocks SET position = ? WHERE id = ?",
                        [b["position"] + n, b["id"]],
                    )
                for new_pos, old_pos in enumerate(order):
                    cursor.execute(
                        "UPDATE session_blocks SET position = ? WHERE id = ?",
                        [new_pos, id_by_pos[old_pos]],
                    )

                now = get_utc_now()
                cursor.execute(
                    "UPDATE workout_sessions SET last_modified = ?, modified_by = ? WHERE id = ?",
                    [now, "mcp", session_id],
                )

                updated_plan = _assemble_plan_from_db(cursor, session_id)

            return {
                "success": True,
                "date": date,
                "block_order": [
                    b["title"] or b["block_type"] for b in updated_plan["blocks"]
                ],
                "message": "Blocks reordered",
            }
        except Exception as e:
            raise ValueError(f"Failed to reorder blocks: {str(e)}")


def register(mcp, db_manager, registry, config):
    t = BlockTools(db_manager, registry, config)
    mcp.tool()(t.update_block)
    mcp.tool()(t.add_block)
    mcp.tool()(t.remove_block)
    mcp.tool()(t.reorder_blocks)
