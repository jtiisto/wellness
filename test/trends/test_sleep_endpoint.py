"""Integration tests for the trends health/sleep endpoint (sleep need/debt).

The fitted constants are personal data and live in a gitignored module, so
every test here injects its OWN invented params through the router's
resolution seam — nothing in this file depends on whether the real
src/modules/sleep_params.py exists on the machine running the suite.
"""

import importlib
import json
import math
import shutil
import sqlite3
import sys
from dataclasses import dataclass, fields, replace
from datetime import date, datetime, time, timedelta, timezone
from types import SimpleNamespace
from zoneinfo import ZoneInfo

import pytest

from modules.device_clock import ZoneTimeline

from .conftest import zone_on_another_date as _zone_on_another_date


# Structural twin of src/modules/sleep_params.example.py (that file's name is
# not importable). The values are INVENTED and chosen so every expectation
# below is hand-computable:
#     strain = tier 1 (hybrid, see below) when the day's HR stream can carry it,
#              else 0.5*ln(1+active_cal) + 2.0*steps/1000 + 0.05*max_hr - 10,
#              else 0.002*active_cal + 1.0 (max_hr NULL), clamped [0, 21]
#     f(s)   = 0.5*s^2 - 2*s          (the strain term added to need)
#     weq    = slept_min - 10         (bias applied to the debt math only)
#     debt'  = min(100, 0.4 * max(0, need - weq))
# `debt'` is the debt on WAKING from that night: the value the row EMITS as
# `debt_min`, and the debt ENTERING the next night (so a row's `need` is
# 400 + the PREVIOUS row's debt_min + f(strain), on consecutive rows).
#
# Tier 1 (hybrid) is TRIMP over the day's heart-rate evidence:
#     banister(hr, mins) = mins * hrr * 0.64 * exp(1.92*hrr),
#                          hrr = (hr - rest) / (200 - rest), 0 when hrr <= 0
#     rest    = that date's resting_heart_rate, else 60
#     trimp_out = sum over wrist samples OUTSIDE the day's activity windows,
#                 each worth the day's median sample gap in minutes ([0.5, 5])
#     trimp_act = sum over the day's activities of banister(avg_hr, duration)
#     strain    = 1.0 + 0.25*trimp_out + 1.2*trimp_act, clamped [0, 21]
# hr_max 200 with rest 50 makes the reserve 150, so the sample HRs below give
# round hrr fractions (110 -> 0.4, 125 -> 0.5, 95 -> 0.3, 140 -> 0.6).
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
class HybridStrainCoeffs:
    ban_out: float
    ban_act: float
    intercept: float
    hr_max: float
    rest_hr_fallback: float
    min_samples: int


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
    hybrid: HybridStrainCoeffs


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
    hybrid=HybridStrainCoeffs(
        ban_out=0.25, ban_act=1.2, intercept=1.0,
        hr_max=200.0, rest_hr_fallback=60.0, min_samples=3),
)


def _banister(hr, rest, minutes, hr_max=200.0):
    """The estimator's TRIMP kernel, re-derived here from the formula in the
    comment above rather than imported — a test that called the production
    helper would pin nothing. Used only to spell out the pinned literals."""
    hrr = (hr - rest) / (hr_max - rest)
    return minutes * hrr * 0.64 * math.exp(1.92 * hrr) if hrr > 0 else 0.0


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


def _write_db(path, rows, decoys=(), *, rest_hr=None, naps=None,
              nap_column=True,
              timeseries=None, ts_decoys=(), activities=None, act_decoys=()):
    """rows: (metric_date, sleep_duration_hours, active_calories, total_steps,
    max_heart_rate), inserted as user_id 1; `decoys` are the same shape under
    user_id 2. The composite (user_id, metric_date) key mirrors the real
    Garmin table — the reader filters user_id, so the column must be here.

    `rest_hr` is {metric_date: bpm} for the daily table's resting_heart_rate
    (the hybrid strain tier's per-day HR reserve floor); rows left out keep the
    column NULL, which is what the estimator's own fallback is for.

    `naps` is {metric_date: nap_duration_hours} the same way; a date left out
    keeps the column NULL, which is a day Garmin has not resynced since nap
    support — zero credit, and the shape most days arrive in. `nap_column=False`
    omits the column from the schema entirely: that is every Garmin DB older
    than nap support, and the reader must probe rather than assume it.

    `timeseries` and `activities` feed the hybrid tier and default to NONE —
    meaning the TABLE IS NOT CREATED. That is the shape of every Garmin DB
    older than these tables, and the tier-2 pins throughout this file depend on
    it: the estimator must drop a tier, never take the endpoint down with it.
    Pass a list (even an empty one) to create the table.
        timeseries: (metric_type, timestamp_ms, value) under user_id 1
        activities: (activity_id, activity_date, start_time_iso,
                     duration_seconds, avg_heart_rate) under user_id 1
    `ts_decoys`/`act_decoys` are the same shapes under user_id 2 — both readers
    pin user_id = 1, and these are what make that filter load-bearing.
    """
    assert nap_column or not naps, "naps need the column they live in"
    conn = sqlite3.connect(path)
    conn.execute("""
        CREATE TABLE daily_health_metrics (
            user_id INTEGER NOT NULL,
            metric_date DATE NOT NULL,
            sleep_duration_hours FLOAT,
            active_calories INTEGER,
            total_steps INTEGER,
            max_heart_rate INTEGER,
            resting_heart_rate INTEGER,
            %s
            PRIMARY KEY (user_id, metric_date)
        )
    """ % ("nap_duration_hours FLOAT," if nap_column else ""))
    insert = ("INSERT INTO daily_health_metrics "
              "(user_id, metric_date, sleep_duration_hours, active_calories, "
              "total_steps, max_heart_rate) VALUES (%d,?,?,?,?,?)")
    conn.executemany(insert % 1, rows)
    # Decoy rows under user_id 2: the reader's WHERE user_id = 1 is what keeps
    # them out of every exact-JSON pin, so the filter is load-bearing here.
    conn.executemany(insert % 2, decoys)
    for metric_date, bpm in (rest_hr or {}).items():
        conn.execute(
            "UPDATE daily_health_metrics SET resting_heart_rate = ? "
            "WHERE user_id = 1 AND metric_date = ?", (bpm, metric_date))
    for metric_date, hours in (naps or {}).items():
        conn.execute(
            "UPDATE daily_health_metrics SET nap_duration_hours = ? "
            "WHERE user_id = 1 AND metric_date = ?", (hours, metric_date))

    if timeseries is not None:
        conn.execute("""
            CREATE TABLE timeseries (
                user_id INTEGER NOT NULL,
                metric_type VARCHAR NOT NULL,
                timestamp INTEGER NOT NULL,
                value FLOAT NOT NULL,
                meta_data JSON,
                PRIMARY KEY (user_id, metric_type, timestamp)
            )
        """)
        ts_insert = ("INSERT INTO timeseries "
                     "(user_id, metric_type, timestamp, value) "
                     "VALUES (%d,?,?,?)")
        conn.executemany(ts_insert % 1, timeseries)
        conn.executemany(ts_insert % 2, ts_decoys)
    if activities is not None:
        conn.execute("""
            CREATE TABLE activities (
                user_id INTEGER NOT NULL,
                activity_id VARCHAR NOT NULL,
                activity_date DATE NOT NULL,
                start_time VARCHAR,
                duration_seconds INTEGER,
                avg_heart_rate INTEGER,
                PRIMARY KEY (user_id, activity_id)
            )
        """)
        act_insert = ("INSERT INTO activities (user_id, activity_id, "
                      "activity_date, start_time, duration_seconds, "
                      "avg_heart_rate) VALUES (%d,?,?,?,?,?)")
        conn.executemany(act_insert % 1, activities)
        conn.executemany(act_insert % 2, act_decoys)
    conn.commit()
    conn.close()
    return path


def _ms(day, hh, mm):
    """Epoch milliseconds at LOCAL hh:mm on `day`. The reader buckets samples
    by local calendar date (`date(timestamp/1000,'unixepoch','localtime')`),
    so the fixture has to speak local time too — a UTC-built timestamp would
    land on the wrong day for most of the world."""
    return int(datetime.combine(day, time(hh, mm)).timestamp() * 1000)


