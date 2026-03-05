class JournalPage:
    def __init__(self, page):
        self.page = page

    def wait_for_loaded(self):
        self.page.wait_for_selector(".journal", timeout=5000)

    def wait_for_trackers(self, timeout=15000):
        """Wait for tracker categories to appear after sync, then expand them."""
        self.page.wait_for_selector(".category-header", timeout=timeout)
        self.expand_all_categories()

    def expand_all_categories(self):
        """Click all collapsed category headers to show tracker items."""
        collapsed = self.page.locator(".category-chevron.collapsed")
        count = collapsed.count()
        for i in range(count):
            self.page.locator(".category-chevron.collapsed").first.click()
            self.page.wait_for_timeout(100)

    def get_sync_dot_class(self):
        dot = self.page.locator(".sync-dot")
        classes = dot.get_attribute("class") or ""
        for color in ["green", "red", "yellow", "gray"]:
            if color in classes:
                return color
        return None

    def get_sync_tooltip(self):
        return self.page.locator(".sync-indicator").get_attribute("title")

    def select_date(self, index):
        self.page.locator(".date-item").nth(index).click()

    def get_tracker_names(self):
        return self.page.locator(".tracker-name").all_text_contents()

    def set_tracker_checkbox(self, tracker_name, checked=True):
        row = self.page.locator(".tracker-item").filter(has_text=tracker_name)
        checkbox = row.locator("input[type='checkbox']")
        if checked:
            checkbox.check()
        else:
            checkbox.uncheck()

    def set_tracker_value(self, tracker_name, value):
        row = self.page.locator(".tracker-item").filter(has_text=tracker_name)
        input_el = row.locator("input[type='number']")
        input_el.fill(str(value))
        # Preact needs an explicit change event dispatch after fill
        input_el.dispatch_event("change")

    def open_config(self):
        self.page.locator(".header-actions button.icon-btn").click()
        self.page.wait_for_selector(".config-screen", timeout=3000)

    def add_tracker(self, name, category, tracker_type="simple"):
        self.page.locator("button.btn-primary").filter(has_text="Add").click()
        self.page.wait_for_selector(".modal-content", timeout=3000)
        self.page.locator(".form-input").first.fill(name)
        selects = self.page.locator(".form-select")
        selects.first.select_option(label=category)
        selects.nth(1).select_option(value=tracker_type)
        self.page.locator("button[type='submit']").click()
        self.page.wait_for_selector(".modal-content", state="hidden", timeout=3000)

    def delete_tracker(self, tracker_name):
        item = self.page.locator(".tracker-config-item").filter(has_text=tracker_name)
        item.locator("button[title='Delete']").click()
