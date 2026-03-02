import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone


def get_utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


@contextmanager
def get_db(db_path: str):
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        yield conn
    finally:
        conn.close()


def init_database(db_path: str):
    with get_db(db_path) as conn:
        conn.execute("""CREATE TABLE IF NOT EXISTS reports (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            query_id TEXT NOT NULL, query_label TEXT NOT NULL,
            prompt_sent TEXT NOT NULL, response_markdown TEXT,
            status TEXT NOT NULL DEFAULT 'pending',
            created_at TEXT NOT NULL, completed_at TEXT, error_message TEXT,
            cli_metadata TEXT)""")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_reports_status ON reports(status)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_reports_created ON reports(created_at)")
        conn.commit()


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