def _iso_at(day, hh, mm):
    """An activity `start_time` the way the sync tool writes it: a local ISO
    datetime string with no offset."""
    return datetime.combine(day, time(hh, mm)).isoformat()


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

        # Every `debt_min` below is the debt ON WAKING from that night — the
        # night's own product, min(100, 0.4 * max(0, need - weq)). The debt
        # ENTERING a night is therefore the PREVIOUS row's debt_min (when the
        # rows are consecutive), and each `need` is written out as
        # 400 + <that entering debt> + f(previous day's strain).
        assert data == {
            "available": True,
            "as_of": d(0),
            # Tonight's carry comes out of today's row and equals what that row
            # emits: need 578.5 vs weq 410 -> 0.4*168.5 = 67.4; tonight's own
            # need = 400 + 67.4 + f(3.0) = 465.9.
            "tonight": {"date": d(0), "need_min": 465.9, "debt_min": 67.4,
                        "strain_est": 3.0, "strain_partial": True},
            "days": [
                # Epoch: nothing to carry from, and NO gap key for it.
                # need = 400 + 0 + f(0.0) = 400.0; weq 410 -> a surplus night,
                # so it wakes owing nothing.
                {"date": d(9), "need_min": 400.0, "slept_min": 420.0,
                 "debt_min": 0.0, "strain_est": 0.0},
                # f(6.0) = 18 - 12 = +6 -> need 400 + 0 + 6 = 406.0;
                # weq 350 -> woke owing 0.4*56 = 22.4.
                {"date": d(8), "need_min": 406.0, "slept_min": 360.0,
                 "debt_min": 22.4, "strain_est": 6.0},
                # Entering 22.4 (the row above) and f(3.0) = 4.5 - 6 = -1.5
                # (the term is NOT floored at 0): 400 + 22.4 - 1.5 = 420.9.
                # Surplus (weq 470) -> the debt is paid off overnight.
                {"date": d(7), "need_min": 420.9, "slept_min": 480.0,
                 "debt_min": 0.0, "strain_est": 3.0},
                # 2.5 h against a 400.0 need: weq 140 -> 0.4*260 = 104,
                # clamped to the 100 cap. The cap now shows on the night that
                # EARNED it rather than on the morning after.
                {"date": d(6), "need_min": 400.0, "slept_min": 150.0,
                 "debt_min": 100.0, "strain_est": 0.0},
                # Entering the capped 100; strain_est shows 4.5 but the ledger
                # chains on the UNROUNDED 4.5222…: f(4.5222…) = +1.1809 ->
                # 501.2 (rounding first would have produced 501.1). weq 410 ->
                # woke owing 0.4*91.1809 = 36.47…, emitted 36.5.
                {"date": d(5), "need_min": 501.2, "slept_min": 420.0,
                 "debt_min": 36.5, "strain_est": 4.5},
                # Missing calendar day: the ENTERING debt resets (the 36.47
                # carried out of the previous night is discarded, which is why
                # need drops to baseline) and the row is flagged. This night's
                # own product is zero on its merits — weq 440 beats the 400.0
                # need — not because of the flag; see the d(0) row.
                {"date": d(3), "need_min": 400.0, "slept_min": 450.0,
                 "debt_min": 0.0, "strain_est": 0.0, "gap": True},
                # Fallback estimator (max_hr NULL): f(2.0) = 2 - 4 = -2.0, so
                # need = 400 + 0 - 2 = 398.0; weq 380 -> 0.4*18 = 7.2.
                {"date": d(2), "need_min": 398.0, "slept_min": 390.0,
                 "debt_min": 7.2, "strain_est": 2.0},
                # Previous row exists but scored no sleep -> same gap rule.
                # Strain clamps at 21: f(21) = 220.5 - 42 = +178.5, need 578.5;
                # weq 410 -> woke owing 67.4. A flagged night with a NONZERO
                # debt: the reset is on the way in, never on the way out.
                {"date": d(0), "need_min": 578.5, "slept_min": 420.0,
                 "debt_min": 67.4, "strain_est": 21.0, "gap": True},
            ],
        }
        # The coherence the flip exists for: today's row is the last one, so
        # the card and the chart's last point are the same number.
        assert data["tonight"]["debt_min"] == data["days"][-1]["debt_min"]

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
        # Both ways a gap arises — a missing calendar day and a row Garmin
        # scored no sleep for — and both reset the debt ENTERING the night,
        # which is why each need falls back to 400 + f(strain).
        for n in (3, 0):
            assert days[d(n)]["gap"] is True
        assert days[d(3)]["need_min"] == 400.0
        assert days[d(0)]["need_min"] == 578.5   # 400 + 0 + f(21.0)
        # The flag no longer implies a zero debt: `debt_min` is what the night
        # left BEHIND, and this one woke owing 0.4*(578.5 - 410) = 67.4. A
        # client dimming or hiding a flagged row's value would drop a real
        # number. Its sibling gap is zero only on its own merits (weq 440
        # against a 400.0 need), which is the contrast worth pinning.
        assert days[d(0)]["debt_min"] == 67.4
        assert days[d(3)]["debt_min"] == 0.0

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
        # The ledger still ran from history's start: the capped 100 earned by
        # the 2.5 h night BEFORE the requested window is inside this need
        # (400 + 100 + f(4.5222…)), even though the night that earned it was
        # clipped away. The row's own debt_min is what IT left behind.
        assert data["days"][0]["need_min"] == 501.2
        assert data["days"][0]["debt_min"] == 36.5
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
        # Yesterday is inside the carry window, so the card's number IS the
        # last plotted point: 20.0 is what that night woke owing.
        assert data["days"][-1]["debt_min"] == 20.0
        assert data["tonight"]["debt_min"] == data["days"][-1]["debt_min"]

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
        # Still inside the carry window (yesterday), so card and last point
        # agree — the epoch night before it woke owing nothing (weq 410 beat
        # its 400.0 need), and the chain shows it.
        assert [r["debt_min"] for r in data["days"]] == [0.0, 22.4]
        assert data["tonight"]["debt_min"] == data["days"][-1]["debt_min"]

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
        # The ONE carry-window case where the card and the chart's last point
        # disagree, and they must: that night really did wake owing
        # 0.4 * (400 - 350) = 20.0 — the row keeps it — but three days later
        # it is not tonight's debt.
        assert data["days"][-1]["debt_min"] == 20.0
        assert data["tonight"]["debt_min"] != data["days"][-1]["debt_min"]

    def test_clipped_end_hides_rows_tonight_still_carries(
            self, tmp_path, client, monkeypatch):
        """The card/last-point coherence holds only on end=today requests (the
        app's only shape): a clipped `end` hides newer rows from `days` while
        `tonight` — which ignores the range by design — still carries them.
        This divergence is deliberate contract, pinned so it can't silently
        become a bug report. Values are INVENTED — never paste rows from the
        real ~/.garmy DB here; this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        path = _write_db(tmp_path / "garmin_clipped.db", [
            (d(1), 6.0, 0, 4000, 100),        # epoch: need 400, out 20.0
            (d(0), 6.0, 0, 4000, 100),        # need 418.5 (f(3.0)), out 27.4
        ])
        with _fresh_client(path, monkeypatch) as c:
            data = c.get(
                f"/wellness/api/trends/health/sleep?end={d(1)}").json()

        assert [r["date"] for r in data["days"]] == [d(1)]
        assert data["days"][-1]["debt_min"] == 20.0
        # tonight carries TODAY'S outgoing (27.4), not the clipped last row's —
        # and as_of reads the full history past the clip too.
        assert data["tonight"]["debt_min"] == 27.4
        assert data["tonight"]["need_min"] == 425.9
        assert data["as_of"] == d(0)

    def test_ledger_chains_on_unrounded_values(self, tmp_path, client, monkeypatch):
        """Emission rounds to 1 dp; the chain must not. Night one's outgoing is
        22.34 (emitted 22.3); chaining the NEXT night on the unrounded value
        lands its outgoing at 17.755 -> emitted 17.8, while a rounded chain
        (400 + 22.3 - 1.5 = 420.8 -> 0.4 * 44.3475) would emit 17.7 — this pin
        fails if rounding ever creeps into the ledger. Values are INVENTED —
        never paste rows from the real ~/.garmy DB here; this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        path = _write_db(tmp_path / "garmin_unrounded.db", [
            # fallback-path days (max_hr NULL): strain 1.0, f(1.0) = -1.5
            (d(2), 5.9025, 0, 0, None),       # need 400, weq 344.15 -> out 22.34
            (d(1), 6.440875, 0, 0, None),     # need 420.84, weq 376.4525 -> out 17.755
        ])
        with _fresh_client(path, monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        assert data["days"] == [
            {"date": d(2), "need_min": 400.0, "slept_min": 354.1,
             "debt_min": 22.3, "strain_est": 0.0},
            {"date": d(1), "need_min": 420.8, "slept_min": 386.5,
             "debt_min": 17.8, "strain_est": 1.0},
        ]
        # carry window: tonight equals the last row, on the unrounded chain.
        assert data["tonight"] == {"date": d(0), "need_min": 417.8,
                                   "debt_min": 17.8, "strain_est": 0.0,
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


@pytest.fixture
def tmp_nap_db(tmp_path):
    """Four consecutive nights over the three shapes the nap column arrives in:
    a real nap, a NULL (a day Garmin has not resynced since nap support) and a
    0.0 (resynced, no naps) — plus a nap on TODAY, which belongs to tonight.
    Every row takes the calorie fallback estimator (max_heart_rate NULL, zero
    active calories → strain 1.0, f(1.0) = -1.5) so the nap term is the only
    moving part between one need and the next.

    Values are INVENTED — never paste rows from the real ~/.garmy DB here;
    this repo is public."""
    today = date.today()

    def d(n):
        return _iso(today - timedelta(days=n))

    rows = [
        (d(3), 6.0, 0, 0, None),
        (d(2), 5.5, 0, 0, None),
        (d(1), 6.5, 0, 0, None),
        (d(0), 7.0, 0, 0, None),
    ]
    _write_db(tmp_path / "garmin_naps.db", rows,
              naps={d(3): 0.75,        # 45 min, credited to the d(2) night
                    d(1): 0.0,         # resynced, no naps
                    d(0): 0.5})        # 30 min, today's — credited to tonight
    #    d(2) is left NULL on purpose: not resynced, and zero credit.
    return {"today": today, "path": tmp_path / "garmin_naps.db"}


@pytest.mark.integration
class TestSleepNaps:
    """The nap term: a day's naps are sleep already taken against the night
    that ENDS the next morning, so nap(X) is subtracted from the need of the
    row keyed X+1 — at full weight, with no bias, cap or floor."""

    def test_exact_json_ledger_with_naps(self, tmp_nap_db, client, monkeypatch):
        today = tmp_nap_db["today"]

        def d(n):
            return _iso(today - timedelta(days=n))

        with _fresh_client(tmp_nap_db["path"], monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        assert data == {
            "available": True,
            "as_of": d(0),
            # Today's 30 min are spent against TONIGHT:
            # need = 400 + 1.784 + f(1.0) - 30 = 370.284.
            "tonight": {"date": d(0), "need_min": 370.3, "debt_min": 1.8,
                        "strain_est": 1.0, "strain_partial": True,
                        "nap_min": 30.0},
            "days": [
                # Epoch. The day before it has no row at all, so neither a
                # strain term nor a nap: need 400.0, weq 350 -> 0.4*50 = 20.0.
                {"date": d(3), "need_min": 400.0, "slept_min": 360.0,
                 "debt_min": 20.0, "strain_est": 0.0},
                # The napped night: 400 + 20.0 + f(1.0) would be 418.5, and the
                # 45 min napped the previous day come straight off it -> 373.5.
                # The debt chain sees the REDUCED need: weq 320 -> 0.4*53.5 =
                # 21.4, where the un-napped need would have left 39.4 behind.
                {"date": d(2), "need_min": 373.5, "slept_min": 330.0,
                 "debt_min": 21.4, "strain_est": 1.0, "nap_min": 45.0},
                # Previous day's nap column is NULL: zero credit, no key.
                # 400 + 21.4 - 1.5 = 419.9; weq 380 -> 0.4*39.9 = 15.96.
                {"date": d(1), "need_min": 419.9, "slept_min": 390.0,
                 "debt_min": 16.0, "strain_est": 1.0},
                # Previous day was resynced with NO naps (0.0): same zero
                # credit, same absent key. Chains on the unrounded 15.96:
                # 400 + 15.96 - 1.5 = 414.46; weq 410 -> 0.4*4.46 = 1.784.
                {"date": d(0), "need_min": 414.5, "slept_min": 420.0,
                 "debt_min": 1.8, "strain_est": 1.0},
            ],
        }

    def test_nap_key_is_omitted_never_zero(self, tmp_nap_db, client, monkeypatch):
        today = tmp_nap_db["today"]

        def d(n):
            return _iso(today - timedelta(days=n))

        with _fresh_client(tmp_nap_db["path"], monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()
        days = {r["date"]: r for r in data["days"]}

        # Optional fields are OMITTED, never null/zero — the clients decode
        # `nap_min` with a default. Only the night that was actually credited
        # carries it, and the NULL day and the 0.0 day are indistinguishable
        # from the outside, which is the point: both are zero credit.
        assert days[d(2)]["nap_min"] == 45.0
        for n in (3, 1, 0):
            assert "nap_min" not in days[d(n)], f"day -{n} was credited nothing"
        # Neither zero-credit night lost a minute of need to a nap: each is
        # exactly 400 + the previous row's debt + f(1.0).
        assert days[d(1)]["need_min"] == 419.9
        assert days[d(0)]["need_min"] == 414.5

    def test_todays_nap_lands_on_tonight_not_on_todays_row(
            self, tmp_path, client, monkeypatch):
        """A nap taken today pays for tonight, so it moves `tonight` and leaves
        today's ROW — the night that ended this morning — untouched. Values are
        INVENTED — never paste rows from the real ~/.garmy DB here; this repo
        is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        path = _write_db(tmp_path / "garmin_nap_tonight.db", [
            (d(1), 6.0, 0, 0, None),      # epoch: need 400, weq 350 -> out 20.0
            (d(0), 7.0, 0, 0, None),      # need 418.5, weq 410 -> out 3.4
        ], naps={d(0): 0.5})              # 30 min, today's
        with _fresh_client(path, monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        assert data["days"][-1] == {"date": d(0), "need_min": 418.5,
                                    "slept_min": 420.0, "debt_min": 3.4,
                                    "strain_est": 1.0}
        # 400 + 3.4 + f(1.0) - 30 = 371.9.
        assert data["tonight"] == {"date": d(0), "need_min": 371.9,
                                   "debt_min": 3.4, "strain_est": 1.0,
                                   "strain_partial": True, "nap_min": 30.0}

    def test_nap_has_no_floor_and_no_cap(self, tmp_path, client, monkeypatch):
        """A fragmented day can file hours of "naps" and pull the next night's
        need well below baseline — WHOOP's own behaviour, and the reason the
        term carries no clamp. Values are INVENTED — never paste rows from the
        real ~/.garmy DB here; this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        path = _write_db(tmp_path / "garmin_nap_big.db", [
            (d(2), 6.0, 0, 0, None),      # epoch: need 400, weq 350 -> out 20.0
            (d(1), 7.0, 0, 0, None),
        ], naps={d(2): 3.0})              # 180 min against the next night
        with _fresh_client(path, monkeypatch) as c:
            days = c.get("/wellness/api/trends/health/sleep").json()["days"]

        # 400 + 20.0 + f(1.0) - 180 = 238.5 — under the 400 baseline, uncapped.
        assert days[-1] == {"date": d(1), "need_min": 238.5, "slept_min": 420.0,
                            "debt_min": 0.0, "strain_est": 1.0,
                            "nap_min": 180.0}

    def test_ledger_chains_on_the_unrounded_nap(self, tmp_path, client, monkeypatch):
        """`nap_min` is presentational (1 dp); the need it buys is not. The
        credited 45.75 min takes the need to 372.75 -> 372.8, where subtracting
        the ROUNDED 45.8 would have emitted 372.7 — this pin fails if rounding
        creeps into the arithmetic. Values are INVENTED — never paste rows from
        the real ~/.garmy DB here; this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        path = _write_db(tmp_path / "garmin_nap_rounding.db", [
            (d(2), 6.0, 0, 0, None),      # epoch: need 400, weq 350 -> out 20.0
            (d(1), 5.0, 0, 0, None),      # weq 290 -> 0.4 * 82.75 = 33.1
        ], naps={d(2): 0.7625})           # 45.75 min exactly
        with _fresh_client(path, monkeypatch) as c:
            days = c.get("/wellness/api/trends/health/sleep").json()["days"]

        assert days[-1] == {"date": d(1), "need_min": 372.8, "slept_min": 300.0,
                            "debt_min": 33.1, "strain_est": 1.0,
                            "nap_min": 45.8}

    def test_sub_resolution_nap_credits_the_need_but_emits_no_key(
            self, tmp_path, client, monkeypatch):
        """A nap too short to survive 1 dp — 0.0008 h, under three seconds —
        is spent by the need in full and emits NO key: the contract's optional
        fields are omitted, and a rounded-to-0.0 `nap_min` would be a zero on
        the wire. The need is the proof it was credited: 442.26 without it,
        442.212 with. Values are INVENTED — never paste rows from the real
        ~/.garmy DB here; this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        path = _write_db(tmp_path / "garmin_nap_tiny.db", [
            (d(2), 5.01, 0, 0, None),     # epoch: need 400, weq 290.6 -> 43.76
            (d(1), 7.0, 0, 0, None),      # weq 410 -> 0.4 * 32.212 = 12.8848
        ], naps={d(2): 0.0008})           # 0.048 min, credited to the d(1) night
        with _fresh_client(path, monkeypatch) as c:
            days = c.get("/wellness/api/trends/health/sleep").json()["days"]

        # 400 + 43.76 + f(1.0) - 0.048 = 442.212; the un-credited need would
        # have emitted 442.3, so the row is a pin on both halves of the rule.
        assert days[-1] == {"date": d(1), "need_min": 442.2, "slept_min": 420.0,
                            "debt_min": 12.9, "strain_est": 1.0}

    def test_sub_resolution_nap_today_credits_tonight_but_emits_no_key(
            self, tmp_path, client, monkeypatch):
        """The same rule on `tonight`: today's 0.048 min move the need and
        leave no `nap_min` behind. Values are INVENTED — never paste rows from
        the real ~/.garmy DB here; this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        path = _write_db(tmp_path / "garmin_nap_tiny_tonight.db", [
            (d(1), 7.0, 0, 0, None),      # epoch: need 400, weq 410 -> out 0.0
            (d(0), 5.01, 0, 0, None),     # need 398.5, weq 290.6 -> out 43.16
        ], naps={d(0): 0.0008})
        with _fresh_client(path, monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        # 400 + 43.16 + f(1.0) - 0.048 = 441.612; without the credit, 441.7.
        assert data["tonight"] == {"date": d(0), "need_min": 441.6,
                                   "debt_min": 43.2, "strain_est": 1.0,
                                   "strain_partial": True}

    def test_db_without_the_nap_column_serves_the_same_ledger(
            self, tmp_path, client, monkeypatch):
        """Every Garmin DB older than nap support: the column is probed, not
        assumed, so its absence costs the ledger nothing — the response is the
        one the same rows produce WITH the column and no nap values in it.
        Values are INVENTED — never paste rows from the real ~/.garmy DB here;
        this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        rows = [
            (d(2), 6.0, 0, 0, None),
            (d(1), 5.5, 0, 0, None),
            (d(0), 7.0, 0, 0, None),
        ]
        old = _write_db(tmp_path / "garmin_no_nap_col.db", rows,
                        nap_column=False)
        new = _write_db(tmp_path / "garmin_nap_col.db", rows)
        with _fresh_client(old, monkeypatch) as c:
            without = c.get("/wellness/api/trends/health/sleep").json()
        with _fresh_client(new, monkeypatch) as c:
            with_col = c.get("/wellness/api/trends/health/sleep").json()

        assert without["available"] is True
        assert without == with_col
        assert [r["date"] for r in without["days"]] == [d(2), d(1), d(0)]
        assert "nap_min" not in without["tonight"]
        assert all("nap_min" not in r for r in without["days"])


@pytest.fixture
def tmp_hybrid_db(tmp_path):
    """Three nights whose strain comes off three DIFFERENT estimator tiers, so
    one exact-JSON pin covers the whole ladder:

      d(2) — wrist stream, NO resting_heart_rate  -> tier 1 on the rest fallback
      d(1) — wrist stream + an activity window    -> tier 1 on a per-day rest
      d(0) — no stream at all, max_heart_rate NULL-> tier 3 (calorie fallback)

    Every value is INVENTED — never paste rows from the real ~/.garmy DB here;
    this repo is public. Sample HRs are chosen against the params' hr_max 200
    so the heart-rate reserve fractions are round (rest 50 -> 110 is 0.4,
    125 is 0.5, 95 is 0.3, 140 is 0.6)."""
    today = date.today()

    def d(n):
        return _iso(today - timedelta(days=n))

    def day(n):
        return today - timedelta(days=n)

    rows = [
        # (wake date, sleep h, active_cal, steps, max_hr)
        (d(2), 7.0, 0, 0, None),
        (d(1), 7.0, 0, 0, None),
        (d(0), 7.0, 0, 0, None),
    ]
    timeseries = [
        # d(2): four samples two minutes apart, all 140 bpm. No
        # resting_heart_rate for this date, so the reserve floor is the
        # params' rest_hr_fallback (60) and the reserve is 140.
        ("heart_rate", _ms(day(2), 8, 0), 140.0),
        ("heart_rate", _ms(day(2), 8, 2), 140.0),
        ("heart_rate", _ms(day(2), 8, 4), 140.0),
        ("heart_rate", _ms(day(2), 8, 6), 140.0),
        # d(1): six samples two minutes apart. The middle two sit INSIDE the
        # activity window below and must not reach trimp_out — they are loud
        # (180 bpm) precisely so double-counting them would be unmissable.
        ("heart_rate", _ms(day(1), 8, 0), 110.0),
        ("heart_rate", _ms(day(1), 8, 2), 125.0),
        ("heart_rate", _ms(day(1), 8, 4), 180.0),
        ("heart_rate", _ms(day(1), 8, 6), 180.0),
        ("heart_rate", _ms(day(1), 8, 8), 95.0),
        # At rest exactly: hrr = 0, so it buys no strain (recovery is not
        # training) while still counting toward the sample minimum and the
        # cadence — which is why it keeps the 2-minute spacing.
        ("heart_rate", _ms(day(1), 8, 10), 50.0),
        # Decoy metric on d(1): the reader's metric_type = 'heart_rate' filter
        # is the only thing keeping this out of the pin below.
        ("stress", _ms(day(1), 8, 1), 199.0),
    ]
    # Decoy stream under user_id 2 on d(1), loud enough to move every number
    # if the reader stopped pinning user_id.
    ts_decoys = [("heart_rate", _ms(day(1), 8, 1), 195.0),
                 ("heart_rate", _ms(day(1), 8, 3), 195.0),
                 ("heart_rate", _ms(day(1), 8, 5), 195.0)]
    activities = [
        # 08:03 + 240 s = the window [08:03, 08:07] on d(1), avg 140 bpm.
        ("act-1", d(1), _iso_at(day(1), 8, 3), 240, 140),
    ]
    act_decoys = [("act-2", d(1), _iso_at(day(1), 9, 0), 3600, 190)]
    _write_db(tmp_path / "garmin_hybrid.db", rows,
              rest_hr={d(1): 50},
              timeseries=timeseries, ts_decoys=ts_decoys,
              activities=activities, act_decoys=act_decoys)
    return {"today": today, "path": tmp_path / "garmin_hybrid.db"}


@pytest.mark.integration
class TestHybridStrainTier:
    """The primary (tier-1) strain estimator: Banister TRIMP over the all-day
    wrist stream plus the day's activity windows, each weighted separately.
    The tier is per DAY and per TABLE — a day it cannot score, and a Garmin DB
    that has no `timeseries`/`activities` at all, both fall back to the older
    estimators rather than taking the endpoint's `available` flag with them.
    """

    def test_hybrid_math_pin(self, tmp_hybrid_db, client, monkeypatch):
        today = tmp_hybrid_db["today"]

        def d(n):
            return _iso(today - timedelta(days=n))

        with _fresh_client(tmp_hybrid_db["path"], monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        # --- d(2), tier 1 on the fallback rest (60), reserve 140 ------------
        # Median sample gap 2 min (clamped range [0.5, 5]), four samples:
        #   hrr = (140-60)/140 = 0.571428571, exp(1.92*hrr) = 2.995594943
        #   per sample = 2.0 * 0.571428571 * 0.64 * 2.995594943 = 2.191063730
        #   trimp_out  = 4 * 2.191063730 = 8.764254918,  trimp_act = 0
        #   strain     = 1.0 + 0.25*8.764254918 = 3.191063730 -> 3.2
        assert round(4 * _banister(140, 60, 2.0), 9) == 8.764254918
        # --- d(1), tier 1 on the day's own rest (50), reserve 150 -----------
        # The window [08:03, 08:07] eats the two 180 bpm samples and the
        # 08:10 sample sits at rest (hrr = 0, worth nothing), leaving
        # 110/125/95 at 2 min each:
        #   1.103590931 + 1.671485500 + 0.683101125 = 3.458177556 = trimp_out
        #   trimp_act = banister(140, 4 min) = 4.0*0.6*0.64*3.164516 = 4.860695986
        #   strain    = 1.0 + 0.25*3.458177556 + 1.2*4.860695986
        #             = 1.0 + 0.864544389 + 5.832835184 = 7.697379573 -> 7.7
        assert round(sum(_banister(hr, 50, 2.0)
                         for hr in (110, 125, 95)), 9) == 3.458177556
        assert _banister(50, 50, 2.0) == 0.0
        assert round(_banister(140, 50, 4.0), 9) == 4.860695986
        # --- d(0): no stream -> tier 3, 0.002*0 + 1.0 = 1.0 -----------------
        assert data == {
            "available": True,
            "as_of": d(0),
            # tonight = 400 + today's outgoing 1.692026799 + f(1.0)
            #         = 400 + 1.692026799 - 1.5 = 400.192026799 -> 400.2
            "tonight": {"date": d(0), "need_min": 400.2, "debt_min": 1.7,
                        "strain_est": 1.0, "strain_partial": True},
            "days": [
                # Epoch night: no previous row, so need = 400 + f(0.0).
                # weq 410 beats it -> woke owing nothing.
                {"date": d(2), "need_min": 400.0, "slept_min": 420.0,
                 "debt_min": 0.0, "strain_est": 0.0},
                # Bought by d(2)'s hybrid 3.191063730 (emitted 3.2, chained
                # unrounded): f = 0.5*3.191063730^2 - 2*3.191063730
                #               = 5.091447 - 6.382127 = -1.290683596
                # need = 400 + 0 - 1.290683596 = 398.709316404 -> 398.7,
                # weq 410 -> another surplus, out 0.
                {"date": d(1), "need_min": 398.7, "slept_min": 420.0,
                 "debt_min": 0.0, "strain_est": 3.2},
                # Bought by d(1)'s hybrid 7.697379573:
                #   f = 0.5*7.697379573^2 - 2*7.697379573
                #     = 29.624826 - 15.394759 = 14.230066997
                #   need = 400 + 0 + 14.230066997 = 414.230066997 -> 414.2
                #   out  = 0.4 * (414.230066997 - 410) = 1.692026799 -> 1.7
                {"date": d(0), "need_min": 414.2, "slept_min": 420.0,
                 "debt_min": 1.7, "strain_est": 7.7},
            ],
        }

    def test_activity_window_excludes_wrist_samples(
            self, tmp_hybrid_db, client, monkeypatch):
        """A wrist sample taken DURING an activity is already represented, at
        better fidelity, by that activity's strap average — counting it twice
        would inflate the day. The fixture's two 180 bpm samples sit strictly
        inside [08:03, 08:07], and the emission is pinned to the value that
        leaves them out."""
        today = tmp_hybrid_db["today"]

        def d(n):
            return _iso(today - timedelta(days=n))

        with _fresh_client(tmp_hybrid_db["path"], monkeypatch) as c:
            days = {r["date"]: r for r in
                    c.get("/wellness/api/trends/health/sleep").json()["days"]}

        # d(0)'s night is bought by d(1)'s strain, so it is where the exclusion
        # shows up.
        assert days[d(0)]["strain_est"] == 7.7
        assert days[d(0)]["need_min"] == 414.2

        # Had the two in-window samples been counted, each would have added
        # 5.857712882 (hrr = 130/150 = 0.866666667) to trimp_out — turning
        # 3.458177556 into 15.173603321 and the strain into
        #   1.0 + 0.25*15.173603321 + 1.2*4.860695986 = 10.626236 -> 10.6,
        # which drags the night's need to 435.2 and its debt to 10.1. The
        # difference is far larger than any rounding could explain.
        counted_twice = (1.0
                         + 0.25 * (sum(_banister(hr, 50, 2.0)
                                       for hr in (110, 125, 95))
                                   + 2 * _banister(180, 50, 2.0))
                         + 1.2 * _banister(140, 50, 4.0))
        assert round(counted_twice, 1) == 10.6
        assert days[d(0)]["strain_est"] != round(counted_twice, 1)
        assert days[d(0)]["debt_min"] == 1.7

    def test_day_below_min_samples_drops_a_tier(
            self, tmp_path, client, monkeypatch):
        """A day whose wrist stream is too sparse to trust (watch off the
        wrist) is not scored by the hybrid tier AT ALL — its activity TRIMP
        goes down with it rather than standing alone on a day with no
        out-of-activity baseline. Two databases identical but for the sample
        count straddle the threshold. Values are INVENTED — never paste rows
        from the real ~/.garmy DB here; this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        def day(n):
            return today - timedelta(days=n)

        # Tier 2 is available on d(1) for the sparse case to fall back to:
        # 0.5*ln(1+0) + 2.0*4000/1000 + 0.05*100 - 10 = 0 + 8 + 5 - 10 = 3.0
        rows = [(d(1), 7.0, 0, 4000, 100), (d(0), 7.0, 0, 4000, 100)]
        activity = [("act-1", d(1), _iso_at(day(1), 12, 0), 180, 115)]

        def build(name, sample_minutes):
            return _write_db(
                tmp_path / name, rows, rest_hr={d(1): 50},
                timeseries=[("heart_rate", _ms(day(1), 8, m), 110.0)
                            for m in sample_minutes],
                activities=list(activity))

        # min_samples is 3: two samples is below it, four is above.
        sparse = build("garmin_sparse.db", (0, 2))
        dense = build("garmin_dense.db", (0, 2, 4, 6))

        with _fresh_client(sparse, monkeypatch) as c:
            sparse_days = c.get(
                "/wellness/api/trends/health/sleep").json()["days"]
        with _fresh_client(dense, monkeypatch) as c:
            dense_days = c.get(
                "/wellness/api/trends/health/sleep").json()["days"]

        # Sparse: tier 2 exactly as before this estimator existed. The 115 bpm
        # activity contributed nothing — the day was skipped whole.
        assert sparse_days[-1] == {"date": d(0), "need_min": 398.5,
                                   "slept_min": 420.0, "debt_min": 0.0,
                                   "strain_est": 3.0}      # f(3.0) = -1.5
        # Dense: tier 1.
        #   trimp_out = 4 * 1.103590931 = 4.414363726
        #   trimp_act = banister(115, 3 min), hrr = 65/150 = 0.433333333,
        #               exp = 2.297909967 -> 1.911861093
        #   strain    = 1.0 + 1.103590931 + 2.294233312 = 4.397824243 -> 4.4
        #   f(strain) = 9.670516 - 8.795649 = 0.874780550 -> need 400.9
        assert round(4 * _banister(110, 50, 2.0), 9) == 4.414363726
        assert round(_banister(115, 50, 3.0), 9) == 1.911861093
        assert dense_days[-1] == {"date": d(0), "need_min": 400.9,
                                  "slept_min": 420.0, "debt_min": 0.0,
                                  "strain_est": 4.4}

    def test_cadence_is_the_median_gap_and_it_clamps(
            self, tmp_path, client, monkeypatch):
        """The per-day sample weight is the MEDIAN gap, clamped to [0.5, 5.0]
        minutes — pinned so neither half can silently change. Day A's gaps are
        [1, 1, 1, 9] min: median 1.0 while the MEAN is 3.0, and a mean-cadence
        implementation would emit strain 2.7 against the pinned 1.6. Day B's
        gaps are [10, 10, 10, 10]: median 10 clamps to 5.0, and an unclamped
        implementation would emit 6.7 against the pinned 3.8. Both days are
        5 x 110 bpm at rest-fallback 60 (hrr 50/140), banister/min
        0.453757559…; A: 1 + 0.25*(5*1.0*0.45376) = 1.5672 -> 1.6;
        B: 1 + 0.25*(5*5.0*0.45376) = 3.8360 -> 3.8. Values are INVENTED —
        never paste rows from the real ~/.garmy DB here; this repo is public."""
        today = date.today()

        def d(n):
            return today - timedelta(days=n)

        stream = (
            # Day d(2): offsets 0,1,2,3,12 min -> gaps [1,1,1,9]
            [("heart_rate", _ms(d(2), 10, 0) + m * 60000, 110)
             for m in (0, 1, 2, 3, 12)]
            # Day d(1): offsets 0,10,20,30,40 -> gaps [10,10,10,10]
            + [("heart_rate", _ms(d(1), 10, 0) + m * 60000, 110)
               for m in (0, 10, 20, 30, 40)]
        )
        path = _write_db(
            tmp_path / "garmin_cadence.db",
            [(_iso(d(2)), 7.0, None, None, None),
             (_iso(d(1)), 7.0, None, None, None),
             (_iso(d(0)), 7.0, None, None, None)],
            timeseries=stream, activities=[])
        with _fresh_client(path, monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        by_date = {r["date"]: r for r in data["days"]}
        # d(2)'s hybrid strain (median cadence) shows on wake-row d(1);
        # d(1)'s (clamped cadence) on wake-row d(0). All nights sleep 7.0 h
        # against sub-420 needs, so every debt is 0 and needs read the strain
        # terms directly: f(1.5672) = -1.906 -> 398.1; f(3.8360) = -0.315
        # -> 399.7; tonight rides d(0)'s tier-3 strain 1.0 -> 398.5.
        assert by_date[_iso(d(1))]["strain_est"] == 1.6
        assert by_date[_iso(d(1))]["need_min"] == 398.1
        assert by_date[_iso(d(0))]["strain_est"] == 3.8
        assert by_date[_iso(d(0))]["need_min"] == 399.7
        assert data["tonight"]["need_min"] == 398.5

    def test_missing_source_table_drops_the_tier_not_the_endpoint(
            self, tmp_path, client, monkeypatch):
        """Per-TABLE degradation. A wrist stream with no `activities` table
        cannot tell training time from the rest of the day, so the hybrid tier
        stands down entirely rather than attributing an unknown workout to the
        out-of-activity weight — and the ledger still ships, on tier 2. (The
        both-tables-absent case is every other fixture in this file, whose
        pins would move if the tier ever leaked in.) Values are INVENTED —
        never paste rows from the real ~/.garmy DB here; this repo is
        public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        def day(n):
            return today - timedelta(days=n)

        path = _write_db(
            tmp_path / "garmin_no_activities.db",
            [(d(1), 7.0, 0, 4000, 100), (d(0), 7.0, 0, 4000, 100)],
            rest_hr={d(1): 50},
            timeseries=[("heart_rate", _ms(day(1), 8, m), 110.0)
                        for m in (0, 2, 4, 6)],
            activities=None)          # table simply does not exist
        with _fresh_client(path, monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        assert data["available"] is True
        # Tier 2 (3.0), not the 2.1 those four samples would have scored.
        assert data["days"][-1]["strain_est"] == 3.0
        assert data["days"][-1]["need_min"] == 398.5

    def test_unusable_activity_rows_are_skipped_whole(
            self, tmp_path, client, monkeypatch):
        """Two junk activity rows the sync tool can produce — one with no
        parseable `start_time`, one with no duration. Neither may take the
        day's tier down, and neither may contribute a strap term: a row whose
        window cannot be placed would double-count the wrist samples underneath
        it, and a zero-length one has no time to weight. Values are INVENTED —
        never paste rows from the real ~/.garmy DB here; this repo is
        public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        def day(n):
            return today - timedelta(days=n)

        path = _write_db(
            tmp_path / "garmin_junk_activities.db",
            [(d(1), 7.0, 0, 4000, 100), (d(0), 7.0, 0, 4000, 100)],
            rest_hr={d(1): 50},
            timeseries=[("heart_rate", _ms(day(1), 8, m), 110.0)
                        for m in (0, 2, 4, 6)],
            activities=[("act-unplaceable", d(1), None, 600, 130),
                        ("act-instant", d(1), _iso_at(day(1), 20, 0), 0, 130)])
        with _fresh_client(path, monkeypatch) as c:
            days = c.get("/wellness/api/trends/health/sleep").json()["days"]

        # Wrist only: 1.0 + 0.25 * 4 * 1.103590931 = 2.103590931 -> 2.1.
        # Neither 130 bpm row added a thing (and the day did NOT fall back to
        # tier 2's 3.0).
        assert days[-1]["strain_est"] == 2.1

    def test_degenerate_hr_ceiling_scores_zero_not_negative(
            self, tmp_path, client, monkeypatch):
        """A params file whose `hr_max` sits at or below the day's resting HR
        leaves no heart-rate reserve at all. Every TRIMP increment is zero
        rather than sign-flipped, so the day scores the intercept — nonsense
        in, nothing out, never a negative strain masquerading as an easy day.
        Values are INVENTED — never paste rows from the real ~/.garmy DB here;
        this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        def day(n):
            return today - timedelta(days=n)

        broken = replace(
            _PARAMS, hybrid=replace(_PARAMS.hybrid, hr_max=40.0))
        path = _write_db(
            tmp_path / "garmin_no_reserve.db",
            [(d(1), 7.0, 0, 4000, 100), (d(0), 7.0, 0, 4000, 100)],
            rest_hr={d(1): 50},
            timeseries=[("heart_rate", _ms(day(1), 8, m), 110.0)
                        for m in (0, 2, 4, 6)],
            activities=[("act-1", d(1), _iso_at(day(1), 12, 0), 180, 115)])
        with _fresh_client(path, monkeypatch, params=broken) as c:
            days = c.get("/wellness/api/trends/health/sleep").json()["days"]

        # Tier 1 still owns the day; it just scores 1.0 + 0.25*0 + 1.2*0.
        # Tier 2 would have said 3.0, so this also proves the tier ran.
        assert days[-1]["strain_est"] == 1.0

    def test_params_predating_the_tier_still_serve_the_ledger(
            self, tmp_path, client, monkeypatch):
        """A `sleep_params.py` written before this tier existed has no `hybrid`
        field at all (the file is gitignored and hand-maintained, so it can lag
        the code). The estimator skips the tier rather than raising — the same
        judgement as an absent source table. Values are INVENTED — never paste
        rows from the real ~/.garmy DB here; this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        def day(n):
            return today - timedelta(days=n)

        legacy = SimpleNamespace(**{f.name: getattr(_PARAMS, f.name)
                                    for f in fields(_PARAMS)
                                    if f.name != "hybrid"})
        assert not hasattr(legacy, "hybrid")
        path = _write_db(
            tmp_path / "garmin_legacy_params.db",
            [(d(1), 7.0, 0, 4000, 100), (d(0), 7.0, 0, 4000, 100)],
            rest_hr={d(1): 50},
            timeseries=[("heart_rate", _ms(day(1), 8, m), 110.0)
                        for m in (0, 2, 4, 6)],
            activities=[("act-1", d(1), _iso_at(day(1), 12, 0), 180, 115)])
        with _fresh_client(path, monkeypatch, params=legacy) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        assert data["available"] is True
        assert data["days"][-1]["strain_est"] == 3.0    # tier 2

    def test_settled_days_are_memoized_recent_days_are_not(
            self, tmp_path, client, monkeypatch):
        """The full-history wrist scan is far too expensive to redo per
        request, so a day older than the 7-day recompute window is computed
        ONCE per (database path, process) and read from a module-level memo
        afterwards; everything from the window forward is recomputed every
        time (the sync backfills three days, and the memo must never outrank
        fresh data there).

        What this pins is exactly that rule and nothing more: the memo is
        per-process and keyed by DB path, so the third request below — the
        same bytes at a NEW path — is cold and sees the change the second
        request was right to ignore. Values are INVENTED — never paste rows
        from the real ~/.garmy DB here; this repo is public."""
        today = date.today()

        def d(n):
            return _iso(today - timedelta(days=n))

        def day(n):
            return today - timedelta(days=n)

        # Four streamed days spanning the boundary: d(20) well outside the
        # 7-day window, d(8) immediately outside it, d(7) the first day INSIDE
        # it (today - 7), and d(1) well inside. Each is followed by a night
        # that reports its strain. All four carry the SAME stream, so every
        # divergence below can only come from the memo.
        streamed = (20, 8, 7, 1)
        path = _write_db(
            tmp_path / "garmin_memo.db",
            [(d(n), 7.0, 0, 0, None)
             for n in (20, 19, 8, 7, 6, 1, 0)],
            rest_hr={d(n): 50 for n in streamed},
            timeseries=[("heart_rate", _ms(day(n), 8, m), 110.0)
                        for n in streamed for m in (0, 2, 4, 6)],
            activities=[])

        with _fresh_client(path, monkeypatch) as c:
            first = {r["date"]: r for r in
                     c.get("/wellness/api/trends/health/sleep").json()["days"]}
            # 1.0 + 0.25 * 4 * 1.103590931 = 2.103590931 -> 2.1 on all four
            # (each night is bought by the PREVIOUS day's strain).
            for n in (19, 7, 6, 0):
                assert first[d(n)]["strain_est"] == 2.1

            # One identical edit to both streams: 110 -> 170 bpm, which would
            # score 1.0 + 0.25 * 4 * 4.757472437 = 5.757472437 -> 5.8.
            edit = sqlite3.connect(path)
            edit.execute("UPDATE timeseries SET value = 170.0 "
                         "WHERE metric_type = 'heart_rate'")
            edit.commit()
            edit.close()

            second = {r["date"]: r for r in
                      c.get("/wellness/api/trends/health/sleep").json()["days"]}

        assert round(1.0 + 0.25 * 4 * _banister(170, 50, 2.0), 9) == 5.757472437
        # Inside the window: recomputed, so the edit lands. d(6) is bought by
        # d(7) = today - 7, the first day the rule calls mutable.
        assert second[d(0)]["strain_est"] == 5.8
        assert second[d(6)]["strain_est"] == 5.8
        # Outside it: the settled value stands, edit or no edit — including
        # d(7)'s night, bought by d(8), the day one step past the boundary
        # (and one the warm scan deliberately re-reads and then discards, so
        # that a local midnight the clock skipped can never truncate the
        # cutoff day itself).
        assert second[d(19)]["strain_est"] == 2.1
        assert second[d(7)]["strain_est"] == 2.1

        # Same data, new path -> cold memo, and the old day recomputes to the
        # edited value. This is what makes the assertion above a statement
        # about the CACHE rather than about some filter dropping old days.
        copied = tmp_path / "garmin_memo_copy.db"
        shutil.copy(path, copied)
        with _fresh_client(copied, monkeypatch) as c:
            cold = {r["date"]: r for r in
                    c.get("/wellness/api/trends/health/sleep").json()["days"]}
        for n in (19, 7, 6, 0):
            assert cold[d(n)]["strain_est"] == 5.8


# ==================== The device clock ====================
#
# Everything above runs on an EMPTY timeline — the server's own zone from
# -infinity, which is the behaviour that predates the device clock and is what
# a headerless deployment still gets. What follows is the other half: the same
# estimator and the same ledger with the watch somewhere else. Server zones are
# INJECTED here rather than inherited from the host, so these tests read the
# same on a laptop in Helsinki and on a CI runner in UTC.


def _utc_ms(y, mo, d, hh, mm):
    """Epoch milliseconds at a UTC wall clock. The wrist stream is true-UTC
    epochs (unlike `_ms` above, which speaks the host's local time), and a
    device-clock fixture has to say which instant it means."""
    return int(datetime(y, mo, d, hh, mm, tzinfo=timezone.utc).timestamp() * 1000)


def _tokyo_timeline(server_zone=timezone.utc, switch=None):
    """A timeline that is Tokyo (+9, no DST) over the whole fixture window.

    `switch` places the change point; the default sits far enough back that
    every sample below lands in the Tokyo segment.
    """
    switch = switch or datetime(2030, 1, 1, tzinfo=timezone.utc)
    return ZoneTimeline(server_zone, [(switch, "Asia/Tokyo", 540)])


def _conn(path):
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    return conn


@pytest.mark.unit
class TestHybridTrimpOnTheDeviceClock:
    """The estimator buckets by the DEVICE's day, not the server's.

    Nine hours east, a third of a "day" of wrist samples belongs to the day
    before and the activity window lands nine hours off the workout it exists
    to exclude — which is exactly what double-counts that workout's exertion.
    Garmin's own daily rows have always been keyed by the device's day; these
    pins are what make the heart-rate evidence agree with them.
    """

    def _trimp(self, path, timeline, today=date(2030, 4, 1)):
        from modules.trends_queries import _hybrid_trimp
        conn = _conn(path)
        try:
            return _hybrid_trimp(conn, str(path), _PARAMS, today, timeline)
        finally:
            conn.close()

    def test_samples_straddling_device_midnight_split_into_two_days(
            self, tmp_path):
        """One UTC evening, two Tokyo days. Under the server's zone all eight
        samples are one bucket; under the watch's they are two."""
        path = _write_db(
            tmp_path / "garmin_tokyo_split.db",
            [(d, 7.0, 0, 0, None)
             for d in ("2030-03-05", "2030-03-06", "2030-03-07")],
            rest_hr={"2030-03-05": 50, "2030-03-06": 50},
            timeseries=(
                # Tokyo 2030-03-05 20:00-20:06 — still the 5th on the watch.
                [("heart_rate", _utc_ms(2030, 3, 5, 11, m), 110.0)
                 for m in (0, 2, 4, 6)]
                # Tokyo 2030-03-06 01:00-01:06 — the 6th, same UTC evening.
                + [("heart_rate", _utc_ms(2030, 3, 5, 16, m), 110.0)
                   for m in (0, 2, 4, 6)]),
            activities=[])

        server = self._trimp(path, ZoneTimeline(timezone.utc, []))
        assert sorted(server) == ["2030-03-05"]

        device = self._trimp(path, _tokyo_timeline())
        assert sorted(device) == ["2030-03-05", "2030-03-06"]
        # Four samples each, two minutes apart, all at 110 against a rest of 50.
        each = (4 * _banister(110, 50, 2.0), 0.0)
        assert device["2030-03-05"] == pytest.approx(each)
        assert device["2030-03-06"] == pytest.approx(each)

    def test_an_activity_window_lands_on_the_device_clock(self, tmp_path):
        """`activities.start_time` is a DEVICE-local wall clock. Read as
        server-local it excludes the wrong nine hours — here, nothing at all,
        so the workout's two loud samples are counted a second time on top of
        the strap average that already represents them."""
        path = _write_db(
            tmp_path / "garmin_tokyo_window.db",
            [("2030-03-06", 7.0, 0, 0, None)],
            rest_hr={"2030-03-06": 50},
            # Tokyo 08:57 .. 09:05 on 2030-03-06, two minutes apart. The two at
            # 09:01 and 09:03 sit strictly inside the activity below and are
            # loud (180) precisely so double-counting them is unmissable.
            timeseries=[
                ("heart_rate", _utc_ms(2030, 3, 5, 23, 57), 110.0),
                ("heart_rate", _utc_ms(2030, 3, 5, 23, 59), 110.0),
                ("heart_rate", _utc_ms(2030, 3, 6, 0, 1), 180.0),
                ("heart_rate", _utc_ms(2030, 3, 6, 0, 3), 180.0),
                ("heart_rate", _utc_ms(2030, 3, 6, 0, 5), 110.0),
            ],
            # 09:00 device-local + 240 s = the window [09:00, 09:04).
            activities=[("act-1", "2030-03-06", "2030-03-06T09:00:00",
                         240, 140)])

        device = self._trimp(path, _tokyo_timeline())
        out, act = device["2030-03-06"]
        assert out == pytest.approx(3 * _banister(110, 50, 2.0))
        assert act == pytest.approx(_banister(140, 50, 4.0))

        # The bug this replaces, in both of its halves at once. Under the
        # server's zone the day breaks at the WRONG midnight — the 08:57 and
        # 08:59 samples fall off the far side into a bucket too thin to score
        # — and the window is placed nine hours away, so it excludes nothing
        # and the strap's own minutes are counted twice: once at 180 on the
        # wrist, once at 140 on the strap.
        server = self._trimp(path, ZoneTimeline(timezone.utc, []))
        assert "2030-03-05" not in server
        server_out = server["2030-03-06"][0]
        assert server_out > out
        assert server_out == pytest.approx(
            2 * _banister(180, 50, 2.0) + _banister(110, 50, 2.0))

    def test_a_window_straddling_a_westward_change_still_excludes(
            self, tmp_path):
        """The exclusion is keyed by INSTANTS, not by a calendar day.

        Fly west mid-workout and the device clock rewinds across a midnight:
        the ride's later samples bucket to the day BEFORE the one the activity
        is filed under. The retired rule looked at this day's windows plus the
        previous day's, on the reasoning that a workout can only spill FORWARD
        past local midnight — which is true of a fixed zone and false of a
        travelling one. Those post-change samples are the strap's own minutes;
        counting them again on the wrist is the double-count this tier exists
        to avoid.
        """
        path = _write_db(
            tmp_path / "garmin_westward.db",
            [(d, 7.0, 0, 0, None) for d in ("2030-03-05", "2030-03-06")],
            rest_hr={"2030-03-05": 50, "2030-03-06": 50},
            timeseries=[
                # Tokyo 2030-03-06 05:02 .. 05:06 — inside the ride, before
                # the change, and filed under the 6th like the activity.
                ("heart_rate", _utc_ms(2030, 3, 5, 20, 2), 180.0),
                ("heart_rate", _utc_ms(2030, 3, 5, 20, 4), 180.0),
                ("heart_rate", _utc_ms(2030, 3, 5, 20, 6), 180.0),
                # Still inside the ride, but now on UTC, where the clock reads
                # 2030-03-05 20:4x — the day BEFORE the activity's own date.
                ("heart_rate", _utc_ms(2030, 3, 5, 20, 40), 180.0),
                ("heart_rate", _utc_ms(2030, 3, 5, 20, 42), 180.0),
                ("heart_rate", _utc_ms(2030, 3, 5, 20, 44), 180.0),
                # After the ride, same UTC day: the only wrist work that
                # should survive on 2030-03-05.
                ("heart_rate", _utc_ms(2030, 3, 5, 22, 10), 110.0),
                ("heart_rate", _utc_ms(2030, 3, 5, 22, 12), 110.0),
                ("heart_rate", _utc_ms(2030, 3, 5, 22, 14), 110.0),
            ],
            # Tokyo 2030-03-06 05:00 = 20:00Z, running two hours: the window
            # [20:00Z, 22:00Z) spans the change point at 20:30Z.
            activities=[("act-1", "2030-03-06", "2030-03-06T05:00:00",
                         7200, 140)])

        timeline = ZoneTimeline(
            ZoneInfo("Asia/Tokyo"),
            [(datetime(2030, 3, 5, 20, 30, tzinfo=timezone.utc), "UTC", 0)])
        result = self._trimp(path, timeline)

        # The activity's own date: every sample it holds is inside the window.
        assert result["2030-03-06"][0] == 0.0
        assert result["2030-03-06"][1] == pytest.approx(
            _banister(140, 50, 120.0))
        # The day the westward jump moved the rest of the ride onto: only the
        # three post-ride samples count.
        assert result["2030-03-05"][0] == pytest.approx(
            3 * _banister(110, 50, 2.0))

    def test_overlapping_windows_all_exclude(self, tmp_path):
        """A ride logged inside a longer hike: the window that STARTED last is
        not always the one still running, so the covering test cannot just
        look at the nearest start."""
        path = _write_db(
            tmp_path / "garmin_overlap.db",
            [("2030-03-06", 7.0, 0, 0, None)],
            rest_hr={"2030-03-06": 50},
            timeseries=[
                ("heart_rate", _utc_ms(2030, 3, 6, 0, 2), 180.0),   # in both
                ("heart_rate", _utc_ms(2030, 3, 6, 0, 4), 180.0),   # in outer
                ("heart_rate", _utc_ms(2030, 3, 6, 0, 6), 180.0),   # in outer
                ("heart_rate", _utc_ms(2030, 3, 6, 0, 20), 110.0),  # outside
                ("heart_rate", _utc_ms(2030, 3, 6, 0, 22), 110.0),
                ("heart_rate", _utc_ms(2030, 3, 6, 0, 24), 110.0),
            ],
            activities=[
                # 09:00 +9h = 00:00Z, ten minutes -> [00:00Z, 00:10Z)
                ("outer", "2030-03-06", "2030-03-06T09:00:00", 600, 130),
                # 09:01 +9h = 00:01Z, two minutes -> [00:01Z, 00:03Z)
                ("inner", "2030-03-06", "2030-03-06T09:01:00", 120, 150),
            ])

        out, _ = self._trimp(path, _tokyo_timeline())["2030-03-06"]
        assert out == pytest.approx(3 * _banister(110, 50, 2.0))

    def test_a_mid_day_change_point_buckets_each_half_by_its_own_zone(
            self, tmp_path):
        """The travel day itself: the morning is scored on the zone the watch
        left, the evening on the one it arrived in, and the day is simply a
        shorter interval rather than a broken one."""
        path = _write_db(
            tmp_path / "garmin_change_point.db",
            [(d, 7.0, 0, 0, None) for d in ("2030-03-05", "2030-03-06")],
            rest_hr={"2030-03-05": 50, "2030-03-06": 50},
            timeseries=(
                # Before noon UTC: still the server's zone -> the 5th.
                [("heart_rate", _utc_ms(2030, 3, 5, 9, m), 110.0)
                 for m in (0, 2, 4, 6)]
                # After it: Tokyo, where 15:00Z is already 00:00 on the 6th.
                + [("heart_rate", _utc_ms(2030, 3, 5, 15, m), 110.0)
                   for m in (0, 2, 4, 6)]),
            activities=[])

        timeline = _tokyo_timeline(
            switch=datetime(2030, 3, 5, 12, tzinfo=timezone.utc))
        assert sorted(self._trimp(path, timeline)) == [
            "2030-03-05", "2030-03-06"]

    def test_an_unplaceable_activity_is_dropped_whole(self, tmp_path):
        """A start_time inside the hours a zone jump skipped was never on the
        watch's face. Unplaceable is unusable — the row goes, TRIMP and window
        together, exactly as an unparseable one does, because keeping the strap
        term without its exclusion would count the workout twice."""
        path = _write_db(
            tmp_path / "garmin_unplaceable.db",
            [("2030-03-05", 7.0, 0, 0, None)],
            rest_hr={"2030-03-05": 50},
            timeseries=[("heart_rate", _utc_ms(2030, 3, 5, 9, m), 110.0)
                        for m in (0, 2, 4, 6)],
            # 15:00 device-local on the travel day: the clock jumped from
            # 12:00 straight to 21:00, so this hour never existed.
            activities=[("act-1", "2030-03-05", "2030-03-05T15:00:00",
                         600, 140)])

        timeline = _tokyo_timeline(
            switch=datetime(2030, 3, 5, 12, tzinfo=timezone.utc))
        out, act = self._trimp(path, timeline)["2030-03-05"]
        assert act == 0.0
        assert out == pytest.approx(4 * _banister(110, 50, 2.0))

    def test_a_new_change_point_invalidates_the_memo(self, tmp_path):
        """Every memoized bucket was computed under one timeline, so a change
        point re-dates days on both sides of it. The marker carries the
        timeline's fingerprint for exactly this: a settled day the memo would
        otherwise own is rescanned rather than served under the old zone."""
        path = _write_db(
            tmp_path / "garmin_memo_zone.db",
            [(d, 7.0, 0, 0, None)
             for d in ("2030-03-05", "2030-03-06", "2030-03-07")],
            rest_hr={"2030-03-05": 50, "2030-03-06": 50},
            timeseries=[("heart_rate", _utc_ms(2030, 3, 5, 16, m), 110.0)
                        for m in (0, 2, 4, 6)],
            activities=[])
        # today is far past the 7-day window, so these days are settled and
        # the first pass memoizes them.
        server_only = ZoneTimeline(timezone.utc, [])

        assert sorted(self._trimp(path, server_only)) == ["2030-03-05"]

        # Control: the same fingerprint DOES hold the memo. Edit the stream to
        # a value that would score differently and watch nothing move.
        edit = sqlite3.connect(path)
        edit.execute("UPDATE timeseries SET value = 170.0")
        edit.commit()
        edit.close()
        held = self._trimp(path, server_only)
        assert held["2030-03-05"][0] == pytest.approx(
            4 * _banister(110, 50, 2.0))

        # A change point moves the fingerprint: full rescan, so the day is
        # re-dated to the watch's AND picks up the edit the memo was hiding.
        moved = self._trimp(path, _tokyo_timeline())
        assert sorted(moved) == ["2030-03-06"]
        assert moved["2030-03-06"][0] == pytest.approx(
            4 * _banister(170, 50, 2.0))

        # And back: the fingerprint differs again, so the memo does not serve
        # the Tokyo buckets to a server-zone reader either.
        assert sorted(self._trimp(path, server_only)) == ["2030-03-05"]




@pytest.mark.integration
class TestLedgerTodayFromTheHeader:
    """`today` is the REQUEST's calendar date. On a phone a day ahead of the
    server, the night the watch just scored must not read as a future row."""

    @pytest.fixture
    def spread(self, tmp_path):
        """Nights across whatever pair of dates the host and the far zone
        disagree about, with sleep varying so every row's debt is distinct —
        which is what makes "tonight carried THIS row" a sharp claim."""
        zone_name, there, offset_min = _zone_on_another_date()
        here = date.today()
        first = min(here, there) - timedelta(days=3)
        span = (max(here, there) - first).days
        rows = [((first + timedelta(days=i)).isoformat(),
                 7.0 - 0.25 * i, 0, 0, None)
                for i in range(span + 1)]
        _write_db(tmp_path / "garmin_zone_today.db", rows)
        return {"path": tmp_path / "garmin_zone_today.db",
                "zone": zone_name, "offset": offset_min,
                "here": here, "there": there,
                "start": first.isoformat(),
                "end": max(here, there).isoformat()}

    def _fetch(self, spread, client, monkeypatch, headers=None):
        with _fresh_client(spread["path"], monkeypatch) as c:
            return c.get("/wellness/api/trends/health/sleep",
                         params={"start": spread["start"],
                                 "end": spread["end"]},
                         headers=headers or {}).json()

    def test_tonight_is_the_devices_date(self, spread, client, monkeypatch):
        data = self._fetch(spread, client, monkeypatch, headers={
            "X-Client-Zone": spread["zone"],
            "X-Client-Offset-Min": str(spread["offset"])})
        by_date = {r["date"]: r for r in data["days"]}
        there = spread["there"].isoformat()

        assert data["tonight"]["date"] == there
        assert there != spread["here"].isoformat()
        # The night keyed by the device's today is the ledger position tonight
        # carries — the card and the chart's last point are one statement.
        assert data["tonight"]["debt_min"] == by_date[there]["debt_min"]

    def test_without_headers_the_server_clock_still_rules(
            self, spread, client, monkeypatch):
        data = self._fetch(spread, client, monkeypatch)
        by_date = {r["date"]: r for r in data["days"]}
        here = spread["here"].isoformat()

        assert data["tonight"]["date"] == here
        assert data["tonight"]["debt_min"] == by_date[here]["debt_min"]

    def test_the_two_answers_actually_differ(self, spread, client,
                                             monkeypatch):
        """Guard against a fixture that accidentally makes both readings the
        same number — then neither test above would be saying anything."""
        with_headers = self._fetch(spread, client, monkeypatch, headers={
            "X-Client-Zone": spread["zone"],
            "X-Client-Offset-Min": str(spread["offset"])})
        without = self._fetch(spread, client, monkeypatch)
        assert with_headers["tonight"] != without["tonight"]

    def test_an_unresolvable_zone_falls_back_to_the_server_date(
            self, spread, client, monkeypatch):
        """Both headers present, but this host's tz database has never heard of
        the id. The ledger uses the date it has always used rather than
        inventing one from the offset."""
        data = self._fetch(spread, client, monkeypatch, headers={
            "X-Client-Zone": "Mars/Olympus", "X-Client-Offset-Min": "90"})
        assert data["tonight"]["date"] == spread["here"].isoformat()


@pytest.mark.integration
class TestHeaderlessBytesAreUnchanged:
    """The compatibility claim, at the byte level, on a fixture with BOTH
    hybrid tables populated — the shape where the device clock does the most
    work and therefore has the most to break.

    A deployment with no Android client, and every day of history recorded
    before the first header arrived, must serve exactly what it served before
    this feature existed. `TestTheRetiredSqlGrouping` in test_device_clock.py
    pins the bucketing rule against sqlite itself; this pins the wire.
    """

    def test_the_response_bytes_are_exactly_the_derived_ledger(
            self, tmp_hybrid_db, client, monkeypatch):
        today = tmp_hybrid_db["today"]

        def d(n):
            return _iso(today - timedelta(days=n))

        # Hand-derived in TestHybridStrainTier.test_hybrid_math_pin, from the
        # invented params at the top of this file. Repeated as a literal on
        # purpose: a byte pin that computed its own expectation from the code
        # under test would pin nothing.
        expected = {
            "available": True,
            "as_of": d(0),
            "tonight": {"date": d(0), "need_min": 400.2, "debt_min": 1.7,
                        "strain_est": 1.0, "strain_partial": True},
            "days": [
                {"date": d(2), "need_min": 400.0, "slept_min": 420.0,
                 "debt_min": 0.0, "strain_est": 0.0},
                {"date": d(1), "need_min": 398.7, "slept_min": 420.0,
                 "debt_min": 0.0, "strain_est": 3.2},
                {"date": d(0), "need_min": 414.2, "slept_min": 420.0,
                 "debt_min": 1.7, "strain_est": 7.7},
            ],
        }

        with _fresh_client(tmp_hybrid_db["path"], monkeypatch) as c:
            resp = c.get("/wellness/api/trends/health/sleep")

        # Key ORDER is part of the claim, not just the values — which is why
        # this compares bytes rather than parsed JSON.
        assert resp.content == json.dumps(
            expected, ensure_ascii=False, separators=(",", ":")).encode()

    def test_a_timeline_stating_the_server_zone_changes_no_bytes(
            self, tmp_hybrid_db):
        """A change point that does not actually change the zone must be
        invisible. The segment machinery — bucketer windows, memo
        invalidation, window placement — has to introduce no artifacts of its
        own, or the empty-timeline compatibility claim is luck rather than
        design.

        The zone here is Tokyo for both runs, injected rather than inherited
        from the host, and the change point sits in the MIDDLE of the data so
        samples and activity windows straddle it.
        """
        from modules import trends_queries
        from modules.db import DbAccessor

        today = tmp_hybrid_db["today"]
        accessor = DbAccessor(tmp_hybrid_db["path"], read_only=True)
        tokyo = ZoneInfo("Asia/Tokyo")
        midpoint = datetime.combine(
            today - timedelta(days=1), time(12, 0), tzinfo=timezone.utc)

        def run(timeline):
            return trends_queries.sleep_series(
                accessor, _PARAMS, end=_iso(today), today=today,
                timeline=timeline)

        one_segment = run(ZoneTimeline(tokyo, []))
        two_segments = run(ZoneTimeline(tokyo, [(midpoint, "Asia/Tokyo", 540)]))

        assert json.dumps(two_segments) == json.dumps(one_segment)
        assert one_segment["available"] is True
        assert len(one_segment["days"]) == 3   # the fixture really was scored


@pytest.mark.integration
class TestDefaultEndFollowsTheDevice:
    """`end` defaults to a date the client never sent, so it has to be the
    client's own. On a phone a day ahead of the server, a server-dated default
    would clip the very day the phone is asking about — the row it opened the
    app to see.

    Deterministic in both directions: whichever way the far zone differs, the
    emitted range must end on the DEVICE's date with headers and on the
    SERVER's without them, so this says something real at any hour.
    """

    @pytest.fixture
    def spread(self, tmp_path):
        zone_name, there, offset_min = _zone_on_another_date()
        here = date.today()
        first = min(here, there) - timedelta(days=3)
        span = (max(here, there) - first).days
        rows = [((first + timedelta(days=i)).isoformat(),
                 7.0 - 0.25 * i, 0, 0, None)
                for i in range(span + 1)]
        _write_db(tmp_path / "garmin_default_end.db", rows)
        return {"path": tmp_path / "garmin_default_end.db",
                "zone": zone_name, "offset": offset_min,
                "here": here, "there": there}

    def test_the_range_ends_on_the_devices_date(self, spread, client,
                                                monkeypatch):
        with _fresh_client(spread["path"], monkeypatch) as c:
            # No `start`, no `end` — everything is a default.
            with_headers = c.get(
                "/wellness/api/trends/health/sleep",
                headers={"X-Client-Zone": spread["zone"],
                         "X-Client-Offset-Min": str(spread["offset"])}).json()
            without = c.get("/wellness/api/trends/health/sleep").json()

        assert (max(r["date"] for r in with_headers["days"])
                == spread["there"].isoformat())
        assert (max(r["date"] for r in without["days"])
                == spread["here"].isoformat())
        assert spread["there"] != spread["here"]

    def test_an_explicit_end_still_wins(self, spread, client, monkeypatch):
        """The default is a fallback, never an override — a client that names
        its range gets exactly that range."""
        asked = (min(spread["here"], spread["there"]) - timedelta(days=1))
        with _fresh_client(spread["path"], monkeypatch) as c:
            data = c.get("/wellness/api/trends/health/sleep",
                         params={"end": asked.isoformat()},
                         headers={"X-Client-Zone": spread["zone"],
                                  "X-Client-Offset-Min": str(spread["offset"])}
                         ).json()
        assert max(r["date"] for r in data["days"]) == asked.isoformat()
