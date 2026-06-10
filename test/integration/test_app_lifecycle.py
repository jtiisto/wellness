"""App construction is deferred to the __main__ entrypoint, so importing
`server` must be side-effect-free.

`server.py` used to run `app = create_app()` at module scope, so the mere act
of `import server` ran every module's migrations and the analysis stale-report
recovery (which flips any in-flight report to `failed`) against the configured
DBs. That made importing the module from a test or CLI tool quietly mutate real
data. These tests pin the fix: importing the module touches nothing; only
create_app() (called from the entrypoint) builds and initializes the databases.

Run in a subprocess so the assertions reflect a clean import, not whatever the
pytest process has already imported.
"""
import os
import subprocess
import sys
from pathlib import Path

import pytest

_SRC_DIR = Path(__file__).resolve().parents[2] / "src"


def _run_import_snippet(snippet: str, db_dir: Path):
    """Import `server` in a fresh interpreter with the three module DB paths
    pointed at db_dir, then run `snippet`. Returns the CompletedProcess."""
    env = {
        **os.environ,
        "PYTHONPATH": str(_SRC_DIR),
        "JOURNAL_DB_PATH": str(db_dir / "journal.db"),
        "COACH_DB_PATH": str(db_dir / "coach.db"),
        "ANALYSIS_DB_PATH": str(db_dir / "analysis.db"),
    }
    return subprocess.run(
        [sys.executable, "-c", snippet],
        cwd=str(_SRC_DIR), env=env, capture_output=True, text=True,
    )


@pytest.mark.integration
class TestImportIsSideEffectFree:
    def test_importing_server_creates_no_databases(self, tmp_path):
        """`import server` must not create any of the module databases — proving
        no migrations / stale-report recovery run as an import side effect."""
        result = _run_import_snippet("import server; print('IMPORTED')", tmp_path)
        assert result.returncode == 0, f"stderr:\n{result.stderr}"
        assert "IMPORTED" in result.stdout
        created = sorted(p.name for p in tmp_path.iterdir())
        assert created == [], f"import created DB files: {created}"

    def test_module_has_no_import_time_app(self, tmp_path):
        """The module no longer exposes a top-level `app` built at import time;
        the app is constructed only by create_app() at the entrypoint."""
        result = _run_import_snippet(
            "import server; print('HASAPP', hasattr(server, 'app'))", tmp_path
        )
        assert result.returncode == 0, f"stderr:\n{result.stderr}"
        assert "HASAPP False" in result.stdout, result.stdout

    def test_create_app_does_initialize_databases(self, tmp_path):
        """The side effect moved, it didn't vanish: calling create_app() builds
        the databases (migrations run during router construction)."""
        result = _run_import_snippet(
            "import server; server.create_app(); print('BUILT')", tmp_path
        )
        assert result.returncode == 0, f"stderr:\n{result.stderr}"
        assert "BUILT" in result.stdout
        created = sorted(p.name for p in tmp_path.iterdir())
        assert "journal.db" in created and "coach.db" in created and "analysis.db" in created, created
