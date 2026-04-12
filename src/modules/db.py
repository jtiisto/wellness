"""
Shared database utilities for wellness modules.
"""
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone


def get_utc_now() -> str:
    """Return current UTC time as ISO-8601 string."""
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


@contextmanager
def get_db(db_path, foreign_keys=False):
    """Context manager for database connections."""
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout = 5000")
    if foreign_keys:
        conn.execute("PRAGMA foreign_keys = ON")
    try:
        yield conn
    except BaseException:
        conn.rollback()
        raise
    finally:
        conn.close()


def register_client(conn, client_id, client_name=None, now=None):
    """Register or update a client in the clients table."""
    if now is None:
        now = get_utc_now()
    cursor = conn.cursor()
    cursor.execute("""
        INSERT OR REPLACE INTO clients (id, name, last_seen_at)
        VALUES (?, ?, ?)
    """, (client_id, client_name or f"Client-{client_id[:8]}", now))
