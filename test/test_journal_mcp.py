"""Tests for Journal MCP server tools and helpers."""

import sqlite3

import pytest

from journal_mcp.server import QueryValidator, create_mcp_server
from journal_mcp.config import MCPConfig
from modules.db import get_db


# ==================== Unit Tests ====================


@pytest.mark.unit
class TestQueryValidator:
    """Tests for SQL query validation logic."""

    def test_allows_select(self):
        QueryValidator.validate_query("SELECT * FROM trackers")

    def test_allows_with(self):
        QueryValidator.validate_query("WITH cte AS (SELECT 1) SELECT * FROM cte")

    def test_rejects_insert(self):
        with pytest.raises(ValueError, match="Forbidden keywords"):
            QueryValidator.validate_query("SELECT * FROM trackers; INSERT INTO trackers VALUES (1)")

    def test_rejects_delete(self):
        with pytest.raises(ValueError, match="Only SELECT"):
            QueryValidator.validate_query("DELETE FROM trackers")

    def test_rejects_drop(self):
        with pytest.raises(ValueError, match="Only SELECT"):
            QueryValidator.validate_query("DROP TABLE trackers")

    def test_rejects_pragma(self):
        with pytest.raises(ValueError, match="Only SELECT"):
            QueryValidator.validate_query("PRAGMA table_info('trackers')")

    def test_rejects_empty(self):
        with pytest.raises(ValueError, match="cannot be empty"):
            QueryValidator.validate_query("")

    def test_rejects_multiple_statements(self):
        with pytest.raises(ValueError, match="Multiple statements"):
            QueryValidator.validate_query("SELECT 1; SELECT 2")

    def test_semicolon_in_string_ok(self):
        QueryValidator.validate_query("SELECT * FROM trackers WHERE name = 'a;b'")

    def test_add_row_limit(self):
        result = QueryValidator.add_row_limit("SELECT * FROM trackers", 100)
        assert result.endswith("LIMIT 100")

    def test_preserves_existing_limit(self):
        query = "SELECT * FROM trackers LIMIT 50"
        result = QueryValidator.add_row_limit(query, 100)
        assert result == query


@pytest.mark.unit
class TestMCPConfig:
    """Tests for Journal MCP configuration."""

    def test_from_db_path(self, tmp_path):
        db = tmp_path / "test.db"
        db.touch()
        config = MCPConfig.from_db_path(db)
        assert config.db_path == db
        assert config.max_rows == 1000

    def test_validate_missing_db(self, tmp_path):
        config = MCPConfig(db_path=tmp_path / "nonexistent.db")
        with pytest.raises(ValueError, match="not found"):
            config.validate()

    def test_validate_max_rows_zero(self, tmp_path):
        db = tmp_path / "test.db"
        db.touch()
        config = MCPConfig(db_path=db, max_rows=0)
        with pytest.raises(ValueError, match="max_rows must be at least 1"):
            config.validate()

    def test_validate_max_rows_exceeds_absolute(self, tmp_path):
        db = tmp_path / "test.db"
        db.touch()
        config = MCPConfig(db_path=db, max_rows=6000, max_rows_absolute=5000)
        with pytest.raises(ValueError, match="cannot exceed max_rows_absolute"):
            config.validate()


# ==================== Integration Tests ====================


