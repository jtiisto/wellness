"""Cross-transport contract + unit tests for the shared coach domain modules
(Phase 3). Pins that the FastAPI router and the MCP server assemble identical
shapes from the same DB row — the durable guard against the §3.15 divergence."""
import sqlite3

import pytest

import modules.coach as coach_mod
from modules.coach_plans import assemble_plan, store_plan
from coach_mcp.server import _assemble_plan_from_db, _assemble_log_from_db


def _seed_plan(db_path):
    """Seed one workout session with a strength + checklist block. Returns its id.

    Uses the coach schema created by the test_app fixture (or coach init)."""
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO workout_sessions (date, day_name, location, phase, duration_min, "
        "last_modified, modified_by) VALUES ('2026-05-30','Push','Home','Foundation',45,"
        "'2026-05-30T00:00:00Z','test')"
    )
    sid = cur.lastrowid
    cur.execute(
        "INSERT INTO session_blocks (session_id, position, block_type, title, rest_guidance) "
        "VALUES (?, 0, 'strength', 'Strength', 'Rest 2 min')", (sid,)
    )
    bid = cur.lastrowid
    cur.execute(
        "INSERT INTO planned_exercises (session_id, block_id, exercise_key, position, name, "
        "exercise_type, target_sets, target_reps, guidance_note) "
        "VALUES (?, ?, 'ex_1', 0, 'Goblet Squat', 'strength', 3, '10', 'Tempo 3-1-1')",
        (sid, bid),
    )
    cur.execute(
        "INSERT INTO planned_exercises (session_id, block_id, exercise_key, position, name, "
        "exercise_type) VALUES (?, ?, 'warmup_0', 1, 'Mobility', 'checklist')", (sid, bid)
    )
    eid = cur.lastrowid
    cur.execute("INSERT INTO checklist_items (exercise_id, position, item_text) "
                "VALUES (?, 0, 'Cat-Cow x10')", (eid,))
    conn.commit()
    conn.close()
    return sid


@pytest.mark.unit
def test_plan_assemblers_agree_across_transports(test_app, tmp_coach_db):
    """§3.15: the FastAPI router (_assemble_plan) and the MCP server
    (_assemble_plan_from_db) must return byte-identical plan dicts from the same
    session row — both now via the shared canonical assemble_plan."""
    sid = _seed_plan(tmp_coach_db)

    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM workout_sessions WHERE id = ?", (sid,)).fetchone()

    router_plan = coach_mod._assemble_plan(conn, row)
    mcp_plan = _assemble_plan_from_db(conn.cursor(), sid)
    conn.close()

    assert router_plan == mcp_plan
    assert router_plan["session_id"] == sid  # canonical shape includes session_id


@pytest.mark.unit
def test_assemble_plan_shape(test_app, tmp_coach_db):
    """Direct unit test of the canonical reader (reachable without booting MCP)."""
    sid = _seed_plan(tmp_coach_db)
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM workout_sessions WHERE id = ?", (sid,)).fetchone()
    plan = assemble_plan(conn.cursor(), row)
    conn.close()

    assert plan["session_id"] == sid
    assert plan["day_name"] == "Push"
    assert plan["total_duration_min"] == 45
    assert len(plan["blocks"]) == 1
    block = plan["blocks"][0]
    assert block["block_type"] == "strength"
    assert [e["id"] for e in block["exercises"]] == ["ex_1", "warmup_0"]
    strength, checklist = block["exercises"]
    assert strength["target_sets"] == 3 and strength["target_reps"] == "10"
    assert checklist["items"] == ["Cat-Cow x10"]
    # absent optional fields are omitted, not null
    assert "target_duration_min" not in strength


