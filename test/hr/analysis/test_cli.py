"""Tests for `python -m hr_analysis`.

The CLI is the package's only entry point, and the state it meets most often on
a fresh install is "no database yet" — history starts empty by design, with no
import from the pulse-bridge archive. So the empty and absent cases are pinned
as hard as the working one: a stack trace here is a bug, not a diagnostic.
"""
import builtins
import json

import pytest

from hr_analysis.__main__ import main

from .conftest import T0, beat_rows, requires_matplotlib


@pytest.fixture
def cli_db(hr_db, monkeypatch):
    """Point the CLI's path resolution at an isolated temp hr.db."""
    db_path, insert = hr_db
    monkeypatch.setenv("HR_DB_PATH", str(db_path))
    return db_path, insert


@pytest.mark.unit
class TestList:
    def test_lists_sessions_newest_first(self, cli_db, capsys):
        _, insert = cli_db
        insert(intervals=(beat_rows("older-session", 3)
                          + beat_rows("newer-session", 5, t0=T0 + 500_000)),
               sessions=[("newer-session", "dev-a", T0, None, "2030-05-06", 9, "x")])

        assert main(["--list"]) == 0
        out = capsys.readouterr().out
        assert out.index("newer-session") < out.index("older-session")
        assert "2030-05-06" in out          # workout date decorates the row
        assert "-" in out                   # ...and its absence renders a dash

    def test_empty_history_says_so_and_succeeds(self, cli_db, capsys):
        assert main(["--list"]) == 0
        assert capsys.readouterr().out.strip() == "No sessions found."


@pytest.mark.unit
class TestAnalyze:
    def _seed_analysable(self, insert, session="s-1"):
        # 5 minutes of steady beats: long enough for full 2-minute DFA windows
        insert(intervals=beat_rows(session, 400, rr_ms=750, hr_bpm=80))

    def test_session_prints_a_summary_and_writes_both_files(self, cli_db, capsys, tmp_path):
        _, insert = cli_db
        self._seed_analysable(insert)
        out_dir = tmp_path / "reports"

        assert main(["--session", "s-1", "--out", str(out_dir)]) == 0
        out = capsys.readouterr().out
        assert out.startswith("Session s-1")
        assert "VERDICT:" in out

        report = json.loads((out_dir / "report_s-1.json").read_text())
        assert report["session_id"] == "s-1"
        assert report["beats"] == 400

    def test_latest_analyzes_the_newest_session(self, cli_db, capsys, tmp_path):
        _, insert = cli_db
        self._seed_analysable(insert, "old")
        insert(intervals=beat_rows("new", 400, rr_ms=750, hr_bpm=80, t0=T0 + 9_000_000))

        assert main(["--latest", "--out", str(tmp_path)]) == 0
        assert capsys.readouterr().out.startswith("Session new")

    def test_hrmax_adds_the_zone_line(self, cli_db, capsys, tmp_path):
        _, insert = cli_db
        self._seed_analysable(insert)
        assert main(["--session", "s-1", "--hrmax", "185", "--out", str(tmp_path)]) == 0
        assert "zones" in capsys.readouterr().out

    def test_no_files_writes_nothing(self, cli_db, capsys, tmp_path):
        _, insert = cli_db
        self._seed_analysable(insert)
        out_dir = tmp_path / "reports"

        assert main(["--session", "s-1", "--no-files", "--out", str(out_dir)]) == 0
        assert "VERDICT:" in capsys.readouterr().out
        assert not out_dir.exists()

    @requires_matplotlib
    def test_reports_the_png_it_wrote(self, cli_db, capsys, tmp_path):
        _, insert = cli_db
        self._seed_analysable(insert)
        assert main(["--session", "s-1", "--out", str(tmp_path)]) == 0
        assert "wrote" in capsys.readouterr().out
        assert (tmp_path / "report_s-1.png").exists()

    def test_degrades_to_json_only_without_matplotlib(self, cli_db, capsys, tmp_path,
                                                      monkeypatch):
        """matplotlib is an optional dependency: no chart, no crash, exit 0 —
        and the user is told why the PNG is missing rather than left guessing."""
        _, insert = cli_db
        self._seed_analysable(insert)
        real_import = builtins.__import__

        def no_matplotlib(name, *args, **kwargs):
            if name == "matplotlib" or name.startswith("matplotlib."):
                raise ImportError("No module named 'matplotlib'")
            return real_import(name, *args, **kwargs)

        monkeypatch.setattr(builtins, "__import__", no_matplotlib)
        assert main(["--session", "s-1", "--out", str(tmp_path)]) == 0

        out = capsys.readouterr().out
        assert "(PNG skipped — matplotlib not installed)" in out
        assert (tmp_path / "report_s-1.json").exists()
        assert not list(tmp_path.glob("*.png"))

    def test_unknown_session_fails_cleanly(self, cli_db, capsys, tmp_path):
        _, insert = cli_db
        self._seed_analysable(insert)
        assert main(["--session", "ghost", "--out", str(tmp_path)]) == 1
        assert "No data for session ghost" in capsys.readouterr().err

    def test_latest_on_empty_history_is_not_an_error(self, cli_db, capsys):
        assert main(["--latest"]) == 0
        assert capsys.readouterr().out.strip() == "No sessions found."


@pytest.mark.unit
class TestAbsentDatabase:
    """A fresh install has no hr.db at all until the first batch arrives."""

    @pytest.fixture(autouse=True)
    def _no_database(self, tmp_path, monkeypatch):
        self.missing = tmp_path / "hr.db"
        monkeypatch.setenv("HR_DB_PATH", str(self.missing))

    @pytest.mark.parametrize("argv", [["--list"], ["--latest"], ["--session", "s-1"]])
    def test_every_mode_explains_itself_instead_of_raising(self, argv, capsys):
        assert main(argv) == 1
        err = capsys.readouterr().err
        assert "No HR database at" in err
        assert "HR_DB_PATH" in err          # tells the user how to point elsewhere
        assert "Traceback" not in err

    def test_the_cli_does_not_create_the_database(self, capsys):
        main(["--list"])
        capsys.readouterr()
        assert not self.missing.exists()


@pytest.mark.unit
class TestArgumentErrors:
    def test_no_mode_selected_is_a_usage_error(self, cli_db, capsys):
        with pytest.raises(SystemExit) as exc:
            main([])
        assert exc.value.code == 2
        assert "provide --session ID, --latest, or --list" in capsys.readouterr().err
