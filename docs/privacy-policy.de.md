# Datenschutzerklärung — WatchBuddy

**Stand:** 16. April 2026

> **Wichtiger Hinweis:** Dieses Dokument ist ein sorgfältig recherchierter Entwurf auf Basis der tatsächlichen Datenflüsse des Open-Source-Projekts WatchBuddy. Es stellt **keine Rechtsberatung** dar. Vor einer produktiven Veröffentlichung (insbesondere im Google Play Store) sollte die deutsche Fassung von einer Fachanwältin oder einem Fachanwalt für IT- und Datenschutzrecht gegengeprüft werden, insbesondere die Klauseln zu Drittstaatentransfers (Trakt, TMDB, Hugging Face).
>
> Die deutsche Fassung ist die rechtlich maßgebliche Version. Die [englische Fassung](privacy-policy.en.md) ist eine inhaltsgleiche Übersetzung.

---

## 1. Verantwortlicher

Verantwortlicher im Sinne von Art. 4 Nr. 7 und Art. 13 Abs. 1 lit. a DSGVO:

**Bastian Rang**
E-Mail: <justb81@gmail.com>

WatchBuddy wird als nicht-kommerzielles Open-Source-Projekt von einer Privatperson entwickelt und bereitgestellt. Der Quellcode ist öffentlich unter <https://github.com/justb81/watchbuddy> einsehbar.

## 2. Geltungsbereich

Diese Datenschutzerklärung gilt für:

- die **WatchBuddy Phone App** (Android-Companion-App)
- die **WatchBuddy TV App** (Google-TV-App)
- das **Managed Backend** unter <https://watchbuddy.server.rang.it/> (Token-Proxy für die Trakt-OAuth-Authentifizierung)

Die Datenschutzerklärungen der Distributionskanäle (Google Play Store, GitHub Releases) sowie der angebundenen Drittdienste (siehe Abschnitt 9) sind davon unabhängig und gelten zusätzlich.

**Opt-out-Möglichkeiten:** Nutzer können auf die Nutzung des Managed Backends vollständig verzichten, indem sie in den Einstellungen der Phone App entweder
1. ein **selbst gehostetes Backend** (eigene URL) konfigurieren, oder
2. den **Direct-Credentials-Modus** aktivieren und ihren eigenen Trakt-Client-Secret hinterlegen.

In beiden Fällen findet keinerlei Datenverarbeitung durch den Verantwortlichen auf dem Managed Backend statt.

## 3. Privacy by Design

WatchBuddy ist nach dem Grundsatz der Datenminimierung konzipiert (Art. 25 Abs. 1 DSGVO):

- **Kein Tracking, kein Analytics-SDK, kein Crashlytics.** Es sind keine Analyse-, Telemetrie- oder Werbe-SDKs eingebunden.
- **Keine Nutzerkonten bei WatchBuddy.** Die App verwendet den Trakt-Account der Nutzer direkt; WatchBuddy selbst legt keine eigene Nutzerdatenbank an.
- **Zugangsdaten nur lokal.** Trakt-Access-Token, Refresh-Token sowie ein optionaler Trakt-Client-Secret werden ausschließlich auf dem Endgerät in einer per **Android Keystore** (AES-256-GCM) verschlüsselten `EncryptedSharedPreferences`-Datei abgelegt und verlassen das Gerät nicht, außer bei Aufrufen der Trakt-API (siehe Abschnitt 9). Quelle: `app-phone/src/main/java/com/justb81/watchbuddy/phone/auth/TokenRepository.kt`.
- **LLM-Inferenz ausschließlich auf dem Gerät.** Recap-Generierung erfolgt lokal über AICore (Gemini Nano) oder LiteRT-LM; es werden keine Prompts an Cloud-LLMs übermittelt.

## 4. Lokal verarbeitete Daten (verlassen das Gerät nicht)

Die folgenden Daten werden ausschließlich auf dem jeweiligen Endgerät gespeichert und nicht an den Verantwortlichen oder Dritte übermittelt:

