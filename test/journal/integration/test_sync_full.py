"""Integration tests for GET /api/journal/sync/full endpoint."""
import pytest
from datetime import datetime, timedelta


@pytest.mark.integration
class TestSyncFull:
    def test_empty_database_returns_empty_response(self, client):
        """Should return empty config and days for fresh database."""
        response = client.get("/api/journal/sync/full")
        assert response.status_code == 200
        data = response.json()
        assert data["config"] == []
        assert data["days"] == {}
        assert "serverTime" in data

    def test_returns_server_time(self, client):
        """Should return serverTime in ISO-8601 format."""
        response = client.get("/api/journal/sync/full")
        data = response.json()
        assert "serverTime" in data
        assert data["serverTime"].endswith("Z")
        assert "T" in data["serverTime"]

    def test_returns_all_trackers(self, client, journal_seeded_database):
        """Should return all non-deleted trackers."""
        response = client.get("/api/journal/sync/full")
        assert response.status_code == 200
        data = response.json()
        assert len(data["config"]) >= 1
        tracker_ids = [t["id"] for t in data["config"]]
        assert journal_seeded_database["tracker"]["id"] in tracker_ids

    def test_tracker_includes_all_fields(self, client, journal_seeded_database):
        """Returned trackers should include all expected fields."""
        response = client.get("/api/journal/sync/full")
        tracker = response.json()["config"][0]

        assert "id" in tracker
        assert "name" in tracker
        assert "category" in tracker
        assert "type" in tracker
        assert "_version" in tracker
        assert "_lastModifiedBy" in tracker
        assert "_lastModifiedAt" in tracker

    def test_returns_entries_within_7_days(self, client, journal_seeded_database):
        """Should return entries from the last 7 days."""
        response = client.get("/api/journal/sync/full")
        data = response.json()

        seven_days_ago = (datetime.now() - timedelta(days=7)).strftime("%Y-%m-%d")
        for date_str in data["days"].keys():
            assert date_str >= seven_days_ago

    def test_excludes_entries_older_than_7_days(self, client, journal_registered_client, sample_tracker):
        """Entries older than 7 days should not appear."""
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })

        old_date = (datetime.now() - timedelta(days=10)).strftime("%Y-%m-%d")
        today = datetime.now().strftime("%Y-%m-%d")

        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [],
            "days": {
                old_date: {sample_tracker["id"]: {"value": 1, "_baseVersion": 0}},
                today: {sample_tracker["id"]: {"value": 2, "_baseVersion": 0}}
            }
        })

        response = client.get("/api/journal/sync/full")
        days = response.json()["days"]

        assert today in days
        assert old_date not in days

    def test_excludes_deleted_trackers(self, client, journal_registered_client, sample_tracker):
        """Deleted trackers should not appear in full sync."""
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })

        deleted_tracker = {**sample_tracker, "_deleted": True, "_baseVersion": 1}
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [deleted_tracker],
            "days": {}
        })

        response = client.get("/api/journal/sync/full")
        data = response.json()
        tracker_ids = [t["id"] for t in data["config"]]
        assert sample_tracker["id"] not in tracker_ids

    def test_includes_version_metadata(self, client, journal_seeded_database):
        """Response should include version metadata for conflict tracking."""
        response = client.get("/api/journal/sync/full")
        data = response.json()

        if data["config"]:
            tracker = data["config"][0]
            assert "_version" in tracker
            assert tracker["_version"] >= 1

    def test_entry_structure(self, client, journal_seeded_database):
        """Entries should have correct nested structure."""
        response = client.get("/api/journal/sync/full")
        data = response.json()

        for date_str, trackers in data["days"].items():
            assert isinstance(trackers, dict)
            for tracker_id, entry in trackers.items():
                assert isinstance(entry, dict)
                assert "_version" in entry

    def test_metadata_fields_merged(self, client, journal_registered_client):
        """Extra metadata fields should be merged into tracker."""
        tracker = {
            "id": "quantifiable-tracker",
            "name": "Water",
            "category": "health",
            "type": "quantifiable",
            "unit": "glasses",
            "goal": 8,
            "minValue": 0,
            "maxValue": 20,
            "_baseVersion": 0
        }
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [tracker],
            "days": {}
        })

        response = client.get("/api/journal/sync/full")
        saved_tracker = next(
            t for t in response.json()["config"]
            if t["id"] == "quantifiable-tracker"
        )

        assert saved_tracker["unit"] == "glasses"
        assert saved_tracker["goal"] == 8
        assert saved_tracker["minValue"] == 0
