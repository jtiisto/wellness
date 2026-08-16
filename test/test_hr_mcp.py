"""Tests for the HR MCP server tools and helpers.

Ported from `pulse_bridge_mcp/test_tools.py`, which sat outside the pulse-bridge
test runner's cwd and so had never been executed there. Everything it asserted
is kept (the ported cases are marked), and the gaps that showed up while porting
— unbounded `limit`, the never-exercised read-only guarantee, absent-database
behaviour, the registered tool surface — are covered here as well.

Fixtures build a real `hr.db` through the `hr` module's own `init_database`, so
these read the schema the server actually creates. Every value is generated from
a formula and anchored in 2030: nothing here is transcribed from a capture.
"""

import ast
import hashlib
import os
import sqlite3
import subprocess
import sys
from pathlib import Path

import pytest

from hr_mcp.config import MCPConfig
from hr_mcp.database import DatabaseManager, HrDataUnavailable
from hr_mcp.server import create_mcp_server
from hr_mcp.tools import (
    _parse_ms,
    crop_beats,
    get_aligned_timeseries,
    get_latest_session_report,
    get_session_report,
    get_vo2_summary,
    list_sessions,
)

from hr_analysis.quality import classify, rr_coverage
from hr_analysis.hr import time_weighted_mean_hr
from modules.db import DbAccessor
from modules.hr import init_database

# 2030-01-01T00:00:00Z. Far-future on purpose: a plausible *past* epoch-ms
# anchor in a public repo invites the question of whether it came from a real
# capture. Nothing here did. (Same convention as test/hr/analysis/conftest.py.)
T0 = 1_893_456_000_000

_INTERVAL_COLUMNS = (
    "device_id, timestamp_ms, seq, heart_rate_bpm, rr_interval_ms, "
    "is_gap_before, session_id, sensor_type, received_at"
)
_RECEIVED_AT = "2030-01-01T00:00:00Z"


def _steady_beats(session_id, start_ms, device_id="AA:BB"):
    """A warm-up / hard / cool-down block, 420 beats (the ported fixture)."""
    rows = []
    ts = start_ms
    for i in range(420):
        hr = 100 if i < 120 else 165 if i < 300 else 105
        rr = int(60_000 / hr)
        ts += rr
        rows.append((device_id, ts, i, hr, rr, 0, session_id, "garmin_hrm", _RECEIVED_AT))
    return rows


def _interval_beats(session_id, start_ms, device_id="AA:BB"):
    """20 s easy, then 3 x (60 s hard / 45 s easy) — the ported VO2 fixture."""
    rows = []
    ts = start_ms
    seq = 0

    def add_block(hr, seconds):
        nonlocal ts, seq
        rr = int(60_000 / hr)
        for _ in range(int(seconds * hr / 60)):
            ts += rr
            rows.append((device_id, ts, seq, hr, rr, 0, session_id, "garmin_hrm", _RECEIVED_AT))
            seq += 1

    add_block(100, 20)
    for rep in range(3):
        add_block(170, 60)
        if rep < 2:
            add_block(110, 45)
    return rows


def _write(db_path, intervals=(), sessions=()):
    conn = sqlite3.connect(db_path)
    try:
        conn.executemany(
            f"INSERT INTO intervals ({_INTERVAL_COLUMNS}) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            intervals,
        )
        conn.executemany(
            "INSERT INTO sessions (session_id, device_id, started_at_ms, ended_at_ms, "
            "workout_date, workout_session_id, received_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            sessions,
        )
        conn.commit()
    finally:
        conn.close()


@pytest.fixture
def empty_hr_db(tmp_path):
    """An initialised but empty hr.db — the fresh-install state."""
    db_path = tmp_path / "hr.db"
    init_database(DbAccessor(db_path))
    return db_path


@pytest.fixture
def hr_db_path(empty_hr_db):
    """Two captured sessions: a steady one, and a later interval one.

    Only the steady session gets a `sessions` row, so the listing's LEFT JOIN is
    exercised from both sides — beats can arrive for a session whose row was
    never uploaded.
    """
    _write(
        empty_hr_db,
        intervals=_steady_beats("session-1", T0) + _interval_beats("vo2-session", T0 + 3_600_000),
        sessions=[("session-1", "AA:BB", T0, T0 + 300_000, "2030-01-01", None, _RECEIVED_AT)],
    )
    return empty_hr_db


