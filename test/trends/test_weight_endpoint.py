"""Integration tests for the trends weight endpoint (Garmin source)."""

import sqlite3
from datetime import date, timedelta

import pytest


@pytest.fixture
def tmp_garmin_db(tmp_path, monkeypatch):
    """A minimal Garmin health DB with only the columns weight_series reads,
    including multi-measurement days (last-per-day by timestamp must win)."""
    db_path = tmp_path / "garmin_fixture.db"
    today = date.today()
    conn = sqlite3.connect(db_path)
    conn.execute("""
        CREATE TABLE body_composition (
            sample_pk TEXT PRIMARY KEY,
            measurement_date DATE,
            timestamp_gmt DATETIME,
            weight_grams FLOAT
        )
    """)
    rows = [
        # Two measurements the same day — the LATER one (84.4) must win.
        ("a1", (today - timedelta(days=2)).isoformat(), "2026-07-05 06:00:00", 85200.0),
        ("a2", (today - timedelta(days=2)).isoformat(), "2026-07-05 18:00:00", 84400.0),
        ("b1", (today - timedelta(days=1)).isoformat(), "2026-07-06 07:00:00", 84200.0),
        # NULL weight rows are ignored.
        ("c1", today.isoformat(), "2026-07-07 07:00:00", None),
        # An old row for range clipping.
        ("d1", (today - timedelta(days=40)).isoformat(), "2026-05-28 07:00:00", 87000.0),
    ]
    conn.executemany(
        "INSERT INTO body_composition VALUES (?, ?, ?, ?)", rows)
    conn.commit()
    conn.close()
    monkeypatch.setenv("GARMIN_DB_PATH", str(db_path))
    return {"today": today, "path": db_path}


@pytest.mark.integration
class TestWeightEndpoint:
    def test_unavailable_when_db_missing(self, client):
        # Conftest points GARMIN_DB_PATH at a nonexistent file by default.
        resp = client.get("/api/trends/weight")
        assert resp.status_code == 200
        assert resp.json() == {"available": False, "series": []}

    def test_last_per_day_and_kg_conversion(self, tmp_garmin_db, client, monkeypatch):
        # The trends router resolves its Garmin path at create_app time, and
        # the test_app conftest monkeypatch (nonexistent default) runs AFTER
        # the fixture's setenv — so re-point the env here and build fresh.
        monkeypatch.setenv("GARMIN_DB_PATH", str(tmp_garmin_db["path"]))
        import server as server_mod
        from fastapi.testclient import TestClient
        app = server_mod.create_app()
        with TestClient(app) as c:
            data = c.get("/wellness/api/trends/weight").json()
        assert data["available"] is True
        by_date = {r["date"]: r["kg"] for r in data["series"]}
        two_ago = (tmp_garmin_db["today"] - timedelta(days=2)).isoformat()
        assert by_date[two_ago] == 84.4  # later timestamp wins; grams → kg 1dp
        # NULL-weight day absent.
        assert tmp_garmin_db["today"].isoformat() not in by_date

    def test_range_clipping(self, tmp_garmin_db, client, monkeypatch):
        monkeypatch.setenv("GARMIN_DB_PATH", str(tmp_garmin_db["path"]))
        import server as server_mod
        from fastapi.testclient import TestClient
        start = (tmp_garmin_db["today"] - timedelta(days=7)).isoformat()
        app = server_mod.create_app()
        with TestClient(app) as c:
            data = c.get(f"/wellness/api/trends/weight?start={start}").json()
        dates = [r["date"] for r in data["series"]]
        assert all(d >= start for d in dates)
        assert len(dates) == 2

    def test_unavailable_when_table_missing(self, tmp_path, monkeypatch, client):
        empty = tmp_path / "empty.db"
        sqlite3.connect(empty).close()  # file exists, no table
        monkeypatch.setenv("GARMIN_DB_PATH", str(empty))
        import server as server_mod
        from fastapi.testclient import TestClient
        app = server_mod.create_app()
        with TestClient(app) as c:
            data = c.get("/wellness/api/trends/weight").json()
        assert data == {"available": False, "series": []}
