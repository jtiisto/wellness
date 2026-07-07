"""Pure unit tests for the trends domain helpers (no DB)."""

from datetime import date

import pytest

from modules.trends_queries import (
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
