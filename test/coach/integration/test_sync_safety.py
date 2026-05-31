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


def _results(resp):
    """The R3 per-record upload response: {date: merged serverRow}."""
    return resp["results"]


def _server_day(client, client_id, date):
    """The server's current merged log for `date` (carries the day + per-exercise
    `_lastModified` tokens)."""
    return _download(client, client_id)["logs"].get(date, {})


def _server_token(client, client_id, date):
    """The server's current day-level `_lastModified` stamp for `date`."""
    return _server_day(client, client_id, date).get("_lastModified")


def _upload_with_token(client, client_id, date, log):
    """Upload echoing the server's current day **and per-exercise** stamps as base
    tokens (R3) — i.e. an up-to-date client. A 2nd+ write to an existing record
    passes arbitration; a new exercise (no server token) inserts."""
    server = _server_day(client, client_id, date)
    if server.get("_lastModified"):
        log["_baseLastModifiedAt"] = server["_lastModified"]
    for key, val in log.items():
        srv_ex = server.get(key)
        if isinstance(val, dict) and isinstance(srv_ex, dict) and srv_ex.get("_lastModified"):
            val["_baseLastModifiedAt"] = srv_ex["_lastModified"]
    return _upload(client, client_id, date, log)


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
    def test_stale_exercise_base_keeps_server(self, client, coach_registered_client):
        """An exercise edit echoing a base OLDER than the server's stamp is
        rejected per-exercise — the server keeps its version (R3)."""
        today = datetime.now().strftime("%Y-%m-%d")
        _upload(client, coach_registered_client, today, _make_log())

        stale = _make_log(exercises=True)
        stale["ex_1"]["user_note"] = "should not win"
        stale["ex_1"]["_baseLastModifiedAt"] = _past_ts()
        stale["_baseLastModifiedAt"] = _server_token(client, coach_registered_client, today)
        result = _upload(client, coach_registered_client, today, stale)

        # ex_1 kept the server's version (the stale edit lost).
        assert _results(result)[today]["ex_1"]["user_note"] == "Felt strong"

    def test_current_exercise_base_accepted(self, client, coach_registered_client):
        """An exercise edit echoing the server's current per-exercise stamp wins (R3)."""
        today = datetime.now().strftime("%Y-%m-%d")
        _upload(client, coach_registered_client, today, _make_log())

        edit = _make_log(exercises=True)
        edit["ex_1"]["user_note"] = "updated"
        result = _upload_with_token(client, coach_registered_client, today, edit)

        assert _results(result)[today]["ex_1"]["user_note"] == "updated"

    def test_missing_exercise_token_keeps_server(self, client, coach_registered_client):
        """Editing an existing exercise without echoing its token is rejected for
        that exercise — the server keeps its version (R3 hard cutover)."""
        today = datetime.now().strftime("%Y-%m-%d")
        _upload(client, coach_registered_client, today, _make_log())

        edit = _make_log(exercises=True)
        edit["ex_1"]["user_note"] = "no token"
        edit["_baseLastModifiedAt"] = _server_token(client, coach_registered_client, today)
        # ex_1 carries no _baseLastModifiedAt → kept server.
        result = _upload(client, coach_registered_client, today, edit)

        assert _results(result)[today]["ex_1"]["user_note"] == "Felt strong"

    def test_first_upload_new_date_inserts(self, client, coach_registered_client):
        """First upload for a date inserts everything regardless of tokens (R3)."""
        today = datetime.now().strftime("%Y-%m-%d")
        result = _upload(client, coach_registered_client, today, _make_log())
        assert today in _results(result)
        assert "ex_1" in _results(result)[today]

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

    def test_batch_per_exercise_arbitration(self, client, coach_registered_client):
        """Each exercise is arbitrated independently within one upload: a stale
        edit is kept-server while a brand-new exercise on the same date inserts (R3)."""
        today = datetime.now().strftime("%Y-%m-%d")
        _upload(client, coach_registered_client, today, _make_log())  # ex_1, warmup_0

        upd = _make_log(exercises=True)
        upd["ex_1"]["user_note"] = "stale loser"
        upd["ex_1"]["_baseLastModifiedAt"] = _past_ts()           # stale → kept server
        upd["ex_new"] = {"sets": [{"set_num": 1, "weight": 50, "reps": 5}]}  # new → insert
        upd["_baseLastModifiedAt"] = _server_token(client, coach_registered_client, today)
        result = _upload(client, coach_registered_client, today, upd)

        day = _results(result)[today]
        assert day["ex_1"]["user_note"] == "Felt strong"   # stale edit rejected
        assert "ex_new" in day                              # new exercise inserted
        assert len(day["ex_new"]["sets"]) == 1


# ===========================================================================
# Feedback-only uploads (content guard removed in R3 — upsert preserves exercises)
# ===========================================================================

