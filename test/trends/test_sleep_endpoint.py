"""Integration tests for the trends health/sleep endpoint (sleep need/debt).

The fitted constants are personal data and live in a gitignored module, so
every test here injects its OWN invented params through the router's
resolution seam — nothing in this file depends on whether the real
src/modules/sleep_params.py exists on the machine running the suite.
"""

import importlib
import sqlite3
import sys
from dataclasses import dataclass
from datetime import date, timedelta

import pytest


# Structural twin of src/modules/sleep_params.example.py (that file's name is
# not importable). The values are INVENTED and chosen so every expectation
# below is hand-computable:
#     strain = 0.5*ln(1+active_cal) + 2.0*steps/1000 + 0.05*max_hr - 10
#              (or 0.002*active_cal + 1.0 when max_hr is NULL), clamped [0, 21]
#     f(s)   = 0.5*s^2 - 2*s          (the strain term added to need)
#     weq    = slept_min - 10         (bias applied to the debt math only)
#     debt'  = min(100, 0.4 * max(0, need - weq))
@dataclass(frozen=True)
class StrainCoeffs:
    log_ac: float
    steps_k: float
    max_hr: float
    intercept: float


@dataclass(frozen=True)
class StrainFallback:
    slope: float
    intercept: float


@dataclass(frozen=True)
class SleepParams:
    baseline_min: float
    debt_half_weight: float
    debt_cap_min: float
    strain_quad_a: float
    strain_quad_b: float
    sleep_bias_min: float
    strain_coeffs: StrainCoeffs
    strain_fallback: StrainFallback


_PARAMS = SleepParams(
    baseline_min=400.0,
    debt_half_weight=0.4,
    debt_cap_min=100.0,
    strain_quad_a=0.5,
    strain_quad_b=-2.0,
    sleep_bias_min=10.0,
    strain_coeffs=StrainCoeffs(
        log_ac=0.5, steps_k=2.0, max_hr=0.05, intercept=-10.0),
    strain_fallback=StrainFallback(slope=0.002, intercept=1.0),
)


@pytest.mark.unit
class TestSleepParamsSeam:
    """The params import itself — everything else here overrides the seam, so
    these two are what keep the real resolution path honest on a machine that
    HAS sleep_params.py and on one that doesn't."""

    def test_seam_returns_the_resolved_import(self):
        from modules import trends
        assert trends._get_sleep_params() is trends.SLEEP_PARAMS

    def test_absent_params_module_resolves_to_none(self, monkeypatch):
        # A fresh clone has no sleep_params.py (it is gitignored): the import
        # must degrade to None, not explode at router-construction time.
        # A None entry in sys.modules makes the import raise ImportError.
        from modules import trends
        monkeypatch.setitem(sys.modules, "modules.sleep_params", None)
        try:
            importlib.reload(trends)
            assert trends.SLEEP_PARAMS is None
            assert trends._get_sleep_params() is None
        finally:
            monkeypatch.undo()
            importlib.reload(trends)


def _iso(d):
    return d.isoformat()


def _write_db(path, rows, decoys=()):
    """rows: (metric_date, sleep_duration_hours, active_calories, total_steps,
    max_heart_rate), inserted as user_id 1; `decoys` are the same shape under
    user_id 2. The composite (user_id, metric_date) key mirrors the real
    Garmin table — the reader filters user_id, so the column must be here."""
    conn = sqlite3.connect(path)
    conn.execute("""
        CREATE TABLE daily_health_metrics (
            user_id INTEGER NOT NULL,
            metric_date DATE NOT NULL,
            sleep_duration_hours FLOAT,
            active_calories INTEGER,
            total_steps INTEGER,
            max_heart_rate INTEGER,
            PRIMARY KEY (user_id, metric_date)
        )
    """)
    conn.executemany(
        "INSERT INTO daily_health_metrics VALUES (1,?,?,?,?,?)", rows)
    # Decoy rows under user_id 2: the reader's WHERE user_id = 1 is what keeps
    # them out of every exact-JSON pin, so the filter is load-bearing here.
    conn.executemany(
        "INSERT INTO daily_health_metrics VALUES (2,?,?,?,?,?)", decoys)
    conn.commit()
    conn.close()
    return path


