"""Coach MCP Server.

Provides MCP (Model Context Protocol) access to workout plans and logs.
Plans can be created/updated by LLM. Logs are read-only for analysis.
"""

from .config import MCPConfig
from .server import create_mcp_server

__all__ = ["MCPConfig", "create_mcp_server"]
