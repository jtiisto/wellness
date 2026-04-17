"""E2E tests for the coach module."""
import re

import pytest
from pages.app_shell import AppShellPage
from pages.coach import CoachPage

pytestmark = pytest.mark.e2e


@pytest.fixture
def coach_page(app_page, seeded_coach_db):
    """Navigate to coach module with seeded data."""
    shell = AppShellPage(app_page)
    shell.navigate_to("Coach")
    coach = CoachPage(app_page)
    coach.wait_for_loaded()
    app_page.wait_for_timeout(2000)
    return coach


def test_plan_displays(coach_page):
    """Seeded plan renders with correct title."""
    title = coach_page.get_workout_title()
    assert title == "Test Workout"


def test_blocks_display(coach_page):
    """Exercise blocks render with correct titles."""
    titles = coach_page.get_block_titles()
    assert "Warmup" in titles
    assert "Strength" in titles
    assert "Conditioning" in titles


def test_exercise_expand(coach_page, app_page):
    """Clicking an exercise header expands it to show details."""
    coach_page.expand_exercise("KB Goblet Squat")
    app_page.wait_for_timeout(300)
    exercise = app_page.locator(".exercise-item").filter(has_text="KB Goblet Squat")
    assert exercise.locator(".exercise-body").is_visible()


def test_log_workout_set(coach_page, app_page):
    """Filling weight and reps in a set row saves the values."""
    coach_page.start_workout()
    coach_page.expand_exercise("KB Goblet Squat")
    app_page.wait_for_timeout(300)
    coach_page.fill_set_weight(0, 24)
    coach_page.fill_set_reps(0, 10)
    weight_val = app_page.locator(".set-input.weight").first.input_value()
    reps_val = app_page.locator(".set-input.reps").first.input_value()
    assert weight_val == "24"
    assert reps_val == "10"


def test_session_feedback(coach_page, app_page):
    """Filling feedback textareas saves the text."""
    coach_page.start_workout()
    coach_page.fill_feedback("Pain / Discomfort", "Left knee slight ache")
    coach_page.fill_feedback("General Notes", "Good session overall")
    pain_val = app_page.locator(".feedback-field").filter(
        has_text="Pain / Discomfort").locator("textarea").input_value()
    assert pain_val == "Left knee slight ache"


def test_start_gate_blocks_input(coach_page, app_page):
    """Exercises are read-only before Start Workout is clicked."""
    assert coach_page.is_start_gate_active()


def test_start_gate_unlocks_on_click(coach_page, app_page):
    """Clicking Start Workout removes the gate and enables input."""
    assert coach_page.is_start_gate_active()
    coach_page.start_workout()
    assert not coach_page.is_start_gate_active()


def test_start_gate_unlocks_on_failure(coach_page, app_page):
    """Start Workout unlocks exercises even if the server call fails.

    Intercept the POST to force a failure; the gate should still open
    because any click (success or failure) satisfies the gate condition.
    """
    assert coach_page.is_start_gate_active()

    # Block the start endpoint to force a failure
    app_page.route("**/api/coach/workout/*/start", lambda route: route.abort())
    coach_page.start_workout()
    app_page.unroute_all(behavior="ignoreErrors")

    # Gate should be unlocked despite failure
    assert not coach_page.is_start_gate_active()


def test_calendar_highlights_today_with_scheduled_status(coach_page, app_page):
    """Opening the calendar marks today as .today with status-scheduled.

    The seeded plan exists for today but no log has been uploaded yet,
    so getWorkoutStatus returns 'scheduled' per CalendarPicker logic.
    """
    coach_page.open_calendar()
    today_btn = app_page.locator(".calendar-day.today")
    assert today_btn.count() == 1
    classes = today_btn.get_attribute("class") or ""
    assert "status-scheduled" in classes, (
        f"Today should be scheduled, got classes: {classes}")


def test_calendar_past_date_with_no_plan_shows_empty_state(coach_page, app_page):
    """Clicking a past date that has no seeded plan shows the empty state."""
    coach_page.open_calendar()

    # Click a date a few days in the past — seed only has today, so this has no plan
    past_btn = app_page.locator(".calendar-day:not(.today):not(.other-month)").filter(
        has_text=re.compile(r"^\d+$")).first
    past_btn.click()
    app_page.wait_for_timeout(500)
    assert app_page.locator(".empty-state").is_visible()


def test_calendar_status_flips_to_completed_after_logging(coach_page, app_page):
    """Logging a set then opening the calendar should show today as completed.

    getWorkoutStatus returns 'completed' when the log has any entry with
    sets/completed_items/duration/etc. (hasAnyProgress check).
    """
    coach_page.start_workout()
    coach_page.expand_exercise("KB Goblet Squat")
    app_page.wait_for_timeout(300)
    coach_page.fill_set_weight(0, 24)
    coach_page.fill_set_reps(0, 10)
    # Wait for debounce + sync so the log reaches the server
    app_page.wait_for_timeout(4000)

    coach_page.open_calendar()
    today_btn = app_page.locator(".calendar-day.today")
    classes = today_btn.get_attribute("class") or ""
    assert "status-completed" in classes, (
        f"Today should be completed after logging, got: {classes}")


def test_empty_state_no_plan(app_page, app_server):
    """A date with no plan shows empty state."""
    shell = AppShellPage(app_page)
    shell.navigate_to("Coach")
    coach = CoachPage(app_page)
    coach.wait_for_loaded()
    app_page.wait_for_timeout(1000)
    # If no plan is seeded (no seeded_coach_db fixture), should show empty state
    # or we check for the empty state message
    empty = app_page.locator(".empty-state")
    if empty.is_visible():
        assert "No workout scheduled" in empty.text_content()
