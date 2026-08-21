"""Coach plan domain logic — shared by both transports.

Transport-agnostic: imports only stdlib (+ `modules.db` for write helpers added
later). The FastAPI router (`modules.coach`) and the MCP server
(`coach_mcp.server`) both delegate here so there is exactly one implementation of
each plan operation (see plans/ phase 3). Do NOT import fastapi or fastmcp here.
"""
import json
import re

from modules.db import get_utc_now

VALID_BLOCK_TYPES = ["warmup", "strength", "cardio", "circuit", "accessory", "power"]
VALID_EXERCISE_TYPES = ["strength", "duration", "checklist", "weighted_time", "interval", "circuit"]

# Legacy pair-suffix pattern: rejects names like "Bench Press (Pair A)" so that
# pair info is forced through the structured `superset_group` field instead of
# leaking into canonical_slug.
LEGACY_PAIR_SUFFIX_RE = re.compile(r"\((?:Pair|Superset|Triplet)\b[^)]*\)", re.IGNORECASE)

# Longest accepted `exposure` key after normalization. It is a short identity
# label (joined to consumer-side config), never prose, so anything longer is a
# misuse — rejected rather than truncated, since truncation would silently
# collapse two distinct keys into one.
EXPOSURE_MAX_LENGTH = 32


def normalize_exposure(value):
    """Canonicalize an `exposure` identity key, or None when there isn't one.

    The single normalization authority for the field: plan ingest
    (`insert_block`), the MCP editors (`add_exercise` / `update_exercise`) and
    the `get_exercise_history` filter all go through it, so `"  heavy  "` and
    `"HEAVY"` are the same key on every path. Collapses surrounding and internal
    whitespace and upper-cases; an empty (or whitespace-only) value means "no
    exposure" and yields None — absence is meaningful and must stay cheap.

    Raises ValueError for a non-string, or for a value still longer than
    EXPOSURE_MAX_LENGTH after normalization.
    """
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(
            f"exposure must be a string, got {type(value).__name__}"
        )
    normalized = " ".join(value.split()).upper()
    if not normalized:
        return None
    if len(normalized) > EXPOSURE_MAX_LENGTH:
        raise ValueError(
            f"exposure '{normalized}' is {len(normalized)} characters; "
            f"the maximum is {EXPOSURE_MAX_LENGTH}"
        )
    return normalized


# Exercise types a target-HR timeline may ride on. `segments` describes a
# continuous stretch of cardio time, which is the only thing these two types
# measure; a strength or checklist exercise has no clock for a segment to sit on.
SEGMENT_EXERCISE_TYPES = ("duration", "interval")

# The whole of a segment. Closed on purpose: a misspelled optional key
# ("hr_maxx") would otherwise store a floor-only segment that reads as
# deliberate, which is the same silent-drop trap the MCP `column_map` taught.
SEGMENT_KEYS = ("duration_sec", "hr_min", "hr_max", "label")


def _segment_int(segment, key, context):
    """One optional positive integer off a segment, or None.

    Mirrors `hr_analysis.intent._optional_positive_int` — the timeline is the
    persisted generalization of `IntervalIntent`, so it applies that validator's
    rules verbatim: booleans are not integers (in Python they would otherwise
    pass as 0/1), anything int() cannot read is rejected, and the floor is 1.
    Copied rather than imported: `coach_plans` is transport- and module-agnostic
    by contract and must not reach into the HR package.
    """
    raw = segment.get(key)
    if raw is None:
        return None
    if isinstance(raw, bool):
        raise ValueError(f"{context} '{key}' must be an integer")
    try:
        parsed = int(raw)
    except (TypeError, ValueError):
        raise ValueError(f"{context} '{key}' must be an integer")
    if parsed < 1:
        raise ValueError(f"{context} '{key}' must be >= 1")
    return parsed


