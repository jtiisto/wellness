"""Roll-forward safety of the C3 rollback script (review F10).

bin/reverse_backfill_schedule_polarity.py re-embeds the canonical
schedule/polarity columns into meta_json for a code rollback. It must ALSO
hand authority back to meta_json (NULL columns + user_version → 2) so a later
roll-forward re-lifts edits made under the old code instead of silently
shadowing them with stale column values.
"""

import json
import sqlite3
import subprocess
import sys
from pathlib import Path

import pytest

from modules.db import DbAccessor

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
SCRIPT = PROJECT_ROOT / "bin" / "reverse_backfill_schedule_polarity.py"

SCHEDULE = [{"effectiveFrom": "0000-01-01", "days": [1, 2, 3]}]


def _make_db(tmp_path):
    import modules.journal as journal_mod
    db_path = tmp_path / "journal.db"
    journal_mod.init_database(DbAccessor(db_path))
    conn = sqlite3.connect(db_path)
    conn.execute(
        "INSERT INTO trackers (id, name, category, type, meta_json, schedule_json,"
        " polarity, last_modified_at, deleted)"
        " VALUES ('t1', 'Weekly Thing', 'health', 'simple', '{}', ?, 'positive',"
        " '2026-01-01T00:00:00Z', 0)",
        (json.dumps(SCHEDULE),),
    )
    conn.commit()
    conn.close()
    return db_path


@pytest.mark.unit
def test_reverse_backfill_reembeds_and_rewinds_migration_state(tmp_path):
    db_path = _make_db(tmp_path)

    result = subprocess.run(
        [sys.executable, str(SCRIPT), str(db_path)],
        capture_output=True, text=True,
    )
    assert result.returncode == 0, result.stderr

    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM trackers WHERE id = 't1'").fetchone()
    ver = conn.execute("PRAGMA user_version").fetchone()[0]
    conn.close()

    meta = json.loads(row["meta_json"])
    assert meta["scheduleHistory"] == SCHEDULE  # old readers work again
    assert meta["polarity"] == "positive"
    assert row["schedule_json"] is None         # authority handed to meta_json
    assert row["polarity"] is None
    assert ver == 2                             # pre-lift → 3-4 rerun on roll-forward


@pytest.mark.unit
def test_roll_forward_relifts_old_code_edits(tmp_path):
    """Reverse → edit under 'old code' (meta_json only) → re-init: the edit
    must land in the canonical columns, not be reverted."""
    import modules.journal as journal_mod

    db_path = _make_db(tmp_path)
    subprocess.run([sys.executable, str(SCRIPT), str(db_path)], check=True,
                   capture_output=True)

    # Old code changes the schedule (meta_json is its only store).
    edited = [{"effectiveFrom": "0000-01-01", "days": [5]}]
    conn = sqlite3.connect(db_path)
    conn.execute(
        "UPDATE trackers SET meta_json = ? WHERE id = 't1'",
        (json.dumps({"scheduleHistory": edited, "polarity": "negative"}),),
    )
    conn.commit()
    conn.close()

    # Roll forward: migrations 3 (lift) + 4 (strip) + 5 rerun.
    journal_mod.init_database(DbAccessor(db_path))

    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM trackers WHERE id = 't1'").fetchone()
    ver = conn.execute("PRAGMA user_version").fetchone()[0]
    conn.close()

    assert json.loads(row["schedule_json"]) == edited  # edit survived
    assert row["polarity"] == "negative"
    assert ver == len(journal_mod.MIGRATIONS)
    meta = json.loads(row["meta_json"])
    assert "scheduleHistory" not in meta  # migration 4 re-stripped the copies
