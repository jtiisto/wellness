"""
Garmin API Router — an on-demand trigger for the external garmy sync, plus the
device-clock timeline the rest of the server buckets Garmin data by.

A headless module: it mounts no PWA tab. It is mostly a *command* surface, not
a data one — the Garmin data itself keeps arriving in `~/.garmy/health.db`
(written by `bin/garmin-sync.sh`, from cron or from here) and keeps being read
by trends. What the endpoints do is let a client say "sync now" and ask "are
you done yet", which is what turns a Trends pull-to-refresh into fresh data
instead of a re-read of yesterday's.

It does own ONE small table, in a database of its own (`data/garmin.db`,
`GARMIN_MODULE_DB_PATH` — NOT garmy's `GARMIN_DB_PATH`, which this module only
ever reads): `client_zones`, the device-clock change-point timeline documented
in docs/ARCHITECTURE.md "Device clock". Every request from the Android client
carries the phone's zone id and UTC offset; a middleware in the app hands the
valid ones to `record_client_zone`, which appends a row only when the pair
differs from the in-process tail, so the steady state costs no I/O at all.
`load_zone_timeline` reads the points back for `modules.device_clock`, which
turns them into the "which zone was the watch in at instant t" function that
the strain estimator and the sleep ledger bucket by. The table lives HERE
rather than in trends because trends owns no database by design — and the zone
is Garmin-shaped data: it exists only to place Garmin's rows on the right day.

Wire contract (documented in docs/ARCHITECTURE.md "Garmin", its home):

- **snake_case**, matching trends — the consuming surface. (HR's camelCase is
  the documented exception, not the rule.)
- **Optional keys are omitted, never null** (root CLAUDE.md non-negotiable).
  The status response is built key-by-key for exactly that reason.
- `last_finished_at` and `last_synced_at` are **epoch-ms integers**, like every
  other instant the native client consumes.

`POST /sync` answers 200 with a status enum rather than analysis's 409 — a
deliberate deviation. A pull gesture treats "already running" as success-shaped:
the client attaches to the in-flight run and waits, and an error status would
make the UI apologize for doing the right thing.

Single-flight is per uvicorn process: closure state guarded by an `asyncio.Lock`
around the check-and-start. The cooldown that keeps a pull storm from hammering
garmy runs off the last **attempt** (monotonic clock), with a durable backstop
that survives a restart by reading garmy's own `sync_status` table.
"""
import asyncio
import logging
import math
import os
import signal
import sqlite3
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter

from config import get_garmin_db_path, get_garmin_sync_cmd
from modules.background import spawn
from modules.db import DbAccessor, enable_wal, get_db, run_migrations

logger = logging.getLogger(__name__)

# A garmy top-up (2 days, 2 resync) takes ~15s on a healthy connection; 120s is
# generous enough for a slow Garmin API without letting a wedged process hold
# the single-flight flag for the rest of the day.
SYNC_TIMEOUT_SECONDS = 120

# Pulls landing inside this window skip the Garmin phase. Measured from the last
# ATTEMPT, not the last success: a failing script must not become a retry loop
# driven by a user's thumb.
COOLDOWN_SECONDS = 600

# --last-days 2 --resync-days 2: an on-demand top-up, not the cron's 7/3 sweep.
SYNC_ARGS = ("2", "2")


# ==================== Device-clock timeline (client_zones) ====================

# ±14 h covers every real UTC offset (Kiritimati +14, Baker Island −12), and a
# quarter-hour step covers every one that exists (Kathmandu +05:45, Chatham
# +12:45). Anything else is a client bug or a forged header — ignored, never a
# 4xx: a request must not fail over a field it does not depend on.
MAX_OFFSET_MIN = 840
OFFSET_STEP_MIN = 15


