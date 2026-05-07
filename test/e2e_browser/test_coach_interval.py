"""E2E tests for the interval exercise type in the coach module.

Covers the gap fixed by adding an `interval` case to ExerciseItem.renderInputs
and the rounds-based fallback in formatTarget.
"""
import pytest
from pages.app_shell import AppShellPage
from pages.coach import CoachPage

pytestmark = pytest.mark.e2e

INTERVAL_NAME = "Bike Intervals"


@pytest.fixture
def coach_page(app_page, seeded_coach_db):
    shell = AppShellPage(app_page)
    shell.navigate_to("Coach")
    coach = CoachPage(app_page)
    coach.wait_for_loaded()
    app_page.wait_for_timeout(2000)
    return coach


def test_interval_renders_cardio_inputs(coach_page, app_page):
    """Expanding an interval exercise shows the aggregate cardio inputs.

    Before the fix the switch in ExerciseItem.renderInputs fell through to
    `default: return null`, so nothing rendered for `type === 'interval'`.
    """
    coach_page.expand_exercise(INTERVAL_NAME)
    app_page.wait_for_timeout(300)
    assert coach_page.has_cardio_entry(INTERVAL_NAME)
    inputs = app_page.locator(".exercise-item").filter(
        has_text=INTERVAL_NAME).locator(".cardio-entry input[type='number']")
    assert inputs.count() == 3  # Duration, Avg HR, Max HR


def test_interval_header_shows_rounds_fallback(coach_page):
    """With no target_duration_min, the header falls back to rounds × work/rest."""
    target = coach_page.get_exercise_target(INTERVAL_NAME)
    assert target == "4 × 0:30/1:30"


def test_interval_log_duration_marks_progress(coach_page, app_page):
    """Logging duration_min checks the exercise off and shows the ✓ progress badge."""
    coach_page.start_workout()
    coach_page.expand_exercise(INTERVAL_NAME)
    app_page.wait_for_timeout(300)
    coach_page.fill_cardio_duration(INTERVAL_NAME, 12)
    # Blur the field so onValueChange fires and the store updates
    app_page.locator(".exercise-item").filter(
        has_text=INTERVAL_NAME).locator(".exercise-name").click()
    app_page.wait_for_timeout(300)

    assert coach_page.is_exercise_marked_complete(INTERVAL_NAME)
    assert coach_page.get_exercise_progress(INTERVAL_NAME) == "✓"


# ------------------------- pure-JS unit tests via page.evaluate -------------------------
# These exercise the helpers in public/js/coach/utils.js without needing seeded data —
# the running PWA serves the module so we can import it directly in the page context.


def _format_target(page, exercise):
    return page.evaluate(
        """async (ex) => {
            const m = await import('/js/coach/utils.js');
            return m.formatTarget(ex);
        }""",
        exercise,
    )


def _progress(page, exercise, log):
    return page.evaluate(
        """async ([ex, log]) => {
            const m = await import('/js/coach/utils.js');
            return m.getExerciseProgress(ex, log);
        }""",
        [exercise, log],
    )


def _completed(page, exercise, log):
    return page.evaluate(
        """async ([ex, log]) => {
            const m = await import('/js/coach/utils.js');
            return m.isExerciseCompleted(ex, log);
        }""",
        [exercise, log],
    )


def test_format_target_interval_structured_wins_over_duration(app_page):
    """rounds/work/rest take precedence over target_duration_min when both are set."""
    out = _format_target(app_page, {
        "type": "interval",
        "target_duration_min": 20,
        "rounds": 4,
        "work_duration_sec": 30,
        "rest_duration_sec": 90,
    })
    assert out == "4 × 0:30/1:30"


def test_format_target_interval_rounds_work_rest(app_page):
    out = _format_target(app_page, {
        "type": "interval",
        "rounds": 4,
        "work_duration_sec": 30,
        "rest_duration_sec": 90,
    })
    assert out == "4 × 0:30/1:30"


def test_format_target_interval_rounds_work_only(app_page):
    out = _format_target(app_page, {
        "type": "interval",
        "rounds": 4,
        "work_duration_sec": 30,
    })
    assert out == "4 × 0:30"


def test_format_target_interval_vo2_minutes(app_page):
    """Multi-minute work/rest format with mm:ss."""
    out = _format_target(app_page, {
        "type": "interval",
        "rounds": 4,
        "work_duration_sec": 180,
        "rest_duration_sec": 120,
    })
    assert out == "4 × 3:00/2:00"


def test_format_target_interval_rounds_only(app_page):
    out = _format_target(app_page, {"type": "interval", "rounds": 4})
    assert out == "4 rounds"


def test_format_target_interval_empty(app_page):
    out = _format_target(app_page, {"type": "interval"})
    assert out == ""


def test_get_exercise_progress_interval_unlogged(app_page):
    out = _progress(app_page, {"type": "interval", "rounds": 4}, {})
    assert out is None


def test_get_exercise_progress_interval_logged(app_page):
    out = _progress(
        app_page,
        {"type": "interval", "rounds": 4},
        {"duration_min": 12},
    )
    assert out == {"display": "✓", "complete": True}


def test_get_exercise_progress_interval_empty_string(app_page):
    """Empty-string duration_min should not register as logged."""
    out = _progress(
        app_page,
        {"type": "interval", "rounds": 4},
        {"duration_min": ""},
    )
    assert out is None


def test_is_exercise_completed_interval_unlogged(app_page):
    assert _completed(app_page, {"type": "interval"}, None) is False
    assert _completed(app_page, {"type": "interval"}, {}) is False


def test_is_exercise_completed_interval_logged(app_page):
    assert _completed(
        app_page, {"type": "interval"}, {"duration_min": 12}
    ) is True
