# WatchBuddy — Architecture Overview

## System Architecture

```mermaid
graph TB
    subgraph WIFI["LOCAL WIFI NETWORK"]
        TV["Google TV (app-tv)\n───────────────\nUI · Display\nBLE Scanner\nWebView\nMediaSession Scrobbler"]
        Phone["Android Phone(s) (app-phone)\n───────────────\nLLM (Gemma / AICore)\nBLE Advertiser · HTTP API\nTrakt Auth"]
        TV <-->|"BLE beacon + HTTP (port 8765)"| Phone
    end

    Phone -->|"OAuth · sync · scrobble"| Trakt["Trakt API\ntrakt.tv/api\nRate: 1 000 / 5 min"]
    Phone -->|"Token exchange"| Backend["Token Proxy Backend\n(backend/ — Docker)\nInjects client_secret"]
    Phone -->|"Recap: episode metadata\nHome: poster images"| TMDB["TMDB API\napi.tmdb.org\n(per-user key)"]
    TV -->|"Title search\nShow / image data"| TMDB
```

> For a detailed breakdown of TMDB API usage, user journeys, connection handling and error recovery, see [`docs/tmdb-integration.md`](tmdb-integration.md).

## Communication Protocol (TV ↔ Phone)

### BLE Advertisement (Phone side)

The phone broadcasts a BLE service-data beacon carrying its LAN endpoint so
the TV can connect over HTTP without relying on mDNS/multicast (which is blocked
by many home APs with client isolation, VLAN segmentation, or aggressive
multicast filtering). Schema v2 phones additionally emit a **scan response**
with the bearer token used to authenticate TV→phone HTTP requests.

#### Primary advertisement (schema v2, 9 bytes under `SERVICE_UUID`)

```
Service UUID:    5e4b4d3a-9f7c-4b7e-8e6b-6c0e5f27e4a0
Service data:    [schemaVersion (1B) | ipv4 (4B) | port (2B) | modelQuality (1B) | llmBackend ordinal (1B)]
Advertise mode:  ADVERTISE_MODE_BALANCED (~250 ms interval)
TX power:        ADVERTISE_TX_POWER_MEDIUM (~10 m, couch-to-TV)
Connectable:     false → ADV_SCAN_IND when scan response is present
```

Schema byte: `1` = legacy (v1, no auth), `2` = current (auth-capable, emits scan response).
The TV's `PhoneBleScanner` accepts both schema versions via two BLE scan filters.

#### Scan response (schema v2 only, 13 bytes under `TOKEN_SERVICE_UUID`)

```
Token UUID:      7a2c1f8b-3e5d-4c9a-b0e7-8d4f2a6c0b3e
Scan response:   [bearer token bytes (13B)]
```

The bearer token is derived from the Trakt access token: first 13 bytes of the
UTF-8 byte representation. The TV extracts the scan response payload and sets it
as the `Authorization: Bearer <token>` header on every HTTP request. This allows
the phone to reject unauthenticated requests (i.e. from other phones or rogue
clients), without requiring a full handshake.

### HTTP API (TV → Phone)

The phone runs a Ktor HTTP server on port 8765. All endpoints require a `Bearer`
token matching the first 13 bytes of the phone's Trakt access token.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET`  | `/capability` | Bearer | Phone reports LLM quality, RAM, TMDB key, avatar source, country code, last resolved session |
| `GET`  | `/shows` | Bearer | Returns `List<EnrichedShowEntry>` with poster paths and TMDB progress hints |
| `GET`  | `/shows/{traktId}/seasons/{season}/episodes/{episode}` | Bearer | Full `TmdbEpisode` for a single episode |
| `GET`  | `/avatar` | Bearer | JPEG bytes for a user-set custom avatar (`?v=N` cache-busting) |
| `POST` | `/scrobble/extract` | Bearer | LLM title extraction: phone returns `TitleExtractionResponse` from `MediaMetadataSnapshot` + library hints |
| `POST` | `/watched` | Bearer | TV reports a confirmed scrobble; phone calls Trakt and updates show cache |
| `POST` | `/shows/add-to-library` | Bearer | TV requests that phone add a show + episode to the Trakt library |
| `POST` | `/scrobble/prompt` | Bearer | TV dispatches an `AmbiguousScrobbleEvent`; phone presents a disambiguation UI to the user |
| `GET`  | `/provider-catalog` | Bearer | Phone serves the versioned `ProviderCatalogSnapshot` JSON |

