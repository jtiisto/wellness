"""E2E tests for the journal module."""
import json
from datetime import date, datetime, timedelta

import pytest
import requests as http_requests
from pages.app_shell import AppShellPage
from pages.journal import JournalPage



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


@pytest.fixture
def journal_with_accumulator(page, app_server, seeded_journal):
    """Seed an accumulator tracker (e.g. 'Protein') and load the journal."""
    base = app_server["url"]
    client_id = seeded_journal["client_id"]
    today = datetime.now().strftime("%Y-%m-%d")

    http_requests.post(f"{base}/api/journal/sync/update", json={
        "clientId": client_id,
        "config": [{
            "id": "tracker-protein",
            "name": "Protein",
            "category": "nutrition",
            "type": "quantifiable",
            "unit": "g",
            "accumulator": True,
        }],
        "days": {today: {"tracker-protein": {
            "value": 100, "completed": True,
        }}},
    })

    page.goto(app_server["url"])
    page.wait_for_selector(".shell", timeout=10000)
    shell = AppShellPage(page)
    shell.navigate_to("Journal")
    journal = JournalPage(page)
    journal.wait_for_loaded()
    journal.wait_for_trackers()
    return {"journal": journal, "today": today}


def test_accumulator_add_uses_styled_modal(journal_with_accumulator):
    """Tapping + opens an in-app modal (not the native browser prompt) and adds the value."""
    journal = journal_with_accumulator["journal"]
    page = journal.page

    row = page.locator(".tracker-item").filter(has_text="Protein")
    # Open the modal by clicking the accumulator + button
    row.locator(".tracker-accum-btn").click()
    page.wait_for_timeout(200)

    # Styled modal renders (not a native dialog)
    overlay = page.locator(".modal-overlay")
    assert overlay.count() == 1
    assert overlay.locator(".modal-title").text_content() == "Add to Protein"

    # Type an increment and submit
    overlay.locator("input[type='number']").fill("25")
    overlay.locator("button[type='submit']").click()
    page.wait_for_timeout(300)

    # Modal closes and value updates from 100 → 125
    assert page.locator(".modal-overlay").count() == 0
    assert row.locator("input[type='number']").input_value() == "125"


