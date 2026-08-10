"""Integration tests for POST /api/hr/sessions/batch.

Sessions are the one table that is upserted rather than insert-or-ignored: a
session row legitimately changes after its first upload (started ->
workout-anchored -> ended). All values synthetic, dates far-future per the spec.
"""
import pytest

CLIENT_ID = "fixture-client-hr-0001"
DEVICE_ID = "AA:BB:CC:DD:EE:FF"
SESSION_ID = "11111111-2222-3333-4444-555555555555"
STARTED_MS = 1769999990000


def _session(session_id=SESSION_ID, **overrides):
    """An open session — no endedAtMs, no workout anchor unless overridden."""
    return {
        "sessionId": session_id,
        "deviceId": DEVICE_ID,
        "startedAtMs": STARTED_MS,
        **overrides,
    }


def _post(client, *sessions):
    return client.post("/api/hr/sessions/batch",
                       json={"clientId": CLIENT_ID, "sessions": list(sessions)})


@pytest.mark.integration
class TestSessionsBatchHappyPath:
    def test_reports_counts_in_the_documented_shape(self, client):
        response = _post(client, _session())
        assert response.status_code == 200
        assert response.json() == {"upserted": 1, "totalReceived": 1}

    def test_stores_every_wire_field(self, client, hr_rows):
        _post(client, _session(endedAtMs=STARTED_MS + 3600000,
                               workoutDate="2030-01-03", workoutSessionId=42))
        row = hr_rows("sessions")[0]
        assert (row["session_id"], row["device_id"]) == (SESSION_ID, DEVICE_ID)
        assert (row["started_at_ms"], row["ended_at_ms"]) == (
            STARTED_MS, STARTED_MS + 3600000)
        assert (row["workout_date"], row["workout_session_id"]) == ("2030-01-03", 42)
        assert row["received_at"].endswith("Z")

    def test_open_session_leaves_the_optional_columns_null(self, client, hr_rows):
        _post(client, _session())
        row = hr_rows("sessions")[0]
        assert (row["ended_at_ms"], row["workout_date"],
                row["workout_session_id"]) == (None, None, None)

    def test_multiple_sessions_in_one_batch(self, client, hr_rows):
        response = _post(client, _session(), _session("fixture-session-2"))
        assert response.json() == {"upserted": 2, "totalReceived": 2}
        assert len(hr_rows("sessions")) == 2

    def test_empty_batch_is_an_accepted_no_op(self, client, hr_rows):
        assert _post(client).json() == {"upserted": 0, "totalReceived": 0}
        assert hr_rows("sessions") == []


@pytest.mark.integration
class TestSessionsBatchUpsertSemantics:
    """Last write wins, and 'full row' means the incoming row replaces the
    stored one outright — never merges into it."""

    def test_re_upload_replaces_the_row_rather_than_inserting(self, client, hr_rows):
        _post(client, _session(workoutDate="2030-01-03"))
        response = _post(client, _session(workoutDate="2030-01-03",
                                          endedAtMs=STARTED_MS + 1800000))
        assert response.json() == {"upserted": 1, "totalReceived": 1}
        rows = hr_rows("sessions")
        assert len(rows) == 1
        assert rows[0]["ended_at_ms"] == STARTED_MS + 1800000

    def test_omitting_a_previously_present_field_clears_the_column(self, client, hr_rows):
        """The load-bearing half of full-row semantics: the stored row stays a
        faithful copy of the client's, so a field the client no longer sends is
        cleared, not preserved from an earlier upload."""
        _post(client, _session(workoutDate="2030-01-03", workoutSessionId=42,
                               endedAtMs=STARTED_MS + 60000))
        _post(client, _session())  # same session, every optional now omitted
        row = hr_rows("sessions")[0]
        assert (row["ended_at_ms"], row["workout_date"],
                row["workout_session_id"]) == (None, None, None)

    def test_required_columns_are_overwritten_too(self, client, hr_rows):
        _post(client, _session())
        _post(client, _session(deviceId="11:22:33:44:55:66",
                               startedAtMs=STARTED_MS + 1000))
        row = hr_rows("sessions")[0]
        assert (row["device_id"], row["started_at_ms"]) == (
            "11:22:33:44:55:66", STARTED_MS + 1000)

    def test_re_upload_restamps_received_at(self, client, hr_rows):
        """Unlike the ignore-on-conflict tables, the server stamp tracks the
        latest upload of the session."""
        _post(client, _session())
        first_stamp = hr_rows("sessions")[0]["received_at"]
        _post(client, _session(endedAtMs=STARTED_MS + 1))
        assert hr_rows("sessions")[0]["received_at"] >= first_stamp

    def test_same_session_twice_in_one_batch_keeps_the_last(self, client, hr_rows):
        """`upserted` counts DISTINCT sessions written (the batch is deduped to
        each sessionId's last occurrence), so it reports 1 here — not 2 writes
        to the same row."""
        response = _post(client, _session(),
                         _session(endedAtMs=STARTED_MS + 120000))
        assert response.json() == {"upserted": 1, "totalReceived": 2}
        rows = hr_rows("sessions")
        assert len(rows) == 1
        assert rows[0]["ended_at_ms"] == STARTED_MS + 120000

    def test_a_session_row_may_arrive_after_the_rows_referencing_it(self, client, hr_rows):
        """No foreign keys: batches are order-independent, so samples naming a
        session the server has never seen must still be storable."""
        samples = {"clientId": CLIENT_ID, "samples": [{
            "deviceId": DEVICE_ID, "timestampMs": 1770000000000, "seq": 0,
            "heartRateBpm": 142, "rrIntervalMs": 423, "sessionId": SESSION_ID}]}
        assert client.post("/api/hr/samples/batch", json=samples).status_code == 200
        assert _post(client, _session()).status_code == 200
        assert len(hr_rows("sessions")) == 1


@pytest.mark.integration
class TestSessionsBatchValidation:
    @pytest.mark.parametrize("poison, reason", [
        ({"workoutDate": "2030-1-3"}, "workoutDate must be zero-padded"),
        ({"startedAtMs": "recently"}, "wrong type"),
        ({"workoutSessionId": "forty-two"}, "workoutSessionId is numeric"),
    ])
    def test_poison_row_rejects_the_whole_batch(self, client, hr_rows, poison, reason):
        bad = _session("fixture-session-poison", **poison)
        response = _post(client, _session(), bad)
        assert response.status_code == 422, reason
        assert hr_rows("sessions") == [], reason

    def test_missing_started_at_is_rejected(self, client, hr_rows):
        incomplete = _session()
        del incomplete["startedAtMs"]
        assert _post(client, incomplete).status_code == 422
        assert hr_rows("sessions") == []

    def test_unknown_field_is_ignored_not_rejected(self, client, hr_rows):
        """Forward-compat: a client running ahead of the server must not get
        its batch 422'd over a field this server doesn't know yet."""
        assert _post(client, _session(futureField="ignored")).status_code == 200
        assert len(hr_rows("sessions")) == 1
