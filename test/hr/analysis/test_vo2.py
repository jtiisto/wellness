"""Tests for VO2/HIIT interval matching and per-bout summaries.

All fixture data is synthetic: sessions are built from (HR, seconds) segments
with an exact RR so window boundaries land on beat timestamps.
"""

import numpy as np
import pytest

from hr_analysis.intent import IntervalIntent
from hr_analysis.quality import Beat
from hr_analysis.segment import Bout, detect_bouts
from hr_analysis.vo2 import (
    ExpectedBout,
    _durations_for,
    _expected_bouts,
    _hr_at_or_before,
    _hr_drop,
    _max_hr,
    _min_hr,
    _pct_hr,
    _round_or_none,
    _seconds_at_or_above,
    _time_to_threshold,
    summarize_vo2,
)

T0 = 1_893_456_000_000  # arbitrary epoch-ms anchor


def _session(segments, t0=T0):
    """[(hr_bpm, seconds), ...] -> Beat list.

    Segment k starts exactly at t0 + sum(earlier seconds), so a 60 s segment at
    an HR whose RR divides 60000 (160 bpm = 375 ms, 100 bpm = 600 ms) puts the
    first beat of the next segment on the wall-clock boundary.
    """
    beats = []
    start = t0
    for hr, secs in segments:
        rr = int(round(60000 / hr))
        for k in range(int(round(secs * 1000 / rr))):
            beats.append(Beat(ts_ms=start + k * rr, rr_ms=rr, hr_bpm=hr, is_gap=False))
        start += int(secs * 1000)
    return beats


def _bout(kind, start_ms, end_ms, hr_avg=150.0, hr_peak=165):
    return Bout(kind=kind, start_ms=start_ms, end_ms=end_ms, hr_avg=hr_avg, hr_peak=hr_peak)


def _intent(**kwargs):
    return IntervalIntent(source="coach", **kwargs)


def _three_round_session():
    """3 x (60 s @ 160 bpm work / 60 s @ 100 bpm rest), no trailing rest."""
    return _session([(160, 60), (100, 60), (160, 60), (100, 60), (160, 60)])


# --- Expected-bout construction ---

def test_expected_bouts_alternate_and_omit_trailing_rest():
    expected = _expected_bouts(0, _intent(rounds=3, work_duration_s=60, rest_duration_s=30))
    assert [b.kind for b in expected] == ["work", "rest", "work", "rest", "work"]
    assert [b.index for b in expected] == [1, 1, 2, 2, 3]
    assert [b.start_ms for b in expected] == [0, 60_000, 90_000, 150_000, 180_000]
    assert expected[-1].end_ms == 240_000


def test_expected_bouts_empty_without_structure():
    assert _expected_bouts(T0, IntervalIntent()) == []


def test_expected_bout_duration_property():
    assert ExpectedBout(index=1, kind="work", start_ms=1000, end_ms=241_000).duration_s == 240.0


# --- Top-level guards ---

def test_no_beats_returns_error():
    assert summarize_vo2([], [], _intent(rounds=4, work_duration_s=240,
                                         rest_duration_s=180)) == {"error": "no beats"}


def test_unknown_intent_produces_no_expected_bouts():
    beats = _three_round_session()
    detected = [_bout("work", T0, T0 + 59_625), _bout("rest", T0 + 60_000, T0 + 119_400)]
    out = summarize_vo2(beats, detected, IntervalIntent())

    assert out["work_bouts"] == []
    assert out["rest_bouts"] == []
    assert out["expected_structure_available"] is False
    assert out["flags"]["intent_missing"] is True
    # Without an intent nothing can mismatch — every detected bout is just listed
    assert out["flags"]["detected_intent_mismatch"] is False
    assert len(out["unmatched_detected_bouts"]) == 2
    assert out["warnings"] == []
    assert out["match_strategy"] == "maximum_time_overlap"
    assert out["intent"]["source"] == "unknown"


# --- Matching ---

