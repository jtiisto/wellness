"""Tests for the hr.db reader.

This is the layer the pulse-bridge migration actually rewrote — the rest of the
package was carried over unchanged — so the schema mapping, the `seq` ordering,
and the read-only/absent-database behaviour are pinned here rather than assumed.
"""
import sqlite3

import pytest

from hr_analysis.db import (
    HrDataUnavailable,
    latest_session,
    list_sessions,
    load_beats,
)

from .conftest import T0, beat_rows


@pytest.mark.unit
class TestLoadBeats:
    def test_maps_every_column_onto_the_beat_fields(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=[("dev-a", T0 + 800, 0, 74, 812, 1, "s-1", "garmin_hrm", "x")])

        beat, = load_beats(session_id="s-1", db_path=db_path)
        assert (beat.ts_ms, beat.rr_ms, beat.hr_bpm) == (T0 + 800, 812, 74)
        # is_gap_before is stored as an INTEGER; the analysis wants a real bool
        assert beat.is_gap is True

    def test_is_gap_defaults_to_false(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 3))
        assert [b.is_gap for b in load_beats(session_id="s-1", db_path=db_path)] == \
            [False, False, False]

    def test_orders_by_timestamp(self, hr_db):
        db_path, insert = hr_db
        # Insert out of order — SQL, not insertion order, decides
        insert(intervals=[
            ("dev-a", T0 + 3000, 0, 70, 800, 0, "s-1", "garmin_hrm", "x"),
            ("dev-a", T0 + 1000, 0, 72, 800, 0, "s-1", "garmin_hrm", "x"),
            ("dev-a", T0 + 2000, 0, 71, 800, 0, "s-1", "garmin_hrm", "x"),
        ])
        assert [b.ts_ms for b in load_beats(session_id="s-1", db_path=db_path)] == \
            [T0 + 1000, T0 + 2000, T0 + 3000]

    def test_seq_breaks_ties_within_one_millisecond(self, hr_db):
        """Two beats can share a receipt millisecond; `seq` is their true order.

        The pulse-bridge capture stack instead bumped a colliding timestamp
        forward by 1 ms, which silently falsified the inter-beat delta this
        package measures. Ordering on (timestamp_ms, seq) keeps the order
        without touching the timestamps — but only if the sort is on BOTH
        columns, which is what this pins.
        """
        db_path, insert = hr_db
        insert(intervals=[
            ("dev-a", T0 + 1000, 2, 70, 803, 0, "s-1", "garmin_hrm", "x"),
            ("dev-a", T0 + 1000, 0, 72, 801, 0, "s-1", "garmin_hrm", "x"),
            ("dev-a", T0 + 1000, 1, 71, 802, 0, "s-1", "garmin_hrm", "x"),
        ])
        beats = load_beats(session_id="s-1", db_path=db_path)
        assert [b.rr_ms for b in beats] == [801, 802, 803]
        # The timestamps are untouched — no monotonic bump was applied
        assert {b.ts_ms for b in beats} == {T0 + 1000}

    def test_selects_only_the_requested_session(self, hr_db):
        # One device's sessions never overlap in time, and the intervals PK
        # (device_id, timestamp_ms, seq) enforces exactly that — hence the offset
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 4)
               + beat_rows("s-2", 7, hr_bpm=150, t0=T0 + 100_000))
        assert len(load_beats(session_id="s-1", db_path=db_path)) == 4
        assert len(load_beats(session_id="s-2", db_path=db_path)) == 7

    def test_time_range_selection_is_inclusive(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 5))  # beats at T0+800 .. T0+4000
        beats = load_beats(start_ms=T0 + 1600, end_ms=T0 + 3200, db_path=db_path)
        assert [b.ts_ms for b in beats] == [T0 + 1600, T0 + 2400, T0 + 3200]

    def test_time_range_spans_sessions(self, hr_db):
        # A range query is deliberately session-agnostic — it is how a caller
        # analyses a wall-clock window that straddles a reconnect
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 3) + beat_rows("s-2", 3, t0=T0 + 10_000))
        beats = load_beats(start_ms=T0, end_ms=T0 + 99_999, db_path=db_path)
        assert len(beats) == 6

    def test_unknown_session_is_empty_not_an_error(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 3))
        assert load_beats(session_id="nope", db_path=db_path) == []

    def test_requires_a_session_or_a_range(self, hr_db):
        db_path, _ = hr_db
        with pytest.raises(ValueError, match="session_id or"):
            load_beats(db_path=db_path)
        with pytest.raises(ValueError, match="session_id or"):
            load_beats(start_ms=T0, db_path=db_path)


