"""Tests for the hr.db reader.

This is the layer the pulse-bridge migration actually rewrote — the rest of the
package was carried over unchanged — so the schema mapping, the `seq` ordering,
and the read-only/absent-database behaviour are pinned here rather than assumed.
"""
import sqlite3

import pytest

from hr_analysis.db import (
    HrDataUnavailable,
    guide_events_by_session,
    latest_session,
    list_sessions,
    load_beats,
    load_guide_events,
    session_devices,
)

from .conftest import TIMELINE_JSON, T0, beat_rows, guide_row


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

    def test_unknown_session_is_empty_not_an_error(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 3))
        assert load_beats(session_id="nope", db_path=db_path) == []


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
class TestRetiredTimeWindowSurface:
    """Retrieval is by session id, ALWAYS (2026-08-22 ruling).

    The wall-clock window these two functions used to accept is gone from the
    package, and this is what keeps it gone: the reason is not ergonomic but
    architectural — either wellness owns a ride end to end and the session id is
    its key, or the ride lives on the watch and this database has nothing to say
    about it. A caller who could pass a Garmin activity's start time would be
    inviting the cross-check the two-case model does not do.

    Beats captured before session ids existed stay stored and stay unreachable
    here. That is the accepted cost, not an oversight.
    """

    def test_beats_cannot_be_asked_for_by_time(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 5))
        with pytest.raises(TypeError):
            load_beats(start_ms=T0, end_ms=T0 + 4000, db_path=db_path)

    def test_the_listing_takes_no_window(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 3))
        with pytest.raises(TypeError):
            list_sessions(start_ms=T0, db_path=db_path)
        with pytest.raises(TypeError):
            list_sessions(end_ms=T0, db_path=db_path)


@pytest.fixture
def legacy_hr_db(tmp_path):
    """A database holding REAL pre-session-era beats, alongside modern ones.

    Built by hand rather than through `init_database`, because that is what
    makes it legacy: today's `intervals.session_id` is NOT NULL, and the rows
    this guards against were written before it was. The columns the reader
    touches are the same; only the constraint is the old one.
    """
    db_path = tmp_path / "hr.db"
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("""
            CREATE TABLE intervals (
                device_id      TEXT    NOT NULL,
                timestamp_ms   INTEGER NOT NULL,
                seq            INTEGER NOT NULL,
                heart_rate_bpm INTEGER NOT NULL,
                rr_interval_ms INTEGER NOT NULL,
                is_gap_before  INTEGER NOT NULL DEFAULT 0,
                session_id     TEXT,
                sensor_type    TEXT    NOT NULL DEFAULT 'garmin_hrm',
                received_at    TEXT    NOT NULL,
                PRIMARY KEY (device_id, timestamp_ms, seq)
            )
        """)
        conn.execute("""
            CREATE TABLE sessions (
                session_id         TEXT PRIMARY KEY,
                device_id          TEXT    NOT NULL,
                started_at_ms      INTEGER NOT NULL,
                ended_at_ms        INTEGER,
                workout_date       TEXT,
                workout_session_id INTEGER,
                received_at        TEXT    NOT NULL
            )
        """)
        conn.executemany(
            "INSERT INTO intervals (device_id, timestamp_ms, seq, heart_rate_bpm,"
            " rr_interval_ms, is_gap_before, session_id, sensor_type, received_at)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, 'garmin_hrm', 'x')",
            # Three sessionless beats, deliberately the NEWEST in the file, so a
            # listing that leaked them would put the phantom at the top and
            # `latest_session` would hand it straight on.
            [("dev-a", T0 + 900_000 + i * 800, 0, 70, 800, 0, None) for i in range(3)]
            + [("dev-a", T0 + i * 800, 0, 70, 800, 0, "s-1") for i in range(3)],
        )
        conn.commit()
    finally:
        conn.close()
    return db_path


@pytest.mark.unit
class TestLegacySessionlessBeats:
    """Beats from before session ids are stored, and stay unreachable.

    The user ruling: there is little of that data and an access path for it
    would be the very time-window surface the retrieval ruling retired. What it
    must NOT do is surface as a phantom session — `GROUP BY session_id` would
    otherwise collect every sessionless beat ever captured into one row whose id
    is None, which no other tool can be given (`WHERE session_id = NULL` matches
    nothing) and which the CLI's fixed-width formatter cannot even print.
    """

    def test_the_listing_has_no_phantom_session(self, legacy_hr_db):
        rows = list_sessions(db_path=legacy_hr_db)
        assert [row[0] for row in rows] == ["s-1"]
        assert all(row["session_id"] is not None for row in rows)

    def test_latest_session_never_returns_the_phantom(self, legacy_hr_db):
        """The one that would have hurt: `--latest` and
        `get_latest_session_report` both start here."""
        assert latest_session(db_path=legacy_hr_db) == "s-1"

    def test_the_rows_are_still_stored(self, legacy_hr_db):
        """Unreachable, not deleted — this package never writes."""
        conn = sqlite3.connect(legacy_hr_db)
        try:
            count = conn.execute(
                "SELECT COUNT(*) FROM intervals WHERE session_id IS NULL").fetchone()[0]
        finally:
            conn.close()
        assert count == 3

    def test_they_cannot_be_loaded_by_asking_for_no_session(self, legacy_hr_db):
        assert load_beats(None, db_path=legacy_hr_db) == []


