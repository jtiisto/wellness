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


def _blocks(session_id, start_ms, blocks, device_id="AA:BB"):
    """`intervals` rows for a list of (hr_bpm, seconds) blocks, back to back."""
    rows = []
    ts = start_ms
    seq = 0
    for hr, seconds in blocks:
        rr = int(60_000 / hr)
        for _ in range(int(seconds * hr / 60)):
            ts += rr
            rows.append((device_id, ts, seq, hr, rr, 0, session_id, "garmin_hrm", _RECEIVED_AT))
            seq += 1
    return rows


def _interval_beats(session_id, start_ms, device_id="AA:BB", lead_in_s=20):
    """20 s easy, then 3 x (60 s hard / 45 s easy) — the ported VO2 fixture.

    `lead_in_s=0` drops the easy opening. `get_vo2_summary` lays its expected
    bouts out from the FIRST BEAT, and the crop that used to trim the lead-in
    away retired with the rest of the time-window surface — so the alignment a
    plan-matching test needs now lives in the fixture rather than in a caller's
    arithmetic.
    """
    blocks = [(100, lead_in_s)] if lead_in_s else []
    for rep in range(3):
        blocks.append((170, 60))
        if rep < 2:
            blocks.append((110, 45))
    return _blocks(session_id, start_ms, blocks, device_id)


def _write(db_path, intervals=(), sessions=(), guide_events=()):
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
        conn.executemany(
            "INSERT INTO guide_events (event_id, date, exercise_key, action, "
            "client_timestamp_ms, session_id, extension_sec, timeline_json, "
            "received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            guide_events,
        )
        conn.commit()
    finally:
        conn.close()


def _guide(event_id, session_id, exercise_key, action, ts_ms,
           extension_sec=None, timeline_json=None, date="2030-01-01"):
    return (event_id, date, exercise_key, action, ts_ms, session_id,
            extension_sec, timeline_json, _RECEIVED_AT)


@pytest.fixture
def empty_hr_db(tmp_path):
    """An initialised but empty hr.db — the fresh-install state."""
    db_path = tmp_path / "hr.db"
    init_database(DbAccessor(db_path))
    return db_path


@pytest.fixture
def hr_db_path(empty_hr_db):
    """Three captured sessions, none of them guided.

    A steady one, a later interval one, and an earlier interval one whose first
    beat IS its first effort (the plan-matching fixture — see
    `_interval_beats`). Only the steady session gets a `sessions` row, so the
    listing's LEFT JOIN is exercised from both sides: beats can arrive for a
    session whose row was never uploaded.
    """
    _write(
        empty_hr_db,
        intervals=(_steady_beats("session-1", T0)
                   + _interval_beats("vo2-session", T0 + 3_600_000)
                   + _interval_beats("vo2-aligned", T0 - 3_600_000,
                                     device_id="CC:DD", lead_in_s=0)),
        sessions=[("session-1", "AA:BB", T0, T0 + 300_000, "2030-01-01", None, _RECEIVED_AT)],
    )
    return empty_hr_db


@pytest.fixture
def db(hr_db_path):
    return DatabaseManager(MCPConfig.from_db_path(hr_db_path))


# ---------------------------------------------------------------- guided rides

# A three-step timeline in the coach wire's own segment shape: a marked warmup,
# a role-less (therefore work) effort, and a marked cooldown. Every number is
# invented.
GUIDED_TIMELINE = (
    '[{"duration_sec":60,"hr_min":110,"hr_max":125,"label":"ease in","role":"warmup"},'
    '{"duration_sec":180,"hr_min":140,"hr_max":155},'
    '{"duration_sec":60,"hr_max":120,"role":"cooldown"}]'
)
ANCHOR = T0 + 20_000            # 20 s of clipping-in beats precede START
EXTENDED_TOTAL_S = 600          # 300 planned + one 300 s extend, all in the work step


