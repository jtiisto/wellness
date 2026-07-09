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
- ASSISTED exercises (registry `equipment='assisted'`: the logged weight is
  machine assistance, so more weight = easier): every strength aggregate
  scores them by EFFECTIVE load = body weight (Garmin, nearest sample) minus
  assistance, with the raw assistance echoed alongside. Without a resolvable
  body weight the set is dropped from the aggregates — never scored raw,
  which would rank Feb's 45 lb assist above Jul's 20 lb as a "best".
"""
import bisect
import json
import sqlite3
from collections import Counter, defaultdict
from datetime import date, timedelta
from pathlib import Path

from modules.coach_logs import AD_HOC_LOG_SLUGS
from modules.db import read_transaction
from modules.journal_adherence import (
    compute_adherence,
    compute_streaks,
    day_status,
    target_band_segments,
)

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


ASSISTED_EQUIPMENT = "assisted"


def _bw_kg_for(samples, date_str):
    """Body weight (kg) in effect on `date_str` from date-ascending
    `[(date, kg)]` samples: the most recent sample at-or-before the date,
    else the earliest after (bw drifts slowly; a set logged days before the
    first-ever sample is still better scored than dropped). None when empty."""
    if not samples:
        return None
    dates = [s[0] for s in samples]
    i = bisect.bisect_right(dates, date_str)
    return samples[i - 1][1] if i else samples[0][1]


def _apply_assisted_effective(rows, registry, garmin_db):
    """Rewrite assisted-exercise rows (registry equipment='assisted') to their
    EFFECTIVE load: body weight minus assistance, in the row's own unit, with
    the raw machine weight kept as `assistance`. Rows whose effective load
    can't be resolved (no Garmin data, or assistance >= body weight) are
    dropped — a raw assistance weight must never be ranked as if lifted.
    Non-assisted rows pass through unchanged; the Garmin DB is only opened
    when an assisted row is actually present."""
    assisted = {slug for slug, info in registry.items()
                if info["equipment"] == ASSISTED_EQUIPMENT}
    if not any(r["slug"] in assisted for r in rows):
        return rows
    bw = weight_series(garmin_db, end="9999-12-31")
    samples = ([(s["date"], s["kg"]) for s in bw["series"]]
               if bw["available"] else [])
    out = []
    for r in rows:
        if r["slug"] not in assisted:
            out.append(r)
            continue
        bw_kg = _bw_kg_for(samples, r["date"])
        if bw_kg is None:
            continue
        unit = _norm_unit(r["unit"])
        effective = convert_weight(bw_kg, "kg", unit) - r["weight"]
        if effective <= 0:
            continue
        row = {k: r[k] for k in r.keys()}
        row["assistance"] = r["weight"]
        row["weight"] = effective
        out.append(row)
    return out


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
        assist = r["assistance"] if "assistance" in r.keys() else None
        # Compare on the UNROUNDED weight — rounding the incumbent broke the
        # reps tie-break for unit-converted rows (review F4).
        if best_w is None or (w, r["reps"]) > (best_w["_w"], best_w["reps"]):
            best_w = {"_w": w, "weight": round(w, 1), "reps": r["reps"],
                      "date": r["date"], "assistance": assist}
        if best_e is None or e > best_e["_e"]:
            best_e = {"_e": e, "value": round(e, 1), "weight": round(w, 1),
                      "reps": r["reps"], "date": r["date"], "assistance": assist}
    best_w.pop("_w")
    best_e.pop("_e")
    return {"best_weight": best_w, "best_e1rm": best_e}


def strength_exercises(coach_db, garmin_db, *, start=None, end):
    """Picker + PR board: every canonical slug with ≥1 qualifying set, ordered
    `last_used DESC, name ASC` (recent-usage first; alphabetical secondary so
    near-duplicate slugs sit adjacent — the no-consolidation decision).
    `in_range` bests are None when `start` is absent or the range is empty."""
    with coach_db.get_db() as conn:
        with read_transaction(conn) as cursor:
            rows = [r for r in _fetch_qualifying_sets(cursor) if r["slug"]]
            registry = _registry(cursor)
    rows = _apply_assisted_effective(rows, registry, garmin_db)

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
            "equipment": info["equipment"] if info else None,
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


def strength_exercise_series(coach_db, garmin_db, *, slug, start=None, end):
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

    rows = _apply_assisted_effective(rows, {slug: info}, garmin_db)
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
                           "rpe": r["rpe"], "off_plan": bool(r["off_plan"]),
                           "assistance": (r["assistance"]
                                          if "assistance" in r.keys() else None)})
        top_key = max((s["e"], s["w"]) for s in scored)
        tied = [s for s in scored if (s["e"], s["w"]) == top_key]
        rpes = [s["rpe"] for s in tied if s["rpe"] is not None]
        top = tied[0]
        sessions.append({
            "date": d,
            "top_set": {"weight": round(top["w"], 1), "reps": top["reps"],
                        "assistance": top["assistance"]},
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


# ==================== Journal ====================


def _tracker_meta(meta_json):
    try:
        parsed = json.loads(meta_json) if meta_json else {}
        return parsed if isinstance(parsed, dict) else {}
    except (ValueError, TypeError):
        return {}


def _is_actionable(polarity):
    return polarity in ("positive", "negative")


def journal_trackers(journal_db):
    """Picker: quantifiable trackers ∪ actionable trackers (positive/negative
    polarity), excluding deleted and never-logged ones. Ordered by last_entry
    DESC (most recently active first), name ASC secondary."""
    with journal_db.get_db() as conn:
        with read_transaction(conn) as cursor:
            rows = cursor.execute("""
                SELECT t.id, t.name, t.type, t.polarity, t.meta_json,
                       t.target_json,
                       MIN(e.date) AS first_entry, MAX(e.date) AS last_entry
                FROM trackers t
                JOIN entries e ON e.tracker_id = t.id
                WHERE t.deleted = 0
                GROUP BY t.id
            """).fetchall()

    trackers = []
    for r in rows:
        if r["type"] != "quantifiable" and not _is_actionable(r["polarity"]):
            continue
        meta = _tracker_meta(r["meta_json"])
        trackers.append({
            "id": r["id"],
            "name": r["name"],
            "type": r["type"],
            "unit": meta.get("unit"),
            "polarity": r["polarity"],
            "actionable": _is_actionable(r["polarity"]),
            "has_target": bool(r["target_json"]),
            "first_entry": r["first_entry"],
            "last_entry": r["last_entry"],
        })
    trackers.sort(key=lambda t: t["name"])
    trackers.sort(key=lambda t: t["last_entry"], reverse=True)
    return {"trackers": trackers}


def journal_tracker_detail(journal_db, *, tracker_id, start=None, end, today):
    """Values + stepped target segments + weekly adherence buckets + streaks
    for one tracker. Effective start = max(start, first_entry): pre-tracking
    epochs are gaps, never misses. Weekly rows = one compute_adherence call
    per Monday bucket, mapped per polarity/target; a zero-scheduled week
    (pause / fully off-schedule) is `paused` and renders muted.
    NEUTRAL (non-actionable) trackers additionally get `weekly_usage`
    entry-count buckets — for episodic observations (an as-needed med) the
    signal is how OFTEN, not the value, which is frequently constant.
    Raises ValueError for an unknown/deleted tracker (→ 404)."""
    with journal_db.get_db() as conn:
        with read_transaction(conn) as cursor:
            t = cursor.execute(
                "SELECT id, name, type, polarity, meta_json, schedule_json, "
                "target_json FROM trackers WHERE id = ? AND deleted = 0",
                (tracker_id,),
            ).fetchone()
            if t is None:
                raise ValueError(f"Unknown tracker: {tracker_id}")
            entry_rows = cursor.execute(
                "SELECT date, value, completed FROM entries "
                "WHERE tracker_id = ? ORDER BY date",
                (tracker_id,),
            ).fetchall()

    if not entry_rows:
        raise ValueError(f"Tracker has no entries: {tracker_id}")
    first_entry = entry_rows[0]["date"]
    last_entry = entry_rows[-1]["date"]

    eff_start = max(start or first_entry, first_entry)
    entries = {r["date"]: r["completed"] for r in entry_rows}
    values = {r["date"]: r["value"] for r in entry_rows}
    meta = _tracker_meta(t["meta_json"])

    in_range = [r for r in entry_rows if eff_start <= r["date"] <= end]

    weekly = []
    if eff_start <= end:
        for monday, sunday in week_buckets(date.fromisoformat(eff_start),
                                           date.fromisoformat(end)):
            # The floored Monday is only the bucket LABEL: the first bucket's
            # window clamps to eff_start so pre-tracking / pre-range days are
            # gaps, never scheduled misses (review F14) — and weekly_usage
            # below counts over the same clamped windows (F5).
            m = compute_adherence(
                t["schedule_json"], t["polarity"], t["type"], entries,
                max(monday.isoformat(), eff_start),
                min(sunday, date.fromisoformat(end)).isoformat(),
                target_json=t["target_json"], values=values,
                meta_json=t["meta_json"],
            )
            scheduled = m["scheduled_days"]
            has_target = "blended_met_days" in m
            if has_target:
                # Blended, not target_met_days: a week before the target took
                # effect must count its checkbox-met days, or the whole
                # pre-target history renders as missed.
                met = m["blended_met_days"]
                partial_days = m["target_partial_days"]
            elif t["polarity"] == "negative":
                met = scheduled - m["logged_days"]
                partial_days = 0
            else:
                met = m["done_days"]
                partial_days = 0
            rate_key = f"{m['metric_kind']}_rate"
            weekly.append({
                "week_start": monday.isoformat(),
                "partial": monday <= today <= sunday,
                "paused": scheduled == 0,
                "scheduled_days": scheduled,
                "met": met,
                "partial_days": partial_days,
                "missed": max(0, scheduled - met - partial_days),
                "rate": m.get(rate_key, m["coverage_rate"]),
                "metric_kind": m["metric_kind"],
            })

    result = {
        "tracker": {
            "id": t["id"], "name": t["name"], "type": t["type"],
            "unit": meta.get("unit"), "polarity": t["polarity"],
            "actionable": _is_actionable(t["polarity"]),
            "has_target": bool(t["target_json"]),
            "first_entry": first_entry, "last_entry": last_entry,
        },
        "values": [
            {"date": r["date"], "value": r["value"], "completed": r["completed"]}
            for r in in_range
        ],
        "target_segments": target_band_segments(t["target_json"], eff_start, end),
        "weekly_adherence": weekly,
        "streaks": compute_streaks(
            t["schedule_json"], t["polarity"], entries, values,
            t["target_json"], t["meta_json"],
            first_date=first_entry, today=today.isoformat(),
        ),
    }
    if not result["tracker"]["actionable"] and eff_start <= end:
        in_range_dates = [r["date"] for r in in_range]
        result["weekly_usage"] = [
            {
                "week_start": monday.isoformat(),
                "partial": monday <= today <= sunday,
                "count": sum(1 for d in in_range_dates
                             if monday.isoformat() <= d <= sunday.isoformat()),
            }
            for monday, sunday in week_buckets(date.fromisoformat(eff_start),
                                               date.fromisoformat(end))
        ]
    return result


# ==================== Overview ====================

# Overview constants (decision: config-free, tuned against current data;
# iterate from real use).
OVERVIEW_SPARKLINE_WEEKS = 8
OVERVIEW_FOCUS_WINDOW_DAYS = 14      # rolling adherence window for focus rows
OVERVIEW_FOCUS_ACTIVE_DAYS = 28      # tracker must have an entry this recently
OVERVIEW_FOCUS_COUNT = 3
PR_WINDOW_DAYS = 30


def detect_prs(sessions):
    """PRs from per-session top-set records `[{slug, date, e1rm, ...}]`
    (date-ascending): a session is a PR when its e1RM STRICTLY exceeds the
    slug's prior all-time max. A slug's first-ever session is the baseline,
    not a PR."""
    prs = []
    best = {}
    for s in sessions:
        prev = best.get(s["slug"])
        if prev is None:
            best[s["slug"]] = s["e1rm"]
            continue
        if s["e1rm"] > prev:
            prs.append(s)
            best[s["slug"]] = s["e1rm"]
    return prs


def _per_session_e1rms(coach_db, garmin_db):
    """All-time per-slug per-session top e1RM records, date-ascending, in the
    slug's dominant unit — the detect_prs input."""
    with coach_db.get_db() as conn:
        with read_transaction(conn) as cursor:
            rows = [r for r in _fetch_qualifying_sets(cursor) if r["slug"]]
            registry = _registry(cursor)
    rows = _apply_assisted_effective(rows, registry, garmin_db)

    by_slug = defaultdict(list)
    for r in rows:
        by_slug[r["slug"]].append(r)

    sessions = []
    for slug, slug_rows in by_slug.items():
        unit = _dominant_unit(slug_rows)
        by_date = defaultdict(list)
        for r in slug_rows:
            by_date[r["date"]].append(r)
        for d, day_rows in by_date.items():
            top = max(
                ({"w": convert_weight(r["weight"], _norm_unit(r["unit"]), unit),
                  "reps": r["reps"]} for r in day_rows),
                key=lambda s: (epley_e1rm(s["w"], s["reps"]), s["w"]),
            )
            sessions.append({
                "slug": slug,
                "name": registry[slug]["name"] if slug in registry else slug,
                "date": d,
                "e1rm": round(epley_e1rm(top["w"], top["reps"]), 1),
                "weight": round(top["w"], 1),
                "reps": top["reps"],
                "unit": unit,
            })
    sessions.sort(key=lambda s: s["date"])
    return sessions