@pytest.mark.unit
def test_store_plan_round_trips_via_assemble(test_app, tmp_coach_db):
    """The moved write helpers (store_plan -> insert_block) round-trip through
    the canonical reader — directly unit-testable now, no MCP boot needed."""
    plan = {
        "day_name": "Pull", "location": "Gym", "phase": "Build",
        "total_duration_min": 50,
        "blocks": [{
            "block_type": "strength", "title": "Main", "rest_guidance": "Rest 2 min",
            "exercises": [
                {"id": "row_1", "name": "Row", "type": "strength",
                 "target_sets": 4, "target_reps": "8"},
                {"id": "wu", "name": "Warmup", "type": "checklist", "items": ["Band x10"]},
            ],
        }],
    }
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    sid = store_plan(conn.cursor(), "2026-06-01", plan, modified_by="test")
    conn.commit()
    row = conn.execute("SELECT * FROM workout_sessions WHERE id = ?", (sid,)).fetchone()
    got = assemble_plan(conn.cursor(), row)
    conn.close()

    assert got["day_name"] == "Pull"
    assert got["total_duration_min"] == 50
    blk = got["blocks"][0]
    assert blk["block_type"] == "strength" and blk["rest_guidance"] == "Rest 2 min"
    assert [e["id"] for e in blk["exercises"]] == ["row_1", "wu"]
    assert blk["exercises"][0]["target_sets"] == 4
    assert blk["exercises"][1]["items"] == ["Band x10"]


@pytest.mark.unit
def test_tempo_promoted_to_field_not_folded_into_guidance_note():
    """Tempo is a structured field: the raw->formed transform surfaces it as
    `tempo` and never appends "Tempo X" to guidance_note. Free-form `notes`
    still folds into the note."""
    from modules.coach_plans import transform_block_to_exercises

    block = {
        "block_type": "strength",
        "title": "Main",
        "exercises": [
            {"name": "Goblet Squat", "sets": 3, "reps": "10",
             "tempo": "3-1-2-0", "notes": "Brace hard"},
        ],
    }
    [ex] = transform_block_to_exercises(block, 0)

    assert ex["tempo"] == "3-1-2-0"
    assert ex.get("guidance_note") == "Brace hard"
    assert "Tempo" not in (ex.get("guidance_note") or "")


@pytest.mark.unit
def test_intensity_promoted_to_fields_not_folded_into_guidance_note():
    """RPE and load are structured fields now: raw `rpe`/`load_guide` cues map to
    `target_rpe` / `target_load` and are NOT folded into guidance_note. Only the
    free-form `notes` cue still folds."""
    from modules.coach_plans import transform_block_to_exercises

    block = {
        "block_type": "strength",
        "title": "Main",
        "exercises": [
            {"name": "Back Squat", "sets": 3, "reps": "5",
             "rpe": "6-7", "load_guide": "70%", "notes": "Belt on top sets"},
        ],
    }
    [ex] = transform_block_to_exercises(block, 0)

    assert ex["target_rpe"] == "6-7"
    assert ex["target_load"] == "70%"
    assert ex.get("guidance_note") == "Belt on top sets"
    assert "70%" not in (ex.get("guidance_note") or "")
    # raw aliases are consumed
    assert "rpe" not in ex and "load_guide" not in ex


@pytest.mark.unit
def test_store_plan_round_trips_tempo(test_app, tmp_coach_db):
    """tempo on a planned strength exercise persists and reads back via the
    canonical assembler — trimmed to text, and omitted entirely when absent."""
    plan = {
        "day_name": "Legs", "total_duration_min": 40,
        "blocks": [{
            "block_type": "strength", "title": "Main",
            "exercises": [
                {"id": "sq", "name": "Squat", "type": "strength",
                 "target_sets": 3, "target_reps": "5", "tempo": " 30X1 "},
                {"id": "dl", "name": "Deadlift", "type": "strength",
                 "target_sets": 1, "target_reps": "5"},
            ],
        }],
    }
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    sid = store_plan(conn.cursor(), "2026-06-02", plan, modified_by="test")
    conn.commit()
    row = conn.execute("SELECT * FROM workout_sessions WHERE id=?", (sid,)).fetchone()
    got = assemble_plan(conn.cursor(), row)
    conn.close()

    exs = {e["id"]: e for e in got["blocks"][0]["exercises"]}
    assert exs["sq"]["tempo"] == "30X1"      # normalized (trimmed) text
    assert "tempo" not in exs["dl"]          # omitted when absent


