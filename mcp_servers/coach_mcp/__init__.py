"""Coach MCP Server.

Provides MCP (Model Context Protocol) access to workout plans and logs.
Plans can be created/updated by LLM. Logs are read-only for analysis.
"""

# --- shared-package bootstrap (Phase 3) ---------------------------------------
# The coach domain logic lives in the shared `src/modules` package (coach_plans,
# coach_logs, coach_queries, db) so both transports — the FastAPI router and this
# MCP server — delegate to one implementation. This server runs as its own
# process (`python -m coach_mcp`, cwd=mcp_servers) with `src/` not otherwise on
# the path, so put it there BEFORE importing `.server`, which pulls in
# `modules.*`. Idempotent, and a no-op under pytest (conftest already adds src/).
import sys as _sys
from pathlib import Path as _Path

_SRC = _Path(__file__).resolve().parents[2] / "src"
if _SRC.is_dir() and str(_SRC) not in _sys.path:
    _sys.path.insert(0, str(_SRC))
# ------------------------------------------------------------------------------

from .config import MCPConfig
from .server import create_mcp_server

__all__ = ["MCPConfig", "create_mcp_server"]
