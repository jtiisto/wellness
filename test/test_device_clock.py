"""ZoneTimeline — the device's zone as a total function of time.

Pure arithmetic over `(observed_at, zone_id, offset_min)` triples, so every
rule here is pinned without a request or a database. The two rules under test
are the ones docs/ARCHITECTURE.md "Device clock" states: the device-local DATE
of an instant, and the INSTANT of a device-local wall time (earlier wins on
ambiguity, None when the clock never showed it).

Every timestamp is INVENTED and sits in the far-future 2030-* range this suite
uses everywhere, so nothing here can collide with a real observation.
"""

from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import pytest

from modules.db import DbAccessor
from modules.device_clock import (HOST_LOCAL_ZONE, ZoneTimeline, resolve_zone)
from modules.garmin import init_database, record_client_zone, _zone_tail

UTC = timezone.utc
TOKYO = ZoneInfo("Asia/Tokyo")            # +9, no DST
HELSINKI = ZoneInfo("Europe/Helsinki")    # +2 / +3, DST


def _at(y, m, d, hh=0, mm=0):
    return datetime(y, m, d, hh, mm, tzinfo=UTC)


def _ms(dt):
    return int(dt.timestamp() * 1000)


# A change point at noon UTC on 2030-03-01: the server's zone before it, the
# phone's after. Both fixtures below hang off this one instant.
SWITCH = _at(2030, 3, 1, 12)


@pytest.mark.unit
class TestSegments:
    def test_empty_timeline_is_the_server_zone_everywhere(self):
        timeline = ZoneTimeline(TOKYO, [])
        assert timeline.zone_at(_at(1999, 1, 1)) is TOKYO
        assert timeline.zone_at(_at(2099, 1, 1)) is TOKYO
        assert len(timeline) == 0

    def test_before_the_first_point_is_still_the_server_zone(self):
        timeline = ZoneTimeline(UTC, [(SWITCH, "Asia/Tokyo", 540)])
        assert timeline.zone_at(SWITCH - timedelta(seconds=1)) is UTC

    def test_a_point_takes_effect_at_its_own_instant(self):
        """Half-open [observed_at, next): the change point IS the first instant
        of its segment, so nothing falls between two segments."""
        timeline = ZoneTimeline(UTC, [(SWITCH, "Asia/Tokyo", 540)])
        assert timeline.zone_at(SWITCH) == TOKYO
        assert timeline.zone_at(SWITCH + timedelta(days=365)) == TOKYO

    def test_points_are_sorted_however_they_arrive(self):
        later = SWITCH + timedelta(days=1)
        timeline = ZoneTimeline(UTC, [(later, "Europe/Helsinki", 120),
                                      (SWITCH, "Asia/Tokyo", 540)])
        assert timeline.zone_at(SWITCH) == TOKYO
        assert timeline.zone_at(later) == HELSINKI

    def test_points_mixing_naive_and_aware_stamps_still_sort(self):
        """Normalization happens BEFORE the sort, because comparing a naive
        datetime with an aware one raises rather than ordering them — and a
        hand-built timeline, or a half-migrated row, is exactly where the two
        meet."""
        later = SWITCH + timedelta(days=1)
        timeline = ZoneTimeline(UTC, [
            (later.replace(tzinfo=None), "Europe/Helsinki", 120),
            (SWITCH, "Asia/Tokyo", 540)])
        assert timeline.zone_at(SWITCH) == TOKYO
        assert timeline.zone_at(later) == HELSINKI

    def test_an_offset_bearing_observed_at_is_converted_not_relabelled(self):
        stamped = SWITCH.astimezone(timezone(timedelta(hours=5)))
        timeline = ZoneTimeline(UTC, [(stamped, "Asia/Tokyo", 540)])
        assert timeline.zone_at(SWITCH - timedelta(seconds=1)) is UTC
        assert timeline.zone_at(SWITCH) == TOKYO
        assert timeline.points[0][0] == SWITCH

    def test_a_naive_observed_at_is_read_as_utc(self):
        timeline = ZoneTimeline(
            UTC, [(SWITCH.replace(tzinfo=None), "Asia/Tokyo", 540)])
        assert timeline.zone_at(SWITCH - timedelta(seconds=1)) is UTC
        assert timeline.zone_at(SWITCH) == TOKYO

    def test_an_unknown_zone_id_falls_back_to_the_reported_offset(self):
        """A zone this host's tz database has never heard of — a phone newer
        than the server, or a forged header — is still usable: the offset it
        sent alongside is exactly what the fallback is for."""
        timeline = ZoneTimeline(UTC, [(SWITCH, "Mars/Olympus", 90)])
        assert timeline.zone_at(SWITCH) == timezone(timedelta(minutes=90))

    def test_resolve_zone_prefers_the_id_over_the_offset(self):
        """The offset is a cross-check, never the primary: only the id carries
        DST rules, and a stale offset must not override them."""
        assert resolve_zone("Europe/Helsinki", 0) == HELSINKI

    def test_resolve_zone_survives_a_junk_offset_too(self):
        assert resolve_zone("Mars/Olympus", "not-a-number") is UTC


