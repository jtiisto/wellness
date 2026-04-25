#!/usr/bin/env python3
"""One-time migration to lift the legacy `(Pair A)` name-suffix convention into
the structured `superset_group` field.

What it does, in order, all in one transaction:
    1. Adds the `superset_group` column to `planned_exercises` if missing
       (idempotent — safe before or after the new server code is deployed).
    2. Walks `planned_exercises`, parses the legacy suffix from `name`,
       writes the parsed label to `superset_group`, strips the suffix from
       `name`, and recomputes `canonical_slug` from the cleaned name.
    3. Walks the `exercises` registry, dedupes clusters that share a clean
       base name (e.g. `bench_press`, `bench_press_pair_a`, `bench_press_pair_b`
       collapse to a single `bench_press` row). Remaps `canonical_slug`
       references in `planned_exercises` and `exercise_logs` to the survivor.
    4. Idempotency check: re-running finds zero matches.

Default mode is `--dry-run` (prints what would change). Pass `--apply` to commit.

Once the prod DB has been migrated, delete this script from the repo.

Usage:
    python bin/migrate_superset_groups.py                # dry run
    python bin/migrate_superset_groups.py --apply        # commit
    python bin/migrate_superset_groups.py --db /path/to/coach.db --apply
"""

import argparse
import os
import re
import sqlite3
import sys
from pathlib import Path

DEFAULT_DB = Path(__file__).resolve().parent.parent / "data" / "coach.db"

# Match the trailing parenthetical when it contains Pair/Superset/Triplet.
LEGACY_SUFFIX_RE = re.compile(
    r"\s*\(\s*(Pair|Superset|Triplet)\s+([^)]+?)\s*\)\s*$",
    re.IGNORECASE,
)


def generate_slug(name: str) -> str:
    """Mirror of mcp_servers.coach_mcp.exercise_registry.generate_slug.

    Reimplemented here so the migration script has zero runtime imports — it
    can run against a stopped server before the new code is deployed.
    """
    slug = name.lower()
    slug = re.sub(r"[-\s]+", "_", slug)
    slug = re.sub(r"[^a-z0-9_]", "", slug)
    slug = re.sub(r"_+", "_", slug)
    return slug.strip("_")


def parse_legacy_label(name: str):
    """Return (clean_name, group_label) or (name, None) if no suffix.

    Normalization:
      "Bench Press (Pair A)"     -> ("Bench Press", "A")
      "Squat (Superset B)"       -> ("Squat",       "B")
      "Deadlift (Triplet A)"     -> ("Deadlift",    "Triplet A")

    `Pair` / `Superset` are dropped (the UI renders bare labels as
    "Superset A"). `Triplet` is preserved because it carries semantic info
    the bare letter loses.
    """
    m = LEGACY_SUFFIX_RE.search(name)
    if not m:
        return name, None
    kind, inner = m.group(1).lower(), m.group(2).strip()
    clean = name[: m.start()].rstrip()
    if kind == "triplet":
        label = f"Triplet {inner}"
    else:
        label = inner
    return clean, label


def column_exists(conn, table: str, column: str) -> bool:
    rows = conn.execute(f"PRAGMA table_info({table})").fetchall()
    return any(row[1] == column for row in rows)


def ensure_column(conn, dry_run: bool) -> bool:
    """Add `superset_group` column if missing. Returns True if added."""
    if column_exists(conn, "planned_exercises", "superset_group"):
        return False
    if dry_run:
        print("  [dry-run] would ALTER TABLE planned_exercises ADD COLUMN superset_group TEXT")
        return True
    conn.execute("ALTER TABLE planned_exercises ADD COLUMN superset_group TEXT")
    return True


def migrate_planned_exercises(conn, dry_run: bool):
    """Parse legacy suffix on planned_exercises rows and lift into superset_group."""
    rows = conn.execute(
        "SELECT id, name, canonical_slug FROM planned_exercises"
    ).fetchall()

    updates = []
    for row in rows:
        ex_id, name, old_slug = row[0], row[1], row[2]
        clean, label = parse_legacy_label(name)
        if label is None:
            continue
        new_slug = generate_slug(clean)
        updates.append((ex_id, name, clean, label, old_slug, new_slug))

    if not updates:
        print("  no planned_exercises rows match the legacy suffix")
        return updates

    print(f"  found {len(updates)} planned_exercises row(s) to migrate")
    for u in updates[:10]:
        print(f"    [{u[0]}] '{u[1]}' -> name='{u[2]}', superset_group='{u[3]}', slug='{u[4]}' -> '{u[5]}'")
    if len(updates) > 10:
        print(f"    ... and {len(updates) - 10} more")

    if dry_run:
        return updates

    for u in updates:
        ex_id, _old_name, clean, label, _old_slug, new_slug = u
        conn.execute(
            "UPDATE planned_exercises SET name = ?, superset_group = ?, canonical_slug = ? WHERE id = ?",
            (clean, label, new_slug, ex_id),
        )
    return updates


