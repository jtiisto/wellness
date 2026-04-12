"""Tests for Coach MCP server tools and helpers."""

import pytest

from coach_mcp.server import (
    _is_bodyweight_or_band,
    _needs_transform,
    _transform_block_plan,
    _transform_block_to_exercises,
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
