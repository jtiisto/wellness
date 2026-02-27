"""Integration tests for module discovery API."""
import os
import pytest


class TestListModules:
    def test_returns_three_modules(self, client):
        """GET /api/modules should return 3 modules."""
        resp = client.get("/api/modules")
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 3

    def test_each_module_has_required_fields(self, client):
        """Each module should have id, name, icon, color fields."""
        resp = client.get("/api/modules")
        data = resp.json()
        for m in data:
            assert "id" in m
            assert "name" in m
            assert "icon" in m
            assert "color" in m

    def test_module_ids(self, client):
        """Module IDs should include journal, coach, analysis."""
        resp = client.get("/api/modules")
        ids = [m["id"] for m in resp.json()]
        assert "journal" in ids
        assert "coach" in ids
        assert "analysis" in ids

    def test_module_names_are_nonempty(self, client):
        """Module names should not be empty."""
        resp = client.get("/api/modules")
        for m in resp.json():
            assert len(m["name"]) > 0

    def test_module_colors_are_hex(self, client):
        """Module colors should be hex color codes."""
        resp = client.get("/api/modules")
        for m in resp.json():
            assert m["color"].startswith("#")
            assert len(m["color"]) == 7  # #RRGGBB


class TestDisabledModules:
    def test_disabled_modules_env_var(self, client, monkeypatch):
        """HEALTH_DISABLED_MODULES should disable modules.

        Note: This test verifies the config function behavior.
        The actual app module registration happens at import time,
        so we test the config function directly.
        """
        from config import get_enabled_modules, MODULES
        monkeypatch.setenv("HEALTH_DISABLED_MODULES", "journal")
        enabled = get_enabled_modules()
        ids = [m["id"] for m in enabled]
        assert "journal" not in ids
        assert "coach" in ids
        assert "analysis" in ids

    def test_disable_multiple_modules(self, monkeypatch):
        """Should be able to disable multiple modules."""
        from config import get_enabled_modules
        monkeypatch.setenv("HEALTH_DISABLED_MODULES", "journal,analysis")
        enabled = get_enabled_modules()
        ids = [m["id"] for m in enabled]
        assert "journal" not in ids
        assert "analysis" not in ids
        assert "coach" in ids

    def test_empty_disabled_modules(self, monkeypatch):
        """Empty HEALTH_DISABLED_MODULES should enable all modules."""
        from config import get_enabled_modules
        monkeypatch.setenv("HEALTH_DISABLED_MODULES", "")
        enabled = get_enabled_modules()
        assert len(enabled) == 3
