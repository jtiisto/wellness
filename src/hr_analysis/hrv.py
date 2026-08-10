"""Beat-to-beat HRV primitives: DFA alpha1 and RMSSD."""

import numpy as np

# Standard short-term DFA scaling region (beats)
DFA_SCALE_MIN = 4
DFA_SCALE_MAX = 16

# Threshold reference values (Rogers et al.)
ALPHA1_LT1 = 0.75  # aerobic threshold
ALPHA1_LT2 = 0.5   # anaerobic threshold


def dfa_alpha1(rr_ms, scale_min=DFA_SCALE_MIN, scale_max=DFA_SCALE_MAX):
    """Short-term detrended fluctuation scaling exponent of an RR series.

    Assumes the input is a run of beat-adjacent RR intervals (ms). Returns
    None when the series is too short to fit the scaling region.
    """
    rr = np.asarray(rr_ms, dtype=float)
    n = len(rr)
    if n < scale_max * 2:
        return None

    # Integrate the mean-removed series (the DFA "profile")
    y = np.cumsum(rr - rr.mean())

    scales = np.arange(scale_min, scale_max + 1)
    fluct = []
    used_scales = []
    for s in scales:
        boxes = n // s
        if boxes < 1:
            continue
        # Detrend each non-overlapping box linearly, collect residual variance
        variances = []
        x = np.arange(s)
        for b in range(boxes):
            seg = y[b * s:(b + 1) * s]
            coeffs = np.polyfit(x, seg, 1)
            resid = seg - np.polyval(coeffs, x)
            variances.append(np.mean(resid ** 2))
        f = np.sqrt(np.mean(variances))
        if f > 0:
            fluct.append(f)
            used_scales.append(s)

    if len(used_scales) < 2:
        return None
    log_n = np.log(used_scales)
    log_f = np.log(fluct)
    slope = np.polyfit(log_n, log_f, 1)[0]
    return float(slope)


def rmssd(rr_ms):
    """Root mean square of successive RR differences (ms).

    Input should already be artifact-filtered. Returns None with < 2 beats.
    """
    rr = np.asarray(rr_ms, dtype=float)
    if len(rr) < 2:
        return None
    diffs = np.diff(rr)
    return float(np.sqrt(np.mean(diffs ** 2)))


def rmssd_pooled(runs):
    """RMSSD pooled over beat-adjacent runs.

    Successive differences are taken WITHIN each run only — never across a
    missing-beat boundary — then pooled. Returns None when no run has >= 2
    beats (quality too poor to report a continuous-session RMSSD).
    """
    diffs = []
    for run in runs:
        arr = np.asarray(run, dtype=float)
        if len(arr) >= 2:
            diffs.extend(np.diff(arr))
    if len(diffs) < 1:
        return None
    diffs = np.asarray(diffs, dtype=float)
    return float(np.sqrt(np.mean(diffs ** 2)))


def alpha1_threshold_state(alpha1):
    """Map an alpha1 value to a threshold zone label."""
    if alpha1 is None:
        return "unknown"
    if alpha1 >= ALPHA1_LT1:
        return "below_lt1"        # aerobic / sub-threshold
    if alpha1 >= ALPHA1_LT2:
        return "lt1_to_lt2"       # between thresholds
    return "above_lt2"            # anaerobic
