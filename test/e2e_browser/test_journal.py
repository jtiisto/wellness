"""E2E tests for the journal module."""
from datetime import datetime

import pytest
import requests as http_requests
from pages.app_shell import AppShellPage
from pages.journal import JournalPage

pytestmark = pytest.mark.e2e


@pytest.fixture
def journal_page(journal_app_page):
    """Navigate to journal module with seeded data (seeded before page load)."""
    shell = AppShellPage(journal_app_page)
    shell.navigate_to("Journal")
    journal = JournalPage(journal_app_page)
    journal.wait_for_loaded()
    journal.wait_for_trackers()
    return journal


def test_trackers_display(journal_page):
    """Seeded trackers appear in the tracker list."""
    names = journal_page.get_tracker_names()
    assert "Water Intake" in names


def test_edit_entry_value(journal_page):
    """Updating a number input persists the value."""
    page = journal_page.page
    journal_page.set_tracker_value("Water Intake", 7)
    # Wait for debounced sync
    page.wait_for_timeout(3500)
    page.reload()
    page.wait_for_selector(".shell", timeout=10000)
    shell = AppShellPage(page)
    shell.navigate_to("Journal")
    journal = JournalPage(page)
    journal.wait_for_loaded()
    page.wait_for_timeout(3000)
    row = page.locator(".tracker-item").filter(has_text="Water Intake")
    value = row.locator("input[type='number']").input_value()
    assert value == "7"


def test_checkbox_toggle(journal_page):
    """Toggling a tracker checkbox changes its state."""
    page = journal_page.page
    journal_page.set_tracker_checkbox("Water Intake", checked=True)
    row = page.locator(".tracker-item").filter(has_text="Water Intake")
    assert row.locator("input[type='checkbox']").is_checked()


def test_date_navigation(journal_page):
    """Clicking a different date shows different entries."""
    page = journal_page.page
    journal_page.select_date(1)
    page.wait_for_timeout(500)
    names = journal_page.get_tracker_names()
    assert "Water Intake" in names


def test_config_screen_opens(journal_page):
    """Gear icon opens the configuration screen."""
    page = journal_page.page
    journal_page.open_config()
    assert page.locator(".config-screen").is_visible()


def test_value_persists_when_checkbox_unchecked(journal_page):
    """Typing a value while the checkbox is unchecked must persist.

    Regression: the value was silently dropped if completed=false, then
    reverted to the default on the next re-render — users couldn't tell
    their input wasn't stored.
    """
    page = journal_page.page
    journal_page.set_tracker_checkbox("Water Intake", checked=False)
    page.wait_for_timeout(500)
    journal_page.set_tracker_value("Water Intake", 42)
    page.wait_for_timeout(3500)  # debounce + sync
    page.reload()
    page.wait_for_selector(".shell", timeout=10000)
    shell = AppShellPage(page)
    shell.navigate_to("Journal")
    journal = JournalPage(page)
    journal.wait_for_loaded()
    journal.wait_for_trackers()
    page.wait_for_timeout(3000)
    row = page.locator(".tracker-item").filter(has_text="Water Intake")
    assert row.locator("input[type='number']").input_value() == "42"
    # Checkbox must remain unchecked — we don't auto-check on value entry
    assert not row.locator("input[type='checkbox']").is_checked()


def test_last_updated_label_appears_after_value_change(journal_page):
    """Editing a quantifiable tracker's value shows a 'Last updated HH:MM' caption.

    Solves the memory cue users want when bumping accumulating intake (e.g.
    protein) throughout the day. Replaces the prior-day 'Last:' value hint.
    """
    page = journal_page.page
    row = page.locator(".tracker-item").filter(has_text="Water Intake")

    # No update has happened on the current page render → no caption yet.
    assert row.locator(".tracker-last-updated").count() == 0

    journal_page.set_tracker_value("Water Intake", 6)
    page.wait_for_timeout(300)

    caption = row.locator(".tracker-last-updated")
    assert caption.count() == 1
    text = caption.text_content()
    assert text.startswith("Last updated ")
    # Format includes a colon separator for HH:MM
    assert ":" in text


