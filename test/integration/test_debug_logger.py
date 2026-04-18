"""Integration tests for the client-side debug logger feature.

Validates that:
- debug-log.js and tools-menu.js exist with correct structure
- Coach and journal stores import and call debugLog
- App shell integrates the tools menu
- CSS includes tools menu styles
"""
import re
from pathlib import Path

import pytest

PUBLIC_DIR = Path(__file__).parent.parent.parent / "public"
JS_DIR = PUBLIC_DIR / "js"
SHARED_DIR = JS_DIR / "shared"


# ==================== debug-log.js ====================

class TestDebugLogModule:
    """Tests for public/js/shared/debug-log.js structure."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (SHARED_DIR / "debug-log.js").read_text()

    def test_file_exists(self):
        assert (SHARED_DIR / "debug-log.js").is_file()

    def test_uses_localforage_create_instance(self):
        """Should use createInstance to avoid conflicts with other stores."""
        assert "localforage.createInstance" in self.source

    def test_store_name_is_debug_log(self):
        """Store should use 'debug_log' storeName."""
        assert "debug_log" in self.source

    def test_exports_log_function(self):
        assert "export async function log(" in self.source

    def test_exports_get_debug_log(self):
        assert "export async function getDebugLog(" in self.source

    def test_exports_download_debug_log(self):
        assert "export async function downloadDebugLog(" in self.source

    def test_has_ttl_pruning(self):
        """Entries older than TTL should be pruned on write."""
        assert "TTL_MS" in self.source or "TTL" in self.source
        # Check for filtering logic
        assert ".filter(" in self.source

    def test_has_max_entries_cap(self):
        """Should cap entries at a maximum."""
        assert "MAX_ENTRIES" in self.source or "500" in self.source

    def test_log_never_throws(self):
        """log() should catch errors to avoid breaking the app."""
        assert "catch" in self.source

    def test_download_creates_blob(self):
        """Download should create a Blob for file download."""
        assert "Blob" in self.source

    def test_download_filename_format(self):
        """Download filename should include 'debug-log' prefix."""
        assert "debug-log-" in self.source


# ==================== tools-menu.js ====================

class TestToolsMenuModule:
    """Tests for public/js/shared/tools-menu.js structure."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (SHARED_DIR / "tools-menu.js").read_text()

    def test_file_exists(self):
        assert (SHARED_DIR / "tools-menu.js").is_file()

    def test_imports_download_debug_log(self):
        assert "downloadDebugLog" in self.source

    def test_imports_export_all_data(self):
        assert "exportAllData" in self.source

    def test_exports_tools_menu(self):
        assert "export function ToolsMenu(" in self.source

    def test_uses_modal_overlay(self):
        """Should reuse existing modal-overlay styling."""
        assert "modal-overlay" in self.source

    def test_uses_modal_content(self):
        assert "modal-content" in self.source

    def test_has_close_button(self):
        assert "onClose" in self.source

    def test_has_debug_log_button(self):
        """Should have a button to save the debug log."""
        assert "Save Debug Log" in self.source

    def test_has_export_data_button(self):
        """Should have a button to export all data."""
        assert "Export All Data" in self.source


# ==================== Coach store instrumentation ====================

class TestCoachStoreInstrumentation:
    """Tests that coach/store.js has debug log instrumentation."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (JS_DIR / "coach" / "store.js").read_text()

    def test_imports_debug_log(self):
        assert "from '../shared/debug-log.js'" in self.source

    def test_imports_as_debug_log(self):
        """Should import log as debugLog to avoid name collision."""
        assert "log as debugLog" in self.source

    def test_logs_sync_start(self):
        assert "debugLog('coach-sync', 'sync start'" in self.source

    def test_logs_upload_attempt(self):
        assert "debugLog('coach-sync', 'upload attempt'" in self.source

    def test_logs_upload_failure(self):
        assert "debugLog('coach-sync', 'upload failure'" in self.source

    def test_logs_upload_success(self):
        assert "debugLog('coach-sync', 'upload success'" in self.source

    def test_logs_download_attempt(self):
        assert "debugLog('coach-sync', 'download attempt'" in self.source

    def test_logs_download_success(self):
        assert "debugLog('coach-sync', 'download success'" in self.source

    def test_logs_server_data_applied(self):
        assert "debugLog('coach-sync', 'server data applied'" in self.source

    def test_logs_sync_error(self):
        assert "debugLog('coach-sync', 'sync error'" in self.source


# ==================== Journal store instrumentation ====================

class TestJournalStoreInstrumentation:
    """Tests that journal/store.js has debug log instrumentation."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (JS_DIR / "journal" / "store.js").read_text()

    def test_imports_debug_log(self):
        assert "from '../shared/debug-log.js'" in self.source

    def test_imports_as_debug_log(self):
        assert "log as debugLog" in self.source

    def test_logs_sync_start(self):
        assert "debugLog('journal-sync', 'sync start'" in self.source

    def test_logs_delta_sync(self):
        assert "debugLog('journal-sync', 'delta sync'" in self.source

    def test_logs_full_sync(self):
        assert "debugLog('journal-sync', 'full sync" in self.source

    def test_logs_server_data_received(self):
        assert "debugLog('journal-sync', 'server data received'" in self.source

    def test_logs_conflict_detection(self):
        assert "debugLog('journal-sync', 'conflict detection'" in self.source

    def test_logs_auto_merge(self):
        assert "debugLog('journal-sync', 'auto-merge applied'" in self.source

    def test_logs_upload_attempt(self):
        assert "debugLog('journal-sync', 'upload attempt'" in self.source

    def test_logs_sync_error(self):
        assert "debugLog('journal-sync', 'sync error'" in self.source


# ==================== data-export.js ====================

