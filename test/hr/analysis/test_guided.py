"""Tests for the guided-timeline derivation.

The rules here are the *client's*, mirrored so a ride that already happened
reads the same offline as it did on the phone — which is exactly why they are
pinned at the value level rather than only through a database. The Kotlin side
pins its own (`GuidanceTimelineTest`, `GuidedRideFillTest`); no test can span
the two languages, so each half has to fail loudly on its own.

Every number here is invented, and the far-future anchor convention is the
package's (see conftest).
"""
import pytest

from hr_analysis.guided import (
    GuidedExerciseRequired,
    extended_by,
    extension_target_index,
    guided_brief,
    guided_rides,
    guided_structure,
    parse_timeline,
    resolve_segments,
    ride_beats,
    select_ride,
    unguided_structure,
    weighted_stream,
)
from hr_analysis.intent import parse_intent
from hr_analysis.quality import Beat

from .conftest import TIMELINE_JSON, T0


def _start(ts_ms, timeline_json, *, exercise="ex-a", event_id="s1", date="2030-01-01"):
    return {"client_timestamp_ms": ts_ms, "exercise_key": exercise, "action": "start",
            "extension_sec": None, "timeline_json": timeline_json,
            "date": date, "event_id": event_id}


def _extend(ts_ms, sec=300, *, exercise="ex-a", event_id="e1", date="2030-01-01"):
    return {"client_timestamp_ms": ts_ms, "exercise_key": exercise, "action": "extend",
            "extension_sec": sec, "timeline_json": None,
            "date": date, "event_id": event_id}


def _beats(start_ms, hr, seconds):
    """A metronome-steady beat train at one heart rate."""
    rr = int(60_000 / hr)
    ts = start_ms
    out = []
    for _ in range(int(seconds * hr / 60)):
        out.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=hr, is_gap=False))
        ts += rr
    return out


@pytest.mark.unit
class TestParseTimeline:
    def test_reads_the_recorded_snapshot(self):
        assert len(parse_timeline(TIMELINE_JSON)) == 3

    @pytest.mark.parametrize("raw", [None, "", "[]", "not json", '{"a":1}', "42"])
    def test_anything_unreadable_is_segmentless(self, raw):
        """Only validated writes reach the column, so a blob that will not read
        is a hand-edited row — losing the band beats losing the ride."""
        assert parse_timeline(raw) == []

    def test_non_object_entries_are_dropped_not_fatal(self):
        assert parse_timeline('[{"duration_sec":60,"hr_min":100}, 7]') == [
            {"duration_sec": 60, "hr_min": 100}]


