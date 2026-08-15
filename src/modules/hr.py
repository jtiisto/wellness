"""
HR API Router - heart-rate ingestion from the native (Android) client.

A headless module: it owns `data/hr.db` like every other module but has no PWA
tab, so its config entry carries `"headless": True` and `/api/modules` filters it
out. The wire contract (camelCase throughout, epoch-ms integers as data values)
is documented in docs/ARCHITECTURE.md (its home); the Android client's
android/specs/hr-protocol.md defers to it.

Ingestion is three batch POSTs, all idempotent so a client that retries an
unacknowledged flush cannot double-count: samples and set-events INSERT OR
IGNORE on their natural keys, sessions full-row upsert (one writer per session,
last write wins).

Validation is deliberately shallow — types, the `action` enum, date format,
non-negative integers — and **whole-request**: one bad row 422s the entire
batch. That is the contract, not an oversight. 422 is the client's cue to
bisect the batch and quarantine the poison rows; a handler that skipped bad
rows and returned 200 would strand them silently, and any other 4xx tells the
client the failure is systemic and stops the sync run. There are deliberately
no physiological range checks: artifacts are data, and the analysis layer owns
quality judgment.
"""
import logging
from pathlib import Path
from typing import Literal, Optional

from fastapi import APIRouter
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from modules.db import DbAccessor, get_utc_now, run_migrations, enable_wal

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


class _WireModel(BaseModel):
    """Base for every HR wire shape — snake_case in Python, camelCase on the wire.

    The single alias authority for the module: the protocol is camelCase
    throughout (request and response, envelope and rows), so no field here
    should ever spell its own alias. `populate_by_name` additionally accepts the
    Python field name, which only matters for constructing models in-process.
    """
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


# Local calendar dates (`date`, `workoutDate`), not instants. Shape only — the
# server never parses them into a calendar, so an impossible day like 2030-02-31
# is accepted by design; the client owns date correctness.
_DATE_PATTERN = r"^\d{4}-\d{2}-\d{2}$"


class Sample(_WireModel):
    """One RR interval. Optional fields are omitted (never null) on the wire and
    carry the documented defaults here, so an omitted `isGapBefore` stores 0 and
    an omitted `sensorType` stores 'garmin_hrm'."""
    device_id: str
    timestamp_ms: int
    seq: int = Field(ge=0)
    heart_rate_bpm: int
    rr_interval_ms: int = Field(ge=0)  # 0 is a legal artifact sentinel
    is_gap_before: bool = False
    session_id: str
    sensor_type: str = "garmin_hrm"


class SetEvent(_WireModel):
    """One completion toggle. At most one of set_num / item_key is present;
    neither means an exercise-level toggle (e.g. cardio completed)."""
    event_id: str
    date: str = Field(pattern=_DATE_PATTERN)
    exercise_key: str
    set_num: Optional[int] = Field(default=None, ge=1)
    item_key: Optional[str] = None
    action: Literal["check", "uncheck"]
    client_timestamp_ms: int
    session_id: Optional[str] = None


class Session(_WireModel):
    """One capture session, re-uploaded whenever its row changes."""
    session_id: str
    device_id: str
    started_at_ms: int
    ended_at_ms: Optional[int] = None  # omitted while the session is open
    workout_date: Optional[str] = Field(default=None, pattern=_DATE_PATTERN)
    workout_session_id: Optional[int] = None


class SamplesBatch(_WireModel):
    # client_id is the same self-asserted identifier the coach/journal syncs
    # send: diagnostic, not a credential, and hr.db has no clients table, so it
    # is accepted and deliberately not stored.
    client_id: str
    samples: list[Sample]


class SetEventsBatch(_WireModel):
    client_id: str
    events: list[SetEvent]


class SessionsBatch(_WireModel):
    client_id: str
    sessions: list[Session]


class IngestResponse(_WireModel):
    """`accepted + duplicates == total_received` always — the counts come from
    real row changes, so a client can trust them to reconcile a retried flush."""
    accepted: int
    duplicates: int
    total_received: int


class UpsertResponse(_WireModel):
    upserted: int
    total_received: int


class StatusResponse(_WireModel):
    status: str
    samples_count: int
    set_events_count: int
    sessions_count: int


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
            samples_count=row["samples"],
            set_events_count=row["set_events"],
            sessions_count=row["sessions"],
        )


def _ingest(conn, sql, rows):
    """Run one batch write and report how many rows actually changed something.

    Counting via the connection's change counter rather than len(rows) is the
    whole point: INSERT OR IGNORE silently drops the rows whose key is already
    stored, and those are exactly the `duplicates` the client is told about.
    (The sessions upsert routes through here too; its rows are deduped first,
    so its delta is the distinct sessions written.)
    """
    cursor = conn.cursor()
    before = conn.total_changes
    cursor.executemany(sql, rows)
    return conn.total_changes - before


