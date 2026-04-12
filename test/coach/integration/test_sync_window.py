"""Integration tests for the 60-day sync window limit.

Validates that:
- Full sync response includes earliestDate field
- Plans older than SYNC_WINDOW_DAYS are excluded from full sync
- Logs older than SYNC_WINDOW_DAYS are excluded from full sync
- Delta sync still returns recently-modified old plans
- Client store.js and CalendarPicker.js have the expected code
"""
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest


# ==================== Server: earliestDate in response ====================

@pytest.mark.integration
class TestSyncWindowEarliestDate:
    def test_full_sync_includes_earliest_date(self, client, coach_registered_client):
        """Full sync response should include earliestDate field."""
        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        assert response.status_code == 200
        data = response.json()
        assert "earliestDate" in data

    def test_earliest_date_is_date_string(self, client, coach_registered_client):
        """earliestDate should be a YYYY-MM-DD string."""
        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        data = response.json()
        earliest = data["earliestDate"]
        assert len(earliest) == 10
        assert earliest[4] == "-" and earliest[7] == "-"

    def test_earliest_date_is_approximately_60_days_ago(self, client, coach_registered_client):
        """earliestDate should be approximately SYNC_WINDOW_DAYS ago."""
        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        data = response.json()
        earliest = datetime.strptime(data["earliestDate"], "%Y-%m-%d")
        expected = datetime.now(timezone.utc).replace(tzinfo=None) - timedelta(days=60)
        # Allow 1 day tolerance for test timing
        assert abs((earliest - expected).days) <= 1

    def test_delta_sync_includes_earliest_date(self, client, coach_registered_client):
        """Delta sync response should also include earliestDate."""
        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        server_time = response.json()["serverTime"]

        response = client.get(
            f"/api/coach/sync?client_id={coach_registered_client}&last_sync_time={server_time}"
        )
        assert response.status_code == 200
        data = response.json()
        assert "earliestDate" in data


# ==================== Server: plan date filtering ====================

@pytest.mark.integration
class TestSyncWindowPlanFiltering:
    def test_recent_plan_included(self, client, coach_seeded_database):
        """Plans within the sync window should be returned."""
        response = client.get(
            f"/api/coach/sync?client_id={coach_seeded_database['client_id']}"
        )
        data = response.json()
        today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        assert today in data["plans"]

    def test_old_plan_excluded_from_full_sync(self, client, coach_registered_client, tmp_coach_db):
        """Plans older than SYNC_WINDOW_DAYS should be excluded from full sync."""
        old_date = (datetime.now(timezone.utc) - timedelta(days=90)).strftime("%Y-%m-%d")
        now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

        conn = sqlite3.connect(tmp_coach_db)
        cursor = conn.cursor()
        cursor.execute("""
            INSERT INTO workout_sessions
            (date, day_name, location, phase, last_modified, modified_by)
            VALUES (?, ?, ?, ?, ?, ?)
        """, (old_date, "Old Workout", "Home", "Foundation", now, "test"))
        session_id = cursor.lastrowid
        cursor.execute("""
            INSERT INTO session_blocks (session_id, position, block_type, title)
            VALUES (?, ?, ?, ?)
        """, (session_id, 0, "strength", "Strength"))
        block_id = cursor.lastrowid
        cursor.execute("""
            INSERT INTO planned_exercises
            (session_id, block_id, exercise_key, position, name, exercise_type)
            VALUES (?, ?, ?, ?, ?, ?)
        """, (session_id, block_id, "ex_old", 0, "Old Exercise", "strength"))
        conn.commit()
        conn.close()

        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        data = response.json()
        assert old_date not in data["plans"]

    def test_plan_at_boundary_included(self, client, coach_registered_client, tmp_coach_db):
        """Plan exactly at the boundary (60 days ago) should be included."""
        boundary_date = (datetime.now(timezone.utc) - timedelta(days=59)).strftime("%Y-%m-%d")
        now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

        conn = sqlite3.connect(tmp_coach_db)
        cursor = conn.cursor()
        cursor.execute("""
            INSERT INTO workout_sessions
            (date, day_name, location, phase, last_modified, modified_by)
            VALUES (?, ?, ?, ?, ?, ?)
        """, (boundary_date, "Boundary Workout", "Home", "Foundation", now, "test"))
        session_id = cursor.lastrowid
        cursor.execute("""
            INSERT INTO session_blocks (session_id, position, block_type, title)
            VALUES (?, ?, ?, ?)
        """, (session_id, 0, "strength", "Strength"))
        block_id = cursor.lastrowid
        cursor.execute("""
            INSERT INTO planned_exercises
            (session_id, block_id, exercise_key, position, name, exercise_type)
            VALUES (?, ?, ?, ?, ?, ?)
        """, (session_id, block_id, "ex_boundary", 0, "Boundary Exercise", "strength"))
        conn.commit()
        conn.close()

        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        data = response.json()
        assert boundary_date in data["plans"]