def test_accumulator_modal_dismisses_via_overlay(journal_with_accumulator):
    """Clicking the overlay backdrop closes the modal without committing."""
    journal = journal_with_accumulator["journal"]
    page = journal.page

    row = page.locator(".tracker-item").filter(has_text="Protein")
    row.locator(".tracker-accum-btn").click()
    page.wait_for_timeout(200)

    overlay = page.locator(".modal-overlay")
    assert overlay.count() == 1

    # Click on the overlay (outside the modal-content) to dismiss
    overlay.click(position={"x": 10, "y": 10})
    page.wait_for_timeout(200)
    assert page.locator(".modal-overlay").count() == 0
    # Value untouched
    assert row.locator("input[type='number']").input_value() == "100"


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
        },
        {
            "id": "tracker-reflection",
            "name": "Reflection",
            "category": "wellbeing",
            "type": "note",
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
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/delta")
    entry = resp.json().get("days", {}).get(today, {}).get("tracker-reflection")
    assert entry is not None and entry.get("completed") is True

    # Clearing the note should flip completed to False
    refl_row.locator("textarea").fill("")
    page.wait_for_timeout(3500)
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/delta")
    entry = resp.json().get("days", {}).get(today, {}).get("tracker-reflection")
    assert entry is not None and entry.get("completed") is False


def test_stale_upload_recovers_in_cycle_via_server_row(
        journal_page, app_server, seeded_journal):
    """Concurrent third-party write → client's stale upload rejected → client
    silently adopts the server's row via the rejection's `serverRow`. No
    yellow status, no notification, no manual conflict resolution.

    The legacy two-client conflict-resolution UI is gone. The new protocol
    handles the underlying race by having the server reject any upload whose
    `_baseLastModifiedAt` is older than the stored timestamp and return the
    current row inline, which the client folds into local state in the same
    sync cycle.
    """
    import time

    page = journal_page.page
    base = app_server["url"]
    today = datetime.now().strftime("%Y-%m-%d")

    # Wait for the initial sync so the client has a known base token for the
    # Water Intake entry.
    page.wait_for_selector(".sync-dot.green", timeout=10000)

    # Read the current server stamp the client has cached for today's entry.
    current = http_requests.get(f"{base}/api/journal/sync/delta").json()
    server_entry = current.get("days", {}).get(today, {}).get("tracker-e2e", {})
    base_ts = server_entry.get("lastModifiedAt")
    assert base_ts, "seeded entry should have a server-stamped timestamp"

    # Tiny sleep so the third-party POST's new stamp is strictly later than
    # base_ts. The protocol's equal-timestamps-accept rule means an
    # immediately-following POST in the same millisecond could match base_ts
    # exactly and not actually advance the stored stamp.
    time.sleep(0.05)

    # Simulate a third-party write to the same entry.
    third_party_payload = {
        "clientId": "remote-device",
        "config": [],
        "days": {today: {"tracker-e2e": {
            "value": 99, "completed": True,
            "_baseLastModifiedAt": base_ts,
        }}},
    }
    resp = http_requests.post(f"{base}/api/journal/sync/update", json=third_party_payload)
    assert resp.status_code == 200
    accepted = resp.json().get("acceptedEntries") or []
    assert len(accepted) == 1, (
        f"Third-party write rejected: {resp.json()}")
    new_stamp = accepted[0]["lastModifiedAt"]
    assert new_stamp > base_ts, (
        f"Server stamp must advance after third-party write: {base_ts} → {new_stamp}")

    # Edit locally to a different value. The client's `_baseLastModifiedAt`
    # for this row is still base_ts (stale), so the upload will be rejected
    # by the server's optimistic-concurrency check.
    journal_page.set_tracker_value("Water Intake", 33)
    # Blur the input so NumericInput's focused-while-editing guard releases
    # — without this, sync-driven updates to the value prop are ignored.
    water_row = page.locator(".tracker-item").filter(has_text="Water Intake")
    water_row.locator("input[type='number']").blur()

    # Trigger a sync via visibility change (faster than waiting for poll)
    page.evaluate("""() => {
        Object.defineProperty(document, 'visibilityState', {
            value: 'visible', writable: true, configurable: true
        });
        document.dispatchEvent(new Event('visibilitychange'));
    }""")

    # Wait for the debounced sync + in-cycle recovery to complete.
    page.wait_for_timeout(6000)

    # The yellow sync status no longer exists in the new protocol
    assert page.locator(".sync-dot.yellow").count() == 0, (
        "Yellow conflict status should be retired in the new protocol")

    # Server still has the third-party value (99). The client's stale upload
    # was rejected and the client adopted the server's row.
    resp = http_requests.get(f"{base}/api/journal/sync/delta")
    entry = resp.json().get("days", {}).get(today, {}).get("tracker-e2e")
    assert entry is not None
    assert entry["value"] == 99, (
        f"Server row should be unchanged after stale rejection; got {entry['value']}")

    # Client UI shows the server's value (99) — in-cycle recovery applied
    # the rejected response's `serverRow` to local state.
    page.wait_for_selector(".sync-dot.green", timeout=10000)
    water_row = page.locator(".tracker-item").filter(has_text="Water Intake")
    assert water_row.locator("input[type='number']").input_value() == "99"


def test_forcesync_stale_upload_recovers_in_cycle(
        journal_page, app_server, seeded_journal):
    """Force Sync exercises the same in-cycle recovery as triggerSync:
    a stale base token gets rejected, the serverRow is applied to local
    state, and the dirty flag is cleared via generation snapshot.

    forceSync runs a parallel code path from triggerSync; this confirms the
    optimistic-concurrency contract is honored in both directions.
    """
    import time
    from pages.app_shell import AppShellPage

    page = journal_page.page
    base = app_server["url"]
    today = datetime.now().strftime("%Y-%m-%d")

    # Wait for initial sync
    page.wait_for_selector(".sync-dot.green", timeout=10000)

    # Read the seed's server stamp
    current = http_requests.get(f"{base}/api/journal/sync/delta").json()
    server_entry = current.get("days", {}).get(today, {}).get("tracker-e2e", {})
    base_ts = server_entry.get("lastModifiedAt")
    assert base_ts, "seeded entry should have a server-stamped timestamp"

    # Third-party write to make the client's base token stale
    time.sleep(0.05)
    resp = http_requests.post(f"{base}/api/journal/sync/update", json={
        "clientId": "remote-device",
        "config": [],
        "days": {today: {"tracker-e2e": {
            "value": 77, "completed": True,
            "_baseLastModifiedAt": base_ts,
        }}},
    })
    assert resp.status_code == 200
    assert len(resp.json().get("acceptedEntries") or []) == 1

    # Local edit — dirty entry with stale base
    journal_page.set_tracker_value("Water Intake", 42)
    page.locator(".tracker-item").filter(
        has_text="Water Intake").locator("input[type='number']").blur()

    # Trigger Force Sync via the tools menu
    page.once("dialog", lambda dialog: dialog.accept())
    shell = AppShellPage(page)
    shell.open_tools()
    page.locator(".tools-item").filter(has_text="Force Sync").click()

    # Wait for forceSync to complete: full pull + upload-with-rejection +
    # serverRow applied + dirty cleared via generation check.
    page.wait_for_timeout(5000)
    page.wait_for_selector(".sync-dot.green", timeout=10000)

    # Server unchanged — local stale upload was rejected
    resp = http_requests.get(f"{base}/api/journal/sync/delta")
    entry = resp.json().get("days", {}).get(today, {}).get("tracker-e2e")
    assert entry is not None
    assert entry["value"] == 77, (
        f"Server should still have the third-party value (77); got {entry['value']}")

    # Client UI adopted the serverRow in-cycle
    water_row = page.locator(".tracker-item").filter(has_text="Water Intake")
    assert water_row.locator("input[type='number']").input_value() == "77"
    # No yellow status — the rejection was handled silently
    assert page.locator(".sync-dot.yellow").count() == 0


def _seed_disposable_tracker(app_server, seeded_journal, tracker_id, name):
    """Seed a throwaway tracker via API. Avoids mutating the shared seed."""
    http_requests.post(f"{app_server['url']}/api/journal/sync/update", json={
        "clientId": seeded_journal["client_id"],
        "config": [{
            "id": tracker_id, "name": name, "category": "disposable",
            "type": "simple",
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
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/delta")
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
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/delta")
    tracker_ids = [t["id"] for t in resp.json().get("config", [])]
    assert "tracker-to-delete" not in tracker_ids


def test_schedule_days_and_polarity_via_config(journal_page, app_server):
    """A Mon–Fri tracker created via config round-trips scheduleHistory +
    polarity to the server, shows a schedule/polarity summary in the config
    list, and is hidden on weekend dates in the grid (shown on weekdays).
    """
    page = journal_page.page
    journal_page.open_config()
    journal_page.add_tracker(
        "Meds", "health", tracker_type="simple",
        days=[1, 2, 3, 4, 5], polarity="positive")

    # Sync so the tracker reaches the server and dirty state clears (which
    # unlocks past dates in the grid).
    page.wait_for_timeout(3500)
    page.wait_for_selector(".sync-dot.green", timeout=10000)

    # Config list summarizes the schedule + polarity.
    meta = page.locator(".tracker-config-item").filter(
        has_text="Meds").locator(".tracker-config-meta").inner_text()
    assert "Mon–Fri" in meta
    assert "Positive" in meta

    # Server round-trip: scheduleHistory (Mon–Fri) + polarity survive sync.
    resp = http_requests.get(f"{app_server['url']}/api/journal/sync/delta")
    tracker = next(t for t in resp.json().get("config", []) if t["name"] == "Meds")
    assert tracker.get("polarity") == "positive"
    hist = tracker.get("scheduleHistory")
    assert hist and hist[-1]["days"] == [1, 2, 3, 4, 5]

    # Grid visibility: pick a weekend and a weekday date from the last-7-days
    # selector (index 0 = 6 days ago .. index 6 = today). Python weekday():
    # Mon=0..Sun=6, so >=5 is Sat/Sun.
    dates = [date.today() - timedelta(days=(6 - i)) for i in range(7)]
    weekend_idx = next(i for i, d in enumerate(dates) if d.weekday() >= 5)
    weekday_idx = next(i for i, d in enumerate(dates) if d.weekday() < 5)

    page.reload()
    page.wait_for_selector(".shell", timeout=10000)
    AppShellPage(page).navigate_to("Journal")
    journal_page.wait_for_loaded()
    journal_page.wait_for_trackers()
    page.wait_for_selector(".sync-dot.green", timeout=10000)

    journal_page.select_date(weekend_idx)
    page.wait_for_timeout(400)
    weekend_names = journal_page.get_tracker_names()
    assert "Water Intake" in weekend_names  # grid rendered
    assert "Meds" not in weekend_names

    journal_page.select_date(weekday_idx)
    page.wait_for_timeout(400)
    assert "Meds" in journal_page.get_tracker_names()


def test_off_schedule_entry_keeps_tracker_visible(journal_page, app_server, seeded_journal):
    """Entry-exists visibility override: a weekday-only tracker with a logged
    entry on a weekend date stays visible on that date (and after reload), while
    a weekend date with no entry keeps it hidden. On-schedule weekdays show it
    normally.
    """
    page = journal_page.page
    base = app_server["url"]
    client_id = seeded_journal["client_id"]

    # Last-7-days indices (0 = 6 days ago .. 6 = today). Python weekday():
    # Mon=0..Sun=6, so Sat=5, Sun=6.
    dates = [date.today() - timedelta(days=(6 - i)) for i in range(7)]
    sat_idx = next(i for i, d in enumerate(dates) if d.weekday() == 5)
    sun_idx = next(i for i, d in enumerate(dates) if d.weekday() == 6)
    weekday_idx = next(i for i, d in enumerate(dates) if d.weekday() < 5)
    sat_str = dates[sat_idx].strftime("%Y-%m-%d")

    # Seed a Mon–Fri tracker plus an entry on the Saturday (off-schedule).
    http_requests.post(f"{base}/api/journal/sync/update", json={
        "clientId": client_id,
        "config": [{
            "id": "tracker-weekday",
            "name": "Weekday Only",
            "category": "health",
            "type": "simple",
            "scheduleHistory": [{"effectiveFrom": "0000-01-01", "days": [1, 2, 3, 4, 5]}],
        }],
        "days": {sat_str: {"tracker-weekday": {"completed": True}}},
    })

    # Reload so the browser pulls the new tracker + off-schedule entry.
    page.reload()
    page.wait_for_selector(".shell", timeout=10000)
    AppShellPage(page).navigate_to("Journal")
    journal_page.wait_for_loaded()
    journal_page.wait_for_trackers()
    page.wait_for_selector(".sync-dot.green", timeout=10000)

    # On-schedule weekday → visible.
    journal_page.select_date(weekday_idx)
    page.wait_for_timeout(400)
    assert "Weekday Only" in journal_page.get_tracker_names()

    # Off-schedule Saturday but has an entry → visible (the override).
    journal_page.select_date(sat_idx)
    page.wait_for_timeout(400)
    assert "Weekday Only" in journal_page.get_tracker_names()

    # Off-schedule Sunday with no entry → hidden.
    journal_page.select_date(sun_idx)
    page.wait_for_timeout(400)
    names = journal_page.get_tracker_names()
    assert "Water Intake" in names  # grid rendered
    assert "Weekday Only" not in names


def test_data_export_includes_schedule_and_polarity(journal_page, app_server, seeded_journal):
    """The full-data export round-trips a tracker's scheduleHistory + polarity.
    They ride tracker_config, which the export dumps verbatim.
    """
    page = journal_page.page
    base = app_server["url"]
    client_id = seeded_journal["client_id"]

    http_requests.post(f"{base}/api/journal/sync/update", json={
        "clientId": client_id,
        "config": [{
            "id": "tracker-export",
            "name": "Export Me",
            "category": "health",
            "type": "simple",
            "scheduleHistory": [{"effectiveFrom": "0000-01-01", "days": [1, 2, 3, 4, 5]}],
            "polarity": "negative",
        }],
        "days": {},
    })

    # Reload so the browser pulls the tracker into its LocalForage config.
    page.reload()
    page.wait_for_selector(".shell", timeout=10000)
    shell = AppShellPage(page)
    shell.navigate_to("Journal")
    journal_page.wait_for_loaded()
    journal_page.wait_for_trackers()
    page.wait_for_selector(".sync-dot.green", timeout=10000)

    # Trigger the full-data export and capture the download.
    shell.open_tools()
    with page.expect_download() as download_info:
        page.locator(".tools-item").filter(has_text="Export All Data").click()
    with open(download_info.value.path()) as f:
        data = json.load(f)

    config = data["journal"]["tracker_config"]
    tracker = next(t for t in config if t["id"] == "tracker-export")
    assert tracker["polarity"] == "negative"
    assert tracker["scheduleHistory"][-1]["days"] == [1, 2, 3, 4, 5]


def test_legacy_weekly_tracker_normalizes_and_converges(journal_page, app_server, seeded_journal):
    """A legacy frequency:'weekly'+weeklyDay tracker delivered by the server is
    normalized on the client to a canonical scheduleHistory genesis segment and
    uploaded, so the server converges (frequency/weeklyDay stripped).
    """
    page = journal_page.page
    base = app_server["url"]
    client_id = seeded_journal["client_id"]

    # Seed a legacy weekly tracker (frequency/weeklyDay, no scheduleHistory).
    http_requests.post(f"{base}/api/journal/sync/update", json={
        "clientId": client_id,
        "config": [{
            "id": "tracker-legacy", "name": "Legacy Weekly", "category": "health",
            "type": "simple", "frequency": "weekly", "weeklyDay": 1,
        }],
        "days": {},
    })

    # Reload so the client pulls the legacy tracker, normalizes it on delta
    # apply, and uploads the cleaned shape.
    page.reload()
    page.wait_for_selector(".shell", timeout=10000)
    AppShellPage(page).navigate_to("Journal")
    journal_page.wait_for_loaded()
    journal_page.wait_for_trackers()
    page.wait_for_timeout(4000)  # normalize + debounced upload
    page.wait_for_selector(".sync-dot.green", timeout=10000)

    resp = http_requests.get(f"{base}/api/journal/sync/delta")
    tracker = next(t for t in resp.json()["config"] if t["id"] == "tracker-legacy")
    assert "frequency" not in tracker
    assert "weeklyDay" not in tracker
    assert tracker["scheduleHistory"] == [{"effectiveFrom": "0000-01-01", "days": [1]}]


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
