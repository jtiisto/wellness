import os
from pathlib import Path

PROJECT_ROOT = Path(__file__).parent.parent   # health/wellness/
DATA_DIR = PROJECT_ROOT / "data"
PUBLIC_DIR = PROJECT_ROOT / "public"

MODULES = [
    {
        "id": "journal",
        "name": "Journal",
        "icon": "book",
        "color": "#f59e0b",
        "api_prefix": "/api/journal",
        "router_factory": "modules.journal:create_router",
        "db_env": "JOURNAL_DB_PATH",
        "db_default": DATA_DIR / "journal.db",
    },
    {
        "id": "coach",
        "name": "Coach",
        "icon": "dumbbell",
        "color": "#2dd4bf",
        "api_prefix": "/api/coach",
        "router_factory": "modules.coach:create_router",
        "db_env": "COACH_DB_PATH",
        "db_default": DATA_DIR / "coach.db",
    },
    {
        "id": "analysis",
        "name": "Analysis",
        "icon": "chart-bar",
        "color": "#a78bfa",
        "api_prefix": "/api/analysis",
        "router_factory": "modules.analysis:create_router",
        "db_env": "ANALYSIS_DB_PATH",
        "db_default": DATA_DIR / "analysis.db",
    },
    {
        # Trends owns NO database: it reads coach.db + journal.db + the Garmin
        # health DB through its own read-only accessors (a deliberate, narrow
        # exception to module DB isolation — see ARCHITECTURE.md "Trends").
        # No db_env/db_default: create_app calls its factory with no argument.
        "id": "trends",
        "name": "Trends",
        "icon": "trending-up",
        "color": "#38bdf8",
        "api_prefix": "/api/trends",
        "router_factory": "modules.trends:create_router",
    },
]


def get_enabled_modules():
    """Return list of enabled modules. All enabled by default.
    Disable via WELLNESS_DISABLED_MODULES=journal,analysis env var."""
    disabled = set(os.environ.get("WELLNESS_DISABLED_MODULES", "").split(","))
    return [m for m in MODULES if m["id"] not in disabled]


def get_db_path(module):
    """Resolve DB path for a module: env var > default."""
    env = os.environ.get(module["db_env"])
    return Path(env) if env else module["db_default"]


def get_module_db_path(module_id):
    """Resolve another module's DB path by id (env var > default).

    Single-sources the coach/journal defaults for cross-module READERS
    (trends): the reader sees exactly the path the owning module writes,
    including test-harness env overrides.
    """
    module = next(m for m in MODULES if m["id"] == module_id)
    return get_db_path(module)


# The Garmin health DB is written by the user's own sync job (outside this
# repo); trends reads it read-only for the body-weight series. The weight
# chart hides gracefully when the file is absent (dev machines without sync).
GARMIN_DB_DEFAULT = Path.home() / ".garmy" / "health.db"


def get_garmin_db_path():
    """Resolve the Garmin health DB path: GARMIN_DB_PATH env var > default."""
    env = os.environ.get("GARMIN_DB_PATH")
    return Path(env) if env else GARMIN_DB_DEFAULT


def get_hook_path(hook_type):
    """Resolve hook script path: env var > default example script.
    hook_type is 'pre' or 'post'."""
    env_var = f"{hook_type.upper()}_WORKOUT_HOOK"
    env = os.environ.get(env_var)
    if env:
        return Path(env)
    default = PROJECT_ROOT / "bin" / f"{hook_type}-workout-hook.sh"
    return default if default.exists() else None
