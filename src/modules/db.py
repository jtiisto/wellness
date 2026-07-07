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
def get_db(db_path, foreign_keys=False, read_only=False):
    """Context manager for database connections.

    CONVENTION: never call this on the asyncio event loop. Handlers are sync
    `def`s (FastAPI runs them in its threadpool); the few `async def` paths
    (analysis submit/run_report, coach workout hooks) route their DB work
    through `asyncio.to_thread`. A blocking sqlite3 call here can hold the loop
    for up to busy_timeout (5s) under contention.

    `read_only=True` opens with the sqlite URI `mode=ro` (the MCP servers'
    pattern): the connection refuses writes (OperationalError) AND refuses to
    create a missing file — exactly what a cross-module reader (trends) wants.
    Note: reading a WAL database via mode=ro still needs filesystem access to
    its `-shm` file, so reader and writer must run as the same user (true
    here: one server process owns coach/journal; the Garmin DB is written by
    the user's own sync job).
    """
    if read_only:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    else:
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


class DbAccessor:
    """An explicit, injected handle to a module's database (R2).

    Replaces the per-module mutable `_db_path` module-global: `create_router`
    builds one and the route handlers capture it, so two instances can coexist
    in one process and tests isolate by constructing accessors rather than poking
    globals. Wraps the shared `get_db` semantics (busy_timeout, optional foreign
    keys); `.path` exposes the path for callers that take it as a string
    (e.g. the analysis module's `analysis_db` functions).
    """

    def __init__(self, db_path, *, foreign_keys=False, read_only=False):
        self.path = db_path
        self._foreign_keys = foreign_keys
        self._read_only = read_only

    def get_db(self):
        return get_db(self.path, foreign_keys=self._foreign_keys,
                      read_only=self._read_only)


@contextmanager
def immediate_transaction(conn):
    """Run a block inside a ``BEGIN IMMEDIATE`` transaction.

    IMMEDIATE acquires the write lock at transaction start rather than lazily on
    the first write, so a check-then-write (read a row, compare, then update) is
    atomic against a concurrent writer — there is no window for another process
    to slip a write in between the check and the write, and no mid-transaction
    SQLITE_BUSY-on-upgrade. With busy_timeout set, a competing writer waits for
    the lock instead of failing. Commits on success, rolls back on any
    exception; the caller owns the connection.

    Only worth it for genuine check-then-write paths — wrapping read-mostly
    endpoints would needlessly serialize them.
    """
    previous_isolation = conn.isolation_level
    conn.isolation_level = None  # we manage BEGIN/COMMIT/ROLLBACK explicitly
    cursor = conn.cursor()
    cursor.execute("BEGIN IMMEDIATE")
    try:
        yield cursor
        cursor.execute("COMMIT")
    except BaseException:
        try:
            cursor.execute("ROLLBACK")
        except sqlite3.Error:
            pass
        raise
    finally:
        conn.isolation_level = previous_isolation


# Overlap (seconds) subtracted from a sync watermark. See sync_watermark().
SYNC_WATERMARK_OVERLAP_SECONDS = 2


def sync_watermark() -> str:
    """Return a 'changes-since' watermark: now minus a small overlap.

    Sync pull endpoints return this as ``serverTime``; the client stores it and
    sends it back as ``since`` on the next pull. Subtracting a small overlap (and
    stamping it BEFORE the reads) closes a delivery race: a write that stamped
    its ``last_modified_at`` just before the pull's snapshot but committed just
    after it would otherwise be unseen by this pull AND skipped forever by the
    next ``> since`` pull. With the overlap, the next pull's window reaches back
    far enough to re-deliver it. Re-delivery is safe — the client applies
    non-dirty rows idempotently and skips rows it has dirty locally — so the only
    cost is occasionally re-sending a row modified in the last couple of seconds.
    """
    return _iso_z(
        datetime.now(timezone.utc) - timedelta(seconds=SYNC_WATERMARK_OVERLAP_SECONDS)
    )


@contextmanager
def read_transaction(conn):
    """Run a block inside a single deferred read transaction.

    In WAL mode the read snapshot is fixed at the first SELECT, so multiple
    SELECTs inside this block see one consistent point in time (e.g. a delta
    pull's trackers and entries can't straddle a concurrent write). Read-only:
    always rolls back to release the snapshot; the caller owns the connection.
    """
    previous_isolation = conn.isolation_level
    conn.isolation_level = None  # we manage BEGIN/ROLLBACK explicitly
    cursor = conn.cursor()
    cursor.execute("BEGIN")  # DEFERRED — snapshot taken at the first read
    try:
        yield cursor
    finally:
        try:
            cursor.execute("ROLLBACK")
        except sqlite3.Error:
            pass
        conn.isolation_level = previous_isolation


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
