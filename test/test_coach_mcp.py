"""Tests for Coach MCP server tools and helpers."""

from datetime import date, timedelta

import pytest

from coach_mcp.config import MCPConfig
from coach_mcp.server import (
    _is_bodyweight_or_band,
    _needs_transform,
    _transform_block_plan,
    _transform_block_to_exercises,
    create_mcp_server,
)


# ==================== Unit 1: Pure Function Unit Tests ====================


@pytest.mark.unit
class TestNeedsTransform:
    """Tests for _needs_transform detection of raw LLM plans."""

    def test_exercises_missing_id(self):
        plan = {"blocks": [{"exercises": [{"name": "Squat", "type": "strength"}]}]}
        assert _needs_transform(plan) is True

    def test_exercises_missing_type(self):
        plan = {"blocks": [{"exercises": [{"id": "ex_1", "name": "Squat"}]}]}
        assert _needs_transform(plan) is True

    def test_fully_transformed_plan(self):
        plan = {"blocks": [{"exercises": [{"id": "ex_1", "name": "Squat", "type": "strength"}]}]}
        assert _needs_transform(plan) is False

    def test_instruction_block_without_exercises(self):
        plan = {"blocks": [{"instructions": ["Run 30 min zone 2"], "block_type": "cardio"}]}
        assert _needs_transform(plan) is True


@pytest.mark.unit
class TestIsBodyweightOrBand:
    """Tests for _is_bodyweight_or_band keyword matching."""

    def test_pushup_variations(self):
        assert _is_bodyweight_or_band("Push-ups") is True
        assert _is_bodyweight_or_band("Diamond Pushup") is True

    def test_band_exercise(self):
        assert _is_bodyweight_or_band("Band Pull Apart") is True

    def test_weighted_exercise(self):
        assert _is_bodyweight_or_band("Bench Press") is False


@pytest.mark.unit
class TestTransformBlockToExercises:
    """Tests for _transform_block_to_exercises block-level transform."""

    def test_warmup_aggregates_to_checklist(self):
        block = {
            "block_type": "warmup",
            "title": "Warmup",
            "exercises": [
                {"name": "Arm Circles", "reps": 10},
                {"name": "Cat-Cow", "reps": "10 each"},
            ],
        }
        result = _transform_block_to_exercises(block, 0)
        assert len(result) == 1
        ex = result[0]
        assert ex["type"] == "checklist"
        assert ex["id"] == "warmup_0"
        assert "Arm Circles x10" in ex["items"]
        assert "Cat-Cow 10 each" in ex["items"]

    def test_strength_sets_and_reps(self):
        block = {
            "block_type": "strength",
            "title": "Main Lifts",
            "rest_guidance": "Rest 2 min",
            "exercises": [
                {"name": "Bench Press", "sets": 4, "reps": "6-8"},
            ],
        }
        result = _transform_block_to_exercises(block, 1)
        assert len(result) == 1
        ex = result[0]
        assert ex["type"] == "strength"
        assert ex["id"] == "strength_1_1"
        assert ex["target_sets"] == 4
        assert ex["target_reps"] == "6-8"
        assert "Rest 2 min" in ex["guidance_note"]

    def test_circuit_block_type(self):
        block = {
            "block_type": "circuit",
            "title": "Finisher",
            "exercises": [{"name": "KB Swing", "reps": 15}],
        }
        result = _transform_block_to_exercises(block, 2)
        assert result[0]["type"] == "circuit"

    def test_bodyweight_hides_weight(self):
        block = {
            "block_type": "strength",
            "title": "Bodyweight",
            "exercises": [{"name": "Push-ups", "sets": 3, "reps": 15}],
        }
        result = _transform_block_to_exercises(block, 0)
        assert result[0]["hide_weight"] is True

    def test_explicit_equipment_bodyweight_hides_weight(self):
        block = {
            "block_type": "strength",
            "title": "Core",
            "exercises": [{"name": "Custom Move", "equipment": "bodyweight", "sets": 3, "reps": 10}],
        }
        result = _transform_block_to_exercises(block, 0)
        assert result[0]["hide_weight"] is True

    def test_instruction_block_duration(self):
        block = {
            "block_type": "cardio",
            "title": "Zone 2 Cardio",
            "duration_min": 30,
            "instructions": ["Keep HR 130-145", "Easy pace"],
        }
        result = _transform_block_to_exercises(block, 3)
        assert len(result) == 1
        ex = result[0]
        assert ex["type"] == "duration"
        assert ex["target_duration_min"] == 30
        assert "Keep HR 130-145" in ex["guidance_note"]

    def test_instruction_block_vo2max_interval(self):
        block = {
            "block_type": "cardio",
            "title": "VO2 Max",
            "duration_min": 20,
            "instructions": ["4x4 min HARD intervals", "VO2 effort"],
        }
        result = _transform_block_to_exercises(block, 0)
        ex = result[0]
        assert ex["type"] == "interval"
        assert ex["name"] == "VO2 Max Intervals"


