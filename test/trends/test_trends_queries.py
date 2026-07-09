"""Pure unit tests for the trends domain helpers (no DB)."""

from datetime import date

import pytest

from modules.trends_queries import (
    _bw_kg_for,
    convert_weight,
    epley_e1rm,
    to_kg,
    week_buckets,
    week_start,
)


@pytest.mark.unit
class TestWeekMath:
    def test_week_start_is_monday(self):
        assert week_start(date(2026, 7, 8)) == date(2026, 7, 6)   # Wed → Mon
        assert week_start(date(2026, 7, 6)) == date(2026, 7, 6)   # Mon → itself
        assert week_start(date(2026, 7, 12)) == date(2026, 7, 6)  # Sun → same week

    def test_week_buckets_floor_start_and_cover_end(self):
        buckets = week_buckets(date(2026, 7, 8), date(2026, 7, 20))
        assert buckets[0] == (date(2026, 7, 6), date(2026, 7, 12))
        assert buckets[-1] == (date(2026, 7, 20), date(2026, 7, 26))
        assert len(buckets) == 3

    def test_week_buckets_empty_when_inverted(self):
        assert week_buckets(date(2026, 7, 20), date(2026, 7, 8)) == []


@pytest.mark.unit
class TestWeightMath:
    def test_epley(self):
        assert epley_e1rm(100, 1) == 100
        assert round(epley_e1rm(100, 5), 1) == 116.7
        assert round(epley_e1rm(90, 8), 1) == 114.0

    def test_convert_round_trip(self):
        assert convert_weight(100, "lbs", "lbs") == 100
        kg = convert_weight(100, "lbs", "kg")
        assert round(kg, 2) == 45.36
        assert round(convert_weight(kg, "kg", "lbs"), 6) == 100

    def test_unknown_unit_passes_through(self):
        assert convert_weight(5, "bands", "lbs") == 5

    def test_to_kg(self):
        assert to_kg(10, "kg") == 10
        assert round(to_kg(100, "lbs"), 2) == 45.36


@pytest.mark.unit
class TestBwKgFor:
    """_bw_kg_for: nearest body-weight sample at-or-before the date, falling
    back to the earliest after; None only when there are no samples."""

    SAMPLES = [("2026-02-01", 90.0), ("2026-03-01", 88.0), ("2026-04-01", 87.5)]

    def test_empty_samples_is_none(self):
        assert _bw_kg_for([], "2026-03-15") is None

    def test_exact_date_matches(self):
        assert _bw_kg_for(self.SAMPLES, "2026-03-01") == 88.0

    def test_between_samples_uses_most_recent_before(self):
        assert _bw_kg_for(self.SAMPLES, "2026-03-15") == 88.0

    def test_after_last_uses_last(self):
        assert _bw_kg_for(self.SAMPLES, "2026-06-01") == 87.5

    def test_before_first_falls_back_to_earliest(self):
        assert _bw_kg_for(self.SAMPLES, "2026-01-01") == 90.0


@pytest.mark.unit
class TestBestOfTieBreak:
    def test_unrounded_incumbent_wins_over_rounded_equal(self):
        # 90.04 lbs x5 rounds to 90.0 for display but must stay the best
        # weight over 90.0 x8 — comparing against the ROUNDED incumbent let
        # the later row steal the record via the reps tie-break (F4).
        from modules.trends_queries import _best_of
        rows = [
            {"weight": 90.04, "reps": 5, "date": "2026-07-01", "unit": "lbs"},
            {"weight": 90.0, "reps": 8, "date": "2026-07-02", "unit": "lbs"},
        ]
        best = _best_of(rows, "lbs")
        assert best["best_weight"]["date"] == "2026-07-01"
        assert best["best_weight"]["reps"] == 5
        assert "_w" not in best["best_weight"]


@pytest.mark.unit
class TestPlateauFlag:
    """_plateau_flag rule boundaries (v2 Phase 4): flat ±2% 4-week max e1RM
    at unchanged-or-higher mean RPE, ≥3 sessions and ≥1 RPE per window."""

    END = "2026-07-01"
    # Recent window: 06-04..07-01; prior: 05-07..06-03.
    PRIOR_DATES = ["2026-05-10", "2026-05-17", "2026-05-24"]
    RECENT_DATES = ["2026-06-07", "2026-06-14", "2026-06-21"]

    def _rows(self, prior_w, recent_w, prior_rpe=8.0, recent_rpe=8.0):
        def row(d, w, rpe):
            return {"date": d, "weight": w, "reps": 5, "rpe": rpe, "unit": "lbs"}
        return ([row(d, prior_w, prior_rpe) for d in self.PRIOR_DATES]
                + [row(d, recent_w, recent_rpe) for d in self.RECENT_DATES])

    def _flag(self, rows):
        from modules.trends_queries import _plateau_flag
        return _plateau_flag(rows, "lbs", self.END)

    def test_flat_same_rpe_is_plateau(self):
        assert self._flag(self._rows(100, 100)) is True

    def test_exactly_two_percent_is_still_flat(self):
        assert self._flag(self._rows(100, 102)) is True

    def test_improving_beyond_tolerance_is_not(self):
        assert self._flag(self._rows(100, 103)) is False

    def test_regressing_beyond_tolerance_is_not(self):
        assert self._flag(self._rows(100, 97)) is False

    def test_lower_recent_rpe_is_not(self):
        # Same output at LESS effort is progress, not a plateau.
        assert self._flag(self._rows(100, 100, prior_rpe=8.0, recent_rpe=7.0)) is False

    def test_higher_recent_rpe_within_tolerance_is_plateau(self):
        assert self._flag(self._rows(100, 101, prior_rpe=8.0, recent_rpe=9.0)) is True

    def test_too_few_sessions_no_signal(self):
        rows = self._rows(100, 100)
        del rows[0]   # prior window down to 2 sessions
        assert self._flag(rows) is False

    def test_missing_rpe_no_signal(self):
        assert self._flag(self._rows(100, 100, recent_rpe=None)) is False
