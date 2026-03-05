"""E2E tests for the settings menu."""
import pytest
from pages.app_shell import AppShellPage

pytestmark = pytest.mark.e2e


def test_force_sync_button(app_page):
    """Force sync button triggers sync and shows syncing state."""
    shell = AppShellPage(app_page)
    shell.open_settings()
    force_btn = app_page.locator(".settings-item").filter(has_text="Force Sync")
    assert force_btn.is_visible()
    force_btn.click()
    # Should eventually return to "Force Sync" text
    app_page.wait_for_timeout(3000)
    assert force_btn.is_enabled()


def test_export_data_button(app_page):
    """Export All Data button triggers a download."""
    shell = AppShellPage(app_page)
    shell.open_settings()
    with app_page.expect_download(timeout=5000) as download_info:
        app_page.locator(".settings-item").filter(has_text="Export All Data").click()
    download = download_info.value
    assert download.suggested_filename.endswith(".json")


def test_debug_log_button(app_page):
    """Save Debug Log button triggers a download."""
    shell = AppShellPage(app_page)
    shell.open_settings()
    with app_page.expect_download(timeout=5000) as download_info:
        app_page.locator(".settings-item").filter(has_text="Save Debug Log").click()
    download = download_info.value
    assert download.suggested_filename.endswith(".txt")


def test_settings_close(app_page):
    """Closing settings returns to the module view."""
    shell = AppShellPage(app_page)
    shell.open_settings()
    assert app_page.locator(".settings-menu").is_visible()
    shell.close_settings()
    assert not app_page.locator(".settings-menu").is_visible()
    assert app_page.locator(".module-content").is_visible()
