"""Integration tests for POST /api/hr/guide-events/batch.

All values are synthetic; calendar dates are deliberately far-future (2030+) per
the protocol spec, so no literal here can collide with a real logged day.
"""
import pytest

CLIENT_ID = "fixture-client-hr-0001"
SESSION_ID = "11111111-2222-3333-4444-555555555555"
DATE = "2030-01-03"
BASE_MS = 1770000010000
TIMELINE = ('[{"duration_sec":420,"hr_min":118,"hr_max":134,"label":"warmup"},'
            '{"duration_sec":900,"hr_min":126,"hr_max":140}]')


def _start(event_id="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", **overrides):
    """The anchor event — the common case. Optionals omitted unless overridden."""
    return {
        "eventId": event_id,
        "date": DATE,
        "exerciseKey": "ex_ride",
        "action": "start",
        "clientTimestampMs": BASE_MS,
        "sessionId": SESSION_ID,
        "timelineJson": TIMELINE,
        **overrides,
    }


def _extend(event_id="bbbbbbbb-cccc-dddd-eeee-ffffffffffff", **overrides):
    return {
        "eventId": event_id,
        "date": DATE,
        "exerciseKey": "ex_ride",
        "action": "extend",
        "clientTimestampMs": BASE_MS + 900_000,
        "sessionId": SESSION_ID,
        "extensionSec": 300,
        **overrides,
    }


def _post(client, *events):
    return client.post("/api/hr/guide-events/batch",
                       json={"clientId": CLIENT_ID, "events": list(events)})


@pytest.mark.integration
class TestGuideEventsBatchHappyPath:
    def test_reports_counts_in_the_documented_shape(self, client):
        response = _post(client, _start(), _extend())
        assert response.status_code == 200
        assert response.json() == {"accepted": 2, "duplicates": 0, "totalReceived": 2}

    def test_stores_the_anchor_with_its_timeline(self, client, hr_rows):
        _post(client, _start())
        row = hr_rows("guide_events")[0]
        assert row["event_id"] == "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        assert (row["date"], row["exercise_key"]) == (DATE, "ex_ride")
        assert (row["action"], row["client_timestamp_ms"]) == ("start", BASE_MS)
        assert row["session_id"] == SESSION_ID
        # The timeline is stored verbatim: hr.db keeps the segments it was
        # guided against so nothing on the analysis side has to read coach.db.
        assert row["timeline_json"] == TIMELINE
        assert row["extension_sec"] is None

    def test_stores_an_extension(self, client, hr_rows):
        _post(client, _extend())
        row = hr_rows("guide_events")[0]
        assert (row["action"], row["extension_sec"]) == ("extend", 300)
        assert row["timeline_json"] is None

    def test_a_second_run_appends_rather_than_replacing(self, client, hr_rows):
        """The guide may be re-anchored mid-session; every anchor is kept and
        which one wins is the analysis side's policy, not the server's."""
        _post(client, _start(),
              _start("guide-fixture-restart", clientTimestampMs=BASE_MS + 1_800_000))
        rows = hr_rows("guide_events", order_by="client_timestamp_ms")
        assert [r["action"] for r in rows] == ["start", "start"]

    def test_optional_fields_may_be_omitted_entirely(self, client, hr_rows):
        """Omitted-never-null: a start with no timeline and an extend with no
        step are both storable — the pairing is a client convention, and the
        server refuses to be stricter than the spec."""
        bare = _start("guide-fixture-bare")
        del bare["timelineJson"]
        assert _post(client, bare).status_code == 200
        row = hr_rows("guide_events")[0]
        assert (row["timeline_json"], row["extension_sec"]) == (None, None)

    def test_received_at_is_stamped(self, client, hr_rows):
        _post(client, _start())
        assert hr_rows("guide_events")[0]["received_at"].endswith("Z")

    def test_empty_batch_is_an_accepted_no_op(self, client, hr_rows):
        assert _post(client).json() == {
            "accepted": 0, "duplicates": 0, "totalReceived": 0}
        assert hr_rows("guide_events") == []

    def test_guide_events_are_their_own_table(self, client, hr_rows):
        """The two logs are siblings, not one table with a wider enum: a guide
        action must never land where a set tick is counted."""
        _post(client, _start())
        assert hr_rows("set_events") == []


@pytest.mark.integration
class TestGuideEventsBatchIdempotency:
    def test_reposted_batch_is_all_duplicates(self, client, hr_rows):
        _post(client, _start(), _extend())
        response = _post(client, _start(), _extend())
        assert response.json() == {"accepted": 0, "duplicates": 2, "totalReceived": 2}
        assert len(hr_rows("guide_events")) == 2

    def test_mixed_batch_counts_new_and_duplicate_rows(self, client, hr_rows):
        _post(client, _start())
        response = _post(client, _start(), _extend())
        body = response.json()
        assert body == {"accepted": 1, "duplicates": 1, "totalReceived": 2}
        assert body["accepted"] + body["duplicates"] == body["totalReceived"]
        assert len(hr_rows("guide_events")) == 2

    def test_same_event_id_never_updates_the_stored_row(self, client, hr_rows):
        """eventId is the idempotency key, so a re-upload carrying different
        content is ignored outright rather than merged — and the stored row
        keeps its original server stamp (no restamp on ignore)."""
        _post(client, _start())
        first_stamp = hr_rows("guide_events")[0]["received_at"]
        _post(client, _start(action="extend", extensionSec=300))
        rows = hr_rows("guide_events")
        assert len(rows) == 1
        assert (rows[0]["action"], rows[0]["extension_sec"]) == ("start", None)
        assert rows[0]["received_at"] == first_stamp

    def test_unknown_field_is_ignored_not_rejected(self, client, hr_rows):
        """Forward-compat: a client running ahead of the server must not get
        its batch 422'd over a field this server doesn't know yet."""
        assert _post(client, _start(futureField="ignored")).status_code == 200
        assert len(hr_rows("guide_events")) == 1


@pytest.mark.integration
class TestGuideEventsBatchValidation:
    @pytest.mark.parametrize("poison, reason", [
        ({"action": "stop"}, "action is a two-value enum, and stop is not one"),
        ({"action": "check"}, "the set-event verbs are not guide verbs"),
        ({"date": "2030-1-3"}, "date must be zero-padded YYYY-MM-DD"),
        ({"date": "01/03/2030"}, "date must not be a locale format"),
        ({"clientTimestampMs": "soon"}, "wrong type"),
        ({"extensionSec": 0}, "an extension of nothing is not an extension"),
        ({"extensionSec": -300}, "extensionSec must be positive"),
        ({"extensionSec": "five"}, "wrong type"),
        ({"timelineJson": {"segments": []}}, "timelineJson is an opaque string"),
    ])
    def test_poison_row_rejects_the_whole_batch(self, client, hr_rows, poison, reason):
        bad = _start("guide-fixture-poison", **poison)
        response = _post(client, _extend(), bad)
        assert response.status_code == 422, reason
        assert hr_rows("guide_events") == [], reason

    @pytest.mark.parametrize("missing", ["eventId", "sessionId", "exerciseKey", "action"])
    def test_a_missing_required_field_is_rejected(self, client, hr_rows, missing):
        """`sessionId` is required here and optional on set_events, deliberately:
        its presence is what licenses the recording at all."""
        incomplete = _start()
        del incomplete[missing]
        assert _post(client, incomplete).status_code == 422
        assert hr_rows("guide_events") == []
