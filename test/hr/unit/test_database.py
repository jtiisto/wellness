"""Unit tests for HR database initialization (baseline + guide events)."""
import sqlite3

import pytest

from modules.db import DbAccessor, enable_wal, get_db, run_migrations
from modules.hr import MIGRATIONS, init_database


def _fresh(db_path):
    """Initialize a standalone hr DB (no app), returning its accessor."""
    accessor = DbAccessor(db_path)
    init_database(accessor)
    return accessor


@pytest.mark.unit
class TestInitDatabase:
    def test_creates_all_tables(self, tmp_path):
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        with get_db(db_path) as conn:
            tables = {r[0] for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'")}
        assert {"intervals", "set_events", "sessions", "guide_events"} <= tables

    def test_creates_indexes(self, tmp_path):
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        with get_db(db_path) as conn:
            indexes = {r[0] for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='index'")}
        assert {"idx_intervals_session", "idx_set_events_date",
                "idx_guide_events_date"} <= indexes

    def test_upgrades_a_baseline_database_in_place(self, tmp_path):
        """The guide-events table arrives by migration, not by re-baselining: a
        database stamped at version 1 gains it with its existing rows intact."""
        db_path = tmp_path / "hr.db"
        accessor = DbAccessor(db_path)
        with accessor.get_db() as conn:
            enable_wal(conn)
            run_migrations(conn, MIGRATIONS[:1], label="HR DB")
            conn.execute(
                "INSERT INTO set_events (event_id, date, exercise_key, action,"
                " client_timestamp_ms, received_at)"
                " VALUES ('evt-kept', '2030-01-03', 'fixture-adhoc-lift', 'check',"
                " 1770000010000, '2030-01-03T00:00:00Z')")
            conn.commit()

        init_database(accessor)

        with get_db(db_path) as conn:
            assert conn.execute("PRAGMA user_version").fetchone()[0] == len(MIGRATIONS)
            assert conn.execute(
                "SELECT COUNT(*) FROM set_events").fetchone()[0] == 1
            assert conn.execute(
                "SELECT COUNT(*) FROM guide_events").fetchone()[0] == 0

    def test_stamps_user_version_and_enables_wal(self, tmp_path):
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        with get_db(db_path) as conn:
            assert conn.execute("PRAGMA user_version").fetchone()[0] == len(MIGRATIONS)
            assert conn.execute("PRAGMA journal_mode").fetchone()[0].lower() == "wal"

    def test_idempotent(self, tmp_path):
        """Re-init at the current version is a no-op — the baseline's plain
        CREATEs (no IF NOT EXISTS) would raise if the registry gate let them
        run twice."""
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        _fresh(db_path)  # must not raise
        with get_db(db_path) as conn:
            assert conn.execute("PRAGMA user_version").fetchone()[0] == len(MIGRATIONS)


@pytest.mark.unit
class TestSchema:
    def test_intervals_primary_key_is_device_timestamp_seq(self, tmp_path):
        """The PK is what makes a re-uploaded batch idempotent (Stage 2's
        INSERT OR IGNORE), and `seq` is what lets two beats share a millisecond."""
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        with get_db(db_path) as conn:
            info = list(conn.execute("PRAGMA table_info(intervals)"))
        # table_info's `pk` is the 1-based position within the primary key.
        pk = [r["name"] for r in sorted((r for r in info if r["pk"]),
                                        key=lambda r: r["pk"])]
        assert pk == ["device_id", "timestamp_ms", "seq"]

    def test_intervals_columns(self, tmp_path):
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        with get_db(db_path) as conn:
            cols = {r["name"]: r["type"] for r in
                    conn.execute("PRAGMA table_info(intervals)")}
        assert cols == {
            "device_id": "TEXT", "timestamp_ms": "INTEGER", "seq": "INTEGER",
            "heart_rate_bpm": "INTEGER", "rr_interval_ms": "INTEGER",
            "is_gap_before": "INTEGER", "session_id": "TEXT",
            "sensor_type": "TEXT", "received_at": "TEXT",
        }

    def test_interval_defaults(self, tmp_path):
        """is_gap_before and sensor_type carry the wire's omitted-field defaults."""
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        with get_db(db_path) as conn:
            conn.execute(
                "INSERT INTO intervals (device_id, timestamp_ms, seq, heart_rate_bpm,"
                " rr_interval_ms, session_id, received_at)"
                " VALUES ('AA:BB:CC:DD:EE:FF', 1770000000000, 0, 142, 423,"
                " 'sess-fixture-1', '2026-08-10T00:00:00Z')")
            row = conn.execute(
                "SELECT is_gap_before, sensor_type FROM intervals").fetchone()
        assert row["is_gap_before"] == 0
        assert row["sensor_type"] == "garmin_hrm"

    def test_set_events_columns_and_pk(self, tmp_path):
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        with get_db(db_path) as conn:
            info = list(conn.execute("PRAGMA table_info(set_events)"))
        cols = {r["name"]: r["type"] for r in info}
        assert cols == {
            "event_id": "TEXT", "date": "TEXT", "exercise_key": "TEXT",
            "set_num": "INTEGER", "item_key": "TEXT", "action": "TEXT",
            "client_timestamp_ms": "INTEGER", "session_id": "TEXT",
            "received_at": "TEXT",
        }
        assert [r["name"] for r in info if r["pk"]] == ["event_id"]

    def test_set_events_action_check_constraint(self, tmp_path):
        """`action` is an enum on the wire; the CHECK keeps it one in storage."""
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        with get_db(db_path) as conn:
            for action in ("check", "uncheck"):
                conn.execute(
                    "INSERT INTO set_events (event_id, date, exercise_key, action,"
                    " client_timestamp_ms, received_at)"
                    " VALUES (?, '2030-01-03', 'fixture-adhoc-lift', ?,"
                    " 1770000010000, '2026-08-10T00:00:00Z')",
                    (f"evt-{action}", action))
            with pytest.raises(sqlite3.IntegrityError):
                conn.execute(
                    "INSERT INTO set_events (event_id, date, exercise_key, action,"
                    " client_timestamp_ms, received_at)"
                    " VALUES ('evt-bogus', '2030-01-03', 'fixture-adhoc-lift',"
                    " 'toggle', 1770000010000, '2026-08-10T00:00:00Z')")

    def test_guide_events_columns_and_pk(self, tmp_path):
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        with get_db(db_path) as conn:
            info = list(conn.execute("PRAGMA table_info(guide_events)"))
        cols = {r["name"]: r["type"] for r in info}
        assert cols == {
            "event_id": "TEXT", "date": "TEXT", "exercise_key": "TEXT",
            "action": "TEXT", "client_timestamp_ms": "INTEGER",
            "session_id": "TEXT", "extension_sec": "INTEGER",
            "timeline_json": "TEXT", "received_at": "TEXT",
        }
        assert [r["name"] for r in info if r["pk"]] == ["event_id"]

    def test_guide_events_session_id_is_not_null(self, tmp_path):
        """The session is the recording precondition, so the column cannot be
        empty: a guide action without a capture is never written at all.

        Only the two action-specific payload columns are nullable. `event_id` is
        absent from the declared-NOT NULL set for SQLite's own reason — a
        non-INTEGER PRIMARY KEY column is nullable in the legacy behaviour every
        table here relies on — and is covered by the primary-key assertion above.
        """
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        with get_db(db_path) as conn:
            nullable = {r["name"] for r in
                        conn.execute("PRAGMA table_info(guide_events)")
                        if not r["notnull"]}
        assert nullable == {"event_id", "extension_sec", "timeline_json"}

    def test_guide_events_action_check_constraint(self, tmp_path):
        """`action` is a two-value enum on the wire; the CHECK keeps it one in
        storage — and `check`, the sibling table's verb, is not one of them."""
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        with get_db(db_path) as conn:
            for action in ("start", "extend"):
                conn.execute(
                    "INSERT INTO guide_events (event_id, date, exercise_key, action,"
                    " client_timestamp_ms, session_id, received_at)"
                    " VALUES (?, '2030-01-03', 'ex_ride', ?, 1770000010000,"
                    " 'sess-fixture-1', '2030-01-03T00:00:00Z')",
                    (f"guide-{action}", action))
            with pytest.raises(sqlite3.IntegrityError):
                conn.execute(
                    "INSERT INTO guide_events (event_id, date, exercise_key, action,"
                    " client_timestamp_ms, session_id, received_at)"
                    " VALUES ('guide-bogus', '2030-01-03', 'ex_ride', 'check',"
                    " 1770000010000, 'sess-fixture-1', '2030-01-03T00:00:00Z')")

    def test_sessions_columns_and_pk(self, tmp_path):
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        with get_db(db_path) as conn:
            info = list(conn.execute("PRAGMA table_info(sessions)"))
        cols = {r["name"]: r["type"] for r in info}
        assert cols == {
            "session_id": "TEXT", "device_id": "TEXT", "started_at_ms": "INTEGER",
            "ended_at_ms": "INTEGER", "workout_date": "TEXT",
            "workout_session_id": "INTEGER", "received_at": "TEXT",
        }
        assert [r["name"] for r in info if r["pk"]] == ["session_id"]

    def test_no_foreign_keys_between_tables(self, tmp_path):
        """Batches are order-independent: an interval may reference a session
        row that has not been uploaded yet, so nothing declares an FK."""
        db_path = tmp_path / "hr.db"
        _fresh(db_path)
        with get_db(db_path) as conn:
            for table in ("intervals", "set_events", "sessions", "guide_events"):
                assert list(conn.execute(f"PRAGMA foreign_key_list({table})")) == []
