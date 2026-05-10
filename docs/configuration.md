# WatchBuddy — Configuration Values

This file lists all places in the repository where **configuration values must be maintained manually** before the app can be built or the backend started.

---

## Android App (`app-phone`)

**File:** `app-phone/build.gradle.kts` → `defaultConfig { … }`

| BuildConfig field   | Type    | Description                                                                                                 |
|---------------------|---------|-------------------------------------------------------------------------------------------------------------|
| `TRAKT_CLIENT_ID`   | String  | Trakt API client ID of the registered app (https://trakt.tv/oauth/applications). Empty string → Trakt login disabled. |
| `TOKEN_BACKEND_URL` | String  | Base URL of the WatchBuddy token proxy backend (e.g. `https://watchbuddy-backend.example.com`). Empty string → proxy not used, Trakt login disabled. |

Both fields can alternatively be set as **CI environment variables**:

```
TRAKT_CLIENT_ID=xxx
TOKEN_BACKEND_URL=https://...
```

The Gradle script reads these variables via `System.getenv()` and gives them priority over hardcoded values. For local development, enter the values directly in the script (an empty string is the safe default).

> **Important:** Both fields must be set for the Trakt login flow to be activated in the app (`OnboardingViewModel.isTraktConfigured`). If either is missing, the onboarding UI remains in the `NotConfigured` state.

---

## Token Proxy Backend (`backend/`)

**Configuration:** Environment variables are passed directly to the Docker container (see `backend/docker-compose.yml`).

| Variable              | Description                                                      |
|-----------------------|------------------------------------------------------------------|
| `TRAKT_CLIENT_ID`     | Trakt API client ID (identical to the app value above).          |
| `TRAKT_CLIENT_SECRET` | Trakt API client secret. **Here only** — never in the APK.       |
| `PORT`                | HTTP port of the backend (default: `3000`).                      |

```bash
# Set environment variables on the host (e.g. in the shell or in CI/CD)
export TRAKT_CLIENT_ID=your_trakt_client_id_here
export TRAKT_CLIENT_SECRET=your_trakt_client_secret_here
export PORT=3000   # optional, default: 3000
```

---

## Register a Trakt App

All values are available at: **https://trakt.tv/oauth/applications/new**

- **Redirect URI:** `urn:ietf:wg:oauth:2.0:oob` (device auth flow, no redirect required)
- **Grant type:** Device Auth

---

## Summary: Pre-build Checklist

- [ ] Trakt app registered at https://trakt.tv/oauth/applications
- [ ] `TRAKT_CLIENT_ID` set in `app-phone/build.gradle.kts` (or CI env)
- [ ] `TOKEN_BACKEND_URL` set in `app-phone/build.gradle.kts` (or CI env)
- [ ] Environment variables `TRAKT_CLIENT_ID` and `TRAKT_CLIENT_SECRET` set on the host
- [ ] Backend deployed / started locally (`npm start` in `backend/`)