@pytest.fixture
def tmp_sleep_db(tmp_path):
    """Ten calendar days exercising every ledger branch: the epoch night, a
    shortfall→debt chain, a surplus that wipes the debt, the cap clamp, both
    strain clamps, the max-HR and fallback estimator paths, a MISSING day and
    a SLEEPLESS row (the two ways a gap arises) — plus user_id-2 decoy rows
    that the exact-JSON pin proves are filtered out.

    Values are INVENTED — never paste rows from the real ~/.garmy DB here;
    this repo is public."""
    today = date.today()

    def d(n):
        return _iso(today - timedelta(days=n))

    rows = [
        # (wake date, sleep h, active_cal, steps, max_hr)  -> that day's strain
        (d(9), 7.0, 0, 5000, 120),        # 10 + 6 - 10          = 6.0
        (d(8), 6.0, 0, 4000, 100),        #  8 + 5 - 10          = 3.0
        (d(7), 8.0, 0, 2000, 100),        #  4 + 5 - 10 = -1     -> 0.0 floor
        (d(6), 2.5, 20, 4000, 100),       # 0.5*ln(21) + 8 + 5 - 10 = 4.5222…
        (d(5), 7.0, 0, 3000, 110),        # (never a previous day: d(4) absent)
        # d(4): NO ROW AT ALL -> the next night's gap.
        (d(3), 7.5, 500, 0, None),        # fallback: 0.002*500 + 1 = 2.0
        (d(2), 6.5, 0, 0, None),          # fallback: 1.0
        (d(1), None, 999999, 20000, 200),  # sleepless row; strain 46.9 -> 21.0
        (d(0), 7.0, 0, 4000, 100),        # today: 3.0
    ]
    # user_id-2 decoys on loud values: one on the MISSING day (would erase the
    # d(3) gap if the filter broke) and one on today (would corrupt tonight).
    decoys = [
        (d(4), 8.0, 0, 9000, 150),
        (d(0), 1.0, 5000, 30000, 190),
    ]
    _write_db(tmp_path / "garmin_sleep.db", rows, decoys)
    return {"today": today, "path": tmp_path / "garmin_sleep.db"}


def _fresh_client(garmin_path, monkeypatch, params=_PARAMS):
    # The trends router resolves GARMIN_DB_PATH at create_app time; the base
    # conftest pins it to a nonexistent file, so re-point and build fresh.
    # The params seam is patched on the module, not the closure, so it also
    # applies to an already-built app.
    monkeypatch.setenv("GARMIN_DB_PATH", str(garmin_path))
    from modules import trends
    monkeypatch.setattr(trends, "_get_sleep_params", lambda: params)
    import server as server_mod
    from fastapi.testclient import TestClient
    return TestClient(server_mod.create_app())