### 4.1 Android Keystore (Phone App)

Verschlüsselt per `EncryptedSharedPreferences` (Quelle: `TokenRepository.kt`):

| Schlüssel | Inhalt |
|-----------|--------|
| `access_token` | Trakt Access Token |
| `refresh_token` | Trakt Refresh Token |
| `expires_at` | Ablaufzeitpunkt des Access Tokens (Unix-Timestamp in ms) |
| `trakt_client_secret` | Optionaler Trakt-Client-Secret (nur im Direct-Credentials-Modus) |

### 4.2 DataStore Preferences (Phone App)

Unverschlüsselte Konfigurationswerte (Quelle: `app-phone/src/main/java/com/justb81/watchbuddy/phone/settings/SettingsRepository.kt`):

| Schlüssel | Inhalt |
|-----------|--------|
| `auth_mode` | Authentifizierungsmodus (`MANAGED`, `SELF_HOSTED`, `DIRECT`) |
| `backend_url` | URL des verwendeten Token-Proxy-Backends |
| `direct_client_id` | Trakt-Client-ID (nur im Direct-Credentials-Modus) |
| `companion_enabled` | Ein/Aus-Zustand des „I am watching TV"-Schalters |
| `model_download_url` | Download-URL des ausgewählten LLM-Modells |
| `model_ready` | Flag: Ist ein LLM-Modell heruntergeladen? |
| `tmdb_api_key` | Persönlicher TMDB-API-Key (optional, vom Nutzer hinterlegt) |

### 4.3 DataStore Preferences (TV App)

Unverschlüsselte Konfigurationswerte (Quelle: `app-tv/src/main/java/com/justb81/watchbuddy/tv/data/UserSessionRepository.kt`, `StreamingPreferencesRepository.kt`):

| Schlüssel | Inhalt |
|-----------|--------|
| `selected_user_ids` | Menge der aktuell ausgewählten verbundenen Phone-Nutzer |
| `subscribed_service_ids` | Abonnierte Streaming-Dienste (z. B. Netflix, Prime Video) |
| `service_order` | Anzeigereihenfolge der Streaming-Dienste |

### 4.4 LLM-Modelle im App-Dateisystem (Phone App)

- **Gemma 4 E4B** (ca. 3,4 GB, Qualitätsscore 90) — auf Geräten mit ≥ 5 GB freiem RAM
- **Gemma 4 E2B** (ca. 2,4 GB, Qualitätsscore 70) — auf Geräten mit ≥ 3 GB freiem RAM
- **AICore / Gemini Nano** — systemseitig verwaltet, kein separater Download nötig

Quelle: `app-phone/src/main/java/com/justb81/watchbuddy/phone/llm/LlmOrchestrator.kt`.

### 4.5 In-Memory-Caches

- `TvShowCache` (TV App): Liste der Serien des Nutzers, die vom Phone via `/shows` geladen wurden
- `CompanionStateManager` (Phone App): Zeitpunkt der letzten Capability-Abfrage, letztes Scrobble-Ereignis, Service-Status

Diese Daten werden mit Beendigung des Prozesses gelöscht.

## 5. Lokales Netzwerk (NSD/mDNS, Port 8765)

Die Phone App registriert sich im lokalen Netz per Bonjour/mDNS als auffindbarer Dienst, damit die TV App sie entdecken kann (Quelle: `app-phone/src/main/java/com/justb81/watchbuddy/service/CompanionService.kt`).

### 5.1 Per NSD/mDNS gebroadcastete TXT-Records

| Feld | Inhalt |
|------|--------|
| `version` | Protokollversion (aktuell `"1"`) |
| `modelQuality` | LLM-Qualitätsscore (0–150) zur Priorisierung durch die TV App |
| `llmBackend` | LLM-Backend-Typ (`AICORE`, `LITERT`, `NONE`) |

Servicename: `watchbuddy-companion`, Servicetyp: `_watchbuddy._tcp.`, Port: `8765`.

