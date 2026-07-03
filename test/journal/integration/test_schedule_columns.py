"""Integration tests for promoting scheduleHistory + polarity to canonical
`trackers.schedule_json` / `trackers.polarity` columns (dual-stored in
`meta_json` during the transition).

The sync wire shape is unchanged — the fields were already top-level — so these
assert the storage promotion (columns captured, archived, backfilled) and
wire-invariance (delta and reject serverRow still carry the fields top-level).
See docs/ARCHITECTURE.md "Tracker scheduling".
"""
import json
import time

import pytest

from modules.db import get_db

SCHEDULE = [{"effectiveFrom": "0000-01-01", "days": [1, 2, 3, 4, 5]}]


def _tracker(**over):
    base = {
        "id": "tracker-sched",
        "name": "Weekday Vitamin",
        "category": "supplements",
        "type": "simple",
        "scheduleHistory": SCHEDULE,
        "polarity": "positive",
    }
    base.update(over)
    return base


def _upload(client, client_id, config):
    resp = client.post("/api/journal/sync/update", json={
        "clientId": client_id, "config": config, "days": {},
    })
    assert resp.status_code == 200, resp.text
    return resp.json()


@pytest.mark.integration
class TestSchedulePolarityColumns:
    def test_upload_captures_columns_and_omits_them_from_meta(
            self, client, journal_registered_client, tmp_journal_db):
        _upload(client, journal_registered_client, [_tracker()])
        with get_db(tmp_journal_db) as conn:
            row = conn.execute(
                "SELECT schedule_json, polarity, meta_json FROM trackers WHERE id = ?",
                ("tracker-sched",),
            ).fetchone()
        assert json.loads(row["schedule_json"]) == SCHEDULE
        assert row["polarity"] == "positive"
        # Single source of truth: the fields are reserved keys stored only in the
        # canonical columns — NOT copied into meta_json.
        meta = json.loads(row["meta_json"])
        assert "scheduleHistory" not in meta
        assert "polarity" not in meta

    def test_absent_schedule_and_polarity_store_null(
            self, client, journal_registered_client, tmp_journal_db):
        _upload(client, journal_registered_client, [{
            "id": "tracker-plain", "name": "Plain", "category": "misc",
            "type": "simple",
        }])
        with get_db(tmp_journal_db) as conn:
            row = conn.execute(
                "SELECT schedule_json, polarity FROM trackers WHERE id = ?",
                ("tracker-plain",),
            ).fetchone()
        assert row["schedule_json"] is None
        assert row["polarity"] is None

    def test_delta_emits_schedule_and_polarity_toplevel(
            self, client, journal_registered_client):
        _upload(client, journal_registered_client, [_tracker()])
        data = client.get("/api/journal/sync/delta").json()
        tracker = next(t for t in data["config"] if t["id"] == "tracker-sched")
        assert tracker["scheduleHistory"] == SCHEDULE
        assert tracker["polarity"] == "positive"

    def test_stale_reject_serverrow_carries_schedule_and_polarity(
            self, client, journal_registered_client):
        first = _upload(client, journal_registered_client, [_tracker()])
        stamp = first["acceptedTrackers"][0]["lastModifiedAt"]

        time.sleep(0.01)
        # Advance the stored stamp so `stamp` becomes stale.
        _upload(client, journal_registered_client,
                [_tracker(name="v2", _baseLastModifiedAt=stamp)])

        # Re-upload against the now-stale base token → rejected with serverRow.
        resp = _upload(client, journal_registered_client,
                       [_tracker(name="v3", _baseLastModifiedAt=stamp)])
        assert resp["acceptedTrackers"] == []
        rejected = resp["rejectedTrackers"][0]
        assert rejected["errorKind"] == "stale"
        server_row = rejected["serverRow"]
        assert server_row["scheduleHistory"] == SCHEDULE
        assert server_row["polarity"] == "positive"

    def test_update_changes_schedule_and_archives_prior(
            self, client, journal_registered_client, tmp_journal_db):
        first = _upload(client, journal_registered_client, [_tracker()])
        stamp = first["acceptedTrackers"][0]["lastModifiedAt"]

        time.sleep(0.01)
        new_schedule = [
            {"effectiveFrom": "0000-01-01", "days": [1, 2, 3, 4, 5]},
            {"effectiveFrom": "2026-07-03", "days": [1, 2, 3, 4, 5, 6]},
        ]
        _upload(client, journal_registered_client, [
            _tracker(scheduleHistory=new_schedule, polarity="neutral",
                     _baseLastModifiedAt=stamp),
        ])

        with get_db(tmp_journal_db) as conn:
            live = conn.execute(
                "SELECT schedule_json, polarity FROM trackers WHERE id = ?",
                ("tracker-sched",),
            ).fetchone()
            archived = conn.execute(
                "SELECT schedule_json, polarity FROM trackers_archive "
                "WHERE tracker_id = ? ORDER BY id DESC LIMIT 1",
                ("tracker-sched",),
            ).fetchone()

        assert json.loads(live["schedule_json"]) == new_schedule
        assert live["polarity"] == "neutral"
        # The prior version was archived with the OLD schedule + polarity.
        assert json.loads(archived["schedule_json"]) == SCHEDULE
        assert archived["polarity"] == "positive"
