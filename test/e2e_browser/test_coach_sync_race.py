"""E2E tests for coach sync race conditions.

Verifies that exercise log edits made during an active sync are not lost
or reverted. Uses Playwright route interception to add artificial network
latency, creating a window where the user can interact mid-sync.
"""
import time

import pytest
import requests as http_requests
from pages.app_shell import AppShellPage
from pages.coach import CoachPage

pytestmark = pytest.mark.e2e

SYNC_DELAY_MS = 3000  # Artificial delay added to sync endpoints


@pytest.fixture
def coach_sync_page(page, app_server, seeded_coach_db):
    """Navigate to coach with seeded plan, wait for initial sync to complete."""
    page.goto(app_server["url"])
    page.wait_for_selector(".shell", timeout=10000)
    shell = AppShellPage(page)
    shell.navigate_to("Coach")
    coach = CoachPage(page)
    coach.wait_for_loaded()
    # Wait for initial sync to fully complete
    page.wait_for_selector(".sync-dot.green", timeout=10000)
    coach._seed_info = seeded_coach_db
    yield coach
    page.unroute_all(behavior="ignoreErrors")


def _delay_coach_sync_endpoints(page, delay_s=SYNC_DELAY_MS / 1000):
    """Intercept coach sync API calls and add artificial delay."""

    def _delayed_continue(route):
        time.sleep(delay_s)
        try:
            route.continue_()
        except Exception:
            pass

    page.route("**/api/coach/sync", _delayed_continue)


def _get_server_log(app_server, date):
    """Fetch a specific date's log from the server via sync GET."""
    resp = http_requests.get(
        f"{app_server['url']}/api/coach/sync",
        params={"client_id": "e2e-race-verify"})
    data = resp.json()
    return data.get("logs", {}).get(date)


def test_edit_during_sync_preserves_data(coach_sync_page, app_server):
    """Enter set data → sync starts → enter more data during sync → both persist.

    This is the core race condition that caused data loss: the second edit
    must not be overwritten when the sync download phase completes.
    """
    coach = coach_sync_page
    page = coach.page
    today = coach._seed_info["dates"][0]

    # Start workout to unlock exercise entry, then enter first set
    coach.start_workout()
    coach.expand_exercise("KB Goblet Squat")
    page.wait_for_timeout(300)
    coach.fill_set_weight(0, 24)
    coach.fill_set_reps(0, 10)

    # Wait for debounce to fire and sync to start
    page.wait_for_timeout(3000)

    # Now add network delay so the next sync is slow
    _delay_coach_sync_endpoints(page)

    # Enter second set (triggers debounced sync which will be delayed)
    coach.fill_set_weight(1, 28)
    coach.fill_set_reps(1, 8)

    # Wait for debounce + sync to start
    page.wait_for_timeout(3000)

    # Enter third set while sync is in flight
    coach.fill_set_weight(2, 28)
    coach.fill_set_reps(2, 6)

    # Wait for all syncs to complete
    page.wait_for_timeout(SYNC_DELAY_MS + 8000)

    # Verify UI still shows all three sets
    weights = page.locator(".set-input.weight")
    assert weights.nth(0).input_value() == "24"
    assert weights.nth(1).input_value() == "28"
    assert weights.nth(2).input_value() == "28"

    reps = page.locator(".set-input.reps")
    assert reps.nth(0).input_value() == "10"
    assert reps.nth(1).input_value() == "8"
    assert reps.nth(2).input_value() == "6"

    # Verify server has all data
    server_log = _get_server_log(app_server, today)
    assert server_log is not None, "Log missing from server"
    ex_data = server_log.get("ex_1")
    assert ex_data is not None, "Exercise ex_1 missing from server log"
    sets = ex_data.get("sets", [])
    assert len(sets) >= 3, f"Expected 3 sets on server, got {len(sets)}"
    assert sets[0]["weight"] == 24
    assert sets[2]["weight"] == 28
    assert sets[2]["reps"] == 6


