"""POST /api/garmin/sync — single-flight, cooldown, timeout, unconfigured.

Every test points GARMIN_SYNC_CMD at a script this suite wrote, and does so in
the test BODY: the root conftest's own setenv (a nonexistent path) runs when the
`client` fixture builds the app, so a fixture-level setenv would be overwritten.
Because the module resolves its seams per request, re-pointing the variable is
enough — no app rebuild (contrast test/trends/test_weight_endpoint.py, whose
router resolves at create_app time).
"""

import asyncio

import pytest

from .conftest import marker_lines, poll_for


def _use(monkeypatch, script):
    monkeypatch.setenv("GARMIN_SYNC_CMD", str(script))


def _status(client):
    resp = client.get("/api/garmin/sync/status")
    assert resp.status_code == 200
    return resp.json()


def _settled(client):
    """Wait for the background run to release the single-flight flag."""
    return poll_for(lambda: _status(client)["running"] is False, timeout=10.0)


@pytest.mark.integration
class TestTriggerSync:
    def test_started_runs_the_script(self, client, sync_script, monkeypatch):
        script, marker = sync_script()
        _use(monkeypatch, script)

        resp = client.post("/api/garmin/sync")
        assert resp.status_code == 200
        assert resp.json() == {"status": "started"}

        assert poll_for(lambda: marker_lines(marker) == 1), "script never ran"

    def test_script_receives_the_on_demand_scope(self, client, sync_script,
                                                 monkeypatch):
        """2/2 — a top-up, not the cron's 7/3 sweep."""
        script, marker = sync_script()
        _use(monkeypatch, script)

        client.post("/api/garmin/sync")
        assert poll_for(lambda: marker_lines(marker) == 1)
        assert marker.read_text().strip() == "ran 2 2"

    def test_completion_records_ok(self, client, sync_script, monkeypatch):
        script, _ = sync_script()
        _use(monkeypatch, script)

        client.post("/api/garmin/sync")
        assert _settled(client)

        status = _status(client)
        assert status["last_outcome"] == "ok"
        assert isinstance(status["last_finished_at"], int)
        assert status["last_finished_at"] > 1_700_000_000_000  # epoch ms, not s

    def test_nonzero_exit_records_failed(self, client, sync_script, monkeypatch):
        script, _ = sync_script(exit_code=3)
        _use(monkeypatch, script)

        client.post("/api/garmin/sync")
        assert _settled(client)
        assert _status(client)["last_outcome"] == "failed"

    def test_second_pull_attaches_to_the_running_sync(self, client, sync_script,
                                                      monkeypatch):
        """"Already running" is success-shaped, not a 409: the pull gesture
        attaches to the flight instead of reporting an error."""
        script, marker = sync_script(body='sleep 5\n')
        _use(monkeypatch, script)

        assert client.post("/api/garmin/sync").json() == {"status": "started"}

        second = client.post("/api/garmin/sync")
        assert second.status_code == 200
        assert second.json() == {"status": "running"}
        assert _status(client)["running"] is True
        assert marker_lines(marker) == 0  # the sleeping script wrote nothing

    def test_unconfigured_when_script_missing(self, client, monkeypatch, tmp_path):
        """The root conftest's default state — a path that does not exist."""
        _use(monkeypatch, tmp_path / "definitely-not-here.sh")
        assert client.post("/api/garmin/sync").json() == {"status": "unconfigured"}

    def test_unconfigured_when_seam_returns_none(self, client, monkeypatch):
        """A clone with no script at all (dev machine without garmy)."""
        monkeypatch.setattr("modules.garmin.get_garmin_sync_cmd", lambda: None)
        assert client.post("/api/garmin/sync").json() == {"status": "unconfigured"}

    def test_unconfigured_does_not_start_the_cooldown(self, client, sync_script,
                                                      monkeypatch, tmp_path):
        """A pull that found nothing to run must not block the next one."""
        _use(monkeypatch, tmp_path / "missing.sh")
        assert client.post("/api/garmin/sync").json() == {"status": "unconfigured"}

        script, marker = sync_script()
        _use(monkeypatch, script)
        assert client.post("/api/garmin/sync").json() == {"status": "started"}
        assert poll_for(lambda: marker_lines(marker) == 1)


@pytest.mark.integration
class TestCooldown:
    def test_second_pull_within_the_window_is_refused(self, client, sync_script,
                                                      monkeypatch):
        script, marker = sync_script()
        _use(monkeypatch, script)

        client.post("/api/garmin/sync")
        assert _settled(client)

        resp = client.post("/api/garmin/sync")
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "cooldown"
        assert 0 < body["retry_in_sec"] <= 600
        # The point of the cooldown: no second garmy process.
        assert marker_lines(marker) == 1

    def test_cooldown_runs_from_the_attempt_not_the_success(self, client,
                                                            sync_script,
                                                            monkeypatch):
        """A failing script must not become a retry loop driven by a thumb."""
        script, _ = sync_script(exit_code=1)
        _use(monkeypatch, script)

        client.post("/api/garmin/sync")
        assert _settled(client)
        assert _status(client)["last_outcome"] == "failed"

        assert client.post("/api/garmin/sync").json()["status"] == "cooldown"

    def test_pull_after_the_window_starts_again(self, client, sync_script,
                                                monkeypatch):
        """Time advances via the monotonic seam — no sleeping in tests."""
        clock = {"now": 1000.0}
        monkeypatch.setattr("modules.garmin._now_mono", lambda: clock["now"])

        script, marker = sync_script()
        _use(monkeypatch, script)

        client.post("/api/garmin/sync")
        assert _settled(client)
        assert poll_for(lambda: marker_lines(marker) == 1)

        clock["now"] += 601  # past COOLDOWN_SECONDS
        assert client.post("/api/garmin/sync").json() == {"status": "started"}
        assert poll_for(lambda: marker_lines(marker) == 2)

    def test_running_wins_over_cooldown(self, client, sync_script, monkeypatch):
        """Both conditions hold during a long run; the more informative answer
        is the one the client can act on by waiting."""
        script, _ = sync_script(body='sleep 5\n')
        _use(monkeypatch, script)

        client.post("/api/garmin/sync")
        assert client.post("/api/garmin/sync").json() == {"status": "running"}


