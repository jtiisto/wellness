"""Fixtures for the garmin module: fake sync scripts and a fake garmy DB.

Two safety rules govern this directory:

1. **No test may run the real sync.** The root conftest pins GARMIN_SYNC_CMD at
   a nonexistent path (bin/garmin-sync.sh is tracked, so the module's default
   seam WOULD resolve in a clone). Every test here opts in to a script this
   module wrote into tmp_path, and points the env var at it inside the test
   body — after the `client` fixture has run, since test_app's own setenv would
   otherwise win (the ordering trap documented in test/trends/
   test_weight_endpoint.py).
2. **No fixture value comes from a real database.** See `garmy_sync_db`.
"""

import sqlite3
import stat
import time
from datetime import datetime, timedelta, timezone

import pytest


# ==================== Fake sync scripts ====================


@pytest.fixture
def sync_script(tmp_path):
    """Factory: write an executable stand-in for bin/garmin-sync.sh.

    The default body appends a line to a marker file and exits 0, so "did it
    run, and how many times" is a file read (the test_coach_hooks idiom).
    """
    counter = {"n": 0}

    def _make(body=None, exit_code=0, name=None):
        counter["n"] += 1
        script = tmp_path / (name or f"fake-garmin-sync-{counter['n']}.sh")
        marker = tmp_path / f"{script.stem}.marker"
        if body is None:
            body = f'echo "ran $1 $2" >> "{marker}"\nexit {exit_code}\n'
        script.write_text(f"#!/bin/bash\n{body}")
        script.chmod(script.stat().st_mode | stat.S_IEXEC)
        return script, marker

    return _make


def poll_for(predicate, timeout=5.0, interval=0.05):
    """Wait for a background task's side effect (the _poll_exit_code idiom).

    Returns True as soon as `predicate()` holds, False at timeout — the caller
    asserts, so a failure reads as the condition that was never met.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


def marker_lines(marker):
    """How many times a fake script ran (0 when it never did)."""
    if not marker.exists():
        return 0
    return len([line for line in marker.read_text().splitlines() if line.strip()])


# ==================== Fake garmy DB ====================


def _stamp(dt, fractional=True):
    """Render a datetime the way garmy's SQLAlchemy layer stores synced_at:
    naive UTC, space-separated, microseconds present unless they are zero."""
    return dt.strftime("%Y-%m-%d %H:%M:%S.%f" if fractional else "%Y-%m-%d %H:%M:%S")


def write_sync_status(db_path, rows, create_table=True):
    """Build a fake garmy health DB carrying only the sync_status table.

    PROVENANCE: every value here is INVENTED. The table's *shape* mirrors
    garmy's real schema (it is the contract this module reads), but no row is
    ever copied from the developer's ~/.garmy/health.db — this repo is PUBLIC
    and a real sync timestamp is real personal data (see bin/scan_personal_data.py
    and the c18008e incident). Static stamps use the far-future 2030-01-*
    convention so they can never collide with a real one; the "a sync just
    happened" cases are computed from the clock, never transcribed.

    `rows` is a list of synced_at values (str or None).
    """
    conn = sqlite3.connect(db_path)
    if create_table:
        conn.execute("""
            CREATE TABLE sync_status (
                user_id INTEGER NOT NULL,
                sync_date DATE NOT NULL,
                metric_type VARCHAR NOT NULL,
                status VARCHAR NOT NULL,
                synced_at DATETIME,
                error_message TEXT,
                created_at DATETIME,
                PRIMARY KEY (user_id, sync_date, metric_type)
            )
        """)
        conn.executemany(
            "INSERT INTO sync_status "
            "(user_id, sync_date, metric_type, status, synced_at) "
            "VALUES (1, ?, ?, ?, ?)",
            [("2030-01-0%d" % ((i % 9) + 1), f"metric_{i}",
              "completed" if value else "pending", value)
             for i, value in enumerate(rows)],
        )
    else:
        # A DB file that exists but carries no sync_status table — a foreign or
        # half-migrated database.
        conn.execute("CREATE TABLE unrelated (id INTEGER PRIMARY KEY)")
    conn.commit()
    conn.close()
    return db_path


@pytest.fixture
def garmy_sync_db(tmp_path):
    """A fake garmy DB whose newest stamp is the fractional 2030-01-02 one.

    Covers all three shapes the reader must survive together: fractional
    seconds, no fractional seconds, and a NULL synced_at (garmy leaves it null
    on pending rows — MAX must skip it, not choke).
    """
    path = tmp_path / "garmy_fixture.db"
    newest = datetime(2030, 1, 2, 14, 30, 5, 123456)
    write_sync_status(path, [
        _stamp(datetime(2030, 1, 1, 9, 0, 0), fractional=False),
        _stamp(newest),
        None,
    ])
    return {
        "path": path,
        "newest": newest,
        "newest_ms": int(newest.replace(tzinfo=timezone.utc).timestamp() * 1000),
    }


@pytest.fixture
def garmy_db_synced_now(tmp_path):
    """A fake garmy DB claiming a sync completed a minute ago.

    This is the durable cross-restart backstop's subject: no in-process attempt
    has ever happened, yet a sync demonstrably has.
    """
    path = tmp_path / "garmy_recent.db"
    recent = datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(minutes=1)
    write_sync_status(path, [_stamp(recent)])
    return {"path": path, "recent": recent}
