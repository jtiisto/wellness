"""VO2/HIIT interval matching and per-bout summaries."""

from dataclasses import dataclass
from typing import Any

import numpy as np

from .hr import _durations_s, time_weighted_mean_hr
from .hrv import rmssd_pooled
from .intent import IntervalIntent
from .quality import classify, contiguous_rr_runs, rr_coverage


@dataclass(frozen=True)
class ExpectedBout:
    index: int
    kind: str
    start_ms: int
    end_ms: int

    @property
    def duration_s(self) -> float:
        return (self.end_ms - self.start_ms) / 1000.0


def summarize_vo2(beats, detected_bouts, intent: IntervalIntent, hrmax: int | None = None):
    """Summarize expected VO2 work/rest bouts against detected HR bouts."""
    if not beats:
        return {"error": "no beats"}

    expected = _expected_bouts(beats[0].ts_ms, intent)
    work = []
    rest = []
    used_detected = set()
    match_warnings = []

    detected_work = [b for b in detected_bouts if b.kind == "work"]
    detected_rest = [b for b in detected_bouts if b.kind == "rest"]

    for exp in [b for b in expected if b.kind == "work"]:
        match, confidence, overlap_s = _best_match(exp, detected_work)
        if match is not None:
            used_detected.add(id(match))
        if confidence == "none":
            match_warnings.append(f"work {exp.index} unmatched")
        elif confidence == "low":
            match_warnings.append(f"work {exp.index} low overlap")
        work.append(_work_summary(exp, match, confidence, overlap_s, beats, intent, hrmax))

    for exp in [b for b in expected if b.kind == "rest"]:
        match, confidence, overlap_s = _best_match(exp, detected_rest)
        if match is not None:
            used_detected.add(id(match))
        if confidence == "none":
            match_warnings.append(f"rest {exp.index} unmatched")
        elif confidence == "low":
            match_warnings.append(f"rest {exp.index} low overlap")
        rest.append(_rest_summary(exp, match, confidence, overlap_s, beats))

    detected_count_mismatch = bool(expected) and (
        len(detected_work) != len([b for b in expected if b.kind == "work"])
        or len(detected_rest) < len([b for b in expected if b.kind == "rest"])
    )

    unmatched_detected = [
        _detected_dict(b, beats[0].ts_ms)
        for b in detected_bouts
        if id(b) not in used_detected
    ]

    return {
        "intent": intent.to_dict(),
        "expected_structure_available": intent.has_structure,
        "match_strategy": "maximum_time_overlap",
        "work_bouts": work,
        "rest_bouts": rest,
        "unmatched_detected_bouts": unmatched_detected,
        "flags": {
            "intent_missing": not intent.has_structure,
            "detected_intent_mismatch": detected_count_mismatch,
            "low_confidence_matches": any(w for w in match_warnings if "low" in w),
            "unmatched_expected_bouts": any(w for w in match_warnings if "unmatched" in w),
        },
        "warnings": match_warnings,
    }


def _expected_bouts(start_ms: int, intent: IntervalIntent) -> list[ExpectedBout]:
    if not intent.has_structure:
        return []
    expected = []
    cursor = start_ms
    for idx in range(1, intent.rounds + 1):
        work_end = cursor + intent.work_duration_s * 1000
        expected.append(ExpectedBout(index=idx, kind="work", start_ms=cursor, end_ms=work_end))
        cursor = work_end
        if idx < intent.rounds:
            rest_end = cursor + intent.rest_duration_s * 1000
            expected.append(ExpectedBout(index=idx, kind="rest", start_ms=cursor, end_ms=rest_end))
            cursor = rest_end
    return expected


def _best_match(expected: ExpectedBout, candidates):
    if not candidates:
        return None, "none", 0.0
    overlaps = [(_overlap_ms(expected.start_ms, expected.end_ms, c.start_ms, c.end_ms), c)
                for c in candidates]
    overlap_ms, match = max(overlaps, key=lambda item: item[0])
    if overlap_ms <= 0:
        return None, "none", 0.0
    frac_expected = overlap_ms / max(1, expected.end_ms - expected.start_ms)
    frac_detected = overlap_ms / max(1, match.end_ms - match.start_ms)
    confidence = "high" if min(frac_expected, frac_detected) >= 0.60 else "low"
    return match, confidence, round(overlap_ms / 1000.0, 1)


def _overlap_ms(a_start, a_end, b_start, b_end):
    return max(0, min(a_end, b_end) - max(a_start, b_start))


def _work_summary(expected, detected, confidence, overlap_s, beats, intent, hrmax):
    metric_start, metric_end = _metric_window(expected, detected)
    seg = _segment(beats, metric_start, metric_end)
    return {
        "index": expected.index,
        "expected": _expected_dict(expected, beats[0].ts_ms),
        "detected": None if detected is None else _detected_dict(detected, beats[0].ts_ms),
        "match_confidence": confidence,
        "overlap_s": overlap_s,
        "timing_error_s": None if detected is None else round((detected.start_ms - expected.start_ms) / 1000.0, 1),
        "duration_error_s": None if detected is None else round(detected.duration_s - expected.duration_s, 1),
        "metrics_window": _range_dict(metric_start, metric_end, beats[0].ts_ms),
        "avg_hr": _round_or_none(time_weighted_mean_hr(seg), 1),
        "peak_hr": _max_hr(seg),
        "time_to_target_hr_min_s": _time_to_threshold(seg, intent.target_hr_min),
        "time_to_90pct_hrmax_s": _time_to_threshold(seg, _pct_hr(hrmax, 0.90)),
        "seconds_at_or_above_target_hr_min": _seconds_at_or_above(seg, intent.target_hr_min),
        "seconds_at_or_above_90pct_hrmax": _seconds_at_or_above(seg, _pct_hr(hrmax, 0.90)),
        "seconds_at_or_above_95pct_hrmax": _seconds_at_or_above(seg, _pct_hr(hrmax, 0.95)),
        "hr_at_work_end": _hr_at_or_before(seg, metric_end),
    }


