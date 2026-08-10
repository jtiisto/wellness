"""Tripwire for the Kotlin↔Python signal-quality parity.

The DFA trust rule exists twice: here, and live on-device in the Kotlin
`SignalQualityTracker` that drives the capture app's "DFA signal: Good"
indicator. Nothing can verify the two implementations agree from inside this
repo, so this file does the one thing it can — assert the documented values, so
that editing a threshold fails a test whose name points at the other side. If
you are here because this failed: change the Kotlin constants too, then update
the numbers below. See the ⚠ PARITY block at the top of hr_analysis/quality.py
for the current location of the Kotlin file.
"""
import pytest

from hr_analysis import pipeline, quality


@pytest.mark.unit
def test_quality_thresholds_match_the_kotlin_tracker():
    assert quality.ECTOPIC_THRESHOLD == 0.20      # ectopicThreshold
    assert quality.OMISSION_FACTOR == 1.5         # OMISSION_FACTOR
    assert quality.MAX_TRUSTED_ARTIFACT == 0.05   # goodArtifact
    assert quality.MIN_TRUSTED_COVERAGE == 0.95   # goodCoverage
    assert quality.MAX_TRUSTED_COVERAGE == 1.05   # goodCoverageMax


@pytest.mark.unit
def test_dfa_window_matches_the_kotlin_tracker():
    assert pipeline.WINDOW_MS == 120_000          # DEFAULT_WINDOW_MS


@pytest.mark.unit
@pytest.mark.parametrize("deviation, flagged", [(0.19, False), (0.21, True)])
def test_ectopic_threshold_bites_where_it_is_documented(deviation, flagged):
    """The constant above only matters through this behaviour, so pin it too:
    a beat deviating from the local median by just under 20% is kept, just over
    is an artifact. The Kotlin tracker must draw the line in the same place."""
    baseline = 800
    rr = [baseline] * 5
    rr[2] = round(baseline * (1 + deviation))
    beats = [quality.Beat(ts_ms=i * baseline, rr_ms=v, hr_bpm=75, is_gap=False)
             for i, v in enumerate(rr)]
    assert bool(quality.classify(beats).ectopic[2]) is flagged
