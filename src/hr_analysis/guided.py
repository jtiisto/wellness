"""Read a captured session against the timeline it was actually guided to.

A ride this system owns end to end leaves two records in `hr.db`: the beats, and
the cardio guide's own user actions (`guide_events`). This module turns the
second into structure for the first — the absolute instant every segment began
and ended, and how the heart rate behaved inside each one's recorded band.

**The recorded timeline is the authority, and it is not cross-checked against
anything.** No plan is read back out of `coach.db` (a plan edited after the ride
would change what the ride was guided against), and no watch-side activity time
participates — a Garmin activity started while mounting the bike and a START
pressed after clipping in disagree by minutes as a matter of course, so
comparing them would generate a warning about nothing. What the guide recorded
is what the rider rode to; there is no second opinion to have.

Everything here is pure: it takes rows and beats and returns values. The rules
it applies are the *client's own*, mirrored rather than re-invented, because the
two have to agree about a ride that already happened:

- **Absence of `role` means work** (`android/.../GuidanceTimeline.kt`,
  `SegmentRole`). Warmup and cooldown are preparation around the session; the
  easy steps between efforts are work and are meant to be role-less.
- **`+ 5 MIN` lengthens the one work segment**, the segments after it shifting
  later intact — with the *last* segment as the fallback when there is no single
  work one (`extensionTargetIndex`). Because the target is chosen off the
  unextended list and roles never change, folding every extend in at once is the
  same answer as folding them in one at a time.
- **Boundaries are half-open**, `[start, end)`: the instant a segment ends
  belongs to the one arriving. That is the guide's universal convention and it
  is what stops a boundary beat from being counted in two segments at once.
- **Bounds are inclusive**: a beat exactly on the floor or the ceiling is *in*
  the band. A rider holding the ceiling exactly is doing what was asked.

Its one deliberate divergence from the client is weighting: the app's auto-fill
averages the work spans by *beat*, this package averages by each beat's
wall-clock duration, as every other HR number in it does (`hr.time_weighted_mean_hr`,
which also caps a dropout's delta so a stale sample is not credited the silence).
Two averages of one ride can therefore differ by a beat or two. Mixing the two
weightings inside one report would be worse: its numbers would not be comparable
with each other.
"""

import json
from dataclasses import dataclass, replace
from typing import Any

import numpy as np

from .hr import _durations_s
from .hrv import rmssd_pooled
from .quality import classify, contiguous_rr_runs, rr_coverage

# What a segment is *for*, mirroring the server's `coach_plans.SEGMENT_ROLES`.
# Absence is WORK — the rule that keeps every timeline authored before the field
# existed reading exactly as it did.
WARMUP, WORK, COOLDOWN = "warmup", "work", "cooldown"
SEGMENT_ROLES = (WARMUP, WORK, COOLDOWN)

MS_PER_SEC = 1000

# How near the scheduled end a capture has to reach to count as having run the
# whole ride. One beat's worth of silence at the slowest plausible rate — a
# strap notifying at 1 Hz cannot be asked to land a sample on the boundary.
COMPLETE_TOLERANCE_MS = 2000


class GuidedExerciseRequired(Exception):
    """One capture spans several guided exercises and no key said which.

    Raised rather than guessed, and it carries `rides` so the caller can show
    the choice: a VO2 session followed by a Zone 2 ride is one capture with two
    timelines, and picking the first would silently report the wrong one.
    """

    def __init__(self, rides):
        self.rides = list(rides)
        keys = ", ".join(ride.exercise_key for ride in self.rides)
        super().__init__(
            f"This session was guided for {len(self.rides)} exercises ({keys}). "
            f"Pass exercise_key to choose one."
        )


