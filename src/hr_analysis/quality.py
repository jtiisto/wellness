"""Beat-quality classification and correction for RR series.

Artifacts inflate DFA alpha1 (biasing toward a false "below threshold"), so
downstream code gates on the flags produced here rather than trusting raw
alpha1 from contaminated windows.
"""

from dataclasses import dataclass

import numpy as np

# ============================================================================
# ⚠ PARITY: these DFA signal-quality gates are mirrored LIVE on-device by the
# Kotlin `SignalQualityTracker`, so the capture app's "DFA signal: Good"
# indicator promises exactly what this offline module will trust. Change a
# threshold or an ectopic/omission/coverage rule here and it must change there
# too. No shared test enforces the cross-language parity — keep them in sync by
# hand; test/hr/analysis/test_parity_constants.py at least fails loudly when a
# value below is edited, so the edit cannot pass unnoticed.
#
# The Kotlin side is mid-migration (2026-08-10). Its only implementation today
# is in the FROZEN pulse-bridge repo at
#   ~/dev/native/pulse-bridge/feature/capture/src/main/kotlin/dev/jtiisto/
#       pulsebridge/feature/capture/domain/SignalQualityTracker.kt
# whose own parity comment still points at that repo's `server/analysis/`
# (i.e. at this file's ancestor). It is being ported to the wellness Android
# app's new `core/ble` module (~/dev/native/wellness, spec
# `specs/coach-heart-rate.md`, Android phase 2); when that lands, repoint this
# note — and the Kotlin one — at each other.
# ============================================================================
ECTOPIC_THRESHOLD = 0.20   # |delta rr| / prev rr above this = ectopic/artifact
OMISSION_FACTOR = 1.5      # timestamp gap > factor * rr = a beat was omitted
MAX_TRUSTED_ARTIFACT = 0.05  # window artifact fraction ceiling for "trusted"
MIN_TRUSTED_COVERAGE = 0.95  # window RR coverage floor for "trusted"
MAX_TRUSTED_COVERAGE = 1.05  # ceiling: RR summing to MORE than wall-clock
#                              means an inconsistent/compressed timeline


@dataclass
class Beat:
    """One RR interval, as the analysis sees it.

    The field names predate the hr.db schema and are deliberately kept rather
    than renamed to match it: the whole mapping (`timestamp_ms`→ts_ms,
    `rr_interval_ms`→rr_ms, `heart_rate_bpm`→hr_bpm, `is_gap_before`→is_gap)
    lives in one place, db.load_beats, and every other module here — plus the
    Kotlin tracker this file is kept in parity with — already speaks in these
    terms.
    """
    ts_ms: int
    rr_ms: int
    hr_bpm: int
    is_gap: bool


@dataclass
class QualityFlags:
    zero_rr: np.ndarray      # rr == 0 sensor artifact
    ectopic: np.ndarray      # sudden RR jump
    omission: np.ndarray     # a beat is missing before this one
    gap: np.ndarray          # carries the connection-gap marker

    @property
    def artifact(self):
        return self.zero_rr | self.ectopic | self.omission | self.gap

    @property
    def artifact_fraction(self):
        return float(self.artifact.mean()) if len(self.zero_rr) else 0.0


def classify(beats):
    """Flag zero-RR, ectopic, omission, and gap beats in a Beat list."""
    ts = np.array([b.ts_ms for b in beats], dtype=float)
    rr = np.array([b.rr_ms for b in beats], dtype=float)
    gap = np.array([b.is_gap for b in beats], dtype=bool)

    zero_rr = rr == 0

    # Flag ectopics by deviation from the LOCAL MEDIAN, not from the previous
    # beat. A successive-difference test flags both the artifact and the valid
    # beat that returns to baseline, so correcting the rebound would reuse the
    # artifact value and inflate RMSSD/DFA.
    # PARITY (see the block at the top of this file): the live Kotlin
    # SignalQualityTracker uses the same 5-beat median window and 20% threshold,
    # but TRAILING (no future beats exist live) rather than the centered
    # i-2..i+2 used here. Keep the size and threshold in sync.
    ectopic = np.zeros(len(rr), dtype=bool)
    for i in range(len(rr)):
        if rr[i] <= 0:
            continue  # zero-RR handled separately; never an ectopic
        lo, hi = max(0, i - 2), min(len(rr), i + 3)
        window = rr[lo:hi]
        window = window[window > 0]  # ignore sentinel zeros in the baseline
        if len(window) == 0:
            continue
        med = np.median(window)
        if med > 0 and abs(rr[i] - med) / med > ECTOPIC_THRESHOLD:
            ectopic[i] = True

    omission = np.zeros(len(rr), dtype=bool)
    if len(rr) > 1:
        tsgap = np.diff(ts)
        omission[1:] = tsgap > OMISSION_FACTOR * rr[1:]

    return QualityFlags(zero_rr=zero_rr, ectopic=ectopic, omission=omission, gap=gap)


