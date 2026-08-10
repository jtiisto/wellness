"""The e2e marker must stay scoped to test/e2e_browser.

pytest passes EVERY collected item to a conftest's pytest_collection_modifyitems
hook — not just the conftest's own directory — so an unfiltered marking loop in
test/e2e_browser/conftest.py once marked the entire session `e2e`. That inverted
the pre-commit fast slice (`-m "unit or not (integration or e2e)"`) into
"explicitly-marked unit tests only" and made `-m "not e2e"` deselect everything.
This pins the directory boundary from both sides via a real collection run.
"""
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]


def test_e2e_marker_is_scoped_to_its_directory():
    result = subprocess.run(
        [sys.executable, "-m", "pytest", "--collect-only", "-q", "-m", "not e2e",
         "test/integration/test_module_discovery.py",
         "test/e2e_browser/test_navigation.py"],
        cwd=REPO_ROOT, capture_output=True, text=True,
    )
    assert result.returncode == 0, result.stderr
    assert "test_module_discovery.py" in result.stdout, result.stdout
    assert "test_navigation.py" not in result.stdout, result.stdout
