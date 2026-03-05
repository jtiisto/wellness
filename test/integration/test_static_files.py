"""Integration tests for static file serving in the unified Wellness app."""
import json
import re
import pytest


class TestServeIndex:
    def test_root_returns_html(self, client):
        """GET / should serve index.html."""
        resp = client.get("/")
        assert resp.status_code == 200
        assert "text/html" in resp.headers["content-type"]

    def test_index_has_cache_busting(self, client):
        """Index should have cache-busting query params injected."""
        resp = client.get("/")
        assert "?v=" in resp.text

    def test_index_cache_control(self, client):
        """Index should have no-cache header."""
        resp = client.get("/")
        assert "no-cache" in resp.headers.get("cache-control", "")


class TestServeCss:
    def test_returns_css(self, client):
        """GET /styles.css should return CSS content."""
        resp = client.get("/styles.css")
        assert resp.status_code == 200
        assert "text/css" in resp.headers["content-type"]

    def test_cache_control(self, client):
        """CSS should have no-cache header."""
        resp = client.get("/styles.css")
        assert "no-cache" in resp.headers.get("cache-control", "")


class TestServeManifest:
    def test_returns_valid_json(self, client):
        """GET /manifest.json should return valid JSON."""
        resp = client.get("/manifest.json")
        assert resp.status_code == 200
        data = json.loads(resp.text)
        assert "name" in data

    def test_cache_control(self, client):
        """Manifest should have no-cache header."""
        resp = client.get("/manifest.json")
        assert "no-cache" in resp.headers.get("cache-control", "")


class TestServeJs:
    def test_returns_app_js(self, client):
        """GET /js/app.js should return JavaScript."""
        resp = client.get("/js/app.js")
        assert resp.status_code == 200
        assert "javascript" in resp.headers["content-type"]

    def test_missing_js_returns_404(self, client):
        """Missing JS files should return 404."""
        resp = client.get("/js/nonexistent.js")
        assert resp.status_code == 404

    def test_cache_control(self, client):
        """JS files should have no-cache header."""
        resp = client.get("/js/app.js")
        assert "no-cache" in resp.headers.get("cache-control", "")


class TestServeIcons:
    def test_returns_png_icon(self, client):
        """GET /icons/icon-192.png should return PNG."""
        resp = client.get("/icons/icon-192.png")
        assert resp.status_code == 200
        assert "png" in resp.headers["content-type"]

    def test_missing_icon_returns_404(self, client):
        """Missing icon files should return 404."""
        resp = client.get("/icons/nonexistent.png")
        assert resp.status_code == 404

    def test_immutable_cache(self, client):
        """Icons should have immutable cache header."""
        resp = client.get("/icons/icon-192.png")
        assert "immutable" in resp.headers.get("cache-control", "")


class TestServeServiceWorker:
    def test_returns_sw(self, client):
        """GET /sw.js should return the service worker."""
        resp = client.get("/sw.js")
        assert resp.status_code == 200
        assert "javascript" in resp.headers["content-type"]

    def test_service_worker_allowed_header(self, client):
        """SW should have Service-Worker-Allowed header."""
        resp = client.get("/sw.js")
        assert resp.headers.get("Service-Worker-Allowed") == "/"

    def test_cache_control(self, client):
        """SW should have no-cache header."""
        resp = client.get("/sw.js")
        assert "no-cache" in resp.headers.get("cache-control", "")


class TestCORS:
    def test_cors_headers_on_api_response(self, client):
        """API responses should include CORS headers when Origin is present."""
        resp = client.get(
            "/api/modules",
            headers={"Origin": "http://example.com"}
        )
        assert resp.headers.get("access-control-allow-origin") == "*"

    def test_cors_preflight_request(self, client):
        """OPTIONS preflight requests should return CORS headers."""
        resp = client.options(
            "/api/modules",
            headers={
                "Origin": "http://example.com",
                "Access-Control-Request-Method": "GET",
            }
        )
        assert resp.status_code == 200
        assert "access-control-allow-origin" in resp.headers