def test_aligned_bouts_match_with_high_confidence():
    beats = _three_round_session()
    detected = [
        _bout("work", T0, T0 + 59_625, hr_avg=160.0, hr_peak=160),
        _bout("rest", T0 + 60_000, T0 + 119_400, hr_avg=100.0, hr_peak=100),
        _bout("work", T0 + 120_000, T0 + 179_625, hr_avg=160.0, hr_peak=160),
        _bout("rest", T0 + 180_000, T0 + 239_400, hr_avg=100.0, hr_peak=100),
        _bout("work", T0 + 240_000, T0 + 299_625, hr_avg=160.0, hr_peak=160),
    ]
    out = summarize_vo2(beats, detected, _intent(rounds=3, work_duration_s=60,
                                                 rest_duration_s=60))

    assert [b["index"] for b in out["work_bouts"]] == [1, 2, 3]
    assert [b["index"] for b in out["rest_bouts"]] == [1, 2]
    assert all(b["match_confidence"] == "high" for b in out["work_bouts"])
    assert all(b["match_confidence"] == "high" for b in out["rest_bouts"])
    assert out["warnings"] == []
    assert out["unmatched_detected_bouts"] == []
    assert out["flags"] == {
        "intent_missing": False,
        "detected_intent_mismatch": False,
        "low_confidence_matches": False,
        "unmatched_expected_bouts": False,
    }

    first = out["work_bouts"][0]
    assert first["expected"] == {"start_offset_s": 0.0, "end_offset_s": 60.0,
                                 "duration_s": 60.0}
    assert first["detected"]["kind"] == "work"
    assert first["detected"]["hr_avg"] == 160.0
    assert first["overlap_s"] == pytest.approx(59.6, abs=0.2)
    assert first["timing_error_s"] == 0.0
    assert first["duration_error_s"] == pytest.approx(-0.4, abs=0.2)
    assert first["avg_hr"] == pytest.approx(160.0, abs=1.0)
    assert first["peak_hr"] == 160


def test_late_detected_bout_reports_timing_error():
    beats = _three_round_session()
    # Detection lags the prescribed start by 12 s and runs 8 s short
    detected = [_bout("work", T0 + 12_000, T0 + 64_000, hr_avg=158.0, hr_peak=161)]
    out = summarize_vo2(beats, detected, _intent(rounds=1, work_duration_s=60,
                                                 rest_duration_s=60))
    work = out["work_bouts"][0]
    assert work["timing_error_s"] == 12.0
    assert work["duration_error_s"] == -8.0
    assert work["match_confidence"] == "high"  # 48 s of 60 s expected = 80%
    assert work["metrics_window"] == {"start_offset_s": 12.0, "end_offset_s": 60.0,
                                      "duration_s": 48.0}


def test_partial_overlap_is_low_confidence():
    beats = _three_round_session()
    detected = [
        _bout("work", T0 + 40_000, T0 + 100_000),    # 20 s of the 60 s work window
        _bout("rest", T0 + 100_000, T0 + 220_000),   # 20 s of the 60 s rest window
    ]
    out = summarize_vo2(beats, detected, _intent(rounds=2, work_duration_s=60,
                                                 rest_duration_s=60))
    work = out["work_bouts"][0]
    assert work["match_confidence"] == "low"
    assert work["overlap_s"] == 20.0
    assert out["rest_bouts"][0]["match_confidence"] == "low"
    assert "work 1 low overlap" in out["warnings"]
    assert "rest 1 low overlap" in out["warnings"]
    assert out["flags"]["low_confidence_matches"] is True


def test_no_detected_bouts_leaves_every_expected_unmatched():
    beats = _three_round_session()
    out = summarize_vo2(beats, [], _intent(rounds=2, work_duration_s=60,
                                           rest_duration_s=60))

    assert out["warnings"] == ["work 1 unmatched", "work 2 unmatched", "rest 1 unmatched"]
    assert out["flags"]["unmatched_expected_bouts"] is True
    assert out["flags"]["detected_intent_mismatch"] is True
    assert out["unmatched_detected_bouts"] == []
    for b in out["work_bouts"] + out["rest_bouts"]:
        assert b["match_confidence"] == "none"
        assert b["detected"] is None
        assert b["overlap_s"] == 0.0
    work = out["work_bouts"][0]
    assert work["timing_error_s"] is None
    assert work["duration_error_s"] is None
    # Falls back to the prescribed window so metrics are still reported
    assert work["metrics_window"]["duration_s"] == 60.0
    assert work["avg_hr"] == pytest.approx(160.0, abs=1.0)


