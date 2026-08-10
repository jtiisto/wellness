"""Heart-rate summary and (optional) zone distribution.

Durations are derived from timestamp deltas, not summed RR, so omitted beats
(RR missing while HR timestamps continue) still contribute their wall-clock
time — and averages/zones aren't biased by the one-row-per-beat storage.
"""

import numpy as np

# Fractions of HRmax bounding classic 5-zone model
ZONE_EDGES = [0.50, 0.60, 0.70, 0.80, 0.90, 1.01]
ZONE_LABELS = ["Z1", "Z2", "Z3", "Z4", "Z5"]


# A gap between beats beyond this multiple of the interval's OWN RR is dead
# time (BLE disconnect / omission), not a slow heartbeat — cap it so stale HR
# isn't credited that no-data span. Comparing to the beat's own RR (not a
# global median) preserves genuine low-HR stretches, whose delta ≈ RR.
DURATION_OUTLIER_FACTOR = 2.5


def _durations_s(ts_ms, rr_ms=None, gaps=None):
    """Wall-clock seconds each beat represents, from timestamp deltas.

    Deltas that are disconnect gaps (`gaps`) or that greatly exceed the
    interval's own RR are capped, so a no-data span isn't credited to the
    stale HR sample preceding it. Genuine slow-HR beats (delta ≈ RR) are kept.
    """
    ts = np.asarray(ts_ms, dtype=float)
    if len(ts) == 1:
        return np.array([1.0])
    diffs = np.diff(ts)
    med = float(np.median(diffs)) if len(diffs) else 1000.0

    if rr_ms is not None:
        rr = np.asarray(rr_ms, dtype=float)
        expected = rr[1:].copy()          # interval ending at beat i+1
        expected[expected <= 0] = med
        is_gap_next = (
            np.asarray(gaps, dtype=bool)[1:]
            if gaps is not None else np.zeros(len(diffs), dtype=bool)
        )
        cap_mask = is_gap_next | (diffs > DURATION_OUTLIER_FACTOR * expected)
        capped = np.where(cap_mask, expected, diffs)
    else:
        capped = np.where(diffs > DURATION_OUTLIER_FACTOR * med, med, diffs)

    tail = float(np.median(capped)) if len(capped) else med
    dur = np.concatenate([capped, [tail]])
    return dur / 1000.0


def time_weighted_mean_hr(beats):
    """Mean HR weighted by each beat's wall-clock duration; None if no HR."""
    valid = [b for b in beats if b.hr_bpm > 0]
    if not valid:
        return None
    ts = np.array([b.ts_ms for b in valid], dtype=float)
    hr = np.array([b.hr_bpm for b in valid], dtype=float)
    rr = np.array([b.rr_ms for b in valid], dtype=float)
    gaps = np.array([b.is_gap for b in valid], dtype=bool)
    dur = _durations_s(ts, rr, gaps)
    total = dur.sum()
    if total <= 0:
        return float(hr.mean())
    return float(np.average(hr, weights=dur))


def hr_summary(beats, hrmax=None):
    """Avg/max/min HR plus, if hrmax given, seconds in each %HRmax zone."""
    valid = [b for b in beats if b.hr_bpm > 0]
    if len(valid) == 0:
        return {"avg": None, "max": None, "min": None, "zones": None}

    ts = np.array([b.ts_ms for b in valid], dtype=float)
    hr = np.array([b.hr_bpm for b in valid], dtype=float)
    rr = np.array([b.rr_ms for b in valid], dtype=float)
    gaps = np.array([b.is_gap for b in valid], dtype=bool)
    dur_s = _durations_s(ts, rr, gaps)

    total = dur_s.sum()
    avg = float(np.average(hr, weights=dur_s)) if total > 0 else float(hr.mean())
    out = {
        "avg": round(avg, 1),
        "max": int(hr.max()),
        "min": int(hr.min()),
        "zones": None,
    }
    if hrmax:
        edges = [e * hrmax for e in ZONE_EDGES]
        zones = {}
        for i, label in enumerate(ZONE_LABELS):
            in_zone = (hr >= edges[i]) & (hr < edges[i + 1])
            zones[label] = round(float(dur_s[in_zone].sum()), 1)
        out["zones"] = zones
    return out