@pytest.fixture
def guided_db(empty_hr_db):
    """Three sessions, each a different shape of guided record.

    `guided-one` is the ordinary case: one ride, ridden to the end, with beats
    both before START and after the timeline finished. `guided-two` spans two
    guided exercises — the disambiguation case. `guided-short` is the early
    bail: the capture stops halfway through the schedule.
    """
    _write(
        empty_hr_db,
        intervals=(
            _blocks("guided-one", T0, [
                (100, 20),      # clipping in, before START
                (118, 60),      # warmup, inside its 110-125 band
                (132, 60),      # work, below the 140 floor — the ramp
                (148, 420),     # work, inside the band (60 s + the 300 s extend)
                (112, 60),      # cooldown, under the 120 ceiling
                (105, 30),      # still pedalling after the timeline ended
            ], device_id="EE:FF")
            + _blocks("guided-two", T0 + 7_200_000, [
                (150, 120), (140, 60), (128, 300),
            ], device_id="EE:FF")
            + _blocks("guided-short", T0 + 14_400_000, [
                (118, 60), (145, 180), (146, 60),
            ], device_id="EE:FF")
        ),
        guide_events=[
            _guide("g-1", "guided-one", "fixture-cardio-intervals", "start",
                   ANCHOR, timeline_json=GUIDED_TIMELINE),
            _guide("g-2", "guided-one", "fixture-cardio-intervals", "extend",
                   ANCHOR + 90_000, extension_sec=300),
            _guide("g-3", "guided-two", "fixture-cardio-intervals", "start",
                   T0 + 7_200_000, timeline_json=GUIDED_TIMELINE),
            _guide("g-4", "guided-two", "fixture-zone2", "start",
                   T0 + 7_380_000, timeline_json='[{"duration_sec":300,"hr_min":120}]'),
            _guide("g-5", "guided-short", "fixture-cardio-intervals", "start",
                   T0 + 14_400_000, timeline_json=GUIDED_TIMELINE),
            _guide("g-6", "guided-short", "fixture-cardio-intervals", "extend",
                   T0 + 14_500_000, extension_sec=300),
        ],
    )
    return empty_hr_db


@pytest.fixture
def gdb(guided_db):
    return DatabaseManager(MCPConfig.from_db_path(guided_db))


def _extract_tools(mcp_server):
    """Extract tool functions from an MCP server by name."""
    return {tool.fn.__name__: tool.fn for tool in mcp_server._tool_manager._tools.values()}


# ==================== Unit: argument parsing ====================


@pytest.mark.unit
class TestNoTimeWindowSurvives:
    """No tool takes a clock reading — the 2026-08-22 retrieval ruling.

    The crop parameters (and the ISO-8601 parser that served them) are gone from
    every tool, and this pins their absence. The reason is the two-case model:
    either wellness owns a ride end to end, keyed by one session id, or the ride
    happened on the watch and this database has nothing to say about it. A tool
    that accepted a Garmin activity's start time would invite exactly the
    cross-check that model refuses — and a watch activity started on mounting
    the bike routinely predates a START pressed after clipping in, so the
    comparison would warn about nothing at all.
    """

    @pytest.mark.parametrize("tool, args", [
        (list_sessions, {}),
        (get_session_report, {"session_id": "session-1"}),
        (get_aligned_timeseries, {"session_id": "session-1"}),
        (get_vo2_summary, {"session_id": "session-1"}),
    ])
    @pytest.mark.parametrize("window", [{"start_ms": T0}, {"end_ms": T0}])
    def test_tools_reject_a_time_window(self, db, tool, args, window):
        with pytest.raises(TypeError):
            tool(db, **args, **window)

    def test_the_package_no_longer_parses_instants(self):
        """The ISO-8601 reader existed only for the crop bounds."""
        import hr_mcp.tools as tools_module

        assert not hasattr(tools_module, "_parse_ms")
        assert not hasattr(tools_module, "crop_beats")


# ==================== Integration: list_sessions ====================


