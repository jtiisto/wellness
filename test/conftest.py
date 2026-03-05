"""
Shared fixtures for all Wellness unified app tests.

Provides:
- test_app: a FastAPI app with isolated temp databases for all modules
- client: a FastAPI TestClient pointed at test_app
- Module-specific fixtures for journal, coach, and analysis
"""
import os
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

# Add wellness/src to path for imports
_SRC_DIR = Path(__file__).parent.parent / "src"
sys.path.insert(0, str(_SRC_DIR))


@pytest.fixture(scope="function")
def tmp_journal_db(tmp_path):
    """Temporary database file for the journal module."""
    return tmp_path / "journal_test.db"


@pytest.fixture(scope="function")
def tmp_coach_db(tmp_path):
    """Temporary database file for the coach module."""
    return tmp_path / "coach_test.db"


@pytest.fixture(scope="function")
def tmp_analysis_db(tmp_path):
    """Temporary database file for the analysis module."""
    return tmp_path / "analysis_test.db"


@pytest.fixture(scope="function")
def test_app(tmp_path, tmp_journal_db, tmp_coach_db, tmp_analysis_db, monkeypatch):
    """
    Create a test FastAPI app with isolated databases for all modules.

    This re-imports config/server to wire up temp DB paths and a temp public dir.
    """
    # Create minimal public directory structure for static file tests
    public_dir = tmp_path / "public"
    public_dir.mkdir()
    (public_dir / "index.html").write_text(
        '<html><head>'
        '<link rel="stylesheet" href="/styles.css">'
        '<script src="/js/app.js"></script>'
        '</head><body><title>Health</title>Test</body></html>'
    )
    (public_dir / "styles.css").write_text("body { margin: 0; } :root { --bg-primary: #111; }")
    js_dir = public_dir / "js"
    js_dir.mkdir()
    (js_dir / "app.js").write_text("console.log('test');")

    manifest = public_dir / "manifest.json"
    manifest.write_text('{"name":"Wellness","start_url":"/","display":"standalone"}')

    sw = public_dir / "sw.js"
    sw.write_text("// service worker stub\nself.addEventListener('fetch', () => {});")

    icons_dir = public_dir / "icons"
    icons_dir.mkdir()
    # Create a minimal PNG (1x1 pixel)
    import struct, zlib
    def _make_png():
        sig = b'\x89PNG\r\n\x1a\n'
        ihdr_data = struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0)
        ihdr_crc = zlib.crc32(b'IHDR' + ihdr_data) & 0xffffffff
        ihdr = struct.pack('>I', 13) + b'IHDR' + ihdr_data + struct.pack('>I', ihdr_crc)
        raw = b'\x00\x00\x00\x00'
        idat_data = zlib.compress(raw)
        idat_crc = zlib.crc32(b'IDAT' + idat_data) & 0xffffffff
        idat = struct.pack('>I', len(idat_data)) + b'IDAT' + idat_data + struct.pack('>I', idat_crc)
        iend_crc = zlib.crc32(b'IEND') & 0xffffffff
        iend = struct.pack('>I', 0) + b'IEND' + struct.pack('>I', iend_crc)
        return sig + ihdr + idat + iend
    (icons_dir / "icon-192.png").write_bytes(_make_png())

    # Set env vars so modules pick up temp DBs
    monkeypatch.setenv("JOURNAL_DB_PATH", str(tmp_journal_db))
    monkeypatch.setenv("COACH_DB_PATH", str(tmp_coach_db))
    monkeypatch.setenv("ANALYSIS_DB_PATH", str(tmp_analysis_db))

    # Patch PUBLIC_DIR in config before server imports it
    import config
    monkeypatch.setattr(config, "PUBLIC_DIR", public_dir)

    # Force re-import of server module so it picks up patched config
    # We need to build a fresh app each time
    import importlib
    import modules.journal as journal_mod
    import modules.coach as coach_mod
    import modules.analysis as analysis_mod

    # Reset module-level DB paths and reinitialize databases
    journal_mod._db_path = tmp_journal_db
    journal_mod.init_database()

    coach_mod._db_path = tmp_coach_db
    coach_mod.init_database()

    # Analysis uses string paths
    from modules.analysis_db import init_database as init_analysis_db
    init_analysis_db(str(tmp_analysis_db))
    analysis_mod._db_path = tmp_analysis_db

    # Now import the server (which has module routers already mounted).
    # Since routers are already mounted at import time, we just need to patch the
    # module-level vars. The routers use the module-level _db_path, which we've
    # already set above.
    import server
    monkeypatch.setattr(server, "PUBLIC_DIR", public_dir)

    yield server.app


