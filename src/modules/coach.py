"""
Coach API Router - extracted from coach/src/server.py
Workout plan management and log synchronization (last-write-wins).
"""
import asyncio
import json
import logging
import sqlite3
import subprocess
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Optional

from fastapi import APIRouter, HTTPException, Query, Response
from pydantic import BaseModel

from config import get_hook_path
from modules.db import get_db as _shared_get_db, get_utc_now, register_client as _db_register_client

logger = logging.getLogger(__name__)


# Module-level DB path, set by create_router()
_db_path: Path = None

# Sync window: only send plans/logs within this many days to clients.
# Server retains all data permanently.
SYNC_WINDOW_DAYS = 60


@contextmanager
def get_db():
    """Module-scoped wrapper that binds the module's DB path with foreign keys enabled."""
    with _shared_get_db(_db_path, foreign_keys=True) as conn:
        yield conn


def init_database():
    """Initialize the database with required tables."""
    conn = sqlite3.connect(_db_path)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    cursor.execute("PRAGMA foreign_keys = ON")

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS exercises (
            slug       TEXT PRIMARY KEY,
            name       TEXT NOT NULL UNIQUE,
            equipment  TEXT,
            category   TEXT,
            created_at TEXT NOT NULL,
            source     TEXT DEFAULT 'auto'
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_exercises_name_lookup ON exercises(name COLLATE NOCASE)")

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS workout_sessions (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            date          TEXT NOT NULL UNIQUE,
            day_name      TEXT NOT NULL,
            location      TEXT,
            phase         TEXT,
            duration_min  INTEGER,
            last_modified TEXT NOT NULL,
            modified_by   TEXT,
            extra         TEXT
        )
    """)
    cursor.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_sessions_date ON workout_sessions(date)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_sessions_modified ON workout_sessions(last_modified)")

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS session_blocks (
            id             INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id     INTEGER NOT NULL REFERENCES workout_sessions(id) ON DELETE CASCADE,
            position       INTEGER NOT NULL,
            block_type     TEXT NOT NULL,
            title          TEXT,
            duration_min   INTEGER,
            rest_guidance  TEXT,
            rounds         INTEGER,
            UNIQUE(session_id, position)
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_blocks_session ON session_blocks(session_id)")

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS planned_exercises (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id      INTEGER NOT NULL REFERENCES workout_sessions(id) ON DELETE CASCADE,
            block_id        INTEGER NOT NULL REFERENCES session_blocks(id) ON DELETE CASCADE,
            exercise_key    TEXT NOT NULL,
            position        INTEGER NOT NULL,
            name            TEXT NOT NULL,
            exercise_type   TEXT NOT NULL,
            target_sets     INTEGER,
            target_reps     TEXT,
            target_duration_min INTEGER,
            target_duration_sec INTEGER,
            rounds          INTEGER,
            work_duration_sec   INTEGER,
            rest_duration_sec   INTEGER,
            guidance_note   TEXT,
            hide_weight     INTEGER DEFAULT 0,
            show_time       INTEGER DEFAULT 0,
            extra           TEXT,
            UNIQUE(session_id, exercise_key)
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_exercises_session ON planned_exercises(session_id)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_exercises_name ON planned_exercises(name)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_exercises_type ON planned_exercises(exercise_type)")

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS checklist_items (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            exercise_id     INTEGER NOT NULL REFERENCES planned_exercises(id) ON DELETE CASCADE,
            position        INTEGER NOT NULL,
            item_text       TEXT NOT NULL,
            UNIQUE(exercise_id, position)
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_checklist_items_exercise ON checklist_items(exercise_id)")

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS workout_session_logs (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id      INTEGER REFERENCES workout_sessions(id),
            date            TEXT NOT NULL UNIQUE,
            pain_discomfort TEXT,
            general_notes   TEXT,
            last_modified   TEXT NOT NULL,
            modified_by     TEXT,
            extra           TEXT
        )
    """)
    cursor.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_session_logs_date ON workout_session_logs(date)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_session_logs_modified ON workout_session_logs(last_modified)")

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS exercise_logs (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            session_log_id  INTEGER NOT NULL REFERENCES workout_session_logs(id) ON DELETE CASCADE,
            exercise_id     INTEGER REFERENCES planned_exercises(id),
            exercise_key    TEXT NOT NULL,
            completed       INTEGER DEFAULT 0,
            user_note       TEXT,
            duration_min    REAL,
            avg_hr          INTEGER,
            max_hr          INTEGER,
            extra           TEXT
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_exercise_logs_session ON exercise_logs(session_log_id)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_exercise_logs_exercise ON exercise_logs(exercise_id)")

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS checklist_log_items (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            exercise_log_id INTEGER NOT NULL REFERENCES exercise_logs(id) ON DELETE CASCADE,
            item_text       TEXT NOT NULL
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_checklist_log_items_exercise ON checklist_log_items(exercise_log_id)")

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS set_logs (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            exercise_log_id INTEGER NOT NULL REFERENCES exercise_logs(id) ON DELETE CASCADE,
            set_num         INTEGER NOT NULL,
            weight          REAL,
            reps            INTEGER,
            rpe             REAL,
            unit            TEXT DEFAULT 'lbs',
            duration_sec    REAL,
            completed       INTEGER DEFAULT 0,
            extra           TEXT
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_set_logs_exercise ON set_logs(exercise_log_id)")

    for stmt in [
        "ALTER TABLE planned_exercises ADD COLUMN canonical_slug TEXT REFERENCES exercises(slug)",
        "ALTER TABLE exercise_logs ADD COLUMN canonical_slug TEXT REFERENCES exercises(slug)",
    ]:
        try:
            cursor.execute(stmt)
        except sqlite3.OperationalError as e:
            if "duplicate column" not in str(e).lower():
                raise

    cursor.execute("CREATE INDEX IF NOT EXISTS idx_pe_canonical ON planned_exercises(canonical_slug)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_el_canonical ON exercise_logs(canonical_slug)")

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
        CREATE TABLE IF NOT EXISTS workout_hook_results (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id  INTEGER NOT NULL REFERENCES workout_sessions(id) ON DELETE CASCADE,
            hook_type   TEXT NOT NULL,
            fired_at    TEXT NOT NULL,
            exit_code   INTEGER,
            UNIQUE(session_id, hook_type)
        )
    """)

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS workout_hook_data (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            result_id   INTEGER NOT NULL REFERENCES workout_hook_results(id) ON DELETE CASCADE,
            key         TEXT NOT NULL,
            value       TEXT,
            UNIQUE(result_id, key)
        )
    """)

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS deleted_plans (
            date       TEXT PRIMARY KEY,
            deleted_at TEXT NOT NULL
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_deleted_plans_at ON deleted_plans(deleted_at)")

    # Archive tables for soft-delete safety net (Layer 2)
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS workout_session_logs_archive (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            original_id     INTEGER NOT NULL,
            session_id      INTEGER,
            date            TEXT NOT NULL,
            pain_discomfort TEXT,
            general_notes   TEXT,
            last_modified   TEXT NOT NULL,
            modified_by     TEXT,
            superseded_at   TEXT NOT NULL,
            superseded_by   TEXT
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_archive_logs_date ON workout_session_logs_archive(date)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_archive_logs_superseded ON workout_session_logs_archive(superseded_at)")

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS exercise_logs_archive (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            original_id     INTEGER NOT NULL,
            session_log_id  INTEGER NOT NULL,
            exercise_key    TEXT NOT NULL,
            completed       INTEGER DEFAULT 0,
            user_note       TEXT,
            duration_min    REAL,
            avg_hr          INTEGER,
            max_hr          INTEGER,
            canonical_slug  TEXT
        )
    """)

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS set_logs_archive (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            original_id     INTEGER NOT NULL,
            exercise_log_id INTEGER NOT NULL,
            set_num         INTEGER NOT NULL,
            weight          REAL,
            reps            INTEGER,
            rpe             REAL,
            unit            TEXT DEFAULT 'lbs',
            duration_sec    REAL,
            completed       INTEGER DEFAULT 0
        )
    """)

    conn.commit()
    conn.close()


# ==================== Plan/Log Assembly Helpers ====================


def _assemble_plan(conn, session_row):
    """Assemble plan dict from relational tables for sync response."""
    cursor = conn.cursor()
    session_id = session_row["id"]

    cursor.execute("""
        SELECT * FROM session_blocks WHERE session_id = ? ORDER BY position
    """, (session_id,))
    block_rows = cursor.fetchall()

    blocks = []
    for br in block_rows:
        cursor.execute("""
            SELECT * FROM planned_exercises WHERE block_id = ? ORDER BY position
        """, (br["id"],))
        ex_rows = cursor.fetchall()

        exercises = []
        for er in ex_rows:
            exercise = {
                "id": er["exercise_key"],
                "name": er["name"],
                "type": er["exercise_type"],
            }
            if er["target_sets"] is not None:
                exercise["target_sets"] = er["target_sets"]
            if er["target_reps"] is not None:
                exercise["target_reps"] = er["target_reps"]
            if er["target_duration_min"] is not None:
                exercise["target_duration_min"] = er["target_duration_min"]
            if er["target_duration_sec"] is not None:
                exercise["target_duration_sec"] = er["target_duration_sec"]
            if er["rounds"] is not None:
                exercise["rounds"] = er["rounds"]
            if er["work_duration_sec"] is not None:
                exercise["work_duration_sec"] = er["work_duration_sec"]
            if er["rest_duration_sec"] is not None:
                exercise["rest_duration_sec"] = er["rest_duration_sec"]
            if er["guidance_note"]:
                exercise["guidance_note"] = er["guidance_note"]
            if er["hide_weight"]:
                exercise["hide_weight"] = True
            if er["show_time"]:
                exercise["show_time"] = True

            if er["canonical_slug"]:
                exercise["canonical_slug"] = er["canonical_slug"]

            if er["exercise_type"] == "checklist":
                cursor.execute("""
                    SELECT item_text FROM checklist_items
                    WHERE exercise_id = ? ORDER BY position
                """, (er["id"],))
                exercise["items"] = [r["item_text"] for r in cursor.fetchall()]

            exercises.append(exercise)

        blocks.append({
            "block_index": br["position"],
            "block_type": br["block_type"],
            "title": br["title"],
            "duration_min": br["duration_min"],
            "rest_guidance": br["rest_guidance"] or "",
            "rounds": br["rounds"],
            "exercises": exercises,
        })

    return {
        "session_id": session_row["id"],
        "day_name": session_row["day_name"],
        "location": session_row["location"],
        "phase": session_row["phase"],
        "total_duration_min": session_row["duration_min"],
        "blocks": blocks,
    }


def _assemble_log(conn, log_row):
    """Assemble log dict from relational tables for sync response."""
    cursor = conn.cursor()
    log = {}

    feedback = {}
    if log_row["pain_discomfort"]:
        feedback["pain_discomfort"] = log_row["pain_discomfort"]
    if log_row["general_notes"]:
        feedback["general_notes"] = log_row["general_notes"]
    log["session_feedback"] = feedback

    cursor.execute("""
        SELECT * FROM exercise_logs WHERE session_log_id = ?
    """, (log_row["id"],))

    for el in cursor.fetchall():
        entry = {}
        if el["completed"]:
            entry["completed"] = True
        if el["user_note"]:
            entry["user_note"] = el["user_note"]
        if el["duration_min"] is not None:
            entry["duration_min"] = el["duration_min"]
        if el["avg_hr"] is not None:
            entry["avg_hr"] = el["avg_hr"]
        if el["max_hr"] is not None:
            entry["max_hr"] = el["max_hr"]

        cursor.execute("""
            SELECT * FROM set_logs WHERE exercise_log_id = ? ORDER BY set_num
        """, (el["id"],))
        sets = cursor.fetchall()
        if sets:
            entry["sets"] = []
            for s in sets:
                set_dict = {"set_num": s["set_num"]}
                if s["weight"] is not None:
                    set_dict["weight"] = s["weight"]
                if s["reps"] is not None:
                    set_dict["reps"] = s["reps"]
                if s["rpe"] is not None:
                    set_dict["rpe"] = s["rpe"]
                if s["unit"]:
                    set_dict["unit"] = s["unit"]
                if s["duration_sec"] is not None:
                    set_dict["duration_sec"] = s["duration_sec"]
                if s["completed"]:
                    set_dict["completed"] = True
                entry["sets"].append(set_dict)

        cursor.execute("""
            SELECT item_text FROM checklist_log_items WHERE exercise_log_id = ?
        """, (el["id"],))
        items = cursor.fetchall()
        if items:
            entry["completed_items"] = [r["item_text"] for r in items]

        log[el["exercise_key"]] = entry

    return log


ARCHIVE_RETENTION_DAYS = 14


def _purge_old_archives(cursor):
    """Remove archive rows older than the retention window."""
    cutoff = (datetime.now(timezone.utc) - timedelta(days=ARCHIVE_RETENTION_DAYS)).isoformat()
    old_rows = cursor.execute(
        "SELECT id, original_id FROM workout_session_logs_archive WHERE superseded_at < ?",
        (cutoff,)
    ).fetchall()
    if not old_rows:
        return
    # exercise_logs_archive.session_log_id references the original session log id
    original_ids = [row["original_id"] for row in old_rows]
    archive_ids = [row["id"] for row in old_rows]
    orig_ph = ",".join("?" * len(original_ids))
    arch_ph = ",".join("?" * len(archive_ids))
    cursor.execute(
        f"DELETE FROM set_logs_archive WHERE exercise_log_id IN "
        f"(SELECT original_id FROM exercise_logs_archive WHERE session_log_id IN ({orig_ph}))",
        original_ids,
    )
    cursor.execute(
        f"DELETE FROM exercise_logs_archive WHERE session_log_id IN ({orig_ph})",
        original_ids,
    )
    cursor.execute(
        f"DELETE FROM workout_session_logs_archive WHERE id IN ({arch_ph})",
        archive_ids,
    )
    logger.info("Purged %d archived session logs older than %s", len(archive_ids), cutoff)


def _archive_existing_log(cursor, date_str, superseded_by, now):
    """Copy existing log data to archive tables before deletion."""
    row = cursor.execute(
        "SELECT * FROM workout_session_logs WHERE date = ?", (date_str,)
    ).fetchone()
    if not row:
        return

    cursor.execute("""
        INSERT INTO workout_session_logs_archive
        (original_id, session_id, date, pain_discomfort, general_notes,
         last_modified, modified_by, superseded_at, superseded_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (row["id"], row["session_id"], row["date"], row["pain_discomfort"],
          row["general_notes"], row["last_modified"], row["modified_by"],
          now, superseded_by))

    exercises = cursor.execute(
        "SELECT * FROM exercise_logs WHERE session_log_id = ?", (row["id"],)
    ).fetchall()
    for ex in exercises:
        cursor.execute("""
            INSERT INTO exercise_logs_archive
            (original_id, session_log_id, exercise_key, completed, user_note,
             duration_min, avg_hr, max_hr, canonical_slug)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (ex["id"], ex["session_log_id"], ex["exercise_key"], ex["completed"],
              ex["user_note"], ex["duration_min"], ex["avg_hr"], ex["max_hr"],
              ex["canonical_slug"]))

        sets = cursor.execute(
            "SELECT * FROM set_logs WHERE exercise_log_id = ?", (ex["id"],)
        ).fetchall()
        for s in sets:
            cursor.execute("""
                INSERT INTO set_logs_archive
                (original_id, exercise_log_id, set_num, weight, reps, rpe,
                 unit, duration_sec, completed)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (s["id"], s["exercise_log_id"], s["set_num"], s["weight"],
                  s["reps"], s["rpe"], s["unit"], s["duration_sec"], s["completed"]))


def _store_log(conn, date_str, log_data, client_id, now):
    """Decompose a log dict into relational tables.

    Returns the session_log_id on success, or None if the write was rejected
    (incoming timestamp older than server's).
    """
    cursor = conn.cursor()

    # Layer 1: Reject stale writes by comparing timestamps
    incoming_modified = log_data.get("_lastModifiedAt")
    if incoming_modified:
        existing = cursor.execute(
            "SELECT last_modified FROM workout_session_logs WHERE date = ?",
            (date_str,)
        ).fetchone()
        if existing and existing["last_modified"] > incoming_modified:
            logger.warning(
                "Rejecting stale log for %s: server=%s > client=%s (client=%s)",
                date_str, existing["last_modified"], incoming_modified, client_id,
            )
            return None

    feedback = log_data.get("session_feedback", {})
    pain = feedback.get("pain_discomfort")
    notes = feedback.get("general_notes")

    cursor.execute("SELECT id FROM workout_sessions WHERE date = ?", (date_str,))
    session_row = cursor.fetchone()
    session_id = session_row["id"] if session_row else None

    # Layer 2: Archive existing log before deletion
    _archive_existing_log(cursor, date_str, client_id, now)

    cursor.execute("DELETE FROM workout_session_logs WHERE date = ?", (date_str,))

    cursor.execute("""
        INSERT INTO workout_session_logs
        (session_id, date, pain_discomfort, general_notes, last_modified, modified_by)
        VALUES (?, ?, ?, ?, ?, ?)
    """, (session_id, date_str, pain, notes, now, client_id))
    session_log_id = cursor.lastrowid

    meta_keys = {"session_feedback", "_lastModifiedAt", "_lastModifiedBy"}
    for exercise_key, exercise_data in log_data.items():
        if exercise_key in meta_keys:
            continue
        if not isinstance(exercise_data, dict):
            continue

        exercise_id = None
        canonical_slug = None
        if session_id:
            cursor.execute("""
                SELECT id, canonical_slug FROM planned_exercises
                WHERE session_id = ? AND exercise_key = ?
            """, (session_id, exercise_key))
            ex_row = cursor.fetchone()
            if ex_row:
                exercise_id = ex_row["id"]
                canonical_slug = ex_row["canonical_slug"]

        cursor.execute("""
            INSERT INTO exercise_logs
            (session_log_id, exercise_id, exercise_key, completed, user_note,
             duration_min, avg_hr, max_hr, canonical_slug)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            session_log_id, exercise_id, exercise_key,
            1 if exercise_data.get("completed") else 0,
            exercise_data.get("user_note"),
            exercise_data.get("duration_min"),
            exercise_data.get("avg_hr"),
            exercise_data.get("max_hr"),
            canonical_slug,
        ))
        exercise_log_id = cursor.lastrowid

        for s in exercise_data.get("sets", []):
            cursor.execute("""
                INSERT INTO set_logs
                (exercise_log_id, set_num, weight, reps, rpe, unit, duration_sec, completed)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                exercise_log_id, s.get("set_num", 0),
                s.get("weight"), s.get("reps"), s.get("rpe"),
                s.get("unit", "lbs"), s.get("duration_sec"),
                1 if s.get("completed") else 0,
            ))

        for item in exercise_data.get("completed_items", []):
            cursor.execute("""
                INSERT INTO checklist_log_items (exercise_log_id, item_text)
                VALUES (?, ?)
            """, (exercise_log_id, item))

    return session_log_id


# Pydantic models
class WorkoutSyncPayload(BaseModel):
    clientId: str
    logs: dict[str, Any] = {}

class WorkoutSyncResponse(BaseModel):
    plans: dict[str, Any]
    logs: dict[str, Any]
    serverTime: str
    earliestDate: str
    deletedPlanDates: list[str] = []

class StatusResponse(BaseModel):
    lastModified: Optional[str] = None


class PlansVersionResponse(BaseModel):
    version: Optional[str] = None


class WorkoutActionResponse(BaseModel):
    status: str
    result_id: int


class WorkoutPhaseData(BaseModel):
    fired_at: str
    exit_code: Optional[int] = None
    data: dict[str, Any] = {}


class WorkoutStatusResponse(BaseModel):
    start: Optional[WorkoutPhaseData] = None
    end: Optional[WorkoutPhaseData] = None
    actions_available: dict[str, bool]


class WorkoutConfigResponse(BaseModel):
    start: bool
    end: bool


# Router with all workout endpoints
router = APIRouter()


@router.get("/status", response_model=StatusResponse)
def workout_status():
    """Get the last server sync time."""
    with get_db() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT value FROM meta_sync WHERE key = 'last_server_sync_time'")
        row = cursor.fetchone()

        if row:
            return StatusResponse(lastModified=row["value"])
        return StatusResponse(lastModified=None)


@router.get("/plans-version", response_model=PlansVersionResponse)
def plans_version():
    """Return the latest plan modification timestamp (cheap version check)."""
    with get_db() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT MAX(last_modified) as v FROM workout_sessions")
        row = cursor.fetchone()
        return PlansVersionResponse(version=row["v"] if row else None)


@router.post("/register")
def register_client(client_id: str, client_name: Optional[str] = None):
    """Register or update a client."""
    with get_db() as conn:
        _db_register_client(conn, client_id, client_name)
        conn.commit()
        return {"status": "ok", "clientId": client_id}


@router.get("/sync", response_model=WorkoutSyncResponse)
def workout_sync_get(
    response: Response,
    client_id: str = Query(...),
    last_sync_time: Optional[str] = Query(None),
):
    """Fetch workout plans and logs."""
    response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
    response.headers["Pragma"] = "no-cache"

    with get_db() as conn:
        cursor = conn.cursor()

        now = get_utc_now()
        cursor.execute("""
            UPDATE clients SET last_seen_at = ? WHERE id = ?
        """, (now, client_id))

        cutoff = (datetime.now(timezone.utc) - timedelta(days=SYNC_WINDOW_DAYS)).strftime("%Y-%m-%d")

        if last_sync_time:
            cursor.execute("""
                SELECT * FROM workout_sessions
                WHERE last_modified > ?
                ORDER BY date
            """, (last_sync_time,))
        else:
            cursor.execute("""
                SELECT * FROM workout_sessions
                WHERE date >= ?
                ORDER BY date
            """, (cutoff,))

        session_rows = cursor.fetchall()
        plans = {}
        for row in session_rows:
            plan = _assemble_plan(conn, row)
            plan["_lastModified"] = row["last_modified"]
            plans[row["date"]] = plan

        if last_sync_time:
            cursor.execute("""
                SELECT * FROM workout_session_logs
                WHERE last_modified > ?
                ORDER BY date
            """, (last_sync_time,))
        else:
            cursor.execute("""
                SELECT * FROM workout_session_logs
                WHERE date >= ?
                ORDER BY date
            """, (cutoff,))

        log_rows = cursor.fetchall()
        logs = {}
        for row in log_rows:
            log = _assemble_log(conn, row)
            log["_lastModified"] = row["last_modified"]
            logs[row["date"]] = log

        # Tombstones: return deleted plan dates for incremental sync
        deleted_plan_dates = []
        if last_sync_time:
            cursor.execute("""
                SELECT date FROM deleted_plans
                WHERE deleted_at > ?
            """, (last_sync_time,))
            deleted_plan_dates = [row["date"] for row in cursor.fetchall()]

        # Prune tombstones older than the sync window
        cursor.execute("DELETE FROM deleted_plans WHERE deleted_at < ?", (cutoff,))

        conn.commit()
        return WorkoutSyncResponse(
            plans=plans, logs=logs, serverTime=now,
            earliestDate=cutoff, deletedPlanDates=deleted_plan_dates
        )


@router.post("/sync")
def workout_sync_post(payload: WorkoutSyncPayload):
    """Upload workout logs from client (last-write-wins with timestamp guard)."""
    with get_db() as conn:
        cursor = conn.cursor()
        now = get_utc_now()
        client_id = payload.clientId

        _db_register_client(conn, client_id, now=now)

        applied_logs = []
        rejected_logs = []

        try:
            for date_str, log_data in payload.logs.items():
                result = _store_log(conn, date_str, log_data, client_id, now)
                if result is None:
                    rejected_logs.append(date_str)
                else:
                    applied_logs.append(date_str)

            cursor.execute("""
                INSERT OR REPLACE INTO meta_sync (key, value)
                VALUES ('last_server_sync_time', ?)
            """, (now,))

            _purge_old_archives(cursor)

            conn.commit()
        except Exception:
            conn.rollback()
            raise

        return {
            "success": True,
            "appliedLogs": applied_logs,
            "rejectedLogs": rejected_logs,
            "serverTime": now
        }


async def _run_hook(result_id: int, script_path: Path):
    """Run a hook script asynchronously and store results in the database."""
    try:
        proc = await asyncio.create_subprocess_exec(
            str(script_path),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        stdout, stderr = await proc.communicate()
        exit_code = proc.returncode

        with get_db() as conn:
            cursor = conn.cursor()
            cursor.execute(
                "UPDATE workout_hook_results SET exit_code = ? WHERE id = ?",
                (exit_code, result_id),
            )

            # Parse stdout as JSON and store key/value pairs
            if exit_code == 0 and stdout:
                try:
                    data = json.loads(stdout.decode())
                    if isinstance(data, dict):
                        # Clear old data for retry/upsert
                        cursor.execute(
                            "DELETE FROM workout_hook_data WHERE result_id = ?",
                            (result_id,),
                        )
                        for key, value in data.items():
                            cursor.execute(
                                "INSERT INTO workout_hook_data (result_id, key, value) VALUES (?, ?, ?)",
                                (result_id, key, json.dumps(value) if not isinstance(value, str) else value),
                            )
                    else:
                        logger.warning("Hook %d output is not a JSON object", result_id)
                except (json.JSONDecodeError, UnicodeDecodeError) as e:
                    logger.warning("Hook %d produced invalid JSON: %s", result_id, e)

            conn.commit()

    except FileNotFoundError:
        logger.error("Hook script not found: %s", script_path)
        with get_db() as conn:
            conn.execute(
                "UPDATE workout_hook_results SET exit_code = ? WHERE id = ?",
                (-1, result_id),
            )
            conn.commit()
    except Exception:
        logger.exception("Hook %d failed unexpectedly", result_id)
        with get_db() as conn:
            conn.execute(
                "UPDATE workout_hook_results SET exit_code = ? WHERE id = ?",
                (-1, result_id),
            )
            conn.commit()


async def _start_or_end_workout(session_id: int, hook_type: str, action_label: str):
    """Shared logic for start/end workout endpoints."""
    script_path = get_hook_path(hook_type)
    if not script_path or not script_path.exists():
        raise HTTPException(status_code=400, detail=f"No {action_label} action configured")

    with get_db() as conn:
        cursor = conn.cursor()

        cursor.execute("SELECT id FROM workout_sessions WHERE id = ?", (session_id,))
        if not cursor.fetchone():
            raise HTTPException(status_code=404, detail="Session not found")

        now = get_utc_now()

        cursor.execute(
            """INSERT INTO workout_hook_results (session_id, hook_type, fired_at, exit_code)
               VALUES (?, ?, ?, NULL)
               ON CONFLICT(session_id, hook_type) DO UPDATE
               SET fired_at = excluded.fired_at, exit_code = NULL""",
            (session_id, hook_type, now),
        )
        result_id = cursor.lastrowid
        if result_id == 0:
            cursor.execute(
                "SELECT id FROM workout_hook_results WHERE session_id = ? AND hook_type = ?",
                (session_id, hook_type),
            )
            result_id = cursor.fetchone()["id"]
            cursor.execute("DELETE FROM workout_hook_data WHERE result_id = ?", (result_id,))

        conn.commit()

    asyncio.create_task(_run_hook(result_id, script_path))

    return WorkoutActionResponse(status=action_label, result_id=result_id)


def _undo_workout_action(session_id: int, hook_type: str):
    """Shared logic for undoing start/end workout."""
    with get_db() as conn:
        cursor = conn.cursor()
        cursor.execute(
            "DELETE FROM workout_hook_results WHERE session_id = ? AND hook_type = ?",
            (session_id, hook_type),
        )
        if cursor.rowcount == 0:
            raise HTTPException(status_code=404, detail="Nothing to undo")
        conn.commit()

    return {"status": "deleted"}


@router.post("/workout/{session_id}/start", response_model=WorkoutActionResponse)
async def start_workout(session_id: int):
    """Notify the server that a workout is starting."""
    return await _start_or_end_workout(session_id, "pre", "started")


@router.post("/workout/{session_id}/end", response_model=WorkoutActionResponse)
async def end_workout(session_id: int):
    """Notify the server that a workout has ended."""
    return await _start_or_end_workout(session_id, "post", "ended")


@router.delete("/workout/{session_id}/start")
def undo_start_workout(session_id: int):
    """Undo a workout start notification."""
    return _undo_workout_action(session_id, "pre")


@router.delete("/workout/{session_id}/end")
def undo_end_workout(session_id: int):
    """Undo a workout end notification."""
    return _undo_workout_action(session_id, "post")


@router.get("/workout/config", response_model=WorkoutConfigResponse)
def get_workout_config():
    """Get available workout actions."""
    return WorkoutConfigResponse(
        start=_is_hook_available("pre"),
        end=_is_hook_available("post"),
    )


@router.get("/workout/{session_id}/status", response_model=WorkoutStatusResponse)
def get_workout_status(session_id: int):
    """Get workout status for a session."""
    with get_db() as conn:
        cursor = conn.cursor()

        result = {"start": None, "end": None}
        for hook_type, key in (("pre", "start"), ("post", "end")):
            cursor.execute(
                "SELECT * FROM workout_hook_results WHERE session_id = ? AND hook_type = ?",
                (session_id, hook_type),
            )
            row = cursor.fetchone()
            if row:
                cursor.execute(
                    "SELECT key, value FROM workout_hook_data WHERE result_id = ?",
                    (row["id"],),
                )
                data = {r["key"]: r["value"] for r in cursor.fetchall()}
                result[key] = WorkoutPhaseData(
                    fired_at=row["fired_at"],
                    exit_code=row["exit_code"],
                    data=data,
                )

        actions_available = {
            "start": _is_hook_available("pre"),
            "end": _is_hook_available("post"),
        }

        return WorkoutStatusResponse(**result, actions_available=actions_available)


def _is_hook_available(hook_type: str) -> bool:
    """Check whether a hook script is configured and exists."""
    path = get_hook_path(hook_type)
    return path is not None and path.exists()


def create_router(db_path: Path) -> APIRouter:
    """Factory: set the DB path, initialize tables, and return the router."""
    global _db_path
    _db_path = db_path
    init_database()
    return router
