"""
Shared database utilities for wellness modules.
"""
import logging
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone

logger = logging.getLogger(__name__)


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


def enable_wal(conn):
    """Switch the database to WAL journal mode (readers concurrent with a single
    writer; fewer reader/writer stalls than the default rollback journal).

    WAL is a persistent per-DB setting, so this only needs calling once at
    init — but it must run OUTSIDE any transaction. Safe on the local-disk DBs
    under data/ (WAL is unsupported only on networked filesystems). Adds the
    -wal/-shm sidecar files next to the DB.
    """
    conn.execute("PRAGMA journal_mode=WAL")


def column_exists(cursor, table: str, column: str) -> bool:
    """True if `column` already exists on `table` (for guarded ALTER backfills)."""
    cursor.execute(f"PRAGMA table_info({table})")
    return any(row[1] == column for row in cursor.fetchall())


def register_client(conn, client_id, client_name=None, now=None):
    """Register or update a client in the clients table."""
    if now is None:
        now = get_utc_now()
    cursor = conn.cursor()
    cursor.execute("""
        INSERT OR REPLACE INTO clients (id, name, last_seen_at)
        VALUES (?, ?, ?)
    """, (client_id, client_name or f"Client-{client_id[:8]}", now))


def run_migrations(conn, migrations, *, label="DB"):
    """Apply ordered ``(target_version, fn)`` migrations via PRAGMA user_version.

    Each pending migration runs inside its own explicit ``BEGIN IMMEDIATE``
    transaction so concurrent server boots serialize on the write lock and a
    partial-DDL failure rolls back atomically with the version bump. The version
    is re-checked *inside* the transaction in case another process applied the
    migration while we waited on the lock.

    ``migrations`` is an ordered list of ``(target_version, migration_fn)`` pairs;
    each ``migration_fn`` receives a cursor and must contain only DDL/DML — it
    must NOT issue its own BEGIN / COMMIT / ROLLBACK (this runner owns the
    transaction). ``label`` is used only in the applied-migration log line.

    Extracted from the journal module so coach and analysis share one safe,
    versioned init path (see plans/ R7).
    """
    # Switch to autocommit so we manage BEGIN/COMMIT/ROLLBACK explicitly.
    previous_isolation = conn.isolation_level
    conn.isolation_level = None
    cursor = conn.cursor()
    try:
        current_version = cursor.execute("PRAGMA user_version").fetchone()[0]

        for target_version, migration in migrations:
            if current_version >= target_version:
                continue

            cursor.execute("BEGIN IMMEDIATE")
            try:
                actual_version = cursor.execute("PRAGMA user_version").fetchone()[0]
                if actual_version >= target_version:
                    # Another process applied this migration while we waited on
                    # the write lock. Skip and continue.
                    cursor.execute("ROLLBACK")
                    current_version = actual_version
                    continue

                logger.info(
                    "Applying %s migration: %d -> %d",
                    label, actual_version, target_version,
                )
                migration(cursor)
                cursor.execute(f"PRAGMA user_version = {target_version}")
                cursor.execute("COMMIT")
                current_version = target_version
            except Exception:
                try:
                    cursor.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise
    finally:
        conn.isolation_level = previous_isolation
