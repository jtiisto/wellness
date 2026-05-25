"""E2E tests for offline mode and service worker behavior."""
import pytest
import requests as http_requests
from pages.app_shell import AppShellPage
from pages.journal import JournalPage

pytestmark = pytest.mark.e2e


@pytest.fixture
def journal_page_online(journal_app_page):
    """Journal page loaded and synced while online."""
    shell = AppShellPage(journal_app_page)
    shell.navigate_to("Journal")
    journal = JournalPage(journal_app_page)
    journal.wait_for_loaded()
    journal.wait_for_trackers()
    return journal


def test_offline_sync_indicator(journal_page_online):
    """Going offline and triggering a sync attempt shows Offline/Pending label.

    Polls the label so that a slow scheduler debounce or in-flight initial
    sync settling can complete before we check — under coverage load the
    transition can take 5-7s instead of the typical 2.5s debounce window.
    """
    page = journal_page_online.page
    page.context.set_offline(True)
    # set_offline blocks network but does not always flip navigator.onLine in
    # time — dispatch the event explicitly so updateSyncStatus sees the change.
    page.evaluate("() => window.dispatchEvent(new Event('offline'))")

    # Read the displayed Water Intake value and pick a different one so the
    # fill() unambiguously produces an input event. Without this, prior tests
    # that left the server's Water Intake at a particular value would make a
    # hard-coded target a no-op fill and the dirty flag would never get set.
    water_input = page.locator(".tracker-item").filter(
        has_text="Water Intake").locator("input[type='number']")
    current = water_input.input_value()
    target = "13" if current != "13" else "14"
    journal_page_online.set_tracker_value("Water Intake", target)

    # Poll for the sync indicator to leave the green/Synced state. Either
    # the offline-event-induced status change OR the failed sync attempt
    # (which sets status=red on catch) should flip it within ~10s.
    deadline_ms = 15000
    poll_interval_ms = 250
    elapsed = 0
    label = journal_page_online.get_sync_label()
    while label == "Synced" and elapsed < deadline_ms:
        page.wait_for_timeout(poll_interval_ms)
        elapsed += poll_interval_ms
        label = journal_page_online.get_sync_label()

    page.context.set_offline(False)
    page.evaluate("() => window.dispatchEvent(new Event('online'))")
    assert label in ["Offline", "Pending"], (
        f"After offline edit, expected Offline or Pending, got {label!r} "
        f"(elapsed {elapsed}ms)")


def test_offline_data_entry(journal_page_online):
    """Can edit data while offline without errors."""
    page = journal_page_online.page
    console_errors = []
    page.on("console", lambda msg: console_errors.append(msg.text) if msg.type == "error" else None)
    page.context.set_offline(True)
    page.evaluate("() => window.dispatchEvent(new Event('offline'))")
    page.wait_for_timeout(500)
    journal_page_online.set_tracker_value("Water Intake", 42)
    page.wait_for_timeout(1000)
    page.context.set_offline(False)
    page.evaluate("() => window.dispatchEvent(new Event('online'))")
    # No uncaught errors should have occurred
    sync_errors = [e for e in console_errors if "uncaught" in e.lower() or "unhandled" in e.lower()]
    assert len(sync_errors) == 0


def test_online_recovery_syncs(journal_page_online, app_server):
    """Going offline, editing, then online triggers sync that persists data."""
    page = journal_page_online.page
    # Edit a value
    journal_page_online.set_tracker_value("Water Intake", 88)
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
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/delta")
    data = resp.json()
    found = False
    for date_entries in data.get("days", {}).values():
        for tracker_id, entry in date_entries.items():
            if entry.get("value") == 88:
                found = True
    assert found, f"Value 88 not found after online recovery sync"
