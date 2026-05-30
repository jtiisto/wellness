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


def _server_token(client, client_id, date):
    """The server's current `_lastModified` stamp for `date` (the R1 base token)."""
    return _download(client, client_id)["logs"].get(date, {}).get("_lastModified")


def _upload_with_token(client, client_id, date, log):
    """Upload echoing the server's current stamp as `_baseLastModifiedAt` (R1) —
    for a 2nd+ write to an existing date so it passes token arbitration."""
    base = _server_token(client, client_id, date)
    if base:
        log["_baseLastModifiedAt"] = base
    return _upload(client, client_id, date, log)


def _reject_dates(result):
    """Dates in the structured rejectedLogs (`[{date, serverRow}]`) (R1)."""
    return [r["date"] for r in result.get("rejectedLogs", [])]


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
    def test_stale_base_token_rejected(self, client, coach_registered_client):
        """A log echoing a base token OLDER than the server's stored stamp (the
        client missed a newer server write) is rejected (R1)."""
        today = datetime.now().strftime("%Y-%m-%d")

        # First upload — server stamps its own now as last_modified
        result1 = _upload(client, coach_registered_client, today, _make_log())
        assert today in result1["appliedLogs"]

        # Second upload echoing a stale base — older than the stored stamp
        stale = _make_log(exercises=False)
        stale["_baseLastModifiedAt"] = _past_ts()
        result2 = _upload(client, coach_registered_client, today, stale)
        assert today in _reject_dates(result2)
        assert today not in result2["appliedLogs"]

    def test_current_base_token_accepted(self, client, coach_registered_client):
        """A log echoing the server's current stamp as its base token is accepted (R1)."""
        today = datetime.now().strftime("%Y-%m-%d")

        # First upload
        _upload(client, coach_registered_client, today, _make_log())

        # Second upload echoing the current server stamp — accepted
        result = _upload_with_token(client, coach_registered_client, today, _make_log())
        assert today in result["appliedLogs"]
        assert today not in _reject_dates(result)

    def test_missing_token_rejected_for_existing_date(self, client, coach_registered_client):
        """Hard cutover (R1): a token-absent upload to an EXISTING date is rejected
        (no compat path); the server returns its current row for in-cycle recovery."""
        today = datetime.now().strftime("%Y-%m-%d")

        _upload(client, coach_registered_client, today, _make_log())

        # Upload without a base token — rejected against the existing row
        result = _upload(client, coach_registered_client, today, _make_log(timestamp=None))
        assert today in _reject_dates(result)
        assert today not in result["appliedLogs"]
        rej = next(r for r in result["rejectedLogs"] if r["date"] == today)
        assert rej["serverRow"] is not None and "ex_1" in rej["serverRow"]

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

        # Try to overwrite with empty log echoing a stale base — rejected
        stale = _make_log(exercises=False)
        stale["_baseLastModifiedAt"] = _past_ts()
        _upload(client, coach_registered_client, today, stale)

        # Verify original data is intact
        data = _download(client, coach_registered_client)
        assert today in data["logs"]
        assert "ex_1" in data["logs"][today]
        assert len(data["logs"][today]["ex_1"]["sets"]) == 2  # exercise data preserved

    def test_mixed_batch_partial_rejection(self, client, coach_registered_client):
        """In a batch upload, some logs can be accepted while others rejected."""
        today = datetime.now().strftime("%Y-%m-%d")
        yesterday = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")

        # Upload today first — server stamps now
        _upload(client, coach_registered_client, today, _make_log())

        # Batch: stale-base today (rejected) + fresh new-date yesterday (accepted)
        today_stale = _make_log(exercises=False)
        today_stale["_baseLastModifiedAt"] = _past_ts()
        result = client.post(
            "/api/coach/sync",
            json={
                "clientId": coach_registered_client,
                "logs": {
                    today: today_stale,
                    yesterday: _make_log(),
                },
            },
        ).json()

        assert today in _reject_dates(result)
        assert yesterday in result["appliedLogs"]


# ===========================================================================
# Layer 1b: Content guard (reject incomplete payloads)
# ===========================================================================