@pytest.mark.unit
class TestLoadGuideEvents:
    """The cardio guide's recorded actions — the structure half of a ride."""

    def test_maps_every_column_of_a_start(self, hr_db):
        db_path, insert = hr_db
        insert(guide_events=[guide_row("g-1", "start", T0 + 1000,
                                       timeline_json=TIMELINE_JSON)])
        event, = load_guide_events("s-1", db_path=db_path)
        assert event == {
            "client_timestamp_ms": T0 + 1000,
            "exercise_key": "fixture-ride",
            "action": "start",
            "extension_sec": None,
            "timeline_json": TIMELINE_JSON,
            "date": "2030-01-01",
            "event_id": "g-1",
        }

    def test_orders_by_client_stamp_then_id(self, hr_db):
        """The client stamp is what the derivation measures against, and the id
        breaks a same-millisecond tie — so "the latest start" cannot depend on
        the order SQLite happened to store rows in."""
        db_path, insert = hr_db
        insert(guide_events=[
            guide_row("g-c", "extend", T0 + 5000, extension_sec=300),
            guide_row("g-b", "start", T0 + 1000, timeline_json="[]"),
            guide_row("g-a", "start", T0 + 1000, timeline_json="[]"),
        ])
        events = load_guide_events("s-1", db_path=db_path)
        assert [e["event_id"] for e in events] == ["g-a", "g-b", "g-c"]

    def test_selects_only_the_requested_session(self, hr_db):
        db_path, insert = hr_db
        insert(guide_events=[
            guide_row("g-1", "start", T0, timeline_json="[]"),
            guide_row("g-2", "start", T0, session_id="s-2", timeline_json="[]"),
        ])
        assert [e["event_id"] for e in load_guide_events("s-1", db_path=db_path)] == ["g-1"]

    def test_a_session_with_no_guide_events_is_empty_not_an_error(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 3))
        assert load_guide_events("s-1", db_path=db_path) == []


@pytest.mark.unit
class TestGuideEventsBySession:
    """The listing's bulk read: one query for many sessions, not one each."""

    def test_groups_events_under_their_session(self, hr_db):
        db_path, insert = hr_db
        insert(guide_events=[
            guide_row("g-1", "start", T0, timeline_json="[]"),
            guide_row("g-2", "extend", T0 + 60_000, extension_sec=300),
            guide_row("g-3", "start", T0 + 1000, session_id="s-2", timeline_json="[]"),
        ])
        grouped = guide_events_by_session(["s-1", "s-2"], db_path=db_path)
        assert [e["event_id"] for e in grouped["s-1"]] == ["g-1", "g-2"]
        assert [e["event_id"] for e in grouped["s-2"]] == ["g-3"]

    def test_a_session_without_events_is_absent_rather_than_empty(self, hr_db):
        """The caller's `.get(sid, [])` is the "not a guided ride" answer, so a
        row of nothing would only cost bytes."""
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 3))
        assert guide_events_by_session(["s-1"], db_path=db_path) == {}

    def test_no_session_ids_asks_nothing(self, hr_db):
        """An empty listing must not build `IN ()`, which is a syntax error."""
        db_path, _ = hr_db
        assert guide_events_by_session([], db_path=db_path) == {}


@pytest.mark.unit
class TestSessionDevices:
    def test_returns_distinct_device_sensor_pairs(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=(beat_rows("s-1", 3)
                          + beat_rows("s-1", 3, device_id="dev-b", t0=T0 + 10_000)))
        assert [tuple(row) for row in session_devices("s-1", db_path=db_path)] == [
            ("dev-a", "garmin_hrm"), ("dev-b", "garmin_hrm")]

    def test_unknown_session_has_no_devices(self, hr_db):
        db_path, insert = hr_db
        insert(intervals=beat_rows("s-1", 3))
        assert session_devices("nope", db_path=db_path) == []


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
