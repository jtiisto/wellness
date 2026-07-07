"""Journal MCP Server.

Provides MCP (Model Context Protocol) access to journal tracking data.
"""

# --- shared-package bootstrap ---------------------------------------------------
# The adherence math lives in the shared `src/modules` package
# (modules.journal_adherence) so both consumers — this MCP server and the
# Trends module — delegate to one implementation (the coach_mcp precedent).
# This server runs as its own process (`python -m journal_mcp`,
# cwd=mcp_servers) with `src/` not otherwise on the path, so put it there
# BEFORE importing `.server`, which pulls in the `.adherence` re-export shim.
# Idempotent, and a no-op under pytest (conftest already adds src/).
import sys as _sys
from pathlib import Path as _Path

_SRC = _Path(__file__).resolve().parents[2] / "src"
if _SRC.is_dir() and str(_SRC) not in _sys.path:
    _sys.path.insert(0, str(_SRC))
# ------------------------------------------------------------------------------

from .config import MCPConfig
from .server import create_mcp_server

__all__ = ["MCPConfig", "create_mcp_server"]
