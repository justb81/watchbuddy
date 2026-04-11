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
```

## Communication Protocol (TV ↔ Phone)

### NSD Service Registration (Phone side)
```
Service name:  watchbuddy-{username}
Service type:  _watchbuddy._tcp.
Port:          8765
TXT records:   version=1, modelQuality=60, llmBackend=MEDIAPIPE_GPU
```

### HTTP API (Phone exposes, TV calls)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/capability` | Device info + LLM score |
| GET | `/shows` | User's Trakt watched shows (cached) |
| POST | `/recap/{traktShowId}` | Generate HTML recap for a show |

### Device Ranking (TV side)
```
Score = modelQuality (0–150) + ramBonus (0–10)

AICore device:        150 + bonus  → always preferred
MediaPipe BF16 GPU:    90 + bonus
MediaPipe INT8 GPU:    75 + bonus
MediaPipe INT4 GPU:    60 + bonus
MediaPipe INT4 CPU:    40 + bonus
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
Free RAM check
    ├── ≥ 6 GB → Gemma 4 E2B BF16  (~9.6 GB, quality 90)
    ├── ≥ 4 GB → Gemma 4 E2B INT8  (~4.6 GB, quality 75)
    ├── ≥ 3 GB → Gemma 4 E2B INT4  (~3.2 GB, quality 60) ← Pixel 6a / Nothing 2a
    └── < 3 GB → TMDB text only (no model downloaded)
```

Model updates: WorkManager, weekly, WiFi only.
Auto-migrate to AICore if OS update adds support.

## Scrobbling Flow

```
MediaSession on TV
    │
    ▼
Package name + media title extracted
    │
    ▼
Fuzzy match against Trakt watchlist cache
    │
    ├── Confidence ≥ 90% → auto-scrobble (Trakt /scrobble/start)
    │
    └── Confidence < 90% → Toast: "Schaust du gerade [Titel]?" → user confirms
                                │
                           Confirmed → scrobble
                           Rejected → ignore
```

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
| versionCode | ~1000 | ~2000 |
| LAUNCHER | ✅ | ❌ |
| LEANBACK_LAUNCHER | ❌ | ✅ |
| touchscreen required | true | false |
| 64-bit (Aug 2026) | ✅ | ✅ |

## Deep Links

| Service | Package | Link Template |
|---------|---------|---------------|
| Netflix | `com.netflix.ninja` | `https://www.netflix.com/title/{tmdb_id}` |
| Prime Video | `com.amazon.amazonvideo.livingroom` | `https://app.primevideo.com/detail?asin={asin}` |
| Disney+ | `com.disney.disneyplus` | `https://www.disneyplus.com/series/{slug}/{id}` |
| WaipuTV | `tv.waipu.app` | `waipu://tv` |
| Joyn | `de.prosiebensat1digital.android.joyn` | `https://www.joyn.de/serien/{slug}` |
| ARD | `de.swr.avp.ard.phone` | `https://www.ardmediathek.de/video/{id}` |
| ZDF | `de.zdf.android.app` | `https://www.zdf.de/serien/{slug}` |
