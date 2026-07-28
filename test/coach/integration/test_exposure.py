"""Integration tests for the optional `exposure` identity key.

Phase 1 covers schema (migration 7), plan assembly, and the core requirement:
a log row inherits the planned exercise's exposure AT LOGGING TIME and FREEZES
it, so history stays correctly partitioned when the plan is later edited or the
session renamed — the property day_name could never provide. Phase 2 adds the
MCP write/read-path tests in test/test_coach_mcp.py.
"""

import sqlite3
from datetime import datetime

import pytest

from modules.coach_plans import store_plan

# Two exposures of the SAME movement in one session — the case that forces the
# key to be exercise-level rather than session-level.
HEAVY_KEY = "squat_heavy"
LIGHT_KEY = "squat_light"
PLAIN_KEY = "row"


@pytest.fixture
def exposure_seeded_db(client, coach_registered_client, tmp_coach_db):
    """Seed one plan whose two squat entries carry different exposures, plus a
    third exercise with none (absence must break nothing)."""
    today = datetime.now().strftime("%Y-%m-%d")
    plan = {
        "day_name": "Lower A",
        "location": "Home",
        "phase": "Foundation",
        "total_duration_min": 50,
        "blocks": [{
            "block_type": "strength",
            "title": "Main",
            "exercises": [
                {"id": HEAVY_KEY, "name": "Back Squat", "type": "strength",
                 "target_sets": 3, "target_reps": "5", "exposure": " heavy "},
                {"id": LIGHT_KEY, "name": "Back Squat", "type": "strength",
                 "target_sets": 2, "target_reps": "10", "exposure": "light"},
                {"id": PLAIN_KEY, "name": "Bent-Over Row", "type": "strength",
                 "target_sets": 3, "target_reps": "8"},
            ],
        }],
    }

    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    store_plan(conn.cursor(), today, plan, modified_by="test")
    conn.commit()
    conn.close()

    return {"client_id": coach_registered_client, "date": today}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _upload(client, client_id, date, log):
    resp = client.post(
        "/api/coach/sync",
        json={"clientId": client_id, "logs": {date: log}},
    )
    assert resp.status_code == 200
    return resp.json()


def _server_day(client, client_id, date):
    resp = client.get(f"/api/coach/sync?client_id={client_id}")
    assert resp.status_code == 200
    return resp.json()["logs"].get(date, {})


def _upload_with_token(client, client_id, date, log):
    """Upload echoing the server's current day + per-exercise stamps — i.e. an
    up-to-date client, whose edit passes arbitration."""
    server = _server_day(client, client_id, date)
    if server.get("_lastModified"):
        log["_baseLastModifiedAt"] = server["_lastModified"]
    for key, val in log.items():
        srv_ex = server.get(key)
        if isinstance(val, dict) and isinstance(srv_ex, dict) and srv_ex.get("_lastModified"):
            val["_baseLastModifiedAt"] = srv_ex["_lastModified"]
    return _upload(client, client_id, date, log)


def _log_rows(tmp_coach_db, date):
    """The stored exercise_logs rows for `date`, keyed by exercise_key."""
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        "SELECT el.* FROM exercise_logs el "
        "JOIN workout_session_logs sl ON sl.id = el.session_log_id "
        "WHERE sl.date = ?", (date,)
    ).fetchall()
    conn.close()
    return {r["exercise_key"]: r for r in rows}


def _edit_plan_exposure(tmp_coach_db, date, exercise_key, exposure):
    """Rewrite a planned exercise's exposure in place (what Phase 2's
    `update_exercise` will do) — store_plan itself refuses to replace a plan
    that already has logs."""
    conn = sqlite3.connect(tmp_coach_db)
    conn.execute(
        "UPDATE planned_exercises SET exposure = ? WHERE exercise_key = ? AND session_id = "
        "(SELECT id FROM workout_sessions WHERE date = ?)",
        (exposure, exercise_key, date),
    )
    conn.commit()
    conn.close()


def _a_log(note="v1"):
    return {
        "session_feedback": {"general_notes": note},
        HEAVY_KEY: {"user_note": note,
                    "sets": [{"set_num": 1, "weight": 135, "reps": 5, "rpe": 8}]},
        LIGHT_KEY: {"sets": [{"set_num": 1, "weight": 95, "reps": 10, "rpe": 6}]},
        PLAIN_KEY: {"sets": [{"set_num": 1, "weight": 65, "reps": 8}]},
    }


# ===========================================================================
# Plan assembly
# ===========================================================================

@pytest.mark.integration
class TestExposureAssembly:
    def test_exposure_round_trips_normalized(self, client, exposure_seeded_db):
        """Plan sync carries the exposure, normalized at ingest (`" heavy "` →
        `"HEAVY"`), and omits the key entirely where there is none."""
        resp = client.get(f"/api/coach/sync?client_id={exposure_seeded_db['client_id']}")
        assert resp.status_code == 200
        plan = resp.json()["plans"][exposure_seeded_db["date"]]

        by_id = {ex["id"]: ex for ex in plan["blocks"][0]["exercises"]}
        assert by_id[HEAVY_KEY]["exposure"] == "HEAVY"
        assert by_id[LIGHT_KEY]["exposure"] == "LIGHT"
        assert "exposure" not in by_id[PLAIN_KEY]


