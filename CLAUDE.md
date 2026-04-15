# CLAUDE.md — WatchBuddy Agent Guide

This file provides context for AI coding agents (Claude, Copilot, Cursor, etc.) so they can work effectively in this repository without re-analyzing the entire codebase each time.

## Project Overview

WatchBuddy is a two-app Android/Google TV ecosystem for cross-app series tracking. It scrobbles what the user watches across streaming apps, generates AI-powered "Previously on…" recaps via a local LLM on the phone, and deep-links into the correct streaming app — all backed by Trakt and TMDB.

## Repository Structure

```
watchbuddy/
├── app-phone/          Android companion app (Kotlin, Jetpack Compose)
│   └── src/main/java/com/justb81/watchbuddy/phone/
│       ├── auth/       TokenRepository, AuthModule (Trakt OAuth, Keystore)
│       ├── di/         AppModule (Hilt dependency injection)
│       ├── llm/        LlmOrchestrator, RecapGenerator, LlmProviders (LiteRT-LM / AICore)
│       ├── server/     CompanionHttpServer (Ktor, port 8765), DeviceCapabilityProvider, ShowRepository
│       ├── settings/   AppSettings, SettingsRepository (DataStore)
│       ├── ui/         MainActivity, PhoneNavGraph
│       │   ├── home/       HomeScreen, HomeViewModel
│       │   ├── navigation/ PhoneNavGraph
│       │   ├── onboarding/ OnboardingScreen, OnboardingViewModel
│       │   ├── settings/   SettingsScreen, SettingsViewModel
│       │   ├── showdetail/ ShowDetailScreen, ShowDetailViewModel
│       │   └── theme/      Material 3 theme
│       └── (service/)  CompanionService (foreground NSD server)
├── app-tv/             Google TV app (Kotlin, Compose for TV)
│   └── src/main/java/com/justb81/watchbuddy/tv/
│       ├── data/       StreamingPreferencesRepository, UserSessionRepository, TvShowCache
│       ├── di/         AppModule (Hilt dependency injection)
│       ├── discovery/  PhoneDiscoveryManager, PhoneApiService, PhoneApiClientFactory
│       ├── scrobbler/  MediaSessionScrobbler
│       ├── ui/         TvMainActivity, TvNavGraph
│       │   ├── home/       TvHomeScreen, TvHomeViewModel
│       │   ├── navigation/ TvNavGraph
│       │   ├── recap/      RecapScreen, RecapViewModel
│       │   ├── scrobble/   ScrobbleOverlay, ScrobbleViewModel
│       │   ├── settings/   StreamingSettingsScreen, StreamingSettingsViewModel
│       │   ├── showdetail/ ShowDetailScreen, ShowDetailViewModel
│       │   ├── theme/      TV Material theme
│       │   └── userselect/ UserSelectScreen, UserSelectViewModel
├── core/               Shared library module
│   └── src/main/java/com/justb81/watchbuddy/core/
│       ├── locale/     LocaleHelper (LLM language resolution)
│       ├── model/      Data models (Kotlin Serialization)
│       ├── network/    NetworkModule (Hilt, OkHttp, Retrofit)
│       ├── tmdb/       TmdbApiService
│       └── trakt/      TraktApiService, TokenProxyService
├── backend/            Node.js token proxy (Docker)
│   ├── src/
│   │   ├── index.js        Express server entry point
│   │   ├── app.js          Express app setup (Trakt OAuth token exchange)
│   │   └── __tests__/      Backend tests
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── package.json
├── docs/
│   ├── architecture.md  Detailed architecture, protocols, LLM strategy, deep links
│   └── tmdb-integration.md  TMDB API usage, user journeys, connection handling
├── .github/workflows/
│   ├── build-android.yml   CI: builds debug APKs on push/PR
│   ├── release.yml         CD: release-please + signed APK builds
│   └── test-backend.yml    CI: tests for the Node.js backend
└── gradle/
    └── libs.versions.toml  Version catalog (single source of truth for dependencies)
```

## Tech Stack

- **Language:** Kotlin 2.0, JDK 17
- **UI:** Jetpack Compose (phone), Compose for TV (tv)
- **DI:** Hilt (Dagger)
- **Network:** Retrofit + OkHttp (API clients), Ktor (phone HTTP server)
- **Serialization:** kotlinx.serialization
- **LLM:** LiteRT-LM (Gemma 4 models, .litertlm format), AICore (Gemini Nano)
- **Storage:** Room DB, DataStore Preferences, Android Keystore
- **Background:** WorkManager (model updates)
- **Image loading:** Coil
- **Build:** Gradle with version catalog, AGP 8.7.0
- **CI/CD:** GitHub Actions, release-please (Conventional Commits)
- **Backend:** Node.js, Docker

## Key Conventions

### Language — MANDATORY

**All content in this repository must be written in English.** This applies to everything, without exception:

- Code: variable names, function names, class names, constants
- Comments and documentation strings
- Commit messages (Conventional Commits, in English)
- Pull request titles and descriptions
- GitHub issue titles, descriptions, and comments
- Markdown files (README, CLAUDE.md, architecture docs, CHANGELOG, etc.)
- CI/CD configuration and log messages
- Code review comments

The only exceptions are **localization string resources** (`values-de/`, `values-fr/`, `values-es/`) which contain translated user-facing strings by design. The default `values/strings.xml` must remain in English.

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
| Chore / maintenance | `chore/` | `chore/upgrade-litertlm` |
| Release (automated) | `release-please--` | `release-please--branches--main` |

The `release-please--` prefix is reserved for the automated release-please bot — never create branches with this prefix manually.

### Versioning
- release-please with Conventional Commits (`feat:`, `fix:`, `chore:`, etc.)
- Version tracked in `.release-please-manifest.json`
- `versionCode` derived from `github.run_number` in CI
- release-please opens its own PR (`release-please--branches--main`) to bump the version and update `CHANGELOG.md` — merge it to trigger a GitHub Release with signed APKs and AABs

### Distribution
- **Google Play Store:** AABs are automatically uploaded to the **internal** track on each release. Promote to production via Google Play Console.
- **GitHub Releases:** Signed APKs and AABs are attached to each GitHub Release for sideloading.
- **Multi-APK delivery:** Both apps share `applicationId = com.justb81.watchbuddy` with a multiplier-based versionCode scheme (`run_number * 10 + 1` for phone, `run_number * 10 + 2` for TV) to guarantee no collisions. The TV manifest requires `android.software.leanback` so Google Play serves the correct AAB per device type.
- **CI secrets for Play Store:** `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (Google Cloud service account key with Google Play Android Developer API access). If not set, the Play Store upload step is skipped gracefully.

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
| GET | `/capability` | Device info + LLM quality score + TMDB API key |
| GET | `/shows` | User's Trakt watched shows |
| POST | `/recap/{traktShowId}` | Generate HTML recap |
| GET | `/auth/token` | Current access token (phone server exposes; not used by TV) |
| POST | `/scrobble/start` | Forward scrobble start to this user's Trakt account |
| POST | `/scrobble/pause` | Forward scrobble pause to this user's Trakt account |
| POST | `/scrobble/stop` | Forward scrobble stop to this user's Trakt account |

**TV API boundary rules:**
- **TMDB API** is used by the TV for all show/movie data: details, images, and title search. The TMDB API key is sourced from the best connected phone's `/capability` response.
- **Phone API** is used for all user-specific operations: library (`/shows`), scrobbling (`/scrobble/*`), and recaps (`/recap/*`).
- **Trakt API** is **never called directly by the TV**. All Trakt operations are proxied through the phone's HTTP API, which uses its own stored credentials.

The TV ranks connected phones by LLM quality and uses the best one. Failover chain: best phone → next phone → local cache → TMDB synopsis only.

## Important Patterns

- **Scrobbling:** `MediaSessionScrobbler` listens to active media sessions on the TV, extracts package name + title, fuzzy-matches against the local show cache first, then falls back to TMDB title search (using the API key from the best phone's capability). Auto-scrobbles if confidence ≥ 95%, shows overlay confirmation between 70–95%, ignores below 70%. When a scrobble event occurs, `MediaSessionScrobbler` calls `POST /scrobble/{start|pause|stop}` on **every** connected phone in parallel via `PhoneDiscoveryManager` + `PhoneApiClientFactory` — each phone records the episode on its own user's Trakt account using its own stored credentials. A failure for one phone does not block the others. The TV never calls the Trakt API directly for any operation.
- **LLM selection:** `LlmOrchestrator` checks AICore first, then falls back to LiteRT-LM with a Gemma 4 model (E4B or E2B) sized to available RAM.
- **Auth modes:** Managed backend (default), self-hosted proxy, or direct Trakt credentials.
- **Multi-user:** Multiple phones can connect to one TV simultaneously; scrobbling records the episode for each connected user independently; shared watch mode avoids recap spoilers.

## Documentation Maintenance

When making changes to the codebase, keep the following documentation in sync:

1. **`README.md`** — Update if changes affect features, setup instructions, module structure, or supported languages.
2. **`docs/architecture.md`** — Update if changes affect the system architecture, communication protocol, LLM strategy, deep link table, secret storage, or distribution details.
3. **`docs/tmdb-integration.md`** — Update if changes affect TMDB API usage, endpoints, data models, image handling, deep link templates, error handling, caching, or API key management.
4. **`CLAUDE.md`** (this file) — Update if changes affect the repository structure, tech stack, conventions, communication protocol, or any information that helps agents understand the codebase.

Do not let documentation drift from the actual implementation. When adding new modules, API endpoints, supported languages, or deep link integrations, update all relevant files accordingly.
