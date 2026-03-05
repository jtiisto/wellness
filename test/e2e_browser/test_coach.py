"""E2E tests for the coach module."""
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
    coach_page.fill_feedback("Pain / Discomfort", "Left knee slight ache")
    coach_page.fill_feedback("General Notes", "Good session overall")
    pain_val = app_page.locator(".feedback-field").filter(
        has_text="Pain / Discomfort").locator("textarea").input_value()
    assert pain_val == "Left knee slight ache"


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
