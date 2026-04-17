"""Integration tests for POST /api/journal/sync/update endpoint."""
import pytest
from datetime import datetime, timezone


@pytest.mark.integration
class TestSyncUpdateTrackers:
    def test_create_new_tracker(self, client, journal_registered_client, sample_tracker):
        """Should successfully create a new tracker."""
        payload = {
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        }
        response = client.post("/api/journal/sync/update", json=payload)
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert len(data["appliedConfig"]) == 1
        assert data["appliedConfig"][0]["_version"] == 1

    def test_update_existing_tracker(self, client, journal_registered_client, sample_tracker):
        """Should update tracker with incremented version."""
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })

        updated = {**sample_tracker, "name": "Updated Name", "_baseVersion": 1}
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [updated],
            "days": {}
        })
        data = response.json()
        assert data["success"] is True
        assert data["appliedConfig"][0]["_version"] == 2
        assert data["appliedConfig"][0]["name"] == "Updated Name"

    def test_soft_delete_tracker(self, client, journal_registered_client, sample_tracker):
        """Should soft-delete tracker when _deleted flag is set."""
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })

        deleted = {**sample_tracker, "_deleted": True, "_baseVersion": 1}
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [deleted],
            "days": {}
        })
        assert response.status_code == 200

        full_response = client.get("/api/journal/sync/full")
        trackers = full_response.json()["config"]
        assert not any(t["id"] == sample_tracker["id"] for t in trackers)

    def test_conflict_detection_tracker(self, client, journal_registered_client, sample_tracker):
        """Should detect conflict when server version > client base version."""
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })

        updated = {**sample_tracker, "name": "Updated", "_baseVersion": 1}
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [updated],
            "days": {}
        })

        stale = {**sample_tracker, "name": "Stale Update", "_baseVersion": 1}
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [stale],
            "days": {}
        })
        data = response.json()

        assert data["success"] is False
        assert len(data["conflicts"]) == 1
        assert data["conflicts"][0]["entityType"] == "tracker"
        assert data["conflicts"][0]["serverVersion"] == 2
        assert data["conflicts"][0]["clientBaseVersion"] == 1

    def test_multiple_trackers_in_single_update(self, client, journal_registered_client):
        """Should handle multiple trackers in single update."""
        trackers = [
            {"id": f"tracker-{i}", "name": f"Tracker {i}", "category": "test", "type": "simple", "_baseVersion": 0}
            for i in range(3)
        ]
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": trackers,
            "days": {}
        })
        data = response.json()

        assert data["success"] is True
        assert len(data["appliedConfig"]) == 3


@pytest.mark.integration
class TestSyncUpdateEntries:
    def test_create_entry(self, client, journal_registered_client, sample_tracker):
        """Should successfully create entry for a tracker."""
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })

        today = datetime.now().strftime("%Y-%m-%d")
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [],
            "days": {
                today: {
                    sample_tracker["id"]: {
                        "value": 5,
                        "completed": False,
                        "_baseVersion": 0
                    }
                }
            }
        })
        data = response.json()
        assert data["success"] is True
        assert today in data["appliedDays"]
        assert data["appliedDays"][today][sample_tracker["id"]]["value"] == 5

    def test_update_entry(self, client, journal_registered_client, sample_tracker):
        """Should update entry with incremented version."""
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })

        today = datetime.now().strftime("%Y-%m-%d")

        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [],
            "days": {today: {sample_tracker["id"]: {"value": 3, "_baseVersion": 0}}}
        })

        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [],
            "days": {today: {sample_tracker["id"]: {"value": 5, "_baseVersion": 1}}}
        })
        data = response.json()

        assert data["success"] is True
        assert data["appliedDays"][today][sample_tracker["id"]]["_version"] == 2
        assert data["appliedDays"][today][sample_tracker["id"]]["value"] == 5

    def test_conflict_detection_entry(self, client, journal_registered_client, sample_tracker):
        """Should detect conflict for entry updates."""
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })

        today = datetime.now().strftime("%Y-%m-%d")

        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [],
            "days": {today: {sample_tracker["id"]: {"value": 5, "_baseVersion": 0}}}
        })

        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [],
            "days": {today: {sample_tracker["id"]: {"value": 6, "_baseVersion": 1}}}
        })

        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [],
            "days": {today: {sample_tracker["id"]: {"value": 7, "_baseVersion": 1}}}
        })
        data = response.json()

        assert data["success"] is False
        assert len(data["conflicts"]) == 1
        assert data["conflicts"][0]["entityType"] == "entry"
        assert f"{today}|{sample_tracker['id']}" == data["conflicts"][0]["entityId"]

    def test_entry_with_null_value(self, client, journal_registered_client, sample_simple_tracker):
        """Should handle entry with null value (simple tracker)."""
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_simple_tracker],
            "days": {}
        })

        today = datetime.now().strftime("%Y-%m-%d")
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [],
            "days": {today: {sample_simple_tracker["id"]: {"value": None, "completed": True, "_baseVersion": 0}}}
        })
        data = response.json()

        assert data["success"] is True
        entry = data["appliedDays"][today][sample_simple_tracker["id"]]
        assert entry["completed"] is True


@pytest.mark.integration
class TestSyncUpdateResponse:
    def test_success_true_when_no_conflicts(self, client, journal_registered_client, sample_tracker):
        """success should be True when there are no conflicts."""
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })
        data = response.json()

        assert data["success"] is True
        assert data["conflicts"] == []

    def test_last_modified_only_on_success(self, client, journal_registered_client, sample_tracker):
        """lastModified should only be set on successful sync."""
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })
        assert response.json()["lastModified"] is not None

        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [{**sample_tracker, "name": "V2", "_baseVersion": 1}],
            "days": {}
        })
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [{**sample_tracker, "name": "Stale", "_baseVersion": 1}],
            "days": {}
        })
        assert response.json()["lastModified"] is None
