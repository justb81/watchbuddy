# WatchBuddy — Konfigurationswerte

Diese Datei listet alle Stellen im Repository, an denen **manuell Konfigurationswerte gepflegt** werden müssen, bevor die App gebaut oder das Backend gestartet werden kann.

---

## Android App (`app-phone`)

**Datei:** `app-phone/build.gradle.kts` → `defaultConfig { … }`

| BuildConfig-Feld    | Typ     | Beschreibung                                                                                                 |
|---------------------|---------|--------------------------------------------------------------------------------------------------------------|
| `TRAKT_CLIENT_ID`   | String  | Trakt API Client-ID der registrierten App (https://trakt.tv/oauth/applications). Leerstring → Trakt-Login deaktiviert. |
| `TOKEN_BACKEND_URL` | String  | Basis-URL des WatchBuddy Token-Proxy-Backends (z.B. `https://watchbuddy-backend.example.com`). Leerstring → Proxy nicht genutzt, Trakt-Login deaktiviert. |

Beide Felder können alternativ als **CI-Umgebungsvariablen** gesetzt werden:

```
TRAKT_CLIENT_ID=xxx
TOKEN_BACKEND_URL=https://...
```

Das Gradle-Skript liest diese Variablen via `System.getenv()` und priorisiert sie gegenüber Hardcode-Werten. Für lokale Entwicklung die Werte direkt im Skript eintragen (Leerstring ist der sichere Default).

> **Wichtig:** Beide Felder müssen gesetzt sein, damit der Trakt-Login-Flow in der App aktiviert wird (`OnboardingViewModel.isTraktConfigured`). Fehlt eines der beiden, bleibt die Onboarding-UI im Zustand `NotConfigured`.

---

## Token-Proxy-Backend (`backend/`)

**Datei:** `backend/.env` (aus `backend/.env.example` kopieren, **nie committen**)

| Variable              | Beschreibung                                                      |
|-----------------------|-------------------------------------------------------------------|
| `TRAKT_CLIENT_ID`     | Trakt API Client-ID (identisch mit dem App-Wert oben).            |
| `TRAKT_CLIENT_SECRET` | Trakt API Client-Secret. **Nur hier** — niemals in der APK.      |
| `PORT`                | HTTP-Port des Backends (Default: `3000`).                         |

```bash
# backend/.env (Beispiel)
TRAKT_CLIENT_ID=your_trakt_client_id_here
TRAKT_CLIENT_SECRET=your_trakt_client_secret_here
PORT=3000
```

---

## Trakt-App registrieren

Alle Werte erhältst du unter: **https://trakt.tv/oauth/applications/new**

- **Redirect URI:** `urn:ietf:wg:oauth:2.0:oob` (Device-Auth-Flow, kein Redirect nötig)
- **Grant type:** Device Auth

---

## Zusammenfassung: Checkliste vor dem ersten Build

- [ ] Trakt-App unter https://trakt.tv/oauth/applications registriert
- [ ] `TRAKT_CLIENT_ID` in `app-phone/build.gradle.kts` (oder CI-Env) gesetzt
- [ ] `TOKEN_BACKEND_URL` in `app-phone/build.gradle.kts` (oder CI-Env) gesetzt
- [ ] `backend/.env` aus `backend/.env.example` erstellt und befüllt
- [ ] Backend deployed / lokal gestartet (`npm start` in `backend/`)
