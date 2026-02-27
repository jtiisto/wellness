"""Cross-module smoke tests: verify each module's endpoints are reachable."""
import pytest


class TestCrossModuleSmoke:
    def test_journal_endpoint_reachable(self, client):
        """Journal sync status should be reachable via /api/journal/sync/status."""
        resp = client.get("/api/journal/sync/status")
        assert resp.status_code == 200
        assert "lastModified" in resp.json()

    def test_coach_endpoint_reachable(self, client):
        """Coach status should be reachable via /api/coach/status."""
        resp = client.get("/api/coach/status")
        assert resp.status_code == 200
        assert "lastModified" in resp.json()

    def test_analysis_endpoint_reachable(self, client):
        """Analysis queries should be reachable via /api/analysis/queries."""
        resp = client.get("/api/analysis/queries")
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)

    def test_modules_endpoint_reachable(self, client):
        """Modules list should be reachable via /api/modules."""
        resp = client.get("/api/modules")
        assert resp.status_code == 200
        assert len(resp.json()) == 3

    def test_all_modules_have_distinct_prefixes(self, client):
        """Verify each module responds on its own prefix without conflicts."""
        # Journal
        r1 = client.get("/api/journal/sync/full")
        assert r1.status_code == 200

        # Coach (requires client_id)
        client.post("/api/coach/register?client_id=smoke-test")
        r2 = client.get("/api/coach/sync?client_id=smoke-test")
        assert r2.status_code == 200

        # Analysis
        r3 = client.get("/api/analysis/reports")
        assert r3.status_code == 200

    def test_journal_and_coach_databases_are_independent(self, client):
        """Journal and coach should have separate databases."""
        # Register a client on journal
        r1 = client.post("/api/journal/sync/register?client_id=shared-id")
        assert r1.status_code == 200

        # Register a client on coach
        r2 = client.post("/api/coach/register?client_id=shared-id")
        assert r2.status_code == 200

        # Both should work independently - journal creates a tracker
        tracker = {
            "id": "cross-test",
            "name": "Cross Test",
            "category": "test",
            "type": "simple",
            "_baseVersion": 0
        }
        client.post("/api/journal/sync/update", json={
            "clientId": "shared-id",
            "config": [tracker],
            "days": {}
        })

        # Verify journal has the tracker
        full = client.get("/api/journal/sync/full").json()
        assert any(t["id"] == "cross-test" for t in full["config"])

        # Coach should not have journal data - plans should be empty
        coach = client.get("/api/coach/sync?client_id=shared-id").json()
        assert coach["plans"] == {}
