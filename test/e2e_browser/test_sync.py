"""E2E tests for sync indicator and auto-sync behavior."""
import sqlite3
from datetime import datetime, timedelta

import pytest
import requests as http_requests
from pages.app_shell import AppShellPage
from pages.journal import JournalPage



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
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/delta")
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
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/delta")
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
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/delta")
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
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/delta")
    data = resp.json()
    found = False
    for date_entries in data.get("days", {}).values():
        for tracker_id, entry in date_entries.items():
            if entry.get("value") == 88:
                found = True
    assert found, f"Value 88 not found after online recovery sync"


def _read_daily_logs(page):
    """Read the journal store's persisted daily logs straight out of
    LocalForage's IndexedDB, which is where a phantom row would hide: the
    UI renders an entry the server does not have exactly like one it does."""
    return page.evaluate("""() => new Promise((resolve, reject) => {
        const req = indexedDB.open('JournalApp');
        req.onerror = () => reject(req.error);
        req.onsuccess = () => {
            const store = req.result
                .transaction('journal_data', 'readonly')
                .objectStore('journal_data');
            const get = store.get('daily_logs');
            get.onsuccess = () => resolve(get.result || {});
            get.onerror = () => reject(get.error);
        };
    })""")


def test_missing_rejection_drops_the_phantom_entry(journal_page, app_server):
    """A row the server does not have must not survive on the client.

    The client holds a legitimate server token for today's entry, then the row
    disappears server-side (a restore, a re-created DB, a client pointed at
    another instance — nothing in the app hard-deletes journal rows, which is
    why this is the only way `missing` arises). The next edit uploads against
    that token and is rejected as `missing`; the rejection's `deleted` flag is
    the instruction to converge downward. Before the flag existed the record was
    merely resolved — dirty cleared, row kept — and the phantom was permanent
    and invisible: never re-uploaded, and undeliverable by any delta.
    """
    page = journal_page.page
    page.wait_for_selector(".sync-dot.green", timeout=10000)
    today = datetime.now().strftime("%Y-%m-%d")

    conn = sqlite3.connect(app_server["db_dir"] / "journal.db", timeout=10)
    try:
        with conn:
            conn.execute(
                "DELETE FROM entries WHERE date = ? AND tracker_id = ?",
                (today, "tracker-e2e"),
            )
    finally:
        conn.close()

    logs_before = _read_daily_logs(page)
    assert "tracker-e2e" in logs_before.get(today, {}), (
        "precondition: the client should still hold today's entry (and its token)"
    )

    journal_page.set_tracker_value("Water Intake", 42)
    page.wait_for_selector(".sync-dot.red", timeout=5000)   # dirty, pre-upload
    page.wait_for_selector(".sync-dot.green", timeout=20000)  # cycle settled

    logs_after = _read_daily_logs(page)
    assert "tracker-e2e" not in logs_after.get(today, {}), (
        f"phantom entry survived the missing rejection: {logs_after.get(today)}"
    )
    # The instruction is per-record: the tracker never went anywhere, and the
    # other seeded days keep their entries.
    assert "Water Intake" in journal_page.get_tracker_names()
    yesterday = (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")
    assert "tracker-e2e" in logs_after.get(yesterday, {}), (
        "an untouched day lost its entry — the drop is not scoped to the record"
    )

    delta = http_requests.get(f"{app_server['url']}/api/journal/sync/delta").json()
    assert "tracker-e2e" not in delta.get("days", {}).get(today, {}), (
        "the rejected upload must not have resurrected the row server-side"
    )
