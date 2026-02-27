"""Integration tests for GET /api/journal/sync/delta endpoint."""
import pytest
from datetime import datetime, timedelta


@pytest.mark.integration
class TestSyncDelta:
    def test_returns_changes_since_timestamp(self, client, journal_seeded_database):
        """Should return only changes since the given timestamp."""
        past = (datetime.utcnow() - timedelta(hours=1)).isoformat() + "Z"

        response = client.get(
            f"/api/journal/sync/delta?since={past}&client_id={journal_seeded_database['client_id']}"
        )
        assert response.status_code == 200
        data = response.json()
        assert "config" in data
        assert "days" in data
        assert "deletedTrackers" in data
        assert "serverTime" in data

    def test_response_structure(self, client, journal_seeded_database):
        """Response should have correct structure."""
        past = (datetime.utcnow() - timedelta(hours=1)).isoformat() + "Z"
        response = client.get(
            f"/api/journal/sync/delta?since={past}&client_id={journal_seeded_database['client_id']}"
        )
        data = response.json()

        assert isinstance(data["config"], list)
        assert isinstance(data["days"], dict)
        assert isinstance(data["deletedTrackers"], list)
        assert isinstance(data["serverTime"], str)

    def test_includes_deleted_tracker_ids(self, client, journal_registered_client, sample_tracker):
        """Should include IDs of deleted trackers."""
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })
        sync_time = response.json()["lastModified"]

        deleted_tracker = {**sample_tracker, "_deleted": True, "_baseVersion": 1}
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [deleted_tracker],
            "days": {}
        })

        response = client.get(
            f"/api/journal/sync/delta?since={sync_time}&client_id={journal_registered_client}"
        )
        data = response.json()
        assert sample_tracker["id"] in data["deletedTrackers"]

    def test_requires_since_parameter(self, client, journal_registered_client):
        """Should require since parameter."""
        response = client.get(f"/api/journal/sync/delta?client_id={journal_registered_client}")
        assert response.status_code == 422

    def test_requires_client_id_parameter(self, client):
        """Should require client_id parameter."""
        past = (datetime.utcnow() - timedelta(hours=1)).isoformat() + "Z"
        response = client.get(f"/api/journal/sync/delta?since={past}")
        assert response.status_code == 422

    def test_empty_response_for_future_timestamp(self, client, journal_seeded_database):
        """Future timestamp should return empty changes."""
        future = (datetime.utcnow() + timedelta(hours=1)).isoformat() + "Z"
        response = client.get(
            f"/api/journal/sync/delta?since={future}&client_id={journal_seeded_database['client_id']}"
        )
        data = response.json()

        assert data["config"] == []
        assert data["days"] == {}
        assert data["deletedTrackers"] == []

    def test_only_returns_recent_entries(self, client, journal_registered_client, sample_tracker):
        """Should only return entries from last 7 days."""
        old_date = (datetime.now() - timedelta(days=10)).strftime("%Y-%m-%d")
        today = datetime.now().strftime("%Y-%m-%d")
        past = (datetime.utcnow() - timedelta(hours=1)).isoformat() + "Z"

        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {
                old_date: {sample_tracker["id"]: {"value": 1, "_baseVersion": 0}},
                today: {sample_tracker["id"]: {"value": 2, "_baseVersion": 0}}
            }
        })

        response = client.get(
            f"/api/journal/sync/delta?since={past}&client_id={journal_registered_client}"
        )
        days = response.json()["days"]

        assert today in days
        assert old_date not in days

    def test_includes_version_metadata(self, client, journal_seeded_database):
        """Returned items should include version metadata."""
        past = (datetime.utcnow() - timedelta(hours=1)).isoformat() + "Z"
        response = client.get(
            f"/api/journal/sync/delta?since={past}&client_id={journal_seeded_database['client_id']}"
        )
        data = response.json()

        if data["config"]:
            tracker = data["config"][0]
            assert "_version" in tracker
            assert "_lastModifiedBy" in tracker
            assert "_lastModifiedAt" in tracker
