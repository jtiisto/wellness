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

from .analysis_db import (init_database, create_report, create_report_if_idle,
    update_report_running, update_report_completed, update_report_failed,
    get_report, list_reports, get_pending_reports, delete_report,
    has_active_report, recover_stale_reports)
from .analysis_queries import get_query, list_queries, build_prompt
from .background import spawn

# Grace added to the largest registered query timeout before a non-terminal
# report can be reaped as stale at runtime (see create_report_if_idle).
STALE_REPORT_GRACE_SECONDS = 120


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


async def execute_claude_query(prompt: str, extra_tools: list[str] | None = None, timeout: int | None = None, llm_dir: Path | None = None) -> str:
    env = os.environ.copy()
    env["CLAUDECODE"] = ""

    allowed = DEFAULT_ALLOWED_TOOLS + (extra_tools or [])

    cmd = [
        _find_claude_binary(), "-p",
        "--verbose",
        "--dangerously-skip-permissions",
        "--allowedTools", *allowed,
        "--output-format", "stream-json",
        "--model", "sonnet",
        prompt,
    ]

    process = await asyncio.create_subprocess_exec(
        *cmd,
        stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        cwd=str(llm_dir),
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

    # Save full stream for debugging
    debug_path = Path(llm_dir) / ".wellness" / "data" / "last_stream.jsonl"
    try:
        debug_path.write_text(raw)
    except Exception:
        pass

    # stream-json: last line is the result object
    lines = [l for l in raw.split("\n") if l.strip()]
    if not lines:
        return raw, None
    try:
        result = json.loads(lines[-1])
        return result.get("result", raw), result
    except json.JSONDecodeError:
        return raw, None


async def run_report(report_id: int, prompt: str, extra_tools, timeout, db_path: str, llm_dir: Path):
    """Background task: execute Claude query and update report status.

    ``db_path`` (a string) and ``llm_dir`` are injected by create_router rather
    than read from a module global (R2). The 'running' mark sits INSIDE the try:
    any failure — including the mark itself — lands the report in 'failed'
    rather than leaving it wedged in 'pending'/'running', which would block the
    single-active-report 409 guard until a server restart."""
    try:
        # Status writes go through to_thread: they are blocking sqlite3 calls
        # (5s busy_timeout) and this coroutine runs ON the event loop.
        await asyncio.to_thread(update_report_running, db_path, report_id)
        response_text, cli_meta = await execute_claude_query(prompt, extra_tools, timeout, llm_dir=llm_dir)
        meta_json = None
        if cli_meta:
            meta_json = json.dumps({
                "duration_ms": cli_meta.get("duration_ms"),
                "duration_api_ms": cli_meta.get("duration_api_ms"),
                "num_turns": cli_meta.get("num_turns"),
                "total_cost_usd": cli_meta.get("total_cost_usd"),
                "mcp_servers": cli_meta.get("mcp_servers"),
            })
        await asyncio.to_thread(
            update_report_completed, db_path, report_id, response_text, meta_json)
    except Exception as e:
        try:
            await asyncio.to_thread(update_report_failed, db_path, report_id, str(e))
        except Exception:
            # Best effort — recover_stale_reports cleans up on next start.
            pass


# ==================== Request Models ====================

class SubmitQueryRequest(BaseModel):
    query_id: str
    location: str | None = None


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
    """Factory: capture the DB path + LLM working dir, initialize tables, recover
    stale reports, and return a fresh router whose handlers close over them
    (R2 — no module-global state, so two routers can target different DBs in one
    process). The analysis_db helpers take a string path, so we pass db_path_str."""
    db_path_str = str(db_path)
    llm_dir = _get_llm_dir()
    init_database(db_path_str)
    recover_stale_reports(db_path_str)
    router = APIRouter()

    @router.get("/queries")
    def api_list_queries():
        return JSONResponse(content=list_queries())

    @router.post("/reports")
    async def api_submit_query(req: SubmitQueryRequest):
        query = get_query(req.query_id)
        if not query:
            raise HTTPException(status_code=404, detail=f"Unknown query_id: {req.query_id}")
        prompt = build_prompt(query, req.location)
        extra_tools = query.get("extra_allowed_tools")
        timeout = query.get("timeout")
        # Age gate for the runtime stale-report reaper: longer than ANY
        # registered query could legitimately run, so a live report is never
        # reaped (the old startup-only recovery left a wedged report blocking
        # the 409 guard until restart). Computed per-request because
        # user_queries can register custom timeouts.
        stale_after = max(
            [q.get("timeout") or QUERY_TIMEOUT for q in list_queries()] + [QUERY_TIMEOUT]
        ) + STALE_REPORT_GRACE_SECONDS
        # Atomic reap+check+insert (in a thread — blocking sqlite3 call);
        # replaces the racy has_active_report()+create_report() two-step.
        report_id = await asyncio.to_thread(
            create_report_if_idle, db_path_str,
            query["id"], query["label"], prompt, stale_after,
        )
        if report_id is None:
            raise HTTPException(status_code=409, detail="A query is already in progress.")
        spawn(run_report(report_id, prompt, extra_tools, timeout, db_path_str, llm_dir))
        return JSONResponse(content={"id": report_id, "status": "pending"}, status_code=201)

    @router.get("/reports/pending")
    def api_pending_reports():
        return JSONResponse(content=get_pending_reports(db_path_str))

    @router.get("/reports")
    def api_list_reports():
        reports = list_reports(db_path_str)
        return JSONResponse(content=[
            {k: r[k] for k in ("id", "query_id", "query_label", "status", "created_at", "completed_at")}
            for r in reports
        ])

    @router.get("/reports/{report_id}")
    def api_get_report(report_id: int):
        report = get_report(db_path_str, report_id)
        if not report:
            raise HTTPException(status_code=404, detail="Report not found")
        return JSONResponse(content=report)

    @router.delete("/reports/{report_id}")
    def api_delete_report(report_id: int):
        # An active report's subprocess is NOT cancellable here — deleting
        # its row would orphan the paid CLI run and break the single-job
        # invariant (the idle guard would see no active row and start a
        # second one). Reject instead; the row becomes deletable once
        # terminal (codex review 2026-07-09 P1).
        report = get_report(db_path_str, report_id)
        if report and report["status"] in ("pending", "running"):
            raise HTTPException(
                status_code=409,
                detail="Report is still running — wait for it to finish "
                       "(or time out) before deleting.")
        if not delete_report(db_path_str, report_id):
            raise HTTPException(status_code=404, detail="Report not found")
        return JSONResponse(content={"deleted": True})

    return router
