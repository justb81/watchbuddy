# CLAUDE.md — WatchBuddy Agent Guide

This file provides context for AI coding agents (Claude, Copilot, Cursor, etc.) so they can work effectively in this repository without re-analyzing the entire codebase each time.

## Project Overview

WatchBuddy is a two-app Android/Google TV ecosystem for cross-app series tracking. It scrobbles what the user watches across streaming apps, generates AI-powered "Previously on…" recaps via a local LLM on the phone, and deep-links into the correct streaming app — all backed by Trakt and TMDB.

## Repository Structure

```
watchbuddy/
├── app-phone/          Android companion app (Kotlin, Jetpack Compose)
│   └── src/main/java/com/justb81/watchbuddy/
│       ├── phone/
│       │   ├── auth/        TokenRepository, AuthModule (Trakt OAuth, Keystore)
│       │   ├── di/          AppModule (Hilt dependency injection)
│       │   ├── llm/         LlmOrchestrator, RecapGenerator, LlmTitleExtractor, LlmProviders (LiteRT-LM / AICore)
│       │   ├── network/     WifiStateProvider (Wi-Fi connectivity state for HomeViewModel and CompanionService)
│       │   ├── permissions/ Runtime-permission helpers (BLE advertise, notifications)
│       │   ├── server/      CompanionHttpServer (Ktor, port 8765), DeviceCapabilityProvider, ShowRepository (reactive `shows` StateFlow), EpisodeRepository (10-min per-show TTL + sync/history writes)
│       │   ├── settings/    AppSettings, SettingsRepository (DataStore), AvatarImageStore (custom-photo JPEG)
│       │   └── ui/          MainActivity, PhoneNavGraph
│       │       ├── diagnostics/ DiagnosticsScreen, DiagnosticsViewModel (Wi-Fi / HTTP / BLE live health + Share diagnostics)
│       │       ├── home/       HomeScreen, HomeViewModel
│       │       ├── navigation/ PhoneNavGraph
│       │       ├── onboarding/ OnboardingScreen, OnboardingViewModel
│       │       ├── settings/   SettingsScreen, SettingsViewModel
│       │       ├── showdetail/ ShowDetailScreen, ShowDetailViewModel (current-season-first layout; per-episode watched/unwatched checkbox)
│       │       ├── theme/      Material 3 theme
│       │       └── util/       RelativeDateFormatter (relativeTime / relativeDate — "today/yesterday/tomorrow / 2–7 d relative / >7 d absolute date")
│       └── service/    CompanionService, CompanionStateManager, CompanionBleAdvertiser (foreground BLE advertiser + HTTP server + shared state — sibling package, not under phone/)
├── app-tv/             Google TV app (Kotlin, Compose for TV)
│   └── src/main/java/com/justb81/watchbuddy/tv/
│       ├── boot/       BootReceiver (starts TvDiscoveryService on BOOT_COMPLETED when autostart is enabled)
│       ├── data/       StreamingPreferencesRepository (phone-discovery / autostart / showNonInstalledProviders), TvShowCache, WatchProvidersRepository (TMDB watch providers, 24 h cache), LastUsedProviderRepository (TV-local per-show last-used provider)
│       ├── di/         AppModule (Hilt dependency injection)
│       ├── discovery/  PhoneDiscoveryManager, PhoneApiService, PhoneApiClientFactory, PhoneTitleExtractionClient (TitleExtractor → best phone's /scrobble/extract), TvDiscoveryService (foreground service — keeps discovery alive post-boot), InstalledAppsProbe (PackageManager cache, invalidated on install/remove)
│       ├── scrobbler/  TvScrobbleDispatcher, TvWatchedShowSource
│       ├── ui/         TvMainActivity, TvNavGraph
│       │   ├── components/ InitialsAvatar
│       │   ├── home/       TvHomeScreen, TvHomeViewModel (active viewers derived from discovered phones)
│       │   ├── navigation/ TvNavGraph
│       │   ├── recap/      RecapScreen, RecapViewModel
│       │   ├── diagnostics/ TvDiagnosticsScreen, TvDiagnosticsViewModel (discovery / BLE / discovered-phones health — view-only, no Share)
│       │   ├── scrobble/   ScrobbleOverlay, ScrobbleViewModel
│       │   ├── settings/   TvSettingsScreen + TvSettingsViewModel (settings hub — discovery, autostart, show-non-installed toggle, diagnostics)
│       │   ├── showdetail/ ShowDetailScreen, ShowDetailViewModel (next-episode still + TMDB watch providers + installed-app filter + last-used ranking; `NextEpisodeUiState`, `ProviderListUiState` flows)
│       │   └── theme/      TV Material theme
├── core/               Shared library module
│   └── src/main/java/com/justb81/watchbuddy/core/
│       ├── deeplink/   ProviderCatalog (TMDB provider_id → packageName + deep-link template; central source of truth)
│       ├── locale/     LocaleHelper (LLM language resolution)
│       ├── logging/    CrashReporter, DiagnosticLog, DiagnosticShare
│       ├── model/      Data models (Kotlin Serialization)
│       ├── network/    NetworkModule (Hilt, OkHttp, Retrofit), SharedJson (WatchBuddyJson shared instance)
│       ├── progress/   ShowProgressCalculator
│       ├── scrobbler/  MediaSessionScrobbler, ScrobbleContracts
│       ├── tmdb/       TmdbApiService (+ `getWatchProviders` endpoint + `TmdbImageHelper.logo`)
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
│       ├── stats.yml           CI: refreshes the README "Code Statistics" table after a green main build
│       └── test-backend.yml    CI: tests for the Node.js backend
├── scripts/
│   ├── precommit.sh            Scoped local mirror of CI checks (see "Local pre-commit checks")
│   └── update-readme-stats.sh  Regenerates the README "Code Statistics" block (cloc + JUnit + Jest)
└── gradle/
    └── libs.versions.toml  Version catalog (single source of truth for dependencies)
```

