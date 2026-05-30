"""
Shared database utilities for wellness modules.
"""
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone


def _iso_z(dt: datetime) -> str:
    """Format an aware UTC datetime as a Z-suffixed ISO-8601 string.

    The one formatting authority for stored/compared *instants* — keeps
    get_utc_now() and utc_days_ago() from drifting (see plans/ R5).
    """
    return dt.isoformat().replace("+00:00", "Z")


def get_utc_now() -> str:
    """Return the current UTC time as a Z-suffixed ISO-8601 instant string."""
    return _iso_z(datetime.now(timezone.utc))


def utc_days_ago(n: int) -> str:
    """Return (now - n days) as a Z-suffixed UTC instant string.

    Same formatter as get_utc_now(); for *instant* cutoffs only (e.g. archive
    retention windows). Do NOT use this for calendar-date window cutoffs — those
    are intentionally local date-only comparisons formatted with
    strftime("%Y-%m-%d") to match the browser's getToday() (see plans/ R5).
    """
    return _iso_z(datetime.now(timezone.utc) - timedelta(days=n))


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
