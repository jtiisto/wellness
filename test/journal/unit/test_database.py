"""Unit tests for journal database helper functions."""
import pytest
import sqlite3

from modules.db import get_db

# get_db()'s own behavior (Row factory, auto-close, busy_timeout) is covered by
# test_db.py; journal now connects through the shared db.get_db(path) directly,
# so these tests open the test's journal DB by its temp path (R2 — no module
# global to bind a no-arg get_db()).


@pytest.mark.unit
class TestInitDatabase:
    def test_creates_all_required_tables(self, test_app, tmp_journal_db):
        """init_database should create all required tables."""
        import modules.journal as journal
        with get_db(tmp_journal_db) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT name FROM sqlite_master WHERE type='table'")
            tables = {row[0] for row in cursor.fetchall()}

        expected_tables = {'clients', 'meta_sync', 'trackers', 'entries', 'sync_conflicts'}
        assert expected_tables.issubset(tables)

    def test_init_enables_wal(self, test_app, tmp_journal_db):
        """init_database switches the journal DB to WAL journal mode (R7)."""
        import modules.journal as journal
        with get_db(tmp_journal_db) as conn:
            mode = conn.execute("PRAGMA journal_mode").fetchone()[0]
        assert mode.lower() == "wal"

    def test_creates_required_indexes(self, test_app, tmp_journal_db):
        """init_database should create performance indexes."""
        import modules.journal as journal
        with get_db(tmp_journal_db) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT name FROM sqlite_master WHERE type='index'")
            indexes = {row[0] for row in cursor.fetchall()}

        expected_indexes = {
            'idx_trackers_name',
            'idx_entries_date',
            'idx_trackers_modified',
            'idx_entries_modified',
            'idx_conflicts_resolved'
        }
        assert expected_indexes.issubset(indexes)

    def test_trackers_table_has_versioning_columns(self, test_app, tmp_journal_db):
        """trackers table should have versioning columns."""
        import modules.journal as journal
        with get_db(tmp_journal_db) as conn:
            cursor = conn.cursor()
            cursor.execute("PRAGMA table_info(trackers)")
            columns = {row[1] for row in cursor.fetchall()}

        assert 'version' in columns
        assert 'last_modified_by' in columns
        assert 'last_modified_at' in columns
        assert 'deleted' in columns

    def test_archive_tables_exist(self, test_app, tmp_journal_db):
        """Migration 2 should create archive tables with the expected columns."""
        import modules.journal as journal
        with get_db(tmp_journal_db) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT name FROM sqlite_master WHERE type='table'")
            tables = {row[0] for row in cursor.fetchall()}
            assert 'entries_archive' in tables
            assert 'trackers_archive' in tables

            cursor.execute("PRAGMA table_info(entries_archive)")
            cols = {row[1] for row in cursor.fetchall()}
            assert cols == {
                'id', 'date', 'tracker_id', 'value', 'completed',
                'last_modified_at', 'superseded_at',
            }

            cursor.execute("PRAGMA table_info(trackers_archive)")
            cols = {row[1] for row in cursor.fetchall()}
            assert cols == {
                'id', 'tracker_id', 'name', 'category', 'type', 'meta_json',
                'schedule_json', 'polarity',
                'deleted', 'last_modified_at', 'superseded_at',
            }

    def test_user_version_set_to_latest_migration(self, test_app, tmp_journal_db):
        """PRAGMA user_version should reflect the latest applied migration."""
        import modules.journal as journal
        with get_db(tmp_journal_db) as conn:
            cursor = conn.cursor()
            current = cursor.execute("PRAGMA user_version").fetchone()[0]
            expected = max(v for v, _ in journal.MIGRATIONS)
            assert current == expected

    def test_trackers_have_schedule_and_polarity_columns(self, test_app, tmp_journal_db):
        """Migration 3 adds the canonical schedule_json + polarity columns."""
        with get_db(tmp_journal_db) as conn:
            cursor = conn.cursor()
            cursor.execute("PRAGMA table_info(trackers)")
            columns = {row[1] for row in cursor.fetchall()}
        assert 'schedule_json' in columns
        assert 'polarity' in columns

    def test_migration_3_backfills_from_meta_json(self, test_app, tmp_journal_db):
        """Migration 3's backfill lifts scheduleHistory/polarity out of an
        existing row's meta_json into the columns, and tolerates absent or
        malformed meta_json (leaving the columns NULL)."""
        import json
        import modules.journal as journal

        schedule = [{"effectiveFrom": "0000-01-01", "days": [1, 2, 3, 4, 5]}]
        with get_db(tmp_journal_db) as conn:
            cursor = conn.cursor()
            # Simulate legacy rows: columns NULL, fields still in meta_json.
            cursor.execute(
                "INSERT INTO trackers "
                "(id, name, category, type, meta_json, last_modified_at, deleted, "
                " schedule_json, polarity) "
                "VALUES (?, ?, ?, ?, ?, ?, 0, NULL, NULL)",
                ("t-legacy", "Legacy", "cat", "simple",
                 json.dumps({"scheduleHistory": schedule, "polarity": "negative", "unit": "x"}),
                 "2026-01-01T00:00:00Z"),
            )
            cursor.execute(
                "INSERT INTO trackers "
                "(id, name, category, type, meta_json, last_modified_at, deleted, "
                " schedule_json, polarity) "
                "VALUES (?, ?, ?, ?, ?, ?, 0, NULL, NULL)",
                ("t-nometa", "NoMeta", "cat", "simple", "{}", "2026-01-01T00:00:00Z"),
            )
            cursor.execute(
                "INSERT INTO trackers "
                "(id, name, category, type, meta_json, last_modified_at, deleted, "
                " schedule_json, polarity) "
                "VALUES (?, ?, ?, ?, ?, ?, 0, NULL, NULL)",
                ("t-bad", "BadMeta", "cat", "simple", "{not json", "2026-01-01T00:00:00Z"),
            )
            conn.commit()

            # Re-running the migration is safe (guarded ALTER, NULL-only backfill).
            journal._migration_3_schedule_polarity_columns(cursor)
            conn.commit()

            rows = {
                r["id"]: r for r in cursor.execute(
                    "SELECT id, schedule_json, polarity FROM trackers").fetchall()
            }

        assert json.loads(rows["t-legacy"]["schedule_json"]) == schedule
        assert rows["t-legacy"]["polarity"] == "negative"
        assert rows["t-nometa"]["schedule_json"] is None
        assert rows["t-nometa"]["polarity"] is None
        assert rows["t-bad"]["schedule_json"] is None
        assert rows["t-bad"]["polarity"] is None

    def test_purge_old_archives_removes_aged_rows(self, test_app, tmp_journal_db):
        """_purge_old_archives should delete archive rows older than the retention window in both archive tables."""
        import modules.journal as journal
        from datetime import datetime, timedelta, timezone

        old_ts = (datetime.now(timezone.utc) - timedelta(days=30)).isoformat().replace("+00:00", "Z")
        fresh_ts = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat().replace("+00:00", "Z")

        with get_db(tmp_journal_db) as conn:
            cursor = conn.cursor()
            # entries_archive: one old, one fresh
            cursor.execute(
                "INSERT INTO entries_archive (date, tracker_id, value, completed, last_modified_at, superseded_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                ("2026-01-01", "t1", 1.0, 1, old_ts, old_ts),
            )
            cursor.execute(
                "INSERT INTO entries_archive (date, tracker_id, value, completed, last_modified_at, superseded_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                ("2026-01-02", "t1", 2.0, 1, fresh_ts, fresh_ts),
            )
            # trackers_archive: one old, one fresh
            cursor.execute(
                "INSERT INTO trackers_archive (tracker_id, name, category, type, meta_json, deleted, last_modified_at, superseded_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ("t-old", "Old Tracker", "cat", "checkbox", "{}", 0, old_ts, old_ts),
            )
            cursor.execute(
                "INSERT INTO trackers_archive (tracker_id, name, category, type, meta_json, deleted, last_modified_at, superseded_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ("t-fresh", "Fresh Tracker", "cat", "checkbox", "{}", 0, fresh_ts, fresh_ts),
            )
            conn.commit()

            journal._purge_old_archives(conn)
            conn.commit()

            cursor.execute("SELECT date FROM entries_archive")
            assert [r[0] for r in cursor.fetchall()] == ["2026-01-02"]

            cursor.execute("SELECT tracker_id FROM trackers_archive")
            assert [r[0] for r in cursor.fetchall()] == ["t-fresh"]


@pytest.mark.unit
class TestGetUtcNow:
    def test_returns_iso_format_with_z_suffix(self, test_app):
        """get_utc_now should return ISO-8601 format with Z suffix."""
        import modules.journal as journal
        result = journal.get_utc_now()
        assert result.endswith("Z")
        assert "T" in result