@pytest.mark.integration
class TestListSessions:
    def test_lists_every_captured_session(self, db):
        """PORTED: test_list_sessions."""
        sessions = list_sessions(db)
        assert {s["session_id"] for s in sessions} == {
            "session-1", "vo2-session", "vo2-aligned"}
        baseline = next(s for s in sessions if s["session_id"] == "session-1")
        assert baseline["beats"] == 420
        assert baseline["duration_s"] > 0

    def test_newest_session_first(self, db):
        assert [s["session_id"] for s in list_sessions(db)] == [
            "vo2-session", "session-1", "vo2-aligned"]

    def test_workout_date_decorates_a_session_that_has_a_row(self, db):
        by_id = {s["session_id"]: s for s in list_sessions(db)}
        assert by_id["session-1"]["workout_date"] == "2030-01-01"
        # Beats arrived, the session row never did — still analysable, still listed.
        assert by_id["vo2-session"]["workout_date"] is None

    def test_limit_caps_the_listing(self, db):
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


@pytest.mark.integration
class TestListSessionsGuidedness:
    """The listing answers the two-case question before anything else is asked.

    `guided` is on every row, present whether true or false: it decides which
    analysis case applies, and a caller must not have to infer it from a missing
    key.
    """

    def test_an_unguided_capture_says_so_and_lists_no_exercises(self, db):
        row = next(s for s in list_sessions(db) if s["session_id"] == "session-1")
        assert row["guided"] is False
        assert "guided_exercises" not in row

    def test_a_guided_capture_describes_each_ride(self, gdb):
        row = next(s for s in list_sessions(gdb) if s["session_id"] == "guided-one")
        assert row["guided"] is True
        ride, = row["guided_exercises"]
        assert ride["exercise_key"] == "fixture-cardio-intervals"
        assert ride["anchor_ms"] == ANCHOR
        assert ride["segments"] == 3
        # One tap, its 300 s folded in: 300 planned + 300 appended.
        assert (ride["extends"], ride["extension_sec"]) == (1, 300)
        assert ride["total_sec"] == EXTENDED_TOTAL_S
        # Offset into the capture, so "20 s of clipping in" is readable without
        # subtracting two epoch numbers by hand.
        assert ride["anchor_offset_s"] > 0

    def test_a_capture_spanning_two_rides_lists_both(self, gdb):
        row = next(s for s in list_sessions(gdb) if s["session_id"] == "guided-two")
        assert [r["exercise_key"] for r in row["guided_exercises"]] == [
            "fixture-cardio-intervals", "fixture-zone2"]

    def test_one_query_serves_the_whole_listing(self, gdb, monkeypatch):
        """A listing must not cost one guide-event query per row."""
        calls = []
        original = gdb.guide_events_by_session
        monkeypatch.setattr(
            gdb, "guide_events_by_session",
            lambda ids: (calls.append(list(ids)), original(ids))[1],
        )
        list_sessions(gdb)
        assert len(calls) == 1 and len(calls[0]) == 3


# ==================== Integration: session reports ====================


@pytest.mark.integration
class TestSessionReport:
    def test_unguided_session_reports_no_structure(self, db):
        """Every result says where its structure came from. With nothing
        recorded and nothing supplied, it says so rather than staying silent."""
        report = get_session_report(db, "session-1", hrmax=188)
        assert report["structure"] == {"guided": False, "source": "none"}
        assert report["quality"]["hr_usable"] is True

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


