"""Integration tests for the optional target_rpe / target_load fields on
planned_exercises.

Phase 1 covers schema (migration 5) + plan assembly (round-trip via sync).
Phase 2 adds the MCP write-path tests in test/test_coach_mcp.py.
"""

import pytest
import sqlite3
from datetime import datetime, timezone


@pytest.fixture
def rx_seeded_db(client, coach_registered_client, tmp_coach_db):
    """Seed a plan with a strength exercise carrying target_rpe='6-7' and
    target_load='70%'. A second exercise has neither, to assert omission."""
    today = datetime.now().strftime("%Y-%m-%d")
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    cur = conn.cursor()

    cur.execute("""
        INSERT INTO workout_sessions (date, day_name, location, phase, last_modified, modified_by)
        VALUES (?, 'RX Day', 'Home', 'Foundation', ?, 'test')
    """, (today, now))
    s = cur.lastrowid
    cur.execute("""
        INSERT INTO session_blocks (session_id, position, block_type, title)
        VALUES (?, 0, 'strength', 'Strength')
    """, (s,))
    b = cur.lastrowid
    cur.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type,
         target_sets, target_reps, target_rpe, target_load)
        VALUES (?, ?, 'squat', 0, 'Back Squat', 'strength', 3, '5', '6-7', '70%')
    """, (s, b))
    cur.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type,
         target_sets, target_reps)
        VALUES (?, ?, 'dl', 1, 'Deadlift', 'strength', 1, '5')
    """, (s, b))

    conn.commit()
    conn.close()
    return {"client_id": coach_registered_client, "date": today}


@pytest.mark.integration
class TestPrescriptionFieldsAssembly:
    def test_rpe_and_load_round_trip(self, client, rx_seeded_db):
        resp = client.get(f"/api/coach/sync?client_id={rx_seeded_db['client_id']}")
        assert resp.status_code == 200
        plan = resp.json()["plans"][rx_seeded_db["date"]]

        by_id = {ex["id"]: ex for ex in plan["blocks"][0]["exercises"]}
        assert by_id["squat"]["target_rpe"] == "6-7"
        assert by_id["squat"]["target_load"] == "70%"

    def test_omitted_when_absent(self, client, rx_seeded_db):
        resp = client.get(f"/api/coach/sync?client_id={rx_seeded_db['client_id']}")
        plan = resp.json()["plans"][rx_seeded_db["date"]]

        by_id = {ex["id"]: ex for ex in plan["blocks"][0]["exercises"]}
        assert "target_rpe" not in by_id["dl"]
        assert "target_load" not in by_id["dl"]
