"""Integration tests for GET /api/coach/sync endpoint (full sync)."""
import pytest
from datetime import datetime, timedelta, timezone


@pytest.mark.integration
class TestSyncGetEmpty:
    def test_empty_database_returns_empty_response(self, client, coach_registered_client):
        """Should return empty plans and logs for fresh database."""
        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        assert response.status_code == 200
        data = response.json()
        assert data["plans"] == {}
        assert data["logs"] == {}
        assert "serverTime" in data

    def test_returns_server_time(self, client, coach_registered_client):
        """Should return serverTime in ISO-8601 format."""
        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        data = response.json()
        assert "serverTime" in data
        assert data["serverTime"].endswith("Z")
        assert "T" in data["serverTime"]

    def test_requires_client_id(self, client):
        """Should require client_id parameter."""
        response = client.get("/api/coach/sync")
        assert response.status_code == 422


@pytest.mark.integration
class TestSyncGetWithData:
    def test_returns_all_plans(self, client, coach_seeded_database):
        """Should return all workout plans."""
        response = client.get(f"/api/coach/sync?client_id={coach_seeded_database['client_id']}")
        assert response.status_code == 200
        data = response.json()
        assert len(data["plans"]) >= 2

    def test_returns_logs(self, client, coach_seeded_database):
        """Should return workout logs."""
        response = client.get(f"/api/coach/sync?client_id={coach_seeded_database['client_id']}")
        data = response.json()
        assert len(data["logs"]) >= 1

    def test_plan_includes_blocks_with_exercises(self, client, coach_seeded_database):
        """Returned plans should include blocks with exercise data."""
        response = client.get(f"/api/coach/sync?client_id={coach_seeded_database['client_id']}")
        plans = response.json()["plans"]

        today = coach_seeded_database["dates"][0]
        plan = plans.get(today)
        assert plan is not None
        assert "blocks" in plan
        assert len(plan["blocks"]) == 3
        total_exercises = sum(len(b["exercises"]) for b in plan["blocks"])
        assert total_exercises == 3

    def test_plan_includes_metadata(self, client, coach_seeded_database):
        """Plans should include day_name, location, phase."""
        response = client.get(f"/api/coach/sync?client_id={coach_seeded_database['client_id']}")
        plans = response.json()["plans"]

        today = coach_seeded_database["dates"][0]
        plan = plans[today]
        assert plan["day_name"] == "Test Workout"
        assert plan["location"] == "Home"
        assert plan["phase"] == "Foundation"

    def test_log_includes_session_feedback(self, client, coach_seeded_database):
        """Logs should include session feedback."""
        response = client.get(f"/api/coach/sync?client_id={coach_seeded_database['client_id']}")
        logs = response.json()["logs"]

        today = coach_seeded_database["dates"][0]
        log = logs.get(today)
        assert log is not None
        assert "session_feedback" in log
        assert log["session_feedback"]["pain_discomfort"] == "None"

    def test_log_includes_exercise_data(self, client, coach_seeded_database):
        """Logs should include per-exercise completion data."""
        response = client.get(f"/api/coach/sync?client_id={coach_seeded_database['client_id']}")
        logs = response.json()["logs"]

        today = coach_seeded_database["dates"][0]
        log = logs[today]
        assert "ex_1" in log
        assert log["ex_1"]["completed"] is True
        assert len(log["ex_1"]["sets"]) == 3


@pytest.mark.integration
class TestDeltaSync:
    def test_delta_sync_returns_only_recent_changes(self, client, coach_seeded_database):
        """Delta sync should only return changes since last_sync_time."""
        response = client.get(f"/api/coach/sync?client_id={coach_seeded_database['client_id']}")
        server_time = response.json()["serverTime"]

        tomorrow = (datetime.now() + timedelta(days=1)).strftime("%Y-%m-%d")
        client.post(
            "/api/coach/sync",
            json={
                "clientId": coach_seeded_database["client_id"],
                "logs": {tomorrow: {"session_feedback": {"pain_discomfort": "None"}}}
            }
        )

        response = client.get(
            f"/api/coach/sync?client_id={coach_seeded_database['client_id']}&last_sync_time={server_time}"
        )
        data = response.json()
        assert tomorrow in data["logs"]

    def test_full_sync_without_last_sync_time(self, client, coach_seeded_database):
        """Without last_sync_time, should return all recent data."""
        response = client.get(f"/api/coach/sync?client_id={coach_seeded_database['client_id']}")
        data = response.json()

        assert len(data["plans"]) >= 1
        assert len(data["logs"]) >= 1