@pytest.mark.unit
class TestLocalDateOf:
    def test_a_mid_day_change_point_splits_the_day(self):
        """The failure this whole module exists for: nine hours east, samples
        from one UTC day belong to two DEVICE days. Before the switch the
        server's zone answers; after it, the phone's."""
        timeline = ZoneTimeline(UTC, [(SWITCH, "Asia/Tokyo", 540)])
        assert timeline.local_date_of(_at(2030, 3, 1, 11)) == date(2030, 3, 1)
        # 16:00Z is 01:00 the NEXT day in Tokyo.
        assert timeline.local_date_of(_at(2030, 3, 1, 16)) == date(2030, 3, 2)
        assert timeline.local_date_of(_at(2030, 3, 1, 23)) == date(2030, 3, 2)

    def test_dst_is_exact_not_a_frozen_offset(self):
        """`zoneinfo` decides, so a summer instant and a winter one under the
        same recorded point get different offsets — which a stored offset
        alone could never do."""
        timeline = ZoneTimeline(UTC, [(_at(2030, 1, 1), "Europe/Helsinki", 120)])
        # 21:30Z on 30 June is 00:30 the NEXT day in Helsinki (+3, DST)...
        assert timeline.local_date_of(_at(2030, 6, 30, 21, 30)) == date(2030, 7, 1)
        # ...and the same clock time on 30 December is still the 30th (+2).
        assert timeline.local_date_of(_at(2030, 12, 30, 21, 30)) == date(2030, 12, 30)

    def test_a_naive_instant_is_normalized_once(self):
        """Segment selection and the conversion must read the same instant. A
        naive input read as UTC by one and as server-local by the other would
        pick a zone for one instant and date a different one."""
        timeline = ZoneTimeline(UTC, [(SWITCH, "Asia/Tokyo", 540)])
        naive = datetime(2030, 3, 1, 16)
        assert (timeline.local_date_of(naive)
                == timeline.local_date_of(naive.replace(tzinfo=UTC))
                == date(2030, 3, 2))

    def test_local_date_of_ms_matches_local_date_of(self):
        timeline = ZoneTimeline(UTC, [(SWITCH, "Asia/Tokyo", 540)])
        for instant in (_at(2030, 3, 1, 11), SWITCH, _at(2030, 3, 1, 20)):
            assert (timeline.local_date_of_ms(_ms(instant))
                    == timeline.local_date_of(instant))