def overview(coach_db, journal_db, garmin_db, *, today):
    """The landing tiles: last COMPLETE ISO week's Zone 2 + tonnage vs the
    mean of the 4 complete weeks before it (this week rides along as a
    no-delta "so far"; 8-week sparklines), the ≤3 weakest actionable trackers
    by rolling 14-day adherence, and PRs in the last 30 days."""
    spark_start = (week_start(today) - timedelta(weeks=OVERVIEW_SPARKLINE_WEEKS - 1))
    end = today.isoformat()

    cardio = cardio_weekly(coach_db, start=spark_start.isoformat(), end=end, today=today)
    volume = strength_weekly_volume(coach_db, garmin_db,
                                    start=spark_start.isoformat(), end=end, today=today)

    def tile(weeks, value_of):
        """Headline = the LAST COMPLETE week vs the mean of the 4 complete
        weeks before it — always a like-for-like comparison (a week-to-date
        total vs complete weeks is only valid moments before the week ends).
        The in-progress week rides along as `this_week` with no delta."""
        if not weeks:
            return {"this_week": 0, "last_week": None, "four_week_avg": None,
                    "sparkline": []}
        this_week = value_of(weeks[-1]) if weeks[-1]["partial"] else 0
        complete = [w for w in weeks if not w["partial"]]
        prev4 = complete[-5:-1]
        return {
            "this_week": round(this_week, 1),
            "last_week": round(value_of(complete[-1]), 1) if complete else None,
            "four_week_avg": round(sum(value_of(w) for w in prev4) / len(prev4), 1)
                             if prev4 else None,
        }

    zone2_tile = tile(cardio["weeks"],
                      lambda w: w["zone2_planned_min"] + w["zone2_extra_min"])
    zone2_tile["sparkline"] = [
        {"week_start": w["week_start"], "planned_min": w["zone2_planned_min"],
         "extra_min": w["zone2_extra_min"]}
        for w in cardio["weeks"]
    ]
    tonnage_tile = tile(volume["weeks"], lambda w: w["tonnage_kg"])
    tonnage_tile["sparkline"] = [
        {"week_start": w["week_start"], "tonnage_kg": w["tonnage_kg"]}
        for w in volume["weeks"]
    ]

    # Adherence focus: weakest actionable trackers over a rolling 14d window.
    focus_cutoff = (today - timedelta(days=OVERVIEW_FOCUS_ACTIVE_DAYS)).isoformat()
    window_start = (today - timedelta(days=OVERVIEW_FOCUS_WINDOW_DAYS - 1)).isoformat()
    with journal_db.get_db() as conn:
        with read_transaction(conn) as cursor:
            trackers = cursor.execute("""
                SELECT t.id, t.name, t.polarity, t.type, t.meta_json,
                       t.schedule_json, t.target_json, MAX(e.date) AS last_entry,
                       MIN(e.date) AS first_entry
                FROM trackers t
                JOIN entries e ON e.tracker_id = t.id
                WHERE t.deleted = 0 AND t.polarity IN ('positive', 'negative')
                GROUP BY t.id
                HAVING last_entry >= ?
            """, (focus_cutoff,)).fetchall()
            tracker_entries = {}
            for t in trackers:
                rows = cursor.execute(
                    "SELECT date, value, completed FROM entries "
                    "WHERE tracker_id = ? AND date >= ?",
                    (t["id"], window_start),
                ).fetchall()
                tracker_entries[t["id"]] = rows

    focus = []
    for t in trackers:
        rows = tracker_entries[t["id"]]
        entries = {r["date"]: r["completed"] for r in rows}
        values = {r["date"]: r["value"] for r in rows}
        # Clamp the window to the tracker's first entry: pre-creation days
        # are gaps, not misses — a brand-new tracker must not top the
        # weakest-adherence list on days it didn't exist (review F10).
        t_start = max(window_start, t["first_entry"])
        m = compute_adherence(
            t["schedule_json"], t["polarity"], t["type"], entries,
            t_start, end, target_json=t["target_json"], values=values,
            meta_json=t["meta_json"],
        )
        rate = m.get(f"{m['metric_kind']}_rate")
        if rate is None:
            continue  # paused / nothing scheduled — not a focus candidate
        ribbon = []
        d = date.fromisoformat(window_start)
        while d <= today:
            ds = d.isoformat()
            ribbon.append({"date": ds, "status": "off" if ds < t_start
                           else day_status(
                               t["schedule_json"], t["polarity"], entries, values,
                               t["target_json"], t["meta_json"], ds)})
            d += timedelta(days=1)
        focus.append({
            "tracker_id": t["id"], "name": t["name"],
            "metric_kind": m["metric_kind"], "rate": rate, "ribbon": ribbon,
        })
    focus.sort(key=lambda f: f["rate"])
    focus = focus[:OVERVIEW_FOCUS_COUNT]

    # PRs in the last 30 days — inclusive window like the focus window
    # (days=30 spanned exactly 31 calendar days, review F17).
    pr_cutoff = (today - timedelta(days=PR_WINDOW_DAYS - 1)).isoformat()
    all_prs = detect_prs(_per_session_e1rms(coach_db, garmin_db))
    recent = [p for p in all_prs if p["date"] >= pr_cutoff]

    return {
        "zone2": {"this_week_min": zone2_tile["this_week"],
                  "last_week_min": zone2_tile["last_week"],
                  "four_week_avg_min": zone2_tile["four_week_avg"],
                  "sparkline": zone2_tile["sparkline"]},
        "tonnage": {"this_week_kg": tonnage_tile["this_week"],
                    "last_week_kg": tonnage_tile["last_week"],
                    "four_week_avg_kg": tonnage_tile["four_week_avg"],
                    "sparkline": tonnage_tile["sparkline"]},
        "adherence_focus": focus,
        "prs": {"count_30d": len(recent),
                "latest": recent[-1] if recent else None},
    }