def _migration_1_client_zones(cursor):
    """Baseline: the device-clock CHANGE-POINT timeline.

    One row per observed change, not per request — see `record_client_zone`.
    `observed_at` is a UTC 'YYYY-MM-DDTHH:MM:SS' string, compared lexically
    like every other server stamp (root CLAUDE.md), and indexed because it is
    the only ordering the readers ever ask for. `zone_id` is stored exactly as
    the phone spelled it, and `parse_client_clock` has already resolved it, so
    a row written by this server always carries an id `zoneinfo` knew at the
    time. `offset_min` is stored anyway: it is the reader's fallback for a row
    this server did not write (a hand-edited or corrupt one) and for the day a
    tz-database update retires an id the row still names.

    A plain CREATE, not CREATE IF NOT EXISTS: this database is greenfield (the
    module owned no storage before), so there is no unversioned predecessor to
    adopt. Same reasoning as the hr module's baseline.
    """
    cursor.execute("""
        CREATE TABLE client_zones (
            id          INTEGER PRIMARY KEY,
            observed_at TEXT    NOT NULL,
            zone_id     TEXT    NOT NULL,
            offset_min  INTEGER NOT NULL
        )
    """)
    cursor.execute(
        "CREATE INDEX idx_client_zones_observed_at "
        "ON client_zones(observed_at)")


# Ordered (target_version, migration_fn) pairs — see db.run_migrations for the
# transactional contract. Migration fns are DDL-only and must not manage their
# own transactions.
MIGRATIONS = [
    (1, _migration_1_client_zones),
]

# Last (zone_id, offset_min) written or read per database path — the tail the
# change-point rule compares against, so a steady phone costs one dict lookup
# per request instead of a write. Keyed by path for the same reason the strain
# memo is: two databases in one process (the test suite) must not see each
# other's tail. Touched only under the lock, `_trimp_lock`'s idiom.
_zone_tail = {}
_zone_tail_lock = threading.Lock()
_UNSET = object()


def init_database(accessor):
    """Initialize the garmin module database via the shared migration registry.

    Enables WAL once (outside any transaction) then applies pending migrations
    transactionally. See db.run_migrations for the BEGIN IMMEDIATE / in-lock
    re-check contract.
    """
    with accessor.get_db() as conn:
        enable_wal(conn)
        run_migrations(conn, MIGRATIONS, label="Garmin module DB")


def _observed_at(now_utc: datetime) -> str:
    """Stamp a change point: UTC, ISO, second resolution. A naive input is
    read as UTC (the repo's rule for naive instants)."""
    if now_utc.tzinfo is not None:
        now_utc = now_utc.astimezone(timezone.utc)
    return now_utc.strftime("%Y-%m-%dT%H:%M:%S")


def parse_client_clock(zone_id, offset_min):
    """A reported clock as a `ZoneInfo`, or None when the PAIR is not one we
    accept. The single authority on that question.

    Both the app's clock middleware and `record_client_zone` go through here,
    which is the point: a pair good enough to move a handler's calendar date
    must be exactly the pair good enough to store. Split validation would let
    `Pacific/Kiritimati` with a nonsense offset shift the sleep ledger's
    `today` while writing no row to explain it.

    Three independent checks, all cheap: the id must be one `zoneinfo`
    resolves (ZoneInfo caches, so a repeat costs a dict lookup), the offset
    must be a real one, and it must fall on a quarter hour. A pair failing any
    of them is dropped silently — the header is advisory, and a bad one must
    never become an error the client has to handle.
    """
    if not isinstance(zone_id, str) or not zone_id:
        return None
    if isinstance(offset_min, bool) or not isinstance(offset_min, int):
        return None
    if not -MAX_OFFSET_MIN <= offset_min <= MAX_OFFSET_MIN:
        return None
    if offset_min % OFFSET_STEP_MIN != 0:
        return None
    try:
        return ZoneInfo(zone_id)
    except (ZoneInfoNotFoundError, ValueError, TypeError):
        return None


def _read_zone_tail(db_path):
    """The newest stored (zone_id, offset_min), or None when there is none.

    Read-only, so a missing file is an error rather than a newly created empty
    database — exactly what "no observations yet" should look like.
    """
    if not Path(db_path).exists():
        return None
    try:
        with get_db(db_path, read_only=True) as conn:
            row = conn.execute(
                "SELECT zone_id, offset_min FROM client_zones "
                "ORDER BY observed_at DESC, id DESC LIMIT 1").fetchone()
    except sqlite3.Error:
        return None
    return (str(row["zone_id"]), int(row["offset_min"])) if row else None


