class AppShellPage:
    def __init__(self, page):
        self.page = page

    def navigate_to(self, module_name):
        btn = self.page.locator("nav.nav-bar button.nav-btn").filter(has_text=module_name)
        btn.click()
        self.page.wait_for_timeout(500)

    def get_active_module(self):
        return self.page.locator("nav.nav-bar button.nav-btn.active .nav-label").text_content()

    def open_settings(self):
        self.page.locator("button.settings-btn").click()
        self.page.wait_for_selector(".settings-menu", timeout=3000)

    def close_settings(self):
        self.page.locator(".settings-menu .close-btn").click()
        self.page.wait_for_selector(".settings-menu", state="hidden", timeout=3000)

    def is_loaded(self):
        return self.page.locator(".shell").is_visible()

    def get_nav_labels(self):
        return self.page.locator("nav.nav-bar button.nav-btn .nav-label").all_text_contents()
