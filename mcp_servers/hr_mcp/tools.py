"""Tool implementations for the HR MCP server.

These functions are deliberately independent of the FastMCP decorators so they
can be unit-tested without launching an MCP runtime; `server.py` is a thin
registration layer over them.

The metrics themselves are never computed here — every number comes from the
shared `hr_analysis` package, so an MCP report and a `python -m hr_analysis`
report of the same session agree by construction.

**Retrieval is by session id, always** (see `hr_analysis.db`). No tool here
takes a wall-clock window: what a session covers is the session's own business,
and a *guided* session's analysable extent is derived from the timeline the
guide recorded rather than supplied by a caller. Every result therefore says
where its structure came from — `structure.guided`, plus the anchor and the
extends when it is true — because a caller reading numbers later cannot know.
"""

from __future__ import annotations

from typing import Any

from hr_analysis import guided
from hr_analysis.guided import GuidedExerciseRequired
from hr_analysis.hr import time_weighted_mean_hr
from hr_analysis.intent import parse_intent
from hr_analysis.pipeline import analyze
from hr_analysis.quality import Beat, classify, rr_coverage
from hr_analysis.segment import detect_bouts
from hr_analysis.vo2 import summarize_vo2

from .database import DatabaseManager, SessionSummary


def _session_dict(session: SessionSummary, rides: list) -> dict[str, Any]:
    """One listing row, decorated with what the guide recorded for it.

    `guided` is always present — it is the answer to the two-case question, and
    a caller must not have to infer it from a missing key.
    """
    row = {
        "session_id": session.session_id,
        "device_id": session.device_id,
        "start_ms": session.start_ms,
        "end_ms": session.end_ms,
        "duration_s": session.duration_s,
        "beats": session.beats,
        "workout_date": session.workout_date,
        "guided": bool(rides),
    }
    if rides:
        row["guided_exercises"] = [
            ride.brief(session_start_ms=session.start_ms) for ride in rides
        ]
    return row


def _window_dict(
    beats: list[Beat],
    source: str,
    raw_start_ms: int,
    raw_end_ms: int,
) -> dict[str, Any]:
    start_ms = beats[0].ts_ms
    end_ms = beats[-1].ts_ms
    return {
        "source": source,
        "start_ms": start_ms,
        "end_ms": end_ms,
        "duration_s": round((end_ms - start_ms) / 1000.0, 1),
        "trimmed_before_s": round(max(0, start_ms - raw_start_ms) / 1000.0, 1),
        "trimmed_after_s": round(max(0, raw_end_ms - end_ms) / 1000.0, 1),
    }


def _check_resolution(resolution_s: int) -> None:
    """Reject a nonsense bucket width before any analysis work is done."""
    if resolution_s < 1:
        raise ValueError("resolution_s must be at least 1")


def _guided_choice(session_id: str, exc: GuidedExerciseRequired) -> dict[str, Any]:
    """The answer to "which of these two rides did you mean?".

    Returned rather than raised, and returned instead of a report: one capture
    can span a VO2 session and a Zone 2 ride, and analysing the first because it
    came first would answer a question nobody asked. The caller re-calls with
    `exercise_key`.
    """
    return {
        "session_id": session_id,
        "needs_exercise_key": True,
        "guided_exercises": [ride.brief() for ride in exc.rides],
        "message": str(exc),
    }


