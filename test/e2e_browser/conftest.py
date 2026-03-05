"""
E2E browser test fixtures.

Provides a live uvicorn server with isolated temp databases,
database seeding helpers, and browser page fixtures.
"""
import os
import socket
import sqlite3
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

pytestmark = pytest.mark.e2e


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
    }

    for k, v in env_vars.items():
        os.environ[k] = v

    import config
    original_public = config.PUBLIC_DIR
    config.PUBLIC_DIR = PUBLIC_DIR

    import modules.journal as journal_mod
    import modules.coach as coach_mod
    import modules.analysis as analysis_mod
    from modules.analysis_db import init_database as init_analysis_db

    journal_mod._db_path = Path(env_vars["JOURNAL_DB_PATH"])
    journal_mod.init_database()
    coach_mod._db_path = Path(env_vars["COACH_DB_PATH"])
    coach_mod.init_database()
    init_analysis_db(env_vars["ANALYSIS_DB_PATH"])
    analysis_mod._db_path = Path(env_vars["ANALYSIS_DB_PATH"])

    import server
    server.PUBLIC_DIR = PUBLIC_DIR

    port = _find_free_port()
    uvicorn_config = uvicorn.Config(
        server.app, host="127.0.0.1", port=port, log_level="warning"
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


@pytest.fixture
def seeded_coach_db(app_server):
    """Seed coach database with a training plan via direct SQLite."""
    db_path = app_server["db_dir"] / "coach.db"
    conn = sqlite3.connect(db_path, timeout=10)
    conn.execute("PRAGMA foreign_keys = ON")
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    today = datetime.now().strftime("%Y-%m-%d")
    yesterday = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")

    # Clear existing data to avoid UNIQUE constraint on repeated runs
    conn.execute("DELETE FROM checklist_items")
    conn.execute("DELETE FROM planned_exercises")
    conn.execute("DELETE FROM session_blocks")
    conn.execute("DELETE FROM workout_sessions")

    conn.execute("""
        INSERT INTO workout_sessions (date, day_name, location, phase, last_modified, modified_by)
        VALUES (?, 'Test Workout', 'Home', 'Foundation', ?, 'test')
    """, (today, now))
    s1 = conn.execute("SELECT last_insert_rowid()").fetchone()[0]

    conn.execute(
        "INSERT INTO session_blocks (session_id, position, block_type, title) VALUES (?, 0, 'warmup', 'Warmup')",
        (s1,))
    b1 = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
    conn.execute("""
        INSERT INTO planned_exercises (session_id, block_id, exercise_key, position, name, exercise_type)
        VALUES (?, ?, 'warmup_0', 0, 'Stability Start', 'checklist')
    """, (s1, b1))
    e1 = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
    for i, item in enumerate(["Cat-Cow x10", "Bird-Dog x5/side"]):
        conn.execute(
            "INSERT INTO checklist_items (exercise_id, position, item_text) VALUES (?, ?, ?)",
            (e1, i, item))

    conn.execute(
        "INSERT INTO session_blocks (session_id, position, block_type, title, rest_guidance) VALUES (?, 1, 'strength', 'Strength', 'Rest 2 min')",
        (s1,))
    b2 = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
    conn.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type, target_sets, target_reps, guidance_note)
        VALUES (?, ?, 'ex_1', 0, 'KB Goblet Squat', 'strength', 3, '10', 'Tempo 3-1-1')
    """, (s1, b2))

    conn.execute(
        "INSERT INTO session_blocks (session_id, position, block_type, title) VALUES (?, 2, 'cardio', 'Conditioning')",
        (s1,))
    b3 = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
    conn.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type, target_duration_min, guidance_note)
        VALUES (?, ?, 'cardio_1', 0, 'Zone 2 Bike', 'duration', 15, 'HR 135-148')
    """, (s1, b3))

    conn.commit()
    conn.close()

    return {"dates": [today, yesterday], "session_id": s1}


@pytest.fixture
def seeded_journal(app_server):
    """Seed journal data via API calls (respects version tracking)."""
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
        "_baseVersion": 0,
    }
    http_requests.post(f"{base}/api/journal/sync/update", json={
        "clientId": client_id,
        "config": [tracker],
        "days": {},
    })

    today = datetime.now()
    days = {}
    for i in range(3):
        date_str = (today - timedelta(days=i)).strftime("%Y-%m-%d")
        days[date_str] = {
            tracker["id"]: {"value": 5 + i, "completed": i == 0, "_baseVersion": 0}
        }
    http_requests.post(f"{base}/api/journal/sync/update", json={
        "clientId": client_id,
        "config": [],
        "days": days,
    })

    return {"client_id": client_id, "tracker": tracker, "dates": list(days.keys())}
