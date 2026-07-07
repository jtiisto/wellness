"""
Trends API Router — read-only aggregates over coach.db, journal.db, and the
Garmin health DB.

Trends owns NO database. It is the deliberate, narrow exception to module DB
isolation (see docs/ARCHITECTURE.md "Trends"): it builds its OWN read-only
(`mode=ro`) accessors to the source DBs — never the owning module's accessor —
and never writes. Paths resolve through config helpers at create_router()
time, honoring the same env vars the owning modules use (COACH_DB_PATH /
JOURNAL_DB_PATH) plus GARMIN_DB_PATH for the body-weight series.

The Garmin DB may legitimately be absent (dev machines without the sync job);
the /weight endpoint degrades to {"available": false} and the chart hides.
Other endpoints map sqlite3.OperationalError (missing/unmigrated source DB —
e.g. an owning module disabled) to a 503 rather than a 500 traceback.
"""
import functools
import logging
import sqlite3
from datetime import date
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException, Query

from config import get_module_db_path, get_garmin_db_path
from modules import trends_queries
from modules.db import DbAccessor

logger = logging.getLogger(__name__)

# YYYY-MM-DD; range params are local calendar dates (repo convention).
_DATE_PATTERN = r"^\d{4}-\d{2}-\d{2}$"


def _date_params(start: Optional[str], end: Optional[str]):
    """Normalize range params: end defaults to local today (the client always
    sends it; the default keeps curl/exploratory use sane)."""
    return start, end or date.today().isoformat()


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

    if not Path(garmin_db.path).exists():
        logger.info(
            "Garmin DB not found at %s — weight chart disabled", garmin_db.path
        )

    router = APIRouter()

    # Endpoints land phase by phase (strength → cardio → journal → weight →
    # overview); the accessors above are the only construction-time work, so
    # startup cost is nil and no migration runs (trends owns no schema).

    @router.get("/strength/exercises")
    @_source_db_guard
    def strength_exercises(
        start: Optional[str] = Query(None, pattern=_DATE_PATTERN),
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        start, end = _date_params(start, end)
        return trends_queries.strength_exercises(coach_db, start=start, end=end)

    @router.get("/strength/exercise/{slug}")
    @_source_db_guard
    def strength_exercise_series(
        slug: str,
        start: Optional[str] = Query(None, pattern=_DATE_PATTERN),
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        start, end = _date_params(start, end)
        try:
            return trends_queries.strength_exercise_series(
                coach_db, slug=slug, start=start, end=end
            )
        except ValueError as e:
            raise HTTPException(status_code=404, detail=str(e))

    @router.get("/strength/volume")
    @_source_db_guard
    def strength_volume(
        start: Optional[str] = Query(None, pattern=_DATE_PATTERN),
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        start, end = _date_params(start, end)
        return trends_queries.strength_weekly_volume(
            coach_db, start=start, end=end, today=date.today()
        )

    @router.get("/cardio")
    @_source_db_guard
    def cardio(
        start: Optional[str] = Query(None, pattern=_DATE_PATTERN),
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        start, end = _date_params(start, end)
        return trends_queries.cardio_weekly(
            coach_db, start=start, end=end, today=date.today()
        )

    @router.get("/journal/trackers")
    @_source_db_guard
    def journal_trackers():
        return trends_queries.journal_trackers(journal_db)

    @router.get("/journal/tracker/{tracker_id}")
    @_source_db_guard
    def journal_tracker_detail(
        tracker_id: str,
        start: Optional[str] = Query(None, pattern=_DATE_PATTERN),
        end: Optional[str] = Query(None, pattern=_DATE_PATTERN),
    ):
        start, end = _date_params(start, end)
        try:
            return trends_queries.journal_tracker_detail(
                journal_db, tracker_id=tracker_id, start=start, end=end,
                today=date.today(),
            )
        except ValueError as e:
            raise HTTPException(status_code=404, detail=str(e))

    return router
