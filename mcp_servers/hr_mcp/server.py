"""HR MCP Server implementation.

Read-only MCP access to captured heart-rate sessions: listing, per-session
reports, aligned time series, and VO2 plan-vs-actual comparison. Migrated from
the pulse-bridge repo's `pulse_bridge_mcp`, repointed at this repo's `hr.db`.

The server never writes: it reads through `hr_analysis`, whose connections are
sqlite `mode=ro` (see `database.py`). The `hr` module's HTTP endpoints are the
only writer of that database.
"""

from pathlib import Path
from typing import Any, Optional

# Path resolution is the `hr` module's own (HR_DB_PATH env > data/hr.db), so a
# test harness or a second environment repoints server, CLI and MCP together.
# (src/ is placed on the path by hr_mcp/__init__.)
from config import get_module_db_path

try:
    from fastmcp import FastMCP
except ImportError:
    raise ImportError(
        "FastMCP is required for MCP server functionality. "
        "Install with: pip install fastmcp"
    )

from .config import MCPConfig
from .database import DatabaseManager
from . import tools


def create_mcp_server(config: Optional[MCPConfig] = None) -> FastMCP:
    """Create and configure the read-only HR MCP server."""
    if config is None:
        config = MCPConfig.from_db_path(Path(get_module_db_path("hr")))

    config.validate()
    db = DatabaseManager(config)
    mcp = FastMCP("HR Analysis")

    @mcp.tool()
    def list_sessions(
        start_ms: int | str | None = None,
        end_ms: int | str | None = None,
        limit: int | None = None,
    ) -> list[dict[str, Any]]:
        """WHEN TO USE: Find captured heart-rate sessions available for analysis.

        Start here — the other tools take a session_id from this listing.

        Args:
            start_ms: Optional window start, epoch milliseconds or ISO-8601
            end_ms: Optional window end, epoch milliseconds or ISO-8601
            limit: Maximum sessions to return (newest first)

        Returns:
            Sessions overlapping the window, newest first, each with
            session_id, device_id, start/end epoch ms, duration_s, beat count,
            and workout_date (the coach-side day it was captured for, when the
            session row carries one).
        """
        return tools.list_sessions(db, start_ms=start_ms, end_ms=end_ms, limit=limit)

    @mcp.tool()
    def get_session_report(
        session_id: str,
        hrmax: int | None = None,
        start_ms: int | str | None = None,
        end_ms: int | str | None = None,
    ) -> dict[str, Any]:
        """WHEN TO USE: Analyze one captured heart-rate session.

        Args:
            session_id: Session to analyze (from list_sessions)
            hrmax: Max HR, enables the zone breakdown
            start_ms: Optional crop start, epoch milliseconds or ISO-8601
            end_ms: Optional crop end, epoch milliseconds or ISO-8601

        Returns:
            Duration-weighted HR (and zones when hrmax is given), RMSSD, DFA
            alpha1 windows, detected work/rest bouts, and a quality block.
            Check quality first: rr_quality_insufficient means the RR signal
            never met the trust rule, so the HRV numbers are not usable.
        """
        return tools.get_session_report(
            db,
            session_id=session_id,
            hrmax=hrmax,
            start_ms=start_ms,
            end_ms=end_ms,
        )

    @mcp.tool()
    def get_latest_session_report(hrmax: int | None = None) -> dict[str, Any]:
        """WHEN TO USE: Analyze the most recent capture without looking up its id.

        Args:
            hrmax: Max HR, enables the zone breakdown

        Returns:
            The same report shape as get_session_report.
        """
        return tools.get_latest_session_report(db, hrmax=hrmax)

    @mcp.tool()
    def get_aligned_timeseries(
        session_id: str,
        resolution_s: int = 5,
        start_ms: int | str | None = None,
        end_ms: int | str | None = None,
    ) -> dict[str, Any]:
        """WHEN TO USE: Get compact time buckets when bout matching is uncertain.

        The fallback for reading a session's shape yourself — what the HR was
        doing minute by minute — when the automatic work/rest detection in
        get_session_report disagrees with what you expected.

        Args:
            session_id: Session to bucket (from list_sessions)
            resolution_s: Bucket width in seconds (minimum 1)
            start_ms: Optional crop start, epoch milliseconds or ISO-8601
            end_ms: Optional crop end, epoch milliseconds or ISO-8601

        Returns:
            One row per non-empty bucket with mean/max HR, RR coverage,
            artifact fraction, and a gap flag.
        """
        return tools.get_aligned_timeseries(
            db,
            session_id=session_id,
            resolution_s=resolution_s,
            start_ms=start_ms,
            end_ms=end_ms,
        )

    @mcp.tool()
    def get_vo2_summary(
        session_id: str,
        intent: dict[str, Any] | None = None,
        hrmax: int | None = None,
        start_ms: int | str | None = None,
        end_ms: int | str | None = None,
        resolution_s: int = 5,
    ) -> dict[str, Any]:
        """WHEN TO USE: Compare a VO2/HIIT interval plan to captured HR data.

        Args:
            session_id: Session to analyze (from list_sessions)
            intent: The planned structure — rounds, work_duration_s,
                rest_duration_s (all three together or none), plus optional
                target_hr_min/target_hr_max. Omit it and the tool returns the
                time series alone with intent_missing flagged.
            hrmax: Max HR, enables the zone breakdown
            start_ms: Optional crop start, epoch milliseconds or ISO-8601
            end_ms: Optional crop end, epoch milliseconds or ISO-8601
            resolution_s: Bucket width for the fallback time series

        Returns:
            Expected vs detected work/rest bouts with per-bout peak HR and time
            at target, match-uncertainty flags, and a compact time series to
            fall back on when the match confidence is low.
        """
        return tools.get_vo2_summary(
            db,
            session_id=session_id,
            intent=intent,
            hrmax=hrmax,
            start_ms=start_ms,
            end_ms=end_ms,
            resolution_s=resolution_s,
        )

    return mcp


def main():
    """Main entry point for the HR MCP server."""
    try:
        mcp = create_mcp_server()
        mcp.run()
    except Exception as e:
        print(f"Failed to start MCP server: {e}")
        raise


if __name__ == "__main__":
    main()
