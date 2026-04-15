# WatchBuddy — Architecture Overview

## System Architecture

```mermaid
graph TB
    subgraph WIFI["LOCAL WIFI NETWORK"]
        TV["Google TV (app-tv)\n─────────────\nUI · Display\nNSD Client\nWebView\nMediaSession Scrobbler"]
        Phone["Android Phone(s) (app-phone)\n─────────────\nLLM (Gemma / AICore)\nNSD Server · HTTP API\nTrakt Auth"]
        TV <-->|"NSD/mDNS + HTTP (port 8765)"| Phone
    end

    Phone -->|"OAuth · sync · scrobble"| Trakt["Trakt API\ntrakt.tv/api\nRate: 1 000 / 5 min"]
    Phone -->|"Token exchange"| Backend["Token Proxy Backend\n(backend/ — Docker)\nInjects client_secret"]
    Phone -->|"Recap: episode metadata\nHome: poster images"| TMDB["TMDB API\napi.tmdb.org\n(per-user key)"]
    TV -->|"Title search\nShow / image data"| TMDB
```

> For a detailed breakdown of TMDB API usage, user journeys, connection handling and error recovery, see [`docs/tmdb-integration.md`](tmdb-integration.md).

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

```mermaid
flowchart LR
    Score["Score =\nmodelQuality (0–150)\n+ ramBonus (0–10)"]
    Score -->|"highest"| AICore["AICore device\n150 + bonus\nalways preferred"]
    Score --> E4B["LiteRT-LM Gemma E4B\n90 + bonus"]
    Score --> E2B["LiteRT-LM Gemma E2B\n70 + bonus"]
    Score -->|"lowest"| NoLLM["No LLM: 0"]
```

**Failover chain:**

```mermaid
flowchart LR
    P1["Best phone"] -->|unavailable| P2["Next best phone"]
    P2 -->|unavailable| Cache["Local TV cache"]
    Cache -->|empty| TMDB["TMDB synopsis only"]
```

## LLM Strategy

```mermaid
flowchart TD
    Start([App start]) --> AICore{"AICore\navailable?"}
    AICore -->|Yes| Gemini["Use Gemini Nano\n(auto-updated, no download)"]
    AICore -->|No| RAM{"Free RAM check\n(LiteRT-LM runtime)"}
    RAM -->|">= 5 GB"| E4B["Gemma 4 E4B\n(~3.4 GB · quality 90)"]
    RAM -->|">= 3 GB"| E2B["Gemma 4 E2B\n(~2.4 GB · quality 70)"]
    RAM -->|"< 3 GB"| TextOnly["TMDB text only\n(no model downloaded)"]
```

Model updates: WorkManager (`ModelDownloadWorker`), WiFi only.
Auto-migrate to AICore if OS update adds support.
Model download URL is configurable in Advanced Settings (default: HuggingFace `litert-community`).

## Scrobbling Flow

```mermaid
flowchart TD
    Poll["MediaSession on TV\n(polled every 30 s)"] --> Extract["Extract: package name + media title"]
    Extract --> Cache["Fuzzy match against local show cache\n(Levenshtein distance)"]
    Cache -->|"No confident match"| TMDBSearch["TMDB searchTv() fallback\n(key from best phone's /capability)"]
    Cache --> Conf{"Confidence?"}
    TMDBSearch --> Conf
    Conf -->|">= 95%"| Auto["Auto-scrobble"]
    Conf -->|"70 – 95%"| Overlay["ScrobbleOverlay:\nuser confirms or rejects"]
    Conf -->|"< 70%"| Ignore["Ignored"]
    Overlay -->|"Confirmed or 15 s timeout"| Auto
    Overlay -->|"Rejected"| Ignore
    Auto --> Parallel["For each connected phone (in parallel):\nPOST /scrobble/start\nphone forwards to Trakt internally\nfailures isolated per user"]
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