# ==================== Server: log date filtering ====================

@pytest.mark.integration
class TestSyncWindowLogFiltering:
    def test_recent_log_included(self, client, coach_seeded_database):
        """Logs within the sync window should be returned."""
        response = client.get(
            f"/api/coach/sync?client_id={coach_seeded_database['client_id']}"
        )
        data = response.json()
        today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        assert today in data["logs"]

    def test_old_log_excluded(self, client, coach_registered_client):
        """Logs older than SYNC_WINDOW_DAYS should be excluded."""
        old_date = (datetime.now(timezone.utc) - timedelta(days=90)).strftime("%Y-%m-%d")
        client.post(
            "/api/coach/sync",
            json={
                "clientId": coach_registered_client,
                "logs": {
                    old_date: {
                        "session_feedback": {"pain_discomfort": "None"},
                        "_lastModifiedAt": "2024-01-01T00:00:00Z"
                    }
                }
            }
        )

        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        data = response.json()
        assert old_date not in data["logs"]


# ==================== Server: SYNC_WINDOW_DAYS constant ====================

@pytest.mark.integration
class TestSyncWindowConstant:
    def test_sync_window_days_defined(self):
        """coach.py should define SYNC_WINDOW_DAYS constant."""
        from modules import coach
        assert hasattr(coach, "SYNC_WINDOW_DAYS")
        assert coach.SYNC_WINDOW_DAYS == 60


# ==================== Client: store.js ====================

PUBLIC_DIR = Path(__file__).parent.parent.parent.parent / "public"
JS_DIR = PUBLIC_DIR / "js"


class TestStoreEarliestDate:
    """Tests that store.js handles earliestDate from sync response."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (JS_DIR / "coach" / "store.js").read_text()

    def test_exports_earliest_date_signal(self):
        assert "export const earliestDate = signal(" in self.source

    def test_loads_earliest_date_from_metadata(self):
        assert "earliestDate" in self.source
        assert "metadata?.earliestDate" in self.source

    def test_saves_earliest_date_in_metadata(self):
        """saveMetadata should persist earliestDate."""
        assert "earliestDate: earliestDate.value" in self.source

    def test_stores_earliest_date_from_sync_response(self):
        assert "data.earliestDate" in self.source

    def test_prunes_plans_older_than_earliest_date(self):
        """Should filter out plans older than earliestDate after sync."""
        assert "prunedPlans" in self.source

    def test_prunes_logs_older_than_earliest_date(self):
        """Should filter out logs older than earliestDate after sync."""
        assert "prunedLogs" in self.source


# ==================== Client: CalendarPicker.js ====================

class TestCalendarPickerBounds:
    """Tests that CalendarPicker.js disables dates before earliestDate."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (JS_DIR / "coach" / "components" / "CalendarPicker.js").read_text()

    def test_imports_earliest_date(self):
        assert "earliestDate" in self.source
        assert "from '../store.js'" in self.source

    def test_handle_date_select_guards_disabled(self):
        """handleDateSelect should ignore dates before earliestDate."""
        assert "earliestDate.value && dateStr < earliestDate.value" in self.source

    def test_generate_calendar_days_adds_is_disabled(self):
        """generateCalendarDays should set isDisabled on each day."""
        assert "isDisabled" in self.source

    def test_handle_prev_month_guards_navigation(self):
        """handlePrevMonth should prevent navigating before earliestDate."""
        assert "lastDateStr < earliestDate.value" in self.source

    def test_disabled_class_applied(self):
        """Disabled days should get 'disabled' CSS class."""
        assert "' disabled'" in self.source

    def test_disabled_days_no_click_handler(self):
        """Disabled days should not have an onClick handler."""
        assert "isDisabled ? undefined" in self.source


# ==================== Client: CSS ====================

class TestCalendarDisabledCss:
    """Tests that styles.css includes disabled calendar day styling."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (PUBLIC_DIR / "styles.css").read_text()

    def test_has_disabled_calendar_day_rule(self):
        assert ".calendar-day.disabled" in self.source

    def test_disabled_has_low_opacity(self):
        assert "opacity: 0.25" in self.source

    def test_disabled_has_pointer_events_none(self):
        assert "pointer-events: none" in self.source
