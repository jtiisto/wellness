#!/usr/bin/env python3
"""Generate coach golden fixtures against the DEV server (port 9001).

Writes only to two fixture dates inside the 60-day sync window, and only under
`extra_zone2` (the well-known ad-hoc key) plus `fixture-`-prefixed ad-hoc keys.
Nothing else on the dev server is read into a fixture, modified, or deleted.

Every committed payload is filtered to the fixture dates AND asserted clean
afterwards: filtering only what we happened to think about is how a leak gets
committed (see testdata/golden/journal/README.md).

`plan-day.json` is NOT generated here. Every `workout_sessions` row on the dev
server predates the 60-day window, so no pull returns one, and the spec's
fallback applies: a deterministic, hand-built, server-shaped synthetic plan
lives in the committed file. This script only verifies that assumption holds.
"""
import json
import sys
import urllib.request

BASE = "http://localhost:9001/wellness/api/coach"
OUT = sys.argv[1]
CLIENT = "fixture-client-coach-0001"

# Both inside the 60-day window (asserted below against the server's own
# earliestDate) and asserted to carry no pre-existing log before any write: a
# POST response returns the WHOLE merged day, so an occupied date would capture
# unrelated seeded entries.
D1 = "2030-01-03"
D2 = "2030-01-04"
FIXTURE_DATES = (D1, D2)

# Keys a fixture day is allowed to contain. `extra_zone2` is the server's
# well-known ad-hoc key (AD_HOC_LOG_SLUGS); everything else we author is
# `fixture-` prefixed; the rest are protocol meta.
META_KEYS = {
    "session_feedback", "_lastModified", "_lastModifiedAt", "_lastModifiedBy",
    "_baseLastModifiedAt",
}
ALLOWED_KEYS = META_KEYS | {"extra_zone2"}