@pytest.mark.unit
def test_store_plan_round_trips_intensity_fields(test_app, tmp_coach_db):
    """target_rpe (range) and target_load on a planned strength exercise persist
    and read back via the canonical assembler — trimmed, and omitted when absent."""
    plan = {
        "day_name": "Legs", "total_duration_min": 40,
        "blocks": [{
            "block_type": "strength", "title": "Main",
            "exercises": [
                {"id": "sq", "name": "Squat", "type": "strength",
                 "target_sets": 3, "target_reps": "5",
                 "target_rpe": "6-7", "target_load": " 70% "},
                {"id": "dl", "name": "Deadlift", "type": "strength",
                 "target_sets": 1, "target_reps": "5"},
            ],
        }],
    }
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    sid = store_plan(conn.cursor(), "2026-06-03", plan, modified_by="test")
    conn.commit()
    row = conn.execute("SELECT * FROM workout_sessions WHERE id=?", (sid,)).fetchone()
    got = assemble_plan(conn.cursor(), row)
    conn.close()

    exs = {e["id"]: e for e in got["blocks"][0]["exercises"]}
    assert exs["sq"]["target_rpe"] == "6-7"
    assert exs["sq"]["target_load"] == "70%"        # trimmed
    assert "target_rpe" not in exs["dl"]            # omitted when absent
    assert "target_load" not in exs["dl"]


@pytest.mark.unit
@pytest.mark.parametrize("raw,expected", [
    ("HEAVY", "HEAVY"),                    # already canonical → unchanged
    ("  heavy  ", "HEAVY"),                # trimmed + case-folded UP
    ("light   day", "LIGHT DAY"),          # internal whitespace collapsed
    ("Technique\tWork", "TECHNIQUE WORK"),  # tabs/newlines are whitespace too
    (None, None),                          # absence stays absence
    ("", None),                            # empty means "no exposure"
    ("   ", None),                         # whitespace-only likewise
    ("E" * 32, "E" * 32),                  # exactly at the cap is accepted
])
def test_normalize_exposure(raw, expected):
    """The single normalization authority: `"  heavy "` and `"HEAVY"` must be the
    same identity key on every write path, and blank input must read as absent."""
    from modules.coach_plans import normalize_exposure

    assert normalize_exposure(raw) == expected


@pytest.mark.unit
def test_normalize_exposure_rejects_bad_values():
    """Non-strings and over-length keys raise. Over-length is REJECTED, never
    truncated — truncating would silently merge two distinct exposure keys."""
    from modules.coach_plans import EXPOSURE_MAX_LENGTH, normalize_exposure

    with pytest.raises(ValueError, match="must be a string"):
        normalize_exposure(3)
    with pytest.raises(ValueError, match="must be a string"):
        normalize_exposure(["HEAVY"])
    with pytest.raises(ValueError, match="maximum"):
        normalize_exposure("E" * (EXPOSURE_MAX_LENGTH + 1))


