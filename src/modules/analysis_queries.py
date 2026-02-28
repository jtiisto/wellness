# Load user-specific queries from user_queries.py (gitignored).
# This file contains only generic queries safe for version control.
try:
    from modules.user_queries import QUERIES as _USER_QUERIES
except ImportError:
    _USER_QUERIES = []

QUERIES = list(_USER_QUERIES) + [
    {
        "id": "post_workout",
        "label": "Today's Workout Analysis",
        "description": "Post-workout analysis of today's session",
        "prompt_template": (
            "IMPORTANT: Always use Garmin MCP tools to sync the latest health and workout data first.\n\n"
            "Analyze today's workout performance. Use the Coach MCP to get today's "
            "workout plan and logs, and Garmin MCP for heart rate and activity data from today.\n\n"
            "Provide your analysis as structured markdown with these sections:\n\n"
            "## Workout Summary\n"
            "Brief overview of what was done today vs what was planned.\n\n"
            "## Performance Analysis\n"
            "For each exercise, compare actual performance (weight, reps, RPE) to the plan. "
            "Note any PRs, regressions, or notable observations.\n\n"
            "## Heart Rate & Recovery\n"
            "Analyze heart rate data during the workout. Was Zone 2 cardio in the correct zone? "
            "Were rest periods adequate based on HR recovery?\n\n"
            "## Recommendations\n"
            "2-3 specific, actionable items for the next session based on today's performance.\n\n"
            "Use actual data from the MCP tools. If today's workout data is not available, say so clearly."
        ),
    },
    {
        "id": "pre_workout",
        "label": "Pre-Workout Readiness",
        "description": "Check readiness before today's workout",
        "prompt_template": (
            "IMPORTANT: Always use Garmin MCP tools to sync the latest health and workout data first.\n\n"
            "Assess my readiness for today's planned workout. Use:\n"
            "- Coach MCP: Get today's planned workout\n"
            "- Garmin MCP: Get last night's sleep data, current HRV, training readiness, "
            "body battery, resting HR\n"
            "- Journal MCP: Check recent entries for any noted pain, fatigue, or relevant observations\n\n"
            "Provide your analysis as structured markdown with these sections:\n\n"
            "## Readiness Assessment\n"
            "Overall readiness rating (Good / Moderate / Low) with supporting data points.\n\n"
            "## Today's Plan\n"
            "Brief summary of what's scheduled today.\n\n"
            "## Modifications\n"
            "If readiness is not optimal, suggest specific modifications (e.g., reduce volume, "
            "swap exercises, skip conditioning). If readiness is good, confirm the plan as-is.\n\n"
            "## Watch For\n"
            "1-2 things to monitor during the session (e.g., 'knee sensitivity during squats', "
            "'keep HR below X during Zone 2').\n\n"
            "Use actual data from the MCP tools. Be direct and specific."
        ),
    },
    {
        "id": "weekly_review",
        "label": "Weekly Performance Review",
        "description": "Week-to-week performance comparison",
        "timeout": 600,
        "prompt_template": (
            "IMPORTANT: Always use Garmin MCP tools to sync the latest health and workout data first.\n\n"
            "Analyze my training performance over the past 7 days compared to the previous 7 days. Use:\n"
            "- Coach MCP: Get workout plans and logs for the past 14 days\n"
            "- Garmin MCP: Get activity data, sleep trends, HRV trends, training readiness "
            "for the past 14 days\n"
            "- Journal MCP: Get entries from the past 14 days for supplement adherence, "
            "pain notes, and general observations\n\n"
            "Provide your analysis as structured markdown with these sections:\n\n"
            "## Training Volume\n"
            "Compare this week vs last week: sessions completed, total exercises, sets completed. "
            "Note any missed workouts.\n\n"
            "## Strength Trends\n"
            "For key exercises that appeared both weeks, compare weights and reps. "
            "Highlight any progression or regression.\n\n"
            "## Recovery Metrics\n"
            "Summarize sleep quality, HRV trend, and body battery trends. "
            "Are they improving, stable, or declining?\n\n"
            "## Adherence\n"
            "Check supplement and habit adherence from journal data. Note any gaps.\n\n"
            "## Priorities for Next Week\n"
            "3 specific priorities based on the data (e.g., 'increase squat weight by 5 lbs', "
            "'add a Zone 2 session', 'improve sleep consistency').\n\n"
            "Use actual data from the MCP tools. Include specific numbers where available."
        ),
    },
]


def get_query(query_id: str) -> dict | None:
    for q in QUERIES:
        if q["id"] == query_id:
            return q
    return None


def build_prompt(query: dict, location: str | None = None) -> str:
    """Build final prompt from template, substituting arguments and time."""
    from datetime import datetime
    prompt = query["prompt_template"]
    if location:
        prompt = prompt.replace("{arguments}", location)
    else:
        prompt = prompt.replace("{arguments}", "(none)")
    now = datetime.now()
    prompt = prompt.replace("{current_time}", now.strftime("%A, %B %d, %Y %I:%M %p %Z").strip())
    return prompt


def list_queries() -> list[dict]:
    """Return queries without prompt_template (for API response)."""
    return [
        {
            "id": q["id"],
            "label": q["label"],
            "description": q["description"],
            **({"accepts_location": True} if q.get("accepts_location") else {}),
        }
        for q in QUERIES
    ]
