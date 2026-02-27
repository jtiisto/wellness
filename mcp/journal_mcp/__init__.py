"""Journal MCP Server.

Provides MCP (Model Context Protocol) access to journal tracking data.
"""

from .config import MCPConfig
from .server import create_mcp_server

__all__ = ["MCPConfig", "create_mcp_server"]
