"""Reach the tools a FastMCP server registered, through its public API."""

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


def extract_tools(mcp_server):
    """Return `{function __name__: function}` for every tool `mcp_server` lists."""
    return {tool.fn.__name__: tool.fn for tool in list_tools(mcp_server)}
