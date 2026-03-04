"""Integration tests for the force sync feature.

Validates that:
- force-sync.js orchestrator exists with correct structure
- Coach and journal stores export forceSync with proper instrumentation
- Settings menu integrates the Force Sync button
"""
from pathlib import Path

import pytest

PUBLIC_DIR = Path(__file__).parent.parent.parent / "public"
JS_DIR = PUBLIC_DIR / "js"
SHARED_DIR = JS_DIR / "shared"


# ==================== force-sync.js ====================

class TestForceSyncOrchestrator:
    """Tests for public/js/shared/force-sync.js structure."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (SHARED_DIR / "force-sync.js").read_text()

    def test_file_exists(self):
        assert (SHARED_DIR / "force-sync.js").is_file()

    def test_exports_force_sync(self):
        assert "export async function forceSync(" in self.source

    def test_dynamic_import_coach(self):
        """Should dynamically import coach store."""
        assert "import('../coach/store.js')" in self.source

    def test_dynamic_import_journal(self):
        """Should dynamically import journal store."""
        assert "import('../journal/store.js')" in self.source

    def test_returns_both_results(self):
        """Should return results for both coach and journal."""
        assert "results.coach" in self.source
        assert "results.journal" in self.source

    def test_handles_import_errors(self):
        """Should catch errors from each module independently."""
        assert "catch" in self.source
        assert "e.message" in self.source

    def test_no_eager_imports(self):
        """Should NOT eagerly import either store at module level."""
        # Only dynamic imports inside the function
        assert "from '../coach/store.js'" not in self.source
        assert "from '../journal/store.js'" not in self.source


# ==================== Coach store forceSync ====================

class TestCoachForceSync:
    """Tests that coach/store.js has a forceSync export."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (JS_DIR / "coach" / "store.js").read_text()

    def test_exports_force_sync(self):
        assert "export async function forceSync(" in self.source

    def test_downloads_full_server_state(self):
        """Should GET without last_sync_time for full download."""
        assert "client_id=${clientId}`)" in self.source or \
               "client_id=${clientId}" in self.source

    def test_compares_timestamps(self):
        """Should compare local _lastModifiedAt with server _lastModified."""
        assert "_lastModifiedAt" in self.source
        assert "_lastModified" in self.source

    def test_uploads_client_winning_logs(self):
        assert "uploadLogs" in self.source

    def test_plans_server_authoritative(self):
        """Should overwrite local plans with server plans."""
        assert "data.plans" in self.source

    def test_resets_dirty_dates(self):
        assert "dirtyDates: []" in self.source

    def test_uses_earliest_date(self):
        """Should use earliestDate from response, not hardcoded window."""
        assert "data.earliestDate" in self.source

    def test_returns_stats(self):
        assert "uploaded" in self.source
        assert "accepted" in self.source
        assert "skipped" in self.source

    def test_logs_force_sync_start(self):
        assert "debugLog('coach-sync', 'force sync start'" in self.source

    def test_logs_force_sync_complete(self):
        assert "debugLog('coach-sync', 'force sync complete'" in self.source

    def test_logs_force_sync_error(self):
        assert "debugLog('coach-sync', 'force sync error'" in self.source

    def test_guards_offline(self):
        assert "navigator.onLine" in self.source

    def test_guards_concurrent_sync(self):
        assert "isSyncing.value" in self.source


# ==================== Journal store forceSync ====================

class TestJournalForceSync:
    """Tests that journal/store.js has a forceSync export."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (JS_DIR / "journal" / "store.js").read_text()

    def test_exports_force_sync(self):
        assert "export async function forceSync(" in self.source

    def test_downloads_full_server_state(self):
        """Should GET /full for complete server snapshot."""
        assert "full" in self.source

    def test_compares_tracker_timestamps(self):
        """Should compare tracker configs by _lastModifiedAt."""
        assert "uploadConfig" in self.source
        assert "acceptedConfig" in self.source

    def test_compares_entry_timestamps(self):
        """Should compare daily entries by _lastModifiedAt."""
        assert "uploadDays" in self.source
        assert "acceptedDays" in self.source

    def test_sets_base_version_from_server(self):
        """Client-winning uploads should use server's _version as _baseVersion."""
        assert "_baseVersion: server._version" in self.source or \
               "_baseVersion: serverEntry._version" in self.source

    def test_handles_toctou_conflicts(self):
        """Should accept server version for TOCTOU conflicts."""
        assert "conflicts" in self.source
        assert "entityType" in self.source

    def test_handles_deleted_trackers(self):
        assert "deletedTrackers" in self.source

    def test_resets_dirty_state(self):
        assert "dirtyTrackers: []" in self.source
        assert "dirtyEntries: []" in self.source

    def test_returns_stats(self):
        assert "uploaded" in self.source
        assert "accepted" in self.source

    def test_logs_force_sync_start(self):
        assert "debugLog('journal-sync', 'force sync start'" in self.source

    def test_logs_force_sync_complete(self):
        assert "debugLog('journal-sync', 'force sync complete'" in self.source

    def test_logs_force_sync_error(self):
        assert "debugLog('journal-sync', 'force sync error'" in self.source

    def test_clears_pending_conflicts(self):
        assert "pendingConflicts.value = []" in self.source

    def test_prunes_deleted_trackers(self):
        assert "pruneDeletedTrackers" in self.source


# ==================== Settings menu ====================

class TestSettingsForceSync:
    """Tests that settings.js integrates the Force Sync button."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (SHARED_DIR / "settings.js").read_text()

    def test_imports_force_sync(self):
        assert "forceSync" in self.source
        assert "force-sync.js" in self.source

    def test_imports_show_notification(self):
        assert "showNotification" in self.source

    def test_has_force_sync_button(self):
        assert "Force Sync" in self.source

    def test_has_confirmation_dialog(self):
        assert "confirm(" in self.source

    def test_has_offline_guard(self):
        assert "navigator.onLine" in self.source

    def test_has_loading_state(self):
        """Should disable button while syncing."""
        assert "isForceSyncing" in self.source
        assert "Syncing..." in self.source

    def test_has_result_notification(self):
        """Should show notification with results."""
        assert "Force Sync Complete" in self.source

    def test_shows_per_module_results(self):
        """Should report coach and journal results separately."""
        assert "results.coach" in self.source
        assert "results.journal" in self.source

    def test_button_disabled_while_syncing(self):
        assert "disabled" in self.source


# ==================== Static file serving ====================

class TestForceSyncStaticServing:
    """Tests that force-sync.js is served correctly."""

    @pytest.fixture(autouse=True)
    def setup_test_files(self, test_app, tmp_path):
        shared_dir = tmp_path / "public" / "js" / "shared"
        shared_dir.mkdir(parents=True, exist_ok=True)
        (shared_dir / "force-sync.js").write_text(
            "export async function forceSync() { return {}; }\n"
        )

    def test_force_sync_js_served(self, client):
        resp = client.get("/js/shared/force-sync.js")
        assert resp.status_code == 200
        assert "javascript" in resp.headers["content-type"]

    def test_force_sync_has_export(self, client):
        resp = client.get("/js/shared/force-sync.js")
        assert "forceSync" in resp.text
