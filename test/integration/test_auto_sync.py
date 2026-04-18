"""Integration tests for the auto-sync refactor.

Validates that:
- sync-scheduler.js exports the SyncScheduler class with correct API
- Coach and journal stores import and instantiate the scheduler
- Mutation functions call scheduler.scheduleUpload()
- Stores no longer have inline event listeners or manual polling code
- Sync indicator is read-only (no onClick, no cursor: pointer)
- triggerSync error handling delegates to scheduler (no showNotification in catch)
- Force sync resets scheduler retry state
- JournalView no longer calls triggerSync manually
"""
from pathlib import Path

import pytest

PUBLIC_DIR = Path(__file__).parent.parent.parent / "public"
JS_DIR = PUBLIC_DIR / "js"
SHARED_DIR = JS_DIR / "shared"
COACH_DIR = JS_DIR / "coach"
JOURNAL_DIR = JS_DIR / "journal"


# ==================== sync-scheduler.js ====================

class TestSyncSchedulerModule:
    """Tests for public/js/shared/sync-scheduler.js structure."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (SHARED_DIR / "sync-scheduler.js").read_text()

    def test_file_exists(self):
        assert (SHARED_DIR / "sync-scheduler.js").is_file()

    def test_exports_sync_scheduler_class(self):
        assert "export class SyncScheduler" in self.source

    def test_imports_debug_log(self):
        assert "debug-log.js" in self.source

    def test_imports_show_notification(self):
        assert "showNotification" in self.source
        assert "notifications.js" in self.source

    def test_constructor_accepts_name(self):
        assert "name" in self.source

    def test_constructor_accepts_sync_fn(self):
        assert "syncFn" in self.source

    def test_constructor_accepts_poll_check_fn(self):
        assert "pollCheckFn" in self.source

    def test_has_start_method(self):
        assert "start()" in self.source

    def test_has_stop_method(self):
        assert "stop()" in self.source

    def test_has_schedule_upload_method(self):
        assert "scheduleUpload()" in self.source

    def test_has_request_sync_method(self):
        assert "requestSync()" in self.source

    def test_has_reset_retry_method(self):
        assert "resetRetry()" in self.source

    def test_has_debounce_timer(self):
        assert "_debounceTimer" in self.source

    def test_has_poll_timer(self):
        assert "_pollTimer" in self.source

    def test_has_retry_timer(self):
        assert "_retryTimer" in self.source

    def test_has_retry_attempt_counter(self):
        assert "_retryAttempt" in self.source

    def test_listens_for_online_event(self):
        assert "'online'" in self.source

    def test_listens_for_offline_event(self):
        assert "'offline'" in self.source

    def test_listens_for_visibility_change(self):
        assert "visibilitychange" in self.source

    def test_classifies_type_error_as_network(self):
        """TypeError from fetch should be classified as network error."""
        assert "TypeError" in self.source

    def test_classifies_abort_error_as_network(self):
        assert "AbortError" in self.source

    def test_shows_notification_for_server_errors(self):
        """Server errors should trigger a toast notification."""
        assert "showNotification" in self.source
        assert "'server'" in self.source

    def test_exponential_backoff(self):
        """Retry delay should use exponential backoff."""
        assert "Math.pow(2" in self.source or "2 **" in self.source

    def test_max_retry_cap(self):
        """Retry delay should be capped at maxRetryMs."""
        assert "Math.min" in self.source
        assert "_maxRetryMs" in self.source

    def test_default_debounce_ms(self):
        assert "2500" in self.source

    def test_default_poll_interval_ms(self):
        assert "30000" in self.source

    def test_default_base_retry_ms(self):
        assert "5000" in self.source

    def test_default_max_retry_ms(self):
        assert "120000" in self.source

    def test_request_sync_clears_debounce(self):
        """requestSync should clear pending debounce timer."""
        assert "_clearDebounce" in self.source

    def test_request_sync_clears_retry(self):
        """requestSync should clear pending retry timer."""
        assert "_clearRetry" in self.source

    def test_reentry_guard_checks_is_syncing(self):
        """Should skip sync if already syncing."""
        assert "_getIsSyncing()" in self.source

    def test_handles_conflicts_reason(self):
        """Conflicts should be treated as handled (no retry)."""
        assert "'conflicts'" in self.source


# ==================== Coach store scheduler integration ====================

class TestCoachStoreScheduler:
    """Tests that coach/store.js integrates the SyncScheduler."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (COACH_DIR / "store.js").read_text()

    def test_imports_sync_scheduler(self):
        assert "import { SyncScheduler } from '../shared/sync-scheduler.js'" in self.source

    def test_exports_scheduler_instance(self):
        assert "export const scheduler = new SyncScheduler(" in self.source

    def test_scheduler_name_is_coach(self):
        assert "name: 'coach'" in self.source

    def test_scheduler_uses_trigger_sync(self):
        assert "syncFn: triggerSync" in self.source

    def test_scheduler_has_poll_check_fn(self):
        """Coach should use pollCheckFn for plans-version optimization."""
        assert "pollCheckFn" in self.source

    def test_poll_check_fn_checks_plans_version(self):
        """pollCheckFn should hit the /plans-version endpoint."""
        assert "plans-version" in self.source

    def test_no_inline_online_listener(self):
        """Should not have inline window.addEventListener('online')."""
        assert "window.addEventListener('online'" not in self.source

    def test_no_inline_offline_listener(self):
        assert "window.addEventListener('offline'" not in self.source

    def test_no_inline_visibility_listener(self):
        assert "document.addEventListener('visibilitychange'" not in self.source

    def test_no_set_interval_polling(self):
        """Should not have manual setInterval polling."""
        assert "setInterval(" not in self.source

    def test_no_start_polling_function(self):
        assert "function startPolling" not in self.source

    def test_no_stop_polling_function(self):
        assert "function stopPolling" not in self.source

    def test_init_calls_scheduler_request_sync(self):
        assert "scheduler.requestSync()" in self.source

    def test_init_calls_scheduler_start(self):
        assert "scheduler.start()" in self.source

    def test_trigger_sync_not_exported(self):
        """triggerSync should be a private function (not exported)."""
        assert "export async function triggerSync(" not in self.source
        assert "async function triggerSync(" in self.source

    def test_trigger_sync_no_show_notification(self):
        """triggerSync catch should NOT call showNotification (scheduler handles it)."""
        # Find the catch block of triggerSync — it should return { error } not call showNotification
        source = self.source
        catch_idx = source.find("'sync error'")
        assert catch_idx > 0
        # Check the next 200 chars after sync error log — should have error field, not showNotification
        catch_block = source[catch_idx:catch_idx + 200]
        assert "error }" in catch_block or "error}" in catch_block
        assert "showNotification" not in catch_block