@pytest.fixture(scope="function")
def client(test_app):
    """Create a test client for the unified FastAPI app."""
    with TestClient(test_app) as c:
        yield c


# ==================== Journal-Specific Fixtures ====================

@pytest.fixture
def sample_tracker():
    """Sample tracker configuration for journal tests."""
    return {
        "id": "tracker-001",
        "name": "Water Intake",
        "category": "health",
        "type": "quantifiable",
        "unit": "glasses",
        "goal": 8,
        "_baseVersion": 0
    }


@pytest.fixture
def sample_simple_tracker():
    """Sample simple (boolean) tracker for journal tests."""
    return {
        "id": "tracker-simple",
        "name": "Exercise",
        "category": "health",
        "type": "simple",
        "_baseVersion": 0
    }


@pytest.fixture
def sample_entry(sample_tracker):
    """Sample entry data for journal tests."""
    today = datetime.now().strftime("%Y-%m-%d")
    return {
        "date": today,
        "tracker_id": sample_tracker["id"],
        "value": 5,
        "completed": False,
        "_baseVersion": 0
    }


@pytest.fixture
def journal_registered_client(client):
    """A client that has been registered with the journal module."""
    client_id = "test-client-001"
    response = client.post(f"/api/journal/sync/register?client_id={client_id}&client_name=TestClient")
    assert response.status_code == 200
    return client_id


@pytest.fixture
def journal_seeded_database(client, journal_registered_client, sample_tracker):
    """Journal database seeded with sample data for testing."""
    payload = {
        "clientId": journal_registered_client,
        "config": [sample_tracker],
        "days": {}
    }
    response = client.post("/api/journal/sync/update", json=payload)
    assert response.status_code == 200

    today = datetime.now()
    days = {}
    for i in range(3):
        date_str = (today - timedelta(days=i)).strftime("%Y-%m-%d")
        days[date_str] = {
            sample_tracker["id"]: {
                "value": 5 + i,
                "completed": i == 0,
                "_baseVersion": 0
            }
        }

    payload2 = {
        "clientId": journal_registered_client,
        "config": [],
        "days": days
    }
    response = client.post("/api/journal/sync/update", json=payload2)
    assert response.status_code == 200

    return {
        "client_id": journal_registered_client,
        "tracker": sample_tracker,
        "dates": list(days.keys())
    }


# ==================== Coach-Specific Fixtures ====================

@pytest.fixture
def sample_plan():
    """Sample workout plan for coach tests (block-based format)."""
    return {
        "day_name": "Test Workout",
        "location": "Home",
        "phase": "Foundation",
        "blocks": [
            {
                "block_type": "warmup",
                "title": "Warmup",
                "exercises": [
                    {
                        "id": "warmup_0",
                        "name": "Stability Start",
                        "type": "checklist",
                        "items": ["Cat-Cow x10", "Bird-Dog x5/side"]
                    }
                ]
            },
            {
                "block_type": "strength",
                "title": "Strength",
                "rest_guidance": "Rest 2 min",
                "exercises": [
                    {
                        "id": "ex_1",
                        "name": "KB Goblet Squat",
                        "type": "strength",
                        "target_sets": 3,
                        "target_reps": "10",
                        "guidance_note": "Tempo 3-1-1"
                    }
                ]
            },
            {
                "block_type": "cardio",
                "title": "Conditioning",
                "exercises": [
                    {
                        "id": "cardio_1",
                        "name": "Zone 2 Bike",
                        "type": "duration",
                        "target_duration_min": 15,
                        "guidance_note": "HR 135-148"
                    }
                ]
            }
        ]
    }


@pytest.fixture
def sample_log():
    """Sample workout log for coach tests."""
    return {
        "session_feedback": {
            "pain_discomfort": "None",
            "general_notes": "Good session"
        },
        "warmup_0": {
            "completed_items": ["Cat-Cow x10", "Bird-Dog x5/side"]
        },
        "ex_1": {
            "completed": True,
            "user_note": "Felt strong",
            "sets": [
                {"set_num": 1, "weight": 24, "reps": 10, "rpe": 7},
                {"set_num": 2, "weight": 24, "reps": 10, "rpe": 7.5},
                {"set_num": 3, "weight": 24, "reps": 10, "rpe": 8}
            ]
        },
        "cardio_1": {
            "completed": True,
            "duration_min": 16,
            "avg_hr": 142,
            "max_hr": 149
        }
    }


