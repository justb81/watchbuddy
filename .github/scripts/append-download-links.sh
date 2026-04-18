#!/usr/bin/env bash
# Append a download-links table to an existing GitHub Release's notes.
# Reads environment variables set by the workflow:
#   TAG      – the release tag (e.g. v0.17.0)
#   VERSION  – the version string (e.g. 0.17.0)
#   REPO     – the GitHub repository slug (e.g. owner/repo)
#   ASSETS_DIR – directory containing the release asset files
set -euo pipefail

: "${TAG:?TAG environment variable is required}"
: "${VERSION:?VERSION environment variable is required}"
: "${REPO:?REPO environment variable is required}"
: "${ASSETS_DIR:?ASSETS_DIR environment variable is required}"

BASE="https://github.com/${REPO}/releases/download/${TAG}"

DOWNLOADS="---"
DOWNLOADS="${DOWNLOADS}\n\n## Downloads\n"
DOWNLOADS="${DOWNLOADS}\n| Asset | Link |"
DOWNLOADS="${DOWNLOADS}\n|-------|------|"

if [ -f "${ASSETS_DIR}/watchbuddy-phone-${VERSION}-debug.apk" ]; then
    DOWNLOADS="${DOWNLOADS}\n| Phone (debug) | [watchbuddy-phone-${VERSION}-debug.apk](${BASE}/watchbuddy-phone-${VERSION}-debug.apk) |"
fi
if [ -f "${ASSETS_DIR}/watchbuddy-tv-${VERSION}-debug.apk" ]; then
    DOWNLOADS="${DOWNLOADS}\n| TV (debug) | [watchbuddy-tv-${VERSION}-debug.apk](${BASE}/watchbuddy-tv-${VERSION}-debug.apk) |"
fi
if [ -f "${ASSETS_DIR}/watchbuddy-phone-${VERSION}-release.apk" ]; then
    DOWNLOADS="${DOWNLOADS}\n| Phone (signed APK) | [watchbuddy-phone-${VERSION}-release.apk](${BASE}/watchbuddy-phone-${VERSION}-release.apk) |"
fi
if [ -f "${ASSETS_DIR}/watchbuddy-tv-${VERSION}-release.apk" ]; then
    DOWNLOADS="${DOWNLOADS}\n| TV (signed APK) | [watchbuddy-tv-${VERSION}-release.apk](${BASE}/watchbuddy-tv-${VERSION}-release.apk) |"
fi

# Fetch existing release notes and append the download section
EXISTING=$(gh release view "${TAG}" --json body -q .body)
UPDATED=$(printf "%s\n\n%b" "$EXISTING" "$DOWNLOADS")
gh release edit "${TAG}" --notes "$UPDATED"
