"""Re-export shim — the adherence math moved to the shared domain layer.

Canonical implementation: src/modules/journal_adherence.py (shared by this
MCP server and the Trends module). This module keeps the historical import
surface (`journal_mcp.adherence.compute_adherence`) working; the package
__init__ bootstraps `src/` onto sys.path for standalone MCP runs.
"""

from modules.journal_adherence import (  # noqa: F401
    compute_adherence,
    compute_streaks,
    day_status,
    target_band_segments,
)
