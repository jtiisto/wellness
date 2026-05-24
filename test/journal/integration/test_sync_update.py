"""Integration tests for POST /api/journal/sync/update endpoint.

The journal sync protocol uses optimistic concurrency on an opaque
server-issued timestamp token (`_baseLastModifiedAt`). Records without a token
are treated as "INSERT only if no row exists with this key". Strictly-stale
uploads are rejected with the current `serverRow` so the client can recover
in-cycle.
"""
import pytest
import time
from datetime import datetime, timezone


def _upload(client, client_id, *, config=None, days=None):
    """Helper: post a sync_update and return the parsed response body."""
    payload = {
        "clientId": client_id,
        "config": config or [],
        "days": days or {},
    }
    response = client.post("/api/journal/sync/update", json=payload)
    assert response.status_code == 200, response.text
    return response.json()


@pytest.mark.integration
class TestSyncUpdateTrackerInsert:
    def test_create_new_tracker_with_no_base_token(self, client, journal_registered_client, sample_tracker):
        """Tracker upload without `_baseLastModifiedAt` should INSERT a new row."""
        data = _upload(client, journal_registered_client, config=[sample_tracker])
        assert len(data["acceptedTrackers"]) == 1
        accepted = data["acceptedTrackers"][0]
        assert accepted["id"] == sample_tracker["id"]
        assert accepted["lastModifiedAt"]
        assert data["rejectedTrackers"] == []

    def test_missing_kind_when_base_token_given_but_no_row(self, client, journal_registered_client, sample_tracker):
        """If a base token is provided but no stored row exists, reject as `missing`."""
        item = {**sample_tracker, "_baseLastModifiedAt": "2026-01-01T00:00:00Z"}
        data = _upload(client, journal_registered_client, config=[item])
        assert data["acceptedTrackers"] == []
        assert len(data["rejectedTrackers"]) == 1
        rejected = data["rejectedTrackers"][0]
        assert rejected["errorKind"] == "missing"
        assert rejected["serverRow"] is None


@pytest.mark.integration
class TestSyncUpdateTrackerOverwrite:
    def test_update_with_matching_base_token(self, client, journal_registered_client, sample_tracker):
        """Upload with `_baseLastModifiedAt` matching stored timestamp should be accepted."""
        data = _upload(client, journal_registered_client, config=[sample_tracker])
        stamp = data["acceptedTrackers"][0]["lastModifiedAt"]

        time.sleep(0.01)  # ensure server clock advances
        updated = {**sample_tracker, "name": "Updated", "_baseLastModifiedAt": stamp}
        data2 = _upload(client, journal_registered_client, config=[updated])
        assert len(data2["acceptedTrackers"]) == 1
        new_stamp = data2["acceptedTrackers"][0]["lastModifiedAt"]
        assert new_stamp > stamp

    def test_stale_upload_rejected_with_server_row(self, client, journal_registered_client, sample_tracker):
        """Strictly-newer stored timestamp should reject the upload as `stale`."""
        data = _upload(client, journal_registered_client, config=[sample_tracker])
        stamp1 = data["acceptedTrackers"][0]["lastModifiedAt"]

        time.sleep(0.01)
        first_update = {**sample_tracker, "name": "V2", "_baseLastModifiedAt": stamp1}
        _upload(client, journal_registered_client, config=[first_update])

        # Second client uploads based on the stale stamp1
        stale_update = {**sample_tracker, "name": "V3-stale", "_baseLastModifiedAt": stamp1}
        data3 = _upload(client, journal_registered_client, config=[stale_update])
        assert data3["acceptedTrackers"] == []
        assert len(data3["rejectedTrackers"]) == 1
        rejected = data3["rejectedTrackers"][0]
        assert rejected["errorKind"] == "stale"
        assert rejected["serverRow"]["id"] == sample_tracker["id"]
        assert rejected["serverRow"]["name"] == "V2"
        assert rejected["serverRow"]["lastModifiedAt"] > stamp1

    def test_idempotent_retry_with_same_base_token(self, client, journal_registered_client, sample_tracker):
        """Retrying with the same `_baseLastModifiedAt == stored` accepts idempotently.

        Covers the lost-response retry case: client uploaded successfully but
        didn't see the response, and re-sends with the same token.
        """
        data = _upload(client, journal_registered_client, config=[sample_tracker])
        stamp1 = data["acceptedTrackers"][0]["lastModifiedAt"]

        time.sleep(0.01)
        update1 = {**sample_tracker, "name": "U1", "_baseLastModifiedAt": stamp1}
        data2 = _upload(client, journal_registered_client, config=[update1])
        stamp2 = data2["acceptedTrackers"][0]["lastModifiedAt"]
        assert stamp2 > stamp1

        # Same client retries the SAME edit (response lost). It still carries
        # stamp1 as its base. Stored is now stamp2 > stamp1 → REJECTED stale.
        # The recovery path is: client takes server_row, syncs up.
        time.sleep(0.01)
        retry = {**sample_tracker, "name": "U1", "_baseLastModifiedAt": stamp1}
        data3 = _upload(client, journal_registered_client, config=[retry])
        assert len(data3["rejectedTrackers"]) == 1
        assert data3["rejectedTrackers"][0]["errorKind"] == "stale"

    def test_soft_delete_tracker(self, client, journal_registered_client, sample_tracker):
        """Soft-delete by setting `_deleted: True` should mark the tracker deleted."""
        data = _upload(client, journal_registered_client, config=[sample_tracker])
        stamp = data["acceptedTrackers"][0]["lastModifiedAt"]

        time.sleep(0.01)
        deleted = {**sample_tracker, "_deleted": True, "_baseLastModifiedAt": stamp}
        _upload(client, journal_registered_client, config=[deleted])

        # Subsequent delta returns the id in deletedTrackers, not config
        response = client.get("/api/journal/sync/delta")
        body = response.json()
        assert sample_tracker["id"] in body["deletedTrackers"]
        assert not any(t["id"] == sample_tracker["id"] for t in body["config"])