# ===========================================================================
# Log inheritance + freeze (the core requirement)
# ===========================================================================

@pytest.mark.integration
class TestExposureFreeze:
    def test_log_inherits_planned_exposure(self, client, exposure_seeded_db, tmp_coach_db):
        """Each log row takes its planned exercise's exposure at logging time —
        including NULL for the exercise that has none."""
        date = exposure_seeded_db["date"]
        _upload(client, exposure_seeded_db["client_id"], date, _a_log())

        rows = _log_rows(tmp_coach_db, date)
        assert rows[HEAVY_KEY]["exposure"] == "HEAVY"
        assert rows[LIGHT_KEY]["exposure"] == "LIGHT"
        assert rows[PLAIN_KEY]["exposure"] is None

    def test_later_plan_edit_never_leaks_into_a_logged_row(
        self, client, exposure_seeded_db, tmp_coach_db
    ):
        """THE requirement: after the day is logged, changing the plan's exposure
        and then editing that log leaves the log's exposure untouched. The log
        write must genuinely land (the note changes) — otherwise this would pass
        for the wrong reason, by the edit being rejected."""
        date = exposure_seeded_db["date"]
        client_id = exposure_seeded_db["client_id"]
        _upload(client, client_id, date, _a_log("v1"))

        # The plan is re-programmed after the fact...
        _edit_plan_exposure(tmp_coach_db, date, HEAVY_KEY, "MODERATE")

        # ...and the user later edits the same log entry (with a current token,
        # so the write is accepted and the row is rewritten).
        edited = _a_log("v2")
        _upload_with_token(client, client_id, date, edited)

        rows = _log_rows(tmp_coach_db, date)
        assert rows[HEAVY_KEY]["user_note"] == "v2", "the edit was not applied"
        assert rows[HEAVY_KEY]["exposure"] == "HEAVY", "plan edit leaked into history"
        assert rows[LIGHT_KEY]["exposure"] == "LIGHT"

    def test_clearing_the_plans_exposure_leaves_history_partitioned(
        self, client, exposure_seeded_db, tmp_coach_db
    ):
        """Clearing the plan's exposure is just another edit: the already-logged
        row keeps its key, so the historical chain does not silently merge into
        the slug's default exposure."""
        date = exposure_seeded_db["date"]
        client_id = exposure_seeded_db["client_id"]
        _upload(client, client_id, date, _a_log("v1"))

        _edit_plan_exposure(tmp_coach_db, date, HEAVY_KEY, None)
        _upload_with_token(client, client_id, date, _a_log("v2"))

        assert _log_rows(tmp_coach_db, date)[HEAVY_KEY]["exposure"] == "HEAVY"

    def test_day_name_rename_changes_nothing(self, client, exposure_seeded_db, tmp_coach_db):
        """The motivating failure: a mid-plan session rename broke day_name-based
        role chains. Renaming the session must not touch the logged exposures."""
        date = exposure_seeded_db["date"]
        client_id = exposure_seeded_db["client_id"]
        _upload(client, client_id, date, _a_log("v1"))
        before = {k: r["exposure"] for k, r in _log_rows(tmp_coach_db, date).items()}

        conn = sqlite3.connect(tmp_coach_db)
        conn.execute(
            "UPDATE workout_sessions SET day_name = 'Lower A (Rack)' WHERE date = ?", (date,)
        )
        conn.commit()
        conn.close()

        _upload_with_token(client, client_id, date, _a_log("v2"))

        after = {k: r["exposure"] for k, r in _log_rows(tmp_coach_db, date).items()}
        assert after == before == {HEAVY_KEY: "HEAVY", LIGHT_KEY: "LIGHT", PLAIN_KEY: None}

    def test_off_plan_entry_has_no_exposure(self, client, exposure_seeded_db, tmp_coach_db):
        """An ad-hoc entry has no planned_exercises row to inherit from — its
        exposure stays NULL rather than being invented."""
        date = exposure_seeded_db["date"]
        _upload(client, exposure_seeded_db["client_id"], date, {
            "extra_zone2": {"duration_min": 30, "avg_hr": 128},
        })

        row = _log_rows(tmp_coach_db, date)["extra_zone2"]
        assert row["exercise_id"] is None
        assert row["exposure"] is None


# ===========================================================================
# Archive (Layer 2 stays lossless)
# ===========================================================================

@pytest.mark.integration
def test_archive_carries_the_original_exposure(client, exposure_seeded_db, tmp_coach_db):
    """The pre-overwrite archive copy keeps the exposure, so a restore from it
    reconstructs the same history partitioning."""
    date = exposure_seeded_db["date"]
    client_id = exposure_seeded_db["client_id"]
    _upload(client, client_id, date, _a_log("v1"))
    _upload_with_token(client, client_id, date, _a_log("v2"))

    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    archived = conn.execute(
        "SELECT ela.* FROM exercise_logs_archive ela "
        "JOIN workout_session_logs_archive sla ON sla.original_id = ela.session_log_id "
        "WHERE sla.date = ?", (date,)
    ).fetchall()
    conn.close()

    by_key = {r["exercise_key"]: r for r in archived}
    assert by_key[HEAVY_KEY]["exposure"] == "HEAVY"
    assert by_key[LIGHT_KEY]["exposure"] == "LIGHT"
    assert by_key[PLAIN_KEY]["exposure"] is None
