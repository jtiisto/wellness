"""Integration tests for POST /api/hr/samples/batch.

All values are synthetic: invented MAC/UUID strings and round epoch-ms stamps
reused from the protocol spec's fixtures, never copied from a real capture.
"""
import pytest

CLIENT_ID = "fixture-client-hr-0001"
DEVICE_ID = "AA:BB:CC:DD:EE:FF"
SESSION_ID = "11111111-2222-3333-4444-555555555555"
BASE_MS = 1770000000000


def _sample(**overrides):
    """A minimal valid sample — required wire fields only, optionals omitted."""
    return {
        "deviceId": DEVICE_ID,
        "timestampMs": BASE_MS,
        "seq": 0,
        "heartRateBpm": 142,
        "rrIntervalMs": 423,
        "sessionId": SESSION_ID,
        **overrides,
    }


def _batch(*samples):
    return {"clientId": CLIENT_ID, "samples": list(samples)}


def _post(client, *samples):
    return client.post("/api/hr/samples/batch", json=_batch(*samples))


@pytest.mark.integration
class TestSamplesBatchHappyPath:
    def test_reports_counts_in_the_documented_shape(self, client):
        response = _post(client, _sample(), _sample(seq=1))
        assert response.status_code == 200
        assert response.json() == {"accepted": 2, "duplicates": 0, "totalReceived": 2}

    def test_stores_every_wire_field_in_its_snake_case_column(self, client, hr_rows):
        _post(client, _sample(timestampMs=BASE_MS + 400, heartRateBpm=138,
                              rrIntervalMs=431, isGapBefore=True,
                              sensorType="fixture_strap"))
        row = hr_rows("intervals")[0]
        assert (row["device_id"], row["timestamp_ms"], row["seq"]) == (
            DEVICE_ID, BASE_MS + 400, 0)
        assert (row["heart_rate_bpm"], row["rr_interval_ms"]) == (138, 431)
        assert (row["is_gap_before"], row["sensor_type"]) == (1, "fixture_strap")
        assert row["session_id"] == SESSION_ID

    def test_omitted_optionals_take_their_documented_defaults(self, client, hr_rows):
        """Optionals are omitted (never null) on the wire, so the defaults are
        the only thing standing between an omitted field and a NULL column."""
        _post(client, _sample())
        row = hr_rows("intervals")[0]
        assert row["is_gap_before"] == 0
        assert row["sensor_type"] == "garmin_hrm"

    def test_gap_flag_is_coerced_to_an_integer(self, client, hr_rows):
        """SQLite has no bool: a JSON `true` must land as 1, not as the string
        'True' that a naive str() binding would store."""
        _post(client, _sample(isGapBefore=True))
        assert hr_rows("intervals")[0]["is_gap_before"] == 1

    def test_artifact_sentinel_is_data(self, client, hr_rows):
        """rrIntervalMs 0 is a legal artifact marker, not a validation failure."""
        assert _post(client, _sample(rrIntervalMs=0)).status_code == 200
        assert hr_rows("intervals")[0]["rr_interval_ms"] == 0

    @pytest.mark.parametrize("bpm", [0, 300])
    def test_no_physiological_range_policing(self, client, bpm):
        """Implausible heart rates are stored, not rejected — the analysis layer
        owns quality judgment, and a range check here would 422 whole batches."""
        assert _post(client, _sample(heartRateBpm=bpm)).status_code == 200

    def test_received_at_is_one_utc_instant_for_the_whole_batch(self, client, hr_rows):
        _post(client, _sample(), _sample(seq=1), _sample(seq=2))
        stamps = {row["received_at"] for row in hr_rows("intervals")}
        assert len(stamps) == 1
        stamp = stamps.pop()
        assert stamp.endswith("Z") and stamp[4] == "-" and "T" in stamp

    def test_empty_batch_is_an_accepted_no_op(self, client, hr_rows):
        response = _post(client)
        assert response.status_code == 200
        assert response.json() == {"accepted": 0, "duplicates": 0, "totalReceived": 0}
        assert hr_rows("intervals") == []


