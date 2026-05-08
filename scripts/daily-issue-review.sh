#!/usr/bin/env bash
# Runs the daily issue review pipeline via Claude Code.
# Invoke manually or via cron: 0 1 * * * /path/to/watchbuddy/scripts/daily-issue-review.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$REPO_ROOT/logs"
LOG_FILE="$LOG_DIR/daily-issue-review.log"

mkdir -p "$LOG_DIR"

cd "$REPO_ROOT"

echo "=== $(date -u +"%Y-%m-%dT%H:%M:%SZ") daily-issue-review start ===" >> "$LOG_FILE"

claude \
  --dangerously-skip-permissions \
  --model claude-sonnet-4-6 \
  --print \
  "$(cat .claude/commands/daily-issue-review.md)" \
  >> "$LOG_FILE" 2>&1

echo "=== $(date -u +"%Y-%m-%dT%H:%M:%SZ") daily-issue-review end ===" >> "$LOG_FILE"
