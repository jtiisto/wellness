"""Coach read/analytics queries — shared coach domain layer.

The query logic behind the MCP analysis tools. Each function takes a DB accessor
(duck-typed: `.execute_query`, `.get_connection`) and, where a calendar "now" is
needed, an injected `today` (a date) so the module stays clock-free and
unit-testable. Imports only sibling `modules.*` + stdlib; no fastapi/fastmcp.
Validation and error-wrapping stay in the thin MCP tool closures. See plans/ phase 3.
"""
from datetime import timedelta
from difflib import SequenceMatcher

from modules.coach_completion import derive_exercise_completion
from modules.coach_logs import assemble_log


def workout_summary(db, *, days, today):
    """Summary stats over the last `days`: planned vs completed (presence-based)
    vs fully-completed (every planned exercise met target) workout counts, an
    exercise-type breakdown, recent plan dates, and off-plan (extra) sessions.
    `today` is the reference date.
    """
    start_date = (today - timedelta(days=days)).isoformat()
    end_date = today.isoformat()

    # Count planned workouts
    plans_result = db.execute_query("""
        SELECT COUNT(*) as count FROM workout_sessions
        WHERE date >= ? AND date <= ?
    """, [start_date, end_date])
    planned_count = plans_result[0]["count"] if plans_result else 0

    # Count logged workouts (presence-based: a session_log row means the user
    # recorded something). Kept as `completed_workouts` for backward compat —
    # but only PLANNED days count (session_id linked); off-plan extras are
    # reported separately so they can't push the completion rate past 100%.
    logs_result = db.execute_query("""
        SELECT id, session_id FROM workout_session_logs
        WHERE date >= ? AND date <= ? AND session_id IS NOT NULL
    """, [start_date, end_date])
    completed_count = len(logs_result)

    # Off-plan (extra) sessions — e.g. an ad-hoc Zone 2 on a rest day. Only
    # content-bearing days count: a husk row whose entries were all deleted is
    # not a session.
    extra_rows = db.execute_query("""
        SELECT l.date FROM workout_session_logs l
        WHERE l.date >= ? AND l.date <= ? AND l.session_id IS NULL
          AND EXISTS (
              SELECT 1 FROM exercise_logs e
              WHERE e.session_log_id = l.id
                AND (e.duration_min IS NOT NULL
                     OR EXISTS (SELECT 1 FROM set_logs s WHERE s.exercise_log_id = e.id)
                     OR EXISTS (SELECT 1 FROM checklist_log_items c WHERE c.exercise_log_id = e.id))
          )
        ORDER BY l.date
    """, [start_date, end_date])
    extra_session_dates = [row["date"] for row in extra_rows]

    # Derived: sessions that were *fully* completed — every planned exercise met
    # its target (see coach_completion).
    fully_completed_count = 0
    with db.get_connection(read_only=True) as conn:
        cur = conn.cursor()
        for lr in logs_result:
            cur.execute("SELECT * FROM workout_session_logs WHERE id = ?", [lr["id"]])
            log_row = cur.fetchone()
            if not log_row:
                continue
            assembled = assemble_log(
                cur, log_row, session_id=lr["session_id"], derive_completion=True
            )
            sc = assembled.get("session_completion")
            if sc and sc["completed"]:
                fully_completed_count += 1

    # Exercise type breakdown from recent plans
    exercise_types_result = db.execute_query("""
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
    recent_dates_result = db.execute_query("""
        SELECT date FROM workout_sessions
        WHERE date >= ? AND date <= ?
        ORDER BY date DESC
        LIMIT 7
    """, [start_date, end_date])

    completion_rate = round(completed_count / planned_count * 100, 1) if planned_count > 0 else 0
    full_completion_rate = round(fully_completed_count / planned_count * 100, 1) if planned_count > 0 else 0

    return {
        "analysis_period_days": days,
        "planned_workouts": planned_count,
        "completed_workouts": completed_count,
        "completion_rate_percent": completion_rate,
        "sessions_fully_completed": fully_completed_count,
        "full_completion_rate_percent": full_completion_rate,
        "extra_sessions": len(extra_session_dates),
        "extra_session_dates": extra_session_dates,
        "exercise_types_in_recent_plans": exercise_types,
        "recent_plan_dates": [row["date"] for row in recent_dates_result],
    }


def list_scheduled_dates(db, *, start_date=None, end_date=None, today):
    """Dates with plans in [start_date, end_date]; defaults to today .. +6 weeks."""
    if not start_date:
        start_date = today.isoformat()
    if not end_date:
        end_date = (today + timedelta(weeks=6)).isoformat()

    results = db.execute_query("""
        SELECT date FROM workout_sessions
        WHERE date >= ? AND date <= ?
        ORDER BY date
    """, [start_date, end_date])

    return [row["date"] for row in results]


def search_exercises(db, *, query, equipment=None, category=None, limit=20):
    """Fuzzy exercise-registry search by name, with optional equipment/category
    filters; rows carry a usage_count. Substring matches rank highest, then
    difflib ratio >= 50."""
    conditions = ["1=1"]
    params = []
    if equipment:
        conditions.append("e.equipment = ?")
        params.append(equipment)
    if category:
        conditions.append("e.category = ?")
        params.append(category)
    where_clause = " AND ".join(conditions)

    results = db.execute_query(f"""
        SELECT e.slug, e.name, e.equipment, e.category,
               COUNT(pe.id) as usage_count
        FROM exercises e
        LEFT JOIN planned_exercises pe ON pe.canonical_slug = e.slug
        WHERE {where_clause}
        GROUP BY e.slug
        ORDER BY e.name
    """, params)

    # Fuzzy name filtering in Python
    if query.strip():
        query_lower = query.lower()
        scored = []
        for row in results:
            name_lower = row["name"].lower()
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


def exercise_history(db, *, exercise_slug, limit=30):
    """Logged history for a canonical exercise (set data grouped by date, with
    derived completion). Self-contained — no plan join needed beyond targets.
    Raises ValueError if the slug is unknown."""
    exercise_info = db.execute_query(
        "SELECT * FROM exercises WHERE slug = ?", [exercise_slug]
    )
    if not exercise_info:
        raise ValueError(f"Exercise not found: {exercise_slug}")
    info = exercise_info[0]

    # All logged sessions for this exercise. Completion is derived from logged
    # data (see coach_completion), so pull the planned target and item counts
    # rather than the legacy `completed` flag.
    sessions = db.execute_query("""
        SELECT
            wsl.date,
            el.user_note,
            el.duration_min,
            el.avg_hr,
            el.max_hr,
            el.id as exercise_log_id,
            el.exercise_id as exercise_id,
            pe.exercise_type as exercise_type,
            pe.target_sets as target_sets,
            pe.target_duration_min as target_duration_min,
            (SELECT COUNT(*) FROM checklist_items ci
               WHERE ci.exercise_id = pe.id) as planned_items,
            (SELECT COUNT(*) FROM checklist_log_items cli
               WHERE cli.exercise_log_id = el.id) as logged_items
        FROM exercise_logs el
        JOIN workout_session_logs wsl ON el.session_log_id = wsl.id
        LEFT JOIN planned_exercises pe ON pe.id = el.exercise_id
        WHERE el.canonical_slug = ?
        ORDER BY wsl.date DESC
        LIMIT ?
    """, [exercise_slug, limit])

    history = []
    for session in sessions:
        sets = db.execute_query("""
            SELECT set_num, weight, reps, rpe, unit, duration_sec, completed
            FROM set_logs
            WHERE exercise_log_id = ?
            ORDER BY set_num
        """, [session["exercise_log_id"]])

        completion = derive_exercise_completion(
            session["exercise_type"],
            sets=sets,
            duration_min=session["duration_min"],
            logged_items=session["logged_items"],
            planned_items=session["planned_items"],
            target_sets=session["target_sets"],
            target_duration_min=session["target_duration_min"],
        )
        entry = {
            "date": session["date"],
            "attempted": completion["attempted"],
            "completed": completion["completed"],
            "progress": completion["progress"],
        }
        if session["exercise_id"] is None:
            # Not linked to a planned exercise — logged outside the plan
            # (e.g. an ad-hoc extra Zone 2 session on a rest day).
            entry["off_plan"] = True
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