@pytest.mark.unit
class TestResolveSegments:
    def test_offsets_accumulate_and_are_half_open(self):
        segments = resolve_segments(parse_timeline(TIMELINE_JSON))
        assert [(s.start_sec, s.end_sec) for s in segments] == [
            (0, 300), (300, 900), (900, 1080)]

    def test_absent_role_is_work(self):
        """The compatibility rule the whole feature rests on: a timeline
        authored before `role` existed is all work."""
        segments = resolve_segments(parse_timeline(TIMELINE_JSON))
        assert [s.role for s in segments] == ["warmup", "work", "cooldown"]
        assert segments[1].is_work

    @pytest.mark.parametrize("raw, expected", [
        ("warmup", "warmup"), ("COOLDOWN", "cooldown"), (" work ", "work"),
        ("warmpu", "work"), ("", "work"), (None, "work"), (7, "work"),
    ])
    def test_an_unreadable_role_degrades_to_work(self, raw, expected):
        segment, = resolve_segments([{"duration_sec": 60, "hr_min": 100, "role": raw}])
        assert segment.role == expected

    def test_a_zero_bound_is_absence_not_a_bound_of_zero(self):
        """The server's floor is 1, so a 0 can only be hand-edited."""
        segment, = resolve_segments([{"duration_sec": 60, "hr_min": 0, "hr_max": 130}])
        assert (segment.hr_min, segment.band) == (None, "ceiling")

    @pytest.mark.parametrize("bad", [True, False, "seven", None, [1]])
    def test_an_unreadable_bound_is_absence(self, bad):
        """Booleans included: in Python they would otherwise pass as 0/1, which
        is the trap `coach_plans._segment_int` names on the write side."""
        segment, = resolve_segments([{"duration_sec": 60, "hr_min": 120, "hr_max": bad}])
        assert segment.hr_max is None

    def test_an_unreadable_duration_is_zero_length(self):
        segment, = resolve_segments([{"duration_sec": "soon", "hr_min": 120}])
        assert segment.duration_sec == 0

    def test_a_negative_duration_cannot_run_backwards(self):
        first, second = resolve_segments([
            {"duration_sec": -30, "hr_min": 100}, {"duration_sec": 60, "hr_min": 100}])
        assert (first.start_sec, first.end_sec) == (0, 0)
        assert (second.start_sec, second.end_sec) == (0, 60)

    def test_a_blank_label_is_absent(self):
        segment, = resolve_segments([{"duration_sec": 60, "hr_min": 100, "label": "  "}])
        assert segment.label is None

    @pytest.mark.parametrize("raw, band", [
        ({"hr_min": 120, "hr_max": 140}, "range"),
        ({"hr_min": 120}, "floor"),
        ({"hr_max": 140}, "ceiling"),
        ({}, "none"),
    ])
    def test_which_bounds_survive_is_the_meaning(self, raw, band):
        segment, = resolve_segments([{"duration_sec": 60, **raw}])
        assert segment.band == band

    def test_band_membership_is_inclusive(self):
        """A rider holding the ceiling exactly is doing what was asked."""
        segment, = resolve_segments([{"duration_sec": 60, "hr_min": 120, "hr_max": 140}])
        assert segment.holds(120) and segment.holds(140)
        assert not segment.holds(119) and not segment.holds(141)


@pytest.mark.unit
class TestExtensionRules:
    """`+ 5 MIN` lengthens THE WORK SEGMENT — the shipped rule, derived offline."""

    def _segments(self, *roles):
        return resolve_segments([
            {"duration_sec": 60, "hr_min": 100, **({"role": r} if r else {})}
            for r in roles
        ])

    def test_one_work_segment_absorbs_it_wherever_it_sits(self):
        segments = self._segments("warmup", None, "cooldown")
        assert extension_target_index(segments, 300) == 1

    def test_several_work_segments_have_no_single_meaning(self):
        """A VO2 session: there is no one segment five more minutes could mean,
        so the rule falls back to the last — the shape the control was never
        offered on in the first place."""
        assert extension_target_index(self._segments(None, None, None), 300) == 2

    def test_a_timeline_with_no_work_at_all_falls_back_to_the_last(self):
        assert extension_target_index(self._segments("warmup", "cooldown"), 300) == 1

    @pytest.mark.parametrize("segments, extension", [([], 300), (None, 0)])
    def test_nothing_to_extend_is_none(self, segments, extension):
        rules = self._segments("warmup") if segments is None else segments
        assert extension_target_index(rules, extension) is None

    def test_the_segments_after_it_shift_later_intact(self):
        """The cooldown moves rather than stretching: offsets are cumulative,
        so lengthening one in the middle has to move the ones behind it."""
        extended = extended_by(self._segments("warmup", None, "cooldown"), 300)
        assert [(s.start_sec, s.end_sec) for s in extended] == [
            (0, 60), (60, 420), (420, 480)]
        assert [s.duration_sec for s in extended] == [60, 360, 60]

    def test_folding_extends_in_at_once_equals_one_at_a_time(self):
        """Roles do not change as time is appended, so the target segment is the
        same on every pass — which is what makes summing the taps legitimate."""
        segments = self._segments("warmup", None, "cooldown")
        once = extended_by(segments, 600)
        twice = extended_by(extended_by(segments, 300), 300)
        assert [(s.start_sec, s.end_sec) for s in once] == \
            [(s.start_sec, s.end_sec) for s in twice]


