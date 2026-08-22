"""Fixtures and markers for the hr_analysis package's tests.

`hr_analysis` reads a database it never writes, so the fixtures here BUILD one
the way the `hr` module would and hand back its path. Every value is generated
from a formula (see `hr_db`) — no capture is ever transcribed into this repo.
"""
import importlib.util
import sqlite3

import pytest

from modules.db import DbAccessor
from modules.hr import init_database

# matplotlib is an optional dependency (see requirements.txt): the PNG half of
# report.py degrades to None without it, so the tests that assert a real file
# was rendered have to skip rather than fail on an install that omits it. The
# "returns None when matplotlib is missing" tests fake the ImportError and run
# unconditionally.
requires_matplotlib = pytest.mark.skipif(
    importlib.util.find_spec("matplotlib") is None,
    reason="matplotlib is optional; PNG rendering is only asserted when installed",
)

# 2030-01-01T00:00:00Z. Deliberately far-future: a plausible *past* epoch-ms
# anchor in a public repo invites the question of whether it came from a real
# capture. Nothing here did.
T0 = 1_893_456_000_000


def beat_rows(session_id, count, *, device_id="dev-a", rr_ms=800, hr_bpm=75,
              t0=T0, gap_every=None, seq=0):
    """Synthetic `intervals` rows: a metronome-steady beat train."""
    rows = []
    ts = t0
    for i in range(count):
        ts += rr_ms
        rows.append((device_id, ts, seq, hr_bpm, rr_ms,
                     1 if gap_every and i % gap_every == 0 else 0,
                     session_id, "garmin_hrm", "2030-01-01T00:00:00Z"))
    return rows


@pytest.fixture
def hr_db(tmp_path):
    """Build a real, initialised hr.db and return (path, insert_fn).

    Goes through the `hr` module's own `init_database`, so the tests read the
    schema the server actually creates rather than a hand-copied CREATE TABLE
    that could drift away from it.
    """
    db_path = tmp_path / "hr.db"
    init_database(DbAccessor(db_path))

    def insert(intervals=(), sessions=(), guide_events=()):
        conn = sqlite3.connect(db_path)
        try:
            conn.executemany(
                "INSERT INTO intervals (device_id, timestamp_ms, seq, "
                "heart_rate_bpm, rr_interval_ms, is_gap_before, session_id, "
                "sensor_type, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                intervals)
            conn.executemany(
                "INSERT INTO sessions (session_id, device_id, started_at_ms, "
                "ended_at_ms, workout_date, workout_session_id, received_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                sessions)
            conn.executemany(
                "INSERT INTO guide_events (event_id, date, exercise_key, action, "
                "client_timestamp_ms, session_id, extension_sec, timeline_json, "
                "received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                guide_events)
            conn.commit()
        finally:
            conn.close()

    return db_path, insert


def guide_row(event_id, action, ts_ms, *, session_id="s-1", exercise_key="fixture-ride",
              date="2030-01-01", extension_sec=None, timeline_json=None):
    """One `guide_events` row in the column order `hr_db`'s insert expects."""
    return (event_id, date, exercise_key, action, ts_ms, session_id,
            extension_sec, timeline_json, "2030-01-01T00:00:00Z")


# A three-step timeline in the coach wire's own segment shape: a marked warmup,
# a role-less (therefore work) effort, and a marked cooldown. Every number is
# invented; the shape is what matters.
TIMELINE_JSON = (
    '[{"duration_sec":300,"hr_min":112,"hr_max":126,"label":"ease in","role":"warmup"},'
    '{"duration_sec":600,"hr_min":138,"hr_max":152},'
    '{"duration_sec":180,"hr_max":118,"role":"cooldown"}]'
)
