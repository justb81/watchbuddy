# Trakt Connection and Sync — Complete Flow Documentation

This document describes the full user journey and technical flow for connecting to Trakt, syncing watch history, and scrobbling — covering both the phone and TV apps end to end.

---

## Table of Contents

1. [High-Level Overview](#high-level-overview)
2. [Phase 1: Authentication (Phone)](#phase-1-authentication-phone)
3. [Phase 2: Phone Companion Server](#phase-2-phone-companion-server)
4. [Phase 3: TV Discovery and Token Sharing](#phase-3-tv-discovery-and-token-sharing)
5. [Phase 4: Show Sync](#phase-4-show-sync)
6. [Phase 5: Scrobbling](#phase-5-scrobbling)
7. [Phase 6: Deep Linking](#phase-6-deep-linking)
8. [Authentication Modes](#authentication-modes)
9. [Token Lifecycle](#token-lifecycle)
10. [Key Thresholds and Constants](#key-thresholds-and-constants)
11. [Sequence Diagrams](#sequence-diagrams)
12. [File Reference](#file-reference)

---

## High-Level Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                         LOCAL WIFI NETWORK                           │
│                                                                      │
│  ┌──────────────────┐   NSD/mDNS + HTTP   ┌──────────────────────┐  │
│  │   Google TV      │◄────────────────────►│   Android Phone(s)   │  │
│  │   (app-tv)       │     port 8765        │   (app-phone)        │  │
│  │                  │                      │                      │  │
│  │  Scrobbler       │  GET /shows          │  Trakt OAuth login   │  │
│  │  Show grid       │  GET /auth/token     │  Token storage       │  │
│  │  Deep links      │  POST /recap/{id}    │  LLM recap engine    │  │
│  │  Recap WebView   │  GET /capability     │  Show cache          │  │
│  └────────┬─────────┘                      └──────────┬───────────┘  │
│           │                                           │              │
└───────────┼───────────────────────────────────────────┼──────────────┘
            │ Internet                                  │ Internet
   ┌────────▼─────────┐                       ┌────────▼──────────┐
   │   Trakt API      │                       │  Token Proxy      │
   │   trakt.tv/api   │                       │  Backend (Docker) │
   │                  │                       │  Injects client   │
   │  Scrobble calls  │                       │  secret server-   │
   │  Show search     │                       │  side             │
   └──────────────────┘                       └───────────────────┘
```

The phone app owns the Trakt connection. The TV app has **no Trakt credentials** — it discovers all phones on the local network, borrows each phone's access token, and uses those tokens for scrobbling (one Trakt call per connected user) and show lookups.

---

## Phase 1: Authentication (Phone)

### User Journey

1. User launches the phone app for the first time and reaches the **Onboarding screen**.
2. User taps **"Connect to Trakt"**.
3. The app displays a short alphanumeric **user code** and a countdown timer.
4. User opens a browser, navigates to `https://trakt.tv/activate`, and enters the code.
5. User authorizes WatchBuddy on the Trakt website.
6. The app detects the authorization (via polling) and shows "Connected as **{username}**".

### Technical Flow

```
OnboardingViewModel.requestDeviceCode()
    │
    ▼
POST https://api.trakt.tv/oauth/device/code
    Body: { "client_id": "<TRAKT_CLIENT_ID>" }
    │
    ▼
Response: { device_code, user_code, verification_url, expires_in, interval }
    │
    ▼
UI displays user_code + countdown (expires_in seconds)
    │
    ▼
OnboardingViewModel.startPolling()  ← polls every {interval} seconds
    │
    ├── MANAGED mode ──► POST backend/trakt/token  { "code": device_code }
    │                      Backend injects client_secret, calls Trakt upstream
    │
    ├── SELF_HOSTED mode ──► POST {user_url}/trakt/token  { "code": device_code }
    │                          Same protocol, user-provided backend
    │
    └── DIRECT mode ──► POST trakt.tv/oauth/device/token
                          { code, client_id, client_secret }
                          Secret stored locally in Keystore
    │
    ▼
Response: { access_token, refresh_token, expires_in, token_type }
    │
    ▼
TokenRepository.saveTokens(accessToken, refreshToken, expiresIn)
    │  Stored in EncryptedSharedPreferences (AES-256-GCM, Android Keystore)
    │
    ▼
GET https://api.trakt.tv/users/me  (Bearer token)
    │
    ▼
OnboardingState.Success(username)
```

### Key Classes

| Class | Responsibility |
|-------|----------------|
| `OnboardingViewModel` | Drives the OAuth device flow, polls for token |
| `TokenRepository` | Encrypted token storage (Keystore-backed) |
| `TraktApiService` | Retrofit client for Trakt API |
| `TokenProxyService` | Retrofit client for backend token proxy |
| `NetworkModule` | Provides OkHttp + Retrofit instances with cert pinning |

---

## Phase 2: Phone Companion Server

### User Journey

1. After Trakt login, user enables the **Companion Service** in Settings.
2. The phone starts a foreground service with a persistent notification.
3. The phone is now discoverable by TV apps on the same Wi-Fi network.

### Technical Flow

```
SettingsViewModel.toggleCompanionService()
    │
    ▼
CompanionService starts (foreground, persistent notification)
    │
    ├── Starts CompanionHttpServer (Ktor Netty, port 8765)
    │
    └── Registers NSD (mDNS) service:
            Service name:  watchbuddy-{username}
            Service type:  _watchbuddy._tcp.
            Port:          8765
            TXT records:   version=1, modelQuality=90, llmBackend=LITERT
```

### HTTP Endpoints (Phone Serves)

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `GET` | `/capability` | Device info, LLM backend, RAM, model quality score | No |
| `GET` | `/shows` | User's Trakt watched shows (5-min cache) | Token required |
| `POST` | `/recap/{traktShowId}` | Generate HTML recap via LLM | Token required |
| `GET` | `/auth/token` | Return current Trakt access token | Token required |

---

## Phase 3: TV Discovery and Token Sharing

### User Journey

1. User launches the TV app on Google TV.
2. The TV automatically discovers phone(s) on the network.
3. The TV home screen shows a connection badge ("1 phone connected — Pixel 8 Pro").
4. No Trakt login required on the TV — it borrows the phone's token transparently.

### Technical Flow

```
TV app starts
    │
    ▼
PhoneDiscoveryManager.startDiscovery()
    │  Listens for NSD service type: _watchbuddy._tcp.
    │
    ▼
Phone discovered → resolve IP + port
    │
    ▼
GET http://{phone_ip}:8765/capability
    │
    ▼
Response: DeviceCapability { deviceName, userName, llmBackend, modelQuality, freeRamMb }
    │
    ▼
Rank phone:  score = modelQuality (0–150) + ramBonus (0–10)
    │
    │  RAM bonus:  ≥6 GB → +10  |  4–6 GB → +6  |  3–4 GB → +3  |  <3 GB → 0
    │
    ▼
getBestPhone() → highest-scoring phone (used for single-token ops like search)
    │
    ▼
TvTokenCache — per-phone token cache (ConcurrentHashMap, 30-min TTL per phone)
    │
    ├── getToken()       → token for best phone only   (Trakt search)
    └── getAllTokens()   → tokens for ALL available phones  (scrobbling)
              │
              └── For each available phone in parallel:
                    Check per-phone cache (30-min TTL)
                    If expired → GET http://{phone}:8765/auth/token
                                      │
                                      ▼
                                 Response: { "accessToken": "..." }
                                      │
                                      ▼
                                 Cache token keyed by phone baseUrl
```

### Key Classes

| Class | Responsibility |
|-------|----------------|
| `PhoneDiscoveryManager` | NSD listener, phone ranking, best-phone selection |
| `PhoneApiService` | Retrofit interface for phone HTTP endpoints |
| `PhoneApiClientFactory` | Creates per-phone Retrofit clients (cached by base URL) |
| `TvTokenCache` | In-memory token cache with 30-minute TTL |

---

## Phase 4: Show Sync

### User Journey

1. The TV home screen loads automatically once a phone is connected.
2. User sees a grid of show cards with poster art, title, and the last watched episode ("S03E07").
3. If the phone is temporarily unreachable, the TV falls back to its local cache.

### Technical Flow

```
TvHomeViewModel.loadShows()
    │
    ▼
PhoneDiscoveryManager.getBestPhone()
    │
    ▼
PhoneApiClientFactory.createClient(bestPhone.baseUrl)
    │
    ▼
GET http://{phone}:8765/shows
    │
    ├── Phone side: ShowRepository.getShows()
    │     ├── Check in-memory cache (5-min TTL)
    │     └── If stale → GET https://api.trakt.tv/sync/watched/shows (Bearer token)
    │                       │
    │                       ▼
    │                  Cache result, return List<TraktWatchedEntry>
    │
    ▼
TV receives List<TraktWatchedEntry>
    │
    ├── Update TvShowCache (in-memory, used by scrobbler for matching)
    │
    └── Emit to TvHomeUiState → UI renders show grid
```

### Failover Chain

```
Best phone available → fetch shows from phone
    │ unavailable
    ▼
Next best phone → try next in ranking
    │ unavailable
    ▼
Local TV cache → show stale data
    │ empty
    ▼
Empty state → "No phone connected" message
```

---

## Phase 5: Scrobbling

### User Journey

1. User opens a streaming app (Netflix, Disney+, etc.) on the TV and starts watching a show.
2. **Auto-scrobble (≥95% confidence):** The episode is silently reported to Trakt — no UI interruption.
3. **Confirmation overlay (70–95%):** A small overlay appears in the bottom-right corner: "Watching Breaking Bad S01E03?" with Yes/No buttons. Auto-confirms after 15 seconds.
4. **Below 70%:** Ignored entirely — no UI, no scrobble.
5. When the user pauses, the scrobble state updates. When playback stops, the episode is marked as watched on Trakt.

### Technical Flow

```
MediaSessionScrobbler (NotificationListenerService)
    │  Polls MediaSessionManager.getActiveSessions() every 30 seconds
    │
    ▼
Extract from active session:
    - Package name (e.g., com.netflix.ninja)
    - Media title from METADATA_KEY_TITLE (e.g., "Breaking Bad S01E03")
    │
    ▼
Parse episode info:  regex (?i)S(\d{1,2})E(\d{1,2})
    │
    ▼
matchTitleToTrakt() — two-tier fuzzy matching:
    │
    ├── Tier 1: Local cache (TvShowCache)
    │     normalize(title) → lowercase, strip specials, remove "the", collapse spaces
    │     fuzzyScore():
    │       - Exact match → 1.0
    │       - Prefix match → 0.95
    │       - Levenshtein distance → 0.0–1.0
    │     If score ≥ 0.70 → use cached match
    │
    └── Tier 2: Trakt API fallback
          TvTokenCache.getToken() → token from best phone
          GET https://api.trakt.tv/search/show?query={title}&limit=5 (Bearer token)
          If best result score ≥ 0.50 → use API match
    │
    ▼
Create ScrobbleCandidate { packageName, mediaTitle, confidence, matchedShow, matchedEpisode }
    │
    ├── confidence ≥ 0.95 → autoScrobble()
    │     TvTokenCache.getAllTokens() → tokens for ALL connected phones
    │     For each phone token (in parallel):
    │       POST https://api.trakt.tv/scrobble/start
    │         Body: { show, episode, progress: 0.0 }
    │       Failure for one user does not block the others
    │
    ├── confidence 0.70–0.95 → emit to pendingConfirmation SharedFlow
    │     │
    │     ▼
    │   ScrobbleViewModel collects → ScrobbleOverlay displayed
    │     │
    │     ├── User confirms → autoScrobble()  (same multi-user flow above)
    │     ├── User dismisses → remembered in session (won't re-show)
    │     └── 15s timeout → auto-confirms
    │
    └── confidence < 0.70 → ignored

Playback state changes (each fires for ALL connected phones in parallel):
    ├── PLAYING  → scrobbleStart()  { progress: 0.0 }
    ├── PAUSED   → scrobblePause()  { progress: 50.0 }
    └── STOPPED  → scrobbleStop()   { progress: 100.0 }  ← marks episode as watched
```

### Key Classes

| Class | Responsibility |
|-------|----------------|
| `MediaSessionScrobbler` | Polls media sessions, fuzzy matches, scrobbles to Trakt for all connected users |
| `TvTokenCache` | Per-phone token cache; `getToken()` for best phone, `getAllTokens()` for all phones |
| `TvShowCache` | In-memory show cache for first-pass matching |
| `ScrobbleViewModel` | Bridges pending confirmations to the overlay UI |
| `ScrobbleOverlay` | Composable confirmation overlay (D-pad navigable) |

---

## Phase 6: Deep Linking

### User Journey

1. From the TV home grid, user selects a show card.
2. The **Show Detail screen** appears with poster, synopsis, and a "Watch Now" button.
3. User taps "Watch Now" — the correct streaming app launches directly to the show.

### Technical Flow

```
ShowDetailViewModel.resolveDeepLink()
    │
    ▼
Get subscribed streaming services from StreamingPreferencesRepository
    │
    ▼
Match show against service deep link templates:
    │
    │  Netflix:      https://www.netflix.com/title/{tmdb_id}
    │  Prime Video:  https://www.primevideo.com/search?phrase={slug}
    │  Disney+:      https://www.disneyplus.com/series/{slug}/{tmdb_id}
    │  WaipuTV:      waipu://tv
    │  Joyn:         https://www.joyn.de/serien/{slug}
    │  ARD:          https://www.ardmediathek.de/video/{id}
    │  ZDF:          https://www.zdf.de/serien/{slug}
    │
    ▼
Replace placeholders with IDs from TraktShow.ids (tmdb, slug, imdb)
    │
    ▼
startActivity(Intent(ACTION_VIEW, Uri.parse(deepLink)))
```

---

## Authentication Modes

WatchBuddy supports three authentication modes, configurable in Advanced Settings on the phone app.

### 1. Managed (Default)

```
Phone ──► WatchBuddy Backend (api.watchbuddy.app) ──► Trakt API
               │
               └── Injects client_secret server-side
                   APK only contains client_id (public)
```

- **Setup:** No configuration needed (works out of the box).
- **Security:** The Trakt `client_secret` never leaves the backend server. The APK contains only the public `client_id`.
- **Backend:** Node.js + Express, rate-limited (60 req/min per IP), token validation via regex and length limits.

### 2. Self-Hosted

```
Phone ──► User's own backend (custom URL) ──► Trakt API
               │
               └── Same protocol as managed, user-operated
```

- **Setup:** User enters their backend URL in Advanced Settings.
- **Security:** Same proxy protocol — secret stays on the user's server.
- **Use case:** Users who want full control over their infrastructure.

### 3. Direct

```
Phone ──► Trakt API (no proxy)
               │
               └── client_id + client_secret sent directly
                   Secret stored in Android Keystore
```

- **Setup:** User enters their own Trakt app `client_id` and `client_secret` in Advanced Settings.
- **Security:** Secret is stored in Android Keystore (hardware-backed, AES-256-GCM encrypted).
- **Use case:** Developers or users who register their own Trakt API application.

### Resolution Logic

```kotlin
fun resolveClientId(authMode, backendUrl, directClientId): String? = when (authMode) {
    MANAGED     → buildConfigClientId if non-blank AND tokenProxy != null
    SELF_HOSTED → buildConfigClientId if non-blank AND backendUrl non-blank
    DIRECT      → directClientId if non-blank AND clientSecret non-blank
}
// Returns null → OnboardingState.NotConfigured (shows setup instructions)
```

---

## Token Lifecycle

### Storage

| Item | Storage | Encryption |
|------|---------|------------|
| `access_token` | EncryptedSharedPreferences | AES-256-GCM (Keystore master key) |
| `refresh_token` | EncryptedSharedPreferences | AES-256-GCM (Keystore master key) |
| `expires_at` | EncryptedSharedPreferences | AES-256-GCM (Keystore master key) |
| `trakt_client_secret` (DIRECT mode) | EncryptedSharedPreferences | AES-256-GCM (Keystore master key) |

### Token Flow Across Devices

```
Phone A (Alice)  Phone B (Bob)       TV (borrows tokens)
───────────────  ─────────────       ───────────────────
TokenRepository  TokenRepository     TvTokenCache
  │                │                   │
  │  token in      │  token in         │  Per-phone ConcurrentHashMap
  │  Keystore      │  Keystore         │  30-min TTL per phone
  │                │                   │
  │                │                   │  getToken()    → best phone only (search)
  │                │                   │  getAllTokens() → all available phones (scrobble)
  │                │                   │
  │◄── GET /auth/token ───────────────│   On cache miss per phone:
  │                │◄── GET /auth/token│     GET http://{phone}:8765/auth/token
  ▼                ▼                   ▼
  Alice's token    Bob's token         Both cached and used independently
                                       for parallel Trakt scrobble calls
```

### Refresh

The `TraktApiService` and `TokenProxyService` both define refresh endpoints (`POST /oauth/token` and `POST /trakt/token/refresh`), but automated refresh-on-expiry is not yet wired up. When a token expires, the user must re-authenticate through the onboarding flow.

---

## Key Thresholds and Constants

| Constant | Value | Location |
|----------|-------|----------|
| Scrobble auto-confirm threshold | ≥ 0.95 | `MediaSessionScrobbler` |
| Scrobble overlay threshold | 0.70 – 0.95 | `MediaSessionScrobbler` |
| Scrobble ignore threshold | < 0.70 | `MediaSessionScrobbler` |
| Overlay auto-dismiss timeout | 15 seconds | `ScrobbleOverlay` |
| Media session poll interval | 30 seconds | `MediaSessionScrobbler` |
| TV token cache TTL | 30 minutes | `TvTokenCache` |
| Phone show cache TTL | 5 minutes | `ShowRepository` |
| Companion server port | 8765 | `CompanionHttpServer` |
| NSD service type | `_watchbuddy._tcp.` | `CompanionService` |
| Backend rate limit | 60 req/min per IP | `backend/src/app.js` |
| Trakt search result limit | 5 | `TraktApiService` |
| Recap episode context | Last 8 episodes | `RecapGenerator` |

---

## Sequence Diagrams

### Full Authentication Sequence

```
 User          Phone App         Backend          Trakt API
  │                │                │                │
  │  Tap Connect   │                │                │
  │───────────────►│                │                │
  │                │  POST /oauth/device/code        │
  │                │────────────────────────────────►│
  │                │◄────────────────────────────────│
  │                │  { user_code, device_code }     │
  │  Show code     │                │                │
  │◄───────────────│                │                │
  │                │                │                │
  │  Open browser, enter code on trakt.tv/activate   │
  │──────────────────────────────────────────────────│
  │                │                │                │
  │                │  Poll: POST /trakt/token         │
  │                │───────────────►│                │
  │                │                │  POST /oauth/device/token
  │                │                │  + client_secret│
  │                │                │───────────────►│
  │                │                │◄───────────────│
  │                │◄───────────────│                │
  │                │  { access_token, refresh_token } │
  │                │                │                │
  │                │  GET /users/me (Bearer token)   │
  │                │────────────────────────────────►│
  │                │◄────────────────────────────────│
  │  "Connected    │  { username }  │                │
  │   as Alice"    │                │                │
  │◄───────────────│                │                │
```

### Full Scrobble Sequence (multi-user)

```
 Streaming App    TV Scrobbler    Phone A (Alice)   Phone B (Bob)    Trakt API
  │                │                │                │                │
  │  Playing       │                │                │                │
  │  media session │                │                │                │
  │───────────────►│                │                │                │
  │                │  Extract title + package        │                │
  │                │                │                │                │
  │                │  Fuzzy match (local cache)      │                │
  │                │  Score: 0.97 → auto-scrobble    │                │
  │                │                │                │                │
  │                │  getAllTokens() — fetch from all phones in parallel
  │                │  GET /auth/token│               │                │
  │                │───────────────►│                │                │
  │                │  GET /auth/token│               │                │
  │                │────────────────────────────────►│                │
  │                │◄───────────────│                │                │
  │                │  { Alice token }│               │                │
  │                │◄────────────────────────────────│                │
  │                │                │  { Bob token } │                │
  │                │                │                │                │
  │                │  POST /scrobble/start (Alice)   │                │
  │                │  { show, episode, progress: 0 } │                │
  │                │────────────────────────────────────────────────►│
  │                │  POST /scrobble/start (Bob) ← parallel          │
  │                │────────────────────────────────────────────────►│
  │                │◄────────────────────────────────────────────────│
  │                │                │                │                │
  │  Paused        │                │                │                │
  │───────────────►│                │                │                │
  │                │  POST /scrobble/pause (Alice + Bob, parallel)   │
  │                │────────────────────────────────────────────────►│
  │                │                │                │                │
  │  Stopped       │                │                │                │
  │───────────────►│                │                │                │
  │                │  POST /scrobble/stop (Alice + Bob) ← watched   │
  │                │────────────────────────────────────────────────►│
```

---

## File Reference

### Phone App — Authentication

| File | Purpose |
|------|---------|
| `app-phone/.../auth/TokenRepository.kt` | Encrypted token storage (Keystore-backed) |
| `app-phone/.../auth/AuthModule.kt` | Hilt module providing auth dependencies |
| `app-phone/.../ui/onboarding/OnboardingViewModel.kt` | OAuth device flow, polling, token saving |
| `app-phone/.../settings/AppSettings.kt` | Auth mode, backend URL, client ID settings |
| `app-phone/.../settings/SettingsRepository.kt` | DataStore-backed settings persistence |

### Phone App — Companion Server

| File | Purpose |
|------|---------|
| `app-phone/.../server/CompanionHttpServer.kt` | Ktor HTTP server (4 endpoints) |
| `app-phone/.../server/ShowRepository.kt` | Trakt show cache with 5-min TTL |
| `app-phone/.../server/DeviceCapabilityProvider.kt` | Device info for `/capability` endpoint |
| `app-phone/.../service/CompanionService.kt` | Foreground service, NSD registration |

### TV App — Discovery and Token

| File | Purpose |
|------|---------|
| `app-tv/.../discovery/PhoneDiscoveryManager.kt` | NSD listener, phone ranking |
| `app-tv/.../discovery/PhoneApiService.kt` | Retrofit interface for phone HTTP API |
| `app-tv/.../discovery/PhoneApiClientFactory.kt` | Per-phone Retrofit client factory |
| `app-tv/.../scrobbler/TvTokenCache.kt` | Per-phone token cache (ConcurrentHashMap, 30-min TTL); `getAllTokens()` for multi-user scrobbling |

### TV App — Scrobbling

| File | Purpose |
|------|---------|
| `app-tv/.../scrobbler/MediaSessionScrobbler.kt` | Media session polling, fuzzy matching, scrobble calls |
| `app-tv/.../ui/scrobble/ScrobbleOverlay.kt` | Confirmation overlay composable |
| `app-tv/.../ui/scrobble/ScrobbleViewModel.kt` | Bridges scrobbler → overlay UI |
| `app-tv/.../data/TvShowCache.kt` | In-memory show cache for matching |

### TV App — UI

| File | Purpose |
|------|---------|
| `app-tv/.../ui/home/TvHomeViewModel.kt` | Show loading, phone discovery state |
| `app-tv/.../ui/home/TvHomeScreen.kt` | Show grid, phone status badge |
| `app-tv/.../ui/showdetail/ShowDetailViewModel.kt` | Deep link resolution |
| `app-tv/.../ui/showdetail/ShowDetailScreen.kt` | Detail screen with "Watch Now" button |
| `app-tv/.../data/StreamingPreferencesRepository.kt` | User's subscribed streaming services |
| `app-tv/.../data/UserSessionRepository.kt` | Multi-user session tracking |

### Core — Shared

| File | Purpose |
|------|---------|
| `core/.../trakt/TraktApiService.kt` | Retrofit client: OAuth, shows, scrobble, search |
| `core/.../trakt/TokenProxyService.kt` | Retrofit client: backend token exchange + refresh |
| `core/.../network/NetworkModule.kt` | OkHttp (cert pinning), Retrofit instances |
| `core/.../network/TokenProxyServiceFactory.kt` | Dynamic Retrofit client for self-hosted backends |
| `core/.../model/Models.kt` | Shared data models (TraktShow, ScrobbleCandidate, etc.) |

### Backend

| File | Purpose |
|------|---------|
| `backend/src/index.js` | Express server entry point, env config |
| `backend/src/app.js` | Token exchange + refresh routes, rate limiting, validation |