@pytest.mark.unit
class TestGuidedRides:
    def test_one_start_is_one_ride(self):
        ride, = guided_rides([_start(T0, TIMELINE_JSON)])
        assert ride.exercise_key == "ex-a"
        assert ride.anchor_ms == T0
        assert (ride.planned_total_sec, ride.extension_sec, ride.total_sec) == (1080, 0, 1080)
        assert ride.discarded_starts == 0

    def test_the_latest_start_wins_and_earlier_runs_are_counted(self):
        ride, = guided_rides([
            _start(T0, TIMELINE_JSON, event_id="a"),
            _start(T0 + 60_000, '[{"duration_sec":120,"hr_min":130}]', event_id="b"),
        ])
        assert (ride.anchor_ms, ride.total_sec) == (T0 + 60_000, 120)
        assert ride.discarded_starts == 1

    def test_a_same_millisecond_tie_breaks_on_the_event_id(self):
        """So "the latest start" cannot depend on the order rows came back in."""
        ride, = guided_rides([
            _start(T0, '[{"duration_sec":600,"hr_min":130}]', event_id="zz"),
            _start(T0, '[{"duration_sec":120,"hr_min":130}]', event_id="aa"),
        ])
        assert ride.total_sec == 600

    def test_extends_after_the_anchor_sum(self):
        ride, = guided_rides([
            _start(T0, TIMELINE_JSON),
            _extend(T0 + 10_000, event_id="e1"),
            _extend(T0 + 20_000, event_id="e2"),
        ])
        assert (ride.extension_sec, ride.total_sec) == (600, 1680)
        assert [e["offset_s"] for e in ride.extends] == [10.0, 20.0]

    def test_an_extend_before_the_anchor_belonged_to_the_discarded_run(self):
        """The client drops the extension when it re-anchors, so keeping it
        would lengthen a ride nobody lengthened."""
        ride, = guided_rides([
            _start(T0, TIMELINE_JSON, event_id="a"),
            _extend(T0 + 5_000, event_id="e1"),
            _start(T0 + 60_000, TIMELINE_JSON, event_id="b"),
        ])
        assert ride.extension_sec == 0
        assert (ride.discarded_extends, ride.extends) == (1, [])

    def test_an_extend_at_the_anchor_instant_counts_as_after(self):
        """Half-open, like every other boundary in the guide: the instant
        belongs to the thing arriving."""
        ride, = guided_rides([_start(T0, TIMELINE_JSON), _extend(T0)])
        assert ride.extension_sec == 300

    def test_an_extend_with_no_step_recorded_adds_nothing(self):
        """The server does not enforce the field/action pairing, so a row
        without one is possible — it is still a tap, and still reported."""
        ride, = guided_rides([_start(T0, TIMELINE_JSON), _extend(T0 + 1000, sec=None)])
        assert ride.extension_sec == 0
        assert ride.extends == [{"client_timestamp_ms": T0 + 1000, "offset_s": 1.0}]

    def test_extends_without_a_start_are_not_a_ride(self):
        """Clipping the strap on mid-ride: the START predates the session, was
        never recorded, and is never back-filled."""
        assert guided_rides([_extend(T0)]) == []

    def test_two_exercises_are_two_rides_in_anchor_order(self):
        rides = guided_rides([
            _start(T0 + 900_000, "[]", exercise="ex-z", event_id="z"),
            _start(T0, TIMELINE_JSON, exercise="ex-a", event_id="a"),
        ])
        assert [r.exercise_key for r in rides] == ["ex-a", "ex-z"]

    def test_a_segmentless_ride_records_no_length(self):
        """Its planned length lives in the coach plan's target_duration_min,
        which hr.db deliberately does not carry."""
        ride, = guided_rides([_start(T0, "[]")])
        assert ride.is_segmentless
        assert (ride.planned_total_sec, ride.total_sec) == (None, None)

    def test_a_segmentless_ride_still_records_its_extends(self):
        ride, = guided_rides([_start(T0, "[]"), _extend(T0 + 1000)])
        assert ride.extension_sec == 300
        # Nothing to fold it into, so the total stays unknown rather than
        # becoming 300 out of nowhere.
        assert ride.total_sec is None
        assert ride.extended_index is None


