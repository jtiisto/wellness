"""The hybrid strain tier's ELIGIBILITY rule: how many wrist samples a day
needs before TRIMP is allowed to score it.

A settled day is judged on a flat count (`hybrid.min_samples`, about 70 % wear
on a two-minute day). The CURRENT device day is judged on a WEAR FRACTION of
however much of it has elapsed on the device's own clock, floored at an hour
of samples at that day's measured cadence — otherwise the partial-day estimate
runs on the daily-feature regression all morning and STEPS to TRIMP in the
late afternoon, the moment the min_samples-th sample lands. See
docs/ARCHITECTURE.md, "The strain estimator is tiered".

The fixtures reuse test_sleep_endpoint's helpers and its invented params, with
`min_samples` raised to a realistic wear minimum so the fraction and the floor
are separately visible (the file's own value of 3 sits below the floor, where
neither could be told from the other). Every value here is INVENTED, on the
far-future fixture dates — never paste rows from the real ~/.garmy DB; this
repo is public.
"""

import math
from dataclasses import replace
from datetime import date, datetime, timedelta, timezone

import pytest

from modules.device_clock import ZoneTimeline

from .test_sleep_endpoint import (
    _PARAMS,
    _banister,
    _conn,
    _fresh_client,
    _iso,
    _ms,
    _tokyo_timeline,
    _utc_ms,
    _write_db,
)

# Two thirds of a two-minute day. Invented, like every other number in these
# fixtures, and referenced through the params object below rather than as a
# literal wherever an expectation is derived from it.
_MIN_SAMPLES = 480
_ELIGIBLE_PARAMS = replace(
    _PARAMS, hybrid=replace(_PARAMS.hybrid, min_samples=_MIN_SAMPLES))

_TODAY = date(2030, 1, 15)
_SERVER_ZONE_ONLY = ZoneTimeline(timezone.utc, [])

# A device on US Pacific time, where 2030-03-10 is twenty-three hours long and
# 2030-11-03 is twenty-five. The offset rides along as the resolver's fallback
# for an unknown id; the zone itself is what decides here.
_LA_SWITCH = datetime(2030, 1, 1, tzinfo=timezone.utc)


def _la_timeline():
    return ZoneTimeline(
        timezone.utc, [(_LA_SWITCH, "America/Los_Angeles", -480)])


# The default-clock test freezes both seams the request path reads rather than
# mocking the estimator: `date.today()` in the router and `datetime.now()` in
# the estimator. Subclassing keeps every other constructor in those modules —
# `combine`, `fromisoformat` — exactly as it was.
_FROZEN_NOW = datetime(2030, 1, 15, 12, 0, tzinfo=timezone.utc)


class _FrozenDate(date):
    @classmethod
    def today(cls):
        return _TODAY


class _FrozenDatetime(datetime):
    @classmethod
    def now(cls, tz=None):
        return (_FROZEN_NOW if tz is not None
                else _FROZEN_NOW.replace(tzinfo=None))

# One wrist sample's worth of TRIMP in these fixtures: 65 bpm against a
# resting 50 and the params' hr_max 200 (reserve fraction 0.1), weighted by a
# two-minute cadence. Low on purpose — hundreds of samples must still land
# under the estimator's 21.0 ceiling, or every strain expectation would read
# as the clamp instead of as the tier.
_HR = 65.0
_REST = 50


def _stream(start_ms, count, cadence_min=2.0, hr=_HR):
    """`count` heart-rate samples `cadence_min` apart from `start_ms`."""
    step = int(cadence_min * 60_000)
    return [("heart_rate", start_ms + i * step, hr) for i in range(count)]


def _utc_midnight_ms(day):
    return _utc_ms(day.year, day.month, day.day, 0, 0)


def _trimp(path, now_utc, *, today=_TODAY, timeline=_SERVER_ZONE_ONLY,
           params=_ELIGIBLE_PARAMS):
    from modules.trends_queries import _hybrid_trimp
    conn = _conn(path)
    try:
        return _hybrid_trimp(conn, str(path), params, today, timeline, now_utc)
    finally:
        conn.close()


def _one_day_db(path, day, stream):
    """A Garmin DB with both hybrid tables present and one streamed day. The
    daily row carries a `max_heart_rate`, so the day always has a tier-2
    answer to fall back to and "hybrid" is a claim rather than a default."""
    return _write_db(path, [(_iso(day), 7.0, 0, 4000, 100)],
                     rest_hr={_iso(day): _REST},
                     timeseries=stream, activities=[])