@pytest.mark.unit
def test_store_plan_round_trips_exposure_on_a_subset(test_app, tmp_coach_db):
    """Acceptance sketch: a plan with exposures on 3 of 8 exercises reads back
    with the (normalized) key on exactly those 3 — the other 5 omit it entirely,
    because absence is meaningful and must stay cheap."""
    exposures = {"ex_2": " heavy ", "ex_5": "light", "ex_7": "Technique  Work"}
    plan = {
        "day_name": "Full Body", "total_duration_min": 55,
        "blocks": [{
            "block_type": "strength", "title": "Main",
            "exercises": [
                dict(
                    {"id": f"ex_{n}", "name": f"Movement {n}", "type": "strength",
                     "target_sets": 3, "target_reps": "8"},
                    **({"exposure": exposures[f"ex_{n}"]} if f"ex_{n}" in exposures else {}),
                )
                for n in range(1, 9)
            ],
        }],
    }
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    sid = store_plan(conn.cursor(), "2026-06-04", plan, modified_by="test")
    conn.commit()
    row = conn.execute("SELECT * FROM workout_sessions WHERE id=?", (sid,)).fetchone()
    got = assemble_plan(conn.cursor(), row)
    conn.close()

    exs = {e["id"]: e for e in got["blocks"][0]["exercises"]}
    assert len(exs) == 8
    assert {k: v["exposure"] for k, v in exs.items() if "exposure" in v} == {
        "ex_2": "HEAVY", "ex_5": "LIGHT", "ex_7": "TECHNIQUE WORK",
    }


@pytest.mark.unit
def test_store_plan_rejects_invalid_exposure(test_app, tmp_coach_db):
    """validate_plan runs inside store_plan, so a bad exposure is rejected with
    the exercise index before any row is written."""
    from modules.coach_plans import EXPOSURE_MAX_LENGTH

    plan = {
        "day_name": "Legs", "total_duration_min": 40,
        "blocks": [{
            "block_type": "strength", "title": "Main",
            "exercises": [
                {"id": "sq", "name": "Squat", "type": "strength"},
                {"id": "dl", "name": "Deadlift", "type": "strength",
                 "exposure": "E" * (EXPOSURE_MAX_LENGTH + 1)},
            ],
        }],
    }
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    with pytest.raises(ValueError, match="Exercise 1"):
        store_plan(conn.cursor(), "2026-06-05", plan, modified_by="test")
    conn.rollback()
    written = conn.execute(
        "SELECT COUNT(*) FROM workout_sessions WHERE date='2026-06-05'"
    ).fetchone()[0]
    conn.close()
    assert written == 0


@pytest.mark.unit
def test_transform_passes_exposure_through():
    """`exposure` is a canonical plan-JSON key, not a raw alias: the raw->formed
    transform copies it verbatim (normalization is insert_block's job) and never
    pops it, and an exercise without one gains nothing."""
    from modules.coach_plans import transform_block_plan

    raw = {
        "theme": "Lower", "blocks": [{
            "block_type": "strength", "title": "Main",
            "exercises": [
                {"name": "Back Squat", "sets": 3, "reps": "5", "exposure": "heavy"},
                {"name": "Split Squat", "sets": 3, "reps": "8"},
            ],
        }],
    }
    exs = transform_block_plan(raw)["blocks"][0]["exercises"]

    assert exs[0]["exposure"] == "heavy"
    assert "exposure" not in exs[1]


# ==================== segments (cardio target-HR timeline) ====================


@pytest.mark.unit
def test_normalize_segments_canonicalizes_a_timeline():
    """The authority returns each segment rebuilt with only the keys it carries,
    in a fixed order, so storage is deterministic and the sparse-omit convention
    holds inside the blob too."""
    from modules.coach_plans import normalize_segments

    assert normalize_segments([
        {"hr_max": 150, "duration_sec": 120, "label": "  easy  "},
        {"duration_sec": 180, "hr_min": 160, "hr_max": 175},
        {"duration_sec": 300, "hr_min": 125, "label": ""},
    ]) == [
        {"duration_sec": 120, "hr_max": 150, "label": "easy"},
        {"duration_sec": 180, "hr_min": 160, "hr_max": 175},
        {"duration_sec": 300, "hr_min": 125},
    ]


@pytest.mark.unit
@pytest.mark.parametrize("raw", [None, []])
def test_normalize_segments_absence(raw):
    """No timeline and an empty timeline are the same thing, and neither may
    reach the wire as `"segments": []`."""
    from modules.coach_plans import normalize_segments, segments_to_json

    assert normalize_segments(raw) is None
    assert segments_to_json(raw, "duration") is None


