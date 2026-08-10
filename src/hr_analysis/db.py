"""Read captured sessions out of the hr module's database.

The one place in this package that knows SQL. Everything downstream works on
`Beat` lists, so the schema is contained here.

READ-ONLY, always: the analysis is an observer of a database the `hr` module
owns and writes. The connection therefore comes from the repo's shared
`DbAccessor(..., read_only=True)` (sqlite `mode=ro`), which both refuses writes
and — the property that matters most for a CLI — refuses to *create* a missing
file. A plain `sqlite3.connect` would silently conjure an empty `hr.db`, which
on a fresh install is exactly the wrong answer.

Path resolution goes through `config.get_module_db_path("hr")` — the same
`HR_DB_PATH` env var > `data/hr.db` default the server itself resolves — so a
test harness or a second environment repoints both sides at once. It is
resolved per call, never at import, so nothing here touches `data/` merely by
being imported.
"""

import sqlite3
from pathlib import Path

from config import get_module_db_path

from modules.db import DbAccessor

from .quality import Beat


class HrDataUnavailable(Exception):
    """The HR database is absent, unreadable, or carries no HR schema.

    Distinct from "the database is fine and simply has no sessions yet" (an
    empty result, not an error): history starts empty by design, and only the
    CLI's caller can tell those two apart from the message.
    """


def _resolve(db_path=None):
    return Path(db_path) if db_path is not None else Path(get_module_db_path("hr"))


def _explain(path, exc):
    """Turn a sqlite OperationalError into something a human can act on."""
    if not path.exists():
        return (f"No HR database at {path}. Nothing has been captured yet — the "
                f"file appears once the server accepts its first HR batch. "
                f"Set HR_DB_PATH to analyse a database elsewhere.")
    if "no such table" in str(exc):
        return (f"The database at {path} has no HR tables. It is not an hr.db "
                f"(or the server has never initialised it).")
    return f"Could not read the HR database at {path}: {exc}"


def _query(sql, params=(), db_path=None):
    """Run one read-only SELECT, mapping DB-level failures to HrDataUnavailable."""
    path = _resolve(db_path)
    try:
        with DbAccessor(path, read_only=True).get_db() as conn:
            return conn.execute(sql, params).fetchall()
    except sqlite3.OperationalError as exc:
        raise HrDataUnavailable(_explain(path, exc)) from exc


def list_sessions(limit=20, db_path=None):
    """Recent sessions as (session_id, device_id, start_ms, end_ms, beats, workout_date).

    Derived from `intervals`, not from the `sessions` table: the batches arrive
    independently, so beats can exist for a session whose row has not been
    uploaded (or whose upload failed) and a listing that hid those would hide
    analysable data. The LEFT JOIN only decorates the row with the coach-side
    `workout_date` when it happens to be there.

    Rows come back as `sqlite3.Row`, so the aggregates are aliased and a caller
    may read them either positionally or by name.
    """
    return _query(
        """
        SELECT i.session_id                AS session_id,
               i.device_id                 AS device_id,
               MIN(i.timestamp_ms)         AS start_ms,
               MAX(i.timestamp_ms)         AS end_ms,
               COUNT(*)                    AS beats,
               s.workout_date              AS workout_date
        FROM intervals i
        LEFT JOIN sessions s ON s.session_id = i.session_id
        GROUP BY i.session_id
        ORDER BY MIN(i.timestamp_ms) DESC
        LIMIT ?
        """,
        (limit,),
        db_path,
    )


def load_beats(session_id=None, start_ms=None, end_ms=None, db_path=None):
    """Ordered Beat list for a session id or an explicit time range.

    ORDER BY (timestamp_ms, seq) is the whole ordering story. The old capture
    stack made timestamps unique by bumping any collision forward a millisecond,
    which quietly corrupted the very inter-beat deltas this analysis measures;
    `seq` replaces that hack, so beats sharing a receipt millisecond keep their
    true capture order without their timestamps being edited. Nothing re-sorts
    in Python afterwards — SQL is the only ordering authority.
    """
    if session_id is not None:
        where, params = "session_id = ?", [session_id]
    elif start_ms is not None and end_ms is not None:
        where, params = "timestamp_ms BETWEEN ? AND ?", [start_ms, end_ms]
    else:
        raise ValueError("Provide session_id or (start_ms, end_ms)")

    rows = _query(
        f"""
        SELECT timestamp_ms, rr_interval_ms, heart_rate_bpm, is_gap_before
        FROM intervals
        WHERE {where}
        ORDER BY timestamp_ms, seq
        """,
        params,
        db_path,
    )
    return [
        Beat(ts_ms=r[0], rr_ms=r[1], hr_bpm=r[2], is_gap=bool(r[3]))
        for r in rows
    ]


def latest_session(db_path=None):
    """Most recently started session id, or None when nothing is captured."""
    sessions = list_sessions(limit=1, db_path=db_path)
    return sessions[0][0] if sessions else None
