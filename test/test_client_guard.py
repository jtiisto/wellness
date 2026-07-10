"""Client-IP guard + CORS allowlist (codex review 2026-07-09 P1).

The server binds 0.0.0.0 with Tailscale as the auth layer; the guard makes
the app itself refuse sources outside loopback + tailnet ranges, and CORS is
off unless a deployment explicitly allowlists origins.
"""
import ipaddress

import httpx
import pytest
from fastapi.testclient import TestClient


async def _get(app, client_addr, path="/api/modules"):
    transport = httpx.ASGITransport(app=app, client=client_addr)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        return await c.get(path)


@pytest.mark.unit
class TestTrustedNetworks:
    def test_default_set(self, monkeypatch):
        from server import _trusted_networks
        monkeypatch.delenv("WELLNESS_TRUSTED_CLIENTS", raising=False)
        nets = _trusted_networks()
        assert ipaddress.ip_network("100.64.0.0/10") in nets
        assert ipaddress.ip_network("127.0.0.0/8") in nets

    def test_env_replaces_defaults(self, monkeypatch):
        from server import _trusted_networks
        monkeypatch.setenv("WELLNESS_TRUSTED_CLIENTS", "10.0.0.0/8, 192.168.1.0/24")
        nets = _trusted_networks()
        assert nets == [ipaddress.ip_network("10.0.0.0/8"),
                        ipaddress.ip_network("192.168.1.0/24")]

    def test_star_disables(self, monkeypatch):
        from server import _trusted_networks
        monkeypatch.setenv("WELLNESS_TRUSTED_CLIENTS", "*")
        assert _trusted_networks() is None

    def test_invalid_cidr_fails_loudly(self, monkeypatch):
        from server import _trusted_networks
        monkeypatch.setenv("WELLNESS_TRUSTED_CLIENTS", "not-a-network")
        with pytest.raises(ValueError):
            _trusted_networks()


@pytest.mark.integration
class TestClientGuard:
    async def test_lan_client_rejected(self, test_app):
        r = await _get(test_app, ("192.168.1.50", 1234))
        assert r.status_code == 403
        assert r.json() == {"detail": "Client address not trusted"}

    async def test_tailnet_client_allowed(self, test_app):
        assert (await _get(test_app, ("100.68.200.116", 1234))).status_code == 200

    async def test_loopback_allowed(self, test_app):
        assert (await _get(test_app, ("127.0.0.1", 1234))).status_code == 200

    async def test_tailscale_ipv6_ula_allowed(self, test_app):
        assert (await _get(test_app, ("fd7a:115c:a1e0::ab12", 1234))).status_code == 200

    async def test_public_internet_rejected(self, test_app):
        assert (await _get(test_app, ("203.0.113.9", 1234))).status_code == 403

    def test_synthetic_test_client_allowed(self, test_app):
        # Starlette's TestClient presents client=("testclient", 50000) — not a
        # network peer; the whole suite depends on it passing.
        with TestClient(test_app) as c:
            assert c.get("/api/modules").status_code == 200

    async def test_star_env_disables_guard(self, test_app, monkeypatch):
        monkeypatch.setenv("WELLNESS_TRUSTED_CLIENTS", "*")
        import server as server_mod
        app = server_mod.create_app()
        assert (await _get(app, ("192.168.1.50", 1234))).status_code == 200

    async def test_custom_ranges_replace_defaults(self, test_app, monkeypatch):
        monkeypatch.setenv("WELLNESS_TRUSTED_CLIENTS", "10.0.0.0/8")
        import server as server_mod
        app = server_mod.create_app()
        assert (await _get(app, ("10.1.2.3", 1234))).status_code == 200
        assert (await _get(app, ("100.68.200.116", 1234))).status_code == 403


@pytest.mark.integration
class TestCorsAllowlist:
    def test_no_cors_by_default(self, test_app):
        # No wildcard: a foreign origin gets no CORS grant, so a browser
        # page on another origin cannot read responses.
        with TestClient(test_app) as c:
            r = c.get("/api/modules", headers={"Origin": "https://evil.example"})
        assert "access-control-allow-origin" not in r.headers

    def test_env_allowlists_origin(self, test_app, monkeypatch):
        monkeypatch.setenv("WELLNESS_CORS_ORIGINS", "https://ok.example")
        import server as server_mod
        app = server_mod.create_app()
        with TestClient(app) as c:
            ok = c.get("/api/modules", headers={"Origin": "https://ok.example"})
            evil = c.get("/api/modules", headers={"Origin": "https://evil.example"})
        assert ok.headers.get("access-control-allow-origin") == "https://ok.example"
        assert "access-control-allow-origin" not in evil.headers
