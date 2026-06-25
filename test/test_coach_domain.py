"""Cross-transport contract + unit tests for the shared coach domain modules
(Phase 3). Pins that the FastAPI router and the MCP server assemble identical
shapes from the same DB row — the durable guard against the §3.15 divergence."""
import sqlite3

import pytest

import modules.coach as coach_mod
from modules.coach_plans import assemble_plan, store_plan
from coach_mcp.server import _assemble_plan_from_db, _assemble_log_from_db


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


@pytest.mark.unit
def test_store_plan_round_trips_via_assemble(test_app, tmp_coach_db):
    """The moved write helpers (store_plan -> insert_block) round-trip through
    the canonical reader — directly unit-testable now, no MCP boot needed."""
    plan = {
        "day_name": "Pull", "location": "Gym", "phase": "Build",
        "total_duration_min": 50,
        "blocks": [{
            "block_type": "strength", "title": "Main", "rest_guidance": "Rest 2 min",
            "exercises": [
                {"id": "row_1", "name": "Row", "type": "strength",
                 "target_sets": 4, "target_reps": "8"},
                {"id": "wu", "name": "Warmup", "type": "checklist", "items": ["Band x10"]},
            ],
        }],
    }
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    sid = store_plan(conn.cursor(), "2026-06-01", plan, modified_by="test")
    conn.commit()
    row = conn.execute("SELECT * FROM workout_sessions WHERE id = ?", (sid,)).fetchone()
    got = assemble_plan(conn.cursor(), row)
    conn.close()

    assert got["day_name"] == "Pull"
    assert got["total_duration_min"] == 50
    blk = got["blocks"][0]
    assert blk["block_type"] == "strength" and blk["rest_guidance"] == "Rest 2 min"
    assert [e["id"] for e in blk["exercises"]] == ["row_1", "wu"]
    assert blk["exercises"][0]["target_sets"] == 4
    assert blk["exercises"][1]["items"] == ["Band x10"]


@pytest.mark.unit
def test_tempo_promoted_to_field_not_folded_into_guidance_note():
    """Tempo is a structured field now: the raw->formed transform surfaces it as
    `tempo` and no longer appends "Tempo X" to guidance_note. Other cues
    (load_guide/notes) still fold into the note."""
    from modules.coach_plans import transform_block_to_exercises

    block = {
        "block_type": "strength",
        "title": "Main",
        "exercises": [
            {"name": "Goblet Squat", "sets": 3, "reps": "10",
             "tempo": "3-1-2-0", "load_guide": "RPE 8"},
        ],
    }
    [ex] = transform_block_to_exercises(block, 0)

    assert ex["tempo"] == "3-1-2-0"
    assert ex.get("guidance_note") == "RPE 8"
    assert "Tempo" not in (ex.get("guidance_note") or "")


@pytest.mark.unit
def test_store_plan_round_trips_tempo(test_app, tmp_coach_db):
    """tempo on a planned strength exercise persists and reads back via the
    canonical assembler — trimmed to text, and omitted entirely when absent."""
    plan = {
        "day_name": "Legs", "total_duration_min": 40,
        "blocks": [{
            "block_type": "strength", "title": "Main",
            "exercises": [
                {"id": "sq", "name": "Squat", "type": "strength",
                 "target_sets": 3, "target_reps": "5", "tempo": " 30X1 "},
                {"id": "dl", "name": "Deadlift", "type": "strength",
                 "target_sets": 1, "target_reps": "5"},
            ],
        }],
    }
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    sid = store_plan(conn.cursor(), "2026-06-02", plan, modified_by="test")
    conn.commit()
    row = conn.execute("SELECT * FROM workout_sessions WHERE id=?", (sid,)).fetchone()
    got = assemble_plan(conn.cursor(), row)
    conn.close()

    exs = {e["id"]: e for e in got["blocks"][0]["exercises"]}
    assert exs["sq"]["tempo"] == "30X1"      # normalized (trimmed) text
    assert "tempo" not in exs["dl"]          # omitted when absent


@pytest.mark.unit
def test_log_lean_vs_rich_shapes(coach_seeded_database, tmp_coach_db):
    """§3.15 for logs: both transports share the raw per-exercise core, but the
    sync path stays LEAN (no derived completion/stats — the PWA derives it) while
    the MCP path is RICH (adds per-exercise completion + session_completion)."""
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    log_row = conn.execute(
        "SELECT * FROM workout_session_logs ORDER BY date DESC LIMIT 1"
    ).fetchone()
    sess = conn.execute(
        "SELECT id FROM workout_sessions WHERE date = ?", (log_row["date"],)
    ).fetchone()
    session_id = sess["id"] if sess else None

    lean = coach_mod._assemble_log(conn, log_row)
    rich = _assemble_log_from_db(conn.cursor(), log_row["id"], session_id)
    conn.close()

    # Sync shape: feedback + raw entries, NO derived completion / stats.
    assert "session_feedback" in lean
    assert "session_completion" not in lean
    assert "workout_stats" not in lean
    assert "ex_1" in lean and "completed" not in lean["ex_1"]

    # MCP shape: same raw entries PLUS derived completion.
    assert "session_completion" in rich
    assert "ex_1" in rich and "completed" in rich["ex_1"]

    # The shared raw core is identical across transports.
    assert lean["ex_1"].get("sets") == rich["ex_1"].get("sets")
    assert lean["session_feedback"] == rich["session_feedback"]


