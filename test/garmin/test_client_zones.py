"""The device-clock timeline the garmin module owns: the recorder's validation,
the change-point rule, the in-process tail, and the loader's degradation.

PROVENANCE: every value here is INVENTED. Zone ids are real IANA ids (they have
to be — the validator resolves them), but no observation is copied from
anywhere: stamps use the far-future 2030-01-* convention this suite uses
everywhere, so they can never collide with a real one.
"""

import sqlite3
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import pytest

from modules.db import DbAccessor
from modules.garmin import (MIGRATIONS, init_database, load_zone_timeline,
                            parse_client_clock, record_client_zone,
                            _zone_tail)

# A fixed UTC instant to stamp observations with — the recorder takes `now` as
# an argument precisely so no test needs the clock.
_T0 = datetime(2030, 1, 2, 9, 30, 0, tzinfo=timezone.utc)


@pytest.fixture
def zone_db(tmp_path):
    """A migrated garmin module DB. `tmp_path` is per-test, so the recorder's
    path-keyed tail cache always starts cold — the same isolation the strain
    memo gets from keying on the database path."""
    path = tmp_path / "garmin_module.db"
    init_database(DbAccessor(path))
    yield path
    _zone_tail.pop(str(path), None)


def _rows(path):
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    try:
        return [(r["observed_at"], r["zone_id"], r["offset_min"])
                for r in conn.execute(
                    "SELECT * FROM client_zones ORDER BY id")]
    finally:
        conn.close()


