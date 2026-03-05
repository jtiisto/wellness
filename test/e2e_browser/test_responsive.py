"""E2E tests for responsive/mobile viewport behavior."""
import pytest
from pages.app_shell import AppShellPage
from pages.journal import JournalPage

pytestmark = pytest.mark.e2e


@pytest.fixture
def mobile_page(browser, app_server):
    """A page with mobile viewport dimensions (iPhone SE)."""
    context = browser.new_context(viewport={"width": 375, "height": 667})
    page = context.new_page()
    page.goto(app_server["url"])
    page.wait_for_selector(".shell", timeout=10000)
    yield page
    context.close()


@pytest.fixture
def mobile_journal_page(browser, app_server, seeded_journal):
    """Mobile page with journal data seeded BEFORE navigation."""
    context = browser.new_context(viewport={"width": 375, "height": 667})
    page = context.new_page()
    page.goto(app_server["url"])
    page.wait_for_selector(".shell", timeout=10000)
    yield page
    context.close()


def test_mobile_viewport_renders(mobile_page):
    """App renders correctly at mobile viewport size."""
    shell = AppShellPage(mobile_page)
    assert shell.is_loaded()
    assert mobile_page.locator("nav.nav-bar").is_visible()


def test_nav_bar_usable_mobile(mobile_page):
    """Nav buttons are tappable at mobile size."""
    shell = AppShellPage(mobile_page)
    shell.navigate_to("Coach")
    assert shell.get_active_module() == "Coach"
    shell.navigate_to("Journal")
    assert shell.get_active_module() == "Journal"


def test_touch_interactions(mobile_journal_page):
    """Tap interactions work on tracker checkboxes."""
    shell = AppShellPage(mobile_journal_page)
    shell.navigate_to("Journal")
    journal = JournalPage(mobile_journal_page)
    journal.wait_for_loaded()
    journal.wait_for_trackers()
    names = journal.get_tracker_names()
    if names:
        journal.set_tracker_checkbox(names[0], checked=True)
        row = mobile_journal_page.locator(".tracker-item").filter(has_text=names[0])
        assert row.locator("input[type='checkbox']").is_checked()