## Tech Stack

- **Language:** Kotlin 2.1, JDK 17
- **UI:** Jetpack Compose (phone), Compose for TV (tv)
- **DI:** Hilt (Dagger)
- **Network:** Retrofit + OkHttp (API clients), Ktor (phone HTTP server)
- **Serialization:** kotlinx.serialization
- **LLM:** LiteRT-LM (Gemma 4 models, .litertlm format), AICore (Gemini Nano)
- **Storage:** Room DB, DataStore Preferences, Android Keystore
- **Background:** WorkManager (model updates)
- **Image loading:** Coil
- **Build:** Gradle with version catalog, AGP 9.0
- **CI/CD:** GitHub Actions, release-please (Conventional Commits)
- **Backend:** Node.js ≥ 22, Docker

Single source of truth for all versions: `gradle/libs.versions.toml` (Android) and `backend/package.json` (Node.js).

## External API References

- **Trakt API:** https://github.com/trakt/trakt-api (official, contracts in `projects/api/src/contracts/`)
  - Do NOT use the outdated Apiary docs (`trakt.docs.apiary.io`) — they reference deprecated endpoints.
- **TMDB API:** https://developer.themoviedb.org/docs

## GitHub Tools — `gh` CLI vs MCP

The `gh` CLI tool (GitHub CLI, version 2.45.0+) is available in this environment and is the **primary interface for GitHub operations** — it is more efficient and direct than the MCP abstraction layer. Use `gh` for:
- Checking for concurrent agent work (`gh pr list --state open`)
- Creating and updating PRs (`gh pr create`, `gh pr edit`)
- Posting comments (`gh pr comment`, `gh issue comment`)
- Monitoring CI status (`gh pr checks`, `gh run view`)

The GitHub MCP server tools (prefixed with `mcp__github__`) remain available as a fallback when `gh` output doesn't provide the needed information or when MCP-specific features are required (e.g., subscribing to PR activity webhooks).

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