@pytest.fixture
def db(hr_db_path):
    return DatabaseManager(MCPConfig.from_db_path(hr_db_path))


def _extract_tools(mcp_server):
    """Extract tool functions from an MCP server by name."""
    return {tool.fn.__name__: tool.fn for tool in mcp_server._tool_manager._tools.values()}


# ==================== Unit: argument parsing ====================


@pytest.mark.unit
class TestParseMs:
    """`_parse_ms` is the tool ergonomics surface: an LLM may pass either an
    epoch-ms number or an ISO-8601 instant for the crop bounds."""

    def test_passes_epoch_ms_through(self):
        assert _parse_ms(T0) == T0

    def test_accepts_digit_string(self):
        assert _parse_ms(str(T0)) == T0

    def test_accepts_iso_8601_with_zulu(self):
        assert _parse_ms("2030-01-01T00:00:00Z") == T0

    def test_treats_a_naive_timestamp_as_utc(self):
        assert _parse_ms("2030-01-01T00:00:00") == T0

    def test_none_and_blank_mean_unbounded(self):
        assert _parse_ms(None) is None
        assert _parse_ms("   ") is None


@pytest.mark.unit
class TestCropBeats:
    def test_rejects_an_inverted_window(self):
        with pytest.raises(ValueError, match="must be <="):
            crop_beats([], start_ms=T0 + 1000, end_ms=T0)

    def test_bounds_are_inclusive(self):
        beats = _steady_beats("s", T0)
        from hr_analysis.quality import Beat

        parsed = [Beat(ts_ms=r[1], rr_ms=r[4], hr_bpm=r[3], is_gap=False) for r in beats]
        first, last = parsed[0].ts_ms, parsed[-1].ts_ms
        assert len(crop_beats(parsed, start_ms=first, end_ms=last)) == len(parsed)


# ==================== Integration: list_sessions ====================


@pytest.mark.integration
class TestListSessions:
    def test_lists_every_captured_session(self, db):
        """PORTED: test_list_sessions."""
        sessions = list_sessions(db)
        assert {s["session_id"] for s in sessions} == {"session-1", "vo2-session"}
        baseline = next(s for s in sessions if s["session_id"] == "session-1")
        assert baseline["beats"] == 420
        assert baseline["duration_s"] > 0

    def test_newest_session_first(self, db):
        assert [s["session_id"] for s in list_sessions(db)] == ["vo2-session", "session-1"]

    def test_workout_date_decorates_a_session_that_has_a_row(self, db):
        by_id = {s["session_id"]: s for s in list_sessions(db)}
        assert by_id["session-1"]["workout_date"] == "2030-01-01"
        # Beats arrived, the session row never did — still analysable, still listed.
        assert by_id["vo2-session"]["workout_date"] is None

    def test_window_keeps_sessions_that_overlap_it(self, db):
        """A window that lands inside the second capture returns only it —
        whole, not cropped: `beats` still counts the entire session, because
        that is what analysing it would cover."""
        whole = {s["session_id"]: s for s in list_sessions(db)}
        vo2 = whole["vo2-session"]
        inside = list_sessions(db, start_ms=vo2["start_ms"] + 10_000, end_ms=vo2["end_ms"])
        assert [s["session_id"] for s in inside] == ["vo2-session"]
        assert inside[0]["beats"] == vo2["beats"]
        assert inside[0]["start_ms"] == vo2["start_ms"]

    def test_window_before_any_capture_is_empty(self, db):
        assert list_sessions(db, end_ms=T0 - 1) == []

    def test_window_bounds_accept_iso_8601(self, db):
        assert [s["session_id"] for s in list_sessions(db, start_ms="2030-01-01T00:30:00Z")] == \
            ["vo2-session"]

    def test_limit_applies_after_the_window_filter(self, db):
        assert len(list_sessions(db, limit=1)) == 1

    def test_limit_is_capped_by_max_rows(self, hr_db_path):
        capped = DatabaseManager(MCPConfig(db_path=hr_db_path, max_rows=1))
        assert len(list_sessions(capped, limit=50)) == 1

    def test_rejects_a_non_positive_limit(self, db):
        """LATENT BUG in the pulse-bridge original: `min(limit or max_rows,
        max_rows)` passed a negative limit straight into SQL, and SQLite reads a
        negative LIMIT as *no limit* — so `limit=-1` returned the entire table
        from a tool whose whole point is a bounded result. `limit=0` fell back
        to max_rows, silently ignoring the caller."""
        for bad in (0, -1):
            with pytest.raises(ValueError, match="limit must be at least 1"):
                list_sessions(db, limit=bad)


