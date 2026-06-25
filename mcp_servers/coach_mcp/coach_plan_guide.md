# Coach Workout Plan Guide

## Quick Start
1. Use `list_scheduled_dates` to see what's already planned
2. Use `get_workout_plan` to see existing plan structures
3. Use `set_workout_plan` to create new plans (block format required)
4. Use `get_workout_logs` to analyze past performance

## Plan Structure

Each workout plan uses block-based format:
- `blocks`: Array of typed groups (warmup, strength, cardio, circuit, accessory, power)
- Each block contains exercises appropriate to its type

## Block Types

### warmup
Exercises are aggregated into a single checklist.

### strength / accessory
Individual exercises with sets/reps.

### circuit / power
Exercises performed for `rounds` rounds. Round/work/rest timing is
**block-level** — put `rounds`, `work_duration_sec`, `rest_duration_sec` on
the block, not on the individual exercises:
```json
{"block_type": "circuit", "title": "Metcon", "rounds": 4,
 "work_duration_sec": 40, "rest_duration_sec": 20,
 "exercises": [ ... ]}
```

### cardio
Use an `exercises` list with `duration` exercises (steady cardio) and/or
`interval` exercises (VO2 / HARD work). For an interval block the round /
work / rest timing is **block-level** — put `rounds`, `work_duration_sec`,
`rest_duration_sec` on the block:
```json
{"block_type": "cardio", "title": "VO2 Max", "duration_min": 20,
 "rounds": 4, "work_duration_sec": 180, "rest_duration_sec": 120,
 "exercises": [
   {"id": "vo2_1", "name": "VO2 Max Intervals", "type": "interval",
    "target_duration_min": 20,
    "guidance_note": "4 x (3 min HARD / 2 min easy). HR 160-175"}
 ]}
```
Shorthand: a block with an `instructions` array (free-form text, no
`exercises`) still works — the server expands it into a single `duration`
exercise, or `interval` if the text mentions VO2/HARD. The `exercises` form
is preferred: explicit type and name, no keyword-guessing.

## Exercise Types

### strength
```json
{"id": "ex_1", "name": "KB Goblet Squat", "type": "strength",
 "target_sets": 3, "target_reps": "10",
 "tempo": "3-1-1", "target_rpe": "7", "target_load": "24kg"}
```
Optional structured prescription fields (all free-form text, all displayed in a
compact line in the UI — put them in their own field, **not** `guidance_note`):
- `tempo` — e.g. `"3-1-1"`, `"3-1-2-0"`, `"30X1"`.
- `target_rpe` — target RPE/RIR; may be a range, e.g. `"7"`, `"6-7"`, `"8-9"`.
- `target_load` — free-form load cue, e.g. `"70%"`, `"24kg"`, `"BW"`, `"level 5"`.

Per-set nuance (e.g. "last set RPE 9", drop sets, AMRAP) still goes in
`guidance_note`.

### duration
```json
{"id": "cardio_1", "name": "Zone 2 Bike", "type": "duration",
 "target_duration_min": 15, "guidance_note": "HR 135-148"}
```

### checklist
```json
{"id": "warmup_0", "name": "Stability Start", "type": "checklist",
 "items": ["Cat-Cow x10", "Bird-Dog x5/side"]}
```

### weighted_time
```json
{"id": "ex_5", "name": "Farmer's Carry", "type": "weighted_time",
 "target_duration_sec": 60}
```

### interval
A standalone interval workout (one exercise in a cardio block). Timing may be
on the exercise; for an interval *block* (multiple stations) put it on the
block instead — see the cardio/circuit block types above.
```json
{"id": "hiit_1", "name": "Bike Intervals", "type": "interval",
 "rounds": 4, "work_duration_sec": 30, "rest_duration_sec": 90}
```

## Antagonist Pairs / Supersets

Group two or more exercises into a superset using the `superset_group` field.
Exercises in the same block that share the same group label are rendered
together in the UI. The label is free-form: `"A"`, `"B"`, `"Triplet A"`, etc.

```json
{"id": "ex_1", "name": "Bench Press", "type": "strength",
 "target_sets": 3, "target_reps": "8", "superset_group": "A"},
{"id": "ex_2", "name": "Bent Row", "type": "strength",
 "target_sets": 3, "target_reps": "8", "superset_group": "A"}
```

**Do NOT** put pair info in the exercise `name` (e.g. `"Bench Press (Pair A)"`).
Names like that are rejected by the server because the suffix would leak into
the canonical slug and break cross-session comparison.

## Example: Block-Based Plan

```json
{
    "day_name": "Lower Body + Conditioning",
    "location": "Home",
    "phase": "Foundation",
    "blocks": [
        {
            "block_type": "warmup",
            "title": "Stability Start",
            "exercises": [
                {"id": "warmup_0", "name": "Stability Start", "type": "checklist",
                 "items": ["Cat-Cow x10", "Bird-Dog x5/side", "Dead Bug x10"]}
            ]
        },
        {
            "block_type": "strength",
            "title": "Main Lifts",
            "rest_guidance": "Rest until HR <= 130",
            "exercises": [
                {"id": "ex_1", "name": "KB Goblet Squat", "type": "strength",
                 "target_sets": 3, "target_reps": "10", "tempo": "3-1-1"},
                {"id": "ex_2", "name": "DB Romanian Deadlift", "type": "strength",
                 "target_sets": 3, "target_reps": "10"}
            ]
        },
        {
            "block_type": "cardio",
            "title": "Zone 2 Cooldown",
            "exercises": [
                {"id": "cardio_1", "name": "Zone 2 Bike", "type": "duration",
                 "target_duration_min": 15, "guidance_note": "HR 135-148"}
            ]
        }
    ]
}
```

## Editing Existing Plans

`set_workout_plan` / `ingest_training_program` replace a whole plan and are
blocked once a workout log exists for that date. To tweak an existing plan,
use the in-place editors instead (they don't rebuild the plan):

- Plan metadata: `update_plan_metadata`
- Exercises: `update_exercise`, `add_exercise`, `remove_exercise`
- Blocks: `update_block`, `add_block`, `remove_block`, `reorder_blocks`
  (blocks are addressed by 0-indexed position; `update_block` is also how you
  change a block's circuit/interval timing)

## Exercise Registry

Exercises are automatically registered with canonical slugs (e.g., `kb_goblet_squat`)
when plans are created. This enables cross-session queries.

### Available Tools
- `search_exercises(query)` — find exercises by name, equipment, or category
- `get_exercise_history(exercise_slug)` — view all logged sessions for an exercise

### Available Resources
- `exercise_registry_summary` — full list of registered exercises grouped by equipment

### How It Works
- When you create a plan, exercise names are automatically resolved to canonical slugs
- Fuzzy matching handles minor name variations (e.g., "KB Goblet Squat" vs "Kettlebell Goblet Squat")
- New exercises are auto-registered; equipment is inferred from the name
- Use `search_exercises` to check what exercises already exist before creating plans

## Best Practices

1. **Block grouping**: Group exercises by type (warmup, strength, cardio)
2. **Unique IDs**: Each exercise needs a unique `id` within the plan
3. **Prescription fields**: For strength lifts, put tempo, target RPE, and load
   in their dedicated fields (`tempo`, `target_rpe`, `target_load`; all free-form,
   e.g. `"3-1-1"` / `"6-7"` / `"70%"`) — not in `guidance_note`
4. **Guidance Notes**: Include rest periods, HR targets, form cues, and per-set
   nuance (e.g. "last set RPE 9")
5. **Progressive Overload**: Increase volume/intensity across phases
6. **Consistent Names**: Use `search_exercises` to find existing exercise names