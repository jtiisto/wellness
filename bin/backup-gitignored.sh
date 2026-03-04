#!/bin/bash
#
# Back up gitignored files listed in .backup-paths to Google Drive.
# Preserves directory structure under gdrive:Backups/wellness/gitignored/
# Designed to run in background from post-commit hook.

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$REPO_ROOT/.backup-paths"
REMOTE="gdrive:Backups/wellness/gitignored"

if [ ! -f "$MANIFEST" ]; then
    echo "backup-gitignored: $MANIFEST not found" >&2
    exit 1
fi

while IFS= read -r path || [ -n "$path" ]; do
    # Skip comments and blank lines
    [[ -z "$path" || "$path" =~ ^# ]] && continue

    full_path="$REPO_ROOT/$path"

    if [ -d "$full_path" ]; then
        rclone copy "$full_path" "$REMOTE/$path" 2>&1 | while read -r line; do
            echo "backup-gitignored: $line" >&2
        done
    elif [ -f "$full_path" ]; then
        dest_dir="$(dirname "$path")"
        rclone copy "$full_path" "$REMOTE/$dest_dir" 2>&1 | while read -r line; do
            echo "backup-gitignored: $line" >&2
        done
    fi
done < "$MANIFEST"
