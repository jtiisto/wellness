"""Orchestrate the per-session analysis into a report dict."""

import numpy as np

from . import hrv
from .hr import hr_summary, time_weighted_mean_hr
from .quality import (
    MAX_TRUSTED_ARTIFACT,
    MAX_TRUSTED_COVERAGE,
    MIN_TRUSTED_COVERAGE,
    classify,
    contiguous_rr_runs,
    contiguous_runs,
    rr_coverage,
)
from .segment import detect_bouts

WINDOW_MS = 120_000  # 2-minute DFA window
STEP_MS = 30_000     # 30-second step


def _covered_ms(run):
    """Wall-clock time a run's beats actually cover = sum of its RR intervals.

    Excludes the first beat's interval (which ends AT the run's start, spanning
    time before it). Returns 0 for an absent/single-beat run.
    """
    if not run or len(run["rr"]) < 2:
        return 0.0
    return float(sum(run["rr"][1:]))


def _window_alpha1(beats):
    """Rolling DFA a1 windows with per-window quality gating.

    ⚠ PARITY: the trust rule here (longest contiguous clean run covering
    [0.95, 1.05] of the window, artifacts ≤5%, no gap) is mirrored live in the
    Kotlin SignalQualityTracker so the capture app's DFA-signal indicator agrees
    with what this trusts. Change the rule here → change it there too; the
    module-level note in quality.py carries the current Kotlin location and the
    state of that migration.
    """
    if len(beats) < 2:
        return []
    t0 = beats[0].ts_ms
    end = beats[-1].ts_ms
    ts = np.array([b.ts_ms for b in beats])

    windows = []
    start = t0
    # Only FULL windows whose right edge is within the captured data — never a
    # partial tail masquerading as a 2-minute window
    while start + WINDOW_MS <= end:
        mask = (ts >= start) & (ts < start + WINDOW_MS)
        seg = [beats[i] for i in np.where(mask)[0]]
        if len(seg) >= 40:
            flags = classify(seg)
            runs = contiguous_runs(seg, flags)
            longest = max(runs, key=lambda r: len(r["rr"]), default=None)
            a1 = hrv.dfa_alpha1(longest["rr"]) if longest else None
            # Covered time = summed RR intervals of the longest clean run.
            # This single measure catches BOTH a short run (edge/middle hole)
            # and a full-span run whose beats under-cover the window (steady
            # sub-threshold drift that never trips the omission flag).
            covered_ms = _covered_ms(longest)
            window_coverage = covered_ms / WINDOW_MS
            artifact = flags.artifact_fraction
            # Coverage must land in a plausible band: below the floor = missing
            # beats; materially above 100% = compressed/overlapping timeline
            trusted = (
                a1 is not None
                and MIN_TRUSTED_COVERAGE <= window_coverage <= MAX_TRUSTED_COVERAGE
                and artifact <= MAX_TRUSTED_ARTIFACT
                and not flags.gap.any()
            )
            windows.append({
                "offset_s": round((start - t0) / 1000.0, 1),
                "hr_avg": round(time_weighted_mean_hr(seg) or 0.0, 1),
                "beats": len(seg),
                "artifact_frac": round(artifact, 3),
                "window_coverage": round(window_coverage, 3),
                "alpha1": None if a1 is None else round(a1, 3),
                "trusted": bool(trusted),
                "state": hrv.alpha1_threshold_state(a1) if trusted else "untrusted",
            })
        start += STEP_MS
    return windows


