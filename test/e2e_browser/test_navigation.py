"""E2E tests for app shell navigation."""
import pytest
from pages.app_shell import AppShellPage



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


def test_tools_opens_and_closes(app_page):
    """Tools button opens and closes the tools menu."""
    shell = AppShellPage(app_page)
    shell.open_tools()
    assert app_page.locator(".tools-menu").is_visible()
    shell.close_tools()
    assert not app_page.locator(".tools-menu").is_visible()


def test_unvisited_module_syncs_in_background(app_page):
    """Background store init: the coach module syncs without its tab ever
    being visited (journal is the default active module in a fresh context).
    Init is delayed ~8s past boot and runs sequentially, so it cannot regress
    the active module's first render — the failure that reverted the original
    boot-init-everything approach."""
    with app_page.expect_request(
        lambda r: "/api/coach/sync" in r.url, timeout=20000
    ) as req_info:
        pass  # request fires from the delayed background init
    assert "/api/coach/sync" in req_info.value.url
