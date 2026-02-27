"""Canonical exercise registry with stable slug IDs.

Provides fuzzy name resolution so plans and logs are self-describing
and cross-session queries (e.g. "all sets of KB Goblet Squat") are trivial.
"""

import logging
import re
from datetime import datetime, timezone
from difflib import SequenceMatcher
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)


# ==================== Slug Generation ====================


def generate_slug(name: str) -> str:
    """Convert an exercise name to a canonical slug.

    Rules: lowercase, hyphens/spaces to underscores, strip non-alphanumeric.
    Examples:
        "KB Goblet Squat"    -> "kb_goblet_squat"
        "Push-ups (Pair A)"  -> "push_ups_pair_a"
        "DB Floor Press"     -> "db_floor_press"
    """
    slug = name.lower()
    # Replace hyphens and spaces with underscores
    slug = re.sub(r"[-\s]+", "_", slug)
    # Strip non-alphanumeric (keep underscores)
    slug = re.sub(r"[^a-z0-9_]", "", slug)
    # Collapse multiple underscores
    slug = re.sub(r"_+", "_", slug)
    # Strip leading/trailing underscores
    slug = slug.strip("_")
    return slug


# ==================== Equipment / Category Inference ====================

# Keywords that indicate bodyweight or band exercises (no meaningful weight)
_BODYWEIGHT_KEYWORDS = [
    "push-up", "pushup", "push up", "bodyweight", "band pull",
    "banded", "jump squat", "plank", "dead hang", "wall sit",
    "glute bridge", "bird-dog", "bird dog", "dead bug", "cat-cow",
    "cat cow", "mountain climber", "burpee", "lunge",
]

_EQUIPMENT_KEYWORDS = {
    "kb": "kettlebell", "kettlebell": "kettlebell",
    "db": "dumbbell", "dumbbell": "dumbbell",
    "barbell": "barbell", "bb": "barbell",
    "trap bar": "barbell",
    "cable": "cable",
    "machine": "machine",
    "band": "band",
    "trx": "suspension",
    "suspension": "suspension",
}


def _infer_equipment(ex: Dict[str, Any]) -> Optional[str]:
    """Infer equipment from explicit field or name keywords."""
    # Explicit equipment field takes priority
    equipment = ex.get("equipment")
    if equipment:
        return equipment

    name = ex.get("name", "").lower()

    # Check bodyweight keywords first
    if any(kw in name for kw in _BODYWEIGHT_KEYWORDS):
        return "bodyweight"

    # Check equipment keywords
    for keyword, equip in _EQUIPMENT_KEYWORDS.items():
        if keyword in name:
            return equip

    return None


def _infer_category(ex: Dict[str, Any], block: Optional[Dict[str, Any]] = None) -> Optional[str]:
    """Infer category from block type."""
    if block is None:
        return None

    block_type = block.get("block_type", "")
    if block_type == "warmup":
        return "mobility"
    elif block_type == "cardio":
        return "cardio"
    return None


# ==================== Exercise Registry ====================


