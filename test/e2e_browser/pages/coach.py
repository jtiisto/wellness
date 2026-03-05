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

    def fill_set_weight(self, set_index, value):
        self.page.locator(".set-input.weight").nth(set_index).fill(str(value))

    def fill_set_reps(self, set_index, value):
        self.page.locator(".set-input.reps").nth(set_index).fill(str(value))

    def fill_feedback(self, field_label, text):
        field = self.page.locator(".feedback-field").filter(has_text=field_label)
        field.locator("textarea").fill(text)

    def is_empty_state(self):
        return self.page.locator(".empty-state").is_visible()