@pytest.mark.integration
class TestFeedbackOnlyUpsert:
    def test_feedback_only_accepted_preserves_exercises(self, client, coach_registered_client):
        """R3: a feedback-only upload is accepted (content guard removed) and the
        un-mentioned exercises are preserved structurally by per-exercise upsert."""
        today = datetime.now().strftime("%Y-%m-%d")
        _upload(client, coach_registered_client, today, _make_log(exercises=True))

        # Feedback-only upload echoing the current day token — accepted; ex_1 (not
        # in the payload) is untouched, not wiped.
        fb = _make_log(exercises=False)
        fb["_baseLastModifiedAt"] = _server_token(client, coach_registered_client, today)
        result = _upload(client, coach_registered_client, today, fb)

        day = _results(result)[today]
        assert day["session_feedback"]["general_notes"] == "Good session"  # feedback applied
        assert "ex_1" in day                                               # exercise preserved
        assert len(day["ex_1"]["sets"]) == 2

    def test_complete_payload_replaces_exercise(self, client, coach_registered_client):
        """A full edit echoing current tokens updates the exercise in place (R3)."""
        today = datetime.now().strftime("%Y-%m-%d")
        _upload(client, coach_registered_client, today, _make_log(exercises=True))

        new_log = _make_log(exercises=True)
        new_log["ex_1"]["user_note"] = "Updated note"
        result = _upload_with_token(client, coach_registered_client, today, new_log)

        assert _results(result)[today]["ex_1"]["user_note"] == "Updated note"

    def test_first_upload_feedback_only_accepted(self, client, coach_registered_client):
        """First upload with only feedback (no existing data) is accepted (R3)."""
        today = datetime.now().strftime("%Y-%m-%d")
        result = _upload(client, coach_registered_client, today, _make_log(exercises=False))
        assert today in _results(result)
        assert _results(result)[today]["session_feedback"]["general_notes"] == "Good session"


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

    def test_upload_archives_existing_day_defensively(self, client, coach_registered_client, tmp_coach_db):
        """R3 archives the existing day before per-record upsert (defensive net, so
        any overwrite is recoverable), while a stale per-exercise edit still leaves
        the server's exercise intact."""
        today = datetime.now().strftime("%Y-%m-%d")
        _upload(client, coach_registered_client, today, _make_log())

        stale = _make_log(exercises=True)
        stale["ex_1"]["user_note"] = "stale"
        stale["ex_1"]["_baseLastModifiedAt"] = _past_ts()
        stale["_baseLastModifiedAt"] = _server_token(client, coach_registered_client, today)
        result = _upload(client, coach_registered_client, today, stale)

        # ex_1 kept the server's version (stale per-exercise edit rejected)...
        assert _results(result)[today]["ex_1"]["user_note"] == "Felt strong"
        # ...and the day was archived defensively before the upsert.
        conn = sqlite3.connect(tmp_coach_db)
        count = conn.execute(
            "SELECT COUNT(*) FROM workout_session_logs_archive WHERE date = ?", (today,)
        ).fetchone()[0]
        conn.close()
        assert count >= 1

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
# R3 per-record merge — the blast-radius wins (multi-device, offline insert, set edit)
# ===========================================================================