def _rest_summary(expected, detected, confidence, overlap_s, beats):
    metric_start, metric_end = _metric_window(expected, detected)
    seg = _segment(beats, metric_start, metric_end)
    flags = classify(seg) if len(seg) >= 2 else None
    rmssd_ms = None if flags is None else rmssd_pooled(contiguous_rr_runs(seg, flags))
    return {
        "index": expected.index,
        "expected": _expected_dict(expected, beats[0].ts_ms),
        "detected": None if detected is None else _detected_dict(detected, beats[0].ts_ms),
        "match_confidence": confidence,
        "overlap_s": overlap_s,
        "metrics_window": _range_dict(metric_start, metric_end, beats[0].ts_ms),
        "hr_drop_30_s": _hr_drop(seg, 30),
        "hr_drop_60_s": _hr_drop(seg, 60),
        "hr_drop_120_s": _hr_drop(seg, 120),
        "hr_drop_180_s": _hr_drop(seg, 180),
        "minimum_hr": _min_hr(seg),
        "rr_coverage": None if len(seg) < 2 else round(rr_coverage(seg), 3),
        "artifact_frac": None if flags is None else round(flags.artifact_fraction, 3),
        "rmssd_ms": _round_or_none(rmssd_ms, 1),
    }


def _metric_window(expected, detected):
    if detected is None:
        return expected.start_ms, expected.end_ms
    return max(expected.start_ms, detected.start_ms), min(expected.end_ms, detected.end_ms)


def _segment(beats, start_ms, end_ms):
    return [b for b in beats if start_ms <= b.ts_ms <= end_ms]


def _expected_dict(expected: ExpectedBout, t0_ms: int):
    return {
        "start_offset_s": round((expected.start_ms - t0_ms) / 1000.0, 1),
        "end_offset_s": round((expected.end_ms - t0_ms) / 1000.0, 1),
        "duration_s": round(expected.duration_s, 1),
    }


def _detected_dict(bout, t0_ms: int):
    return {
        "kind": bout.kind,
        "start_offset_s": round((bout.start_ms - t0_ms) / 1000.0, 1),
        "end_offset_s": round((bout.end_ms - t0_ms) / 1000.0, 1),
        "duration_s": round(bout.duration_s, 1),
        "hr_avg": bout.hr_avg,
        "hr_peak": bout.hr_peak,
    }


def _range_dict(start_ms, end_ms, t0_ms):
    return {
        "start_offset_s": round((start_ms - t0_ms) / 1000.0, 1),
        "end_offset_s": round((end_ms - t0_ms) / 1000.0, 1),
        "duration_s": round(max(0, end_ms - start_ms) / 1000.0, 1),
    }


def _pct_hr(hrmax, pct):
    return None if hrmax is None else int(round(hrmax * pct))


def _durations_for(beats):
    if not beats:
        return np.array([])
    ts = np.array([b.ts_ms for b in beats], dtype=float)
    rr = np.array([b.rr_ms for b in beats], dtype=float)
    gaps = np.array([b.is_gap for b in beats], dtype=bool)
    return _durations_s(ts, rr, gaps)


def _seconds_at_or_above(beats, threshold):
    if threshold is None or not beats:
        return None
    durations = _durations_for(beats)
    total = sum(d for b, d in zip(beats, durations) if b.hr_bpm >= threshold)
    return round(float(total), 1)


def _time_to_threshold(beats, threshold):
    if threshold is None or not beats:
        return None
    t0 = beats[0].ts_ms
    for beat in beats:
        if beat.hr_bpm >= threshold:
            return round((beat.ts_ms - t0) / 1000.0, 1)
    return None


def _hr_at_or_before(beats, ts_ms):
    prior = [b.hr_bpm for b in beats if b.hr_bpm > 0 and b.ts_ms <= ts_ms]
    return prior[-1] if prior else None


def _hr_drop(beats, seconds):
    if not beats:
        return None
    start_hr = beats[0].hr_bpm if beats[0].hr_bpm > 0 else None
    if start_hr is None:
        return None
    target_ts = beats[0].ts_ms + seconds * 1000
    hr = _hr_at_or_before(beats, target_ts)
    return None if hr is None else start_hr - hr


def _max_hr(beats):
    values = [b.hr_bpm for b in beats if b.hr_bpm > 0]
    return max(values) if values else None


def _min_hr(beats):
    values = [b.hr_bpm for b in beats if b.hr_bpm > 0]
    return min(values) if values else None


def _round_or_none(value, digits):
    return None if value is None else round(float(value), digits)