def record_client_zone(db_path, zone_id, offset_min, now_utc=None) -> bool:
    """Record a client clock, writing only when it CHANGED. Returns whether a
    row was written.

    BLOCKING sqlite3 work — the caller (the app's middleware) runs it through
    `asyncio.to_thread`, per modules/db.py's standing contract.

    The timeline is change points, not a request log: the tail is held in
    process, so the overwhelmingly common case (the same phone, same zone, all
    day) reads a dict and returns. The first call for a path loads the tail
    from the database so a restart does not re-record the zone it already has.

    This function NEVER raises. A header is advisory data on a request that
    is about something else entirely; a locked database or a schema that is
    not there must cost a log line, not the client's request.
    """
    if parse_client_clock(zone_id, offset_min) is None:
        return False
    now_utc = now_utc or datetime.now(timezone.utc)
    key = str(db_path)
    pair = (zone_id, offset_min)
    try:
        with _zone_tail_lock:
            tail = _zone_tail.get(key, _UNSET)
            if tail is _UNSET:
                tail = _read_zone_tail(db_path)
            if tail == pair:
                _zone_tail[key] = tail
                return False
            if not Path(db_path).exists():
                # The module owns this file's creation (create_router migrates
                # it). Writing here would make an empty, table-less database
                # that every later read has to survive.
                logger.warning(
                    "Client zone not recorded: %s does not exist", db_path)
                return False
            with get_db(db_path) as conn:
                conn.execute(
                    "INSERT INTO client_zones (observed_at, zone_id, offset_min) "
                    "VALUES (?, ?, ?)",
                    (_observed_at(now_utc), zone_id, offset_min))
                conn.commit()
            _zone_tail[key] = pair
            return True
    except sqlite3.Error:
        # The zone id never reaches the log: it is where the user physically
        # is, and a log line is a wider audience than the database row.
        logger.warning("Could not record the client clock", exc_info=True)
        return False
    except Exception:
        logger.exception("Unexpected failure recording the client clock")
        return False


def load_zone_timeline(db_path):
    """The recorded change points as [(observed_at_utc, zone_id, offset_min)],
    oldest first.

    Every "we cannot tell" case collapses to the empty list, which the reader
    reads as "no observations, use the server's zone" — the behaviour that
    predates the device clock. That covers a missing file (the module
    disabled, or a fresh clone), a database without the table, and a read
    error; and per row, an `observed_at` or `offset_min` that will not parse.
    """
    if db_path is None or not Path(db_path).exists():
        return []
    try:
        with get_db(db_path, read_only=True) as conn:
            rows = conn.execute(
                "SELECT observed_at, zone_id, offset_min FROM client_zones "
                "ORDER BY observed_at, id").fetchall()
    except sqlite3.Error:
        return []

    points = []
    for row in rows:
        try:
            observed = datetime.fromisoformat(str(row["observed_at"]))
            offset = int(row["offset_min"])
        except (TypeError, ValueError):
            continue
        if observed.tzinfo is None:
            observed = observed.replace(tzinfo=timezone.utc)
        points.append(
            (observed.astimezone(timezone.utc), str(row["zone_id"]), offset))
    points.sort(key=lambda p: p[0])
    return points


# ==================== Sync trigger ====================


def _now_mono() -> float:
    """Monotonic clock seam (tests advance it instead of sleeping).

    Monotonic, not wall-clock: the cooldown measures an elapsed interval, and a
    clock step (NTP, DST on a naive stack) must not be able to shorten or
    lengthen it.
    """
    return time.monotonic()


def _now_epoch_ms() -> int:
    """Wall-clock instant as epoch ms — what a client can actually render."""
    return int(time.time() * 1000)