@pytest.mark.integration
class TestSleepEndpoint:
    def test_unavailable_when_db_missing(self, client, monkeypatch):
        # Params present (so this can only be about the DB); conftest points
        # GARMIN_DB_PATH at a nonexistent file.
        from modules import trends
        monkeypatch.setattr(trends, "_get_sleep_params", lambda: _PARAMS)
        resp = client.get("/api/trends/health/sleep")
        assert resp.status_code == 200
        assert resp.json() == {"available": False, "days": []}

    def test_unavailable_when_table_missing(self, client, tmp_path, monkeypatch):
        # A Garmin DB without daily_health_metrics (schema drift / older sync
        # tool) degrades, never 500s.
        db_path = tmp_path / "garmin_no_table.db"
        sqlite3.connect(db_path).close()
        with _fresh_client(db_path, monkeypatch) as c:
            resp = c.get("/wellness/api/trends/health/sleep")
        assert resp.status_code == 200
        assert resp.json() == {"available": False, "days": []}

    def test_unavailable_when_params_missing(self, tmp_sleep_db, client, monkeypatch):
        # Full history present, no fitted constants on this machine: the
        # params gate wins and the example values are never substituted.
        with _fresh_client(tmp_sleep_db["path"], monkeypatch, params=None) as c:
            resp = c.get("/wellness/api/trends/health/sleep")
        assert resp.status_code == 200
        assert resp.json() == {"available": False, "days": []}

    def test_exact_json_ledger(self, tmp_sleep_db, client, monkeypatch):
        today = tmp_sleep_db["today"]

        def d(n):
            return _iso(today - timedelta(days=n))

        with _fresh_client(tmp_sleep_db["path"], monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        assert data == {
            "available": True,
            "as_of": d(0),
            # Tonight's carry comes out of today's row: need 578.5 vs weq 410
            # -> 0.4*168.5 = 67.4; need = 400 + 67.4 + f(3.0) = 465.9.
            "tonight": {"date": d(0), "need_min": 465.9, "debt_min": 67.4,
                        "strain_est": 3.0, "strain_partial": True},
            "days": [
                # Epoch: nothing to carry from, and NO gap key for it.
                # need = 400 + 0 + f(0.0) = 400.0; weq 410 -> no debt.
                {"date": d(9), "need_min": 400.0, "slept_min": 420.0,
                 "debt_min": 0.0, "strain_est": 0.0},
                # f(6.0) = 18 - 12 = +6 -> 406.0; weq 350 -> 0.4*56 = 22.4.
                {"date": d(8), "need_min": 406.0, "slept_min": 360.0,
                 "debt_min": 0.0, "strain_est": 6.0},
                # f(3.0) = 4.5 - 6 = -1.5 (the term is NOT floored at 0):
                # 400 + 22.4 - 1.5 = 420.9. Surplus (weq 470) -> debt 0.
                {"date": d(7), "need_min": 420.9, "slept_min": 480.0,
                 "debt_min": 22.4, "strain_est": 3.0},
                # 2.5 h: weq 140 -> 0.4*260 = 104, clamped to the 100 cap.
                {"date": d(6), "need_min": 400.0, "slept_min": 150.0,
                 "debt_min": 0.0, "strain_est": 0.0},
                # Cap visible; strain_est shows 4.5 but the ledger chains on
                # the UNROUNDED 4.5222…: f(4.5222…) = +1.1809 -> 501.2
                # (rounding first would have produced 501.1).
                {"date": d(5), "need_min": 501.2, "slept_min": 420.0,
                 "debt_min": 100.0, "strain_est": 4.5},
                # Missing calendar day: debt resets (36.47 carried out of the
                # previous night is discarded) and the row is flagged.
                {"date": d(3), "need_min": 400.0, "slept_min": 450.0,
                 "debt_min": 0.0, "strain_est": 0.0, "gap": True},
                # Fallback estimator (max_hr NULL): f(2.0) = 2 - 4 = -2.0.
                {"date": d(2), "need_min": 398.0, "slept_min": 390.0,
                 "debt_min": 0.0, "strain_est": 2.0},
                # Previous row exists but scored no sleep -> same gap rule.
                # Strain clamps at 21: f(21) = 220.5 - 42 = +178.5.
                {"date": d(0), "need_min": 578.5, "slept_min": 420.0,
                 "debt_min": 0.0, "strain_est": 21.0, "gap": True},
            ],
        }

    def test_gap_key_omitted_not_false(self, tmp_sleep_db, client, monkeypatch):
        today = tmp_sleep_db["today"]

        def d(n):
            return _iso(today - timedelta(days=n))

        with _fresh_client(tmp_sleep_db["path"], monkeypatch) as c:
            days = {r["date"]: r for r in
                    c.get("/wellness/api/trends/health/sleep").json()["days"]}

        # Optional fields are OMITTED, never null/false — the clients decode
        # `gap` with a default.
        for n in (9, 8, 7, 6, 5, 2):
            assert "gap" not in days[d(n)], f"day -{n} should carry no gap key"
        # Both ways a gap arises, and both reset the ledger.
        for n in (3, 0):
            assert days[d(n)]["gap"] is True
            assert days[d(n)]["debt_min"] == 0.0

    def test_range_clip_leaves_ledger_intact(self, tmp_sleep_db, client, monkeypatch):
        today = tmp_sleep_db["today"]

        def d(n):
            return _iso(today - timedelta(days=n))

        with _fresh_client(tmp_sleep_db["path"], monkeypatch) as c:
            full = c.get("/wellness/api/trends/health/sleep").json()
            data = c.get(
                f"/wellness/api/trends/health/sleep?start={d(5)}&end={d(2)}"
            ).json()

        # Clipped at both ends (d(4) has no row at all).
        assert [r["date"] for r in data["days"]] == [d(5), d(3), d(2)]
        # The ledger still ran from history's start: this debt was earned by
        # the 2.5 h night BEFORE the requested window.
        assert data["days"][0]["debt_min"] == 100.0
        assert data["days"][0]["need_min"] == 501.2
        # Freshness and tonight ignore the range entirely.
        assert data["as_of"] == full["as_of"] == d(0)
        assert data["tonight"] == full["tonight"]

    def test_tonight_when_today_row_is_sleepless(self, tmp_path, client, monkeypatch):
        """Today synced activity but no sleep yet: tonight CARRIES yesterday's
        outgoing debt (the previous ledger position — last night simply isn't
        scored yet), strain comes from today's own row, and as_of stays on the
        last night that actually scored. Values are INVENTED — never paste
        rows from the real ~/.garmy DB here; this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        path = _write_db(tmp_path / "garmin_sleepless_today.db", [
            (d(1), 6.0, 0, 4000, 100),        # epoch: need 400, weq 350 -> out 20.0
            (d(0), None, 0, 5000, 120),       # sleepless; its strain 6.0 stands
        ])
        with _fresh_client(path, monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        assert data["as_of"] == d(1)
        assert [r["date"] for r in data["days"]] == [d(1)]
        # carry 0.4 * (400 - 350) = 20.0; need = 400 + 20.0 + f(6.0) = 426.0
        assert data["tonight"] == {"date": d(0), "need_min": 426.0,
                                   "debt_min": 20.0, "strain_est": 6.0,
                                   "strain_partial": True}

    def test_tonight_when_today_row_is_missing(self, tmp_path, client, monkeypatch):
        """Sync hasn't run today at all — the after-midnight / stale-morning
        case: tonight still reports the previous ledger position (yesterday's
        outgoing debt), with no strain estimate for a day that has no row.
        Values are INVENTED — never paste rows from the real ~/.garmy DB here;
        this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        path = _write_db(tmp_path / "garmin_stale.db", [
            (d(2), 7.0, 0, 5000, 120),        # epoch: surplus night, out 0
            (d(1), 6.0, 0, 4000, 100),        # need 406 (f(6.0)), weq 350 -> out 22.4
        ])
        with _fresh_client(path, monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        assert data["as_of"] == d(1)      # the lag is the freshness signal
        # carry 0.4 * (406 - 350) = 22.4; need = 400 + 22.4 + f(0) = 422.4
        assert data["tonight"] == {"date": d(0), "need_min": 422.4,
                                   "debt_min": 22.4, "strain_est": 0.0,
                                   "strain_partial": True}

    def test_tonight_resets_after_an_older_gap(self, tmp_path, client, monkeypatch):
        """The last scored night is older than yesterday: that is a gap, and
        tonight resets to 0 exactly as the ledger itself would — a days-old
        debt is not tonight's. Values are INVENTED — never paste rows from the
        real ~/.garmy DB here; this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        path = _write_db(tmp_path / "garmin_old_gap.db", [
            (d(3), 6.0, 0, 4000, 100),        # out 20.0 — too old to carry
        ])
        with _fresh_client(path, monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        assert data["as_of"] == d(3)
        assert data["tonight"] == {"date": d(0), "need_min": 400.0,
                                   "debt_min": 0.0, "strain_est": 0.0,
                                   "strain_partial": True}

    def test_available_with_no_scored_nights_omits_as_of(
            self, tmp_path, client, monkeypatch):
        """Rows exist but Garmin scored no sleep on any of them: the source is
        readable, so this is available with an empty ledger — and as_of is
        OMITTED rather than null. Values are INVENTED — never paste rows from
        the real ~/.garmy DB here; this repo is public."""
        today = date.today()
        path = _write_db(tmp_path / "garmin_no_sleep.db", [
            (_iso(today - timedelta(days=1)), None, 0, 4000, 100),
            (_iso(today), None, 0, 4000, 100),
        ])
        with _fresh_client(path, monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        assert data["available"] is True
        assert data["days"] == []
        assert "as_of" not in data
        assert data["tonight"]["debt_min"] == 0.0
        assert data["tonight"]["strain_est"] == 3.0

    def test_calendar_invalid_date_422(self, tmp_sleep_db, client, monkeypatch):
        with _fresh_client(tmp_sleep_db["path"], monkeypatch) as c:
            assert c.get(
                "/wellness/api/trends/health/sleep?start=2026-02-30"
            ).status_code == 422