@dataclass(frozen=True)
class GuidedSegment:
    """One step of a recorded timeline, with its place on it resolved.

    `start_sec`/`end_sec` are offsets from the anchor, half-open. Bounds are
    absolute bpm; which of them survive IS the segment's meaning — floor only,
    ceiling only, both a range, neither a step with no band at all.
    """

    index: int
    label: str | None
    role: str
    start_sec: int
    end_sec: int
    hr_min: int | None
    hr_max: int | None

    def shifted(self, *, grow_sec=0, move_sec=0):
        """A copy lengthened at its end and/or moved later as a whole."""
        return replace(
            self,
            start_sec=self.start_sec + move_sec,
            end_sec=self.end_sec + move_sec + grow_sec,
        )

    @property
    def duration_sec(self):
        return self.end_sec - self.start_sec

    @property
    def is_work(self):
        return self.role == WORK

    @property
    def band(self):
        """`range` / `floor` / `ceiling` / `none` — what the bounds add up to."""
        if self.hr_min is not None and self.hr_max is not None:
            return "range"
        if self.hr_min is not None:
            return "floor"
        if self.hr_max is not None:
            return "ceiling"
        return "none"

    @property
    def has_band(self):
        return self.band != "none"

    def holds(self, bpm):
        """Whether a reading is inside the band. Bounds are inclusive."""
        return ((self.hr_min is None or bpm >= self.hr_min)
                and (self.hr_max is None or bpm <= self.hr_max))


@dataclass(frozen=True)
class GuidedRide:
    """One exercise's guided run, as the record describes it.

    `segments` is the **effective** timeline — appended minutes already folded
    into the segment that absorbed them — so nothing downstream has to remember
    to apply the extends.

    `total_sec` is None for a segmentless ride, and that is an honest hole
    rather than a bug: a `duration` exercise with no authored timeline records
    `[]`, and its planned length lives in the coach plan's `target_duration_min`,
    which this database deliberately does not carry. Such a ride is analysed
    from its anchor to wherever the beats end.
    """

    exercise_key: str
    date: str
    anchor_ms: int
    segments: list[GuidedSegment]
    planned_total_sec: int | None
    extension_sec: int
    extends: list[dict[str, Any]]
    extended_index: int | None
    discarded_starts: int
    discarded_extends: int

    @property
    def is_segmentless(self):
        return not self.segments

    @property
    def total_sec(self):
        """The ride's whole length including appended minutes, or None."""
        if self.planned_total_sec is None:
            return None
        return self.planned_total_sec + self.extension_sec

    @property
    def scheduled_end_ms(self):
        total = self.total_sec
        return None if total is None else self.anchor_ms + total * MS_PER_SEC

    def brief(self, session_start_ms=None):
        """The listing's one-line description of this ride.

        Enough to choose between two of them without analysing either: which
        exercise, when it anchored, how long it was and how much of that was
        appended.
        """
        out = {
            "exercise_key": self.exercise_key,
            "date": self.date,
            "anchor_ms": self.anchor_ms,
            "segments": len(self.segments),
            "extends": len(self.extends),
            "extension_sec": self.extension_sec,
            "total_sec": self.total_sec,
        }
        if session_start_ms is not None:
            out["anchor_offset_s"] = _seconds(self.anchor_ms - session_start_ms)
        return out


# ---------------------------------------------------------------- derivation

def _int_or_none(value, minimum=1):
    """A wire integer, or None for absent / zero / unreadable.

    Lenient on purpose, the client's rule: only validated writes reach the
    column, so a value that will not read is a hand-edited row, and degrading to
    absence beats refusing to describe a ride that happened. A zero bound is
    absence too — the server's floor is 1, so a 0 can only be hand-edited.
    """
    if value is None or isinstance(value, bool):
        return None
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed >= minimum else None


def parse_timeline(raw):
    """The `timeline_json` snapshot as a list of segment dicts.

    Anything that will not parse, or is not a list, reads as *segmentless* — the
    same degradation `coach_plans.segments_from_json` applies, and for the same
    reason: a malformed blob is a hand-edited row, and losing the band is a
    better answer than losing the ride.
    """
    if not raw:
        return []
    try:
        parsed = json.loads(raw)
    except (ValueError, TypeError):
        return []
    if not isinstance(parsed, list):
        return []
    return [item for item in parsed if isinstance(item, dict)]


def _role_of(raw):
    """The wire's role read leniently: anything that is not one of the three is
    work, which is what absence already means (the client's `fromWire`)."""
    if not isinstance(raw, str):
        return WORK
    folded = raw.strip().lower()
    return folded if folded in SEGMENT_ROLES else WORK