def normalize_segments(value, context="segments"):
    """Validate a cardio target-HR timeline, or None when there isn't one.

    The single validation authority for the field, shared by `validate_plan`
    (and through it every plan write path) and the MCP exercise editors, exactly
    as `normalize_exposure` is for `exposure`. Returns the canonical list — each
    segment rebuilt with only the keys it actually carries, in a fixed order, so
    storage is deterministic and the sparse-omit wire convention holds inside the
    blob too.

    An empty list means "no timeline" and yields None: absence is the normal
    case, and the wire must never carry `"segments": []` for a plan that simply
    has no timeline.

    The rules (the `IntervalIntent` idiom, generalized):
    - `duration_sec` — required, integer >= 1.
    - `hr_min` / `hr_max` — each optional, integer bpm >= 1, at least one
      present, `hr_min <= hr_max` when both are. Min-only is a floor, max-only a
      ceiling, both a range. **Absolute bpm always** — nothing in the system
      resolves a symbolic zone, so the authoring LLM computes the numbers.
    - `label` — optional short display string; blank is the same as absent.
    - Unknown keys are rejected rather than dropped (see SEGMENT_KEYS).

    The list is the timeline, flat and in order: a VO2 session's repeats are
    written out. Nothing is derived from block `rounds`/`work_duration_sec` —
    a plan without `segments` has no timeline at all.

    Raises ValueError, prefixed with `context`, on the first problem.
    """
    if value is None:
        return None
    if not isinstance(value, list):
        raise ValueError(f"{context} must be a list")
    if not value:
        return None

    normalized = []
    for i, segment in enumerate(value):
        where = f"{context}[{i}]"
        if not isinstance(segment, dict):
            raise ValueError(f"{where} must be an object")
        unknown = set(segment) - set(SEGMENT_KEYS)
        if unknown:
            raise ValueError(
                f"{where} has unknown field(s): {sorted(unknown)}. "
                f"Allowed: {list(SEGMENT_KEYS)}"
            )
        if segment.get("duration_sec") is None:
            raise ValueError(f"{where} missing 'duration_sec'")
        duration_sec = _segment_int(segment, "duration_sec", where)
        hr_min = _segment_int(segment, "hr_min", where)
        hr_max = _segment_int(segment, "hr_max", where)
        if hr_min is None and hr_max is None:
            raise ValueError(f"{where} must have at least one of 'hr_min' / 'hr_max'")
        if hr_min is not None and hr_max is not None and hr_min > hr_max:
            raise ValueError(f"{where} 'hr_min' must be <= 'hr_max'")

        label = segment.get("label")
        if label is not None and not isinstance(label, str):
            raise ValueError(f"{where} 'label' must be a string")
        label = label.strip() if label else ""

        out = {"duration_sec": duration_sec}
        if hr_min is not None:
            out["hr_min"] = hr_min
        if hr_max is not None:
            out["hr_max"] = hr_max
        if label:
            out["label"] = label
        normalized.append(out)

    return normalized


def validate_exercise_segments(value, exercise_type, context=""):
    """The whole timeline rule for one exercise: a legal type, then a valid
    shape. Returns the canonical list, or None when there is no timeline.

    The type gate lives here rather than inside `normalize_segments` because the
    MCP editors have to ask it of the type a row *will have* after the update,
    not the one the caller happened to pass alongside.

    `context` names the offending exercise when the caller has an index to give
    ("Exercise 3") and is omitted by the single-exercise MCP editors, whose own
    error wrapper already says which one — the `reject_legacy_pair_suffix`
    convention.
    """
    if value is None:
        return None
    if isinstance(value, list) and not value:
        # An empty list IS absence (`normalize_segments` yields None for it),
        # and absence is legal on every exercise type — so it short-circuits
        # ahead of the type gate. Otherwise `segments: []` could not ride a
        # type change away from cardio to clear the timeline that change
        # would strand.
        return None
    if exercise_type not in SEGMENT_EXERCISE_TYPES:
        prefix = f"{context}: " if context else ""
        raise ValueError(
            f"{prefix}'segments' is only valid on "
            f"{list(SEGMENT_EXERCISE_TYPES)} exercises, not '{exercise_type}'"
        )
    return normalize_segments(value, f"{context} segments".strip())


def segments_to_json(value, exercise_type, context=""):
    """A validated timeline in its storage form: JSON TEXT, or None for absent."""
    segments = validate_exercise_segments(value, exercise_type, context)
    return json.dumps(segments) if segments else None


