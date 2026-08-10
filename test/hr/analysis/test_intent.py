"""Tests for typed interval-intent parsing."""

import pytest

from hr_analysis.intent import IntervalIntent, parse_intent


# --- Absent / malformed input ---

def test_missing_intent_is_a_normal_unknown_state():
    intent = parse_intent(None)
    assert intent.source == "unknown"
    assert intent.has_structure is False
    assert intent.rounds is None


def test_non_dict_intent_rejected():
    with pytest.raises(ValueError, match="must be an object"):
        parse_intent("4x4 at threshold")


def test_empty_dict_defaults_to_user_supplied():
    # A caller who sends an object but omits `source` is supplying it themselves
    intent = parse_intent({})
    assert intent.source == "user_supplied"
    assert intent.has_structure is False


# --- source ---

@pytest.mark.parametrize("source", ["coach", "user_supplied", "inferred", "unknown"])
def test_known_sources_accepted(source):
    assert parse_intent({"source": source}).source == source


def test_blank_source_falls_back_to_user_supplied():
    assert parse_intent({"source": ""}).source == "user_supplied"


def test_unknown_source_rejected():
    with pytest.raises(ValueError, match="intent.source must be"):
        parse_intent({"source": "garmin_watch"})


# --- Structured rounds/work/rest ---

def test_full_structure_sets_has_structure():
    intent = parse_intent({"rounds": 4, "work_duration_s": 240, "rest_duration_s": 180})
    assert intent.has_structure is True
    assert (intent.rounds, intent.work_duration_s, intent.rest_duration_s) == (4, 240, 180)


@pytest.mark.parametrize("partial", [
    {"rounds": 4},
    {"work_duration_s": 240},
    {"rest_duration_s": 180},
    {"rounds": 4, "work_duration_s": 240},
    {"work_duration_s": 240, "rest_duration_s": 180},
])
def test_partial_structure_rejected(partial):
    # Half a structure would silently produce nonsense expected bouts
    with pytest.raises(ValueError, match="must be supplied together"):
        parse_intent(partial)


def test_numeric_strings_coerced_to_int():
    intent = parse_intent({"rounds": "6", "work_duration_s": "30", "rest_duration_s": "30"})
    assert intent.rounds == 6
    assert isinstance(intent.rounds, int)


def test_bool_is_not_an_integer():
    # bool is an int subclass in Python — True must not become rounds=1
    with pytest.raises(ValueError, match="rounds must be an integer"):
        parse_intent({"rounds": True})


@pytest.mark.parametrize("bad", ["many", [4], {"n": 4}, object()])
def test_uncoercible_rounds_rejected(bad):
    with pytest.raises(ValueError, match="rounds must be an integer"):
        parse_intent({"rounds": bad})


@pytest.mark.parametrize("bad", [0, -1, -240])
def test_non_positive_values_rejected(bad):
    with pytest.raises(ValueError, match="must be >= 1"):
        parse_intent({"work_duration_s": bad})


def test_explicit_none_values_stay_none():
    intent = parse_intent({"rounds": None, "target_hr_min": None})
    assert intent.rounds is None
    assert intent.target_hr_min is None


# --- Target HR band ---

def test_target_hr_band_accepted():
    intent = parse_intent({"target_hr_min": 158, "target_hr_max": 174})
    assert (intent.target_hr_min, intent.target_hr_max) == (158, 174)


def test_equal_target_hr_bounds_accepted():
    assert parse_intent({"target_hr_min": 165, "target_hr_max": 165}).target_hr_min == 165


def test_inverted_target_hr_band_rejected():
    with pytest.raises(ValueError, match="target_hr_min must be <= target_hr_max"):
        parse_intent({"target_hr_min": 180, "target_hr_max": 150})


def test_target_hr_min_alone_is_allowed():
    # Only a floor is meaningful for "get above X" interval work
    intent = parse_intent({"target_hr_min": 168})
    assert intent.target_hr_min == 168
    assert intent.target_hr_max is None


# --- Free-text fields ---

def test_text_fields_stripped():
    intent = parse_intent({"modality": "  rower  ", "notes": " 4x4 norwegian "})
    assert intent.modality == "rower"
    assert intent.notes == "4x4 norwegian"


def test_whitespace_only_text_becomes_none():
    intent = parse_intent({"modality": "   ", "notes": ""})
    assert intent.modality is None
    assert intent.notes is None


def test_non_string_text_field_stringified():
    assert parse_intent({"notes": 42}).notes == "42"


# --- Model surface ---

def test_to_dict_carries_every_field():
    intent = parse_intent({
        "source": "coach",
        "rounds": 4,
        "work_duration_s": 240,
        "rest_duration_s": 180,
        "target_hr_min": 158,
        "target_hr_max": 174,
        "modality": "bike",
        "notes": "seated, cadence 85",
    })
    assert intent.to_dict() == {
        "source": "coach",
        "rounds": 4,
        "work_duration_s": 240,
        "rest_duration_s": 180,
        "target_hr_min": 158,
        "target_hr_max": 174,
        "modality": "bike",
        "notes": "seated, cadence 85",
    }


def test_intent_is_immutable():
    intent = IntervalIntent(rounds=4)
    with pytest.raises(Exception):
        intent.rounds = 5


def test_default_model_has_no_structure():
    assert IntervalIntent().has_structure is False