@pytest.mark.unit
class TestSelectRide:
    def test_no_rides_is_an_unguided_session(self):
        assert select_ride([]) is None

    def test_a_lone_ride_needs_no_key(self):
        rides = guided_rides([_start(T0, TIMELINE_JSON)])
        assert select_ride(rides) is rides[0]

    def test_several_rides_ask_rather_than_guess(self):
        rides = guided_rides([
            _start(T0, TIMELINE_JSON, exercise="ex-a", event_id="a"),
            _start(T0 + 1000, "[]", exercise="ex-b", event_id="b"),
        ])
        with pytest.raises(GuidedExerciseRequired) as exc:
            select_ride(rides)
        assert [r.exercise_key for r in exc.value.rides] == ["ex-a", "ex-b"]
        assert "exercise_key" in str(exc.value)

    def test_a_key_chooses_one(self):
        rides = guided_rides([
            _start(T0, TIMELINE_JSON, exercise="ex-a", event_id="a"),
            _start(T0 + 1000, "[]", exercise="ex-b", event_id="b"),
        ])
        assert select_ride(rides, "ex-b").exercise_key == "ex-b"

    def test_an_unknown_key_names_what_is_there(self):
        rides = guided_rides([_start(T0, TIMELINE_JSON)])
        with pytest.raises(ValueError, match="ex-a"):
            select_ride(rides, "ex-nothing")

    def test_a_key_against_an_unguided_session_is_not_silently_ignored(self):
        """Answering with an unguided report would leave the caller holding a
        wrong belief about what they just measured."""
        with pytest.raises(ValueError, match="not guided at all"):
            select_ride([], "ex-a")


@pytest.mark.unit
class TestRideWindow:
    def test_the_window_is_half_open_at_the_scheduled_end(self):
        ride, = guided_rides([_start(T0, '[{"duration_sec":60,"hr_min":100}]')])
        beats = [Beat(ts_ms=T0 - 1, rr_ms=500, hr_bpm=120, is_gap=False),
                 Beat(ts_ms=T0, rr_ms=500, hr_bpm=120, is_gap=False),
                 Beat(ts_ms=T0 + 59_999, rr_ms=500, hr_bpm=120, is_gap=False),
                 Beat(ts_ms=T0 + 60_000, rr_ms=500, hr_bpm=120, is_gap=False)]
        assert [b.ts_ms for b in ride_beats(beats, ride)] == [T0, T0 + 59_999]

    def test_a_segmentless_ride_takes_everything_from_the_anchor(self):
        ride, = guided_rides([_start(T0 + 1000, "[]")])
        beats = [Beat(ts_ms=T0, rr_ms=500, hr_bpm=120, is_gap=False),
                 Beat(ts_ms=T0 + 900_000, rr_ms=500, hr_bpm=120, is_gap=False)]
        assert [b.ts_ms for b in ride_beats(beats, ride)] == [T0 + 900_000]


