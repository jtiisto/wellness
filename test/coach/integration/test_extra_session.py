"""Integration tests for ad-hoc (off-plan) extra sessions and per-exercise
log deletion propagation.

Off-plan semantics are IMPLICIT: a log on a date with no plan persists with
workout_session_logs.session_id IS NULL and exercise_logs.exercise_id IS NULL;
the well-known key `extra_zone2` additionally gets canonical_slug 'zone_2'
(AD_HOC_LOG_SLUGS) so ad-hoc Zone 2 shows in exercise history.

Deletion: a `{"_deleted": true, "_baseLastModifiedAt": ...}` entry hard-deletes
the exercise_logs row (arbitrated with should_accept_log_write) and records a
tombstone in deleted_exercise_logs so incremental sync re-delivers the day and
stale edits cannot resurrect the row.
"""

import pytest
import sqlite3
from datetime import datetime

EXTRA_KEY = "extra_zone2"
EXTRA_ENTRY = {"duration_min": 45, "avg_hr": 128, "max_hr": 142}


def _today():
    # Local time by convention: date keys are the browser's local calendar day.
    return datetime.now().strftime("%Y-%m-%d")


def _post_log(client, client_id, date, day):
    resp = client.post(
        "/api/coach/sync", json={"clientId": client_id, "logs": {date: day}}
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["success"] is True
    return data


def _upload_extra(client, client_id, date, entry=None):
    """Upload a fresh ad-hoc entry (no base tokens) and return (result day, stamp)."""
    day = {"session_feedback": {}, EXTRA_KEY: dict(entry or EXTRA_ENTRY)}
    data = _post_log(client, client_id, date, day)
    server_day = data["results"][date]
    return server_day, server_day[EXTRA_KEY]["_lastModified"]


def _db(tmp_coach_db):
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    return conn


def _exercise_row(conn, date, key=EXTRA_KEY):
    return conn.execute(
        """SELECT el.* FROM exercise_logs el
           JOIN workout_session_logs sl ON sl.id = el.session_log_id
           WHERE sl.date = ? AND el.exercise_key = ?""",
        (date, key),
    ).fetchone()


def _tombstone(conn, date, key=EXTRA_KEY):
    return conn.execute(
        "SELECT * FROM deleted_exercise_logs WHERE date = ? AND exercise_key = ?",
        (date, key),
    ).fetchone()


@pytest.mark.integration
class TestExtraSessionUpload:
    def test_adhoc_entry_persists_off_plan(self, client, coach_registered_client, tmp_coach_db):
        """An entry on a plan-less date lands with NULL session/exercise links
        and the well-known ad-hoc canonical slug."""
        today = _today()
        _upload_extra(client, coach_registered_client, today)

        conn = _db(tmp_coach_db)
        day_row = conn.execute(
            "SELECT * FROM workout_session_logs WHERE date = ?", (today,)
        ).fetchone()
        ex_row = _exercise_row(conn, today)
        registry = conn.execute(
            "SELECT * FROM exercises WHERE slug = 'zone_2'"
        ).fetchone()
        conn.close()

        assert day_row is not None and day_row["session_id"] is None
        assert ex_row is not None
        assert ex_row["exercise_id"] is None
        assert ex_row["canonical_slug"] == "zone_2"
        assert ex_row["duration_min"] == 45
        assert registry is not None  # self-healed on a fresh DB

    def test_adhoc_slug_survives_edit(self, client, coach_registered_client, tmp_coach_db):
        """The UPDATE branch must re-resolve the ad-hoc slug — an accepted edit
        would otherwise null it back out."""
        today = _today()
        _, stamp = _upload_extra(client, coach_registered_client, today)

        edited = {**EXTRA_ENTRY, "duration_min": 50, "_baseLastModifiedAt": stamp}
        _post_log(client, coach_registered_client, today,
                  {"session_feedback": {}, EXTRA_KEY: edited})

        conn = _db(tmp_coach_db)
        ex_row = _exercise_row(conn, today)
        conn.close()
        assert ex_row["duration_min"] == 50
        assert ex_row["canonical_slug"] == "zone_2"

    def test_adhoc_roundtrip_via_get(self, client, coach_registered_client):
        today = _today()
        _upload_extra(client, coach_registered_client, today)

        resp = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        data = resp.json()
        assert today not in data["plans"]
        entry = data["logs"][today][EXTRA_KEY]
        assert entry["duration_min"] == 45
        assert entry["avg_hr"] == 128
        assert entry["max_hr"] == 142
        assert "_lastModified" in entry

    def test_unknown_adhoc_key_gets_no_slug(self, client, coach_registered_client, tmp_coach_db):
        """Only well-known ad-hoc keys resolve a canonical slug."""
        today = _today()
        _post_log(client, coach_registered_client, today,
                  {"session_feedback": {}, "mystery_key": {"duration_min": 10}})

        conn = _db(tmp_coach_db)
        ex_row = _exercise_row(conn, today, "mystery_key")
        conn.close()
        assert ex_row is not None
        assert ex_row["canonical_slug"] is None


@pytest.mark.integration
class TestLogEntryDeletion:
    def test_accepted_delete_removes_row_and_writes_tombstone(
        self, client, coach_registered_client, tmp_coach_db
    ):
        today = _today()
        server_day, stamp = _upload_extra(client, coach_registered_client, today)

        data = _post_log(client, coach_registered_client, today, {
            "session_feedback": {},
            "_baseLastModifiedAt": server_day["_lastModified"],
            EXTRA_KEY: {"_deleted": True, "_baseLastModifiedAt": stamp},
        })

        # The reconciled day no longer carries the key — adopting it clears
        # the client's tombstone.
        assert EXTRA_KEY not in data["results"][today]

        conn = _db(tmp_coach_db)
        assert _exercise_row(conn, today) is None
        assert _tombstone(conn, today) is not None
        conn.close()

    def test_delete_rejected_after_concurrent_edit(
        self, client, coach_registered_client, tmp_coach_db
    ):
        """Another client's accepted edit advances the stamp; a delete echoing
        the older base loses (server-wins) and the row survives."""
        today = _today()
        _, stamp_t1 = _upload_extra(client, coach_registered_client, today)

        # Client B edits on top of t1 → stamp advances to t2.
        _post_log(client, coach_registered_client, today, {
            "session_feedback": {},
            EXTRA_KEY: {**EXTRA_ENTRY, "duration_min": 60, "_baseLastModifiedAt": stamp_t1},
        })

        # Client A deletes with the stale base t1 → rejected.
        data = _post_log(client, coach_registered_client, today, {
            "session_feedback": {},
            EXTRA_KEY: {"_deleted": True, "_baseLastModifiedAt": stamp_t1},
        })

        assert data["results"][today][EXTRA_KEY]["duration_min"] == 60
        conn = _db(tmp_coach_db)
        assert _exercise_row(conn, today)["duration_min"] == 60
        assert _tombstone(conn, today) is None
        conn.close()

    def test_stale_edit_cannot_resurrect_deleted_row(
        self, client, coach_registered_client, tmp_coach_db
    ):
        """An edit that echoes a base token for a deleted record is editing the
        deleted row → delete wins, no re-insert."""
        today = _today()
        server_day, stamp = _upload_extra(client, coach_registered_client, today)
        _post_log(client, coach_registered_client, today, {
            "session_feedback": {},
            "_baseLastModifiedAt": server_day["_lastModified"],
            EXTRA_KEY: {"_deleted": True, "_baseLastModifiedAt": stamp},
        })

        data = _post_log(client, coach_registered_client, today, {
            "session_feedback": {},
            EXTRA_KEY: {**EXTRA_ENTRY, "duration_min": 99, "_baseLastModifiedAt": stamp},
        })

        assert EXTRA_KEY not in data["results"][today]
        conn = _db(tmp_coach_db)
        assert _exercise_row(conn, today) is None
        assert _tombstone(conn, today) is not None
        conn.close()

    def test_deliberate_readd_clears_tombstone(
        self, client, coach_registered_client, tmp_coach_db
    ):
        """A fresh entry with NO base token is a deliberate re-add: accepted,
        tombstone cleared."""
        today = _today()
        server_day, stamp = _upload_extra(client, coach_registered_client, today)
        _post_log(client, coach_registered_client, today, {
            "session_feedback": {},
            "_baseLastModifiedAt": server_day["_lastModified"],
            EXTRA_KEY: {"_deleted": True, "_baseLastModifiedAt": stamp},
        })

        data = _post_log(client, coach_registered_client, today, {
            "session_feedback": {},
            EXTRA_KEY: {"duration_min": 30, "avg_hr": 130},
        })

        assert data["results"][today][EXTRA_KEY]["duration_min"] == 30
        conn = _db(tmp_coach_db)
        assert _exercise_row(conn, today)["duration_min"] == 30
        assert _tombstone(conn, today) is None
        conn.close()

    def test_delete_retry_is_idempotent(self, client, coach_registered_client, tmp_coach_db):
        """Retrying a delete after a lost response (row already gone) succeeds
        and keeps the tombstone."""
        today = _today()
        server_day, stamp = _upload_extra(client, coach_registered_client, today)
        tombstone_upload = {
            "session_feedback": {},
            "_baseLastModifiedAt": server_day["_lastModified"],
            EXTRA_KEY: {"_deleted": True, "_baseLastModifiedAt": stamp},
        }
        _post_log(client, coach_registered_client, today, tombstone_upload)
        data = _post_log(client, coach_registered_client, today, tombstone_upload)

        assert EXTRA_KEY not in data["results"][today]
        conn = _db(tmp_coach_db)
        assert _exercise_row(conn, today) is None
        assert _tombstone(conn, today) is not None
        conn.close()

    def test_delete_last_exercise_keeps_day_row(
        self, client, coach_registered_client, tmp_coach_db
    ):
        """The emptied workout_session_logs row stays (matches the existing
        emptied-synced-day behavior); no day-level tombstone protocol needed."""
        today = _today()
        server_day, stamp = _upload_extra(client, coach_registered_client, today)
        _post_log(client, coach_registered_client, today, {
            "session_feedback": {},
            "_baseLastModifiedAt": server_day["_lastModified"],
            EXTRA_KEY: {"_deleted": True, "_baseLastModifiedAt": stamp},
        })

        conn = _db(tmp_coach_db)
        day_row = conn.execute(
            "SELECT * FROM workout_session_logs WHERE date = ?", (today,)
        ).fetchone()
        conn.close()
        assert day_row is not None

    def test_delete_planned_exercise_log(self, client, coach_seeded_database, tmp_coach_db):
        """The deletion protocol generalizes to planned-exercise log entries."""
        client_id = coach_seeded_database["client_id"]
        today = coach_seeded_database["dates"][0]

        resp = client.get(f"/api/coach/sync?client_id={client_id}")
        day = resp.json()["logs"][today]
        stamp = day["cardio_1"]["_lastModified"]

        data = _post_log(client, client_id, today, {
            "session_feedback": day.get("session_feedback", {}),
            "_baseLastModifiedAt": day["_lastModified"],
            "cardio_1": {"_deleted": True, "_baseLastModifiedAt": stamp},
        })

        assert "cardio_1" not in data["results"][today]
        assert "ex_1" in data["results"][today]  # siblings untouched
        conn = _db(tmp_coach_db)
        assert _exercise_row(conn, today, "cardio_1") is None
        assert _exercise_row(conn, today, "ex_1") is not None
        assert _tombstone(conn, today, "cardio_1") is not None
        conn.close()


@pytest.mark.integration
class TestDeletionPropagation:
    def test_incremental_get_redelivers_day_after_delete(
        self, client, coach_registered_client
    ):
        """A second client's incremental pull picks the day up via the
        tombstone arm even though it holds a pre-delete watermark."""
        today = _today()
        server_day, stamp = _upload_extra(client, coach_registered_client, today)

        # Client B syncs now — its watermark predates the delete.
        resp_b = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        watermark = resp_b.json()["serverTime"]
        assert EXTRA_KEY in resp_b.json()["logs"][today]

        # Client A deletes. Deliberately echo a STALE day base so the feedback
        # record is rejected and the day stamp does NOT move — propagation must
        # come from the tombstone arm alone.
        _post_log(client, coach_registered_client, today, {
            "session_feedback": {},
            EXTRA_KEY: {"_deleted": True, "_baseLastModifiedAt": stamp},
        })

        resp_b2 = client.get(
            f"/api/coach/sync?client_id={coach_registered_client}&last_sync_time={watermark}"
        )
        logs = resp_b2.json()["logs"]
        assert today in logs  # re-delivered
        assert EXTRA_KEY not in logs[today]  # without the deleted key

    def test_plans_version_moves_on_delete(self, client, coach_registered_client):
        today = _today()
        server_day, stamp = _upload_extra(client, coach_registered_client, today)
        before = client.get("/api/coach/plans-version").json()["version"]

        _post_log(client, coach_registered_client, today, {
            "session_feedback": {},
            EXTRA_KEY: {"_deleted": True, "_baseLastModifiedAt": stamp},
        })

        after = client.get("/api/coach/plans-version").json()["version"]
        assert after > before
