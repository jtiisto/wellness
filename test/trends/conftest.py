"""Trends test fixtures: multi-week coach histories seeded by direct sqlite.

The coach sync API only writes "today", so trends tests (which need date
SPREADS) seed the tmp coach DB directly — the same approach as test/seeds.py.
The `client` fixture must be pulled first so the coach router has initialized
the schema.
"""

import sqlite3
from datetime import date, timedelta

import pytest

NOW = "2026-01-01T00:00:00Z"  # server stamps are irrelevant to trends reads


def _iso(d):
    return d.isoformat()


@pytest.fixture
def strength_history(client, tmp_coach_db):
    """Six weeks of bench/squat history plus the edge rows every strength
    aggregation must handle:

    - linked (on-plan) exercise_logs with qualifying sets, lbs
    - one kg set on a bench day (dominant-unit conversion)
    - a null-RPE set and a non-qualifying bodyweight set (weight NULL)
    - an ORPHAN row: planned day, exercise_id NULL, ordinary key, slug kept —
      included in the series, NOT off-plan
    - an OFF-PLAN row: plan-less day (session_id NULL), slug present — the
      future ad-hoc-strength generalization; series marks it off_plan
    - a slug-less plan-less row ('adhoc_press') — volume groups it under its
      exercise_key; absent from the picker

    Returns {'today', 'bench_dates', ...} for assertions.
    """
    today = date.today()
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()

    for slug, name in (("bench_press", "Bench Press"),
                       ("kb_goblet_squat", "KB Goblet Squat")):
        cur.execute(
            "INSERT OR IGNORE INTO exercises (slug, name, category, created_at, source) "
            "VALUES (?, ?, 'strength', ?, 'test')", (slug, name, NOW),
        )

    def seed_planned_day(d, entries):
        """entries: [(key, slug, sets)] where sets: [(weight, reps, rpe, unit)]"""
        cur.execute(
            "INSERT INTO workout_sessions (date, day_name, last_modified, modified_by) "
            "VALUES (?, 'Strength', ?, 'test')", (_iso(d), NOW),
        )
        session_id = cur.lastrowid
        cur.execute(
            "INSERT INTO session_blocks (session_id, position, block_type, title) "
            "VALUES (?, 0, 'strength', 'Main')", (session_id,),
        )
        block_id = cur.lastrowid
        cur.execute(
            "INSERT INTO workout_session_logs (session_id, date, last_modified, modified_by) "
            "VALUES (?, ?, ?, 'test')", (session_id, _iso(d), NOW),
        )
        log_id = cur.lastrowid
        for pos, (key, slug, sets) in enumerate(entries):
            cur.execute(
                "INSERT INTO planned_exercises (session_id, block_id, exercise_key, "
                "position, name, exercise_type, target_sets, canonical_slug) "
                "VALUES (?, ?, ?, ?, ?, 'strength', 3, ?)",
                (session_id, block_id, key, pos, key, slug),
            )
            pe_id = cur.lastrowid
            cur.execute(
                "INSERT INTO exercise_logs (session_log_id, exercise_id, exercise_key, "
                "canonical_slug, last_modified) VALUES (?, ?, ?, ?, ?)",
                (log_id, pe_id, key, slug, NOW),
            )
            el_id = cur.lastrowid
            for set_num, (w, reps, rpe, unit) in enumerate(sets, 1):
                cur.execute(
                    "INSERT INTO set_logs (exercise_log_id, set_num, weight, reps, rpe, unit) "
                    "VALUES (?, ?, ?, ?, ?, ?)", (el_id, set_num, w, reps, rpe, unit),
                )
        return log_id

    # Bench progresses 90x8 → 100x5 over recent weeks; squat is steady.
    bench_dates = [today - timedelta(days=n) for n in (37, 23, 9, 2)]
    seed_planned_day(bench_dates[0], [
        ("bench", "bench_press", [(85, 8, 7.0, "lbs"), (85, 8, 7.5, "lbs")]),
    ])
    seed_planned_day(bench_dates[1], [
        ("bench", "bench_press", [(90, 8, 8.0, "lbs"), (90, 8, None, "lbs")]),
        ("squat", "kb_goblet_squat", [(53, 10, 7.0, "lbs")]),
    ])
    seed_planned_day(bench_dates[2], [
        # 40.8 kg ≈ 90 lbs — the stray kg set; also a bodyweight (non-qualifying) set
        ("bench", "bench_press", [(95, 6, 8.0, "lbs"), (40.8, 6, 8.0, "kg"),
                                  (None, 12, None, "lbs")]),
    ])
    day_log_id = seed_planned_day(bench_dates[3], [
        ("bench", "bench_press", [(100, 5, 8.5, "lbs"), (100, 5, 9.5, "lbs")]),
    ])

    # Orphan on the most recent planned day: exercise_id NULL, ordinary key,
    # slug kept (a removed planned exercise). NOT off-plan.
    cur.execute(
        "INSERT INTO exercise_logs (session_log_id, exercise_id, exercise_key, "
        "canonical_slug, last_modified) VALUES (?, NULL, 'removed_bench', 'bench_press', ?)",
        (day_log_id, NOW),
    )
    cur.execute(
        "INSERT INTO set_logs (exercise_log_id, set_num, weight, reps, rpe, unit) "
        "VALUES (?, 1, 80, 10, 6.0, 'lbs')", (cur.lastrowid,),
    )

    # Plan-less day: one slugged off-plan strength row + one slug-less row.
    offplan_date = today - timedelta(days=5)
    cur.execute(
        "INSERT INTO workout_session_logs (session_id, date, last_modified, modified_by) "
        "VALUES (NULL, ?, ?, 'test')", (_iso(offplan_date), NOW),
    )
    offplan_log_id = cur.lastrowid
    cur.execute(
        "INSERT INTO exercise_logs (session_log_id, exercise_id, exercise_key, "
        "canonical_slug, last_modified) VALUES (?, NULL, 'extra_bench', 'bench_press', ?)",
        (offplan_log_id, NOW),
    )
    cur.execute(
        "INSERT INTO set_logs (exercise_log_id, set_num, weight, reps, rpe, unit) "
        "VALUES (?, 1, 70, 12, 6.0, 'lbs')", (cur.lastrowid,),
    )
    cur.execute(
        "INSERT INTO exercise_logs (session_log_id, exercise_id, exercise_key, "
        "canonical_slug, last_modified) VALUES (?, NULL, 'adhoc_press', NULL, ?)",
        (offplan_log_id, NOW),
    )
    cur.execute(
        "INSERT INTO set_logs (exercise_log_id, set_num, weight, reps, rpe, unit) "
        "VALUES (?, 1, 30, 10, NULL, 'lbs')", (cur.lastrowid,),
    )

    conn.commit()
    conn.close()
    return {
        "today": today,
        "bench_dates": [_iso(d) for d in bench_dates],
        "offplan_date": _iso(offplan_date),
    }


