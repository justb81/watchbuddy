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
TXT records:   version=X.Y.Z, modelQuality=70, llmBackend=LITERT
```

**TXT record contract:**
- `version` — the phone app's `versionName` (e.g. `0.15.1`), sourced from
  `BuildConfig.VERSION_NAME`. This is **not** a protocol version; the HTTP
  contract is versioned by endpoint. If a protocol version is ever needed, a
  new TXT key (`proto`) will be added — `version` will not be reused.
- `modelQuality` — integer 0–150, matches `LlmOrchestrator.LlmConfig.qualityScore`.
- `llmBackend` — one of the `LlmBackend` enum names. The TV parses this
  leniently: unknown values fall back to `LlmBackend.NONE` so a new phone-side
  enum value does not make the phone silently invisible to older TVs. Missing
  or unparseable `version` / `modelQuality`, however, cause the entry to be
  rejected outright.

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

## Manual Watched-State Marking (Phone)

Tapping a show on the phone `HomeScreen` opens `ShowDetailScreen`. The detail view
fetches the full season / episode structure for that show via Trakt
`GET shows/:id/seasons?extended=episodes`, wrapped in `EpisodeRepository` with a
10-minute per-show TTL cache. Each episode renders as a checkbox row reflecting its
watched state as derived from the user's existing `sync/watched/shows` cache in
`ShowRepository`.

Toggling a checkbox is **optimistic**: the UI flips immediately, the row is marked
pending, and `EpisodeRepository.markEpisode{Watched,Unwatched}` forwards the write to
Trakt `POST sync/history` or `POST sync/history/remove` with a single-show /
single-season / single-episode body. On success, `ShowRepository.updateLocalWatched(...)`
mutates the in-memory watched-shows list so any home-screen progress counter
recomputes live through the reactive `shows: StateFlow`. On failure the UI reverts
and a snackbar surfaces `show_detail_error_toggle`.

Default layout puts the user's current season (the lowest-numbered season at or
above the last-watched season that still has unwatched episodes) **first and
expanded**; every other season — older caught-up seasons, specials, future seasons —
sits below, collapsed behind a progress chip.

A connected TV picks up the change on its next 5-minute `/shows` poll from the phone.
The TV side has no write path for Trakt history; all manual edits originate from the
phone (#216).

## Companion Service Lifecycle (Phone)

The phone's companion service is controlled via the "I am watching TV" toggle on the HomeScreen.
The toggle is always visible, but is enabled only when all three prerequisites are satisfied:
Trakt is connected, TMDB is configured, **and** the phone is currently on a Wi-Fi network.
When any prerequisite is missing, the toggle is disabled and the reason is shown inline
(Trakt/TMDB missing vs. Wi-Fi missing). The Wi-Fi requirement is tracked reactively by
`phone/network/WifiStateProvider` (a `StateFlow<Boolean>` backed by a
`ConnectivityManager.registerDefaultNetworkCallback`).

**State management:** `CompanionStateManager` (Hilt singleton) is the shared state hub between
the `CompanionService`, `CompanionHttpServer`, and `HomeViewModel`. It tracks:
- `lastCapabilityCheck` — timestamp of the most recent `/capability` request from a TV
- `lastScrobbleEvent` — the latest scrobble event for display on the phone HomeScreen
- `isServiceRunning` — whether the foreground service is active

**Wi-Fi precondition & auto-stop:** `CompanionService.onStartCommand` probes
`wifiIpv4Address()` before doing any work. If the phone is not on Wi-Fi, the service clears
`companionEnabled` in settings, calls `stopSelf(startId)`, and returns `START_NOT_STICKY`
so the system does not re-deliver the start intent. While running, the service registers a
`ConnectivityManager.NetworkCallback` for Wi-Fi. When Wi-Fi is lost, NSD is unregistered
immediately and a 3 s grace timer runs; if Wi-Fi has not returned by then, the service
self-stops and clears `companionEnabled` so the foreground notification is dismissed. The
grace period tolerates brief SSID handoffs where `onLost(oldNet)` fires just before
`onAvailable(newNet)`. When Wi-Fi returns within the grace period, `onAvailable` is debounced
for 2 s, the existing registration is torn down, and a fresh one is registered 300 ms later.
The unregister-then-register sequence is required because `NsdManager.unregisterService` is
asynchronous — calling `registerService` before the teardown completes leaves duplicate
advertisements on the network (#264, #278).

**NSD registration state machine:** `registerNsd` / `unregisterNsd` transition states
under a single lock. The state is flipped before calling the async `NsdManager` API so
concurrent callers (`onStartCommand` + Wi-Fi `onAvailable`) cannot race past the guard
while a prior registration is still in flight.

```
IDLE → REGISTERING → REGISTERED
 ↑                       ↓
 └──── UNREGISTERING ←───┘