@pytest.mark.unit
class TestTransformBlockPlan:
    """Tests for _transform_block_plan full plan transform."""

    def test_full_plan_transform(self):
        raw = {
            "theme": "Upper Body",
            "location": "Gym",
            "phase": "Building",
            "total_duration_min": 60,
            "blocks": [
                {
                    "block_type": "warmup",
                    "title": "Warmup",
                    "exercises": [{"name": "Arm Circles", "reps": 10}],
                },
                {
                    "block_type": "strength",
                    "title": "Main",
                    "exercises": [{"name": "Bench Press", "sets": 4, "reps": "6-8"}],
                },
            ],
        }
        result = _transform_block_plan(raw)
        assert result["day_name"] == "Upper Body"
        assert result["location"] == "Gym"
        assert result["phase"] == "Building"
        assert len(result["blocks"]) == 2
        # Warmup block has checklist exercise
        assert result["blocks"][0]["exercises"][0]["type"] == "checklist"
        # Strength block has strength exercise
        assert result["blocks"][1]["exercises"][0]["type"] == "strength"

    def test_theme_maps_to_day_name(self):
        raw = {
            "theme": "Lower Body Power",
            "blocks": [],
        }
        result = _transform_block_plan(raw)
        assert result["day_name"] == "Lower Body Power"
        # day_name not in raw, so theme is used
        assert "theme" not in result


# ==================== Unit 2: Read Tools Integration Tests ====================


def _extract_tools(mcp_server):
    """Extract tool functions from MCP server by name."""
    tools = {}
    for tool in mcp_server._tool_manager._tools.values():
        tools[tool.fn.__name__] = tool.fn
    return tools


@pytest.mark.integration
class TestReadTools:
    """Tests for read-only coach MCP tools against a seeded database."""

    @pytest.fixture(autouse=True)
    def setup_mcp(self, test_app, coach_seeded_database, tmp_coach_db):
        self.seed = coach_seeded_database
        config = MCPConfig(db_path=tmp_coach_db)
        mcp = create_mcp_server(config)
        self.tools = _extract_tools(mcp)

    def test_get_workout_plan_returns_seeded_plans(self):
        today = self.seed["dates"][0]
        yesterday = self.seed["dates"][1]
        result = self.tools["get_workout_plan"](
            start_date=yesterday, end_date=today
        )
        assert len(result) == 2
        dates = [r["date"] for r in result]
        assert today in dates
        assert yesterday in dates

    def test_get_workout_plan_empty_range(self):
        result = self.tools["get_workout_plan"](
            start_date="2099-01-01", end_date="2099-01-31"
        )
        assert result == []

    def test_get_workout_plan_structure(self):
        today = self.seed["dates"][0]
        result = self.tools["get_workout_plan"](
            start_date=today, end_date=today
        )
        assert len(result) == 1
        plan = result[0]["plan"]
        assert "day_name" in plan
        assert "location" in plan
        assert "phase" in plan
        assert "blocks" in plan
        assert len(plan["blocks"]) >= 1
        block = plan["blocks"][0]
        assert "block_type" in block
        assert "exercises" in block

    def test_get_workout_logs_returns_seeded_log(self):
        today = self.seed["dates"][0]
        result = self.tools["get_workout_logs"](
            start_date=today, end_date=today
        )
        assert len(result) == 1
        log = result[0]["log"]
        assert "session_feedback" in log
        assert log["session_feedback"]["general_notes"] == "Good session"
        # Check exercise log is present
        assert "ex_1" in log
        assert log["ex_1"]["sets"][0]["weight"] == 24

    def test_get_workout_logs_empty_range(self):
        result = self.tools["get_workout_logs"](
            start_date="2099-01-01", end_date="2099-01-31"
        )
        assert result == []

    def test_get_workout_summary(self):
        result = self.tools["get_workout_summary"](days=30)
        assert result["planned_workouts"] >= 2
        assert result["completed_workouts"] >= 1
        assert "completion_rate_percent" in result
        assert "exercise_types_in_recent_plans" in result

    def test_get_workout_summary_max_days(self):
        with pytest.raises(ValueError, match="cannot exceed 365"):
            self.tools["get_workout_summary"](days=366)

    def test_list_scheduled_dates(self):
        today = self.seed["dates"][0]
        yesterday = self.seed["dates"][1]
        result = self.tools["list_scheduled_dates"](
            start_date=yesterday, end_date=today
        )
        assert today in result
        assert yesterday in result

    def test_list_scheduled_dates_defaults(self):
        # No args should use today..+6 weeks — should not error
        result = self.tools["list_scheduled_dates"]()
        assert isinstance(result, list)


