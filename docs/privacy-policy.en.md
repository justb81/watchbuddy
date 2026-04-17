# Privacy Policy — WatchBuddy

**Last updated:** 16 April 2026

> **Important note:** This document is a carefully researched draft based on the actual data flows of the WatchBuddy open-source project. It does **not constitute legal advice**. Before any production publication (in particular on the Google Play Store), the German version should be reviewed by a qualified IT / data-protection lawyer, especially the clauses on third-country transfers (Trakt, TMDB, Hugging Face).
>
> The German version is the legally authoritative one. This English text is a content-equivalent translation provided for convenience. See [`privacy-policy.de.md`](privacy-policy.de.md).

---

## 1. Controller

Controller within the meaning of Art. 4(7) and Art. 13(1)(a) GDPR:

**Bastian Rang**
Email: <justb81@gmail.com>

WatchBuddy is developed and provided as a non-commercial open-source project by a private individual. The source code is publicly available at <https://github.com/justb81/watchbuddy>.

## 2. Scope

This privacy policy covers:

- the **WatchBuddy Phone App** (Android companion app)
- the **WatchBuddy TV App** (Google TV app)
- the **managed backend** at <https://watchbuddy.server.rang.it/> (token proxy for Trakt OAuth authentication)

The privacy policies of the distribution channels (Google Play Store, GitHub Releases) and of the third-party services (see Section 9) are independent and additionally apply.

**Opt-out options:** Users can fully refrain from using the managed backend by configuring in the phone app settings either
1. a **self-hosted backend** (custom URL), or
2. the **direct credentials mode** with their own Trakt client secret.

In both cases, no data is processed by the controller via the managed backend.

## 3. Privacy by Design

WatchBuddy is designed on the principle of data minimisation (Art. 25(1) GDPR):

- **No tracking, no analytics SDK, no Crashlytics.** No analytics, telemetry or advertising SDKs are integrated.
- **No WatchBuddy user accounts.** The app uses the user's existing Trakt account directly; WatchBuddy itself does not maintain a separate user database.
- **Credentials stored locally only.** Trakt access tokens, refresh tokens and an optional Trakt client secret are stored exclusively on the device in an `EncryptedSharedPreferences` file backed by the **Android Keystore** (AES-256-GCM). They do not leave the device, except when calling the Trakt API itself (see Section 9). Source: `app-phone/src/main/java/com/justb81/watchbuddy/phone/auth/TokenRepository.kt`.
- **LLM inference is strictly on-device.** Recap generation runs locally via AICore (Gemini Nano) or LiteRT-LM; no prompts are sent to any cloud LLM.

## 4. Locally Processed Data (does not leave the device)

The following data is stored exclusively on the respective device and is not transmitted to the controller or any third party:

### 4.1 Android Keystore (phone app)

Encrypted via `EncryptedSharedPreferences` (source: `TokenRepository.kt`):

| Key | Content |
|-----|---------|
| `access_token` | Trakt access token |
| `refresh_token` | Trakt refresh token |
| `expires_at` | Access-token expiry (Unix timestamp in ms) |
| `trakt_client_secret` | Optional Trakt client secret (direct credentials mode only) |

### 4.2 DataStore preferences (phone app)

Unencrypted configuration values (source: `app-phone/src/main/java/com/justb81/watchbuddy/phone/settings/SettingsRepository.kt`):

| Key | Content |
|-----|---------|
| `auth_mode` | Authentication mode (`MANAGED`, `SELF_HOSTED`, `DIRECT`) |
| `backend_url` | URL of the token-proxy backend in use |
| `direct_client_id` | Trakt client ID (direct credentials mode only) |
| `companion_enabled` | On/off state of the "I am watching TV" toggle |
| `model_download_url` | Download URL of the selected LLM model |
| `model_ready` | Flag: is an LLM model downloaded? |
| `tmdb_api_key` | Personal TMDB API key (optional, set by the user) |

### 4.3 DataStore preferences (TV app)

Unencrypted configuration values (source: `app-tv/src/main/java/com/justb81/watchbuddy/tv/data/UserSessionRepository.kt`, `StreamingPreferencesRepository.kt`):

| Key | Content |
|-----|---------|
| `selected_user_ids` | Set of connected phone users currently selected |
| `subscribed_service_ids` | Subscribed streaming services (e.g. Netflix, Prime Video) |
| `service_order` | Display order of streaming services |

