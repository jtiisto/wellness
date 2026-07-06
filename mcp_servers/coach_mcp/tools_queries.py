"""Query-family MCP tools for the Coach server.

get_workout_logs, get_workout_summary, list_scheduled_dates.

Bodies moved verbatim from `server.py`; the only change is
rebinding the captured `db_manager`/`registry`/`config` to `self.*`.
"""

from datetime import date
from typing import Any, Dict, List, Optional

from modules import coach_queries

from ._helpers import _assemble_log_from_db


class QueryTools:
    def __init__(self, db_manager, registry, config):
        self.db_manager = db_manager
        self.registry = registry
        self.config = config

    def get_workout_logs(
        self,
        start_date: str,
        end_date: str
    ) -> List[Dict[str, Any]]:
        """WHEN TO USE: When analyzing workout history or performance trends.

        Retrieves completed workout logs for the specified date range.
        This is READ-ONLY - logs are created by the user through the PWA.

        Args:
            start_date: Start date (YYYY-MM-DD)
            end_date: End date (YYYY-MM-DD)

        Returns:
            List of logs with date, exercise completion data, and
            pre/post workout stats (readiness metrics, recovery data, etc.)
            when available. A log with "off_plan": true was recorded on a
            day with no workout plan (an extra session, e.g. ad-hoc Zone 2)
            — treat it as additional volume, not plan adherence.
        """
        try:
            results = self.db_manager.execute_query("""
                SELECT * FROM workout_session_logs
                WHERE date >= ? AND date <= ?
                ORDER BY date
            """, [start_date, end_date])

            logs = []
            for row in results:
                with self.db_manager.get_connection(read_only=True) as conn:
                    cursor = conn.cursor()
                    log_data = _assemble_log_from_db(
                        cursor, row["id"], session_id=row["session_id"]
                    )

                log_entry = {
                    "date": row["date"],
                    "last_modified": row["last_modified"],
                    "log": log_data
                }
                if row["session_id"] is None:
                    log_entry["off_plan"] = True
                logs.append(log_entry)

            return logs
        except Exception as e:
            raise ValueError(f"Failed to get workout logs: {str(e)}")

    def get_workout_summary(self, days: int = 30) -> Dict[str, Any]:
        """WHEN TO USE: When you want a quick overview of workout activity.

        Provides summary statistics about workout plans and completed logs.

        Args:
            days: Number of recent days to analyze (max 365, default: 30)

        Returns:
            Summary including planned vs completed workouts, exercise counts,
            and off-plan activity (`extra_sessions` / `extra_session_dates` —
            sessions logged on days with no plan; they never count toward the
            completion rates).
        """
        if days > 365:
            raise ValueError("Days cannot exceed 365")

        try:
            return coach_queries.workout_summary(self.db_manager, days=days, today=date.today())
        except Exception as e:
            raise ValueError(f"Failed to generate summary: {str(e)}")

    def list_scheduled_dates(
        self,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None
    ) -> List[str]:
        """WHEN TO USE: When you need to see which dates have plans scheduled.

        Returns a list of dates that have workout plans.

        Args:
            start_date: Start date (YYYY-MM-DD), defaults to today
            end_date: End date (YYYY-MM-DD), defaults to 6 weeks from today

        Returns:
            List of dates (YYYY-MM-DD) that have plans
        """
        try:
            return coach_queries.list_scheduled_dates(
                self.db_manager, start_date=start_date, end_date=end_date, today=date.today()
            )
        except Exception as e:
            raise ValueError(f"Failed to list scheduled dates: {str(e)}")


def register(mcp, db_manager, registry, config):
    t = QueryTools(db_manager, registry, config)
    mcp.tool()(t.get_workout_logs)
    mcp.tool()(t.get_workout_summary)
    mcp.tool()(t.list_scheduled_dates)
