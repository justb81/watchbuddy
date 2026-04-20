#!/bin/bash
# .claude/hooks/session-start.sh — prepare Claude Code on the web sessions.
#
# Guard on CLAUDE_CODE_REMOTE so local-interactive runs stay untouched. The
# container image caches whatever this script produces, so subsequent
# sessions are effectively free after the first.
#
# What this does:
#   1. Puts Node 22 on PATH when the container's default `node` is older
#      (backend/package.json requires >=22 for vitest 3.x).
#   2. Installs backend/ npm dependencies so vitest / eslint / prettier are
#      ready by the time the harness runs its test smoke check.
# What this deliberately does NOT do:
#   - Install the Android SDK. That's gigabytes and minutes; instead,
#     scripts/precommit.sh detects its absence and degrades to a loud skip.
#     See CLAUDE.md § "Local pre-commit checks" (sandboxed environments).

set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

if [ -x /opt/node22/bin/node ]; then
  current_major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
  if [ "$current_major" -lt 22 ]; then
    export PATH="/opt/node22/bin:$PATH"
    if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
      cat >> "$CLAUDE_ENV_FILE" <<'EOF'
export PATH="/opt/node22/bin:$PATH"
EOF
    fi
  fi
fi

echo "session-start-hook: node=$(node --version) ($(command -v node))"

if [ -d "${CLAUDE_PROJECT_DIR:-$PWD}/backend" ]; then
  cd "${CLAUDE_PROJECT_DIR:-$PWD}/backend"
  npm install --no-audit --no-fund
  echo "session-start-hook: backend deps ready"
fi