### Heartbeat / presence

The TV polls `/capability` every **30 seconds** for each discovered phone. A phone
is marked **unavailable** after **90 seconds** with no successful poll. Polls use a
**5-second** connect + read timeout to keep the UI responsive. Discovery state is
aggregated in `PhoneDiscoveryManager` and exposed as a `StateFlow<List<PhoneDevice>>`.

## LLM Strategy

The phone hosts an on-device LLM (LiteRT-LM with Gemma models or AICore/Gemini
Nano). The TV discovers phones by BLE and ranks them by `modelQuality` (0–150).
LLM calls are proxied through the best-available phone — the TV never runs
inference locally.

### Model Scoring (`modelQuality`)

| Score range | Description |
|------------|-------------|
| 0 | No LLM (`LlmBackend.NONE`) |
| 1–49 | AICore / Gemini Nano (small) |
| 50–99 | LiteRT-LM with a small model (< 4 B params) |
| 100–150 | LiteRT-LM with a large model (≥ 4 B params) |

### Recap Generation Flow

1. TV calls `GET /shows/{traktId}/seasons/{season}/episodes/{episode}` on the best phone
2. Phone fetches episode metadata from TMDB and Trakt
3. Phone passes episode text to `LlmOrchestrator` → `RecapGenerator`
4. `RecapGenerator` builds a prompt with the episode synopsis and any previously-watched episode summaries
5. LLM generates a recap; the phone streams it back to the TV over the HTTP response body
6. TV displays the recap in `RecapScreen` with episode still image from TMDB

### Title Extraction Flow

1. TV's `MediaSessionScrobbler` detects playback and builds a `MediaMetadataSnapshot`
2. TV calls `POST /scrobble/extract` on the best phone with the snapshot + library hints
3. Phone's `LlmTitleExtractor` runs the LLM to normalize the raw media title to `(showTitle, season?, episode?)`
4. TV re-runs its existing fuzzy-match cascade with the normalized title

## Secret Storage

### Phone: Android Keystore + EncryptedSharedPreferences

Trakt OAuth tokens are encrypted with a key stored in the Android Keystore. The
actual token bytes never leave the Keystore's hardware-backed TEE. A Kotlin
`TokenRepository` wraps the `EncryptedSharedPreferences` and exposes
`suspend fun getToken(): String?` / `suspend fun saveToken(token: String)` to
authentication code.

The token exchange itself is proxied through the Node.js backend
(`backend/`). The phone sends the authorization code; the backend injects the
OAuth `client_secret` and returns the access + refresh tokens. This keeps the
`client_secret` out of the APK.

### TV: Runtime-only memory

The TV never persists a Trakt token. The `tmdbApiKey` shipped in `/capability`
and the bearer token extracted from the BLE scan response are held in process
memory only and discarded when the app restarts.

## Scrobbling

The TV monitors `MediaSession` activity from all foreground streaming apps. When
confidence reaches 70 %, it fires a `POST /watched` to every discovered phone.
Each phone independently calls `POST /sync/history` on Trakt and emits a
`ScrobbleDisplayEvent` back to the TV over the next `/capability` poll.

### Scrobble Cascade (TV-side, `MediaSessionScrobbler`)

**Phase 1 — Library fast-path**: Fuzzy-match media title against the user's
library (`TvShowCache`). Confidence formula:
```
score = titleSimilarity * runtimeAffinity
```
where `runtimeAffinity` (±15 % of episode runtime) boosts matches where the
streaming app reports a playback duration close to the known episode length.

**Phase 2 — TMDB search**: If Phase 1 yields no match above 40 %, the scrobbler
calls `TmdbApiService.searchTv()` and re-runs the cascade against TMDB results.

