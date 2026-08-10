"""Tests for the workout analysis pipeline.

Ported from the pulse-bridge server's suite along with the package itself. Every
session here is generated from a formula (a beat train, a square wave, a seeded
RNG) — no capture is transcribed. The one dropped case covered the Polar
start-anchored timestamp shift, whose branch this deployment does not have; the
reader's schema mapping and `seq` ordering are covered in test_db.py instead.
"""

import numpy as np
import pytest

from hr_analysis import hrv
from hr_analysis.quality import (
    Beat,
    classify,
    contiguous_rr_runs,
    contiguous_runs,
    corrected_rr,
    rr_coverage,
)
from hr_analysis.hr import hr_summary, _durations_s
from hr_analysis.segment import _smooth, detect_bouts
from hr_analysis.pipeline import analyze
from hr_analysis import report as report_mod

from .conftest import requires_matplotlib


# --- DFA alpha1 ---

def test_dfa_alpha1_white_noise_near_half():
    rng = np.random.default_rng(42)
    rr = 800 + rng.normal(0, 20, 4000)  # uncorrelated → a1 ~ 0.5
    a1 = hrv.dfa_alpha1(rr)
    assert a1 is not None
    assert 0.35 < a1 < 0.65


def test_dfa_alpha1_correlated_above_one():
    rng = np.random.default_rng(7)
    rr = 800 + np.cumsum(rng.normal(0, 2, 4000))  # random walk → a1 ~ 1.5
    a1 = hrv.dfa_alpha1(rr)
    assert a1 is not None
    assert a1 > 1.0


def test_dfa_alpha1_too_short_returns_none():
    assert hrv.dfa_alpha1([800, 810, 790]) is None


def test_alpha1_threshold_state():
    assert hrv.alpha1_threshold_state(0.9) == "below_lt1"
    assert hrv.alpha1_threshold_state(0.6) == "lt1_to_lt2"
    assert hrv.alpha1_threshold_state(0.4) == "above_lt2"
    assert hrv.alpha1_threshold_state(None) == "unknown"


# --- RMSSD ---

def test_rmssd_known_vector():
    # diffs: [10, -10, 10] → rms = 10
    assert hrv.rmssd([800, 810, 800, 810]) == pytest.approx(10.0)


def test_rmssd_too_short():
    assert hrv.rmssd([800]) is None


def test_rmssd_pooled_ignores_run_boundaries():
    # Two clean runs; the big jump BETWEEN them must not enter the diff set
    runs = [[800, 810, 800], [1200, 1210, 1200]]
    # within-run diffs are all +/-10 -> rms 10, boundary 800->1200 excluded
    assert hrv.rmssd_pooled(runs) == pytest.approx(10.0)


def test_rmssd_pooled_empty():
    assert hrv.rmssd_pooled([[800]]) is None


# --- Quality flagging ---

def _beats(rr_list, gaps=None, dt=None):
    gaps = gaps or [False] * len(rr_list)
    ts = 0
    beats = []
    for i, rr in enumerate(rr_list):
        step = dt[i] if dt else rr
        ts += step
        beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=int(60000 / rr) if rr else 0, is_gap=gaps[i]))
    return beats


def test_zero_rr_flagged_and_dropped():
    beats = _beats([800, 0, 810])
    flags = classify(beats)
    assert flags.zero_rr.tolist() == [False, True, False]
    assert len(corrected_rr(beats, flags)) == 2


def test_ectopic_flagged_and_corrected():
    beats = _beats([800, 800, 1300, 800])  # 1300 is the outlier
    flags = classify(beats)
    # Only the outlier is flagged — the valid rebound beat must NOT be
    assert flags.ectopic.tolist() == [False, False, True, False]
    corrected = corrected_rr(beats, flags)
    assert corrected[2] == pytest.approx(800.0)
    assert corrected[3] == pytest.approx(800.0)  # rebound left intact


