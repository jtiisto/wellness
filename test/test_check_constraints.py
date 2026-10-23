"""Unit tests for bin/check_constraints.py, the venv-drift warning in pre-commit."""
import importlib.util
import pathlib

import pytest

pytestmark = pytest.mark.unit

_SCRIPT = pathlib.Path(__file__).resolve().parent.parent / "bin" / "check_constraints.py"
_spec = importlib.util.spec_from_file_location("check_constraints", _SCRIPT)
cc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cc)


def test_normalize_ignores_case_and_separators():
    assert cc.normalize("Typing_Extensions") == "typing-extensions"
    assert cc.normalize("zope.interface") == "zope-interface"
    assert cc.normalize("a__b--c") == "a-b-c"


def test_parse_constraints_reads_pins_and_skips_noise():
    text = "\n".join([
        "# header comment",
        "",
        "FastAPI==0.161.1",
        "pytest==9.1.1  # trailing comment",
        "requests>=2.31.0",  # not a pin: ignored
    ])
    assert cc.parse_constraints(text) == {"fastapi": "0.161.1", "pytest": "9.1.1"}


def test_drift_reports_wrong_and_missing_but_not_extra():
    pins = {"fastmcp": "2.14.7", "starlette": "1.9.0", "numpy": "2.3.0"}
    installed = {"fastmcp": "3.4.11", "starlette": "1.9.0", "py-spy": "0.4.0"}
    assert cc.drift(pins, installed) == [
        ("fastmcp", "2.14.7", "3.4.11"),
        ("numpy", "2.3.0", None),
    ]


def test_main_warns_but_passes_by_default(tmp_path, capsys):
    constraints = tmp_path / "constraints.txt"
    constraints.write_text("no-such-package-xyz==1.0\n")
    assert cc.main(["--constraints", str(constraints)]) == 0
    out = capsys.readouterr().out
    assert "no-such-package-xyz: pinned 1.0, installed missing" in out
    assert "pip install -r requirements.txt -c constraints.txt" in out


def test_main_strict_fails_on_drift(tmp_path):
    constraints = tmp_path / "constraints.txt"
    constraints.write_text("no-such-package-xyz==1.0\n")
    assert cc.main(["--strict", "--constraints", str(constraints)]) == 1


def test_main_is_silent_when_in_sync(tmp_path, capsys):
    constraints = tmp_path / "constraints.txt"
    constraints.write_text(f"pytest=={pytest.__version__}\n")
    assert cc.main(["--strict", "--constraints", str(constraints)]) == 0
    assert capsys.readouterr().out == ""


def test_main_skips_without_a_constraints_file(tmp_path, capsys):
    assert cc.main(["--constraints", str(tmp_path / "absent.txt")]) == 0
    assert "skipped" in capsys.readouterr().out
