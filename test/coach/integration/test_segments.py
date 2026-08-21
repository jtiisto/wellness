"""Integration tests for the optional `segments` target-HR timeline on
planned_exercises — the field the Android client's live cardio guidance draws
against (`android/specs/cardio-guidance.md`).

Covers the wire end of the contract: the timeline survives the sync GET intact
and in order, and a cardio exercise without one omits the key entirely (absent,
never null, never `[]`). The write-path rules are unit-tested in
`test/test_coach_domain.py`; the MCP editors in `test/test_coach_mcp.py`.
"""

import pytest
import sqlite3
from datetime import datetime

from seeds import seed_coach_plan


@pytest.fixture
def segments_seeded_db(client, coach_registered_client, tmp_coach_db):
    """The canonical seed with `segments=True`: 'Zone 2 Bike' carries a
    three-segment timeline and 'Easy Walk' beside it carries none."""
    today = datetime.now().strftime("%Y-%m-%d")

    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    seed_coach_plan(conn, today=today, segments=True)
    conn.commit()
    conn.close()
    return {"client_id": coach_registered_client, "date": today}


@pytest.mark.integration
class TestSegmentsAssembly:
    def _exercises(self, client, seeded):
        resp = client.get(f"/api/coach/sync?client_id={seeded['client_id']}")
        assert resp.status_code == 200
        plan = resp.json()["plans"][seeded["date"]]
        cardio = [b for b in plan["blocks"] if b["block_type"] == "cardio"][0]
        return {ex["id"]: ex for ex in cardio["exercises"]}

    def test_timeline_round_trips_in_order(self, client, segments_seeded_db):
        by_id = self._exercises(client, segments_seeded_db)

        assert by_id["cardio_1"]["segments"] == [
            {"duration_sec": 180, "hr_min": 112, "hr_max": 128, "label": "ease in"},
            {"duration_sec": 600, "hr_min": 134, "hr_max": 149, "label": "steady"},
            {"duration_sec": 120, "hr_max": 122, "label": "spin down"},
        ]

    def test_a_ceiling_only_segment_keeps_its_missing_bound_missing(
        self, client, segments_seeded_db
    ):
        """Which bounds a segment carries IS its meaning — a ceiling that came
        back as `hr_min: 0` would be a range starting at zero."""
        last = self._exercises(client, segments_seeded_db)["cardio_1"]["segments"][-1]

        assert "hr_min" not in last
        assert last["hr_max"] == 122

    def test_omitted_when_absent(self, client, segments_seeded_db):
        by_id = self._exercises(client, segments_seeded_db)

        assert "segments" not in by_id["cardio_plain"]

    def test_strength_exercises_are_untouched(self, client, segments_seeded_db):
        """The field costs a plan that has no timeline exactly nothing."""
        resp = client.get(f"/api/coach/sync?client_id={segments_seeded_db['client_id']}")
        plan = resp.json()["plans"][segments_seeded_db["date"]]
        strength = [b for b in plan["blocks"] if b["block_type"] == "strength"][0]

        assert all("segments" not in ex for ex in strength["exercises"])