def test_last_updated_label_no_prior_day_cue(journal_page):
    """The legacy 'Last: <value> on <date>' caption is gone (no .tracker-last-value)."""
    page = journal_page.page
    # Seeded data has prior-day values, so under the old behavior at least
    # one .tracker-last-value div would render. The class is removed.
    assert page.locator(".tracker-last-value").count() == 0


def test_focus_blur_without_value_change_does_not_update_timestamp(journal_page):
    """Tabbing into and out of the field without editing must not bump the
    'Last updated' timestamp. Otherwise scrolling through trackers would
    appear to mark every value as freshly edited.
    """
    page = journal_page.page
    row = page.locator(".tracker-item").filter(has_text="Water Intake")

    # First, do a real edit so a "Last updated" caption exists to compare against.
    journal_page.set_tracker_value("Water Intake", 6)
    page.wait_for_timeout(300)
    initial = row.locator(".tracker-last-updated").text_content()
    assert initial.startswith("Last updated ")

    # Now read the underlying timestamp before focus/blur.
    before = page.evaluate(
        """async () => {
            const m = await import('/js/journal/store.js');
            return JSON.parse(JSON.stringify(m.trackerValueUpdatedTimes.value));
        }"""
    )

    # Simulate tabbing: focus the input, then blur without typing.
    input_el = row.locator("input[type='number']")
    input_el.focus()
    page.wait_for_timeout(100)
    input_el.blur()
    page.wait_for_timeout(300)

    after = page.evaluate(
        """async () => {
            const m = await import('/js/journal/store.js');
            return JSON.parse(JSON.stringify(m.trackerValueUpdatedTimes.value));
        }"""
    )
    assert before == after, (
        f"Focus/blur with no value change should not bump the timestamp. "
        f"Before: {before}, After: {after}"
    )


@pytest.fixture
def journal_with_extras(page, app_server, seeded_journal):
    """Load the journal after seeding an evaluation slider and a note tracker.

    Extends seeded_journal (Water Intake quantifiable) with a mood slider
    (evaluation type) and a reflections note (note type) so tests can
    exercise each tracker-value persistence path.
    """
    base = app_server["url"]
    client_id = seeded_journal["client_id"]
    today = datetime.now().strftime("%Y-%m-%d")

    extras = [
        {
            "id": "tracker-mood",
            "name": "Mood",
            "category": "wellbeing",
            "type": "evaluation",
            "_baseVersion": 0,
        },
        {
            "id": "tracker-reflection",
            "name": "Reflection",
            "category": "wellbeing",
            "type": "note",
            "_baseVersion": 0,
        },
    ]
    http_requests.post(f"{base}/api/journal/sync/update", json={
        "clientId": client_id, "config": extras, "days": {}})

    page.goto(app_server["url"])
    page.wait_for_selector(".shell", timeout=10000)
    shell = AppShellPage(page)
    shell.navigate_to("Journal")
    journal = JournalPage(page)
    journal.wait_for_loaded()
    journal.wait_for_trackers()
    return {"journal": journal, "today": today}


def test_slider_value_persists_when_checkbox_unchecked(journal_with_extras):
    """Evaluation slider values must persist without the checkbox being checked.

    Mirrors the number-input regression — the completed guard was also
    removed from handleSliderChange so slider changes aren't silently dropped.
    """
    journal = journal_with_extras["journal"]
    page = journal.page

    mood_row = page.locator(".tracker-item").filter(has_text="Mood")
    slider = mood_row.locator("input[type='range']")
    # Set value to 75 (valid for step=25)
    slider.evaluate("el => { el.value = '75'; el.dispatchEvent(new Event('input', { bubbles: true })); }")
    page.wait_for_timeout(3500)  # debounce + sync

    page.reload()
    page.wait_for_selector(".shell", timeout=10000)
    shell = AppShellPage(page)
    shell.navigate_to("Journal")
    journal.wait_for_loaded()
    journal.wait_for_trackers()
    page.wait_for_timeout(3000)

    mood_row = page.locator(".tracker-item").filter(has_text="Mood")
    assert mood_row.locator("input[type='range']").input_value() == "75"
    # Checkbox stays unchecked — we don't auto-check on slider interaction
    assert not mood_row.locator("input[type='checkbox']").is_checked()