### 5.2 Über HTTP (`/capability`) lesbare Daten

Wenn die TV App den Dienst entdeckt hat, ruft sie per HTTP `GET /capability` folgende Daten ab (Quelle: `app-phone/src/main/java/com/justb81/watchbuddy/phone/server/DeviceCapabilityProvider.kt`):

- Android-Build-ID (`deviceId`)
- Gerätemodell (`deviceName`, z. B. `Pixel 8`)
- Trakt-Benutzername (`userName`)
- Trakt-Avatar-URL (`userAvatarUrl`, sofern im Trakt-Profil hinterlegt)
- LLM-Backend und Qualitätsscore
- Freier Arbeitsspeicher in MB
- TMDB-Konfigurationsstatus sowie ggf. der TMDB-API-Key, damit die TV App eigene TMDB-Abfragen durchführen kann

### 5.3 Sicherheitshinweise

- **Intra-LAN-HTTP ist unverschlüsselt.** Der Companion-Server spricht Klartext-HTTP. Der Geltungsbereich ist ausdrücklich auf das eigene Heimnetzwerk beschränkt; die App ist nicht für Nutzung in offenen oder geteilten Netzwerken vorgesehen.
- Die mDNS-Broadcasts sind nur im jeweils verbundenen LAN sichtbar.

## 6. Scrobbling (Trakt-Forwarding)

Die TV App erkennt automatisch, welche Inhalte auf dem Fernseher abgespielt werden, und meldet sie an das Trakt-Konto des jeweiligen Phone-Nutzers (Quelle: `app-tv/src/main/java/com/justb81/watchbuddy/tv/scrobbler/MediaSessionScrobbler.kt`).

### 6.1 Aus Android Media Sessions gelesene Felder

- Paketname der abspielenden App (z. B. `com.netflix.ninja`)
- Medientitel aus `MediaMetadata.METADATA_KEY_TITLE`
- Wiedergabestatus (`PLAYING`, `PAUSED`, `STOPPED`, `NONE`)
- Aktueller Wiedergabefortschritt in Prozent (0–100 %), ermittelt aus `PlaybackState.position` und `MediaMetadata.METADATA_KEY_DURATION`, zur Übermittlung an Trakt

### 6.2 Confidence-Schwellen

- **≥ 0,95** → Auto-Scrobble
- **0,70 – 0,95** → Bestätigungsoverlay für den Nutzer
- **< 0,70** → verworfen, kein Scrobble

### 6.3 Forwarding-Fluss

```
TV-App → POST /scrobble/{start|pause|stop} an alle verbundenen Phones
        → Phone ruft Trakt-API mit eigenem Access Token
```

Die TV App ruft die Trakt-API **niemals direkt** auf. Bei mehreren verbundenen Phones wird der Scrobble an jedes einzeln weitergereicht; das Versagen einer Übermittlung blockiert die anderen nicht.

## 7. LLM-Recaps („Was bisher geschah")

Für die Generierung von Episoden-Zusammenfassungen verarbeitet die Phone App lokal die folgenden Eingaben:

- Serientitel (aus dem Trakt-Account des Nutzers)
- Episoden-Synopsen (aus der TMDB-API, siehe Abschnitt 9.2)

Die Inferenz erfolgt **ausschließlich on-device** (AICore oder LiteRT-LM). Es werden **keine Prompts an Cloud-LLMs** übermittelt. Das generierte HTML-Recap wird der TV App im lokalen Netz über die HTTP-Schnittstelle zur Verfügung gestellt.

## 8. Managed Backend (`watchbuddy.server.rang.it`)

### 8.1 Zweck

Das Managed Backend ist ein schlanker Token-Proxy, der im Trakt-OAuth-Device-Flow den geheimen `client_secret` serverseitig injiziert, damit dieser nicht in der öffentlich verteilten App gespeichert werden muss.

### 8.2 Verarbeitete Daten

Quelle: `backend/src/app.js`.