@pytest.mark.unit
@pytest.mark.parametrize("segments,message", [
    ({"duration_sec": 60, "hr_max": 130}, "must be a list"),   # not a list
    ([["duration_sec", 60]], "must be an object"),
    ([{"hr_max": 130}], "missing 'duration_sec'"),
    ([{"duration_sec": 0, "hr_max": 130}], ">= 1"),
    ([{"duration_sec": True, "hr_max": 130}], "must be an integer"),
    ([{"duration_sec": "soon", "hr_max": 130}], "must be an integer"),
    ([{"duration_sec": 60, "hr_min": False}], "must be an integer"),
    ([{"duration_sec": 60, "hr_max": 0}], ">= 1"),
    ([{"duration_sec": 60}], "at least one"),
    ([{"duration_sec": 60, "hr_min": 150, "hr_max": 140}], "must be <="),
    ([{"duration_sec": 60, "hr_max": 140, "label": 7}], "must be a string"),
    ([{"duration_sec": 60, "hr_maxx": 140}], "unknown field"),
])
def test_normalize_segments_rejects(segments, message):
    """The IntervalIntent rules, generalized — booleans are not integers, the
    floor is 1, one bound at least, min <= max — plus a closed key set: a
    misspelled `hr_maxx` must fail loudly rather than store a floor-only segment
    that reads as deliberate."""
    from modules.coach_plans import normalize_segments

    with pytest.raises(ValueError, match=message):
        normalize_segments(segments)


@pytest.mark.unit
def test_normalize_segments_names_the_offending_segment():
    """The index is in the message: a VO2 timeline is a dozen segments long and
    "some segment is wrong" is not an actionable error for the authoring LLM."""
    from modules.coach_plans import normalize_segments

    with pytest.raises(ValueError, match=r"segments\[2\]"):
        normalize_segments([
            {"duration_sec": 60, "hr_max": 130},
            {"duration_sec": 60, "hr_max": 130},
            {"duration_sec": 60},
        ])


@pytest.mark.unit
@pytest.mark.parametrize("ex_type", ["strength", "checklist", "weighted_time", "circuit"])
def test_segments_rejected_on_non_cardio_types(ex_type):
    """A timeline describes a continuous stretch of cardio time; a strength or
    checklist exercise has no clock for it to sit on."""
    from modules.coach_plans import validate_exercise_segments

    with pytest.raises(ValueError, match="only valid on"):
        validate_exercise_segments([{"duration_sec": 60, "hr_max": 130}], ex_type)


@pytest.mark.unit
@pytest.mark.parametrize("ex_type", ["duration", "interval"])
def test_segments_accepted_on_cardio_types(ex_type):
    from modules.coach_plans import validate_exercise_segments

    assert validate_exercise_segments([{"duration_sec": 60, "hr_max": 130}], ex_type) == [
        {"duration_sec": 60, "hr_max": 130},
    ]


@pytest.mark.unit
@pytest.mark.parametrize("raw", [None, "", "not json", '{"duration_sec": 60}', "[]"])
def test_segments_from_json_tolerates_a_hand_edited_row(raw):
    """Only validated writes reach the column, so an unparseable value is a
    hand-edited row: the field drops off that one exercise rather than failing
    the whole day's sync (the journal's `_load_json_list` precedent)."""
    from modules.coach_plans import segments_from_json

    assert segments_from_json(raw) is None


