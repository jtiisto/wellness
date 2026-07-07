"""Trends module registration: the DB-less registry seam (Phase 0).

Trends owns no database — its MODULES entry has no db_env/db_default, and
create_app calls its factory with no argument. Path resolution goes through
config helpers so the reader always follows the owning modules' env vars.
"""

import pytest

from config import (
    MODULES,
    GARMIN_DB_DEFAULT,
    get_garmin_db_path,
    get_module_db_path,
)


@pytest.mark.unit
class TestRegistrySeam:
    def _trends(self):
        return next(m for m in MODULES if m["id"] == "trends")

    def test_trends_entry_is_db_less(self):
        trends = self._trends()
        assert "db_env" not in trends
        assert "db_default" not in trends
        assert trends["api_prefix"] == "/api/trends"

    def test_trends_is_last_in_tab_order(self):
        # Tab order = MODULES order; journal must stay the default (first),
        # and the owning modules must register (and migrate) before trends.
        assert MODULES[-1]["id"] == "trends"
        assert MODULES[0]["id"] == "journal"

    def test_get_module_db_path_honors_env(self, monkeypatch, tmp_path):
        monkeypatch.setenv("COACH_DB_PATH", str(tmp_path / "elsewhere.db"))
        assert get_module_db_path("coach") == tmp_path / "elsewhere.db"

    def test_get_module_db_path_default(self, monkeypatch):
        monkeypatch.delenv("COACH_DB_PATH", raising=False)
        coach = next(m for m in MODULES if m["id"] == "coach")
        assert get_module_db_path("coach") == coach["db_default"]

    def test_garmin_path_default_and_override(self, monkeypatch, tmp_path):
        monkeypatch.delenv("GARMIN_DB_PATH", raising=False)
        assert get_garmin_db_path() == GARMIN_DB_DEFAULT
        monkeypatch.setenv("GARMIN_DB_PATH", str(tmp_path / "g.db"))
        assert get_garmin_db_path() == tmp_path / "g.db"


@pytest.mark.integration
class TestTrendsMounts:
    def test_app_builds_with_trends_and_missing_garmin_db(self, test_app):
        """create_app must succeed with trends enabled while the Garmin DB
        path (set by conftest) points at a nonexistent file."""
        # test_app fixture building at all is most of the assertion; the
        # /api/modules projection is checked in test_module_discovery.
        assert test_app is not None

    def test_modules_endpoint_includes_trends(self, client):
        ids = [m["id"] for m in client.get("/api/modules").json()]
        assert ids[-1] == "trends"