def test_ectopic_correction_does_not_inflate_rmssd():
    # The regression Codex caught: correcting the rebound with the artifact
    # value inflated RMSSD to ~289 ms on an otherwise-flat series
    beats = _beats([800, 800, 1300, 800, 800])
    flags = classify(beats)
    runs = contiguous_rr_runs(beats, flags)
    assert hrv.rmssd_pooled(runs) == pytest.approx(0.0, abs=1.0)


def test_ectopic_interpolation_skips_zero_rr_neighbor():
    # [800, 1300, 0, 800]: the 0 must NOT be used to interpolate the 1300,
    # which would produce a spurious 400 ms value
    beats = _beats([800, 1300, 0, 800], dt=[800, 1300, 1, 800])
    flags = classify(beats)
    for run in contiguous_runs(beats, flags):
        for v in run["rr"]:
            assert v >= 600  # never the 400 ms an ill-chosen neighbour gives


def test_omission_flagged_by_timestamp_gap():
    # A beat arrives 2000 ms after the previous while its RR says 800 → omission
    beats = _beats([800, 800], dt=[800, 2000])
    flags = classify(beats)
    assert flags.omission[1]


def test_gap_flag_carried():
    beats = _beats([800, 800], gaps=[False, True])
    flags = classify(beats)
    assert flags.gap[1]
    assert flags.artifact[1]


def test_rr_coverage_full_and_partial():
    full = _beats([800, 800, 800])
    assert rr_coverage(full) == pytest.approx(1.0, abs=0.05)
    # Omitted beat: timestamps span 2400 ms but only 1600 ms of RR present
    partial = _beats([800, 800], dt=[800, 1600])
    assert rr_coverage(partial) < 0.7


# --- Segmentation ---

def test_detect_bouts_square_wave():
    # Alternate 60 s work (HR 160) / 60 s rest (HR 100), 4 reps
    beats = []
    ts = 0
    for rep in range(4):
        for hr in (160, 100):
            n = 90  # ~beats per 60 s
            for _ in range(n):
                rr = int(60000 / hr)
                ts += rr
                beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=hr, is_gap=False))
    bouts = detect_bouts(beats, min_bout_s=20)
    kinds = [b.kind for b in bouts]
    assert kinds.count("work") >= 3
    assert kinds.count("rest") >= 3


def test_detect_bouts_flat_returns_none():
    beats = _beats([500] * 400)  # steady 120 bpm
    assert detect_bouts(beats) == []


def test_detect_bouts_asymmetric_work_rest():
    # 3-min work (160) / 1-min rest (100): rest is <20% of ROWS, so row-based
    # percentiles would collapse — time-uniform resampling must still find them
    beats = []
    ts = 0
    for _ in range(3):
        for hr, secs in ((160, 180), (100, 60)):
            n = int(secs * hr / 60)
            for _ in range(n):
                rr = int(60000 / hr)
                ts += rr
                beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=hr, is_gap=False))
    bouts = detect_bouts(beats, min_bout_s=20)
    assert sum(1 for b in bouts if b.kind == "work") >= 2
    assert sum(1 for b in bouts if b.kind == "rest") >= 2


def test_smooth_uses_edge_not_zero_padding():
    # A constant signal must stay constant — zero-padding would dip the ends
    x = np.full(100, 150.0)
    sm = _smooth(x, 15)
    assert np.allclose(sm, 150.0, atol=1e-9)


def test_detect_bouts_no_false_bout_from_edges():
    # Steady HR with tiny noise: edge padding must not manufacture a bout
    rng = np.random.default_rng(3)
    beats = []
    ts = 0
    for _ in range(400):
        hr = 150 + rng.normal(0, 1)
        rr = int(60000 / hr)
        ts += rr
        beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=int(hr), is_gap=False))
    assert detect_bouts(beats) == []