# ==================== Unit 3: Write Tools Integration Tests ====================


@pytest.mark.integration
class TestWriteTools:
    """Tests for write coach MCP tools. Uses future dates to avoid seed collisions."""

    FUTURE = "2099-01-15"
    FUTURE2 = "2099-01-16"
    FUTURE3 = "2099-01-17"

    @pytest.fixture(autouse=True)
    def setup_mcp(self, test_app, coach_seeded_database, tmp_coach_db):
        self.seed = coach_seeded_database
        config = MCPConfig(db_path=tmp_coach_db)
        mcp = create_mcp_server(config)
        self.tools = _extract_tools(mcp)

    def _make_plan(self, **overrides):
        """Build a minimal valid plan dict."""
        plan = {
            "day_name": "Test Plan",
            "location": "Gym",
            "phase": "Foundation",
            "blocks": [
                {
                    "block_type": "strength",
                    "title": "Main",
                    "exercises": [
                        {
                            "id": "test_ex_1",
                            "name": "Test Exercise",
                            "type": "strength",
                            "target_sets": 3,
                            "target_reps": "10",
                        }
                    ],
                }
            ],
        }
        plan.update(overrides)
        return plan

    # --- set_workout_plan ---

    def test_set_workout_plan_creates(self):
        result = self.tools["set_workout_plan"](
            date=self.FUTURE, plan=self._make_plan()
        )
        assert result["success"] is True
        assert result["date"] == self.FUTURE
        assert result["plan"]["day_name"] == "Test Plan"

    def test_set_workout_plan_invalid_date(self):
        with pytest.raises(ValueError, match="Invalid date format"):
            self.tools["set_workout_plan"](
                date="not-a-date", plan=self._make_plan()
            )

    def test_set_workout_plan_missing_blocks(self):
        with pytest.raises(ValueError, match="must have 'blocks'"):
            self.tools["set_workout_plan"](
                date=self.FUTURE2, plan={"day_name": "No blocks"}
            )

    def test_set_workout_plan_invalid_block_type(self):
        plan = self._make_plan()
        plan["blocks"][0]["block_type"] = "yoga"
        with pytest.raises(ValueError, match="invalid block_type"):
            self.tools["set_workout_plan"](date=self.FUTURE2, plan=plan)

    def test_set_workout_plan_block_missing_type(self):
        plan = self._make_plan()
        del plan["blocks"][0]["block_type"]
        with pytest.raises(ValueError, match="missing 'block_type'"):
            self.tools["set_workout_plan"](date=self.FUTURE2, plan=plan)

    def test_set_workout_plan_auto_transform(self):
        """Raw LLM format (no id/type on exercises) should be auto-transformed."""
        raw_plan = {
            "theme": "Upper Body",
            "location": "Gym",
            "phase": "Building",
            "blocks": [
                {
                    "block_type": "warmup",
                    "title": "Warmup",
                    "exercises": [{"name": "Arm Circles", "reps": 10}],
                },
                {
                    "block_type": "strength",
                    "title": "Main",
                    "exercises": [
                        {"name": "Bench Press", "sets": 4, "reps": "6-8"},
                    ],
                },
            ],
        }
        result = self.tools["set_workout_plan"](date=self.FUTURE2, plan=raw_plan)
        assert result["success"] is True
        plan = result["plan"]
        # Should have been transformed: exercises now have id and type
        for block in plan["blocks"]:
            for ex in block["exercises"]:
                assert "id" in ex
                assert "type" in ex

    def test_set_workout_plan_rejects_overwrite_with_log(self):
        """Cannot replace a plan that has a workout log."""
        today = self.seed["dates"][0]
        with pytest.raises(ValueError, match="workout log exists"):
            self.tools["set_workout_plan"](
                date=today, plan=self._make_plan()
            )

    # --- update_exercise ---

    def test_update_exercise_success(self):
        # Create a plan first
        self.tools["set_workout_plan"](date=self.FUTURE3, plan=self._make_plan())
        result = self.tools["update_exercise"](
            date=self.FUTURE3,
            exercise_id="test_ex_1",
            updates={"target_reps": "12", "guidance_note": "Slow tempo"},
        )
        assert result["success"] is True
        assert result["updated_exercise"]["target_reps"] == "12"
        assert result["updated_exercise"]["guidance_note"] == "Slow tempo"

    def test_update_exercise_not_found(self):
        self.tools["set_workout_plan"](date=self.FUTURE3, plan=self._make_plan())
        with pytest.raises(ValueError, match="not found"):
            self.tools["update_exercise"](
                date=self.FUTURE3,
                exercise_id="nonexistent",
                updates={"target_reps": "5"},
            )

    # --- add_exercise ---

    def test_add_exercise_success(self):
        self.tools["set_workout_plan"](date=self.FUTURE3, plan=self._make_plan())
        new_ex = {
            "id": "added_ex",
            "name": "Lateral Raise",
            "type": "strength",
            "target_sets": 3,
            "target_reps": "12",
        }
        result = self.tools["add_exercise"](
            date=self.FUTURE3, exercise=new_ex, block_position=0
        )
        assert result["success"] is True
        assert result["total_exercises"] == 2

    def test_add_exercise_duplicate_id(self):
        self.tools["set_workout_plan"](date=self.FUTURE3, plan=self._make_plan())
        dup_ex = {
            "id": "test_ex_1",
            "name": "Duplicate",
            "type": "strength",
        }
        with pytest.raises(ValueError, match="already exists"):
            self.tools["add_exercise"](
                date=self.FUTURE3, exercise=dup_ex, block_position=0
            )

    def test_add_exercise_invalid_type(self):
        self.tools["set_workout_plan"](date=self.FUTURE3, plan=self._make_plan())
        bad_ex = {"id": "bad_type", "name": "Bad", "type": "yoga"}
        with pytest.raises(ValueError, match="Invalid exercise type"):
            self.tools["add_exercise"](
                date=self.FUTURE3, exercise=bad_ex, block_position=0
            )

    def test_add_exercise_missing_field(self):
        with pytest.raises(ValueError, match="missing required field"):
            self.tools["add_exercise"](
                date=self.FUTURE3,
                exercise={"id": "x", "name": "X"},  # missing type
                block_position=0,
            )

    # --- remove_exercise ---

    def test_remove_exercise_success(self):
        self.tools["set_workout_plan"](date=self.FUTURE3, plan=self._make_plan())
        result = self.tools["remove_exercise"](
            date=self.FUTURE3, exercise_id="test_ex_1"
        )
        assert result["success"] is True
        assert result["remaining_exercises"] == 0

    def test_remove_exercise_not_found(self):
        self.tools["set_workout_plan"](date=self.FUTURE3, plan=self._make_plan())
        with pytest.raises(ValueError, match="not found"):
            self.tools["remove_exercise"](
                date=self.FUTURE3, exercise_id="nonexistent"
            )

    # --- delete_workout_plan ---

    def test_delete_workout_plan_success(self):
        self.tools["set_workout_plan"](date=self.FUTURE3, plan=self._make_plan())
        result = self.tools["delete_workout_plan"](date=self.FUTURE3)
        assert result["success"] is True
        # Verify it's gone
        plans = self.tools["get_workout_plan"](
            start_date=self.FUTURE3, end_date=self.FUTURE3
        )
        assert plans == []

    def test_delete_workout_plan_guarded_by_log(self):
        today = self.seed["dates"][0]
        with pytest.raises(ValueError, match="workout log exists"):
            self.tools["delete_workout_plan"](date=today)

    def test_delete_workout_plan_not_found(self):
        with pytest.raises(ValueError, match="No plan found"):
            self.tools["delete_workout_plan"](date="2099-12-31")

    # --- update_plan_metadata ---

    def test_update_plan_metadata_success(self):
        self.tools["set_workout_plan"](date=self.FUTURE3, plan=self._make_plan())
        result = self.tools["update_plan_metadata"](
            date=self.FUTURE3,
            updates={"day_name": "Renamed", "location": "Home"},
        )
        assert result["success"] is True
        assert result["plan_metadata"]["day_name"] == "Renamed"
        assert result["plan_metadata"]["location"] == "Home"

    def test_update_plan_metadata_invalid_field(self):
        self.tools["set_workout_plan"](date=self.FUTURE3, plan=self._make_plan())
        with pytest.raises(ValueError, match="Invalid metadata fields"):
            self.tools["update_plan_metadata"](
                date=self.FUTURE3, updates={"bogus": "value"}
            )

    def test_update_plan_metadata_no_plan(self):
        with pytest.raises(ValueError, match="No plan found"):
            self.tools["update_plan_metadata"](
                date="2099-12-31", updates={"day_name": "X"}
            )

    # --- ingest_training_program ---

    def test_ingest_training_program_success(self):
        plans = {
            "2099-06-01": self._make_plan(day_name="Day 1"),
            "2099-06-02": self._make_plan(day_name="Day 2"),
        }
        result = self.tools["ingest_training_program"](plans=plans)
        assert result["success_count"] == 2
        assert result["failed_count"] == 0

    def test_ingest_training_program_mixed_results(self):
        plans = {
            "2099-07-01": self._make_plan(day_name="Good"),
            "bad-date": self._make_plan(day_name="Bad Date"),
        }
        result = self.tools["ingest_training_program"](plans=plans)
        assert result["success_count"] == 1
        assert result["failed_count"] == 1
