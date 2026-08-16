"""Tool implementations for the HR MCP server.

These functions are deliberately independent of the FastMCP decorators so they
can be unit-tested without launching an MCP runtime; `server.py` is a thin
registration layer over them.

The metrics themselves are never computed here — every number comes from the
shared `hr_analysis` package, so an MCP report and a `python -m hr_analysis`
report of the same session agree by construction.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from hr_analysis.hr import time_weighted_mean_hr
from hr_analysis.intent import parse_intent
from hr_analysis.pipeline import analyze
from hr_analysis.quality import Beat, classify, rr_coverage
from hr_analysis.segment import detect_bouts
from hr_analysis.vo2 import summarize_vo2

from .database import DatabaseManager, SessionSummary


def _parse_ms(value: int | str | None) -> int | None:
    """Accept epoch milliseconds or an ISO-8601 instant; return epoch ms."""
    if value is None:
        return None
    if isinstance(value, int):
        return value
    text = value.strip()
    if not text:
        return None
    if text.isdigit():
        return int(text)
    normalized = text.replace("Z", "+00:00")
    dt = datetime.fromisoformat(normalized)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp() * 1000)


def _session_dict(session: SessionSummary) -> dict[str, Any]:
    return {
        "session_id": session.session_id,
        "device_id": session.device_id,
        "start_ms": session.start_ms,
        "end_ms": session.end_ms,
        "duration_s": session.duration_s,
        "beats": session.beats,
        "workout_date": session.workout_date,
    }


def crop_beats(
    beats: list[Beat],
    start_ms: int | str | None = None,
    end_ms: int | str | None = None,
) -> list[Beat]:
    """Restrict a beat list to an inclusive [start, end] window."""
    crop_start = _parse_ms(start_ms)
    crop_end = _parse_ms(end_ms)
    if crop_start is not None and crop_end is not None and crop_start > crop_end:
        raise ValueError("start_ms/start_time must be <= end_ms/end_time")
    return [
        beat for beat in beats
        if (crop_start is None or beat.ts_ms >= crop_start)
        and (crop_end is None or beat.ts_ms <= crop_end)
    ]


def _load_window(
    db: DatabaseManager,
    session_id: str,
    start_ms: int | str | None,
    end_ms: int | str | None,
) -> tuple[list[Beat], list[Beat]]:
    """(whole capture, cropped analysis window) for one session.

    The shared preamble of every per-session tool, and the only place they read
    beats: `get_vo2_summary` composes a report, a VO2 summary and a time series,
    all of which want the same beats, and the pulse-bridge original loaded the
    session from SQLite three times per call to build them.
    """
    raw_beats = db.load_beats(session_id)
    if not raw_beats:
        raise ValueError(f"No data for session {session_id}")
    beats = crop_beats(raw_beats, start_ms=start_ms, end_ms=end_ms)
    if not beats:
        raise ValueError("Crop window contains no beats")
    return raw_beats, beats


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


def list_sessions(
    db: DatabaseManager,
    start_ms: int | str | None = None,
    end_ms: int | str | None = None,
    limit: int | None = None,
) -> list[dict[str, Any]]:
    """Return recent captured sessions, newest first."""
    sessions = db.list_sessions(
        start_ms=_parse_ms(start_ms),
        end_ms=_parse_ms(end_ms),
        limit=limit,
    )
    return [_session_dict(session) for session in sessions]


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


def _build_report(
    db: DatabaseManager,
    session_id: str,
    raw_beats: list[Beat],
    beats: list[Beat],
    hrmax: int | None,
    cropped: bool,
    include_windows: bool,
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
            "manual" if cropped else "full_capture",
            raw_start_ms,
            raw_end_ms,
        ),
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
    start_ms: int | str | None = None,
    end_ms: int | str | None = None,
    include_windows: bool = False,
) -> dict[str, Any]:
    """Analyze one session, optionally cropped to an explicit time window."""
    raw_beats, beats = _load_window(db, session_id, start_ms, end_ms)
    return _build_report(
        db,
        session_id,
        raw_beats,
        beats,
        hrmax,
        cropped=start_ms is not None or end_ms is not None,
        include_windows=include_windows,
    )


def get_latest_session_report(
    db: DatabaseManager,
    hrmax: int | None = None,
    include_windows: bool = False,
) -> dict[str, Any]:
    """Analyze the most recently started session."""
    sessions = db.list_sessions(limit=1)
    if not sessions:
        raise ValueError("No sessions found")
    return get_session_report(
        db, sessions[0].session_id, hrmax=hrmax, include_windows=include_windows,
    )


def _build_timeseries(
    db: DatabaseManager,
    session_id: str,
    beats: list[Beat],
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
    start_ms: int | str | None = None,
    end_ms: int | str | None = None,
    include_quality: bool = False,
) -> dict[str, Any]:
    """Return compact HR buckets (+ set markers) for one session."""
    _check_resolution(resolution_s)
    _, beats = _load_window(db, session_id, start_ms, end_ms)
    return _build_timeseries(
        db, session_id, beats, resolution_s, include_quality=include_quality,
    )


def get_vo2_summary(
    db: DatabaseManager,
    session_id: str,
    intent: dict[str, Any] | None = None,
    hrmax: int | None = None,
    start_ms: int | str | None = None,
    end_ms: int | str | None = None,
    resolution_s: int = 5,
) -> dict[str, Any]:
    """Return expected/detected VO2 interval summary plus fallback time series."""
    interval_intent = parse_intent(intent)
    _check_resolution(resolution_s)
    raw_beats, beats = _load_window(db, session_id, start_ms, end_ms)

    detected = detect_bouts(beats)
    base_report = _build_report(
        db,
        session_id,
        raw_beats,
        beats,
        hrmax,
        cropped=start_ms is not None or end_ms is not None,
        include_windows=False,
    )
    vo2 = summarize_vo2(beats, detected, interval_intent, hrmax=hrmax)

    return {
        "session_id": session_id,
        "analysis_window": base_report["analysis_window"],
        "quality": base_report["quality"],
        "hr": base_report["hr"],
        "vo2": vo2,
        # The embedded fallback series is always lean — an hour at 5s in full
        # form is what blew the tool result limit; get_aligned_timeseries with
        # include_quality=true is the escape hatch for the RR detail.
        "timeseries": _build_timeseries(
            db, session_id, beats, resolution_s, include_quality=False,
        ),
    }
