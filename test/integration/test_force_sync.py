"""Integration tests for the force sync feature.

Validates that:
- force-sync.js orchestrator exists with correct structure
- Coach and journal stores export forceSync with proper instrumentation
- Tools menu integrates the Force Sync button
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

    def test_no_client_clock_comparison(self):
        """Force sync must NOT decide winners by comparing client/server
        timestamps — the unsafe client-clock LWW path is removed; the server is
        the only arbiter."""
        assert "resolveForceSyncLogs" not in self.source
        assert "withServerTokens" not in self.source

    def test_uploads_dirty_logs_via_base_tokens(self):
        """Force sync uploads the dirty set through the normal per-record
        base-token contract (selectLogsToUpload), not a force-overwrite."""
        assert "selectLogsToUpload" in self.source

    def test_plans_server_authoritative(self):
        """Should overwrite local plans with server plans."""
        assert "data.plans" in self.source

    def test_clears_dirty_dates_via_generation_check(self):
        assert "clearAppliedDirtyDates" in self.source

    def test_snapshots_generations_before_sync(self):
        assert "snapshotGens" in self.source
        assert "dirtyDateGenerations" in self.source

    def test_uses_earliest_date(self):
        """Should use earliestDate from response, not hardcoded window."""
        assert "data.earliestDate" in self.source

    def test_returns_uploaded_count(self):
        assert "uploaded: uploadedDates.length" in self.source

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
    """Tests that journal/store.js has a forceSync export wired against the
    optimistic-concurrency protocol.

    The post-LWW forceSync is server-arbitrated: full pull, then upload dirty
    records using the same `_baseLastModifiedAt` token contract as normal
    sync. The client never compares timestamps to decide a winner.
    """

    @pytest.fixture(autouse=True)
    def load_source(self):
        # The pure dirty-clearing + upload-payload logic now lives in
        # journal/sync-logic.js (unit-tested in test/js); read both files so
        # these wiring assertions find the strings regardless of which file they
        # landed in. The store keeps thin same-named wrappers.
        self.source = (
            (JS_DIR / "journal" / "store.js").read_text()
            + (JS_DIR / "journal" / "sync-logic.js").read_text()
        )

    def test_exports_force_sync(self):
        assert "export async function forceSync(" in self.source

    def test_pulls_full_server_state_via_delta_endpoint(self):
        """Journal's force sync pulls via pullServerChanges with `since=null`.
        The legacy /sync/full endpoint was removed; /sync/delta with no `since`
        is the unified full-pull entry point."""
        assert "pullServerChanges(clientId, null)" in self.source

    def test_uploads_dirty_with_base_token(self):
        """Uploads go through buildUploadPayload which attaches
        `_baseLastModifiedAt` (the server's prior stamp for each row) — never
        the client wall clock."""
        assert "buildUploadPayload()" in self.source
        # And buildUploadPayload itself sets the base token from the stored stamp
        assert "_baseLastModifiedAt = tracker.lastModifiedAt" in self.source
        assert "_baseLastModifiedAt = entry.lastModifiedAt" in self.source

    def test_applies_accepted_server_stamps(self):
        """After upload, server-stamped timestamps are folded back into local
        rows so subsequent edits use the correct base token."""
        assert "applyAccepted(result.acceptedTrackers" in self.source

    def test_applies_rejected_server_rows_for_in_cycle_recovery(self):
        """Rejected uploads carry the current `serverRow`; the client adopts
        it in-cycle rather than waiting for the next delta pull."""
        assert "applyRejected(result.rejectedTrackers" in self.source

    def test_no_client_side_timestamp_comparison(self):
        """The legacy forceSync compared `localTs > serverTs` to pick a
        winner. The new protocol delegates that decision to the server, so
        no such comparison exists in the journal store."""
        assert "localTs > serverTs" not in self.source
        assert "serverTs > localTs" not in self.source

    def test_no_legacy_base_version_token(self):
        """The integer `_baseVersion` token is fully retired in favor of the
        opaque `_baseLastModifiedAt` timestamp token. (The string is allowed
        to appear in code comments that explain the migration.)"""
        # No assignment of the field anywhere
        assert "_baseVersion:" not in self.source
        # No property access via dot notation
        import re
        assert re.search(r"\.\s*_baseVersion\b", self.source) is None
        # No bracket-style access
        assert "'_baseVersion'" not in self.source
        assert '"_baseVersion"' not in self.source

    def test_snapshots_generations_before_upload(self):
        """Generation counters are snapshotted so a concurrent edit during
        force sync stays dirty after the upload completes."""
        assert "snapshotTrackerGens" in self.source
        assert "snapshotEntryGens" in self.source

    def test_clears_dirty_state_via_generation_check(self):
        """Dirty state for resolved records is cleared only when the
        generation counter still matches the pre-upload snapshot."""
        assert "clearDirtyState(" in self.source

    def test_handles_deleted_trackers(self):
        """Server-side tracker deletions are honored — pullServerChanges
        routes through dropDeletedTrackerIds which also prunes the dirty
        entries belonging to those trackers."""
        assert "deletedTrackers" in self.source
        assert "dropDeletedTrackerIds" in self.source

    def test_returns_stats(self):
        """Force sync returns uploaded / accepted / conflicts counts. The
        `conflicts` name is preserved for the orchestrator UI even though
        under the new protocol it actually counts rejected-and-recovered
        rows rather than user-facing conflicts."""
        assert "uploaded" in self.source
        assert "accepted: uploaded" in self.source
        assert "conflicts:" in self.source

    def test_logs_force_sync_start(self):
        assert "debugLog('journal-sync', 'force sync start'" in self.source

    def test_logs_force_sync_complete(self):
        assert "debugLog('journal-sync', 'force sync complete'" in self.source

    def test_logs_force_sync_error(self):
        assert "debugLog('journal-sync', 'force sync error'" in self.source

    def test_prunes_deleted_trackers(self):
        assert "pruneDeletedTrackers" in self.source

    def test_guards_offline(self):
        assert "navigator.onLine" in self.source

    def test_guards_concurrent_sync(self):
        """Already-running sync should short-circuit, not double-pump."""
        assert "isSyncing.value" in self.source


# ==================== Tools menu ====================

class TestToolsMenuForceSync:
    """Tests that tools-menu.js integrates the Force Sync button."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (SHARED_DIR / "tools-menu.js").read_text()

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
