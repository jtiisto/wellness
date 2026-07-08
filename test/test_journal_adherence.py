"""Unit tests for the pure schedule-adherence computation (journal MCP).

These exercise `compute_adherence` in isolation (no DB). Reference dates:
2026-07-06 Mon .. 2026-07-13 Mon (07-11 Sat, 07-12 Sun).
"""
import json
import os
import time

import pytest

# Canonical location (moved from journal_mcp.adherence, which now re-exports).
from modules.journal_adherence import (
    compute_adherence,
    compute_streaks,
    day_status,
    target_band_segments,
)


@pytest.mark.unit
def test_mcp_shim_reexports_canonical_functions():
    """The journal MCP's historical import surface must stay identical to the
    shared domain module (one implementation, re-exported)."""
    import journal_mcp.adherence as shim
    import modules.journal_adherence as canonical
    assert shim.compute_adherence is canonical.compute_adherence
    assert shim.day_status is canonical.day_status
    assert shim.compute_streaks is canonical.compute_streaks
    assert shim.target_band_segments is canonical.target_band_segments


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

    def test_mid_window_pause_counts_only_pre_pause_days(self):
        # Paused tracker = an empty-days segment. Mon–Fri from genesis, paused
        # (days []) effective Wed: over Mon..Fri only Mon,Tue are scheduled
        # (pre-pause); Wed..Fri fall in the pause window (0 scheduled days), so
        # the rate is computed on the pre-pause days only — the pause is NOT
        # counted as missed.
        schedule = json.dumps([
            {"effectiveFrom": GENESIS, "days": [1, 2, 3, 4, 5]},
            {"effectiveFrom": WED, "days": []},
        ])
        r = compute_adherence(schedule, "positive", "simple", {MON: 1, TUE: 1}, MON, FRI)
        assert r["scheduled_days"] == 2          # Mon, Tue (before the pause)
        assert r["done_days"] == 2
        assert r["missed_days"] == 0             # Wed..Fri are paused, not missed
        assert r["adherence_rate"] == 1.0        # 2/2, pre-pause days only
        assert r["coverage_rate"] == 1.0

    def test_fully_paused_window_null_rates(self):
        # A genesis empty-days segment = paused from day one → zero scheduled
        # days, every rate null ("nothing to measure"), and a stray entry lands
        # off-schedule rather than counting as a scheduled/missed day.
        paused = json.dumps([{"effectiveFrom": GENESIS, "days": []}])
        r = compute_adherence(paused, "positive", "simple", {MON: 1}, MON, FRI)
        assert r["scheduled_days"] == 0
        assert r["adherence_rate"] is None
        assert r["coverage_rate"] is None
        assert r["missed_days"] == 0
        assert r["off_schedule_entries"] == 1

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


# ---- target-aware adherence ----------------------------------------------

def _target(min_v=None, max_v=None, effective=GENESIS):
    t = {}
    if min_v is not None:
        t["min"] = min_v
    if max_v is not None:
        t["max"] = max_v
    return json.dumps([{"effectiveFrom": effective, "target": t}])


