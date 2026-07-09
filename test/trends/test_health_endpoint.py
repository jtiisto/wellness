"""Integration tests for the trends health/recovery endpoint (v2 Phase 1)."""

import sqlite3
from datetime import date, timedelta

import pytest


def _iso(d):
    return d.isoformat()


@pytest.fixture
def tmp_recovery_db(tmp_path, monkeypatch):
    """A minimal Garmin health DB with only the daily_health_metrics columns
    recovery_series reads, covering: full rows, per-field nulls, a band-less
    day, and an out-of-range old row."""
    db_path = tmp_path / "garmin_recovery.db"
    today = date.today()
    conn = sqlite3.connect(db_path)
    conn.execute("""
        CREATE TABLE daily_health_metrics (
            metric_date DATE PRIMARY KEY,
            resting_heart_rate INTEGER,
            hrv_last_night_avg FLOAT,
            hrv_baseline_low_upper FLOAT,
            hrv_baseline_balanced_low FLOAT,
            hrv_baseline_balanced_upper FLOAT,
            sleep_duration_hours FLOAT,
            sleep_score INTEGER
        )
    """)
    rows = [
        # Full row.
        (_iso(today - timedelta(days=2)), 60, 30.0, 23.0, 25.0, 31.0,
         8.233333333, 82),
        # HRV null (watch not worn), sleep present.
        (_iso(today - timedelta(days=1)), 61, None, 23.0, 25.0, 31.0, 5.75, 67),
        # Band columns null → hrv_band omitted, hrv value still emitted.
        (_iso(today), 59, 28.0, None, None, None, None, None),
        # Old row for range clipping.
        (_iso(today - timedelta(days=40)), 65, 40.0, 23.0, 25.0, 31.0, 7.0, 75),
    ]
    conn.executemany(
        "INSERT INTO daily_health_metrics VALUES (?,?,?,?,?,?,?,?)", rows)
    conn.commit()
    conn.close()
    return {"today": today, "path": db_path}


def _fresh_client(garmin_path, monkeypatch):
    # The trends router resolves GARMIN_DB_PATH at create_app time; the base
    # conftest pins it to a nonexistent file, so re-point and build fresh.
    monkeypatch.setenv("GARMIN_DB_PATH", str(garmin_path))
    import server as server_mod
    from fastapi.testclient import TestClient
    return TestClient(server_mod.create_app())


@pytest.mark.integration
class TestRecoveryEndpoint:
    def test_unavailable_when_db_missing(self, client):
        # Conftest points GARMIN_DB_PATH at a nonexistent file by default.
        resp = client.get("/api/trends/health/recovery")
        assert resp.status_code == 200
        assert resp.json() == {"available": False, "days": []}

    def test_unavailable_when_table_missing(self, client, tmp_path, monkeypatch):
        # A Garmin DB without daily_health_metrics (schema drift / older
        # sync tool) degrades, never 500s.
        db_path = tmp_path / "garmin_no_table.db"
        sqlite3.connect(db_path).close()
        with _fresh_client(db_path, monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/recovery").json()
        assert data == {"available": False, "days": []}

    def test_days_shape_nulls_and_band(self, tmp_recovery_db, client, monkeypatch):
        today = tmp_recovery_db["today"]
        start = _iso(today - timedelta(days=7))
        with _fresh_client(tmp_recovery_db["path"], monkeypatch) as c:
            data = c.get(
                f"/wellness/api/trends/health/recovery?start={start}&end={_iso(today)}"
            ).json()

        assert data["available"] is True
        days = {d["date"]: d for d in data["days"]}
        # Range clipping: the 40-day-old row is excluded.
        assert _iso(today - timedelta(days=40)) not in days
        assert len(days) == 3

        full = days[_iso(today - timedelta(days=2))]
        assert full["rhr"] == 60
        assert full["hrv"] == 30.0
        assert full["hrv_band"] == {"low": 25.0, "high": 31.0, "low_floor": 23.0}
        assert full["sleep_hours"] == 8.23   # rounded to 2dp
        assert full["sleep_score"] == 82

        # Per-field nulls pass through — no imputation.
        assert days[_iso(today - timedelta(days=1))]["hrv"] is None
        bandless = days[_iso(today)]
        assert bandless["hrv"] == 28.0
        assert bandless["hrv_band"] is None
        assert bandless["sleep_hours"] is None

    def test_dates_ascending(self, tmp_recovery_db, client, monkeypatch):
        with _fresh_client(tmp_recovery_db["path"], monkeypatch) as c:
            days = c.get("/wellness/api/trends/health/recovery").json()["days"]
        dates = [d["date"] for d in days]
        assert dates == sorted(dates)

    def test_calendar_invalid_date_422(self, tmp_recovery_db, client, monkeypatch):
        with _fresh_client(tmp_recovery_db["path"], monkeypatch) as c:
            assert c.get(
                "/wellness/api/trends/health/recovery?start=2026-02-30"
            ).status_code == 422
