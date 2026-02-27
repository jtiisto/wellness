"""
Wellness - Unified FastAPI server
Mounts module API routers and serves the single-page PWA.
"""
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse

from config import get_enabled_modules, get_db_path, PUBLIC_DIR


SERVER_VERSION = uuid.uuid4().hex[:8]


@asynccontextmanager
async def lifespan(app):
    # Module routers initialize their own databases in create_router()
    yield


app = FastAPI(title="Wellness", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ==================== Module Registration ====================

_enabled_modules = get_enabled_modules()

for _module in _enabled_modules:
    _mod_id = _module["id"]
    _db = get_db_path(_module)

    if _mod_id == "journal":
        from modules.journal import create_router as create_journal_router
        app.include_router(create_journal_router(_db), prefix="/api/journal")
    elif _mod_id == "coach":
        from modules.coach import create_router as create_coach_router
        app.include_router(create_coach_router(_db), prefix="/api/coach")
    elif _mod_id == "analysis":
        from modules.analysis import create_router as create_analysis_router
        app.include_router(create_analysis_router(_db), prefix="/api/analysis")


# ==================== API Endpoints ====================

@app.get("/api/modules")
def list_modules():
    """Return list of enabled modules for the frontend."""
    return JSONResponse(content=[
        {"id": m["id"], "name": m["name"], "icon": m["icon"], "color": m["color"]}
        for m in _enabled_modules
    ])


# ==================== Static File Serving ====================

@app.get("/")
def serve_root():
    """Serve the main index.html with cache-busting version injected."""
    index_path = PUBLIC_DIR / "index.html"
    if not index_path.exists():
        raise HTTPException(status_code=404, detail="index.html not found")

    html = index_path.read_text()
    html = html.replace('href="/styles.css"', f'href="/styles.css?v={SERVER_VERSION}"')
    html = html.replace('src="/js/app.js"', f'src="/js/app.js?v={SERVER_VERSION}"')

    return HTMLResponse(
        content=html,
        headers={"Cache-Control": "no-cache, must-revalidate"}
    )


@app.get("/styles.css")
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


@app.get("/js/{file_path:path}")
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


@app.get("/manifest.json")
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


@app.get("/sw.js")
def serve_sw():
    """Serve the service worker from root scope."""
    sw_path = PUBLIC_DIR / "sw.js"
    if sw_path.exists():
        return FileResponse(
            sw_path,
            media_type="application/javascript",
            headers={
                "Cache-Control": "no-cache, must-revalidate",
                "Service-Worker-Allowed": "/"
            }
        )
    raise HTTPException(status_code=404, detail="sw.js not found")


@app.get("/icons/{file_path:path}")
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