| Datum | Zweck | Quelle im Code |
|-------|-------|---------------|
| `device_code` (max. 256 Zeichen, Regex-validiert) | Zwischenwert im Trakt-OAuth-Device-Flow | `app.js:12` (`TOKEN_PATTERN`), `app.js:227` |
| `refresh_token` (max. 256 Zeichen, Regex-validiert) | Erneuerung eines abgelaufenen Access Tokens | `app.js:295` |
| Trakt-Client-ID | Identifikation der App bei Trakt | Umgebungsvariable |
| IP-Adresse | Rate-Limiting (60 Requests pro Minute pro IP) | `app.js:214-220` (`express-rate-limit`) |
| Debug-Logs (nur bei `DEBUG=true`) | Fehleranalyse durch den Administrator; umfasst Zeitstempel, Methode, Pfad, IP und HTTP-Status | `app.js:200-212` |

### 8.3 Nicht verarbeitete Daten

- Access Tokens und Refresh Tokens werden vom Backend **nicht persistent gespeichert**. Sie werden ausschließlich im RAM durch den Trakt-Aufruf durchgereicht und anschließend verworfen.
- Secret-Werte (Client Secret, Refresh Token, Access Token) werden in Debug-Logs nur maskiert (erste 4 Zeichen + `***`, siehe `app.js:29-43`).

### 8.4 Hosting

Das Backend wird bei einem **Hoster in der Europäischen Union** betrieben. Es findet kein Drittstaatentransfer für das Backend selbst statt.

### 8.5 Rechtsgrundlage

Art. 6 Abs. 1 lit. b DSGVO (Vertragserfüllung / vorvertragliche Maßnahmen — die Nutzer wünschen die Durchführung der Trakt-Authentifizierung).

### 8.6 Speicherdauer

- Tokens und Device Codes: **ephemer** — nur für die Dauer der Anfrage (wenige Sekunden).
- Rate-Limit-Eintrag (IP): maximal 60 Sekunden im RAM des Express-Rate-Limiters.
- Debug-Logs (nur bei aktiviertem Debug-Modus): so lange, bis der Container neugestartet wird; standardmäßig ist `DEBUG=false`.

### 8.7 Opt-out

Nutzer, die das Managed Backend nicht nutzen möchten, können in den Einstellungen auf Self-Hosting oder Direct-Credentials umschalten (siehe Abschnitt 2).

## 9. Externe Dienste

WatchBuddy bindet die folgenden externen Dienste ein. Für deren Datenverarbeitung ist der jeweilige Anbieter verantwortlich.

### 9.1 Trakt (Trakt LLC, USA)