class TestDataExportModule:
    """Tests for public/js/shared/data-export.js structure."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (SHARED_DIR / "data-export.js").read_text()

    def test_file_exists(self):
        assert (SHARED_DIR / "data-export.js").is_file()

    def test_uses_localforage_create_instance(self):
        """Should use createInstance for read-only access to existing stores."""
        assert "localforage.createInstance" in self.source

    def test_journal_store_config(self):
        """Should match the JournalApp store name."""
        assert "JournalApp" in self.source
        assert "journal_data" in self.source

    def test_coach_store_config(self):
        """Should match the CoachApp store name."""
        assert "CoachApp" in self.source
        assert "coach_data" in self.source

    def test_exports_export_all_data(self):
        assert "export async function exportAllData(" in self.source

    def test_reads_journal_keys(self):
        """Should read all 5 journal store keys."""
        for key in ['tracker_config', 'daily_logs', 'app_metadata', 'client_id', 'expanded_categories']:
            assert key in self.source, f"Missing journal key: {key}"

    def test_reads_coach_keys(self):
        """Should read all 4 coach store keys."""
        for key in ['workout_plans', 'workout_logs', 'coach_metadata', 'coach_client_id']:
            assert key in self.source, f"Missing coach key: {key}"

    def test_reads_app_state(self):
        """Should read the active module from localStorage."""
        assert "wellness_active_module" in self.source

    def test_does_not_read_debug_log(self):
        """Debug log should NOT be included in the data export."""
        assert "DebugLog" not in self.source
        assert "debug_log" not in self.source

    def test_has_export_version(self):
        """Should include a version field for forward compatibility."""
        assert "version" in self.source

    def test_parallel_reads(self):
        """Should read all stores in parallel with Promise.all."""
        assert "Promise.all" in self.source

    def test_downloads_json(self):
        """Should trigger a JSON file download."""
        assert "application/json" in self.source
        assert "Blob" in self.source

    def test_filename_format(self):
        """Filename should use wellness-export prefix."""
        assert "wellness-export-" in self.source

    def test_error_handling(self):
        """Should catch errors and return a result object."""
        assert "catch" in self.source

    def test_read_only(self):
        """Should never write to any store — only getItem, no setItem."""
        assert "setItem" not in self.source


# ==================== App shell integration ====================

class TestAppShellIntegration:
    """Tests that app.js integrates the tools menu."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (JS_DIR / "app.js").read_text()

    def test_imports_tools_menu(self):
        assert "import { ToolsMenu }" in self.source

    def test_has_tools_signal(self):
        assert "toolsOpen" in self.source

    def test_has_tools_icon(self):
        """Should have a wrench/tools icon in ICONS map."""
        assert "tools:" in self.source

    def test_has_tools_button_in_navbar(self):
        assert "tools-btn" in self.source

    def test_renders_tools_menu(self):
        assert "ToolsMenu" in self.source


# ==================== CSS ====================

class TestToolsCss:
    """Tests that styles.css includes tools menu styles."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (PUBLIC_DIR / "styles.css").read_text()

    def test_has_tools_btn_style(self):
        assert ".tools-btn" in self.source

    def test_tools_btn_sizes_as_peer(self):
        """Tools button shares the flex: 1 sizing of other nav buttons so
        the bottom row stays symmetric regardless of module count."""
        match = re.search(r'\.tools-btn\s*\{[^}]+\}', self.source)
        assert match is not None
        assert "flex: 1" in match.group()

    def test_has_tools_menu_style(self):
        assert ".tools-menu" in self.source

    def test_has_tools_list_style(self):
        assert ".tools-list" in self.source

    def test_has_tools_item_style(self):
        assert ".tools-item" in self.source

    def test_tools_item_has_tap_target(self):
        """Tools items should meet minimum tap target size."""
        match = re.search(r'\.tools-item\s*\{[^}]+\}', self.source)
        assert match is not None
        assert "tap-target" in match.group() or "44px" in match.group()


# ==================== Static file serving ====================

class TestDebugLogStaticServing:
    """Tests that the new JS files are served correctly."""

    @pytest.fixture(autouse=True)
    def setup_test_files(self, test_app, tmp_path):
        """Add new shared JS files to the test public directory."""
        shared_dir = tmp_path / "public" / "js" / "shared"
        shared_dir.mkdir(parents=True, exist_ok=True)
        (shared_dir / "debug-log.js").write_text(
            "export async function log() {}\n"
            "export async function getDebugLog() { return []; }\n"
            "export async function downloadDebugLog() {}\n"
        )
        (shared_dir / "tools-menu.js").write_text(
            "export function ToolsMenu() {}\n"
        )
        (shared_dir / "data-export.js").write_text(
            "export async function exportAllData() {}\n"
        )

    def test_debug_log_js_served(self, client):
        resp = client.get("/js/shared/debug-log.js")
        assert resp.status_code == 200
        assert "javascript" in resp.headers["content-type"]

    def test_tools_menu_js_served(self, client):
        resp = client.get("/js/shared/tools-menu.js")
        assert resp.status_code == 200
        assert "javascript" in resp.headers["content-type"]

    def test_debug_log_has_log_export(self, client):
        resp = client.get("/js/shared/debug-log.js")
        assert "export" in resp.text
        assert "log" in resp.text

    def test_tools_menu_has_export(self, client):
        resp = client.get("/js/shared/tools-menu.js")
        assert "ToolsMenu" in resp.text

    def test_data_export_js_served(self, client):
        resp = client.get("/js/shared/data-export.js")
        assert resp.status_code == 200
        assert "javascript" in resp.headers["content-type"]

    def test_data_export_has_export_function(self, client):
        resp = client.get("/js/shared/data-export.js")
        assert "exportAllData" in resp.text