@pytest.mark.integration
class TestSamplesBatchIdempotency:
    def test_reposted_batch_is_all_duplicates(self, client, hr_rows):
        _post(client, _sample(), _sample(seq=1))
        response = _post(client, _sample(), _sample(seq=1))
        assert response.json() == {"accepted": 0, "duplicates": 2, "totalReceived": 2}
        assert len(hr_rows("intervals")) == 2

    def test_mixed_batch_counts_new_and_duplicate_rows(self, client, hr_rows):
        """The realistic retry: a flush that partly landed before the client
        lost the acknowledgement."""
        _post(client, _sample(), _sample(seq=1))
        response = _post(client, _sample(), _sample(seq=1), _sample(seq=2))
        body = response.json()
        assert body == {"accepted": 1, "duplicates": 2, "totalReceived": 3}
        assert body["accepted"] + body["duplicates"] == body["totalReceived"]
        assert len(hr_rows("intervals")) == 3

    def test_duplicate_inside_one_batch_counts_once(self, client, hr_rows):
        response = _post(client, _sample(), _sample())
        assert response.json() == {"accepted": 1, "duplicates": 1, "totalReceived": 2}
        assert len(hr_rows("intervals")) == 1

    def test_seq_disambiguates_beats_sharing_a_millisecond(self, client, hr_rows):
        """Two beats at the same receipt ms are distinct rows, not a duplicate —
        this is why seq is in the PK."""
        response = _post(client, _sample(seq=0), _sample(seq=1))
        assert response.json()["accepted"] == 2
        assert [r["seq"] for r in hr_rows("intervals", order_by="seq")] == [0, 1]

    def test_a_re_upload_restamps_nothing(self, client, hr_rows):
        """OR IGNORE means the stored row wins: the second upload's server stamp
        must not overwrite the first one's."""
        _post(client, _sample())
        first_stamp = hr_rows("intervals")[0]["received_at"]
        _post(client, _sample(heartRateBpm=99))
        row = hr_rows("intervals")[0]
        assert row["received_at"] == first_stamp
        assert row["heart_rate_bpm"] == 142


@pytest.mark.integration
class TestSamplesBatchValidation:
    """One bad row 422s the whole request — the client's cue to bisect. Nothing
    from a rejected request may reach the table."""

    @pytest.mark.parametrize("poison, reason", [
        ({"seq": -1}, "seq must be non-negative"),
        ({"rrIntervalMs": -5}, "rrIntervalMs must be non-negative"),
        ({"heartRateBpm": "fast"}, "wrong type"),
        ({"deviceId": None}, "required field nulled"),
    ])
    def test_poison_row_rejects_the_whole_batch(self, client, hr_rows, poison, reason):
        bad = _sample(**{"seq": 1, **poison})
        response = _post(client, _sample(), bad, _sample(seq=2))
        assert response.status_code == 422, reason
        assert hr_rows("intervals") == [], reason

    def test_missing_required_field_is_rejected(self, client, hr_rows):
        incomplete = _sample()
        del incomplete["sessionId"]
        assert _post(client, incomplete).status_code == 422
        assert hr_rows("intervals") == []

    def test_missing_envelope_key_is_rejected(self, client):
        assert client.post("/api/hr/samples/batch",
                           json={"clientId": CLIENT_ID}).status_code == 422

    def test_unknown_field_is_ignored_not_rejected(self, client, hr_rows):
        """Forward compatibility: a client running ahead of the server must not
        have its whole batch 422'd (and then bisected row by row, fruitlessly)
        over a field this server does not know yet."""
        response = _post(client, _sample(futureField="v2"))
        assert response.status_code == 200
        assert len(hr_rows("intervals")) == 1
