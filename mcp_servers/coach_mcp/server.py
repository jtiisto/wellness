"""Coach MCP Server implementation.

Provides access to workout plans (read-write) and logs (read-only)
through the Model Context Protocol for LLM workout planning and analysis.

The tool/resource bodies live in per-family modules (tools_plans,
tools_exercises, tools_blocks, tools_queries, resources); this module wires
them together. The historical import surface that the tests rely on is
re-exported below.
"""

import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

# Shared coach domain logic (src/ is placed on the path by coach_mcp/__init__).
# Re-imported under the historical private names this module + its tests use.
from modules.coach_plans import (
    assemble_plan,
    insert_block as _insert_block,
    store_plan as _store_plan_to_db,
    needs_transform as _needs_transform,
    ensure_exercise_ids as _ensure_exercise_ids,
    is_bodyweight_or_band as _is_bodyweight_or_band,
    transform_block_to_exercises as _transform_block_to_exercises,
    transform_block_plan as _transform_block_plan,
)
from modules.coach_logs import assemble_log, workout_stats as _get_workout_stats
from modules import coach_queries

try:
    from fastmcp import FastMCP
except ImportError:
    raise ImportError(
        "FastMCP is required for MCP server functionality. "
        "Install with: pip install fastmcp"
    )

from .config import MCPConfig
from .exercise_registry import ExerciseRegistry, resolve_plan_exercises
from .database import (
    SQLiteConnection,
    DatabaseManager,
    _DEFAULT_DB_PATH,
)
from ._helpers import (
    LEGACY_PAIR_SUFFIX_RE,
    _reject_legacy_pair_suffix,
    _assemble_plan_from_db,
    _assemble_log_from_db,
    _get_coach_plan_guide,
)
from . import tools_plans, tools_exercises, tools_blocks, tools_queries, resources


def get_utc_now() -> str:
    """Return current UTC time as ISO-8601 string.

    Kept defined here (not re-imported from .database) so it resolves
    ``datetime`` from this module's namespace — the R5 byte-identical-producer
    test (test_db.py) monkeypatches ``coach_mcp.server.datetime`` and asserts
    this function honors it. Identical in behavior to ``database.get_utc_now``.
    """
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def create_mcp_server(config: Optional[MCPConfig] = None) -> FastMCP:
    """Create and configure the Coach MCP server."""
    if config is None:
        db_path = Path(os.environ.get("COACH_DB_PATH", str(_DEFAULT_DB_PATH)))
        config = MCPConfig.from_db_path(db_path)

    config.validate()
    db_manager = DatabaseManager(config)
    mcp = FastMCP("Coach Workout Manager")

    # Initialize exercise registry. A single instance is shared across every
    # tool family — add_exercise/update_exercise mutate it, so they must all see
    # the same object.
    registry = ExerciseRegistry()
    with db_manager.get_connection(read_only=True) as conn:
        registry.load(conn.cursor())

    tools_plans.register(mcp, db_manager, registry, config)
    tools_exercises.register(mcp, db_manager, registry, config)
    tools_blocks.register(mcp, db_manager, registry, config)
    tools_queries.register(mcp, db_manager, registry, config)
    resources.register(mcp, db_manager, registry, config)

    return mcp


def main():
    """Main entry point for the Coach MCP server."""
    try:
        mcp = create_mcp_server()
        mcp.run()
    except Exception as e:
        print(f"Failed to start MCP server: {e}")
        raise


if __name__ == "__main__":
    main()
