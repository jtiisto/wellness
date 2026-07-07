"""
Wellness - Unified FastAPI server
Mounts module API routers and serves the single-page PWA.
"""
import hashlib
import importlib
import json
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import APIRouter, FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response

from config import get_enabled_modules, get_db_path, PUBLIC_DIR


def _compute_server_version() -> str:
    """Cache-bust token derived from the committed build stamp.

    Stable across restarts (so the service worker doesn't re-precache the whole
    app shell on every boot) and changes only when public/version.json is
    re-stamped at commit time. Falls back to a random token in dev when
    version.json is absent.
    """
    try:
        data = json.loads((PUBLIC_DIR / "version.json").read_text())
        if data.get("buildDate"):
            return hashlib.sha256(data["buildDate"].encode()).hexdigest()[:8]
    except (OSError, ValueError, KeyError):
        pass
    return uuid.uuid4().hex[:8]


SERVER_VERSION = _compute_server_version()
BASE_PATH = "/wellness"


@asynccontextmanager
async def lifespan(app):
    # Module routers initialize their own databases in create_router()
    yield


class StripPrefixMiddleware:
    """ASGI middleware that strips BASE_PATH prefix from incoming requests.

    Frontend assets use absolute paths with the prefix (e.g. /wellness/api/journal/sync)
    because that's the URL the browser sees via Tailscale. This middleware
    strips the prefix so backend routes can stay at root (e.g. /api/journal/sync).

    This serves two purposes:
    1. Direct access (localhost:9000/wellness/...) works for local dev/testing
       without needing Tailscale.
    2. Tailscale `serve --set-path /wellness` already strips the prefix, so
       requests arriving without it pass through unchanged.
    """
    def __init__(self, app, prefix: str):
        self.app = app
        self.prefix = prefix

    async def __call__(self, scope, receive, send):
        if scope["type"] in ("http", "websocket"):
            path = scope.get("path", "")
            if path.startswith(self.prefix):
                scope = dict(scope, path=path[len(self.prefix):] or "/")
        await self.app(scope, receive, send)


# ==================== Static File Serving ====================
# Backend routes stay at root. The StripPrefixMiddleware handles stripping
# /wellness from incoming requests, so the app works both directly and behind
# Tailscale serve --set-path. These handlers read only module-level state
# (PUBLIC_DIR, SERVER_VERSION, BASE_PATH), so they live on a shared router that
# every app built by create_app() mounts.


def _safe_static_file(subdir: str, file_path: str) -> Path:
    """Resolve PUBLIC_DIR/<subdir>/<file_path>, rejecting path traversal.

    The `{file_path:path}` route parameter is untrusted: a non-normalizing HTTP
    client can send `../` segments to escape the public directory. We resolve the
    candidate and require it to stay within PUBLIC_DIR/<subdir> and be a regular
    file. Returns the resolved Path on success; raises HTTPException(404)
    otherwise (404 rather than 403 so the endpoint doesn't reveal whether a path
    outside the root exists).
    """
    base = (PUBLIC_DIR / subdir).resolve()
    try:
        candidate = (base / file_path).resolve()
    except (OSError, RuntimeError, ValueError):
        raise HTTPException(status_code=404, detail="Not found")
    if not candidate.is_relative_to(base) or not candidate.is_file():
        raise HTTPException(status_code=404, detail="Not found")
    return candidate


def _app_shell_urls() -> list:
    """Build the service worker's precache list by walking PUBLIC_DIR.

    Generated at serve time so a newly added JS module/component or vendored
    library under public/js/ is precached automatically — there is no
    hand-maintained list in sw.js to drift out of sync. (Drift previously
    dropped coach/last-performance.js from the precache, breaking Coach offline
    after every deploy.) Returns BASE_PATH-prefixed URLs; injected into sw.js as
    a JSON array literal in place of the $APP_SHELL_URLS$ placeholder.
    """
    urls = [
        f"{BASE_PATH}/",
        f"{BASE_PATH}/styles.css",
        f"{BASE_PATH}/manifest.json",
        f"{BASE_PATH}/version.json",
    ]
    fonts_dir = PUBLIC_DIR / "fonts"
    if fonts_dir.is_dir():
        for font in sorted(fonts_dir.glob("*.woff2")):
            urls.append(f"{BASE_PATH}/fonts/{font.name}")
    js_dir = PUBLIC_DIR / "js"
    if js_dir.is_dir():
        for js_file in sorted(js_dir.rglob("*.js")):
            rel = js_file.relative_to(js_dir).as_posix()
            urls.append(f"{BASE_PATH}/js/{rel}")
    return urls


static_router = APIRouter()


@static_router.get("/")
def serve_root():
    """Serve the main index.html with cache-busting version injected."""
    index_path = PUBLIC_DIR / "index.html"
    if not index_path.exists():
        raise HTTPException(status_code=404, detail="index.html not found")

    html = index_path.read_text()
    html = html.replace(f'href="{BASE_PATH}/styles.css"', f'href="{BASE_PATH}/styles.css?v={SERVER_VERSION}"')
    html = html.replace(f'src="{BASE_PATH}/js/app.js"', f'src="{BASE_PATH}/js/app.js?v={SERVER_VERSION}"')

    return HTMLResponse(
        content=html,
        headers={"Cache-Control": "no-cache, must-revalidate"}
    )