def _samples_batch(get_db, payload):
    """Store RR intervals, idempotent on the (device, timestamp, seq) PK."""
    received_at = get_utc_now()  # one server stamp per request, diagnostics only
    rows = [
        (s.device_id, s.timestamp_ms, s.seq, s.heart_rate_bpm, s.rr_interval_ms,
         int(s.is_gap_before), s.session_id, s.sensor_type, received_at)
        for s in payload.samples
    ]
    with get_db() as conn:
        # OR IGNORE's reach is wider than the PK — it would also swallow a NOT
        # NULL or CHECK violation — which is safe only because every such
        # constraint is already enforced by the Pydantic models above, where a
        # violation becomes the 422 the client knows how to bisect on.
        accepted = _ingest(conn, """
            INSERT OR IGNORE INTO intervals
                (device_id, timestamp_ms, seq, heart_rate_bpm, rr_interval_ms,
                 is_gap_before, session_id, sensor_type, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, rows)
        conn.commit()
    logger.debug("HR samples batch from %s: %d accepted of %d",
                 payload.client_id, accepted, len(rows))
    return IngestResponse(accepted=accepted, duplicates=len(rows) - accepted,
                          total_received=len(rows))


def _set_events_batch(get_db, payload):
    """Store completion toggles, idempotent on event_id."""
    received_at = get_utc_now()
    rows = [
        (e.event_id, e.date, e.exercise_key, e.set_num, e.item_key, e.action,
         e.client_timestamp_ms, e.session_id, received_at)
        for e in payload.events
    ]
    with get_db() as conn:
        accepted = _ingest(conn, """
            INSERT OR IGNORE INTO set_events
                (event_id, date, exercise_key, set_num, item_key, action,
                 client_timestamp_ms, session_id, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, rows)
        conn.commit()
    logger.debug("HR set-events batch from %s: %d accepted of %d",
                 payload.client_id, accepted, len(rows))
    return IngestResponse(accepted=accepted, duplicates=len(rows) - accepted,
                          total_received=len(rows))


def _sessions_batch(get_db, payload):
    """Upsert capture sessions: full-row replace on session_id, last write wins.

    Not INSERT OR IGNORE — a session row legitimately changes after its first
    upload (started -> workout-anchored -> ended), and a single writer (the
    capturing device) means there is no lost-update risk. Every column is
    overwritten from the incoming row, so a field the client omits this time
    clears the stored value rather than lingering from the previous upload; that
    is what "full-row" buys, and it keeps the stored row a faithful copy of the
    client's rather than a merge of every upload ever sent.
    """
    received_at = get_utc_now()
    # `upserted` counts DISTINCT sessions written, mirroring how `duplicates`
    # on the ignore tables counts rows that changed nothing. A batch repeating
    # a sessionId collapses to its last occurrence before the write (the upsert
    # would apply rows in order anyway — same stored row, honest count).
    last_by_id = {s.session_id: s for s in payload.sessions}
    rows = [
        (s.session_id, s.device_id, s.started_at_ms, s.ended_at_ms,
         s.workout_date, s.workout_session_id, received_at)
        for s in last_by_id.values()
    ]
    with get_db() as conn:
        upserted = _ingest(conn, """
            INSERT INTO sessions
                (session_id, device_id, started_at_ms, ended_at_ms,
                 workout_date, workout_session_id, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(session_id) DO UPDATE SET
                device_id          = excluded.device_id,
                started_at_ms      = excluded.started_at_ms,
                ended_at_ms        = excluded.ended_at_ms,
                workout_date       = excluded.workout_date,
                workout_session_id = excluded.workout_session_id,
                received_at        = excluded.received_at
        """, rows)
        conn.commit()
    logger.debug("HR sessions batch from %s: %d upserted of %d",
                 payload.client_id, upserted, len(payload.sessions))
    return UpsertResponse(upserted=upserted,
                          total_received=len(payload.sessions))


def create_router(db_path: Path) -> APIRouter:
    """Factory: build an injected DB accessor, initialize tables, and return a
    fresh router whose handlers capture the accessor (R2 — no module-global DB
    path). Foreign keys stay off: the schema declares no cross-table references
    (batches are order-independent)."""
    accessor = DbAccessor(db_path)
    init_database(accessor)
    get_db = accessor.get_db
    router = APIRouter()

    # Handlers stay sync `def`s so FastAPI runs their blocking sqlite work in
    # its threadpool rather than on the event loop (see db.get_db).
    @router.get("/status", response_model=StatusResponse)
    def hr_status():
        return _status(get_db)

    @router.post("/samples/batch", response_model=IngestResponse)
    def hr_samples_batch(payload: SamplesBatch):
        return _samples_batch(get_db, payload)

    @router.post("/set-events/batch", response_model=IngestResponse)
    def hr_set_events_batch(payload: SetEventsBatch):
        return _set_events_batch(get_db, payload)

    @router.post("/sessions/batch", response_model=UpsertResponse)
    def hr_sessions_batch(payload: SessionsBatch):
        return _sessions_batch(get_db, payload)

    return router
