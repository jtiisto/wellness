"""Integration tests for GET /api/hr/status.

The counts probe is the client's reachability check, so the assertions here are
as much about the response *shape* (camelCase keys, per the wire contract) as
about the numbers.
"""
import sqlite3

import pytest

# Synthetic rows only — deviceId/sessionId/exerciseKey values are invented, and
# the epoch-ms stamps are round numbers, never copied from a real capture.
DEVICE_ID = "AA:BB:CC:DD:EE:FF"
SESSION_ID = "11111111-2222-3333-4444-555555555555"


def _seed(db_path, *, intervals=0, set_events=0, sessions=0):
    """Insert synthetic rows by direct SQL (the batch endpoints land in Stage 2)."""
    conn = sqlite3.connect(db_path)
    for i in range(intervals):
        conn.execute(
            "INSERT INTO intervals (device_id, timestamp_ms, seq, heart_rate_bpm,"
            " rr_interval_ms, session_id, received_at)"
            " VALUES (?, ?, 0, 142, 423, ?, '2026-08-10T00:00:00Z')",
            (DEVICE_ID, 1770000000000 + i * 400, SESSION_ID))
    for i in range(set_events):
        conn.execute(
            "INSERT INTO set_events (event_id, date, exercise_key, set_num, action,"
            " client_timestamp_ms, session_id, received_at)"
            " VALUES (?, '2030-01-03', 'fixture-adhoc-lift', ?, 'check', ?, ?,"
            " '2026-08-10T00:00:00Z')",
            (f"fixture-event-{i}", i + 1, 1770000010000 + i * 1000, SESSION_ID))
    for i in range(sessions):
        conn.execute(
            "INSERT INTO sessions (session_id, device_id, started_at_ms,"
            " workout_date, received_at)"
            " VALUES (?, ?, ?, '2030-01-03', '2026-08-10T00:00:00Z')",
            (f"fixture-session-{i}", DEVICE_ID, 1769999990000 + i * 3600000))
    conn.commit()
    conn.close()


@pytest.mark.integration
class TestHrStatus:
    def test_router_is_mounted(self, client):
        """The hr module is headless but still mounted at its api_prefix."""
        assert client.get("/api/hr/status").status_code == 200

    def test_zero_counts_on_fresh_db(self, client):
        assert client.get("/api/hr/status").json() == {
            "status": "ok",
            "samplesCount": 0,
            "setEventsCount": 0,
            "sessionsCount": 0,
        }

    def test_counts_reflect_stored_rows(self, client, tmp_hr_db):
        _seed(tmp_hr_db, intervals=3, set_events=2, sessions=1)
        assert client.get("/api/hr/status").json() == {
            "status": "ok",
            "samplesCount": 3,
            "setEventsCount": 2,
            "sessionsCount": 1,
        }

    def test_counts_are_independent_per_table(self, client, tmp_hr_db):
        """A count wired to the wrong table would pass the all-equal case."""
        _seed(tmp_hr_db, intervals=5, set_events=0, sessions=2)
        body = client.get("/api/hr/status").json()
        assert (body["samplesCount"], body["setEventsCount"], body["sessionsCount"]) == (5, 0, 2)