def segments_from_json(raw):
    """A stored timeline back as a list, or None (absent / malformed / not a list).

    Read-side counterpart of `segments_to_json`, mirroring the journal's
    `_load_json_list`: only validated writes reach the column, so a value that
    will not parse is a hand-edited row, and dropping the field from one exercise
    is a better answer than failing the whole day's sync.
    """
    if not raw:
        return None
    try:
        parsed = json.loads(raw)
    except (ValueError, TypeError):
        return None
    return parsed if isinstance(parsed, list) and parsed else None


def reject_legacy_pair_suffix(name, context=""):
    """Raise ValueError if name still uses the deprecated `(Pair X)` suffix."""
    if name and LEGACY_PAIR_SUFFIX_RE.search(name):
        prefix = f"{context}: " if context else ""
        raise ValueError(
            f"{prefix}Exercise name '{name}' uses the legacy pair suffix "
            f"convention. Put pair info in the structured `superset_group` "
            f"field instead (e.g. \"superset_group\": \"A\")."
        )


def validate_plan_structure(plan):
    """Validate the plan's block-level shape (safe on a raw, pre-transform plan).

    Raises ValueError on the first problem.
    """
    if not isinstance(plan, dict):
        raise ValueError("Plan must be a dictionary")
    if "blocks" not in plan:
        raise ValueError("Plan must have 'blocks'")
    if not isinstance(plan["blocks"], list):
        raise ValueError("Plan blocks must be a list")
    for i, block in enumerate(plan["blocks"]):
        if "block_type" not in block:
            raise ValueError(f"Block {i} missing 'block_type' field")
        if not isinstance(block["block_type"], str):
            raise ValueError(f"Block {i} 'block_type' must be a string")
        if block["block_type"] not in VALID_BLOCK_TYPES:
            raise ValueError(
                f"Block {i} has invalid block_type: {block['block_type']}. "
                f"Must be one of: {VALID_BLOCK_TYPES}"
            )
        if "exercises" not in block and "instructions" not in block:
            raise ValueError(f"Block {i} must have either 'exercises' or 'instructions'")


def validate_plan(plan):
    """Full plan validation (expects the post-transform shape with exercise ids).

    Called from store_plan itself so EVERY write path inherits it — the bulk
    ingest path used to skip the per-exercise checks entirely, making the
    least-supervised writer the least-validated one. Raises ValueError.
    """
    validate_plan_structure(plan)
    for block in plan.get("blocks", []):
        for i, exercise in enumerate(block.get("exercises", [])):
            if "id" not in exercise:
                raise ValueError(f"Exercise {i} missing 'id' field")
            if "name" not in exercise:
                raise ValueError(f"Exercise {i} missing 'name' field")
            if "type" not in exercise:
                raise ValueError(f"Exercise {i} missing 'type' field")
            if exercise["type"] not in VALID_EXERCISE_TYPES:
                raise ValueError(
                    f"Exercise {i} has invalid type: {exercise['type']}. "
                    f"Must be one of: {VALID_EXERCISE_TYPES}"
                )
            reject_legacy_pair_suffix(exercise["name"], f"Exercise {i}")
            if "exposure" in exercise:
                # normalize_exposure owns the type + length rules; validating
                # here (rather than only at insert time) means every write path
                # inherits them through store_plan, and a bad key is rejected
                # before any row is written.
                try:
                    normalize_exposure(exercise["exposure"])
                except ValueError as e:
                    raise ValueError(f"Exercise {i}: {e}") from e
            # Same reasoning as `exposure` one clause up: one authority owns the
            # timeline's rules, and validating here means every write path
            # inherits them through store_plan, before a row is written.
            validate_exercise_segments(
                exercise.get("segments"), exercise["type"], f"Exercise {i}"
            )