# ==================== Body weight (Garmin) ====================


def weight_series(garmin_db, *, start=None, end):
    """Daily body weight (kg) from the Garmin health DB's body_composition
    table — last measurement per day. The DB is external (written by the
    user's sync job) and may legitimately be absent or unreadable: degrade to
    {"available": False} so the chart hides instead of erroring."""
    if not Path(garmin_db.path).exists():
        return {"available": False, "series": []}
    try:
        with garmin_db.get_db() as conn:
            params = [end]
            sql = (
                # Bare column + MAX(timestamp_gmt) in GROUP BY: SQLite
                # (documented behavior) returns the weight from the row that
                # holds the max timestamp — i.e. the day's LAST measurement.
                "SELECT measurement_date AS date, weight_grams, MAX(timestamp_gmt) "
                "FROM body_composition "
                "WHERE weight_grams IS NOT NULL AND measurement_date <= ?"
            )
            if start:
                sql += " AND measurement_date >= ?"
                params.append(start)
            sql += " GROUP BY measurement_date ORDER BY measurement_date"
            rows = conn.execute(sql, params).fetchall()
    except sqlite3.Error:
        # Missing table / schema drift / mode=ro open race — hide, don't 500.
        return {"available": False, "series": []}

    return {
        "available": True,
        "series": [
            {"date": str(r["date"]), "kg": round(r["weight_grams"] / 1000, 1)}
            for r in rows
        ],
    }