@pytest.mark.integration
class TestJournalMCPTools:
    """Tests for Journal MCP tool functions against a seeded database."""

    @pytest.fixture(autouse=True)
    def setup_mcp(self, test_app, journal_seeded_database, tmp_journal_db):
        """Create MCP server tools bound to the test database."""
        self.seed_data = journal_seeded_database
        self.db_path = tmp_journal_db

        config = MCPConfig(db_path=tmp_journal_db)
        mcp = create_mcp_server(config)

        # Extract the registered tool functions from the MCP server
        self.tools = {}
        for tool in mcp._tool_manager._tools.values():
            self.tools[tool.fn.__name__] = tool.fn

    def test_explore_database_structure(self):
        result = self.tools["explore_database_structure"]()
        assert "available_tables" in result
        tables = result["available_tables"]
        assert "trackers" in tables
        assert "entries" in tables
        assert tables["trackers"]["row_count"] >= 1
        assert tables["entries"]["row_count"] >= 1

    def test_get_table_details(self):
        result = self.tools["get_table_details"](table_name="trackers")
        assert result["table_name"] == "trackers"
        assert len(result["columns"]) > 0
        column_names = [c["name"] for c in result["columns"]]
        assert "name" in column_names
        assert "category" in column_names
        assert len(result["sample_data"]) > 0

    def test_get_table_details_invalid(self):
        with pytest.raises(ValueError, match="does not exist"):
            self.tools["get_table_details"](table_name="nonexistent")

    def test_execute_sql_query(self):
        result = self.tools["execute_sql_query"](
            query="SELECT name, category FROM trackers WHERE deleted = 0"
        )
        assert len(result) >= 1
        assert result[0]["name"] == "Water Intake"

    def test_execute_sql_query_rejects_write(self):
        with pytest.raises(ValueError):
            self.tools["execute_sql_query"](
                query="DELETE FROM trackers"
            )

    def test_list_trackers(self):
        result = self.tools["list_trackers"]()
        assert len(result) >= 1
        tracker = result[0]
        assert "name" in tracker
        assert "category" in tracker
        assert "metadata" in tracker
        # `deleted` is surfaced as a proper bool for the analysis prompt
        assert tracker["deleted"] is False

    def test_get_schedule_adherence(self, client, journal_registered_client):
        """get_schedule_adherence rolls up scheduled vs logged/done per tracker,
        reading the canonical schedule_json / polarity columns populated by the
        sync path, and clamps the window to the tracker's first entry."""
        tracker = {
            "id": "tracker-adh",
            "name": "Adherence Vitamin",
            "category": "adh",
            "type": "simple",
            "scheduleHistory": [{"effectiveFrom": "0000-01-01", "days": [1, 2, 3, 4, 5]}],
            "polarity": "positive",
        }
        # Mon done, Tue done, Wed logged-not-done, Sat off-schedule.
        days = {
            "2026-07-06": {"tracker-adh": {"completed": True}},
            "2026-07-07": {"tracker-adh": {"completed": True}},
            "2026-07-08": {"tracker-adh": {"completed": False}},
            "2026-07-11": {"tracker-adh": {"completed": True}},
        }
        resp = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client, "config": [tracker], "days": days,
        })
        assert resp.status_code == 200, resp.text

        result = self.tools["get_schedule_adherence"](
            start_date="2026-07-06", end_date="2026-07-12",
            tracker_name="Adherence Vitamin",
        )
        assert len(result) == 1
        r = result[0]
        assert r["tracker"] == "Adherence Vitamin"
        assert r["polarity"] == "positive"
        assert r["metric_kind"] == "adherence"
        assert r["window"] == {"start": "2026-07-06", "end": "2026-07-12"}
        assert r["scheduled_days"] == 5
        assert r["logged_days"] == 3
        assert r["done_days"] == 2
        assert r["missed_days"] == 2
        assert r["off_schedule_entries"] == 1
        assert r["adherence_rate"] == 0.4
        assert r["coverage_rate"] == 0.6

    def test_get_schedule_adherence_omits_trackers_without_entries(self, client, journal_registered_client):
        """A scheduled tracker with no entries is omitted (nothing to measure)."""
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [{
                "id": "tracker-noentry", "name": "No Entry Sched",
                "category": "adh", "type": "simple",
                "scheduleHistory": [{"effectiveFrom": "0000-01-01", "days": [1, 2, 3, 4, 5]}],
            }],
            "days": {},
        })
        result = self.tools["get_schedule_adherence"](tracker_name="No Entry Sched")
        assert result == []

    def test_list_trackers_surfaces_schedule_and_polarity(self, client, journal_registered_client):
        """scheduleHistory + polarity ride meta_json and surface under `metadata`.

        Uploads through the real sync API (which serializes non-reserved fields
        into meta_json) and reads back via the MCP tool, so this covers the whole
        passthrough, not just a hand-written meta_json blob.
        """
        tracker = {
            "id": "tracker-sched",
            "name": "Weekday Vitamin",
            "category": "schedule-mcp",
            "type": "simple",
            "scheduleHistory": [{"effectiveFrom": "0000-01-01", "days": [1, 2, 3, 4, 5]}],
            "polarity": "positive",
        }
        resp = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client, "config": [tracker], "days": {},
        })
        assert resp.status_code == 200

        result = self.tools["list_trackers"](category="schedule-mcp")
        assert len(result) == 1
        meta = result[0]["metadata"]
        assert meta["polarity"] == "positive"
        assert meta["scheduleHistory"][-1]["days"] == [1, 2, 3, 4, 5]

    def test_list_trackers_include_deleted_surfaces_soft_deleted_rows(self):
        """When include_deleted=True, soft-deleted trackers appear with deleted=True.

        Historical analysis prompts need to see retired trackers to attribute
        their entries correctly.
        """
        # Mark the seeded tracker as deleted directly in the DB
        with get_db(self.db_path) as conn:
            conn.execute(
                "UPDATE trackers SET deleted = 1 WHERE id = ?",
                (self.seed_data["tracker"]["id"],),
            )
            conn.commit()

        active = self.tools["list_trackers"]()
        assert all(t["deleted"] is False for t in active)
        assert not any(t["name"] == "Water Intake" for t in active)

        full = self.tools["list_trackers"](include_deleted=True)
        deleted = [t for t in full if t["name"] == "Water Intake"]
        assert len(deleted) == 1
        assert deleted[0]["deleted"] is True

    def test_list_trackers_by_category(self):
        result = self.tools["list_trackers"](category="health")
        assert all(t["category"] == "health" for t in result)

    def test_get_entries(self):
        dates = self.seed_data["dates"]
        result = self.tools["get_entries"](
            start_date=dates[-1], end_date=dates[0]
        )
        assert len(result) >= 1
        entry = result[0]
        assert "date" in entry
        assert "tracker_name" in entry
        assert "value" in entry
        assert entry["tracker_deleted"] is False

    def test_get_entries_surfaces_entries_for_deleted_trackers(self):
        """Entries belonging to soft-deleted trackers must still be returned.

        Each entry carries `tracker_deleted` so the analysis prompt can
        distinguish entries from a now-retired tracker.
        """
        dates = self.seed_data["dates"]

        with get_db(self.db_path) as conn:
            conn.execute(
                "UPDATE trackers SET deleted = 1 WHERE id = ?",
                (self.seed_data["tracker"]["id"],),
            )
            conn.commit()

        result = self.tools["get_entries"](
            start_date=dates[-1], end_date=dates[0]
        )
        assert len(result) >= 1, "entries for the deleted tracker should still appear"
        assert all(e["tracker_deleted"] is True for e in result)

    def test_get_entries_by_tracker_name(self):
        dates = self.seed_data["dates"]
        result = self.tools["get_entries"](
            start_date=dates[-1], end_date=dates[0],
            tracker_name="Water",
        )
        assert all("Water" in e["tracker_name"] for e in result)

    def test_get_journal_summary(self):
        result = self.tools["get_journal_summary"](days=30)
        assert "total_entries" in result
        assert "completion_rate_percent" in result
        assert "active_days" in result
        assert "top_trackers" in result
        assert result["total_entries"] >= 1

    def test_get_journal_summary_max_days(self):
        with pytest.raises(ValueError, match="cannot exceed 365"):
            self.tools["get_journal_summary"](days=500)

    def test_get_entries_empty_date_range(self):
        result = self.tools["get_entries"](
            start_date="2099-01-01", end_date="2099-01-31"
        )
        assert result == []

    def test_execute_sql_query_rejects_multi_statement(self):
        with pytest.raises(ValueError, match="Multiple statements"):
            self.tools["execute_sql_query"](
                query="SELECT 1; SELECT 2"
            )

    def test_list_trackers_empty_category(self):
        result = self.tools["list_trackers"](category="nonexistent_category_xyz")
        assert result == []


