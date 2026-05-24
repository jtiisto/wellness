"""E2E tests for complete sync workflows via unified app."""
import pytest
import time
from datetime import datetime, timedelta, timezone


@pytest.mark.e2e
class TestFreshClientWorkflow:
    def test_fresh_client_full_sync_workflow(self, client):
        """Complete workflow for a new client: register, full pull, upload, full pull."""
        client_id = "new-client-e2e"
        response = client.post(f"/api/journal/sync/register?client_id={client_id}")
        assert response.status_code == 200
        assert response.json()["status"] == "ok"

        response = client.get("/api/journal/sync/status")
        assert response.json()["lastModified"] is None

        # Full pull on an empty DB
        response = client.get("/api/journal/sync/delta")
        data = response.json()
        assert data["config"] == []
        assert data["days"] == {}

        tracker = {
            "id": "e2e-tracker",
            "name": "E2E Test Tracker",
            "category": "test",
            "type": "simple",
        }
        today = datetime.now().strftime("%Y-%m-%d")
        payload = {
            "clientId": client_id,
            "config": [tracker],
            "days": {
                today: {
                    "e2e-tracker": {"value": None, "completed": True},
                }
            }
        }
        response = client.post("/api/journal/sync/update", json=payload)
        body = response.json()
        assert len(body["acceptedTrackers"]) == 1
        assert len(body["acceptedEntries"]) == 1

        # Full pull again — should now contain the new tracker + entry
        response = client.get("/api/journal/sync/delta")
        data = response.json()
        assert len(data["config"]) == 1
        assert data["config"][0]["name"] == "E2E Test Tracker"
        assert today in data["days"]

        # Status endpoint now reports a server sync time
        response = client.get("/api/journal/sync/status")
        assert response.json()["lastModified"] is not None


@pytest.mark.e2e
class TestIncrementalSyncWorkflow:
    def test_incremental_sync_workflow(self, client, journal_seeded_database):
        """Delta sync after an initial full pull should return only new changes."""
        client_id = journal_seeded_database["client_id"]

        response = client.get("/api/journal/sync/delta")
        server_time = response.json()["serverTime"]

        time.sleep(0.01)
        new_tracker = {
            "id": "delta-tracker",
            "name": "Delta Tracker",
            "category": "test",
            "type": "simple",
        }
        client.post("/api/journal/sync/update", json={
            "clientId": client_id,
            "config": [new_tracker],
            "days": {},
        })

        response = client.get(f"/api/journal/sync/delta?since={server_time}")
        data = response.json()
        tracker_ids = [t["id"] for t in data["config"]]
        assert "delta-tracker" in tracker_ids


@pytest.mark.e2e
class TestTrackerLifecycle:
    def test_tracker_create_update_delete_lifecycle(self, client, journal_registered_client):
        """Create, update, then delete a tracker; verify each stage end-to-end."""
        tracker = {
            "id": "lifecycle-tracker",
            "name": "Lifecycle Test",
            "category": "test",
            "type": "quantifiable",
            "unit": "items",
        }

        # Create
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [tracker],
            "days": {},
        })
        body = response.json()
        assert len(body["acceptedTrackers"]) == 1
        stamp_after_create = body["acceptedTrackers"][0]["lastModifiedAt"]

        # Verify it shows up in full pull
        full = client.get("/api/journal/sync/delta").json()
        assert any(t["id"] == "lifecycle-tracker" for t in full["config"])

        # Update with the correct base token
        time.sleep(0.01)
        updated = {**tracker, "name": "Updated Lifecycle",
                   "_baseLastModifiedAt": stamp_after_create}
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [updated],
            "days": {},
        })
        body = response.json()
        assert len(body["acceptedTrackers"]) == 1
        stamp_after_update = body["acceptedTrackers"][0]["lastModifiedAt"]
        assert stamp_after_update > stamp_after_create

        # Delete with the latest base token
        time.sleep(0.01)
        deleted = {**tracker, "_deleted": True,
                   "_baseLastModifiedAt": stamp_after_update}
        response = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [deleted],
            "days": {},
        })
        assert len(response.json()["acceptedTrackers"]) == 1

        # Full pull no longer surfaces the tracker
        full = client.get("/api/journal/sync/delta").json()
        assert not any(t["id"] == "lifecycle-tracker" for t in full["config"])

        # Delta from before the delete includes the id in deletedTrackers
        past = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat().replace("+00:00", "Z")
        delta = client.get(f"/api/journal/sync/delta?since={past}").json()
        assert "lifecycle-tracker" in delta["deletedTrackers"]


@pytest.mark.e2e
class TestStaleUploadRecovery:
    """When a client's `_baseLastModifiedAt` is older than the stored row, the
    server rejects with the current `serverRow` so the client can recover
    in-cycle without waiting for a delta pull."""

    def test_stale_upload_returns_server_row(self, client, journal_registered_client):
        tracker = {"id": "stale-test", "name": "T", "category": "test", "type": "simple"}
        data1 = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [tracker],
            "days": {},
        }).json()
        stamp1 = data1["acceptedTrackers"][0]["lastModifiedAt"]

        # Advance the row server-side
        time.sleep(0.01)
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [{**tracker, "name": "Advanced",
                        "_baseLastModifiedAt": stamp1}],
            "days": {},
        })

        # Stale upload still based on stamp1
        time.sleep(0.01)
        data3 = client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [{**tracker, "name": "Stale",
                        "_baseLastModifiedAt": stamp1}],
            "days": {},
        }).json()

        assert len(data3["rejectedTrackers"]) == 1
        rejected = data3["rejectedTrackers"][0]
        assert rejected["errorKind"] == "stale"
        assert rejected["serverRow"]["name"] == "Advanced"
        assert rejected["serverRow"]["lastModifiedAt"] > stamp1
