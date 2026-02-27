"""
Coach API Router - extracted from coach/src/server.py
Workout plan management and log synchronization (last-write-wins).
"""
import json
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from contextlib import contextmanager
from typing import Any, Optional

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel


# Module-level DB path, set by create_router()
_db_path: Path = None


@contextmanager
def get_db():
    """Context manager for database connections."""
    conn = sqlite3.connect(_db_path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        yield conn
    finally:
        conn.close()


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

    conn.commit()
    conn.close()


def get_utc_now() -> str:
    """Return current UTC time as ISO-8601 string."""
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


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


def _store_log(conn, date_str, log_data, client_id, now):
    """Decompose a log dict into relational tables."""
    cursor = conn.cursor()

    feedback = log_data.get("session_feedback", {})
    pain = feedback.get("pain_discomfort")
    notes = feedback.get("general_notes")

    cursor.execute("SELECT id FROM workout_sessions WHERE date = ?", (date_str,))
    session_row = cursor.fetchone()
    session_id = session_row["id"] if session_row else None

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


# Pydantic models
class WorkoutSyncPayload(BaseModel):
    clientId: str
    logs: dict[str, Any] = {}

class WorkoutSyncResponse(BaseModel):
    plans: dict[str, Any]
    logs: dict[str, Any]
    serverTime: str

class StatusResponse(BaseModel):
    lastModified: Optional[str] = None


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


@router.post("/register")
def register_client(client_id: str, client_name: Optional[str] = None):
    """Register or update a client."""
    with get_db() as conn:
        cursor = conn.cursor()
        now = get_utc_now()
        cursor.execute("""
            INSERT OR REPLACE INTO clients (id, name, last_seen_at)
            VALUES (?, ?, ?)
        """, (client_id, client_name or f"Client-{client_id[:8]}", now))
        conn.commit()
        return {"status": "ok", "clientId": client_id}


@router.get("/sync", response_model=WorkoutSyncResponse)
def workout_sync_get(
    client_id: str = Query(...),
    last_sync_time: Optional[str] = Query(None)
):
    """Fetch workout plans and logs."""
    with get_db() as conn:
        cursor = conn.cursor()

        now = get_utc_now()
        cursor.execute("""
            UPDATE clients SET last_seen_at = ? WHERE id = ?
        """, (now, client_id))

        if last_sync_time:
            cursor.execute("""
                SELECT * FROM workout_sessions
                WHERE last_modified > ?
                ORDER BY date
            """, (last_sync_time,))
        else:
            cursor.execute("SELECT * FROM workout_sessions ORDER BY date")

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
            thirty_days_ago = (datetime.now() - timedelta(days=30)).strftime("%Y-%m-%d")
            cursor.execute("""
                SELECT * FROM workout_session_logs
                WHERE date >= ?
                ORDER BY date
            """, (thirty_days_ago,))

        log_rows = cursor.fetchall()
        logs = {}
        for row in log_rows:
            log = _assemble_log(conn, row)
            log["_lastModified"] = row["last_modified"]
            logs[row["date"]] = log

        conn.commit()
        return WorkoutSyncResponse(plans=plans, logs=logs, serverTime=now)


@router.post("/sync")
def workout_sync_post(payload: WorkoutSyncPayload):
    """Upload workout logs from client (last-write-wins)."""
    with get_db() as conn:
        cursor = conn.cursor()
        now = get_utc_now()
        client_id = payload.clientId

        cursor.execute("""
            INSERT OR REPLACE INTO clients (id, name, last_seen_at)
            VALUES (?, ?, ?)
        """, (client_id, f"Client-{client_id[:8]}", now))

        applied_logs = []

        for date_str, log_data in payload.logs.items():
            _store_log(conn, date_str, log_data, client_id, now)
            applied_logs.append(date_str)

        cursor.execute("""
            INSERT OR REPLACE INTO meta_sync (key, value)
            VALUES ('last_server_sync_time', ?)
        """, (now,))

        conn.commit()

        return {
            "success": True,
            "appliedLogs": applied_logs,
            "serverTime": now
        }


def create_router(db_path: Path) -> APIRouter:
    """Factory: set the DB path, initialize tables, and return the router."""
    global _db_path
    _db_path = db_path
    init_database()
    return router