# ==================== Integration: session reports ====================


@pytest.mark.integration
class TestSessionReport:
    def test_supports_crop(self, db):
        """PORTED: test_get_session_report_supports_crop."""
        full = get_session_report(db, "session-1", hrmax=188)
        crop_start = full["raw_capture_window"]["start_ms"] + 20_000
        cropped = get_session_report(db, "session-1", start_ms=crop_start, hrmax=188)

        assert cropped["analysis_window"]["source"] == "manual"
        assert cropped["analysis_window"]["trimmed_before_s"] > 0
        assert cropped["raw_capture_window"]["beats"] == 420
        assert cropped["analysis_window"]["start_ms"] >= crop_start
        assert cropped["quality"]["hr_usable"] is True

    def test_uncropped_window_is_the_full_capture(self, db):
        report = get_session_report(db, "session-1")
        assert report["analysis_window"]["source"] == "full_capture"
        assert report["analysis_window"]["trimmed_before_s"] == 0
        assert report["analysis_window"]["trimmed_after_s"] == 0

    def test_reports_the_capture_devices(self, db):
        report = get_session_report(db, "session-1")
        assert report["device"] == {"device_ids": ["AA:BB"], "sensor_types": ["garmin_hrm"]}

    def test_zones_appear_only_with_hrmax(self, db):
        assert get_session_report(db, "session-1", hrmax=188)["hr"]["zones"]
        assert get_session_report(db, "session-1")["hr"]["zones"] is None

    def test_unknown_session_raises(self, db):
        with pytest.raises(ValueError, match="No data for session nope"):
            get_session_report(db, "nope")

    def test_empty_crop_window_raises(self, db):
        with pytest.raises(ValueError, match="Crop window contains no beats"):
            get_session_report(db, "session-1", start_ms=T0 + 10_000_000)


@pytest.mark.integration
class TestLatestSessionReport:
    def test_analyzes_the_newest_session(self, db):
        """PORTED: test_get_latest_session_report."""
        report = get_latest_session_report(db)
        assert report["session_id"] == "vo2-session"
        assert "quality" in report

    def test_empty_database_raises(self, empty_hr_db):
        db = DatabaseManager(MCPConfig.from_db_path(empty_hr_db))
        with pytest.raises(ValueError, match="No sessions found"):
            get_latest_session_report(db)


# ==================== Integration: aligned time series ====================


def _naive_timeseries_rows(beats, resolution_s):
    """The pulse-bridge bucketing loop, verbatim, as the parity oracle.

    The port replaced it with a single pass (it re-scanned every beat once per
    bucket); this keeps the original as the definition of what the rows are.
    """
    t0 = beats[0].ts_ms
    bucket_ms = resolution_s * 1000
    out_rows = []
    start = t0
    while start <= beats[-1].ts_ms:
        stop = start + bucket_ms
        bucket = [beat for beat in beats if start <= beat.ts_ms < stop]
        if bucket:
            flags = classify(bucket)
            valid_hr = [beat.hr_bpm for beat in bucket if beat.hr_bpm > 0]
            hr_weighted = time_weighted_mean_hr(bucket)
            out_rows.append({
                "timestamp_ms": int(start),
                "offset_s": round((start - t0) / 1000.0, 1),
                "duration_s": resolution_s,
                "hr_mean": None if hr_weighted is None else round(hr_weighted, 1),
                "hr_max": max(valid_hr) if valid_hr else None,
                "rr_coverage": round(rr_coverage(bucket), 3),
                "artifact_frac": round(flags.artifact_fraction, 3),
                "gap": bool(flags.gap.any()),
                "beats": len(bucket),
            })
        start = stop
    return out_rows