@pytest.mark.unit
class TestUtcOfLocal:
    def test_a_plain_wall_time_resolves_in_its_segment(self):
        timeline = ZoneTimeline(UTC, [(SWITCH, "Asia/Tokyo", 540)])
        # 06:00 in Tokyo on 2 March is 21:00Z on 1 March — after the switch.
        assert timeline.utc_of_local(datetime(2030, 3, 2, 6),
                                     hint=date(2030, 3, 2)) == _at(2030, 3, 1, 21)

    def test_the_earlier_instant_wins_when_a_change_point_repeats_a_wall_time(self):
        """Flying WEST rewinds the device clock, so one wall time happens
        twice on the travel day. The contract picks the earlier instant."""
        timeline = ZoneTimeline(TOKYO, [(SWITCH, "UTC", 0)])
        # 15:00 device-local on 1 March happened at 06:00Z (still Tokyo) and
        # again at 15:00Z (now UTC). The first one is the answer.
        assert timeline.utc_of_local(datetime(2030, 3, 1, 15),
                                     hint=date(2030, 3, 1)) == _at(2030, 3, 1, 6)

    def test_a_wall_time_a_zone_jump_skipped_is_unplaceable(self):
        """Flying EAST fast-forwards the clock: the hours it skipped were
        never displayed, so there is no instant to return."""
        timeline = ZoneTimeline(UTC, [(SWITCH, "Asia/Tokyo", 540)])
        assert timeline.utc_of_local(datetime(2030, 3, 1, 15),
                                     hint=date(2030, 3, 1)) is None

    def test_a_dst_gap_is_unplaceable_too(self):
        """Helsinki's spring forward skips 03:00-03:59 local on 2030-03-31 —
        the same "the device never showed this" case, from DST rather than a
        flight."""
        timeline = ZoneTimeline(HELSINKI, [])
        assert timeline.utc_of_local(datetime(2030, 3, 31, 3, 30),
                                     hint=date(2030, 3, 31)) is None
        # The hours either side of the gap are ordinary.
        assert timeline.utc_of_local(datetime(2030, 3, 31, 2, 30),
                                     hint=date(2030, 3, 31)) == _at(2030, 3, 31, 0, 30)
        assert timeline.utc_of_local(datetime(2030, 3, 31, 4, 30),
                                     hint=date(2030, 3, 31)) == _at(2030, 3, 31, 1, 30)

    def test_a_change_point_inside_a_fold_finds_the_later_side(self):
        """Both sides of an autumn fold are real instants. When a change point
        lands inside the repeated hour, the EARLIER side is outside the new
        segment and only the later one qualifies — trying `fold=0` alone would
        report "never displayed" about a wall clock the watch showed twice.

        Helsinki's fold repeats 03:00-03:59 local on 2030-10-27 at 00:xxZ
        (+3) and again at 01:xxZ (+2). With the Helsinki segment opening at
        01:00Z, 00:30Z is out and 01:30Z is the answer.
        """
        timeline = ZoneTimeline(
            UTC, [(_at(2030, 10, 27, 1), "Europe/Helsinki", 120)])
        assert timeline.utc_of_local(datetime(2030, 10, 27, 3, 30),
                                     hint=date(2030, 10, 27)) == _at(2030, 10, 27, 1, 30)

    def test_both_fold_sides_are_still_beaten_by_an_earlier_segment(self):
        """Trying fold=1 must not overturn the earliest-wins rule: when both
        sides qualify, the earlier one is still the answer."""
        timeline = ZoneTimeline(HELSINKI, [])
        assert timeline.utc_of_local(datetime(2030, 10, 27, 3, 30),
                                     hint=date(2030, 10, 27)) == _at(2030, 10, 27, 0, 30)

    def test_a_dst_fold_resolves_to_the_earlier_instant(self):
        """Helsinki's autumn fold repeats 03:00-03:59 local on 2030-10-27:
        00:30Z (+3) and 01:30Z (+2) both read 03:30 on the watch."""
        timeline = ZoneTimeline(HELSINKI, [])
        assert timeline.utc_of_local(datetime(2030, 10, 27, 3, 30),
                                     hint=date(2030, 10, 27)) == _at(2030, 10, 27, 0, 30)

    def test_the_hint_never_changes_the_answer(self):
        """It only narrows the segment search; the span test decides."""
        timeline = ZoneTimeline(TOKYO, [(SWITCH, "UTC", 0)])
        naive = datetime(2030, 3, 1, 15)
        assert (timeline.utc_of_local(naive, hint=date(2030, 3, 1))
                == timeline.utc_of_local(naive))

    def test_an_offset_bearing_stamp_is_honored_as_written(self):
        """Defensive, matching modules/garmin's rule for a naive-vs-aware
        stamp: if a future sync tool ever wrote an offset, respect it rather
        than re-interpreting it under some zone."""
        timeline = ZoneTimeline(UTC, [(SWITCH, "Asia/Tokyo", 540)])
        aware = datetime(2030, 3, 1, 15, tzinfo=timezone(timedelta(hours=2)))
        assert timeline.utc_of_local(aware) == _at(2030, 3, 1, 13)

    def test_utc_of_local_inverts_local_date_of(self):
        timeline = ZoneTimeline(UTC, [(SWITCH, "Asia/Tokyo", 540)])
        instant = timeline.utc_of_local(datetime(2030, 3, 3, 9))
        assert timeline.local_date_of(instant) == date(2030, 3, 3)


