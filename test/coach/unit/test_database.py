"""Unit tests for coach database initialization."""

import pytest
import sqlite3

from modules.db import DbAccessor


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
def test_planned_exercises_has_superset_group(test_app, tmp_coach_db):
    """planned_exercises.superset_group column is created on init."""
    conn = sqlite3.connect(tmp_coach_db)
    cursor = conn.cursor()

    cursor.execute("PRAGMA table_info(planned_exercises)")
    columns = {row[1]: row[2] for row in cursor.fetchall()}

    conn.close()

    assert "superset_group" in columns
    assert columns["superset_group"] == "TEXT"


@pytest.mark.unit
def test_canonical_slug_columns_in_create_table(test_app, tmp_coach_db):
    """canonical_slug exists on both tables from CREATE TABLE (no ALTER needed)."""
    conn = sqlite3.connect(tmp_coach_db)
    cursor = conn.cursor()

    cursor.execute("PRAGMA table_info(planned_exercises)")
    pe_cols = {row[1] for row in cursor.fetchall()}
    cursor.execute("PRAGMA table_info(exercise_logs)")
    el_cols = {row[1] for row in cursor.fetchall()}

    conn.close()

    assert "canonical_slug" in pe_cols
    assert "canonical_slug" in el_cols


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


# ==================== Migration registry (R7) ====================


@pytest.mark.unit
def test_fresh_db_stamped_at_latest_version(tmp_path):
    """A fresh coach DB ends at the latest migration version with WAL on and the
    block interval columns present (added by migration 2)."""
    import modules.coach as coach_mod
    db_path = tmp_path / "coach.db"

    coach_mod.init_database(DbAccessor(db_path, foreign_keys=True))

    conn = sqlite3.connect(db_path)
    assert conn.execute("PRAGMA user_version").fetchone()[0] == len(coach_mod.MIGRATIONS)
    assert conn.execute("PRAGMA journal_mode").fetchone()[0].lower() == "wal"
    block_cols = {row[1] for row in conn.execute("PRAGMA table_info(session_blocks)")}
    assert {"work_duration_sec", "rest_duration_sec"} <= block_cols
    conn.close()


@pytest.mark.unit
def test_adopts_existing_unversioned_db(tmp_path):
    """An existing pre-registry DB (full schema, user_version=0) upgrades cleanly:
    guarded migrations are no-ops, the version is stamped forward, and data is
    untouched. This is the headline 'adopt production DB' safety path (R7)."""
    import modules.coach as coach_mod
    db_path = tmp_path / "coach.db"
    accessor = DbAccessor(db_path, foreign_keys=True)

    # Build the current schema, then simulate the pre-registry state.
    coach_mod.init_database(accessor)
    seed = sqlite3.connect(db_path)
    seed.execute("PRAGMA user_version = 0")  # pre-registry: schema present, unversioned
    seed.execute("INSERT INTO meta_sync (key, value) VALUES ('marker', 'keepme')")
    seed.commit()
    seed.close()

    # Re-init must adopt without error and preserve data.
    coach_mod.init_database(accessor)

    conn = sqlite3.connect(db_path)
    assert conn.execute("PRAGMA user_version").fetchone()[0] == len(coach_mod.MIGRATIONS)
    assert conn.execute("SELECT value FROM meta_sync WHERE key='marker'").fetchone()[0] == "keepme"
    block_cols = {row[1] for row in conn.execute("PRAGMA table_info(session_blocks)")}
    assert {"work_duration_sec", "rest_duration_sec"} <= block_cols
    conn.close()
