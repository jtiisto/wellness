"""Garmin module registration and the config seam (mirrors trends' Phase 0).

The garmin module is headless (no tab) and owns ONE small table of its own —
`client_zones`, the device-clock timeline — so create_app calls its factory
with a db_path like every other DB-owning module. Its other two seams are
external: the sync script (resolving exactly like get_hook_path) and garmy's
health DB, whose env var must stay distinct from the module's own.
"""

from datetime import datetime, timezone
from pathlib import Path

import pytest

from config import (MODULES, get_garmin_sync_cmd, get_module_db_path,
                    get_garmin_db_path)
from modules.garmin import _parse_naive_utc


@pytest.mark.unit
class TestRegistrySeam:
    def _garmin(self):
        return next(m for m in MODULES if m["id"] == "garmin")

    def test_garmin_entry_owns_its_own_database(self):
        garmin = self._garmin()
        assert garmin["db_env"] == "GARMIN_MODULE_DB_PATH"
        assert garmin["db_default"].name == "garmin.db"
        assert garmin["api_prefix"] == "/api/garmin"

    def test_module_db_and_garmy_db_are_different_seams(self, monkeypatch,
                                                        tmp_path):
        """The module's own storage and garmy's health database are two files
        behind two env vars. Collapsing them would point the device-clock
        writer at a database this repo must never write to."""
        monkeypatch.setenv("GARMIN_MODULE_DB_PATH", str(tmp_path / "mine.db"))
        monkeypatch.setenv("GARMIN_DB_PATH", str(tmp_path / "garmy.db"))
        assert get_module_db_path("garmin") == tmp_path / "mine.db"
        assert get_garmin_db_path() == tmp_path / "garmy.db"

    def test_garmin_entry_is_headless(self):
        """Headless entries carry no presentation fields — the /api/modules
        projection reads name/icon/color unconditionally, so a listed tabless
        module would KeyError the endpoint the app shell boots on."""
        garmin = self._garmin()
        assert garmin["headless"] is True
        assert not {"name", "icon", "color"} & garmin.keys()

    def test_garmin_is_last_and_after_the_ui_modules(self):
        """MODULES order is tab order for everything visible, so headless
        entries belong at the end."""
        ids = [m["id"] for m in MODULES]
        assert ids[-1] == "garmin"
        visible = [m["id"] for m in MODULES if not m.get("headless")]
        assert visible[-1] == "trends"


@pytest.mark.unit
class TestSyncCmdSeam:
    def test_env_var_wins(self, monkeypatch, tmp_path):
        script = tmp_path / "elsewhere.sh"
        monkeypatch.setenv("GARMIN_SYNC_CMD", str(script))
        assert get_garmin_sync_cmd() == script

    def test_env_var_is_not_existence_checked(self, monkeypatch, tmp_path):
        """Matching get_hook_path: the seam returns the configured path and the
        module decides at use. (That is what makes the root conftest's
        nonexistent-path pin read as 'unconfigured' rather than crashing.)"""
        missing = tmp_path / "nope.sh"
        monkeypatch.setenv("GARMIN_SYNC_CMD", str(missing))
        assert get_garmin_sync_cmd() == missing
        assert not missing.exists()

    def test_default_is_the_tracked_script(self, monkeypatch):
        monkeypatch.delenv("GARMIN_SYNC_CMD", raising=False)
        resolved = get_garmin_sync_cmd()
        assert resolved is not None
        assert resolved.name == "garmin-sync.sh"
        assert resolved.exists(), "bin/garmin-sync.sh must be tracked and present"

    def test_none_when_absent(self, monkeypatch, tmp_path):
        """A clone without the script (or a PROJECT_ROOT that has no bin/)
        degrades to None — the module then reports 'unconfigured'."""
        monkeypatch.delenv("GARMIN_SYNC_CMD", raising=False)
        import config
        monkeypatch.setattr(config, "PROJECT_ROOT", tmp_path)
        assert get_garmin_sync_cmd() is None