@pytest.mark.unit
class TestFingerprint:
    def test_it_tracks_length_and_the_newest_point(self):
        empty = ZoneTimeline(UTC, [])
        one = ZoneTimeline(UTC, [(SWITCH, "Asia/Tokyo", 540)])
        two = ZoneTimeline(UTC, [(SWITCH, "Asia/Tokyo", 540),
                                 (SWITCH + timedelta(days=1), "UTC", 0)])
        assert empty.fingerprint == "0:"
        assert one.fingerprint != empty.fingerprint
        assert two.fingerprint != one.fingerprint

    def test_the_same_points_fingerprint_the_same(self):
        points = [(SWITCH, "Asia/Tokyo", 540)]
        assert (ZoneTimeline(UTC, points).fingerprint
                == ZoneTimeline(UTC, list(points)).fingerprint)


@pytest.mark.unit
class TestBucketer:
    """The fast path over a run of sorted samples. It caches the epoch window
    of the local day it last answered, so it has to agree with the exact rule
    everywhere — including where the cache would be wrong to hold."""

    def _agrees_over(self, timeline, first, count, step_sec):
        exact, fast = [], []
        bucket = timeline.bucketer()
        for i in range(count):
            ms = _ms(first + timedelta(seconds=i * step_sec))
            exact.append(timeline.local_date_of_ms(ms).isoformat())
            fast.append(bucket(ms))
        assert fast == exact
        return exact

    def test_it_agrees_across_a_change_point(self):
        timeline = ZoneTimeline(UTC, [(SWITCH, "Asia/Tokyo", 540)])
        dates = self._agrees_over(timeline, _at(2030, 2, 28), 2 * 24 * 30, 120)
        assert set(dates) == {"2030-02-28", "2030-03-01", "2030-03-02"}

    def test_it_agrees_across_a_dst_spring_forward(self):
        timeline = ZoneTimeline(HELSINKI, [])
        self._agrees_over(timeline, _at(2030, 3, 30, 12), 24 * 30, 120)

    def test_it_agrees_across_a_dst_autumn_fold(self):
        timeline = ZoneTimeline(HELSINKI, [])
        self._agrees_over(timeline, _at(2030, 10, 26, 12), 24 * 30, 120)

    def test_an_unrepresentable_instant_buckets_to_nothing(self):
        """Rather than to a string no metric date could ever match. The caller
        skips the sample."""
        timeline = ZoneTimeline(UTC, [])
        assert timeline.bucketer()(10 ** 18) is None

    def test_it_does_not_carry_a_window_across_a_backwards_jump(self):
        """Samples arrive sorted, but nothing here depends on that."""
        timeline = ZoneTimeline(UTC, [(SWITCH, "Asia/Tokyo", 540)])
        bucket = timeline.bucketer()
        assert bucket(_ms(_at(2030, 3, 1, 20))) == "2030-03-02"
        assert bucket(_ms(_at(2030, 3, 1, 11))) == "2030-03-01"


@pytest.mark.unit
class TestHostLocalZone:
    """The default server segment. It must reproduce what this code did before
    the device clock existed — sqlite's `date(..., 'localtime')` and
    `datetime.fromtimestamp()`, i.e. the platform's own conversion, whatever
    zone the host is in and DST history included."""

    def test_local_date_matches_a_bare_fromtimestamp(self):
        timeline = ZoneTimeline.empty()
        for stamp in (1_600_000_000, 1_700_000_000, 1_772_000_000,
                      1_800_000_000):
            assert (timeline.local_date_of_ms(stamp * 1000)
                    == datetime.fromtimestamp(stamp).date())

    def test_utc_of_local_matches_a_bare_naive_timestamp(self):
        timeline = ZoneTimeline.empty()
        naive = datetime(2030, 6, 15, 9, 30)
        assert timeline.utc_of_local(naive, hint=naive.date()).timestamp() == \
            naive.timestamp()

    def test_the_shared_instance_is_stateless_and_reused(self):
        assert ZoneTimeline.empty().server_zone is HOST_LOCAL_ZONE
        assert repr(HOST_LOCAL_ZONE) == "HostLocalZone()"
        assert HOST_LOCAL_ZONE.dst(datetime(2030, 1, 1)) is None
        assert HOST_LOCAL_ZONE.tzname(datetime(2030, 1, 1)) is None
        assert HOST_LOCAL_ZONE.utcoffset(None) is None


