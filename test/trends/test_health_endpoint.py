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


@pytest.fixture
def tmp_bodyspec_db(tmp_path):
    """A minimal BodySpec DB: three scans (one future-dated for end-clipping),
    whole-body + regional bone rows (only 'total' must surface), and one scan
    with no bone rows at all. Values are INVENTED — never paste rows from the
    real ~/.bodyspecy DB here; this repo is public."""
    db_path = tmp_path / "bodyspec_fixture.db"
    conn = sqlite3.connect(db_path)
    conn.execute("""
        CREATE TABLE scans (
            scan_date DATE PRIMARY KEY, lean_mass_kg FLOAT, fat_mass_kg FLOAT,
            total_mass_kg FLOAT, total_body_fat_pct FLOAT, vat_mass_kg FLOAT,
            ag_ratio FLOAT
        )
    """)
    conn.execute("""
        CREATE TABLE scan_bone_density (
            scan_date DATE, region TEXT, bmd_g_cm2 FLOAT, t_score FLOAT
        )
    """)
    conn.executemany("INSERT INTO scans VALUES (?,?,?,?,?,?,?)", [
        # Full float precision: the reader rounds (2dp mass, 1dp pct/t-score,
        # 3dp BMD), so the fixture must arrive unrounded to prove it.
        ("2026-02-10", 55.123456, 25.987654, 81.111110, 32.44, 1.454321, 1.216),
        ("2026-05-18", 58.246802, 22.135791, 80.382593, 27.53, 1.098765, 1.191),
        ("2099-01-01", 60.0, 20.0, 83.0, 24.0, 1.0, 1.30),  # future: clipped
    ])
    conn.executemany("INSERT INTO scan_bone_density VALUES (?,?,?,?)", [
        ("2026-02-10", "spine", 1.10, 0.5),
        ("2026-02-10", "total", 1.234567, 1.24),
        # 2026-05-18 has no bone rows → nulls, not a dropped scan.
    ])
    conn.commit()
    conn.close()
    return db_path


def _bodyspec_client(bodyspec_path, monkeypatch):
    monkeypatch.setenv("BODYSPEC_DB_PATH", str(bodyspec_path))
    import server as server_mod
    from fastapi.testclient import TestClient
    return TestClient(server_mod.create_app())


