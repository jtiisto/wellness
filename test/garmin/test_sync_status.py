"""GET /api/garmin/sync/status and the durable sync_status backstop.

Two contracts live here: optional keys are OMITTED (never null), and the
cooldown survives a server restart by consulting garmy's own record of when it
last synced — the one thing the in-process clock cannot know.
"""

import pytest

from .conftest import marker_lines, poll_for, write_sync_status


def _status(client):
    resp = client.get("/api/garmin/sync/status")
    assert resp.status_code == 200
    return resp.json()


@pytest.mark.integration
class TestOmittedNeverNull:
    def test_fresh_server_reports_only_running(self, client):
        """Nothing has run and no garmy DB exists (conftest pins it at a
        nonexistent path), so every optional key must be ABSENT — not null."""
        assert _status(client) == {"running": False}

    def test_absent_keys_are_absent_not_null(self, client):
        body = _status(client)
        assert set(body) == {"running"}
        for key in ("last_finished_at", "last_outcome", "last_synced_at"):
            assert key not in body

    def test_keys_appear_once_they_have_values(self, client, sync_script,
                                               garmy_sync_db, monkeypatch):
        script, _ = sync_script()
        monkeypatch.setenv("GARMIN_SYNC_CMD", str(script))
        monkeypatch.setenv("GARMIN_DB_PATH", str(garmy_sync_db["path"]))

        client.post("/api/garmin/sync")
        assert poll_for(lambda: _status(client)["running"] is False, timeout=10.0)

        body = _status(client)
        assert set(body) == {"running", "last_finished_at", "last_outcome",
                             "last_synced_at"}
        assert body["last_outcome"] == "ok"


@pytest.mark.integration
class TestLastSyncedAt:
    def test_reports_the_newest_stamp_as_epoch_ms(self, client, garmy_sync_db,
                                                  monkeypatch):
        """MAX across a fraction-less row, a fractional row and a NULL one."""
        monkeypatch.setenv("GARMIN_DB_PATH", str(garmy_sync_db["path"]))
        assert _status(client)["last_synced_at"] == garmy_sync_db["newest_ms"]

    def test_fraction_less_stamps_parse(self, client, tmp_path, monkeypatch):
        """garmy omits microseconds when they are exactly zero."""
        path = write_sync_status(tmp_path / "whole_seconds.db",
                                 ["2030-01-05 06:07:08"])
        monkeypatch.setenv("GARMIN_DB_PATH", str(path))
        from datetime import datetime, timezone
        expected = int(datetime(2030, 1, 5, 6, 7, 8,
                                tzinfo=timezone.utc).timestamp() * 1000)
        assert _status(client)["last_synced_at"] == expected

    def test_all_null_stamps_omit_the_key(self, client, tmp_path, monkeypatch):
        """Every row pending: MAX is NULL, so there is nothing to report."""
        path = write_sync_status(tmp_path / "all_pending.db", [None, None])
        monkeypatch.setenv("GARMIN_DB_PATH", str(path))
        assert "last_synced_at" not in _status(client)

    def test_unparseable_stamp_omits_the_key(self, client, tmp_path, monkeypatch):
        path = write_sync_status(tmp_path / "garbage.db", ["not a timestamp"])
        monkeypatch.setenv("GARMIN_DB_PATH", str(path))
        body = _status(client)
        assert "last_synced_at" not in body
        assert body["running"] is False

    def test_missing_table_omits_the_key(self, client, tmp_path, monkeypatch):
        """A foreign or half-migrated DB must degrade, not 500."""
        path = write_sync_status(tmp_path / "no_table.db", [], create_table=False)
        monkeypatch.setenv("GARMIN_DB_PATH", str(path))
        assert _status(client) == {"running": False}

    def test_missing_db_file_omits_the_key(self, client, tmp_path, monkeypatch):
        """The dev-machine case: no garmy at all. A read-only connect would
        RAISE on a missing file, so the exists() guard is load-bearing."""
        monkeypatch.setenv("GARMIN_DB_PATH", str(tmp_path / "nothing_here.db"))
        assert _status(client) == {"running": False}