class TestCoachMutationsScheduleUpload:
    """Tests that coach mutation functions call scheduler.scheduleUpload()."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (COACH_DIR / "store.js").read_text()

    def _get_function_body(self, func_name):
        """Extract a function's body from source."""
        start = self.source.find(f"function {func_name}(")
        if start == -1:
            start = self.source.find(f"function {func_name} (")
        assert start >= 0, f"Function {func_name} not found"
        # Find the closing brace by counting braces
        brace_count = 0
        body_start = self.source.index("{", start)
        for i in range(body_start, len(self.source)):
            if self.source[i] == "{":
                brace_count += 1
            elif self.source[i] == "}":
                brace_count -= 1
                if brace_count == 0:
                    return self.source[body_start:i + 1]
        return ""

    def test_update_log_schedules_upload(self):
        body = self._get_function_body("updateLog")
        assert "scheduler.scheduleUpload()" in body

    def test_update_session_feedback_schedules_upload(self):
        body = self._get_function_body("updateSessionFeedback")
        assert "scheduler.scheduleUpload()" in body


class TestCoachForceSyncResetRetry:
    """Tests that coach forceSync resets scheduler retry state."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (COACH_DIR / "store.js").read_text()

    def test_force_sync_resets_retry(self):
        # Find forceSync function and check for resetRetry
        start = self.source.find("export async function forceSync(")
        assert start >= 0
        body = self.source[start:]
        assert "scheduler.resetRetry()" in body


# ==================== Journal store scheduler integration ====================

class TestJournalStoreScheduler:
    """Tests that journal/store.js integrates the SyncScheduler."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (JOURNAL_DIR / "store.js").read_text()

    def test_imports_sync_scheduler(self):
        assert "import { SyncScheduler } from '../shared/sync-scheduler.js'" in self.source

    def test_exports_scheduler_instance(self):
        assert "export const scheduler = new SyncScheduler(" in self.source

    def test_scheduler_name_is_journal(self):
        assert "name: 'journal'" in self.source

    def test_scheduler_uses_trigger_sync(self):
        assert "syncFn: triggerSync" in self.source

    def test_scheduler_no_poll_check_fn(self):
        """Journal should NOT have a pollCheckFn (no lightweight version endpoint)."""
        # The scheduler constructor call should not include pollCheckFn
        start = self.source.find("new SyncScheduler(")
        end = self.source.find("});", start)
        constructor_call = self.source[start:end]
        assert "pollCheckFn" not in constructor_call

    def test_init_calls_scheduler_request_sync(self):
        assert "scheduler.requestSync()" in self.source

    def test_init_calls_scheduler_start(self):
        assert "scheduler.start()" in self.source

    def test_trigger_sync_no_show_notification_in_catch(self):
        """triggerSync catch should NOT call showNotification (scheduler handles it)."""
        source = self.source
        catch_idx = source.find("'sync error'")
        assert catch_idx > 0
        catch_block = source[catch_idx:catch_idx + 200]
        assert "error }" in catch_block or "error}" in catch_block
        assert "showNotification" not in catch_block

    def test_conflict_notifications_preserved(self):
        """Auto-merge and conflict notifications should still exist."""
        assert "'Data Merged'" in self.source
        assert "'Sync Conflict'" in self.source