@pytest.mark.integration
class TestContentGuard:
    def test_newer_partial_payload_preserves_exercises(self, client, coach_registered_client):
        """Upload full log, then newer feedback-only log — exercises should survive."""
        today = datetime.now().strftime("%Y-%m-%d")

        # Upload full workout with exercises
        _upload(client, coach_registered_client, today, _make_log(exercises=True))

        # Feedback-only upload with a VALID base token — passes token arbitration,
        # so it reaches (and is caught by) the content guard.
        result = _upload_with_token(client, coach_registered_client, today,
                                    _make_log(exercises=False))

        assert today in result["contentRejectedLogs"]
        assert today not in result["appliedLogs"]
        assert today not in _reject_dates(result)

        # Verify exercise data survived
        data = _download(client, coach_registered_client)
        assert "ex_1" in data["logs"][today]
        assert len(data["logs"][today]["ex_1"]["sets"]) == 2  # exercise data preserved

    def test_newer_complete_payload_replaces(self, client, coach_registered_client):
        """Upload full log, then newer log with different exercises — should replace."""
        today = datetime.now().strftime("%Y-%m-%d")

        # Upload initial workout
        _upload(client, coach_registered_client, today, _make_log(exercises=True))

        # Upload newer log with exercises echoing the current token — accepted
        new_log = _make_log(exercises=True)
        new_log["ex_1"]["user_note"] = "Updated note"
        result = _upload_with_token(client, coach_registered_client, today, new_log)

        assert today in result["appliedLogs"]
        assert today not in result.get("contentRejectedLogs", [])

        # Verify the updated data is present
        data = _download(client, coach_registered_client)
        assert data["logs"][today]["ex_1"]["user_note"] == "Updated note"

    def test_first_upload_feedback_only_accepted(self, client, coach_registered_client):
        """First upload with only feedback (no existing data) should be accepted."""
        today = datetime.now().strftime("%Y-%m-%d")

        result = _upload(client, coach_registered_client, today,
                         _make_log(exercises=False))

        assert today in result["appliedLogs"]
        assert today not in result.get("contentRejectedLogs", [])


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

        # Upload replacement echoing the current token so it's accepted
        _upload_with_token(client, coach_registered_client, today,
                           _make_log(exercises=True))

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
        old_ts = (datetime.now(timezone.utc) - timedelta(days=15)).isoformat()
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

        # Upload + overwrite (echoing the current token) to create a recent archive
        _upload(client, coach_registered_client, today, _make_log())
        _upload_with_token(client, coach_registered_client, today, _make_log())

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


# ===========================================================================
# Batch atomicity
# ===========================================================================

@pytest.mark.integration
class TestBatchUploadAtomicity:
    def test_batch_rolls_back_on_error(self, client, coach_registered_client, tmp_coach_db):
        """If _store_log raises mid-batch, no dates from that batch should persist."""
        from unittest.mock import patch

        today = datetime.now().strftime("%Y-%m-%d")
        yesterday = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")

        call_count = 0
        original_store_log = None

        def _exploding_store_log(conn, date_str, log_data, client_id, now):
            nonlocal call_count
            call_count += 1
            if call_count == 2:
                raise RuntimeError("simulated DB failure on second date")
            return original_store_log(conn, date_str, log_data, client_id, now)

        # Grab a reference to the real function before patching
        import modules.coach as coach_mod
        original_store_log = coach_mod._store_log

        with patch("modules.coach._store_log", side_effect=_exploding_store_log):
            with pytest.raises(RuntimeError, match="simulated DB failure"):
                client.post(
                    "/api/coach/sync",
                    json={
                        "clientId": coach_registered_client,
                        "logs": {
                            yesterday: _make_log(),
                            today: _make_log(),
                        },
                    },
                )

        # Neither date should have been persisted
        conn = sqlite3.connect(tmp_coach_db)
        conn.row_factory = sqlite3.Row
        count = conn.execute("SELECT COUNT(*) FROM workout_session_logs").fetchone()[0]
        assert count == 0, f"Expected 0 logs after rollback, found {count}"
        conn.close()


# ===========================================================================
# R1: server-token arbitration (replaces client-clock LWW) — see
# plans/phase4-r1-coach-clock-skew.md. The headline target, written against the
# token protocol; xfail until R1-1 lands the server-side arbitration, then the
# marker is removed (strict=True fails on XPASS so it can't be forgotten).
# ===========================================================================

@pytest.mark.integration
def test_client_behind_clock_still_wins_with_token(client, coach_registered_client):
    """R1 target invariant: a device whose wall clock is an hour BEHIND still has
    its newer edit accepted, because the server compares its own stored stamp
    against the server-issued base token the client echoed — never the client
    clock. Fails today (client-time arbiter rejects the 'stale' edit)."""
    today = datetime.now().strftime("%Y-%m-%d")

    # 1. Initial upload; the server stamps its own last_modified.
    _upload(client, coach_registered_client, today, _make_log())

    # 2. Client downloads and learns the server's token for this date.
    server_stamp = _download(client, coach_registered_client)["logs"][today]["_lastModified"]

    # 3. A genuine new edit that echoes the server token as its base — but the
    #    device clock is an hour behind, so its client _lastModifiedAt looks "old".
    edit = _make_log(exercises=True, timestamp=_past_ts())
    edit["_baseLastModifiedAt"] = server_stamp

    # 4. Target: accepted (stored == base => stored <= base), despite the behind clock.
    result = _upload(client, coach_registered_client, today, edit)
    assert today in result["appliedLogs"]
    assert today not in result.get("rejectedLogs", [])
