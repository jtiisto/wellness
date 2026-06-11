from datetime import datetime, timedelta, timezone

from modules.db import get_db, get_utc_now, run_migrations, enable_wal, immediate_transaction


def _migration_1_baseline(cursor):
    """Baseline analysis schema: the reports table and its indexes.

    Idempotent (CREATE IF NOT EXISTS), so an existing unversioned production DB
    adopts cleanly — the migration is a no-op that just stamps the version.
    """
    cursor.execute("""CREATE TABLE IF NOT EXISTS reports (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        query_id TEXT NOT NULL, query_label TEXT NOT NULL,
        prompt_sent TEXT NOT NULL, response_markdown TEXT,
        status TEXT NOT NULL DEFAULT 'pending',
        created_at TEXT NOT NULL, completed_at TEXT, error_message TEXT,
        cli_metadata TEXT)""")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_reports_status ON reports(status)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_reports_created ON reports(created_at)")


# Ordered (target_version, fn) pairs — see db.run_migrations for the contract.
MIGRATIONS = [
    (1, _migration_1_baseline),
]


def init_database(db_path: str):
    """Initialize the analysis database via the shared migration registry.

    Enables WAL once (outside any transaction) then applies pending migrations
    transactionally (see db.run_migrations).
    """
    with get_db(db_path) as conn:
        enable_wal(conn)
        run_migrations(conn, MIGRATIONS, label="analysis DB")


def create_report(db_path, query_id, query_label, prompt_sent) -> int:
    with get_db(db_path) as conn:
        cur = conn.execute(
            "INSERT INTO reports (query_id, query_label, prompt_sent, status, created_at) VALUES (?,?,?,'pending',?)",
            (query_id, query_label, prompt_sent, get_utc_now()))
        conn.commit()
        return cur.lastrowid


def update_report_running(db_path, report_id):
    with get_db(db_path) as conn:
        conn.execute("UPDATE reports SET status='running' WHERE id=?", (report_id,))
        conn.commit()


def update_report_completed(db_path, report_id, response_markdown, cli_metadata=None):
    with get_db(db_path) as conn:
        conn.execute("UPDATE reports SET status='completed', response_markdown=?, completed_at=?, cli_metadata=? WHERE id=?",
                     (response_markdown, get_utc_now(), cli_metadata, report_id))
        conn.commit()


def update_report_failed(db_path, report_id, error_message):
    with get_db(db_path) as conn:
        conn.execute("UPDATE reports SET status='failed', error_message=?, completed_at=? WHERE id=?",
                     (error_message, get_utc_now(), report_id))
        conn.commit()


def get_report(db_path, report_id) -> dict | None:
    with get_db(db_path) as conn:
        row = conn.execute("SELECT * FROM reports WHERE id=?", (report_id,)).fetchone()
        return dict(row) if row else None


def list_reports(db_path, limit=50) -> list[dict]:
    with get_db(db_path) as conn:
        return [dict(r) for r in conn.execute(
            "SELECT * FROM reports ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()]


def get_pending_reports(db_path) -> list[dict]:
    with get_db(db_path) as conn:
        return [dict(r) for r in conn.execute(
            "SELECT * FROM reports WHERE status IN ('pending','running') ORDER BY created_at DESC").fetchall()]


def delete_report(db_path, report_id) -> bool:
    with get_db(db_path) as conn:
        cur = conn.execute("DELETE FROM reports WHERE id=?", (report_id,))
        conn.commit()
        return cur.rowcount > 0


def has_active_report(db_path) -> bool:
    with get_db(db_path) as conn:
        row = conn.execute("SELECT COUNT(*) as cnt FROM reports WHERE status IN ('pending','running')").fetchone()
        return row["cnt"] > 0


def create_report_if_idle(db_path, query_id, query_label, prompt_sent,
                          stale_after_seconds) -> int | None:
    """Atomically create a report iff no live one exists. Returns the new id,
    or None when a report is genuinely active (caller answers 409).

    Replaces the racy has_active_report() + create_report() two-step: a
    double-tap could pass the check in both requests before either inserted,
    launching two CLI subprocesses. One BEGIN IMMEDIATE transaction makes the
    check-and-insert atomic.

    Also the age-gated runtime reaper: a pending/running report older than
    ``stale_after_seconds`` can only be a corpse (every live path ends in a
    terminal write bounded by the query timeout — this catches the residual
    case where that terminal write itself failed), so it is marked failed here
    rather than wedging the 409 guard until the next server restart. The age
    gate is what makes the sweep safe: callers pass max-query-timeout + grace,
    so a legitimately long-running report is never reaped.
    """
    cutoff = (
        datetime.now(timezone.utc) - timedelta(seconds=stale_after_seconds)
    ).isoformat().replace("+00:00", "Z")
    with get_db(db_path) as conn:
        with immediate_transaction(conn) as cur:
            cur.execute(
                "UPDATE reports SET status='failed', "
                "error_message='Reaped: stuck in a non-terminal status past the query timeout', "
                "completed_at=? "
                "WHERE status IN ('pending','running') AND created_at < ?",
                (get_utc_now(), cutoff),
            )
            cur.execute(
                "INSERT INTO reports (query_id, query_label, prompt_sent, status, created_at) "
                "SELECT ?,?,?,'pending',? "
                "WHERE NOT EXISTS (SELECT 1 FROM reports WHERE status IN ('pending','running'))",
                (query_id, query_label, prompt_sent, get_utc_now()),
            )
            if cur.rowcount == 0:
                return None
            return cur.lastrowid


def recover_stale_reports(db_path):
    """Mark RUNNING or PENDING reports as FAILED on startup.

    Called once at startup (create_router), before the server accepts requests,
    so any report still 'running' or 'pending' is necessarily orphaned by the
    restart — no async task survives it. Leaving a 'pending' row would wedge
    has_active_report() and block every new query with a 409.
    """
    with get_db(db_path) as conn:
        conn.execute(
            "UPDATE reports SET status='failed', error_message='Server restarted during execution', completed_at=? "
            "WHERE status IN ('running', 'pending')",
            (get_utc_now(),)
        )
        conn.commit()
