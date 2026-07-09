"""Integration tests for the trends journal endpoints."""

from datetime import date, timedelta

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
        # Regression: weekly `met` must be the BLENDED count — weeks before
        # the target took effect count checkbox-met days, not 0 (the bug that
        # rendered a daily habit's whole pre-target history as missed).
        today = journal_history["today"]
        target_from = journal_history["target_from"]
        start = _iso(today - timedelta(days=21))
        weeks = client.get(
            f"/api/trends/journal/tracker/t-protein?start={start}"
        ).json()["weekly_adherence"]

        def expected_status(d):
            n = (today - d).days
            logged = 0 <= n <= 20 and not (n % 5 == 0 and n != 0)
            if _iso(d) >= target_from:               # targeted era: value vs min=150
                if not logged:
                    return "missed"
                value = 120 + (20 - n) * 2.5
                return "met" if value >= 150 else "partial"
            return "met" if logged else "missed"     # checkbox era (completed=1)

        assert all(w["metric_kind"] == "adherence" for w in weeks)
        assert weeks[-1]["partial"] is True
        # First entry: n=20 is an every-5th-day gap, so tracking starts at
        # n=19 — the clamped first-bucket window begins there.
        eff_start = today - timedelta(days=19)
        for w in weeks:
            monday = date.fromisoformat(w["week_start"])
            # The first bucket's window clamps to eff_start: pre-tracking
            # days are gaps, never scheduled misses (F14).
            days = [monday + timedelta(days=k) for k in range(7)
                    if eff_start <= monday + timedelta(days=k) <= today]
            statuses = [expected_status(d) for d in days]
            assert w["scheduled_days"] == len(days)
            assert w["met"] == statuses.count("met")
            assert w["partial_days"] == statuses.count("partial")
            assert w["missed"] == statuses.count("missed")
        # The blend is actually exercised: some pre-target week has met days.
        pre_target = [w for w in weeks
                      if _iso(date.fromisoformat(w["week_start"]) + timedelta(days=6))
                      < target_from]
        assert pre_target and any(w["met"] > 0 for w in pre_target)

    def test_negative_tracker_avoidance_mapping(self, client, journal_history):
        today = journal_history["today"]
        start = _iso(today - timedelta(days=14))
        data = client.get(
            f"/api/trends/journal/tracker/t-alcohol?start={start}"
        ).json()
        weeks = data["weekly_adherence"]
        assert all(w["metric_kind"] == "avoidance" for w in weeks)
        # Exact reconstruction, not a tautology (F8): entries exist on
        # today-4 and today-12; met = avoided days, missed = lapse days,
        # windows clamped to first entry (today-12).
        entry_days = {today - timedelta(days=4), today - timedelta(days=12)}
        eff_start = today - timedelta(days=12)
        for w in weeks:
            monday = date.fromisoformat(w["week_start"])
            days = [monday + timedelta(days=k) for k in range(7)
                    if eff_start <= monday + timedelta(days=k) <= today]
            lapses = sum(1 for d in days if d in entry_days)
            assert w["scheduled_days"] == len(days)
            assert w["missed"] == lapses
            assert w["met"] == len(days) - lapses

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

    def test_neutral_tracker_gets_weekly_usage(self, client, journal_history):
        # Neutral (non-actionable) trackers report entries-per-week buckets —
        # the meaningful trend for episodic observations.
        today = journal_history["today"]
        start = _iso(today - timedelta(days=14))
        data = client.get(
            f"/api/trends/journal/tracker/t-mood?start={start}"
        ).json()
        assert data["tracker"]["actionable"] is False
        usage = data["weekly_usage"]
        assert sum(w["count"] for w in usage) == 1     # one entry yesterday
        assert usage[-1]["partial"] in (True, False)
        assert all(set(w) == {"week_start", "partial", "count"} for w in usage)
        # Buckets align with the adherence weeks (same eff_start flooring).
        assert [w["week_start"] for w in usage] == \
               [w["week_start"] for w in data["weekly_adherence"]]

    def test_actionable_tracker_has_no_weekly_usage(self, client, journal_history):
        data = client.get("/api/trends/journal/tracker/t-protein").json()
        assert data["tracker"]["actionable"] is True
        assert "weekly_usage" not in data

    def test_unknown_and_deleted_404(self, client, journal_history):
        assert client.get("/api/trends/journal/tracker/nope").status_code == 404
        assert client.get("/api/trends/journal/tracker/t-old").status_code == 404
        assert client.get("/api/trends/journal/tracker/t-never").status_code == 404

    def test_calendar_invalid_dates_422(self, client, journal_history):
        # Shape-valid but calendar-invalid dates must 422 at validation —
        # they used to 500 on aggregates and masquerade as tracker 404s (F2).
        assert client.get(
            "/api/trends/journal/tracker/t-protein?start=2026-02-30"
        ).status_code == 422
        assert client.get(
            "/api/trends/journal/tracker/t-protein?end=2026-13-05"
        ).status_code == 422
        assert client.get("/api/trends/cardio?start=2026-02-30").status_code == 422
        assert client.get(
            "/api/trends/strength/volume?end=2026-02-30").status_code == 422
