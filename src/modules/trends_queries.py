"""Trends aggregate queries — the domain layer behind the read-only charts.

Conventions (see docs/ARCHITECTURE.md "Trends" and the plan spec):
- Functions take read-only `DbAccessor`s (`with db.get_db() as conn:`) and an
  injected `today: datetime.date` where a calendar "now" matters — clock-free
  and unit-testable, like coach_queries.
- All dates are LOCAL calendar `YYYY-MM-DD` strings (the repo-wide convention
  for coach/journal `date` columns).
- Weeks are ISO calendar weeks identified by their MONDAY. Weekly aggregates
  floor a requested `start` down to its Monday (no ragged first bar) and flag
  the week containing `today` as `partial` so consumers don't compare an
  in-progress week against complete ones.
- Multi-SELECT aggregates run inside `read_transaction` on one connection so
  the numbers come from a single WAL snapshot while the owning module writes.
- Qualifying strength set: `weight IS NOT NULL AND reps IS NOT NULL AND
  reps > 0` — presence-based, matching the derived-completion philosophy (the
  legacy per-set `completed` tick is deliberately ignored here).
- Off-plan semantics mirror `coach_logs.is_off_plan_entry`, applied in SQL
  with the key set taken from `AD_HOC_LOG_SLUGS` so the two can't drift.
"""
from collections import Counter, defaultdict
from datetime import date, timedelta

from modules.coach_logs import AD_HOC_LOG_SLUGS
from modules.db import read_transaction

# lbs per kg — the single conversion constant.
_LBS_PER_KG = 1 / 0.45359237
_KG_PER_LB = 0.45359237


# ==================== Pure helpers ====================


def week_start(d: date) -> date:
    """The Monday of d's ISO week."""
    return d - timedelta(days=d.weekday())


def week_buckets(start: date, end: date) -> list:
    """[(monday, sunday), ...] covering [start, end]; start floors to its
    Monday. Empty when start > end."""
    buckets = []
    monday = week_start(start)
    while monday <= end:
        buckets.append((monday, monday + timedelta(days=6)))
        monday += timedelta(days=7)
    return buckets


def epley_e1rm(weight: float, reps: int) -> float:
    """Estimated 1RM (Epley): weight × (1 + reps/30), with the conventional
    special case that a true single IS its own 1RM (the raw formula would
    overestimate a 1-rep set by 3.3%)."""
    if reps == 1:
        return float(weight)
    return weight * (1 + reps / 30)


def convert_weight(value: float, from_unit: str, to_unit: str) -> float:
    """lbs↔kg conversion; same-unit (or unknown-unit) values pass through."""
    if from_unit == to_unit:
        return value
    if from_unit == "lbs" and to_unit == "kg":
        return value * _KG_PER_LB
    if from_unit == "kg" and to_unit == "lbs":
        return value * _LBS_PER_KG
    return value


def to_kg(weight: float, unit: str) -> float:
    return weight if unit == "kg" else weight * _KG_PER_LB if unit == "lbs" else weight


def _norm_unit(unit) -> str:
    """set_logs.unit defaults to 'lbs'; NULL/empty means the default."""
    return unit if unit in ("lbs", "kg") else "lbs"


# ==================== Strength ====================

# One row per qualifying set, with everything the aggregations need. The
# off_plan arm mirrors coach_logs.is_off_plan_entry (adhoc keys interpolated
# as parameters below).
_QUALIFYING_SETS_SQL = """
    SELECT wsl.date AS date,
           el.canonical_slug AS slug,
           el.exercise_key AS exercise_key,
           sl.weight AS weight, sl.reps AS reps, sl.rpe AS rpe, sl.unit AS unit,
           (el.exercise_id IS NULL AND
            (wsl.session_id IS NULL OR el.exercise_key IN ({adhoc}))) AS off_plan
    FROM set_logs sl
    JOIN exercise_logs el ON el.id = sl.exercise_log_id
    JOIN workout_session_logs wsl ON wsl.id = el.session_log_id
    WHERE sl.weight IS NOT NULL AND sl.reps IS NOT NULL AND sl.reps > 0
    ORDER BY wsl.date
"""