@pytest.mark.integration
class TestGuidedSessionReport:
    """A guided session is analysed against the timeline it was ridden to."""

    def test_the_window_is_the_ride_not_the_capture(self, gdb):
        report = get_session_report(gdb, "guided-one")
        window = report["analysis_window"]
        assert window["source"] == "guided"
        # The 20 s of clipping in and the 30 s of pedalling afterwards are
        # capture, not ride: both are trimmed, and both are still visible in
        # raw_capture_window so nothing is hidden.
        assert window["trimmed_before_s"] > 0
        assert window["trimmed_after_s"] > 0
        assert window["start_ms"] >= ANCHOR
        assert report["raw_capture_window"]["beats"] > window["duration_s"]

    def test_structure_names_the_recorded_authority(self, gdb):
        structure = get_session_report(gdb, "guided-one")["structure"]
        assert structure["guided"] is True
        assert structure["source"] == "guide_events"
        assert structure["exercise_key"] == "fixture-cardio-intervals"
        assert structure["anchor_ms"] == ANCHOR
        assert (structure["planned_total_sec"], structure["extension_sec"]) == (300, 300)
        assert structure["total_sec"] == EXTENDED_TOTAL_S
        assert [e["extension_sec"] for e in structure["extends"]] == [300]
        assert structure["extends"][0]["offset_s"] == 90.0

    def test_appended_minutes_land_in_the_single_work_segment(self, gdb):
        """The shipped rule, derived offline: with exactly one work-role
        segment the extension lengthens THAT one and the cooldown shifts later
        intact — never the last segment, which is what the pre-role rule did."""
        structure = get_session_report(gdb, "guided-one")["structure"]
        warmup, work, cooldown = structure["segments"]
        assert structure["extended_index"] == 1
        assert (warmup["role"], work["role"], cooldown["role"]) == (
            "warmup", "work", "cooldown")
        assert (warmup["start_offset_s"], warmup["end_offset_s"]) == (0.0, 60.0)
        assert (work["start_offset_s"], work["end_offset_s"]) == (60.0, 540.0)
        assert (cooldown["start_offset_s"], cooldown["end_offset_s"]) == (540.0, 600.0)
        assert work["extended_sec"] == 300
        assert "extended_sec" not in cooldown

    def test_each_segment_is_judged_against_its_own_recorded_band(self, gdb):
        structure = get_session_report(gdb, "guided-one")["structure"]
        warmup, work, cooldown = structure["segments"]

        assert warmup["band"] == "range" and (warmup["hr_min"], warmup["hr_max"]) == (110, 125)
        assert warmup["fraction_in_band"] == 1.0
        assert warmup["label"] == "ease in"

        # The work step opens 60 s below its 140 floor (the ramp) and holds the
        # band for the rest: below-band time is real and reported as such.
        assert work["seconds_below_band"] > 50
        assert work["seconds_above_band"] == 0.0
        assert 0.8 < work["fraction_in_band"] < 0.95
        assert work["time_to_band_s"] > 50

        # A ceiling-only segment has no floor to be below.
        assert cooldown["band"] == "ceiling" and "hr_min" not in cooldown
        assert cooldown["seconds_below_band"] == 0.0

    def test_band_seconds_partition_the_covered_time(self, gdb):
        """in + below + above is the span's covered time, so the three numbers
        can be read as shares of one whole rather than three measurements."""
        for segment in get_session_report(gdb, "guided-one")["structure"]["segments"]:
            total = (segment["seconds_in_band"] + segment["seconds_below_band"]
                     + segment["seconds_above_band"])
            assert total == pytest.approx(segment["covered_s"], abs=0.2)

    def test_work_aggregates_exclude_the_preparation(self, gdb):
        """Warmup and cooldown are around the session, not the session. The
        aggregate is over work-role spans only — and it agrees with the
        segments it sums, because it is built from them."""
        structure = get_session_report(gdb, "guided-one")["structure"]
        work_segment = structure["segments"][1]
        work = structure["work"]
        assert work["segment_indexes"] == [1]
        assert work["avg_hr"] == work_segment["avg_hr"]
        assert work["peak_hr"] == work_segment["peak_hr"]
        assert work["seconds_in_band"] == work_segment["seconds_in_band"]
        # The cooldown's 112 bpm would drag a whole-ride average below this.
        assert work["avg_hr"] > structure["segments"][2]["avg_hr"]

    def test_coverage_reports_a_ride_that_ran_to_the_end(self, gdb):
        coverage = get_session_report(gdb, "guided-one")["structure"]["coverage"]
        assert coverage["scheduled_sec"] == EXTENDED_TOTAL_S
        assert coverage["complete"] is True
        assert coverage["fraction"] == 1.0
        assert coverage["beats_before_anchor"] > 0
        assert coverage["beats_after_schedule"] > 0

    def test_coverage_reports_an_early_bail_honestly(self, gdb):
        """The capture stops halfway through a 600 s schedule. The window is
        clipped by where the beats end, and the shortfall is stated rather than
        absorbed — the last segments simply hold no beats."""
        structure = get_session_report(gdb, "guided-short")["structure"]
        coverage = structure["coverage"]
        assert coverage["complete"] is False
        assert coverage["missing_tail_sec"] > 250
        assert coverage["fraction"] < 0.6
        assert structure["segments"][-1]["beats"] == 0
        assert structure["segments"][-1]["avg_hr"] is None

    def test_detected_bouts_stay_beside_the_recorded_segments(self, gdb):
        """Two independent readings of the same beats. The signal detector is
        not silenced by a recorded timeline — where they disagree, that is
        information."""
        report = get_session_report(gdb, "guided-one")
        assert "bouts" in report
        assert report["structure"]["segments"]

    def test_a_capture_with_two_rides_asks_which_one(self, gdb):
        """Never a guess: a VO2 session followed by a Zone 2 ride is one
        capture with two timelines, and analysing the first because it came
        first would answer a question nobody asked."""
        answer = get_session_report(gdb, "guided-two")
        assert answer["needs_exercise_key"] is True
        assert [r["exercise_key"] for r in answer["guided_exercises"]] == [
            "fixture-cardio-intervals", "fixture-zone2"]
        assert "hr" not in answer

    def test_the_exercise_key_chooses_between_them(self, gdb):
        report = get_session_report(gdb, "guided-two", exercise_key="fixture-zone2")
        structure = report["structure"]
        assert structure["exercise_key"] == "fixture-zone2"
        assert structure["total_sec"] == 300
        # A floor-only band: min only is a floor, and there is no ceiling to be
        # above.
        segment, = structure["segments"]
        assert segment["band"] == "floor" and segment["seconds_above_band"] == 0.0

    def test_an_unknown_exercise_key_says_what_is_there(self, gdb):
        with pytest.raises(ValueError, match="fixture-zone2"):
            get_session_report(gdb, "guided-two", exercise_key="fixture-nothing")

    def test_a_lone_ride_needs_no_key(self, gdb):
        assert get_session_report(gdb, "guided-one")["structure"]["exercise_key"] \
            == "fixture-cardio-intervals"