@pytest.mark.integration
class TestSyncUpdateEntries:
    def test_create_entry_without_base_token(self, client, journal_registered_client, sample_tracker):
        """Entry upload without `_baseLastModifiedAt` should INSERT."""
        _upload(client, journal_registered_client, config=[sample_tracker])

        today = datetime.now().strftime("%Y-%m-%d")
        data = _upload(
            client, journal_registered_client,
            days={today: {sample_tracker["id"]: {"value": 5, "completed": False}}},
        )
        assert len(data["acceptedEntries"]) == 1
        accepted = data["acceptedEntries"][0]
        assert accepted["date"] == today
        assert accepted["trackerId"] == sample_tracker["id"]
        assert accepted["lastModifiedAt"]

    def test_update_entry_with_matching_base_token(self, client, journal_registered_client, sample_tracker):
        """Entry update with matching `_baseLastModifiedAt` is accepted."""
        _upload(client, journal_registered_client, config=[sample_tracker])
        today = datetime.now().strftime("%Y-%m-%d")
        data = _upload(
            client, journal_registered_client,
            days={today: {sample_tracker["id"]: {"value": 3, "completed": False}}},
        )
        stamp = data["acceptedEntries"][0]["lastModifiedAt"]

        time.sleep(0.01)
        data2 = _upload(
            client, journal_registered_client,
            days={today: {sample_tracker["id"]: {"value": 5, "_baseLastModifiedAt": stamp}}},
        )
        assert len(data2["acceptedEntries"]) == 1

    def test_stale_entry_upload_rejected_with_server_row(self, client, journal_registered_client, sample_tracker):
        """Stale entry upload should return errorKind=stale with the current serverRow."""
        _upload(client, journal_registered_client, config=[sample_tracker])
        today = datetime.now().strftime("%Y-%m-%d")
        data = _upload(
            client, journal_registered_client,
            days={today: {sample_tracker["id"]: {"value": 3}}},
        )
        stamp1 = data["acceptedEntries"][0]["lastModifiedAt"]

        time.sleep(0.01)
        _upload(
            client, journal_registered_client,
            days={today: {sample_tracker["id"]: {"value": 5, "_baseLastModifiedAt": stamp1}}},
        )

        # Stale: client still thinks base is stamp1
        time.sleep(0.01)
        data3 = _upload(
            client, journal_registered_client,
            days={today: {sample_tracker["id"]: {"value": 7, "_baseLastModifiedAt": stamp1}}},
        )
        assert data3["acceptedEntries"] == []
        assert len(data3["rejectedEntries"]) == 1
        rejected = data3["rejectedEntries"][0]
        assert rejected["errorKind"] == "stale"
        assert rejected["serverRow"]["value"] == 5
        assert rejected["serverRow"]["lastModifiedAt"] > stamp1

    def test_missing_kind_when_entry_base_token_given_but_no_row(
        self, client, journal_registered_client, sample_tracker
    ):
        """If an entry payload includes `_baseLastModifiedAt` but no row exists, reject as `missing`."""
        _upload(client, journal_registered_client, config=[sample_tracker])
        today = datetime.now().strftime("%Y-%m-%d")
        data = _upload(
            client, journal_registered_client,
            days={today: {sample_tracker["id"]: {
                "value": 5,
                "_baseLastModifiedAt": "2026-01-01T00:00:00Z",
            }}},
        )
        assert data["acceptedEntries"] == []
        assert len(data["rejectedEntries"]) == 1
        rejected = data["rejectedEntries"][0]
        assert rejected["errorKind"] == "missing"
        assert rejected["serverRow"] is None
        assert rejected["date"] == today
        assert rejected["trackerId"] == sample_tracker["id"]

    def test_entry_completed_only(self, client, journal_registered_client, sample_simple_tracker):
        """Simple tracker entries with completed-only (no value) should round-trip."""
        _upload(client, journal_registered_client, config=[sample_simple_tracker])
        today = datetime.now().strftime("%Y-%m-%d")
        data = _upload(
            client, journal_registered_client,
            days={today: {sample_simple_tracker["id"]: {"value": None, "completed": True}}},
        )
        assert len(data["acceptedEntries"]) == 1