def test_short_bouts_merged_not_dropped():
    # 10 s blip of high HR inside a long rest must not survive as its own bout
    beats = []
    ts = 0

    def add(hr, seconds):
        nonlocal ts
        n = int(seconds * hr / 60)
        for _ in range(n):
            rr = int(60000 / hr)
            ts += rr
            beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=hr, is_gap=False))

    add(100, 90)   # rest
    add(165, 10)   # 10 s blip (< 20 s min) — must be merged away
    add(100, 90)   # rest
    bouts = detect_bouts(beats, min_bout_s=20)
    # No bout shorter than the minimum survives, and time is contiguous
    assert all(b.duration_s >= 20 for b in bouts)
    for a, b in zip(bouts, bouts[1:]):
        assert a.kind != b.kind  # no duplicate adjacent kinds


# --- Time-weighted HR ---

def test_hr_avg_is_time_weighted():
    # Equal DURATION at 60 and 180 bpm -> time average 120, not row-mean 150
    beats = []
    ts = 0
    for _ in range(30):  # 30 s at 60 bpm = 30 beats
        ts += 1000
        beats.append(Beat(ts_ms=ts, rr_ms=1000, hr_bpm=60, is_gap=False))
    for _ in range(90):  # 30 s at 180 bpm = 90 beats
        ts += 333
        beats.append(Beat(ts_ms=ts, rr_ms=333, hr_bpm=180, is_gap=False))
    avg = hr_summary(beats)["avg"]
    assert 115 < avg < 125  # ~120, not the 150 an unweighted mean would give


def test_hr_duration_from_timestamps_not_rr():
    # 15% of beats omitted (timestamp gap 2x RR) — duration must follow the
    # clock, so the average still reflects real wall-clock time
    beats = []
    ts = 0
    for i in range(200):
        rr = 600  # 100 bpm nominal
        step = rr * 2 if i % 6 == 0 else rr  # omitted-beat gaps
        ts += step
        beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=100, is_gap=False))
    s = hr_summary(beats)
    assert s["avg"] == pytest.approx(100.0, abs=1.0)


def test_hrmax_zone_durations_track_wall_clock():
    # 60 s at 150 bpm, hrmax 200 -> 75% HRmax = Z3 (0.70-0.80), ~60 s
    beats = []
    ts = 0
    for _ in range(150):
        ts += 400  # 150 bpm
        beats.append(Beat(ts_ms=ts, rr_ms=400, hr_bpm=150, is_gap=False))
    zones = hr_summary(beats, hrmax=200)["zones"]
    assert zones["Z3"] > 55  # ~60 s, duration from the clock not undercounted


# --- Pipeline ---

def test_pipeline_smoke_steady_session():
    rng = np.random.default_rng(1)
    beats = []
    ts = 0
    for _ in range(1500):
        rr = int(700 + rng.normal(0, 15))
        ts += rr
        beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=int(60000 / rr), is_gap=False))
    report = analyze(beats)
    assert report["beats"] == 1500
    assert report["alpha1"]["windows_total"] > 0
    assert report["rmssd_ms"] is not None
    assert "verdict" in report["alpha1"]


def test_pipeline_no_trusted_windows_is_honest():
    # Every window riddled with gaps → no trusted a1, verdict says so
    beats = []
    ts = 0
    for i in range(1500):
        rr = 700
        ts += rr
        beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=85, is_gap=(i % 3 == 0)))
    report = analyze(beats)
    assert report["alpha1"]["windows_trusted"] == 0
    assert "Cannot assess threshold" in report["alpha1"]["verdict"]