@pytest.mark.unit
class TestGuidedStructure:
    @pytest.fixture
    def ride(self):
        return guided_rides([_start(T0, TIMELINE_JSON), _extend(T0 + 30_000)])[0]

    @pytest.fixture
    def beats(self):
        # warmup 300 s in band, work 900 s (600 + the 300 appended) in band,
        # cooldown 180 s in band. The bands are TIMELINE_JSON's.
        return (_beats(T0, 118, 300)
                + _beats(T0 + 300_000, 145, 900)
                + _beats(T0 + 1_200_000, 112, 180))

    def test_it_says_where_the_structure_came_from(self, beats, ride):
        structure = guided_structure(beats, ride)
        assert (structure["guided"], structure["source"]) == (True, "guide_events")
        assert structure["anchor_ms"] == T0

    def test_the_appended_time_is_visible_in_the_segment_that_took_it(self, beats, ride):
        warmup, work, cooldown = guided_structure(beats, ride)["segments"]
        assert work["extended_sec"] == 300
        assert (work["start_offset_s"], work["end_offset_s"]) == (300.0, 1200.0)
        assert (cooldown["start_offset_s"], cooldown["end_offset_s"]) == (1200.0, 1380.0)
        assert "extended_sec" not in warmup

    def test_bounds_ride_along_sparsely(self, beats, ride):
        warmup, _work, cooldown = guided_structure(beats, ride)["segments"]
        assert (warmup["hr_min"], warmup["hr_max"]) == (112, 126)
        assert warmup["label"] == "ease in"
        assert "hr_min" not in cooldown and "label" not in cooldown

    def test_a_ride_held_in_band_reads_as_held(self, beats, ride):
        for segment in guided_structure(beats, ride)["segments"]:
            assert segment["fraction_in_band"] == 1.0
            assert segment["seconds_above_band"] == 0.0

    def test_the_work_aggregate_is_the_work_segments_only(self, beats, ride):
        structure = guided_structure(beats, ride)
        work = structure["work"]
        assert work["segment_indexes"] == [1]
        assert work["avg_hr"] == structure["segments"][1]["avg_hr"]
        assert work["beats"] == structure["segments"][1]["beats"]

    def test_the_supplied_intent_is_announced_as_ignored(self, beats, ride):
        assert guided_structure(beats, ride, intent_supplied=True)[
            "supplied_intent_ignored"] is True
        assert "supplied_intent_ignored" not in guided_structure(beats, ride)

    def test_coverage_measures_the_scheduled_ride(self, beats, ride):
        coverage = guided_structure(beats, ride)["coverage"]
        assert coverage["scheduled_sec"] == 1380
        assert coverage["complete"] is True
        assert coverage["beats_before_anchor"] == 0

    @pytest.mark.parametrize("shortfall_ms, complete", [
        # 0 is deliberately absent: a beat exactly on the scheduled end is
        # outside the half-open ride window, so the latest one that can count
        # is a millisecond before it.
        (1, True),
        (2_000, True),    # exactly the documented tolerance
        (2_001, False),   # one millisecond past it
    ])
    def test_the_completeness_tolerance_is_measured_in_raw_milliseconds(
            self, shortfall_ms, complete):
        """Two seconds means two seconds. The offsets beside this verdict are
        rounded to a tenth for reading, and comparing against one of those would
        stretch the tolerance to 2.05 s — a verdict must not inherit a display's
        precision."""
        ride, = guided_rides([_start(T0, '[{"duration_sec":60,"hr_min":100}]')])
        last = T0 + 60_000 - shortfall_ms
        beats = [Beat(ts_ms=T0, rr_ms=500, hr_bpm=120, is_gap=False),
                 Beat(ts_ms=last, rr_ms=500, hr_bpm=120, is_gap=False)]
        assert guided_structure(beats, ride)["coverage"]["complete"] is complete

    def test_a_beat_exactly_on_a_bound_is_in_the_band(self, ride):
        """Asserted on the METRIC, not just the predicate: a rider holding the
        ceiling exactly is doing what was asked, and the seconds have to say so
        or the fraction in band contradicts the dot on the screen."""
        floor_and_ceiling, = guided_rides(
            [_start(T0, '[{"duration_sec":40,"hr_min":120,"hr_max":140}]')])
        beats = _beats(T0, 120, 20) + _beats(T0 + 20_000, 140, 20)
        segment, = guided_structure(beats, floor_and_ceiling)["segments"]
        assert segment["seconds_below_band"] == 0.0
        assert segment["seconds_above_band"] == 0.0
        assert segment["fraction_in_band"] == 1.0
        assert segment["seconds_in_band"] == segment["covered_s"]

    def test_a_beat_one_past_a_bound_is_out(self, ride):
        """The other side of the same edge, so "inclusive" is pinned rather than
        merely observed."""
        banded, = guided_rides(
            [_start(T0, '[{"duration_sec":40,"hr_min":120,"hr_max":140}]')])
        beats = _beats(T0, 119, 20) + _beats(T0 + 20_000, 141, 20)
        segment, = guided_structure(beats, banded)["segments"]
        assert segment["seconds_in_band"] == 0.0
        assert segment["seconds_below_band"] > 0
        assert segment["seconds_above_band"] > 0

    def test_an_empty_capture_covers_nothing_without_raising(self, ride):
        coverage = guided_structure([], ride)["coverage"]
        assert (coverage["covered_sec"], coverage["complete"]) == (0.0, False)
        assert coverage["missing_tail_sec"] == 1380.0
        assert (coverage["capture_beats"], coverage["ride_beats"]) == (0, 0)

    def test_time_over_a_ceiling_is_measured_the_same_way_as_time_under_a_floor(self):
        """A rider who sat above their cooldown ceiling did not cool down, and
        the number has to say so — the mirror of the below-floor case, and the
        one a range-only fixture never exercises."""
        ride, = guided_rides([_start(T0, TIMELINE_JSON)])
        beats = (_beats(T0, 118, 300)               # warmup, in band
                 + _beats(T0 + 300_000, 145, 600)   # work, in band
                 + _beats(T0 + 900_000, 131, 90)    # cooldown, over its 118 ceiling
                 + _beats(T0 + 990_000, 111, 90))   # ...and then under it
        cooldown = guided_structure(beats, ride)["segments"][2]
        assert cooldown["seconds_above_band"] == pytest.approx(90, abs=2)
        assert cooldown["seconds_below_band"] == 0.0
        assert cooldown["fraction_in_band"] == pytest.approx(0.5, abs=0.05)
        # It reached the band eventually, and time_to_band_s says when.
        assert cooldown["time_to_band_s"] == pytest.approx(90, abs=2)

    def test_a_band_never_reached_has_no_time_to_band(self):
        """Different from reaching it late — the fraction in band tells the two
        apart, so the null is unambiguous."""
        ride, = guided_rides([_start(T0, '[{"duration_sec":120,"hr_min":170}]')])
        segment, = guided_structure(_beats(T0, 120, 120), ride)["segments"]
        assert segment["time_to_band_s"] is None
        assert segment["fraction_in_band"] == 0.0

    def test_a_segmentless_ride_with_no_beats_at_all(self):
        """Nothing to measure and nothing to crash on: the synthetic whole-ride
        span has no length to take from a window that does not exist."""
        ride, = guided_rides([_start(T0, "[]")])
        structure = guided_structure([], ride)
        assert structure["work"]["beats"] == 0
        assert structure["work"]["avg_hr"] is None
        assert structure["coverage"]["fraction"] is None