- **Zweck:** OAuth-Authentifizierung, Abruf der Watch-History, Scrobbling, Profilabfrage
- **Übermittelte Daten:** Trakt-Login-Daten (nur auf <https://trakt.tv>, nicht in der App), Access-/Refresh-Token, Scrobble-Ereignisse (Show, Episode, Fortschritt), API-Requests inkl. IP-Adresse des Geräts
- **Rechtsgrundlage:** Art. 6 Abs. 1 lit. b DSGVO (Vertragserfüllung)
- **Drittstaat:** USA — Angemessenheitsbeschluss EU-US Data Privacy Framework (DPF) bzw. Standardvertragsklauseln (SCC) nach Art. 46 DSGVO
- **Datenschutzerklärung:** <https://trakt.tv/privacy>

### 9.2 TMDB — The Movie Database, Inc. (USA)

- **Zweck:** Abruf von Metadaten (Titel, Synopsen, Episodeninformationen) und Bildern (Poster, Backdrops)
- **Übermittelte Daten:** API-Requests mit Suchbegriffen/IDs, API-Key, IP-Adresse des Geräts
- **Rechtsgrundlage:** Art. 6 Abs. 1 lit. f DSGVO (berechtigtes Interesse an korrekten Medienmetadaten)
- **Drittstaat:** USA — Standardvertragsklauseln (SCC)
- **Datenschutzerklärung:** <https://www.themoviedb.org/privacy-policy>

### 9.3 Hugging Face (Hugging Face, Inc., USA)

- **Zweck:** Einmaliger Download der Gemma-4-LLM-Modelldatei von <https://huggingface.co/litert-community>
- **Übermittelte Daten:** HTTP-Request auf die Modell-URL inklusive IP-Adresse des Geräts
- **Rechtsgrundlage:** Art. 6 Abs. 1 lit. b DSGVO (Bereitstellung der vom Nutzer angeforderten Recap-Funktion)
- **Drittstaat:** USA
- **Datenschutzerklärung:** <https://huggingface.co/privacy>

Der Modell-Download kann umgangen werden, indem das Modell manuell bereitgestellt oder der Recap-Modus deaktiviert wird.

### 9.4 Google AICore / Gemini Nano

- **Zweck:** Optionale on-device-LLM-Inferenz auf AICore-fähigen Android-Geräten (Android 14+)
- **Übermittelte Daten:** **Keine.** AICore verarbeitet alle Eingaben strikt lokal im Systembereich; es findet keine Übermittlung an Google statt.
- **Rechtsgrundlage:** Art. 6 Abs. 1 lit. b DSGVO
- **Datenschutzerklärung:** <https://policies.google.com/privacy>

### 9.5 Google Play Store

- **Zweck:** Distributionskanal für die Android-Apps
- **Übermittelte Daten:** Die Datenverarbeitung durch Google Play (Installationsstatistiken, Geräte-IDs, Absturzberichte) unterliegt ausschließlich der Kontrolle und der Datenschutzerklärung von Google Ireland Ltd.
- **Datenschutzerklärung:** <https://policies.google.com/privacy>

## 10. Android-Berechtigungen

### 10.1 Phone App

Quelle: `app-phone/src/main/AndroidManifest.xml`.

| Berechtigung | Zweck | Runtime-Prompt |
|--------------|-------|----------------|
| `INTERNET` | Trakt-, TMDB- und Hugging-Face-Aufrufe | nein (Install-time) |
| `ACCESS_NETWORK_STATE` | Netzwerkverfügbarkeit prüfen, Auto-Reconnect | nein |
| `ACCESS_WIFI_STATE` | WLAN-Zustand für NSD-Betrieb prüfen | nein |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS/Bonjour-Pakete im LAN versenden | nein |
| `FOREGROUND_SERVICE` | Companion-HTTP-Server / Modell-Download im Vordergrund | nein |
| `FOREGROUND_SERVICE_DATA_SYNC` | Subtyp des Foreground-Service (Android 14+) | nein |
| `POST_NOTIFICATIONS` | Anzeige der Foreground-Service-Benachrichtigung | **ja (Android 13+)** |

### 10.2 TV App

Quelle: `app-tv/src/main/AndroidManifest.xml`.

| Berechtigung | Zweck | Runtime-Prompt |
|--------------|-------|----------------|
| `INTERNET` | TMDB-Aufrufe, Phone-Server-Aufrufe | nein |
| `ACCESS_NETWORK_STATE` | Netzwerkverfügbarkeit prüfen | nein |
| `ACCESS_WIFI_STATE` | WLAN-Zustand für NSD-Discovery prüfen | nein |
| `CHANGE_WIFI_MULTICAST_STATE` | mDNS-Discovery von Phone-Diensten | nein |
| `MEDIA_CONTENT_CONTROL` | Lesezugriff auf Media Sessions anderer Apps für Scrobbling | **ja (Notification-Listener-Bestätigung)** |
| `RECEIVE_BOOT_COMPLETED` | Automatischer Start der Discovery beim TV-Boot | nein |
| `FOREGROUND_SERVICE` | Phone-Discovery im Vordergrund | nein |
| `FOREGROUND_SERVICE_DATA_SYNC` | Subtyp des Foreground-Service (Android 14+) | nein |

## 11. Speicherdauer

| Datenkategorie | Ort | Dauer |
|---------------|-----|-------|
| Trakt-Access-Token | Keystore (Phone) | Bis zur Abmeldung oder zum Ablauf |
| Trakt-Refresh-Token | Keystore (Phone) | Bis zur Abmeldung |
| App-Einstellungen (DataStore) | DataStore (Phone + TV) | Bis zur Deinstallation oder `App-Daten löschen` |
| LLM-Modelldatei | App-Filesystem (Phone) | Bis zur manuellen Löschung oder Deinstallation |
| In-Memory-Caches | RAM | Prozesslaufzeit |
| Managed-Backend: Device-Code / Refresh-Token | RAM des Containers | Anfrageverarbeitung (Sekunden) |
| Managed-Backend: Rate-Limit-Eintrag (IP) | RAM des Containers | ≤ 60 Sekunden |
| Managed-Backend: Debug-Logs | STDOUT des Containers | Bis Neustart; standardmäßig deaktiviert |
| Trakt-Accountdaten | Server von Trakt LLC | Gemäß Trakt-Datenschutzerklärung |
| TMDB-Request-Logs | Server von TMDB | Gemäß TMDB-Datenschutzerklärung |

## 12. Betroffenenrechte (Art. 15–22 DSGVO)

Als betroffene Person haben Sie die folgenden Rechte:

- **Auskunft** (Art. 15 DSGVO)
- **Berichtigung** (Art. 16 DSGVO)
- **Löschung** (Art. 17 DSGVO)
- **Einschränkung der Verarbeitung** (Art. 18 DSGVO)
- **Datenübertragbarkeit** (Art. 20 DSGVO)
- **Widerspruch** (Art. 21 DSGVO)
- **Widerruf einer Einwilligung** (Art. 7 Abs. 3 DSGVO)

**Durchsetzung in der Praxis:**

- Rechte gegenüber dem Managed Backend: formlose E-Mail an <justb81@gmail.com>. Da das Backend keine persistenten personenbezogenen Daten speichert, beschränkt sich die Auskunft im Regelfall auf die Bestätigung, dass keine Daten über die in Abschnitt 8 genannten hinaus verarbeitet werden.
- **Lokale Daten auf dem Gerät löschen:** Android-Einstellungen → Apps → WatchBuddy → Speicher → *App-Daten löschen* oder App deinstallieren.
- **Trakt-seitige Datenlöschung:** über die Kontoeinstellungen auf <https://trakt.tv> möglich.
- Rechte gegenüber TMDB, Trakt und Hugging Face: direkt beim jeweiligen Anbieter ausüben (siehe Abschnitt 9).

## 13. Beschwerderecht

Sie haben gemäß Art. 77 DSGVO das Recht, sich bei einer Datenschutz-Aufsichtsbehörde zu beschweren, insbesondere bei der Aufsichtsbehörde Ihres gewöhnlichen Aufenthaltsorts, Arbeitsplatzes oder des Orts des mutmaßlichen Verstoßes.

Für Deutschland zuständig: der/die Bundesbeauftragte für den Datenschutz und die Informationsfreiheit (BfDI) bzw. die jeweilige Landesdatenschutzbehörde. Eine Liste der deutschen Aufsichtsbehörden ist unter <https://www.bfdi.bund.de> abrufbar.

## 14. Kinder

WatchBuddy richtet sich nicht an Personen unter 16 Jahren. Es werden wissentlich keine personenbezogenen Daten von Kindern unter 16 Jahren ohne elterliche Einwilligung verarbeitet (Art. 8 DSGVO).

## 15. Änderungen der Datenschutzerklärung

Diese Datenschutzerklärung wird bei relevanten Änderungen der Datenverarbeitung aktualisiert. Die Versionshistorie ist vollständig im Git-Verlauf des Dokuments unter <https://github.com/justb81/watchbuddy/commits/main/docs/privacy-policy.de.md> nachvollziehbar.

## 16. Stand

Letzte Aktualisierung: **16. April 2026**.
