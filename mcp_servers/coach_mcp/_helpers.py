"""Shared helpers for the Coach MCP server.

- `LEGACY_PAIR_SUFFIX_RE` / `_reject_legacy_pair_suffix` (re-exported — the
  canonical implementation moved to `modules.coach_plans` so plan validation is
  a domain rule every transport inherits)
- `_assemble_plan_from_db` / `_assemble_log_from_db` (shared canonical readers)
- `_get_coach_plan_guide` (reads the markdown from `coach_plan_guide.md`)
"""

from pathlib import Path

# Shared coach domain logic (src/ is placed on the path by coach_mcp/__init__).
from modules.coach_plans import (
    assemble_plan,
    LEGACY_PAIR_SUFFIX_RE,  # noqa: F401 — historical import surface
    reject_legacy_pair_suffix as _reject_legacy_pair_suffix,  # noqa: F401
)
from modules.coach_logs import assemble_log


# ==================== Plan Storage Helpers ====================


def _assemble_plan_from_db(cursor, session_id):
    """Fetch the session row and assemble its plan via the shared canonical
    reader (`coach_plans.assemble_plan`), which both transports delegate to
    (plans/ phase 3). Returns None if the session does not exist.
    """
    cursor.execute("SELECT * FROM workout_sessions WHERE id = ?", [session_id])
    session = cursor.fetchone()
    if not session:
        return None
    return assemble_plan(cursor, session)


# ==================== Log Assembly Helper ====================


def _assemble_log_from_db(cursor, session_log_id, session_id=None):
    """Fetch the session-log row and assemble it via the shared canonical reader
    in its RICH mode (hook workout_stats + per-exercise completion +
    session_completion rollup for LLM analysis). Returns {} if the log is
    absent. See coach_logs.assemble_log (plans/ phase 3).
    """
    cursor.execute("SELECT * FROM workout_session_logs WHERE id = ?", [session_log_id])
    log_row = cursor.fetchone()
    if not log_row:
        return {}
    return assemble_log(cursor, log_row, session_id=session_id, derive_completion=True)


def _get_coach_plan_guide() -> str:
    """Get comprehensive guide for creating workout plans."""
    return (Path(__file__).parent / "coach_plan_guide.md").read_text(encoding="utf-8")
