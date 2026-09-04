"""The clock middleware: two advisory headers, and everything they must never
break.

The Android client sends `X-Client-Zone` and `X-Client-Offset-Min` on every
request (docs/ARCHITECTURE.md "Device clock"); the PWA sends neither. The
middleware publishes the parsed zone for handlers and hands the pair to the
garmin module's recorder. Both effects are invisible when they fail: a header
must never turn a request into a 4xx or a 5xx.

Zone ids here are real IANA ids (the validator resolves them); every value is
otherwise INVENTED.
"""

import sqlite3

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from server import ClientClockMiddleware, create_app
from modules.garmin import _zone_tail

HELSINKI = {"X-Client-Zone": "Europe/Helsinki", "X-Client-Offset-Min": "120"}
TOKYO = {"X-Client-Zone": "Asia/Tokyo", "X-Client-Offset-Min": "540"}


def _zone_rows(path):
    if not path.exists():
        return []
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    try:
        return [(r["zone_id"], r["offset_min"])
                for r in conn.execute(
                    "SELECT * FROM client_zones ORDER BY id")]
    except sqlite3.OperationalError:
        return []
    finally:
        conn.close()


@pytest.fixture
def probe(tmp_path):
    """A one-route app behind the middleware, reporting what the handler sees.

    Wrapping a bare app rather than the whole server keeps these tests about
    the middleware: no module DB, so nothing is recorded and the state
    publication is the only thing under test.
    """
    app = FastAPI()

    @app.get("/probe")
    def read_state(request: Request):
        if not hasattr(request.state, "client_zone"):
            return {"state": "absent"}
        zone = request.state.client_zone
        return {"state": "present",
                "zone": None if zone is None else str(zone)}

    with TestClient(ClientClockMiddleware(app, None)) as client:
        yield client


@pytest.mark.integration
class TestRequestState:
    def test_a_reported_zone_reaches_the_handler(self, probe):
        body = probe.get("/probe", headers=HELSINKI).json()
        assert body == {"state": "present", "zone": "Europe/Helsinki"}

    def test_no_headers_publish_nothing(self, probe):
        """A PWA request must look exactly as it did before this middleware —
        `request.state.client_zone` is not merely None, it is not there."""
        assert probe.get("/probe").json() == {"state": "absent"}

    @pytest.mark.parametrize("headers", [
        {"X-Client-Zone": "Europe/Helsinki"},          # id without an offset
        {"X-Client-Offset-Min": "120"},                # offset without an id
    ])
    def test_half_a_clock_is_no_clock(self, probe, headers):
        assert probe.get("/probe", headers=headers).json() == {"state": "absent"}

    def test_an_unparseable_offset_is_no_clock(self, probe):
        assert probe.get("/probe", headers={
            "X-Client-Zone": "Europe/Helsinki",
            "X-Client-Offset-Min": "not-a-number"}).json() == {"state": "absent"}

    @pytest.mark.parametrize("headers", [
        # An id this host cannot resolve — no DST rules, so no zone.
        {"X-Client-Zone": "Mars/Olympus", "X-Client-Offset-Min": "90"},
        # A path, not a key.
        {"X-Client-Zone": "../../../etc/passwd", "X-Client-Offset-Min": "0"},
        # A REAL zone carrying an offset no zone has. This is the one that
        # matters: publishing before validating would let it move the sleep
        # ledger's today while the recorder rejected it, so the ledger would
        # answer on a clock nothing in the database could explain.
        {"X-Client-Zone": "Pacific/Kiritimati", "X-Client-Offset-Min": "7"},
        {"X-Client-Zone": "Pacific/Kiritimati", "X-Client-Offset-Min": "1000"},
    ])
    def test_an_unacceptable_clock_publishes_nothing(self, probe, headers):
        """One authority decides. A pair the recorder would refuse must not
        reach a handler either — state and storage cannot disagree."""
        resp = probe.get("/probe", headers=headers)
        assert resp.status_code == 200
        assert resp.json() == {"state": "absent"}

    def test_a_very_long_zone_header_is_ignored_not_fatal(self, probe):
        resp = probe.get("/probe", headers={
            "X-Client-Zone": "Europe/" + "x" * 4000,
            "X-Client-Offset-Min": "120"})
        assert resp.status_code == 200
        assert resp.json() == {"state": "absent"}

    def test_a_non_ascii_zone_header_is_ignored_not_fatal(self, probe):
        """Sent as raw bytes, because that is the only way a non-ASCII header
        reaches a server at all. Latin-1 is the wire encoding, so it decodes
        cleanly and then fails to resolve — the failure must land in the
        validator, not in a decode nobody guarded."""
        resp = probe.get("/probe", headers={
            "X-Client-Zone": "Europe/Z\u00fcrich".encode("latin-1"),
            "X-Client-Offset-Min": b"120"})
        assert resp.status_code == 200
        assert resp.json() == {"state": "absent"}

    def test_an_undecodable_zone_header_is_ignored_not_fatal(self, probe):
        """Bytes that are not latin-1-shaped text. Latin-1 maps every byte, so
        this cannot actually fail to decode — which is the point: the reader
        never raises on the wire, and the validator rejects the result."""
        resp = probe.get("/probe", headers={
            "X-Client-Zone": b"\xff\xfe\x00garbage",
            "X-Client-Offset-Min": b"120"})
        assert resp.status_code == 200
        assert resp.json() == {"state": "absent"}


