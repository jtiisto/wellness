"""Shared test-data seeds.

One implementation of the canonical "Test Workout" coach plan, used by BOTH the
unit/integration conftest and the e2e conftest. The two used to carry ~90-line
near-duplicate raw-SQL blocks that had already drifted (the e2e copy grew
supersets and intervals); every coach schema change had to be mirrored by hand.
Feature flags preserve each caller's exact seeded content.
"""
from datetime import datetime, timezone


def seed_coach_plan(conn, *, today, yesterday=None, supersets=False,
                    intervals=False, tempo=False, now=None):
    """Insert the canonical test plan(s) into an open coach DB connection.

    today/yesterday are local-calendar YYYY-MM-DD strings (the browser and the
    MCP summary windows use local dates). The caller owns the connection:
    commit/close (and any reset) stay with the fixture.

    Returns {"session_id": <today's session id>}.
    """
    if now is None:
        now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    cursor = conn.cursor()

    # --- Today's plan ---
    cursor.execute("""
        INSERT INTO workout_sessions
        (date, day_name, location, phase, last_modified, modified_by)
        VALUES (?, ?, ?, ?, ?, ?)
    """, (today, "Test Workout", "Home", "Foundation", now, "test"))
    s1 = cursor.lastrowid

    # Warmup block with a checklist exercise
    cursor.execute("""
        INSERT INTO session_blocks (session_id, position, block_type, title)
        VALUES (?, 0, 'warmup', 'Warmup')
    """, (s1,))
    b1 = cursor.lastrowid
    cursor.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type)
        VALUES (?, ?, 'warmup_0', 0, 'Stability Start', 'checklist')
    """, (s1, b1))
    e_warmup = cursor.lastrowid
    for i, item in enumerate(["Cat-Cow x10", "Bird-Dog x5/side"]):
        cursor.execute(
            "INSERT INTO checklist_items (exercise_id, position, item_text) VALUES (?, ?, ?)",
            (e_warmup, i, item),
        )

    # Strength block
    cursor.execute("""
        INSERT INTO session_blocks (session_id, position, block_type, title, rest_guidance)
        VALUES (?, 1, 'strength', 'Strength', 'Rest 2 min')
    """, (s1,))
    b2 = cursor.lastrowid
    cursor.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type,
         target_sets, target_reps, guidance_note)
        VALUES (?, ?, 'ex_1', 0, 'KB Goblet Squat', 'strength', 3, '10', 'Tempo 3-1-1')
    """, (s1, b2))
    if supersets:
        cursor.execute("""
            INSERT INTO planned_exercises
            (session_id, block_id, exercise_key, position, name, exercise_type,
             target_sets, target_reps, superset_group)
            VALUES (?, ?, 'ex_pair_a1', 1, 'DB Bench Press', 'strength', 3, '8', 'A')
        """, (s1, b2))
        cursor.execute("""
            INSERT INTO planned_exercises
            (session_id, block_id, exercise_key, position, name, exercise_type,
             target_sets, target_reps, superset_group)
            VALUES (?, ?, 'ex_pair_a2', 2, 'Bent Row', 'strength', 3, '8', 'A')
        """, (s1, b2))
    if tempo:
        # A strength exercise carrying a structured `tempo` and NO guidance_note,
        # so the UI shows the tempo caption distinctly. KB Goblet Squat above
        # keeps its legacy inline "Tempo 3-1-1" guidance_note (no structured
        # tempo) so both the new field and the historical note are exercised.
        cursor.execute("""
            INSERT INTO planned_exercises
            (session_id, block_id, exercise_key, position, name, exercise_type,
             target_sets, target_reps, tempo)
            VALUES (?, ?, 'ex_tempo', 3, 'Front Squat', 'strength', 4, '6', '3-1-2-0')
        """, (s1, b2))

    # Cardio block
    cursor.execute("""
        INSERT INTO session_blocks (session_id, position, block_type, title)
        VALUES (?, 2, 'cardio', 'Conditioning')
    """, (s1,))
    b3 = cursor.lastrowid
    cursor.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type,
         target_duration_min, guidance_note)
        VALUES (?, ?, 'cardio_1', 0, 'Zone 2 Bike', 'duration', 15, 'HR 135-148')
    """, (s1, b3))
    if intervals:
        cursor.execute("""
            INSERT INTO planned_exercises
            (session_id, block_id, exercise_key, position, name, exercise_type,
             rounds, work_duration_sec, rest_duration_sec, guidance_note)
            VALUES (?, ?, 'cardio_2', 1, 'Bike Intervals', 'interval', 4, 30, 90, 'VO2 max effort')
        """, (s1, b3))

    # --- Yesterday's plan (unit/integration fixtures only) ---
    if yesterday:
        cursor.execute("""
            INSERT INTO workout_sessions
            (date, day_name, location, phase, last_modified, modified_by)
            VALUES (?, ?, ?, ?, ?, ?)
        """, (yesterday, "Yesterday's Workout", "Home", "Foundation", now, "test"))
        s2 = cursor.lastrowid
        cursor.execute("""
            INSERT INTO session_blocks (session_id, position, block_type, title)
            VALUES (?, 0, 'strength', 'Strength')
        """, (s2,))
        b_y = cursor.lastrowid
        cursor.execute("""
            INSERT INTO planned_exercises
            (session_id, block_id, exercise_key, position, name, exercise_type,
             target_sets, target_reps)
            VALUES (?, ?, 'ex_1', 0, 'Squat', 'strength', 3, '10')
        """, (s2, b_y))

    return {"session_id": s1}
