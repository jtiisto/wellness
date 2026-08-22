"""CLI: python -m hr_analysis [--latest | --session ID | --list] [--hrmax N]

Run it with `src/` importable, e.g. from the repo root:

    PYTHONPATH=src python3 -m hr_analysis --list
    PYTHONPATH=src python3 -m hr_analysis --latest --hrmax 185 --out /tmp

The database is the `hr` module's (HR_DB_PATH > data/hr.db), opened read-only.
Sessions are the only thing this analyses, and a session id is the only way to
name one: there is no `--from/--to`, by the ruling `db.py` records.

A session the cardio guide recorded a timeline for is analysed against that
timeline — the window is the ride, and the summary reports per-segment time in
band. `--exercise` picks between two rides in one capture; `--whole-capture`
opts out of the narrowing altogether.

The pulse-bridge original also took `--environment production|test` to pick
between two databases; that flag is gone — there is one HR database here and
HR_DB_PATH already repoints it.
"""

import argparse
import sys
from datetime import datetime, timezone

from . import guided, report
from .db import (
    HrDataUnavailable,
    guide_events_by_session,
    latest_session,
    list_sessions,
    load_beats,
    load_guide_events,
)
from .pipeline import analyze


def _print_sessions(sessions, rides_by_session):
    print(f"{'session_id':38s} {'start (UTC)':20s} {'beats':>7s}  {'workout':12s} guided")
    for sid, _dev, start_ms, _end_ms, beats, workout_date in sessions:
        start = datetime.fromtimestamp(start_ms / 1000, tz=timezone.utc)
        rides = rides_by_session.get(sid, [])
        guided_col = ", ".join(ride.exercise_key for ride in rides) or "-"
        print(f"{sid:38s} {start.strftime('%Y-%m-%d %H:%M:%S'):20s} "
              f"{beats:7d}  {(workout_date or '-'):12s} {guided_col}")


def main(argv=None):
    p = argparse.ArgumentParser(prog="hr_analysis", description="Workout RR/HR analysis")
    p.add_argument("--session", help="session_id to analyze")
    p.add_argument("--latest", action="store_true", help="analyze the most recent session")
    p.add_argument("--list", action="store_true", help="list recent sessions and exit")
    p.add_argument("--exercise", default=None,
                   help="which guided exercise, when the capture spans several")
    p.add_argument("--whole-capture", action="store_true",
                   help="analyze every beat, not just the guided ride's window")
    p.add_argument("--hrmax", type=int, default=None, help="max HR for zone breakdown")
    p.add_argument("--out", default=".", help="output directory for report files")
    p.add_argument("--no-files", action="store_true", help="terminal summary only")
    args = p.parse_args(argv)

    # Every DB read can fail the same way (no database yet, or one without the
    # HR schema) and that is an ordinary state on a fresh install, not a bug —
    # so it prints a sentence and exits, never a traceback.
    try:
        if args.list:
            sessions = list_sessions()
            if not sessions:
                print("No sessions found.")
                return 0
            events = guide_events_by_session([row[0] for row in sessions])
            _print_sessions(sessions, {
                sid: guided.guided_rides(rows) for sid, rows in events.items()
            })
            return 0

        session_id = args.session
        if args.latest:
            session_id = latest_session()
            if not session_id:
                print("No sessions found.")
                return 0
        if not session_id:
            p.error("provide --session ID, --latest, or --list")

        beats = load_beats(session_id)
        rides = guided.guided_rides(load_guide_events(session_id))
    except HrDataUnavailable as exc:
        print(exc, file=sys.stderr)
        return 1

    if not beats:
        print(f"No data for session {session_id}", file=sys.stderr)
        return 1

    try:
        ride = guided.select_ride(rides, args.exercise)
    except guided.GuidedExerciseRequired as exc:
        print(exc, file=sys.stderr)
        for candidate in exc.rides:
            print(f"  --exercise {candidate.exercise_key}"
                  f"   anchored {candidate.anchor_ms}"
                  f"   {candidate.total_sec or '?'}s", file=sys.stderr)
        return 1
    except ValueError as exc:
        print(exc, file=sys.stderr)
        return 1

    window = beats
    if ride is not None and not args.whole_capture:
        window = guided.ride_beats(beats, ride)
        if not window:
            print(f"No beats inside the guided window for {ride.exercise_key} "
                  f"in session {session_id}", file=sys.stderr)
            return 1

    result = analyze(window, hrmax=args.hrmax)
    result["structure"] = (
        guided.unguided_structure() if ride is None
        else guided.guided_structure(beats, ride)
    )
    print(report.terminal_summary(session_id, result))

    if not args.no_files:
        json_path = report.write_json(session_id, result, args.out)
        png_path = report.write_png(session_id, result, args.out, beats=window)
        print(f"\n  wrote {json_path}")
        print(f"  wrote {png_path}" if png_path else "  (PNG skipped — matplotlib not installed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
