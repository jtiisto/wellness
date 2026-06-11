"""Tests for analysis API endpoints in the unified wellness app."""
import pytest
from modules.analysis_db import (
    create_report, update_report_running, update_report_completed,
    update_report_failed, get_report, list_reports, has_active_report
)


# ==================== Query endpoints ====================

class TestApiListQueries:
    def test_returns_builtin_queries(self, client):
        resp = client.get("/api/analysis/queries")
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) >= 3

    def test_query_shape(self, client):
        resp = client.get("/api/analysis/queries")
        data = resp.json()
        for q in data:
            assert "id" in q
            assert "label" in q
            assert "description" in q
            assert "prompt_template" not in q

    def test_expected_builtin_query_ids(self, client):
        resp = client.get("/api/analysis/queries")
        ids = [q["id"] for q in resp.json()]
        assert "post_workout" in ids
        assert "pre_workout" in ids
        assert "weekly_review" in ids


# ==================== Submit query ====================

class TestApiSubmitQuery:
    def test_success(self, client, mock_claude_cli):
        resp = client.post("/api/analysis/reports", json={"query_id": "post_workout"})
        assert resp.status_code == 201
        data = resp.json()
        assert "id" in data
        assert data["status"] == "pending"

    def test_unknown_query_id(self, client):
        resp = client.post("/api/analysis/reports", json={"query_id": "nonexistent"})
        assert resp.status_code == 404

    def test_missing_query_id(self, client):
        resp = client.post("/api/analysis/reports", json={})
        assert resp.status_code == 422

    def test_conflict_when_active_report(self, client, analysis_initialized_db):
        create_report(analysis_initialized_db, "test", "Test", "prompt")
        update_report_running(analysis_initialized_db, 1)
        resp = client.post("/api/analysis/reports", json={"query_id": "post_workout"})
        assert resp.status_code == 409


# ==================== Get report ====================

class TestApiGetReport:
    def test_get_completed_report(self, client, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        update_report_completed(analysis_initialized_db, report_id, "## Done")
        resp = client.get(f"/api/analysis/reports/{report_id}")
        assert resp.status_code == 200
        data = resp.json()
        assert data["id"] == report_id
        assert data["response_markdown"] == "## Done"
        assert data["status"] == "completed"

    def test_get_pending_report(self, client, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        resp = client.get(f"/api/analysis/reports/{report_id}")
        assert resp.status_code == 200
        assert resp.json()["status"] == "pending"

    def test_get_failed_report(self, client, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        update_report_failed(analysis_initialized_db, report_id, "timeout")
        resp = client.get(f"/api/analysis/reports/{report_id}")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "failed"
        assert data["error_message"] == "timeout"

    def test_not_found(self, client):
        resp = client.get("/api/analysis/reports/99999")
        assert resp.status_code == 404


# ==================== List reports ====================

class TestApiListReports:
    def test_returns_reports(self, client, analysis_initialized_db):
        create_report(analysis_initialized_db, "a", "A", "p")
        create_report(analysis_initialized_db, "b", "B", "p")
        resp = client.get("/api/analysis/reports")
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 2

    def test_excludes_response_markdown(self, client, analysis_initialized_db):
        rid = create_report(analysis_initialized_db, "a", "A", "p")
        update_report_completed(analysis_initialized_db, rid, "## Big response")
        resp = client.get("/api/analysis/reports")
        for r in resp.json():
            assert "response_markdown" not in r

    def test_empty_list(self, client):
        resp = client.get("/api/analysis/reports")
        assert resp.status_code == 200
        assert resp.json() == []


# ==================== Delete report ====================

class TestApiDeleteReport:
    def test_delete_existing(self, client, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        resp = client.delete(f"/api/analysis/reports/{report_id}")
        assert resp.status_code == 200
        assert resp.json()["deleted"] is True

    def test_delete_not_found(self, client):
        resp = client.delete("/api/analysis/reports/99999")
        assert resp.status_code == 404

    def test_report_gone_after_delete(self, client, analysis_initialized_db):
        report_id = create_report(analysis_initialized_db, "test", "Test", "prompt")
        client.delete(f"/api/analysis/reports/{report_id}")
        resp = client.get(f"/api/analysis/reports/{report_id}")
        assert resp.status_code == 404


# ==================== Pending reports ====================

class TestApiPendingReports:
    def test_returns_pending_reports(self, client, analysis_initialized_db):
        create_report(analysis_initialized_db, "a", "A", "p")
        resp = client.get("/api/analysis/reports/pending")
        data = resp.json()
        assert len(data) == 1
        assert data[0]["status"] == "pending"

    def test_excludes_completed_and_failed(self, client, analysis_initialized_db):
        id1 = create_report(analysis_initialized_db, "a", "A", "p")
        id2 = create_report(analysis_initialized_db, "b", "B", "p")
        update_report_completed(analysis_initialized_db, id1, "done")
        update_report_failed(analysis_initialized_db, id2, "err")
        resp = client.get("/api/analysis/reports/pending")
        assert resp.json() == []

    def test_empty_when_no_reports(self, client):
        resp = client.get("/api/analysis/reports/pending")
        assert resp.status_code == 200
        assert resp.json() == []


@pytest.mark.integration
class TestRunReportLifecycle:
    """run_report must never leave a report wedged in pending/running — any
    failure (including marking it running) lands it in 'failed', so the
    single-active-report 409 guard cannot get stuck."""

    async def test_failure_in_running_mark_lands_in_failed(
        self, analysis_initialized_db, monkeypatch, tmp_path
    ):
        import modules.analysis as analysis
        from modules.analysis_db import get_report

        report_id = create_report(analysis_initialized_db, "t", "T", "prompt")

        def _boom(*a, **k):
            raise RuntimeError("db hiccup while marking running")

        monkeypatch.setattr(analysis, "update_report_running", _boom)
        await analysis.run_report(report_id, "prompt", None, None,
                                  analysis_initialized_db, tmp_path)

        report = get_report(analysis_initialized_db, report_id)
        assert report["status"] == "failed"

    async def test_spawn_holds_strong_reference_until_done(self):
        import asyncio
        from modules import background

        release = asyncio.Event()

        async def work():
            await release.wait()

        task = background.spawn(work())
        assert task in background._tasks  # strong ref held while running
        release.set()
        await task
        await asyncio.sleep(0)  # let the done-callback run
        assert task not in background._tasks  # discarded on completion
