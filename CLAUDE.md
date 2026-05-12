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
│       ├── data/       StreamingPreferencesRepository (phone-discovery / autostart / showNonInstalledProviders), TvShowCache, WatchProvidersRepository (TMDB watch providers, 24 h cache), LastUsedProviderRepository (TV-local per-show last-used provider), JustWatchDeepLinkRepository + JustWatchDeepLinkDao + JustWatchDeepLinkDatabase (Room-backed per-episode deep link cache)
│       ├── di/         AppModule (Hilt dependency injection), ApplicationScope qualifier (@ApplicationScope CoroutineScope for goAsync in BootReceiver)
│       ├── discovery/  PhoneDiscoveryManager, PhoneApiService, PhoneApiClientFactory, PhoneTitleExtractionClient (TitleExtractor → best phone's /scrobble/extract), TvDiscoveryService (foreground service — keeps discovery alive post-boot), InstalledAppsProbe (PackageManager cache, invalidated on install/remove)
│       ├── scrobbler/  TvScrobbleDispatcher, TvWatchedShowSource, WatchNextMetadataSource, NotificationMetadataSource, WatchBuddyNotificationListener
│       ├── ui/         TvMainActivity, TvNavGraph
│       │   ├── components/ InitialsAvatar
│       │   ├── home/       TvHomeScreen, TvHomeViewModel (active viewers derived from discovered phones)
│       │   ├── navigation/ TvNavGraph
│       │   ├── recap/      RecapScreen, RecapViewModel
│       │   ├── diagnostics/ TvDiagnosticsScreen, TvDiagnosticsViewModel (discovery / BLE / discovered-phones health — view-only, no Share)
│       │   ├── scrobble/   ScrobbleOverlay, ScrobbleViewModel
│       │   ├── settings/   TvSettingsScreen + TvSettingsViewModel (settings hub — discovery, autostart, show-non-installed toggle, diagnostics)
│       │   ├── showdetail/ ShowDetailScreen, ShowDetailViewModel (next-episode still + TMDB watch providers + installed-app filter + last-used ranking + JustWatch deep links; `NextEpisodeUiState`, `ProviderListUiState`, `DeepLinkState` flows; one-tap "Mark as watched" button — `MarkWatchedState` sealed interface, `markCurrentEpisodeWatched` fan-out to all phones via `POST /watched`, `advancedEntry` optimistic-advance flow with `AnimatedContent` slide transition)
│       │   └── theme/      TV Material theme
├── build-logic/        Gradle convention plugins (included build)
│   └── convention/
│       └── src/main/kotlin/
│           ├── watchbuddy.android.library.gradle.kts    Shared library config: compileSdk, Java 17, Kotlin JVM target, Hilt/KSP plugins, common test deps
│           └── watchbuddy.android.application.gradle.kts  Everything above + compose-compiler, signing config, NDK debugSymbolLevel, build types, Lint SARIF
├── core/               Shared library module
│   └── src/main/java/com/justb81/watchbuddy/core/
│       ├── deeplink/   ProviderCatalog (TMDB provider_id → packageName; deep links handled by JustWatch)
│       ├── justwatch/  JustWatchApiService (GraphQL Retrofit interface), JustWatchPackageMap (technicalName → TMDB provider_id)
│       ├── locale/     LocaleHelper (LLM language resolution)
│       ├── logging/    CrashReporter, DiagnosticLog, DiagnosticShare
│       ├── model/      Data models (Kotlin Serialization)
│       ├── network/    NetworkModule (Hilt, OkHttp, Retrofit), SharedJson (WatchBuddyJson shared instance)
│       ├── progress/   ShowProgressCalculator
│       ├── scrobbler/  MediaSessionScrobbler, ScrobbleContracts, EpisodeMarkerExtractor (pure marker extraction), EpisodeResolution (pure episode resolution — `resolveEpisodeFromMetadata`, `EpisodeResolutionResult`, `ResolveSource`)
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
│   ├── precommit.sh                  Scoped local mirror of CI checks (see "Local pre-commit checks")
│   ├── validate-release-security.py  Validates release.yml signing-secret hygiene (run by precommit.sh when release.yml is staged)
│   └── update-readme-stats.sh        Regenerates the README "Code Statistics" block (cloc + JUnit + Jest)
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

### Active Early Development — No Cross-Component Fallbacks

The app is in active early development. Phone, TV and backend are always deployed together from the same repository, so **version/schema fallbacks between components are not needed and must not be added**. Concretely:

- The BLE discovery payload supports only the current schema version. Legacy schema versions are rejected.
- The HTTP server API has no backward-compatibility shims or version negotiation.
- The token storage format is always the current v1 format. Migration code for older formats is not needed.
- When changing a shared protocol or data format, update all components in the same commit/PR.

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
- Convention plugins live in `build-logic/convention/`. `watchbuddy.android.library` is applied by `core`; `watchbuddy.android.application` is applied by `app-phone` and `app-tv`. Both plugins centralise `compileSdk`, Java/Kotlin 17 options, Hilt/KSP wiring, and test dependencies so module `build.gradle.kts` files declare only what is unique to that module.
- **ProGuard / R8 rules:** `core/consumer-rules.pro` contains a single generic `-if interface * { @retrofit2.http.* <methods>; } -keep interface <1> { *; }` rule that is automatically merged into any app module that depends on `:core` (via `consumerProguardFiles`). This means adding a new Retrofit service interface to `:core` requires no manual ProGuard entry in `app-phone/proguard-rules.pro` or `app-tv/proguard-rules.pro`. Retrofit service interfaces defined outside `:core` (e.g. `PhoneApiService` in `app-tv`) are covered by an equivalent `-if/-keep` rule in their own module's `proguard-rules.pro`. The rule prevents R8 from renaming interfaces; renaming breaks the `Proxy.newProxyInstance` → Kotlin `checkcast` invariant used by `Retrofit.create(Foo::class.java)`.

**Release signing credentials** are passed via Gradle project properties, not environment variables, so they never appear in env-var dumps or third-party plugin logs. CI writes them to `~/.gradle/gradle.properties` (chmod 600) in the `release.yml` workflow. For local release builds, add them to your personal `~/.gradle/gradle.properties`:
```
watchbuddy.signing.storePassword=<your keystore password>
watchbuddy.signing.keyAlias=<your key alias>
watchbuddy.signing.keyPassword=<your key password>
```
The keystore file path is still supplied via the `KEYSTORE_FILE` environment variable (it is not a secret). The convention plugin (`watchbuddy.android.application.gradle.kts`) reads the Gradle properties via `providers.gradleProperty("watchbuddy.signing.*")`.

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

- `app-phone/**`, `app-tv/**`, `core/**`, `build-logic/**`, `*.gradle.kts`, `gradle/**`, `gradle.properties`, `config/detekt/**`, `.github/actions/**` → `./gradlew test detektAll :app-phone:lintDebug :app-tv:lintDebug`
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

**2. Access build logs with `gh` (with efficient filtering):**

- `gh run view <run-id> --job <job-id> --log --repo justb81/watchbuddy` → outputs the **full log for a specific job**. **Always specify a job** to avoid timeouts on large runs; get job IDs from `gh pr view <pr-number> --repo justb81/watchbuddy --json statusCheckRollup` or `gh run view <run-id> --verbose`.
- **Filter for errors to keep context small:** pipe to `grep` to extract only relevant failures:
  - `gh run view <run-id> --job <job-id> --log --repo justb81/watchbuddy | grep -i "error\|failed\|exception"` — extracts error lines.
  - `gh run view <run-id> --job <job-id> --log --repo justb81/watchbuddy | grep -B5 -A5 "FAILED"` — shows 5 lines of context around failures.
- `gh run view <run-id> --job <job-id> --log-failed --repo justb81/watchbuddy` → outputs logs for **failed steps only** (no filtering needed).
- To find the run ID: use `gh pr view <pr-number> --repo justb81/watchbuddy --json statusCheckRollup`, or check `gh run list --repo justb81/watchbuddy` and filter by PR number.

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
- **Native debug symbols:** Release AABs use `debugSymbolLevel = "FULL"`, so AGP embeds per-AAB symbols under `BUNDLE-METADATA` and Play auto-associates them for native crash/ANR symbolication. Per-module `native-debug-symbols.zip` files are still attached to the GitHub Release for manual symbolication.
- **R8 mapping (deobfuscation) files:** Both apps enable `isMinifyEnabled = true`. AGP embeds `mapping.txt` inside each AAB so Play Console can de-obfuscate stack traces per versionCode. Per-module `mapping.txt` files are also attached to the GitHub Release as `watchbuddy-{phone,tv}-<version>-mapping.txt` for manual triage.
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

## Documentation Maintenance

When making changes to the codebase, keep the following documentation in sync:

1. **`README.md`** — Update if changes affect features, setup instructions, module structure, or supported languages.
2. **`docs/architecture.md`** — Update if changes affect the system architecture, communication protocol, LLM strategy, deep link table, secret storage, or distribution details.
3. **`docs/tmdb-integration.md`** — Update if changes affect TMDB API usage, endpoints, data models, image handling, deep link templates, error handling, caching, or API key management.
4. **`CLAUDE.md`** (this file) — Update if changes affect the repository structure, tech stack, conventions, communication protocol, or any information that helps agents understand the codebase.

Do not let documentation drift from the actual implementation. When adding new modules, API endpoints, supported languages, or deep link integrations, update all relevant files accordingly.
