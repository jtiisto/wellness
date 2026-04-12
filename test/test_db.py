"""Tests for shared database utilities (db.py)."""
import sqlite3
import pytest
from src.modules.db import get_db


class TestGetDbAutoRollback:
    """get_db should auto-rollback uncommitted changes on exception."""

    def test_rollback_on_exception(self, tmp_path):
        db_path = tmp_path / "test.db"
        # Create a table
        with get_db(db_path) as conn:
            conn.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)")
            conn.commit()

        # Insert a row then raise — should be rolled back
        with pytest.raises(RuntimeError):
            with get_db(db_path) as conn:
                conn.execute("INSERT INTO items (name) VALUES ('should_not_persist')")
                raise RuntimeError("simulated failure")

        # Verify the row was not persisted
        with get_db(db_path) as conn:
            count = conn.execute("SELECT COUNT(*) FROM items").fetchone()[0]
            assert count == 0

    def test_commit_persists_on_success(self, tmp_path):
        db_path = tmp_path / "test.db"
        with get_db(db_path) as conn:
            conn.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)")
            conn.commit()

        with get_db(db_path) as conn:
            conn.execute("INSERT INTO items (name) VALUES ('persisted')")
            conn.commit()

        with get_db(db_path) as conn:
            count = conn.execute("SELECT COUNT(*) FROM items").fetchone()[0]
            assert count == 1
