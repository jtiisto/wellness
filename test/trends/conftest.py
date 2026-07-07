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
