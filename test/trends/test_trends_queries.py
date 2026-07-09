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
