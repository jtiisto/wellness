"""E2E tests for offline resilience: app loads and displays cached data after
going offline, including after a full page reload via service worker.

Tests use the /wellness/ prefix so the service worker (scoped to /wellness/)
controls the page and can serve cached assets offline.

Tests cover:
- App shell loads offline using service worker cache + localStorage modules list
- Journal displays cached data offline after reload
- Coach displays cached data offline after reload
- Analysis falls back to cached report history offline
- Analysis can view a cached report offline
- Analysis submit shows toast when offline
"""
import sqlite3
from datetime import datetime, timezone

import pytest
import requests as http_requests

from pages.app_shell import AppShellPage
from pages.journal import JournalPage
from pages.coach import CoachPage

pytestmark = pytest.mark.e2e


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _wellness_url(app_server):
    """Return the base URL with /wellness/ prefix for service worker scope."""
    return app_server["url"] + "/wellness/"


def _goto_online(page, app_server):
    """Navigate to the app online and wait for shell + service worker activation."""
    page.goto(_wellness_url(app_server))
    page.wait_for_selector(".shell", timeout=10000)
    # Wait for the service worker to be active and controlling the page
    page.evaluate("""() => new Promise((resolve) => {
        if (navigator.serviceWorker.controller) {
            resolve(true);
            return;
        }
        navigator.serviceWorker.addEventListener('controllerchange', () => resolve(true));
        // Fallback timeout — SW may already be active but not yet controlling
        setTimeout(() => resolve(false), 10000);
    })""")


def _reload_offline(page):
    """Reload the page while offline. Service worker serves from cache."""
    try:
        page.reload(wait_until="domcontentloaded", timeout=15000)
    except Exception:
        # Service worker may serve from cache but the load event might not fire
        pass
    page.wait_for_selector(".shell", timeout=15000)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def seeded_analysis_report(app_server):
    """Insert a completed analysis report directly into the database."""
    db_path = app_server["db_dir"] / "analysis.db"
    conn = sqlite3.connect(db_path)
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    conn.execute("""
        INSERT INTO reports (query_id, query_label, prompt_sent, response_markdown,
                             status, created_at, completed_at)
        VALUES (?, ?, ?, ?, 'completed', ?, ?)
    """, ("test_query", "Test Report", "test prompt", "## Test\nThis is a test report.",
          now, now))
    conn.commit()
    report_id = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
    conn.close()
    return report_id


# ---------------------------------------------------------------------------
# App Shell Offline
# ---------------------------------------------------------------------------

class TestAppShellOffline:
    def test_app_loads_offline_after_prior_visit(self, page, app_server):
        """App shell should load offline using SW cache + localStorage modules list."""
        _goto_online(page, app_server)
        shell = AppShellPage(page)
        labels = shell.get_nav_labels()
        assert len(labels) >= 2, "Should have multiple nav tabs online"

        # Go offline and reload via service worker
        page.context.set_offline(True)
        _reload_offline(page)

        labels_offline = shell.get_nav_labels()
        assert labels_offline == labels, "Offline nav should match online nav"

        page.context.set_offline(False)

    def test_module_navigation_works_offline(self, page, app_server):
        """Tab navigation should work offline (client-side routing)."""
        _goto_online(page, app_server)
        shell = AppShellPage(page)

        page.context.set_offline(True)

        # Navigate between tabs — should work via cached JS modules
        shell.navigate_to("Coach")
        assert shell.get_active_module() == "Coach"
        shell.navigate_to("Journal")
        assert shell.get_active_module() == "Journal"

        page.context.set_offline(False)


# ---------------------------------------------------------------------------
# Journal Offline
# ---------------------------------------------------------------------------

class TestJournalOffline:
    def test_journal_shows_cached_data_after_offline_reload(self, page, app_server, seeded_journal):
        """Journal should display cached tracker data after going offline and reloading."""
        _goto_online(page, app_server)
        shell = AppShellPage(page)
        shell.navigate_to("Journal")
        journal = JournalPage(page)
        journal.wait_for_loaded()
        journal.wait_for_trackers()
        trackers_online = journal.get_tracker_names()

        # Go offline and reload
        page.context.set_offline(True)
        _reload_offline(page)
        shell.navigate_to("Journal")
        journal.wait_for_loaded()
        page.wait_for_timeout(2000)

        trackers_offline = journal.get_tracker_names()
        assert trackers_offline == trackers_online, "Journal trackers should persist offline"

        page.context.set_offline(False)


