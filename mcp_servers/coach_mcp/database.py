"""Database connection and management for the Coach MCP server.

`SQLiteConnection`, `DatabaseManager`, `get_utc_now`, and `_DEFAULT_DB_PATH`
moved here from `server.py` (behavior-preserving split). Re-exported
from `server.py` for the historical import surface the tests rely on.
"""

import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from .config import MCPConfig

# Default DB path: ../../data/coach.db relative to this file's directory
_DEFAULT_DB_PATH = Path(__file__).resolve().parent.parent.parent / "data" / "coach.db"


class SQLiteConnection:
    """SQLite connection context manager with configurable read/write mode."""

    def __init__(self, db_path: Path, read_only: bool = True):
        self.db_path = db_path
        self.read_only = read_only
        self.conn = None

    def __enter__(self):
        """Open SQLite connection."""
        if self.read_only:
            self.conn = sqlite3.connect(f"file:{self.db_path}?mode=ro", uri=True)
        else:
            self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA busy_timeout = 5000")
        self.conn.execute("PRAGMA foreign_keys = ON")
        return self.conn

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Close connection safely."""
        if self.conn:
            self.conn.close()


class DatabaseManager:
    """Manages database connections and operations."""

    def __init__(self, config: MCPConfig):
        self.config = config

    def get_connection(self, read_only: bool = True):
        """Get database connection."""
        return SQLiteConnection(self.config.db_path, read_only=read_only)

    def execute_query(
        self, query: str, params: Optional[List[Any]] = None, read_only: bool = True
    ) -> List[Dict[str, Any]]:
        """Execute a query and return results."""
        try:
            with self.get_connection(read_only=read_only) as conn:
                cursor = conn.cursor()
                cursor.execute(query, params or [])
                if not read_only:
                    conn.commit()
                results = [dict(row) for row in cursor.fetchall()]
                return results
        except sqlite3.Error as e:
            raise ValueError(f"Database error: {str(e)}")

    def execute_write(
        self, query: str, params: Optional[List[Any]] = None
    ) -> int:
        """Execute a write query and return rows affected."""
        try:
            with self.get_connection(read_only=False) as conn:
                cursor = conn.cursor()
                cursor.execute(query, params or [])
                conn.commit()
                return cursor.rowcount
        except sqlite3.Error as e:
            raise ValueError(f"Database error: {str(e)}")

    @contextmanager
    def transaction(self):
        """Get a cursor for multi-statement transactions.

        Uses BEGIN IMMEDIATE so the write lock is taken up front: plan save/
        delete here read-then-write the same coach.db that the web server's sync
        endpoint writes, so the check-then-write must be atomic across the two
        processes. Commits on success, rolls back on error.
        """
        with self.get_connection(read_only=False) as conn:
            conn.isolation_level = None  # we manage BEGIN/COMMIT/ROLLBACK
            cursor = conn.cursor()
            cursor.execute("BEGIN IMMEDIATE")
            try:
                yield cursor
                cursor.execute("COMMIT")
            except Exception:
                try:
                    cursor.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise


def get_utc_now() -> str:
    """Return current UTC time as ISO-8601 string."""
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