@pytest.mark.unit
def test_migration_adds_exercise_log_token(test_app, tmp_coach_db):
    """R3-0: migration 3 adds the per-exercise concurrency token column."""
    conn = sqlite3.connect(tmp_coach_db)
    cols = {r[1] for r in conn.execute("PRAGMA table_info(exercise_logs)")}
    conn.close()
    assert "last_modified" in cols


@pytest.mark.unit
def test_assemble_log_emits_per_exercise_token(test_app, tmp_coach_db):
    """R3-0: assemble_log surfaces each exercise's last_modified as _lastModified
    (the per-exercise base token); a NULL stamp (pre-migration row) is omitted."""
    from modules.coach_logs import assemble_log
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    conn.execute(
        "INSERT INTO workout_session_logs (date, last_modified, modified_by) "
        "VALUES ('2026-06-01', '2026-06-01T00:00:00Z', 'test')"
    )
    slid = conn.execute(
        "SELECT id FROM workout_session_logs WHERE date='2026-06-01'"
    ).fetchone()["id"]
    conn.execute(
        "INSERT INTO exercise_logs (session_log_id, exercise_key, last_modified) "
        "VALUES (?, 'ex_stamped', '2026-06-01T12:00:00Z')", (slid,)
    )
    conn.execute(
        "INSERT INTO exercise_logs (session_log_id, exercise_key, last_modified) "
        "VALUES (?, 'ex_null', NULL)", (slid,)
    )
    conn.commit()
    row = conn.execute("SELECT * FROM workout_session_logs WHERE id=?", (slid,)).fetchone()
    log = assemble_log(conn.cursor(), row)
    conn.close()

    assert log["ex_stamped"]["_lastModified"] == "2026-06-01T12:00:00Z"
    assert "_lastModified" not in log["ex_null"]


@pytest.mark.unit
def test_should_accept_log_write_arbiter():
    """R1: the pure server-side arbiter compares server stamps only (no client
    clock). See plans/phase4-r1-coach-clock-skew.md."""
    from modules.coach_logs import should_accept_log_write

    # No existing row → insert, token irrelevant.
    assert should_accept_log_write(None, None) is True
    assert should_accept_log_write(None, "2026-05-30T00:00:00Z") is True

    # Existing row + token absent → reject (hard cutover).
    assert should_accept_log_write("2026-05-30T00:00:00Z", None) is False

    # Existing row + stored <= base → accept (client saw the latest; equal accepts).
    assert should_accept_log_write("2026-05-30T00:00:00Z", "2026-05-30T00:00:00Z") is True
    assert should_accept_log_write("2026-05-30T00:00:00Z", "2026-05-30T01:00:00Z") is True

    # Existing row + stored > base → reject (client missed a newer server write).
    assert should_accept_log_write("2026-05-30T02:00:00Z", "2026-05-30T01:00:00Z") is False


@pytest.mark.unit
def test_list_scheduled_dates_today_is_injectable(test_app, tmp_coach_db):
    """coach_queries takes an injected `today`, so its date-window logic is
    directly unit-testable without depending on the real clock (plans/ phase 3)."""
    from datetime import date as _date
    from coach_mcp.config import MCPConfig
    from coach_mcp.server import DatabaseManager
    from modules import coach_queries

    conn = sqlite3.connect(tmp_coach_db)
    for d in ("2026-06-10", "2026-06-20"):
        conn.execute(
            "INSERT INTO workout_sessions (date, day_name, last_modified, modified_by) "
            "VALUES (?, 'X', 't', 't')", (d,)
        )
    conn.commit()
    conn.close()

    db = DatabaseManager(MCPConfig.from_db_path(tmp_coach_db))

    # today=06-01 → default window 06-01 .. +6wk includes both.
    wide = coach_queries.list_scheduled_dates(db, today=_date(2026, 6, 1))
    assert "2026-06-10" in wide and "2026-06-20" in wide

    # today=06-15 → window starts 06-15, so 06-10 falls before it.
    narrow = coach_queries.list_scheduled_dates(db, today=_date(2026, 6, 15))
    assert "2026-06-10" not in narrow and "2026-06-20" in narrow