@pytest.mark.integration
class TestDurableCooldownBackstop:
    def test_recent_db_sync_blocks_a_pull_with_no_in_process_history(
            self, client, sync_script, garmy_db_synced_now, monkeypatch):
        """The cross-restart case: this process has never run a sync, but the
        DB proves one finished a minute ago — a pull must not start another.

        Also covers cron: the closure cannot see cron's flight, but cron's
        result lands in the same table.
        """
        script, marker = sync_script()
        monkeypatch.setenv("GARMIN_SYNC_CMD", str(script))
        monkeypatch.setenv("GARMIN_DB_PATH", str(garmy_db_synced_now["path"]))

        resp = client.post("/api/garmin/sync")
        assert resp.json()["status"] == "cooldown"
        assert resp.json()["retry_in_sec"] > 0
        assert marker_lines(marker) == 0, "a sync started despite the backstop"

    def test_old_db_sync_does_not_block(self, client, sync_script, tmp_path,
                                        monkeypatch):
        """Evidence of a sync long past is no reason to refuse a new one."""
        path = write_sync_status(tmp_path / "stale.db", ["2020-01-01 00:00:00"])
        script, marker = sync_script()
        monkeypatch.setenv("GARMIN_SYNC_CMD", str(script))
        monkeypatch.setenv("GARMIN_DB_PATH", str(path))

        assert client.post("/api/garmin/sync").json() == {"status": "started"}
        assert poll_for(lambda: marker_lines(marker) == 1)

    def test_future_stamp_is_ignored_not_honored(self, client, sync_script,
                                                 garmy_sync_db, monkeypatch):
        """A stamp AHEAD of now must not block anything.

        Regression pin for a real hole this suite's own far-future fixture
        convention exposed: honoring a future stamp yields a remaining time
        LONGER than the cooldown window, so every subsequent pull returns
        cooldown with a retry_in_sec that never expires — on-demand sync dies
        silently until the skew clears. One redundant run is the cheaper wrong
        answer, and the in-process clock still guards against a pull storm.
        """
        script, marker = sync_script()
        monkeypatch.setenv("GARMIN_SYNC_CMD", str(script))
        monkeypatch.setenv("GARMIN_DB_PATH", str(garmy_sync_db["path"]))

        assert client.post("/api/garmin/sync").json() == {"status": "started"}
        assert poll_for(lambda: marker_lines(marker) == 1)

    def test_future_stamp_still_reports_last_synced_at(self, client,
                                                       garmy_sync_db, monkeypatch):
        """Ignoring a future stamp for the COOLDOWN is not the same as hiding
        it: the client is still told what the DB says."""
        monkeypatch.setenv("GARMIN_DB_PATH", str(garmy_sync_db["path"]))
        assert _status(client)["last_synced_at"] == garmy_sync_db["newest_ms"]

    def test_cooldown_reports_the_larger_of_both_clocks(
            self, client, sync_script, monkeypatch):
        """An attempt nine minutes ago says 'one minute left'; a cron sync one
        minute ago says 'nine minutes left'. retry_in_sec must carry the
        LARGER number — the re-check would block a premature pull anyway, but
        the number handed to the client is a promise, not a hint. (Replaces
        the old skip-the-DB-read pin: both clocks are now always consulted.)"""
        clock = {"now": 1000.0}
        monkeypatch.setattr("modules.garmin._now_mono", lambda: clock["now"])
        script, _ = sync_script()
        monkeypatch.setenv("GARMIN_SYNC_CMD", str(script))

        client.post("/api/garmin/sync")            # attempt at mono 1000
        assert poll_for(lambda: _status(client)["running"] is False, timeout=10.0)

        clock["now"] = 1000.0 + 540.0              # 9 min later: 60s left local
        from modules import garmin as garmin_mod
        monkeypatch.setattr(                        # durable: synced 60s ago
            "modules.garmin._read_last_synced_ms",
            lambda: garmin_mod._now_epoch_ms() - 60_000)

        body = client.post("/api/garmin/sync").json()
        assert body["status"] == "cooldown"
        # 600 - 60 = 540 from the durable clock; the 60s local remainder must
        # not shadow it. ceil() tolerance for the ms between read and now.
        assert 540 <= body["retry_in_sec"] <= 541
