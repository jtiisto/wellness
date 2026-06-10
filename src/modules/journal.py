"""
Journal API Router - extracted from journal/src/server.py
Conflict-aware versioning sync engine for personal journal trackers.
"""
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict

from modules.db import (
    DbAccessor,
    get_utc_now,
    utc_days_ago,
    sync_watermark,
    read_transaction,
    register_client as _db_register_client,
    run_migrations,
    enable_wal,
)


def _column_exists(cursor, table: str, column: str) -> bool:
    cursor.execute(f"PRAGMA table_info({table})")
    return any(row[1] == column for row in cursor.fetchall())


def _migration_1_baseline(cursor):
    """Baseline schema: clients, meta_sync, trackers, entries, sync_conflicts.

    Idempotent: CREATE TABLE uses IF NOT EXISTS; column backfills are guarded by
    PRAGMA table_info checks so DBs that already have the columns are unchanged.
    """
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS clients (
            id TEXT PRIMARY KEY,
            name TEXT,
            last_seen_at TEXT
        )
    """)

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS meta_sync (
            key TEXT PRIMARY KEY,
            value TEXT
        )
    """)

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS trackers (
            id TEXT PRIMARY KEY,
            name TEXT,
            category TEXT,
            type TEXT,
            meta_json TEXT,
            version INTEGER DEFAULT 1,
            last_modified_by TEXT,
            last_modified_at TEXT,
            deleted INTEGER DEFAULT 0
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_trackers_name ON trackers(name)")

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS entries (
            date TEXT,
            tracker_id TEXT,
            value REAL,
            completed INTEGER,
            version INTEGER DEFAULT 1,
            last_modified_by TEXT,
            last_modified_at TEXT,
            PRIMARY KEY (date, tracker_id),
            FOREIGN KEY (tracker_id) REFERENCES trackers(id)
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_entries_date ON entries(date)")

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS sync_conflicts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            entity_type TEXT,
            entity_id TEXT,
            client_id TEXT,
            client_data TEXT,
            server_data TEXT,
            resolution TEXT,
            resolved_at TEXT,
            created_at TEXT
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_conflicts_resolved ON sync_conflicts(resolved_at)")

    backfills = [
        ("trackers", "version", "ALTER TABLE trackers ADD COLUMN version INTEGER DEFAULT 1"),
        ("trackers", "last_modified_by", "ALTER TABLE trackers ADD COLUMN last_modified_by TEXT"),
        ("trackers", "last_modified_at", "ALTER TABLE trackers ADD COLUMN last_modified_at TEXT"),
        ("trackers", "deleted", "ALTER TABLE trackers ADD COLUMN deleted INTEGER DEFAULT 0"),
        ("entries", "version", "ALTER TABLE entries ADD COLUMN version INTEGER DEFAULT 1"),
        ("entries", "last_modified_by", "ALTER TABLE entries ADD COLUMN last_modified_by TEXT"),
        ("entries", "last_modified_at", "ALTER TABLE entries ADD COLUMN last_modified_at TEXT"),
    ]
    for table, column, ddl in backfills:
        if not _column_exists(cursor, table, column):
            cursor.execute(ddl)

    cursor.execute("CREATE INDEX IF NOT EXISTS idx_trackers_modified ON trackers(last_modified_at)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_entries_modified ON entries(last_modified_at)")


def _migration_2_archive_tables(cursor):
    """Add archive tables for recovering overwrites of entries and trackers.

    Each archive row captures the prior values plus a superseded_at timestamp.
    Purged on a 14-day retention window by _purge_old_archives. Mirrors the
    Coach module's archive pattern.

    Columns intentionally omit `version` and `last_modified_by`: those fields
    on the live `entries` / `trackers` tables are being phased out by the
    journal-sync-simplification work, so the archive captures only fields the
    post-LWW protocol actually uses for recovery.
    """
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS entries_archive (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            date TEXT NOT NULL,
            tracker_id TEXT NOT NULL,
            value REAL,
            completed INTEGER,
            last_modified_at TEXT NOT NULL,
            superseded_at TEXT NOT NULL
        )
    """)
    cursor.execute(
        "CREATE INDEX IF NOT EXISTS idx_entries_archive_superseded "
        "ON entries_archive(superseded_at)"
    )

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS trackers_archive (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            tracker_id TEXT NOT NULL,
            name TEXT,
            category TEXT,
            type TEXT,
            meta_json TEXT,
            deleted INTEGER,
            last_modified_at TEXT NOT NULL,
            superseded_at TEXT NOT NULL
        )
    """)
    cursor.execute(
        "CREATE INDEX IF NOT EXISTS idx_trackers_archive_superseded "
        "ON trackers_archive(superseded_at)"
    )


# Ordered (target_version, migration_fn) pairs. Each migration is applied at
# most once per DB, tracked via PRAGMA user_version. New schema changes are
# added as a new entry with the next sequential version number.
#
# Constraint: migration functions must contain only DDL/DML statements. They
# must NOT issue their own BEGIN / COMMIT / ROLLBACK — init_database() wraps
# each migration in a single BEGIN IMMEDIATE transaction.
MIGRATIONS = [
    (1, _migration_1_baseline),
    (2, _migration_2_archive_tables),
]


# Archive retention window. Rows older than this are pruned on each sync.
# Recovery is manual SQL only — there is no UI restore path.
ARCHIVE_RETENTION_DAYS = 14


def _purge_old_archives(conn):
    """Delete archive rows older than ARCHIVE_RETENTION_DAYS.

    Called opportunistically on every sync upload (see _sync_update). Cheap
    (indexed on superseded_at) and idempotent.
    """
    cutoff = utc_days_ago(ARCHIVE_RETENTION_DAYS)
    cursor = conn.cursor()
    cursor.execute(
        "DELETE FROM entries_archive WHERE superseded_at < ?", (cutoff,)
    )
    cursor.execute(
        "DELETE FROM trackers_archive WHERE superseded_at < ?", (cutoff,)
    )


def init_database(accessor):
    """Initialize the database, applying any pending migrations.

    Migrations are versioned via PRAGMA user_version and applied transactionally
    by the shared db.run_migrations runner (see its docstring for the
    BEGIN IMMEDIATE / in-lock re-check contract). WAL is enabled once here
    (outside any transaction).
    """
    with accessor.get_db() as conn:
        enable_wal(conn)
        run_migrations(conn, MIGRATIONS, label="journal DB")


# Pydantic models
#
# The sync protocol uses optimistic concurrency on an opaque server-issued
# timestamp token (`_baseLastModifiedAt`). The client wall clock is never part
# of the comparator — only timestamps the server itself has stamped. Records
# without a `_baseLastModifiedAt` are treated as "INSERT only if no row exists
# with this key", which lets the client send brand-new records without first
# round-tripping a server stamp.

class TrackerEntry(BaseModel):
    """Shape of a per-day entry value. Used for type hinting; the actual sync
    payload uses raw dicts so extra fields like `_baseLastModifiedAt` pass
    through untouched."""
    value: Optional[float] = None
    completed: Optional[bool] = None


class TrackerConfig(BaseModel):
    """Shape of a tracker config row. Extra fields are merged into meta_json."""
    model_config = ConfigDict(extra="allow")

    id: str
    name: str
    category: Optional[str] = ""
    type: Optional[str] = "simple"


class SyncPayload(BaseModel):
    """Inbound sync upload.

    `config` items are raw tracker dicts; `days[date][tracker_id]` are raw
    entry dicts. Each record may carry a `_baseLastModifiedAt` opaque token
    (the server-stamped timestamp the client last observed for this row) and
    trackers may carry a `_deleted` boolean.
    """
    clientId: str
    config: list[dict[str, Any]] = []
    days: dict[str, dict[str, dict[str, Any]]] = {}


class StatusResponse(BaseModel):
    lastModified: Optional[str] = None


class SyncResponse(BaseModel):
    """Outbound result of POST /sync/update.

    Each accepted item carries the new server-stamped `lastModifiedAt`. Each
    rejected item carries an `errorKind` and the current `serverRow` so the
    client can recover in the same sync cycle without waiting for a delta pull.
    """
    serverTime: str
    acceptedTrackers: list[dict[str, Any]] = []
    acceptedEntries: list[dict[str, Any]] = []
    rejectedTrackers: list[dict[str, Any]] = []
    rejectedEntries: list[dict[str, Any]] = []


class DeltaSyncResponse(BaseModel):
    """Outbound result of GET /sync/delta. With `since` omitted this serves as
    the full-sync response (initial pull / post-reinstall)."""
    config: list[dict[str, Any]]
    days: dict[str, dict[str, dict[str, Any]]]
    deletedTrackers: list[str]
    serverTime: str


# Router with all sync endpoints
# Tracker fields owned by the sync protocol — excluded when capturing
# free-form meta_json fields from a tracker upload. Includes both the new
# top-level response keys (`lastModifiedAt`, `deleted`) and the legacy
# underscore-prefixed names a client might still echo back during migration.
_TRACKER_RESERVED_KEYS = frozenset({
    "id", "name", "category", "type", "lastModifiedAt", "deleted",
    "_version", "_baseVersion", "_baseLastModifiedAt",
    "_lastModifiedBy", "_lastModifiedAt", "_deleted",
})


def _tracker_meta(item: dict) -> str:
    """Serialize the non-reserved fields of a tracker upload to meta_json."""
    return json.dumps({
        k: v for k, v in item.items() if k not in _TRACKER_RESERVED_KEYS
    })


def _tracker_server_row(row, tracker_id: str) -> dict:
    """Build the public-facing shape of a stored tracker row.

    Protocol-owned fields (id, name, category, type, deleted, lastModifiedAt)
    are written LAST so they always win over any stray field that might have
    slipped into `meta_json` from an old client echo.
    """
    out = {}
    if row["meta_json"]:
        out.update(json.loads(row["meta_json"]))
    out["id"] = tracker_id
    out["name"] = row["name"]
    out["category"] = row["category"]
    out["type"] = row["type"]
    out["deleted"] = bool(row["deleted"])
    out["lastModifiedAt"] = row["last_modified_at"]
    return out


def _entry_server_row(row, date_str: str, tracker_id: str) -> dict:
    """Build the public-facing shape of a stored entry row."""
    return {
        "date": date_str,
        "trackerId": tracker_id,
        "value": row["value"],
        "completed": bool(row["completed"]) if row["completed"] is not None else None,
        "lastModifiedAt": row["last_modified_at"],
    }


def _completed_to_int(completed):
    if completed is None:
        return None
    return 1 if completed else 0


def _sync_status(get_db):
    """Get the last server sync time."""
    with get_db() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT value FROM meta_sync WHERE key = 'last_server_sync_time'")
        row = cursor.fetchone()

        if row:
            return StatusResponse(lastModified=row["value"])
        return StatusResponse(lastModified=None)


def _register_client(get_db, client_id, client_name=None):
    """Register or update a client. Debug breadcrumb only — no correctness
    dependency on this in the optimistic-concurrency protocol."""
    with get_db() as conn:
        _db_register_client(conn, client_id, client_name)
        conn.commit()
        return {"status": "ok", "clientId": client_id}


def _sync_delta(get_db, since=None, client_id=None):
    """Return server data the client doesn't have yet.

    When `since` is omitted, returns everything visible (initial pull /
    post-reinstall). `client_id` is accepted as a debug breadcrumb but is not
    load-bearing for correctness.

    Entries are filtered to only those whose tracker is active (`deleted=0`).
    The full entry history remains on the server for MCP queries; the client
    only ever sees entries for trackers it can still display.
    """
    with get_db() as conn:
        # Stamp the watermark BEFORE the reads (and read both tables in one
        # consistent snapshot). Stamping it after the reads — as this once did —
        # let a write that committed during a slow read carry a timestamp below
        # serverTime yet be unseen by the snapshot, then be skipped forever by
        # the next `> since` pull. sync_watermark() also subtracts a small
        # overlap so a write that stamped just before this snapshot but committed
        # just after is re-delivered next pull rather than lost; re-delivery is
        # harmless (the client applies non-dirty rows idempotently, skips dirty).
        server_time = sync_watermark()
        with read_transaction(conn) as cursor:
            if since:
                cursor.execute(
                    "SELECT * FROM trackers "
                    "WHERE last_modified_at > ? OR last_modified_at IS NULL",
                    (since,),
                )
            else:
                cursor.execute("SELECT * FROM trackers")
            tracker_rows = cursor.fetchall()

            config = []
            deleted_trackers = []
            for row in tracker_rows:
                if row["deleted"]:
                    deleted_trackers.append(row["id"])
                    continue
                tracker = {}
                if row["meta_json"]:
                    tracker.update(json.loads(row["meta_json"]))
                # Protocol-owned fields written LAST so they win over any stray
                # field that might have slipped into meta_json.
                tracker["id"] = row["id"]
                tracker["name"] = row["name"]
                tracker["category"] = row["category"]
                tracker["type"] = row["type"]
                tracker["lastModifiedAt"] = row["last_modified_at"]
                config.append(tracker)

            seven_days_ago = (
                datetime.now(timezone.utc) - timedelta(days=7)
            ).strftime("%Y-%m-%d")
            if since:
                cursor.execute(
                    "SELECT e.* FROM entries e "
                    "JOIN trackers t ON e.tracker_id = t.id "
                    "WHERE (e.last_modified_at > ? OR e.last_modified_at IS NULL) "
                    "AND e.date >= ? AND t.deleted = 0",
                    (since, seven_days_ago),
                )
            else:
                cursor.execute(
                    "SELECT e.* FROM entries e "
                    "JOIN trackers t ON e.tracker_id = t.id "
                    "WHERE e.date >= ? AND t.deleted = 0",
                    (seven_days_ago,),
                )
            entry_rows = cursor.fetchall()

        days: dict[str, dict[str, dict[str, Any]]] = {}
        for row in entry_rows:
            date_str = row["date"]
            tracker_id = row["tracker_id"]
            days.setdefault(date_str, {})[tracker_id] = {
                "value": row["value"],
                "completed": (
                    bool(row["completed"]) if row["completed"] is not None else None
                ),
                "lastModifiedAt": row["last_modified_at"],
            }

        return DeltaSyncResponse(
            config=config,
            days=days,
            deletedTrackers=deleted_trackers,
            serverTime=server_time,
        )


def _apply_tracker_upload(
    cursor, item: dict, now: str,
) -> tuple[Optional[dict], Optional[dict]]:
    """Apply one tracker upload.

    Returns (accepted, rejected). Exactly one is non-None. On accept the prior
    row (if any) is copied to trackers_archive before the UPDATE.
    """
    tracker_id = item.get("id")
    base_ts = item.get("_baseLastModifiedAt")
    is_deleted = bool(item.get("_deleted", False))

    cursor.execute(
        "SELECT name, category, type, meta_json, deleted, last_modified_at "
        "FROM trackers WHERE id = ?",
        (tracker_id,),
    )
    row = cursor.fetchone()

    if row is None:
        if base_ts is not None:
            return None, {
                "id": tracker_id,
                "errorKind": "missing",
                "serverRow": None,
            }
        cursor.execute(
            "INSERT INTO trackers "
            "(id, name, category, type, meta_json, last_modified_at, deleted) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (
                tracker_id,
                item.get("name"),
                item.get("category", ""),
                item.get("type", "simple"),
                _tracker_meta(item),
                now,
                1 if is_deleted else 0,
            ),
        )
        return {"id": tracker_id, "lastModifiedAt": now}, None

    stored_ts = row["last_modified_at"]
    # Compare opaque timestamp tokens. Equal accepts (covers idempotent retry
    # after a lost response). Strictly-greater rejects. NULL stored_ts means a
    # pre-LWW row that has no timestamp yet — accept and stamp.
    if stored_ts is not None and (base_ts is None or stored_ts > base_ts):
        return None, {
            "id": tracker_id,
            "errorKind": "stale",
            "serverRow": _tracker_server_row(row, tracker_id),
        }

    # Archive the prior row before overwriting.
    cursor.execute(
        "INSERT INTO trackers_archive "
        "(tracker_id, name, category, type, meta_json, deleted, "
        " last_modified_at, superseded_at) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (
            tracker_id,
            row["name"],
            row["category"],
            row["type"],
            row["meta_json"],
            row["deleted"] or 0,
            stored_ts or now,
            now,
        ),
    )
    cursor.execute(
        "UPDATE trackers SET name = ?, category = ?, type = ?, meta_json = ?, "
        "deleted = ?, last_modified_at = ? WHERE id = ?",
        (
            item.get("name"),
            item.get("category", ""),
            item.get("type", "simple"),
            _tracker_meta(item),
            1 if is_deleted else 0,
            now,
            tracker_id,
        ),
    )
    return {"id": tracker_id, "lastModifiedAt": now}, None