def test_low_rr_coverage_windows_not_trusted():
    # Continuous HR timestamps but ~20% of beats have no RR reported: artifact
    # flags stay lowish, yet coverage is far below 95% -> must NOT be trusted
    rng = np.random.default_rng(9)
    beats = []
    ts = 0
    for i in range(1500):
        true_rr = int(700 + rng.normal(0, 10))
        if i % 5 == 0:
            # omitted beat: advance the clock by two intervals, one row
            ts += true_rr * 2
            beats.append(Beat(ts_ms=ts, rr_ms=true_rr, hr_bpm=85, is_gap=False))
        else:
            ts += true_rr
            beats.append(Beat(ts_ms=ts, rr_ms=true_rr, hr_bpm=85, is_gap=False))
    report = analyze(beats)
    assert report["rr_coverage"] < 0.95
    # frequent omissions break every window's contiguous run — none trusted
    assert all(not w["trusted"] for w in report["windows"])
    assert report["alpha1"]["windows_trusted"] == 0


def test_edge_hole_window_not_trusted():
    # A 2-min window whose first 40 s are missing (omission straddling the
    # left edge) has clean data only for ~80 s -> must NOT be trusted
    beats = []
    ts = 0
    # 3 min of clean data
    for _ in range(3 * 60 * 2):  # ~2 beats/s
        ts += 500
        beats.append(Beat(ts_ms=ts, rr_ms=500, hr_bpm=120, is_gap=False))
    # Punch a 40 s hole near 1:00 (one big timestamp jump = omission)
    holed = [b for b in beats if not (60_000 < b.ts_ms < 100_000)]
    report = analyze(holed)
    # Windows overlapping the hole must be untrusted; their coverage < 0.95
    overlapping = [w for w in report["windows"]
                   if w["offset_s"] <= 100 and w["offset_s"] + 120 >= 60]
    assert overlapping
    assert all(not w["trusted"] for w in overlapping)


def test_mid_window_omission_not_trusted_as_full_window():
    # One omission in the middle splits the window into two ~1-min runs;
    # neither spans the required near-full 2 minutes, so it is not trusted
    beats = []
    ts = 0
    for i in range(600):  # 5 min at 2 beats/s
        step = 500 * 3 if i == 300 else 500  # single omission mid-stream
        ts += step
        beats.append(Beat(ts_ms=ts, rr_ms=500, hr_bpm=120, is_gap=False))
    report = analyze(beats)
    # The window centered on the omission has no full-length clean run
    around = [w for w in report["windows"]
              if w["offset_s"] <= 150 and w["offset_s"] + 120 >= 150]
    assert around
    assert all(not w["trusted"] for w in around)


def test_steady_subthreshold_drift_not_trusted():
    # Every timestamp gap is 1.3x the reported RR — below the 1.5x omission
    # flag, so the run never breaks, yet only ~77% of wall-clock is covered.
    # Covered-time gate must reject these windows.
    beats = []
    ts = 0
    for _ in range(900):  # ~7.8 min of wall clock
        rr = 500
        ts += int(rr * 1.3)  # sub-threshold drift, no omission flag
        beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=120, is_gap=False))
    report = analyze(beats)
    # Runs span the windows but summed RR covers < 95% -> nothing trusted
    assert report["windows"]
    assert all(w["window_coverage"] < 0.95 for w in report["windows"])
    assert report["alpha1"]["windows_trusted"] == 0


def test_compressed_timeline_over_100pct_not_trusted():
    # Timestamps compressed to half the RR (RR sums to ~200% of wall-clock):
    # an internally inconsistent timeline must fail the upper coverage bound
    beats = []
    ts = 0
    for _ in range(900):
        rr = 500
        ts += 250  # timestamps advance at half the RR -> ~200% coverage
        beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=120, is_gap=False))
    report = analyze(beats)
    assert report["windows"]
    assert all(w["window_coverage"] > 1.05 for w in report["windows"])
    assert report["alpha1"]["windows_trusted"] == 0


def test_disconnect_gap_time_not_credited_to_stale_hr():
    # A big delta (disconnect) must not weight the preceding HR sample with the
    # dead time — the capped duration keeps the average honest
    ts = [0, 1000, 2000, 12000, 13000]  # 10 s dead span before the 4th sample
    dur = _durations_s(ts)
    # No single duration should absorb the 10 s gap; all near the ~1 s median
    assert max(dur) < 3.0


