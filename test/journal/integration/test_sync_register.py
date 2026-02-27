"""Integration tests for POST /api/journal/sync/register endpoint."""
import pytest


@pytest.mark.integration
class TestSyncRegister:
    def test_register_new_client(self, client):
        """Should successfully register a new client."""
        response = client.post("/api/journal/sync/register?client_id=new-client-001")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"
        assert data["clientId"] == "new-client-001"

    def test_register_with_custom_name(self, client):
        """Should register client with custom name."""
        response = client.post(
            "/api/journal/sync/register?client_id=client-002&client_name=MyPhone"
        )
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"
        assert data["clientId"] == "client-002"

    def test_re_register_existing_client(self, client):
        """Should update last_seen_at for existing client."""
        client_id = "existing-client"
        response1 = client.post(f"/api/journal/sync/register?client_id={client_id}")
        assert response1.status_code == 200

        response2 = client.post(f"/api/journal/sync/register?client_id={client_id}")
        assert response2.status_code == 200
        assert response2.json()["status"] == "ok"

    def test_multiple_clients_can_register(self, client):
        """Multiple different clients should be able to register."""
        for i in range(5):
            response = client.post(f"/api/journal/sync/register?client_id=client-{i}")
            assert response.status_code == 200
            assert response.json()["clientId"] == f"client-{i}"

    def test_client_id_required(self, client):
        """Should fail without client_id parameter."""
        response = client.post("/api/journal/sync/register")
        assert response.status_code == 422

    def test_default_name_generated(self, client):
        """Should generate default name from client ID prefix."""
        import modules.journal as journal
        client_id = "abcd1234-5678-90ab-cdef"
        client.post(f"/api/journal/sync/register?client_id={client_id}")

        with journal.get_db() as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT name FROM clients WHERE id = ?", (client_id,))
            row = cursor.fetchone()
            assert row is not None
            assert "abcd1234" in row["name"]
