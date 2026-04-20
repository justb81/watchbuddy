#!/usr/bin/env bash
# scripts/precommit.sh — run CI-equivalent checks locally on what's staged.
#
# Invoke directly before `git commit`, or enable the shared hook:
#   git config core.hooksPath .githooks
#
# Scoping mirrors the path filters in .github/workflows/build-android.yml
# and .github/workflows/test-backend.yml: we only run what the staged
# diff would trigger on CI. This keeps the common edit loop fast while
# still catching the failure modes that land on main.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

STAGED="$(git diff --cached --name-only --diff-filter=ACMR || true)"

if [ -z "$STAGED" ]; then
  echo "precommit: nothing staged — skipping checks"
  exit 0
fi

section() { printf "\n\033[1;34m==> %s\033[0m\n" "$*"; }
warn() { printf "\n\033[1;33m⚠ %s\033[0m\n" "$*"; }

match() {
  printf '%s\n' "$STAGED" | grep -Eq "$1"
}

# Detect a usable Android SDK so sandboxed environments (Claude Code on the
# web, ephemeral CI runners without the SDK layer) can fall through to a
# loud skip instead of forcing the agent into `--no-verify`. We accept any
# of the three ways the Android toolchain normally discovers the SDK.
android_sdk_available() {
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}" ]; then
    return 0
  fi
  if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "${ANDROID_SDK_ROOT}" ]; then
    return 0
  fi
  if [ -f local.properties ] && grep -Eq '^sdk\.dir=' local.properties; then
    return 0
  fi
  return 1
}

ran_any=0
skipped_android=0

# --- Android / Kotlin scope (mirrors build-android.yml `android:` filter) ---
if match '^(app-phone|app-tv|core)/' \
   || match '\.gradle\.kts$' \
   || match '^gradle/' \
   || match '^gradle\.properties$' \
   || match '^config/detekt/' \
   || match '^\.github/actions/' ; then
  if android_sdk_available; then
    ran_any=1
    section "Gradle unit tests"
    ./gradlew test
    section "detekt (all modules)"
    ./gradlew detektAll
    section "Android Lint (phone + TV debug)"
    ./gradlew :app-phone:lintDebug :app-tv:lintDebug
  else
    skipped_android=1
    warn "Android SDK not found (ANDROID_HOME / ANDROID_SDK_ROOT / local.properties sdk.dir all unset) — skipping Gradle checks."
    echo "   The staged change touches Android/Kotlin files. CI will run ./gradlew test / detektAll / lintDebug."
    echo "   Relying on CI for this commit. Do NOT use --no-verify; this script has already granted the skip."
  fi
fi

# --- Backend scope (mirrors test-backend.yml `backend:` filter) ---
if match '^backend/'; then
  ran_any=1
  section "Backend ESLint"
  npm --prefix backend run lint
  section "Backend Prettier check"
  npm --prefix backend run format:check
  section "Backend tests"
  npm --prefix backend test
fi

# --- Workflow YAML — syntax + actionlint when available ---
if match '^\.github/workflows/.*\.ya?ml$'; then
  ran_any=1
  section "Workflow YAML syntax"
  while IFS= read -r f; do
    python3 -c "import sys, yaml; yaml.safe_load(open('$f'))"
    echo "  OK: $f"
  done < <(printf '%s\n' "$STAGED" | grep -E '^\.github/workflows/.*\.ya?ml$')

  if command -v actionlint >/dev/null 2>&1; then
    section "actionlint"
    actionlint
  else
    echo "precommit: actionlint not installed — skipping (install via 'go install github.com/rhysd/actionlint/cmd/actionlint@latest' or 'brew install actionlint')"
  fi
fi

if [ "$ran_any" = "0" ] && [ "$skipped_android" = "0" ]; then
  echo "precommit: no staged files require checks — skipping"
elif [ "$ran_any" = "0" ] && [ "$skipped_android" = "1" ]; then
  warn "precommit: only Android/Kotlin checks applied and they were skipped (no SDK). Commit proceeds; CI is the real gate."
else
  if [ "$skipped_android" = "1" ]; then
    warn "precommit: backend / workflow checks passed, but Android/Kotlin checks were skipped (no SDK). CI is the real gate for those."
  fi
  section "All relevant checks passed"
fi