@pytest.mark.unit
class TestBeatWeighting:
    """Weights come off the WHOLE ride, then get partitioned — never the other
    way round.

    `_durations_s` reads a beat's weight off its successor and invents a median
    tail for the last beat it is handed. Weighting each segment's slice
    separately would therefore hand every boundary beat that invented tail in
    place of the capped truth its real successor earns it.
    """

    # Two 10-second steps, so the boundary falls at 0:10 and the ride ends at
    # 0:20 — the instant every weight is clipped at.
    TWO_STEPS = ('[{"duration_sec":10,"hr_min":100},'
                 '{"duration_sec":10,"hr_min":100}]')
    RIDE_END = T0 + 20_000

    def _ride(self):
        return guided_rides([_start(T0, self.TWO_STEPS)])[0]

    def _stream(self, beats):
        return weighted_stream(beats, self.RIDE_END)

    def _beats_with_a_gap_after(self, last_pre_ms, gap_rr_ms, straddler_hr=120):
        """Beats every 500 ms up to `last_pre_ms`, then a gap-marked beat at 15 s.

        The gap flag is what makes the last pre-boundary beat's six-second
        silence cap to one RR instead of being paid in full — the exact case
        Codex named. `straddler_hr` marks that last pre-boundary beat with a
        distinctive rate so it can be traced through the discrete facts.
        """
        beats = [
            Beat(ts_ms=T0 + ms, rr_ms=500, hr_bpm=120, is_gap=False)
            for ms in range(0, last_pre_ms, 500)
        ]
        beats.append(Beat(ts_ms=T0 + last_pre_ms, rr_ms=500,
                          hr_bpm=straddler_hr, is_gap=False))
        beats.append(Beat(ts_ms=T0 + 15_000, rr_ms=gap_rr_ms,
                          hr_bpm=90, is_gap=True))
        return beats

    def test_the_boundary_beat_keeps_its_capped_weight(self):
        """A beat at 0:09 whose successor at 0:15 is gap-marked is worth that
        successor's RR — one second — not the 500 ms median of the half of the
        ride that happens to precede the boundary."""
        beats = self._beats_with_a_gap_after(9_000, gap_rr_ms=1000)
        at_nine = next(row for row in self._stream(beats) if row[0] == T0 + 9_000)
        assert at_nine[2] == 1000.0

        # And the consequence at the segment level: the first step covers its
        # whole ten seconds. Weighting its slice alone would report 9.5 — the
        # median tail — and quietly understate the ride.
        first, _second = guided_structure(beats, self._ride())["segments"]
        assert first["covered_s"] == 10.0

    def test_a_straddling_beat_has_its_weight_split_at_the_boundary(self):
        """The rule: milliseconds before the boundary belong to the segment that
        was running then, the rest to the one arriving. Giving the whole weight
        to whichever segment held the timestamp would push a segment past its own
        length and leave its neighbour short."""
        beats = self._beats_with_a_gap_after(9_500, gap_rr_ms=1000)
        at_nine_five = next(row for row in self._stream(beats) if row[0] == T0 + 9_500)
        # Spans [9.5, 10.5) — half a second either side of the boundary.
        assert at_nine_five[2] == 1000.0

        first, second = guided_structure(beats, self._ride())["segments"]
        assert first["covered_s"] == 10.0
        # Half the straddling beat, plus the 0:15 beat's own half-second — the
        # median tail it gets for being the ride's genuine last beat, the one
        # invented weight the full-stream rule still allows.
        assert second["covered_s"] == pytest.approx(1.0, abs=0.05)

    def test_the_beat_itself_stays_in_the_segment_holding_its_timestamp(self):
        """Time is attributed where it was spent; a beat is where it happened.

        The straddling beat is marked at 170 while everything around it sits at
        120/90, so a leak into the next segment's discrete facts is visible: it
        would show up as that segment's peak, its opening reading, or a
        time-to-band it never earned.
        """
        beats = self._beats_with_a_gap_after(9_500, gap_rr_ms=1000, straddler_hr=170)
        first, second = guided_structure(beats, self._ride())["segments"]

        assert first["beats"] + second["beats"] == len(beats)
        assert second["beats"] == 1                    # only the 0:15 beat

        # The straddler is the first segment's peak and its closing reading.
        assert (first["peak_hr"], first["min_hr"]) == (170, 120)
        assert first["hr_at_end"] == 170
        # ...and appears nowhere in the second's, whose only beat is the 90 bpm
        # one at 0:15.
        assert (second["peak_hr"], second["min_hr"]) == (90, 90)
        assert (second["hr_at_start"], second["hr_at_end"]) == (90, 90)

        # time_to_band reads beats, not seconds: no reading INSIDE the second
        # segment ever reached its 100 bpm floor, so there is no time to report
        # — even though half a second of its time was spent in band, carried
        # across the boundary by the straddler. Both statements are true, and
        # they are the two halves of the rule.
        assert second["time_to_band_s"] is None
        assert second["seconds_in_band"] == pytest.approx(0.5, abs=0.05)
        assert first["time_to_band_s"] == 0.0

    def test_the_segments_seconds_partition_the_ride_exactly(self):
        """EXACT equality, and a ride whose final tail overruns the schedule.

        The tail `_durations_s` invents for the last beat is a guess about time
        after it; uncapped it runs past the end of the ride into time no segment
        covers, and that overflow would drop silently out of the totals. Clipping
        it where the weight is minted makes the partition exhaustive by
        construction — so this can assert `==`, not `approx`.
        """
        # Beats every 500 ms to 0:19, then one at 0:19.75 whose median tail
        # would reach 0:20.25 — a quarter-second past the ride's end.
        beats = [Beat(ts_ms=T0 + ms, rr_ms=500, hr_bpm=120, is_gap=False)
                 for ms in range(0, 19_001, 500)]
        beats.append(Beat(ts_ms=T0 + 19_750, rr_ms=750, hr_bpm=120, is_gap=False))

        structure = guided_structure(beats, self._ride())
        stream_total = sum(row[2] for row in self._stream(beats)) / 1000.0
        assert sum(s["covered_s"] for s in structure["segments"]) == stream_total
        assert [s["covered_s"] for s in structure["segments"]] == [10.0, 10.0]
        # Nothing of the overrun survives: the ride is twenty seconds and the
        # beats cover all twenty, not twenty and a quarter.
        assert stream_total == 20.0

    def test_the_averages_agree_with_hr_summary_when_the_tail_fits(self):
        """Same weighting rule, same beats, same answer — asserted on a fixture
        where the weights actually matter.

        The heart rate AND the beat spacing both vary, so a beat-counted average
        (100) and a duration-weighted one (90) are different numbers and only
        the right weighting lands on 90. A constant-HR fixture would have been
        satisfied by any positive weighting at all.
        """
        from hr_analysis.hr import hr_summary

        # Ten seconds at 60 bpm (one beat a second), then ten at 120 (two).
        beats = ([Beat(ts_ms=T0 + s * 1000, rr_ms=1000, hr_bpm=60, is_gap=False)
                  for s in range(10)]
                 + [Beat(ts_ms=T0 + 10_000 + i * 500, rr_ms=500, hr_bpm=120,
                         is_gap=False) for i in range(20)])
        ride, = guided_rides([_start(T0, '[{"duration_sec":20,"hr_min":100}]')])

        segment, = guided_structure(beats, ride)["segments"]
        assert segment["avg_hr"] == 90.0                      # not 100
        assert segment["avg_hr"] == hr_summary(ride_beats(beats, ride))["avg"]

    def test_the_averages_diverge_from_hr_summary_when_the_tail_overruns(self):
        """The one place the two deliberately differ, stated rather than hidden.

        A ride knows when it ended and a beat list does not. Codex's case: beats
        at 0:08 and 0:09.5 of a ten-second ride, each handed 1.5 s. `hr_summary`
        pays the second beat all 1.5 s and answers 50; the ride stops at 0:10,
        so only half a second of it was ridden, and the honest average over the
        ride is 55.
        """
        from hr_analysis.hr import hr_summary

        beats = [Beat(ts_ms=T0 + 8_000, rr_ms=1500, hr_bpm=60, is_gap=False),
                 Beat(ts_ms=T0 + 9_500, rr_ms=1500, hr_bpm=40, is_gap=False)]
        ride, = guided_rides([_start(T0, '[{"duration_sec":10,"hr_min":30}]')])

        segment, = guided_structure(beats, ride)["segments"]
        assert segment["avg_hr"] == 55.0
        assert segment["covered_s"] == 2.0
        assert hr_summary(ride_beats(beats, ride))["avg"] == 50.0