def test_disjoint_detected_bout_is_not_matched():
    beats = _three_round_session()
    detected = [_bout("work", T0 + 240_000, T0 + 299_625, hr_avg=159.0, hr_peak=163)]
    out = summarize_vo2(beats, detected, _intent(rounds=1, work_duration_s=60,
                                                 rest_duration_s=60))

    assert out["work_bouts"][0]["match_confidence"] == "none"
    assert out["work_bouts"][0]["detected"] is None
    assert out["flags"]["unmatched_expected_bouts"] is True
    # Counts agree (1 expected work, 1 detected work) even though neither lines up
    assert out["flags"]["detected_intent_mismatch"] is False
    assert out["unmatched_detected_bouts"] == [{
        "kind": "work",
        "start_offset_s": 240.0,
        "end_offset_s": 299.6,
        "duration_s": 59.6,
        "hr_avg": 159.0,
        "hr_peak": 163,
    }]


def test_best_match_picks_the_largest_overlap():
    beats = _three_round_session()
    detected = [
        _bout("work", T0 - 30_000, T0 + 10_000),   # 10 s overlap
        _bout("work", T0 + 5_000, T0 + 55_000),    # 50 s overlap — the winner
    ]
    out = summarize_vo2(beats, detected, _intent(rounds=1, work_duration_s=60,
                                                 rest_duration_s=60))
    assert out["work_bouts"][0]["overlap_s"] == 50.0
    assert out["work_bouts"][0]["detected"]["start_offset_s"] == 5.0
    assert [b["start_offset_s"] for b in out["unmatched_detected_bouts"]] == [-30.0]


def test_missing_detected_round_sets_mismatch_flag():
    beats = _three_round_session()
    detected = [
        _bout("work", T0, T0 + 59_625),
        _bout("rest", T0 + 60_000, T0 + 119_400),
        _bout("work", T0 + 120_000, T0 + 179_625),
    ]  # third work round never detected
    out = summarize_vo2(beats, detected, _intent(rounds=3, work_duration_s=60,
                                                 rest_duration_s=60))
    assert out["flags"]["detected_intent_mismatch"] is True


def test_matches_bouts_from_the_real_detector():
    # End-to-end with segment.detect_bouts rather than hand-built Bouts
    beats = _three_round_session()
    detected = detect_bouts(beats, min_bout_s=20)
    assert [b.kind for b in detected][:2] == ["work", "rest"]

    out = summarize_vo2(beats, detected, _intent(rounds=3, work_duration_s=60,
                                                 rest_duration_s=60))
    assert all(b["match_confidence"] == "high" for b in out["work_bouts"])
    assert all(b["detected"] is not None for b in out["rest_bouts"])


# --- Work-bout metrics ---

def test_work_metrics_track_target_and_hrmax_thresholds():
    # 20 s ramp at 140 bpm then 40 s at 185 bpm inside a 60 s prescribed bout
    beats = _session([(140, 20), (185, 40), (100, 60)])
    intent = _intent(rounds=1, work_duration_s=60, rest_duration_s=60, target_hr_min=170)
    out = summarize_vo2(beats, [], intent, hrmax=200)
    work = out["work_bouts"][0]

    assert work["time_to_target_hr_min_s"] == 20.0
    assert work["time_to_90pct_hrmax_s"] == 20.0          # 90% of 200 = 180
    assert work["seconds_at_or_above_target_hr_min"] == pytest.approx(40.0, abs=2.0)
    assert work["seconds_at_or_above_90pct_hrmax"] == pytest.approx(40.0, abs=2.0)
    assert work["seconds_at_or_above_95pct_hrmax"] == 0.0  # 95% of 200 = 190 > 185
    assert work["peak_hr"] == 185
    assert work["hr_at_work_end"] in (185, 100)


def test_target_never_reached_reports_none():
    beats = _session([(160, 60), (100, 60)])
    intent = _intent(rounds=1, work_duration_s=60, rest_duration_s=60, target_hr_min=190)
    out = summarize_vo2(beats, [], intent)
    work = out["work_bouts"][0]
    assert work["time_to_target_hr_min_s"] is None
    assert work["seconds_at_or_above_target_hr_min"] == 0.0


def test_hrmax_metrics_omitted_when_hrmax_unknown():
    beats = _session([(160, 60), (100, 60)])
    out = summarize_vo2(beats, [], _intent(rounds=1, work_duration_s=60,
                                           rest_duration_s=60))
    work = out["work_bouts"][0]
    assert work["time_to_90pct_hrmax_s"] is None
    assert work["seconds_at_or_above_90pct_hrmax"] is None
    assert work["seconds_at_or_above_95pct_hrmax"] is None
    # No target_hr_min in this intent either
    assert work["time_to_target_hr_min_s"] is None
    assert work["seconds_at_or_above_target_hr_min"] is None


# --- Rest-bout metrics ---