def _apply_entry_upload(
    cursor, date_str: str, tracker_id: str, data: dict, now: str,
) -> tuple[Optional[dict], Optional[dict]]:
    """Apply one entry upload. Same optimistic-concurrency rules as trackers."""
    base_ts = data.get("_baseLastModifiedAt")
    value = data.get("value")
    completed_int = _completed_to_int(data.get("completed"))

    cursor.execute(
        "SELECT value, completed, last_modified_at FROM entries "
        "WHERE date = ? AND tracker_id = ?",
        (date_str, tracker_id),
    )
    row = cursor.fetchone()

    if row is None:
        if base_ts is not None:
            return None, {
                "date": date_str,
                "trackerId": tracker_id,
                "errorKind": "missing",
                "serverRow": None,
            }
        cursor.execute(
            "INSERT INTO entries (date, tracker_id, value, completed, last_modified_at) "
            "VALUES (?, ?, ?, ?, ?)",
            (date_str, tracker_id, value, completed_int, now),
        )
        return {
            "date": date_str,
            "trackerId": tracker_id,
            "lastModifiedAt": now,
        }, None

    stored_ts = row["last_modified_at"]
    if stored_ts is not None and (base_ts is None or stored_ts > base_ts):
        return None, {
            "date": date_str,
            "trackerId": tracker_id,
            "errorKind": "stale",
            "serverRow": _entry_server_row(row, date_str, tracker_id),
        }

    cursor.execute(
        "INSERT INTO entries_archive "
        "(date, tracker_id, value, completed, last_modified_at, superseded_at) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (
            date_str,
            tracker_id,
            row["value"],
            row["completed"],
            stored_ts or now,
            now,
        ),
    )
    cursor.execute(
        "UPDATE entries SET value = ?, completed = ?, last_modified_at = ? "
        "WHERE date = ? AND tracker_id = ?",
        (value, completed_int, now, date_str, tracker_id),
    )
    return {
        "date": date_str,
        "trackerId": tracker_id,
        "lastModifiedAt": now,
    }, None