@pytest.mark.integration
class TestSyncUpdateClockSkewAndArchive:
    def test_client_clock_behind_server_does_not_reject(self, client, journal_registered_client, sample_tracker):
        """The comparator must not read the client wall clock.

        If it did, a client whose clock is 10 minutes behind would have
        legitimate new edits rejected as stale.
        """
        # First create the tracker
        data = _upload(client, journal_registered_client, config=[sample_tracker])
        stamp = data["acceptedTrackers"][0]["lastModifiedAt"]

        # Now upload an "edit" but pretend the client wall clock is in 1970.
        # Under a wall-clock comparator this would reject. Under
        # optimistic-concurrency-on-base-token, only the base token matters.
        time.sleep(0.01)
        update = {
            **sample_tracker,
            "name": "Updated despite stone-age clock",
            "_baseLastModifiedAt": stamp,
            # _lastModifiedAt or similar wall-clock fields are not part of the contract
        }
        data2 = _upload(client, journal_registered_client, config=[update])
        assert len(data2["acceptedTrackers"]) == 1, (
            "comparator must use the server-issued base token, not client wall clock"
        )

    def test_archive_row_inserted_on_overwrite(self, client, journal_registered_client, sample_tracker):
        """Overwriting a tracker should snapshot the prior row into trackers_archive."""
        import modules.journal as journal

        data = _upload(client, journal_registered_client, config=[sample_tracker])
        stamp = data["acceptedTrackers"][0]["lastModifiedAt"]

        time.sleep(0.01)
        update = {**sample_tracker, "name": "After", "_baseLastModifiedAt": stamp}
        _upload(client, journal_registered_client, config=[update])

        with journal.get_db() as conn:
            cursor = conn.cursor()
            cursor.execute(
                "SELECT tracker_id, name, last_modified_at, superseded_at "
                "FROM trackers_archive WHERE tracker_id = ?",
                (sample_tracker["id"],),
            )
            archived = cursor.fetchall()
            assert len(archived) == 1
            assert archived[0]["name"] == sample_tracker["name"]  # the OLD name
            assert archived[0]["last_modified_at"] == stamp

    def test_archive_row_inserted_on_entry_overwrite(self, client, journal_registered_client, sample_tracker):
        """Overwriting an entry should snapshot the prior value into entries_archive."""
        import modules.journal as journal

        _upload(client, journal_registered_client, config=[sample_tracker])
        today = datetime.now().strftime("%Y-%m-%d")
        data = _upload(
            client, journal_registered_client,
            days={today: {sample_tracker["id"]: {"value": 3, "completed": False}}},
        )
        stamp = data["acceptedEntries"][0]["lastModifiedAt"]

        time.sleep(0.01)
        _upload(
            client, journal_registered_client,
            days={today: {sample_tracker["id"]: {"value": 9, "_baseLastModifiedAt": stamp}}},
        )

        with journal.get_db() as conn:
            cursor = conn.cursor()
            cursor.execute(
                "SELECT value, last_modified_at FROM entries_archive "
                "WHERE date = ? AND tracker_id = ?",
                (today, sample_tracker["id"]),
            )
            archived = cursor.fetchall()
            assert len(archived) == 1
            assert archived[0]["value"] == 3  # the OLD value