**Phase 3 — LLM title extraction**: If Phase 2 also fails, the TV dispatches a
`POST /scrobble/extract` to the best phone. The phone's on-device LLM normalizes
the raw media title (strips ads, episode numbers, etc.) and the TV retries Phases
1 and 2 with the cleaned-up title.

**Ambiguous prompt**: If all three phases find at least one candidate scoring
40–69 % but none clears 70 %, an `AmbiguousScrobbleEvent` is fanned out to every
discovered phone via `POST /scrobble/prompt`. The user resolves on the phone;
the TV reads the resolution from the next `/capability` poll
(`lastResolvedSessionKey` / `lastResolvedTraktId`).

**WatchNext enricher**: The TV also reads Android TV's WatchNext API channel for
a secondary source of media metadata. When a WatchNext entry matches a library
show with confidence ≥ 40 %, it is merged into the `MediaMetadataSnapshot` before
the LLM call.

## Avatar Images

The phone controls where the TV sources the user's avatar from, via
`DeviceCapability.avatarSource`:

| Value | Behaviour |
|-------|----------|
| `TRAKT` | TV renders `userAvatarUrl` directly (Trakt CDN URL) |
| `GENERATED` | TV renders deterministic initials avatar (no network call) |
| `CUSTOM` | TV fetches JPEG from phone's `GET /avatar?v=N` endpoint |

The phone increments `?v=N` on each custom-photo change to bust the TV's
Coil image cache.

## Provider Catalog

`GET /provider-catalog` on the companion HTTP API serves a versioned
`ProviderCatalogSnapshot` JSON payload. The phone fetches this from its own
backend (or constructs it locally) and serves it to the TV. The TV caches the
catalog in a Room table (`ProviderCatalogCacheDao`) and refreshes it via
`TvProviderCatalogRepository` whenever a new phone connects.

`ProviderCatalogRegistry` (singleton in `:core`) is updated by both
`ProviderCatalogRepository` (phone) and `TvProviderCatalogRepository` (TV)
via `updateFromSnapshot()`. When no snapshot has been injected, the registry
falls back to an in-code `BUNDLED_SNAPSHOT` that covers the major streaming
services (Netflix, Prime Video, Disney+, etc.).

## Distribution

### Automated releases (release-please)

`release-please` opens a `release-please--branches--main` PR after every merge to
`main` that contains at least one `feat:` or `fix:` Conventional Commit. Merging
the release PR:

1. Bumps the version in `gradle/libs.versions.toml`
2. Updates `CHANGELOG.md`
3. Creates a GitHub Release
4. Triggers `release.yml` which builds signed APK + AAB and attaches them to the release
5. Uploads the AAB to the Google Play internal track via Gradle Play Publisher

### Manual re-release

`release.yml` supports a `workflow_dispatch` trigger with two optional inputs:

| Input | Default | Options |
|-------|---------|--------|
| `tag` | *(empty — uses latest push)* | Any existing release tag, e.g. `v0.35.0` |
| `play_track` | `internal` | `internal`, `alpha`, `beta`, `production` |
| `play_status` | `DRAFT` | `DRAFT`, `COMPLETED` |

A manual dispatch checks out the specified `tag`, rebuilds the APK/AAB, and publishes to the given track — no YAML edits required.

## Deep Links

The available-provider list for each show is fetched from TMDB `/tv/{id}/watch/providers` (region-aware, keyed by device locale country code). No manual service selection is needed.

`core/deeplink/ProviderCatalogRegistry.kt` is the single source of truth for all provider catalog lookups — it maps TMDB `provider_id` integers to Android package names and JustWatch `technicalName` strings to TMDB provider IDs. Deep-link URLs are sourced from JustWatch's unofficial GraphQL API rather than hard-coded templates.

