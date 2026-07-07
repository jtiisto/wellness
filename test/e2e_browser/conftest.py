"""
E2E browser test fixtures.

Provides a live uvicorn server with isolated temp databases,
database seeding helpers, and browser page fixtures.
"""
import os
import socket
import sqlite3
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
import requests as http_requests
import uvicorn

PROJECT_ROOT = Path(__file__).parent.parent.parent
SRC_DIR = PROJECT_ROOT / "src"
PUBLIC_DIR = PROJECT_ROOT / "public"

# Shared test-data seeds (one implementation with test/conftest.py)
sys.path.insert(0, str(PROJECT_ROOT / "test"))
from seeds import seed_coach_plan  # noqa: E402

def pytest_collection_modifyitems(items):
    """Apply the e2e marker to every test in this directory.

    A `pytestmark` module variable in a conftest has NO effect (it only works
    inside test modules) — each e2e module used to repeat it manually, and the
    first new module to forget would silently run in the wrong selection
    (e.g. inside the fast pre-commit slice). This hook makes the directory
    itself the marker boundary.
    """
    for item in items:
        item.add_marker(pytest.mark.e2e)


def _find_free_port():
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


def _wait_for_server(url, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            http_requests.get(url, timeout=1)
            return
        except http_requests.ConnectionError:
            time.sleep(0.1)
    raise RuntimeError(f"Server at {url} did not start within {timeout}s")


@pytest.fixture(scope="session")
def app_server(tmp_path_factory):
    """Start a real HTTP server with isolated databases and real public/ dir."""
    import sys
    sys.path.insert(0, str(SRC_DIR))

    db_dir = tmp_path_factory.mktemp("e2e_dbs")
    env_vars = {
        "JOURNAL_DB_PATH": str(db_dir / "journal.db"),
        "COACH_DB_PATH": str(db_dir / "coach.db"),
        "ANALYSIS_DB_PATH": str(db_dir / "analysis.db"),
        # Nonexistent by default: trends' weight chart exercises its
        # available:false path and no e2e run touches the real ~/.garmy DB.
        "GARMIN_DB_PATH": str(db_dir / "garmin_health.db"),
    }

    for k, v in env_vars.items():
        os.environ[k] = v

    import config
    original_public = config.PUBLIC_DIR
    config.PUBLIC_DIR = PUBLIC_DIR

    import server
    server.PUBLIC_DIR = PUBLIC_DIR

    # Build a fresh app bound to the e2e temp DBs via create_app overrides (R2):
    # deterministic regardless of any earlier `import server` in the process, with
    # no reliance on the module-level app or global DB-path poking. Each module's
    # create_router initializes its own temp DB.
    app = server.create_app(db_path_overrides={
        "journal": Path(env_vars["JOURNAL_DB_PATH"]),
        "coach": Path(env_vars["COACH_DB_PATH"]),
        "analysis": Path(env_vars["ANALYSIS_DB_PATH"]),
    })

    port = _find_free_port()
    uvicorn_config = uvicorn.Config(
        app, host="127.0.0.1", port=port, log_level="warning"
    )
    uv_server = uvicorn.Server(uvicorn_config)
    thread = threading.Thread(target=uv_server.run, daemon=True)
    thread.start()

    base_url = f"http://127.0.0.1:{port}"
    _wait_for_server(base_url)

    yield {
        "url": base_url,
        "db_dir": db_dir,
        "env": env_vars,
        "port": port,
    }

    uv_server.should_exit = True
    thread.join(timeout=5)
    config.PUBLIC_DIR = original_public
    for k in env_vars:
        os.environ.pop(k, None)


@pytest.fixture
def app_page(page, app_server):
    """Navigate to app and wait for shell to load."""
    page.goto(app_server["url"])
    page.wait_for_selector(".shell", timeout=10000)
    return page


@pytest.fixture
def journal_app_page(page, app_server, seeded_journal):
    """Navigate to app AFTER journal data is seeded, so initial sync gets data."""
    page.goto(app_server["url"])
    page.wait_for_selector(".shell", timeout=10000)
    return page


def _reset_coach_data(conn):
    """Clear coach data tables to avoid UNIQUE constraints / stale tombstones
    on repeated runs against the session-scoped server. Order respects FK
    constraints: children before parents."""
    conn.execute("DELETE FROM checklist_log_items")
    conn.execute("DELETE FROM set_logs")
    conn.execute("DELETE FROM exercise_logs")
    conn.execute("DELETE FROM workout_session_logs")
    conn.execute("DELETE FROM deleted_exercise_logs")
    conn.execute("DELETE FROM checklist_items")
    conn.execute("DELETE FROM planned_exercises")
    conn.execute("DELETE FROM session_blocks")
    conn.execute("DELETE FROM workout_sessions")
    conn.execute("DELETE FROM deleted_plans")


@pytest.fixture
def seeded_coach_db(app_server):
    """Seed coach database with a training plan via direct SQLite."""
    db_path = app_server["db_dir"] / "coach.db"
    conn = sqlite3.connect(db_path, timeout=10)
    conn.execute("PRAGMA foreign_keys = ON")
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    # Use local time for calendar dates — the browser uses new Date() (local)
    # to determine "today", so seed data must match.
    today = datetime.now().strftime("%Y-%m-%d")
    yesterday = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")

    _reset_coach_data(conn)

    # One shared seed implementation with the unit/integration conftest
    # (test/seeds.py) — the two used to carry drifting near-duplicate SQL.
    seeded = seed_coach_plan(conn, today=today, now=now,
                             supersets=True, intervals=True, prescription=True)
    s1 = seeded["session_id"]

    conn.commit()
    conn.close()

    return {"dates": [today, yesterday], "session_id": s1}


@pytest.fixture
def coach_rest_day(app_server):
    """Coach database with NO plan for any date — every day is a rest day.
    The empty state + ad-hoc extra-session flow renders on today."""
    db_path = app_server["db_dir"] / "coach.db"
    conn = sqlite3.connect(db_path, timeout=10)
    conn.execute("PRAGMA foreign_keys = ON")
    _reset_coach_data(conn)
    conn.commit()
    conn.close()

    return {"today": datetime.now().strftime("%Y-%m-%d")}


@pytest.fixture
def seeded_journal(app_server):
    """Seed journal data via API calls (respects version tracking).

    Resets the journal DATA tables first — in one transaction, scoped to data
    only (clients/meta_sync sync plumbing untouched) — so each test starts from
    a clean DB instead of accumulating trackers/entries across the session.
    A previous blanket-wipe attempt destabilized the suite by clearing sync
    infrastructure non-atomically; this mirrors coach's reset-before-seed.
    """
    conn = sqlite3.connect(app_server["db_dir"] / "journal.db", timeout=10)
    try:
        with conn:  # one transaction: no empty-DB window visible to the server
            for table in ("entries", "entries_archive", "trackers_archive",
                          "sync_conflicts", "trackers"):
                conn.execute(f"DELETE FROM {table}")
    finally:
        conn.close()

    base = app_server["url"]

    client_id = "e2e-test-client"
    http_requests.post(
        f"{base}/api/journal/sync/register?client_id={client_id}&client_name=E2ETest")

    tracker = {
        "id": "tracker-e2e",
        "name": "Water Intake",
        "category": "health",
        "type": "quantifiable",
        "unit": "glasses",
        "goal": 8,
    }
    http_requests.post(f"{base}/api/journal/sync/update", json={
        "clientId": client_id,
        "config": [tracker],
        "days": {},
    })

    # Use local time for calendar dates — the browser determines "today"
    # via new Date() (local timezone), so seed dates must match.
    today = datetime.now()
    days = {}
    for i in range(3):
        date_str = (today - timedelta(days=i)).strftime("%Y-%m-%d")
        days[date_str] = {
            tracker["id"]: {"value": 5 + i, "completed": i == 0}
        }
    http_requests.post(f"{base}/api/journal/sync/update", json={
        "clientId": client_id,
        "config": [],
        "days": days,
    })

    return {"client_id": client_id, "tracker": tracker, "dates": list(days.keys())}