def resolve_segments(raw_segments):
    """Segment dicts -> `GuidedSegment`s carrying resolved offsets.

    Durations are clamped at zero (a negative one would put every later offset
    behind the one before it) and a blank label counts as absent.
    """
    resolved = []
    offset = 0
    for index, raw in enumerate(raw_segments):
        duration = _int_or_none(raw.get("duration_sec"), minimum=1) or 0
        label = raw.get("label")
        label = label.strip() if isinstance(label, str) else None
        resolved.append(GuidedSegment(
            index=index,
            label=label or None,
            role=_role_of(raw.get("role")),
            start_sec=offset,
            end_sec=offset + duration,
            hr_min=_int_or_none(raw.get("hr_min")),
            hr_max=_int_or_none(raw.get("hr_max")),
        ))
        offset += duration
    return resolved


def extension_target_index(segments, extension_sec):
    """Which segment appended time goes into: **the work one**.

    Exactly one work-role segment — the only shape the control is offered on —
    means that segment is the ride, and lengthening it is what "five more
    minutes" means: a cooldown after it shifts later intact rather than being
    stretched into a cooldown nobody asked for. The fallback is the **last**
    segment, which is what shipped before roles and is what a record extended
    under one plan shape but re-synced into another falls back to. Either way
    the appended time has to land somewhere, or the segments would no longer
    sum to the total.

    Mirrors `List<GuidanceSegment>.extensionTargetIndex` exactly.
    """
    if extension_sec <= 0 or not segments:
        return None
    work = [seg for seg in segments if seg.is_work]
    return work[0].index if len(work) == 1 else segments[-1].index


def extended_by(segments, extension_sec):
    """Fold appended time into the segment `extension_target_index` names,
    shifting everything after it later by the same amount."""
    target = extension_target_index(segments, extension_sec)
    if target is None:
        return list(segments)
    out = []
    for seg in segments:
        if seg.index < target:
            out.append(seg.shifted())
        elif seg.index == target:
            out.append(seg.shifted(grow_sec=extension_sec))
        else:
            out.append(seg.shifted(move_sec=extension_sec))
    return out


def guided_rides(events):
    """The guided rides one session's `guide_events` describe, anchor order.

    Grouped by exercise, because one capture legitimately spans two of them
    (a VO2 session, then a Zone 2 ride). Within an exercise:

    - **The latest `start` wins.** A fresh run appends a second start rather
      than editing the first; the record is append-only, and the earlier rows
      are discarded runs whose count is reported rather than hidden.
    - **Extends at or after that anchor apply**, and their steps sum. An extend
      stamped before it belonged to the run the anchor discarded — the client
      drops the extension when it re-anchors, so keeping it here would lengthen
      a ride nobody lengthened. The boundary instant itself counts as after, on
      the half-open convention that governs every other boundary in the guide.
    - **Extends without a start are not a guided ride at all.** That is the
      recorded shape of clipping the strap on mid-ride: the START happened
      before the session existed, so it was never recorded and is never
      back-filled. Such a session reads as unguided, which is the honest answer.
    """
    by_exercise = {}
    for event in events:
        by_exercise.setdefault(event["exercise_key"], []).append(event)

    rides = []
    for exercise_key, group in by_exercise.items():
        starts = sorted(
            (e for e in group if e["action"] == "start"), key=_event_order,
        )
        if not starts:
            continue
        chosen = starts[-1]
        anchor_ms = chosen["client_timestamp_ms"]

        applied, discarded_extends = [], 0
        for event in group:
            if event["action"] != "extend":
                continue
            if event["client_timestamp_ms"] >= anchor_ms:
                applied.append(event)
            else:
                discarded_extends += 1

        applied.sort(key=_event_order)
        extension_sec = sum(
            _int_or_none(e.get("extension_sec")) or 0 for e in applied
        )
        planned = resolve_segments(parse_timeline(chosen["timeline_json"]))
        rides.append(GuidedRide(
            exercise_key=exercise_key,
            date=chosen["date"],
            anchor_ms=anchor_ms,
            segments=extended_by(planned, extension_sec),
            planned_total_sec=planned[-1].end_sec if planned else None,
            extension_sec=extension_sec,
            extends=[_extend_dict(e, anchor_ms) for e in applied],
            extended_index=extension_target_index(planned, extension_sec),
            discarded_starts=len(starts) - 1,
            discarded_extends=discarded_extends,
        ))
    rides.sort(key=lambda ride: (ride.anchor_ms, ride.exercise_key))
    return rides