@pytest.mark.integration
class TestPerRecordMerge:
    def test_multi_device_merge_preserves_unmentioned(self, client, coach_registered_client):
        """Headline (failed under the old delete-rebuild): device B uploading
        {ex_1, ex_3} over server {ex_1, ex_2} merges to {ex_1, ex_2, ex_3} —
        ex_2 (un-mentioned by B) is preserved, not dropped."""
        today = datetime.now().strftime("%Y-%m-%d")
        # Device A establishes {ex_1, ex_2}.
        a = {"session_feedback": {}, "ex_1": {"sets": [{"set_num": 1, "weight": 100, "reps": 5}]},
             "ex_2": {"sets": [{"set_num": 1, "weight": 50, "reps": 8}]}}
        _upload(client, coach_registered_client, today, a)

        # Device B knows only ex_1; edits it and logs a new ex_3.
        server = _server_day(client, coach_registered_client, today)
        b = {"session_feedback": {},
             "ex_1": {"sets": [{"set_num": 1, "weight": 105, "reps": 5}],
                      "_baseLastModifiedAt": server["ex_1"]["_lastModified"]},
             "ex_3": {"sets": [{"set_num": 1, "weight": 20, "reps": 12}]},  # new → insert
             "_baseLastModifiedAt": server["_lastModified"]}
        day = _results(_upload(client, coach_registered_client, today, b))[today]

        assert "ex_1" in day and "ex_2" in day and "ex_3" in day  # ex_2 preserved!
        assert day["ex_1"]["sets"][0]["weight"] == 105            # ex_1 updated

    def test_offline_behind_insert_merges(self, client, coach_registered_client):
        """A behind client (stale day base) that inserts a new exercise AND edits
        an existing one: the insert applies (no base → no conflict), the stale edit
        is kept-server, and an un-mentioned exercise is untouched (R3)."""
        today = datetime.now().strftime("%Y-%m-%d")
        base = {"session_feedback": {},
                "ex_1": {"sets": [{"set_num": 1, "weight": 100, "reps": 5}], "user_note": "v1"},
                "ex_2": {"sets": [{"set_num": 1, "weight": 50, "reps": 8}]}}
        _upload(client, coach_registered_client, today, base)

        behind = {"session_feedback": {},
                  "ex_1": {"user_note": "stale edit", "_baseLastModifiedAt": _past_ts()},  # stale → kept
                  "ex_3": {"sets": [{"set_num": 1, "weight": 20, "reps": 12}]},  # new → insert
                  "_baseLastModifiedAt": _past_ts()}  # stale day base — must not block ex_3
        day = _results(_upload(client, coach_registered_client, today, behind))[today]

        assert day["ex_1"]["user_note"] == "v1"   # stale edit rejected (kept server)
        assert "ex_2" in day                       # untouched
        assert "ex_3" in day                       # inserted despite the stale day base

    def test_set_edit_replaces_only_that_exercises_sets(self, client, coach_registered_client):
        """Editing an exercise's sets (here, removing one) replaces that exercise's
        sets; a different exercise's sets are untouched (R3)."""
        today = datetime.now().strftime("%Y-%m-%d")
        log = {"session_feedback": {},
               "ex_1": {"sets": [{"set_num": i, "weight": 100, "reps": 5} for i in (1, 2, 3)]},
               "ex_2": {"sets": [{"set_num": 1, "weight": 50, "reps": 8}]}}
        _upload(client, coach_registered_client, today, log)

        server = _server_day(client, coach_registered_client, today)
        edit = {"session_feedback": {},
                "ex_1": {"sets": [{"set_num": i, "weight": 100, "reps": 6} for i in (1, 2)],
                         "_baseLastModifiedAt": server["ex_1"]["_lastModified"]},
                "_baseLastModifiedAt": server["_lastModified"]}
        day = _results(_upload(client, coach_registered_client, today, edit))[today]

        assert len(day["ex_1"]["sets"]) == 2   # ex_1's sets replaced (3 → 2)
        assert len(day["ex_2"]["sets"]) == 1   # ex_2 untouched


# ===========================================================================
# Server-token arbitration end-to-end (R1 invariants under R3 per-record) — see
# plans/phase4-r1-coach-clock-skew.md + phase4-r3-coach-upsert.md.
# ===========================================================================

@pytest.mark.integration
def test_reject_then_recover_with_returned_token(client, coach_registered_client):
    """In-cycle recovery: a stale per-exercise edit is rejected (server keeps its
    version); the merged serverRow carries that exercise's current token, and a
    re-upload echoing it is accepted — recovery without a separate pull."""
    today = datetime.now().strftime("%Y-%m-%d")
    _upload(client, coach_registered_client, today, _make_log())

    # Stale-base edit on ex_1 → rejected per-exercise; merged result carries the token.
    stale = _make_log(exercises=True)
    stale["ex_1"]["user_note"] = "stale"
    stale["ex_1"]["_baseLastModifiedAt"] = _past_ts()
    stale["_baseLastModifiedAt"] = _server_token(client, coach_registered_client, today)
    rejected = _upload(client, coach_registered_client, today, stale)
    recovered_token = _results(rejected)[today]["ex_1"]["_lastModified"]

    # Re-upload echoing the returned token → accepted (stored == base).
    retry = _make_log(exercises=True)
    retry["ex_1"]["user_note"] = "after recovery"
    retry["ex_1"]["_baseLastModifiedAt"] = recovered_token
    retry["_baseLastModifiedAt"] = _server_token(client, coach_registered_client, today)
    result = _upload(client, coach_registered_client, today, retry)
    assert _results(result)[today]["ex_1"]["user_note"] == "after recovery"


@pytest.mark.integration
def test_client_behind_clock_still_wins_with_token(client, coach_registered_client):
    """Invariant: a device whose wall clock is an hour BEHIND still has its edit
    accepted, because arbitration uses the server-issued per-exercise token, never
    the client clock (`_lastModifiedAt` is advisory only)."""
    today = datetime.now().strftime("%Y-%m-%d")
    _upload(client, coach_registered_client, today, _make_log())

    server = _server_day(client, coach_registered_client, today)
    edit = _make_log(exercises=True)
    edit["ex_1"]["user_note"] = "behind-clock edit"
    edit["ex_1"]["_lastModifiedAt"] = _past_ts()                  # behind clock (advisory)
    edit["ex_1"]["_baseLastModifiedAt"] = server["ex_1"]["_lastModified"]  # current token
    edit["_baseLastModifiedAt"] = server["_lastModified"]

    result = _upload(client, coach_registered_client, today, edit)
    assert _results(result)[today]["ex_1"]["user_note"] == "behind-clock edit"
