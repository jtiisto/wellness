"""Integration tests for GET /api/journal/sync/status endpoint."""
import pytest


@pytest.mark.integration
class TestSyncStatus:
    def test_returns_null_when_no_sync(self, client):
        """Should return null lastModified when no sync has occurred."""
        response = client.get("/api/journal/sync/status")
        assert response.status_code == 200
        data = response.json()
        assert data["lastModified"] is None

    def test_returns_timestamp_after_sync(self, client, journal_registered_client, sample_tracker):
        """Should return lastModified timestamp after successful sync."""
        payload = {
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        }
        client.post("/api/journal/sync/update", json=payload)

        response = client.get("/api/journal/sync/status")
        assert response.status_code == 200
        data = response.json()
        assert data["lastModified"] is not None
        assert data["lastModified"].endswith("Z")

    def test_timestamp_format_is_iso8601(self, client, journal_registered_client, sample_tracker):
        """lastModified should be ISO-8601 format with Z suffix."""
        payload = {
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        }
        client.post("/api/journal/sync/update", json=payload)

        response = client.get("/api/journal/sync/status")
        timestamp = response.json()["lastModified"]

        assert "T" in timestamp
        assert timestamp.endswith("Z")

    def test_timestamp_not_updated_on_conflict(self, client, journal_registered_client, sample_tracker):
        """lastModified should not update when sync has conflicts."""
        payload = {
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        }
        client.post("/api/journal/sync/update", json=payload)

        initial_timestamp = client.get("/api/journal/sync/status").json()["lastModified"]

        updated = {**sample_tracker, "name": "Updated", "_baseVersion": 1}
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [updated],
            "days": {}
        })

        stale = {**sample_tracker, "name": "Stale", "_baseVersion": 1}
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [stale],
            "days": {}
        })
        assert response.json()["success"] is False

        final_timestamp = client.get("/api/journal/sync/status").json()["lastModified"]
        assert final_timestamp is not None