def assemble_plan(cursor, session_row):
    """Assemble a plan dict from the relational tables for `session_row`.

    The canonical plan reader for both transports. Always includes `session_id`
    — unifying the two previously-divergent assemblers on the superset shape
    (§3.15); the extra field is backward-compatible for both the sync client and
    the MCP read tools.

    `session_row` is a `SELECT * FROM workout_sessions` row (so it carries `id`,
    `day_name`, `location`, `phase`, `duration_min`). `cursor` is reused for the
    block / exercise / checklist sub-queries.
    """
    session_id = session_row["id"]

    cursor.execute(
        "SELECT * FROM session_blocks WHERE session_id = ? ORDER BY position",
        (session_id,),
    )
    block_rows = cursor.fetchall()

    blocks = []
    for br in block_rows:
        cursor.execute(
            "SELECT * FROM planned_exercises WHERE block_id = ? ORDER BY position",
            (br["id"],),
        )
        ex_rows = cursor.fetchall()

        exercises = []
        for er in ex_rows:
            exercise = {
                "id": er["exercise_key"],
                "name": er["name"],
                "type": er["exercise_type"],
            }
            if er["target_sets"] is not None:
                exercise["target_sets"] = er["target_sets"]
            if er["target_reps"] is not None:
                exercise["target_reps"] = er["target_reps"]
            if er["target_duration_min"] is not None:
                exercise["target_duration_min"] = er["target_duration_min"]
            if er["target_duration_sec"] is not None:
                exercise["target_duration_sec"] = er["target_duration_sec"]
            if er["rounds"] is not None:
                exercise["rounds"] = er["rounds"]
            if er["work_duration_sec"] is not None:
                exercise["work_duration_sec"] = er["work_duration_sec"]
            if er["rest_duration_sec"] is not None:
                exercise["rest_duration_sec"] = er["rest_duration_sec"]
            if er["guidance_note"]:
                exercise["guidance_note"] = er["guidance_note"]
            if er["hide_weight"]:
                exercise["hide_weight"] = True
            if er["show_time"]:
                exercise["show_time"] = True
            if er["superset_group"]:
                exercise["superset_group"] = er["superset_group"]
            if er["exposure"]:
                exercise["exposure"] = er["exposure"]
            if er["tempo"]:
                exercise["tempo"] = er["tempo"]
            if er["target_rpe"]:
                exercise["target_rpe"] = er["target_rpe"]
            if er["target_load"]:
                exercise["target_load"] = er["target_load"]
            if er["canonical_slug"]:
                exercise["canonical_slug"] = er["canonical_slug"]
            segments = segments_from_json(er["segments_json"])
            if segments:
                exercise["segments"] = segments

            if er["exercise_type"] == "checklist":
                cursor.execute(
                    "SELECT item_text FROM checklist_items "
                    "WHERE exercise_id = ? ORDER BY position",
                    (er["id"],),
                )
                exercise["items"] = [r["item_text"] for r in cursor.fetchall()]

            exercises.append(exercise)

        blocks.append({
            "block_index": br["position"],
            "block_type": br["block_type"],
            "title": br["title"],
            "duration_min": br["duration_min"],
            "rest_guidance": br["rest_guidance"] or "",
            "rounds": br["rounds"],
            "work_duration_sec": br["work_duration_sec"],
            "rest_duration_sec": br["rest_duration_sec"],
            "exercises": exercises,
        })

    return {
        "session_id": session_id,
        "day_name": session_row["day_name"],
        "location": session_row["location"],
        "phase": session_row["phase"],
        "total_duration_min": session_row["duration_min"],
        "blocks": blocks,
    }


# ==================== Plan-shape transforms (raw LLM plan -> canonical) ========


def needs_transform(plan):
    """Check whether a block plan still holds raw LLM exercises.

    A raw exercise is identified by a missing ``type`` — the field the
    transform derives from ``block_type``. A missing ``id`` alone does NOT
    require transformation: ids are filled in idempotently afterwards by
    ``ensure_exercise_ids``, so a pre-formed plan that merely lacks ids skips
    the (lossy) transform entirely.
    """
    for block in plan.get("blocks", []):
        for ex in block.get("exercises", []):
            if "type" not in ex:
                return True
    # Cardio blocks with instructions (no exercises) also need transform
    for block in plan.get("blocks", []):
        if "instructions" in block and "exercises" not in block:
            return True
    return False


