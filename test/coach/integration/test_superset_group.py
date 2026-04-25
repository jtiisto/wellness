"""Integration tests for the superset_group field on planned_exercises.

Phase 1 covers schema + plan assembly (round-trip via sync). Phase 2 will add
the MCP write path tests in a separate file.
"""

import pytest
import sqlite3
from datetime import datetime, timezone


@pytest.fixture
def superset_seeded_db(client, coach_registered_client, tmp_coach_db):
    """Seed a plan with two strength exercises sharing superset_group='A'.

    A third exercise in the same block has no superset_group so we can assert
    the field is omitted when null.
    """
    today = datetime.now().strftime("%Y-%m-%d")
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    cursor = conn.cursor()

    cursor.execute("""
        INSERT INTO workout_sessions (date, day_name, location, phase, last_modified, modified_by)
        VALUES (?, 'Superset Day', 'Home', 'Foundation', ?, 'test')
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
         target_sets, target_reps, superset_group)
        VALUES (?, ?, 'bench', 0, 'Bench Press', 'strength', 3, '8', 'A')
    """, (s, b))
    cursor.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type,
         target_sets, target_reps, superset_group)
        VALUES (?, ?, 'row', 1, 'Bent Row', 'strength', 3, '8', 'A')
    """, (s, b))
    cursor.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type,
         target_sets, target_reps)
        VALUES (?, ?, 'core', 2, 'Plank', 'strength', 3, '30s')
    """, (s, b))

    conn.commit()
    conn.close()
    return {"client_id": coach_registered_client, "date": today}


@pytest.mark.integration
class TestSupersetGroupAssembly:
    def test_superset_group_round_trips(self, client, superset_seeded_db):
        """Plan sync includes superset_group on exercises that have it."""
        resp = client.get(f"/api/coach/sync?client_id={superset_seeded_db['client_id']}")
        assert resp.status_code == 200
        plan = resp.json()["plans"][superset_seeded_db["date"]]

        exercises = plan["blocks"][0]["exercises"]
        by_id = {ex["id"]: ex for ex in exercises}

        assert by_id["bench"]["superset_group"] == "A"
        assert by_id["row"]["superset_group"] == "A"

    def test_superset_group_omitted_when_absent(self, client, superset_seeded_db):
        """Exercises without a group don't get a superset_group key in the response."""
        resp = client.get(f"/api/coach/sync?client_id={superset_seeded_db['client_id']}")
        plan = resp.json()["plans"][superset_seeded_db["date"]]

        exercises = plan["blocks"][0]["exercises"]
        by_id = {ex["id"]: ex for ex in exercises}

        assert "superset_group" not in by_id["core"]

    def test_freeform_label_round_trips(self, client, coach_registered_client, tmp_coach_db):
        """Free-form labels like 'Triplet A' survive the round trip."""
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
            (session_id, block_id, exercise_key, position, name, exercise_type, superset_group)
            VALUES (?, ?, 'a', 0, 'A', 'strength', 'Triplet A')
        """, (s, b))
        conn.commit()
        conn.close()

        resp = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        plan = resp.json()["plans"][today]
        ex = plan["blocks"][0]["exercises"][0]
        assert ex["superset_group"] == "Triplet A"