@pytest.mark.unit
class TestValidatorIgnoresStringLiterals:
    """Forbidden-keyword scan must not fire on words inside quoted data — the
    ?mode=ro connection is the enforcement boundary; the validator is
    defense-in-depth and false positives only cost usability."""

    def test_keyword_inside_single_quotes_is_allowed(self):
        QueryValidator.validate_query(
            "SELECT * FROM trackers WHERE name = 'Update meds'")

    def test_keyword_inside_double_quotes_is_allowed(self):
        QueryValidator.validate_query(
            'SELECT * FROM entries WHERE tracker_id = "delete-me-tracker"')

    def test_keyword_outside_literals_still_rejected(self):
        with pytest.raises(ValueError, match="Forbidden"):
            QueryValidator.validate_query(
                "SELECT * FROM trackers WHERE name = 'x'; UPDATE trackers SET name='y'"
                .replace(";", " "))  # single statement, raw UPDATE keyword

    def test_busy_timeout_is_set(self, tmp_path):
        import sqlite3 as _sq
        from journal_mcp.server import SQLiteConnection
        db = tmp_path / "j.db"
        _sq.connect(db).close()
        with SQLiteConnection(db) as conn:
            assert conn.execute("PRAGMA busy_timeout").fetchone()[0] == 5000
