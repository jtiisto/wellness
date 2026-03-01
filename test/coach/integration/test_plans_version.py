"""Integration tests for GET /api/coach/plans-version endpoint."""

import sqlite3

import pytest
from datetime import datetime, timedelta, timezone


@pytest.mark.integration
class TestPlansVersion:
    def test_empty_db_returns_null(self, client):
        """Empty database should return null version."""
        response = client.get("/api/coach/plans-version")
        assert response.status_code == 200
        data = response.json()
        assert data["version"] is None

    def test_returns_max_last_modified(self, client, coach_seeded_database):
        """Should return the MAX(last_modified) from workout_sessions."""
        response = client.get("/api/coach/plans-version")
        assert response.status_code == 200
        data = response.json()
        assert data["version"] is not None
        # The seeded database inserts sessions with a known timestamp
        assert "T" in data["version"]  # ISO-8601 format

    def test_version_updates_on_plan_change(self, client, coach_seeded_database, tmp_coach_db):
        """Version should change when plans are modified."""
        # Get initial version
        resp1 = client.get("/api/coach/plans-version")
        version1 = resp1.json()["version"]

        # Modify a plan directly in the DB (simulating MCP plan update)
        new_ts = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        conn = sqlite3.connect(tmp_coach_db)
        conn.execute(
            "UPDATE workout_sessions SET last_modified = ? WHERE date = ?",
            (new_ts, coach_seeded_database["dates"][0]),
        )
        conn.commit()
        conn.close()

        # Version should now be the new timestamp
        resp2 = client.get("/api/coach/plans-version")
        version2 = resp2.json()["version"]

        assert version2 != version1
        assert version2 == new_ts

    def test_version_reflects_latest_session(self, client, tmp_coach_db):
        """Version should reflect the most recently modified session."""
        conn = sqlite3.connect(tmp_coach_db)
        conn.execute("PRAGMA foreign_keys = ON")

        old_ts = "2025-01-01T00:00:00Z"
        new_ts = "2025-06-15T12:00:00Z"

        conn.execute(
            "INSERT INTO workout_sessions (date, day_name, last_modified) VALUES (?, ?, ?)",
            ("2025-01-01", "Day A", old_ts),
        )
        conn.execute(
            "INSERT INTO workout_sessions (date, day_name, last_modified) VALUES (?, ?, ?)",
            ("2025-06-15", "Day B", new_ts),
        )
        conn.commit()
        conn.close()

        response = client.get("/api/coach/plans-version")
        assert response.json()["version"] == new_ts
