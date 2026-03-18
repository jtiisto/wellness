"""E2E tests for sync race conditions.

Verifies that edits made during an active sync are not lost or reverted.
Uses Playwright route interception to add artificial network latency,
creating a window where the user can interact mid-sync.
"""
import time

import pytest
import requests as http_requests
from datetime import datetime, timedelta
from pages.app_shell import AppShellPage
from pages.journal import JournalPage

pytestmark = pytest.mark.e2e

SYNC_DELAY_MS = 3000  # Artificial delay added to sync endpoints


@pytest.fixture
def seeded_journal_two_trackers(app_server):
    """Seed journal with two trackers: a quantifiable and a simple (checkbox)."""
    base = app_server["url"]
    client_id = "e2e-race-client"

    http_requests.post(
        f"{base}/api/journal/sync/register?client_id={client_id}&client_name=RaceTest")

    tracker_quant = {
        "id": "tracker-race-quant",
        "name": "Steps",
        "category": "fitness",
        "type": "quantifiable",
        "unit": "steps",
        "_baseVersion": 0,
    }
    tracker_simple = {
        "id": "tracker-race-simple",
        "name": "Meditation",
        "category": "fitness",
        "type": "simple",
        "_baseVersion": 0,
    }

    http_requests.post(f"{base}/api/journal/sync/update", json={
        "clientId": client_id,
        "config": [tracker_quant, tracker_simple],
        "days": {},
    })

    today = datetime.now().strftime("%Y-%m-%d")
    days = {
        today: {
            tracker_quant["id"]: {"value": 1000, "completed": True, "_baseVersion": 0},
            tracker_simple["id"]: {"completed": False, "_baseVersion": 0},
        }
    }
    http_requests.post(f"{base}/api/journal/sync/update", json={
        "clientId": client_id,
        "config": [],
        "days": days,
    })

    return {
        "client_id": client_id,
        "trackers": [tracker_quant, tracker_simple],
        "today": today,
    }


@pytest.fixture
def race_journal_page(page, app_server, seeded_journal_two_trackers):
    """Navigate to journal with two-tracker seed, wait for initial sync to complete."""
    page.goto(app_server["url"])
    page.wait_for_selector(".shell", timeout=10000)
    shell = AppShellPage(page)
    shell.navigate_to("Journal")
    journal = JournalPage(page)
    journal.wait_for_loaded()
    journal.wait_for_trackers()
    # Wait for initial sync to fully complete
    page.wait_for_selector(".sync-dot.green", timeout=10000)
    yield journal
    # Clean up intercepted routes to avoid Playwright teardown errors
    page.unroute_all(behavior="ignoreErrors")


def _delay_sync_endpoints(page, delay_s=SYNC_DELAY_MS / 1000):
    """Intercept sync API calls and add artificial delay to simulate slow network.

    Uses time.sleep instead of page.wait_for_timeout to avoid Playwright
    lifecycle issues when the route outlives the page context.
    """

    def _delayed_continue(route):
        time.sleep(delay_s)
        try:
            route.continue_()
        except Exception:
            pass  # Route may be dead if page closed during delay

    page.route("**/api/journal/sync/update", _delayed_continue)
    page.route("**/api/journal/sync/delta*", _delayed_continue)


def _get_server_entry(app_server, tracker_id, date=None):
    """Fetch a specific entry from the server."""
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/full")
    data = resp.json()
    if date is None:
        date = datetime.now().strftime("%Y-%m-%d")
    return data.get("days", {}).get(date, {}).get(tracker_id)


def test_edit_different_tracker_during_sync_preserves_both(
        race_journal_page, app_server, seeded_journal_two_trackers):
    """Edit tracker A → sync starts → edit tracker B during sync → both persist.

    This is the core race condition: the second edit must not be reverted
    when the first sync completes.
    """
    page = race_journal_page.page
    seed = seeded_journal_two_trackers

    # Add network delay so we have time to interact during sync
    _delay_sync_endpoints(page)

    # Edit Steps (triggers debounced sync)
    race_journal_page.set_tracker_value("Steps", 5000)

    # Wait for debounce to fire and sync to start (2.5s debounce + small buffer)
    page.wait_for_timeout(3000)

    # Now sync is in flight (delayed by SYNC_DELAY_MS).
    # Edit Meditation checkbox while sync is active.
    race_journal_page.set_tracker_checkbox("Meditation", checked=True)

    # Wait for all syncs to complete (delayed sync + follow-up sync for second edit)
    page.wait_for_timeout(SYNC_DELAY_MS + 6000)

    # Verify both values persisted on the server
    steps_entry = _get_server_entry(app_server, seed["trackers"][0]["id"], seed["today"])
    meditation_entry = _get_server_entry(app_server, seed["trackers"][1]["id"], seed["today"])

    assert steps_entry is not None, "Steps entry missing from server"
    assert steps_entry["value"] == 5000, (
        f"Steps value should be 5000, got {steps_entry['value']}")
    assert meditation_entry is not None, "Meditation entry missing from server"
    assert meditation_entry["completed"] is True, (
        f"Meditation should be completed, got {meditation_entry['completed']}")

    # Verify UI still shows correct values
    steps_row = page.locator(".tracker-item").filter(has_text="Steps")
    assert steps_row.locator("input[type='number']").input_value() == "5000"
    meditation_row = page.locator(".tracker-item").filter(has_text="Meditation")
    assert meditation_row.locator("input[type='checkbox']").is_checked()