### Static analysis
- `./gradlew detektAll` — runs detekt (Kotlin static analysis) on every module. Config: `config/detekt/detekt.yml`. Baselines: `<module>/detekt-baseline.xml`.
- `./gradlew detektBaselineAll` — regenerates detekt baselines (only when you intentionally want to accept new findings; the goal is normally to **fix** them).
- `./gradlew :app-phone:lintDebug :app-tv:lintDebug` — Android Lint. Baselines: `<app>/lint-baseline.xml`.
- `./gradlew :app-phone:updateLintBaseline :app-tv:updateLintBaseline` — regenerates Android Lint baselines.
- Backend: `npm --prefix backend run lint && npm --prefix backend run format:check` (ESLint 9 flat config + Prettier 3).
- CI gates every PR: any **new** finding beyond the baselines fails the `Build Android APKs` or `Backend Tests` workflow. SARIF reports are uploaded to GitHub code scanning and a summary comment with the per-module finding counts is posted on each PR (`<!-- watchbuddy-lint-report -->`).

### Local pre-commit checks

`scripts/precommit.sh` runs the same test / detekt / Android Lint / backend-lint / workflow-YAML checks CI runs, scoped to whatever is currently staged (mirrors the `paths-filter` in `build-android.yml` and `test-backend.yml`). Both human contributors and agents MUST run it before every commit — CI's path filter means a workflow-only or docs-only PR is never actually exercised, so bugs that don't touch Kotlin / Gradle / backend files land on main unchecked. The script is the only place that catches those.

