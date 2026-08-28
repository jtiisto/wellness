#!/bin/bash
#
# Portable garmy sync runner — used by BOTH the 9am/2pm/10pm cron (no args, so
# the historical 7/3 scope) and the server's on-demand /api/garmin/sync trigger
# (2/2, a top-up for a pull-to-refresh).
#
#   $1  --last-days    (default 7)
#   $2  --resync-days  (default 3)
#
# Output is APPENDED, timestamped, to ~/.garmy/sync-cron.log in the format the
# cron trail has always used — that log is the user's record of every sync, and
# a bare-stdout runner would end it. The server invocation reads only the exit
# code (it discards the pipes entirely); the log is for humans.
#
# Shipping this file REPLACES prod's untracked equivalent: deploy-prod.sh's only
# never-clobber pattern is *-workout-hook.sh, so bin/deploy.manifest's
# `ship-bin garmin-sync.sh` line overwrites prod's copy as intended.
set -u

LAST_DAYS="${1:-7}"
RESYNC_DAYS="${2:-3}"
GARMY_PROFILE="$HOME/.garmy"
LOG_FILE="$GARMY_PROFILE/sync-cron.log"

# garmy is a source checkout (editable install), not a packaged dist, on both
# machines — GARMY_SRC repoints it where the layout differs.
export PYTHONPATH="${GARMY_SRC:-$HOME/dev/garmy/src}:${PYTHONPATH:-}"

mkdir -p "$GARMY_PROFILE"

# The block's exit status is that of its LAST command — the python one — so
# cron and the server both still see garmy's own exit code despite the
# redirection. --profile-path is a top-level garmy argument; --resync-days and
# --progress belong to the `sync` subcommand.
{
    echo "--- $(date '+%Y-%m-%d %H:%M:%S') ---"
    python3 -m garmy.localdb.cli \
        --profile-path "$GARMY_PROFILE" \
        sync \
        --last-days "$LAST_DAYS" \
        --resync-days "$RESYNC_DAYS" \
        --progress silent
} >> "$LOG_FILE" 2>&1