def test_re_edit_same_entry_during_sync_keeps_latest(
        race_journal_page, app_server, seeded_journal_two_trackers):
    """Edit entry to value A → sync starts → edit same entry to value B → B wins.

    The latest value must be what ends up on the server, not the value
    that was captured in the first sync's upload payload.
    """
    page = race_journal_page.page
    seed = seeded_journal_two_trackers

    _delay_sync_endpoints(page)

    # First edit (triggers sync after debounce)
    race_journal_page.set_tracker_value("Steps", 2000)

    # Wait for debounce + sync to start
    page.wait_for_timeout(3000)

    # Re-edit the same entry while sync is in flight
    race_journal_page.set_tracker_value("Steps", 9999)

    # Wait for all syncs to complete
    page.wait_for_timeout(SYNC_DELAY_MS + 6000)

    # Server must have the LATEST value (9999), not the first (2000)
    entry = _get_server_entry(app_server, seed["trackers"][0]["id"], seed["today"])
    assert entry is not None, "Steps entry missing from server"
    assert entry["value"] == 9999, (
        f"Server should have latest value 9999, got {entry['value']}")

    # UI must also show 9999
    steps_row = page.locator(".tracker-item").filter(has_text="Steps")
    assert steps_row.locator("input[type='number']").input_value() == "9999"


def test_checkbox_edit_during_sync_not_reverted(
        race_journal_page, app_server, seeded_journal_two_trackers):
    """Check checkbox → sync starts → uncheck during sync → unchecked persists.

    Specifically tests checkboxes since the user reported this interaction.
    """
    page = race_journal_page.page
    seed = seeded_journal_two_trackers

    _delay_sync_endpoints(page)

    # Check the checkbox (triggers sync)
    race_journal_page.set_tracker_checkbox("Meditation", checked=True)

    # Wait for debounce + sync to start
    page.wait_for_timeout(3000)

    # Uncheck while sync is in flight
    race_journal_page.set_tracker_checkbox("Meditation", checked=False)

    # Wait for all syncs to complete
    page.wait_for_timeout(SYNC_DELAY_MS + 6000)

    # Server should have the FINAL state (unchecked)
    entry = _get_server_entry(app_server, seed["trackers"][1]["id"], seed["today"])
    assert entry is not None, "Meditation entry missing from server"
    assert entry["completed"] is False, (
        f"Meditation should be unchecked (False), got {entry['completed']}")

    # UI should show unchecked
    meditation_row = page.locator(".tracker-item").filter(has_text="Meditation")
    assert not meditation_row.locator("input[type='checkbox']").is_checked()


def test_dirty_data_during_sync_triggers_followup(
        race_journal_page, app_server, seeded_journal_two_trackers):
    """Edit during sync → after sync completes, follow-up sync fires and uploads.

    Tests that the SyncScheduler's pending-sync mechanism works: dirty data
    accumulated during an active sync must be uploaded promptly, not wait
    for the 30s poll.
    """
    page = race_journal_page.page
    seed = seeded_journal_two_trackers

    _delay_sync_endpoints(page)

    # Edit Steps (triggers sync)
    race_journal_page.set_tracker_value("Steps", 3000)

    # Wait for debounce + sync to start
    page.wait_for_timeout(3000)

    # Edit Meditation while sync is in flight
    race_journal_page.set_tracker_checkbox("Meditation", checked=True)

    # Wait for: delayed sync to complete + follow-up debounce (2.5s) + follow-up sync
    # Should NOT need to wait 30s for the poll — the follow-up should fire promptly
    page.wait_for_timeout(SYNC_DELAY_MS + 8000)

    # Both values should be on the server
    steps = _get_server_entry(app_server, seed["trackers"][0]["id"], seed["today"])
    meditation = _get_server_entry(app_server, seed["trackers"][1]["id"], seed["today"])

    assert steps["value"] == 3000, f"Steps not synced: {steps}"
    assert meditation["completed"] is True, f"Meditation not synced: {meditation}"

    # Sync indicator should be green (all clean)
    page.wait_for_selector(".sync-dot.green", timeout=5000)