### 4.4 LLM models in the app file system (phone app)

- **Gemma 4 E4B** (~3.4 GB, quality score 90) — on devices with ≥ 5 GB free RAM
- **Gemma 4 E2B** (~2.4 GB, quality score 70) — on devices with ≥ 3 GB free RAM
- **AICore / Gemini Nano** — managed by the system, no separate download required

Source: `app-phone/src/main/java/com/justb81/watchbuddy/phone/llm/LlmOrchestrator.kt`.

### 4.5 In-memory caches

- `TvShowCache` (TV app): user's show list fetched from the phone via `/shows`
- `CompanionStateManager` (phone app): timestamp of the last capability check, last scrobble event, service state

These data are discarded when the process ends.

## 5. Local Network (NSD/mDNS, port 8765)

The phone app registers itself as a discoverable service in the local network via Bonjour/mDNS so that the TV app can find it (source: `app-phone/src/main/java/com/justb81/watchbuddy/service/CompanionService.kt`).

### 5.1 TXT records broadcast via NSD/mDNS

| Field | Content |
|-------|---------|
| `version` | Protocol version (currently `"1"`) |
| `modelQuality` | LLM quality score (0–150) for prioritisation by the TV app |
| `llmBackend` | LLM backend type (`AICORE`, `LITERT`, `NONE`) |

Service name: `watchbuddy-companion`, service type: `_watchbuddy._tcp.`, port: `8765`.

### 5.2 Data readable via HTTP (`/capability`)

Once the TV app has discovered the service, it calls `GET /capability` over HTTP to fetch (source: `app-phone/src/main/java/com/justb81/watchbuddy/phone/server/DeviceCapabilityProvider.kt`):

- Android build ID (`deviceId`)
- Device model (`deviceName`, e.g. `Pixel 8`)
- Trakt username (`userName`)
- Trakt avatar URL (`userAvatarUrl`, if present in the Trakt profile)
- LLM backend and quality score
- Free RAM in MB
- TMDB configuration state and, where configured, the TMDB API key so that the TV app can issue its own TMDB requests

### 5.3 Security notes

- **Intra-LAN HTTP is unencrypted.** The companion server speaks plain HTTP. Its scope is explicitly limited to the home network; the app is not intended for use on open or shared networks.
- The mDNS broadcasts are visible only within the connected LAN.

## 6. Scrobbling (Trakt forwarding)

The TV app automatically detects what is being played on the television and reports it to the Trakt account of each connected phone user (source: `app-tv/src/main/java/com/justb81/watchbuddy/tv/scrobbler/MediaSessionScrobbler.kt`).

### 6.1 Fields read from Android Media Sessions

- Package name of the playing app (e.g. `com.netflix.ninja`)
- Media title from `MediaMetadata.METADATA_KEY_TITLE`
- Playback state (`PLAYING`, `PAUSED`, `STOPPED`, `NONE`)
- Current playback progress as a percentage (0–100 %), derived from `PlaybackState.position` and `MediaMetadata.METADATA_KEY_DURATION`, forwarded to Trakt

### 6.2 Confidence thresholds

- **≥ 0.95** → auto-scrobble
- **0.70 – 0.95** → confirmation overlay for the user
- **< 0.70** → discarded, no scrobble

### 6.3 Forwarding flow

```
TV app → POST /scrobble/{start|pause|stop} to all connected phones
       → phone calls the Trakt API using its own access token
```

The TV app **never calls the Trakt API directly**. When several phones are connected, the scrobble event is forwarded to each of them individually; a failure on one phone does not block the others.

## 7. LLM Recaps ("Previously on…")

To generate episode recaps, the phone app processes the following inputs locally:

- Show title (from the user's Trakt account)
- Episode synopses (from the TMDB API, see Section 9.2)

Inference is performed **strictly on-device** (AICore or LiteRT-LM). **No prompts are sent to any cloud LLM.** The generated HTML recap is served to the TV app via the local HTTP interface.

## 8. Managed Backend (`watchbuddy.server.rang.it`)

### 8.1 Purpose

The managed backend is a lightweight token proxy that injects the Trakt `client_secret` server-side during the Trakt OAuth device flow, so that this secret does not have to be embedded in the publicly distributed app.

### 8.2 Data processed

Source: `backend/src/app.js`.

| Data | Purpose | Source in code |
|------|---------|----------------|
| `device_code` (max. 256 chars, regex-validated) | Intermediate value in the Trakt OAuth device flow | `app.js:12` (`TOKEN_PATTERN`), `app.js:227` |
| `refresh_token` (max. 256 chars, regex-validated) | Renewal of an expired access token | `app.js:295` |
| Trakt client ID | App identification towards Trakt | Environment variable |
| IP address | Rate limiting (60 requests per minute per IP) | `app.js:214-220` (`express-rate-limit`) |
| Debug logs (only when `DEBUG=true`) | Diagnostics by the administrator; includes timestamp, method, path, IP and HTTP status | `app.js:200-212` |

### 8.3 Data NOT processed

- Access tokens and refresh tokens are **not persistently stored** by the backend. They are held in RAM only for the duration of the upstream Trakt call and then discarded.
- Secret values (client secret, refresh token, access token) are masked in debug logs (first 4 characters + `***`, see `app.js:29-43`).

### 8.4 Hosting

The backend is operated by a **hosting provider inside the European Union**. No third-country transfer applies to the backend itself.

### 8.5 Legal basis

Art. 6(1)(b) GDPR (performance of a contract / pre-contractual steps — users ask for the Trakt authentication to be carried out).

### 8.6 Retention period

- Tokens and device codes: **ephemeral** — only for the duration of the request (a few seconds).
- Rate-limit entry (IP): at most 60 seconds in the RAM of the express rate limiter.
- Debug logs (only when debug mode is enabled): until the container is restarted; the default is `DEBUG=false`.

### 8.7 Opt-out

Users who do not want to use the managed backend can switch to self-hosting or direct credentials in the app settings (see Section 2).

## 9. Third-Party Services

WatchBuddy integrates the following external services. The respective provider is responsible for their data processing.

### 9.1 Trakt (Trakt LLC, USA)

- **Purpose:** OAuth authentication, watch history retrieval, scrobbling, profile lookup
- **Data transferred:** Trakt login credentials (only on <https://trakt.tv>, not in the app), access/refresh tokens, scrobble events (show, episode, progress), API requests including the device IP address
- **Legal basis:** Art. 6(1)(b) GDPR (performance of a contract)
- **Third country:** USA — EU-US Data Privacy Framework (DPF) adequacy decision and/or Standard Contractual Clauses (SCCs) under Art. 46 GDPR
- **Privacy policy:** <https://trakt.tv/privacy>

### 9.2 TMDB — The Movie Database, Inc. (USA)

- **Purpose:** Retrieval of metadata (titles, synopses, episode details) and images (posters, backdrops)
- **Data transferred:** API requests with query terms/IDs, API key, device IP address
- **Legal basis:** Art. 6(1)(f) GDPR (legitimate interest in accurate media metadata)
- **Third country:** USA — Standard Contractual Clauses (SCCs)
- **Privacy policy:** <https://www.themoviedb.org/privacy-policy>

### 9.3 Hugging Face (Hugging Face, Inc., USA)

- **Purpose:** One-off download of the Gemma 4 LLM model file from <https://huggingface.co/litert-community>
- **Data transferred:** HTTP request to the model URL including the device IP address
- **Legal basis:** Art. 6(1)(b) GDPR (provision of the recap feature requested by the user)
- **Third country:** USA
- **Privacy policy:** <https://huggingface.co/privacy>

The model download can be avoided by sideloading the model file manually or by disabling the recap feature.

### 9.4 Google AICore / Gemini Nano

- **Purpose:** Optional on-device LLM inference on AICore-capable Android devices (Android 14+)
- **Data transferred:** **None.** AICore processes all inputs strictly locally in the system partition; no data is sent to Google.
- **Legal basis:** Art. 6(1)(b) GDPR
- **Privacy policy:** <https://policies.google.com/privacy>

### 9.5 Google Play Store

- **Purpose:** Distribution channel for the Android apps
- **Data transferred:** Any processing by Google Play (installation statistics, device IDs, crash reports) is under Google Ireland Ltd.'s sole control and subject to their own privacy policy.
- **Privacy policy:** <https://policies.google.com/privacy>

## 10. Android Permissions

### 10.1 Phone app

Source: `app-phone/src/main/AndroidManifest.xml`.

| Permission | Purpose | Runtime prompt |
|------------|---------|----------------|
| `INTERNET` | Trakt, TMDB and Hugging Face calls | no (install-time) |
| `ACCESS_NETWORK_STATE` | Check network availability, auto-reconnect | no |
| `ACCESS_WIFI_STATE` | Check Wi-Fi state for NSD operation | no |
| `CHANGE_WIFI_MULTICAST_STATE` | Send mDNS/Bonjour packets on the LAN | no |
| `FOREGROUND_SERVICE` | Run the companion HTTP server / model download in the foreground | no |
| `FOREGROUND_SERVICE_DATA_SYNC` | Subtype of the foreground service (Android 14+) | no |
| `POST_NOTIFICATIONS` | Display the foreground-service notification | **yes (Android 13+)** |

### 10.2 TV app

Source: `app-tv/src/main/AndroidManifest.xml`.

| Permission | Purpose | Runtime prompt |
|------------|---------|----------------|
| `INTERNET` | TMDB calls, phone-server calls | no |
| `ACCESS_NETWORK_STATE` | Check network availability | no |
| `ACCESS_WIFI_STATE` | Check Wi-Fi state for NSD discovery | no |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS discovery of phone services | no |
| `MEDIA_CONTENT_CONTROL` | Read access to other apps' media sessions for scrobbling | **yes (notification listener consent)** |
| `RECEIVE_BOOT_COMPLETED` | Automatic start of discovery at TV boot | no |
| `FOREGROUND_SERVICE` | Phone discovery in the foreground | no |
| `FOREGROUND_SERVICE_DATA_SYNC` | Subtype of the foreground service (Android 14+) | no |

## 11. Retention Periods

| Data category | Location | Duration |
|--------------|----------|----------|
| Trakt access token | Keystore (phone) | Until logout or expiry |
| Trakt refresh token | Keystore (phone) | Until logout |
| App settings (DataStore) | DataStore (phone + TV) | Until uninstall or "clear app data" |
| LLM model file | App file system (phone) | Until manual deletion or uninstall |
| In-memory caches | RAM | Process lifetime |
| Managed backend: device code / refresh token | Container RAM | Request processing (seconds) |
| Managed backend: rate-limit entry (IP) | Container RAM | ≤ 60 seconds |
| Managed backend: debug logs | Container STDOUT | Until restart; disabled by default |
| Trakt account data | Trakt LLC servers | Per the Trakt privacy policy |
| TMDB request logs | TMDB servers | Per the TMDB privacy policy |

## 12. Data Subject Rights (Art. 15–22 GDPR)

As a data subject you have the following rights:

- **Access** (Art. 15 GDPR)
- **Rectification** (Art. 16 GDPR)
- **Erasure** (Art. 17 GDPR)
- **Restriction of processing** (Art. 18 GDPR)
- **Data portability** (Art. 20 GDPR)
- **Objection** (Art. 21 GDPR)
- **Withdrawal of consent** (Art. 7(3) GDPR)

**How to exercise these rights in practice:**

- Rights towards the managed backend: informal email to <justb81@gmail.com>. As the backend does not persistently store personal data, access requests will in general simply confirm that no data beyond what is described in Section 8 is processed.
- **Delete local data on the device:** Android Settings → Apps → WatchBuddy → Storage → *Clear app data*, or uninstall the app.
- **Trakt-side data deletion:** via the account settings on <https://trakt.tv>.
- Rights against TMDB, Trakt and Hugging Face: exercise them directly with the respective provider (see Section 9).

## 13. Right to Lodge a Complaint

Under Art. 77 GDPR you have the right to lodge a complaint with a data-protection supervisory authority, in particular in the Member State of your habitual residence, place of work or the place of the alleged infringement.

For Germany the competent authority is the Federal Commissioner for Data Protection and Freedom of Information (BfDI) or the respective state data-protection authority. A list of German supervisory authorities is available at <https://www.bfdi.bund.de>.

## 14. Children

WatchBuddy is not aimed at persons under the age of 16. No personal data of children under 16 is knowingly processed without parental consent (Art. 8 GDPR).

## 15. Changes to This Privacy Policy

This privacy policy will be updated whenever the data processing changes in a material way. The full revision history is available via the Git log of this document at <https://github.com/justb81/watchbuddy/commits/main/docs/privacy-policy.en.md>.

## 16. Version

Last updated: **16 April 2026**.
