"""Every trends endpoint that has a calendar "now" reads the DEVICE's.

The contract's principle (docs/ARCHITECTURE.md "Device clock") is that the
server never assumes its own timezone is the watch's. `today` is what decides
which week is still in progress, how far back an adherence window reaches, and
where a ribbon ends — so a phone a day ahead of the server must not be told
about the server's week, or shown a ribbon ending yesterday.

Deterministic whichever way the far zone differs: the assertions are always
"this output is keyed to the DEVICE's date", never "the two answers differ",
because a one-day shift is only visible across a Monday for the week-granular
outputs. `zone_on_another_date` (test/trends/conftest.py) always finds a zone
whose date disagrees with the host's, at any hour.
"""

from datetime import date

import pytest

from modules.trends_queries import week_start

from .conftest import client_clock_headers, zone_on_another_date


@pytest.fixture
def far(client):
    """A client clock on another calendar date, and the headers that report
    it. Depends on `client` so the app (and its garmin module DB) exists."""
    zone_name, there, offset_min = zone_on_another_date()
    return {"zone": zone_name, "there": there, "offset": offset_min,
            "headers": client_clock_headers(zone_name, offset_min),
            "here": date.today()}


def _partial_weeks(weeks):
    return [w["week_start"] for w in weeks if w["partial"]]


@pytest.mark.integration
class TestWeeklyPartialFlagFollowsTheDevice:
    """`partial` means "this week is still running". Whose week is a question
    about whose clock — a week the device has already finished must not be
    served as in progress, and vice versa."""

    def test_strength_volume(self, client, strength_history, far):
        with_headers = client.get(
            "/api/trends/strength/volume", headers=far["headers"]).json()
        without = client.get("/api/trends/strength/volume").json()

        assert _partial_weeks(with_headers["weeks"]) == [
            week_start(far["there"]).isoformat()]
        assert _partial_weeks(without["weeks"]) == [
            week_start(far["here"]).isoformat()]

    def test_cardio(self, client, cardio_history, far):
        with_headers = client.get(
            "/api/trends/cardio", headers=far["headers"]).json()
        without = client.get("/api/trends/cardio").json()

        assert _partial_weeks(with_headers["weeks"]) == [
            week_start(far["there"]).isoformat()]
        assert _partial_weeks(without["weeks"]) == [
            week_start(far["here"]).isoformat()]

    def test_journal_tracker_detail(self, client, journal_history, far):
        url = "/api/trends/journal/tracker/t-protein"
        with_headers = client.get(url, headers=far["headers"]).json()
        without = client.get(url).json()

        assert _partial_weeks(with_headers["weekly_adherence"]) == [
            week_start(far["there"]).isoformat()]
        assert _partial_weeks(without["weekly_adherence"]) == [
            week_start(far["here"]).isoformat()]

    def test_the_emitted_range_ends_on_the_devices_date(
            self, client, journal_history, far):
        """The other half of the same statement: `end` defaults to the device's
        date, so the last week bucket is the one holding it. Day-granular, so
        this discriminates every hour of the day, not only across a Monday."""
        url = "/api/trends/journal/tracker/t-protein"
        with_headers = client.get(url, headers=far["headers"]).json()
        without = client.get(url).json()

        assert (with_headers["weekly_adherence"][-1]["week_start"]
                == week_start(far["there"]).isoformat())
        # Values are clipped by the same default, and the fixture logs every
        # day, so the last emitted value cannot outrun the device's date.
        assert all(v["date"] <= far["there"].isoformat()
                   for v in with_headers["values"])
        assert all(v["date"] <= far["here"].isoformat()
                   for v in without["values"])


@pytest.mark.integration
class TestOverviewWindowsFollowTheDevice:
    """Overview takes no range at all — every window inside it is measured
    back from `today`, which makes it the sharpest test of the wiring: the
    adherence ribbon is day-granular, so a one-day shift is always visible."""

    def test_the_focus_ribbon_ends_on_the_devices_date(
            self, client, journal_history, cardio_history, far):
        with_headers = client.get(
            "/api/trends/overview", headers=far["headers"]).json()
        without = client.get("/api/trends/overview").json()

        assert with_headers["adherence_focus"], "fixture produced no focus rows"
        for row in with_headers["adherence_focus"]:
            assert row["ribbon"][-1]["date"] == far["there"].isoformat()
        for row in without["adherence_focus"]:
            assert row["ribbon"][-1]["date"] == far["here"].isoformat()

    def test_the_ribbon_window_shifts_whole(self, client, journal_history, far):
        """Not just the end: the window is a fixed span measured back from
        today, so both edges move together."""
        data = client.get(
            "/api/trends/overview", headers=far["headers"]).json()
        for row in data["adherence_focus"]:
            dates = [r["date"] for r in row["ribbon"]]
            assert dates[-1] == far["there"].isoformat()
            assert len(dates) == 14
            assert dates == sorted(dates)