def _fetch_qualifying_sets(cursor):
    adhoc_keys = list(AD_HOC_LOG_SLUGS)
    sql = _QUALIFYING_SETS_SQL.format(adhoc=",".join("?" for _ in adhoc_keys))
    return cursor.execute(sql, adhoc_keys).fetchall()


def _registry(cursor):
    return {
        r["slug"]: r
        for r in cursor.execute(
            "SELECT slug, name, equipment, category FROM exercises"
        ).fetchall()
    }


def _dominant_unit(rows) -> str:
    counts = Counter(_norm_unit(r["unit"]) for r in rows)
    # Deterministic tie-break: lbs (the schema default) wins ties.
    return max(counts, key=lambda u: (counts[u], u == "lbs"))


def _best_of(rows, unit):
    """Best set (weight + e1RM records) over rows, in `unit`.
    Returns None when rows is empty."""
    if not rows:
        return None
    best_w, best_e = None, None
    for r in rows:
        w = convert_weight(r["weight"], _norm_unit(r["unit"]), unit)
        e = epley_e1rm(w, r["reps"])
        if best_w is None or (w, r["reps"]) > (best_w["weight"], best_w["reps"]):
            best_w = {"weight": round(w, 1), "reps": r["reps"], "date": r["date"]}
        if best_e is None or e > best_e["_e"]:
            best_e = {"_e": e, "value": round(e, 1), "weight": round(w, 1),
                      "reps": r["reps"], "date": r["date"]}
    best_e.pop("_e")
    return {"best_weight": best_w, "best_e1rm": best_e}


def strength_exercises(coach_db, *, start=None, end):
    """Picker + PR board: every canonical slug with ≥1 qualifying set, ordered
    `last_used DESC, name ASC` (recent-usage first; alphabetical secondary so
    near-duplicate slugs sit adjacent — the no-consolidation decision).
    `in_range` bests are None when `start` is absent or the range is empty."""
    with coach_db.get_db() as conn:
        with read_transaction(conn) as cursor:
            rows = [r for r in _fetch_qualifying_sets(cursor) if r["slug"]]
            registry = _registry(cursor)

    by_slug = defaultdict(list)
    for r in rows:
        if r["date"] <= end:
            by_slug[r["slug"]].append(r)

    exercises = []
    for slug, slug_rows in by_slug.items():
        unit = _dominant_unit(slug_rows)
        in_range_rows = [r for r in slug_rows if start and start <= r["date"]]
        info = registry.get(slug, {})
        exercises.append({
            "slug": slug,
            "name": info["name"] if info else slug,
            "last_used": max(r["date"] for r in slug_rows),
            "session_count": len({r["date"] for r in slug_rows}),
            "unit": unit,
            "all_time": _best_of(slug_rows, unit),
            "in_range": _best_of(in_range_rows, unit) if start else None,
        })

    # last_used DESC with name ASC as secondary: stable sort by name first,
    # then by last_used (preserves name order within equal dates).
    exercises.sort(key=lambda e: e["name"])
    exercises.sort(key=lambda e: e["last_used"], reverse=True)
    return {"exercises": exercises}


