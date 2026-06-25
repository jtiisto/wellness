"""Integration tests for the optional `tempo` field on planned_exercises.

Phase 1 covers schema (migration 4) + plan assembly (round-trip via sync).
Phase 2 adds the MCP write-path tests in test/test_coach_mcp.py.
"""

import pytest
import sqlite3
from datetime import datetime, timezone


@pytest.fixture
def tempo_seeded_db(client, coach_registered_client, tmp_coach_db):
    """Seed a plan with a strength exercise carrying tempo='3-1-2-0'.

    A second exercise in the same block has no tempo so we can assert the field
    is omitted when null.
    """
    today = datetime.now().strftime("%Y-%m-%d")
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    cursor = conn.cursor()

    cursor.execute("""
        INSERT INTO workout_sessions (date, day_name, location, phase, last_modified, modified_by)
        VALUES (?, 'Tempo Day', 'Home', 'Foundation', ?, 'test')
    """, (today, now))
    s = cursor.lastrowid

    cursor.execute("""
        INSERT INTO session_blocks (session_id, position, block_type, title)
        VALUES (?, 0, 'strength', 'Strength')
    """, (s,))
    b = cursor.lastrowid

    cursor.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type,
         target_sets, target_reps, tempo)
        VALUES (?, ?, 'squat', 0, 'Back Squat', 'strength', 3, '5', '3-1-2-0')
    """, (s, b))
    cursor.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type,
         target_sets, target_reps)
        VALUES (?, ?, 'dl', 1, 'Deadlift', 'strength', 1, '5')
    """, (s, b))

    conn.commit()
    conn.close()
    return {"client_id": coach_registered_client, "date": today}


@pytest.mark.integration
class TestTempoAssembly:
    def test_tempo_round_trips(self, client, tempo_seeded_db):
        """Plan sync includes tempo on exercises that have it."""
        resp = client.get(f"/api/coach/sync?client_id={tempo_seeded_db['client_id']}")
        assert resp.status_code == 200
        plan = resp.json()["plans"][tempo_seeded_db["date"]]

        by_id = {ex["id"]: ex for ex in plan["blocks"][0]["exercises"]}
        assert by_id["squat"]["tempo"] == "3-1-2-0"

    def test_tempo_omitted_when_absent(self, client, tempo_seeded_db):
        """Exercises without a tempo don't get a tempo key in the response."""
        resp = client.get(f"/api/coach/sync?client_id={tempo_seeded_db['client_id']}")
        plan = resp.json()["plans"][tempo_seeded_db["date"]]

        by_id = {ex["id"]: ex for ex in plan["blocks"][0]["exercises"]}
        assert "tempo" not in by_id["dl"]

    def test_freeform_value_round_trips(self, client, coach_registered_client, tmp_coach_db):
        """Free-form notations like '30X1' survive the round trip unchanged."""
        today = datetime.now().strftime("%Y-%m-%d")
        now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

        conn = sqlite3.connect(tmp_coach_db)
        conn.execute("PRAGMA foreign_keys = ON")
        cur = conn.cursor()
        cur.execute("""
            INSERT INTO workout_sessions (date, day_name, last_modified, modified_by)
            VALUES (?, 'D', ?, 't')
        """, (today, now))
        s = cur.lastrowid
        cur.execute("""
            INSERT INTO session_blocks (session_id, position, block_type, title)
            VALUES (?, 0, 'strength', 'S')
        """, (s,))
        b = cur.lastrowid
        cur.execute("""
            INSERT INTO planned_exercises
            (session_id, block_id, exercise_key, position, name, exercise_type, tempo)
            VALUES (?, ?, 'a', 0, 'A', 'strength', '30X1')
        """, (s, b))
        conn.commit()
        conn.close()

        resp = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        plan = resp.json()["plans"][today]
        ex = plan["blocks"][0]["exercises"][0]
        assert ex["tempo"] == "30X1"