def ensure_exercise_ids(plan):
    """Assign a deterministic ``id`` to every exercise that lacks one.

    Idempotent: exercises that already carry an ``id`` keep it, and generated
    ids are disambiguated against ids already present in the same block.
    Mutates ``plan`` in place and returns it. Keeping id assignment separate
    from the raw->formed transform lets a pre-formed plan that is only missing
    ids skip that (lossy) transform — see ``needs_transform``.
    """
    for i, block in enumerate(plan.get("blocks", [])):
        block_type = block.get("block_type", "ex")
        block_exercises = block.get("exercises", [])
        used = {ex["id"] for ex in block_exercises if ex.get("id")}
        for j, ex in enumerate(block_exercises):
            if ex.get("id"):
                continue
            n = j + 1
            candidate = f"{block_type}_{i}_{n}"
            while candidate in used:
                n += 1
                candidate = f"{block_type}_{i}_{n}"
            ex["id"] = candidate
            used.add(candidate)
    return plan


def is_bodyweight_or_band(name: str) -> bool:
    """Check if an exercise is bodyweight or band-based (no meaningful weight)."""
    keywords = [
        "push-up", "pushup", "push up", "bodyweight", "band pull",
        "banded", "jump squat", "plank", "dead hang", "wall sit",
        "glute bridge",
    ]
    lower = name.lower()
    return any(kw in lower for kw in keywords)


def transform_block_to_exercises(block: dict, block_index: int) -> list:
    """Transform a block into a list of exercises with proper IDs and types."""
    exercises = []
    block_type = block.get("block_type", "")
    title = block.get("title", "")
    duration = block.get("duration_min", 0)

    # Warmup blocks aggregate raw movements into a single checklist. An
    # already-formed exercise (it carries a `type`) is preserved verbatim, so a
    # pre-built checklist's `items` and metadata are never rebuilt from names.
    if block_type == "warmup" and "exercises" in block:
        raw_items = []
        for ex in block["exercises"]:
            if ex.get("type"):
                exercises.append(dict(ex))
                continue
            name = ex.get("name", "Unknown")
            reps = ex.get("reps", "")
            if reps:
                raw_items.append(f"{name} x{reps}" if isinstance(reps, int) else f"{name} {reps}")
            else:
                raw_items.append(name)

        if raw_items:
            exercises.append({
                "id": f"warmup_{block_index}",
                "name": title or "Warmup",
                "type": "checklist",
                "items": raw_items,
            })

    # Non-warmup blocks with an explicit exercise list. Each exercise is
    # copied (no caller-provided field is dropped), then any missing canonical
    # field is derived — an already-formed exercise passes through losslessly.
    elif "exercises" in block:
        block_rounds = block.get("rounds")

        for idx, ex in enumerate(block["exercises"]):
            exercise = dict(ex)
            exercise.setdefault("name", "Unknown")

            # Derive type from the block when the exercise doesn't carry one.
            if not exercise.get("type"):
                if block_type in ("circuit", "power"):
                    exercise["type"] = "circuit"
                elif block_type == "cardio":
                    exercise["type"] = "duration"
                else:  # strength / accessory / unknown
                    exercise["type"] = "strength"

            # Fold raw sets/reps onto the stored target_* fields.
            if exercise.get("target_sets") is None:
                if ex.get("sets"):
                    exercise["target_sets"] = ex["sets"] if isinstance(ex["sets"], int) else 3
                elif block_rounds:
                    exercise["target_sets"] = block_rounds
            if exercise.get("target_reps") is None and ex.get("reps") is not None:
                reps_str = str(ex["reps"])
                exercise["target_reps"] = reps_str
                if "sec" in reps_str.lower():
                    exercise.setdefault("show_time", True)

            # Hide weight for bodyweight/band exercises, unless explicitly set.
            if "hide_weight" not in exercise:
                equipment = ex.get("equipment")
                if equipment in ("bodyweight", "band"):
                    exercise["hide_weight"] = True
                elif not equipment and is_bodyweight_or_band(exercise["name"]):
                    exercise["hide_weight"] = True

            # Tempo / RPE / load are first-class fields now (free-form text, e.g.
            # tempo "3-1-2-0", RPE "6-7", load "70%"); none are folded into
            # guidance_note. `exercise = dict(ex)` already copied any canonical
            # keys through; here we also map the raw aliases the program/LLM
            # format may use. insert_block normalizes each to trimmed text.
            if not exercise.get("target_rpe") and ex.get("rpe"):
                exercise["target_rpe"] = ex["rpe"]
            if not exercise.get("target_load"):
                load = ex.get("load") or ex.get("load_guide")
                if load:
                    exercise["target_load"] = load

            # Build a guidance note from any remaining free cue, unless the
            # exercise already carries one. tempo / RPE / load are structured now
            # and are NOT folded in; only free-form `notes` is. Block-level
            # rest_guidance is never folded in — it stays on the block.
            if not exercise.get("guidance_note") and ex.get("notes"):
                exercise["guidance_note"] = ex["notes"]

            # Drop raw input hints now mapped onto canonical fields. tempo /
            # target_rpe / target_load are kept (structured fields); their raw
            # aliases (rpe / load / load_guide) are dropped.
            for raw_key in ("sets", "reps", "rpe", "load", "load_guide", "notes", "equipment"):
                exercise.pop(raw_key, None)

            exercise.setdefault("id", f"{block_type}_{block_index}_{idx + 1}")
            exercises.append(exercise)

    # Handle blocks with instructions (cardio blocks)
    elif "instructions" in block:
        exercise_id = f"{block_type}_{block_index}_1"
        instructions_text = " ".join(block["instructions"])

        if "VO2" in instructions_text or "HARD" in instructions_text:
            ex_type = "interval"
            name = "VO2 Max Intervals"
        else:
            ex_type = "duration"
            name = title or "Zone 2 Cardio"

        # Block-level rounds/work/rest timing stays on the block (see
        # transform_block_plan); the synthesized exercise only carries the
        # cardio-specific fields it owns.
        exercise = {
            "id": exercise_id,
            "name": name,
            "type": ex_type,
            "target_duration_min": duration,
            "guidance_note": " | ".join(block["instructions"])
        }
        exercises.append(exercise)

    return exercises