def _event_order(event):
    """(stamp, id) — the id breaks a same-millisecond tie deterministically, so
    "the latest start" can never depend on the order rows came back in."""
    return (event["client_timestamp_ms"], event.get("event_id") or "")


def _extend_dict(event, anchor_ms):
    out = {
        "client_timestamp_ms": event["client_timestamp_ms"],
        "offset_s": _seconds(event["client_timestamp_ms"] - anchor_ms),
    }
    step = _int_or_none(event.get("extension_sec"))
    if step is not None:
        out["extension_sec"] = step
    return out


def select_ride(rides, exercise_key=None):
    """The one ride to analyse, or None when the session was not guided.

    Never a guess: with several guided exercises and no key this raises
    `GuidedExerciseRequired` carrying the choice, and the callers turn that into
    whatever asking looks like on their surface.
    """
    if exercise_key is not None:
        # Asked for, so answered — never silently ignored. A caller naming an
        # exercise this session does not have has a wrong belief, and handing
        # them an unguided report instead would leave them holding it.
        for ride in rides:
            if ride.exercise_key == exercise_key:
                return ride
        if not rides:
            raise ValueError(
                f"This session was not guided at all, so there is no "
                f"'{exercise_key}' in it to analyse."
            )
        keys = ", ".join(ride.exercise_key for ride in rides)
        raise ValueError(
            f"This session has no guided exercise '{exercise_key}'. It has: {keys}."
        )
    if not rides:
        return None
    if len(rides) > 1:
        raise GuidedExerciseRequired(rides)
    return rides[0]


# -------------------------------------------------------------------- window

def ride_beats(beats, ride):
    """The session's beats inside the ride's window.

    `[anchor, anchor + total)` — half-open, so a beat landing exactly on the
    scheduled end belongs to the silence after the ride, not to its last
    segment. A segmentless ride has no recorded end and takes everything from
    the anchor on.
    """
    end_ms = ride.scheduled_end_ms
    return [
        beat for beat in beats
        if beat.ts_ms >= ride.anchor_ms and (end_ms is None or beat.ts_ms < end_ms)
    ]


def _coverage(beats, window, ride):
    """How much of the scheduled ride the beats actually cover.

    The window is clipped by where the capture ends, and that clipping is
    reported rather than absorbed: a rider who bailed at minute 12 of a 20-minute
    plan has a report whose per-segment numbers stop, and a reader has to be able
    to tell that from a ride whose last segments were simply easy.
    """
    total_sec = ride.total_sec
    out = {
        "anchor_ms": ride.anchor_ms,
        "scheduled_sec": total_sec,
        "scheduled_end_ms": ride.scheduled_end_ms,
        # Two different counts, named apart on purpose: what the strap captured,
        # and what fell inside the ride.
        "capture_beats": len(beats),
        "ride_beats": len(window),
        "beats_before_anchor": sum(1 for b in beats if b.ts_ms < ride.anchor_ms),
        "beats_after_schedule": (
            0 if ride.scheduled_end_ms is None
            else sum(1 for b in beats if b.ts_ms >= ride.scheduled_end_ms)
        ),
    }
    if not window:
        out.update({
            "first_beat_offset_s": None, "last_beat_offset_s": None,
            "covered_sec": 0.0, "fraction": 0.0 if total_sec else None,
            "missing_tail_sec": None if total_sec is None else float(total_sec),
            "complete": False,
        })
        return out

    first_offset = _seconds(window[0].ts_ms - ride.anchor_ms)
    last_offset = _seconds(window[-1].ts_ms - ride.anchor_ms)
    out.update({
        "first_beat_offset_s": first_offset,
        "last_beat_offset_s": last_offset,
        "covered_sec": last_offset,
        "fraction": None if not total_sec else round(min(1.0, last_offset / total_sec), 3),
        "missing_tail_sec": (
            None if total_sec is None else round(max(0.0, total_sec - last_offset), 1)
        ),
        # A ride is complete when the capture reached its last seconds. The
        # tolerance is one beat's worth of silence, not a judgment: a strap
        # notifying at 1 Hz cannot be expected to land a sample on the boundary.
        #
        # Measured in RAW milliseconds, against the scheduled end rather than
        # the displayed offset: `last_offset` is rounded to a tenth for reading,
        # and comparing it would quietly stretch the documented two seconds to
        # 2.05. A verdict must not inherit a display's precision.
        "complete": (
            ride.scheduled_end_ms is not None
            and window[-1].ts_ms >= ride.scheduled_end_ms - COMPLETE_TOLERANCE_MS
        ),
    })
    return out