def _parse_naive_utc(value) -> "int | None":
    """Parse a garmy `synced_at` stamp to epoch ms, or None if unparseable.

    garmy writes this column with `datetime.utcnow()` (garmy localdb/db.py),
    so the stored text is a **naive UTC** timestamp that SQLAlchemy renders as
    'YYYY-MM-DD HH:MM:SS.ffffff' — and as 'YYYY-MM-DD HH:MM:SS' when the
    microseconds happen to be zero. `fromisoformat` accepts both (and the space
    separator). Naive input is stamped UTC; anything that arrives aware is
    honored as-is rather than being re-interpreted.
    """
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return int(parsed.timestamp() * 1000)


def _read_last_synced_ms() -> "int | None":
    """Newest `sync_status.synced_at` from the garmy DB, as epoch ms.

    BLOCKING sqlite3 work — every caller here is an `async def`, so this only
    ever runs through `asyncio.to_thread` (modules/db.py's standing contract).

    Returns None for every "we cannot tell" case, which all mean the same thing
    to the caller (no durable evidence, fall back to the in-process clock):
    no DB file, no `sync_status` table, no non-NULL row, an unparseable stamp.
    The path is resolved per call so a test can repoint GARMIN_DB_PATH without
    rebuilding the app.

    MAX() over the raw text is lexical, which for this fixed-width format is
    also chronological — a fraction-less stamp sorts before a same-second
    fractional one, which is the correct order. (MAX ignores NULLs by
    definition, and garmy leaves synced_at NULL on pending/failed rows.)
    """
    path = get_garmin_db_path()
    # A read-only connect raises on a missing file rather than creating one, so
    # the guard is what keeps the common dev case (no garmy) off the error path.
    if not Path(path).exists():
        return None
    try:
        with get_db(path, read_only=True) as conn:
            row = conn.execute(
                "SELECT MAX(synced_at) AS newest FROM sync_status"
            ).fetchone()
    except sqlite3.OperationalError:
        # No sync_status table (a foreign or half-migrated DB), or unreadable.
        return None
    return _parse_naive_utc(row["newest"]) if row else None


async def _run_sync(state, lock, cmd: Path):
    """Background task: run the sync script, then record how it went.

    Exit-code-only contract — the script owns its own log trail (it appends to
    ~/.garmy/sync-cron.log), so the server wires stdio to DEVNULL and never
    reads a pipe. `start_new_session` puts the script in its own process group
    so the timeout kill takes out the whole tree; killing just the shell would
    leave the python child syncing on (coach._run_hook precedent).
    """
    outcome = "failed"
    try:
        proc = await asyncio.create_subprocess_exec(
            str(cmd), *SYNC_ARGS,
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
            start_new_session=True,
        )
        try:
            returncode = await asyncio.wait_for(
                proc.wait(), timeout=SYNC_TIMEOUT_SECONDS
            )
            outcome = "ok" if returncode == 0 else "failed"
            if returncode != 0:
                logger.warning("Garmin sync failed (exit %s)", returncode)
        except asyncio.TimeoutError:
            try:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
            await proc.wait()
            logger.error("Garmin sync timed out after %ds", SYNC_TIMEOUT_SECONDS)
        except asyncio.CancelledError:
            # Server shutdown (loop teardown cancels the spawned task) must
            # not orphan the process group: garmy would outlive the server and
            # overlap a restarted instance's runs. Same kill as the timeout
            # path, then let the cancellation propagate.
            try:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
            raise
    except (FileNotFoundError, PermissionError) as e:
        logger.error("Garmin sync script not runnable: %s", e)
    except Exception:
        logger.exception("Garmin sync failed unexpectedly")
    finally:
        # In `finally` so no failure path can wedge the module in `running`
        # forever — the lesson analysis learned about its own status mark.
        # Plain dict writes, deliberately NOT under the lock: a cancelled task
        # cannot await (the lock acquire would itself raise and SKIP this
        # reset), and single GIL-atomic assignments need no lock — the lock
        # exists for trigger_sync's compound check-and-start.
        state["running"] = False
        state["last_finished_ms"] = _now_epoch_ms()
        state["last_outcome"] = outcome


