"""Tests for workout start/end endpoints and async hook runner."""

import asyncio
import json
import os
import sqlite3
import stat
import time

import pytest
from unittest.mock import patch


# ==================== Unit Tests ====================


@pytest.mark.unit
class TestHookSchema:
    def test_hook_tables_exist(self, test_app, tmp_coach_db):
        """Verify hook tables are created during init_database."""
        conn = sqlite3.connect(tmp_coach_db)
        cursor = conn.cursor()
        cursor.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
        tables = [row[0] for row in cursor.fetchall()]
        conn.close()

        assert "workout_hook_results" in tables
        assert "workout_hook_data" in tables

    def test_hook_results_schema(self, test_app, tmp_coach_db):
        """Verify workout_hook_results columns."""
        conn = sqlite3.connect(tmp_coach_db)
        cursor = conn.cursor()
        cursor.execute("PRAGMA table_info(workout_hook_results)")
        columns = {row[1]: row[2] for row in cursor.fetchall()}
        conn.close()

        assert "session_id" in columns
        assert "hook_type" in columns
        assert "fired_at" in columns
        assert "exit_code" in columns

    def test_hook_data_schema(self, test_app, tmp_coach_db):
        """Verify workout_hook_data columns."""
        conn = sqlite3.connect(tmp_coach_db)
        cursor = conn.cursor()
        cursor.execute("PRAGMA table_info(workout_hook_data)")
        columns = {row[1]: row[2] for row in cursor.fetchall()}
        conn.close()

        assert "result_id" in columns
        assert "key" in columns
        assert "value" in columns


# ==================== Integration Tests ====================


def _make_hook_script(tmp_path, name, output_json, exit_code=0):
    """Create a test hook script that outputs JSON and exits with given code."""
    script = tmp_path / name
    script.write_text(
        f"#!/bin/bash\n"
        f"cat <<'EOF'\n"
        f"{json.dumps(output_json)}\n"
        f"EOF\n"
        f"exit {exit_code}\n"
    )
    script.chmod(script.stat().st_mode | stat.S_IEXEC)
    return script


def _make_bad_hook_script(tmp_path, name, output_text="not json", exit_code=0):
    """Create a test hook script that outputs non-JSON text."""
    script = tmp_path / name
    script.write_text(
        f"#!/bin/bash\n"
        f"echo '{output_text}'\n"
        f"exit {exit_code}\n"
    )
    script.chmod(script.stat().st_mode | stat.S_IEXEC)
    return script


def _get_session_id(tmp_coach_db):
    """Get the first session ID from the test database."""
    conn = sqlite3.connect(tmp_coach_db)
    cursor = conn.cursor()
    cursor.execute("SELECT id FROM workout_sessions LIMIT 1")
    row = cursor.fetchone()
    conn.close()
    return row[0] if row else None


