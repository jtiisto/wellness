# User-specific analysis queries.
#
# Copy this file to user_queries.py and customize with your own queries.
# user_queries.py is gitignored and will not be committed.
#
# Each query needs: id, label, description, prompt_template
# Optional: accepts_location (bool), extra_allowed_tools (list of str)
#
# Available template variables in prompt_template:
#   {arguments}    - user-provided arguments (or "(none)")
#   {current_time} - current date/time string

QUERIES = [
    {
        "id": "example_check",
        "label": "Example Health Check",
        "description": "Example query — replace with your own",
        "prompt_template": (
            "Analyze today's health data.\n\n"
            "**Arguments:** {arguments}\n"
            "**Current system time:** {current_time}\n\n"
            "Use available MCP tools to gather data and provide a summary."
        ),
    },
]