@pytest.mark.unit
class TestTheCurrentDaysWearFraction:
    """`_hybrid_trimp` itself, with the clock injected. A day is in the result
    when the tier can score it and absent when it cannot, which is exactly the
    signal the caller turns into a tier drop."""

    def test_the_fraction_replaces_the_flat_count_on_the_current_day(
            self, tmp_path):
        """Device-local 08:00, worn all morning. A third of the day has
        elapsed, so a third of the flat minimum is the bar — 160, not 480 —
        and 240 samples clear it."""
        stream = _stream(_utc_midnight_ms(_TODAY), 240)
        path = _one_day_db(tmp_path / "morning.db", _TODAY, stream)
        now = datetime(2030, 1, 15, 8, 0, tzinfo=timezone.utc)

        assert round(_MIN_SAMPLES * 8 / 24) == 160
        assert 160 <= 240 < _MIN_SAMPLES        # the settled rule would refuse
        result = _trimp(path, now)
        assert _iso(_TODAY) in result
        assert result[_iso(_TODAY)] == pytest.approx(
            (240 * _banister(_HR, _REST, 2.0), 0.0))

    def test_below_the_fraction_the_day_is_still_absent(self, tmp_path):
        """The same 08:00 with 100 samples. The bar scales with the day; it
        does not disappear."""
        stream = _stream(_utc_midnight_ms(_TODAY), 100)
        path = _one_day_db(tmp_path / "sparse_morning.db", _TODAY, stream)
        now = datetime(2030, 1, 15, 8, 0, tzinfo=timezone.utc)

        assert 100 < round(_MIN_SAMPLES * 8 / 24)
        assert _iso(_TODAY) not in _trimp(path, now)

    def test_an_hour_of_wear_is_the_floor_the_fraction_cannot_undercut(
            self, tmp_path):
        """Device-local 00:30. The fraction alone would ask for 10 samples —
        five minutes of TRIMP standing in for a whole day's strain — so the
        requirement is floored at an hour of wear at the day's own cadence."""
        now = datetime(2030, 1, 15, 0, 30, tzinfo=timezone.utc)
        assert round(_MIN_SAMPLES * 0.5 / 24) == 10
        assert math.ceil(60 / 2.0) == 30

        def build(name, count):
            return _one_day_db(tmp_path / name, _TODAY,
                               _stream(_utc_midnight_ms(_TODAY), count))

        # Above the fraction's own answer and still refused.
        assert _iso(_TODAY) not in _trimp(build("quarter.db", 15), now)
        assert _iso(_TODAY) not in _trimp(build("just_under.db", 29), now)
        assert _iso(_TODAY) in _trimp(build("exactly.db", 30), now)

    def test_the_floor_is_an_hour_at_the_days_own_cadence(self, tmp_path):
        """A minute-cadence day needs sixty samples for that same hour, not
        thirty: the floor is spelled in wall-clock time and converted by the
        cadence the day actually measured."""
        now = datetime(2030, 1, 15, 1, 0, tzinfo=timezone.utc)
        assert math.ceil(60 / 1.0) == 60

        def build(name, count):
            return _one_day_db(
                tmp_path / name, _TODAY,
                _stream(_utc_midnight_ms(_TODAY), count, cadence_min=1.0))

        # 45 clears the two-minute floor and misses the one-minute floor.
        assert _iso(_TODAY) not in _trimp(build("fast_under.db", 45), now)
        assert _iso(_TODAY) not in _trimp(build("fast_just_under.db", 59), now)
        assert _iso(_TODAY) in _trimp(build("fast_exactly.db", 60), now)

    def test_fewer_than_two_samples_is_never_a_day(self, tmp_path):
        """A cadence needs two samples to exist at all, so a single-sample day
        has no weight to give its own TRIMP — no floor and no fraction can
        rescue it."""
        now = datetime(2030, 1, 15, 23, 0, tzinfo=timezone.utc)
        path = _one_day_db(tmp_path / "one_sample.db", _TODAY,
                           _stream(_utc_midnight_ms(_TODAY), 1))
        assert _trimp(path, now) == {}

    def test_a_settled_day_still_needs_the_flat_minimum(self, tmp_path):
        """The rule that history is byte-identical: yesterday and everything
        before it is judged on `min_samples`, whatever the clock says now."""
        settled = _TODAY - timedelta(days=5)
        now = datetime(2030, 1, 15, 8, 0, tzinfo=timezone.utc)

        def build(name, count):
            return _write_db(
                tmp_path / name,
                [(_iso(settled), 7.0, 0, 4000, 100),
                 (_iso(_TODAY), 7.0, 0, 4000, 100)],
                rest_hr={_iso(settled): _REST},
                timeseries=_stream(_utc_midnight_ms(settled), count),
                activities=[])

        # 240 would have been plenty under the current day's 08:00 fraction.
        assert _iso(settled) not in _trimp(build("short.db", 240), now)
        assert _iso(settled) not in _trimp(
            build("one_short.db", _MIN_SAMPLES - 1), now)
        assert _iso(settled) in _trimp(build("exact.db", _MIN_SAMPLES), now)

    def test_the_fraction_is_read_on_the_device_clock(self, tmp_path):
        """Nine hours east the watch is at 16:00 while the server is at 07:00,
        and two thirds of ITS day have elapsed. Judging the fraction on the
        server's clock would let a day through on 140 samples that the
        device's own afternoon says needs 320."""
        now = datetime(2030, 1, 15, 7, 0, tzinfo=timezone.utc)
        # Tokyo midnight opening 2030-01-15 is 15:00Z on the 14th.
        tokyo_midnight = _utc_ms(2030, 1, 14, 15, 0)
        assert round(_MIN_SAMPLES * 16 / 24) == 320
        assert round(_MIN_SAMPLES * 7 / 24) == 140

        def build(name, count):
            return _write_db(
                tmp_path / name, [(_iso(_TODAY), 7.0, 0, 4000, 100)],
                rest_hr={_iso(_TODAY): _REST},
                timeseries=_stream(tokyo_midnight, count),
                activities=[])

        timeline = _tokyo_timeline()
        # 319 sits above the server-clock reading and below the device's.
        assert _iso(_TODAY) not in _trimp(
            build("east_under.db", 319), now, timeline=timeline)
        assert _iso(_TODAY) in _trimp(
            build("east_exact.db", 320), now, timeline=timeline)

    def test_a_today_the_device_has_not_reached_asks_only_the_floor(
            self, tmp_path):
        """Clock skew the other way: the instant being served is BEFORE the
        device midnight `today` opens at. Nothing of the day has elapsed, so
        the fraction contributes nothing and the hour-of-wear floor is the
        whole requirement — never a negative one that would admit any day at
        all."""
        now = datetime(2030, 1, 14, 22, 0, tzinfo=timezone.utc)

        def build(name, count):
            return _one_day_db(tmp_path / name, _TODAY,
                               _stream(_utc_midnight_ms(_TODAY), count))

        assert _iso(_TODAY) not in _trimp(build("skew_under.db", 29), now)
        assert _iso(_TODAY) in _trimp(build("skew_floor.db", 30), now)

    def test_just_after_device_midnight_asks_only_the_floor(self, tmp_path):
        """The same floor from the other side of midnight: ten minutes in, the
        fraction asks for three samples and the floor still asks for an
        hour's worth."""
        now = datetime(2030, 1, 15, 0, 10, tzinfo=timezone.utc)
        assert round(_MIN_SAMPLES * 10 / 1440) == 3

        def build(name, count):
            return _one_day_db(tmp_path / name, _TODAY,
                               _stream(_utc_midnight_ms(_TODAY), count))

        assert _iso(_TODAY) not in _trimp(build("dawn_under.db", 29), now)
        assert _iso(_TODAY) in _trimp(build("dawn_floor.db", 30), now)

    def test_a_device_past_the_day_never_asks_more_than_the_flat_minimum(
            self, tmp_path):
        """A `today` the device has already left behind — a stale calendar
        date against a clock a day and a half on. The elapsed fraction clamps
        at one, so the current day can never be asked for MORE than a settled
        day would be."""
        now = datetime(2030, 1, 16, 12, 0, tzinfo=timezone.utc)
        path = _one_day_db(
            tmp_path / "overrun.db", _TODAY,
            _stream(_utc_midnight_ms(_TODAY), _MIN_SAMPLES))

        # Unclamped, 36 elapsed hours would have asked for 720.
        assert round(_MIN_SAMPLES * 36 / 24) == 720
        assert _iso(_TODAY) in _trimp(path, now)

    def test_the_floor_never_outruns_the_settled_requirement(self, tmp_path):
        """A params file whose `min_samples` is SMALLER than an hour of
        samples — the shape the rest of this suite's shared params have. The
        current day is a relaxation of the flat count, so the floor is capped
        at it: a day that would already be settled-eligible can never be
        refused for being young."""
        now = datetime(2030, 1, 15, 2, 0, tzinfo=timezone.utc)
        tiny = _PARAMS.hybrid.min_samples
        assert tiny < math.ceil(60 / 2.0)      # 3 against an hour's 30

        def build(name, count):
            return _one_day_db(tmp_path / name, _TODAY,
                               _stream(_utc_midnight_ms(_TODAY), count))

        # Uncapped, the floor would have demanded 30 of this 2-minute day.
        assert _iso(_TODAY) not in _trimp(
            build("tiny_under.db", tiny - 1), now, params=_PARAMS)
        assert _iso(_TODAY) in _trimp(
            build("tiny_exact.db", tiny), now, params=_PARAMS)

    def test_the_requirement_rounds_rather_than_ceilings(self, tmp_path):
        """08:01:30 is 28 890 seconds into the day, and 480 x 28890/86400 is
        exactly 160.5 samples. The requirement ROUNDS — to the even 160, so
        the 160th sample admits the day — where a ceiling would have held out
        for a 161st."""
        now = datetime(2030, 1, 15, 8, 1, 30, tzinfo=timezone.utc)
        assert _MIN_SAMPLES * (28890 / 86400.0) == 160.5

        def build(name, count):
            return _one_day_db(tmp_path / name, _TODAY,
                               _stream(_utc_midnight_ms(_TODAY), count))

        assert _iso(_TODAY) not in _trimp(build("half_under.db", 159), now)
        assert _iso(_TODAY) in _trimp(build("half_exact.db", 160), now)

    @pytest.mark.parametrize("cadence,floor", [(0.25, 120), (10.0, 12)])
    def test_the_floor_uses_the_CLAMPED_cadence(self, tmp_path, cadence,
                                                floor):
        """An hour of samples at the cadence the estimator actually WEIGHTS
        by, which is the median gap clamped to [0.5, 5] minutes. A
        fifteen-second stream is weighted at the half-minute clamp, so its
        hour is 120 samples and not the 240 its raw gap implies; a ten-minute
        stream is weighted at the five-minute clamp, so its hour is 12 and not
        6. (The sparse stream runs past the hour being served — the rule
        counts samples and reads the clock, and never compares the two.)"""
        now = datetime(2030, 1, 15, 0, 30, tzinfo=timezone.utc)
        assert round(_MIN_SAMPLES * (1800 / 86400.0)) == 10   # not the floor
        assert math.ceil(60 / min(5.0, max(0.5, cadence))) == floor

        def build(name, count):
            return _one_day_db(
                tmp_path / name, _TODAY,
                _stream(_utc_midnight_ms(_TODAY), count, cadence_min=cadence))

        assert _iso(_TODAY) not in _trimp(build(f"c{floor}_under.db",
                                                floor - 1), now)
        assert _iso(_TODAY) in _trimp(build(f"c{floor}_exact.db", floor), now)

    def test_the_fraction_is_of_the_days_own_length(self, tmp_path):
        """2030-11-03 in Los Angeles is TWENTY-FIVE hours long — the clocks go
        back at 02:00 — so device-local noon is thirteen hours into the day,
        not twelve. The bar follows the day the device actually lived:
        round(480 x 13/25) = 250, where a nominal 24-hour denominator would
        have said 240 and admitted a day that is not yet that dense."""
        long_day = date(2030, 11, 3)
        midnight = _utc_ms(2030, 11, 3, 7, 0)          # 00:00 PDT
        now = datetime(2030, 11, 3, 20, 0, tzinfo=timezone.utc)   # 12:00 PST
        assert round(_MIN_SAMPLES * (13 / 25)) == 250
        assert round(_MIN_SAMPLES * (12 / 24)) == 240

        def build(name, count):
            return _write_db(
                tmp_path / name, [(_iso(long_day), 7.0, 0, 4000, 100)],
                rest_hr={_iso(long_day): _REST},
                timeseries=_stream(midnight, count), activities=[])

        def run(path):
            return _trimp(path, now, today=long_day, timeline=_la_timeline())

        # 245 clears the nominal denominator's 240 and misses the real one.
        assert _iso(long_day) not in run(build("long_naive.db", 245))
        assert _iso(long_day) not in run(build("long_under.db", 249))
        assert _iso(long_day) in run(build("long_exact.db", 250))

    def test_a_short_day_lowers_the_bar_by_its_own_length(self, tmp_path):
        """The mirror: 2030-03-10 in Los Angeles is TWENTY-THREE hours, since
        02:00 never happens, so device-local noon is only eleven hours in and
        the bar is round(480 x 11/23) = 230. A day the nominal denominator
        would have refused at 240 is eligible, because the day really is that
        far along."""
        short_day = date(2030, 3, 10)
        midnight = _utc_ms(2030, 3, 10, 8, 0)          # 00:00 PST
        now = datetime(2030, 3, 10, 19, 0, tzinfo=timezone.utc)   # 12:00 PDT
        assert round(_MIN_SAMPLES * (11 / 23)) == 230

        def build(name, count):
            return _write_db(
                tmp_path / name, [(_iso(short_day), 7.0, 0, 4000, 100)],
                rest_hr={_iso(short_day): _REST},
                timeseries=_stream(midnight, count), activities=[])

        def run(path):
            return _trimp(path, now, today=short_day, timeline=_la_timeline())

        assert _iso(short_day) not in run(build("short_under.db", 229))
        assert _iso(short_day) in run(build("short_exact.db", 230))
        # 235 is refused by a nominal 24-hour denominator's 240 and admitted
        # by the day's own length.
        assert _iso(short_day) in run(build("short_naive.db", 235))

    def test_a_future_dated_row_keeps_the_settled_rule(self, tmp_path):
        """A day AFTER `today` — the shape sync-job clock skew leaves behind.
        Only the current day scales; a future one is judged on the flat count
        like any other day that is not today."""
        tomorrow = _TODAY + timedelta(days=1)
        now = datetime(2030, 1, 15, 12, 0, tzinfo=timezone.utc)

        def build(name, count):
            return _write_db(
                tmp_path / name,
                [(_iso(_TODAY), 7.0, 0, 4000, 100),
                 (_iso(tomorrow), 7.0, 0, 4000, 100)],
                rest_hr={_iso(tomorrow): _REST},
                timeseries=_stream(_utc_midnight_ms(tomorrow), count),
                activities=[])

        # Well past any floor, and past the fraction a current day would ask.
        assert _iso(tomorrow) not in _trimp(build("ahead_short.db", 300), now)
        assert _iso(tomorrow) in _trimp(
            build("ahead_full.db", _MIN_SAMPLES), now)


