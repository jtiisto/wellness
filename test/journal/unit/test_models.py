"""Unit tests for journal Pydantic models."""
import pytest
from pydantic import ValidationError


@pytest.mark.unit
class TestTrackerConfig:
    def test_valid_tracker_config(self, test_app):
        """Valid tracker config should pass validation."""
        from modules.journal import TrackerConfig
        config = TrackerConfig(id="test-id", name="Test Tracker", category="health", type="simple")
        assert config.id == "test-id"
        assert config.name == "Test Tracker"

    def test_default_values(self, test_app):
        """TrackerConfig should have sensible defaults."""
        from modules.journal import TrackerConfig
        config = TrackerConfig(id="test", name="Test")
        assert config.category == ""
        assert config.type == "simple"

    def test_allows_extra_fields(self, test_app):
        """TrackerConfig should allow extra fields (for meta_json)."""
        from modules.journal import TrackerConfig
        config = TrackerConfig(id="test-id", name="Test", unit="cups", goal=8, minValue=0)
        assert config.model_extra.get("unit") == "cups"
        assert config.model_extra.get("goal") == 8

    def test_missing_id_raises(self, test_app):
        """Missing id field should raise ValidationError."""
        from modules.journal import TrackerConfig
        with pytest.raises(ValidationError):
            TrackerConfig(name="Test")

    def test_missing_name_raises(self, test_app):
        """Missing name field should raise ValidationError."""
        from modules.journal import TrackerConfig
        with pytest.raises(ValidationError):
            TrackerConfig(id="test")


@pytest.mark.unit
class TestSyncPayload:
    def test_valid_sync_payload(self, test_app):
        """Valid sync payload should pass validation."""
        from modules.journal import SyncPayload
        payload = SyncPayload(clientId="client-001", config=[], days={})
        assert payload.clientId == "client-001"

    def test_default_values(self, test_app):
        """SyncPayload should have sensible defaults."""
        from modules.journal import SyncPayload
        payload = SyncPayload(clientId="client-001")
        assert payload.config == []
        assert payload.days == {}
        assert payload.lastSyncTime is None

    def test_missing_client_id_raises(self, test_app):
        """Missing clientId should raise ValidationError."""
        from modules.journal import SyncPayload
        with pytest.raises(ValidationError):
            SyncPayload()


@pytest.mark.unit
class TestStatusResponse:
    def test_null_last_modified(self, test_app):
        """StatusResponse should handle null lastModified."""
        from modules.journal import StatusResponse
        response = StatusResponse()
        assert response.lastModified is None

    def test_with_timestamp(self, test_app):
        """StatusResponse should accept timestamp."""
        from modules.journal import StatusResponse
        response = StatusResponse(lastModified="2024-01-15T10:30:00Z")
        assert response.lastModified == "2024-01-15T10:30:00Z"
