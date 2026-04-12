# CLAUDE.md — WatchBuddy Agent Guide

This file provides context for AI coding agents (Claude, Copilot, Cursor, etc.) so they can work effectively in this repository without re-analyzing the entire codebase each time.

## Project Overview

WatchBuddy is a two-app Android/Google TV ecosystem for cross-app series tracking. It scrobbles what the user watches across streaming apps, generates AI-powered "Previously on…" recaps via a local LLM on the phone, and deep-links into the correct streaming app — all backed by Trakt and TMDB.

## Repository Structure

```
watchbuddy/
├── app-phone/          Android companion app (Kotlin, Jetpack Compose)
│   └── src/main/java/com/justb81/watchbuddy/phone/
│       ├── llm/        LlmOrchestrator, RecapGenerator (MediaPipe / AICore)
│       ├── server/     Ktor HTTP server (port 8765), DeviceCapabilityProvider
│       ├── ui/         MainActivity, Home, Onboarding, Settings screens
│       └── ui/theme/   Material 3 theme
├── app-tv/             Google TV app (Kotlin, Compose for TV)
│   └── src/main/java/com/justb81/watchbuddy/tv/
│       ├── discovery/  PhoneDiscoveryManager (NSD/mDNS client)
│       ├── scrobbler/  MediaSessionScrobbler (auto-tracking)
│       ├── ui/         TvMainActivity, Home, Recap, ShowDetail, UserSelect, ScrobbleOverlay
│       └── ui/theme/   TV Material theme
├── core/               Shared library module
│   └── src/main/java/com/justb81/watchbuddy/core/
│       ├── locale/     LocaleHelper (LLM language resolution)
│       ├── model/      Data models (Kotlin Serialization)
│       ├── network/    NetworkModule (Hilt, OkHttp, Retrofit)
│       ├── tmdb/       TmdbApiService
│       └── trakt/      TraktApiService
├── backend/            Node.js token proxy (Docker)
│   ├── src/index.js    Express server for Trakt OAuth token exchange
│   ├── Dockerfile
│   └── docker-compose.yml
├── docs/
│   └── architecture.md Detailed architecture, protocols, LLM strategy, deep links
├── .github/workflows/
│   ├── build-android.yml   CI: builds debug APKs on push/PR
│   └── release.yml         CD: release-please + signed APK builds
└── gradle/
    └── libs.versions.toml  Version catalog (single source of truth for dependencies)
```

## Tech Stack

- **Language:** Kotlin 2.0, JDK 17
- **UI:** Jetpack Compose (phone), Compose for TV (tv)
- **DI:** Hilt (Dagger)
- **Network:** Retrofit + OkHttp (API clients), Ktor (phone HTTP server)
- **Serialization:** kotlinx.serialization
- **LLM:** MediaPipe Tasks GenAI (Gemma models), AICore (Gemini Nano)
- **Storage:** Room DB, DataStore Preferences, Android Keystore
- **Background:** WorkManager (model updates)
- **Image loading:** Coil
- **Build:** Gradle with version catalog, AGP 8.4.2
- **CI/CD:** GitHub Actions, release-please (Conventional Commits)
- **Backend:** Node.js, Docker

## Key Conventions

### Code Style
- Kotlin official code style (`kotlin.code.style=official`)
- Compose UI follows single-Activity, screen-level composables
- ViewModels use `StateFlow` for UI state
- Hilt `@HiltViewModel` for all ViewModels
- Modules injected via Hilt `@Module` / `@Provides`

### Build
- `./gradlew assembleDebug` — builds both phone and TV debug APKs
- `./gradlew :app-phone:assembleDebug` — phone only
- `./gradlew :app-tv:assembleDebug` — TV only
- Secrets via `local.properties` (not checked in) or environment variables for CI

### Git Workflow — IMPORTANT

**Never push directly to `main`.** All changes must go through a Pull Request — no exceptions, including for agents.

**The complete agent workflow:**

1. Create a feature branch from `main`
2. Make changes and commit using Conventional Commits (see below)
3. Push the branch and open a PR against `main`
4. **Wait for a green CI build** (`build-android.yml`) — do not continue if the build is red; fix the issue first
5. **Stop here.** The PR is the deliverable. Do NOT merge into `main` automatically.
6. Merging into `main` is the owner's decision and happens only on explicit instruction.

> Agents that open multiple PRs without waiting for CI or without owner approval create unreviewed, potentially broken state on `main`. One PR per task, green build, then hand off.

**Branch naming:**

| Purpose | Prefix | Example |
|---------|--------|---------|
| New feature | `feature/` | `feature/add-watchlist-filter` |
| Bug fix | `fix/` | `fix/scrobble-confidence-threshold` |
| Documentation | `docs/` | `docs/update-architecture` |
| Chore / maintenance | `chore/` | `chore/upgrade-mediapipe` |
| Release (automated) | `release-please--` | `release-please--branches--main` |

The `release-please--` prefix is reserved for the automated release-please bot — never create branches with this prefix manually.

### Versioning
- release-please with Conventional Commits (`feat:`, `fix:`, `chore:`, etc.)
- Version tracked in `.release-please-manifest.json`
- `versionCode` derived from `github.run_number` in CI
- release-please opens its own PR (`release-please--branches--main`) to bump the version and update `CHANGELOG.md` — merge it to trigger a GitHub Release with signed APKs

### Localization
- Supported languages: English (default), German, French, Spanish
- String resources: `values/strings.xml` (EN), `values-de/`, `values-fr/`, `values-es/`
- Both `app-phone` and `app-tv` have independent string files
- LLM recaps adapt to device language via `LocaleHelper`

### Package Structure
- Base package: `com.justb81.watchbuddy`
- Phone: `com.justb81.watchbuddy.phone.*`
- TV: `com.justb81.watchbuddy.tv.*`
- Core: `com.justb81.watchbuddy.core.*`

## Communication Protocol

The phone acts as an NSD/mDNS server on port 8765. The TV discovers phone(s) on the local network and calls the phone's HTTP API:

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/capability` | Device info + LLM quality score |
| GET | `/shows` | User's Trakt watched shows |
| POST | `/recap/{traktShowId}` | Generate HTML recap |

The TV ranks connected phones by LLM quality and uses the best one. Failover chain: best phone → next phone → local cache → TMDB synopsis only.

## Important Patterns

- **Scrobbling:** `MediaSessionScrobbler` listens to active media sessions on the TV, extracts package name + title, fuzzy-matches against Trakt watchlist, and auto-scrobbles if confidence ≥ 90%.
- **LLM selection:** `LlmOrchestrator` checks AICore first, then falls back to MediaPipe with a Gemma model sized to available RAM.
- **Auth modes:** Managed backend (default), self-hosted proxy, or direct Trakt credentials.
- **Multi-user:** Multiple phones can connect to one TV simultaneously; shared watch mode avoids recap spoilers.

## Documentation Maintenance

When making changes to the codebase, keep the following documentation in sync:

1. **`README.md`** — Update if changes affect features, setup instructions, module structure, or supported languages.
2. **`docs/architecture.md`** — Update if changes affect the system architecture, communication protocol, LLM strategy, deep link table, secret storage, or distribution details.
3. **`CLAUDE.md`** (this file) — Update if changes affect the repository structure, tech stack, conventions, communication protocol, or any information that helps agents understand the codebase.

Do not let documentation drift from the actual implementation. When adding new modules, API endpoints, supported languages, or deep link integrations, update all three files accordingly.