@pytest.mark.unit
class TestTargetAdherence:
    def test_at_least_met_partial_missed(self):
        # min 150; Mon 160 met, Tue 100 partial, Wed no entry -> missed (positive).
        r = compute_adherence(
            None, "positive", "quantifiable", {MON: 1, TUE: 1}, MON, WED,
            target_json=_target(min_v=150), values={MON: 160, TUE: 100})
        assert r["scheduled_days"] == 3
        assert r["logged_days"] == 2
        assert r["done_days"] == 1
        assert r["target_met_days"] == 1
        assert r["target_partial_days"] == 1
        assert r["target"] == {"min": 150}
        assert r["adherence_rate"] == round(1 / 3, 3)   # target_met/scheduled

    def test_at_most_met_over_and_negative_no_entry_met(self):
        # max 2 (negative); Mon 1 met, Tue 3 over->missed, Wed no entry -> met.
        r = compute_adherence(
            None, "negative", "quantifiable", {MON: 1, TUE: 1}, MON, WED,
            target_json=_target(max_v=2), values={MON: 1, TUE: 3})
        assert r["scheduled_days"] == 3
        assert r["logged_days"] == 2
        assert r["target_met_days"] == 2          # Mon (<=2) + Wed (no entry, avoided)
        assert r["target_partial_days"] == 0
        assert r["metric_kind"] == "avoidance"
        assert r["avoidance_rate"] == round(2 / 3, 3)
        assert "adherence_rate" not in r

    def test_range_and_neutral_coverage_not_redefined(self):
        # range 150-170 (neutral); Mon 160 met, Tue 100 partial, Wed 200 over,
        # Thu no entry -> missed. coverage stays logged/scheduled.
        r = compute_adherence(
            None, "neutral", "quantifiable", {MON: 1, TUE: 1, WED: 1}, MON, THU,
            target_json=_target(min_v=150, max_v=170),
            values={MON: 160, TUE: 100, WED: 200})
        assert r["scheduled_days"] == 4
        assert r["logged_days"] == 3
        assert r["target_met_days"] == 1
        assert r["target_partial_days"] == 1
        assert r["metric_kind"] == "coverage"
        assert r["coverage_rate"] == 0.75          # logged/scheduled (unchanged)
        assert "adherence_rate" not in r and "avoidance_rate" not in r

    def test_positive_no_entry_missed_vs_negative_no_entry_met(self):
        pos = compute_adherence(
            None, "positive", "quantifiable", {}, MON, MON,
            target_json=_target(min_v=10), values={})
        assert pos["target_met_days"] == 0 and pos["adherence_rate"] == 0.0
        neg = compute_adherence(
            None, "negative", "quantifiable", {}, MON, MON,
            target_json=_target(max_v=2), values={})
        assert neg["target_met_days"] == 1 and neg["avoidance_rate"] == 1.0

    def test_target_change_mid_window_is_effective_dated(self):
        # min 100 from genesis, min 200 from Tue; value 150 each day.
        target_json = json.dumps([
            {"effectiveFrom": GENESIS, "target": {"min": 100}},
            {"effectiveFrom": TUE, "target": {"min": 200}},
        ])
        r = compute_adherence(
            None, "positive", "quantifiable", {MON: 1, TUE: 1, WED: 1}, MON, WED,
            target_json=target_json, values={MON: 150, TUE: 150, WED: 150})
        assert r["target_met_days"] == 1          # Mon (>=100)
        assert r["target_partial_days"] == 2      # Tue, Wed (<200)
        assert r["target"] == {"min": 200}        # echoed as of window end

    def test_null_target_segment_ends_targeting(self):
        # min 150 from genesis, target removed from Tue; completed each day.
        target_json = json.dumps([
            {"effectiveFrom": GENESIS, "target": {"min": 150}},
            {"effectiveFrom": TUE, "target": None},
        ])
        r = compute_adherence(
            None, "positive", "quantifiable", {MON: 1, TUE: 1, WED: 1}, MON, WED,
            target_json=target_json, values={MON: 100, TUE: 100, WED: 100})
        assert r["target_met_days"] == 0          # Mon partial
        assert r["target_partial_days"] == 1
        assert r["done_days"] == 2                # Tue+Wed untargeted → completed==1
        assert r["target"] is None                # removed as of window end

    def test_untargeted_output_has_no_target_fields(self):
        r = compute_adherence(None, "positive", "quantifiable", {MON: 1}, MON, MON)
        assert "target" not in r
        assert "target_met_days" not in r
        assert "target_partial_days" not in r
        assert "blended_met_days" not in r
        assert r["adherence_rate"] == 1.0

    def test_zero_scheduled_days_with_target_null_rates(self):
        weekend = json.dumps([{"effectiveFrom": GENESIS, "days": [0, 6]}])
        r = compute_adherence(
            weekend, "positive", "quantifiable", {}, MON, FRI,
            target_json=_target(min_v=10), values={})
        assert r["scheduled_days"] == 0
        assert r["adherence_rate"] is None
        assert r["target_met_days"] == 0

    def test_genesis_target_segment_no_year0_crash(self):
        # date.fromisoformat('0000-01-01') would raise; the sentinel is only
        # string-compared. A genesis-only target must flow through cleanly.
        r = compute_adherence(
            None, "positive", "quantifiable", {MON: 1}, MON, FRI,
            target_json=_target(min_v=10), values={MON: 12})
        assert r["target_met_days"] >= 1

    def test_blended_rate_positive_pretarget_checkbox_counts(self):
        # Target added Tue (genesis-null before). Mon has no target → the
        # checkbox-completed day must count toward adherence_rate (blended).
        target_json = json.dumps([
            {"effectiveFrom": GENESIS, "target": None},
            {"effectiveFrom": TUE, "target": {"min": 150}},
        ])
        r = compute_adherence(
            None, "positive", "quantifiable", {MON: 1, TUE: 1, WED: 1}, MON, WED,
            target_json=target_json, values={TUE: 160, WED: 100})
        assert r["target_met_days"] == 1          # Tue met (targeted-only)
        assert r["target_partial_days"] == 1      # Wed partial
        # Blended numerator = Mon (checkbox) + Tue (met) = 2, NOT target_met_days.
        assert r["blended_met_days"] == 2         # exposed for per-week display
        assert r["adherence_rate"] == round(2 / 3, 3)

    def test_blended_rate_negative_pretarget_no_entry_counts(self):
        # Target added Tue. Mon has no target and no entry → avoided → counts
        # toward avoidance_rate (blended).
        target_json = json.dumps([
            {"effectiveFrom": GENESIS, "target": None},
            {"effectiveFrom": TUE, "target": {"max": 2}},
        ])
        r = compute_adherence(
            None, "negative", "quantifiable", {TUE: 1, WED: 1}, MON, WED,
            target_json=target_json, values={TUE: 1, WED: 3})
        assert r["target_met_days"] == 1          # Tue met (<=2)
        # Blended numerator = Mon (no entry, avoided) + Tue (met) = 2.
        assert r["blended_met_days"] == 2
        assert r["avoidance_rate"] == round(2 / 3, 3)


