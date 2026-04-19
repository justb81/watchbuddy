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
│       ├── server/     CompanionHttpServer (Ktor, port 8765), DeviceCapabilityProvider, ShowRepository (reactive `shows` StateFlow), EpisodeRepository (10-min per-show TTL + sync/history writes)
│       ├── settings/   AppSettings, SettingsRepository (DataStore), AvatarImageStore (custom-photo JPEG)
│       ├── ui/         MainActivity, PhoneNavGraph
│       │   ├── diagnostics/ DiagnosticsScreen, DiagnosticsViewModel (Wi-Fi / NSD / HTTP / BLE live health + Share diagnostics)
│       │   ├── home/       HomeScreen, HomeViewModel
│       │   ├── navigation/ PhoneNavGraph
│       │   ├── onboarding/ OnboardingScreen, OnboardingViewModel
│       │   ├── settings/   SettingsScreen, SettingsViewModel
│       │   ├── showdetail/ ShowDetailScreen, ShowDetailViewModel (current-season-first layout; per-episode watched/unwatched checkbox)
│       │   └── theme/      Material 3 theme
│       └── (service/)  CompanionService, CompanionStateManager (foreground NSD server + shared state)
├── app-tv/             Google TV app (Kotlin, Compose for TV)
│   └── src/main/java/com/justb81/watchbuddy/tv/
│       ├── boot/       BootReceiver (starts TvDiscoveryService on BOOT_COMPLETED when autostart is enabled)
│       ├── data/       StreamingPreferencesRepository (+ phone-discovery / autostart toggles), TvShowCache
│       ├── di/         AppModule (Hilt dependency injection)
│       ├── discovery/  PhoneDiscoveryManager, PhoneApiService, PhoneApiClientFactory, TvDiscoveryService (foreground service — keeps discovery alive post-boot)
│       ├── scrobbler/  TvScrobbleDispatcher, TvWatchedShowSource
│       ├── ui/         TvMainActivity, TvNavGraph
│       │   ├── components/ InitialsAvatar
│       │   ├── home/       TvHomeScreen, TvHomeViewModel (active viewers derived from discovered phones)
│       │   ├── navigation/ TvNavGraph
│       │   ├── recap/      RecapScreen, RecapViewModel
│       │   ├── diagnostics/ TvDiagnosticsScreen, TvDiagnosticsViewModel (discovery / BLE / discovered-phones health — view-only, no Share)
│       │   ├── scrobble/   ScrobbleOverlay, ScrobbleViewModel
│       │   ├── settings/   TvSettingsScreen + TvSettingsViewModel (generic settings hub), StreamingSettingsScreen + StreamingSettingsViewModel (nested)
│       │   ├── showdetail/ ShowDetailScreen, ShowDetailViewModel
│       │   └── theme/      TV Material theme
├── core/               Shared library module
│   └── src/main/java/com/justb81/watchbuddy/core/
│       ├── locale/     LocaleHelper (LLM language resolution)
│       ├── logging/    CrashReporter, DiagnosticLog, DiagnosticShare
│       ├── model/      Data models (Kotlin Serialization)
│       ├── network/    NetworkModule (Hilt, OkHttp, Retrofit), SharedJson (WatchBuddyJson shared instance)
│       ├── progress/   ShowProgressCalculator
│       ├── scrobbler/  MediaSessionScrobbler, ScrobbleContracts
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
├── .github/
│   ├── actions/
│   │   └── setup-android-build/
│   │       └── action.yml  Composite action: checkout + JDK 17 + Gradle setup (shared by build-android.yml and release.yml)
│   └── workflows/
│       ├── build-android.yml   CI: builds debug APKs on push/PR
│       ├── release.yml         CD: release-please + signed APK builds
│       └── test-backend.yml    CI: tests for the Node.js backend
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