@pytest.mark.unit
class TestMigration:
    def test_table_and_index_exist(self, zone_db):
        conn = sqlite3.connect(zone_db)
        try:
            names = {r[0] for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type IN ('table','index')")}
            version = conn.execute("PRAGMA user_version").fetchone()[0]
        finally:
            conn.close()
        assert "client_zones" in names
        assert "idx_client_zones_observed_at" in names
        assert version == MIGRATIONS[-1][0]

    def test_migration_is_idempotent(self, zone_db):
        """A second app build against the same file must not re-run the DDL —
        the baseline is a plain CREATE, so a re-run would raise."""
        init_database(DbAccessor(zone_db))
        assert _rows(zone_db) == []


@pytest.mark.unit
class TestParseClientClock:
    """The ONE authority on whether a reported clock is acceptable. The app's
    middleware and the recorder both go through it, so a clock good enough to
    move a handler's calendar date is exactly the clock good enough to store —
    split validation would let a real zone id with a nonsense offset shift the
    sleep ledger while leaving no row to explain it."""

    def test_a_good_pair_resolves_to_its_zone(self):
        assert parse_client_clock("Europe/Helsinki", 120) == ZoneInfo(
            "Europe/Helsinki")

    def test_a_real_zone_with_an_impossible_offset_is_rejected(self):
        """The case the split-validation bug turned on."""
        assert parse_client_clock("Pacific/Kiritimati", 7) is None
        assert parse_client_clock("Pacific/Kiritimati", 1000) is None

    def test_an_unresolvable_id_is_rejected_however_sane_the_offset(self):
        assert parse_client_clock("Mars/Olympus", 90) is None

    def test_it_agrees_with_what_the_recorder_stores(self, zone_db):
        """The two must never diverge: whatever this accepts is what lands in
        the table, and whatever it refuses writes nothing."""
        for zone_id, offset in (("Europe/Helsinki", 120),
                                ("Pacific/Kiritimati", 7),
                                ("Mars/Olympus", 90)):
            accepted = parse_client_clock(zone_id, offset) is not None
            written = record_client_zone(zone_db, zone_id, offset, _T0)
            assert written == accepted, (zone_id, offset)
            _zone_tail.pop(str(zone_db), None)


@pytest.mark.unit
class TestRecorderValidation:
    """A header is advisory data on a request about something else. A bad one
    is dropped silently — never a 4xx, never a stored row."""

    @pytest.mark.parametrize("zone_id,offset_min", [
        ("Europe/Helsinki", 120),
        ("UTC", 0),
        ("Pacific/Kiritimati", 840),       # the eastern extreme
        ("Etc/GMT+12", -720),              # the western one
        ("Asia/Kathmandu", 345),           # a quarter-hour offset
        ("Pacific/Chatham", 765),          # and another
    ])
    def test_valid_pairs_are_stored(self, zone_db, zone_id, offset_min):
        assert record_client_zone(zone_db, zone_id, offset_min, _T0) is True
        assert _rows(zone_db) == [("2030-01-02T09:30:00", zone_id, offset_min)]

    @pytest.mark.parametrize("zone_id,offset_min", [
        ("Not/AZone", 120),                # an id zoneinfo cannot resolve
        ("", 120),                         # empty
        (None, 120),                       # absent
        (b"Europe/Helsinki", 120),         # not a str
        ("../../../etc/passwd", 0),        # a path, not a key
        ("Europe/Helsinki", 900),          # beyond +14 h
        ("Europe/Helsinki", -900),         # beyond -14 h
        ("Europe/Helsinki", 7),            # not a quarter hour
        ("Europe/Helsinki", "120"),        # not an int
        ("Europe/Helsinki", 120.0),        # nor a float
        ("Europe/Helsinki", None),
        ("Europe/Helsinki", True),         # a bool is an int; not one of these
    ])
    def test_invalid_pairs_write_nothing(self, zone_db, zone_id, offset_min):
        assert record_client_zone(zone_db, zone_id, offset_min, _T0) is False
        assert _rows(zone_db) == []

    def test_naive_now_is_stamped_as_utc(self, zone_db):
        """The repo's rule for a naive instant, and the only stamp format the
        loader promises to read back."""
        record_client_zone(zone_db, "Europe/Helsinki", 120,
                           datetime(2030, 1, 2, 9, 30, 0))
        assert _rows(zone_db)[0][0] == "2030-01-02T09:30:00"

    def test_offset_bearing_now_is_converted(self, zone_db):
        record_client_zone(
            zone_db, "Europe/Helsinki", 120,
            datetime(2030, 1, 2, 11, 30, 0,
                     tzinfo=timezone(timedelta(hours=2))))
        assert _rows(zone_db)[0][0] == "2030-01-02T09:30:00"


@pytest.mark.unit
class TestChangePointsOnly:
    """The table is a timeline of CHANGES, not a request log: the steady state
    (one phone, one zone, all day) must cost no I/O at all."""

    def test_the_same_pair_twice_writes_once(self, zone_db):
        assert record_client_zone(zone_db, "Europe/Helsinki", 120, _T0) is True
        later = _T0 + timedelta(hours=3)
        assert record_client_zone(zone_db, "Europe/Helsinki", 120, later) is False
        assert len(_rows(zone_db)) == 1

    def test_a_different_zone_opens_a_new_point(self, zone_db):
        record_client_zone(zone_db, "Europe/Helsinki", 120, _T0)
        later = _T0 + timedelta(days=1)
        assert record_client_zone(zone_db, "Asia/Tokyo", 540, later) is True
        assert _rows(zone_db) == [
            ("2030-01-02T09:30:00", "Europe/Helsinki", 120),
            ("2030-01-03T09:30:00", "Asia/Tokyo", 540),
        ]

    def test_the_same_zone_at_a_new_offset_opens_one_too(self, zone_db):
        """DST inside one zone is a change point like any other — the offset is
        half the pair, so a spring-forward is recorded."""
        record_client_zone(zone_db, "Europe/Helsinki", 120, _T0)
        assert record_client_zone(
            zone_db, "Europe/Helsinki", 180, _T0 + timedelta(days=90)) is True
        assert [r[2] for r in _rows(zone_db)] == [120, 180]

    def test_the_tail_is_in_process_not_re_read(self, zone_db):
        """Deleting the row behind the recorder's back changes nothing: the
        comparison is against the cached tail, which is what makes the steady
        state free."""
        record_client_zone(zone_db, "Europe/Helsinki", 120, _T0)
        conn = sqlite3.connect(zone_db)
        conn.execute("DELETE FROM client_zones")
        conn.commit()
        conn.close()
        assert record_client_zone(zone_db, "Europe/Helsinki", 120, _T0) is False
        assert _rows(zone_db) == []

    def test_a_cold_process_adopts_the_stored_tail(self, zone_db):
        """First call for a path loads the tail from the database, so a restart
        does not re-record the zone the timeline already ends with."""
        record_client_zone(zone_db, "Europe/Helsinki", 120, _T0)
        _zone_tail.pop(str(zone_db))          # a fresh process
        assert record_client_zone(zone_db, "Europe/Helsinki", 120, _T0) is False
        assert len(_rows(zone_db)) == 1

    def test_a_cold_process_still_records_a_change(self, zone_db):
        record_client_zone(zone_db, "Europe/Helsinki", 120, _T0)
        _zone_tail.pop(str(zone_db))
        assert record_client_zone(
            zone_db, "Asia/Tokyo", 540, _T0 + timedelta(hours=1)) is True
        assert len(_rows(zone_db)) == 2

    def test_two_databases_keep_separate_tails(self, tmp_path):
        """Path-keyed, like the strain memo: one test's database must never
        answer for another's."""
        first, second = tmp_path / "a.db", tmp_path / "b.db"
        init_database(DbAccessor(first))
        init_database(DbAccessor(second))
        try:
            assert record_client_zone(first, "Europe/Helsinki", 120, _T0) is True
            assert record_client_zone(second, "Europe/Helsinki", 120, _T0) is True
            assert len(_rows(first)) == 1
            assert len(_rows(second)) == 1
        finally:
            _zone_tail.pop(str(first), None)
            _zone_tail.pop(str(second), None)


@pytest.mark.unit
class TestRecorderNeverRaises:
    def test_absent_database_is_not_created(self, tmp_path):
        """The module owns this file's creation. Writing here would leave an
        empty, table-less database every later read has to survive."""
        missing = tmp_path / "never-migrated.db"
        assert record_client_zone(missing, "Europe/Helsinki", 120, _T0) is False
        assert not missing.exists()
        _zone_tail.pop(str(missing), None)

    def test_database_without_the_table_degrades(self, tmp_path):
        foreign = tmp_path / "foreign.db"
        conn = sqlite3.connect(foreign)
        conn.execute("CREATE TABLE unrelated (id INTEGER PRIMARY KEY)")
        conn.commit()
        conn.close()
        assert record_client_zone(foreign, "Europe/Helsinki", 120, _T0) is False
        _zone_tail.pop(str(foreign), None)


@pytest.mark.unit
class TestLoadZoneTimeline:
    def test_absent_database_is_no_observations(self, tmp_path):
        assert load_zone_timeline(tmp_path / "nope.db") == []

    def test_none_path_is_no_observations(self):
        assert load_zone_timeline(None) == []

    def test_database_without_the_table_is_no_observations(self, tmp_path):
        path = tmp_path / "foreign.db"
        sqlite3.connect(path).close()
        assert load_zone_timeline(path) == []

    def test_empty_table_is_no_observations(self, zone_db):
        assert load_zone_timeline(zone_db) == []

    def test_points_come_back_oldest_first_as_aware_utc(self, zone_db):
        record_client_zone(zone_db, "Europe/Helsinki", 120, _T0)
        record_client_zone(zone_db, "Asia/Tokyo", 540, _T0 + timedelta(days=1))
        points = load_zone_timeline(zone_db)
        assert points == [
            (_T0, "Europe/Helsinki", 120),
            (_T0 + timedelta(days=1), "Asia/Tokyo", 540),
        ]
        assert all(p[0].tzinfo is timezone.utc for p in points)

    def test_out_of_order_rows_are_sorted(self, zone_db):
        conn = sqlite3.connect(zone_db)
        conn.executemany(
            "INSERT INTO client_zones (observed_at, zone_id, offset_min) "
            "VALUES (?, ?, ?)",
            [("2030-01-05T00:00:00", "Asia/Tokyo", 540),
             ("2030-01-04T00:00:00", "Europe/Helsinki", 120)])
        conn.commit()
        conn.close()
        assert [p[1] for p in load_zone_timeline(zone_db)] == [
            "Europe/Helsinki", "Asia/Tokyo"]

    def test_unreadable_rows_are_skipped_not_fatal(self, zone_db):
        """One corrupt row must not cost the whole timeline — the alternative
        is silently reverting every reader to the server's zone."""
        conn = sqlite3.connect(zone_db)
        conn.executemany(
            "INSERT INTO client_zones (observed_at, zone_id, offset_min) "
            "VALUES (?, ?, ?)",
            [("not-a-timestamp", "Asia/Tokyo", 540),
             ("2030-01-04T00:00:00", "Europe/Helsinki", 120)])
        conn.commit()
        conn.close()
        assert [p[1] for p in load_zone_timeline(zone_db)] == ["Europe/Helsinki"]

    def test_an_unknown_zone_id_still_loads(self, zone_db):
        """The loader stores what the phone said; resolving it (and falling
        back to the offset) is ZoneTimeline's job, not this one's."""
        conn = sqlite3.connect(zone_db)
        conn.execute(
            "INSERT INTO client_zones (observed_at, zone_id, offset_min) "
            "VALUES ('2030-01-04T00:00:00', 'Mars/Olympus', 90)")
        conn.commit()
        conn.close()
        assert load_zone_timeline(zone_db) == [
            (datetime(2030, 1, 4, tzinfo=timezone.utc), "Mars/Olympus", 90)]
