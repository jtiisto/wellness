"""Integration tests for GET /api/journal/sync/delta endpoint.

Delta sync also serves as the full-sync entry point when `since` is omitted
(initial pull / post-reinstall). Entries are filtered to only those whose
tracker is active (`deleted=0`); the full history remains on the server for
MCP queries.
"""
import pytest
import time
from datetime import datetime, timedelta, timezone

from modules.db import get_db, get_utc_now


def _upload(client, client_id, *, config=None, days=None):
    payload = {
        "clientId": client_id,
        "config": config or [],
        "days": days or {},
    }
    response = client.post("/api/journal/sync/update", json=payload)
    assert response.status_code == 200, response.text
    return response.json()


@pytest.mark.integration
class TestSyncDeltaWithSince:
    def test_returns_changes_since_timestamp(self, client, journal_seeded_database):
        past = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat().replace("+00:00", "Z")
        response = client.get(f"/api/journal/sync/delta?since={past}")
        assert response.status_code == 200
        data = response.json()
        assert "config" in data
        assert "days" in data
        assert "deletedTrackers" in data
        assert "serverTime" in data

    def test_response_structure(self, client, journal_seeded_database):
        past = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat().replace("+00:00", "Z")
        data = client.get(f"/api/journal/sync/delta?since={past}").json()
        assert isinstance(data["config"], list)
        assert isinstance(data["days"], dict)
        assert isinstance(data["deletedTrackers"], list)
        assert isinstance(data["serverTime"], str)

    def test_future_timestamp_returns_empty_changes(self, client, journal_seeded_database):
        future = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat().replace("+00:00", "Z")
        data = client.get(f"/api/journal/sync/delta?since={future}").json()
        assert data["config"] == []
        assert data["days"] == {}
        assert data["deletedTrackers"] == []

    def test_includes_deleted_tracker_ids(self, client, journal_registered_client, sample_tracker):
        data1 = _upload(client, journal_registered_client, config=[sample_tracker])
        stamp = data1["acceptedTrackers"][0]["lastModifiedAt"]
        past = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat().replace("+00:00", "Z")

        time.sleep(0.01)
        deleted = {**sample_tracker, "_deleted": True, "_baseLastModifiedAt": stamp}
        _upload(client, journal_registered_client, config=[deleted])

        data = client.get(f"/api/journal/sync/delta?since={past}").json()
        assert sample_tracker["id"] in data["deletedTrackers"]

    def test_only_returns_recent_entries(self, client, journal_registered_client, sample_tracker):
        old_date = (datetime.now() - timedelta(days=10)).strftime("%Y-%m-%d")
        today = datetime.now().strftime("%Y-%m-%d")
        past = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat().replace("+00:00", "Z")

        _upload(client, journal_registered_client, config=[sample_tracker])
        _upload(client, journal_registered_client, days={
            old_date: {sample_tracker["id"]: {"value": 1}},
            today: {sample_tracker["id"]: {"value": 2}},
        })

        days = client.get(f"/api/journal/sync/delta?since={past}").json()["days"]
        assert today in days
        assert old_date not in days

    def test_entries_include_last_modified_at(self, client, journal_seeded_database):
        past = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat().replace("+00:00", "Z")
        data = client.get(f"/api/journal/sync/delta?since={past}").json()
        for date_entries in data["days"].values():
            for entry in date_entries.values():
                assert "lastModifiedAt" in entry
                # Old version fields should NOT appear
                assert "_version" not in entry
                assert "_lastModifiedBy" not in entry

    def test_trackers_include_last_modified_at(self, client, journal_seeded_database):
        past = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat().replace("+00:00", "Z")
        data = client.get(f"/api/journal/sync/delta?since={past}").json()
        assert data["config"], "seed should have produced at least one tracker"
        tracker = data["config"][0]
        assert "lastModifiedAt" in tracker
        # Old version fields should NOT appear
        assert "_version" not in tracker
        assert "_lastModifiedBy" not in tracker


