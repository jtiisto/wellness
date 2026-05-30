"""Derive workout completion from logged data.

Completion is **derived** from the underlying logged data — sets, checklist
items, and cardio duration — not read from a stored exercise-level flag. The
legacy ``exercise_logs.completed`` column was unreliable: the PWA header
checkbox is display-derived for strength/checklist/duration, so it auto-shows
checked and the user never toggles it, leaving the stored flag 0 on real,
fully-logged work. See ``docs/plan_workout_completion_derivation.md``.

For each exercise we report three things:

* ``attempted`` — any real data was logged
* ``completed`` — the planned target was met (``None`` when the target is
  unknown, e.g. an unlinked log row)
* ``progress`` — ``{"done": <n>, "target": <n|None>}`` for adherence math

The set-level ``set_logs.completed`` tick remains a reliable, separate signal
and is left untouched; it is an *input* to the derivation, not part of the bug.

Pure functions, no DB — shared coach domain logic delegated to by the MCP read
tools and the log assembler (`modules.coach_logs`); see plans/ phase 3.
"""

# Fields whose presence means a set was actually performed.
SET_DATA_FIELDS = ("weight", "reps", "rpe", "duration_sec")

# Exercise types whose completion is measured by sets.
_SET_BASED_TYPES = ("strength", "circuit", "weighted_time")
_DURATION_TYPES = ("duration", "interval")


def _field(row, key, default=None):
    """Read ``key`` from a dict or sqlite3.Row, tolerating a missing column."""
    if isinstance(row, dict):
        return row.get(key, default)
    try:
        return row[key]
    except (KeyError, IndexError):
        return default


def set_has_data(s) -> bool:
    """A set counts as performed if it carries any real metric."""
    return any(_field(s, f) is not None for f in SET_DATA_FIELDS)


def _infer_type(exercise_type, *, duration_min, logged_items, planned_items,
                target_duration_min):
    """Best-effort type when the log row isn't linked to a planned exercise."""
    etype = (exercise_type or "").lower()
    if etype:
        return etype
    if logged_items or planned_items:
        return "checklist"
    if duration_min is not None or target_duration_min is not None:
        return "duration"
    return "strength"


def derive_exercise_completion(exercise_type, *, sets=None, duration_min=None,
                               logged_items=0, planned_items=None,
                               target_sets=None, target_duration_min=None):
    """Derive ``{attempted, completed, progress}`` for one exercise log.

    ``completed`` is the *met-target* verdict: ``True``/``False`` when the
    target is known, ``None`` when it is unknown (so callers can tell "missed"
    apart from "can't judge"). Not-attempted always yields ``completed=False``.
    """
    sets = sets or []
    logged_items = logged_items or 0
    etype = _infer_type(
        exercise_type,
        duration_min=duration_min,
        logged_items=logged_items,
        planned_items=planned_items,
        target_duration_min=target_duration_min,
    )

    if etype == "checklist":
        done, target = logged_items, planned_items
        attempted = done > 0
    elif etype in _DURATION_TYPES:
        done, target = duration_min, target_duration_min
        attempted = duration_min is not None
    else:  # set-based: strength / circuit / weighted_time
        done = sum(1 for s in sets if set_has_data(s))
        target = target_sets
        attempted = done > 0

    if not attempted:
        completed = False
    elif target is None or target == 0:
        completed = None          # attempted, but no target to judge against
    else:
        completed = done >= target

    return {
        "attempted": attempted,
        "completed": completed,
        "progress": {"done": done, "target": target},
    }


def derive_session_completion(exercise_results, planned_total=None):
    """Roll exercise-level results up to a session verdict.

    ``exercise_results`` is a list of dicts from
    :func:`derive_exercise_completion` (typically one per *logged* exercise).
    ``planned_total`` is the number of planned exercises for the session; when
    given, exercises that were planned but never logged correctly count against
    full completion. Falls back to the logged count when ``None``.

    A session is ``completed`` only when every planned exercise met its target;
    exercises with an unknown (``None``) verdict are conservatively treated as
    not completed.
    """
    attempted_n = sum(1 for r in exercise_results if r["attempted"])
    completed_n = sum(1 for r in exercise_results if r["completed"] is True)
    total = planned_total if planned_total is not None else len(exercise_results)
    return {
        "attempted": attempted_n > 0,
        "completed": total > 0 and completed_n >= total,
        "progress": {"done": completed_n, "target": total},
    }
