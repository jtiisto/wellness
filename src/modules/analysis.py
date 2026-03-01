"""
Analysis API Router - extracted from analysis/src/server.py
LLM-powered analysis reports with async execution.
"""
import asyncio
import json
import os
import shutil
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .analysis_db import (init_database, create_report, update_report_running,
    update_report_completed, update_report_failed, get_report, list_reports,
    get_pending_reports, delete_report, has_active_report)
from .analysis_queries import get_query, list_queries, build_prompt


# Module-level state, set by create_router()
_db_path: Path = None
_llm_dir: Path = None


# ==================== Claude CLI Execution ====================

QUERY_TIMEOUT = 180  # seconds (default, can be overridden per query)

DEFAULT_ALLOWED_TOOLS = [
    "mcp__journal-localdb__*", "mcp__coach-localdb__*",
    "mcp__garmy-localdb__*", "Read", "Glob", "Grep",
]


def _find_claude_binary() -> str:
    """Resolve the claude CLI binary path.

    shutil.which works when the user's PATH is available (dev).
    Falls back to ~/.local/bin/claude for systemd services with minimal PATH.
    """
    found = shutil.which("claude")
    if found:
        return found
    fallback = Path.home() / ".local" / "bin" / "claude"
    if fallback.exists():
        return str(fallback)
    raise FileNotFoundError("claude CLI not found in PATH or ~/.local/bin/claude")


async def execute_claude_query(prompt: str, extra_tools: list[str] | None = None, timeout: int | None = None) -> str:
    env = os.environ.copy()
    env["CLAUDECODE"] = ""

    allowed = DEFAULT_ALLOWED_TOOLS + (extra_tools or [])

    cmd = [
        _find_claude_binary(), "-p",
        "--dangerously-skip-permissions",
        "--allowedTools", *allowed,
        "--output-format", "json",
        "--model", "sonnet",
        prompt,
    ]

    process = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        cwd=str(_llm_dir),
        env=env,
    )

    effective_timeout = timeout or QUERY_TIMEOUT
    try:
        stdout, stderr = await asyncio.wait_for(
            process.communicate(), timeout=effective_timeout
        )
    except asyncio.TimeoutError:
        process.kill()
        await process.communicate()
        raise TimeoutError(f"Claude CLI timed out after {effective_timeout}s")

    if process.returncode != 0:
        error_text = stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"Claude CLI failed (exit {process.returncode}): {error_text}")

    raw = stdout.decode("utf-8", errors="replace").strip()
    try:
        result = json.loads(raw)
        return result.get("result", raw)
    except json.JSONDecodeError:
        return raw


async def run_report(report_id: int, prompt: str, extra_tools: list[str] | None = None, timeout: int | None = None):
    """Background task: execute Claude query and update report status."""
    db_path = str(_db_path)
    update_report_running(db_path, report_id)
    try:
        response_text = await execute_claude_query(prompt, extra_tools, timeout)
        update_report_completed(db_path, report_id, response_text)
    except Exception as e:
        update_report_failed(db_path, report_id, str(e))


# ==================== Request Models ====================

class SubmitQueryRequest(BaseModel):
    query_id: str
    location: str | None = None


# ==================== Router ====================

router = APIRouter()


@router.get("/queries")
def api_list_queries():
    return JSONResponse(content=list_queries())


@router.post("/reports")
async def api_submit_query(req: SubmitQueryRequest):
    query = get_query(req.query_id)
    if not query:
        raise HTTPException(status_code=404, detail=f"Unknown query_id: {req.query_id}")
    if has_active_report(str(_db_path)):
        raise HTTPException(status_code=409, detail="A query is already in progress.")
    prompt = build_prompt(query, req.location)
    extra_tools = query.get("extra_allowed_tools")
    timeout = query.get("timeout")
    report_id = create_report(str(_db_path), query["id"], query["label"], prompt)
    asyncio.create_task(run_report(report_id, prompt, extra_tools, timeout))
    return JSONResponse(content={"id": report_id, "status": "pending"}, status_code=201)


@router.get("/reports/pending")
def api_pending_reports():
    return JSONResponse(content=get_pending_reports(str(_db_path)))


@router.get("/reports")
def api_list_reports():
    reports = list_reports(str(_db_path))
    return JSONResponse(content=[
        {k: r[k] for k in ("id", "query_id", "query_label", "status", "created_at", "completed_at")}
        for r in reports
    ])


@router.get("/reports/{report_id}")
def api_get_report(report_id: int):
    report = get_report(str(_db_path), report_id)
    if not report:
        raise HTTPException(status_code=404, detail="Report not found")
    return JSONResponse(content=report)


@router.delete("/reports/{report_id}")
def api_delete_report(report_id: int):
    if not delete_report(str(_db_path), report_id):
        raise HTTPException(status_code=404, detail="Report not found")
    return JSONResponse(content={"deleted": True})


def _get_llm_dir() -> Path:
    """Resolve LLM working directory: env var > .llm-dir file > project root."""
    env_dir = os.environ.get("ANALYSIS_LLM_DIR")
    if env_dir:
        return Path(env_dir)
    config_file = Path(__file__).parent.parent.parent / ".llm-dir"
    if config_file.exists():
        return Path(config_file.read_text().strip())
    # Default: project root (health/)
    return Path(__file__).parent.parent.parent.parent


def create_router(db_path: Path) -> APIRouter:
    """Factory: set the DB path, initialize tables, and return the router."""
    global _db_path, _llm_dir
    _db_path = db_path
    _llm_dir = _get_llm_dir()
    init_database(str(db_path))
    return router