class ExerciseRegistry:
    """In-memory cache of canonical exercises with fuzzy name resolution."""

    # Fuzzy match thresholds
    EXACT_THRESHOLD = 90
    FUZZY_WARN_THRESHOLD = 85

    def __init__(self):
        # slug -> {name, equipment, category}
        self._by_slug: Dict[str, Dict[str, Any]] = {}
        # lowercase name -> slug (for fast exact lookups)
        self._by_name: Dict[str, str] = {}

    def load(self, cursor) -> None:
        """Load all exercises from the database."""
        self._by_slug.clear()
        self._by_name.clear()

        cursor.execute("SELECT slug, name, equipment, category FROM exercises")
        for row in cursor.fetchall():
            self._by_slug[row["slug"]] = {
                "name": row["name"],
                "equipment": row["equipment"],
                "category": row["category"],
            }
            self._by_name[row["name"].lower()] = row["slug"]

    def resolve(self, name: str) -> Tuple[Optional[str], str]:
        """Resolve a free-text exercise name to a canonical slug.

        Returns:
            (slug, match_type) where match_type is "exact", "fuzzy", or "new"
            - "exact": name matched exactly (case-insensitive)
            - "fuzzy": name matched within threshold (auto-resolved)
            - "new": no match found, caller should create a new entry
        """
        # Try exact match (case-insensitive)
        lower_name = name.lower()
        if lower_name in self._by_name:
            return self._by_name[lower_name], "exact"

        # Fuzzy match against all known names
        best_score = 0
        best_slug = None
        best_name = None

        for known_name, slug in self._by_name.items():
            score = SequenceMatcher(None, lower_name, known_name).ratio() * 100
            if score > best_score:
                best_score = score
                best_slug = slug
                best_name = known_name

        if best_score >= self.EXACT_THRESHOLD:
            return best_slug, "exact"

        if best_score >= self.FUZZY_WARN_THRESHOLD:
            logger.warning(
                "Fuzzy match: '%s' -> '%s' (score=%.0f)",
                name, best_name, best_score,
            )
            return best_slug, "fuzzy"

        # No match — caller should create new entry
        return None, "new"

    def add(self, slug: str, name: str, equipment: Optional[str] = None,
            category: Optional[str] = None) -> None:
        """Add an exercise to the in-memory cache (after DB insert)."""
        self._by_slug[slug] = {
            "name": name,
            "equipment": equipment,
            "category": category,
        }
        self._by_name[name.lower()] = slug

    def get(self, slug: str) -> Optional[Dict[str, Any]]:
        """Get exercise info by slug."""
        return self._by_slug.get(slug)

    def all_exercises(self) -> Dict[str, Dict[str, Any]]:
        """Return all exercises keyed by slug."""
        return dict(self._by_slug)

    def __len__(self) -> int:
        return len(self._by_slug)


# ==================== Plan Resolution ====================


def _get_utc_now() -> str:
    """Return current UTC time as ISO-8601 string."""
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def resolve_plan_exercises(
    registry: ExerciseRegistry,
    plan: Dict[str, Any],
    cursor,
) -> Dict[str, Any]:
    """Walk plan blocks, resolve each exercise to a canonical slug.

    Mutates the plan dict in-place: adds 'canonical_slug' to each exercise.
    Creates new registry entries as needed (inserts into DB).

    Returns:
        Resolution report: {resolved, fuzzy, created, details}
    """
    report = {"resolved": 0, "fuzzy": 0, "created": 0, "details": []}

    for block in plan.get("blocks", []):
        for ex in block.get("exercises", []):
            name = ex.get("name", "")
            ex_type = ex.get("type", "")

            # Skip checklist items — the parent checklist exercise
            # gets a slug, but we still resolve it
            slug, match_type = registry.resolve(name)

            if match_type == "exact":
                ex["canonical_slug"] = slug
                report["resolved"] += 1

            elif match_type == "fuzzy":
                ex["canonical_slug"] = slug
                report["fuzzy"] += 1
                report["details"].append(
                    f"fuzzy: '{name}' -> '{registry.get(slug)['name']}'"
                )

            else:
                # Create new registry entry
                slug = generate_slug(name)

                # Handle slug collision (different name, same slug)
                if registry.get(slug) is not None:
                    # Append a suffix
                    suffix = 2
                    while registry.get(f"{slug}_{suffix}") is not None:
                        suffix += 1
                    slug = f"{slug}_{suffix}"

                equipment = _infer_equipment(ex)
                category = _infer_category(ex, block)
                now = _get_utc_now()

                cursor.execute("""
                    INSERT OR IGNORE INTO exercises (slug, name, equipment, category, created_at, source)
                    VALUES (?, ?, ?, ?, ?, ?)
                """, [slug, name, equipment, category, now, "auto"])

                registry.add(slug, name, equipment, category)
                ex["canonical_slug"] = slug
                report["created"] += 1
                report["details"].append(f"new: '{name}' -> '{slug}'")

    return report