@pytest.fixture
def cardio_history(client, tmp_coach_db):
    """Cardio spreads covering every attribution rule:

    - planned steady (type 'duration') sessions with duration+HR, incl. one
      SHORT (<20 min) and one HR-less (both excluded from the aerobic proxy)
    - a planned interval (type 'interval') session with content
    - a rest-day extra (extra_zone2, plan-less day) with duration+HR
    - a RELINKED extra: plan exists for the date and the day row is
      session-linked, but the entry keeps the ad-hoc key → still extra
    - an orphan cardio row (planned day, ordinary key, exercise_id NULL) —
      excluded from every cardio stat (unknown provenance)
    """
    today = date.today()
    conn = sqlite3.connect(tmp_coach_db)
    cur = conn.cursor()

    def plan_day(d, ptype, key, name):
        cur.execute(
            "INSERT INTO workout_sessions (date, day_name, last_modified, modified_by) "
            "VALUES (?, 'Cardio', ?, 'test')", (_iso(d), NOW),
        )
        session_id = cur.lastrowid
        cur.execute(
            "INSERT INTO session_blocks (session_id, position, block_type, title) "
            "VALUES (?, 0, 'cardio', 'Conditioning')", (session_id,),
        )
        block_id = cur.lastrowid
        cur.execute(
            "INSERT INTO planned_exercises (session_id, block_id, exercise_key, "
            "position, name, exercise_type, target_duration_min) "
            "VALUES (?, ?, ?, 0, ?, ?, 30)",
            (session_id, block_id, key, name, ptype),
        )
        pe_id = cur.lastrowid
        cur.execute(
            "INSERT INTO workout_session_logs (session_id, date, last_modified, modified_by) "
            "VALUES (?, ?, ?, 'test')", (session_id, _iso(d), NOW),
        )
        return cur.lastrowid, pe_id, session_id

    def log_entry(log_id, pe_id, key, duration, hr):
        cur.execute(
            "INSERT INTO exercise_logs (session_log_id, exercise_id, exercise_key, "
            "duration_min, avg_hr, last_modified) VALUES (?, ?, ?, ?, ?, ?)",
            (log_id, pe_id, key, duration, hr, NOW),
        )
        return cur.lastrowid

    # Offsets chosen to avoid strength_history's dates ({37,23,9,2} sessions,
    # {5} plan-less log) — the two fixtures compose in the overview tests and
    # workout_sessions.date / workout_session_logs.date are UNIQUE.
    steady_dates = [today - timedelta(days=n) for n in (16, 17, 3)]
    log_id, pe_id, _ = plan_day(steady_dates[0], "duration", "z2", "Zone 2 Bike")
    log_entry(log_id, pe_id, "z2", 45.0, 142)
    log_id, pe_id, _ = plan_day(steady_dates[1], "duration", "z2", "Zone 2 Bike")
    log_entry(log_id, pe_id, "z2", 15.0, 138)      # short: counted, no proxy
    log_id, pe_id, _ = plan_day(steady_dates[2], "duration", "z2", "Zone 2 Bike")
    log_entry(log_id, pe_id, "z2", 40.0, None)     # HR-less: counted, no proxy

    interval_date = today - timedelta(days=8)
    log_id, pe_id, _ = plan_day(interval_date, "interval", "vo2", "Bike Intervals")
    log_entry(log_id, pe_id, "vo2", 24.0, 165)

    # Rest-day extra.
    extra_date = today - timedelta(days=6)
    cur.execute(
        "INSERT INTO workout_session_logs (session_id, date, last_modified, modified_by) "
        "VALUES (NULL, ?, ?, 'test')", (_iso(extra_date), NOW),
    )
    log_entry(cur.lastrowid, None, "extra_zone2", 30.0, 128)

    # Relinked extra + orphan share one planned day.
    relink_date = today - timedelta(days=1)
    log_id, pe_id, _ = plan_day(relink_date, "duration", "z2", "Zone 2 Bike")
    log_entry(log_id, pe_id, "z2", 35.0, 140)          # the planned work
    log_entry(log_id, None, "extra_zone2", 25.0, 126)  # relinked extra
    log_entry(log_id, None, "removed_cardio", 30.0, 150)  # orphan: excluded

    conn.commit()
    conn.close()
    return {
        "today": today,
        "steady_dates": [_iso(d) for d in steady_dates],
        "interval_date": _iso(interval_date),
        "extra_date": _iso(extra_date),
        "relink_date": _iso(relink_date),
    }