```

**Multicast lock (phone side):** The service acquires a `WifiManager.MulticastLock` for
its entire lifetime. Many phone OEM skins (OxygenOS, OneUI, MIUI) filter outgoing
multicast packets at the Wi-Fi driver unless an app holds this lock — without it, the
phone's NSD registration succeeds locally but no mDNS packets leave the radio, so peers
cannot discover it (TV-side discovery requires the same lock for the inbound path).

**NSD host pin:** The `NsdServiceInfo.host` is pinned to the phone's Wi-Fi IPv4 address
at registration time (resolved via `ConnectivityManager.getLinkProperties`). This prevents
`NsdManager` from advertising a wrong interface's address on multi-homed devices
(Wi-Fi + cellular, Wi-Fi + Ethernet dongle).

**HTTP server bind:** `CompanionHttpServer` binds Netty explicitly to `0.0.0.0` so the
listener accepts connections on the same Wi-Fi interface advertised via NSD.

**Cross-device discoverability note:** None of the code-level fixes above can overcome a
Wi-Fi access point that enforces client isolation (peer-to-peer traffic blocked at the
AP). If the TV cannot reach the phone even with both on the same SSID, verify that client
isolation / "AP isolation" / "Wi-Fi guest network" is disabled on the router.

**BLE fallback discovery:** Because client isolation, VLAN-segmented mesh Wi-Fi, and
aggressive multicast filtering block mDNS entirely at the network layer, a parallel BLE
advertising channel ships alongside NSD. The phone's `CompanionBleAdvertiser`
(see `service/CompanionBleAdvertiser.kt`) broadcasts a 9-byte service-data payload under
the custom UUID `5e4b4d3a-9f7c-4b7e-8e6b-6c0e5f27e4a0` containing the phone's IPv4
address, port, `modelQuality`, and `llmBackend` ordinal (schema defined in
`core/discovery/BleDiscoveryContract.kt`). The TV's `PhoneBleScanner` listens for the
same UUID; discovered endpoints flow into the same capability-fetch and heartbeat
pipeline as NSD-discovered ones, deduped by `baseUrl`. BLE and NSD run **in parallel**
from the moment the companion service starts. Graceful degradation is the default: on
Bluetooth-off, permission-denied, or BLE-unsupported hardware the advertiser/scanner
no-ops and NSD keeps working. Permissions: `BLUETOOTH_ADVERTISE` (phone, runtime prompt
from HomeScreen when the "I am watching TV" toggle flips on) and `BLUETOOTH_SCAN` with
`neverForLocation` (TV, requested on `TvMainActivity.onCreate`). BLE advertising starts
and stops in lockstep with NSD under the same `NetworkCallback` so a Wi-Fi drop or IP
change rebroadcasts with the new endpoint.

**Presence timeout:** A coroutine checks `lastCapabilityCheck` every 60 seconds. If no TV
has polled `/capability` for 5 minutes, the service auto-deactivates and sets `companionEnabled = false`.

**App close:** `onTaskRemoved()` stops the service and clears `companionEnabled` when the user
swipes the app from recents.

**Service health sync:** `onStartCommand()` is idempotent — if `CompanionStateManager.isServiceRunning`
is already true the start is skipped, and `CompanionHttpServer.start()` additionally guards against
double-binding Netty.

## Presence Heartbeat (TV)

The TV's `PhoneDiscoveryManager` runs a heartbeat coroutine every 60 seconds that re-fetches
`GET /capability` for each discovered phone. This serves two purposes:

1. **Presence verification** — if a phone fails 3 consecutive heartbeats, it is removed from
   the discovered list and excluded from scrobbling.
2. **Capability refresh** — updated capability data (RAM, LLM backend) is reflected immediately.

The `MediaSessionScrobbler` additionally checks each phone's `lastSuccessfulCheck` timestamp
before sending scrobble requests. Phones with stale presence (> 2 minutes) are skipped to
avoid network timeouts during playback.

**mDNS reliability on TV hardware:** `PhoneDiscoveryManager` holds a
`WifiManager.MulticastLock` for the entire lifetime of active discovery. Many Android TV
ROMs (Google TV, Chromecast with Google TV, Shield, several Sony/TCL images) silently
drop inbound multicast packets at the Wi-Fi driver unless an app holds this lock, which
would otherwise make the phone undiscoverable even though the `CHANGE_WIFI_MULTICAST_STATE`
permission is granted. Discovery is also self-healing: `onStartDiscoveryFailed` with
`FAILURE_ALREADY_ACTIVE` triggers a delayed stop+start cycle, a `ConnectivityManager`
network callback restarts discovery when Wi-Fi returns, and an empty phone list at the
60 s heartbeat tick cycles discovery so the TV recovers from silent NSD failures without
requiring an app relaunch.

## Scrobble Event Display (Phone)

When the phone's HTTP server receives a scrobble event (`/scrobble/start|pause|stop`), it
emits a `ScrobbleDisplayEvent` via `CompanionStateManager`. The phone's HomeScreen observes
this flow and shows a "Now Watching" card with the show title, episode number, and action
(started / paused / finished). Events older than 30 minutes are auto-hidden.

## Secret Storage Strategy

### Private APK (sideload)
- `client_secret` embedded via NDK + hidden-secrets-gradle-plugin (XOR + signature binding)
- On first run: migrated to Android Keystore (TEE/hardware-backed)
- `access_token` / `refresh_token`: always in Android Keystore

### Play Store APK
- `client_secret` lives ONLY on the token proxy backend
- APK contains only `client_id` (public)
- 3 auth modes (configurable in Advanced Settings):
  1. **Managed** → `https://watchbuddy.server.rang.it/trakt/token` (default; injected at build time via `TOKEN_BACKEND_URL`, self-hosters can override it in `local.properties`)
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

Release AABs are built with `debugSymbolLevel = "FULL"`, so AGP embeds per-AAB native debug symbols under `BUNDLE-METADATA` and Play Console auto-associates them for native crash/ANR symbolication. A per-module `native-debug-symbols.zip` is also attached to the GitHub Release for manual triage.

Release AABs likewise enable R8 (`isMinifyEnabled = true`), and AGP embeds the resulting `mapping.txt` inside each AAB so Play Console can de-obfuscate stack traces per versionCode. The Play upload is performed by [Gradle Play Publisher](https://github.com/Triple-T/gradle-play-publisher) (`./gradlew :app-phone:publishReleaseBundle`) in `artifactDir` mode, which uploads the phone + TV AABs as one atomic Play edit. Per-module `mapping.txt` files are also attached to the GitHub Release for manual symbolication.

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