def analyze(beats, hrmax=None):
    """Full pipeline over a loaded Beat list -> report dict."""
    if not beats:
        return {"error": "no beats"}

    span_s = (beats[-1].ts_ms - beats[0].ts_ms) / 1000.0
    coverage = rr_coverage(beats)
    flags = classify(beats)

    windows = _window_alpha1(beats)
    trusted = [w for w in windows if w["trusted"]]
    trusted_a1 = [w["alpha1"] for w in trusted if w["alpha1"] is not None]

    # Time above each threshold, from trusted windows only
    above_lt1 = sum(1 for w in trusted if w["alpha1"] is not None and w["alpha1"] < hrv.ALPHA1_LT1)
    above_lt2 = sum(1 for w in trusted if w["alpha1"] is not None and w["alpha1"] < hrv.ALPHA1_LT2)

    # RMSSD pooled over beat-adjacent runs — never differenced across a gap
    overall_rmssd = hrv.rmssd_pooled(contiguous_rr_runs(beats, flags))

    bouts = detect_bouts(beats)

    alpha1_verdict = _verdict(trusted, trusted_a1, coverage)

    return {
        "span_s": round(span_s, 1),
        "beats": len(beats),
        "rr_coverage": round(coverage, 3),
        "gaps": int(flags.gap.sum()),
        "artifact_frac_overall": round(flags.artifact_fraction, 3),
        "hr": hr_summary(beats, hrmax=hrmax),
        "rmssd_ms": None if overall_rmssd is None else round(overall_rmssd, 1),
        "alpha1": {
            "windows_total": len(windows),
            "windows_trusted": len(trusted),
            "trusted_mean": None if not trusted_a1 else round(float(np.mean(trusted_a1)), 3),
            "trusted_min": None if not trusted_a1 else round(float(np.min(trusted_a1)), 3),
            "trusted_windows_above_lt1": above_lt1,
            "trusted_windows_above_lt2": above_lt2,
            "verdict": alpha1_verdict,
        },
        "bouts": [_bout_report(b, beats) for b in bouts],
        "windows": windows,
    }


def _bout_report(bout, beats):
    """Per-bout metrics: HR, RMSSD (rest-recovery signal), and a1 when clean."""
    seg = [b for b in beats if bout.start_ms <= b.ts_ms <= bout.end_ms]
    rmssd_ms = None
    alpha1 = None
    coverage = None
    if len(seg) >= 2:
        flags = classify(seg)
        detailed = contiguous_runs(seg, flags)
        rmssd_ms = hrv.rmssd_pooled([r["rr"] for r in detailed])
        coverage = rr_coverage(seg)
        # Bout a1 = mean of the bout's own TRUSTED 2-min rolling windows. This
        # reuses the exact window trust rule, so it is comparable to the
        # session thresholds AND works for bouts of any length (a long bout
        # simply contains several full windows; a short one contains none).
        trusted_a1 = [w["alpha1"] for w in _window_alpha1(seg)
                      if w["trusted"] and w["alpha1"] is not None]
        if trusted_a1:
            alpha1 = round(float(np.mean(trusted_a1)), 3)
    return {
        "kind": bout.kind,
        "offset_s": round((bout.start_ms - beats[0].ts_ms) / 1000.0, 1),
        "duration_s": round(bout.duration_s, 1),
        "hr_avg": round(time_weighted_mean_hr(seg) or bout.hr_avg, 1),
        "hr_peak": bout.hr_peak,
        "rr_coverage": None if coverage is None else round(coverage, 3),
        "rmssd_ms": None if rmssd_ms is None else round(rmssd_ms, 1),
        "alpha1": None if alpha1 is None else round(alpha1, 3),
    }


def _verdict(trusted, trusted_a1, coverage):
    """Human-readable threshold call, honest about data quality."""
    if not trusted_a1:
        return ("No DFA a1 window met the quality bar (<=5% artifacts, no gaps)"
                f" — RR coverage {coverage:.0%}. Cannot assess threshold; artifacts"
                " bias a1 upward toward a false 'below threshold'.")
    mean_a1 = float(np.mean(trusted_a1))
    frac_trusted = len(trusted_a1) / max(len(trusted), 1)
    if mean_a1 >= hrv.ALPHA1_LT1:
        call = "predominantly BELOW LT1 (aerobic)"
    elif mean_a1 >= hrv.ALPHA1_LT2:
        call = "between LT1 and LT2 (tempo/threshold)"
    else:
        call = "ABOVE LT2 (anaerobic)"
    return (f"Trusted a1 mean {mean_a1:.2f} -> {call}. Based on {len(trusted_a1)} "
            f"trusted windows; treat with caution if few.")
