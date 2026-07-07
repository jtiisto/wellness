"""
Coach API Router - extracted from coach/src/server.py
Workout plan management and log synchronization (per-record server-token
arbitration — the server compares its stored stamp against the client's echoed
base token, never the client clock).
"""
import asyncio
import json
import logging
import os
import signal
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Optional

from fastapi import APIRouter, HTTPException, Query, Response
from pydantic import BaseModel

from config import get_hook_path
from modules.coach_plans import assemble_plan
from modules.coach_logs import AD_HOC_LOG_SLUGS, assemble_log, should_accept_log_write
from modules.db import (
    DbAccessor,
    get_utc_now,
    utc_days_ago,
    sync_watermark,
    register_client as _db_register_client,
    run_migrations,
    enable_wal,
    column_exists,
    immediate_transaction,
)
from modules.background import spawn

logger = logging.getLogger(__name__)


# Sync window: only send plans/logs within this many days to clients.
# Server retains all data permanently.
SYNC_WINDOW_DAYS = 60


def _migration_1_baseline(cursor):
    """Baseline coach schema (the full pre-registry schema, minus the two
    block-level interval columns which land in migration 2 to mirror the
    original ALTER history).

    Idempotent: every CREATE uses IF NOT EXISTS, so adopting an existing
    unversioned (user_version=0) production DB is a clean no-op that just stamps
    the version forward.
    """
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
            id                INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id        INTEGER NOT NULL REFERENCES workout_sessions(id) ON DELETE CASCADE,
            position          INTEGER NOT NULL,
            block_type        TEXT NOT NULL,
            title             TEXT,
            duration_min      INTEGER,
            rest_guidance     TEXT,
            rounds            INTEGER,
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
            superset_group  TEXT,
            canonical_slug  TEXT REFERENCES exercises(slug),
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
            user_note       TEXT,
            duration_min    REAL,
            avg_hr          INTEGER,
            max_hr          INTEGER,
            canonical_slug  TEXT REFERENCES exercises(slug),
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

    _create_deleted_exercise_logs(cursor)

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


def _migration_2_block_interval_cols(cursor):
    """Add block-level interval timing columns (rounds/work/rest are canonical at
    the block level; see _transform_block_plan in the coach MCP). Guarded so a DB
    that already has them — e.g. one adopted from the pre-registry CREATE+ALTER —
    is unchanged.
    """
    for col, decl in (("work_duration_sec", "INTEGER"), ("rest_duration_sec", "INTEGER")):
        if not column_exists(cursor, "session_blocks", col):
            cursor.execute(f"ALTER TABLE session_blocks ADD COLUMN {col} {decl}")


def _migration_3_exercise_log_token(cursor):
    """Add the per-exercise optimistic-concurrency token (R3). Each exercise_logs
    row gets its own server stamp so log writes arbitrate per exercise, not by
    whole-day replace. Guarded; existing rows backfill to NULL, which the
    arbiter treats as "accept the client's write" (then it gets a real stamp).
    """
    if not column_exists(cursor, "exercise_logs", "last_modified"):
        cursor.execute("ALTER TABLE exercise_logs ADD COLUMN last_modified TEXT")


def _migration_4_planned_exercise_tempo(cursor):
    """Add the optional strength `tempo` prescription column (free-form text,
    e.g. "3-1-2-0" or "30X1"). Guarded; existing rows backfill to NULL (no
    tempo). Tempo used to live inline in guidance_note as "Tempo X" — those
    historical notes are left as-is (no backfill), so old plans keep showing
    tempo in the note while new plans use this structured field.
    """
    if not column_exists(cursor, "planned_exercises", "tempo"):
        cursor.execute("ALTER TABLE planned_exercises ADD COLUMN tempo TEXT")