**Enable the shared git hook once per clone** (agents should do this at session start if it isn't already set):

```sh
git config core.hooksPath .githooks
```

Once enabled, `.githooks/pre-commit` delegates to `scripts/precommit.sh` on every `git commit`. Without the hook, run the script manually: `./scripts/precommit.sh`. Either path fails the commit if any check fails. Do **not** pass `--no-verify` to bypass — follow the guidance in "Executing actions with care" and fix the underlying failure.

**Scoping rules** (must match the CI `paths-filter` sets exactly):

- `app-phone/**`, `app-tv/**`, `core/**`, `*.gradle.kts`, `gradle/**`, `gradle.properties`, `config/detekt/**`, `.github/actions/**` → `./gradlew test detektAll :app-phone:lintDebug :app-tv:lintDebug`
- `backend/**` → `npm --prefix backend run lint && npm --prefix backend run format:check && npm --prefix backend test`
- `.github/workflows/*.y?ml` → `python3 yaml.safe_load` on each changed file + `actionlint` when installed

If you change either workflow's `paths-filter`, update `scripts/precommit.sh` in the same commit.

**Sandboxed environments without the Android SDK** (Claude Code on the web, ephemeral runners): `scripts/precommit.sh` detects a missing SDK (`ANDROID_HOME` / `ANDROID_SDK_ROOT` unset and no `sdk.dir=` in `local.properties`) and **skips only the Gradle scope** with a loud yellow warning, while still running backend and workflow-YAML checks. The commit is allowed to proceed and CI (`build-android.yml`) becomes the real gate for Kotlin/Android changes. Agents in this situation MUST:

1. Run `./scripts/precommit.sh` (or let the hook run it); do not reach for `--no-verify`.
2. Surface the skip notice in the PR description so reviewers know the Gradle scope wasn't exercised locally.
3. Treat a red `Test & Build` check on the PR as a blocker — there's no local pre-flight to fall back on.

### Git Workflow — IMPORTANT

**Never push directly to `main`.** All changes must go through a Pull Request — no exceptions, including for agents.

**The complete agent workflow:**

1. **Check for concurrent agent work — MANDATORY first step.** Before doing anything else, verify that no other Claude Code session is already working on the same issue:
   - Identify the target issue number from the triggering context (initial prompt, linked issue, or branch-name hint).
   - Run `gh pr list --state open --repo justb81/watchbuddy` and scan each PR's title and body for a closing keyword referencing the target issue: `Closes #N`, `Fixes #N`, `Resolves #N`, `Closes GH-N`, `Fixes GH-N`, `Resolves GH-N` (case-insensitive).
   - If any open PR matches, post a comment with `gh issue comment <issue-number> --body "Another Claude Code session is already working on this issue — see #<PR-number>. Aborting this session to avoid parallel work."` and stop immediately. Do not create a branch, do not edit files, do not commit.
   - Exception: skip this check when the session is explicitly invoked to continue work on a specific existing PR (e.g. responding to review comments on that PR).
2. Create a feature branch from `main`
3. Make changes. Before **every** commit run `./scripts/precommit.sh` (or enable `git config core.hooksPath .githooks` once so the shared hook runs automatically) — see "Local pre-commit checks" above. Commit using Conventional Commits (see below)
4. Push the branch and open a PR with `gh pr create --title "..." --body "..." --repo justb81/watchbuddy` (or use the interactive `gh pr create` with no args)
5. **Wait for a green CI build** (`build-android.yml`) — do not continue if the build is red; fix the issue first (see "Monitoring PR checks & accessing build logs" below)
6. **Auto-merge when green.** Once every required build step on the PR has completed successfully, the agent may merge the PR into `main` automatically with `gh pr merge <pr-number> --squash --delete-branch --repo justb81/watchbuddy`. Use a squash merge and delete the branch after merge.
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

### Monitoring PR checks & accessing build logs

**1. Check overall CI status with `gh`:**

- `gh pr checks <pr-number> --repo justb81/watchbuddy` → per-job `status` / `conclusion`. Expect `Test & Build` (from `build-android.yml`) and `Backend Tests` (from `test-backend.yml`); the `changes` path-filter jobs may legitimately be skipped.
- `gh pr view <pr-number> --repo justb81/watchbuddy` → shows PR status and linked CI runs.

**2. Access full build logs with `gh`:**

- `gh run view <run-id> --log --repo justb81/watchbuddy` → outputs the **full log** for the entire workflow run.
- `gh run view <run-id> --job <job-id> --log --repo justb81/watchbuddy` → outputs the **full log for a specific job** (e.g., `Test & Build` or `Backend Tests`).
- `gh run view <run-id> --log-failed --repo justb81/watchbuddy` → outputs logs for **failed steps only**.
- To find the run ID: use `gh pr view <pr-number> --repo justb81/watchbuddy --json statusCheckRollup` and extract the workflow run URL, or check `gh run list --repo justb81/watchbuddy` and filter by PR number.

**3. Read filtered failure excerpts (optional fallback):**

`build-android.yml` and `test-backend.yml` post filtered failure excerpts as PR comments for convenience:
- `gh pr view <pr-number> --repo justb81/watchbuddy --comments`. Match by marker:
  - `<!-- watchbuddy-build-log -->` — Android build / detekt / Android Lint / unit-test failure.
  - `<!-- watchbuddy-backend-log -->` — ESLint / Prettier / Jest failure.
  - `<!-- watchbuddy-lint-report -->` — per-module finding counts (see § Static analysis).
- These excerpts are regex-filtered slices with ~20 lines of context per match, capped at ~55 000 chars — useful for quick diagnostics before diving into full logs.

**4. Wait for CI without polling:** Poll `gh pr checks` in a loop with a reasonable interval, or check back after a few minutes. For production workflows requiring webhook subscriptions, the `mcp__github__subscribe_pr_activity` tool remains available.

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

The phone runs an HTTP server on port 8765 and advertises its LAN endpoint over BLE (sole discovery channel — no mDNS/NSD). The TV scans for the advert via `PhoneBleScanner`, reads `(ipv4, port, modelQuality, llmBackend ordinal)` out of the 9-byte service-data payload, and connects to `/capability` over plain HTTP. The TV ranks phones by `modelQuality` and proxies all Trakt operations through the phone — the TV never calls Trakt directly.

For the authoritative HTTP API table, BLE wire format, and presence/heartbeat thresholds, see [`docs/architecture.md` § Communication Protocol](docs/architecture.md#communication-protocol-tv--phone).

## Important Patterns

- **Watching TV toggle:** The phone HomeScreen shows an "I am watching TV" toggle, gated by Trakt + TMDB availability **and** Wi-Fi connectivity. Toggling starts/stops the `CompanionService`. When the user swipes the app from recents, the service auto-stops via `onTaskRemoved()`. Off-Wi-Fi the toggle is disabled with a "connect to Wi-Fi" reason; a running companion self-stops when Wi-Fi is lost (after a 3 s grace period for SSID handoffs) and clears `companionEnabled` so the FG notification is dismissed (#278). Wi-Fi state is tracked by `phone/network/WifiStateProvider` and consulted both by `HomeViewModel` and defensively by `CompanionService.onStartCommand`.
- **CompanionStateManager:** Hilt singleton (`service/CompanionStateManager.kt`) that is the shared state hub between `CompanionService`, `CompanionHttpServer`, and `HomeViewModel`. Tracks `lastCapabilityCheck`, `lastScrobbleEvent`, and `isServiceRunning`.
- **TV discovery lifecycle & boot autostart:** Discovery is user-controlled via the `isPhoneDiscoveryEnabled` toggle in `StreamingPreferencesRepository` (default on). `TvHomeViewModel` observes the flow and drives `PhoneDiscoveryManager.setEnabled(...)` — discovery outlives `TvMainActivity` recreation. A separate `isAutostartEnabled` toggle (default off) lets `BootReceiver` start the foreground `TvDiscoveryService` on `BOOT_COMPLETED`. See [`docs/architecture.md` § Communication Protocol](docs/architecture.md#communication-protocol-tv--phone) for the full lifecycle (FGS type, permissions, Hilt wiring via `EntryPointAccessors`).
- **Presence heartbeat (TV):** `PhoneDiscoveryManager` polls `/capability` every 60 s; 3 consecutive failures evict the phone; scrobbling skips phones with stale presence (> 2 min). A `ConnectivityManager.NetworkCallback` restarts the BLE scanner when Wi-Fi returns. Full rationale in [`docs/architecture.md` § Presence Heartbeat (TV)](docs/architecture.md#presence-heartbeat-tv).
- **Presence timeout (phone):** If no TV polls `/capability` for 5 minutes, the companion service auto-deactivates.
- **Wi-Fi handoff (phone):** `CompanionService` stops the BLE advertiser on Wi-Fi loss with a 3 s grace for SSID handoffs, restarts on return with the new IPv4 in the payload, else self-stops and clears `companionEnabled` so the FG notification is dismissed (#278). `onStartCommand` is idempotent. See [`docs/architecture.md` § Companion Service Lifecycle](docs/architecture.md#companion-service-lifecycle-phone).
- **BLE discovery (sole channel — no mDNS fallback):** `CompanionBleAdvertiser` broadcasts a 9-byte Service Data payload (IPv4, port, `modelQuality`, `llmBackend` ordinal) under the UUID in `core/discovery/BleDiscoveryContract.kt`. The advertisement stays under the 31-byte legacy envelope — Android 16 / Nothing reject the old 48-byte layout with `DATA_TOO_LARGE` (#345). Non-connectable: the TV never opens a GATT connection; it reads the payload, then fetches `/capability` over HTTP. `PhoneBleScanner` filters with `setServiceData(uuid, schemaVersion, 0xFF mask)` so future incompatible schemas are rejected. Bluetooth-off / permission-denied / BLE-unsupported hardware degrade silently. Permissions: `BLUETOOTH_ADVERTISE` (phone, requested from HomeScreen), `BLUETOOTH_SCAN` `neverForLocation` (TV, requested in `TvMainActivity.onCreate`). Wire-format details and RSSI plumbing: [`docs/architecture.md` § BLE Advertisement](docs/architecture.md#ble-advertisement-phone-side).
- **Scrobble display:** When a scrobble event is received on the phone, `CompanionStateManager.lastScrobbleEvent` is updated. The phone HomeScreen shows a "Now Watching" card with show/episode details, auto-hidden after 30 minutes.
- **Scrobbling:** `MediaSessionScrobbler` (core, consumed by `TvScrobbleDispatcher`) listens to active media sessions on the TV, builds a `MediaMetadataSnapshot` that captures every relevant MediaMetadata field (`TITLE`, `DISPLAY_TITLE`, `DISPLAY_SUBTITLE`, `DISPLAY_DESCRIPTION`, `ARTIST`, `ALBUM_ARTIST`, `ALBUM`, `SUBTITLE`), and runs a three-stage cascade: (1) Phase 1 — try each field against the local show cache with the existing Levenshtein fuzzy matcher, harvesting any `S##E##` marker from any field so Plex/Jellyfin-shape metadata resolves cleanly when the show name sits in one field and the episode marker in another; (2) LLM fallback — on cache miss (< 0.70), call `TitleExtractor.extract(snapshot)` which the TV binds to `PhoneTitleExtractionClient` (ships the snapshot + a top-50 library-hint list to the best phone's `POST /scrobble/extract`, dedups by `packageName:title` so the 30 s poll cycle can't stack duplicate inferences, 90 s client / 75 s server timeout to absorb cold LiteRT-LM inference) for normalized `(showTitle, season?, episode?)` and re-runs the cache match; (3) TMDB fallback — search TMDB with whichever normalized title survived. Auto-scrobbles if confidence ≥ 95%, shows overlay confirmation between 70–95%, ignores below 70%. When a scrobble event occurs, `TvScrobbleDispatcher` calls `POST /scrobble/{start|pause|stop}` on **every** connected phone in parallel via `PhoneDiscoveryManager` + `PhoneApiClientFactory` — each phone records the episode on its own user's Trakt account using its own stored credentials. A failure for one phone does not block the others. The TV never calls the Trakt API directly for any operation. Progress is derived from `PlaybackState.position` and `MediaMetadata.METADATA_KEY_DURATION`; if unavailable, start/pause fall back to 0/50 and stop is skipped to avoid Trakt marking partially-watched episodes as watched (Trakt treats `progress >= 80` on `/scrobble/stop` as watched).
- **LLM selection:** `LlmOrchestrator` checks AICore first, then falls back to LiteRT-LM with a Gemma 4 model (E4B or E2B) sized to available RAM.
- **Auth modes:** Managed backend (default), self-hosted proxy, or direct Trakt credentials.
- **Multi-user:** Multiple phones can connect to one TV simultaneously; scrobbling records the episode for each connected user independently; shared watch mode avoids recap spoilers. The TV has no manual "who is watching" picker — active viewers are derived directly from `PhoneDiscoveryManager.discoveredPhones` and rendered as a read-only chip strip on `TvHomeScreen`.
- **Identity overrides (phone):** The phone's Settings → Identity section lets the user override their Trakt display name and pick the avatar source — `TRAKT` (default, uses Trakt CDN URL), `GENERATED` (deterministic initials via `InitialsAvatar`), or `CUSTOM` (user-picked photo via the Android Photo Picker, decoded + downscaled to 256×256 JPEG by `AvatarImageStore`, served over the LAN by `GET /avatar` with an ETag keyed on `customAvatarVersion`). Both fields live in DataStore and are projected into `/capability` by `DeviceCapabilityProvider`; the `/capability` JSON grows an `avatarSource` enum.
- **Manual episode marking (phone):** Tapping a show on HomeScreen opens `ShowDetailScreen`, which fetches the full season/episode structure via `EpisodeRepository.getSeasonsWithEpisodes` (Trakt `shows/:id/seasons?extended=episodes`, 10-min per-show cache). Each episode has a checkbox; toggling calls `sync/history` add or remove through `EpisodeRepository`, optimistically flips the UI, and on success calls `ShowRepository.updateLocalWatched(...)`. That mutates the in-memory `shows` `StateFlow` so `HomeViewModel` counters update without a round-trip. The layout pulls the season the user is currently mid-watching to the top, expanded; all other seasons appear below, collapsed.
- **"Last watched" episode selection:** `ShowProgressCalculator.latestWatched()` picks the episode with the **highest (season, episode) tuple** among all watched regular (season ≥ 1) episodes — not the episode with the most recent `last_watched_at` timestamp. Back-filling an earlier episode (which gets a new `Instant.now()` timestamp) must never displace a higher-numbered already-watched episode from the "last watched" position. The `last_watched_at` timestamp of the highest S×E episode is used as the sort key for the HomeScreen list (most recently progressed show at the top).
- **HomeScreen show-row date layout:** `ShowRowCard` (`ProgressLines`, non-compact) renders `InProgress` shows with a two-column, two-line layout: each column has a small secondary label ("Last watched" / "Last aired") on top and `bodyMedium` value ("S03E07 · today") below, side-by-side via `Row { Column(weight(1f)); Column(weight(1f)) }`. `ShelfCard` (compact mode) keeps a single-line value without the label to fit the narrow overlay. Date formatting lives in `phone/ui/util/RelativeDateFormatter` — today/yesterday/tomorrow within ±1 day, relative span ("3 days ago") within ±7 days, short absolute ("12 Mar") beyond 7 days; the pure branching logic (`formatRelativeTime` / `formatRelativeDate`) is internal and unit-tested without Android context.
- **HomeScreen two-section layout:** Both the phone `HomeScreen` and TV `TvHomeScreen` split the show list into two sections computed in the ViewModel layer. **"Continue Watching"** contains shows whose `latestWatchedInstant` is within the last 30 days (`HomeViewModel.CONTINUE_WATCHING_WINDOW` / `TvHomeViewModel.CONTINUE_WATCHING_WINDOW`), sorted by last-watched DESC. **"All Shows"** contains every other show, sorted alphabetically (case-insensitive). On the **phone**, a show that is fully watched (`ShowProgressCalculator.isCompleted(entry, hint) == true`) is always routed to "All Shows" regardless of how recently the finale was watched — the shelf is a "watch next" prompt and a finished show has nothing left to watch. TV hides completed shows entirely (see next bullet), so this carve-out only affects the phone. The section split is exposed as `continueWatching` and `allShows` on `HomeUiState` / `TvHomeUiState`; the UI reads these directly without re-computing. The "Continue Watching" header is hidden when that list is empty.
- **TV hides fully-watched shows:** On the **TV** `TvHomeScreen`, shows where every aired regular episode (`season ≥ 1`) has been watched are hidden from both sections — they are noise, not a browsing target. The completion check is `ShowProgressCalculator.isCompleted(entry, hint)` and is applied inside `TvHomeViewModel.partitionShows` before the 30-day window split. Shows with no TMDB hint or zero aired regular episodes are never considered completed. The **phone** HomeScreen keeps completed shows visible but relocates them to "All Shows" so they remain available for catalog/history utility without cluttering "Continue Watching". A show re-enters the TV list automatically when a new episode airs (TMDB `airedRegular` count increases).
- **New-season highlight:** `ShowProgressCalculator.hasNewSeasonAvailable(entry, hint)` returns `true` when the user finished their last-watched regular season (watched episode ≥ TMDB episode count for that season) and at least one episode of the next season has already aired (`hint.lastAired.season_number >= lastWatchedSeason + 1`). Returns `false` when: hint is null, user hasn't started the show, user is mid-season, the season length is unknown in TMDB, or the next season hasn't aired yet. ViewModels compute a `hasNewSeason: Map<Int, Boolean>` alongside `progress` and pass it to show card composables. Cards render a star icon badge (top-left) and an accent-color border when `hasNewSeason` is true. The badge carries a localized `contentDescription` ("New season available" / "Neue Staffel verfügbar" / "Nouvelle saison disponible" / "Nueva temporada disponible") for TalkBack/D-pad accessibility. Specials (season 0) are excluded from both sides of the check.
- **TV ShowDetail next-episode image and title:** `ShowDetailViewModel.loadNextEpisode(EnrichedShowEntry)` calls TMDB `GET /tv/{id}/season/{s}/episode/{e}` (using `tmdbApiKey` from the best phone's `/capability`) to fetch the next unwatched episode's `still_path` and `name`. Episode numbers come from `ShowProgressCalculator.nextEpisodeNumbers()`, which prefers `TmdbProgressHint.nextAired` over the naive Trakt +1 fallback. The TV `ShowDetailScreen` left panel shows the fetched still image (falling back to series poster, then a plain surface); the right panel shows the actual episode title as the primary line and the `SxxExx` code as a secondary line. State is exposed as `NextEpisodeUiState` (`isLoading`, `stillUrl`, `episodeName`, `episodeCode`). The nav graph (`TvNavGraph`) now passes `EnrichedShowEntry` instead of `TraktWatchedEntry` so TMDB hint data is available throughout the detail→recap flow.
- **TV ShowDetail "Available on" providers:** `ShowDetailViewModel.loadProviders(EnrichedShowEntry)` calls `WatchProvidersRepository.getResolvedProviders(tmdbId, countryCode, apiKey, showNonInstalled)`. The repository fetches TMDB `GET /tv/{id}/watch/providers`, merges flatrate/ads/free results, caches 24 h in-memory (keyed by `tmdbId:countryCode`), then orders the list as: **last-used** (from `LastUsedProviderRepository`) → **installed** (from `InstalledAppsProbe`) → **not-installed** (only if `showNonInstalledProviders` setting is on). State is exposed as `ProviderListUiState` (`Loading`, `Success(providers)`, `Empty(tmdbPageUrl)`, `Error`). `ShowDetailScreen` renders a `LazyRow` of `ProviderChip` cards; the first entry (last-used) gets a primary-color border and a "Last used" badge. Tapping a chip calls `onProviderSelected(provider, entry)` which records the choice in `LastUsedProviderRepository` and fires the deep link via `ProviderCatalog` (keyed by TMDB `provider_id`). Graceful fallback to `tmdbPageUrl` when no deep-link template exists. The provider catalog lives in `core/deeplink/ProviderCatalog.kt`. All known package names are declared in a `<queries>` block in `app-tv/AndroidManifest.xml`. The manual streaming-service settings screen (`StreamingSettingsScreen` / `StreamingSettingsViewModel`) has been removed; the `StreamingPreferencesRepository` no longer stores subscribed-service lists.
- **Diagnostics view:** Settings → Diagnostics on both apps renders live phone↔TV connection health from the existing shared singletons (`CompanionStateManager` on phone, `PhoneDiscoveryManager` on TV) — Wi-Fi / IPv4 / service running / HTTP bind / BLE advertiser state on the phone; discovery active / heartbeat age / BLE scan state / per-phone score + failCount + RSSI on the TV. Status dots are color-coded (green/yellow/red) so users can tell "no phones in BLE range" apart from "`/capability` 500" (discovered but broken). On the **phone only**, a "Share diagnostics" button funnels through `DiagnosticShare.launchShare()` so the `DiagnosticLog` breadcrumb snapshot + any pending crash reports can be exported via the system share sheet. The TV screen is view-only — TV share was removed because the Android TV system share sheet is effectively unusable with a D-pad. For the snapshot to be actionable, connectivity subsystems on the phone (`CompanionService`, `CompanionHttpServer` request log, `CompanionBleAdvertiser`, `WifiStateProvider`, `HomeViewModel` toggle) emit `DiagnosticLog.event(...)` breadcrumbs — without those the ring only carries auth/settings/app-lifecycle traces. Available in release builds; no new build variant.

## Documentation Maintenance

When making changes to the codebase, keep the following documentation in sync:

1. **`README.md`** — Update if changes affect features, setup instructions, module structure, or supported languages.
2. **`docs/architecture.md`** — Update if changes affect the system architecture, communication protocol, LLM strategy, deep link table, secret storage, or distribution details.
3. **`docs/tmdb-integration.md`** — Update if changes affect TMDB API usage, endpoints, data models, image handling, deep link templates, error handling, caching, or API key management.
4. **`CLAUDE.md`** (this file) — Update if changes affect the repository structure, tech stack, conventions, communication protocol, or any information that helps agents understand the codebase.

Do not let documentation drift from the actual implementation. When adding new modules, API endpoints, supported languages, or deep link integrations, update all relevant files accordingly.