def recovery_series(garmin_db, *, start=None, end):
    """Daily recovery signals for the Health tab: resting HR, last-night HRV
    with GARMIN'S OWN baseline band (no invented thresholds — balanced range
    plus the low-zone ceiling), sleep hours and score. Reads
    daily_health_metrics via the same external-source contract as
    weight_series: absent DB / missing table / schema drift degrade to
    {"available": False} — never a 500. Per-field nulls pass through (the
    charts skip them); no imputation."""
    if not Path(garmin_db.path).exists():
        return {"available": False, "days": []}
    try:
        with garmin_db.get_db() as conn:
            params = [end]
            sql = (
                "SELECT metric_date AS date, resting_heart_rate, "
                "hrv_last_night_avg, hrv_baseline_balanced_low, "
                "hrv_baseline_balanced_upper, hrv_baseline_low_upper, "
                "sleep_duration_hours, sleep_score "
                "FROM daily_health_metrics WHERE metric_date <= ?"
            )
            if start:
                sql += " AND metric_date >= ?"
                params.append(start)
            sql += " ORDER BY metric_date"
            rows = conn.execute(sql, params).fetchall()
    except sqlite3.Error:
        return {"available": False, "days": []}

    days = []
    for r in rows:
        band = None
        if (r["hrv_baseline_balanced_low"] is not None
                and r["hrv_baseline_balanced_upper"] is not None):
            band = {"low": r["hrv_baseline_balanced_low"],
                    "high": r["hrv_baseline_balanced_upper"],
                    "low_floor": r["hrv_baseline_low_upper"]}
        days.append({
            "date": str(r["date"]),
            "rhr": r["resting_heart_rate"],
            "hrv": r["hrv_last_night_avg"],
            "hrv_band": band,
            "sleep_hours": (round(r["sleep_duration_hours"], 2)
                            if r["sleep_duration_hours"] is not None else None),
            "sleep_score": r["sleep_score"],
        })
    return {"available": True, "days": days}


