"""Tests for analysis database functions in the unified wellness app."""
from modules.analysis_db import (
    init_database, create_report, update_report_running,
    update_report_completed, update_report_failed, get_report,
    list_reports, get_pending_reports, delete_report, has_active_report,
    recover_stale_reports, get_utc_now, get_db
)


# ==================== get_utc_now ====================

class TestGetUtcNow:
    def test_format_ends_with_z(self):
        ts = get_utc_now()
        assert ts.endswith("Z"), f"Expected UTC timestamp ending in Z, got: {ts}"

    def test_no_timezone_offset(self):
        ts = get_utc_now()
        assert "+00:00" not in ts

    def test_is_iso_format(self):
        ts = get_utc_now()
        from datetime import datetime
        parsed = datetime.fromisoformat(ts.replace("Z", "+00:00"))
        assert parsed is not None


# ==================== init_database ====================

class TestInitDatabase:
    def test_creates_reports_table(self, tmp_analysis_db):
        init_database(str(tmp_analysis_db))
        with get_db(str(tmp_analysis_db)) as conn:
            row = conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='reports'"
            ).fetchone()
            assert row is not None

    def test_idempotent(self, tmp_analysis_db):
        init_database(str(tmp_analysis_db))
        init_database(str(tmp_analysis_db))  # Should not raise

    def test_creates_status_index(self, tmp_analysis_db):
        init_database(str(tmp_analysis_db))
        with get_db(str(tmp_analysis_db)) as conn:
            row = conn.execute(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_reports_status'"
            ).fetchone()
            assert row is not None


# ==================== create_report ====================

class TestCreateReport:
    def test_returns_positive_id(self, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "post_workout", "Test Label", "Test prompt")
        assert isinstance(report_id, int)
        assert report_id > 0

    def test_initial_status_is_pending(self, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "post_workout", "Test Label", "Test prompt")
        report = get_report(analysis_initialized_db, report_id)
        assert report["status"] == "pending"


# ==================== update_report_running ====================

class TestUpdateReportRunning:
    def test_sets_status_to_running(self, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        update_report_running(analysis_initialized_db, report_id)
        report = get_report(analysis_initialized_db, report_id)
        assert report["status"] == "running"


# ==================== update_report_completed ====================

class TestUpdateReportCompleted:
    def test_sets_status_and_response(self, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        update_report_completed(analysis_initialized_db, report_id, "## Result\nDone.")
        report = get_report(analysis_initialized_db, report_id)
        assert report["status"] == "completed"
        assert report["response_markdown"] == "## Result\nDone."

    def test_sets_completed_at(self, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        update_report_completed(analysis_initialized_db, report_id, "done")
        report = get_report(analysis_initialized_db, report_id)
        assert report["completed_at"] is not None
        assert report["completed_at"].endswith("Z")


# ==================== update_report_failed ====================

class TestUpdateReportFailed:
    def test_sets_status_and_error(self, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        update_report_failed(analysis_initialized_db, report_id, "Timeout error")
        report = get_report(analysis_initialized_db, report_id)
        assert report["status"] == "failed"
        assert report["error_message"] == "Timeout error"


# ==================== delete_report ====================

class TestDeleteReport:
    def test_delete_existing(self, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        assert delete_report(analysis_initialized_db, report_id) is True
        assert get_report(analysis_initialized_db, report_id) is None

    def test_delete_nonexistent(self, analysis_initialized_db):
        assert delete_report(analysis_initialized_db, 99999) is False


# ==================== has_active_report ====================

class TestHasActiveReport:
    def test_false_when_empty(self, analysis_initialized_db):
        assert has_active_report(analysis_initialized_db) is False

    def test_true_with_pending(self, analysis_initialized_db):
        create_report(analysis_initialized_db, "test", "Test", "prompt")
        assert has_active_report(analysis_initialized_db) is True

    def test_false_after_completed(self, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        update_report_completed(analysis_initialized_db, report_id, "done")
        assert has_active_report(analysis_initialized_db) is False


# ==================== Full lifecycle ====================

class TestReportLifecycle:
    def test_pending_to_running_to_completed(self, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        assert get_report(analysis_initialized_db, report_id)["status"] == "pending"
        update_report_running(analysis_initialized_db, report_id)
        assert get_report(analysis_initialized_db, report_id)["status"] == "running"
        update_report_completed(analysis_initialized_db, report_id, "result")
        report = get_report(analysis_initialized_db, report_id)
        assert report["status"] == "completed"
        assert report["response_markdown"] == "result"


# ==================== recover_stale_reports ====================

class TestRecoverStaleReports:
    def test_running_reports_marked_failed(self, analysis_initialized_db):
        """RUNNING reports should be marked FAILED after recovery."""
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        update_report_running(analysis_initialized_db, report_id)
        assert get_report(analysis_initialized_db, report_id)["status"] == "running"

        recover_stale_reports(analysis_initialized_db)

        report = get_report(analysis_initialized_db, report_id)
        assert report["status"] == "failed"
        assert report["error_message"] == "Server restarted during execution"
        assert report["completed_at"] is not None

    def test_pending_reports_not_touched(self, analysis_initialized_db):
        """PENDING reports should remain PENDING after recovery."""
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        assert get_report(analysis_initialized_db, report_id)["status"] == "pending"

        recover_stale_reports(analysis_initialized_db)

        assert get_report(analysis_initialized_db, report_id)["status"] == "pending"

    def test_has_active_report_false_after_recovery(self, analysis_initialized_db):
        """After recovery, has_active_report should return False if only RUNNING existed."""
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        update_report_running(analysis_initialized_db, report_id)
        assert has_active_report(analysis_initialized_db) is True

        recover_stale_reports(analysis_initialized_db)

        assert has_active_report(analysis_initialized_db) is False
