"""Integration tests for POST /api/coach/sync endpoint (log uploads)."""

import pytest
from datetime import datetime, timedelta, timezone


@pytest.mark.integration
class TestSyncPostBasic:
    def test_workout_status_empty(self, client):
        """Test status endpoint with empty database."""
        response = client.get("/api/coach/status")
        assert response.status_code == 200
        data = response.json()
        assert "lastModified" in data

    def test_register_client(self, client):
        """Test client registration."""
        response = client.post(
            "/api/coach/register",
            params={"client_id": "test-client-123", "client_name": "Test Device"}
        )
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"
        assert data["clientId"] == "test-client-123"

    def test_register_client_without_name(self, client):
        """Test client registration with default name."""
        response = client.post(
            "/api/coach/register",
            params={"client_id": "test-client-456"}
        )
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"


@pytest.mark.integration
class TestSyncPostLogs:
    def test_upload_single_log(self, client, sample_log, coach_registered_client):
        """Should successfully upload a workout log."""
        today = datetime.now().strftime("%Y-%m-%d")
        response = client.post(
            "/api/coach/sync",
            json={
                "clientId": coach_registered_client,
                "logs": {today: sample_log}
            }
        )
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert today in data["appliedLogs"]

    def test_upload_multiple_logs(self, client, sample_log, coach_registered_client):
        """Should handle multiple logs in single upload."""
        today = datetime.now().strftime("%Y-%m-%d")
        yesterday = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")

        response = client.post(
            "/api/coach/sync",
            json={
                "clientId": coach_registered_client,
                "logs": {
                    today: sample_log,
                    yesterday: {"session_feedback": {"pain_discomfort": "Minor soreness"}}
                }
            }
        )
        data = response.json()

        assert data["success"] is True
        assert len(data["appliedLogs"]) == 2

    def test_upload_empty_logs(self, client, coach_registered_client):
        """Should handle empty logs payload."""
        response = client.post(
            "/api/coach/sync",
            json={
                "clientId": coach_registered_client,
                "logs": {}
            }
        )
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["appliedLogs"] == []

    def test_log_roundtrip(self, client, sample_log, coach_registered_client):
        """Test uploading and then downloading a log."""
        today = datetime.now().strftime("%Y-%m-%d")

        client.post(
            "/api/coach/sync",
            json={
                "clientId": coach_registered_client,
                "logs": {today: sample_log}
            }
        )

        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        data = response.json()

        assert today in data["logs"]
        assert data["logs"][today]["session_feedback"]["pain_discomfort"] == "None"
        assert data["logs"][today]["ex_1"]["completed"] is True


@pytest.mark.integration
class TestSyncResponse:
    def test_response_includes_server_time(self, client, coach_registered_client):
        """Response should include serverTime."""
        response = client.post(
            "/api/coach/sync",
            json={"clientId": coach_registered_client, "logs": {}}
        )
        data = response.json()
        assert "serverTime" in data
        assert data["serverTime"].endswith("Z")


@pytest.mark.integration
class TestLogDataIntegrity:
    def test_exercise_sets_preserved(self, client, coach_registered_client):
        """Exercise set data should be fully preserved."""
        today = datetime.now().strftime("%Y-%m-%d")
        log = {
            "ex_1": {
                "completed": True,
                "sets": [
                    {"set_num": 1, "weight": 24, "reps": 10, "rpe": 7, "unit": "kg"},
                    {"set_num": 2, "weight": 28, "reps": 8, "rpe": 8, "unit": "kg"},
                    {"set_num": 3, "weight": 28, "reps": 6, "rpe": 9, "unit": "kg"}
                ]
            }
        }

        client.post(
            "/api/coach/sync",
            json={"clientId": coach_registered_client, "logs": {today: log}}
        )

        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        saved = response.json()["logs"][today]["ex_1"]

        assert len(saved["sets"]) == 3
        assert saved["sets"][0]["weight"] == 24
        assert saved["sets"][1]["rpe"] == 8
        assert saved["sets"][2]["reps"] == 6

    def test_cardio_data_preserved(self, client, coach_registered_client):
        """Cardio entry data should be fully preserved."""
        today = datetime.now().strftime("%Y-%m-%d")
        log = {
            "cardio_1": {
                "completed": True,
                "duration_min": 45,
                "avg_hr": 142,
                "max_hr": 158
            }
        }

        client.post(
            "/api/coach/sync",
            json={"clientId": coach_registered_client, "logs": {today: log}}
        )

        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        saved = response.json()["logs"][today]["cardio_1"]

        assert saved["duration_min"] == 45
        assert saved["avg_hr"] == 142
        assert saved["max_hr"] == 158

    def test_checklist_items_preserved(self, client, coach_registered_client):
        """Checklist completed items should be preserved."""
        today = datetime.now().strftime("%Y-%m-%d")
        log = {
            "warmup_1": {
                "completed_items": ["Cat-Cow x10", "Bird-Dog x5/side", "Dead Bug x10"]
            }
        }

        client.post(
            "/api/coach/sync",
            json={"clientId": coach_registered_client, "logs": {today: log}}
        )

        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        saved = response.json()["logs"][today]["warmup_1"]

        assert len(saved["completed_items"]) == 3
        assert "Cat-Cow x10" in saved["completed_items"]

    def test_user_notes_preserved(self, client, coach_registered_client):
        """User notes should be preserved."""
        today = datetime.now().strftime("%Y-%m-%d")
        log = {
            "ex_1": {
                "completed": True,
                "user_note": "Felt strong today, could have gone heavier"
            },
            "session_feedback": {
                "pain_discomfort": "Slight tightness after lunges",
                "general_notes": "Great session overall, energy was high"
            }
        }

        client.post(
            "/api/coach/sync",
            json={"clientId": coach_registered_client, "logs": {today: log}}
        )

        response = client.get(f"/api/coach/sync?client_id={coach_registered_client}")
        saved = response.json()["logs"][today]

        assert saved["ex_1"]["user_note"] == "Felt strong today, could have gone heavier"
        assert "tightness" in saved["session_feedback"]["pain_discomfort"]
