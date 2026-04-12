"""Integration tests for sync safety layers (data loss prevention).

Layer 0: Cache-Control headers on GET /sync
Layer 1: Timestamp-based stale write rejection
Layer 2: Soft-delete archive before overwrite
Layer 3: (client-side only — not tested here)
"""

import sqlite3
from datetime import datetime, timedelta, timezone

import pytest


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _future_ts(hours=1):
    """Return a UTC ISO timestamp in the future (always newer than server's now)."""
    return (datetime.now(timezone.utc) + timedelta(hours=hours)).isoformat().replace("+00:00", "Z")


def _past_ts(hours=24):
    """Return a UTC ISO timestamp in the past (always older than server's now)."""
    return (datetime.now(timezone.utc) - timedelta(hours=hours)).isoformat().replace("+00:00", "Z")


def _make_log(exercises=True, timestamp=None, client_id="test-client-001"):
    """Build a workout log payload with optional exercise data and timestamp."""
    log = {
        "session_feedback": {
            "pain_discomfort": "None",
            "general_notes": "Good session",
        },
    }
    if exercises:
        log["ex_1"] = {
            "completed": True,
            "user_note": "Felt strong",
            "sets": [
                {"set_num": 1, "weight": 100, "reps": 5, "rpe": 7},
                {"set_num": 2, "weight": 100, "reps": 5, "rpe": 8},
            ],
        }
        log["warmup_0"] = {
            "completed_items": ["Cat-Cow x10", "Bird-Dog x5/side"],
        }
    if timestamp:
        log["_lastModifiedAt"] = timestamp
        log["_lastModifiedBy"] = client_id
    return log


def _upload(client, client_id, date, log):
    resp = client.post(
        "/api/coach/sync",
        json={"clientId": client_id, "logs": {date: log}},
    )
    assert resp.status_code == 200
    return resp.json()


def _download(client, client_id):
    resp = client.get(f"/api/coach/sync?client_id={client_id}")
    assert resp.status_code == 200
    return resp.json()


# ===========================================================================
# Layer 0: Cache-Control headers
# ===========================================================================

@pytest.mark.integration
class TestCacheControlHeaders:
    def test_get_sync_has_cache_control(self, client, coach_registered_client):
        """GET /sync response must include no-cache headers."""
        resp = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        assert resp.status_code == 200
        assert "no-store" in resp.headers.get("cache-control", "")
        assert resp.headers.get("pragma") == "no-cache"

    def test_post_sync_no_cache_requirement(self, client, coach_registered_client):
        """POST /sync doesn't need cache headers (POST is not cached by default)."""
        resp = client.post(
            "/api/coach/sync",
            json={"clientId": coach_registered_client, "logs": {}},
        )
        assert resp.status_code == 200
        # No assertion on cache-control — just verify it doesn't break


# ===========================================================================
# Layer 1: Timestamp-based rejection
# ===========================================================================