@pytest.mark.integration
class TestGuidedDerivationEdges:
    """The shapes a record can take that are not the ordinary ride."""

    def test_the_latest_start_wins_and_the_earlier_run_is_counted(self, empty_hr_db):
        """A fresh run appends a second start; the record is append-only, so
        the discarded run is reported rather than hidden."""
        _write(
            empty_hr_db,
            intervals=_blocks("s", T0, [(140, 400)], device_id="EE:FF"),
            guide_events=[
                _guide("a", "s", "ex", "start", T0, timeline_json=GUIDED_TIMELINE),
                _guide("b", "s", "ex", "extend", T0 + 10_000, extension_sec=300),
                _guide("c", "s", "ex", "start", T0 + 60_000,
                       timeline_json='[{"duration_sec":120,"hr_min":130}]'),
            ],
        )
        db = DatabaseManager(MCPConfig.from_db_path(empty_hr_db))
        structure = get_session_report(db, "s")["structure"]
        assert structure["anchor_ms"] == T0 + 60_000
        assert structure["discarded_starts"] == 1
        # The extend belonged to the run the anchor discarded — the client drops
        # the extension when it re-anchors, so keeping it would lengthen a ride
        # nobody lengthened.
        assert structure["extension_sec"] == 0
        assert structure["discarded_extends"] == 1
        assert structure["total_sec"] == 120

    def test_extends_without_a_start_are_not_a_guided_ride(self, empty_hr_db):
        """The recorded shape of clipping the strap on mid-ride: the START
        happened before the session existed, was never recorded, and is never
        back-filled. Unguided is the honest reading."""
        _write(
            empty_hr_db,
            intervals=_blocks("s", T0, [(140, 300)], device_id="EE:FF"),
            guide_events=[_guide("a", "s", "ex", "extend", T0 + 5_000, extension_sec=300)],
        )
        db = DatabaseManager(MCPConfig.from_db_path(empty_hr_db))
        assert get_session_report(db, "s")["structure"] == {
            "guided": False, "source": "none"}

    def test_a_segmentless_ride_is_all_work_and_has_no_recorded_end(self, empty_hr_db):
        """A `duration` exercise with no authored timeline records `[]`. Its
        planned length lives in the coach plan, which this database deliberately
        never reads — so the ride runs from the anchor to wherever the beats
        end, and the whole of it is work."""
        _write(
            empty_hr_db,
            intervals=_blocks("s", T0, [(100, 30), (138, 300)], device_id="EE:FF"),
            guide_events=[_guide("a", "s", "ex", "start", T0 + 30_000, timeline_json="[]")],
        )
        db = DatabaseManager(MCPConfig.from_db_path(empty_hr_db))
        structure = get_session_report(db, "s")["structure"]
        assert structure["segmentless"] is True
        assert (structure["total_sec"], structure["planned_total_sec"]) == (None, None)
        assert structure["segments"] == []
        assert structure["coverage"]["scheduled_sec"] is None
        # No timeline to tell one part from another: the whole window is the
        # ride, and it has no band to be in or out of.
        assert structure["work"]["segment_indexes"] == []
        assert structure["work"]["avg_hr"] is not None
        assert structure["work"]["fraction_in_band"] is None
        # No recorded length means nothing was scheduled. Reporting the window's
        # own span here would read as a plan that was never written.
        assert structure["work"]["scheduled_s"] is None
        assert structure["coverage"]["missing_tail_sec"] is None

    def test_a_malformed_timeline_degrades_to_segmentless(self, empty_hr_db):
        """Only validated writes reach the column, so an unreadable blob is a
        hand-edited row. Losing the band beats losing the ride."""
        _write(
            empty_hr_db,
            intervals=_blocks("s", T0, [(138, 200)], device_id="EE:FF"),
            guide_events=[_guide("a", "s", "ex", "start", T0, timeline_json="{not json")],
        )
        db = DatabaseManager(MCPConfig.from_db_path(empty_hr_db))
        structure = get_session_report(db, "s")["structure"]
        assert structure["guided"] is True and structure["segmentless"] is True

    def test_an_unknown_role_reads_as_work(self, empty_hr_db):
        """The server holds the closed set, so a value that gets this far can
        only be hand-edited — and absence already means work."""
        _write(
            empty_hr_db,
            intervals=_blocks("s", T0, [(145, 200)], device_id="EE:FF"),
            guide_events=[_guide(
                "a", "s", "ex", "start", T0,
                timeline_json='[{"duration_sec":120,"hr_min":130,"role":"warmpu"}]')],
        )
        db = DatabaseManager(MCPConfig.from_db_path(empty_hr_db))
        structure = get_session_report(db, "s")["structure"]
        assert structure["segments"][0]["role"] == "work"
        assert structure["work"]["segment_indexes"] == [0]

    def test_the_ride_window_is_half_open(self, empty_hr_db):
        """A beat landing exactly on the scheduled end belongs to the silence
        after the ride, not to its last segment — the guide's convention
        everywhere else, and what stops a boundary beat being counted twice."""
        _write(
            empty_hr_db,
            intervals=[
                ("EE:FF", T0, 0, 120, 500, 0, "s", "garmin_hrm", _RECEIVED_AT),
                ("EE:FF", T0 + 60_000, 0, 120, 500, 0, "s", "garmin_hrm", _RECEIVED_AT),
            ],
            guide_events=[_guide(
                "a", "s", "ex", "start", T0,
                timeline_json='[{"duration_sec":60,"hr_min":100}]')],
        )
        db = DatabaseManager(MCPConfig.from_db_path(empty_hr_db))
        structure = get_session_report(db, "s")["structure"]
        assert structure["segments"][0]["beats"] == 1
        assert structure["coverage"]["beats_after_schedule"] == 1

    def test_a_guide_that_never_met_its_capture_says_so(self, empty_hr_db):
        """Beats and a timeline for the same session that do not overlap at all
        — the report cannot be built, and the message names the ride."""
        _write(
            empty_hr_db,
            intervals=_blocks("s", T0, [(120, 60)], device_id="EE:FF"),
            guide_events=[_guide(
                "a", "s", "ex", "start", T0 + 3_600_000,
                timeline_json='[{"duration_sec":60,"hr_min":100}]')],
        )
        db = DatabaseManager(MCPConfig.from_db_path(empty_hr_db))
        with pytest.raises(ValueError, match="no beats inside the guided window"):
            get_session_report(db, "s")


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

    def test_an_unguided_series_says_it_has_no_structure(self, db):
        assert get_aligned_timeseries(db, "session-1")["structure"] == {
            "guided": False, "source": "none"}

    def test_a_guided_series_carries_the_boundaries_on_its_own_clock(self, gdb):
        """Boundaries as offsets into THIS series, so the recorded plan can be
        laid over the curve without arithmetic — and no metrics, which live in
        get_session_report and must not be computed twice."""
        series = get_aligned_timeseries(gdb, "guided-one", resolution_s=10)
        structure = series["structure"]
        assert structure["guided"] is True
        assert [s["role"] for s in structure["segments"]] == [
            "warmup", "work", "cooldown"]
        assert structure["segments"][0]["hr_min"] == 110
        assert "fraction_in_band" not in structure["segments"][0]
        # The window starts at the first beat AT OR AFTER the anchor, so the
        # first boundary sits at or just before offset 0.
        assert structure["segments"][0]["start_offset_s"] <= 0
        last = structure["segments"][-1]
        assert last["end_offset_s"] == pytest.approx(EXTENDED_TOTAL_S, abs=1.0)

    def test_whole_capture_opts_out_of_the_guided_window(self, gdb):
        """The one way to see outside a guided ride: what preceded START and
        what followed the timeline's end."""
        ride = get_aligned_timeseries(gdb, "guided-one")
        whole = get_aligned_timeseries(gdb, "guided-one", whole_capture=True)
        assert whole["analysis_window"]["start_ms"] < ride["analysis_window"]["start_ms"]
        assert whole["analysis_window"]["end_ms"] > ride["analysis_window"]["end_ms"]
        assert len(whole["rows"]) > len(ride["rows"])
        # Structure is still reported — the ride happened either way — and its
        # anchor offset now measures from the capture's own start.
        assert whole["structure"]["guided"] is True
        assert whole["structure"]["anchor_offset_s"] > 0

    def test_two_rides_ask_which_one_to_bucket(self, gdb):
        answer = get_aligned_timeseries(gdb, "guided-two")
        assert answer["needs_exercise_key"] is True
        assert "rows" not in answer


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

    def test_markers_crop_with_the_guided_window(self, empty_hr_db):
        """The analysis window is now the guided ride's, and the markers crop
        to it: a toggle from before START belongs to the capture, not the ride,
        and offsets re-anchor to the window's first beat — the same t0 the rows
        use, so a marker and a bucket at one offset are one instant."""
        _write(
            empty_hr_db,
            intervals=_blocks("s", T0, [(120, 60), (140, 180)], device_id="EE:FF"),
            guide_events=[_guide(
                "a", "s", "ex", "start", T0 + 60_000,
                timeline_json='[{"duration_sec":120,"hr_min":130}]')],
        )
        conn = sqlite3.connect(empty_hr_db)
        conn.executemany(
            "INSERT INTO set_events (event_id, date, exercise_key, set_num, "
            "item_key, action, client_timestamp_ms, session_id, received_at) "
            "VALUES (?, '2030-01-01', ?, NULL, NULL, 'check', ?, 's', ?)",
            [("before", "ex_early", T0 + 10_000, _RECEIVED_AT),
             ("inside", "ex_mid", T0 + 90_000, _RECEIVED_AT)],
        )
        conn.commit()
        conn.close()

        db = DatabaseManager(MCPConfig.from_db_path(empty_hr_db))
        series = get_aligned_timeseries(db, "s")
        assert [m["exercise_key"] for m in series["set_events"]] == ["ex_mid"]
        assert 0 <= series["set_events"][0]["offset_s"] <= 120

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
        """PORTED: test_get_vo2_summary_matches_intent — the crop that used to
        align the plan retired with the rest of the time-window surface, so the
        fixture whose first beat IS its first effort is what the plan is
        matched against."""
        result = get_vo2_summary(
            db,
            "vo2-aligned",
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

        assert result["structure"]["source"] == "supplied_intent"
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

    def test_agreement_holds_on_a_guided_session(self, gdb):
        """Same agreement when the window is derived rather than whole: the one
        shared `_load_session` must narrow both tools identically."""
        report = get_session_report(gdb, "guided-one", hrmax=188)
        result = get_vo2_summary(gdb, "guided-one", hrmax=188)
        assert result["analysis_window"] == report["analysis_window"]
        assert result["quality"] == report["quality"]
        assert result["hr"] == report["hr"]
        assert result["structure"] == report["structure"]

    def test_a_guided_session_needs_no_matching(self, gdb):
        """Its structure is recorded, so there is nothing to match: the
        recorded reading replaces `vo2` entirely rather than sitting beside a
        second, invented one."""
        result = get_vo2_summary(gdb, "guided-one")
        assert "vo2" not in result
        assert result["structure"]["guided"] is True
        assert result["timeseries"]["rows"]

    @pytest.mark.parametrize("intent", [
        {"rounds": 3, "work_duration_s": 60, "rest_duration_s": 45},
        {"target_hr_min": 140},          # bounds alone are ignored too
    ])
    def test_a_supplied_intent_is_ignored_out_loud(self, gdb, intent):
        """Ignoring it silently would leave a caller believing their plan drove
        the numbers."""
        result = get_vo2_summary(gdb, "guided-one", intent=intent)
        assert result["structure"]["supplied_intent_ignored"] is True
        assert "intent" not in result["structure"]

    def test_no_intent_is_not_reported_as_ignored(self, gdb):
        assert "supplied_intent_ignored" not in get_vo2_summary(
            gdb, "guided-one")["structure"]

    def test_two_rides_ask_before_matching_anything(self, gdb):
        answer = get_vo2_summary(gdb, "guided-two")
        assert answer["needs_exercise_key"] is True
        assert "vo2" not in answer


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

    def test_every_registered_tool_threads_the_exercise_key(self, guided_db):
        """The disambiguation argument is copy-paste shaped across four
        wrappers — one that dropped it would silently ask the caller a question
        they had already answered."""
        tools = _extract_tools(create_mcp_server(MCPConfig.from_db_path(guided_db)))

        for name, call in (
            ("get_session_report", lambda: tools["get_session_report"](
                "guided-two", exercise_key="fixture-zone2")),
            ("get_aligned_timeseries", lambda: tools["get_aligned_timeseries"](
                "guided-two", exercise_key="fixture-zone2")),
            ("get_vo2_summary", lambda: tools["get_vo2_summary"](
                "guided-two", exercise_key="fixture-zone2")),
        ):
            result = call()
            assert result["structure"]["exercise_key"] == "fixture-zone2", name
        # The latest session is the guided one; its lone ride needs no key.
        latest = tools["get_latest_session_report"]()
        assert latest["structure"]["guided"] is True

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
