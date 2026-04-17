"""Integration tests for conflict resolution and pruning."""
import pytest
from datetime import datetime, timedelta, timezone


@pytest.mark.integration
class TestResolveConflict:
    def test_resolve_tracker_conflict_with_client(self, client, journal_registered_client, sample_tracker):
        """Should apply client data when resolving with 'client' resolution."""
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })

        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [{**sample_tracker, "name": "Server Version", "_baseVersion": 1}],
            "days": {}
        })

        client_data = {
            "id": sample_tracker["id"],
            "name": "Client Wins",
            "category": sample_tracker["category"],
            "type": sample_tracker["type"]
        }
        response = client.post(
            "/api/journal/sync/resolve-conflict",
            params={
                "entity_type": "tracker",
                "entity_id": sample_tracker["id"],
                "resolution": "client",
                "client_id": journal_registered_client
            },
            json=client_data
        )
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"
        assert data["resolution"] == "client"
        assert data["entityId"] == sample_tracker["id"]

        full_response = client.get("/api/journal/sync/full")
        tracker = next(
            t for t in full_response.json()["config"]
            if t["id"] == sample_tracker["id"]
        )
        assert tracker["name"] == "Client Wins"

    def test_resolve_tracker_conflict_with_server(self, client, journal_registered_client, sample_tracker):
        """Should keep server data when resolving with 'server' resolution."""
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [{**sample_tracker, "name": "Server Version", "_baseVersion": 1}],
            "days": {}
        })

        response = client.post(
            "/api/journal/sync/resolve-conflict",
            params={
                "entity_type": "tracker",
                "entity_id": sample_tracker["id"],
                "resolution": "server",
                "client_id": journal_registered_client
            }
        )
        assert response.status_code == 200
        assert response.json()["resolution"] == "server"

        full_response = client.get("/api/journal/sync/full")
        tracker = next(
            t for t in full_response.json()["config"]
            if t["id"] == sample_tracker["id"]
        )
        assert tracker["name"] == "Server Version"

    def test_resolve_entry_conflict_with_client(self, client, journal_registered_client, sample_tracker):
        """Should resolve entry conflicts with client data."""
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

        entity_id = f"{today}|{sample_tracker['id']}"
        client_data = {"value": 10, "completed": True}
        response = client.post(
            "/api/journal/sync/resolve-conflict",
            params={
                "entity_type": "entry",
                "entity_id": entity_id,
                "resolution": "client",
                "client_id": journal_registered_client
            },
            json=client_data
        )
        assert response.status_code == 200
        assert response.json()["status"] == "ok"

        full_response = client.get("/api/journal/sync/full")
        entry = full_response.json()["days"][today][sample_tracker["id"]]
        assert entry["value"] == 10
        assert entry["completed"] is True

    def test_resolution_increments_version(self, client, journal_registered_client, sample_tracker):
        """Client resolution should increment version."""
        import modules.journal as journal

        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })

        with journal.get_db() as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT version FROM trackers WHERE id = ?", (sample_tracker["id"],))
            initial_version = cursor.fetchone()["version"]

        client.post(
            "/api/journal/sync/resolve-conflict",
            params={
                "entity_type": "tracker",
                "entity_id": sample_tracker["id"],
                "resolution": "client",
                "client_id": journal_registered_client
            },
            json={"name": "Resolved", "category": "test", "type": "simple"}
        )

        with journal.get_db() as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT version FROM trackers WHERE id = ?", (sample_tracker["id"],))
            new_version = cursor.fetchone()["version"]

        assert new_version == initial_version + 1

    def test_resolution_logged_in_sync_conflicts(self, client, journal_registered_client, sample_tracker):
        """Resolution should be logged in sync_conflicts table."""
        import modules.journal as journal

        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })

        client.post(
            "/api/journal/sync/resolve-conflict",
            params={
                "entity_type": "tracker",
                "entity_id": sample_tracker["id"],
                "resolution": "client",
                "client_id": journal_registered_client
            },
            json={"name": "Resolved", "category": "test", "type": "simple"}
        )

        with journal.get_db() as conn:
            cursor = conn.cursor()
            cursor.execute(
                "SELECT * FROM sync_conflicts WHERE entity_id = ?",
                (sample_tracker["id"],)
            )
            row = cursor.fetchone()

        assert row is not None
        assert row["entity_type"] == "tracker"
        assert row["resolution"] == "client"
        assert row["resolved_at"] is not None


@pytest.mark.integration
class TestGetUnresolvedConflicts:
    def test_returns_empty_for_new_client(self, client, journal_registered_client):
        """Should return empty list for client with no conflicts."""
        response = client.get(f"/api/journal/sync/conflicts?client_id={journal_registered_client}")
        assert response.status_code == 200
        data = response.json()
        assert data["conflicts"] == []

    def test_requires_client_id(self, client):
        """Should require client_id parameter."""
        response = client.get("/api/journal/sync/conflicts")
        assert response.status_code == 422


@pytest.mark.integration
class TestConflictPruning:
    def test_resolved_conflicts_pruned_after_30_days(self, client, journal_registered_client, sample_tracker):
        """Old resolved conflicts should be deleted during sync."""
        import modules.journal as journal

        # Insert an old resolved conflict directly
        old_ts = (datetime.now(timezone.utc) - timedelta(days=31)).isoformat().replace("+00:00", "Z")
        with journal.get_db() as conn:
            conn.execute("""
                INSERT INTO sync_conflicts
                (entity_type, entity_id, client_id, client_data, resolution, resolved_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, ("tracker", "old-tracker", journal_registered_client, '{}', "client", old_ts, old_ts))
            conn.commit()

        # Trigger a sync to run the prune
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })

        # Verify old conflict was pruned
        with journal.get_db() as conn:
            count = conn.execute(
                "SELECT COUNT(*) FROM sync_conflicts WHERE entity_id = 'old-tracker'"
            ).fetchone()[0]
            assert count == 0

    def test_recent_resolved_conflicts_not_pruned(self, client, journal_registered_client, sample_tracker):
        """Recently resolved conflicts should survive pruning."""
        import modules.journal as journal

        recent_ts = (datetime.now(timezone.utc) - timedelta(days=5)).isoformat().replace("+00:00", "Z")
        with journal.get_db() as conn:
            conn.execute("""
                INSERT INTO sync_conflicts
                (entity_type, entity_id, client_id, client_data, resolution, resolved_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, ("tracker", "recent-tracker", journal_registered_client, '{}', "client", recent_ts, recent_ts))
            conn.commit()

        # Trigger a sync to run the prune
        client.post("/api/journal/sync/update", json={
            "clientId": journal_registered_client,
            "config": [sample_tracker],
            "days": {}
        })

        # Verify recent conflict survives
        with journal.get_db() as conn:
            count = conn.execute(
                "SELECT COUNT(*) FROM sync_conflicts WHERE entity_id = 'recent-tracker'"
            ).fetchone()[0]
            assert count == 1
