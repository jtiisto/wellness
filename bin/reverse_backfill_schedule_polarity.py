#!/usr/bin/env python3
"""C3 rollback: re-embed the canonical schedule/polarity columns into meta_json.

The C3 single-source cleanup (journal migration 4) moved `scheduleHistory` /
`polarity` out of `trackers.meta_json` into dedicated `schedule_json` /
`polarity` columns and made those the only copy. To roll the *code* back to a
pre-C3 revision (which read the fields from `meta_json`), first run this script
to re-embed the column values into `meta_json`, so the old readers work again.

Idempotent and safe to run repeatedly. Live `trackers` only (archives are
historical). Does not touch the columns.

Usage:
    python bin/reverse_backfill_schedule_polarity.py [DB_PATH]

DB_PATH defaults to ../data/journal.db relative to this script.
"""
import json
import sqlite3
import sys
from pathlib import Path

_DEFAULT_DB = Path(__file__).resolve().parent.parent / "data" / "journal.db"


def reverse_backfill(db_path):
    conn = sqlite3.connect(db_path)
    try:
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute(
            "SELECT id, meta_json, schedule_json, polarity FROM trackers"
        )
        updated = 0
        for row in cursor.fetchall():
            meta = {}
            if row["meta_json"]:
                try:
                    parsed = json.loads(row["meta_json"])
                    if isinstance(parsed, dict):
                        meta = parsed
                except (ValueError, TypeError):
                    meta = {}
            changed = False
            if row["schedule_json"] is not None:
                schedule = json.loads(row["schedule_json"])
                if meta.get("scheduleHistory") != schedule:
                    meta["scheduleHistory"] = schedule
                    changed = True
            if row["polarity"] is not None and meta.get("polarity") != row["polarity"]:
                meta["polarity"] = row["polarity"]
                changed = True
            if changed:
                cursor.execute(
                    "UPDATE trackers SET meta_json = ? WHERE id = ?",
                    (json.dumps(meta), row["id"]),
                )
                updated += 1
        conn.commit()
        return updated
    finally:
        conn.close()


def main():
    db_path = sys.argv[1] if len(sys.argv) > 1 else str(_DEFAULT_DB)
    updated = reverse_backfill(db_path)
    print(f"Re-embedded schedule/polarity into meta_json for {updated} tracker(s) "
          f"in {db_path}.")


if __name__ == "__main__":
    main()