def _load_session(
    db: DatabaseManager,
    session_id: str,
    exercise_key: str | None,
    whole_capture: bool = False,
) -> tuple[list[Beat], list[Beat], Any]:
    """(whole capture, analysed window, chosen ride or None) for one session.

    The shared preamble of every per-session tool, and the only place they read
    beats: `get_vo2_summary` composes a report, a structure summary and a time
    series, all of which want the same beats.

    A guided session narrows to `[anchor, anchor + total)` — the ride is what
    the numbers are about, and beats logged while clipping in are not part of
    it. An unguided one is analysed whole, exactly as before.

    Raises `GuidedExerciseRequired` when the session holds several rides and no
    key chose one; the public tools turn that into the ask.
    """
    raw_beats = db.load_beats(session_id)
    if not raw_beats:
        raise ValueError(f"No data for session {session_id}")

    rides = guided.guided_rides(db.load_guide_events(session_id))
    ride = guided.select_ride(rides, exercise_key)
    if ride is None or whole_capture:
        return raw_beats, raw_beats, ride

    window = guided.ride_beats(raw_beats, ride)
    if not window:
        raise ValueError(
            f"Session {session_id} has no beats inside the guided window for "
            f"'{ride.exercise_key}' (anchored at {ride.anchor_ms}). The capture "
            f"and the guide do not overlap."
        )
    return raw_beats, window, ride


def list_sessions(
    db: DatabaseManager,
    limit: int | None = None,
) -> list[dict[str, Any]]:
    """Return recent captured sessions, newest first, with their guided-ness."""
    sessions = db.list_sessions(limit=limit)
    events = db.guide_events_by_session([s.session_id for s in sessions])
    return [
        _session_dict(session, guided.guided_rides(events.get(session.session_id, [])))
        for session in sessions
    ]


def _set_event_markers(
    db: DatabaseManager,
    session_id: str,
    t0_ms: int,
    start_ms: int,
    end_ms: int,
) -> list[dict[str, Any]]:
    """The exercise ground truth next to the HR curve: set ticks and checklist
    toggles as offsets from the same t0 the rows/windows use, cropped to the
    analysis window. Wire style matches the sync protocol — optional fields
    (set_num, item_key) are omitted, never null."""
    markers = []
    for event in db.load_set_events(session_id):
        ts = event["client_timestamp_ms"]
        if ts < start_ms or ts > end_ms:
            continue
        marker: dict[str, Any] = {
            "offset_s": round((ts - t0_ms) / 1000.0, 1),
            "exercise_key": event["exercise_key"],
            "action": event["action"],
        }
        if event["set_num"] is not None:
            marker["set_num"] = event["set_num"]
        if event["item_key"] is not None:
            marker["item_key"] = event["item_key"]
        markers.append(marker)
    return markers


def _structure(
    raw_beats: list[Beat],
    ride: Any,
    intent: Any = None,
) -> dict[str, Any]:
    """The authority annotation, and for a guided session the whole reading.

    `source="unknown"` is what `parse_intent` returns for an absent intent, so
    it is also the test for "the caller supplied one" — and a guided session
    says so about ANY intent it was handed, target bounds included, because
    those are ignored in favour of the recorded bands just as the bout layout
    is.
    """
    supplied = intent is not None and intent.source != "unknown"
    if ride is None:
        return guided.unguided_structure(intent)
    return guided.guided_structure(raw_beats, ride, intent_supplied=supplied)