@pytest.mark.integration
class TestAlignedTimeseries:
    def test_returns_quality_buckets(self, db):
        """PORTED: test_get_aligned_timeseries — the full row shape now lives
        behind include_quality (the lean curve is the default)."""
        series = get_aligned_timeseries(
            db, "session-1", resolution_s=10, include_quality=True)
        assert series["session_id"] == "session-1"
        assert series["resolution_s"] == 10
        assert series["rows"]
        assert {"hr_mean", "hr_max", "rr_coverage", "artifact_frac", "gap"} <= set(series["rows"][0])

    @pytest.mark.parametrize("resolution_s", [1, 5, 37])
    def test_matches_the_original_bucketing(self, db, resolution_s):
        rows = get_aligned_timeseries(
            db, "session-1", resolution_s=resolution_s, include_quality=True)["rows"]
        assert rows == _naive_timeseries_rows(db.load_beats("session-1"), resolution_s)

    def test_lean_default_is_the_curve_only(self, db):
        """The default row is offset + HR — the RR-quality detail was ~2/3 of
        every row's bytes and pushed hour-long sessions past the tool result
        limit. Values must agree with the full shape, only the keys differ."""
        lean = get_aligned_timeseries(db, "session-1", resolution_s=10)
        full = get_aligned_timeseries(
            db, "session-1", resolution_s=10, include_quality=True)
        assert len(lean["rows"]) == len(full["rows"])
        for slim, fat in zip(lean["rows"], full["rows"]):
            assert set(slim) <= {"offset_s", "hr_mean", "hr_max", "gap"}
            assert slim["offset_s"] == fat["offset_s"]
            assert slim["hr_mean"] == fat["hr_mean"]
            assert slim["hr_max"] == fat["hr_max"]
            # gap appears ONLY when true — an honest hole still shows, a
            # clean bucket doesn't pay for the key.
            assert slim.get("gap", False) == fat["gap"]
        # The envelope still reconstructs absolute time.
        assert lean["analysis_window"]["start_ms"] == full["analysis_window"]["start_ms"]

    def test_buckets_are_anchored_at_the_window_start(self, db):
        rows = get_aligned_timeseries(db, "session-1", resolution_s=10)["rows"]
        assert rows[0]["offset_s"] == 0.0
        assert all(row["offset_s"] % 10 == 0 for row in rows)

    def test_rejects_a_sub_second_resolution(self, db):
        with pytest.raises(ValueError, match="resolution_s must be at least 1"):
            get_aligned_timeseries(db, "session-1", resolution_s=0)


def _write_set_events(db_path, events):
    """(event_id, ts_ms, exercise_key, set_num, item_key, action) rows for
    session-1, dated with the fixture's far-future convention."""
    conn = sqlite3.connect(db_path)
    try:
        conn.executemany(
            "INSERT INTO set_events (event_id, date, exercise_key, set_num, "
            "item_key, action, client_timestamp_ms, session_id, received_at) "
            "VALUES (?, '2030-01-01', ?, ?, ?, ?, ?, 'session-1', ?)",
            [(e[0], e[2], e[3], e[4], e[5], e[1], _RECEIVED_AT) for e in events],
        )
        conn.commit()
    finally:
        conn.close()


