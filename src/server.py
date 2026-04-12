"""
Wellness - Unified FastAPI server
Mounts module API routers and serves the single-page PWA.
"""
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response

from config import get_enabled_modules, get_db_path, PUBLIC_DIR


SERVER_VERSION = uuid.uuid4().hex[:8]
BASE_PATH = "/wellness"


@asynccontextmanager
async def lifespan(app):
    # Module routers initialize their own databases in create_router()
    yield


_inner_app = FastAPI(title="Wellness", lifespan=lifespan)

_inner_app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


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


app = StripPrefixMiddleware(_inner_app, BASE_PATH)


# ==================== Module Registration ====================

_enabled_modules = get_enabled_modules()

for _module in _enabled_modules:
    _mod_id = _module["id"]
    _db = get_db_path(_module)

    if _mod_id == "journal":
        from modules.journal import create_router as create_journal_router
        _inner_app.include_router(create_journal_router(_db), prefix="/api/journal")
    elif _mod_id == "coach":
        from modules.coach import create_router as create_coach_router
        _inner_app.include_router(create_coach_router(_db), prefix="/api/coach")
    elif _mod_id == "analysis":
        from modules.analysis import create_router as create_analysis_router
        _inner_app.include_router(create_analysis_router(_db), prefix="/api/analysis")


# ==================== API Endpoints ====================

@_inner_app.get("/api/modules")
def list_modules():
    """Return list of enabled modules for the frontend."""
    return JSONResponse(content=[
        {"id": m["id"], "name": m["name"], "icon": m["icon"], "color": m["color"]}
        for m in _enabled_modules
    ])


# ==================== Static File Serving ====================
# Backend routes stay at root. The StripPrefixMiddleware handles stripping
# /wellness from incoming requests, so the app works both directly and behind
# Tailscale serve --set-path.


@_inner_app.get("/")
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


@_inner_app.get("/styles.css")
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


@_inner_app.get("/js/{file_path:path}")
def serve_js(file_path: str):
    """Serve JavaScript files."""
    js_path = PUBLIC_DIR / "js" / file_path
    if js_path.exists() and js_path.is_file():
        return FileResponse(
            js_path,
            media_type="application/javascript",
            headers={"Cache-Control": "no-cache, must-revalidate"}
        )
    raise HTTPException(status_code=404, detail=f"JS file not found: {file_path}")


@_inner_app.get("/manifest.json")
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


@_inner_app.get("/version.json")
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


@_inner_app.get("/sw.js")
def serve_sw():
    """Serve the service worker with version injected for cache invalidation."""
    sw_path = PUBLIC_DIR / "sw.js"
    if not sw_path.exists():
        raise HTTPException(status_code=404, detail="sw.js not found")

    content = sw_path.read_text()
    content = content.replace("$SERVER_VERSION$", SERVER_VERSION)
    content = content.replace("$BASE_PATH$", BASE_PATH)

    return Response(
        content=content,
        media_type="application/javascript",
        headers={
            "Cache-Control": "no-cache, must-revalidate",
            "Service-Worker-Allowed": f"{BASE_PATH}/"
        }
    )


@_inner_app.get("/fonts/{file_path:path}")
def serve_fonts(file_path: str):
    """Serve font files."""
    font_path = PUBLIC_DIR / "fonts" / file_path
    if font_path.exists() and font_path.is_file():
        return FileResponse(
            font_path,
            media_type="font/woff2",
            headers={"Cache-Control": "public, max-age=31536000, immutable"}
        )
    raise HTTPException(status_code=404, detail=f"Font not found: {file_path}")


@_inner_app.get("/icons/{file_path:path}")
def serve_icons(file_path: str):
    """Serve icon files."""
    icon_path = PUBLIC_DIR / "icons" / file_path
    if icon_path.exists() and icon_path.is_file():
        media_type = "image/png"
        if file_path.endswith(".svg"):
            media_type = "image/svg+xml"
        elif file_path.endswith(".ico"):
            media_type = "image/x-icon"
        return FileResponse(
            icon_path,
            media_type=media_type,
            headers={"Cache-Control": "public, max-age=31536000, immutable"}
        )
    raise HTTPException(status_code=404, detail=f"Icon not found: {file_path}")


if __name__ == "__main__":
    import argparse
    import uvicorn

    parser = argparse.ArgumentParser(description="Wellness Server")
    parser.add_argument("--port", type=int, default=9000, help="Port number (default: 9000)")
    args = parser.parse_args()

    uvicorn.run(app, host="0.0.0.0", port=args.port)
