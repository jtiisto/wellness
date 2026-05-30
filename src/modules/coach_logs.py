"""Coach log domain logic — shared by both transports.

Transport-agnostic (stdlib + sibling `modules.*` only; no fastapi/fastmcp). The
FastAPI sync path uses the LEAN log shape — feedback + raw per-exercise entries —
because the PWA derives completion client-side. The MCP analysis path passes
`derive_completion=True` to additionally fold in hook workout stats, per-exercise
attempted/completed/progress, and a session_completion rollup for the LLM. See
plans/ phase 3.
"""
from modules.coach_completion import (
    derive_exercise_completion,
    derive_session_completion,
)


def should_accept_log_write(stored_last_modified, base_token):
    """Decide whether an incoming coach log write wins — server-side, WITHOUT
    consulting any client clock (R1; see plans/phase4-r1-coach-clock-skew.md).

    Both operands are *server-issued* Z-suffixed instants, so the comparison is
    skew-free and byte-lexical:

    * no existing row (`stored_last_modified is None`) → accept (insert).
    * existing row, `base_token` present, `stored <= base_token` → accept: the
      client echoed the latest server stamp it saw, so its edit is newer.
    * else → reject: either the client missed a newer server write
      (`stored > base_token`), or it sent no token against an existing row
      (hard cutover — the token is required to overwrite).
    """
    if stored_last_modified is None:
        return True
    if base_token is None:
        return False
    return stored_last_modified <= base_token


def workout_stats(cursor, session_id):
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


def assemble_log(cursor, log_row, *, session_id=None, derive_completion=False):
    """Assemble a log dict from the relational tables for `log_row`.

    The canonical log reader for both transports. The LEAN shape (the default,
    used by the FastAPI sync response) is: `session_feedback` plus one raw entry
    per logged exercise (user_note / duration_min / avg_hr / max_hr / sets /
    completed_items). The PWA derives completion itself, so the sync response
    deliberately omits it.

    With `derive_completion=True` (the MCP analysis path) the result also carries
    hook `workout_stats` (needs `session_id`), per-exercise `attempted` /
    `completed` / `progress`, and a top-level `session_completion` rollup.
    """
    log = {}

    if derive_completion and session_id is not None:
        stats = workout_stats(cursor, session_id)
        if stats:
            log["workout_stats"] = stats

    feedback = {}
    if log_row["pain_discomfort"]:
        feedback["pain_discomfort"] = log_row["pain_discomfort"]
    if log_row["general_notes"]:
        feedback["general_notes"] = log_row["general_notes"]
    log["session_feedback"] = feedback

    if derive_completion:
        # Join the planned target so completion can report met-target verdicts.
        cursor.execute("""
            SELECT el.*,
                   pe.exercise_type AS pe_exercise_type,
                   pe.target_sets AS pe_target_sets,
                   pe.target_duration_min AS pe_target_duration_min,
                   (SELECT COUNT(*) FROM checklist_items ci
                      WHERE ci.exercise_id = pe.id) AS planned_items
            FROM exercise_logs el
            LEFT JOIN planned_exercises pe ON pe.id = el.exercise_id
            WHERE el.session_log_id = ?
        """, [log_row["id"]])
    else:
        cursor.execute(
            "SELECT * FROM exercise_logs WHERE session_log_id = ?", (log_row["id"],)
        )
    exercise_rows = cursor.fetchall()

    completion_results = []
    for el in exercise_rows:
        entry = {}
        if el["user_note"]:
            entry["user_note"] = el["user_note"]
        if el["duration_min"] is not None:
            entry["duration_min"] = el["duration_min"]
        if el["avg_hr"] is not None:
            entry["avg_hr"] = el["avg_hr"]
        if el["max_hr"] is not None:
            entry["max_hr"] = el["max_hr"]

        cursor.execute(
            "SELECT * FROM set_logs WHERE exercise_log_id = ? ORDER BY set_num",
            (el["id"],),
        )
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

        cursor.execute(
            "SELECT item_text FROM checklist_log_items WHERE exercise_log_id = ?",
            (el["id"],),
        )
        items = cursor.fetchall()
        if items:
            entry["completed_items"] = [r["item_text"] for r in items]

        if derive_completion:
            completion = derive_exercise_completion(
                el["pe_exercise_type"],
                sets=sets,
                duration_min=el["duration_min"],
                logged_items=len(items),
                planned_items=el["planned_items"],
                target_sets=el["pe_target_sets"],
                target_duration_min=el["pe_target_duration_min"],
            )
            entry["attempted"] = completion["attempted"]
            entry["completed"] = completion["completed"]
            entry["progress"] = completion["progress"]
            completion_results.append(completion)

        log[el["exercise_key"]] = entry

    if derive_completion:
        # Count planned exercises so a planned-but-unlogged exercise correctly
        # counts against full completion.
        planned_total = None
        if session_id is not None:
            row = cursor.execute(
                "SELECT COUNT(*) AS n FROM planned_exercises WHERE session_id = ?",
                [session_id],
            ).fetchone()
            planned_total = row["n"] if row else None
        log["session_completion"] = derive_session_completion(
            completion_results, planned_total=planned_total
        )

    return log