@pytest.mark.integration
class TestSetEventMarkers:
    """The exercise ground truth next to the HR curve: set-completion markers
    ride along in both the report and the timeseries, offset-aligned to the
    analysis window and cropped with it."""

    @pytest.fixture(autouse=True)
    def seed_events(self, hr_db_path):
        _write_set_events(hr_db_path, [
            ("ev-1", T0 + 10_000, "ex_squat", 1, None, "check"),
            ("ev-2", T0 + 60_000, "ex_squat", 2, None, "check"),
            ("ev-3", T0 + 62_000, "ex_squat", 2, None, "uncheck"),
            ("ev-4", T0 + 90_000, "fixture_mobility", None, "item-a", "check"),
        ])
        self.db_path = hr_db_path

    def test_timeseries_carries_offset_aligned_markers(self, db):
        series = get_aligned_timeseries(db, "session-1", resolution_s=10)
        markers = series["set_events"]
        # Offsets share the rows' t0 — the first BEAT, not the session row —
        # so a marker and a bucket at the same offset are the same instant.
        t0 = series["analysis_window"]["start_ms"]
        expected = [round((T0 + ms - t0) / 1000.0, 1)
                    for ms in (10_000, 60_000, 62_000, 90_000)]
        assert [m["offset_s"] for m in markers] == expected
        # Wire style matches the sync protocol: optional identity fields are
        # omitted, never null — a set tick has set_num, a toggle has item_key.
        tick, undo, toggle = markers[0], markers[2], markers[3]
        assert tick == {"offset_s": expected[0], "exercise_key": "ex_squat",
                        "action": "check", "set_num": 1}
        assert undo["action"] == "uncheck"          # an undo is a real event
        assert "item_key" not in tick
        assert toggle["item_key"] == "item-a" and "set_num" not in toggle

    def test_report_carries_the_same_markers(self, db):
        report = get_session_report(db, "session-1")
        assert [m["exercise_key"] for m in report["set_events"]] == \
            ["ex_squat", "ex_squat", "ex_squat", "fixture_mobility"]

    def test_markers_crop_with_the_analysis_window(self, db):
        series = get_aligned_timeseries(
            db, "session-1", start_ms=T0 + 30_000, end_ms=T0 + 80_000)
        # Only ev-2 and ev-3 fall inside the window; offsets re-anchor to the
        # cropped window's first beat, same t0 the rows use.
        assert [m["exercise_key"] for m in series["set_events"]] == ["ex_squat"] * 2
        assert all(0 <= m["offset_s"] <= 50 for m in series["set_events"])

    def test_report_windows_are_opt_in(self, db):
        """The per-window DFA array is ~85% of a report's bytes; the quality
        block's trusted/total counts summarize it by default."""
        lean = get_session_report(db, "session-1")
        full = get_session_report(db, "session-1", include_windows=True)
        assert "windows" not in lean
        assert isinstance(full["windows"], list)
        assert lean["quality"]["total_dfa_windows"] == len(full["windows"])


# ==================== Integration: VO2 plan vs actual ====================


@pytest.mark.integration
class TestVo2Summary:
    def test_matches_intent(self, db):
        """PORTED: test_get_vo2_summary_matches_intent."""
        vo2_session = next(
            s for s in list_sessions(db) if s["session_id"] == "vo2-session"
        )
        result = get_vo2_summary(
            db,
            "vo2-session",
            start_ms=vo2_session["start_ms"] + 20_000,
            hrmax=188,
            intent={
                "rounds": 3,
                "work_duration_s": 60,
                "rest_duration_s": 45,
                "target_hr_min": 169,
                "target_hr_max": 188,
                "modality": "bike",
            },
        )

        assert result["vo2"]["expected_structure_available"] is True
        assert len(result["vo2"]["work_bouts"]) == 3
        assert result["vo2"]["work_bouts"][0]["peak_hr"] == 170
        assert result["vo2"]["work_bouts"][0]["seconds_at_or_above_target_hr_min"] > 0
        assert result["timeseries"]["rows"]

    def test_without_intent_returns_timeseries_fallback(self, db):
        """PORTED: test_get_vo2_summary_without_intent_returns_timeseries_fallback."""
        result = get_vo2_summary(db, "vo2-session")

        assert result["vo2"]["expected_structure_available"] is False
        assert result["vo2"]["flags"]["intent_missing"] is True
        assert result["vo2"]["work_bouts"] == []
        assert result["timeseries"]["rows"]

    def test_rejects_bad_intent(self, db):
        """PORTED: test_get_vo2_summary_rejects_bad_intent."""
        with pytest.raises(ValueError, match="target_hr_min"):
            get_vo2_summary(
                db,
                "vo2-session",
                intent={
                    "rounds": 3,
                    "work_duration_s": 60,
                    "rest_duration_s": 45,
                    "target_hr_min": 180,
                    "target_hr_max": 170,
                },
            )

    def test_intent_is_validated_before_the_database_is_touched(self, db):
        """A bad plan is the caller's mistake, not a data problem: it must not
        depend on the session existing."""
        with pytest.raises(ValueError, match="must be supplied together"):
            get_vo2_summary(db, "no-such-session", intent={"rounds": 3})

    def test_shares_the_report_window_and_quality(self, db):
        """The composed tool must not disagree with the report tool it quotes."""
        report = get_session_report(db, "vo2-session", hrmax=188)
        result = get_vo2_summary(db, "vo2-session", hrmax=188)
        assert result["analysis_window"] == report["analysis_window"]
        assert result["quality"] == report["quality"]
        assert result["hr"] == report["hr"]

    def test_agreement_holds_under_a_crop_window(self, db):
        """Same agreement with start_ms/end_ms supplied: the single shared
        _load_window must thread the crop identically into both tools."""
        crop = {"start_ms": T0 + 3_630_000, "end_ms": T0 + 3_800_000}
        report = get_session_report(db, "vo2-session", hrmax=188, **crop)
        result = get_vo2_summary(db, "vo2-session", hrmax=188, **crop)
        assert result["analysis_window"] == report["analysis_window"]
        assert result["quality"] == report["quality"]
        assert result["hr"] == report["hr"]
        assert result["analysis_window"]["start_ms"] >= crop["start_ms"]
        assert result["analysis_window"]["end_ms"] <= crop["end_ms"]


