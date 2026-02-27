"""Unit tests for coach database initialization."""

import pytest
import sqlite3


@pytest.mark.unit
def test_database_tables_created(test_app, tmp_coach_db):
    """Test that all required tables are created."""
    conn = sqlite3.connect(tmp_coach_db)
    cursor = conn.cursor()

    cursor.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
    tables = [row[0] for row in cursor.fetchall()]

    conn.close()

    assert "workout_sessions" in tables
    assert "session_blocks" in tables
    assert "planned_exercises" in tables
    assert "checklist_items" in tables
    assert "workout_session_logs" in tables
    assert "exercise_logs" in tables
    assert "set_logs" in tables
    assert "checklist_log_items" in tables
    assert "clients" in tables
    assert "meta_sync" in tables


@pytest.mark.unit
def test_workout_sessions_schema(test_app, tmp_coach_db):
    """Test workout_sessions table schema."""
    conn = sqlite3.connect(tmp_coach_db)
    cursor = conn.cursor()

    cursor.execute("PRAGMA table_info(workout_sessions)")
    columns = {row[1]: row[2] for row in cursor.fetchall()}

    conn.close()

    assert "date" in columns
    assert "day_name" in columns
    assert "location" in columns
    assert "phase" in columns
    assert "duration_min" in columns
    assert "last_modified" in columns
    assert "modified_by" in columns


@pytest.mark.unit
def test_foreign_keys_enforced(test_app, tmp_coach_db):
    """Test that foreign keys are enforced."""
    conn = sqlite3.connect(tmp_coach_db)
    conn.execute("PRAGMA foreign_keys = ON")
    cursor = conn.cursor()

    with pytest.raises(Exception):
        cursor.execute("""
            INSERT INTO session_blocks (session_id, position, block_type, title)
            VALUES (99999, 0, 'warmup', 'Test')
        """)
        conn.commit()

    conn.close()
