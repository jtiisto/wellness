"""The service worker's precache list is generated server-side by walking
public/, so it can never drift from the real asset tree.

The list used to be hand-maintained in sw.js; a new module file
(coach/last-performance.js) was added but not listed, so it was never precached
and Coach broke offline after every deploy. These tests assert the generated
list covers every JS module (including vendored libs) and that the served sw.js
has no unresolved placeholders.
"""
import json
import re
from pathlib import Path

import pytest

import server

REAL_PUBLIC = Path(__file__).resolve().parents[2] / "public"


@pytest.fixture
def real_public(monkeypatch):
    """Point the server's PUBLIC_DIR at the real public/ tree (not the conftest
    temp stub) so the generator and serve_sw see actual assets."""
    monkeypatch.setattr(server, "PUBLIC_DIR", REAL_PUBLIC)
    return REAL_PUBLIC


@pytest.mark.integration
class TestAppShellGeneration:
    def test_every_js_module_is_precached(self, real_public):
        """Every public/js/**/*.js must appear in the generated precache list —
        this is the structural guard that makes drift impossible."""
        shell = set(server._app_shell_urls())
        js_files = sorted((real_public / "js").rglob("*.js"))
        assert js_files, "expected JS modules under public/js"
        missing = [
            f"/wellness/js/{f.relative_to(real_public / 'js').as_posix()}"
            for f in js_files
            if f"/wellness/js/{f.relative_to(real_public / 'js').as_posix()}" not in shell
        ]
        assert not missing, f"JS modules not precached: {missing}"

    def test_last_performance_is_precached(self, real_public):
        """The specific file whose omission broke Coach offline."""
        assert "/wellness/js/coach/last-performance.js" in server._app_shell_urls()

    def test_vendored_libraries_are_precached(self, real_public):
        """Vendored runtime libs live under js/vendor/ and must be in the shell
        so the app loads offline with no CDN."""
        shell = set(server._app_shell_urls())
        for lib in ("preact", "preact-hooks", "preact-signals", "htm", "localforage", "marked"):
            assert f"/wellness/js/vendor/{lib}.js" in shell, lib

    def test_shell_includes_core_assets(self, real_public):
        shell = set(server._app_shell_urls())
        for url in ("/wellness/", "/wellness/styles.css", "/wellness/manifest.json",
                    "/wellness/version.json", "/wellness/js/app.js"):
            assert url in shell, url


@pytest.mark.integration
class TestServedServiceWorker:
    def test_served_sw_is_fully_resolved_and_valid(self, real_public):
        """serve_sw() must leave no $PLACEHOLDER$ tokens, and the injected
        APP_SHELL_URLS must be a valid (JSON-parseable) array containing the
        real modules."""
        resp = server.serve_sw()
        body = resp.body.decode()

        assert "$APP_SHELL_URLS$" not in body
        assert "$SERVER_VERSION$" not in body
        assert "$BASE_PATH$" not in body

        match = re.search(r"const APP_SHELL_URLS = (\[.*?\]);", body, re.DOTALL)
        assert match, "APP_SHELL_URLS array not found in served sw.js"
        urls = json.loads(match.group(1))
        assert "/wellness/js/app.js" in urls
        assert "/wellness/js/coach/last-performance.js" in urls

    def test_no_esm_cdn_reference_remains(self, real_public):
        """The SW no longer references the esm.sh CDN (libs are vendored)."""
        body = server.serve_sw().body.decode()
        assert "esm.sh" not in body
        assert "CDN_CACHE" not in body
