"""Integration tests for module discovery API."""
import os
import pytest


class TestListModules:
    def test_returns_all_visible_modules(self, client):
        """GET /api/modules should return the 4 modules that have a PWA tab."""
        resp = client.get("/api/modules")
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 4

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
        """Module IDs should include journal, coach, analysis, trends."""
        resp = client.get("/api/modules")
        ids = [m["id"] for m in resp.json()]
        assert "journal" in ids
        assert "coach" in ids
        assert "analysis" in ids
        assert "trends" in ids

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


class TestHeadlessModules:
    """A headless module is mounted like any other but never advertised to the
    frontend: it has no name/icon/color and no view component to load."""

    def test_headless_module_is_not_listed(self, client):
        ids = [m["id"] for m in client.get("/api/modules").json()]
        assert "hr" not in ids

    def test_headless_module_is_still_mounted(self, client):
        """Filtering is a projection over the response, not a mount decision."""
        assert client.get("/api/hr/status").status_code == 200

    def test_headless_entries_carry_no_presentation_fields(self):
        """The filter is load-bearing: the /api/modules projection reads
        name/icon/color unconditionally, so listing a headless module would
        KeyError rather than render a blank tab."""
        from config import MODULES
        for m in MODULES:
            if m.get("headless"):
                assert not {"name", "icon", "color"} & m.keys()

    def test_visible_modules_default_to_not_headless(self):
        """`headless` is opt-in — absent means the module has a tab."""
        from config import MODULES
        visible = [m for m in MODULES if not m.get("headless")]
        assert [m["id"] for m in visible] == ["journal", "coach", "analysis", "trends"]


class TestDisabledModules:
    def test_disabled_modules_env_var(self, client, monkeypatch):
        """WELLNESS_DISABLED_MODULES should disable modules.

        Note: This test verifies the config function behavior.
        The actual app module registration happens at import time,
        so we test the config function directly.
        """
        from config import get_enabled_modules, MODULES
        monkeypatch.setenv("WELLNESS_DISABLED_MODULES", "journal")
        enabled = get_enabled_modules()
        ids = [m["id"] for m in enabled]
        assert "journal" not in ids
        assert "coach" in ids
        assert "analysis" in ids

    def test_disable_multiple_modules(self, monkeypatch):
        """Should be able to disable multiple modules."""
        from config import get_enabled_modules
        monkeypatch.setenv("WELLNESS_DISABLED_MODULES", "journal,analysis")
        enabled = get_enabled_modules()
        ids = [m["id"] for m in enabled]
        assert "journal" not in ids
        assert "analysis" not in ids
        assert "coach" in ids

    def test_empty_disabled_modules(self, monkeypatch):
        """Empty WELLNESS_DISABLED_MODULES should enable all modules
        (5: the 4 with a tab plus headless hr)."""
        from config import get_enabled_modules, MODULES
        monkeypatch.setenv("WELLNESS_DISABLED_MODULES", "")
        enabled = get_enabled_modules()
        assert len(enabled) == len(MODULES) == 5