@pytest.mark.unit
class TestThePartialDayNeverSettlesIntoTheMemo:
    """The memo exists because a settled day's TRIMP can never change. A day
    scored while it was still CURRENT is not that: it was admitted on a
    partial day's bar, and the reading is true only while the day is current.
    So it is never written to the memo — and the next pass, which may arrive
    after the recompute window has moved past it, owes it a settled reading
    rather than a gap.

    Both halves run two passes against ONE database path, which is what the
    module-level memo is keyed by.
    """

    def test_a_morning_reading_never_stands_as_a_settled_day(self, tmp_path):
        """240 samples at device 08:00: eligible on the morning's bar of 160.
        Eight days later the same rows are a settled day that never reached
        480, so the day drops a tier — the morning's TRIMP does not stand in
        for a day the watch spent mostly off the wrist."""
        path = _one_day_db(tmp_path / "memo_partial.db", _TODAY,
                           _stream(_utc_midnight_ms(_TODAY), 240))
        morning = datetime(2030, 1, 15, 8, 0, tzinfo=timezone.utc)
        later = datetime(2030, 1, 23, 8, 0, tzinfo=timezone.utc)

        assert _iso(_TODAY) in _trimp(path, morning)
        assert _iso(_TODAY) not in _trimp(
            path, later, today=_TODAY + timedelta(days=8))

    def test_the_day_the_memo_never_owned_is_read_back_not_lost(
            self, tmp_path):
        """The other half of the same rule. The current day is the one day the
        memo does not hold, so a later pass has to READ it rather than trust
        an entry that is not there: 480 samples clear the settled minimum, and
        the day is still scored after the recompute window has moved past
        it — at the full-day value, not the morning's."""
        path = _one_day_db(tmp_path / "memo_full.db", _TODAY,
                           _stream(_utc_midnight_ms(_TODAY), _MIN_SAMPLES))
        morning = datetime(2030, 1, 15, 8, 0, tzinfo=timezone.utc)
        later = datetime(2030, 1, 23, 8, 0, tzinfo=timezone.utc)
        whole_day = (_MIN_SAMPLES * _banister(_HR, _REST, 2.0), 0.0)

        assert _trimp(path, morning)[_iso(_TODAY)] == pytest.approx(whole_day)
        settled = _trimp(path, later, today=_TODAY + timedelta(days=8))
        assert settled[_iso(_TODAY)] == pytest.approx(whole_day)