@pytest.mark.integration
class TestTimeout:
    def test_hung_sync_is_killed_and_recorded_failed(self, client, sync_script,
                                                     monkeypatch):
        script, _ = sync_script(body='sleep 30\n')
        _use(monkeypatch, script)
        monkeypatch.setattr("modules.garmin.SYNC_TIMEOUT_SECONDS", 1)

        client.post("/api/garmin/sync")

        assert _settled(client), "timeout never released the single-flight flag"
        assert _status(client)["last_outcome"] == "failed"

    def test_timeout_kills_the_whole_process_group(self, client, sync_script,
                                                   monkeypatch, tmp_path):
        """start_new_session + killpg, proven by a CHILD process.

        The script backgrounds a child that would write its marker at t≈2s,
        then sleeps 30. A bare proc.kill() would take out only the shell and
        leave the child to write; killpg takes out the group. Waiting ~4s is
        therefore a real proof, not a proxy.
        """
        child_marker = tmp_path / "child-survived.marker"
        script, _ = sync_script(
            body=f'( sleep 2; echo alive >> "{child_marker}" ) &\nsleep 30\n')
        _use(monkeypatch, script)
        monkeypatch.setattr("modules.garmin.SYNC_TIMEOUT_SECONDS", 1)

        client.post("/api/garmin/sync")
        assert _settled(client)

        # Give the orphaned child more than its 2s if it were still alive.
        assert not poll_for(child_marker.exists, timeout=4.0), \
            "a child outlived the timeout — the process group was not killed"


@pytest.mark.integration
class TestRunnerFailsSafe:
    """Whatever goes wrong, the single-flight flag must come back — a wedged
    `running` would block every future pull until a restart."""

    def test_non_executable_script_records_failed(self, client, tmp_path,
                                                  monkeypatch):
        """The file exists (so it is not 'unconfigured') but cannot be exec'd."""
        script = tmp_path / "not-executable.sh"
        script.write_text("#!/bin/bash\nexit 0\n")  # deliberately not chmod +x
        _use(monkeypatch, script)

        assert client.post("/api/garmin/sync").json() == {"status": "started"}
        assert _settled(client), "a failed exec left the module marked running"
        assert _status(client)["last_outcome"] == "failed"

    def test_unexpected_error_records_failed(self, client, sync_script,
                                             monkeypatch):
        script, _ = sync_script()
        _use(monkeypatch, script)

        async def boom(*args, **kwargs):
            raise RuntimeError("no process for you")

        monkeypatch.setattr("modules.garmin.asyncio.create_subprocess_exec", boom)

        assert client.post("/api/garmin/sync").json() == {"status": "started"}
        assert _settled(client)
        assert _status(client)["last_outcome"] == "failed"

    def test_failed_run_still_allows_a_later_pull(self, client, sync_script,
                                                  tmp_path, monkeypatch):
        """A failure burns the cooldown like any other attempt, but once the
        window passes the module works again — it is not poisoned."""
        clock = {"now": 5000.0}
        monkeypatch.setattr("modules.garmin._now_mono", lambda: clock["now"])

        broken = tmp_path / "broken.sh"
        broken.write_text("#!/bin/bash\nexit 0\n")  # not executable
        _use(monkeypatch, broken)
        client.post("/api/garmin/sync")
        assert _settled(client)
        assert _status(client)["last_outcome"] == "failed"

        clock["now"] += 601
        script, marker = sync_script()
        _use(monkeypatch, script)
        assert client.post("/api/garmin/sync").json() == {"status": "started"}
        assert poll_for(lambda: marker_lines(marker) == 1)
        assert _settled(client)
        assert _status(client)["last_outcome"] == "ok"


@pytest.mark.integration
class TestHandlerStaysOffTheEventLoop:
    """db.py's standing contract: no blocking sqlite3 on the loop. Both handlers
    are `async def`s, so the garmy read MUST go through asyncio.to_thread."""

    def _probe(self, monkeypatch):
        seen = {}

        def probe():
            try:
                asyncio.get_running_loop()
                seen["on_loop"] = True
            except RuntimeError:
                seen["on_loop"] = False  # a worker thread has no running loop
            return None

        monkeypatch.setattr("modules.garmin._read_last_synced_ms", probe)
        return seen

    def test_status_reads_the_db_off_the_loop(self, client, monkeypatch):
        seen = self._probe(monkeypatch)
        client.get("/api/garmin/sync/status")
        assert seen == {"on_loop": False}

    def test_trigger_reads_the_db_off_the_loop(self, client, sync_script,
                                               monkeypatch):
        script, _ = sync_script()
        _use(monkeypatch, script)
        seen = self._probe(monkeypatch)
        client.post("/api/garmin/sync")
        assert seen == {"on_loop": False}
