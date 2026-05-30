"""Coach plan domain logic — shared by both transports.

Transport-agnostic: imports only stdlib (+ `modules.db` for write helpers added
later). The FastAPI router (`modules.coach`) and the MCP server
(`coach_mcp.server`) both delegate here so there is exactly one implementation of
each plan operation (see plans/ phase 3). Do NOT import fastapi or fastmcp here.
"""


def assemble_plan(cursor, session_row):
    """Assemble a plan dict from the relational tables for `session_row`.

    The canonical plan reader for both transports. Always includes `session_id`
    — unifying the two previously-divergent assemblers on the superset shape
    (§3.15); the extra field is backward-compatible for both the sync client and
    the MCP read tools.

    `session_row` is a `SELECT * FROM workout_sessions` row (so it carries `id`,
    `day_name`, `location`, `phase`, `duration_min`). `cursor` is reused for the
    block / exercise / checklist sub-queries.
    """
    session_id = session_row["id"]

    cursor.execute(
        "SELECT * FROM session_blocks WHERE session_id = ? ORDER BY position",
        (session_id,),
    )
    block_rows = cursor.fetchall()

    blocks = []
    for br in block_rows:
        cursor.execute(
            "SELECT * FROM planned_exercises WHERE block_id = ? ORDER BY position",
            (br["id"],),
        )
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
                cursor.execute(
                    "SELECT item_text FROM checklist_items "
                    "WHERE exercise_id = ? ORDER BY position",
                    (er["id"],),
                )
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
        "session_id": session_id,
        "day_name": session_row["day_name"],
        "location": session_row["location"],
        "phase": session_row["phase"],
        "total_duration_min": session_row["duration_min"],
        "blocks": blocks,
    }
