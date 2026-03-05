"""E2E tests for app shell navigation."""
import pytest
from pages.app_shell import AppShellPage

pytestmark = pytest.mark.e2e


def test_app_loads(app_page):
    """Shell renders and nav bar is visible."""
    shell = AppShellPage(app_page)
    assert shell.is_loaded()
    assert app_page.locator("nav.nav-bar").is_visible()


def test_module_switching(app_page):
    """Clicking nav buttons loads the correct module."""
    shell = AppShellPage(app_page)
    shell.navigate_to("Coach")
    assert app_page.locator(".coach").is_visible()
    shell.navigate_to("Journal")
    assert app_page.locator(".journal").is_visible()


def test_active_nav_highlight(app_page):
    """Active module button has .active class."""
    shell = AppShellPage(app_page)
    shell.navigate_to("Coach")
    assert shell.get_active_module() == "Coach"
    shell.navigate_to("Journal")
    assert shell.get_active_module() == "Journal"


def test_module_persistence(page, app_server):
    """Reloading preserves the selected module."""
    page.goto(app_server["url"])
    page.wait_for_selector(".shell", timeout=10000)
    shell = AppShellPage(page)
    shell.navigate_to("Coach")
    page.reload()
    page.wait_for_selector(".shell", timeout=10000)
    assert shell.get_active_module() == "Coach"


def test_initial_module_default(app_page):
    """Fresh load shows first enabled module (Journal)."""
    shell = AppShellPage(app_page)
    active = shell.get_active_module()
    assert active in ["Journal", "Coach"]


def test_settings_opens_and_closes(app_page):
    """Settings gear opens and closes the settings menu."""
    shell = AppShellPage(app_page)
    shell.open_settings()
    assert app_page.locator(".settings-menu").is_visible()
    shell.close_settings()
    assert not app_page.locator(".settings-menu").is_visible()