@pytest.mark.unit
class TestValueCoercion:
    """Stale targets can meet non-numeric values (a tracker converted from/to
    'note' shares the entries.value column) — the comparison must degrade to
    'missed', never raise (review F6)."""

    def _target(self, **kw):
        return json.dumps([{"effectiveFrom": GENESIS, "target": kw}])

    def test_string_value_is_missed_not_crash(self):
        r = compute_adherence(
            None, "negative", "quantifiable", {MON: 1}, MON, MON,
            target_json=self._target(max=2), values={MON: "skipped it"})
        assert r["target_met_days"] == 0
        assert r["avoidance_rate"] == 0.0

    def test_string_value_never_satisfies_a_range(self):
        # NaN-style comparisons must not read as in-range (the client-twin bug).
        r = compute_adherence(
            None, "positive", "quantifiable", {MON: 1}, MON, MON,
            target_json=self._target(min=1, max=5), values={MON: "three"})
        assert r["target_met_days"] == 0

    def test_numeric_string_still_counts(self):
        r = compute_adherence(
            None, "positive", "quantifiable", {MON: 1}, MON, MON,
            target_json=self._target(min=150), values={MON: "160"})
        assert r["target_met_days"] == 1


@pytest.mark.unit
class TestLegacyWeeklyFallback:
    """schedule_json NULL + meta_json {frequency: weekly, weeklyDay} must be
    judged weekly, mirroring the client twin — not defaulted to daily
    (review F7: a perfect weekly habit read ~87% missed)."""

    def test_legacy_weekly_scheduled_only_on_weekly_day(self):
        meta = json.dumps({"frequency": "weekly", "weeklyDay": 3})  # Wednesday
        r = compute_adherence(
            None, "positive", "simple", {WED: 1}, MON, SUN, meta_json=meta)
        assert r["scheduled_days"] == 1
        assert r["done_days"] == 1
        assert r["adherence_rate"] == 1.0
        assert r["off_schedule_entries"] == 0

    def test_legacy_weekly_invalid_day_falls_back_to_daily(self):
        meta = json.dumps({"frequency": "weekly", "weeklyDay": 9})
        r = compute_adherence(
            None, "positive", "simple", {}, MON, SUN, meta_json=meta)
        assert r["scheduled_days"] == 7  # normalize rule: invalid → daily

    def test_schedule_json_wins_over_legacy_meta(self):
        meta = json.dumps({"frequency": "weekly", "weeklyDay": 3})
        r = compute_adherence(
            MON_FRI, "positive", "simple", {}, MON, SUN, meta_json=meta)
        assert r["scheduled_days"] == 5  # canonical column, not the legacy shape

    def test_legacy_daily_meta_stays_daily(self):
        meta = json.dumps({"frequency": "daily"})
        r = compute_adherence(
            None, "positive", "simple", {}, MON, SUN, meta_json=meta)
        assert r["scheduled_days"] == 7


