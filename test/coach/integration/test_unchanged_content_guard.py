"""Integration tests for the unchanged-content guard in `_store_log`.

A record's `last_modified` is the optimistic-concurrency token every OTHER
device echoes back as its base. The clients upload the WHOLE day, so re-stamping
records the upload merely carried — rather than changed — turned one set tick
into a fresh stamp on every record of the day: every other device's base tokens
went stale at once and their next upload was rejected for records nobody had
edited. `_store_log` therefore skips the write entirely (no UPDATE, no stamp, no
set/checklist delete+reinsert) when the incoming client-owned content already
equals what is stored.

The guard's safety asymmetry drives the coverage below: a false CHANGED verdict
only costs a needless re-stamp (the old behavior), while a false UNCHANGED
verdict would silently drop a real edit — so the "must be changed" cases are
enumerated far more aggressively than the "must be unchanged" ones.
"""

import sqlite3
import time
from copy import deepcopy
from datetime import datetime

import pytest


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _today():
    # Local time by convention: date keys are the browser's local calendar day.
    return datetime.now().strftime("%Y-%m-%d")


def _upload(client, client_id, date, day):
    resp = client.post(
        "/api/coach/sync", json={"clientId": client_id, "logs": {date: day}}
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["success"] is True
    return data["results"][date]


def _server_day(client, client_id, date):
    resp = client.get(f"/api/coach/sync?client_id={client_id}")
    assert resp.status_code == 200
    return resp.json()["logs"].get(date, {})


def _with_tokens(server_day, day):
    """`day` with the server's current day + per-exercise stamps attached as base
    tokens — i.e. what an up-to-date client uploads. Returns a copy; the caller's
    dict is never mutated."""
    out = deepcopy(day)
    if server_day.get("_lastModified"):
        out["_baseLastModifiedAt"] = server_day["_lastModified"]
    for key, val in out.items():
        srv = server_day.get(key)
        if isinstance(val, dict) and isinstance(srv, dict) and srv.get("_lastModified"):
            val["_baseLastModifiedAt"] = srv["_lastModified"]
    return out


def _reupload(client, client_id, date, day):
    """Upload `day` echoing the server's current tokens (an up-to-date client)."""
    return _upload(client, client_id, date, _with_tokens(
        _server_day(client, client_id, date), day))


def _stamps(day_row):
    """{record key → stamp} for a merged server day: every exercise plus the
    day-level (feedback) token under the empty key."""
    out = {"": day_row.get("_lastModified")}
    for key, val in day_row.items():
        if isinstance(val, dict) and val.get("_lastModified"):
            out[key] = val["_lastModified"]
    return out


def _tick():
    """Guarantee the next server stamp is lexically newer than the last."""
    time.sleep(0.005)


def _db(tmp_coach_db):
    conn = sqlite3.connect(tmp_coach_db)
    conn.row_factory = sqlite3.Row
    return conn


def _child_rowids(conn, date, key):
    """(set rowids, checklist rowids) for one exercise — row IDENTITY, which the
    delete+reinsert destroys even when the values come back identical."""
    ex = conn.execute(
        """SELECT el.id FROM exercise_logs el
           JOIN workout_session_logs sl ON sl.id = el.session_log_id
           WHERE sl.date = ? AND el.exercise_key = ?""",
        (date, key),
    ).fetchone()
    assert ex is not None, f"no exercise_logs row for {key}"
    sets = [r["id"] for r in conn.execute(
        "SELECT id FROM set_logs WHERE exercise_log_id = ? ORDER BY id", (ex["id"],))]
    items = [r["id"] for r in conn.execute(
        "SELECT id FROM checklist_log_items WHERE exercise_log_id = ? ORDER BY id",
        (ex["id"],))]
    return sets, items


def _full_day():
    """A day exercising every stored shape: feedback, sets, a duration/HR entry
    and a checklist."""
    return {
        "session_feedback": {"pain_discomfort": "None", "general_notes": "Solid"},
        "warmup_0": {"completed_items": ["Cat-Cow x10", "Bird-Dog x5/side"]},
        "ex_1": {
            "user_note": "Felt strong",
            "sets": [
                {"set_num": 1, "weight": 100, "reps": 5, "rpe": 7, "completed": True},
                {"set_num": 2, "weight": 100, "reps": 5, "rpe": 8, "completed": True},
            ],
        },
        "ex_2": {"sets": [{"set_num": 1, "weight": 40, "reps": 12, "unit": "kg"}]},
        "cardio_1": {"duration_min": 22.5, "avg_hr": 131, "max_hr": 148},
    }


# ===========================================================================
# The guard itself: unchanged records keep their stamp and their rows
# ===========================================================================

@pytest.mark.integration
class TestUnchangedRecordsAreNotRewritten:
    def test_identical_reupload_moves_no_stamp(self, client, coach_registered_client):
        """The whole-day re-upload every debounced sync sends: nothing changed,
        so no record — day token included — may be re-stamped."""
        date = _today()
        day = _full_day()
        before = _stamps(_upload(client, coach_registered_client, date, day))
        assert set(before) == {"", "warmup_0", "ex_1", "ex_2", "cardio_1"}

        _tick()
        after = _stamps(_reupload(client, coach_registered_client, date, day))
        assert after == before

        # ...and the response agreed with what is actually stored.
        assert _stamps(_server_day(client, coach_registered_client, date)) == before

    def test_identical_reupload_does_not_churn_child_rows(
            self, client, coach_registered_client, tmp_coach_db):
        """No delete+reinsert either: the set and checklist rows keep their
        identity, which value-equality assertions alone would not catch."""
        date = _today()
        day = _full_day()
        _upload(client, coach_registered_client, date, day)

        conn = _db(tmp_coach_db)
        sets_before, _ = _child_rowids(conn, date, "ex_1")
        _, items_before = _child_rowids(conn, date, "warmup_0")
        conn.close()
        assert sets_before and items_before

        _tick()
        _reupload(client, coach_registered_client, date, day)

        conn = _db(tmp_coach_db)
        sets_after, _ = _child_rowids(conn, date, "ex_1")
        _, items_after = _child_rowids(conn, date, "warmup_0")
        conn.close()
        assert sets_after == sets_before
        assert items_after == items_before

    def test_mixed_day_restamps_only_the_changed_record(self, client, coach_registered_client):
        """One edited exercise in a four-record day: exactly one stamp moves."""
        date = _today()
        day = _full_day()
        before = _stamps(_upload(client, coach_registered_client, date, day))

        edited = deepcopy(day)
        edited["ex_1"]["sets"][1]["reps"] = 6
        _tick()
        after = _stamps(_reupload(client, coach_registered_client, date, edited))

        assert after["ex_1"] > before["ex_1"]
        for key in ("", "warmup_0", "ex_2", "cardio_1"):
            assert after[key] == before[key], f"{key!r} was re-stamped"

    def test_response_carries_old_stamp_for_unchanged_new_for_changed(
            self, client, coach_registered_client):
        """The upload response is stored reality, not a uniform 'now': in ONE
        day it hands back the old stamp for the untouched record and a fresh one
        for the edited record. Both clients adopt returned rows verbatim, so
        this shape is what keeps their bases valid."""
        date = _today()
        day = _full_day()
        before = _stamps(_upload(client, coach_registered_client, date, day))

        edited = deepcopy(day)
        edited["ex_2"]["sets"][0]["reps"] = 10
        _tick()
        result = _reupload(client, coach_registered_client, date, edited)

        assert result["ex_1"]["_lastModified"] == before["ex_1"]   # old stamp
        assert result["ex_2"]["_lastModified"] > before["ex_2"]    # fresh stamp
        # The response and a subsequent pull tell the same story.
        assert _stamps(_server_day(client, coach_registered_client, date)) == _stamps(result)

    def test_reupload_preserves_content(self, client, coach_registered_client):
        """The skipped write must not be a skipped *read*: everything still
        round-trips."""
        date = _today()
        day = _full_day()
        _upload(client, coach_registered_client, date, day)
        _reupload(client, coach_registered_client, date, day)

        stored = _server_day(client, coach_registered_client, date)
        assert stored["session_feedback"]["general_notes"] == "Solid"
        assert stored["ex_1"]["user_note"] == "Felt strong"
        assert [s["reps"] for s in stored["ex_1"]["sets"]] == [5, 5]
        assert stored["ex_2"]["sets"][0]["unit"] == "kg"
        assert stored["cardio_1"]["duration_min"] == 22.5
        assert stored["warmup_0"]["completed_items"] == ["Cat-Cow x10", "Bird-Dog x5/side"]


# ===========================================================================
# Session feedback
# ===========================================================================

@pytest.mark.integration
class TestFeedbackGuard:
    def test_unchanged_feedback_keeps_the_day_token(self, client, coach_registered_client):
        date = _today()
        day = {"session_feedback": {"pain_discomfort": "None", "general_notes": "Solid"}}
        before = _upload(client, coach_registered_client, date, day)["_lastModified"]

        _tick()
        after = _reupload(client, coach_registered_client, date, day)["_lastModified"]
        assert after == before

    @pytest.mark.parametrize("mutation", [
        {"pain_discomfort": "None", "general_notes": "Solid, second half easier"},
        {"pain_discomfort": "Left knee", "general_notes": "Solid"},
        {"general_notes": "Solid"},                       # pain cleared → NULL
        {"pain_discomfort": "None"},                      # notes cleared → NULL
        {"pain_discomfort": "None", "general_notes": ""},  # "" is not None
        {},                                               # both cleared
    ], ids=["notes", "pain", "pain-cleared", "notes-cleared", "notes-empty", "both-cleared"])
    def test_changed_feedback_advances_the_day_token(
            self, client, coach_registered_client, mutation):
        date = _today()
        day = {"session_feedback": {"pain_discomfort": "None", "general_notes": "Solid"}}
        before = _upload(client, coach_registered_client, date, day)["_lastModified"]

        _tick()
        after = _reupload(client, coach_registered_client, date,
                          {"session_feedback": mutation})["_lastModified"]
        assert after > before

    def test_cleared_feedback_converges_to_unchanged(self, client, coach_registered_client):
        """A clear is a change once, then quiet: the second identical upload of
        the cleared shape is a no-op."""
        date = _today()
        _upload(client, coach_registered_client, date,
                {"session_feedback": {"general_notes": "Solid"}})
        _tick()
        cleared = _reupload(client, coach_registered_client, date,
                            {"session_feedback": {}})["_lastModified"]
        _tick()
        again = _reupload(client, coach_registered_client, date,
                          {"session_feedback": {}})["_lastModified"]
        assert again == cleared


# ===========================================================================
# Type-identity traps — the comparison must never call two different values
# equal, and must not call two identically-STORED values different
# ===========================================================================

@pytest.mark.integration
class TestComparisonTypeIdentity:
    @pytest.mark.parametrize("entry", [
        {"sets": [{"set_num": 1, "weight": 22.5, "reps": 8, "rpe": 7.5}]},
        {"sets": [{"set_num": 1, "duration_sec": 90.5, "completed": True}]},
        {"sets": [{"set_num": 1, "weight": 100, "reps": 5}]},   # no rpe/unit keys
        {"sets": []},                                            # empty list
        {"duration_min": 45, "avg_hr": 128, "max_hr": 142},      # no sets key at all
        {"completed_items": ["a", "b", "b"]},                    # duplicates
        {"user_note": ""},                                       # empty string
    ], ids=["floats", "duration-sec", "sparse-set", "empty-sets", "no-sets-key",
            "duplicate-items", "empty-note"])
    def test_identical_shapes_compare_unchanged(self, client, coach_registered_client, entry):
        date = _today()
        day = {"session_feedback": {}, "ex_1": entry}
        before = _upload(client, coach_registered_client, date, day)["ex_1"]["_lastModified"]

        _tick()
        after = _reupload(client, coach_registered_client, date, day)["ex_1"]["_lastModified"]
        assert after == before

    def test_int_and_float_spelling_of_one_value_is_unchanged(
            self, client, coach_registered_client):
        """weight is a REAL column: 100 and 100.0 store the identical value, so
        re-spelling one as the other is not an edit."""
        date = _today()
        before = _upload(client, coach_registered_client, date, {
            "ex_1": {"sets": [{"set_num": 1, "weight": 100, "reps": 5}]},
        })["ex_1"]["_lastModified"]

        _tick()
        after = _reupload(client, coach_registered_client, date, {
            "ex_1": {"sets": [{"set_num": 1, "weight": 100.0, "reps": 5}]},
        })["ex_1"]["_lastModified"]
        assert after == before

    def test_explicit_null_equals_an_absent_optional(self, client, coach_registered_client):
        """JSON `null` and an absent key both store NULL — the same value."""
        date = _today()
        before = _upload(client, coach_registered_client, date, {
            "ex_1": {"sets": [{"set_num": 1, "weight": 100, "reps": 5}]},
        })["ex_1"]["_lastModified"]

        _tick()
        after = _reupload(client, coach_registered_client, date, {
            "ex_1": {"user_note": None, "duration_min": None,
                     "sets": [{"set_num": 1, "weight": 100, "reps": 5, "rpe": None}]},
        })["ex_1"]["_lastModified"]
        assert after == before

    @pytest.mark.parametrize("entry", [
        {"sets": [{"set_num": 1, "weight": 100.5, "reps": 5, "rpe": 7}]},   # weight
        {"sets": [{"set_num": 1, "weight": 100, "reps": 6, "rpe": 7}]},     # reps
        {"sets": [{"set_num": 1, "weight": 100, "reps": 5, "rpe": 7.5}]},   # rpe
        {"sets": [{"set_num": 1, "weight": 100, "rpe": 7}]},                # reps dropped
        {"sets": [{"set_num": 1, "weight": 100, "reps": 5, "rpe": 7, "unit": "kg"}]},
        {"sets": [{"set_num": 1, "weight": 100, "reps": 5, "rpe": 7, "completed": True}]},
        {"sets": [{"set_num": 2, "weight": 100, "reps": 5, "rpe": 7}]},     # set_num
        {"sets": [{"set_num": 1, "weight": 100, "reps": 5, "rpe": 7},
                  {"set_num": 2, "weight": 100, "reps": 5, "rpe": 7}]},     # set added
        {"sets": []},                                                       # sets emptied
        {},                                                                 # sets key gone
        {"sets": [{"set_num": 1, "weight": 100, "reps": 5, "rpe": 7}],
         "user_note": "added a note"},
        {"sets": [{"set_num": 1, "weight": 100, "reps": 5, "rpe": 7}],
         "completed_items": ["extra"]},
    ], ids=["weight", "reps", "rpe", "reps-dropped", "unit", "completed", "set-num",
            "set-added", "sets-emptied", "sets-key-gone", "note-added", "item-added"])
    def test_any_real_difference_is_a_change(self, client, coach_registered_client, entry):
        """Every one of these MUST re-stamp — a false 'unchanged' here would
        silently drop the edit."""
        date = _today()
        original = {"ex_1": {"sets": [{"set_num": 1, "weight": 100, "reps": 5, "rpe": 7}]}}
        before = _upload(client, coach_registered_client, date, original)["ex_1"]["_lastModified"]

        _tick()
        result = _reupload(client, coach_registered_client, date, {"ex_1": entry})
        assert result["ex_1"]["_lastModified"] > before, "the edit was skipped as unchanged"
        # And the edit actually landed.
        assert result["ex_1"].get("sets", []) == \
            _server_day(client, coach_registered_client, date)["ex_1"].get("sets", [])

    @pytest.mark.parametrize("note", ["", "note", None], ids=["empty", "text", "null"])
    def test_user_note_none_and_empty_string_are_distinct(
            self, client, coach_registered_client, note):
        """None vs "" is ambiguous only in the read path (both render as absent);
        the guard treats them as different values and lets the write converge."""
        date = _today()
        before = _upload(client, coach_registered_client, date, {
            "ex_1": {"user_note": "original", "sets": []},
        })["ex_1"]["_lastModified"]

        _tick()
        after = _reupload(client, coach_registered_client, date, {
            "ex_1": {"user_note": note, "sets": []},
        })["ex_1"]["_lastModified"]
        assert after > before

    def test_reordered_sets_are_treated_as_a_change(self, client, coach_registered_client):
        """Storage order is what is compared, so a pure reorder reads as changed
        (the conservative direction) and settles after that one write."""
        date = _today()
        sets = [{"set_num": 1, "weight": 100, "reps": 5},
                {"set_num": 2, "weight": 90, "reps": 8}]
        before = _upload(client, coach_registered_client, date,
                         {"ex_1": {"sets": sets}})["ex_1"]["_lastModified"]

        _tick()
        after = _reupload(client, coach_registered_client, date,
                          {"ex_1": {"sets": list(reversed(sets))}})["ex_1"]["_lastModified"]
        assert after > before

        _tick()
        settled = _reupload(client, coach_registered_client, date,
                            {"ex_1": {"sets": list(reversed(sets))}})["ex_1"]["_lastModified"]
        assert settled == after

    def test_reordered_checklist_items_are_treated_as_a_change(
            self, client, coach_registered_client):
        date = _today()
        items = ["Cat-Cow x10", "Bird-Dog x5/side"]
        before = _upload(client, coach_registered_client, date,
                         {"warmup_0": {"completed_items": items}})["warmup_0"]["_lastModified"]

        _tick()
        after = _reupload(client, coach_registered_client, date,
                          {"warmup_0": {"completed_items": list(reversed(items))}}
                          )["warmup_0"]["_lastModified"]
        assert after > before


# ===========================================================================
# Server-derived metadata: refreshed, but never at the cost of a stamp
# ===========================================================================

@pytest.mark.integration
class TestServerDerivedMetadataRefresh:
    def test_plan_authored_later_relinks_without_restamping(
            self, client, coach_registered_client, tmp_coach_db):
        """An extra session logged on a plan-less day, with the plan authored
        afterwards: the next (content-identical) upload must relink session_id /
        exercise_id / canonical_slug — the analysis reads key on them — while
        leaving every concurrency token where it was."""
        date = _today()
        day = {"session_feedback": {"general_notes": "Solid"},
               "ex_1": {"sets": [{"set_num": 1, "weight": 100, "reps": 5}]}}
        before = _stamps(_upload(client, coach_registered_client, date, day))

        conn = _db(tmp_coach_db)
        assert conn.execute(
            "SELECT session_id FROM workout_session_logs WHERE date = ?", (date,)
        ).fetchone()["session_id"] is None

        # A plan for that date appears (authored via the MCP editor in real life).
        conn.execute(
            "INSERT OR IGNORE INTO exercises (slug, name, category, created_at, source) "
            "VALUES ('back_squat', 'Back Squat', 'strength', ?, 'manual')", (date,))
        conn.execute(
            "INSERT INTO workout_sessions (date, day_name, last_modified, modified_by) "
            "VALUES (?, 'Test Workout', ?, 'test')", (date, date))
        session_id = conn.execute(
            "SELECT id FROM workout_sessions WHERE date = ?", (date,)).fetchone()["id"]
        conn.execute(
            "INSERT INTO session_blocks (session_id, position, block_type, title) "
            "VALUES (?, 0, 'strength', 'Strength')", (session_id,))
        block_id = conn.execute(
            "SELECT id FROM session_blocks WHERE session_id = ?", (session_id,)).fetchone()["id"]
        conn.execute(
            "INSERT INTO planned_exercises (session_id, block_id, exercise_key, position, "
            "name, exercise_type, canonical_slug) "
            "VALUES (?, ?, 'ex_1', 0, 'Back Squat', 'strength', 'back_squat')",
            (session_id, block_id))
        conn.commit()
        conn.close()

        _tick()
        after = _stamps(_reupload(client, coach_registered_client, date, day))
        assert after == before, "a server-derived relink advanced a client's token"

        conn = _db(tmp_coach_db)
        log_row = conn.execute(
            "SELECT session_id FROM workout_session_logs WHERE date = ?", (date,)).fetchone()
        ex_row = conn.execute(
            """SELECT el.exercise_id, el.canonical_slug FROM exercise_logs el
               JOIN workout_session_logs sl ON sl.id = el.session_log_id
               WHERE sl.date = ? AND el.exercise_key = 'ex_1'""", (date,)).fetchone()
        conn.close()
        assert log_row["session_id"] == session_id
        assert ex_row["exercise_id"] is not None
        assert ex_row["canonical_slug"] == "back_squat"


# ===========================================================================
# The amplifier, end to end: one device's set tick used to stale every other
# device's base token for records it never touched
# ===========================================================================

@pytest.mark.integration
class TestCrossDeviceAmplification:
    def test_one_devices_set_tick_does_not_stale_the_others_bases(
            self, client, coach_registered_client):
        """A (PWA) and B (native) both hold the day. B ticks ONE set on ex_1 and
        uploads the WHOLE day. A then edits ex_2 with the base it has held since
        before B's upload: it must be ACCEPTED. Pre-fix B's upload re-stamped
        ex_2 (and the day) too, so A's base was stale and its edit was rejected —
        the rejection item 1's client bug then converted into data loss."""
        date = _today()
        day = _full_day()
        a_view = _upload(client, coach_registered_client, date, day)
        a_bases = _stamps(a_view)

        # B pulls the same day, ticks one set on ex_1, uploads everything.
        _tick()
        b_day = deepcopy(day)
        b_day["ex_1"]["sets"][0]["reps"] = 6
        _upload(client, "device-b", date, _with_tokens(
            _server_day(client, "device-b", date), b_day))

        # A, which never saw B's upload, edits ex_2 against its ORIGINAL bases.
        _tick()
        a_edit = {
            "session_feedback": day["session_feedback"],
            "_baseLastModifiedAt": a_bases[""],
            "ex_2": {"sets": [{"set_num": 1, "weight": 45, "reps": 12, "unit": "kg"}],
                     "_baseLastModifiedAt": a_bases["ex_2"]},
        }
        merged = _upload(client, coach_registered_client, date, a_edit)

        assert merged["ex_2"]["sets"][0]["weight"] == 45, "A's edit was rejected as stale"
        assert merged["ex_1"]["sets"][0]["reps"] == 6, "B's tick was lost"

    def test_stale_base_is_still_rejected(self, client, coach_registered_client):
        """Control: the guard must not weaken arbitration. When B genuinely
        CHANGES the record A is editing, A's now-stale base still loses."""
        date = _today()
        day = _full_day()
        a_bases = _stamps(_upload(client, coach_registered_client, date, day))

        _tick()
        b_day = deepcopy(day)
        b_day["ex_2"]["sets"][0]["reps"] = 15
        _upload(client, "device-b", date, _with_tokens(
            _server_day(client, "device-b", date), b_day))

        _tick()
        merged = _upload(client, coach_registered_client, date, {
            "ex_2": {"sets": [{"set_num": 1, "weight": 45, "reps": 12, "unit": "kg"}],
                     "_baseLastModifiedAt": a_bases["ex_2"]},
        })
        assert merged["ex_2"]["sets"][0]["reps"] == 15, "stale write clobbered newer data"