@pytest.mark.unit
class TestGuidedBrief:
    def test_boundaries_shift_onto_the_callers_clock(self):
        """A series whose first beat is 20 s after the anchor puts the first
        boundary at -20 s: the ride began before this window did."""
        ride, = guided_rides([_start(T0, TIMELINE_JSON)])
        brief = guided_brief(ride, T0 + 20_000)
        assert brief["anchor_offset_s"] == -20.0
        assert brief["segments"][0]["start_offset_s"] == -20.0
        assert brief["segments"][-1]["end_offset_s"] == 1060.0

    def test_it_carries_no_metrics(self):
        """Computing them twice is how two surfaces come to disagree."""
        ride, = guided_rides([_start(T0, TIMELINE_JSON)])
        keys = set(guided_brief(ride, T0)["segments"][0])
        assert not keys & {"avg_hr", "beats", "fraction_in_band", "covered_s"}


@pytest.mark.unit
class TestUnguidedStructure:
    def test_nothing_recorded_and_nothing_supplied(self):
        assert unguided_structure() == {"guided": False, "source": "none"}

    def test_a_supplied_plan_is_named_as_the_authority(self):
        structure = unguided_structure(parse_intent(
            {"rounds": 3, "work_duration_s": 60, "rest_duration_s": 45}))
        assert structure["source"] == "supplied_intent"
        assert structure["intent"]["rounds"] == 3

    def test_an_intent_without_structure_is_no_authority_at_all(self):
        """Target bounds alone do not lay out bouts, so there is no structure
        to credit."""
        assert unguided_structure(parse_intent({"target_hr_min": 140}))["source"] == "none"