@pytest.fixture
def coach_registered_client(client):
    """A client that has been registered with the coach module."""
    client_id = "test-client-001"
    response = client.post(f"/api/coach/register?client_id={client_id}&client_name=TestClient")
    assert response.status_code == 200
    return client_id


@pytest.fixture
def coach_seeded_database(client, coach_registered_client, sample_plan, sample_log, tmp_coach_db):
    """Coach database seeded with sample plan and log data for testing."""
    import sqlite3

    today = datetime.now().strftime("%Y-%m-%d")
    yesterday = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")

    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    cursor = conn.cursor()

    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    # Insert today's plan
    cursor.execute("""
        INSERT INTO workout_sessions
        (date, day_name, location, phase, last_modified, modified_by)
        VALUES (?, ?, ?, ?, ?, ?)
    """, (today, "Test Workout", "Home", "Foundation", now, "test"))
    s1 = cursor.lastrowid

    cursor.execute("""
        INSERT INTO session_blocks (session_id, position, block_type, title)
        VALUES (?, ?, ?, ?)
    """, (s1, 0, "warmup", "Warmup"))
    b1 = cursor.lastrowid

    cursor.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type)
        VALUES (?, ?, ?, ?, ?, ?)
    """, (s1, b1, "warmup_0", 0, "Stability Start", "checklist"))
    e_warmup = cursor.lastrowid

    for i, item in enumerate(["Cat-Cow x10", "Bird-Dog x5/side"]):
        cursor.execute(
            "INSERT INTO checklist_items (exercise_id, position, item_text) VALUES (?, ?, ?)",
            (e_warmup, i, item)
        )

    cursor.execute("""
        INSERT INTO session_blocks (session_id, position, block_type, title, rest_guidance)
        VALUES (?, ?, ?, ?, ?)
    """, (s1, 1, "strength", "Strength", "Rest 2 min"))
    b2 = cursor.lastrowid

    cursor.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type,
         target_sets, target_reps, guidance_note)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (s1, b2, "ex_1", 0, "KB Goblet Squat", "strength", 3, "10", "Tempo 3-1-1"))

    cursor.execute("""
        INSERT INTO session_blocks (session_id, position, block_type, title)
        VALUES (?, ?, ?, ?)
    """, (s1, 2, "cardio", "Conditioning"))
    b3 = cursor.lastrowid

    cursor.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type,
         target_duration_min, guidance_note)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """, (s1, b3, "cardio_1", 0, "Zone 2 Bike", "duration", 15, "HR 135-148"))

    # Yesterday's plan
    cursor.execute("""
        INSERT INTO workout_sessions
        (date, day_name, location, phase, last_modified, modified_by)
        VALUES (?, ?, ?, ?, ?, ?)
    """, (yesterday, "Yesterday's Workout", "Home", "Foundation", now, "test"))
    s2 = cursor.lastrowid

    cursor.execute("""
        INSERT INTO session_blocks (session_id, position, block_type, title)
        VALUES (?, ?, ?, ?)
    """, (s2, 0, "strength", "Strength"))
    b_y = cursor.lastrowid

    cursor.execute("""
        INSERT INTO planned_exercises
        (session_id, block_id, exercise_key, position, name, exercise_type,
         target_sets, target_reps)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """, (s2, b_y, "ex_1", 0, "Squat", "strength", 3, "10"))

    conn.commit()
    conn.close()

    # Upload log via API
    client.post(
        "/api/coach/sync",
        json={
            "clientId": coach_registered_client,
            "logs": {today: sample_log}
        }
    )

    return {
        "client_id": coach_registered_client,
        "plan": sample_plan,
        "log": sample_log,
        "dates": [today, yesterday]
    }


# ==================== Analysis-Specific Fixtures ====================

@pytest.fixture
def analysis_initialized_db(tmp_analysis_db):
    """An initialized analysis database."""
    from modules.analysis_db import init_database
    init_database(str(tmp_analysis_db))
    return str(tmp_analysis_db)


@pytest.fixture
def mock_claude_cli():
    """Mock the Claude CLI execution for analysis tests."""
    async def fake_execute(prompt, extra_tools=None):
        return "## Workout Summary\nTest response.\n\n## Performance Analysis\nAll good."
    with patch("modules.analysis.execute_claude_query", side_effect=fake_execute) as mock:
        yield mock