1. **Check for concurrent agent work — MANDATORY first step.** Before doing anything else, verify that no other Claude Code session is already working on the same issue:
   - Identify the target issue number from the triggering context (initial prompt, linked issue, or branch-name hint).
   - Call `mcp__github__list_pull_requests` with `state: "open"` on `justb81/watchbuddy` and scan each PR's title **and** body for a closing keyword referencing the target issue: `Closes #N`, `Fixes #N`, `Resolves #N`, `Closes GH-N`, `Fixes GH-N`, `Resolves GH-N` (case-insensitive).
   - If any open PR matches, post a single comment on the issue via `mcp__github__add_issue_comment` — e.g. *"Another Claude Code session is already working on this issue — see #\<PR-number\>. Aborting this session to avoid parallel work."* — and stop immediately. Do not create a branch, do not edit files, do not commit.
   - Exception: skip this check when the session is explicitly invoked to continue work on a specific existing PR (e.g. responding to review comments on that PR).
2. Create a feature branch from `main`
3. Make changes and commit using Conventional Commits (see below)
4. Push the branch and open a PR against `main`
5. **Wait for a green CI build** (`build-android.yml`) — do not continue if the build is red; fix the issue first
6. **Auto-merge when green.** Once every required build step on the PR has completed successfully, the agent may merge the PR into `main` automatically (e.g. via `enable_pr_auto_merge` or by merging directly after CI passes). Use a squash merge and delete the branch after merge.
7. If CI fails or a required check is still pending, do NOT merge — fix the failure or wait.

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
- **Google Play Store:** AABs are automatically uploaded to the **internal** track on each release via [Gradle Play Publisher (GPP)](https://github.com/Triple-T/gradle-play-publisher) configured in `app-phone/build.gradle.kts`. Promote to production via Google Play Console.
- **GitHub Releases:** Signed APKs, AABs, per-module `native-debug-symbols.zip` files, and per-module `mapping.txt` files are attached to each GitHub Release for sideloading and crash triage.
- **Multi-APK delivery:** Both apps share `applicationId = com.justb81.watchbuddy` with a multiplier-based versionCode scheme (`run_number * 10 + 1` for phone, `run_number * 10 + 2` for TV) to guarantee no collisions. The TV manifest requires `android.software.leanback` so Google Play serves the correct AAB per device type.
- **Atomic multi-AAB upload:** Phone + TV AABs are staged into a top-level `play-artifacts/` directory in CI; GPP's `artifactDir` mode uploads both AABs in one atomic Play edit. Running `./gradlew :app-phone:publishReleaseBundle` on CI publishes the whole release.
- **Native debug symbols:** Release AABs use `debugSymbolLevel = "FULL"`, so AGP embeds per-AAB symbols under `BUNDLE-METADATA` and Play auto-associates them for native crash/ANR symbolication (#262). Per-module `native-debug-symbols.zip` files are still attached to the GitHub Release for manual symbolication.
- **R8 mapping (deobfuscation) files:** Both apps enable `isMinifyEnabled = true`. AGP embeds `mapping.txt` inside each AAB so Play Console can de-obfuscate stack traces per versionCode (#273). Per-module `mapping.txt` files are also attached to the GitHub Release as `watchbuddy-{phone,tv}-<version>-mapping.txt` for manual triage.
- **Release-notes source:** GPP reads Play Store "What's new" text from `app-phone/src/main/play/release-notes/<locale>/default.txt`. CI generates these files per release from the release-please body across `en-US`, `de-DE`, `fr-FR`, `es-ES`.
- **CI secrets for Play Store:** `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (Google Cloud service account key with Google Play Android Developer API access). The workflow writes this to `/tmp/gpp-sa.json` and points `GOOGLE_PLAY_SERVICE_ACCOUNT_FILE` at it, which the `play { }` block consumes. If the secret is unset, the Play Store upload step is skipped gracefully.

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

The phone is an NSD/mDNS server on port 8765; the TV discovers it via NSD and a parallel BLE fallback channel (`CompanionBleAdvertiser` / `PhoneBleScanner`). Both channels converge on the same capability-fetch pipeline, deduped by `baseUrl`. The TV ranks phones by `modelQuality` and proxies all Trakt operations through the phone — the TV never calls Trakt directly.

For the authoritative HTTP API table, NSD TXT-record contract (`version`, `modelQuality`, `llmBackend`), NSD registration state machine, and presence/heartbeat thresholds, see [`docs/architecture.md` § Communication Protocol](docs/architecture.md#communication-protocol-tv--phone).

**Agent note:** The NSD TXT-record contract is pinned by `CompanionServiceNsdTest` and `PhoneDiscoveryManagerTest` — update those tests whenever the contract changes.

## Important Patterns

- **Watching TV toggle:** The phone HomeScreen shows an "I am watching TV" toggle, gated by Trakt + TMDB availability **and** Wi-Fi connectivity. Toggling starts/stops the `CompanionService`. When the user swipes the app from recents, the service auto-stops via `onTaskRemoved()`. Off-Wi-Fi the toggle is disabled with a "connect to Wi-Fi" reason; a running companion self-stops when Wi-Fi is lost (after a 3 s grace period for SSID handoffs) and clears `companionEnabled` so the FG notification is dismissed (#278). Wi-Fi state is tracked by `phone/network/WifiStateProvider` and consulted both by `HomeViewModel` and defensively by `CompanionService.onStartCommand`.
- **CompanionStateManager:** Hilt singleton (`service/CompanionStateManager.kt`) that is the shared state hub between `CompanionService`, `CompanionHttpServer`, and `HomeViewModel`. Tracks `lastCapabilityCheck`, `lastScrobbleEvent`, and `isServiceRunning`.
- **TV discovery lifecycle & boot autostart (#344):** Phone discovery on the TV is controlled by a user-facing toggle in `TvSettingsScreen` and persisted in `StreamingPreferencesRepository` (`isPhoneDiscoveryEnabled`, default `true`). `TvHomeViewModel.init` observes the flow and calls `PhoneDiscoveryManager.setEnabled(...)` — discovery is no longer tied to the activity's lifecycle, so it can outlive `TvMainActivity` recreation. A second toggle (`isAutostartEnabled`, default `false`) controls whether `BootReceiver` starts `TvDiscoveryService` on `BOOT_COMPLETED`. The foreground service (`dataSync` FGS type, low-importance notification channel `watchbuddy_tv_discovery`) owns discovery in the background and self-stops when the discovery toggle flips off. `BootReceiver` is `exported=true` (required for system broadcasts) and uses `EntryPointAccessors` to reach the Hilt singleton — it is not `@AndroidEntryPoint`. Permissions: `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`.
- **Presence heartbeat (TV):** `PhoneDiscoveryManager` runs a heartbeat every 60s, re-fetching `/capability` for each phone. 3 consecutive failures → phone removed from discovered list. `MediaSessionScrobbler` skips phones with stale presence (> 2 min) before scrobbling. A `WifiManager.MulticastLock` is held while discovery is active (required on most Android TV ROMs for mDNS to receive any packets), and discovery self-heals on `FAILURE_ALREADY_ACTIVE`, Wi-Fi reconnect, and an empty-list heartbeat tick. Once any phone has 3 consecutive heartbeat successes, the TV stops the BLE scanner to save radio (a Wi-Fi route is enough); any heartbeat miss resumes it, with a 2-min hysteresis guarding the stop transition (#345 Opt B).
- **Presence timeout (phone):** If no TV polls `/capability` for 5 minutes, the companion service auto-deactivates.
- **Auto-reconnect:** `CompanionService` registers a `ConnectivityManager.NetworkCallback` for Wi-Fi. On network loss, NSD is unregistered; on network available, `onAvailable` is debounced (2 s) and NSD is torn down then re-registered 300 ms later — `NsdManager.unregisterService` is async and calling `registerService` before its callback runs leaves ghost advertisements on the network (#264). All register/unregister transitions run through an `IDLE → REGISTERING → REGISTERED → UNREGISTERING` state machine under a single lock so concurrent callers (`onStartCommand` + network callback) can't race past the guard while a prior registration is still in flight. `onStartCommand` is also idempotent against duplicate starts. On Wi-Fi re-announces where the IPv4 hasn't changed the full unregister/register dance is skipped — `onAvailable` can fire repeatedly during captive-portal / DNS retries without any real handoff, and each needless churn briefly yanks the advertisement off the network (#345 Opt D).
- **NSD advertising (phone):** `CompanionService` holds a `WifiManager.MulticastLock` for its whole lifetime (OEM skins filter outgoing multicast packets without it), pins `NsdServiceInfo.host` to the Wi-Fi IPv4 address for multi-homed devices, and `CompanionHttpServer` binds Netty explicitly to `0.0.0.0` (#265). Cross-device discovery additionally requires the Wi-Fi AP to allow peer-to-peer traffic (client isolation off) — no code-level fix helps there.
- **BLE fallback discovery:** When mDNS cannot cross the Wi-Fi (AP/client isolation, VLAN-segmented mesh, multicast filtering) `CompanionBleAdvertiser` broadcasts a 9-byte service-data payload (IPv4, port, `modelQuality`, `llmBackend` ordinal) under a custom UUID defined in `core/discovery/BleDiscoveryContract.kt`. The advertisement carries only a Service Data 128-bit AD field (no separate Complete-List-of-UUIDs AD) to stay under the 31-byte legacy envelope — Android 16 / Nothing reject the old 48-byte payload with `DATA_TOO_LARGE` (#345). `PhoneBleScanner` on the TV filters with `setServiceData(uuid, schemaVersion, 0xFF mask)` so it matches the service-data AD and also rejects adverts from a future incompatible schema. Matches are fed into the existing `fetchCapabilityAndAdd` path, dedup'd by `baseUrl`. Once a TV has been steadily polling `/capability` for 3+ consecutive heartbeats, the phone demotes the advertiser from `ADVERTISE_MODE_BALANCED` (~250 ms) to `ADVERTISE_MODE_LOW_POWER` (~1 s) and the TV stops its BLE scanner; any heartbeat miss reverts both sides, guarded by a 2-min hysteresis (#345 Opt B). Bluetooth-off / permission-denied / BLE-unsupported hardware all degrade silently to NSD-only. Permissions: `BLUETOOTH_ADVERTISE` (phone) is requested from HomeScreen when the "I am watching TV" toggle flips on, and `BLUETOOTH_SCAN` (`neverForLocation`) is requested by `TvMainActivity.onCreate`.
- **Scrobble display:** When a scrobble event is received on the phone, `CompanionStateManager.lastScrobbleEvent` is updated. The phone HomeScreen shows a "Now Watching" card with show/episode details, auto-hidden after 30 minutes.
- **Scrobbling:** `MediaSessionScrobbler` (core, consumed by `TvScrobbleDispatcher`) listens to active media sessions on the TV, extracts package name + title, fuzzy-matches against the local show cache first, then falls back to TMDB title search (using the API key from the best phone's capability). Auto-scrobbles if confidence ≥ 95%, shows overlay confirmation between 70–95%, ignores below 70%. When a scrobble event occurs, `TvScrobbleDispatcher` calls `POST /scrobble/{start|pause|stop}` on **every** connected phone in parallel via `PhoneDiscoveryManager` + `PhoneApiClientFactory` — each phone records the episode on its own user's Trakt account using its own stored credentials. A failure for one phone does not block the others. The TV never calls the Trakt API directly for any operation. Progress is derived from `PlaybackState.position` and `MediaMetadata.METADATA_KEY_DURATION`; if unavailable, start/pause fall back to 0/50 and stop is skipped to avoid Trakt marking partially-watched episodes as watched (Trakt treats `progress >= 80` on `/scrobble/stop` as watched).
- **LLM selection:** `LlmOrchestrator` checks AICore first, then falls back to LiteRT-LM with a Gemma 4 model (E4B or E2B) sized to available RAM.
- **Auth modes:** Managed backend (default), self-hosted proxy, or direct Trakt credentials.
- **Multi-user:** Multiple phones can connect to one TV simultaneously; scrobbling records the episode for each connected user independently; shared watch mode avoids recap spoilers. The TV has no manual "who is watching" picker — active viewers are derived directly from `PhoneDiscoveryManager.discoveredPhones` and rendered as a read-only chip strip on `TvHomeScreen` (#353).
- **Identity overrides (phone, #342):** The phone's Settings → Identity section lets the user override their Trakt display name and pick the avatar source — `TRAKT` (default, uses Trakt CDN URL), `GENERATED` (deterministic initials via `InitialsAvatar`), or `CUSTOM` (user-picked photo via the Android Photo Picker, decoded + downscaled to 256×256 JPEG by `AvatarImageStore`, served over the LAN by `GET /avatar` with an ETag keyed on `customAvatarVersion`). Both fields live in DataStore and are projected into `/capability` by `DeviceCapabilityProvider`; the `/capability` JSON grows an `avatarSource` enum.
- **Manual episode marking (phone):** Tapping a show on HomeScreen opens `ShowDetailScreen`, which fetches the full season/episode structure via `EpisodeRepository.getSeasonsWithEpisodes` (Trakt `shows/:id/seasons?extended=episodes`, 10-min per-show cache). Each episode has a checkbox; toggling calls `sync/history` add or remove through `EpisodeRepository`, optimistically flips the UI, and on success calls `ShowRepository.updateLocalWatched(...)`. That mutates the in-memory `shows` `StateFlow` so `HomeViewModel` counters update without a round-trip (#216). The layout pulls the season the user is currently mid-watching to the top, expanded; all other seasons appear below, collapsed.
- **Diagnostics view:** Settings → Diagnostics on both apps renders live phone↔TV connection health from the existing shared singletons (`CompanionStateManager` on phone, `PhoneDiscoveryManager` on TV) — Wi-Fi / multicast lock / NSD state / HTTP bind / BLE state on the phone; discovery active / heartbeat age / BLE scan state / per-phone score + failCount on the TV (#331). Status dots are color-coded (green/yellow/red) so users can tell "AP isolation" (no phones at all) apart from "`/capability` 500" (discovered but broken). On the **phone only**, a "Share diagnostics" button funnels through `DiagnosticShare.launchShare()` so the `DiagnosticLog` breadcrumb snapshot + any pending crash reports can be exported via the system share sheet (#338). The TV screen is view-only — TV share was removed because the Android TV system share sheet is effectively unusable with a D-pad. For the snapshot to be actionable, connectivity subsystems on the phone (`CompanionService`, NSD state machine, `CompanionHttpServer` request log, `CompanionBleAdvertiser`, `WifiStateProvider`, `HomeViewModel` toggle) emit `DiagnosticLog.event(...)` breadcrumbs — without those the ring only carries auth/settings/app-lifecycle traces. Available in release builds; no new build variant.

## Documentation Maintenance

When making changes to the codebase, keep the following documentation in sync:

1. **`README.md`** — Update if changes affect features, setup instructions, module structure, or supported languages.
2. **`docs/architecture.md`** — Update if changes affect the system architecture, communication protocol, LLM strategy, deep link table, secret storage, or distribution details.
3. **`docs/tmdb-integration.md`** — Update if changes affect TMDB API usage, endpoints, data models, image handling, deep link templates, error handling, caching, or API key management.
4. **`CLAUDE.md`** (this file) — Update if changes affect the repository structure, tech stack, conventions, communication protocol, or any information that helps agents understand the codebase.

Do not let documentation drift from the actual implementation. When adding new modules, API endpoints, supported languages, or deep link integrations, update all relevant files accordingly.