# ---------------------------------------------------------------------------
# Coach Offline
# ---------------------------------------------------------------------------

class TestCoachOffline:
    def test_coach_shows_cached_plan_after_offline_reload(self, page, app_server, seeded_coach_db):
        """Coach should display cached workout plan after going offline and reloading."""
        _goto_online(page, app_server)
        shell = AppShellPage(page)
        shell.navigate_to("Coach")
        coach = CoachPage(page)
        coach.wait_for_loaded()
        # Wait for sync to complete and workout title to appear
        page.wait_for_selector(".workout-day-name", timeout=10000)
        title_online = coach.get_workout_title()
        assert title_online is not None, "Should have workout title online"
        # Ensure sync has persisted to LocalForage
        page.wait_for_timeout(2000)

        # Go offline and reload
        page.context.set_offline(True)
        _reload_offline(page)
        shell.navigate_to("Coach")
        coach.wait_for_loaded()
        # Wait for either workout title or empty state to appear (LocalForage async load)
        page.locator(".workout-day-name, .empty-state").first.wait_for(timeout=10000)

        title_offline = coach.get_workout_title()
        assert title_offline == title_online, "Coach workout title should persist offline"

        page.context.set_offline(False)


# ---------------------------------------------------------------------------
# Analysis Offline
# ---------------------------------------------------------------------------

class TestAnalysisOffline:
    def test_analysis_shows_cached_history_offline(self, page, app_server, seeded_analysis_report):
        """Analysis should show cached report history when offline."""
        _goto_online(page, app_server)
        shell = AppShellPage(page)
        shell.navigate_to("Analysis")
        page.wait_for_selector(".analysis", timeout=5000)

        # Navigate to History to trigger loadHistory (caches to LocalForage)
        page.locator(".analysis-tab-btn").filter(has_text="History").click()
        page.wait_for_timeout(2000)

        assert page.locator(".history-item").count() >= 1, "Should see report in history online"
        history_text = page.locator(".history-item-label").first.text_content()

        # Go offline and reload
        page.context.set_offline(True)
        _reload_offline(page)
        shell.navigate_to("Analysis")
        page.wait_for_selector(".analysis", timeout=5000)
        page.wait_for_timeout(2000)

        # Should fall back to History view with cached data
        page.locator(".analysis-tab-btn").filter(has_text="History").click()
        page.wait_for_timeout(1000)
        assert page.locator(".history-item").count() >= 1, "Should see cached history offline"
        assert page.locator(".history-item-label").first.text_content() == history_text

        page.context.set_offline(False)

    def test_analysis_cached_report_viewable_offline(self, page, app_server, seeded_analysis_report):
        """A previously viewed report should be viewable offline from cache."""
        _goto_online(page, app_server)
        shell = AppShellPage(page)
        shell.navigate_to("Analysis")
        page.wait_for_selector(".analysis", timeout=5000)

        # View the report online to cache it
        page.locator(".analysis-tab-btn").filter(has_text="History").click()
        page.wait_for_timeout(2000)
        page.locator(".history-item").first.click()
        page.wait_for_selector(".report-content", timeout=5000)
        report_text = page.locator(".report-content").text_content()

        # Go offline and reload
        page.context.set_offline(True)
        _reload_offline(page)
        shell.navigate_to("Analysis")
        page.wait_for_selector(".analysis", timeout=5000)
        page.wait_for_timeout(2000)

        # Navigate to History and click the report
        page.locator(".analysis-tab-btn").filter(has_text="History").click()
        page.wait_for_timeout(1000)
        page.locator(".history-item").first.click()
        page.wait_for_timeout(2000)

        assert page.locator(".report-content").is_visible(), "Cached report should be viewable offline"
        assert page.locator(".report-content").text_content() == report_text

        page.context.set_offline(False)

    def test_analysis_submit_offline_shows_toast(self, page, app_server):
        """Clicking a query while offline should show an error toast, not crash."""
        _goto_online(page, app_server)
        shell = AppShellPage(page)
        shell.navigate_to("Analysis")
        page.wait_for_selector(".analysis", timeout=5000)
        page.wait_for_timeout(2000)

        query_cards = page.locator(".query-card")
        if query_cards.count() == 0:
            pytest.skip("No queries configured in test environment")

        # Go offline and click a query
        page.context.set_offline(True)
        query_cards.first.click()
        page.wait_for_timeout(2000)

        toast = page.locator(".notification")
        assert toast.is_visible(), "Should show offline notification toast"

        page.context.set_offline(False)