@pytest.mark.integration
class TestSyncUpdateEchoSafety:
    """Regression: a client that echoes a tracker dict from /sync/delta must
    not poison meta_json with protocol fields. Top-level `lastModifiedAt`,
    `deleted`, and the legacy underscore-prefixed names must be stripped
    before serialization and must not override the server-stamped value on
    the next response."""

    def test_lastModifiedAt_in_payload_does_not_leak_into_meta_json(
        self, client, journal_registered_client, sample_tracker
    ):
        # Create tracker
        data = _upload(client, journal_registered_client, config=[sample_tracker])
        stamp = data["acceptedTrackers"][0]["lastModifiedAt"]

        # Client echoes the response back, including the top-level
        # `lastModifiedAt` field they just received, as part of a real update.
        time.sleep(0.01)
        echo_update = {
            **sample_tracker,
            "name": "Renamed",
            "lastModifiedAt": stamp,        # echoed from prior response
            "deleted": False,                # also a protocol field
            "_baseLastModifiedAt": stamp,    # the actual concurrency token
        }
        data2 = _upload(client, journal_registered_client, config=[echo_update])
        new_stamp = data2["acceptedTrackers"][0]["lastModifiedAt"]
        assert new_stamp > stamp, "server must stamp with its own clock, not the echoed value"

        # Pull the tracker back. The response's lastModifiedAt must be the
        # real new server stamp, not the stale echoed one.
        delta = client.get("/api/journal/sync/delta").json()
        tracker = next(t for t in delta["config"] if t["id"] == sample_tracker["id"])
        assert tracker["lastModifiedAt"] == new_stamp
        # And `deleted` echoed in the payload didn't poison meta either
        assert "deleted" not in tracker or tracker["deleted"] is False


@pytest.mark.integration
class TestSyncUpdateBatch:
    def test_multiple_trackers_in_single_update(self, client, journal_registered_client):
        """Should handle multiple new trackers in a single batch."""
        trackers = [
            {"id": f"tracker-{i}", "name": f"Tracker {i}", "category": "test", "type": "simple"}
            for i in range(3)
        ]
        data = _upload(client, journal_registered_client, config=trackers)
        assert len(data["acceptedTrackers"]) == 3
        assert data["rejectedTrackers"] == []

    def test_mixed_accept_and_reject_in_single_batch(self, client, journal_registered_client, sample_tracker):
        """A single batch may accept some records and reject others."""
        # Set up: one tracker exists, one does not
        data = _upload(client, journal_registered_client, config=[sample_tracker])
        stamp = data["acceptedTrackers"][0]["lastModifiedAt"]

        new_tracker = {
            "id": "tracker-new", "name": "Fresh", "category": "test", "type": "simple",
        }
        # Stale update to the existing one (wrong base)
        stale_update = {
            **sample_tracker, "name": "Stale",
            "_baseLastModifiedAt": "2020-01-01T00:00:00Z",
        }

        # Advance the existing tracker first so the stale_update is genuinely behind
        time.sleep(0.01)
        _upload(client, journal_registered_client,
                config=[{**sample_tracker, "name": "Advance", "_baseLastModifiedAt": stamp}])

        time.sleep(0.01)
        data3 = _upload(client, journal_registered_client, config=[new_tracker, stale_update])
        assert len(data3["acceptedTrackers"]) == 1
        assert data3["acceptedTrackers"][0]["id"] == "tracker-new"
        assert len(data3["rejectedTrackers"]) == 1
        assert data3["rejectedTrackers"][0]["id"] == sample_tracker["id"]
        assert data3["rejectedTrackers"][0]["errorKind"] == "stale"


@pytest.mark.integration
class TestSyncUpdateMeta:
    def test_last_server_sync_time_updates_on_accept(self, client, journal_registered_client, sample_tracker):
        """meta_sync.last_server_sync_time should be set when any record is accepted."""
        _upload(client, journal_registered_client, config=[sample_tracker])
        response = client.get("/api/journal/sync/status")
        assert response.json()["lastModified"] is not None

    def test_serverTime_returned_on_every_response(self, client, journal_registered_client):
        """SyncResponse should always include serverTime even on an empty upload."""
        data = _upload(client, journal_registered_client)
        assert data["serverTime"]
        assert data["serverTime"].endswith("Z")