class TestJournalMutationsScheduleUpload:
    """Tests that journal mutation functions call scheduler.scheduleUpload()."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (JOURNAL_DIR / "store.js").read_text()

    def _get_function_body(self, func_name):
        start = self.source.find(f"function {func_name}(")
        if start == -1:
            start = self.source.find(f"function {func_name} (")
        assert start >= 0, f"Function {func_name} not found"
        brace_count = 0
        body_start = self.source.index("{", start)
        for i in range(body_start, len(self.source)):
            if self.source[i] == "{":
                brace_count += 1
            elif self.source[i] == "}":
                brace_count -= 1
                if brace_count == 0:
                    return self.source[body_start:i + 1]
        return ""

    def test_add_tracker_schedules_upload(self):
        body = self._get_function_body("addTracker")
        assert "scheduler.scheduleUpload()" in body

    def test_update_tracker_schedules_upload(self):
        body = self._get_function_body("updateTracker")
        assert "scheduler.scheduleUpload()" in body

    def test_delete_tracker_schedules_upload(self):
        body = self._get_function_body("deleteTracker")
        assert "scheduler.scheduleUpload()" in body

    def test_update_entry_schedules_upload(self):
        body = self._get_function_body("updateEntry")
        assert "scheduler.scheduleUpload()" in body


class TestJournalForceSyncResetRetry:
    """Tests that journal forceSync resets scheduler retry state."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (JOURNAL_DIR / "store.js").read_text()

    def test_force_sync_resets_retry(self):
        start = self.source.find("export async function forceSync(")
        assert start >= 0
        body = self.source[start:]
        assert "scheduler.resetRetry()" in body


# ==================== JournalView no manual sync ====================

