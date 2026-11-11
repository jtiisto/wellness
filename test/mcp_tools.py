"""Reach the tools and instructions a FastMCP server exposes, through its public API."""

import asyncio
from concurrent.futures import ThreadPoolExecutor


def list_tools(mcp_server):
    """Return the tool objects `mcp_server` lists, in listing order.

    `list_tools()` is a coroutine, and it runs on a worker thread with a loop of
    its own: Playwright's sync API (the browser tests) leaves an event loop running
    on the main thread, and a bare `asyncio.run` there raises RuntimeError.
    """
    with ThreadPoolExecutor(max_workers=1) as pool:
        return pool.submit(asyncio.run, mcp_server.list_tools()).result()


def server_instructions(mcp_server):
    """Return the `instructions` `mcp_server` sends a client, or None.

    Read the way a client reads them: an in-memory `fastmcp.Client` performs
    the MCP initialize handshake (`mode="legacy"` pins the classic
    `initialize` request rather than FastMCP 4's newer negotiation) and the
    text is taken from its `InitializeResult`. Same worker-thread pattern as
    `list_tools`.
    """
    from fastmcp import Client

    async def handshake():
        async with Client(mcp_server, mode="legacy") as client:
            return client.initialize_result.instructions

    with ThreadPoolExecutor(max_workers=1) as pool:
        return pool.submit(asyncio.run, handshake()).result()


def extract_tools(mcp_server):
    """Return `{function __name__: function}` for every tool `mcp_server` lists."""
    return {tool.fn.__name__: tool.fn for tool in list_tools(mcp_server)}
