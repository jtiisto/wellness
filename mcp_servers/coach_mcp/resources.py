"""MCP resources for the Coach server.

exercise_registry_summary, coach_plan_guide.

Bodies moved verbatim from `server.py`; the only change is
rebinding the captured `db_manager`/`registry`/`config` to `self.*`.
"""

from ._helpers import _get_coach_plan_guide


class Resources:
    def __init__(self, db_manager, registry, config):
        self.db_manager = db_manager
        self.registry = registry
        self.config = config

    def exercise_registry_summary(self) -> str:
        """Summary of all exercises in the registry, grouped by equipment."""
        try:
            results = self.db_manager.execute_query("""
                SELECT e.slug, e.name, e.equipment, e.category,
                       COUNT(pe.id) as usage_count
                FROM exercises e
                LEFT JOIN planned_exercises pe ON pe.canonical_slug = e.slug
                GROUP BY e.slug
                ORDER BY e.equipment, e.name
            """)

            if not results:
                return "# Exercise Registry\n\nNo exercises registered yet."

            lines = ["# Exercise Registry", ""]
            current_equip = None
            for row in results:
                equip = row["equipment"] or "unclassified"
                if equip != current_equip:
                    current_equip = equip
                    lines.append(f"## {equip.title()}")
                    lines.append("")

                cat_str = f" [{row['category']}]" if row["category"] else ""
                usage_str = f" (used {row['usage_count']}x)" if row["usage_count"] else ""
                lines.append(f"- **{row['name']}** (`{row['slug']}`){cat_str}{usage_str}")

            lines.append("")
            lines.append(f"Total: {len(results)} exercises")
            return "\n".join(lines)
        except Exception as e:
            return f"Error loading registry: {str(e)}"

    def coach_plan_guide(self) -> str:
        """Complete guide to creating workout plans."""
        return _get_coach_plan_guide()


def register(mcp, db_manager, registry, config):
    t = Resources(db_manager, registry, config)
    mcp.resource("file://exercise_registry_summary")(t.exercise_registry_summary)
    mcp.resource("file://coach_plan_guide")(t.coach_plan_guide)
