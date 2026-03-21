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
        "color": "#e94560",
        "api_prefix": "/api/journal",
        "db_env": "JOURNAL_DB_PATH",
        "db_default": DATA_DIR / "journal.db",
    },
    {
        "id": "coach",
        "name": "Coach",
        "icon": "dumbbell",
        "color": "#0f3460",
        "api_prefix": "/api/coach",
        "db_env": "COACH_DB_PATH",
        "db_default": DATA_DIR / "coach.db",
    },
    {
        "id": "analysis",
        "name": "Analysis",
        "icon": "chart-bar",
        "color": "#4ecdc4",
        "api_prefix": "/api/analysis",
        "db_env": "ANALYSIS_DB_PATH",
        "db_default": DATA_DIR / "analysis.db",
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


def get_hook_path(hook_type):
    """Resolve hook script path: env var > default example script.
    hook_type is 'pre' or 'post'."""
    env_var = f"{hook_type.upper()}_WORKOUT_HOOK"
    env = os.environ.get(env_var)
    if env:
        return Path(env)
    default = PROJECT_ROOT / "bin" / f"{hook_type}-workout-hook.sh"
    return default if default.exists() else None