@static_router.get("/styles.css")
def serve_css():
    """Serve the stylesheet."""
    css_path = PUBLIC_DIR / "styles.css"
    if css_path.exists():
        return FileResponse(
            css_path,
            media_type="text/css",
            headers={"Cache-Control": "no-cache, must-revalidate"}
        )
    raise HTTPException(status_code=404, detail="styles.css not found")


@static_router.get("/js/{file_path:path}")
def serve_js(file_path: str):
    """Serve JavaScript files."""
    return FileResponse(
        _safe_static_file("js", file_path),
        media_type="application/javascript",
        headers={"Cache-Control": "no-cache, must-revalidate"}
    )


@static_router.get("/manifest.json")
def serve_manifest():
    """Serve the PWA manifest."""
    manifest_path = PUBLIC_DIR / "manifest.json"
    if manifest_path.exists():
        return FileResponse(
            manifest_path,
            media_type="application/manifest+json",
            headers={"Cache-Control": "no-cache, must-revalidate"}
        )
    raise HTTPException(status_code=404, detail="manifest.json not found")


@static_router.get("/version.json")
def serve_version():
    """Serve the build version stamp (written by pre-commit hook)."""
    version_path = PUBLIC_DIR / "version.json"
    if version_path.exists():
        return FileResponse(
            version_path,
            media_type="application/json",
            headers={"Cache-Control": "no-cache, must-revalidate"}
        )
    raise HTTPException(status_code=404, detail="version.json not found")


@static_router.get("/sw.js")
def serve_sw():
    """Serve the service worker with version injected for cache invalidation."""
    sw_path = PUBLIC_DIR / "sw.js"
    if not sw_path.exists():
        raise HTTPException(status_code=404, detail="sw.js not found")

    content = sw_path.read_text()
    content = content.replace("$SERVER_VERSION$", SERVER_VERSION)
    content = content.replace("$APP_SHELL_URLS$", json.dumps(_app_shell_urls()))
    content = content.replace("$BASE_PATH$", BASE_PATH)

    return Response(
        content=content,
        media_type="application/javascript",
        headers={
            "Cache-Control": "no-cache, must-revalidate",
            "Service-Worker-Allowed": f"{BASE_PATH}/"
        }
    )


@static_router.get("/fonts/{file_path:path}")
def serve_fonts(file_path: str):
    """Serve font files."""
    return FileResponse(
        _safe_static_file("fonts", file_path),
        media_type="font/woff2",
        headers={"Cache-Control": "public, max-age=31536000, immutable"}
    )


@static_router.get("/icons/{file_path:path}")
def serve_icons(file_path: str):
    """Serve icon files."""
    icon_path = _safe_static_file("icons", file_path)
    media_type = "image/png"
    if icon_path.suffix == ".svg":
        media_type = "image/svg+xml"
    elif icon_path.suffix == ".ico":
        media_type = "image/x-icon"
    return FileResponse(
        icon_path,
        media_type=media_type,
        headers={"Cache-Control": "public, max-age=31536000, immutable"}
    )


# ==================== App Factory ====================


def create_app(db_path_overrides=None):
    """Build a fully-wired Wellness ASGI app.

    Each enabled module declares its router factory as "module.path:function" in
    config; a factory for a DB-owning module (has "db_env") takes a db_path and
    returns an APIRouter whose handlers capture that path (R2 — no module-global
    DB path). A DB-LESS module (no "db_env", e.g. trends) gets its factory
    called with no argument and resolves its own read-only sources via config
    helpers. Adding a module is a config-only change.

    ``db_path_overrides`` maps a module id ("journal"/"coach"/"analysis") to a
    DB path that supersedes the configured one. Production calls
    ``create_app()`` with no overrides; tests pass per-test temp paths so each
    test gets a fully isolated app+DB without poking module globals. (DB-less
    modules follow the owners' env vars, so they need no override entry.)
    """
    inner_app = FastAPI(title="Wellness", lifespan=lifespan)

    inner_app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    overrides = db_path_overrides or {}
    enabled_modules = get_enabled_modules()
    for module in enabled_modules:
        module_path, factory_name = module["router_factory"].split(":")
        create_router = getattr(importlib.import_module(module_path), factory_name)
        if "db_env" in module:
            router = create_router(overrides.get(module["id"], get_db_path(module)))
        else:
            router = create_router()  # DB-less module resolves its own sources
        inner_app.include_router(router, prefix=module["api_prefix"])

    @inner_app.get("/api/modules")
    def list_modules():
        """Return list of enabled modules for the frontend."""
        return JSONResponse(content=[
            {"id": m["id"], "name": m["name"], "icon": m["icon"], "color": m["color"]}
            for m in enabled_modules
        ])

    inner_app.include_router(static_router)

    return StripPrefixMiddleware(inner_app, BASE_PATH)


if __name__ == "__main__":
    import argparse
    import uvicorn

    parser = argparse.ArgumentParser(description="Wellness Server")
    parser.add_argument("--port", type=int, default=9000, help="Port number (default: 9000)")
    args = parser.parse_args()

    # Build the app inside the entrypoint, not at import time, so that importing
    # `server` is side-effect-free. create_app() runs every module's migrations
    # and the analysis stale-report recovery (which marks any in-flight report
    # failed); those belong to an actual server start, not to a stray `import
    # server` from a test or CLI tool. Prod (systemd unit + server.sh) and dev
    # both launch through this path: `python src/server.py --port N`.
    uvicorn.run(create_app(), host="0.0.0.0", port=args.port)
