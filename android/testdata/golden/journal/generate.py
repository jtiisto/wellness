#!/usr/bin/env python3
"""Generate journal golden fixtures against the DEV server (port 9001).

Only creates rows whose ids and names are prefixed `fixture-`. Never modifies
or deletes anything else. Committed fixtures are filtered so they contain only
fixture- content.
"""
import json
import sys
import urllib.request

BASE = "http://localhost:9001/wellness/api/journal/sync"
OUT = sys.argv[1]
CLIENT = "fixture-client-0001"


def get(path):
    with urllib.request.urlopen(BASE + path, timeout=15) as r:
        return json.loads(r.read().decode())


def post(path, payload_text):
    req = urllib.request.Request(
        BASE + path,
        data=payload_text.encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode())


def compact(obj):
    return json.dumps(obj, separators=(",", ":"), ensure_ascii=False)


def write(name, obj):
    with open(f"{OUT}/{name}", "w") as f:
        f.write(compact(obj) + "\n")
    print("wrote", name)


# --- 0. watermark before any fixture write -------------------------------
t0 = get(f"/delta?client_id={CLIENT}")["serverTime"]
print("T0 =", t0)

# --- 1. create the fixture trackers + entries (all brand new: no tokens) --
create = {
    "clientId": CLIENT,
    "config": [
        {"id": "fixture-simple", "name": "fixture-Simple", "category": "fixture-Habits",
         "type": "simple", "polarity": "positive"},
        {"id": "fixture-quant", "name": "fixture-Quantifiable", "category": "fixture-Habits",
         "type": "quantifiable",
         "targetHistory": [{"effectiveFrom": "2026-01-01", "target": {"min": 1000, "max": 2000}}],
         "unit": "ml", "defaultValue": 250, "accumulator": True},
        {"id": "fixture-eval", "name": "fixture-Evaluation", "category": "fixture-Observations",
         "type": "evaluation",
         "scheduleHistory": [{"effectiveFrom": "0000-01-01", "days": [1, 3, 5]}],
         "polarity": "negative"},
        {"id": "fixture-note", "name": "fixture-Note", "category": "fixture-Observations",
         "type": "note"},
        {"id": "fixture-legacy", "name": "fixture-Legacy", "category": "fixture-Habits",
         "type": "simple", "frequency": "weekly", "weeklyDay": 3},
    ],
    "days": {
        "2030-01-03": {
            "fixture-simple": {"value": None, "completed": True},
            "fixture-quant": {"value": 750, "completed": True},
        },
        "2030-01-04": {
            "fixture-note": {"value": "fixture-note-text", "completed": None},
            "fixture-eval": {"value": 2, "completed": False},
        },
    },
}
created = post("/update", compact(create))
print("created:", len(created["acceptedTrackers"]), "trackers",
      len(created["acceptedEntries"]), "entries",
      "rejected:", created["rejectedTrackers"], created["rejectedEntries"])

tok_t = {a["id"]: a["lastModifiedAt"] for a in created["acceptedTrackers"]}
tok_e = {(a["date"], a["trackerId"]): a["lastModifiedAt"] for a in created["acceptedEntries"]}

# --- 2. the canonical upload: same rows, now carrying base tokens --------
# Key order mirrors TrackerDtoSerializer.encode (id, name, category, type,
# deleted, scheduleHistory, polarity, targetHistory, then extras) with the
# protocol-reserved top-level lastModifiedAt removed and _baseLastModifiedAt
# appended -- exactly what computeUploadPayload produces.
update = {
    "clientId": CLIENT,
    "config": [
        {"id": "fixture-simple", "name": "fixture-Simple", "category": "fixture-Habits",
         "type": "simple", "polarity": "positive",
         "_baseLastModifiedAt": tok_t["fixture-simple"]},
        {"id": "fixture-quant", "name": "fixture-Quantifiable", "category": "fixture-Habits",
         "type": "quantifiable",
         "targetHistory": [{"effectiveFrom": "2026-01-01", "target": {"min": 1000, "max": 2000}}],
         "unit": "ml", "defaultValue": 250, "accumulator": True,
         "_baseLastModifiedAt": tok_t["fixture-quant"]},
        {"id": "fixture-eval", "name": "fixture-Evaluation", "category": "fixture-Observations",
         "type": "evaluation",
         "scheduleHistory": [{"effectiveFrom": "0000-01-01", "days": [1, 3, 5]}],
         "polarity": "negative",
         "_baseLastModifiedAt": tok_t["fixture-eval"]},
        {"id": "fixture-note", "name": "fixture-Note", "category": "fixture-Observations",
         "type": "note", "_baseLastModifiedAt": tok_t["fixture-note"]},
        {"id": "fixture-legacy", "name": "fixture-Legacy", "category": "fixture-Habits",
         "type": "simple", "frequency": "weekly", "weeklyDay": 3,
         "_baseLastModifiedAt": tok_t["fixture-legacy"]},
    ],
    "days": {
        "2030-01-03": {
            "fixture-simple": {"value": None, "completed": True,
                               "_baseLastModifiedAt": tok_e[("2030-01-03", "fixture-simple")]},
            "fixture-quant": {"value": 750, "completed": True,
                              "_baseLastModifiedAt": tok_e[("2030-01-03", "fixture-quant")]},
        },
        "2030-01-04": {
            "fixture-note": {"value": "fixture-note-text", "completed": None,
                             "_baseLastModifiedAt": tok_e[("2030-01-04", "fixture-note")]},
            "fixture-eval": {"value": 2, "completed": False,
                             "_baseLastModifiedAt": tok_e[("2030-01-04", "fixture-eval")]},
        },
    },
}
update_text = compact(update)
accepted = post("/update", update_text)
print("accepted:", len(accepted["acceptedTrackers"]), len(accepted["acceptedEntries"]),
      "rejected:", accepted["rejectedTrackers"], accepted["rejectedEntries"])