# ==================== Read-only + absent-database guarantees ====================


@pytest.mark.integration
class TestReadOnlyGuarantee:
    """The `hr` module's endpoints are the only writer of hr.db. Nothing in this
    package may write it — or create it."""

    def test_tools_do_not_modify_the_database(self, db, hr_db_path):
        before = hashlib.sha256(hr_db_path.read_bytes()).hexdigest()
        list_sessions(db)
        get_session_report(db, "session-1", hrmax=188)
        get_latest_session_report(db)
        get_aligned_timeseries(db, "session-1")
        get_vo2_summary(db, "vo2-session", hrmax=188)
        assert hashlib.sha256(hr_db_path.read_bytes()).hexdigest() == before

    def test_absent_database_is_reported_not_created(self, tmp_path):
        """A fresh install has no hr.db until the first capture batch lands, so
        this is an ordinary state: the tool says so in a sentence, and — the
        part a plain sqlite3.connect would get wrong — leaves no empty database
        behind for the server to inherit."""
        missing = tmp_path / "hr.db"
        db = DatabaseManager(MCPConfig.from_db_path(missing))

        with pytest.raises(HrDataUnavailable, match="No HR database at"):
            list_sessions(db)

        assert not missing.exists()
        assert list(tmp_path.iterdir()) == []

    def test_foreign_database_is_reported_not_parsed(self, tmp_path):
        other = tmp_path / "hr.db"
        conn = sqlite3.connect(other)
        conn.execute("CREATE TABLE unrelated (id INTEGER)")
        conn.commit()
        conn.close()

        db = DatabaseManager(MCPConfig.from_db_path(other))
        with pytest.raises(HrDataUnavailable, match="no HR tables"):
            list_sessions(db)

    def test_package_never_opens_a_database_itself(self):
        """Structural guard: the package holds no SQL and no connection of its
        own — every read goes through hr_analysis, which opens `mode=ro`. That
        is what makes "cannot write, cannot create" a property of the code
        rather than a promise, so a future edit reaching for a cursor has to
        notice this test first."""
        package = Path(__file__).resolve().parents[1] / "mcp_servers" / "hr_mcp"
        db_calls = {"connect", "execute", "executemany", "executescript", "cursor", "commit"}
        offenders = []

        for path in sorted(package.glob("*.py")):
            tree = ast.parse(path.read_text())
            for node in ast.walk(tree):
                imported = []
                if isinstance(node, ast.Import):
                    imported = [alias.name for alias in node.names]
                elif isinstance(node, ast.ImportFrom):
                    imported = [node.module or ""]
                if any(name.split(".")[0] == "sqlite3" for name in imported):
                    offenders.append(f"{path.name}:{node.lineno} imports sqlite3")
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) \
                        and node.func.attr in db_calls:
                    offenders.append(f"{path.name}:{node.lineno} calls .{node.func.attr}()")

        assert offenders == []


# ==================== Configuration ====================