def _create_deleted_exercise_logs(cursor):
    """Per-exercise log tombstones (shared by baseline and migration 6).

    A row here means "this exercise entry was deliberately deleted": it lets
    incremental sync re-deliver the day to other clients and lets `_store_log`
    reject a stale edit that would otherwise re-insert (resurrect) the deleted
    row. Tombstones are pruned outside the sync window like `deleted_plans`.
    """
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS deleted_exercise_logs (
            date         TEXT NOT NULL,
            exercise_key TEXT NOT NULL,
            deleted_at   TEXT NOT NULL,
            PRIMARY KEY (date, exercise_key)
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_deleted_el_at ON deleted_exercise_logs(deleted_at)")


def _migration_5_prescription_fields(cursor):
    """Add the optional strength intensity prescriptions: `target_rpe` (free-form,
    e.g. "6-7" or "8" — TEXT so it can hold a range like target_reps) and
    `target_load` (free-form, e.g. "70%", "24kg", "level 5"). Both guarded;
    existing rows backfill to NULL. `target_load` supersedes the old practice of
    folding a `load_guide` cue into guidance_note — historical notes are left
    as-is (no backfill).
    """
    if not column_exists(cursor, "planned_exercises", "target_rpe"):
        cursor.execute("ALTER TABLE planned_exercises ADD COLUMN target_rpe TEXT")
    if not column_exists(cursor, "planned_exercises", "target_load"):
        cursor.execute("ALTER TABLE planned_exercises ADD COLUMN target_load TEXT")


# Ordered (target_version, migration_fn) pairs — see db.run_migrations for the
# transactional contract. Migration fns are DDL-only and must not manage their
# own transactions.
def _migration_6_deleted_exercise_logs(cursor):
    """Add the per-exercise log tombstone table (log-deletion sync support).
    Idempotent CREATE IF NOT EXISTS, shared with the baseline."""
    _create_deleted_exercise_logs(cursor)


MIGRATIONS = [
    (1, _migration_1_baseline),
    (2, _migration_2_block_interval_cols),
    (3, _migration_3_exercise_log_token),
    (4, _migration_4_planned_exercise_tempo),
    (5, _migration_5_prescription_fields),
    (6, _migration_6_deleted_exercise_logs),
]


def init_database(accessor):
    """Initialize the coach database via the shared migration registry.

    Enables WAL once (outside any transaction) then applies pending migrations
    transactionally. See db.run_migrations for the BEGIN IMMEDIATE / in-lock
    re-check contract.
    """
    with accessor.get_db() as conn:
        enable_wal(conn)
        run_migrations(conn, MIGRATIONS, label="coach DB")


# ==================== Plan/Log Assembly Helpers ====================


def _assemble_plan(conn, session_row):
    """Assemble plan dict for the sync response.

    Thin adapter over the shared canonical reader (`coach_plans.assemble_plan`),
    which both transports delegate to (plans/ phase 3).
    """
    return assemble_plan(conn.cursor(), session_row)


def _assemble_log(conn, log_row):
    """Assemble log dict for the sync response (lean shape — the PWA derives
    completion client-side). Thin adapter over the shared canonical reader
    `coach_logs.assemble_log` (plans/ phase 3).
    """
    return assemble_log(conn.cursor(), log_row)


def _assemble_log_for_date(cursor, date_str):
    """The server's current log for `date_str` in the lean sync shape (plus its
    `_lastModified` stamp), or None if there is none. Returned as the `serverRow`
    on a rejected upload so the client adopts both the content and the fresh base
    token in-cycle (R1)."""
    row = cursor.execute(
        "SELECT * FROM workout_session_logs WHERE date = ?", (date_str,)
    ).fetchone()
    if not row:
        return None
    server_row = assemble_log(cursor, row)
    server_row["_lastModified"] = row["last_modified"]
    return server_row


ARCHIVE_RETENTION_DAYS = 14


def _purge_old_archives(cursor):
    """Remove archive rows older than the retention window."""
    # Both `superseded_at` (stored via get_utc_now) and this cutoff are now
    # Z-suffixed UTC instants from the same formatter, so the lexical compare is
    # exact (R5 — was the +00:00 vs Z drift documented as deferred bugfix #4/#5).
    cutoff = utc_days_ago(ARCHIVE_RETENTION_DAYS)
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
        # The archive table keeps its `completed` column for historical rows but
        # is no longer populated (it defaults to 0); completion is derived now.
        cursor.execute("""
            INSERT INTO exercise_logs_archive
            (original_id, session_log_id, exercise_key, user_note,
             duration_min, avg_hr, max_hr, canonical_slug)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, (ex["id"], ex["session_log_id"], ex["exercise_key"],
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


def _adhoc_canonical_slug(cursor, exercise_key, now):
    """Canonical slug for a well-known ad-hoc (off-plan) log key, or None.

    Self-heals the registry row (INSERT OR IGNORE) so the exercise_logs FK is
    always satisfiable on a fresh database.
    """
    spec = AD_HOC_LOG_SLUGS.get(exercise_key)
    if spec is None:
        return None
    cursor.execute(
        "INSERT OR IGNORE INTO exercises (slug, name, equipment, category, created_at, source) "
        "VALUES (?, ?, NULL, ?, ?, 'auto')",
        (spec["slug"], spec["name"], spec["category"], now),
    )
    return spec["slug"]


def _delete_exercise_log(cursor, exercise_log_id):
    """Hard-delete one exercise_logs row with its children. Children are removed
    explicitly (not via FK cascade) so the behavior doesn't depend on the
    connection's foreign_keys pragma."""
    cursor.execute("DELETE FROM set_logs WHERE exercise_log_id = ?", (exercise_log_id,))
    cursor.execute("DELETE FROM checklist_log_items WHERE exercise_log_id = ?", (exercise_log_id,))
    cursor.execute("DELETE FROM exercise_logs WHERE id = ?", (exercise_log_id,))


def _store_log(conn, date_str, log_data, client_id, now):
    """Apply a coach log upload at per-record granularity (R3).

    Upserts the session-log (feedback) row — arbitrated on the session token —
    and each exercise independently on its own per-exercise token: a brand-new or
    NULL-stamped exercise inserts; `stored <= base` updates (replacing that
    exercise's sets/items, since the client's list is authoritative for an
    exercise it sent); a stale base leaves the server's exercise untouched.
    Exercises absent from the payload are never touched. Never whole-rejects.
    Returns the reconciled day (merged serverRow, carrying per-exercise + day
    tokens) for the client to adopt. See plans/phase4-r3-coach-upsert.md.
    """
    cursor = conn.cursor()
    meta_keys = {"session_feedback", "_lastModifiedAt", "_lastModifiedBy", "_baseLastModifiedAt"}

    # Layer 2: archive the existing day before mutating (safety net).
    _archive_existing_log(cursor, date_str, client_id, now)

    # Link to the plan session for this date (for exercise_id / canonical_slug).
    session_row = cursor.execute(
        "SELECT id FROM workout_sessions WHERE date = ?", (date_str,)
    ).fetchone()
    session_id = session_row["id"] if session_row else None

    # --- Feedback record: upsert the session_log, arbitrated on the session token.
    session_base = log_data.get("_baseLastModifiedAt")
    feedback = log_data.get("session_feedback", {})
    pain = feedback.get("pain_discomfort")
    notes = feedback.get("general_notes")

    existing_sl = cursor.execute(
        "SELECT id, last_modified FROM workout_session_logs WHERE date = ?", (date_str,)
    ).fetchone()
    if existing_sl is None:
        cursor.execute("""
            INSERT INTO workout_session_logs
            (session_id, date, pain_discomfort, general_notes, last_modified, modified_by)
            VALUES (?, ?, ?, ?, ?, ?)
        """, (session_id, date_str, pain, notes, now, client_id))
        session_log_id = cursor.lastrowid
    else:
        session_log_id = existing_sl["id"]
        if should_accept_log_write(existing_sl["last_modified"], session_base):
            cursor.execute("""
                UPDATE workout_session_logs
                SET session_id = ?, pain_discomfort = ?, general_notes = ?,
                    last_modified = ?, modified_by = ?
                WHERE id = ?
            """, (session_id, pain, notes, now, client_id, session_log_id))
        # else: feedback's base is stale → keep the server's feedback row.

    # --- Exercise records: arbitrate + upsert each independently.
    for exercise_key, exercise_data in log_data.items():
        if exercise_key in meta_keys or not isinstance(exercise_data, dict):
            continue

        existing_ex = cursor.execute(
            "SELECT id, last_modified FROM exercise_logs "
            "WHERE session_log_id = ? AND exercise_key = ?",
            (session_log_id, exercise_key),
        ).fetchone()

        # Deletion tombstone: the client deliberately removed this entry.
        # Arbitrated on the same per-exercise token as edits; an absent row
        # (upload retry) still refreshes the tombstone so the delete is
        # idempotent and re-delivered to other clients.
        if exercise_data.get("_deleted"):
            if existing_ex is not None:
                if not should_accept_log_write(
                    existing_ex["last_modified"], exercise_data.get("_baseLastModifiedAt")
                ):
                    continue  # remote edit after the deleter's last sync → edit wins
                _delete_exercise_log(cursor, existing_ex["id"])
            cursor.execute(
                "INSERT OR REPLACE INTO deleted_exercise_logs (date, exercise_key, deleted_at) "
                "VALUES (?, ?, ?)",
                (date_str, exercise_key, now),
            )
            continue

        if existing_ex is not None and not should_accept_log_write(
            existing_ex["last_modified"], exercise_data.get("_baseLastModifiedAt")
        ):
            continue  # stale base → keep the server's version of this exercise

        if existing_ex is None:
            tombstone = cursor.execute(
                "SELECT 1 FROM deleted_exercise_logs WHERE date = ? AND exercise_key = ?",
                (date_str, exercise_key),
            ).fetchone()
            if tombstone is not None:
                if exercise_data.get("_baseLastModifiedAt") and not exercise_data.get("_readd"):
                    # A base token proves this client is editing the record that
                    # was deleted → delete wins (else the edit resurrects it).
                    # Exception: `_readd` marks a client that authored the delete
                    # itself and then deliberately re-added — its write keeps the
                    # old stamp (needed to win when its delete has NOT reached the
                    # server yet, i.e. the row still exists) but must not be
                    # mistaken for a stale edit here.
                    continue
                # No base token, or an explicit re-add = deliberate re-creation.
                cursor.execute(
                    "DELETE FROM deleted_exercise_logs WHERE date = ? AND exercise_key = ?",
                    (date_str, exercise_key),
                )

        exercise_id = None
        canonical_slug = None
        if session_id:
            pe = cursor.execute(
                "SELECT id, canonical_slug FROM planned_exercises "
                "WHERE session_id = ? AND exercise_key = ?",
                (session_id, exercise_key),
            ).fetchone()
            if pe:
                exercise_id = pe["id"]
                canonical_slug = pe["canonical_slug"]
        if exercise_id is None:
            # Off-plan entry: no planned_exercises row to take the slug from.
            canonical_slug = _adhoc_canonical_slug(cursor, exercise_key, now)

        if existing_ex is None:
            cursor.execute("""
                INSERT INTO exercise_logs
                (session_log_id, exercise_id, exercise_key, user_note,
                 duration_min, avg_hr, max_hr, canonical_slug, last_modified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                session_log_id, exercise_id, exercise_key,
                exercise_data.get("user_note"), exercise_data.get("duration_min"),
                exercise_data.get("avg_hr"), exercise_data.get("max_hr"),
                canonical_slug, now,
            ))
            exercise_log_id = cursor.lastrowid
        else:
            exercise_log_id = existing_ex["id"]
            cursor.execute("""
                UPDATE exercise_logs
                SET exercise_id = ?, user_note = ?, duration_min = ?, avg_hr = ?,
                    max_hr = ?, canonical_slug = ?, last_modified = ?
                WHERE id = ?
            """, (
                exercise_id, exercise_data.get("user_note"),
                exercise_data.get("duration_min"), exercise_data.get("avg_hr"),
                exercise_data.get("max_hr"), canonical_slug, now, exercise_log_id,
            ))
            # The client's set/checklist list is authoritative for an exercise it
            # sent → replace them.
            cursor.execute("DELETE FROM set_logs WHERE exercise_log_id = ?", (exercise_log_id,))
            cursor.execute("DELETE FROM checklist_log_items WHERE exercise_log_id = ?", (exercise_log_id,))

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

    return _assemble_log_for_date(cursor, date_str)


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
def _workout_status(get_db):
    """Get the last server sync time."""
    with get_db() as conn:
        cursor = conn.cursor()
        cursor.execute("SELECT value FROM meta_sync WHERE key = 'last_server_sync_time'")
        row = cursor.fetchone()

        if row:
            return StatusResponse(lastModified=row["value"])
        return StatusResponse(lastModified=None)


def _plans_version(get_db):
    """Return the latest coach change timestamp (cheap poll version check).

    Folds in everything the 30s poll should notice:
    - plan edits (workout_sessions.last_modified),
    - plan deletions (deleted_plans.deleted_at — the delete removes the session
      row, so without this arm deleting a non-latest plan never moved MAX),
    - LOG writes (workout_session_logs.last_modified — without this arm another
      device's logged sets reached a continuously-visible client only on a
      refocus/online event; mid-workout phone+tablet is exactly that case).
    - log-entry deletions (deleted_exercise_logs.deleted_at — a hard DELETE
      leaves no child stamp, and the day-level stamp only moves when the
      feedback record is accepted, so without this arm a delete whose feedback
      base was stale never moved MAX).
    Accepted cost: any device logging a set triggers the other devices' next
    poll to run a full sync.
    """
    with get_db() as conn:
        cursor = conn.cursor()
        cursor.execute("""
            SELECT MAX(v) as v FROM (
                SELECT MAX(last_modified) as v FROM workout_sessions
                UNION ALL
                SELECT MAX(deleted_at) as v FROM deleted_plans
                UNION ALL
                SELECT MAX(last_modified) as v FROM workout_session_logs
                UNION ALL
                SELECT MAX(deleted_at) as v FROM deleted_exercise_logs
            )
        """)
        row = cursor.fetchone()
        return PlansVersionResponse(version=row["v"] if row else None)


def _register_client(get_db, client_id, client_name=None):
    """Register or update a client."""
    with get_db() as conn:
        _db_register_client(conn, client_id, client_name)
        conn.commit()
        return {"status": "ok", "clientId": client_id}


def _workout_sync_get(get_db, response, client_id, last_sync_time=None):
    """Fetch workout plans and logs."""
    response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
    response.headers["Pragma"] = "no-cache"

    with get_db() as conn:
        cursor = conn.cursor()

        now = get_utc_now()
        # Watermark returned to the client as its next `since`. Like journal's
        # delta, it is stamped before the reads and offset into the past by a
        # small overlap, so a write that committed during this pull (timestamp
        # just below `now`) is re-delivered on the next pull rather than skipped
        # forever by `last_modified > since`. Re-delivery is safe: plans are
        # server-managed (idempotent overwrite) and logs merge under the
        # per-record dirty protection. `now` itself still stamps last_seen_at.
        server_time = sync_watermark()
        cursor.execute("""
            UPDATE clients SET last_seen_at = ? WHERE id = ?
        """, (now, client_id))

        # Sync-window boundary as a date-only string (YYYY-MM-DD). It is compared
        # against the `date` columns below, which hold the client's *local*
        # calendar dates (convention: instants are UTC `Z`, calendar dates are
        # local to match the browser's new Date()). Deliberately date-only — do
        # not reformat. Also reused by the tombstone prune below (see note there).
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
                SELECT * FROM workout_session_logs l
                WHERE l.last_modified > ?
                   OR EXISTS (
                        SELECT 1 FROM exercise_logs e
                        WHERE e.session_log_id = l.id
                          AND e.last_modified > ?
                   )
                   OR EXISTS (
                        SELECT 1 FROM deleted_exercise_logs d
                        WHERE d.date = l.date
                          AND d.deleted_at > ?
                   )
                ORDER BY l.date
            """, (last_sync_time, last_sync_time, last_sync_time))
            # The first EXISTS arm: the day-level stamp bumps only when the
            # FEEDBACK record is accepted (it is that record's concurrency
            # token, so it must not move on exercise-only writes). Without it,
            # a day where only exercise records were accepted — the R3
            # multi-device merge — kept its old stamp and was never delivered
            # to the other device's incremental pull. Propagation reads child
            # stamps; arbitration semantics are untouched.
            # The second EXISTS arm: a deleted exercise entry leaves no child
            # row to stamp, so a fresh tombstone re-delivers the (kept) day —
            # the other client adopts the server day, which simply lacks the
            # deleted key.
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

        # Prune tombstones older than the sync window. `cutoff` is date-only
        # (YYYY-MM-DD) while `deleted_at` is a full Z timestamp; comparing them
        # lexically prunes tombstones whose *date* precedes the cutoff and keeps
        # the whole cutoff day. This errs safe (keeps tombstones a bit longer →
        # deletion propagation still works). Do NOT "fix" this to
        # `cutoff + "T00:00:00Z"`: deleted_at carries sub-second precision, so a
        # fraction-less cutoff would prune the first instant after midnight early.
        cursor.execute("DELETE FROM deleted_plans WHERE deleted_at < ?", (cutoff,))
        cursor.execute("DELETE FROM deleted_exercise_logs WHERE deleted_at < ?", (cutoff,))

        conn.commit()
        return WorkoutSyncResponse(
            plans=plans, logs=logs, serverTime=server_time,
            earliestDate=cutoff, deletedPlanDates=deleted_plan_dates
        )


def _workout_sync_post(get_db, payload):
    """Upload workout logs from client (per-record upsert; R3).

    Each date is reconciled at exercise granularity (see _store_log) and the
    merged server day is returned in `results[date]` for the client to adopt —
    there is no whole-upload reject. `results` carries each exercise's
    `_lastModified` token so the client advances/recovers per exercise.
    """
    with get_db() as conn:
        now = get_utc_now()
        client_id = payload.clientId
        results = {}

        # BEGIN IMMEDIATE: each _store_log reads stored rows, arbitrates per
        # record, then writes. Acquiring the write lock up front makes that
        # check-then-write atomic against the coach MCP server, which writes
        # plans to the same coach.db from a separate process.
        with immediate_transaction(conn) as cursor:
            _db_register_client(conn, client_id, now=now)

            for date_str, log_data in payload.logs.items():
                results[date_str] = _store_log(conn, date_str, log_data, client_id, now)

            cursor.execute("""
                INSERT OR REPLACE INTO meta_sync (key, value)
                VALUES ('last_server_sync_time', ?)
            """, (now,))

            _purge_old_archives(cursor)

        return {"success": True, "results": results, "serverTime": now}


# Hook scripts shell out to external services (e.g. Garmin); a hung script must
# not leave the hook row at exit_code NULL forever (the UI reads NULL as
# still-running). Distinct exit codes: -1 = failed to run, -2 = timed out.
HOOK_TIMEOUT_SECONDS = 120


def _store_hook_result(get_db, result_id: int, exit_code, stdout: bytes):
    """Persist a hook's exit code (+ parsed JSON key/values on success).

    Blocking sqlite3 work — callers on the event loop go through
    asyncio.to_thread."""
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


async def _run_hook(get_db, result_id: int, script_path: Path):
    """Run a hook script asynchronously and store results in the database."""
    try:
        # start_new_session: the script runs in its own process group, so the
        # timeout kill below takes out the whole tree (a bare proc.kill() would
        # kill only the shell — a child like curl/sleep inherits the stdout
        # pipe and communicate() would block until it exits anyway).
        proc = await asyncio.create_subprocess_exec(
            str(script_path),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
        )
        try:
            stdout, stderr = await asyncio.wait_for(
                proc.communicate(), timeout=HOOK_TIMEOUT_SECONDS
            )
            exit_code = proc.returncode
        except asyncio.TimeoutError:
            try:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
            await proc.wait()
            logger.error("Hook %d timed out after %ds: %s",
                         result_id, HOOK_TIMEOUT_SECONDS, script_path)
            stdout = b""
            exit_code = -2

        await asyncio.to_thread(_store_hook_result, get_db, result_id, exit_code, stdout)

    except FileNotFoundError:
        logger.error("Hook script not found: %s", script_path)
        await asyncio.to_thread(_store_hook_result, get_db, result_id, -1, b"")
    except Exception:
        logger.exception("Hook %d failed unexpectedly", result_id)
        await asyncio.to_thread(_store_hook_result, get_db, result_id, -1, b"")


async def _start_or_end_workout(get_db, session_id: int, hook_type: str, action_label: str):
    """Shared logic for start/end workout endpoints."""
    script_path = get_hook_path(hook_type)
    if not script_path or not script_path.exists():
        raise HTTPException(status_code=400, detail=f"No {action_label} action configured")

    def _record_hook_fired():
        """Blocking sqlite3 work — run via to_thread (this endpoint is async)."""
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
            return result_id

    result_id = await asyncio.to_thread(_record_hook_fired)

    spawn(_run_hook(get_db, result_id, script_path))

    return WorkoutActionResponse(status=action_label, result_id=result_id)


def _undo_workout_action(get_db, session_id: int, hook_type: str):
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


def _get_workout_status(get_db, session_id: int):
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
    """Factory: build an injected DB accessor, initialize tables, and return a
    fresh router whose handlers capture the accessor (R2 — no module-global DB
    path, so two routers can target different DBs in one process). Foreign keys
    are enabled for coach's relational schema."""
    accessor = DbAccessor(db_path, foreign_keys=True)
    init_database(accessor)
    get_db = accessor.get_db
    router = APIRouter()

    @router.get("/status", response_model=StatusResponse)
    def workout_status():
        return _workout_status(get_db)

    @router.get("/plans-version", response_model=PlansVersionResponse)
    def plans_version():
        return _plans_version(get_db)

    @router.post("/register")
    def register_client(client_id: str, client_name: Optional[str] = None):
        return _register_client(get_db, client_id, client_name)

    @router.get("/sync", response_model=WorkoutSyncResponse)
    def workout_sync_get(
        response: Response,
        client_id: str = Query(...),
        last_sync_time: Optional[str] = Query(None),
    ):
        return _workout_sync_get(get_db, response, client_id, last_sync_time)

    @router.post("/sync")
    def workout_sync_post(payload: WorkoutSyncPayload):
        return _workout_sync_post(get_db, payload)

    @router.post("/workout/{session_id}/start", response_model=WorkoutActionResponse)
    async def start_workout(session_id: int):
        """Notify the server that a workout is starting."""
        return await _start_or_end_workout(get_db, session_id, "pre", "started")

    @router.post("/workout/{session_id}/end", response_model=WorkoutActionResponse)
    async def end_workout(session_id: int):
        """Notify the server that a workout has ended."""
        return await _start_or_end_workout(get_db, session_id, "post", "ended")

    @router.delete("/workout/{session_id}/start")
    def undo_start_workout(session_id: int):
        """Undo a workout start notification."""
        return _undo_workout_action(get_db, session_id, "pre")

    @router.delete("/workout/{session_id}/end")
    def undo_end_workout(session_id: int):
        """Undo a workout end notification."""
        return _undo_workout_action(get_db, session_id, "post")

    @router.get("/workout/config", response_model=WorkoutConfigResponse)
    def get_workout_config():
        """Get available workout actions."""
        return WorkoutConfigResponse(
            start=_is_hook_available("pre"),
            end=_is_hook_available("post"),
        )

    @router.get("/workout/{session_id}/status", response_model=WorkoutStatusResponse)
    def get_workout_status(session_id: int):
        return _get_workout_status(get_db, session_id)

    return router
