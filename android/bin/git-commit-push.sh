#!/usr/bin/env bash
#
# git-commit-push.sh — mechanical git commit / push for this repo.
#
# Designed to be launched detached (Bash run_in_background) by the main agent,
# so the slow git hooks (pre-commit unit tests, pre-push full suite with
# coverage) never block the conversation — the agent is re-invoked when the
# script exits.
#
# What this script does NOT do — the agent must do these FIRST, in the
# foreground, before invoking the script:
#   * review `git diff`
#   * the sensitive-content scan (a semantic judgement; not scriptable)
#   * draft the commit message
#   * choose which files to stage
#
# Usage:
#   bin/git-commit-push.sh commit --message "MSG" --files "path1 path2 ..."
#   bin/git-commit-push.sh push
#   bin/git-commit-push.sh both   --message "MSG" --files "path1 path2 ..."
#
# --files paths are space-separated, relative to the repo root. The standard
# Co-Authored-By trailer is appended automatically (unless already present).
#
# push/both set the branch's upstream automatically on its first push
# (git push --set-upstream origin <branch>) and use the existing upstream
# afterwards — no flag needed. (The pre-push hook still runs the full suite, so
# the first push must still be launched detached, like every other push here.)
#
# Rules enforced here so they cannot be skipped:
#   * never --no-verify, never amend, never force-push
#   * on hook failure: print the error, exit non-zero, do NOT retry or fix
#
# Exit codes: 0 success | 2 usage error | other = underlying git/hook exit code.

set -uo pipefail

TRAILER="Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"

die() { echo "ERROR: $*" >&2; exit 2; }

MODE="${1:-}"
[[ -n "$MODE" ]] || die "missing mode (commit|push|both)"
shift

MESSAGE=""
FILES=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --message) [[ $# -ge 2 ]] || die "--message needs a value"; MESSAGE="$2"; shift 2 ;;
        --files)   [[ $# -ge 2 ]] || die "--files needs a value";   FILES="$2";   shift 2 ;;
        *) die "unknown argument: $1" ;;
    esac
done

case "$MODE" in
    commit|push|both) ;;
    *) die "invalid mode '$MODE' (expected commit|push|both)" ;;
esac

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "not inside a git work tree"
cd "$(git rev-parse --show-toplevel)" || die "cannot cd to repo root"

if [[ "$MODE" == "commit" || "$MODE" == "both" ]]; then
    [[ -n "$MESSAGE" ]] || die "--message is required for mode '$MODE'"
    [[ -n "$FILES" ]]   || die "--files is required for mode '$MODE'"
fi

# --- commit ---
if [[ "$MODE" == "commit" || "$MODE" == "both" ]]; then
    echo "=== staging ==="
    # word-splitting of $FILES is intentional (space-separated paths)
    # shellcheck disable=SC2086
    git add -- $FILES || die "git add failed"

    full_message="$MESSAGE"
    if [[ "$MESSAGE" != *"Co-Authored-By"* ]]; then
        full_message="$MESSAGE"$'\n\n'"$TRAILER"
    fi

    echo "=== committing ==="
    if git commit -m "$full_message"; then
        echo "COMMIT_OK $(git rev-parse HEAD)"
    else
        rc=$?
        echo "COMMIT_FAILED (exit $rc) — see pre-commit hook output above" >&2
        exit "$rc"
    fi
fi

# --- push ---
if [[ "$MODE" == "push" || "$MODE" == "both" ]]; then
    echo "=== pushing (pre-push hook runs the full test suite) ==="
    # Push to the existing upstream when there is one; otherwise set it on this
    # branch's first push. Handled automatically, never required — the caller
    # passes no extra argument either way.
    if git rev-parse --abbrev-ref --symbolic-full-name '@{u}' >/dev/null 2>&1; then
        set_upstream=""
    else
        branch="$(git rev-parse --abbrev-ref HEAD)"
        [[ "$branch" != "HEAD" ]] || die "detached HEAD: check out a branch before pushing"
        echo "no upstream tracking branch — setting it on first push: origin/$branch"
        set_upstream="--set-upstream origin $branch"
    fi
    # word-splitting of $set_upstream is intentional (git branch names have no spaces)
    # shellcheck disable=SC2086
    if git push $set_upstream; then
        echo "PUSH_OK"
    else
        rc=$?
        echo "PUSH_FAILED (exit $rc) — see pre-push hook output above" >&2
        exit "$rc"
    fi
    git fetch origin --quiet 2>/dev/null || true
fi

# --- final state ---
echo "=== done ==="
echo "branch: $(git rev-parse --abbrev-ref HEAD)"
echo "HEAD:   $(git rev-parse HEAD)"
upstream="$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || true)"
if [[ -n "$upstream" ]]; then
    echo "local:  $(git rev-parse HEAD)"
    echo "remote: $(git rev-parse "$upstream")"
    if [[ "$(git rev-parse HEAD)" == "$(git rev-parse "$upstream")" ]]; then
        echo "IN_SYNC"
    else
        echo "NOT_IN_SYNC"
    fi
fi