@pytest.mark.unit
class TestConfig:
    def test_missing_database_is_not_a_configuration_error(self, tmp_path):
        """Deliberate deviation from coach/journal, whose databases always
        exist: HR history starts empty, and a server that refused to start
        before the first capture would be unusable exactly when a caller wants
        to ask whether anything has been captured."""
        MCPConfig.from_db_path(tmp_path / "absent.db").validate()

    def test_directory_in_place_of_a_database_is_rejected(self, tmp_path):
        with pytest.raises(ValueError, match="not a file"):
            MCPConfig.from_db_path(tmp_path).validate()

    @pytest.mark.parametrize("kwargs, match", [
        ({"max_rows": 0}, "max_rows must be at least 1"),
        ({"max_rows": 99_999}, "cannot exceed max_rows_absolute"),
        ({"transport": "carrier-pigeon"}, "Invalid transport"),
        ({"port": 0}, "Invalid port"),
        ({"port": 70_000}, "Invalid port"),
    ])
    def test_rejects_bad_settings(self, empty_hr_db, kwargs, match):
        with pytest.raises(ValueError, match=match):
            MCPConfig(db_path=empty_hr_db, **kwargs).validate()


# ==================== Server registration ====================


@pytest.mark.integration
class TestServerRegistration:
    def test_registers_the_five_tools(self, hr_db_path):
        tools = _extract_tools(create_mcp_server(MCPConfig.from_db_path(hr_db_path)))
        assert set(tools) == {
            "list_sessions",
            "get_session_report",
            "get_latest_session_report",
            "get_aligned_timeseries",
            "get_vo2_summary",
        }

    def test_every_registered_tool_reaches_the_database(self, hr_db_path):
        """Each of the five wrappers, through the surface an MCP client sees.

        A five-tool registration block is copy-paste shaped: a wrapper handed
        the wrong argument, or pointed at its neighbour's implementation, is
        invisible to tests that call `tools.py` directly."""
        tools = _extract_tools(create_mcp_server(MCPConfig.from_db_path(hr_db_path)))

        assert tools["list_sessions"](limit=1)[0]["session_id"] == "vo2-session"
        assert tools["get_session_report"]("session-1")["raw_capture_window"]["beats"] == 420
        assert tools["get_latest_session_report"](hrmax=188)["session_id"] == "vo2-session"
        assert tools["get_aligned_timeseries"]("session-1", resolution_s=30)["resolution_s"] == 30
        vo2 = tools["get_vo2_summary"]("vo2-session", resolution_s=15)
        assert vo2["session_id"] == "vo2-session"
        assert vo2["timeseries"]["resolution_s"] == 15

    def test_starts_without_a_database(self, tmp_path, monkeypatch):
        """Creating the server must not require a capture to have happened."""
        monkeypatch.setenv("HR_DB_PATH", str(tmp_path / "hr.db"))
        tools = _extract_tools(create_mcp_server())
        with pytest.raises(HrDataUnavailable):
            tools["list_sessions"]()
        assert not (tmp_path / "hr.db").exists()

    def test_default_config_follows_HR_DB_PATH(self, tmp_path, monkeypatch):
        """One path resolution for server, CLI and MCP — the module's own."""
        monkeypatch.setenv("HR_DB_PATH", str(tmp_path / "elsewhere.db"))
        from config import get_module_db_path

        assert Path(get_module_db_path("hr")) == tmp_path / "elsewhere.db"


@pytest.mark.unit
class TestSrcBootstrap:
    """Importing the package must put src/ on sys.path so `hr_analysis` and
    `config` resolve in the REAL MCP process (python -m hr_mcp, cwd=mcp_servers,
    src/ not otherwise on the path) — the coach_mcp precedent. Runs in a
    subprocess with PYTHONPATH cleared so it cannot lean on conftest's setup."""

    def test_importing_package_makes_hr_analysis_importable(self):
        repo_root = Path(__file__).resolve().parents[1]
        env = {**os.environ, "PYTHONPATH": ""}
        result = subprocess.run(
            [sys.executable, "-c",
             "import hr_mcp; import hr_analysis.db as d; print('OK', bool(d.list_sessions))"],
            cwd=str(repo_root / "mcp_servers"), env=env, capture_output=True, text=True,
        )
        assert result.returncode == 0, f"stderr:\n{result.stderr}"
        assert result.stdout.strip().endswith("OK True"), result.stdout
