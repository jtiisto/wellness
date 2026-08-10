"""The protocol spec's canonical payloads, POSTed at the real endpoints.

`test/hr/golden/` holds the example payloads from
`~/dev/native/wellness/specs/hr-protocol.md` copied byte-for-byte; the Android
client keeps the same bytes under `testdata/golden/hr/`. Any protocol change
regenerates both sides in one change set, and these tests are what stops the
spec's examples from quietly drifting into fiction on this side.
"""
import json
import re

import pytest

from ..conftest import GOLDEN_DIR

SAMPLES = "samples-batch-request.json"
SET_EVENTS = "set-events-batch-request.json"
SESSIONS = "sessions-batch-request.json"
INGEST_RESPONSE = "ingest-response.json"
UPSERT_RESPONSE = "upsert-response.json"


@pytest.mark.integration
class TestGoldenPayloads:
    def test_samples_payload_returns_the_golden_response(self, post_golden, golden_json):
        response = post_golden("/api/hr/samples/batch", SAMPLES)
        assert response.status_code == 200
        assert response.json() == golden_json(INGEST_RESPONSE)

    def test_samples_payload_stores_its_three_rows(self, post_golden, hr_rows):
        post_golden("/api/hr/samples/batch", SAMPLES)
        rows = hr_rows("intervals", order_by="timestamp_ms, seq")
        assert len(rows) == 3
        # Two beats share a receipt millisecond, separated only by seq.
        assert [r["seq"] for r in rows] == [0, 1, 0]
        # The spec's second sample carries the artifact sentinel, the third the
        # gap flag; none carries sensorType, so all three take the default.
        assert rows[1]["rr_interval_ms"] == 0
        assert [r["is_gap_before"] for r in rows] == [0, 0, 1]
        assert {r["sensor_type"] for r in rows} == {"garmin_hrm"}

    def test_set_events_payload_returns_the_golden_response(self, post_golden, golden_json):
        response = post_golden("/api/hr/set-events/batch", SET_EVENTS)
        assert response.status_code == 200
        assert response.json() == golden_json(INGEST_RESPONSE)

    def test_set_events_payload_stores_its_three_rows(self, post_golden, hr_rows):
        post_golden("/api/hr/set-events/batch", SET_EVENTS)
        rows = hr_rows("set_events", order_by="client_timestamp_ms")
        assert len(rows) == 3
        assert [r["action"] for r in rows] == ["check", "uncheck", "check"]
        # A set tick, its undo, then a checklist toggle that carries an itemKey
        # and no session.
        assert [(r["set_num"], r["item_key"]) for r in rows] == [
            (1, None), (1, None), (None, "fixture-item-a")]
        assert rows[2]["session_id"] is None

    def test_sessions_payload_returns_the_golden_response(self, post_golden, golden_json):
        response = post_golden("/api/hr/sessions/batch", SESSIONS)
        assert response.status_code == 200
        assert response.json() == golden_json(UPSERT_RESPONSE)

    def test_sessions_payload_stores_an_open_workout_anchored_session(
            self, post_golden, hr_rows):
        post_golden("/api/hr/sessions/batch", SESSIONS)
        row = hr_rows("sessions")[0]
        assert (row["workout_date"], row["workout_session_id"]) == ("2030-01-03", 42)
        assert row["ended_at_ms"] is None  # omitted while the session is open

    def test_golden_batches_are_idempotent(self, post_golden, hr_rows):
        """The retry the spec promises is harmless: same bytes, zero new rows."""
        post_golden("/api/hr/samples/batch", SAMPLES)
        post_golden("/api/hr/set-events/batch", SET_EVENTS)
        assert post_golden("/api/hr/samples/batch", SAMPLES).json() == {
            "accepted": 0, "duplicates": 3, "totalReceived": 3}
        assert post_golden("/api/hr/set-events/batch", SET_EVENTS).json() == {
            "accepted": 0, "duplicates": 3, "totalReceived": 3}
        assert len(hr_rows("intervals")) == 3
        assert len(hr_rows("set_events")) == 3

    def test_response_bytes_match_the_golden_files(self, post_golden):
        """Stronger than the parsed comparison: pins key ORDER and the compact
        separators, i.e. the exact bytes a client parser sees."""
        response = post_golden("/api/hr/samples/batch", SAMPLES)
        assert response.content == (GOLDEN_DIR / INGEST_RESPONSE).read_bytes().strip()
        response = post_golden("/api/hr/sessions/batch", SESSIONS)
        assert response.content == (GOLDEN_DIR / UPSERT_RESPONSE).read_bytes().strip()

    def test_status_counts_the_golden_rows(self, post_golden, client):
        """End-to-end across both stages: ingest via the batch endpoints, read
        back through the probe the client actually calls."""
        post_golden("/api/hr/samples/batch", SAMPLES)
        post_golden("/api/hr/set-events/batch", SET_EVENTS)
        post_golden("/api/hr/sessions/batch", SESSIONS)
        assert client.get("/api/hr/status").json() == {
            "status": "ok", "samplesCount": 3, "setEventsCount": 3,
            "sessionsCount": 1,
        }


@pytest.mark.integration
class TestGoldenFilesThemselves:
    """Guards on the checked-in bytes, independent of any endpoint."""

    @pytest.mark.parametrize(
        "name", [SAMPLES, SET_EVENTS, SESSIONS, INGEST_RESPONSE, UPSERT_RESPONSE])
    def test_is_parseable_json(self, name, golden_json):
        assert isinstance(golden_json(name), dict)

    @pytest.mark.parametrize("name", [SAMPLES, SET_EVENTS, SESSIONS])
    def test_every_date_literal_is_far_future(self, name):
        """This repo is public and its pre-commit guard scans staged literals
        against the live health databases, so a plausible past date in a fixture
        is a collision waiting to happen. 2030+ makes collision impossible."""
        text = (GOLDEN_DIR / name).read_text()
        years = {int(y) for y in re.findall(r"\b(\d{4})-\d{2}-\d{2}\b", text)}
        assert all(year >= 2030 for year in years), sorted(years)

    @pytest.mark.parametrize("name", [SAMPLES, SET_EVENTS, SESSIONS])
    def test_no_optional_field_is_sent_as_null(self, name):
        """Optionals are omitted, never null — the convention the server's
        defaults depend on."""
        payload = json.loads((GOLDEN_DIR / name).read_text())
        rows = next(v for k, v in payload.items() if k != "clientId")
        assert all(value is not None for row in rows for value in row.values())
