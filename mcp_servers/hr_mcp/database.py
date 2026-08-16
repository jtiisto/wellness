"""Read-only database access for the HR MCP tools.

This module contains no SQL, on purpose. `hr_analysis.db` is the single place
that knows the `hr` schema, and it is what the `python -m hr_analysis` CLI reads
through too — so the MCP tools and the CLI can never drift into disagreeing
about what a session is. Everything here is a thin adapter: resolve the
configured database, hand it to `hr_analysis.db`, shape the rows.

Read-only is structural rather than promised. Every read goes through
`hr_analysis.db`, which opens the file with the shared `DbAccessor(...,
read_only=True)` — sqlite `mode=ro`, which refuses writes AND refuses to create
a missing file. There is no code path in this package that could conjure an
empty `hr.db` next to the real one.
"""

from dataclasses import dataclass
from typing import Optional

from hr_analysis import db as hr_db
from hr_analysis.db import HrDataUnavailable  # re-exported: tools raise it through
from hr_analysis.quality import Beat

from .config import MCPConfig

__all__ = ["Beat", "DatabaseManager", "HrDataUnavailable", "SessionSummary"]


@dataclass
class SessionSummary:
    """One captured session, as the listing tools report it."""

    session_id: str
    device_id: str
    start_ms: int
    end_ms: int
    beats: int
    workout_date: Optional[str] = None

    @property
    def duration_s(self) -> float:
        return round((self.end_ms - self.start_ms) / 1000.0, 1)


class DatabaseManager:
    """Narrow read-only query surface used by the MCP tools."""

    def __init__(self, config: MCPConfig):
        self.config = config

    def list_sessions(
        self,
        start_ms: Optional[int] = None,
        end_ms: Optional[int] = None,
        limit: Optional[int] = None,
    ) -> list[SessionSummary]:
        """Recent sessions, newest first, optionally limited to a time window."""
        if limit is not None and limit < 1:
            raise ValueError("limit must be at least 1")
        effective_limit = min(limit or self.config.max_rows, self.config.max_rows)
        rows = hr_db.list_sessions(
            limit=effective_limit,
            start_ms=start_ms,
            end_ms=end_ms,
            db_path=self.config.db_path,
        )
        return [
            SessionSummary(
                session_id=row["session_id"],
                device_id=row["device_id"],
                start_ms=int(row["start_ms"]),
                end_ms=int(row["end_ms"]),
                beats=int(row["beats"]),
                workout_date=row["workout_date"],
            )
            for row in rows
        ]

    def load_beats(self, session_id: str) -> list[Beat]:
        """Every beat of one session, ordered by (timestamp_ms, seq).

        SQL is the only ordering authority — nothing re-sorts these in Python.
        """
        return hr_db.load_beats(session_id=session_id, db_path=self.config.db_path)

    def load_set_events(self, session_id: str) -> list[dict]:
        """Ordered set-completion markers for one session (see hr_analysis.db)."""
        return hr_db.load_set_events(session_id, db_path=self.config.db_path)

    def session_devices(self, session_id: str) -> tuple[list[str], list[str]]:
        """(device_ids, sensor_types) that contributed beats to one session."""
        rows = hr_db.session_devices(session_id, db_path=self.config.db_path)
        return (
            sorted({row["device_id"] for row in rows if row["device_id"]}),
            sorted({row["sensor_type"] for row in rows if row["sensor_type"]}),
        )