@pytest.mark.unit
def test_store_plan_round_trips_segments(test_app, tmp_coach_db):
    """The timeline survives the JSON TEXT column intact and in order, and the
    cardio exercise beside it that has none omits the key entirely."""
    plan = {
        "day_name": "VO2", "total_duration_min": 40,
        "blocks": [{
            "block_type": "cardio", "title": "Conditioning",
            "exercises": [
                {"id": "vo2", "name": "Bike Intervals", "type": "interval",
                 "target_duration_min": 16,
                 "segments": [
                     {"duration_sec": 300, "hr_min": 121, "hr_max": 137, "label": "warmup"},
                     {"duration_sec": 180, "hr_min": 159, "hr_max": 174, "label": "hard"},
                     {"duration_sec": 120, "hr_max": 146, "label": "easy"},
                 ]},
                {"id": "walk", "name": "Easy Walk", "type": "duration",
                 "target_duration_min": 10},
            ],
        }],
    }
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    sid = store_plan(conn.cursor(), "2026-06-06", plan, modified_by="test")
    conn.commit()
    row = conn.execute("SELECT * FROM workout_sessions WHERE id=?", (sid,)).fetchone()
    got = assemble_plan(conn.cursor(), row)
    conn.close()

    exs = {e["id"]: e for e in got["blocks"][0]["exercises"]}
    assert exs["vo2"]["segments"] == plan["blocks"][0]["exercises"][0]["segments"]
    assert "segments" not in exs["walk"]


@pytest.mark.unit
def test_store_plan_rejects_segments_on_a_strength_exercise(test_app, tmp_coach_db):
    """validate_plan runs inside store_plan, so the type rule is enforced with
    the exercise index before any row is written."""
    plan = {
        "day_name": "Legs", "total_duration_min": 40,
        "blocks": [{
            "block_type": "strength", "title": "Main",
            "exercises": [
                {"id": "sq", "name": "Squat", "type": "strength"},
                {"id": "dl", "name": "Deadlift", "type": "strength",
                 "segments": [{"duration_sec": 60, "hr_max": 130}]},
            ],
        }],
    }
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    with pytest.raises(ValueError, match="Exercise 1"):
        store_plan(conn.cursor(), "2026-06-07", plan, modified_by="test")
    conn.rollback()
    written = conn.execute(
        "SELECT COUNT(*) FROM workout_sessions WHERE date='2026-06-07'"
    ).fetchone()[0]
    conn.close()
    assert written == 0


@pytest.mark.unit
def test_store_plan_accepts_an_empty_timeline_on_any_type(test_app, tmp_coach_db):
    """`[]` IS absence: it normalizes away before the type gate, so a harmless
    no-op is legal even on a strength exercise (deep-review fix: the gate
    used to run first and reject it, which also broke `segments: []` riding a
    type change away from cardio as a clear)."""
    plan = {
        "day_name": "Legs", "total_duration_min": 40,
        "blocks": [{
            "block_type": "strength", "title": "Main",
            "exercises": [
                {"id": "dl", "name": "Deadlift", "type": "strength",
                 "segments": []},
            ],
        }],
    }
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    sid = store_plan(conn.cursor(), "2026-06-21", plan, modified_by="test")
    conn.commit()
    row = conn.execute("SELECT * FROM workout_sessions WHERE id=?", (sid,)).fetchone()
    got = assemble_plan(conn.cursor(), row)
    stored = conn.execute(
        "SELECT segments_json FROM planned_exercises WHERE session_id=?", (sid,)
    ).fetchone()["segments_json"]
    conn.close()
    assert stored is None
    assert "segments" not in got["blocks"][0]["exercises"][0]


@pytest.mark.unit
def test_transform_passes_segments_through():
    """`segments` is a canonical plan-JSON key, not a raw alias: the raw->formed
    transform copies it verbatim and never pops it."""
    from modules.coach_plans import transform_block_plan

    raw = {
        "theme": "Cardio", "blocks": [{
            "block_type": "cardio", "title": "Conditioning",
            "exercises": [
                {"name": "Zone 2 Bike", "type": "duration", "target_duration_min": 30,
                 "segments": [{"duration_sec": 1800, "hr_min": 118, "hr_max": 134}]},
            ],
        }],
    }
    ex = transform_block_plan(raw)["blocks"][0]["exercises"][0]

    assert ex["segments"] == [{"duration_sec": 1800, "hr_min": 118, "hr_max": 134}]