@pytest.mark.integration
class TestSyncDeltaFullPull:
    """`/sync/delta` with `since` omitted serves as the full-sync endpoint."""

    def test_empty_database_returns_empty_response(self, client):
        data = client.get("/api/journal/sync/delta").json()
        assert data["config"] == []
        assert data["days"] == {}
        assert "serverTime" in data

    def test_returns_all_active_trackers(self, client, journal_seeded_database):
        data = client.get("/api/journal/sync/delta").json()
        assert len(data["config"]) >= 1
        tracker_ids = [t["id"] for t in data["config"]]
        assert journal_seeded_database["tracker"]["id"] in tracker_ids

    def test_excludes_deleted_trackers_from_config(self, client, journal_registered_client, sample_tracker):
        data1 = _upload(client, journal_registered_client, config=[sample_tracker])
        stamp = data1["acceptedTrackers"][0]["lastModifiedAt"]

        time.sleep(0.01)
        _upload(client, journal_registered_client,
                config=[{**sample_tracker, "_deleted": True, "_baseLastModifiedAt": stamp}])

        data = client.get("/api/journal/sync/delta").json()
        tracker_ids = [t["id"] for t in data["config"]]
        assert sample_tracker["id"] not in tracker_ids
        assert sample_tracker["id"] in data["deletedTrackers"]

    def test_returns_entries_within_7_days(self, client, journal_seeded_database):
        data = client.get("/api/journal/sync/delta").json()
        seven_days_ago = (datetime.now() - timedelta(days=7)).strftime("%Y-%m-%d")
        for date_str in data["days"].keys():
            assert date_str >= seven_days_ago

    def test_metadata_fields_merged_into_tracker(self, client, journal_registered_client):
        tracker = {
            "id": "quantifiable-tracker",
            "name": "Water",
            "category": "health",
            "type": "quantifiable",
            "unit": "glasses",
            "goal": 8,
        }
        _upload(client, journal_registered_client, config=[tracker])

        data = client.get("/api/journal/sync/delta").json()
        saved = next(t for t in data["config"] if t["id"] == "quantifiable-tracker")
        assert saved["unit"] == "glasses"
        assert saved["goal"] == 8


@pytest.mark.integration
class TestSyncDeltaDeletedTrackerFiltering:
    """Entries belonging to deleted trackers should not appear in sync.

    The full entry history remains on the server (visible to MCP) but the
    client never sees entries for trackers it can't display.
    """

    def test_entries_for_deleted_tracker_excluded_from_delta(
        self, client, journal_registered_client, sample_tracker
    ):
        # Create tracker, add entries
        data1 = _upload(client, journal_registered_client, config=[sample_tracker])
        tracker_stamp = data1["acceptedTrackers"][0]["lastModifiedAt"]

        today = datetime.now().strftime("%Y-%m-%d")
        _upload(client, journal_registered_client,
                days={today: {sample_tracker["id"]: {"value": 5, "completed": False}}})

        # Delete the tracker
        time.sleep(0.01)
        _upload(client, journal_registered_client,
                config=[{**sample_tracker, "_deleted": True,
                         "_baseLastModifiedAt": tracker_stamp}])

        # Full pull should NOT return any entries for the deleted tracker
        data = client.get("/api/journal/sync/delta").json()
        assert sample_tracker["id"] in data["deletedTrackers"]
        # No date should carry an entry for this tracker
        for date_entries in data["days"].values():
            assert sample_tracker["id"] not in date_entries

    def test_entries_for_deleted_tracker_still_in_server_db(
        self, client, journal_registered_client, sample_tracker, tmp_journal_db
    ):
        """Entries persist server-side for MCP; only the sync delta filters them."""
        data1 = _upload(client, journal_registered_client, config=[sample_tracker])
        tracker_stamp = data1["acceptedTrackers"][0]["lastModifiedAt"]

        today = datetime.now().strftime("%Y-%m-%d")
        _upload(client, journal_registered_client,
                days={today: {sample_tracker["id"]: {"value": 7}}})

        time.sleep(0.01)
        _upload(client, journal_registered_client,
                config=[{**sample_tracker, "_deleted": True,
                         "_baseLastModifiedAt": tracker_stamp}])

        with get_db(tmp_journal_db) as conn:
            cursor = conn.cursor()
            cursor.execute(
                "SELECT value FROM entries WHERE date = ? AND tracker_id = ?",
                (today, sample_tracker["id"]),
            )
            row = cursor.fetchone()
            assert row is not None, "entry should remain in server DB after tracker delete"
            assert row["value"] == 7


