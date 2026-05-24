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

    def test_timestamp_not_updated_when_all_records_rejected(
        self, client, journal_registered_client, sample_tracker
    ):
        """lastModified should not advance when every record in an upload is rejected.

        meta_sync.last_server_sync_time is only set when at least one record is
        accepted; a batch where all records are stale leaves it unchanged.
        """
        import time

        # Initial create stamps the status
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {},
        })
        first_status = client.get("/api/journal/sync/status").json()["lastModified"]

        # Advance the row so a stale upload won't match
        time.sleep(0.01)
        adv_response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [{**sample_tracker, "name": "Advance",
                        "_baseLastModifiedAt": first_status}],
            "days": {},
        })
        post_advance_status = client.get("/api/journal/sync/status").json()["lastModified"]
        assert post_advance_status >= first_status

        # Now send an all-stale batch — every record references an old base token
        time.sleep(0.01)
        rejected_response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [{**sample_tracker, "name": "Stale",
                        "_baseLastModifiedAt": first_status}],
            "days": {},
        }).json()
        assert rejected_response["acceptedTrackers"] == []
        assert len(rejected_response["rejectedTrackers"]) == 1

        # Status timestamp is unchanged from the prior accept
        final_status = client.get("/api/journal/sync/status").json()["lastModified"]
        assert final_status == post_advance_status