def test_note_text_sets_completed_and_clearing_unsets(journal_with_extras, app_server):
    """Note trackers auto-set completed based on whether text is non-empty."""
    journal = journal_with_extras["journal"]
    page = journal.page

    refl_row = page.locator(".tracker-item").filter(has_text="Reflection")
    textarea = refl_row.locator("textarea")

    textarea.fill("felt focused today")
    page.wait_for_timeout(3500)

    # Reload and verify text persisted + completed inferred
    page.reload()
    page.wait_for_selector(".shell", timeout=10000)
    shell = AppShellPage(page)
    shell.navigate_to("Journal")
    journal.wait_for_loaded()
    journal.wait_for_trackers()
    page.wait_for_timeout(3000)

    refl_row = page.locator(".tracker-item").filter(has_text="Reflection")
    assert refl_row.locator("textarea").input_value() == "felt focused today"
    # Note trackers don't render a checkbox, so we check server state instead
    today = journal_with_extras["today"]
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/full")
    entry = resp.json().get("days", {}).get(today, {}).get("tracker-reflection")
    assert entry is not None and entry.get("completed") is True

    # Clearing the note should flip completed to False
    refl_row.locator("textarea").fill("")
    page.wait_for_timeout(3500)
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/full")
    entry = resp.json().get("days", {}).get(today, {}).get("tracker-reflection")
    assert entry is not None and entry.get("completed") is False


def test_multi_client_conflict_resolution(journal_page, app_server, seeded_journal):
    """Two clients edit the same entry value → yellow state → user picks a side.

    Exercises the ConflictResolver UI end-to-end — the only way this
    component renders is when detectLocalConflicts flags an entry where
    both the local client and the server have overlapping value changes.
    """
    page = journal_page.page
    base = app_server["url"]
    today = datetime.now().strftime("%Y-%m-%d")

    # Simulate another device writing to the same entry. Must use the
    # current server version for _baseVersion so the write isn't rejected
    # under accumulated state from other tests.
    current = http_requests.get(f"{base}/api/journal/sync/full").json()
    server_entry = current.get("days", {}).get(today, {}).get("tracker-e2e", {})
    server_version = server_entry.get("_version", 0)
    http_requests.post(f"{base}/api/journal/sync/update", json={
        "clientId": "remote-device",
        "config": [],
        "days": {today: {"tracker-e2e": {
            "value": 99, "completed": True, "_baseVersion": server_version}}},
    })

    # Edit locally to a different value — both sides now have diverged.
    journal_page.set_tracker_value("Water Intake", 33)

    # Trigger a sync via visibility change (faster than waiting for poll)
    page.evaluate("""() => {
        Object.defineProperty(document, 'visibilityState', {
            value: 'visible', writable: true, configurable: true
        });
        document.dispatchEvent(new Event('visibilitychange'));
    }""")

    # Conflict is detected → yellow dot + "Sync Conflict" notification
    page.wait_for_selector(".sync-dot.yellow", timeout=10000)
    resolve_btn = page.locator(".notification-action-btn").filter(has_text="Resolve")
    resolve_btn.click()

    # Conflict screen renders with both versions side by side
    page.wait_for_selector(".conflict-screen", timeout=5000)
    local_version = page.locator(".conflict-version.local")
    server_version = page.locator(".conflict-version.server")
    assert "33" in local_version.text_content()
    assert "99" in server_version.text_content()

    # Keep the local version
    local_version.locator("button").filter(has_text="Keep Mine").click()
    page.wait_for_timeout(3000)

    # Server should now have our value (33), not the remote's (99)
    resp = http_requests.get(f"{base}/api/journal/sync/full")
    entry = resp.json().get("days", {}).get(today, {}).get("tracker-e2e")
    assert entry is not None
    assert entry["value"] == 33, f"Local value should have won; server has {entry['value']}"
    # Yellow dot clears once the conflict is resolved
    page.wait_for_selector(".sync-dot.yellow", state="detached", timeout=5000)