@pytest.mark.integration
class TestSyncDeltaTrackerLifecycle:
    """Same-name new tracker after deletion: distinct UUIDs keep histories
    structurally separate even though MCP can see both."""

    def test_recreating_same_name_tracker_has_distinct_id_and_history(
        self, client, journal_registered_client
    ):
        old = {"id": "tracker-old-uuid", "name": "B12",
               "category": "supplements", "type": "simple"}
        new = {"id": "tracker-new-uuid", "name": "B12",
               "category": "supplements", "type": "simple"}

        data1 = _upload(client, journal_registered_client, config=[old])
        stamp = data1["acceptedTrackers"][0]["lastModifiedAt"]

        today = datetime.now().strftime("%Y-%m-%d")
        _upload(client, journal_registered_client,
                days={today: {"tracker-old-uuid": {"value": 1}}})

        # Delete the old "B12"
        time.sleep(0.01)
        _upload(client, journal_registered_client,
                config=[{**old, "_deleted": True, "_baseLastModifiedAt": stamp}])

        # Create a new "B12" with a different UUID
        time.sleep(0.01)
        _upload(client, journal_registered_client, config=[new])
        _upload(client, journal_registered_client,
                days={today: {"tracker-new-uuid": {"value": 2}}})

        # Delta returns only the new active tracker
        data = client.get("/api/journal/sync/delta").json()
        active_ids = [t["id"] for t in data["config"]]
        assert "tracker-new-uuid" in active_ids
        assert "tracker-old-uuid" not in active_ids
        # New tracker's entry is present
        assert data["days"][today]["tracker-new-uuid"]["value"] == 2
        # Old tracker's entry is NOT in the delta (filtered by t.deleted=0)
        assert "tracker-old-uuid" not in data["days"][today]


@pytest.mark.integration
class TestDeltaWatermarkRace:
    """The delta watermark (serverTime) must be a lower bound on what the
    pull's snapshot reflects, so a write that races the pull is delivered on the
    next pull instead of being skipped forever by the `> since` filter."""

    @staticmethod
    def _parse(ts):
        return datetime.fromisoformat(ts.replace("Z", "+00:00"))

    def test_serverTime_is_a_past_watermark(self, client, journal_seeded_database):
        """serverTime is stamped before the reads and offset into the past by
        the overlap, so it is strictly before the moment the request was issued.
        (The old code stamped it AFTER the reads, so serverTime was later than
        `before` and this failed.)"""
        before = datetime.now(timezone.utc)
        data = client.get("/api/journal/sync/delta").json()
        assert self._parse(data["serverTime"]) < before

    def test_write_stamped_just_before_pull_is_not_skipped(
        self, client, journal_seeded_database, tmp_journal_db
    ):
        """A write whose last_modified_at falls just before a pull's snapshot
        (it committed during the pull, so the pull missed it) must be delivered
        by the next pull. Models the delta-token boundary race directly."""
        # t0: when an in-flight write stamps its last_modified_at, just before
        # we issue the pull whose snapshot misses it.
        t0 = get_utc_now()
        server_time = client.get("/api/journal/sync/delta").json()["serverTime"]
        # The watermark must land before t0, so `since=server_time` re-delivers
        # the raced write. (Old after-the-reads stamp put server_time >= t0.)
        assert server_time < t0

        # Inject the raced tracker straight into the DB at last_modified_at=t0.
        with get_db(tmp_journal_db) as conn:
            conn.execute(
                "INSERT INTO trackers (id, name, category, type, last_modified_at, deleted) "
                "VALUES ('inflight-1', 'Inflight', 'health', 'simple', ?, 0)",
                (t0,),
            )
            conn.commit()

        data = client.get(f"/api/journal/sync/delta?since={server_time}").json()
        assert "inflight-1" in [t["id"] for t in data["config"]]