@pytest.mark.integration
class TestStaleWriteRejection:
    def test_stale_timestamp_rejected(self, client, coach_registered_client):
        """A log with an older _lastModifiedAt than the server's should be rejected."""
        today = datetime.now().strftime("%Y-%m-%d")

        # First upload — server stamps its own now as last_modified
        result1 = _upload(client, coach_registered_client, today, _make_log())
        assert today in result1["appliedLogs"]

        # Second upload with a past timestamp — older than server's last_modified
        result2 = _upload(client, coach_registered_client, today,
                          _make_log(exercises=False, timestamp=_past_ts()))
        assert today in result2["rejectedLogs"]
        assert today not in result2["appliedLogs"]

    def test_newer_timestamp_accepted(self, client, coach_registered_client):
        """A log with a newer _lastModifiedAt should overwrite the existing one."""
        today = datetime.now().strftime("%Y-%m-%d")

        # First upload
        _upload(client, coach_registered_client, today, _make_log())

        # Second upload with a future timestamp — newer than server's last_modified
        result = _upload(client, coach_registered_client, today,
                         _make_log(timestamp=_future_ts()))
        assert today in result["appliedLogs"]
        assert today not in result.get("rejectedLogs", [])

    def test_missing_timestamp_accepted(self, client, coach_registered_client):
        """A log without _lastModifiedAt should always be accepted (backward compat)."""
        today = datetime.now().strftime("%Y-%m-%d")

        # Upload with timestamp first
        _upload(client, coach_registered_client, today, _make_log())

        # Upload without timestamp — should be accepted (no comparison possible)
        result = _upload(client, coach_registered_client, today,
                         _make_log(timestamp=None))
        assert today in result["appliedLogs"]

    def test_first_upload_no_existing_accepted(self, client, coach_registered_client):
        """First upload for a date should always succeed regardless of timestamp."""
        today = datetime.now().strftime("%Y-%m-%d")
        result = _upload(client, coach_registered_client, today,
                         _make_log(timestamp=_past_ts()))
        assert today in result["appliedLogs"]

    def test_rejected_log_preserves_data(self, client, coach_registered_client):
        """After a stale write is rejected, the original data should be intact."""
        today = datetime.now().strftime("%Y-%m-%d")

        # Upload full workout
        _upload(client, coach_registered_client, today,
                _make_log(exercises=True))

        # Try to overwrite with empty log (stale timestamp)
        _upload(client, coach_registered_client, today,
                _make_log(exercises=False, timestamp=_past_ts()))

        # Verify original data is intact
        data = _download(client, coach_registered_client)
        assert today in data["logs"]
        assert "ex_1" in data["logs"][today]
        assert data["logs"][today]["ex_1"]["completed"] is True

    def test_mixed_batch_partial_rejection(self, client, coach_registered_client):
        """In a batch upload, some logs can be accepted while others rejected."""
        today = datetime.now().strftime("%Y-%m-%d")
        yesterday = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")

        # Upload today first — server stamps now
        _upload(client, coach_registered_client, today, _make_log())

        # Batch: stale today + fresh yesterday
        result = client.post(
            "/api/coach/sync",
            json={
                "clientId": coach_registered_client,
                "logs": {
                    today: _make_log(exercises=False, timestamp=_past_ts()),
                    yesterday: _make_log(timestamp=_future_ts()),
                },
            },
        ).json()

        assert today in result["rejectedLogs"]
        assert yesterday in result["appliedLogs"]


# ===========================================================================
# Layer 2: Soft-delete archive
# ===========================================================================