def _build_report(
    db: DatabaseManager,
    session_id: str,
    raw_beats: list[Beat],
    beats: list[Beat],
    ride: Any,
    hrmax: int | None,
    include_windows: bool,
    intent: Any = None,
) -> dict[str, Any]:
    report = analyze(beats, hrmax=hrmax)
    raw_start_ms = raw_beats[0].ts_ms
    raw_end_ms = raw_beats[-1].ts_ms
    device_ids, sensor_types = db.session_devices(session_id)

    return {
        "session_id": session_id,
        "device": {
            "device_ids": device_ids,
            "sensor_types": sensor_types,
        },
        "raw_capture_window": {
            "start_ms": raw_start_ms,
            "end_ms": raw_end_ms,
            "duration_s": round((raw_end_ms - raw_start_ms) / 1000.0, 1),
            "beats": len(raw_beats),
        },
        "analysis_window": _window_dict(
            beats,
            "guided" if ride is not None else "full_capture",
            raw_start_ms,
            raw_end_ms,
        ),
        # Where the structure came from — the recorded timeline, a supplied
        # plan, or nothing at all. Always present, never inferred.
        "structure": _structure(raw_beats, ride, intent),
        "quality": {
            "rr_coverage": report["rr_coverage"],
            "gaps": report["gaps"],
            "artifact_frac_overall": report["artifact_frac_overall"],
            "trusted_dfa_windows": report["alpha1"]["windows_trusted"],
            "total_dfa_windows": report["alpha1"]["windows_total"],
            "hr_usable": report["hr"]["avg"] is not None,
            "rr_hrv_usable": report["rmssd_ms"] is not None,
        },
        "hr": report["hr"],
        "rmssd_ms": report["rmssd_ms"],
        "alpha1": report["alpha1"],
        # Signal-DETECTED work/rest bouts, independent of any recorded
        # structure: on a guided session these are a second opinion about the
        # same beats, and disagreement with the recorded segments is
        # information rather than an error.
        "bouts": report["bouts"],
        # The per-window DFA/quality detail is ~85% of the report's bytes and
        # is summarized by the quality block's trusted/total counts — opt in
        # when the window-by-window evidence is actually needed.
        **({"windows": report["windows"]} if include_windows else {}),
        "set_events": _set_event_markers(
            db, session_id,
            t0_ms=beats[0].ts_ms,
            start_ms=beats[0].ts_ms,
            end_ms=beats[-1].ts_ms,
        ),
        "flags": {
            "analysis_window_uncertain": False,
            "rr_quality_insufficient": report["alpha1"]["windows_trusted"] == 0,
        },
    }


def get_session_report(
    db: DatabaseManager,
    session_id: str,
    hrmax: int | None = None,
    exercise_key: str | None = None,
    include_windows: bool = False,
) -> dict[str, Any]:
    """Analyze one session — over its guided timeline when it has one."""
    try:
        raw_beats, beats, ride = _load_session(db, session_id, exercise_key)
    except GuidedExerciseRequired as exc:
        return _guided_choice(session_id, exc)
    return _build_report(
        db, session_id, raw_beats, beats, ride, hrmax, include_windows,
    )


def get_latest_session_report(
    db: DatabaseManager,
    hrmax: int | None = None,
    exercise_key: str | None = None,
    include_windows: bool = False,
) -> dict[str, Any]:
    """Analyze the most recently started session."""
    sessions = db.list_sessions(limit=1)
    if not sessions:
        raise ValueError("No sessions found")
    return get_session_report(
        db, sessions[0].session_id, hrmax=hrmax, exercise_key=exercise_key,
        include_windows=include_windows,
    )


