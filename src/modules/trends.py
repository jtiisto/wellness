"""
Trends API Router — read-only aggregates over coach.db, journal.db, and the
Garmin health DB.

Trends owns NO database. It is the deliberate, narrow exception to module DB
isolation (see docs/ARCHITECTURE.md "Trends"): it builds its OWN read-only
(`mode=ro`) accessors to the source DBs — never the owning module's accessor —
and never writes. Paths resolve through config helpers at create_router()
time, honoring the same env vars the owning modules use (COACH_DB_PATH /
JOURNAL_DB_PATH / GARMIN_MODULE_DB_PATH) plus GARMIN_DB_PATH for the
body-weight series. The garmin MODULE database is the newest of those
readers and the smallest: the device-clock change-point timeline, which is
what places Garmin's rows on the watch's day rather than the server's.

The Garmin DB may legitimately be absent (dev machines without the sync job);
the /weight endpoint degrades to {"available": false} and the chart hides.
Strength endpoints also read it, but only when an ASSISTED exercise
(registry equipment='assisted') has qualifying sets — effective load =
body weight − assistance; without body-weight data those sets drop out of
the aggregates rather than being scored as if the assistance were lifted.
Other endpoints map sqlite3.OperationalError (missing/unmigrated source DB —
e.g. an owning module disabled) to a 503 rather than a 500 traceback.
"""
import functools
import logging
import sqlite3
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException, Query, Request

from config import (get_module_db_path, get_garmin_db_path,
                    get_bodyspec_db_path, get_questy_db_path)
from modules import trends_queries
from modules.db import DbAccessor
from modules.device_clock import ZoneTimeline

# The sleep ledger's fitted constants are personal data and live in a
# gitignored module (modules/sleep_params.example.py documents the shape).
# Absent params degrade exactly like an absent source DB — the example values
# are never used as a fallback.
try:
    from modules.sleep_params import PARAMS as SLEEP_PARAMS
except ImportError:
    SLEEP_PARAMS = None

logger = logging.getLogger(__name__)

# YYYY-MM-DD; range params are local calendar dates (repo convention).
_DATE_PATTERN = r"^\d{4}-\d{2}-\d{2}$"


def _get_sleep_params():
    """Resolution seam for the sleep constants (tests override this)."""
    return SLEEP_PARAMS


def _date_params(start: Optional[str], end: Optional[str], today=None):
    """Normalize range params: end defaults to `today` (the client always
    sends it; the default keeps curl/exploratory use sane). The regex only
    checks shape — calendar-invalid dates (2026-02-30) must 422 here, not
    500 in an aggregate or masquerade as a tracker 404 (review F2).

    `today` is the DEVICE's date when the request reported a zone, so a phone
    a day ahead of the server does not have its own day clipped off the range
    it asked for by a default it never sent. Absent, the server's own date, as
    before.
    """
    end = end or (today or date.today()).isoformat()
    for label, value in (("start", start), ("end", end)):
        if value is not None:
            try:
                date.fromisoformat(value)
            except ValueError:
                raise HTTPException(
                    status_code=422, detail=f"Invalid {label} date: {value}")
    return start, end


def _source_db_guard(fn):
    """Map a missing/unmigrated source DB to 503 (the owning module is
    disabled or hasn't initialized) instead of a 500 traceback."""
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except sqlite3.OperationalError as e:
            raise HTTPException(
                status_code=503, detail=f"Source database unavailable: {e}"
            )
    return wrapper