@pytest.mark.integration
class TestTheLedgerSeesThePartialDaysTrimp:
    """The same rule as the ledger emits it: `tonight.strain_est` is the
    hybrid number by mid-morning instead of the regression's, which is the
    whole point — the card must not step when the 480th sample lands."""

    def _series(self, path, now_utc, *, today=_TODAY):
        from modules import trends_queries
        from modules.db import DbAccessor
        return trends_queries.sleep_series(
            DbAccessor(path, read_only=True), _ELIGIBLE_PARAMS,
            end=_iso(today), today=today, timeline=_SERVER_ZONE_ONLY,
            now_utc=now_utc)

    def test_tonight_rides_the_partial_days_hybrid_strain(self, tmp_path):
        """240 samples by 08:00: strain 1.0 + 0.25*240*0.155093826 = 10.3, and
        the night's need 400 + f(10.305629570) = 432.5. The regression on the
        same row would have said 3.0 and 398.5."""
        path = _one_day_db(
            tmp_path / "ledger_morning.db", _TODAY,
            _stream(_utc_midnight_ms(_TODAY), 240))
        data = self._series(
            path, datetime(2030, 1, 15, 8, 0, tzinfo=timezone.utc))

        assert round(1.0 + 0.25 * 240 * _banister(_HR, _REST, 2.0), 9) == \
            10.305629570
        assert data["tonight"] == {
            "date": _iso(_TODAY), "need_min": 432.5, "debt_min": 0.0,
            "strain_est": 10.3, "strain_partial": True}

    def test_the_same_row_falls_to_the_regression_below_the_fraction(
            self, tmp_path):
        """100 samples at the same hour: tier 2 on the daily row's
        max_heart_rate, 0.5*ln(1) + 2.0*4000/1000 + 0.05*100 - 10 = 3.0, and
        f(3.0) = -1.5."""
        path = _one_day_db(
            tmp_path / "ledger_sparse.db", _TODAY,
            _stream(_utc_midnight_ms(_TODAY), 100))
        data = self._series(
            path, datetime(2030, 1, 15, 8, 0, tzinfo=timezone.utc))

        assert data["tonight"] == {
            "date": _iso(_TODAY), "need_min": 398.5, "debt_min": 0.0,
            "strain_est": 3.0, "strain_partial": True}

    def test_the_endpoint_scores_a_partial_today_on_the_default_clock(
            self, tmp_path, client, monkeypatch):
        """`now_utc` is passed nowhere: the router resolves its own date and
        the estimator calls its own `datetime.now`. Both seams are frozen at a
        synthetic instant — a test that read the wall clock could cross
        midnight between building the fixture and asking for the ledger — and
        a full flat minimum of samples clears any bar the fraction can set, so
        the assertion holds under every host zone."""
        from modules import trends, trends_queries
        monkeypatch.setattr(trends, "date", _FrozenDate)
        monkeypatch.setattr(trends_queries, "datetime", _FrozenDatetime)

        path = _write_db(
            tmp_path / "today_live.db",
            [(_iso(_TODAY), 7.0, 0, 4000, 100)],
            rest_hr={_iso(_TODAY): _REST},
            # Host-local midnight: the endpoint runs on an empty timeline,
            # which is the server's own zone.
            timeseries=_stream(_ms(_TODAY, 0, 0), _MIN_SAMPLES),
            activities=[])

        with _fresh_client(path, monkeypatch, params=_ELIGIBLE_PARAMS) as c:
            data = c.get("/wellness/api/trends/health/sleep").json()

        assert data["tonight"]["date"] == _iso(_TODAY)

        # 1.0 + 0.25*480*0.155093826 = 19.611259141; tier 2 would say 3.0.
        assert round(1.0 + 0.25 * _MIN_SAMPLES * _banister(_HR, _REST, 2.0),
                     9) == 19.611259141
        assert data["tonight"]["strain_est"] == 19.6
        assert data["tonight"]["need_min"] == 553.1
