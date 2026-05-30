"""Cross-transport contract + unit tests for the shared coach domain modules
(Phase 3). Pins that the FastAPI router and the MCP server assemble identical
shapes from the same DB row — the durable guard against the §3.15 divergence."""
import sqlite3

import pytest

import modules.coach as coach_mod
from modules.coach_plans import assemble_plan
from coach_mcp.server import _assemble_plan_from_db


def _seed_plan(db_path):
    """Seed one workout session with a strength + checklist block. Returns its id.

    Uses the coach schema created by the test_app fixture (or coach init)."""
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO workout_sessions (date, day_name, location, phase, duration_min, "
        "last_modified, modified_by) VALUES ('2026-05-30','Push','Home','Foundation',45,"
        "'2026-05-30T00:00:00Z','test')"
    )
    sid = cur.lastrowid
    cur.execute(
        "INSERT INTO session_blocks (session_id, position, block_type, title, rest_guidance) "
        "VALUES (?, 0, 'strength', 'Strength', 'Rest 2 min')", (sid,)
    )
    bid = cur.lastrowid
    cur.execute(
        "INSERT INTO planned_exercises (session_id, block_id, exercise_key, position, name, "
        "exercise_type, target_sets, target_reps, guidance_note) "
        "VALUES (?, ?, 'ex_1', 0, 'Goblet Squat', 'strength', 3, '10', 'Tempo 3-1-1')",
        (sid, bid),
    )
    cur.execute(
        "INSERT INTO planned_exercises (session_id, block_id, exercise_key, position, name, "
        "exercise_type) VALUES (?, ?, 'warmup_0', 1, 'Mobility', 'checklist')", (sid, bid)
    )
    eid = cur.lastrowid
    cur.execute("INSERT INTO checklist_items (exercise_id, position, item_text) "
                "VALUES (?, 0, 'Cat-Cow x10')", (eid,))
    conn.commit()
    conn.close()
    return sid


@pytest.mark.unit
def test_plan_assemblers_agree_across_transports(test_app, tmp_coach_db):
    """§3.15: the FastAPI router (_assemble_plan) and the MCP server
    (_assemble_plan_from_db) must return byte-identical plan dicts from the same
    session row — both now via the shared canonical assemble_plan."""
    sid = _seed_plan(tmp_coach_db)

    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM workout_sessions WHERE id = ?", (sid,)).fetchone()

    router_plan = coach_mod._assemble_plan(conn, row)
    mcp_plan = _assemble_plan_from_db(conn.cursor(), sid)
    conn.close()

    assert router_plan == mcp_plan
    assert router_plan["session_id"] == sid  # canonical shape includes session_id


@pytest.mark.unit
def test_assemble_plan_shape(test_app, tmp_coach_db):
    """Direct unit test of the canonical reader (reachable without booting MCP)."""
    sid = _seed_plan(tmp_coach_db)
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM workout_sessions WHERE id = ?", (sid,)).fetchone()
    plan = assemble_plan(conn.cursor(), row)
    conn.close()

    assert plan["session_id"] == sid
    assert plan["day_name"] == "Push"
    assert plan["total_duration_min"] == 45
    assert len(plan["blocks"]) == 1
    block = plan["blocks"][0]
    assert block["block_type"] == "strength"
    assert [e["id"] for e in block["exercises"]] == ["ex_1", "warmup_0"]
    strength, checklist = block["exercises"]
    assert strength["target_sets"] == 3 and strength["target_reps"] == "10"
    assert checklist["items"] == ["Cat-Cow x10"]
    # absent optional fields are omitted, not null
    assert "target_duration_min" not in strength
