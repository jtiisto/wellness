"""Integration tests for the trends journal endpoints."""

from datetime import timedelta

import pytest


def _iso(d):
    return d.isoformat()


@pytest.mark.integration
class TestJournalTrackers:
    def test_picker_contents_and_exclusions(self, client, journal_history):
        resp = client.get("/api/trends/journal/trackers")
        assert resp.status_code == 200
        trackers = {t["id"]: t for t in resp.json()["trackers"]}

        assert set(trackers) == {"t-protein", "t-alcohol", "t-mood", "t-stretch"}
        # Deleted and never-logged are excluded.
        assert "t-old" not in trackers
        assert "t-never" not in trackers

        protein = trackers["t-protein"]
        assert protein["unit"] == "g"
        assert protein["actionable"] is True
        assert protein["has_target"] is True

        mood = trackers["t-mood"]
        assert mood["actionable"] is False  # neutral: in picker via type only

    def test_ordering_recent_first(self, client, journal_history):
        trackers = client.get("/api/trends/journal/trackers").json()["trackers"]
        last_entries = [t["last_entry"] for t in trackers]
        assert last_entries == sorted(last_entries, reverse=True)


@pytest.mark.integration
class TestJournalTrackerDetail:
    def test_values_targets_and_streaks_shape(self, client, journal_history):
        today = journal_history["today"]
        start = _iso(today - timedelta(days=21))
        resp = client.get(f"/api/trends/journal/tracker/t-protein?start={start}")
        assert resp.status_code == 200
        data = resp.json()

        assert data["tracker"]["id"] == "t-protein"
        # Values ascending, in range, every-5th-day gaps present.
        dates = [v["date"] for v in data["values"]]
        assert dates == sorted(dates)
        assert all(start <= d <= _iso(today) for d in dates)

        # Target band: untargeted era (genesis None) → gap, then min=150 from
        # target_from through today.
        segs = data["target_segments"]
        assert len(segs) == 1
        assert segs[0]["start"] == journal_history["target_from"]
        assert segs[0]["end"] == _iso(today)
        assert segs[0]["min"] == 150

        assert set(data["streaks"]) == {"current", "best"}

    def test_weekly_adherence_blends_target_eras(self, client, journal_history):
        today = journal_history["today"]
        start = _iso(today - timedelta(days=21))
        weeks = client.get(
            f"/api/trends/journal/tracker/t-protein?start={start}"
        ).json()["weekly_adherence"]

        assert all(w["metric_kind"] == "adherence" for w in weeks)
        # Current week flagged partial; each week's buckets are consistent.
        for w in weeks:
            assert w["met"] + w["partial_days"] + w["missed"] <= w["scheduled_days"] or w["paused"]
        assert weeks[-1]["partial"] is True

    def test_negative_tracker_avoidance_mapping(self, client, journal_history):
        today = journal_history["today"]
        start = _iso(today - timedelta(days=14))
        data = client.get(
            f"/api/trends/journal/tracker/t-alcohol?start={start}"
        ).json()
        weeks = data["weekly_adherence"]
        assert all(w["metric_kind"] == "avoidance" for w in weeks)
        # Days with entries count against avoidance: met = scheduled - logged.
        for w in weeks:
            assert w["met"] + w["missed"] == w["scheduled_days"]

    def test_paused_tracker_weeks_muted_not_missed(self, client, journal_history):
        today = journal_history["today"]
        start = _iso(today - timedelta(days=14))
        weeks = client.get(
            f"/api/trends/journal/tracker/t-stretch?start={start}"
        ).json()["weekly_adherence"]
        # The most recent full pause week: zero scheduled days → paused.
        assert any(w["paused"] for w in weeks)
        for w in weeks:
            if w["paused"]:
                assert w["scheduled_days"] == 0
                assert w["missed"] == 0
                assert w["rate"] is None

    def test_effective_start_clamps_to_first_entry(self, client, journal_history):
        # Mood has a single entry yesterday; a 6-month range must not fabricate
        # earlier weeks.
        today = journal_history["today"]
        start = _iso(today - timedelta(days=182))
        data = client.get(
            f"/api/trends/journal/tracker/t-mood?start={start}"
        ).json()
        assert len(data["values"]) == 1
        assert len(data["weekly_adherence"]) <= 2  # first-entry week (+ boundary)

    def test_unknown_and_deleted_404(self, client, journal_history):
        assert client.get("/api/trends/journal/tracker/nope").status_code == 404
        assert client.get("/api/trends/journal/tracker/t-old").status_code == 404
        assert client.get("/api/trends/journal/tracker/t-never").status_code == 404
