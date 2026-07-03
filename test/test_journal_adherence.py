"""Unit tests for the pure schedule-adherence computation (journal MCP).

These exercise `compute_adherence` in isolation (no DB). Reference dates:
2026-07-06 Mon .. 2026-07-13 Mon (07-11 Sat, 07-12 Sun).
"""
import json
import os
import time

import pytest

from journal_mcp.adherence import compute_adherence

GENESIS = "0000-01-01"
MON_FRI = json.dumps([{"effectiveFrom": GENESIS, "days": [1, 2, 3, 4, 5]}])

MON = "2026-07-06"
TUE = "2026-07-07"
WED = "2026-07-08"
THU = "2026-07-09"
FRI = "2026-07-10"
SAT = "2026-07-11"
SUN = "2026-07-12"
NEXT_MON = "2026-07-13"


@pytest.mark.unit
class TestComputeAdherence:
    def test_positive_adherence_math(self):
        # Mon–Fri schedule over Mon..Sun. Logged Mon,Tue,Wed (Wed not done),
        # plus a Sat off-schedule entry; Thu,Fri scheduled-but-not-logged.
        entries = {MON: 1, TUE: 1, WED: 0, SAT: 1}
        r = compute_adherence(MON_FRI, "positive", "simple", entries, MON, SUN)
        assert r["metric_kind"] == "adherence"
        assert r["scheduled_days"] == 5           # Mon..Fri
        assert r["logged_days"] == 3              # Mon,Tue,Wed
        assert r["done_days"] == 2               # Mon,Tue (Wed completed=0)
        assert r["missed_days"] == 2             # Thu,Fri
        assert r["off_schedule_entries"] == 1    # Sat
        assert r["adherence_rate"] == 0.4        # 2/5
        assert r["coverage_rate"] == 0.6         # 3/5

    def test_completed_false_is_logged_not_done(self):
        r = compute_adherence(MON_FRI, "positive", "simple", {MON: 0}, MON, MON)
        assert r["logged_days"] == 1
        assert r["done_days"] == 0
        assert r["adherence_rate"] == 0.0
        assert r["coverage_rate"] == 1.0

    def test_negative_polarity_avoidance_inversion(self):
        # Occurrence = entry present; avoided = scheduled days with no entry.
        entries = {MON: 1, TUE: 1}
        r = compute_adherence(MON_FRI, "negative", "simple", entries, MON, FRI)
        assert r["metric_kind"] == "avoidance"
        assert r["scheduled_days"] == 5
        assert r["logged_days"] == 2
        assert r["avoidance_rate"] == 0.6         # (5-2)/5
        assert "adherence_rate" not in r
        assert r["coverage_rate"] == 0.4

    def test_neutral_polarity_coverage_only(self):
        r = compute_adherence(MON_FRI, "neutral", "simple", {MON: 1}, MON, FRI)
        assert r["metric_kind"] == "coverage"
        assert "adherence_rate" not in r
        assert "avoidance_rate" not in r
        assert r["coverage_rate"] == 0.2          # 1/5

    def test_unspecified_polarity_is_coverage(self):
        r = compute_adherence(MON_FRI, None, "simple", {MON: 1}, MON, FRI)
        assert r["metric_kind"] == "coverage"
        assert r["coverage_rate"] == 0.2

    def test_off_schedule_excluded_from_denominator(self):
        # Weekend-only entries under a Mon–Fri schedule over the weekend.
        r = compute_adherence(MON_FRI, "positive", "simple", {SAT: 1, SUN: 1}, SAT, SUN)
        assert r["scheduled_days"] == 0
        assert r["off_schedule_entries"] == 2
        assert r["adherence_rate"] is None        # never divide by zero
        assert r["coverage_rate"] is None

    def test_zero_scheduled_days_null_rates(self):
        # Weekend-only schedule over a Mon–Fri window → scheduled_days 0.
        weekend = json.dumps([{"effectiveFrom": GENESIS, "days": [0, 6]}])
        r = compute_adherence(weekend, "positive", "simple", {}, MON, FRI)
        assert r["scheduled_days"] == 0
        assert r["adherence_rate"] is None
        assert r["coverage_rate"] is None
        assert r["missed_days"] == 0

    def test_genesis_sentinel_never_date_parsed(self):
        # date.fromisoformat('0000-01-01') would raise; the sentinel must only be
        # string-compared. A genesis-only schedule must flow through cleanly.
        r = compute_adherence(MON_FRI, "positive", "simple", {MON: 1}, MON, FRI)
        assert r["scheduled_days"] == 5

    def test_segment_boundary_selection(self):
        # Mon–Fri from genesis, widened to Mon–Sat effective Wed.
        schedule = json.dumps([
            {"effectiveFrom": GENESIS, "days": [1, 2, 3, 4, 5]},
            {"effectiveFrom": WED, "days": [1, 2, 3, 4, 5, 6]},
        ])
        # Mon,Tue under seg1 (no Sat); Wed..Sat under seg2 (Sat now scheduled).
        r = compute_adherence(schedule, "neutral", "simple", {}, MON, SAT)
        assert r["scheduled_days"] == 6           # Mon,Tue,Wed,Thu,Fri,Sat

    def test_date_before_all_segments_uses_earliest(self):
        schedule = json.dumps([
            {"effectiveFrom": WED, "days": [1]},               # Mondays only
            {"effectiveFrom": FRI, "days": [1, 2, 3, 4, 5]},
        ])
        # Window Mon..Tue precedes both segments → earliest (Mondays only).
        r = compute_adherence(schedule, "neutral", "simple", {}, MON, TUE)
        assert r["scheduled_days"] == 1           # only Mon

    def test_absent_schedule_is_daily(self):
        r = compute_adherence(None, "neutral", "simple", {MON: 1}, MON, SUN)
        assert r["scheduled_days"] == 7

    def test_empty_schedule_list_is_daily(self):
        r = compute_adherence("[]", "neutral", "simple", {}, MON, SUN)
        assert r["scheduled_days"] == 7

    def test_weekday_is_tz_free(self):
        # A plain date's weekday is timezone-independent. Under LA, 2026-07-13
        # must still be recognized as Monday (not shifted to Sunday).
        orig = os.environ.get("TZ")
        try:
            os.environ["TZ"] = "America/Los_Angeles"
            time.tzset()
            r = compute_adherence(MON_FRI, "positive", "simple", {}, SAT, NEXT_MON)
            # Sat,Sun,Mon under Mon–Fri → only Mon (07-13) scheduled.
            assert r["scheduled_days"] == 1
        finally:
            if orig is None:
                os.environ.pop("TZ", None)
            else:
                os.environ["TZ"] = orig
            time.tzset()