@pytest.mark.unit
class TestListSessions:
    def test_derives_start_end_and_beat_count_from_intervals(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 5))
        (sid, device, start, end, beats, workout), = list_sessions(db_path=db_path)
        assert (sid, device, beats) == ("s-1", "dev-a", 5)
        assert (start, end) == (T0 + 800, T0 + 4000)
        assert workout is None

    def test_decorates_with_workout_date_when_the_session_row_exists(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 3),
               sessions=[("s-1", "dev-a", T0, T0 + 3000, "2030-03-04", 41, "x")])
        assert list_sessions(db_path=db_path)[0][5] == "2030-03-04"

    def test_lists_a_session_whose_sessions_row_never_arrived(self, hr_db):
        """The three batch endpoints are independent: beats can land while the
        sessions upload is still queued or has failed. Listing off `intervals`
        (not `sessions`) is what keeps that data visible."""
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-orphan", 3))
        assert [row[0] for row in list_sessions(db_path=db_path)] == ["s-orphan"]

    def test_newest_first(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=(beat_rows("older", 3)
                          + beat_rows("newer", 3, t0=T0 + 500_000)))
        assert [row[0] for row in list_sessions(db_path=db_path)] == ["newer", "older"]

    def test_limit_caps_the_listing(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=[r for i in range(4)
                          for r in beat_rows(f"s-{i}", 2, t0=T0 + i * 100_000)])
        assert len(list_sessions(limit=2, db_path=db_path)) == 2

    def test_empty_database_lists_nothing(self, hr_db):
        db_path, _ = hr_db
        assert list_sessions(db_path=db_path) == []


@pytest.mark.unit
class TestLatestSession:
    def test_returns_the_most_recently_started(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=(beat_rows("older", 3)
                          + beat_rows("newer", 3, t0=T0 + 500_000)))
        assert latest_session(db_path=db_path) == "newer"

    def test_none_when_history_is_empty(self, hr_db):
        """History starts empty by design (no import from pulse-bridge) — the
        first thing the CLI ever does is hit this branch."""
        db_path, _ = hr_db
        assert latest_session(db_path=db_path) is None


@pytest.mark.unit
class TestUnavailableDatabase:
    def test_absent_database_raises_with_an_actionable_message(self, tmp_path):
        missing = tmp_path / "hr.db"
        with pytest.raises(HrDataUnavailable, match="No HR database at"):
            list_sessions(db_path=missing)

    def test_absent_database_is_not_created(self, tmp_path):
        """mode=ro is load-bearing: a plain connect would leave an empty hr.db
        behind, and the next run would report 'no sessions' instead of 'no
        database' forever after."""
        missing = tmp_path / "hr.db"
        with pytest.raises(HrDataUnavailable):
            load_beats(session_id="s-1", db_path=missing)
        assert not missing.exists()

    def test_database_without_hr_tables_says_so(self, tmp_path):
        other = tmp_path / "not-hr.db"
        conn = sqlite3.connect(other)
        conn.execute("CREATE TABLE unrelated (v TEXT)")
        conn.commit()
        conn.close()
        with pytest.raises(HrDataUnavailable, match="no HR tables"):
            list_sessions(db_path=other)

    def test_reader_cannot_write(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 2))
        load_beats(session_id="s-1", db_path=db_path)
        # The package holds no writable handle; prove the mode it opens with
        # refuses one, so an accidental UPDATE in a future query cannot land.
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        try:
            with pytest.raises(sqlite3.OperationalError, match="readonly"):
                conn.execute("DELETE FROM intervals")
        finally:
            conn.close()


@pytest.mark.unit
class TestPathResolution:
    def test_uses_hr_db_path_env_var(self, hr_db, monkeypatch):
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-env", 3))
        monkeypatch.setenv("HR_DB_PATH", str(db_path))
        assert latest_session() == "s-env"

    def test_env_var_is_read_per_call_not_at_import(self, hr_db, monkeypatch, tmp_path):
        """Resolving lazily is what lets a test (or a second environment) point
        the CLI somewhere else without re-importing, and is why importing this
        package never touches the real data/ directory."""
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-env", 3))
        monkeypatch.setenv("HR_DB_PATH", str(tmp_path / "absent.db"))
        with pytest.raises(HrDataUnavailable):
            latest_session()
        monkeypatch.setenv("HR_DB_PATH", str(db_path))
        assert latest_session() == "s-env"