def test_rest_metrics_report_recovery_drop_and_rmssd():
    # 30 s work, then a decaying recovery: 150 -> 130 -> 115 bpm
    beats = _session([(160, 30), (150, 30), (130, 30), (115, 60), (160, 30)])
    intent = _intent(rounds=2, work_duration_s=30, rest_duration_s=110)
    out = summarize_vo2(beats, [], intent)
    rest = out["rest_bouts"][0]

    assert rest["metrics_window"] == {"start_offset_s": 30.0, "end_offset_s": 140.0,
                                      "duration_s": 110.0}
    assert rest["hr_drop_30_s"] == 20    # 150 -> 130
    assert rest["hr_drop_60_s"] == 35    # 150 -> 115
    assert rest["minimum_hr"] == 115
    assert 0.95 < rest["rr_coverage"] < 1.10
    assert rest["artifact_frac"] < 0.10
    assert rest["rmssd_ms"] is not None


def test_hr_drop_past_the_window_repeats_the_last_sample():
    # Current behaviour: a 110 s window still reports a 180 s drop, reusing the
    # last available HR rather than returning None. Documented, not endorsed.
    beats = _session([(160, 30), (150, 30), (130, 30), (115, 60), (160, 30)])
    out = summarize_vo2(beats, [], _intent(rounds=2, work_duration_s=30,
                                           rest_duration_s=110))
    rest = out["rest_bouts"][0]
    assert rest["hr_drop_120_s"] == rest["hr_drop_180_s"] == 35


def test_sparse_rest_window_reports_no_hrv_metrics():
    # BLE dropout: the prescribed rest window holds a single beat
    beats = _session([(160, 60)])
    beats.append(Beat(ts_ms=T0 + 70_000, rr_ms=545, hr_bpm=110, is_gap=True))
    beats += _session([(160, 60)], t0=T0 + 130_000)

    out = summarize_vo2(beats, [], _intent(rounds=2, work_duration_s=60,
                                           rest_duration_s=60))
    rest = out["rest_bouts"][0]
    assert rest["rr_coverage"] is None
    assert rest["artifact_frac"] is None
    assert rest["rmssd_ms"] is None
    assert rest["minimum_hr"] == 110
    assert rest["hr_drop_30_s"] == 0  # only one sample: start and end coincide


def test_empty_rest_window_reports_nothing():
    # A prescribed rest that falls entirely in a data hole
    beats = _session([(160, 60)]) + _session([(160, 60)], t0=T0 + 180_000)
    out = summarize_vo2(beats, [], _intent(rounds=2, work_duration_s=60,
                                           rest_duration_s=60))
    rest = out["rest_bouts"][0]
    assert rest["minimum_hr"] is None
    assert rest["hr_drop_30_s"] is None
    assert rest["rmssd_ms"] is None


# --- Helpers ---

def test_pct_hr():
    assert _pct_hr(200, 0.90) == 180
    assert _pct_hr(200, 0.95) == 190
    assert _pct_hr(None, 0.90) is None


def test_durations_for_empty_beats():
    assert len(_durations_for([])) == 0


def test_durations_for_uses_wall_clock():
    beats = _session([(120, 10)])  # rr 500 ms
    assert np.allclose(_durations_for(beats), 0.5)


def test_seconds_at_or_above_guards():
    beats = _session([(160, 10)])
    assert _seconds_at_or_above(beats, None) is None
    assert _seconds_at_or_above([], 150) is None


def test_time_to_threshold_guards():
    beats = _session([(160, 10)])
    assert _time_to_threshold(beats, None) is None
    assert _time_to_threshold([], 150) is None
    assert _time_to_threshold(beats, 150) == 0.0


def test_hr_helpers_ignore_zero_hr_rows():
    blank = [Beat(ts_ms=T0 + i * 500, rr_ms=0, hr_bpm=0, is_gap=False) for i in range(4)]
    assert _max_hr(blank) is None
    assert _min_hr(blank) is None
    assert _hr_at_or_before(blank, T0 + 2000) is None
    assert _hr_drop(blank, 30) is None
    assert _hr_drop([], 30) is None


def test_hr_at_or_before_takes_the_latest_prior_sample():
    beats = _session([(100, 3), (150, 3)])  # 100 bpm for 3 s, then 150 bpm
    assert _hr_at_or_before(beats, T0 + 1000) == 100
    assert _hr_at_or_before(beats, T0 + 5000) == 150


def test_round_or_none():
    assert _round_or_none(None, 1) is None
    assert _round_or_none(12.3456, 1) == 12.3