@pytest.mark.unit
class TestSyncScriptContract:
    """The script is the cron's script too — its defaults are load-bearing."""

    def _script(self):
        import config
        return config.PROJECT_ROOT / "bin" / "garmin-sync.sh"

    def test_script_is_executable(self):
        script = self._script()
        assert script.exists()
        assert script.stat().st_mode & 0o111, "garmin-sync.sh must be executable"

    def test_defaults_are_the_cron_scope(self):
        """No args = 7/3, so shipping this file replaces prod's untracked
        equivalent without changing what cron does."""
        text = self._script().read_text()
        assert '${1:-7}' in text
        assert '${2:-3}' in text

    def test_appends_timestamped_to_the_cron_log(self):
        """The user's sync trail must survive the replacement (review finding:
        a bare-stdout runner would end it)."""
        text = self._script().read_text()
        assert 'sync-cron.log' in text
        assert '>> "$LOG_FILE" 2>&1' in text
        assert "date '+%Y-%m-%d %H:%M:%S'" in text

    def test_garmy_src_is_overridable(self):
        assert '${GARMY_SRC:-$HOME/dev/garmy/src}' in self._script().read_text()


@pytest.mark.unit
class TestParseNaiveUtc:
    """garmy stamps synced_at with datetime.utcnow() — naive UTC, rendered with
    microseconds unless they are exactly zero."""

    def test_fractional_seconds(self):
        expected = datetime(2030, 1, 2, 14, 30, 5, 123456, tzinfo=timezone.utc)
        assert _parse_naive_utc("2030-01-02 14:30:05.123456") == \
            int(expected.timestamp() * 1000)

    def test_without_fractional_seconds(self):
        expected = datetime(2030, 1, 2, 14, 30, 5, tzinfo=timezone.utc)
        assert _parse_naive_utc("2030-01-02 14:30:05") == \
            int(expected.timestamp() * 1000)

    def test_naive_is_read_as_utc_not_local(self):
        """The whole point: a naive stamp is UTC, so the epoch value must not
        drift with the server's timezone."""
        parsed = _parse_naive_utc("2030-01-02 00:00:00")
        assert parsed == int(
            datetime(2030, 1, 2, tzinfo=timezone.utc).timestamp() * 1000)

    def test_aware_input_is_honored_not_reinterpreted(self):
        """Defensive: if a future garmy ever wrote an offset, respect it."""
        assert _parse_naive_utc("2030-01-02 14:30:05+02:00") == int(
            datetime(2030, 1, 2, 12, 30, 5, tzinfo=timezone.utc).timestamp() * 1000)

    @pytest.mark.parametrize("value", ["", "not a date", "2030-13-45 99:99:99"])
    def test_unparseable_is_none(self, value):
        assert _parse_naive_utc(value) is None

    @pytest.mark.parametrize("value", [None, 12345, 3.5, b"2030-01-02"])
    def test_non_string_is_none(self, value):
        assert _parse_naive_utc(value) is None


@pytest.mark.integration
class TestScriptExecution:
    """Run the REAL bin/garmin-sync.sh against a fake python3 and a temp HOME.

    Source-grepping pins none of the script's actual contract — argv order,
    the log append, and exit-code propagation through the redirected block can
    all regress while a substring test stays green (review finding)."""

    def test_wrapper_argv_log_append_and_exit_code(self, tmp_path):
        import os
        import subprocess

        home = tmp_path / "home"
        (home / ".garmy").mkdir(parents=True)
        fake_bin = tmp_path / "bin"
        fake_bin.mkdir()
        argv_file = tmp_path / "argv.txt"
        fake = fake_bin / "python3"
        # Records its argv one-per-line, speaks on stdout, exits 3 — the exit
        # code is the part the server actually consumes.
        fake.write_text(
            "#!/bin/bash\n"
            f'printf \'%s\\n\' "$@" > "{argv_file}"\n'
            "echo fake-garmy-ran\n"
            "exit 3\n"
        )
        fake.chmod(0o755)

        script = Path(__file__).resolve().parents[2] / "bin" / "garmin-sync.sh"
        result = subprocess.run(
            [str(script), "2", "2"],
            env={"PATH": f"{fake_bin}:/usr/bin:/bin", "HOME": str(home),
                 "GARMY_SRC": str(tmp_path / "garmy-src")},
            capture_output=True, text=True, timeout=30,
        )

        # garmy's own exit code survives the log-redirect block.
        assert result.returncode == 3
        assert argv_file.read_text().splitlines() == [
            "-m", "garmy.localdb.cli",
            "--profile-path", str(home / ".garmy"),
            "sync", "--last-days", "2", "--resync-days", "2",
            "--progress", "silent",
        ]
        log = (home / ".garmy" / "sync-cron.log").read_text()
        assert "fake-garmy-ran" in log      # output landed in the trail...
        assert "--- " in log                # ...under a timestamped header
        assert result.stdout == ""          # ...and NOT on the caller's stdout