def dedupe_exercise_registry(conn, dry_run: bool):
    """Merge duplicate exercises rows whose slugs share a clean base.

    Strategy:
      - For each row in `exercises`, compute (clean_name, clean_slug).
      - Group by clean_slug.
      - In each group, prefer a row that already has the clean slug. If none
        exists, create one and remap references.
      - For non-survivors: update referencing rows in planned_exercises and
        exercise_logs to the survivor's slug, then delete the orphan row.
    """
    rows = conn.execute("SELECT slug, name, equipment, category, created_at, source FROM exercises").fetchall()

    clusters = {}
    for row in rows:
        slug, name, equipment, category, created_at, source = row
        clean_name, _ = parse_legacy_label(name)
        clean_slug = generate_slug(clean_name)
        clusters.setdefault(clean_slug, []).append({
            "slug": slug,
            "name": name,
            "clean_name": clean_name,
            "equipment": equipment,
            "category": category,
            "created_at": created_at,
            "source": source,
        })

    plan = []  # list of (survivor_slug, [orphan_slugs])
    creates = []  # list of survivor rows to insert (when no clean exists)

    for clean_slug, members in clusters.items():
        if len(members) == 1 and members[0]["slug"] == clean_slug:
            continue  # already canonical, nothing to do

        # Prefer an existing row whose slug already matches the clean slug.
        survivor = next((m for m in members if m["slug"] == clean_slug), None)
        if survivor is None:
            # Create a fresh survivor row from the first member's metadata.
            seed = members[0]
            creates.append({
                "slug": clean_slug,
                "name": seed["clean_name"],
                "equipment": seed["equipment"],
                "category": seed["category"],
                "created_at": seed["created_at"],
                "source": "auto-merged",
            })
            survivor_slug = clean_slug
        else:
            survivor_slug = survivor["slug"]

        orphan_slugs = [m["slug"] for m in members if m["slug"] != survivor_slug]
        if orphan_slugs:
            plan.append((survivor_slug, orphan_slugs))

    if not creates and not plan:
        print("  exercises registry has no duplicate clusters")
        return creates, plan

    if creates:
        print(f"  would create {len(creates)} canonical survivor row(s)")
        for c in creates[:10]:
            print(f"    + {c['slug']} (name='{c['name']}')")
        if len(creates) > 10:
            print(f"    ... and {len(creates) - 10} more")

    total_orphans = sum(len(o) for _, o in plan)
    print(f"  would merge {total_orphans} orphan row(s) into {len(plan)} survivor(s)")
    for survivor, orphans in plan[:10]:
        print(f"    {orphans} -> {survivor}")
    if len(plan) > 10:
        print(f"    ... and {len(plan) - 10} more clusters")

    if dry_run:
        return creates, plan

    for c in creates:
        conn.execute(
            """
            INSERT OR IGNORE INTO exercises (slug, name, equipment, category, created_at, source)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (c["slug"], c["name"], c["equipment"], c["category"], c["created_at"], c["source"]),
        )

    for survivor, orphans in plan:
        for orphan in orphans:
            conn.execute(
                "UPDATE planned_exercises SET canonical_slug = ? WHERE canonical_slug = ?",
                (survivor, orphan),
            )
            conn.execute(
                "UPDATE exercise_logs SET canonical_slug = ? WHERE canonical_slug = ?",
                (survivor, orphan),
            )
            conn.execute("DELETE FROM exercises WHERE slug = ?", (orphan,))

    return creates, plan


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db", default=os.environ.get("COACH_DB_PATH", str(DEFAULT_DB)),
                        help=f"Path to coach.db (default: {DEFAULT_DB})")
    parser.add_argument("--apply", action="store_true",
                        help="Commit the migration. Without this flag, runs in dry-run mode.")
    args = parser.parse_args()

    db_path = Path(args.db)
    if not db_path.exists():
        print(f"error: database not found at {db_path}", file=sys.stderr)
        return 2

    dry_run = not args.apply
    print(f"Coach DB: {db_path}")
    print(f"Mode:     {'DRY RUN' if dry_run else 'APPLY'}")
    print()

    conn = sqlite3.connect(db_path, isolation_level=None)  # we manage our own tx
    conn.execute("PRAGMA foreign_keys = ON")

    try:
        if not dry_run:
            conn.execute("BEGIN")

        print("Step 1: ensure superset_group column")
        added = ensure_column(conn, dry_run)
        print("  added column" if added else "  column already present")
        print()

        # Order matters: dedupe the registry FIRST so the canonical "clean"
        # rows exist (and orphan-referencing rows are remapped onto them)
        # before we rewrite planned_exercises.canonical_slug — otherwise the
        # FK constraint to exercises(slug) fires when we point a row at a
        # slug that doesn't yet exist.
        print("Step 2: dedupe exercises registry")
        creates, registry_plan = dedupe_exercise_registry(conn, dry_run)
        print()

        print("Step 3: migrate planned_exercises rows")
        # If we just added the column in dry-run mode, we can't actually
        # SELECT/UPDATE it, so the planned-exercises migration is informational
        # only on a fresh DB. On a real prod DB the column either exists from
        # phase-1 server code or we just added it for real.
        planned_updates = migrate_planned_exercises(conn, dry_run)
        print()

        if not dry_run:
            print("Step 4: idempotency check")
            remaining = conn.execute(
                "SELECT COUNT(*) FROM planned_exercises WHERE name LIKE '%(Pair %' OR name LIKE '%(Superset %' OR name LIKE '%(Triplet %'"
            ).fetchone()[0]
            if remaining:
                raise RuntimeError(f"idempotency check failed: {remaining} planned_exercises rows still have legacy suffix")
            print(f"  planned_exercises with legacy suffix: 0  ✓")
            print()

            conn.execute("COMMIT")
            print("Committed.")
        else:
            print("Dry run complete — no changes written. Re-run with --apply to commit.")
        return 0
    except Exception as e:
        if not dry_run:
            try:
                conn.execute("ROLLBACK")
            except sqlite3.OperationalError:
                pass
        print(f"error: {e}", file=sys.stderr)
        return 1
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
