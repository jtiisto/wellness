"""Signal-based work/rest bout detection (no lap markers available).

Heuristic: smooth instantaneous HR, then split into work/rest bouts with a
hysteresis threshold at the midpoint between the session's low and high HR
plateaus, enforcing a minimum bout duration.
"""

from dataclasses import dataclass

import numpy as np


@dataclass
class Bout:
    kind: str        # "work" | "rest"
    start_ms: int
    end_ms: int
    hr_avg: float
    hr_peak: int

    @property
    def duration_s(self):
        return (self.end_ms - self.start_ms) / 1000.0


def _smooth(x, window):
    """Edge-aware moving average.

    Padding with edge values (not zeros) keeps the capture boundaries from
    becoming synthetic HR drops that trigger false bouts.
    """
    if len(x) < window or window < 2:
        return x
    pad = window // 2
    padded = np.pad(x, pad, mode="edge")
    kernel = np.ones(window) / window
    return np.convolve(padded, kernel, mode="valid")[:len(x)]


def detect_bouts(beats, smooth_beats=15, min_bout_s=20.0, hysteresis_frac=0.10):
    """Split a session into alternating work/rest bouts from the HR signal.

    Returns [] when HR is too flat to distinguish efforts (steady-state ride).
    """
    if len(beats) < smooth_beats * 2:
        return []

    ts = np.array([b.ts_ms for b in beats], dtype=float)
    hr = np.array([b.hr_bpm for b in beats], dtype=float)
    hr_s = _smooth(hr, smooth_beats)

    # Derive plateaus from a UNIFORM time grid — one row per beat weights
    # high-HR work more than equal wall-clock rest, which can collapse the
    # percentiles and hide real intervals
    grid = np.arange(ts[0], ts[-1] + 1, 1000.0)
    hr_uniform = np.interp(grid, ts, hr_s) if len(grid) > 1 else hr_s
    lo, hi = np.percentile(hr_uniform, 20), np.percentile(hr_uniform, 80)
    span = hi - lo
    # A near-flat HR profile has no meaningful work/rest structure
    if span < 8:
        return []

    mid = (lo + hi) / 2.0
    band = span * hysteresis_frac
    high_thresh, low_thresh = mid + band, mid - band

    # Hysteresis state machine over smoothed HR -> segment boundary indices
    state = "work" if hr_s[0] >= mid else "rest"
    bounds = [0]
    kinds = [state]
    for i in range(1, len(hr_s)):
        if state == "rest" and hr_s[i] >= high_thresh:
            state = "work"
            bounds.append(i)
            kinds.append(state)
        elif state == "work" and hr_s[i] <= low_thresh:
            state = "rest"
            bounds.append(i)
            kinds.append(state)
    bounds.append(len(beats))

    # segments as mutable [kind, start_idx, end_idx]
    segs = [[kinds[k], bounds[k], bounds[k + 1]] for k in range(len(kinds))]

    def seg_dur(seg):
        return (ts[seg[2] - 1] - ts[seg[1]]) / 1000.0

    # Merge sub-minimum bouts into a neighbour, then coalesce same-kind
    # neighbours — so no time is dropped and the minimum is enforced for the
    # first bout too. Iterate to a fixed point.
    changed = True
    while changed and len(segs) > 1:
        changed = False
        for idx, seg in enumerate(segs):
            if seg_dur(seg) < min_bout_s:
                if idx > 0:
                    segs[idx - 1][2] = seg[2]   # extend previous over this
                else:
                    segs[idx + 1][1] = seg[1]   # first bout: fold into next
                segs.pop(idx)
                changed = True
                break
        j = 0
        while j < len(segs) - 1:
            if segs[j][0] == segs[j + 1][0]:
                segs[j][2] = segs[j + 1][2]
                segs.pop(j + 1)
                changed = True
            else:
                j += 1

    bouts = []
    for kind, s, e in segs:
        seg_hr = hr[s:e]
        if len(seg_hr) == 0:
            continue
        bouts.append(Bout(
            kind=kind,
            start_ms=int(ts[s]),
            end_ms=int(ts[e - 1]),
            hr_avg=round(float(seg_hr.mean()), 1),
            hr_peak=int(seg_hr.max()),
        ))
    return bouts