def composition_series(bodyspec_db, *, end):
    """DEXA scans from the BodySpec DB for the Health tab: lean/fat/total
    mass (kg), body-fat %, VAT, A/G ratio, plus the whole-body BMD row.
    Returns ALL scans up to `end` — scans are months apart, so the UI shows
    the full history regardless of the range selector (the weight-chart
    overlay filters client-side). Same external-source degradation contract
    as the Garmin readers: absent DB / missing table → {"available": False},
    never a 500. The .bak files beside the DB show the sync tool rewrites it;
    a mid-read replacement surfaces as sqlite3.Error → degrade."""
    if not Path(bodyspec_db.path).exists():
        return {"available": False, "scans": []}
    try:
        with bodyspec_db.get_db() as conn:
            rows = conn.execute(
                "SELECT s.scan_date AS date, s.lean_mass_kg, s.fat_mass_kg, "
                "s.total_mass_kg, s.total_body_fat_pct, s.vat_mass_kg, "
                "s.ag_ratio, b.bmd_g_cm2, b.t_score "
                "FROM scans s "
                "LEFT JOIN scan_bone_density b "
                "  ON b.scan_date = s.scan_date AND b.region = 'total' "
                "WHERE s.scan_date <= ? ORDER BY s.scan_date",
                (end,),
            ).fetchall()
    except sqlite3.Error:
        return {"available": False, "scans": []}

    def _r(v, nd=2):
        return round(v, nd) if v is not None else None

    return {
        "available": True,
        "scans": [
            {
                "date": str(r["date"]),
                "lean_kg": _r(r["lean_mass_kg"]),
                "fat_kg": _r(r["fat_mass_kg"]),
                "total_kg": _r(r["total_mass_kg"]),
                "body_fat_pct": _r(r["total_body_fat_pct"], 1),
                "vat_kg": _r(r["vat_mass_kg"]),
                "ag_ratio": _r(r["ag_ratio"]),
                "bmd_total": _r(r["bmd_g_cm2"], 3),
                "t_score_total": _r(r["t_score"], 1),
            }
            for r in rows
        ],
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


def strength_weekly_volume(coach_db, garmin_db, *, start=None, end, today):
    """Weekly tonnage (kg) + hard-set counts, with a per-exercise breakdown
    for stacking (tonnage desc; slug-less rows group under their exercise_key).
    Weeks with no work are emitted with zeros (continuous axis). All-range
    (`start` None) starts at the earliest qualifying set. Assisted sets count
    their EFFECTIVE load (bw − assistance) — assistance is not tonnage."""
    with coach_db.get_db() as conn:
        with read_transaction(conn) as cursor:
            rows = _fetch_qualifying_sets(cursor)
            registry = _registry(cursor)
    rows = _apply_assisted_effective(rows, registry, garmin_db)

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