def transform_block_plan(plan_data: dict) -> dict:
    """Transform block-based plan to include blocks with transformed exercises."""
    blocks = []

    for i, block in enumerate(plan_data.get("blocks", [])):
        existing = block.get("exercises")
        # `type` is the marker of an already-formed exercise; a missing `id` is
        # backfilled separately (see ensure_exercise_ids) and does not by
        # itself force a block through the (lossy) transform.
        if existing and all("type" in ex for ex in existing):
            block_exercises = existing
        else:
            block_exercises = transform_block_to_exercises(block, i)

        transformed_block = {
            "block_index": i,
            "block_type": block.get("block_type", ""),
            "title": block.get("title", ""),
            "duration_min": block.get("duration_min"),
            "rest_guidance": block.get("rest_guidance", ""),
            "rounds": block.get("rounds"),
            "work_duration_sec": block.get("work_duration_sec"),
            "rest_duration_sec": block.get("rest_duration_sec"),
            "exercises": block_exercises
        }
        blocks.append(transformed_block)

    return {
        "day_name": plan_data.get("theme", plan_data.get("day_name", "Workout")),
        "location": plan_data.get("location", "Home"),
        "phase": plan_data.get("phase", "Foundation"),
        "total_duration_min": plan_data.get("total_duration_min", 60),
        "blocks": blocks,
    }


# ==================== Plan write ==============================================