def _sync_update(get_db, payload):
    """Upload client changes using optimistic concurrency on `_baseLastModifiedAt`.

    Per record: if `stored.last_modified_at <= incoming._baseLastModifiedAt`
    (or no stored row exists and no base token was provided), the upload
    overwrites the stored row, the prior values are archived, and the new
    server-stamped timestamp is returned. Otherwise the upload is rejected
    with the current `serverRow` so the client can recover in-cycle without
    waiting for a delta pull.
    """
    now = get_utc_now()
    client_id = payload.clientId

    accepted_trackers: list[dict] = []
    accepted_entries: list[dict] = []
    rejected_trackers: list[dict] = []
    rejected_entries: list[dict] = []
    had_accept = False

    with get_db() as conn:
        cursor = conn.cursor()
        _db_register_client(conn, client_id, now=now)

        try:
            for item in payload.config:
                accepted, rejected = _apply_tracker_upload(cursor, item, now)
                if accepted is not None:
                    accepted_trackers.append(accepted)
                    had_accept = True
                if rejected is not None:
                    rejected_trackers.append(rejected)

            for date_str, trackers_map in payload.days.items():
                for tracker_id, data in trackers_map.items():
                    accepted, rejected = _apply_entry_upload(
                        cursor, date_str, tracker_id, data, now,
                    )
                    if accepted is not None:
                        accepted_entries.append(accepted)
                        had_accept = True
                    if rejected is not None:
                        rejected_entries.append(rejected)

            if had_accept:
                cursor.execute(
                    "INSERT OR REPLACE INTO meta_sync (key, value) "
                    "VALUES ('last_server_sync_time', ?)",
                    (now,),
                )

            _purge_old_archives(conn)
            conn.commit()

            return SyncResponse(
                serverTime=now,
                acceptedTrackers=accepted_trackers,
                acceptedEntries=accepted_entries,
                rejectedTrackers=rejected_trackers,
                rejectedEntries=rejected_entries,
            )

        except Exception as e:
            conn.rollback()
            raise HTTPException(status_code=500, detail=str(e))


def create_router(db_path: Path) -> APIRouter:
    """Factory: build an injected DB accessor, initialize tables, and return a
    fresh router whose handlers capture the accessor (R2 — no module-global DB
    path, so two routers can target different DBs in one process)."""
    accessor = DbAccessor(db_path)
    init_database(accessor)
    get_db = accessor.get_db
    router = APIRouter()

    @router.get("/sync/status", response_model=StatusResponse)
    def sync_status():
        return _sync_status(get_db)

    @router.post("/sync/register")
    def register_client(client_id: str, client_name: Optional[str] = None):
        return _register_client(get_db, client_id, client_name)

    @router.get("/sync/delta", response_model=DeltaSyncResponse)
    def sync_delta(since: Optional[str] = None, client_id: Optional[str] = None):
        return _sync_delta(get_db, since, client_id)

    @router.post("/sync/update", response_model=SyncResponse)
    def sync_update(payload: SyncPayload):
        return _sync_update(get_db, payload)

    return router
