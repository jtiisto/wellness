"""Read captured sessions out of the hr module's database.

The one place in this package that knows SQL. Everything downstream works on
`Beat` lists, so the schema is contained here.

RETRIEVAL IS BY SESSION ID, ALWAYS (2026-08-22 ruling). There is no
wall-clock-window read surface here, and none above this layer: `load_beats`
takes a session id, `list_sessions` takes a limit, and the only windowing left
in the package is the *derived* one — the guided timeline's own
anchor..anchor+total span, computed from rows this file returns, never supplied
by a caller. The reason is the system's two-case model: either wellness owns a
ride end to end (strap capture and guide together, everything keyed by one
session id) or the ride lives entirely on the Garmin side and wellness records
only the completion checkbox. Watch-side activity times therefore participate in
nothing — never an input, never a cross-check — and a caller who could pass one
would be inviting exactly the correlation this model does not do.

The cost, accepted deliberately: beats captured before session ids existed stay
stored and become unreachable through this package. There is little of that
data, and an access path for it would be the very time-window surface the ruling
retired.

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

    Newest first and capped — this is the *browse* surface, the one place a
    caller who has no session id starts from. It takes no time window, by the
    same ruling that made retrieval session-id-driven: see the module docstring.

    `session_id IS NOT NULL` is the other half of that ruling made real. The
    column is NOT NULL in today's schema, but rows predating it are still stored,
    and `GROUP BY` would collect every one of them into a single phantom session
    whose id is None — a row naming a session no other tool can be given
    (`WHERE session_id = NULL` matches nothing) and which the CLI's `%38s`
    formatter cannot even print. Those beats are meant to be unreachable, not
    unreachable-but-listed.

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
        WHERE i.session_id IS NOT NULL
        GROUP BY i.session_id
        ORDER BY MIN(i.timestamp_ms) DESC
        LIMIT ?
        """,
        [limit],
        db_path,
    )


def session_devices(session_id, db_path=None):
    """Distinct (device_id, sensor_type) pairs that contributed beats to a session.

    Provenance for a report header. Read off `intervals` for the same reason the
    listing is: it is the table that actually holds the capture, so this answers
    "what produced these beats" even when no `sessions` row was ever uploaded.
    """
    return _query(
        """
        SELECT DISTINCT device_id, sensor_type
        FROM intervals
        WHERE session_id = ?
        ORDER BY device_id, sensor_type
        """,
        (session_id,),
        db_path,
    )


def load_beats(session_id, db_path=None):
    """Ordered Beat list for one session.

    ORDER BY (timestamp_ms, seq) is the whole ordering story. The old capture
    stack made timestamps unique by bumping any collision forward a millisecond,
    which quietly corrupted the very inter-beat deltas this analysis measures;
    `seq` replaces that hack, so beats sharing a receipt millisecond keep their
    true capture order without their timestamps being edited. Nothing re-sorts
    in Python afterwards — SQL is the only ordering authority.

    The session id is the ONLY selector. The wall-clock range this used to also
    accept is retired: see the module docstring.
    """
    rows = _query(
        """
        SELECT timestamp_ms, rr_interval_ms, heart_rate_bpm, is_gap_before
        FROM intervals
        WHERE session_id = ?
        ORDER BY timestamp_ms, seq
        """,
        [session_id],
        db_path,
    )
    return [
        Beat(ts_ms=r[0], rr_ms=r[1], hr_bpm=r[2], is_gap=bool(r[3]))
        for r in rows
    ]


def load_set_events(session_id, db_path=None):
    """Ordered set-completion markers for one session.

    These are the exercise ground truth next to the HR curve: each row is a
    set tick (`set_num`) or a checklist toggle (`item_key`) the client stamped
    during capture, with `action` 'check' or 'uncheck' (an undo is a real
    event, not an erasure). Ordered by client stamp — the capture-side clock
    the offsets in a report are computed against.
    """
    rows = _query(
        """
        SELECT client_timestamp_ms, exercise_key, set_num, item_key, action
        FROM set_events
        WHERE session_id = ?
        ORDER BY client_timestamp_ms, event_id
        """,
        [session_id],
        db_path,
    )
    return [
        {
            "client_timestamp_ms": r[0],
            "exercise_key": r[1],
            "set_num": r[2],
            "item_key": r[3],
            "action": r[4],
        }
        for r in rows
    ]


_GUIDE_EVENT_COLUMNS = (
    "client_timestamp_ms, exercise_key, action, extension_sec, "
    "timeline_json, date, event_id"
)


def _guide_event(row):
    return {
        "client_timestamp_ms": row[0],
        "exercise_key": row[1],
        "action": row[2],
        "extension_sec": row[3],
        "timeline_json": row[4],
        "date": row[5],
        "event_id": row[6],
    }


def load_guide_events(session_id, db_path=None):
    """Ordered cardio-guide actions for one session.

    The recorded structure of a guided ride: a `start` carries the timeline as
    the guide drew it (`timeline_json`, the coach wire's segment shape), an
    `extend` carries its own `extension_sec`, and every boundary is derived from
    the two. Nothing is interpreted here — `guided.py` owns the rules, including
    which `start` wins when a rider rode twice.

    Ordered by (client_timestamp_ms, event_id): the client stamp is what the
    derivation measures against, and the id breaks a tie deterministically so
    "the latest start" cannot depend on SQLite's row order.
    """
    rows = _query(
        f"""
        SELECT {_GUIDE_EVENT_COLUMNS}
        FROM guide_events
        WHERE session_id = ?
        ORDER BY client_timestamp_ms, event_id
        """,
        [session_id],
        db_path,
    )
    return [_guide_event(r) for r in rows]


def guide_events_by_session(session_ids, db_path=None):
    """`load_guide_events` for many sessions at once, keyed by session id.

    One query rather than one per session: the listing tool decorates every row
    it returns with that session's guided-ness, and a per-row query would make a
    20-session listing 21 round trips through the read-only accessor. Sessions
    with no guide events are simply absent from the mapping — the caller's
    `.get(sid, [])` is the "this was not a guided ride" answer.
    """
    ids = list(session_ids)
    if not ids:
        return {}
    placeholders = ",".join("?" * len(ids))
    rows = _query(
        f"""
        SELECT session_id, {_GUIDE_EVENT_COLUMNS}
        FROM guide_events
        WHERE session_id IN ({placeholders})
        ORDER BY client_timestamp_ms, event_id
        """,
        ids,
        db_path,
    )
    by_session = {}
    for row in rows:
        # `sqlite3.Row` indexes but does not slice — tuple() first.
        by_session.setdefault(row[0], []).append(_guide_event(tuple(row)[1:]))
    return by_session


def latest_session(db_path=None):
    """Most recently started session id, or None when nothing is captured."""
    sessions = list_sessions(limit=1, db_path=db_path)
    return sessions[0][0] if sessions else None