# ------------------------------------------------------------------- metrics

def _seconds(millis):
    return round(millis / 1000.0, 1)


def _span(beats, start_ms, end_ms):
    """Beats in `[start, end)`, or `[start, ...)` for an open-ended span."""
    return [
        beat for beat in beats
        if beat.ts_ms >= start_ms and (end_ms is None or beat.ts_ms < end_ms)
    ]


def weighted_stream(window, ride_end_ms):
    """Per-beat wall-clock weights for a ride, computed ONCE over the whole of it.

    `(ts_ms, bpm, dur_ms)` per valid-HR beat, where `dur_ms` is the span that
    beat occupies before the next one — clipped so that it cannot run past
    `ride_end_ms`.

    **Weighting before slicing is the whole point.** `hr._durations_s` reads each
    beat's weight off the NEXT beat — its timestamp delta, capped by that beat's
    own RR when it is gap-flagged or implausibly far — and invents a median
    "tail" for the last beat it is given. Slicing the ride into segments first
    and weighting each slice separately would hand every segment's final beat
    that invented tail instead of the capped truth: a beat at 0:09 followed by a
    gap-flagged beat at 0:15, with a boundary at 0:10, would be paid a median
    weight in place of the one-RR cap the dropout earns it. Computing over the
    full stream and partitioning afterwards leaves exactly one invented tail per
    ride — the real last beat's, which has no successor anywhere.

    **That one invented tail is why `ride_end_ms` is not optional.** It is a
    guess about time after the last beat, and left alone it overruns the end of
    the ride: with a 10-second segment and beats at 0:08 and 0:09.5, the final
    beat is handed 1.5 s and spans to 0:11, of which the second half falls in no
    segment at all and would vanish out of the per-segment totals — quietly
    turning a 50 bpm average into 55. The ride is `[anchor, scheduled_end)` and
    time past that was never ridden to plan, so the clip happens here, once,
    where the weight is minted. The partition is then exhaustive **by
    construction**: every beat's span lies inside the ride, the segments tile
    the ride, so the per-segment seconds sum to the stream's exactly rather than
    approximately.

    Valid-HR beats only, filtered before weighting, so these are `hr.hr_summary`'s
    weights over the same window with exactly that one difference: a ride knows
    when it ended and a beat list does not, so `hr_summary` pays the final tail
    in full and this does not. On a capture whose last beat's tail fits inside
    the schedule the two agree exactly.
    """
    valid = [b for b in window if b.hr_bpm > 0]
    if not valid:
        return []
    durations = _durations_s(
        np.array([b.ts_ms for b in valid], dtype=float),
        np.array([b.rr_ms for b in valid], dtype=float),
        np.array([b.is_gap for b in valid], dtype=bool),
    )
    return [
        (
            float(b.ts_ms),
            b.hr_bpm,
            max(0.0, min(float(seconds) * MS_PER_SEC, float(ride_end_ms) - b.ts_ms)),
        )
        for b, seconds in zip(valid, durations)
    ]