def strength_exercise_series(coach_db, *, slug, start=None, end):
    """Per-session progression for one canonical slug: top set (max e1RM;
    tie → higher weight), its RPE (ties → mean of non-null RPEs among tied
    sets), set count, and the off-plan flag of the top set's row.
    Raises ValueError for a slug not in the registry (→ 404)."""
    with coach_db.get_db() as conn:
        with read_transaction(conn) as cursor:
            info = cursor.execute(
                "SELECT slug, name, equipment, category FROM exercises WHERE slug = ?",
                (slug,),
            ).fetchone()
            if info is None:
                raise ValueError(f"Unknown exercise slug: {slug}")
            rows = [r for r in _fetch_qualifying_sets(cursor) if r["slug"] == slug]

    rows = [r for r in rows if (not start or r["date"] >= start) and r["date"] <= end]
    unit = _dominant_unit(rows) if rows else "lbs"

    by_date = defaultdict(list)
    for r in rows:
        by_date[r["date"]].append(r)

    sessions = []
    for d in sorted(by_date):
        scored = []
        for r in by_date[d]:
            w = convert_weight(r["weight"], _norm_unit(r["unit"]), unit)
            scored.append({"w": w, "reps": r["reps"], "e": epley_e1rm(w, r["reps"]),
                           "rpe": r["rpe"], "off_plan": bool(r["off_plan"])})
        top_key = max((s["e"], s["w"]) for s in scored)
        tied = [s for s in scored if (s["e"], s["w"]) == top_key]
        rpes = [s["rpe"] for s in tied if s["rpe"] is not None]
        top = tied[0]
        sessions.append({
            "date": d,
            "top_set": {"weight": round(top["w"], 1), "reps": top["reps"]},
            "e1rm": round(top["e"], 1),
            "top_set_rpe": round(sum(rpes) / len(rpes), 1) if rpes else None,
            "set_count": len(scored),
            "off_plan": top["off_plan"],
        })

    return {
        "exercise": {"slug": info["slug"], "name": info["name"],
                     "equipment": info["equipment"], "category": info["category"]},
        "unit": unit,
        "sessions": sessions,
    }


# ==================== Cardio ====================

# All cardio-relevant log rows. Zone 2 identification is TYPE-based
# (planned_exercises.exercise_type='duration'), not slug-based — prod Zone 2
# slugs are fragmented (zone_2, zone_2_block, zone_2_flush, ...). Extras are
# the off-plan predicate; orphaned rows (exercise_id NULL, ordinary key, on a
# planned day) match neither arm and are deliberately excluded — their
# provenance is unknown.
_CARDIO_ROWS_SQL = """
    SELECT wsl.date AS date,
           el.duration_min AS duration_min,
           el.avg_hr AS avg_hr,
           pe.exercise_type AS ptype,
           (el.exercise_id IS NULL AND
            (wsl.session_id IS NULL OR el.exercise_key IN ({adhoc}))) AS off_plan,
           (SELECT COUNT(*) FROM set_logs s WHERE s.exercise_log_id = el.id) AS set_count
    FROM exercise_logs el
    JOIN workout_session_logs wsl ON wsl.id = el.session_log_id
    LEFT JOIN planned_exercises pe ON pe.id = el.exercise_id
    ORDER BY wsl.date
"""

# Aerobic-proxy inclusion floor: steady sessions shorter than this carry too
# little signal for an avg-HR trend.
STEADY_PROXY_MIN_DURATION = 20


