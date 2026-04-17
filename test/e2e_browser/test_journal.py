"""E2E tests for the journal module."""
import pytest
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
