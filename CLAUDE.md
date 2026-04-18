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
│       └── (service/)  CompanionService, CompanionStateManager (foreground NSD server + shared state)
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

## External API References

- **Trakt API:** https://github.com/trakt/trakt-api (official, contracts in `projects/api/src/contracts/`)
  - Do NOT use the outdated Apiary docs (`trakt.docs.apiary.io`) — they reference deprecated endpoints.
- **TMDB API:** https://developer.themoviedb.org/docs

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
5. **Auto-merge when green.** Once every required build step on the PR has completed successfully, the agent may merge the PR into `main` automatically (e.g. via `enable_pr_auto_merge` or by merging directly after CI passes). Use a squash merge and delete the branch after merge.
6. If CI fails or a required check is still pending, do NOT merge — fix the failure or wait.

> One PR per task. Never merge with red or missing required checks. If a reviewer has requested changes, wait for their approval before merging even if CI is green.

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
- **GitHub Releases:** Signed APKs, AABs, and per-module `native-debug-symbols.zip` files are attached to each GitHub Release for sideloading and crash triage.
- **Multi-APK delivery:** Both apps share `applicationId = com.justb81.watchbuddy` with a multiplier-based versionCode scheme (`run_number * 10 + 1` for phone, `run_number * 10 + 2` for TV) to guarantee no collisions. The TV manifest requires `android.software.leanback` so Google Play serves the correct AAB per device type.
- **Native debug symbols:** Release AABs use `debugSymbolLevel = "FULL"`. AGP-emitted `native-debug-symbols.zip` is uploaded to Play per AAB via the Play upload action's `debugSymbols:` input so native crashes/ANRs are symbolicated (#262).
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

**NSD TXT record contract:** The phone advertises three attributes — `version` (the phone app's `BuildConfig.VERSION_NAME`, e.g. `0.15.1`, **not** a protocol version), `modelQuality` (integer 0–150), and `llmBackend` (one of the `LlmBackend` enum names). The TV parses `llmBackend` leniently (unknown values fall back to `LlmBackend.NONE`) but treats missing / unparseable `version` or `modelQuality` as a hard failure. Contract is pinned by `CompanionServiceNsdTest` and `PhoneDiscoveryManagerTest`.

## Important Patterns

- **Watching TV toggle:** The phone HomeScreen shows an "I am watching TV" toggle, gated by Trakt + TMDB availability **and** Wi-Fi connectivity. Toggling starts/stops the `CompanionService`. When the user swipes the app from recents, the service auto-stops via `onTaskRemoved()`. Off-Wi-Fi the toggle is disabled with a "connect to Wi-Fi" reason; a running companion self-stops when Wi-Fi is lost (after a 3 s grace period for SSID handoffs) and clears `companionEnabled` so the FG notification is dismissed (#278). Wi-Fi state is tracked by `phone/network/WifiStateProvider` and consulted both by `HomeViewModel` and defensively by `CompanionService.onStartCommand`.
- **CompanionStateManager:** Hilt singleton (`service/CompanionStateManager.kt`) that is the shared state hub between `CompanionService`, `CompanionHttpServer`, and `HomeViewModel`. Tracks `lastCapabilityCheck`, `lastScrobbleEvent`, and `isServiceRunning`.
- **Presence heartbeat (TV):** `PhoneDiscoveryManager` runs a heartbeat every 60s, re-fetching `/capability` for each phone. 3 consecutive failures → phone removed from discovered list. `MediaSessionScrobbler` skips phones with stale presence (> 2 min) before scrobbling. A `WifiManager.MulticastLock` is held while discovery is active (required on most Android TV ROMs for mDNS to receive any packets), and discovery self-heals on `FAILURE_ALREADY_ACTIVE`, Wi-Fi reconnect, and an empty-list heartbeat tick.
- **Presence timeout (phone):** If no TV polls `/capability` for 5 minutes, the companion service auto-deactivates.
- **Auto-reconnect:** `CompanionService` registers a `ConnectivityManager.NetworkCallback` for Wi-Fi. On network loss, NSD is unregistered; on network available, `onAvailable` is debounced (2 s) and NSD is torn down then re-registered 300 ms later — `NsdManager.unregisterService` is async and calling `registerService` before its callback runs leaves ghost advertisements on the network (#264). All register/unregister transitions run through an `IDLE → REGISTERING → REGISTERED → UNREGISTERING` state machine under a single lock so concurrent callers (`onStartCommand` + network callback) can't race past the guard while a prior registration is still in flight. `onStartCommand` is also idempotent against duplicate starts.
- **NSD advertising (phone):** `CompanionService` holds a `WifiManager.MulticastLock` for its whole lifetime (OEM skins filter outgoing multicast packets without it), pins `NsdServiceInfo.host` to the Wi-Fi IPv4 address for multi-homed devices, and `CompanionHttpServer` binds Netty explicitly to `0.0.0.0` (#265). Cross-device discovery additionally requires the Wi-Fi AP to allow peer-to-peer traffic (client isolation off) — no code-level fix helps there.
- **Scrobble display:** When a scrobble event is received on the phone, `CompanionStateManager.lastScrobbleEvent` is updated. The phone HomeScreen shows a "Now Watching" card with show/episode details, auto-hidden after 30 minutes.
- **Scrobbling:** `MediaSessionScrobbler` listens to active media sessions on the TV, extracts package name + title, fuzzy-matches against the local show cache first, then falls back to TMDB title search (using the API key from the best phone's capability). Auto-scrobbles if confidence ≥ 95%, shows overlay confirmation between 70–95%, ignores below 70%. When a scrobble event occurs, `MediaSessionScrobbler` calls `POST /scrobble/{start|pause|stop}` on **every** connected phone in parallel via `PhoneDiscoveryManager` + `PhoneApiClientFactory` — each phone records the episode on its own user's Trakt account using its own stored credentials. A failure for one phone does not block the others. The TV never calls the Trakt API directly for any operation. Progress is derived from `PlaybackState.position` and `MediaMetadata.METADATA_KEY_DURATION`; if unavailable, start/pause fall back to 0/50 and stop is skipped to avoid Trakt marking partially-watched episodes as watched (Trakt treats `progress >= 80` on `/scrobble/stop` as watched).
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