def get(path):
    req = urllib.request.Request(BASE + path, headers={"Cache-Control": "no-store"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())


def post(path, payload_text):
    req = urllib.request.Request(
        BASE + path,
        data=payload_text.encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())


def compact(obj):
    return json.dumps(obj, separators=(",", ":"), ensure_ascii=False)


def write(name, obj):
    with open(f"{OUT}/{name}", "w") as f:
        f.write(compact(obj) + "\n")
    print("wrote", name)


def with_base_tokens(log):
    """The client's `withBaseTokens` — mirrored here so `sync-post-request.json`
    is byte-identical to what the Kotlin port emits (key order included)."""
    out = {}
    for key, val in log.items():
        if isinstance(val, dict) and val.get("_lastModified"):
            out[key] = {**val, "_baseLastModifiedAt": val["_lastModified"]}
        else:
            out[key] = val
    if log.get("_lastModified"):
        out["_baseLastModifiedAt"] = log["_lastModified"]
    return out


def assert_fixture_only(name, payload):
    """Prove a committed payload holds nothing but our own fixture content.

    Covers every date-keyed section any committed file can carry: `plans` and
    `deletedPlanDates` from a pull, `logs` from a pull or an upload request, and
    `results` from an upload response. A file holding a `{request, response}`
    pair is walked on both halves. Checking only the sections we happened to
    think about is how a leak gets committed.
    """
    for half in ("request", "response"):
        if isinstance(payload.get(half), dict):
            assert_fixture_only(f"{name}.{half}", payload[half])

    for date in payload.get("plans", {}):
        assert date in FIXTURE_DATES, f"{name}: plans[{date}] is not a fixture date"
    for date in payload.get("deletedPlanDates", []):
        assert date in FIXTURE_DATES, f"{name}: deletedPlanDates has {date}"

    for section in ("logs", "results"):
        for date, day in payload.get(section, {}).items():
            assert date in FIXTURE_DATES, f"{name}: {section}[{date}] is not a fixture date"
            for key in day:
                assert key in ALLOWED_KEYS or key.startswith("fixture-"), \
                    f"{name}: {section}[{date}] has unexpected key {key!r}"


def keep_fixture_dates(payload, label):
    """Drop every non-fixture date from plans / logs / deletedPlanDates."""
    removed = {}
    for section in ("plans", "logs"):
        original = payload.get(section, {})
        kept = {d: v for d, v in original.items() if d in FIXTURE_DATES}
        if len(kept) != len(original):
            removed[section] = sorted(set(original) - set(kept))
        payload[section] = kept
    deleted = payload.get("deletedPlanDates", [])
    kept_deleted = [d for d in deleted if d in FIXTURE_DATES]
    if len(kept_deleted) != len(deleted):
        removed["deletedPlanDates"] = sorted(set(deleted) - set(kept_deleted))
    payload["deletedPlanDates"] = kept_deleted
    if removed:
        print(f"  {label}: filtered out {removed}")
    return removed


def main():
    # --- 0. preconditions + the watermark taken before any fixture write ------
    t0_full = get(f"/sync?client_id={CLIENT}")
    T0 = t0_full["serverTime"]
    print("T0 =", T0, "earliestDate =", t0_full["earliestDate"])

    for date in FIXTURE_DATES:
        assert date >= t0_full["earliestDate"], \
            f"{date} is outside the 60-day window ({t0_full['earliestDate']}) — a full GET would not return it"
        assert date not in t0_full["logs"], f"{date} already carries a log; pick an empty date"
    assert not t0_full["plans"], (
        "the dev server now returns a plan inside the window: plan-day.json's synthetic "
        "fallback assumption no longer holds — capture a real plan only after re-checking "
        "its provenance"
    )

    # --- 1. create the fixture days (brand new: no base tokens anywhere) ------
    create = {
        "clientId": CLIENT,
        "logs": {
            D1: {
                "session_feedback": {"general_notes": "fixture-felt-good"},
                "extra_zone2": {"duration_min": 30, "avg_hr": 128},
                "fixture-adhoc-lift": {
                    "sets": [
                        {"set_num": 1, "weight": 100, "reps": 5, "completed": True},
                        {"set_num": 2, "weight": 100, "reps": 5},
                    ],
                },
                "_lastModifiedAt": "2030-01-03T12:00:00.000000Z",
                "_lastModifiedBy": CLIENT,
            },
            D2: {
                "session_feedback": {},
                "fixture-adhoc-checklist": {"completed_items": ["fixture-item-a", "fixture-item-b"]},
                "_lastModifiedAt": "2030-01-04T12:00:00.000000Z",
                "_lastModifiedBy": CLIENT,
            },
        },
    }
    created = post("/sync", compact(create))
    assert created["success"], created
    print("created days:", sorted(created["results"]))

    # --- 2. the canonical upload: the adopted server days, now token-bearing --
    # Exactly the client's own path: adopt `results[date]` wholesale, then upload it
    # again through withBaseTokens. Key order therefore matches the port's output.
    upload = {
        "clientId": CLIENT,
        "logs": {date: with_base_tokens(created["results"][date]) for date in FIXTURE_DATES},
    }
    upload_text = compact(upload)
    accepted = post("/sync", upload_text)
    assert accepted["success"], accepted
    assert_fixture_only("sync-post-request", upload)
    assert_fixture_only("sync-post-response", accepted)

    with open(f"{OUT}/sync-post-request.json", "w") as f:
        f.write(upload_text + "\n")
    print("wrote sync-post-request.json")
    write("sync-post-response.json", accepted)

    # --- 3. stale base: update a token-bearing row, re-send its OLD base ------
    # Step 2 advanced every stored token, so replaying step 2's bases is a genuine
    # `stored > base` rejection. The payload carries CHANGED content so the fixture
    # shows the server keeping its own version rather than a no-op.
    stale = json.loads(upload_text)
    stale["logs"][D1]["extra_zone2"]["duration_min"] = 999
    stale["logs"][D1]["session_feedback"]["general_notes"] = "fixture-stale-write"
    stale["logs"][D2]["fixture-adhoc-checklist"]["completed_items"] = ["fixture-stale-item"]
    rejected = post("/sync", compact(stale))
    assert rejected["success"], rejected
    assert rejected["results"][D1]["extra_zone2"]["duration_min"] == 30, \
        "the stale write was ACCEPTED — the fixture would pin the wrong rule"
    assert_fixture_only("sync-post-response-rejected", rejected)
    write("sync-post-response-rejected.json", rejected)

    # --- 4. the pull fixtures (captured before the tombstone) ----------------
    incremental = get(f"/sync?client_id={CLIENT}&last_sync_time={T0}")
    # The incremental window opens after every pre-existing row, so anything
    # non-fixture here means the server changed under us: worth shouting about.
    leaked = keep_fixture_dates(incremental, "sync-get-incremental")
    if leaked:
        print("WARNING: incremental pull contained non-fixture content:", leaked)
    assert_fixture_only("sync-get-incremental", incremental)
    assert set(incremental["logs"]) == set(FIXTURE_DATES), incremental["logs"].keys()
    write("sync-get-incremental.json", incremental)

    full = get(f"/sync?client_id={CLIENT}")
    keep_fixture_dates(full, "sync-get-full")
    assert_fixture_only("sync-get-full", full)
    assert set(full["logs"]) == set(FIXTURE_DATES), full["logs"].keys()
    write("sync-get-full.json", full)

    # --- 5. tombstone round trip: delete an entry, capture the verdict -------
    # One file holding both halves: the verdict is only legible against the request
    # that produced it (the server returns the merged day, and an ACCEPTED delete is
    # visible precisely as the key's ABSENCE).
    day = full["logs"][D1]
    tombstone_request = {
        "clientId": CLIENT,
        "logs": {
            D1: {
                "session_feedback": day["session_feedback"],
                "extra_zone2": {
                    "_deleted": True,
                    "_lastModified": day["extra_zone2"]["_lastModified"],
                    "_baseLastModifiedAt": day["extra_zone2"]["_lastModified"],
                },
                "_baseLastModifiedAt": day["_lastModified"],
            },
        },
    }
    tombstone_response = post("/sync", compact(tombstone_request))
    assert tombstone_response["success"], tombstone_response
    assert "extra_zone2" not in tombstone_response["results"][D1], \
        "the delete was rejected; this fixture is meant to pin an ACCEPTED one"
    roundtrip = {"request": tombstone_request, "response": tombstone_response}
    assert_fixture_only("sync-post-tombstone-roundtrip", roundtrip)
    write("sync-post-tombstone-roundtrip.json", roundtrip)

    # --- 6. the cheap poll probe ---------------------------------------------
    write("plans-version.json", get("/plans-version"))

    print("\nplan-day.json is hand-built (the dev server has no plan rows); it is not regenerated here.")

if __name__ == "__main__":
    main()