class TestJournalViewNoManualSync:
    """Tests that JournalView no longer calls triggerSync manually."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (JOURNAL_DIR / "JournalView.js").read_text()

    def test_no_trigger_sync_import(self):
        assert "triggerSync" not in self.source

    def test_imports_initialize_store(self):
        assert "initializeStore" in self.source


# ==================== Read-only sync indicator ====================

class TestSyncIndicatorReadOnly:
    """Tests that the sync indicator is read-only (no click behavior)."""

    @pytest.fixture(autouse=True)
    def load_shared_header(self):
        self.header_source = (SHARED_DIR / "header.js").read_text()

    def test_no_on_click_prop(self):
        """SyncIndicator should not accept onClick prop."""
        assert "onClick" not in self.header_source

    def test_tooltip_no_click_to_sync(self):
        assert "click to sync" not in self.header_source.lower()

    def test_tooltip_shows_offline(self):
        assert "'Offline'" in self.header_source

    def test_tooltip_shows_synced(self):
        assert "'Synced'" in self.header_source

    def test_label_shows_pending(self):
        # Post-5A the label is a short state word — "Pending" rather than
        # the earlier "Pending changes" tooltip copy.
        assert "'Pending'" in self.header_source


class TestCoachViewNoSyncClick:
    """Tests that CoachView no longer passes onClick to sync indicator."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (COACH_DIR / "CoachView.js").read_text()

    def test_no_trigger_sync_import(self):
        assert "triggerSync" not in self.source

    def test_no_on_click_on_indicator(self):
        assert "onClick" not in self.source


class TestJournalHeaderNoSyncClick:
    """Tests that journal Header no longer has sync click behavior.

    Journal Header used to inline the sync-indicator markup; post-5A it
    delegates to the shared SyncIndicator component, which makes the
    no-onClick contract structural — there's no inline indicator here
    at all to attach a handler to."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (JOURNAL_DIR / "components" / "Header.js").read_text()

    def test_no_trigger_sync_import(self):
        assert "triggerSync" not in self.source

    def test_no_handle_sync_click(self):
        assert "handleSyncClick" not in self.source

    def test_uses_shared_sync_indicator(self):
        """Delegates to the shared component instead of inlining markup —
        the no-click contract is then enforced by TestSyncIndicatorReadOnly."""
        assert "SyncIndicator" in self.source
        assert "sync-indicator" not in self.source  # no inline class literal

    def test_tooltip_no_click_to_sync(self):
        assert "click to sync" not in self.source.lower()


class TestSyncIndicatorCss:
    """Tests that sync indicator CSS has no pointer cursor."""

    @pytest.fixture(autouse=True)
    def load_source(self):
        self.source = (PUBLIC_DIR / "styles.css").read_text()

    def test_no_cursor_pointer(self):
        """sync-indicator should not have cursor: pointer."""
        # Find the .sync-indicator rule
        idx = self.source.find(".sync-indicator {")
        assert idx >= 0
        rule_end = self.source.find("}", idx)
        rule = self.source[idx:rule_end]
        assert "cursor" not in rule

    def test_no_hover_rule(self):
        """Should not have a .sync-indicator:hover rule."""
        assert ".sync-indicator:hover" not in self.source


# ==================== Static file serving ====================

class TestSyncSchedulerStaticServing:
    """Tests that sync-scheduler.js is served correctly."""

    @pytest.fixture(autouse=True)
    def setup_test_files(self, test_app, tmp_path):
        shared_dir = tmp_path / "public" / "js" / "shared"
        shared_dir.mkdir(parents=True, exist_ok=True)
        (shared_dir / "sync-scheduler.js").write_text(
            "export class SyncScheduler {}\n"
        )

    def test_sync_scheduler_js_served(self, client):
        resp = client.get("/js/shared/sync-scheduler.js")
        assert resp.status_code == 200
        assert "javascript" in resp.headers["content-type"]