@pytest.fixture
def journal_history(client, tmp_journal_db):
    """Journal spreads for the trends endpoints:

    - 'Protein' (quantifiable, positive, targeted min=150 effective 10 days
      ago — a genesis untargeted era before that) with values across 3 weeks
    - 'Alcohol' (simple, negative) with sparse entries (avoidance semantics)
    - 'Mood' (quantifiable, NEUTRAL, untargeted) — in the picker via type,
      not actionable
    - 'Stretch' (simple, positive) PAUSED 7 days ago (empty-days segment)
    - 'Old Habit' deleted=1 and 'Never Logged' without entries — both excluded
    """
    today = date.today()
    conn = sqlite3.connect(tmp_journal_db)
    cur = conn.cursor()

    def tracker(tid, name, ttype, polarity=None, meta=None, schedule=None, target=None):
        import json as _json
        cur.execute(
            "INSERT INTO trackers (id, name, category, type, meta_json, "
            "schedule_json, polarity, target_json, last_modified_at, deleted) "
            "VALUES (?, ?, 'health', ?, ?, ?, ?, ?, ?, 0)",
            (tid, name, ttype,
             _json.dumps(meta) if meta else "{}",
             _json.dumps(schedule) if schedule else None,
             polarity,
             _json.dumps(target) if target else None,
             NOW),
        )

    def entry(tid, d, value=None, completed=1):
        cur.execute(
            "INSERT INTO entries (date, tracker_id, value, completed, last_modified_at) "
            "VALUES (?, ?, ?, ?, ?)", (_iso(d), tid, value, completed, NOW),
        )

    target_from = _iso(today - timedelta(days=10))
    tracker("t-protein", "Protein", "quantifiable", polarity="positive",
            meta={"unit": "g"},
            target=[{"effectiveFrom": "0000-01-01", "target": None},
                    {"effectiveFrom": target_from, "target": {"min": 150}}])
    for n in range(20, -1, -1):
        d = today - timedelta(days=n)
        # Values ramp 120→170; every 5th day unlogged. completed=1 mirrors
        # prod (the accumulator UI checks the box) and makes the pre-target
        # era's checkbox-met days observable in weekly buckets.
        if n % 5 == 0 and n != 0:
            continue
        entry("t-protein", d, value=120 + (20 - n) * 2.5, completed=1)

    tracker("t-alcohol", "Alcohol", "simple", polarity="negative")
    entry("t-alcohol", today - timedelta(days=4))
    entry("t-alcohol", today - timedelta(days=12))

    tracker("t-mood", "Mood", "quantifiable", polarity="neutral",
            meta={"unit": "1-10"})
    entry("t-mood", today - timedelta(days=1), value=7)

    pause_from = _iso(today - timedelta(days=7))
    tracker("t-stretch", "Stretch", "simple", polarity="positive",
            schedule=[{"effectiveFrom": "0000-01-01", "days": [0, 1, 2, 3, 4, 5, 6]},
                      {"effectiveFrom": pause_from, "days": []}])
    for n in (13, 12, 11, 10, 9, 8):
        entry("t-stretch", today - timedelta(days=n))

    tracker("t-old", "Old Habit", "simple", polarity="positive")
    cur.execute("UPDATE trackers SET deleted = 1 WHERE id = 't-old'")
    entry("t-old", today - timedelta(days=3))
    tracker("t-never", "Never Logged", "quantifiable", polarity="positive")

    conn.commit()
    conn.close()
    return {
        "today": today,
        "target_from": target_from,
        "pause_from": pause_from,
    }


