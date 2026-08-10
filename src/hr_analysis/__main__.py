"""CLI: python -m hr_analysis [--latest | --session ID | --list] [--hrmax N]

Run it with `src/` importable, e.g. from the repo root:

    PYTHONPATH=src python3 -m hr_analysis --list
    PYTHONPATH=src python3 -m hr_analysis --latest --hrmax 185 --out /tmp

The database is the `hr` module's (HR_DB_PATH > data/hr.db), opened read-only.
The pulse-bridge original also took `--environment production|test` to pick
between two databases; that flag is gone — there is one HR database here and
HR_DB_PATH already repoints it.
"""

import argparse
import sys
from datetime import datetime, timezone

from . import report
from .db import HrDataUnavailable, latest_session, list_sessions, load_beats
from .pipeline import analyze


def _print_sessions(sessions):
    print(f"{'session_id':38s} {'start (UTC)':20s} {'beats':>7s}  workout")
    for sid, _dev, start_ms, _end_ms, beats, workout_date in sessions:
        start = datetime.fromtimestamp(start_ms / 1000, tz=timezone.utc)
        print(f"{sid:38s} {start.strftime('%Y-%m-%d %H:%M:%S'):20s} "
              f"{beats:7d}  {workout_date or '-'}")


def main(argv=None):
    p = argparse.ArgumentParser(prog="hr_analysis", description="Workout RR/HR analysis")
    p.add_argument("--session", help="session_id to analyze")
    p.add_argument("--latest", action="store_true", help="analyze the most recent session")
    p.add_argument("--list", action="store_true", help="list recent sessions and exit")
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
            _print_sessions(sessions)
            return 0

        session_id = args.session
        if args.latest:
            session_id = latest_session()
            if not session_id:
                print("No sessions found.")
                return 0
        if not session_id:
            p.error("provide --session ID, --latest, or --list")

        beats = load_beats(session_id=session_id)
    except HrDataUnavailable as exc:
        print(exc, file=sys.stderr)
        return 1

    if not beats:
        print(f"No data for session {session_id}", file=sys.stderr)
        return 1

    result = analyze(beats, hrmax=args.hrmax)
    print(report.terminal_summary(session_id, result))

    if not args.no_files:
        json_path = report.write_json(session_id, result, args.out)
        png_path = report.write_png(session_id, result, args.out, beats=beats)
        print(f"\n  wrote {json_path}")
        print(f"  wrote {png_path}" if png_path else "  (PNG skipped — matplotlib not installed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
