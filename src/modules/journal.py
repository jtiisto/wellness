"""
Journal API Router - extracted from journal/src/server.py
Conflict-aware versioning sync engine for personal journal trackers.
"""
import json
import logging
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict

from modules.db import get_db as _shared_get_db, get_utc_now, register_client as _db_register_client


logger = logging.getLogger(__name__)


# Module-level DB path, set by create_router()
_db_path: Path = None


@contextmanager
def get_db():
    """Module-scoped wrapper that binds the module's DB path."""
    with _shared_get_db(_db_path) as conn:
        yield conn


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

    Intended to be called opportunistically during sync. Cheap (indexed on
    superseded_at) and idempotent — safe to invoke on every sync request.
    Not yet wired in; the call site is added when sync endpoints start writing
    archive rows.
    """
    cutoff = (
        datetime.now(timezone.utc) - timedelta(days=ARCHIVE_RETENTION_DAYS)
    ).isoformat().replace("+00:00", "Z")
    cursor = conn.cursor()
    cursor.execute(
        "DELETE FROM entries_archive WHERE superseded_at < ?", (cutoff,)
    )
    cursor.execute(
        "DELETE FROM trackers_archive WHERE superseded_at < ?", (cutoff,)
    )


def init_database():
    """Initialize the database, applying any pending migrations.

    Migrations are versioned via PRAGMA user_version. Each migration runs inside
    an explicit BEGIN IMMEDIATE transaction so concurrent server boots serialize
    on the write lock and a partial-DDL failure rolls back atomically with the
    version bump. The version is re-checked inside the transaction in case
    another process applied the migration while we waited on the lock.
    """
    with get_db() as conn:
        # Switch to autocommit so we manage BEGIN/COMMIT/ROLLBACK explicitly.
        previous_isolation = conn.isolation_level
        conn.isolation_level = None
        cursor = conn.cursor()
        try:
            current_version = cursor.execute("PRAGMA user_version").fetchone()[0]

            for target_version, migration in MIGRATIONS:
                if current_version >= target_version:
                    continue

                cursor.execute("BEGIN IMMEDIATE")
                try:
                    actual_version = cursor.execute("PRAGMA user_version").fetchone()[0]
                    if actual_version >= target_version:
                        # Another process applied this migration while we waited
                        # on the write lock. Skip and continue.
                        cursor.execute("ROLLBACK")
                        current_version = actual_version
                        continue

                    logger.info(
                        "Applying journal DB migration: %d -> %d",
                        actual_version, target_version,
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


# Pydantic models
class TrackerEntry(BaseModel):
    value: Optional[float] = None
    completed: Optional[bool] = None
    _version: Optional[int] = None
    _baseVersion: Optional[int] = None


class TrackerConfig(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: str
    name: str
    category: Optional[str] = ""
    type: Optional[str] = "simple"
    _version: Optional[int] = None
    _baseVersion: Optional[int] = None
    _deleted: Optional[bool] = False


class SyncPayload(BaseModel):
    clientId: str
    config: list[dict[str, Any]] = []
    days: dict[str, dict[str, dict[str, Any]]] = {}
    lastSyncTime: Optional[str] = None


class StatusResponse(BaseModel):
    lastModified: Optional[str] = None


class FullSyncResponse(BaseModel):
    config: list[dict[str, Any]]
    days: dict[str, dict[str, dict[str, Any]]]
    serverTime: str


class ConflictInfo(BaseModel):
    entityType: str
    entityId: str
    serverVersion: int
    clientBaseVersion: int
    serverData: dict[str, Any]


class SyncResponse(BaseModel):
    success: bool
    conflicts: list[ConflictInfo] = []
    appliedConfig: list[dict[str, Any]] = []
    appliedDays: dict[str, dict[str, dict[str, Any]]] = {}
    lastModified: Optional[str] = None
    overwrittenData: list[dict[str, Any]] = []


class DeltaSyncResponse(BaseModel):
    config: list[dict[str, Any]]
    days: dict[str, dict[str, dict[str, Any]]]
    deletedTrackers: list[str]
    serverTime: str


# Router with all sync endpoints
router = APIRouter()


@router.get("/sync/status", response_model=StatusResponse)
def sync_status():
    """Get the last server sync time."""
    with get_db() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT value FROM meta_sync WHERE key = 'last_server_sync_time'")
        row = cursor.fetchone()

        if row:
            return StatusResponse(lastModified=row["value"])
        return StatusResponse(lastModified=None)


@router.post("/sync/register")
def register_client(client_id: str, client_name: Optional[str] = None):
    """Register or update a client."""
    with get_db() as conn:
        _db_register_client(conn, client_id, client_name)
        conn.commit()
        return {"status": "ok", "clientId": client_id}


@router.get("/sync/full", response_model=FullSyncResponse)
def sync_full():
    """Get full data dump for client synchronization."""
    with get_db() as conn:
        cursor = conn.cursor()

        cursor.execute("SELECT * FROM trackers WHERE deleted = 0 OR deleted IS NULL")
        tracker_rows = cursor.fetchall()

        config = []
        for row in tracker_rows:
            tracker = {
                "id": row["id"],
                "name": row["name"],
                "category": row["category"],
                "type": row["type"],
                "_version": row["version"] or 1,
                "_lastModifiedBy": row["last_modified_by"],
                "_lastModifiedAt": row["last_modified_at"]
            }
            if row["meta_json"]:
                meta = json.loads(row["meta_json"])
                tracker.update(meta)
            config.append(tracker)

        seven_days_ago = (datetime.now(timezone.utc) - timedelta(days=7)).strftime("%Y-%m-%d")
        cursor.execute(
            "SELECT * FROM entries WHERE date >= ?",
            (seven_days_ago,)
        )
        entry_rows = cursor.fetchall()

        days = {}
        for row in entry_rows:
            date_str = row["date"]
            tracker_id = row["tracker_id"]

            if date_str not in days:
                days[date_str] = {}

            days[date_str][tracker_id] = {
                "value": row["value"],
                "completed": bool(row["completed"]) if row["completed"] is not None else None,
                "_version": row["version"] or 1,
                "_lastModifiedBy": row["last_modified_by"],
                "_lastModifiedAt": row["last_modified_at"]
            }

        return FullSyncResponse(config=config, days=days, serverTime=get_utc_now())


@router.get("/sync/delta")
def sync_delta(since: str, client_id: str):
    """Get changes since a specific timestamp for incremental sync."""
    with get_db() as conn:
        cursor = conn.cursor()

        cursor.execute("""
            SELECT * FROM trackers
            WHERE last_modified_at > ? OR last_modified_at IS NULL
        """, (since,))
        tracker_rows = cursor.fetchall()

        config = []
        deleted_trackers = []
        for row in tracker_rows:
            if row["deleted"]:
                deleted_trackers.append(row["id"])
            else:
                tracker = {
                    "id": row["id"],
                    "name": row["name"],
                    "category": row["category"],
                    "type": row["type"],
                    "_version": row["version"] or 1,
                    "_lastModifiedBy": row["last_modified_by"],
                    "_lastModifiedAt": row["last_modified_at"]
                }
                if row["meta_json"]:
                    meta = json.loads(row["meta_json"])
                    tracker.update(meta)
                config.append(tracker)

        seven_days_ago = (datetime.now(timezone.utc) - timedelta(days=7)).strftime("%Y-%m-%d")
        cursor.execute("""
            SELECT * FROM entries
            WHERE (last_modified_at > ? OR last_modified_at IS NULL)
            AND date >= ?
        """, (since, seven_days_ago))
        entry_rows = cursor.fetchall()

        days = {}
        for row in entry_rows:
            date_str = row["date"]
            tracker_id = row["tracker_id"]

            if date_str not in days:
                days[date_str] = {}

            days[date_str][tracker_id] = {
                "value": row["value"],
                "completed": bool(row["completed"]) if row["completed"] is not None else None,
                "_version": row["version"] or 1,
                "_lastModifiedBy": row["last_modified_by"],
                "_lastModifiedAt": row["last_modified_at"]
            }

        return DeltaSyncResponse(
            config=config,
            days=days,
            deletedTrackers=deleted_trackers,
            serverTime=get_utc_now()
        )


CONFLICT_RETENTION_DAYS = 30


def _prune_old_conflicts(cursor):
    """Remove resolved sync_conflicts rows older than the retention window."""
    cutoff = (datetime.now(timezone.utc) - timedelta(days=CONFLICT_RETENTION_DAYS)).isoformat()
    cursor.execute(
        "DELETE FROM sync_conflicts WHERE resolved_at IS NOT NULL AND resolved_at < ?",
        (cutoff,)
    )


@router.post("/sync/update", response_model=SyncResponse)
def sync_update(payload: SyncPayload):
    """Update server with client data, with conflict detection."""
    conflicts = []
    applied_config = []
    applied_days = {}
    overwritten_data = []
    now = get_utc_now()
    client_id = payload.clientId

    with get_db() as conn:
        cursor = conn.cursor()

        _db_register_client(conn, client_id, now=now)

        try:
            for item in payload.config:
                tracker_id = item.get("id")
                client_base_version = item.get("_baseVersion", 0)
                is_deleted = item.get("_deleted", False)

                cursor.execute(
                    "SELECT version, name, category, type, meta_json, deleted FROM trackers WHERE id = ?",
                    (tracker_id,)
                )
                row = cursor.fetchone()
                server_version = row["version"] if row else 0

                if row and server_version > client_base_version:
                    server_data = {
                        "id": tracker_id,
                        "name": row["name"],
                        "category": row["category"],
                        "type": row["type"],
                        "_version": server_version
                    }
                    if row["meta_json"]:
                        server_data.update(json.loads(row["meta_json"]))

                    conflicts.append(ConflictInfo(
                        entityType="tracker",
                        entityId=tracker_id,
                        serverVersion=server_version,
                        clientBaseVersion=client_base_version,
                        serverData=server_data
                    ))
                    continue

                name = item.get("name")
                category = item.get("category", "")
                tracker_type = item.get("type", "simple")
                new_version = server_version + 1

                excluded_keys = {"id", "name", "category", "type", "_version", "_baseVersion",
                               "_lastModifiedBy", "_lastModifiedAt", "_deleted"}
                meta = {k: v for k, v in item.items() if k not in excluded_keys}

                if is_deleted:
                    cursor.execute("""
                        UPDATE trackers SET deleted = 1, version = ?,
                        last_modified_by = ?, last_modified_at = ?
                        WHERE id = ?
                    """, (new_version, client_id, now, tracker_id))
                else:
                    cursor.execute("""
                        INSERT INTO trackers (id, name, category, type, meta_json, version,
                                            last_modified_by, last_modified_at, deleted)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                        ON CONFLICT(id) DO UPDATE SET
                            name = excluded.name,
                            category = excluded.category,
                            type = excluded.type,
                            meta_json = excluded.meta_json,
                            version = excluded.version,
                            last_modified_by = excluded.last_modified_by,
                            last_modified_at = excluded.last_modified_at,
                            deleted = 0
                    """, (tracker_id, name, category, tracker_type, json.dumps(meta),
                          new_version, client_id, now))

                applied_config.append({
                    **item,
                    "_version": new_version,
                    "_lastModifiedBy": client_id,
                    "_lastModifiedAt": now
                })

            for date_str, trackers_map in payload.days.items():
                if date_str not in applied_days:
                    applied_days[date_str] = {}

                for tracker_id, data in trackers_map.items():
                    client_base_version = data.get("_baseVersion", 0)

                    cursor.execute(
                        "SELECT version, value, completed FROM entries WHERE date = ? AND tracker_id = ?",
                        (date_str, tracker_id)
                    )
                    row = cursor.fetchone()
                    server_version = row["version"] if row else 0

                    if row and server_version > client_base_version:
                        server_data = {
                            "value": row["value"],
                            "completed": bool(row["completed"]) if row["completed"] is not None else None,
                            "_version": server_version
                        }
                        conflicts.append(ConflictInfo(
                            entityType="entry",
                            entityId=f"{date_str}|{tracker_id}",
                            serverVersion=server_version,
                            clientBaseVersion=client_base_version,
                            serverData=server_data
                        ))
                        continue

                    value = data.get("value")
                    completed = data.get("completed")
                    completed_int = 1 if completed else 0 if completed is not None else None
                    new_version = server_version + 1

                    cursor.execute("""
                        INSERT INTO entries (date, tracker_id, value, completed, version,
                                           last_modified_by, last_modified_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(date, tracker_id) DO UPDATE SET
                            value = excluded.value,
                            completed = excluded.completed,
                            version = excluded.version,
                            last_modified_by = excluded.last_modified_by,
                            last_modified_at = excluded.last_modified_at
                    """, (date_str, tracker_id, value, completed_int, new_version, client_id, now))

                    applied_days[date_str][tracker_id] = {
                        "value": value,
                        "completed": completed,
                        "_version": new_version,
                        "_lastModifiedBy": client_id,
                        "_lastModifiedAt": now
                    }

            if not conflicts:
                cursor.execute("""
                    INSERT OR REPLACE INTO meta_sync (key, value)
                    VALUES ('last_server_sync_time', ?)
                """, (now,))

            _prune_old_conflicts(cursor)
            conn.commit()

            return SyncResponse(
                success=len(conflicts) == 0,
                conflicts=conflicts,
                appliedConfig=applied_config,
                appliedDays=applied_days,
                lastModified=now if not conflicts else None,
                overwrittenData=overwritten_data
            )

        except Exception as e:
            conn.rollback()
            raise HTTPException(status_code=500, detail=str(e))


@router.post("/sync/resolve-conflict")
def resolve_conflict(
    entity_type: str,
    entity_id: str,
    resolution: str,
    client_id: str,
    client_data: Optional[dict[str, Any]] = None
):
    """Resolve a specific conflict by choosing client or server version."""
    now = get_utc_now()

    with get_db() as conn:
        cursor = conn.cursor()

        if resolution == "client" and client_data:
            if entity_type == "tracker":
                cursor.execute("SELECT version FROM trackers WHERE id = ?", (entity_id,))
                row = cursor.fetchone()
                new_version = (row["version"] if row else 0) + 1

                name = client_data.get("name")
                category = client_data.get("category", "")
                tracker_type = client_data.get("type", "simple")
                excluded_keys = {"id", "name", "category", "type", "_version", "_baseVersion",
                               "_lastModifiedBy", "_lastModifiedAt", "_deleted"}
                meta = {k: v for k, v in client_data.items() if k not in excluded_keys}

                cursor.execute("""
                    INSERT OR REPLACE INTO trackers
                    (id, name, category, type, meta_json, version, last_modified_by, last_modified_at, deleted)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, (entity_id, name, category, tracker_type, json.dumps(meta),
                      new_version, client_id, now))

            elif entity_type == "entry":
                date_str, tracker_id = entity_id.split("|")
                cursor.execute(
                    "SELECT version FROM entries WHERE date = ? AND tracker_id = ?",
                    (date_str, tracker_id)
                )
                row = cursor.fetchone()
                new_version = (row["version"] if row else 0) + 1

                value = client_data.get("value")
                completed = client_data.get("completed")
                completed_int = 1 if completed else 0 if completed is not None else None

                cursor.execute("""
                    INSERT OR REPLACE INTO entries
                    (date, tracker_id, value, completed, version, last_modified_by, last_modified_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """, (date_str, tracker_id, value, completed_int, new_version, client_id, now))

        cursor.execute("""
            INSERT INTO sync_conflicts
            (entity_type, entity_id, client_id, client_data, resolution, resolved_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """, (entity_type, entity_id, client_id,
              json.dumps(client_data) if client_data else None,
              resolution, now, now))

        cursor.execute("""
            INSERT OR REPLACE INTO meta_sync (key, value)
            VALUES ('last_server_sync_time', ?)
        """, (now,))

        conn.commit()

        return {"status": "ok", "resolution": resolution, "entityId": entity_id}


@router.get("/sync/conflicts")
def get_unresolved_conflicts(client_id: str):
    """Get unresolved conflicts for a client."""
    with get_db() as conn:
        cursor = conn.cursor()
        cursor.execute("""
            SELECT * FROM sync_conflicts
            WHERE client_id = ? AND resolved_at IS NULL
            ORDER BY created_at DESC
        """, (client_id,))
        rows = cursor.fetchall()

        conflicts = []
        for row in rows:
            conflicts.append({
                "id": row["id"],
                "entityType": row["entity_type"],
                "entityId": row["entity_id"],
                "clientData": json.loads(row["client_data"]) if row["client_data"] else None,
                "serverData": json.loads(row["server_data"]) if row["server_data"] else None,
                "createdAt": row["created_at"]
            })

        return {"conflicts": conflicts}


def create_router(db_path: Path) -> APIRouter:
    """Factory: set the DB path, initialize tables, and return the router."""
    global _db_path
    _db_path = db_path
    init_database()
    return router