@pytest.fixture
def assisted_history(client, tmp_coach_db, tmp_path):
    """An ASSISTED exercise (registry equipment='assisted') plus a plain bench
    row, and a Garmin DB whose body weight steps 90.7 → 88.4 kg between the
    two assisted sessions. The weight logged on assisted sets is machine
    ASSISTANCE — aggregates must score effective load = bw − assistance.

    Returns dates + the Garmin path; tests that want the effective-load math
    must re-point GARMIN_DB_PATH at `garmin_path` and build a fresh app (the
    default conftest pins it to a nonexistent file — that default state IS the
    degradation case: assisted sets drop out of every aggregate).
    """
    today = date.today()
    conn = sqlite3.connect(tmp_coach_db)
    cur = conn.cursor()

    cur.execute(
        "INSERT OR IGNORE INTO exercises (slug, name, equipment, category, created_at, source) "
        "VALUES ('assisted_pull_up', 'Assisted Pull-Up', 'assisted', 'strength', ?, 'test')",
        (NOW,),
    )
    cur.execute(
        "INSERT OR IGNORE INTO exercises (slug, name, category, created_at, source) "
        "VALUES ('bench_press', 'Bench Press', 'strength', ?, 'test')", (NOW,),
    )

    def seed_planned_day(d, entries):
        cur.execute(
            "INSERT INTO workout_sessions (date, day_name, last_modified, modified_by) "
            "VALUES (?, 'Strength', ?, 'test')", (_iso(d), NOW),
        )
        session_id = cur.lastrowid
        cur.execute(
            "INSERT INTO session_blocks (session_id, position, block_type, title) "
            "VALUES (?, 0, 'strength', 'Main')", (session_id,),
        )
        block_id = cur.lastrowid
        cur.execute(
            "INSERT INTO workout_session_logs (session_id, date, last_modified, modified_by) "
            "VALUES (?, ?, ?, 'test')", (session_id, _iso(d), NOW),
        )
        log_id = cur.lastrowid
        for pos, (key, slug, sets) in enumerate(entries):
            cur.execute(
                "INSERT INTO planned_exercises (session_id, block_id, exercise_key, "
                "position, name, exercise_type, target_sets, canonical_slug) "
                "VALUES (?, ?, ?, ?, ?, 'strength', 3, ?)",
                (session_id, block_id, key, pos, key, slug),
            )
            pe_id = cur.lastrowid
            cur.execute(
                "INSERT INTO exercise_logs (session_log_id, exercise_id, exercise_key, "
                "canonical_slug, last_modified) VALUES (?, ?, ?, ?, ?)",
                (log_id, pe_id, key, slug, NOW),
            )
            el_id = cur.lastrowid
            for set_num, (w, reps, rpe, unit) in enumerate(sets, 1):
                cur.execute(
                    "INSERT INTO set_logs (exercise_log_id, set_num, weight, reps, rpe, unit) "
                    "VALUES (?, ?, ?, ?, ?, ?)", (el_id, set_num, w, reps, rpe, unit),
                )

    d1, d2 = today - timedelta(days=21), today - timedelta(days=7)
    seed_planned_day(d1, [
        # Two assisted sets: the 50-assist set has MORE reps, so it wins the
        # top-set contest on effective e1RM despite more assistance.
        ("apu", "assisted_pull_up", [(45, 6, 7.0, "lbs"), (50, 8, 7.5, "lbs")]),
        ("bench", "bench_press", [(100, 5, 8.0, "lbs")]),
    ])
    seed_planned_day(d2, [
        ("apu", "assisted_pull_up", [(30, 6, 8.0, "lbs")]),
    ])
    conn.commit()
    conn.close()

    garmin_path = tmp_path / "garmin_assisted.db"
    g = sqlite3.connect(garmin_path)
    g.execute(
        "CREATE TABLE body_composition (sample_pk TEXT PRIMARY KEY, "
        "measurement_date DATE, timestamp_gmt DATETIME, weight_grams FLOAT)"
    )
    g.executemany(
        "INSERT INTO body_composition VALUES (?, ?, ?, ?)",
        [
            ("s1", _iso(today - timedelta(days=30)), "2026-06-07 07:00:00", 90700.0),
            ("s2", _iso(today - timedelta(days=10)), "2026-06-27 07:00:00", 88400.0),
        ],
    )
    g.commit()
    g.close()

    return {"today": today, "d1": d1, "d2": d2, "garmin_path": garmin_path}