with open(f"{OUT}/update-request.json", "w") as f:
    f.write(update_text + "\n")
print("wrote update-request.json")
write("update-response-accepted.json", accepted)

# --- 3. replay the same payload: base tokens are now stale -> rejected ----
rejected = post("/update", update_text)
print("rejected:", len(rejected["rejectedTrackers"]), len(rejected["rejectedEntries"]),
      "accepted:", len(rejected["acceptedTrackers"]), len(rejected["acceptedEntries"]))
write("update-response-rejected.json", rejected)

# --- 3b. phantom upload: base tokens for rows the server never had --------
# A `_baseLastModifiedAt` claims "I have seen a server copy of this row"; on a
# row the server does not have, that is the out-of-band `missing` case
# (restored backup, re-created DB, wrong instance) and the rejection is a
# deletion instruction: errorKind "missing", deleted true, serverRow null.
# One tracker case (a never-created id) and one entry case (an existing
# tracker at a date nothing wrote) pin both shapes. Nothing is stored, so
# this cannot leak into the deltas below.
phantom = {
    "clientId": CLIENT,
    "config": [
        {"id": "fixture-phantom", "name": "fixture-Phantom",
         "category": "fixture-Habits", "type": "simple",
         "_baseLastModifiedAt": t0},
    ],
    "days": {
        "2030-01-05": {
            "fixture-simple": {"value": None, "completed": True,
                               "_baseLastModifiedAt": t0},
        },
    },
}
missing = post("/update", compact(phantom))
assert not missing["acceptedTrackers"] and not missing["acceptedEntries"], \
    "phantom upload must accept nothing"
assert [r["errorKind"] for r in missing["rejectedTrackers"]] == ["missing"], missing
assert [r["errorKind"] for r in missing["rejectedEntries"]] == ["missing"], missing
write("update-response-missing.json", missing)

# --- 4. deltas -----------------------------------------------------------
PREFIX = "fixture-"


def strip_non_fixture(delta):
    """Drop every non-`fixture-` id from ALL THREE sections and report what went.

    `config` is not the only way real content reaches a fixture: an incremental
    pull covers the 2s watermark overlap and anything else the server committed
    while this script ran, which lands in `days` and `deletedTrackers` just as
    easily. Filtering only what we happened to think about is how a leak gets
    committed.
    """
    removed = {}

    kept_config = [t for t in delta["config"] if t["id"].startswith(PREFIX)]
    if len(kept_config) != len(delta["config"]):
        removed["config"] = [t["id"] for t in delta["config"] if not t["id"].startswith(PREFIX)]
    delta["config"] = kept_config

    kept_deleted = [i for i in delta["deletedTrackers"] if i.startswith(PREFIX)]
    if len(kept_deleted) != len(delta["deletedTrackers"]):
        removed["deletedTrackers"] = [
            i for i in delta["deletedTrackers"] if not i.startswith(PREFIX)
        ]
    delta["deletedTrackers"] = kept_deleted

    dropped_entries = []
    for date in list(delta["days"]):
        day = delta["days"][date]
        kept = {k: v for k, v in day.items() if k.startswith(PREFIX)}
        dropped_entries += [f"{date}|{k}" for k in day if not k.startswith(PREFIX)]
        if kept:
            delta["days"][date] = kept
        else:
            del delta["days"][date]
    if dropped_entries:
        removed["days"] = dropped_entries

    return removed


def assert_fixture_only(name, delta):
    """Belt and braces: prove the committed object is clean after filtering."""
    assert all(t["id"].startswith(PREFIX) for t in delta["config"]), f"{name}: config"
    assert all(i.startswith(PREFIX) for i in delta["deletedTrackers"]), f"{name}: deletedTrackers"
    for date, day in delta["days"].items():
        assert all(k.startswith(PREFIX) for k in day), f"{name}: days[{date}]"


incremental = get(f"/delta?since={t0}&client_id={CLIENT}")
leaked = strip_non_fixture(incremental)
# The incremental window starts after every pre-existing row, so anything
# non-fixture in here means the server changed under us: worth shouting about,
# not silently filtering.
if leaked:
    print("WARNING: incremental delta contained non-fixture content, removed:", leaked)
assert_fixture_only("delta-incremental", incremental)
write("delta-incremental.json", incremental)

full = get(f"/delta?client_id={CLIENT}")
print("full config before filtering:", len(full["config"]))
print("full delta filtered out:", strip_non_fixture(full))
assert_fixture_only("delta-full", full)
write("delta-full.json", full)

legacy = next(t for t in incremental["config"] if t["id"] == "fixture-legacy")
write("tracker-legacy.json", legacy)
