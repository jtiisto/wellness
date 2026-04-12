"""E2E tests for complete sync workflows via unified app."""
import pytest
from datetime import datetime, timedelta, timezone


@pytest.mark.e2e
class TestFreshClientWorkflow:
    def test_fresh_client_full_sync_workflow(self, client):
        """Test complete workflow for a new client."""
        client_id = "new-client-e2e"
        response = client.post(f"/api/journal/sync/register?client_id={client_id}")
        assert response.status_code == 200
        assert response.json()["status"] == "ok"

        response = client.get("/api/journal/sync/status")
        assert response.json()["lastModified"] is None

        response = client.get("/api/journal/sync/full")
        data = response.json()
        assert data["config"] == []
        assert data["days"] == {}

        tracker = {
            "id": "e2e-tracker",
            "name": "E2E Test Tracker",
            "category": "test",
            "type": "simple",
            "_baseVersion": 0
        }
        today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        payload = {
            "clientId": client_id,
            "config": [tracker],
            "days": {
                today: {
                    "e2e-tracker": {"value": None, "completed": True, "_baseVersion": 0}
                }
            }
        }
        response = client.post("/api/journal/sync/update", json=payload)
        assert response.json()["success"] is True

        response = client.get("/api/journal/sync/full")
        data = response.json()
        assert len(data["config"]) == 1
        assert data["config"][0]["name"] == "E2E Test Tracker"
        assert today in data["days"]

        response = client.get("/api/journal/sync/status")
        assert response.json()["lastModified"] is not None


@pytest.mark.e2e
class TestIncrementalSyncWorkflow:
    def test_incremental_sync_workflow(self, client, journal_seeded_database):
        """Test delta sync after initial full sync."""
        client_id = journal_seeded_database["client_id"]

        response = client.get("/api/journal/sync/full")
        server_time = response.json()["serverTime"]

        new_tracker = {
            "id": "delta-tracker",
            "name": "Delta Tracker",
            "category": "test",
            "type": "simple",
            "_baseVersion": 0
        }
        client.post("/api/journal/sync/update", json={
            "clientId": client_id,
            "config": [new_tracker],
            "days": {}
        })

        response = client.get(f"/api/journal/sync/delta?since={server_time}&client_id={client_id}")
        data = response.json()

        tracker_ids = [t["id"] for t in data["config"]]
        assert "delta-tracker" in tracker_ids


@pytest.mark.e2e
class TestTrackerLifecycle:
    def test_tracker_create_update_delete_lifecycle(self, client, journal_registered_client):
        """Test complete tracker lifecycle: create, update, delete."""
        tracker = {
            "id": "lifecycle-tracker",
            "name": "Lifecycle Test",
            "category": "test",
            "type": "quantifiable",
            "unit": "items",
            "_baseVersion": 0
        }

        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [tracker],
            "days": {}
        })
        assert response.json()["success"] is True
        assert response.json()["appliedConfig"][0]["_version"] == 1

        full = client.get("/api/journal/sync/full").json()
        assert any(t["id"] == "lifecycle-tracker" for t in full["config"])

        updated = {**tracker, "name": "Updated Lifecycle", "_baseVersion": 1}
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [updated],
            "days": {}
        })
        assert response.json()["appliedConfig"][0]["_version"] == 2

        deleted = {**tracker, "_deleted": True, "_baseVersion": 2}
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [deleted],
            "days": {}
        })
        assert response.json()["success"] is True

        full = client.get("/api/journal/sync/full").json()
        assert not any(t["id"] == "lifecycle-tracker" for t in full["config"])

        past = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat().replace("+00:00", "Z")
        delta = client.get(f"/api/journal/sync/delta?since={past}&client_id={journal_registered_client}").json()
        assert "lifecycle-tracker" in delta["deletedTrackers"]


@pytest.mark.e2e
class TestMultiClientSync:
    def test_two_clients_create_different_trackers(self, client):
        """Two clients should be able to create different trackers."""
        client.post("/api/journal/sync/register?client_id=client-a")
        client.post("/api/journal/sync/register?client_id=client-b")

        tracker_a = {
            "id": "tracker-a", "name": "Client A Tracker",
            "category": "test", "type": "simple", "_baseVersion": 0
        }
        client.post("/api/journal/sync/update", json={
            "clientId": "client-a", "config": [tracker_a], "days": {}
        })

        tracker_b = {
            "id": "tracker-b", "name": "Client B Tracker",
            "category": "test", "type": "simple", "_baseVersion": 0
        }
        client.post("/api/journal/sync/update", json={
            "clientId": "client-b", "config": [tracker_b], "days": {}
        })

        response = client.get("/api/journal/sync/full")
        tracker_ids = [t["id"] for t in response.json()["config"]]
        assert "tracker-a" in tracker_ids
        assert "tracker-b" in tracker_ids

    def test_concurrent_updates_same_tracker_conflict(self, client):
        """Concurrent updates to same tracker should detect conflicts."""
        client.post("/api/journal/sync/register?client_id=client-x")
        client.post("/api/journal/sync/register?client_id=client-y")

        tracker = {
            "id": "shared-tracker", "name": "Shared",
            "category": "test", "type": "simple", "_baseVersion": 0
        }

        client.post("/api/journal/sync/update", json={
            "clientId": "client-x", "config": [tracker], "days": {}
        })

        updated_x = {**tracker, "name": "X Updated", "_baseVersion": 1}
        client.post("/api/journal/sync/update", json={
            "clientId": "client-x", "config": [updated_x], "days": {}
        })

        updated_y = {**tracker, "name": "Y Updated", "_baseVersion": 1}
        response = client.post("/api/journal/sync/update", json={
            "clientId": "client-y", "config": [updated_y], "days": {}
        })

        data = response.json()
        assert data["success"] is False
        assert len(data["conflicts"]) == 1
        assert data["conflicts"][0]["serverVersion"] == 2