def create_router() -> APIRouter:
    coach_db = DbAccessor(get_module_db_path("coach"), read_only=True)
    journal_db = DbAccessor(get_module_db_path("journal"), read_only=True)
    garmin_db = DbAccessor(get_garmin_db_path(), read_only=True)
    bodyspec_db = DbAccessor(get_bodyspec_db_path(), read_only=True)

    if not Path(garmin_db.path).exists():
        logger.info(
            "Garmin DB not found at %s — weight chart disabled", garmin_db.path
        )
    if not Path(bodyspec_db.path).exists():
        logger.info(
            "BodySpec DB not found at %s — composition cards disabled",
            bodyspec_db.path,
        )
    questy_db = DbAccessor(get_questy_db_path(), read_only=True)
    if not Path(questy_db.path).exists():
        logger.info(
            "Questy DB not found at %s — labs cards disabled", questy_db.path
        )
    # The device-clock timeline, owned by the garmin MODULE (its own small DB,
    # not garmy's) and read here exactly like the other cross-module sources:
    # read-only, path resolved through config so a test env var repoints it.
    # Absent file (garmin disabled, a fresh clone, no Android client yet) →
    # an empty timeline → the server's own zone, the pre-device-clock
    # behaviour. See ARCHITECTURE.md "Device clock".
    client_zone_db_path = get_module_db_path("garmin")

    router = APIRouter()

    def _zone_timeline():
        """Load the change points for this request. A handful of rows against
        an index, so it is not worth caching — and caching it would be wrong:
        a zone change must reach the very next request, not the next restart."""
        return ZoneTimeline.load(client_zone_db_path)

    def _device_today(request):
        """The requesting device's calendar date, or the server's.

        `request.state.client_zone` is set by the app's clock middleware when
        the request carried a resolvable zone; absent or unresolvable, the
        server's own date is the answer it has always been.
        """
        zone = getattr(request.state, "client_zone", None)
        if zone is None:
            return date.today()
        return datetime.now(timezone.utc).astimezone(zone).date()

    # Endpoints land phase by phase (strength → cardio → journal → weight →
    # overview); the accessors above are the only construction-time work, so
    # startup cost is nil and no migration runs (trends owns no schema).

    @router.get("/strength/exercises")
    @_source_db_guard
    def strength_exercises(
        request: Request,
        start: Optional[str] = Query(None, pattern=_DATE_PATTERN),
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        start, end = _date_params(start, end, _device_today(request))
        return trends_queries.strength_exercises(coach_db, garmin_db, start=start, end=end)

    @router.get("/strength/exercise/{slug}")
    @_source_db_guard
    def strength_exercise_series(
        request: Request,
        slug: str,
        start: Optional[str] = Query(None, pattern=_DATE_PATTERN),
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        start, end = _date_params(start, end, _device_today(request))
        try:
            return trends_queries.strength_exercise_series(
                coach_db, garmin_db, slug=slug, start=start, end=end
            )
        except ValueError as e:
            raise HTTPException(status_code=404, detail=str(e))

    @router.get("/strength/volume")
    @_source_db_guard
    def strength_volume(
        request: Request,
        start: Optional[str] = Query(None, pattern=_DATE_PATTERN),
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        today = _device_today(request)
        start, end = _date_params(start, end, today)
        return trends_queries.strength_weekly_volume(
            coach_db, garmin_db, start=start, end=end, today=today
        )

    @router.get("/cardio")
    @_source_db_guard
    def cardio(
        request: Request,
        start: Optional[str] = Query(None, pattern=_DATE_PATTERN),
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        today = _device_today(request)
        start, end = _date_params(start, end, today)
        return trends_queries.cardio_weekly(
            coach_db, start=start, end=end, today=today
        )

    @router.get("/journal/trackers")
    @_source_db_guard
    def journal_trackers():
        return trends_queries.journal_trackers(journal_db)

    @router.get("/journal/tracker/{tracker_id}")
    @_source_db_guard
    def journal_tracker_detail(
        request: Request,
        tracker_id: str,
        start: Optional[str] = Query(None, pattern=_DATE_PATTERN),
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        today = _device_today(request)
        start, end = _date_params(start, end, today)
        try:
            return trends_queries.journal_tracker_detail(
                journal_db, tracker_id=tracker_id, start=start, end=end,
                today=today,
            )
        except ValueError as e:
            raise HTTPException(status_code=404, detail=str(e))

    @router.get("/overview")
    @_source_db_guard
    def overview(request: Request):
        # No range params, but every window inside is measured back from
        # `today` — so it is the device's date for the same reason the ranges
        # are: the server never assumes its own zone is the watch's.
        return trends_queries.overview(
            coach_db, journal_db, garmin_db, today=_device_today(request)
        )

    @router.get("/weight")
    def weight(
        request: Request,
        start: Optional[str] = Query(None, pattern=_DATE_PATTERN),
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        # No 503 guard: weight_series degrades to available:false itself —
        # an absent Garmin DB is a supported state, not an error.
        start, end = _date_params(start, end, _device_today(request))
        return trends_queries.weight_series(garmin_db, start=start, end=end)

    @router.get("/health/recovery")
    def health_recovery(
        request: Request,
        start: Optional[str] = Query(None, pattern=_DATE_PATTERN),
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        # Same degradation contract as /weight: absent Garmin data is a
        # supported state ({"available": false}), never an error.
        start, end = _date_params(start, end, _device_today(request))
        return trends_queries.recovery_series(garmin_db, start=start, end=end)

    @router.get("/health/sleep")
    def health_sleep(
        request: Request,
        start: Optional[str] = Query(None, pattern=_DATE_PATTERN),
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        # Two supported unavailable states, same shape: no Garmin DB, and no
        # fitted params on this machine (a fresh clone has neither).
        start, end = _date_params(start, end, _device_today(request))
        params = _get_sleep_params()
        if params is None:
            return {"available": False, "days": []}
        # The only endpoint on the device clock: its ledger is keyed by the
        # night the watch scored, and its strain term reads the wrist stream.
        return trends_queries.sleep_series(
            garmin_db, params, start=start, end=end,
            today=_device_today(request), timeline=_zone_timeline()
        )

    @router.get("/health/composition")
    def health_composition(
        request: Request,
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        # All scans up to `end` (no start: months-apart scans are shown in
        # full; the weight-overlay filters client-side). Degrades like /weight.
        _, end = _date_params(None, end, _device_today(request))
        return trends_queries.composition_series(bodyspec_db, end=end)

    @router.get("/health/labs")
    def health_labs(
        request: Request,
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        # All reports up to `end` (months apart, like scans). Degrades like
        # /weight.
        _, end = _date_params(None, end, _device_today(request))
        return trends_queries.labs_series(questy_db, end=end)

    return router
