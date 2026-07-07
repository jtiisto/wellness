"""Integration tests for the trends cardio endpoint."""

from datetime import timedelta

import pytest

from modules.trends_queries import week_start


def _iso(d):
    return d.isoformat()


@pytest.mark.integration
class TestCardioWeekly:
    def test_planned_vs_extra_attribution(self, client, cardio_history):
        # Seed days can share ISO weeks depending on today's weekday, so
        # attribution is asserted on RANGE TOTALS (weekday-invariant).
        today = cardio_history["today"]
        start = _iso(today - timedelta(days=21))
        data = client.get(f"/api/trends/cardio?start={start}").json()

        planned_total = sum(w["zone2_planned_min"] for w in data["weeks"])
        extra_total = sum(w["zone2_extra_min"] for w in data["weeks"])
        # Planned: 45 + 15 + 40 + 35 (the relinked day's planned sibling).
        assert planned_total == 135.0
        # Extra: rest-day 30 + relinked 25 — the relinked entry must stay
        # extra even though its day row is session-linked.
        assert extra_total == 55.0

    def test_orphan_cardio_excluded_everywhere(self, client, cardio_history):
        today = cardio_history["today"]
        start = _iso(today - timedelta(days=21))
        data = client.get(f"/api/trends/cardio?start={start}").json()
        # Totals exclude the orphan's 30 min entirely.
        total = sum(w["zone2_planned_min"] + w["zone2_extra_min"] for w in data["weeks"])
        assert total == 190.0
        # The orphan (30 min @ HR 150) would qualify for the proxy if it
        # weren't excluded — assert it isn't there.
        assert all(s["avg_hr"] != 150 for s in data["steady_sessions"])

    def test_interval_sessions_counted_separately(self, client, cardio_history):
        today = cardio_history["today"]
        start = _iso(today - timedelta(days=21))
        data = client.get(f"/api/trends/cardio?start={start}").json()
        wk = next(w for w in data["weeks"]
                  if w["week_start"] == _iso(week_start(today - timedelta(days=8))))
        assert wk["interval_sessions"] == 1
        # Interval minutes do NOT land in the zone2 buckets.
        assert all(w["zone2_planned_min"] != 24.0 for w in data["weeks"])

    def test_proxy_excludes_short_and_hrless(self, client, cardio_history):
        today = cardio_history["today"]
        start = _iso(today - timedelta(days=21))
        sessions = client.get(f"/api/trends/cardio?start={start}").json()["steady_sessions"]
        dates = [s["date"] for s in sessions]
        assert cardio_history["steady_dates"][1] not in dates  # 15 min: too short
        assert cardio_history["steady_dates"][2] not in dates  # no HR
        assert cardio_history["steady_dates"][0] in dates      # 45 min @ 142

        extra = next(s for s in sessions if s["date"] == cardio_history["extra_date"])
        assert extra["off_plan"] is True
        assert extra["avg_hr"] == 128

    def test_partial_flag_and_zero_weeks(self, client, cardio_history):
        today = cardio_history["today"]
        start = _iso(today - timedelta(days=21))
        weeks = client.get(f"/api/trends/cardio?start={start}").json()["weeks"]
        assert weeks[0]["week_start"] == _iso(week_start(today - timedelta(days=21)))
        for w in weeks:
            assert w["partial"] == (w["week_start"] == _iso(week_start(today)))

    def test_empty_db(self, client):
        data = client.get("/api/trends/cardio").json()
        assert data == {"weeks": [], "steady_sessions": []}
