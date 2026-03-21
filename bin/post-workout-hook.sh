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
# Replace this with real data collection.

echo "{\"timestamp\": \"$(date -Iseconds)\"}"
