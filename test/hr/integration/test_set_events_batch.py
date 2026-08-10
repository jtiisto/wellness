"""Integration tests for POST /api/hr/set-events/batch.

All values are synthetic; calendar dates are deliberately far-future (2030+) per
the protocol spec, so no literal here can collide with a real logged day.
"""
import pytest

CLIENT_ID = "fixture-client-hr-0001"
SESSION_ID = "11111111-2222-3333-4444-555555555555"
DATE = "2030-01-03"
BASE_MS = 1770000010000


def _event(event_id="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", **overrides):
    """A set tick — the common case. Optionals are omitted unless overridden."""
    return {
        "eventId": event_id,
        "date": DATE,
        "exerciseKey": "fixture-adhoc-lift",
        "setNum": 1,
        "action": "check",
        "clientTimestampMs": BASE_MS,
        **overrides,
    }


def _post(client, *events):
    return client.post("/api/hr/set-events/batch",
                       json={"clientId": CLIENT_ID, "events": list(events)})


@pytest.mark.integration
class TestSetEventsBatchHappyPath:
    def test_reports_counts_in_the_documented_shape(self, client):
        response = _post(client, _event(), _event("event-fixture-2", setNum=2))
        assert response.status_code == 200
        assert response.json() == {"accepted": 2, "duplicates": 0, "totalReceived": 2}

    def test_stores_a_set_tick(self, client, hr_rows):
        _post(client, _event(setNum=3, sessionId=SESSION_ID))
        row = hr_rows("set_events")[0]
        assert row["event_id"] == "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        assert (row["date"], row["exercise_key"]) == (DATE, "fixture-adhoc-lift")
        assert (row["set_num"], row["item_key"]) == (3, None)
        assert (row["action"], row["client_timestamp_ms"]) == ("check", BASE_MS)
        assert row["session_id"] == SESSION_ID

    def test_stores_a_checklist_toggle_with_no_set_number(self, client, hr_rows):
        event = _event("event-fixture-checklist", itemKey="fixture-item-a")
        del event["setNum"]
        _post(client, event)
        row = hr_rows("set_events")[0]
        assert (row["set_num"], row["item_key"]) == (None, "fixture-item-a")

    def test_stores_an_exercise_level_toggle_with_neither(self, client, hr_rows):
        """Neither setNum nor itemKey means the whole exercise was toggled
        (cardio completed) — both columns stay NULL."""
        event = _event("event-fixture-cardio", exerciseKey="extra_zone2")
        del event["setNum"]
        _post(client, event)
        row = hr_rows("set_events")[0]
        assert (row["set_num"], row["item_key"], row["session_id"]) == (None, None, None)

    def test_uncheck_is_stored_as_data(self, client, hr_rows):
        """Undo is a row, not a delete: both toggles survive so correlation can
        fold them in client-timestamp order."""
        _post(client, _event(),
              _event("event-fixture-undo", action="uncheck",
                     clientTimestampMs=BASE_MS + 5000))
        actions = [r["action"] for r in
                   hr_rows("set_events", order_by="client_timestamp_ms")]
        assert actions == ["check", "uncheck"]

    def test_received_at_is_stamped(self, client, hr_rows):
        _post(client, _event())
        assert hr_rows("set_events")[0]["received_at"].endswith("Z")

    def test_empty_batch_is_an_accepted_no_op(self, client, hr_rows):
        assert _post(client).json() == {
            "accepted": 0, "duplicates": 0, "totalReceived": 0}
        assert hr_rows("set_events") == []


@pytest.mark.integration
class TestSetEventsBatchIdempotency:
    def test_reposted_batch_is_all_duplicates(self, client, hr_rows):
        _post(client, _event(), _event("event-fixture-2"))
        response = _post(client, _event(), _event("event-fixture-2"))
        assert response.json() == {"accepted": 0, "duplicates": 2, "totalReceived": 2}
        assert len(hr_rows("set_events")) == 2

    def test_mixed_batch_counts_new_and_duplicate_rows(self, client, hr_rows):
        _post(client, _event())
        response = _post(client, _event(), _event("event-fixture-2"))
        body = response.json()
        assert body == {"accepted": 1, "duplicates": 1, "totalReceived": 2}
        assert body["accepted"] + body["duplicates"] == body["totalReceived"]
        assert len(hr_rows("set_events")) == 2

    def test_same_event_id_never_updates_the_stored_row(self, client, hr_rows):
        """eventId is the idempotency key, so a re-upload carrying different
        content is ignored outright rather than merged — and the stored row
        keeps its original server stamp (no restamp on ignore)."""
        _post(client, _event())
        first_stamp = hr_rows("set_events")[0]["received_at"]
        _post(client, _event(action="uncheck", setNum=9))
        rows = hr_rows("set_events")
        assert len(rows) == 1
        assert (rows[0]["action"], rows[0]["set_num"]) == ("check", 1)
        assert rows[0]["received_at"] == first_stamp

    def test_unknown_field_is_ignored_not_rejected(self, client, hr_rows):
        """Forward-compat: a client running ahead of the server must not get
        its batch 422'd over a field this server doesn't know yet."""
        event = _event(futureField="ignored")
        assert _post(client, event).status_code == 200
        assert len(hr_rows("set_events")) == 1


@pytest.mark.integration
class TestSetEventsBatchValidation:
    @pytest.mark.parametrize("poison, reason", [
        ({"action": "toggle"}, "action is a two-value enum"),
        ({"date": "2030-1-3"}, "date must be zero-padded YYYY-MM-DD"),
        ({"date": "01/03/2030"}, "date must not be a locale format"),
        ({"setNum": 0}, "setNum is 1-based"),
        ({"setNum": -1}, "setNum must be positive"),
        ({"clientTimestampMs": "soon"}, "wrong type"),
    ])
    def test_poison_row_rejects_the_whole_batch(self, client, hr_rows, poison, reason):
        bad = _event("event-fixture-poison", **poison)
        response = _post(client, _event(), bad)
        assert response.status_code == 422, reason
        assert hr_rows("set_events") == [], reason

    def test_missing_event_id_is_rejected(self, client, hr_rows):
        incomplete = _event()
        del incomplete["eventId"]
        assert _post(client, incomplete).status_code == 422
        assert hr_rows("set_events") == []