def insert_block(cursor, session_id, position, block):
    """Insert one block (with its exercises and checklist items) at ``position``.

    Exercises without an explicit ``id`` get a key derived from the block type
    and position; callers needing collision-free keys across an existing
    session should set ``id`` first. Returns the new ``session_blocks`` row id.
    """
    cursor.execute("""
        INSERT INTO session_blocks
        (session_id, position, block_type, title, duration_min, rest_guidance, rounds,
         work_duration_sec, rest_duration_sec)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, [
        session_id, position,
        block.get("block_type", ""),
        block.get("title"),
        block.get("duration_min"),
        block.get("rest_guidance", ""),
        block.get("rounds"),
        block.get("work_duration_sec"),
        block.get("rest_duration_sec"),
    ])
    block_id = cursor.lastrowid

    for j, ex in enumerate(block.get("exercises", [])):
        exercise_key = ex.get("id") or f"{block.get('block_type', 'ex')}_{position}_{j}"
        cursor.execute("""
            INSERT INTO planned_exercises
            (session_id, block_id, exercise_key, position, name, exercise_type,
             target_sets, target_reps, target_duration_min, target_duration_sec,
             rounds, work_duration_sec, rest_duration_sec,
             guidance_note, hide_weight, show_time, superset_group, tempo,
             target_rpe, target_load, exposure, extra, canonical_slug, segments_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, [
            session_id, block_id, exercise_key, j,
            ex.get("name", "Unknown"),
            ex.get("type", "strength"),
            ex.get("target_sets"),
            ex.get("target_reps"),
            ex.get("target_duration_min"),
            ex.get("target_duration_sec"),
            ex.get("rounds"),
            ex.get("work_duration_sec"),
            ex.get("rest_duration_sec"),
            ex.get("guidance_note"),
            1 if ex.get("hide_weight") else 0,
            1 if ex.get("show_time") else 0,
            ex.get("superset_group"),
            str(ex["tempo"]).strip() if ex.get("tempo") else None,
            str(ex["target_rpe"]).strip() if ex.get("target_rpe") else None,
            str(ex["target_load"]).strip() if ex.get("target_load") else None,
            normalize_exposure(ex.get("exposure")),
            json.dumps(ex["extra"]) if ex.get("extra") else None,
            ex.get("canonical_slug"),
            # `add_block` reaches this writer without going through
            # `validate_plan`, so the rule is applied here too — the same
            # belt-and-braces `normalize_exposure` gets two lines up.
            segments_to_json(
                ex.get("segments"), ex.get("type", "strength"), f"Exercise {j}"
            ),
        ])
        exercise_id = cursor.lastrowid

        # Checklist items
        if ex.get("type") == "checklist":
            for k, item in enumerate(ex.get("items", [])):
                cursor.execute("""
                    INSERT INTO checklist_items (exercise_id, position, item_text)
                    VALUES (?, ?, ?)
                """, [exercise_id, k, item])

    return block_id


def store_plan(cursor, date_str, plan, modified_by="mcp"):
    """Store a plan dict into normalized tables. Returns session_id.

    Validates first (validate_plan) — the single enforcement point every
    transport and tool inherits."""
    validate_plan(plan)
    now = get_utc_now()

    # Guard: refuse to REPLACE a plan that has workout logs attached.
    # Use update_exercise/add_exercise/remove_exercise instead.
    # Scoped to replacement only: a log row on a date with NO plan is a normal
    # state since ad-hoc extra sessions (rest-day Zone 2) — creating the
    # first plan for such a date must not be blocked (the DELETE below would
    # remove nothing, so nothing logged is at risk).
    existing_plan = cursor.execute(
        "SELECT id FROM workout_sessions WHERE date = ?", [date_str]
    ).fetchone()
    if existing_plan:
        log_row = cursor.execute(
            "SELECT id FROM workout_session_logs WHERE date = ?", [date_str]
        ).fetchone()
        if log_row:
            raise ValueError(
                f"Cannot replace plan for {date_str}: a workout log exists. "
                f"Use update_exercise, add_exercise, or remove_exercise to edit in place."
            )

    # Delete existing session for this date (CASCADE cleans blocks, exercises, checklist)
    cursor.execute("DELETE FROM workout_sessions WHERE date = ?", [date_str])
    # Clear any tombstone if re-creating a plan for a previously deleted date
    cursor.execute("DELETE FROM deleted_plans WHERE date = ?", [date_str])

    # Insert workout_sessions row
    cursor.execute("""
        INSERT INTO workout_sessions
        (date, day_name, location, phase, duration_min, last_modified, modified_by)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    """, [
        date_str,
        plan.get("day_name", "Workout"),
        plan.get("location"),
        plan.get("phase"),
        plan.get("total_duration_min"),
        now,
        modified_by,
    ])
    session_id = cursor.lastrowid

    # Insert blocks and exercises
    for i, block in enumerate(plan.get("blocks", [])):
        insert_block(cursor, session_id, i, block)

    return session_id