@pytest.mark.unit
def test_log_lean_vs_rich_shapes(coach_seeded_database, tmp_coach_db):
    """§3.15 for logs: both transports share the raw per-exercise core, but the
    sync path stays LEAN (no derived completion/stats — the PWA derives it) while
    the MCP path is RICH (adds per-exercise completion + session_completion)."""
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    log_row = conn.execute(
        "SELECT * FROM workout_session_logs ORDER BY date DESC LIMIT 1"
    ).fetchone()
    sess = conn.execute(
        "SELECT id FROM workout_sessions WHERE date = ?", (log_row["date"],)
    ).fetchone()
    session_id = sess["id"] if sess else None

    lean = coach_mod._assemble_log(conn, log_row)
    rich = _assemble_log_from_db(conn.cursor(), log_row["id"], session_id)
    conn.close()

    # Sync shape: feedback + raw entries, NO derived completion / stats.
    assert "session_feedback" in lean
    assert "session_completion" not in lean
    assert "workout_stats" not in lean
    assert "ex_1" in lean and "completed" not in lean["ex_1"]

    # MCP shape: same raw entries PLUS derived completion.
    assert "session_completion" in rich
    assert "ex_1" in rich and "completed" in rich["ex_1"]

    # The shared raw core is identical across transports.
    assert lean["ex_1"].get("sets") == rich["ex_1"].get("sets")
    assert lean["session_feedback"] == rich["session_feedback"]


@pytest.mark.unit
def test_log_exposure_is_rich_shape_only(test_app, tmp_coach_db):
    """The exposure frozen on a log row is emitted by the RICH (MCP) shape only.
    The lean sync shape stays wire-invariant for the PWA, which reads exposure
    off the plan object — so the sync payload must not grow the key."""
    from modules.coach_logs import assemble_log

    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO workout_sessions (date, day_name, last_modified, modified_by) "
        "VALUES ('2026-06-06', 'Lower', '2026-06-06T00:00:00Z', 'test')"
    )
    sid = cur.lastrowid
    cur.execute(
        "INSERT INTO session_blocks (session_id, position, block_type, title) "
        "VALUES (?, 0, 'strength', 'Main')", (sid,)
    )
    bid = cur.lastrowid
    cur.execute(
        "INSERT INTO planned_exercises (session_id, block_id, exercise_key, position, "
        "name, exercise_type, target_sets, exposure) "
        "VALUES (?, ?, 'squat', 0, 'Back Squat', 'strength', 3, 'HEAVY')", (sid, bid)
    )
    peid = cur.lastrowid
    cur.execute(
        "INSERT INTO workout_session_logs (session_id, date, last_modified, modified_by) "
        "VALUES (?, '2026-06-06', '2026-06-06T12:00:00Z', 'test')", (sid,)
    )
    slid = cur.lastrowid
    cur.execute(
        "INSERT INTO exercise_logs (session_log_id, exercise_id, exercise_key, "
        "exposure, last_modified) VALUES (?, ?, 'squat', 'HEAVY', '2026-06-06T12:00:00Z')",
        (slid, peid),
    )
    conn.commit()

    log_row = conn.execute(
        "SELECT * FROM workout_session_logs WHERE id=?", (slid,)
    ).fetchone()
    lean = assemble_log(conn.cursor(), log_row)
    rich = assemble_log(conn.cursor(), log_row, session_id=sid, derive_completion=True)
    conn.close()

    assert rich["squat"]["exposure"] == "HEAVY"
    assert "exposure" not in lean["squat"]


@pytest.mark.unit
def test_migration_adds_exercise_log_token(test_app, tmp_coach_db):
    """R3-0: migration 3 adds the per-exercise concurrency token column."""
    conn = sqlite3.connect(tmp_coach_db)
    cols = {r[1] for r in conn.execute("PRAGMA table_info(exercise_logs)")}
    conn.close()
    assert "last_modified" in cols