@pytest.mark.unit
class TestLoad:
    def test_absent_database_is_the_server_zone(self, tmp_path):
        timeline = ZoneTimeline.load(tmp_path / "nope.db", server_zone=TOKYO)
        assert len(timeline) == 0
        assert timeline.zone_at(_at(2030, 1, 1)) is TOKYO

    def test_a_none_path_is_the_server_zone(self):
        assert len(ZoneTimeline.load(None, server_zone=TOKYO)) == 0

    def test_recorded_points_come_back_as_segments(self, tmp_path):
        path = tmp_path / "garmin_module.db"
        init_database(DbAccessor(path))
        try:
            record_client_zone(path, "Europe/Helsinki", 120,
                               _at(2030, 1, 1, 6))
            record_client_zone(path, "Asia/Tokyo", 540, SWITCH)
            timeline = ZoneTimeline.load(path, server_zone=UTC)
        finally:
            _zone_tail.pop(str(path), None)
        assert len(timeline) == 2
        assert timeline.zone_at(_at(2029, 12, 31)) is UTC
        assert timeline.zone_at(_at(2030, 1, 1, 6)) == HELSINKI
        assert timeline.zone_at(SWITCH) == TOKYO
        assert timeline.fingerprint == "2:2030-03-01T12:00:00+00:00"

    def test_the_default_server_zone_is_the_host(self, tmp_path):
        assert (ZoneTimeline.load(tmp_path / "nope.db").server_zone
                is HOST_LOCAL_ZONE)


@pytest.mark.unit
class TestTheRetiredSqlGrouping:
    """The empty timeline must reproduce, exactly, the SQL grouping it
    replaced: `date(timestamp/1000, 'unixepoch', 'localtime')`.

    That is the whole compatibility claim — a deployment with no Android
    client, and every day of history recorded before the first header arrived,
    reads as it always did. Checked against sqlite itself rather than against a
    re-derivation, and over a year of instants so whatever DST transitions this
    host's zone has are inside the range.
    """

    def test_it_agrees_with_sqlite_over_a_year(self):
        import sqlite3

        first = int(datetime(2026, 1, 1, tzinfo=UTC).timestamp())
        stamps = [(first + i * 3600) * 1000 for i in range(0, 24 * 366, 7)]

        conn = sqlite3.connect(":memory:")
        try:
            expected = [
                row[0] for row in conn.execute(
                    "WITH t(ms) AS (VALUES " +
                    ",".join("(%d)" % ms for ms in stamps) +
                    ") SELECT date(ms/1000,'unixepoch','localtime') FROM t")
            ]
        finally:
            conn.close()

        timeline = ZoneTimeline.empty()
        bucket = timeline.bucketer()
        assert [bucket(ms) for ms in stamps] == expected
        assert [timeline.local_date_of_ms(ms).isoformat()
                for ms in stamps] == expected


@pytest.mark.unit
class TestBucketingCost:
    """A warm request re-reads about a week of wrist samples — a few thousand
    at the stream's ~2-minute cadence. Bucketing them must stay negligible
    against the sqlite read they ride on, which is what the day-window cache in
    `bucketer` buys over a timezone conversion per sample.

    Measured on the dev machine at ~13 ms for 5000 samples (against ~16 ms for
    the naive per-sample conversion, and ~370 ms vs ~910 ms at the cold scan's
    quarter-million). The bound below is deliberately loose — this is a guard
    against an order-of-magnitude regression, not a benchmark, and a loaded CI
    box must not fail it.
    """

    def test_five_thousand_samples_stay_cheap(self):
        import time as _time

        timeline = ZoneTimeline(UTC, [(SWITCH, "Europe/Helsinki", 120)])
        first = _ms(_at(2030, 3, 20))
        stamps = [first + i * 120_000 for i in range(5000)]

        bucket = timeline.bucketer()
        started = _time.perf_counter()
        out = [bucket(ms) for ms in stamps]
        elapsed_ms = (_time.perf_counter() - started) * 1000

        # Just under seven days at a two-minute cadence, and it does not start
        # at local midnight — so eight calendar dates, which is also eight
        # window rebuilds against 4992 cache hits.
        assert len(set(out)) == 8
        assert elapsed_ms < 250, f"bucketing 5000 samples took {elapsed_ms:.1f} ms"