def _band_seconds(stream, segment, anchor_ms):
    """Wall-clock seconds one segment spent in / below / above its band.

    **A beat whose span straddles a boundary has its weight split at it**: the
    milliseconds before the boundary count toward the segment that was running
    then, the rest toward the one arriving, each judged against its own band. The
    alternative — giving the whole weight to whichever segment held the beat's
    timestamp — would let one beat's tail inflate a segment past its own length
    and leave the next one short, so the per-segment seconds would no longer sum
    to the ride. Clipping keeps them a partition of it, which is what makes the
    work aggregate addable from the parts.

    Discrete facts (the beat count, peak, min, the first and last reading, time
    to band) deliberately do NOT split: those belong to the segment holding the
    beat's own timestamp, which is the client's membership rule
    (`GuidanceTimeline.segmentAt`). Time is attributed where it was spent; a
    beat is where it happened.

    Returns `(inside, below, above, covered, avg_hr)`, the first three None when
    the segment has no band to be inside or outside of.
    """
    start_ms = float(anchor_ms + segment.start_sec * MS_PER_SEC)
    end_ms = float(anchor_ms + segment.end_sec * MS_PER_SEC)
    inside = below = above = covered = hr_weight = 0.0

    for ts_ms, bpm, dur_ms in stream:
        overlap_ms = min(ts_ms + dur_ms, end_ms) - max(ts_ms, start_ms)
        if overlap_ms <= 0:
            continue
        seconds = overlap_ms / 1000.0
        covered += seconds
        hr_weight += bpm * seconds
        if segment.hr_min is not None and bpm < segment.hr_min:
            below += seconds
        elif segment.hr_max is not None and bpm > segment.hr_max:
            above += seconds
        else:
            inside += seconds

    avg_hr = None if covered <= 0 else hr_weight / covered
    if not segment.has_band:
        return None, None, None, covered, avg_hr
    return inside, below, above, covered, avg_hr


def _segment_metrics(beats, stream, segment, anchor_ms, extended_sec):
    """Everything one recorded segment is worth reporting.

    This is where the bout metrics live for a guided ride: the recorded segments
    ARE its bouts, so nothing is matched against detected ones and no match
    confidence has to be reported. (`bouts` elsewhere in a report stays the
    signal-detected shape — the two are independent readings of the same beats,
    and disagreement between them is information, not an error.)

    `beats` is the ride's beat list for the discrete facts; `stream` is the same
    ride pre-weighted by `weighted_stream` for the timed ones. See
    `_band_seconds` for why the two are separate.
    """
    span = _span(beats, anchor_ms + segment.start_sec * MS_PER_SEC,
                 anchor_ms + segment.end_sec * MS_PER_SEC)
    valid = [b for b in span if b.hr_bpm > 0]
    inside, below, above, covered, avg_hr = _band_seconds(stream, segment, anchor_ms)
    flags = classify(span) if len(span) >= 2 else None

    out = {
        "index": segment.index,
        "role": segment.role,
        "start_offset_s": float(segment.start_sec),
        "end_offset_s": float(segment.end_sec),
        "duration_s": float(segment.duration_sec),
        "band": segment.band,
        "beats": len(span),
        "covered_s": round(covered, 1),
        "avg_hr": _round_or_none(avg_hr, 1),
        "peak_hr": max((b.hr_bpm for b in valid), default=None),
        "min_hr": min((b.hr_bpm for b in valid), default=None),
        "hr_at_start": valid[0].hr_bpm if valid else None,
        "hr_at_end": valid[-1].hr_bpm if valid else None,
        "seconds_in_band": _round_or_none(inside, 1),
        "seconds_below_band": _round_or_none(below, 1),
        "seconds_above_band": _round_or_none(above, 1),
        "fraction_in_band": (
            None if inside is None or covered <= 0 else round(inside / covered, 3)
        ),
        "time_to_band_s": _time_to_band(span, segment, anchor_ms),
        "rr_coverage": None if len(span) < 2 else round(rr_coverage(span), 3),
        "artifact_frac": None if flags is None else round(flags.artifact_fraction, 3),
        "rmssd_ms": _round_or_none(
            None if flags is None else rmssd_pooled(contiguous_rr_runs(span, flags)), 1
        ),
    }
    # Identity fields follow the wire's sparse rule — a floor-only segment says
    # so by having no `hr_max`, exactly as its recorded row does.
    if segment.label:
        out["label"] = segment.label
    if segment.hr_min is not None:
        out["hr_min"] = segment.hr_min
    if segment.hr_max is not None:
        out["hr_max"] = segment.hr_max
    if extended_sec:
        out["extended_sec"] = extended_sec
    return out