| TMDB provider_id | Service | Package |
|-----------------|---------|--------|
| 8 | Netflix | `com.netflix.ninja` |
| 9, 119 | Prime Video | `com.amazon.amazonvideo.livingroom` |
| 337 | Disney+ | `com.disney.disneyplus` |
| 350 | Apple TV+ | `com.apple.atve.androidtv.appletv` |
| 531 | Paramount+ | `com.cbs.app` |
| 1899 | Max / HBO | `com.hbo.hbonow` |
| 2187 | WaipuTV | `de.exaring.waipu` |
| 2184 | Joyn | `de.prosiebensat1.joyn.tv` |
| 192 | YouTube | `com.google.android.youtube.tv` |
| 35 | Rakuten TV | `tv.wuaki.apptv` |
| 195 | ARD Mediathek | `de.swr.avp.ard.tv` |
| 231 | ZDF Mediathek | `com.zdf.android.mediathek` |

### JustWatch-powered per-episode deep links

`JustWatchDeepLinkRepository` resolves streaming URLs by querying JustWatch's unofficial GraphQL API (`https://apis.justwatch.com/graphql`) and caching results in a Room database (`justwatch_deep_links.db`) on the TV device.

**Resolution cascade** for each `(tmdbShowId, season, episode, providerId, countryCode)`:
1. Episode-level Room cache lookup (positive hit: return URL immediately)
2. If miss or expired negative: live JustWatch call via three sequential GraphQL queries — `SEARCH_QUERY` (find show by title, verify TMDB ID), `SEASONS_QUERY` (get season node IDs), `EPISODES_QUERY` (get episode offers). Caches all providers found and negatives for known providers not returned.
3. Show-level Room cache lookup (season=0, episode=0)
4. Show-level live JustWatch call (single search query, no episode drill-down)
5. Returns `null` → caller treats as `DeepLinkState.Unavailable`

**Caching policy:** Positive hits cached permanently. Negative entries (no offer found) expire after 30 days (`NEGATIVE_TTL_MS`). Network exceptions do not write negatives — the next call retries the API.

**Batch dedup:** A `Mutex`-protected `Map<FetchKey, Mutex>` prevents duplicate in-flight JustWatch API calls when multiple coroutines request the same `(showId, season, episode, countryCode)` simultaneously. After acquiring the per-key lock, the cache is re-checked before calling the API.

**Provider mapping:** `ProviderCatalogRegistry` (`core/deeplink/`) maps JustWatch `technicalName` strings (e.g. `netflix`, `amazonprime`, `disneyplus`) to TMDB `provider_id` integers via `providerIdByJustWatchName()`. The same registry drives negative-cache writes for known-but-absent providers.

**ViewModel integration:** `ShowDetailViewModel.loadDeepLinks()` is triggered once `ProviderListUiState.Success` arrives. Each provider gets a `viewModelScope.async` backed by `JustWatchDeepLinkRepository`; in-flight dedup at the ViewModel level prevents duplicate `Deferred` jobs per key. State is `deepLinks: StateFlow<Map<Int, DeepLinkState>>` with `DeepLinkState = Loading | Available(url) | Unavailable`. The UI shows a spinner overlay on loading chips, disables unavailable chips, and displays a JustWatch attribution badge when any link is `Available`.

**Diagnostics:** `TvDiagnosticsScreen` shows a "Streaming Links" section with cached URL count, negative entry count, last-fetch timestamp, and a "Clear cache" button.

### Provider ordering on ShowDetail

`WatchProvidersRepository.getResolvedProviders()` composes the final list in this order:
1. **Last-used** provider for this show (from `LastUsedProviderRepository`, TV-local DataStore)
2. **Installed** providers (cross-referenced with `InstalledAppsProbe` via `PackageManager`)
3. **Not-installed** providers (only when "Show unavailable services" setting is on)

The last-used entry is recorded when the user taps a provider chip or a confirmed scrobble is attributed to a known package. `InstalledAppsProbe` caches installed packages and invalidates on `ACTION_PACKAGE_ADDED`/`ACTION_PACKAGE_REMOVED`.

All known streaming package names are declared in a `<queries>` block in `app-tv/AndroidManifest.xml` so PackageManager reports them on Android 11+.