@pytest.mark.unit
class TestDayStatus:
    def test_off_on_unscheduled_day(self):
        assert day_status(MON_FRI, "positive", {}, {}, None, None, SAT) == "off"

    def test_pause_segment_is_off(self):
        paused = json.dumps([
            {"effectiveFrom": GENESIS, "days": [1, 2, 3, 4, 5]},
            {"effectiveFrom": WED, "days": []},
        ])
        assert day_status(paused, "positive", {TUE: 1}, {}, None, None, TUE) == "met"
        assert day_status(paused, "positive", {}, {}, None, None, THU) == "off"

    def test_targeted_day_uses_value(self):
        target = json.dumps([{"effectiveFrom": GENESIS, "target": {"min": 150}}])
        assert day_status(None, "positive", {MON: 0}, {MON: 160}, target, None, MON) == "met"
        assert day_status(None, "positive", {MON: 1}, {MON: 100}, target, None, MON) == "partial"
        assert day_status(None, "positive", {}, {}, target, None, MON) == "missed"

    def test_untargeted_positive_is_checkbox(self):
        assert day_status(None, "positive", {MON: 1}, {}, None, None, MON) == "met"
        assert day_status(None, "positive", {MON: 0}, {}, None, None, MON) == "missed"
        assert day_status(None, "positive", {}, {}, None, None, MON) == "missed"

    def test_negative_absence_is_met(self):
        assert day_status(None, "negative", {}, {}, None, None, MON) == "met"
        assert day_status(None, "negative", {MON: 1}, {}, None, None, MON) == "missed"

    def test_legacy_weekly_fallback(self):
        meta = json.dumps({"frequency": "weekly", "weeklyDay": 3})  # Wednesday
        assert day_status(None, "positive", {WED: 1}, {}, None, meta, WED) == "met"
        assert day_status(None, "positive", {}, {}, None, meta, THU) == "off"


@pytest.mark.unit
class TestComputeStreaks:
    def test_basic_run_and_reset(self):
        # Mon,Tue met; Wed missed; Thu,Fri met → best 2, current 2 (as of Fri).
        entries = {MON: 1, TUE: 1, THU: 1, FRI: 1}
        r = compute_streaks(MON_FRI, "positive", entries, {}, None, None,
                            first_date=MON, today=FRI)
        assert r == {"current": 2, "best": 2}

    def test_off_days_are_transparent(self):
        # Mon–Fri schedule: met Fri, weekend off, met next Mon → streak of 2.
        entries = {FRI: 1, NEXT_MON: 1}
        r = compute_streaks(MON_FRI, "positive", entries, {}, None, None,
                            first_date=FRI, today=NEXT_MON)
        assert r["current"] == 2

    def test_pause_window_is_transparent(self):
        paused_midway = json.dumps([
            {"effectiveFrom": GENESIS, "days": [0, 1, 2, 3, 4, 5, 6]},
            {"effectiveFrom": WED, "days": []},
        ])
        # Met Mon,Tue then paused Wed→Sun: current streak survives the pause.
        entries = {MON: 1, TUE: 1}
        r = compute_streaks(paused_midway, "positive", entries, {}, None, None,
                            first_date=MON, today=SUN)
        assert r == {"current": 2, "best": 2}

    def test_today_unmet_does_not_break(self):
        entries = {MON: 1, TUE: 1}  # Wed (today) not logged yet
        r = compute_streaks(MON_FRI, "positive", entries, {}, None, None,
                            first_date=MON, today=WED)
        assert r["current"] == 2

    def test_today_met_counts(self):
        entries = {MON: 1, TUE: 1, WED: 1}
        r = compute_streaks(MON_FRI, "positive", entries, {}, None, None,
                            first_date=MON, today=WED)
        assert r["current"] == 3

    def test_negative_avoidance_streak(self):
        # Daily negative tracker: entry on Wed breaks the absence streak.
        entries = {WED: 1}
        r = compute_streaks(None, "negative", entries, {}, None, None,
                            first_date=MON, today=FRI)
        assert r["current"] == 2   # Thu, Fri avoided
        assert r["best"] == 2      # Mon, Tue equally

    def test_no_first_date(self):
        assert compute_streaks(None, "positive", {}, {}, None, None,
                               first_date=None, today=MON) == {"current": 0, "best": 0}


@pytest.mark.unit
class TestTargetBandSegments:
    def test_change_mid_window_splits_with_day_before_boundary(self):
        target = json.dumps([
            {"effectiveFrom": GENESIS, "target": {"min": 100}},
            {"effectiveFrom": WED, "target": {"min": 150}},
        ])
        segs = target_band_segments(target, MON, FRI)
        assert segs == [
            {"start": MON, "end": TUE, "min": 100, "max": None},
            {"start": WED, "end": FRI, "min": 150, "max": None},
        ]

    def test_null_target_window_is_a_gap(self):
        target = json.dumps([
            {"effectiveFrom": GENESIS, "target": None},
            {"effectiveFrom": WED, "target": {"max": 2}},
        ])
        segs = target_band_segments(target, MON, FRI)
        assert segs == [{"start": WED, "end": FRI, "min": None, "max": 2}]

    def test_absent_history(self):
        assert target_band_segments(None, MON, FRI) == []

    def test_range_target(self):
        target = json.dumps([{"effectiveFrom": GENESIS, "target": {"min": 150, "max": 170}}])
        segs = target_band_segments(target, MON, MON)
        assert segs == [{"start": MON, "end": MON, "min": 150, "max": 170}]
