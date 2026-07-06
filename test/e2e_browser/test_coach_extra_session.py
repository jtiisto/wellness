"""E2E tests for the ad-hoc extra Zone 2 session on rest days.

Today (editable, no plan) offers an "Add Zone 2 session" button on the empty
state; Save commits the draft to the log store and syncs; Delete tombstones
the entry and the deletion propagates to the server (verified by reload).
"""
from datetime import datetime, timedelta

import pytest
from pages.app_shell import AppShellPage
from pages.coach import CoachPage


@pytest.fixture
def rest_day_page(app_page, coach_rest_day):
    """Navigate to Coach with an all-rest-days database."""
    shell = AppShellPage(app_page)
    shell.navigate_to("Coach")
    coach = CoachPage(app_page)
    coach.wait_for_loaded()
    app_page.wait_for_selector(".empty-state", timeout=10000)
    return coach


def test_add_button_on_todays_empty_state(rest_day_page):
    """Today's rest day shows the empty state AND the add button."""
    assert rest_day_page.is_empty_state()
    assert rest_day_page.has_extra_session_button()


def test_no_add_button_on_past_rest_day(rest_day_page, app_page):
    """A past rest day keeps the plain empty state — no add button."""
    yesterday = datetime.now() - timedelta(days=1)
    prev_months = 1 if yesterday.month != datetime.now().month else 0
    rest_day_page.select_calendar_day(
        yesterday.strftime("%Y-%m-%d"), prev_months=prev_months)
    app_page.wait_for_selector(".empty-state", timeout=5000)
    assert not rest_day_page.has_extra_session_button()


def test_save_requires_duration(rest_day_page, app_page):
    """The draft's Save button stays disabled until a duration is entered."""
    rest_day_page.add_extra_session()
    assert not rest_day_page.is_extra_save_enabled()
    rest_day_page.fill_extra_field("Avg HR", 128)
    assert not rest_day_page.is_extra_save_enabled()
    rest_day_page.fill_extra_field("Duration (min)", 45)
    assert rest_day_page.is_extra_save_enabled()


def test_draft_delete_discards_without_saving(rest_day_page, app_page):
    """Deleting an unsaved draft returns to the add button; nothing persists."""
    rest_day_page.add_extra_session()
    rest_day_page.fill_extra_field("Duration (min)", 45)
    rest_day_page.delete_extra_session()
    app_page.wait_for_selector(".extra-session-add-btn", timeout=3000)
    assert not rest_day_page.has_extra_session_card()


def test_save_syncs_and_persists_across_reload(rest_day_page, app_page):
    """Save → sync → reload: the session comes back from the server."""
    rest_day_page.add_extra_session()
    rest_day_page.fill_extra_field("Duration (min)", 45)
    rest_day_page.fill_extra_field("Avg HR", 128)
    rest_day_page.fill_extra_field("Max HR", 142)
    rest_day_page.save_extra_session()

    app_page.wait_for_selector(
        ".extra-session-card:not(.extra-session-card--draft)", timeout=5000)
    assert not rest_day_page.is_empty_state()
    app_page.wait_for_selector(".sync-dot.green", timeout=15000)

    app_page.reload()
    app_page.wait_for_selector(".coach", timeout=10000)
    app_page.wait_for_selector(
        ".extra-session-card:not(.extra-session-card--draft)", timeout=10000)
    assert rest_day_page.get_extra_session_duration() == "45"


def test_delete_propagates_to_server(rest_day_page, app_page):
    """Deleting a saved+synced session removes it server-side (survives reload)."""
    rest_day_page.add_extra_session()
    rest_day_page.fill_extra_field("Duration (min)", 45)
    rest_day_page.save_extra_session()
    app_page.wait_for_selector(".sync-dot.green", timeout=15000)

    rest_day_page.delete_extra_session()
    app_page.wait_for_selector(".empty-state", timeout=5000)
    assert rest_day_page.has_extra_session_button()
    app_page.wait_for_selector(".sync-dot.green", timeout=15000)

    app_page.reload()
    app_page.wait_for_selector(".coach", timeout=10000)
    app_page.wait_for_selector(".empty-state", timeout=10000)
    assert not rest_day_page.has_extra_session_card()


def test_calendar_dot_shows_completed_for_extra_session(rest_day_page, app_page):
    """A rest day with a logged extra session earns the completed status dot."""
    rest_day_page.add_extra_session()
    rest_day_page.fill_extra_field("Duration (min)", 45)
    rest_day_page.save_extra_session()
    app_page.wait_for_selector(
        ".extra-session-card:not(.extra-session-card--draft)", timeout=5000)

    # Trigger dot (current date) reflects the new status without opening the modal.
    app_page.wait_for_selector(".calendar-status-dot.completed", timeout=5000)

    rest_day_page.open_calendar()
    assert app_page.locator(".calendar-day.today.status-completed").is_visible()