def _time_to_band(beats, segment, anchor_ms):
    """Seconds from the segment's start to the first reading inside its band.

    The generalization of "time to target HR": null when the segment has no
    band, and null when the band was never reached at all — which is a different
    fact from reaching it late, and the fraction in band tells them apart.
    """
    if not segment.has_band:
        return None
    start_ms = anchor_ms + segment.start_sec * MS_PER_SEC
    for beat in beats:
        if beat.hr_bpm > 0 and segment.holds(beat.hr_bpm):
            return _seconds(beat.ts_ms - start_ms)
    return None


def _work_aggregate(segment_reports, ride):
    """The work spans summed — the ride as distinct from the preparation.

    Aggregated from the per-segment numbers rather than recomputed over their
    concatenated beats, so the total a reader adds up by hand is the total this
    reports. The average is weighted by each segment's covered time, which makes
    it the same duration-weighted mean the segments carry.

    A segmentless ride is entirely work: there is no timeline to tell one part
    from another, and the whole window is what the rider rode.
    """
    work = [s for s in segment_reports if s["role"] == WORK]
    covered = sum(s["covered_s"] for s in work)
    weighted = [(s["avg_hr"], s["covered_s"]) for s in work if s["avg_hr"] is not None]
    weight_total = sum(w for _, w in weighted)
    peaks = [s["peak_hr"] for s in work if s["peak_hr"] is not None]
    mins = [s["min_hr"] for s in work if s["min_hr"] is not None]

    banded = [s for s in work if s["seconds_in_band"] is not None]
    in_band = sum(s["seconds_in_band"] for s in banded)
    banded_covered = sum(s["covered_s"] for s in banded)

    return {
        # Empty for a segmentless ride: its one span is synthetic and appears in
        # no `segments` list, so naming an index would point at nothing.
        "segment_indexes": [] if ride.is_segmentless else [s["index"] for s in work],
        "segmentless": ride.is_segmentless,
        # Null for a segmentless ride, with the rest of its planned facts: there
        # is no recorded length to schedule against, and its one span is a
        # synthetic stand-in for the window rather than a plan. Reporting the
        # window's own length here would read as a plan that was never written.
        "scheduled_s": (
            None if ride.is_segmentless else float(sum(s["duration_s"] for s in work))
        ),
        "beats": sum(s["beats"] for s in work),
        "covered_s": round(covered, 1),
        "avg_hr": (
            None if not weight_total
            else round(sum(hr * w for hr, w in weighted) / weight_total, 1)
        ),
        "peak_hr": max(peaks) if peaks else None,
        "min_hr": min(mins) if mins else None,
        "seconds_in_band": None if not banded else round(in_band, 1),
        "seconds_below_band": (
            None if not banded else round(sum(s["seconds_below_band"] for s in banded), 1)
        ),
        "seconds_above_band": (
            None if not banded else round(sum(s["seconds_above_band"] for s in banded), 1)
        ),
        "fraction_in_band": (
            None if not banded or banded_covered <= 0
            else round(in_band / banded_covered, 3)
        ),
    }


def _whole_span_sec(window, anchor_ms):
    """Seconds from the anchor to just past the last beat, for a ride whose
    record carries no length (segmentless: the plan's `target_duration_min`
    lives in `coach.db`, which this side deliberately never reads)."""
    if not window:
        return 0
    return (window[-1].ts_ms - anchor_ms) // MS_PER_SEC + 1


def _round_or_none(value, digits):
    return None if value is None else round(float(value), digits)


# ------------------------------------------------------------------ structure