@pytest.mark.integration
class TestRecording:
    """Through the real app, so the path the middleware writes to is the very
    one create_app handed the garmin module."""

    def test_valid_headers_write_one_point(self, client, tmp_garmin_module_db):
        resp = client.get("/api/modules", headers=HELSINKI)
        assert resp.status_code == 200
        assert _zone_rows(tmp_garmin_module_db) == [("Europe/Helsinki", 120)]

    def test_a_steady_phone_writes_once(self, client, tmp_garmin_module_db):
        for _ in range(3):
            client.get("/api/modules", headers=HELSINKI)
        assert _zone_rows(tmp_garmin_module_db) == [("Europe/Helsinki", 120)]

    def test_a_moved_phone_opens_a_second_point(self, client,
                                                tmp_garmin_module_db):
        client.get("/api/modules", headers=HELSINKI)
        client.get("/api/modules", headers=TOKYO)
        assert _zone_rows(tmp_garmin_module_db) == [
            ("Europe/Helsinki", 120), ("Asia/Tokyo", 540)]

    def test_no_headers_record_nothing(self, client, tmp_garmin_module_db):
        assert client.get("/api/modules").status_code == 200
        assert _zone_rows(tmp_garmin_module_db) == []

    @pytest.mark.parametrize("headers", [
        {"X-Client-Zone": "Mars/Olympus", "X-Client-Offset-Min": "90"},
        {"X-Client-Zone": "Europe/Helsinki", "X-Client-Offset-Min": "999"},
        {"X-Client-Zone": "Europe/Helsinki", "X-Client-Offset-Min": "7"},
        {"X-Client-Zone": "", "X-Client-Offset-Min": "0"},
        {"X-Client-Zone": "Europe/Helsinki", "X-Client-Offset-Min": "abc"},
    ])
    def test_garbage_headers_are_a_200_and_no_row(self, client, headers,
                                                  tmp_garmin_module_db):
        assert client.get("/api/modules", headers=headers).status_code == 200
        assert _zone_rows(tmp_garmin_module_db) == []

    def test_a_recorded_zone_does_not_change_the_response(
            self, client, tmp_garmin_module_db):
        """The headers are about a different subject entirely; the body they
        ride on must be untouched."""
        without = client.get("/api/modules")
        with_headers = client.get("/api/modules", headers=HELSINKI)
        assert with_headers.json() == without.json()


@pytest.mark.integration
class TestDisabledModule:
    def test_nothing_is_recorded_and_nothing_errors(self, tmp_path,
                                                    monkeypatch):
        """With the garmin module disabled there is no table to write to. The
        middleware still publishes the request's zone (handlers of other
        modules may want it) and records nothing."""
        monkeypatch.setenv("WELLNESS_DISABLED_MODULES", "garmin")
        monkeypatch.setenv("JOURNAL_DB_PATH", str(tmp_path / "journal.db"))
        monkeypatch.setenv("COACH_DB_PATH", str(tmp_path / "coach.db"))
        monkeypatch.setenv("ANALYSIS_DB_PATH", str(tmp_path / "analysis.db"))
        monkeypatch.setenv("HR_DB_PATH", str(tmp_path / "hr.db"))
        monkeypatch.setenv("GARMIN_MODULE_DB_PATH", str(tmp_path / "garmin.db"))
        monkeypatch.setenv("GARMIN_DB_PATH", str(tmp_path / "absent-garmy.db"))
        monkeypatch.setenv("BODYSPEC_DB_PATH", str(tmp_path / "absent-bs.db"))
        monkeypatch.setenv("QUESTY_DB_PATH", str(tmp_path / "absent-q.db"))

        import server as server_mod
        app = server_mod.create_app()
        # The clock middleware sits directly inside the client guard, and with
        # the module off it has nowhere to write.
        assert app.app.zone_db_path is None
        with TestClient(app) as c:
            assert c.get("/api/modules", headers=HELSINKI).status_code == 200
        assert not (tmp_path / "garmin.db").exists()
        _zone_tail.pop(str(tmp_path / "garmin.db"), None)


@pytest.mark.integration
class TestRecorderFailureIsInvisible:
    """The recorder promises never to raise. This pins what happens if it ever
    breaks that promise anyway — the request must not notice."""

    @pytest.fixture
    def exploding(self, tmp_path, monkeypatch):
        import server as server_mod

        def boom(*args, **kwargs):
            raise RuntimeError("recorder exploded")

        monkeypatch.setattr(server_mod, "record_client_zone", boom)

        app = FastAPI()

        @app.get("/probe")
        def read_state(request: Request):
            return {"state": "present" if hasattr(request.state, "client_zone")
                    else "absent"}

        with TestClient(ClientClockMiddleware(app, tmp_path / "zones.db")) as c:
            yield c

    def test_the_request_still_succeeds(self, exploding):
        resp = exploding.get("/probe", headers=HELSINKI)
        assert resp.status_code == 200
        # The handler ran BEFORE the recorder was reached, so it saw the zone.
        assert resp.json() == {"state": "present"}

    def test_the_next_headerless_request_sees_no_stale_zone(self, exploding):
        """State lives on the scope, so a failed write cannot leave a zone
        behind for the next caller to inherit."""
        exploding.get("/probe", headers=HELSINKI)
        assert exploding.get("/probe").json() == {"state": "absent"}
