"""
HR API Router - heart-rate ingestion from the native (Android) client.

A headless module: it owns `data/hr.db` like every other module but has no PWA
tab, so its config entry carries `"headless": True` and `/api/modules` filters it
out. The wire contract (camelCase throughout, epoch-ms integers as data values)
lives in `~/dev/native/wellness/specs/hr-protocol.md` until it moves into
docs/ARCHITECTURE.md.
"""
import logging
from pathlib import Path

from fastapi import APIRouter
from pydantic import BaseModel

from modules.db import DbAccessor, run_migrations, enable_wal

logger = logging.getLogger(__name__)


def _migration_1_baseline(cursor):
    """Baseline HR schema: RR intervals, set-completion events, capture sessions.

    Columns are snake_case mirrors of the wire fields (see the protocol spec for
    their semantics). Unlike the other modules' baselines these are plain
    CREATEs, not CREATE IF NOT EXISTS: hr.db is greenfield (the fresh-start
    decision — the pulse-bridge server's database stays a frozen archive, nothing
    is imported), so there is no pre-registry unversioned DB to adopt.

    No foreign keys between the tables: batches arrive in whatever order the
    client drains them, so an interval or event may legitimately reference a
    session row that has not been uploaded yet.
    """
    # One row per RR interval. The wire calls these "samples"; the table keeps
    # the domain name. `seq` disambiguates beats sharing a receipt millisecond,
    # so the PK stays natural (no surrogate id) and re-uploads INSERT OR IGNORE.
    cursor.execute("""
        CREATE TABLE intervals (
            device_id     TEXT    NOT NULL,
            timestamp_ms  INTEGER NOT NULL,
            seq           INTEGER NOT NULL,
            heart_rate_bpm INTEGER NOT NULL,
            rr_interval_ms INTEGER NOT NULL,          -- 0 = artifact sentinel
            is_gap_before INTEGER NOT NULL DEFAULT 0,
            session_id    TEXT    NOT NULL,
            sensor_type   TEXT    NOT NULL DEFAULT 'garmin_hrm',
            received_at   TEXT    NOT NULL,           -- server stamp, diagnostics only
            PRIMARY KEY (device_id, timestamp_ms, seq)
        )
    """)
    cursor.execute("CREATE INDEX idx_intervals_session ON intervals(session_id)")

    # One row per completion toggle. Unchecks are undo-as-data — nothing is ever
    # deleted; correlation folds the stream in client-timestamp order.
    cursor.execute("""
        CREATE TABLE set_events (
            event_id            TEXT PRIMARY KEY,
            date                TEXT    NOT NULL,     -- YYYY-MM-DD (local)
            exercise_key        TEXT    NOT NULL,     -- coach day-log entry key
            set_num             INTEGER,              -- set ticks only
            item_key            TEXT,                 -- checklist toggles only
            action              TEXT    NOT NULL CHECK (action IN ('check','uncheck')),
            client_timestamp_ms INTEGER NOT NULL,
            session_id          TEXT,
            received_at         TEXT    NOT NULL
        )
    """)
    cursor.execute("CREATE INDEX idx_set_events_date ON set_events(date, exercise_key)")

    # One row per capture session, re-uploaded as it changes (started →
    # workout-anchored → ended), hence the full-row upsert in the batch endpoint.
    cursor.execute("""
        CREATE TABLE sessions (
            session_id         TEXT PRIMARY KEY,
            device_id          TEXT    NOT NULL,
            started_at_ms      INTEGER NOT NULL,
            ended_at_ms        INTEGER,               -- NULL while open
            workout_date       TEXT,                  -- YYYY-MM-DD (local)
            workout_session_id INTEGER,               -- coach hook session id
            received_at        TEXT    NOT NULL
        )
    """)


# Ordered (target_version, migration_fn) pairs — see db.run_migrations for the
# transactional contract. Migration fns are DDL-only and must not manage their
# own transactions.
MIGRATIONS = [
    (1, _migration_1_baseline),
]


def init_database(accessor):
    """Initialize the HR database via the shared migration registry.

    Enables WAL once (outside any transaction) then applies pending migrations
    transactionally. See db.run_migrations for the BEGIN IMMEDIATE / in-lock
    re-check contract.
    """
    with accessor.get_db() as conn:
        enable_wal(conn)
        run_migrations(conn, MIGRATIONS, label="HR DB")


class StatusResponse(BaseModel):
    status: str
    samplesCount: int
    setEventsCount: int
    sessionsCount: int


def _status(get_db):
    """Row counts per table — the client's reachability probe."""
    with get_db() as conn:
        cursor = conn.cursor()
        cursor.execute("""
            SELECT (SELECT COUNT(*) FROM intervals)   AS samples,
                   (SELECT COUNT(*) FROM set_events)  AS set_events,
                   (SELECT COUNT(*) FROM sessions)    AS sessions
        """)
        row = cursor.fetchone()
        return StatusResponse(
            status="ok",
            samplesCount=row["samples"],
            setEventsCount=row["set_events"],
            sessionsCount=row["sessions"],
        )


def create_router(db_path: Path) -> APIRouter:
    """Factory: build an injected DB accessor, initialize tables, and return a
    fresh router whose handlers capture the accessor (R2 — no module-global DB
    path). Foreign keys stay off: the schema declares no cross-table references
    (batches are order-independent)."""
    accessor = DbAccessor(db_path)
    init_database(accessor)
    get_db = accessor.get_db
    router = APIRouter()

    @router.get("/status", response_model=StatusResponse)
    def hr_status():
        return _status(get_db)

    return router
