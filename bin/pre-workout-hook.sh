#!/bin/bash

# Pre-Workout Hook — Example Template
#
# This script runs before a workout starts. Its primary purpose is to
# capture a snapshot of stats that will change after exercise (e.g.,
# Garmin training readiness, body battery, HRV status).
#
# Contract:
#   - Exit code 0 = success, non-zero = failure
#   - Stdout = flat JSON object with string/number/boolean/null values
#   - Stderr is ignored (use it for logging/debug output)
#
# Replace the hardcoded values below with real data collection
# (e.g., query the garmy database, call an API, etc.)

cat <<'EOF'
{
  "training_readiness": 70,
  "hrv_status": "balanced",
  "body_battery": 85,
  "sleep_score": 82
}
EOF