def _build_timeseries(
    db: DatabaseManager,
    session_id: str,
    beats: list[Beat],
    ride: Any,
    resolution_s: int,
    include_quality: bool,
) -> dict[str, Any]:
    t0 = beats[0].ts_ms
    bucket_ms = resolution_s * 1000

    # One pass, bucket index straight off the timestamp. Buckets with no beats
    # are simply absent from the output, as they were in the pulse-bridge
    # original — which re-scanned the whole beat list once per bucket, so a
    # 1-second resolution over an hour's capture cost ~13M comparisons.
    buckets: dict[int, list[Beat]] = {}
    for beat in beats:
        buckets.setdefault((beat.ts_ms - t0) // bucket_ms, []).append(beat)

    out_rows = []
    for index in sorted(buckets):
        bucket = buckets[index]
        start = t0 + index * bucket_ms
        flags = classify(bucket)
        valid_hr = [beat.hr_bpm for beat in bucket if beat.hr_bpm > 0]
        hr_weighted = time_weighted_mean_hr(bucket)
        if include_quality:
            # The full row, unchanged shape — the pre-2026-08 default.
            out_rows.append({
                "timestamp_ms": int(start),
                "offset_s": round((start - t0) / 1000.0, 1),
                "duration_s": resolution_s,
                "hr_mean": None if hr_weighted is None else round(hr_weighted, 1),
                "hr_max": max(valid_hr) if valid_hr else None,
                "rr_coverage": round(rr_coverage(bucket), 3),
                "artifact_frac": round(flags.artifact_fraction, 3),
                "gap": bool(flags.gap.any()),
                "beats": len(bucket),
            })
        else:
            # Lean row: the curve itself. The RR-quality detail is ~2/3 of
            # every row's bytes and pushed hour-long sessions past the tool
            # result limit. timestamp_ms/duration_s reconstruct from the
            # envelope (start_ms + offset_s, resolution_s); `gap` appears only
            # when true so an honest hole still shows.
            row: dict[str, Any] = {
                "offset_s": round((start - t0) / 1000.0, 1),
                "hr_mean": None if hr_weighted is None else round(hr_weighted, 1),
                "hr_max": max(valid_hr) if valid_hr else None,
            }
            if bool(flags.gap.any()):
                row["gap"] = True
            out_rows.append(row)

    return {
        "session_id": session_id,
        "resolution_s": resolution_s,
        "analysis_window": {
            "start_ms": beats[0].ts_ms,
            "end_ms": beats[-1].ts_ms,
            "duration_s": round((beats[-1].ts_ms - beats[0].ts_ms) / 1000.0, 1),
        },
        # The recorded structure as offsets on THIS series' own clock, so a
        # caller can lay the bands over the curve without arithmetic. Boundaries
        # only — the per-segment metrics live in get_session_report.
        "structure": (
            guided.unguided_structure() if ride is None
            else guided.guided_brief(ride, t0)
        ),
        "set_events": _set_event_markers(
            db, session_id,
            t0_ms=t0,
            start_ms=beats[0].ts_ms,
            end_ms=beats[-1].ts_ms,
        ),
        "rows": out_rows,
    }


def get_aligned_timeseries(
    db: DatabaseManager,
    session_id: str,
    resolution_s: int = 5,
    exercise_key: str | None = None,
    whole_capture: bool = False,
    include_quality: bool = False,
) -> dict[str, Any]:
    """Return compact HR buckets (+ set markers and guide boundaries)."""
    _check_resolution(resolution_s)
    try:
        _, beats, ride = _load_session(
            db, session_id, exercise_key, whole_capture=whole_capture,
        )
    except GuidedExerciseRequired as exc:
        return _guided_choice(session_id, exc)
    return _build_timeseries(
        db, session_id, beats, ride, resolution_s, include_quality=include_quality,
    )


def get_vo2_summary(
    db: DatabaseManager,
    session_id: str,
    intent: dict[str, Any] | None = None,
    hrmax: int | None = None,
    exercise_key: str | None = None,
    resolution_s: int = 5,
) -> dict[str, Any]:
    """Match a supplied interval plan to the beats — UNGUIDED sessions only.

    A guided session needs no matching: its structure is recorded, so this
    returns that reading in `structure` and omits `vo2` entirely. Any supplied
    intent is ignored and said to be ignored.
    """
    interval_intent = parse_intent(intent)
    _check_resolution(resolution_s)
    try:
        raw_beats, beats, ride = _load_session(db, session_id, exercise_key)
    except GuidedExerciseRequired as exc:
        return _guided_choice(session_id, exc)

    base_report = _build_report(
        db, session_id, raw_beats, beats, ride, hrmax,
        include_windows=False, intent=interval_intent,
    )

    result = {
        "session_id": session_id,
        "analysis_window": base_report["analysis_window"],
        "structure": base_report["structure"],
        "quality": base_report["quality"],
        "hr": base_report["hr"],
    }
    if ride is None:
        result["vo2"] = summarize_vo2(
            beats, detect_bouts(beats), interval_intent, hrmax=hrmax,
        )
    # The embedded fallback series is always lean — an hour at 5s in full
    # form is what blew the tool result limit; get_aligned_timeseries with
    # include_quality=true is the escape hatch for the RR detail.
    result["timeseries"] = _build_timeseries(
        db, session_id, beats, ride, resolution_s, include_quality=False,
    )
    return result