def _valid_neighbor(rr, flags, i, step):
    """Nearest beat in direction `step` that is a valid baseline value.

    Skips zero-RR and other ectopic rows so a bad value never enters an
    interpolation (which would create an artificial RR difference).
    """
    j = i + step
    while 0 <= j < len(rr):
        if rr[j] > 0 and not flags.ectopic[j] and not flags.zero_rr[j]:
            return float(rr[j])
        j += step
    return None


def corrected_rr(beats, flags):
    """RR series with isolated ectopics replaced by valid-neighbour means.

    Zero-RR rows are dropped. Omissions/gaps are left in place (the caller
    decides trust) since they mark genuinely missing beats that cannot be
    fabricated without biasing alpha1.
    """
    rr = np.array([b.rr_ms for b in beats], dtype=float)
    out = []
    for i in range(len(beats)):
        if flags.zero_rr[i]:
            continue
        if flags.ectopic[i]:
            lo = _valid_neighbor(rr, flags, i, -1)
            hi = _valid_neighbor(rr, flags, i, +1)
            vals = [v for v in (lo, hi) if v is not None]
            out.append(float(np.mean(vals)) if vals else float(rr[i]))
        else:
            out.append(float(rr[i]))
    return np.array(out, dtype=float)


def contiguous_runs(beats, flags):
    """Beat-adjacent runs with corrected RR and their wall-clock span.

    Each run: {"rr": [...], "start_ms", "end_ms"}. Runs break at omissions,
    gaps, and zero-RR rows. Ectopics are corrected only from valid neighbours.
    The span lets the caller reject a "2-minute" window whose only clean run
    is actually far shorter (a hole at the window edge or middle).
    """
    rr = np.array([b.rr_ms for b in beats], dtype=float)
    ts = np.array([b.ts_ms for b in beats], dtype=float)

    runs = []
    for a, b in _run_index_ranges(beats, flags):
        vals = []
        for i in range(a, b + 1):
            if flags.ectopic[i]:
                lo = vals[-1] if vals else None  # already corrected, in-run
                hi = _valid_neighbor_in_range(rr, flags, i, b)
                cands = [v for v in (lo, hi) if v is not None]
                vals.append(float(np.mean(cands)) if cands else float(rr[i]))
            else:
                vals.append(float(rr[i]))
        runs.append({"rr": vals, "start_ms": int(ts[a]), "end_ms": int(ts[b])})
    return runs


def _run_index_ranges(beats, flags):
    """Index ranges (inclusive) of beat-adjacent runs with >= 2 beats."""
    ranges = []
    start = None
    for i in range(len(beats)):
        if flags.zero_rr[i]:
            if start is not None:
                ranges.append((start, i - 1))
                start = None
            continue
        if (flags.omission[i] or flags.gap[i]) and start is not None:
            ranges.append((start, i - 1))
            start = None
        if start is None:
            start = i
    if start is not None:
        ranges.append((start, len(beats) - 1))
    return [(a, b) for a, b in ranges if b - a + 1 >= 2]


def _valid_neighbor_in_range(rr, flags, i, end):
    """First valid forward neighbour within [i+1, end]."""
    j = i + 1
    while j <= end:
        if rr[j] > 0 and not flags.ectopic[j] and not flags.zero_rr[j]:
            return float(rr[j])
        j += 1
    return None


def rr_coverage(beats):
    """Fraction of wall-clock span covered by summed RR intervals.

    < 1.0 means the strap omitted beats (no RR reported for them). This is the
    coverage check that catches the failure mode where beats are missing but
    the artifact flags stay low (continuous HR timestamps, absent RRs).
    """
    if len(beats) < 2:
        return 0.0
    span = beats[-1].ts_ms - beats[0].ts_ms
    if span <= 0:
        return 0.0
    # Each RR is the interval ENDING at its beat, so the first beat's RR spans
    # time before the window — sum from the second beat to match the span
    total_rr = sum(b.rr_ms for b in beats[1:] if b.rr_ms > 0)
    return total_rr / span


def contiguous_rr_runs(beats, flags):
    """Corrected RR grouped into beat-adjacent runs (values only).

    RMSSD and DFA must never take a successive difference across a boundary
    where a beat is actually missing. See contiguous_runs for the version that
    also carries each run's wall-clock span.
    """
    return [r["rr"] for r in contiguous_runs(beats, flags)]
