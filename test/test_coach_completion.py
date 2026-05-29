"""Unit tests for derived workout completion (mcp_servers/coach_mcp/completion.py).

These are pure-function tests — no database. They pin the derive rules from
docs/plan_workout_completion_derivation.md, including the user's rule that a
below-target duration is attempted but NOT completed.
"""
import pytest

from coach_mcp.completion import (
    set_has_data,
    derive_exercise_completion,
    derive_session_completion,
)


@pytest.mark.unit
class TestSetHasData:
    def test_weight_only(self):
        assert set_has_data({"weight": 24}) is True

    def test_reps_only(self):
        assert set_has_data({"reps": 10}) is True

    def test_duration_sec_only(self):
        assert set_has_data({"duration_sec": 40}) is True

    def test_placeholder_set_has_no_data(self):
        assert set_has_data({"set_num": 1}) is False

    def test_all_none(self):
        assert set_has_data(
            {"weight": None, "reps": None, "rpe": None, "duration_sec": None}
        ) is False


@pytest.mark.unit
class TestDeriveExerciseCompletion:
    def test_strength_meets_target(self):
        sets = [{"weight": 24, "reps": 10}] * 3
        r = derive_exercise_completion("strength", sets=sets, target_sets=3)
        assert r["attempted"] is True
        assert r["completed"] is True
        assert r["progress"] == {"done": 3, "target": 3}

    def test_strength_partial_below_target(self):
        sets = [{"weight": 24, "reps": 10}, {"weight": 24, "reps": 10}]
        r = derive_exercise_completion("strength", sets=sets, target_sets=3)
        assert r["attempted"] is True
        assert r["completed"] is False
        assert r["progress"] == {"done": 2, "target": 3}

    def test_strength_data_but_no_stored_flag_is_the_bug(self):
        # The exact regression: real data present, no completion flag — must
        # report completed once the target is met.
        sets = [{"weight": 100, "reps": 5, "rpe": 8}] * 3
        r = derive_exercise_completion("strength", sets=sets, target_sets=3)
        assert r["completed"] is True

    def test_strength_unknown_target_is_indeterminate(self):
        r = derive_exercise_completion("strength", sets=[{"weight": 24, "reps": 10}],
                                       target_sets=None)
        assert r["attempted"] is True
        assert r["completed"] is None

    def test_strength_not_attempted(self):
        r = derive_exercise_completion("strength", sets=[], target_sets=3)
        assert r["attempted"] is False
        assert r["completed"] is False

    def test_strength_placeholder_sets_not_attempted(self):
        r = derive_exercise_completion("strength", sets=[{"set_num": 1}, {"set_num": 2}],
                                       target_sets=3)
        assert r["attempted"] is False
        assert r["progress"] == {"done": 0, "target": 3}

    def test_checklist_meets_target(self):
        r = derive_exercise_completion("checklist", logged_items=2, planned_items=2)
        assert r["attempted"] is True
        assert r["completed"] is True

    def test_checklist_partial(self):
        r = derive_exercise_completion("checklist", logged_items=1, planned_items=3)
        assert r["completed"] is False
        assert r["progress"] == {"done": 1, "target": 3}

    def test_duration_meets_target(self):
        r = derive_exercise_completion("duration", duration_min=16, target_duration_min=15)
        assert r["attempted"] is True
        assert r["completed"] is True

    def test_duration_below_target_attempted_not_completed(self):
        # User's explicit rule (2026-05-28): below-target duration counts as
        # attempted but NOT completed.
        r = derive_exercise_completion("duration", duration_min=10, target_duration_min=15)
        assert r["attempted"] is True
        assert r["completed"] is False
        assert r["progress"] == {"done": 10, "target": 15}

    def test_duration_unknown_target_indeterminate(self):
        r = derive_exercise_completion("duration", duration_min=12, target_duration_min=None)
        assert r["attempted"] is True
        assert r["completed"] is None

    def test_interval_no_metrics_not_attempted(self):
        # Mirrors production exercise_log id 770: flagged done, but no data.
        r = derive_exercise_completion("interval", duration_min=None, target_duration_min=None)
        assert r["attempted"] is False
        assert r["completed"] is False

    def test_circuit_derives_from_sets(self):
        sets = [{"weight": 20, "reps": 12}] * 4
        r = derive_exercise_completion("circuit", sets=sets, target_sets=4)
        assert r["completed"] is True

    def test_unknown_type_infers_from_sets(self):
        r = derive_exercise_completion(None, sets=[{"reps": 10}] * 3, target_sets=3)
        assert r["attempted"] is True
        assert r["completed"] is True

    def test_unknown_type_infers_checklist(self):
        r = derive_exercise_completion("", logged_items=2, planned_items=2)
        assert r["completed"] is True


@pytest.mark.unit
class TestDeriveSessionCompletion:
    def test_all_planned_complete(self):
        results = [{"attempted": True, "completed": True}] * 3
        r = derive_session_completion(results, planned_total=3)
        assert r["attempted"] is True
        assert r["completed"] is True
        assert r["progress"] == {"done": 3, "target": 3}

    def test_planned_but_unlogged_blocks_full_completion(self):
        results = [{"attempted": True, "completed": True},
                   {"attempted": True, "completed": True}]
        r = derive_session_completion(results, planned_total=3)
        assert r["completed"] is False
        assert r["progress"] == {"done": 2, "target": 3}

    def test_unknown_verdict_not_counted_as_complete(self):
        results = [{"attempted": True, "completed": True},
                   {"attempted": True, "completed": None}]
        r = derive_session_completion(results, planned_total=2)
        assert r["completed"] is False

    def test_attempted_but_not_completed(self):
        results = [{"attempted": True, "completed": False}]
        r = derive_session_completion(results, planned_total=1)
        assert r["attempted"] is True
        assert r["completed"] is False

    def test_empty(self):
        r = derive_session_completion([], planned_total=0)
        assert r["attempted"] is False
        assert r["completed"] is False

    def test_fallback_to_logged_count(self):
        results = [{"attempted": True, "completed": True}]
        r = derive_session_completion(results)
        assert r["completed"] is True