def test_long_clean_bout_gets_alpha1():
    # A clean work bout well over 2 min must receive a1 (mean of its trusted
    # rolling windows) — the round-5 upper bound wrongly nulled long bouts
    rng = np.random.default_rng(11)
    beats = []
    ts = 0
    # 4 min work with real RR variability, then 90 s rest
    for _ in range(4 * 160):  # ~160 bpm for 4 min
        rr = int(375 + rng.normal(0, 12))
        ts += rr
        beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=160, is_gap=False))
    for _ in range(90 * 100 // 60):  # 90 s rest at 100 bpm
        rr = int(600 + rng.normal(0, 12))
        ts += rr
        beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=100, is_gap=False))
    report = analyze(beats)
    work = [b for b in report["bouts"] if b["kind"] == "work" and b["duration_s"] > 150]
    assert work
    assert any(b["alpha1"] is not None for b in work)


def test_short_bout_gets_rmssd_but_no_alpha1():
    # 30 s work bouts: RMSSD yes, but a1 requires a full 2-min run -> None
    beats = []
    ts = 0
    for _ in range(6):
        for hr, secs in ((160, 30), (100, 30)):
            n = int(secs * hr / 60)
            for _ in range(n):
                rr = int(60000 / hr)
                ts += rr
                beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=hr, is_gap=False))
    report = analyze(beats)
    work = [b for b in report["bouts"] if b["kind"] == "work"]
    assert work
    assert all(b["rmssd_ms"] is not None for b in work)
    assert all(b["alpha1"] is None for b in work)  # too short for a 2-min a1


def test_short_session_yields_no_full_windows():
    # 90 s session is below the 2-minute window -> zero windows, honest verdict
    beats = []
    ts = 0
    for _ in range(130):
        ts += 700
        beats.append(Beat(ts_ms=ts, rr_ms=700, hr_bpm=85, is_gap=False))
    report = analyze(beats)
    assert report["alpha1"]["windows_total"] == 0
    assert "Cannot assess threshold" in report["alpha1"]["verdict"]


def test_bout_report_includes_rmssd():
    # Clean square-wave session -> bouts carry per-bout RMSSD
    beats = []
    ts = 0
    for _ in range(4):
        for hr in (160, 100):
            for _ in range(120):
                rr = int(60000 / hr)
                ts += rr
                beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=hr, is_gap=False))
    report = analyze(beats)
    assert len(report["bouts"]) >= 4
    assert any(b["rmssd_ms"] is not None for b in report["bouts"])


def test_bout_alpha1_withheld_when_gap_present():
    # A long clean bout with a single gap row must not report a1
    beats = []
    ts = 0
    for i in range(400):
        rr = 600
        ts += rr
        beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=150, is_gap=(i == 200)))
    for i in range(400):
        rr = 900
        ts += rr
        beats.append(Beat(ts_ms=ts, rr_ms=rr, hr_bpm=95, is_gap=False))
    report = analyze(beats)
    work_bouts = [b for b in report["bouts"] if b["kind"] == "work"]
    assert work_bouts and all(b["alpha1"] is None for b in work_bouts)


# --- Report output ---

def test_write_json_creates_missing_dir(tmp_path):
    report = analyze(_beats([700] * 300))
    out = tmp_path / "nested" / "dir"
    path = report_mod.write_json("sess", report, str(out))
    assert path.exists()


@requires_matplotlib
def test_write_png_for_short_session(tmp_path):
    # Session below 2-min window: PNG must still render (HR-only), not skip
    beats = _beats([700] * 100)  # ~70 s
    report = analyze(beats)
    assert report["windows"] == []
    path = report_mod.write_png("short", report, str(tmp_path), beats=beats)
    assert path is not None and path.exists()
