class CoachPage:
    def __init__(self, page):
        self.page = page

    def wait_for_loaded(self):
        self.page.wait_for_selector(".coach", timeout=5000)

    def get_sync_dot_class(self):
        dot = self.page.locator(".sync-dot")
        classes = dot.get_attribute("class") or ""
        for color in ["green", "red", "yellow", "gray"]:
            if color in classes:
                return color
        return None

    def open_calendar(self):
        self.page.locator(".calendar-trigger").click()
        self.page.wait_for_selector(".calendar-modal", timeout=3000)

    def click_today(self):
        self.page.locator(".calendar-today-btn").click()

    def get_workout_title(self):
        title = self.page.locator(".workout-day-name")
        if title.is_visible():
            return title.text_content()
        return None

    def get_block_titles(self):
        return self.page.locator(".block-title").all_text_contents()

    def get_exercise_names(self):
        return self.page.locator(".exercise-name").all_text_contents()

    def expand_exercise(self, name):
        self.page.locator(".exercise-item").filter(has_text=name).locator(".exercise-header").click()

    def get_exercise_target(self, name):
        """Read the prescription text shown in the exercise header."""
        return self.page.locator(".exercise-item").filter(has_text=name).locator(".exercise-target").text_content()

    def is_exercise_marked_complete(self, name):
        """True when the exercise-item has the .completed class."""
        item = self.page.locator(".exercise-item").filter(has_text=name)
        classes = item.get_attribute("class") or ""
        return "completed" in classes.split()

    def get_exercise_progress(self, name):
        """Return the .exercise-progress text or None if not visible."""
        item = self.page.locator(".exercise-item").filter(has_text=name)
        progress = item.locator(".exercise-progress")
        return progress.text_content() if progress.count() else None

    def has_cardio_entry(self, name):
        """True when the named exercise's expanded body shows a .cardio-entry."""
        return self.page.locator(".exercise-item").filter(has_text=name).locator(".cardio-entry").is_visible()

    def fill_cardio_duration(self, name, value):
        """Fill the Duration field (first numeric input) inside the named exercise."""
        self.page.locator(".exercise-item").filter(has_text=name).locator(
            ".cardio-entry input[type='number']").first.fill(str(value))

    def fill_set_weight(self, set_index, value):
        self.page.locator(".sets-grid-input[data-col='weight']").nth(set_index).fill(str(value))

    def fill_set_reps(self, set_index, value):
        self.page.locator(".sets-grid-input[data-col='reps']").nth(set_index).fill(str(value))

    def fill_feedback(self, field_label, text):
        field = self.page.locator(".feedback-field").filter(has_text=field_label)
        field.locator("textarea").fill(text)

    def is_empty_state(self):
        return self.page.locator(".empty-state").is_visible()

    def is_start_gate_active(self):
        """Check if the start gate is active (exercises read-only)."""
        return self.page.locator(".workout-view.read-only").is_visible()

    def start_workout(self):
        """Click Start Workout to unlock exercises. Expands the header first
        only if it is not already expanded (it auto-expands when the gate
        is active, so toggling unconditionally would collapse it)."""
        body = self.page.locator(".workout-header-body--open")
        if body.count() == 0:
            toggle = self.page.locator(".workout-header-toggle")
            toggle.click()
            self.page.wait_for_timeout(300)
        start_btn = self.page.locator(".hook-btn--start")
        start_btn.click()
        self.page.wait_for_timeout(500)

    def end_workout(self):
        """Click End Workout (header must already be expanded)."""
        end_btn = self.page.locator(".hook-btn--end")
        end_btn.click()
        self.page.wait_for_timeout(500)

    def is_workout_started(self):
        """Check if Start Workout button shows the fired (green) state."""
        btn = self.page.locator(".hook-btn--start")
        if not btn.is_visible():
            return False
        classes = btn.get_attribute("class") or ""
        return "--fired" in classes