def create_router(db_path: Path) -> APIRouter:
    """Factory: migrate the module's own database, then build the router.

    `db_path` is the module's `client_zones` storage (GARMIN_MODULE_DB_PATH).
    Migrating it here — at app construction, like every other DB-owning module
    — is what lets `record_client_zone` refuse to create the file itself.

    Single-flight state lives in this closure — one dict per router, so two
    routers in one process (tests) cannot see each other's runs, and the app's
    single uvicorn process gives it exactly the scope it claims.

    The two EXTERNAL seams (the sync script path and garmy's health DB path)
    still resolve **per request** rather than here. Unlike trends — which
    builds five accessors used by dozens of handlers and logs a one-time
    absence — this module makes one occasional single-row read against them, so
    there is nothing to amortize, and one rule ("resolve at use") covers both.
    """
    init_database(DbAccessor(db_path))
    state = {
        "running": False,
        "last_attempt_mono": None,
        "last_finished_ms": None,
        "last_outcome": None,
    }
    lock = asyncio.Lock()
    router = APIRouter()

    async def _cooldown_remaining() -> float:
        """Seconds left before another sync may start; <= 0 means go.

        Two clocks, because neither alone is enough. The in-process monotonic
        clock is authoritative while the server lives but forgets across a
        restart; garmy's own sync_status is durable but only records successes
        (and cron's, which the closure never sees). Whichever says "wait
        longer" wins.
        """
        remaining = 0.0
        last_attempt = state["last_attempt_mono"]
        if last_attempt is not None:
            remaining = max(
                remaining, COOLDOWN_SECONDS - (_now_mono() - last_attempt))
        # Always consult BOTH clocks and report the larger remainder — an
        # early return on the in-process one would understate retry_in_sec
        # whenever cron synced more recently than our own last attempt
        # (review finding: the re-check would still block, but the number
        # handed to the client would be a lie).
        last_synced_ms = await asyncio.to_thread(_read_last_synced_ms)
        if last_synced_ms is not None:
            age_sec = (_now_epoch_ms() - last_synced_ms) / 1000
            # A stamp in the FUTURE proves nothing about elapsed time — it
            # proves a clock disagreement (a backwards step on the host, a
            # restored file, a garmy that wrote the wrong zone). Honoring it
            # would compute a remaining time LONGER than the window and wedge
            # the trigger for as long as the skew lasts, silently, with a
            # retry_in_sec that never expires. Ignoring it costs at most one
            # redundant garmy run, and the in-process clock still holds the
            # line against a pull storm.
            if age_sec >= 0:
                remaining = max(remaining, COOLDOWN_SECONDS - age_sec)
        return remaining

    @router.post("/sync")
    async def trigger_sync():
        """Start a Garmin sync unless one is running or the cooldown blocks it.

        Always 200; the status enum carries the outcome. See the module
        docstring for why "running" is not a 409.
        """
        cmd = get_garmin_sync_cmd()
        if cmd is None or not Path(cmd).exists():
            return {"status": "unconfigured"}

        async with lock:
            if state["running"]:
                return {"status": "running"}
            remaining = await _cooldown_remaining()
            if remaining > 0:
                # Never 0 while still blocked — a client that waits exactly
                # retry_in_sec must find the door open, not shut by rounding.
                return {"status": "cooldown",
                        "retry_in_sec": max(1, math.ceil(remaining))}
            state["running"] = True
            state["last_attempt_mono"] = _now_mono()
            spawn(_run_sync(state, lock, cmd))

        logger.info("Garmin sync started")
        return {"status": "started"}

    @router.get("/sync/status")
    async def sync_status():
        """Report whether a sync is in flight, plus what is known about the last
        one. Every optional key is omitted when absent — never null."""
        async with lock:
            running = state["running"]
            last_finished_ms = state["last_finished_ms"]
            last_outcome = state["last_outcome"]
        # Outside the lock: a POST's check-and-start should never queue behind
        # a status poll's disk read.
        last_synced_ms = await asyncio.to_thread(_read_last_synced_ms)

        body = {"running": running}
        if last_finished_ms is not None:
            body["last_finished_at"] = last_finished_ms
        if last_outcome is not None:
            body["last_outcome"] = last_outcome
        if last_synced_ms is not None:
            body["last_synced_at"] = last_synced_ms
        return body

    return router