def test_re_edit_same_set_during_sync_keeps_latest(coach_sync_page, app_server):
    """Edit set weight to A → sync starts → edit same set to B → B wins.

    The latest value must be what ends up on the server, not the value
    captured in the first sync's upload payload.
    """
    coach = coach_sync_page
    page = coach.page
    today = coach._seed_info["dates"][0]

    # Start workout to unlock exercise entry
    coach.start_workout()
    coach.expand_exercise("KB Goblet Squat")
    page.wait_for_timeout(300)

    _delay_coach_sync_endpoints(page)

    # First edit (triggers sync after debounce)
    coach.fill_set_weight(0, 20)

    # Wait for debounce + sync to start
    page.wait_for_timeout(3000)

    # Re-edit the same set while sync is in flight
    coach.fill_set_weight(0, 32)

    # Wait for all syncs to complete
    page.wait_for_timeout(SYNC_DELAY_MS + 8000)

    # Server must have the LATEST value (32), not the first (20)
    server_log = _get_server_log(app_server, today)
    assert server_log is not None
    ex_data = server_log.get("ex_1")
    assert ex_data is not None
    sets = ex_data.get("sets", [])
    assert len(sets) >= 1
    assert sets[0]["weight"] == 32, (
        f"Server should have latest weight 32, got {sets[0]['weight']}")

    # UI must also show 32
    assert page.locator(".set-input.weight").first.input_value() == "32"


def test_feedback_edit_during_sync_keeps_latest(coach_sync_page, app_server):
    """Edit feedback textarea → sync starts → re-edit during sync → latest wins.

    Feedback textareas serialize through a different path than set inputs.
    The re-edit while sync is in flight must not be dropped by clearDirtyState
    when the first sync response comes back.
    """
    coach = coach_sync_page
    page = coach.page
    today = coach._seed_info["dates"][0]

    coach.start_workout()
    coach.expand_exercise("KB Goblet Squat")
    page.wait_for_timeout(300)
    # Fill a set so the log has exercise content — feedback-only logs are
    # blocked by the content guard and never reach the server.
    coach.fill_set_weight(0, 20)
    page.wait_for_timeout(4000)  # let this sync complete before delaying

    _delay_coach_sync_endpoints(page)

    # First feedback edit (triggers sync after debounce)
    coach.fill_feedback("General Notes", "first draft")

    # Wait for debounce + sync to start (sync is delayed)
    page.wait_for_timeout(3000)

    # Re-edit the same feedback while sync is in flight
    coach.fill_feedback("General Notes", "final version")

    # Wait for all syncs to complete
    page.wait_for_timeout(SYNC_DELAY_MS + 8000)

    server_log = _get_server_log(app_server, today)
    assert server_log is not None, "Log missing from server"
    feedback = server_log.get("session_feedback", {})
    assert feedback.get("general_notes") == "final version", (
        f"Feedback re-edit lost; server has {feedback!r}")

    # UI still shows final text
    notes_field = page.locator(".feedback-field").filter(has_text="General Notes")
    assert notes_field.locator("textarea").input_value() == "final version"


def test_forcesync_during_edit_preserves_latest(coach_sync_page, app_server):
    """Trigger coach Force Sync, edit during its in-flight phase.

    Regression for the generation-counter fix that the journal store also
    received in commit 0483635. Coach's forceSync snapshots dirtyDateGenerations
    before downloading; an edit during the download/upload window must keep
    the date dirty so a follow-up sync uploads the new value.
    """
    coach = coach_sync_page
    page = coach.page
    today = coach._seed_info["dates"][0]

    coach.start_workout()
    coach.expand_exercise("KB Goblet Squat")
    page.wait_for_timeout(300)

    _delay_coach_sync_endpoints(page)
    # Accept the "Continue?" confirm() before Force Sync runs
    page.once("dialog", lambda dialog: dialog.accept())

    shell = AppShellPage(page)
    shell.open_tools()
    page.locator(".tools-item").filter(has_text="Force Sync").click()

    # Let forceSync start its download
    page.wait_for_timeout(1500)

    # Edit mid-forceSync — the generation counter must keep this change dirty
    coach.fill_set_weight(0, 42)

    # Wait for forceSync + follow-up sync from the new dirty state
    page.wait_for_timeout(SYNC_DELAY_MS * 2 + 8000)
    page.wait_for_selector(".sync-dot.green", timeout=10000)

    server_log = _get_server_log(app_server, today)
    assert server_log is not None
    ex_data = server_log.get("ex_1")
    assert ex_data is not None
    sets = ex_data.get("sets", [])
    assert len(sets) >= 1
    assert sets[0]["weight"] == 42, (
        f"Edit during forceSync was lost; server has weight {sets[0].get('weight')}")