@pytest.mark.integration
class TestCompositionEndpoint:
    def test_unavailable_when_db_missing(self, client):
        # Conftest pins BODYSPEC_DB_PATH to a nonexistent file by default.
        resp = client.get("/api/trends/health/composition")
        assert resp.status_code == 200
        assert resp.json() == {"available": False, "scans": []}

    def test_unavailable_when_table_missing(self, client, tmp_path, monkeypatch):
        db_path = tmp_path / "bodyspec_no_table.db"
        sqlite3.connect(db_path).close()
        with _bodyspec_client(db_path, monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/composition").json()
        assert data == {"available": False, "scans": []}

    def test_scans_shape_bone_join_and_end_clip(self, tmp_bodyspec_db, client, monkeypatch):
        with _bodyspec_client(tmp_bodyspec_db, monkeypatch) as c:
            data = c.get(
                "/wellness/api/trends/health/composition?end=2026-07-09").json()

        assert data["available"] is True
        dates = [s["date"] for s in data["scans"]]
        assert dates == ["2026-02-10", "2026-05-18"]   # future scan clipped

        first = data["scans"][0]
        assert first["lean_kg"] == 55.12
        assert first["body_fat_pct"] == 32.4
        assert first["vat_kg"] == 1.45
        # Bone: the whole-body row only — regional rows must not multiply scans.
        assert first["bmd_total"] == 1.235
        assert first["t_score_total"] == 1.2

        second = data["scans"][1]
        assert second["bmd_total"] is None    # no bone rows → nulls, scan kept
        assert second["ag_ratio"] == 1.19

    def test_calendar_invalid_end_422(self, tmp_bodyspec_db, client, monkeypatch):
        with _bodyspec_client(tmp_bodyspec_db, monkeypatch) as c:
            assert c.get(
                "/wellness/api/trends/health/composition?end=2026-02-30"
            ).status_code == 422


@pytest.fixture
def tmp_questy_db(tmp_path):
    """A minimal Quest labs DB: a 3-observation chartable test (one lab-flagged
    H, one-sided range), a single-observation test, a qualitative (value NULL)
    result, a '<' detection-limit prefix, and a future report for end-clipping.
    Dates and values are INVENTED — never paste rows (or draw dates) from the
    real ~/.questy DB here; this repo is public."""
    db_path = tmp_path / "questy_fixture.db"
    conn = sqlite3.connect(db_path)
    conn.execute("""
        CREATE TABLE results (
            report_date DATE, panel_name TEXT, test_name TEXT, value REAL,
            value_text TEXT, value_prefix TEXT, unit TEXT, flag TEXT,
            is_calculated INTEGER, ref_range_low REAL, ref_range_high REAL,
            ref_range_text TEXT
        )
    """)
    conn.executemany(
        "INSERT INTO results VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", [
            ("2025-06-12", "LIPID PANEL", "LDL-CHOLESTEROL", 138.0, "138 H",
             None, "mg/dL", "H", 0, None, 100.0, "<100"),
            ("2025-12-03", "LIPID PANEL", "LDL-CHOLESTEROL", 118.0, "118 H",
             None, "mg/dL", "H", 0, None, 100.0, "<100"),
            ("2026-02-19", "LIPID PANEL", "LDL-CHOLESTEROL", 96.0, "96",
             None, "mg/dL", None, 0, None, 100.0, "<100"),
            ("2026-02-19", "LIPID PANEL", "HDL CHOLESTEROL", 58.0, "58",
             None, "mg/dL", None, 0, 40.0, None, "> OR = 40"),
            ("2026-02-19", "HS CRP", "HS CRP", 0.3, "<0.3",
             "<", "mg/L", None, 0, None, 1.0, "<1.0"),
            ("2026-02-19", "CULTURE", "GROWTH", None, "NOT DETECTED",
             None, None, None, 0, None, None, "NOT DETECTED"),
            ("2099-01-01", "LIPID PANEL", "LDL-CHOLESTEROL", 90.0, "90",
             None, "mg/dL", None, 0, None, 100.0, "<100"),  # future: clipped
        ])
    conn.commit()
    conn.close()
    return db_path


def _questy_client(questy_path, monkeypatch):
    monkeypatch.setenv("QUESTY_DB_PATH", str(questy_path))
    import server as server_mod
    from fastapi.testclient import TestClient
    return TestClient(server_mod.create_app())


@pytest.mark.integration
class TestLabsEndpoint:
    def test_unavailable_when_db_missing(self, client):
        resp = client.get("/api/trends/health/labs")
        assert resp.status_code == 200
        assert resp.json() == {"available": False, "panels": []}

    def test_unavailable_when_table_missing(self, client, tmp_path, monkeypatch):
        db_path = tmp_path / "questy_no_table.db"
        sqlite3.connect(db_path).close()
        with _questy_client(db_path, monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/labs").json()
        assert data == {"available": False, "panels": []}

    def test_grouping_flags_prefix_and_end_clip(self, tmp_questy_db, client, monkeypatch):
        with _questy_client(tmp_questy_db, monkeypatch) as c:
            data = c.get(
                "/wellness/api/trends/health/labs?end=2026-07-09").json()

        assert data["available"] is True
        panels = {p["name"]: p for p in data["panels"]}
        assert set(panels) == {"LIPID PANEL", "HS CRP", "CULTURE"}

        lipid = {t["name"]: t for t in panels["LIPID PANEL"]["tests"]}
        ldl = lipid["LDL-CHOLESTEROL"]
        assert ldl["unit"] == "mg/dL"
        dates = [o["date"] for o in ldl["observations"]]
        assert dates == ["2025-06-12", "2025-12-03", "2026-02-19"]  # future clipped
        assert ldl["observations"][0]["flag"] == "H"       # the LAB's call
        assert ldl["observations"][0]["ref_high"] == 100.0
        assert ldl["observations"][0]["ref_low"] is None   # one-sided range

        # Detection-limit prefix ships alongside the numeric value.
        crp = panels["HS CRP"]["tests"][0]["observations"][0]
        assert crp["value"] == 0.3 and crp["prefix"] == "<"

        # Qualitative result: value null, text carries the answer.
        growth = panels["CULTURE"]["tests"][0]["observations"][0]
        assert growth["value"] is None
        assert growth["text"] == "NOT DETECTED"

    def test_calendar_invalid_end_422(self, tmp_questy_db, client, monkeypatch):
        with _questy_client(tmp_questy_db, monkeypatch) as c:
            assert c.get(
                "/wellness/api/trends/health/labs?end=2026-13-05"
            ).status_code == 422
