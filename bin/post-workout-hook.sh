#!/bin/bash

# Post-Workout Hook — Example Template
#
# This script runs after a workout ends. Use it to capture post-exercise
# metrics or trigger follow-up actions (e.g., sync latest Garmin data).
#
# Contract:
#   - Exit code 0 = success, non-zero = failure
#   - Stdout = flat JSON object with string/number/boolean/null values
#   - Stderr is ignored (use it for logging/debug output)
#
# Replace the hardcoded values below with real data collection.

cat <<'EOF'
{
  "avg_heart_rate": 142,
  "max_heart_rate": 178,
  "calories_burned": 480,
  "training_effect_aerobic": 3.2,
  "training_effect_anaerobic": 2.1
}
EOF
