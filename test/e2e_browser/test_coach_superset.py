"""E2E tests for the structured superset_group rendering.

Phase 5 of the superset-groups feature. The seeded plan from `seeded_coach_db`
includes two strength exercises (DB Bench Press, Bent Row) sharing
`superset_group="A"`. These tests verify they render inside a single
`.superset-group` wrapper with a label and that the legacy `data-pair`
attribute is gone.
"""
import pytest
from pages.app_shell import AppShellPage
from pages.coach import CoachPage

pytestmark = pytest.mark.e2e


@pytest.fixture
def coach_page(app_page, seeded_coach_db):
    shell = AppShellPage(app_page)
    shell.navigate_to("Coach")
    coach = CoachPage(app_page)
    coach.wait_for_loaded()
    app_page.wait_for_timeout(2000)
    return coach


def test_superset_group_wrapper_renders(coach_page, app_page):
    """Two exercises sharing superset_group='A' sit inside one .superset-group."""
    groups = app_page.locator(".superset-group")
    assert groups.count() == 1
    assert groups.first.get_attribute("data-superset-group") == "A"


def test_superset_group_label_uses_superset_prefix(coach_page, app_page):
    """Bare letter labels like 'A' display as 'Superset A'."""
    label = app_page.locator(".superset-group__label").first.text_content()
    assert label.strip() == "Superset A"


def test_superset_group_contains_both_members(coach_page, app_page):
    """Both DB Bench Press and Bent Row render inside the same wrapper."""
    group = app_page.locator(".superset-group")
    members = group.locator(".exercise-item")
    assert members.count() == 2
    names = group.locator(".exercise-name").all_text_contents()
    assert "DB Bench Press" in names
    assert "Bent Row" in names


def test_singletons_not_inside_superset_group(coach_page, app_page):
    """Exercises without superset_group are NOT wrapped (KB Goblet Squat is solo)."""
    squat_item = app_page.locator(".exercise-item").filter(has_text="KB Goblet Squat")
    # The .exercise-item should not have any .superset-group ancestor in this block.
    # Locator chaining `:scope >> ...` is awkward in Playwright; we verify by
    # asserting no .superset-group encloses this specific item.
    parent_groups = app_page.locator(".superset-group .exercise-item").filter(
        has_text="KB Goblet Squat"
    )
    assert parent_groups.count() == 0
    # And the squat itself is still rendered in the block
    assert squat_item.is_visible()


def test_legacy_data_pair_attribute_removed(coach_page, app_page):
    """The pre-refactor `data-pair` attribute should no longer appear anywhere."""
    paired = app_page.locator("[data-pair]")
    assert paired.count() == 0


def test_freeform_label_via_evaluate(app_page):
    """Compound labels like 'Triplet A' display as-is, no 'Superset' prefix.

    Tests the SupersetGroup component directly via page.evaluate, since seeding
    a triplet would require a different fixture. Imports the JSDOM-friendly
    helper from utils.js instead of asserting against a rendered triplet.
    """
    out = app_page.evaluate(
        """async () => {
            const { groupExercises } = await import('/js/coach/utils.js');
            return groupExercises([
                { id: 'a', name: 'A', superset_group: 'Triplet A' },
                { id: 'b', name: 'B', superset_group: 'Triplet A' },
                { id: 'c', name: 'C', superset_group: 'Triplet A' },
                { id: 'd', name: 'D' }
            ]);
        }"""
    )
    assert len(out) == 2
    assert out[0]["kind"] == "group"
    assert out[0]["label"] == "Triplet A"
    assert len(out[0]["exercises"]) == 3
    assert out[1]["kind"] == "single"
    assert out[1]["exercise"]["id"] == "d"


def test_group_exercises_breaks_run_on_label_change(app_page):
    """Two consecutive groups with different labels stay separate."""
    out = app_page.evaluate(
        """async () => {
            const { groupExercises } = await import('/js/coach/utils.js');
            return groupExercises([
                { id: 'a', name: 'A', superset_group: 'A' },
                { id: 'b', name: 'B', superset_group: 'A' },
                { id: 'c', name: 'C', superset_group: 'B' },
                { id: 'd', name: 'D', superset_group: 'B' }
            ]);
        }"""
    )
    assert len(out) == 2
    assert out[0]["label"] == "A"
    assert len(out[0]["exercises"]) == 2
    assert out[1]["label"] == "B"
    assert len(out[1]["exercises"]) == 2


def test_group_exercises_breaks_on_unlabeled_in_middle(app_page):
    """An unlabeled exercise between two same-label exercises breaks the run."""
    out = app_page.evaluate(
        """async () => {
            const { groupExercises } = await import('/js/coach/utils.js');
            return groupExercises([
                { id: 'a', name: 'A', superset_group: 'A' },
                { id: 'b', name: 'B' },
                { id: 'c', name: 'C', superset_group: 'A' }
            ]);
        }"""
    )
    assert len(out) == 3
    assert out[0]["kind"] == "group"
    assert out[1]["kind"] == "single"
    assert out[2]["kind"] == "group"
    # Both groups have label "A" but they are separate runs
    assert out[0]["label"] == "A"
    assert out[2]["label"] == "A"