def guided_structure(beats, ride, *, intent_supplied=False):
    """The whole guided reading of one session: authority, coverage, metrics.

    `beats` is the SESSION's beats, not the window's — the coverage block has to
    be able to say how much of the capture fell outside the ride.

    A segmentless ride still gets a structure block: the anchor is real, the
    work span is the whole window, and only the per-segment detail is missing
    because no band was ever drawn.
    """
    window = ride_beats(beats, ride)
    # The ride's own last instant, computed once and used three times: to clip
    # the beat weights, to bound the synthetic span a segmentless ride is
    # measured over, and (for a ride with segments) as the scheduled end the
    # timeline already tiles up to. One number, so the weights and the segments
    # cannot disagree about where the ride stopped.
    total_sec = ride.total_sec
    if total_sec is None:
        total_sec = _whole_span_sec(window, ride.anchor_ms)
    ride_end_ms = ride.anchor_ms + total_sec * MS_PER_SEC
    # Weighted once, for the whole ride, before anything is partitioned — see
    # `weighted_stream`.
    stream = weighted_stream(window, ride_end_ms)
    if ride.is_segmentless:
        # One synthetic span covering the analysed window. It carries no band,
        # so nothing claims a target that was never set — and its end is past
        # the last beat rather than on it, because the span is half-open and a
        # ride's final beat is inside the ride.
        whole = GuidedSegment(
            index=0, label=None, role=WORK, start_sec=0, end_sec=total_sec,
            hr_min=None, hr_max=None,
        )
        segment_reports = [_segment_metrics(window, stream, whole, ride.anchor_ms, 0)]
        segments_out = []
    else:
        segment_reports = [
            _segment_metrics(
                window, stream, segment, ride.anchor_ms,
                ride.extension_sec if segment.index == ride.extended_index else 0,
            )
            for segment in ride.segments
        ]
        segments_out = segment_reports

    out = {
        "guided": True,
        "source": "guide_events",
        "exercise_key": ride.exercise_key,
        "date": ride.date,
        "anchor_ms": ride.anchor_ms,
        "planned_total_sec": ride.planned_total_sec,
        "extension_sec": ride.extension_sec,
        "total_sec": ride.total_sec,
        "extends": ride.extends,
        "extended_index": ride.extended_index,
        "discarded_starts": ride.discarded_starts,
        "discarded_extends": ride.discarded_extends,
        "segmentless": ride.is_segmentless,
        "coverage": _coverage(beats, window, ride),
        "segments": segments_out,
        "work": _work_aggregate(segment_reports, ride),
    }
    if intent_supplied:
        # Announced rather than silent: a caller who passed a planned structure
        # to a ride whose own timeline was recorded needs to know which of the
        # two the numbers came from.
        out["supplied_intent_ignored"] = True
    return out


def guided_brief(ride, t0_ms):
    """The recorded structure as boundaries on a caller's own clock.

    What a time series needs and a report does not: where each segment starts
    and ends *in the series' offsets*, with the band it asked for, so the shape
    can be drawn over the curve without arithmetic. No metrics — those live in
    `guided_structure`, and computing them twice is how two surfaces come to
    disagree.
    """
    out = {
        "guided": True,
        "source": "guide_events",
        "exercise_key": ride.exercise_key,
        "date": ride.date,
        "anchor_ms": ride.anchor_ms,
        "anchor_offset_s": _seconds(ride.anchor_ms - t0_ms),
        "planned_total_sec": ride.planned_total_sec,
        "extension_sec": ride.extension_sec,
        "total_sec": ride.total_sec,
        "extends": ride.extends,
        "extended_index": ride.extended_index,
        "segmentless": ride.is_segmentless,
        "segments": [],
    }
    shift = _seconds(ride.anchor_ms - t0_ms)
    for segment in ride.segments:
        marker = {
            "index": segment.index,
            "role": segment.role,
            "band": segment.band,
            "start_offset_s": round(shift + segment.start_sec, 1),
            "end_offset_s": round(shift + segment.end_sec, 1),
            "duration_s": float(segment.duration_sec),
        }
        if segment.label:
            marker["label"] = segment.label
        if segment.hr_min is not None:
            marker["hr_min"] = segment.hr_min
        if segment.hr_max is not None:
            marker["hr_max"] = segment.hr_max
        out["segments"].append(marker)
    return out


def unguided_structure(intent=None):
    """The authority annotation for a session with no recorded timeline.

    The other half of the two-case model, and it is stated rather than left to
    be inferred: every result says whether its structure came from the record or
    from the caller, because a caller reading numbers months later cannot know.
    """
    out = {"guided": False}
    if intent is not None and intent.has_structure:
        out["source"] = "supplied_intent"
        out["intent"] = intent.to_dict()
    else:
        out["source"] = "none"
    return out
