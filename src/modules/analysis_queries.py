# Load user-specific queries from user_queries.py (gitignored).
# This file contains only generic queries safe for version control.
try:
    from modules.user_queries import QUERIES as _USER_QUERIES
except ImportError:
    _USER_QUERIES = []

# ---------------------------------------------------------------------------
# Schema hints for MCP data sources.
# These are included in prompts so the LLM queries correct table/column names
# on the first attempt, avoiding trial-and-error retries that cause timeouts.
# ---------------------------------------------------------------------------

_GARMIN_SCHEMA = (
    "Schema hints for Garmin MCP (garmy-localdb):\n"
    "- daily_health_metrics: filter by metric_date (DATE). Columns include: "
    "sleep_duration_hours, sleep_score, sleep_score_qualifier, resting_heart_rate, "
    "hrv_last_night_avg, hrv_weekly_avg, hrv_status, body_battery_high, body_battery_low, "
    "skin_temp_deviation_c, training_readiness_score, training_readiness_level, "
    "avg_stress_level, average_spo2, total_steps, total_calories, active_calories.\n"
    "- activities: filter by activity_date (DATE). Columns include: "
    "activity_name, activity_type, duration_seconds, avg_heart_rate, max_heart_rate, "
    "training_load, distance_meters, calories, total_sets, total_reps, total_weight_kg.\n"
    "- exercise_sets: DO NOT USE. Garmin set/rep/exercise data is never accurate. "
    "Use Coach MCP for all exercise names and performance data.\n"
    "- activity_splits: join to activities on activity_id. Columns: "
    "lap_index, duration_seconds, distance_meters, avg_heart_rate, max_heart_rate, "
    "avg_speed, elevation_gain.\n"
)

_COACH_SCHEMA = (
    "Schema hints for Coach MCP (coach-localdb):\n"
    "- workout_sessions: filter by date (TEXT, YYYY-MM-DD). Columns: "
    "day_name, location, phase, duration_min.\n"
    "- session_blocks: join on session_id → workout_sessions.id. Columns: "
    "position, block_type, title, duration_min, rest_guidance, rounds.\n"
    "- planned_exercises: join on block_id → session_blocks.id. Columns: "
    "exercise_key, name, exercise_type, target_sets, target_reps, guidance_note.\n"
    "- workout_session_logs: filter by date (TEXT, YYYY-MM-DD). Columns: "
    "pain_discomfort, general_notes. Join on session_id → workout_sessions.id.\n"
    "- exercise_logs: join on session_log_id → workout_session_logs.id. Columns: "
    "exercise_key, completed, user_note, duration_min, avg_hr, max_hr.\n"
    "- set_logs: join on exercise_log_id → exercise_logs.id. Columns: "
    "set_num, weight, reps, rpe, unit, duration_sec, completed.\n"
)

_JOURNAL_SCHEMA = (
    "Schema hints for Journal MCP (journal-localdb):\n"
    "- entries: filter by date (TEXT, YYYY-MM-DD). Columns: "
    "tracker_id, value (numeric), completed (0/1).\n"
    "- trackers: join on entries.tracker_id = trackers.id. Columns: "
    "name, category, type ('simple' or 'quantifiable').\n"
)

_PARALLEL_HINT = (
    "IMPORTANT: After the Garmin sync, issue ALL of the data-fetching tool "
    "calls listed below in a SINGLE response — do NOT call them one at a "
    "time. These queries are independent and MUST be executed in parallel "
    "to avoid timeouts.\n\n"
)

QUERIES = list(_USER_QUERIES) + [
    {
        "id": "post_workout",
        "label": "Today's Workout Analysis",
        "description": "Post-workout analysis of today's session",
        "icon": "dumbbell",
        "prompt_template": (
            "IMPORTANT: Always use Garmin MCP tools to sync the latest health and workout data first.\n\n"
            "Analyze today's workout performance.\n\n"
            + _COACH_SCHEMA + "\n"
            + _GARMIN_SCHEMA + "\n"
            + _PARALLEL_HINT
            + "Fetch these 2 queries in parallel in a single response:\n"
            "1. Coach MCP: Get today's workout plan (planned_exercises) and logs "
            "(exercise_logs, set_logs)\n"
            "2. Garmin MCP: Get today's activities and activity_splits "
            "for heart rate data (do NOT use exercise_sets — Garmin set/rep data is unreliable)\n\n"
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
        "icon": "zap",
        "prompt_template": (
            "IMPORTANT: Always use Garmin MCP tools to sync the latest health and workout data first.\n\n"
            "Assess my readiness for today's planned workout.\n\n"
            + _COACH_SCHEMA + "\n"
            + _GARMIN_SCHEMA + "\n"
            + _JOURNAL_SCHEMA + "\n"
            + _PARALLEL_HINT
            + "Fetch these 3 queries in parallel in a single response:\n"
            "1. Coach MCP: Get today's planned workout (workout_sessions, session_blocks, "
            "planned_exercises)\n"
            "2. Garmin MCP: Get last night's sleep data, current HRV, training readiness, "
            "body battery, resting HR from daily_health_metrics\n"
            "3. Journal MCP: Check recent entries (last 3 days) for any noted pain, fatigue, "
            "or relevant observations\n\n"
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
        "icon": "calendar",
        "timeout": 400,
        "prompt_template": (
            "IMPORTANT: Always use Garmin MCP tools to sync the latest health and workout data first.\n\n"
            "Analyze my training performance over the past 7 days compared to the previous 7 days.\n\n"
            + _COACH_SCHEMA + "\n"
            + _GARMIN_SCHEMA + "\n"
            + _JOURNAL_SCHEMA + "\n"
            + _PARALLEL_HINT
            + "Fetch these 3 queries in parallel in a single response:\n"
            "1. Coach MCP: Get workout plans and logs for the past 14 days "
            "(workout_sessions, planned_exercises, workout_session_logs, exercise_logs, set_logs)\n"
            "2. Garmin MCP: Get daily_health_metrics and activities for the past 14 days "
            "(do NOT query exercise_sets — Garmin set/rep data is unreliable. Use Coach MCP as the sole source for exercise names and performance data)\n"
            "3. Journal MCP: Get entries from the past 14 days for supplement adherence, "
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
            "Check supplement and habit adherence from journal data. "
            "IMPORTANT: Before flagging any supplement gaps, read `plans/ACTIVE_CONTEXT.md` "
            "for active taper schedules and cycling rules that override normal adherence expectations "
            "(e.g., caffeine taper, Thesis Clarity paused, L-theanine tapering). "
            "Only flag gaps that violate the current plan.\n\n"
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
            **({"icon": q["icon"]} if q.get("icon") else {}),
            **({"accepts_location": True} if q.get("accepts_location") else {}),
        }
        for q in QUERIES
    ]
