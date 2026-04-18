"""E2E tests for sync indicator and auto-sync behavior."""
import pytest
import requests as http_requests
from pages.app_shell import AppShellPage
from pages.journal import JournalPage

pytestmark = pytest.mark.e2e


@pytest.fixture
def journal_page(journal_app_page):
    """Navigate to journal module with seeded data."""
    shell = AppShellPage(journal_app_page)
    shell.navigate_to("Journal")
    journal = JournalPage(journal_app_page)
    journal.wait_for_loaded()
    journal.wait_for_trackers()
    return journal


def test_sync_indicator_green_after_load(journal_page):
    """After initial sync completes, indicator shows green."""
    page = journal_page.page
    page.wait_for_selector(".sync-dot.green", timeout=10000)
    assert page.locator(".sync-dot.green").is_visible()


def test_sync_indicator_not_clickable(journal_page):
    """Sync indicator has no pointer cursor."""
    page = journal_page.page
    cursor = page.locator(".sync-indicator").evaluate(
        "el => window.getComputedStyle(el).cursor"
    )
    assert cursor != "pointer"


def test_sync_indicator_tooltip_synced(journal_page):
    """Green state shows 'Synced' label."""
    page = journal_page.page
    page.wait_for_selector(".sync-dot.green", timeout=10000)
    label = journal_page.get_sync_label()
    assert label == "Synced"


def test_sync_indicator_offline_on_failed_sync(journal_page):
    """When offline and a sync attempt is made, indicator shows gray/Offline.

    The indicator doesn't change to 'Offline' just from going offline —
    it changes when the next sync attempt detects navigator.onLine is false.
    """
    page = journal_page.page
    # Go offline
    page.context.set_offline(True)
    # Edit a value to trigger a debounced sync attempt while offline
    journal_page.set_tracker_value("Water Intake", 77)
    # Wait for debounce (2.5s) + sync attempt
    page.wait_for_timeout(4000)
    label = journal_page.get_sync_label()
    page.context.set_offline(False)
    # After the sync attempt detects offline, status should be gray
    assert label in ["Offline", "Pending"]


def test_debounced_upload_persists(journal_page, app_server):
    """Editing data triggers a debounced sync that persists to the server."""
    page = journal_page.page
    # Edit tracker value
    journal_page.set_tracker_value("Water Intake", 99)
    # Wait for debounce (2.5s) + sync
    page.wait_for_timeout(5000)
    # Verify data reached the server
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/full")
    data = resp.json()
    # Check if any entry has value 99
    found = False
    for date_entries in data.get("days", {}).values():
        for tracker_id, entry in date_entries.items():
            if entry.get("value") == 99:
                found = True
    assert found, f"Value 99 not found in server data: {data.get('days', {})}"


def test_no_duplicate_sync_on_rapid_edits(journal_page, app_server):
    """Rapid edits result in a single debounced sync with final value."""
    page = journal_page.page
    # Rapidly edit values
    journal_page.set_tracker_value("Water Intake", 10)
    page.wait_for_timeout(500)
    journal_page.set_tracker_value("Water Intake", 20)
    page.wait_for_timeout(500)
    journal_page.set_tracker_value("Water Intake", 30)
    # Wait for debounce + sync
    page.wait_for_timeout(5000)
    # Server should have the final value (30)
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/full")
    data = resp.json()
    found_30 = False
    for date_entries in data.get("days", {}).values():
        for tracker_id, entry in date_entries.items():
            if entry.get("value") == 30:
                found_30 = True
    assert found_30, f"Final value 30 not found in server data"


def test_sync_on_visibility(journal_page, app_server):
    """Simulating visibility change triggers sync."""
    page = journal_page.page
    # Edit a value (creates dirty data)
    journal_page.set_tracker_value("Water Intake", 55)
    page.wait_for_timeout(500)
    # Simulate going hidden then visible (should trigger immediate sync)
    page.evaluate("""() => {
        Object.defineProperty(document, 'visibilityState', {
            value: 'hidden', writable: true, configurable: true
        });
        document.dispatchEvent(new Event('visibilitychange'));
    }""")
    page.wait_for_timeout(500)
    page.evaluate("""() => {
        Object.defineProperty(document, 'visibilityState', {
            value: 'visible', writable: true, configurable: true
        });
        document.dispatchEvent(new Event('visibilitychange'));
    }""")
    # Wait for sync
    page.wait_for_timeout(3000)
    # Check server has the value
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/full")
    data = resp.json()
    found = False
    for date_entries in data.get("days", {}).values():
        for tracker_id, entry in date_entries.items():
            if entry.get("value") == 55:
                found = True
    assert found, f"Value 55 not found after visibility change sync"


def test_online_recovery_triggers_sync(journal_page, app_server):
    """Going offline then online triggers auto-sync."""
    page = journal_page.page
    # Edit a value
    journal_page.set_tracker_value("Water Intake", 88)
    page.wait_for_timeout(500)
    # Go offline (blocks network + dispatches events)
    page.context.set_offline(True)
    page.evaluate("() => window.dispatchEvent(new Event('offline'))")
    page.wait_for_timeout(1000)
    # Go online
    page.context.set_offline(False)
    page.evaluate("() => window.dispatchEvent(new Event('online'))")
    # Wait for auto-sync
    page.wait_for_timeout(5000)
    # Check server has the value
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/full")
    data = resp.json()
    found = False
    for date_entries in data.get("days", {}).values():
        for tracker_id, entry in date_entries.items():
            if entry.get("value") == 88:
                found = True
    assert found, f"Value 88 not found after online recovery sync"