def cardio_weekly(coach_db, *, start=None, end, today):
    """Weekly Zone 2 minutes split planned/extra, interval session counts,
    and the steady-session points for the aerobic proxy (avg HR of ≥20-min
    steady work). Weeks follow the volume conventions (Monday buckets,
    floored start, `partial` flag, zero weeks emitted, All → earliest row)."""
    adhoc_keys = list(AD_HOC_LOG_SLUGS)
    sql = _CARDIO_ROWS_SQL.format(adhoc=",".join("?" for _ in adhoc_keys))
    with coach_db.get_db() as conn:
        with read_transaction(conn) as cursor:
            rows = cursor.execute(sql, adhoc_keys).fetchall()

    def is_planned_steady(r):
        return r["ptype"] == "duration" and r["duration_min"] is not None

    def is_extra(r):
        return bool(r["off_plan"]) and r["duration_min"] is not None

    def is_interval(r):
        return r["ptype"] == "interval" and (
            r["duration_min"] is not None or r["set_count"] > 0
        )

    cardio_rows = [r for r in rows
                   if r["date"] <= end and (is_planned_steady(r) or is_extra(r) or is_interval(r))]
    if start:
        range_start = date.fromisoformat(start)
    elif cardio_rows:
        range_start = date.fromisoformat(min(r["date"] for r in cardio_rows))
    else:
        return {"weeks": [], "steady_sessions": []}
    floor = week_start(range_start).isoformat()
    cardio_rows = [r for r in cardio_rows if r["date"] >= floor]

    weeks = []
    for monday, sunday in week_buckets(range_start, date.fromisoformat(end)):
        in_week = [r for r in cardio_rows
                   if monday.isoformat() <= r["date"] <= sunday.isoformat()]
        weeks.append({
            "week_start": monday.isoformat(),
            "partial": monday <= today <= sunday,
            "zone2_planned_min": round(sum(
                r["duration_min"] for r in in_week if is_planned_steady(r)), 1),
            "zone2_extra_min": round(sum(
                r["duration_min"] for r in in_week if is_extra(r)), 1),
            "interval_sessions": sum(1 for r in in_week if is_interval(r)),
        })

    steady_sessions = [
        {"date": r["date"], "avg_hr": r["avg_hr"],
         "duration_min": r["duration_min"], "off_plan": bool(r["off_plan"])}
        for r in cardio_rows
        if (is_planned_steady(r) or is_extra(r))
        and r["avg_hr"] is not None
        and r["duration_min"] >= STEADY_PROXY_MIN_DURATION
    ]

    return {"weeks": weeks, "steady_sessions": steady_sessions}


def strength_weekly_volume(coach_db, *, start=None, end, today):
    """Weekly tonnage (kg) + hard-set counts, with a per-exercise breakdown
    for stacking (tonnage desc; slug-less rows group under their exercise_key).
    Weeks with no work are emitted with zeros (continuous axis). All-range
    (`start` None) starts at the earliest qualifying set."""
    with coach_db.get_db() as conn:
        with read_transaction(conn) as cursor:
            rows = _fetch_qualifying_sets(cursor)
            registry = _registry(cursor)

    rows = [r for r in rows if r["date"] <= end]
    if start:
        range_start = date.fromisoformat(start)
    elif rows:
        range_start = date.fromisoformat(min(r["date"] for r in rows))
    else:
        return {"weeks": []}
    floor = week_start(range_start).isoformat()
    rows = [r for r in rows if r["date"] >= floor]

    weeks = []
    for monday, sunday in week_buckets(range_start, date.fromisoformat(end)):
        week_rows = [r for r in rows
                     if monday.isoformat() <= r["date"] <= sunday.isoformat()]
        per_ex = defaultdict(lambda: {"tonnage_kg": 0.0, "hard_sets": 0})
        for r in week_rows:
            key = r["slug"] or r["exercise_key"]
            kg = to_kg(r["weight"], _norm_unit(r["unit"])) * r["reps"]
            per_ex[key]["tonnage_kg"] += kg
            per_ex[key]["hard_sets"] += 1
        by_exercise = [
            {"slug": key,
             "name": registry[key]["name"] if key in registry else key,
             "tonnage_kg": round(v["tonnage_kg"], 1),
             "hard_sets": v["hard_sets"]}
            for key, v in per_ex.items()
        ]
        by_exercise.sort(key=lambda x: x["tonnage_kg"], reverse=True)
        weeks.append({
            "week_start": monday.isoformat(),
            "partial": monday <= today <= sunday,
            "tonnage_kg": round(sum(v["tonnage_kg"] for v in per_ex.values()), 1),
            "hard_sets": sum(v["hard_sets"] for v in per_ex.values()),
            "by_exercise": by_exercise,
        })
    return {"weeks": weeks}