@pytest.mark.integration
class TestSoftDeleteArchive:
    def test_overwrite_creates_archive(self, client, coach_registered_client, tmp_coach_db):
        """Overwriting a log should archive the old data before deletion."""
        today = datetime.now().strftime("%Y-%m-%d")

        # Upload original
        _upload(client, coach_registered_client, today,
                _make_log(exercises=True))

        # Upload replacement (future timestamp so it's accepted)
        _upload(client, coach_registered_client, today,
                _make_log(exercises=True, timestamp=_future_ts()))

        # Check archive tables directly
        conn = sqlite3.connect(tmp_coach_db)
        conn.row_factory = sqlite3.Row
        archived = conn.execute(
            "SELECT * FROM workout_session_logs_archive WHERE date = ?", (today,)
        ).fetchall()
        assert len(archived) == 1
        assert archived[0]["superseded_by"] == coach_registered_client

        # Check exercise archive
        ex_archived = conn.execute(
            "SELECT * FROM exercise_logs_archive WHERE session_log_id = ?",
            (archived[0]["original_id"],)
        ).fetchall()
        assert len(ex_archived) == 2  # ex_1 + warmup_0

        # Check set archive
        ex1_archive = [e for e in ex_archived if e["exercise_key"] == "ex_1"][0]
        set_archived = conn.execute(
            "SELECT * FROM set_logs_archive WHERE exercise_log_id = ?",
            (ex1_archive["original_id"],)
        ).fetchall()
        assert len(set_archived) == 2  # 2 sets

        conn.close()

    def test_first_upload_no_archive(self, client, coach_registered_client, tmp_coach_db):
        """First upload for a date should not create archive entries."""
        today = datetime.now().strftime("%Y-%m-%d")
        _upload(client, coach_registered_client, today, _make_log())

        conn = sqlite3.connect(tmp_coach_db)
        count = conn.execute(
            "SELECT COUNT(*) FROM workout_session_logs_archive WHERE date = ?",
            (today,)
        ).fetchone()[0]
        assert count == 0
        conn.close()

    def test_rejected_write_no_archive(self, client, coach_registered_client, tmp_coach_db):
        """A rejected stale write should NOT create an archive entry."""
        today = datetime.now().strftime("%Y-%m-%d")

        _upload(client, coach_registered_client, today, _make_log())

        # Stale write — rejected, should not archive
        _upload(client, coach_registered_client, today,
                _make_log(exercises=False, timestamp=_past_ts()))

        conn = sqlite3.connect(tmp_coach_db)
        count = conn.execute(
            "SELECT COUNT(*) FROM workout_session_logs_archive WHERE date = ?",
            (today,)
        ).fetchone()[0]
        assert count == 0
        conn.close()

    def test_archive_cleanup(self, client, coach_registered_client, tmp_coach_db):
        """Archive rows older than 14 days should be purged during sync."""
        today = datetime.now().strftime("%Y-%m-%d")

        # Manually insert an old archive entry
        conn = sqlite3.connect(tmp_coach_db)
        old_ts = (datetime.now() - timedelta(days=15)).isoformat()
        conn.execute("""
            INSERT INTO workout_session_logs_archive
            (original_id, session_id, date, pain_discomfort, general_notes,
             last_modified, modified_by, superseded_at, superseded_by)
            VALUES (999, NULL, '2026-03-01', NULL, NULL, ?, 'old-client', ?, 'old-client')
        """, (old_ts, old_ts))
        conn.execute("""
            INSERT INTO exercise_logs_archive
            (original_id, session_log_id, exercise_key, completed)
            VALUES (888, 999, 'ex_old', 1)
        """)
        conn.execute("""
            INSERT INTO set_logs_archive
            (original_id, exercise_log_id, set_num, weight, reps, completed)
            VALUES (777, 888, 1, 50, 10, 1)
        """)
        conn.commit()
        conn.close()

        # Trigger a sync (which runs _purge_old_archives)
        _upload(client, coach_registered_client, today, _make_log())

        # Old archives should be gone
        conn = sqlite3.connect(tmp_coach_db)
        assert conn.execute(
            "SELECT COUNT(*) FROM workout_session_logs_archive WHERE original_id = 999"
        ).fetchone()[0] == 0
        assert conn.execute(
            "SELECT COUNT(*) FROM exercise_logs_archive WHERE original_id = 888"
        ).fetchone()[0] == 0
        assert conn.execute(
            "SELECT COUNT(*) FROM set_logs_archive WHERE original_id = 777"
        ).fetchone()[0] == 0
        conn.close()

    def test_recent_archives_not_purged(self, client, coach_registered_client, tmp_coach_db):
        """Archive rows within the retention window should NOT be purged."""
        today = datetime.now().strftime("%Y-%m-%d")

        # Upload + overwrite to create a recent archive
        _upload(client, coach_registered_client, today, _make_log())
        _upload(client, coach_registered_client, today,
                _make_log(timestamp=_future_ts()))

        # Trigger another sync to run cleanup
        yesterday = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")
        _upload(client, coach_registered_client, yesterday, _make_log())

        # Recent archive should still exist
        conn = sqlite3.connect(tmp_coach_db)
        count = conn.execute(
            "SELECT COUNT(*) FROM workout_session_logs_archive WHERE date = ?",
            (today,)
        ).fetchone()[0]
        assert count == 1
        conn.close()
