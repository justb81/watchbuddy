# WatchBuddy — Architecture Overview

## System Architecture

```
┌──────────────────────────────────────────────────┐
│                 LOCAL WIFI NETWORK                │
│                                                  │
│   ┌─────────────────┐     NSD/mDNS + HTTP        │
│   │   Google TV     │◄─────────────────────────► │
│   │   (app-tv)      │       port 8765             │
│   │                 │                             │
│   │  - UI/Display   │   ┌──────────────────────┐  │
│   │  - NSD Client   │   │  Android Phone(s)    │  │
│   │  - WebView      │   │  (app-phone)         │  │
│   │  - MediaSession │   │                      │  │
│   │    Scrobbler    │   │  - LLM (Gemma/AICore)│  │
│   └────────┬────────┘   │  - NSD Server        │  │
│            │            │  - HTTP API          │  │
│            │            │  - Trakt Auth        │  │
│            │            └──────────────────────┘  │
└────────────┼─────────────────────────────────────┘
             │ Internet
    ┌────────▼────────┐    ┌──────────────────────┐
    │   Trakt API     │    │  Token Proxy Backend  │
    │  trakt.tv/api   │    │  (backend/ — Docker)  │
    │                 │    │  Proxmox / own server │
    │  Rate: 1000/5m  │    │  Injects client_secret│
    └─────────────────┘    └──────────────────────┘
             │
    ┌────────▼────────┐
    │   TMDB API      │
    │  api.tmdb.org   │
    │  (per-user key) │
    └─────────────────┘

> For a detailed breakdown of TMDB API usage, user journeys, connection handling and error recovery, see [`docs/tmdb-integration.md`](tmdb-integration.md).
```

## Communication Protocol (TV ↔ Phone)

### NSD Service Registration (Phone side)
```
Service name:  watchbuddy-{username}
Service type:  _watchbuddy._tcp.
Port:          8765
TXT records:   version=1, modelQuality=70, llmBackend=LITERT
```

### HTTP API (Phone exposes, TV calls)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/capability` | Device info + LLM score + TMDB API key |
| GET | `/shows` | User's Trakt watched shows (cached) |
| POST | `/recap/{traktShowId}` | Generate HTML recap for a show |
| GET | `/auth/token` | Current access token (server-side, not used by TV) |
| POST | `/scrobble/start` | Forward scrobble start to this user's Trakt account |
| POST | `/scrobble/pause` | Forward scrobble pause to this user's Trakt account |
| POST | `/scrobble/stop` | Forward scrobble stop to this user's Trakt account |

**TV app API boundaries:**
- **TMDB API** — show/movie details, images, search (direct call from TV using key from `/capability`)
- **Phone API** — user library (`/shows`), scrobbling (`/scrobble/*`), recaps (`/recap/*`)
- **Trakt API** — never called directly by the TV; all Trakt operations are proxied via the phone

### Device Ranking (TV side)
```
Score = modelQuality (0–150) + ramBonus (0–10)

AICore device:        150 + bonus  → always preferred
LiteRT-LM Gemma E4B:  90 + bonus
LiteRT-LM Gemma E2B:  70 + bonus
No LLM:                 0
```

Failover: best phone → next best → next → local TV cache → TMDB synopsis only

## LLM Strategy

```
App start
    │
    ▼
AICore available? ──YES──► Use Gemini Nano (auto-updated, no download)
    │ NO
    ▼
Free RAM check (LiteRT-LM runtime, .litertlm models from HuggingFace)
    ├── ≥ 5 GB → Gemma 4 E4B  (~3.4 GB, quality 90)
    ├── ≥ 3 GB → Gemma 4 E2B  (~2.4 GB, quality 70)
    └── < 3 GB → TMDB text only (no model downloaded)
```

Model updates: WorkManager (`ModelDownloadWorker`), WiFi only.
Auto-migrate to AICore if OS update adds support.
Model download URL is configurable in Advanced Settings (default: HuggingFace `litert-community`).

## Scrobbling Flow

```
MediaSession on TV (polled every 30s)
    │
    ▼
Package name + media title extracted
    │
    ▼
Fuzzy match against local show cache (Levenshtein distance)
    │ Falls back to TMDB search API (key sourced from best phone's /capability)
    │ if no good cache match is found
    │
    ├── Confidence ≥ 95% → auto-scrobble
    │                           │
    │                     For each connected phone:
    │                       POST /scrobble/start (phone calls Trakt internally)
    │                       (parallel, failures isolated per user)
    │
    ├── Confidence 70–95% → ScrobbleOverlay: user confirms or rejects
    │                           │
    │                      Confirmed → scrobble all connected users (same parallel flow)
    │                      Rejected  → ignore
    │
    └── Confidence < 70% → ignored
```

Multi-user: when multiple phones are connected, each user's watch history is recorded
independently — one `/scrobble/*` call per phone, in parallel. A failure for one user
does not block the others. The TV never calls the Trakt API directly for any operation.

## Secret Storage Strategy

### Private APK (sideload)
- `client_secret` embedded via NDK + hidden-secrets-gradle-plugin (XOR + signature binding)
- On first run: migrated to Android Keystore (TEE/hardware-backed)
- `access_token` / `refresh_token`: always in Android Keystore

### Play Store APK
- `client_secret` lives ONLY on the token proxy backend
- APK contains only `client_id` (public)
- 3 auth modes (configurable in Advanced Settings):
  1. **Managed** → `https://api.watchbuddy.app/trakt/token` (default)
  2. **Self-hosted** → user enters own proxy URL
  3. **Direct** → user enters own Client ID + Secret (stored in Keystore)

## Play Store Distribution

| | Phone APK | TV APK |
|---|---|---|
| Package name | `com.justb81.watchbuddy` | `com.justb81.watchbuddy` |
| versionCode | run_number × 10 + 1 | run_number × 10 + 2 |
| LAUNCHER | ✅ | ❌ |
| LEANBACK_LAUNCHER | ❌ | ✅ |
| touchscreen required | true | false |
| 64-bit (Aug 2026) | ✅ | ✅ |

## Deep Links

| Service | Package | Link Template |
|---------|---------|---------------|
| Netflix | `com.netflix.ninja` | `https://www.netflix.com/title/{tmdb_id}` |
| Prime Video | `com.amazon.amazonvideo.livingroom` | `https://www.primevideo.com/search?phrase={slug}` |
| Disney+ | `com.disney.disneyplus` | `https://www.disneyplus.com/series/{slug}/{tmdb_id}` |
| WaipuTV | `tv.waipu.app` | `waipu://tv` |
| Joyn | `de.prosiebensat1digital.android.joyn` | `https://www.joyn.de/serien/{slug}` |
| ARD | `de.swr.avp.ard.phone` | `https://www.ardmediathek.de/video/{id}` |
| ZDF | `de.zdf.android.app` | `https://www.zdf.de/serien/{slug}` |