def _poll_exit_code(tmp_coach_db, result_id, timeout=5.0):
    """Poll the DB until exit_code is set (not NULL) or timeout."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        conn = sqlite3.connect(tmp_coach_db)
        cursor = conn.cursor()
        cursor.execute(
            "SELECT exit_code FROM workout_hook_results WHERE id = ?",
            (result_id,),
        )
        row = cursor.fetchone()
        conn.close()
        if row and row[0] is not None:
            return row[0]
        time.sleep(0.1)
    return None


@pytest.mark.integration
class TestStartWorkout:
    def test_start_workout_success(self, client, coach_seeded_database, tmp_path, tmp_coach_db, monkeypatch):
        """POST /workout/{id}/start creates a result row."""
        script = _make_hook_script(tmp_path, "pre-hook.sh", {"readiness": 75})
        monkeypatch.setattr("modules.coach.get_hook_path", lambda t: script if t == "pre" else None)

        session_id = _get_session_id(tmp_coach_db)
        resp = client.post(f"/api/coach/workout/{session_id}/start")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "started"
        assert "result_id" in data

        # Verify DB row was created
        conn = sqlite3.connect(tmp_coach_db)
        cursor = conn.cursor()
        cursor.execute(
            "SELECT * FROM workout_hook_results WHERE id = ?",
            (data["result_id"],),
        )
        row = cursor.fetchone()
        conn.close()
        assert row is not None

    def test_start_workout_completion(self, client, coach_seeded_database, tmp_path, tmp_coach_db, monkeypatch):
        """After async subprocess completes, exit_code and data are stored."""
        hook_data = {"training_readiness": 70, "hrv_status": "balanced"}
        script = _make_hook_script(tmp_path, "pre-hook.sh", hook_data)
        monkeypatch.setattr("modules.coach.get_hook_path", lambda t: script if t == "pre" else None)

        session_id = _get_session_id(tmp_coach_db)
        resp = client.post(f"/api/coach/workout/{session_id}/start")
        result_id = resp.json()["result_id"]

        # Wait for async hook to complete
        exit_code = _poll_exit_code(tmp_coach_db, result_id)
        assert exit_code == 0

        # Verify data was stored
        conn = sqlite3.connect(tmp_coach_db)
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute(
            "SELECT key, value FROM workout_hook_data WHERE result_id = ?",
            (result_id,),
        )
        rows = cursor.fetchall()
        conn.close()

        stored = {r["key"]: r["value"] for r in rows}
        assert stored["training_readiness"] == "70"
        assert stored["hrv_status"] == "balanced"

    def test_start_workout_no_script(self, client, coach_seeded_database, tmp_coach_db, monkeypatch):
        """Error when no start action is configured."""
        monkeypatch.setattr("modules.coach.get_hook_path", lambda t: None)

        session_id = _get_session_id(tmp_coach_db)
        resp = client.post(f"/api/coach/workout/{session_id}/start")
        assert resp.status_code == 400

    def test_start_workout_nonexistent_session(self, client, coach_seeded_database, tmp_path, monkeypatch):
        """Error when session does not exist."""
        script = _make_hook_script(tmp_path, "pre-hook.sh", {})
        monkeypatch.setattr("modules.coach.get_hook_path", lambda t: script if t == "pre" else None)

        resp = client.post("/api/coach/workout/99999/start")
        assert resp.status_code == 404

    def test_start_workout_retry_upserts(self, client, coach_seeded_database, tmp_path, tmp_coach_db, monkeypatch):
        """Starting workout twice upserts the result row."""
        script = _make_hook_script(tmp_path, "pre-hook.sh", {"v": 1})
        monkeypatch.setattr("modules.coach.get_hook_path", lambda t: script if t == "pre" else None)

        session_id = _get_session_id(tmp_coach_db)

        resp1 = client.post(f"/api/coach/workout/{session_id}/start")
        result_id_1 = resp1.json()["result_id"]

        # Wait for first hook to complete
        _poll_exit_code(tmp_coach_db, result_id_1)

        # Start again with different data
        script2 = _make_hook_script(tmp_path, "pre-hook2.sh", {"v": 2})
        monkeypatch.setattr("modules.coach.get_hook_path", lambda t: script2 if t == "pre" else None)

        resp2 = client.post(f"/api/coach/workout/{session_id}/start")
        assert resp2.status_code == 200
        result_id_2 = resp2.json()["result_id"]

        # Should reuse the same result row
        assert result_id_2 == result_id_1

        # Wait for second hook to complete
        _poll_exit_code(tmp_coach_db, result_id_2)

        # Only one result row should exist
        conn = sqlite3.connect(tmp_coach_db)
        cursor = conn.cursor()
        cursor.execute(
            "SELECT COUNT(*) FROM workout_hook_results WHERE session_id = ? AND hook_type = ?",
            (session_id, "pre"),
        )
        assert cursor.fetchone()[0] == 1
        conn.close()


@pytest.mark.integration
class TestEndWorkout:
    def test_end_workout_success(self, client, coach_seeded_database, tmp_path, tmp_coach_db, monkeypatch):
        """POST /workout/{id}/end creates a result row."""
        script = _make_hook_script(tmp_path, "post-hook.sh", {"hr": 142})
        monkeypatch.setattr("modules.coach.get_hook_path", lambda t: script if t == "post" else None)

        session_id = _get_session_id(tmp_coach_db)
        resp = client.post(f"/api/coach/workout/{session_id}/end")
        assert resp.status_code == 200
        assert resp.json()["status"] == "ended"


@pytest.mark.integration
class TestUndoWorkout:
    def test_undo_start(self, client, coach_seeded_database, tmp_path, tmp_coach_db, monkeypatch):
        """DELETE /workout/{id}/start removes the result and cascaded data."""
        script = _make_hook_script(tmp_path, "pre-hook.sh", {"k": "v"})
        monkeypatch.setattr("modules.coach.get_hook_path", lambda t: script if t == "pre" else None)

        session_id = _get_session_id(tmp_coach_db)
        resp = client.post(f"/api/coach/workout/{session_id}/start")
        result_id = resp.json()["result_id"]
        _poll_exit_code(tmp_coach_db, result_id)

        del_resp = client.delete(f"/api/coach/workout/{session_id}/start")
        assert del_resp.status_code == 200
        assert del_resp.json()["status"] == "deleted"

        # Verify row is gone
        conn = sqlite3.connect(tmp_coach_db)
        cursor = conn.cursor()
        cursor.execute(
            "SELECT COUNT(*) FROM workout_hook_results WHERE id = ?",
            (result_id,),
        )
        assert cursor.fetchone()[0] == 0
        cursor.execute(
            "SELECT COUNT(*) FROM workout_hook_data WHERE result_id = ?",
            (result_id,),
        )
        assert cursor.fetchone()[0] == 0
        conn.close()

    def test_undo_start_not_found(self, client, coach_seeded_database, tmp_coach_db):
        """DELETE returns 404 when no start result exists."""
        session_id = _get_session_id(tmp_coach_db)
        resp = client.delete(f"/api/coach/workout/{session_id}/start")
        assert resp.status_code == 404

    def test_undo_end(self, client, coach_seeded_database, tmp_path, tmp_coach_db, monkeypatch):
        """DELETE /workout/{id}/end removes the result."""
        script = _make_hook_script(tmp_path, "post-hook.sh", {"k": "v"})
        monkeypatch.setattr("modules.coach.get_hook_path", lambda t: script if t == "post" else None)

        session_id = _get_session_id(tmp_coach_db)
        client.post(f"/api/coach/workout/{session_id}/end")

        del_resp = client.delete(f"/api/coach/workout/{session_id}/end")
        assert del_resp.status_code == 200


@pytest.mark.integration
class TestWorkoutStatus:
    def test_get_workout_status(self, client, coach_seeded_database, tmp_path, tmp_coach_db, monkeypatch):
        """GET returns workout status with data for a session."""
        hook_data = {"readiness": 80}
        script = _make_hook_script(tmp_path, "pre-hook.sh", hook_data)
        monkeypatch.setattr("modules.coach.get_hook_path", lambda t: script if t == "pre" else None)
        monkeypatch.setattr("modules.coach._is_hook_available", lambda t: t == "pre")

        session_id = _get_session_id(tmp_coach_db)
        fire_resp = client.post(f"/api/coach/workout/{session_id}/start")
        result_id = fire_resp.json()["result_id"]
        _poll_exit_code(tmp_coach_db, result_id)

        resp = client.get(f"/api/coach/workout/{session_id}/status")
        assert resp.status_code == 200
        data = resp.json()

        assert data["start"] is not None
        assert data["start"]["exit_code"] == 0
        assert data["start"]["data"]["readiness"] == "80"
        assert data["start"]["fired_at"] is not None
        assert data["end"] is None
        assert "actions_available" in data
        assert data["actions_available"]["start"] is True

    def test_get_workout_status_empty(self, client, coach_seeded_database, tmp_coach_db, monkeypatch):
        """GET returns nulls when no actions have been taken."""
        monkeypatch.setattr("modules.coach._is_hook_available", lambda t: False)

        session_id = _get_session_id(tmp_coach_db)
        resp = client.get(f"/api/coach/workout/{session_id}/status")
        assert resp.status_code == 200
        data = resp.json()
        assert data["start"] is None
        assert data["end"] is None


@pytest.mark.integration
class TestWorkoutConfig:
    def test_config_none_available(self, client, coach_seeded_database, monkeypatch):
        """GET /workout/config returns availability flags."""
        monkeypatch.setattr("modules.coach._is_hook_available", lambda t: False)

        resp = client.get("/api/coach/workout/config")
        assert resp.status_code == 200
        data = resp.json()
        assert data["start"] is False
        assert data["end"] is False

    def test_config_with_scripts(self, client, coach_seeded_database, monkeypatch):
        """GET /workout/config returns true when scripts exist."""
        monkeypatch.setattr("modules.coach._is_hook_available", lambda t: True)

        resp = client.get("/api/coach/workout/config")
        assert resp.status_code == 200
        data = resp.json()
        assert data["start"] is True
        assert data["end"] is True


@pytest.mark.integration
class TestHookRunnerEdgeCases:
    def test_nonzero_exit(self, client, coach_seeded_database, tmp_path, tmp_coach_db, monkeypatch):
        """Non-zero exit code is stored, no data saved."""
        script = _make_hook_script(tmp_path, "fail-hook.sh", {"k": "v"}, exit_code=1)
        monkeypatch.setattr("modules.coach.get_hook_path", lambda t: script if t == "pre" else None)

        session_id = _get_session_id(tmp_coach_db)
        resp = client.post(f"/api/coach/workout/{session_id}/start")
        result_id = resp.json()["result_id"]
        exit_code = _poll_exit_code(tmp_coach_db, result_id)
        assert exit_code == 1

        # No data should be stored for non-zero exit
        conn = sqlite3.connect(tmp_coach_db)
        cursor = conn.cursor()
        cursor.execute(
            "SELECT COUNT(*) FROM workout_hook_data WHERE result_id = ?",
            (result_id,),
        )
        assert cursor.fetchone()[0] == 0
        conn.close()

    def test_invalid_json_output(self, client, coach_seeded_database, tmp_path, tmp_coach_db, monkeypatch):
        """Invalid JSON output: exit code stored, no data."""
        script = _make_bad_hook_script(tmp_path, "bad-hook.sh", "not json at all")
        monkeypatch.setattr("modules.coach.get_hook_path", lambda t: script if t == "pre" else None)

        session_id = _get_session_id(tmp_coach_db)
        resp = client.post(f"/api/coach/workout/{session_id}/start")
        result_id = resp.json()["result_id"]
        exit_code = _poll_exit_code(tmp_coach_db, result_id)
        assert exit_code == 0  # Script exits 0 but output is invalid

        conn = sqlite3.connect(tmp_coach_db)
        cursor = conn.cursor()
        cursor.execute(
            "SELECT COUNT(*) FROM workout_hook_data WHERE result_id = ?",
            (result_id,),
        )
        assert cursor.fetchone()[0] == 0
        conn.close()