@pytest.mark.unit
def test_assemble_log_emits_per_exercise_token(test_app, tmp_coach_db):
    """R3-0: assemble_log surfaces each exercise's last_modified as _lastModified
    (the per-exercise base token); a NULL stamp (pre-migration row) is omitted."""
    from modules.coach_logs import assemble_log
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    conn.execute(
        "INSERT INTO workout_session_logs (date, last_modified, modified_by) "
        "VALUES ('2026-06-01', '2026-06-01T00:00:00Z', 'test')"
    )
    slid = conn.execute(
        "SELECT id FROM workout_session_logs WHERE date='2026-06-01'"
    ).fetchone()["id"]
    conn.execute(
        "INSERT INTO exercise_logs (session_log_id, exercise_key, last_modified) "
        "VALUES (?, 'ex_stamped', '2026-06-01T12:00:00Z')", (slid,)
    )
    conn.execute(
        "INSERT INTO exercise_logs (session_log_id, exercise_key, last_modified) "
        "VALUES (?, 'ex_null', NULL)", (slid,)
    )
    conn.commit()
    row = conn.execute("SELECT * FROM workout_session_logs WHERE id=?", (slid,)).fetchone()
    log = assemble_log(conn.cursor(), row)
    conn.close()

    assert log["ex_stamped"]["_lastModified"] == "2026-06-01T12:00:00Z"
    assert "_lastModified" not in log["ex_null"]


@pytest.mark.unit
def test_should_accept_log_write_arbiter():
    """R1: the pure server-side arbiter compares server stamps only (no client
    clock). See plans/phase4-r1-coach-clock-skew.md."""
    from modules.coach_logs import should_accept_log_write

    # No existing row → insert, token irrelevant.
    assert should_accept_log_write(None, None) is True
    assert should_accept_log_write(None, "2026-05-30T00:00:00Z") is True

    # Existing row + token absent → reject (hard cutover).
    assert should_accept_log_write("2026-05-30T00:00:00Z", None) is False

    # Existing row + stored <= base → accept (client saw the latest; equal accepts).
    assert should_accept_log_write("2026-05-30T00:00:00Z", "2026-05-30T00:00:00Z") is True
    assert should_accept_log_write("2026-05-30T00:00:00Z", "2026-05-30T01:00:00Z") is True

    # Existing row + stored > base → reject (client missed a newer server write).
    assert should_accept_log_write("2026-05-30T02:00:00Z", "2026-05-30T01:00:00Z") is False


@pytest.mark.unit
def test_list_scheduled_dates_today_is_injectable(test_app, tmp_coach_db):
    """coach_queries takes an injected `today`, so its date-window logic is
    directly unit-testable without depending on the real clock (plans/ phase 3)."""
    from datetime import date as _date
    from coach_mcp.config import MCPConfig
    from coach_mcp.server import DatabaseManager
    from modules import coach_queries

    conn = sqlite3.connect(tmp_coach_db)
    for d in ("2026-06-10", "2026-06-20"):
        conn.execute(
            "INSERT INTO workout_sessions (date, day_name, last_modified, modified_by) "
            "VALUES (?, 'X', 't', 't')", (d,)
        )
    conn.commit()
    conn.close()

    db = DatabaseManager(MCPConfig.from_db_path(tmp_coach_db))

    # today=06-01 → default window 06-01 .. +6wk includes both.
    wide = coach_queries.list_scheduled_dates(db, today=_date(2026, 6, 1))
    assert "2026-06-10" in wide and "2026-06-20" in wide

    # today=06-15 → window starts 06-15, so 06-10 falls before it.
    narrow = coach_queries.list_scheduled_dates(db, today=_date(2026, 6, 15))
    assert "2026-06-10" not in narrow and "2026-06-20" in narrow