def _seed_disposable_tracker(app_server, seeded_journal, tracker_id, name):
    """Seed a throwaway tracker via API. Avoids mutating the shared seed."""
    http_requests.post(f"{app_server['url']}/api/journal/sync/update", json={
        "clientId": seeded_journal["client_id"],
        "config": [{
            "id": tracker_id, "name": name, "category": "disposable",
            "type": "simple", "_baseVersion": 0,
        }],
        "days": {},
    })


def test_edit_tracker_via_config(journal_app_page, app_server, seeded_journal):
    """Editing a tracker's name through config persists locally and to server.

    Uses a disposable tracker so the rename doesn't poison shared-state used
    by test_sync.py / test_offline.py later in the session.
    """
    _seed_disposable_tracker(app_server, seeded_journal, "tracker-to-edit", "Edit Me")
    # Reload so the browser picks up the freshly seeded tracker
    journal_app_page.reload()
    shell = AppShellPage(journal_app_page)
    shell.navigate_to("Journal")
    journal = JournalPage(journal_app_page)
    journal.wait_for_loaded()
    journal.wait_for_trackers()

    journal.open_config()
    row = journal_app_page.locator(".tracker-config-item").filter(has_text="Edit Me")
    row.locator("button[title='Edit']").click()
    journal_app_page.wait_for_selector(".modal-content", timeout=3000)
    journal_app_page.locator(".modal-content .form-input").first.fill("Edited")
    journal_app_page.locator("button[type='submit']").click()
    journal_app_page.wait_for_selector(".modal-content", state="hidden", timeout=3000)
    journal_app_page.wait_for_timeout(3500)

    assert journal_app_page.locator(".tracker-config-item").filter(
        has_text="Edited").is_visible()
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/full")
    tracker = next(
        t for t in resp.json().get("config", []) if t["id"] == "tracker-to-edit")
    assert tracker["name"] == "Edited"


def test_delete_tracker_via_config(journal_app_page, app_server, seeded_journal):
    """Deleting a tracker tombstones it server-side and hides it locally.

    Uses a disposable tracker so the tombstone doesn't remove Water Intake
    (which later tests depend on).
    """
    _seed_disposable_tracker(app_server, seeded_journal, "tracker-to-delete", "Delete Me")
    journal_app_page.reload()
    shell = AppShellPage(journal_app_page)
    shell.navigate_to("Journal")
    journal = JournalPage(journal_app_page)
    journal.wait_for_loaded()
    journal.wait_for_trackers()

    journal_app_page.on("dialog", lambda dialog: dialog.accept())
    journal.open_config()
    row = journal_app_page.locator(".tracker-config-item").filter(has_text="Delete Me")
    row.locator("button[title='Delete']").click()
    journal_app_page.wait_for_timeout(3500)

    assert journal_app_page.locator(".tracker-config-item").filter(
        has_text="Delete Me").count() == 0
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/full")
    tracker_ids = [t["id"] for t in resp.json().get("config", [])]
    assert "tracker-to-delete" not in tracker_ids


def test_add_tracker_from_config(journal_page):
    """Creating a new tracker via the form adds it to the list."""
    page = journal_page.page
    journal_page.open_config()
    page.locator("button.btn-primary").filter(has_text="Add").click()
    page.wait_for_selector(".modal-content", timeout=3000)
    # Fill name
    page.locator(".form-input").first.fill("Exercise")
    # Need to add a new category since form requires one
    # Click "+ New Category" button if visible, otherwise select existing
    new_cat_btn = page.locator("button").filter(has_text="New Category")
    if new_cat_btn.is_visible():
        new_cat_btn.click()
        page.locator(".form-input").nth(1).fill("fitness")
    else:
        # Select existing category
        selects = page.locator(".form-select")
        if selects.count() > 0:
            selects.first.select_option(index=1)
    # Submit
    page.locator("button[type='submit']").click()
    page.wait_for_timeout(2000)
    # Verify tracker appears in config list
    assert page.locator(".tracker-config-item").filter(has_text="Exercise").is_visible()
