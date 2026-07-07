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
from pathlib import Path

from fastapi import APIRouter, HTTPException

from config import get_module_db_path, get_garmin_db_path
from modules.db import DbAccessor

logger = logging.getLogger(__name__)


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

    return router
