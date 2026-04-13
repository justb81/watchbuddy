/**
 * WatchBuddy — Trakt Token Proxy
 *
 * Purpose:
 *   Exchange a Trakt device auth_code for access/refresh tokens without
 *   exposing the client_secret in the Android APK.
 *
 * Endpoints:
 *   POST /trakt/token         — exchange device auth_code for tokens
 *   POST /trakt/token/refresh — refresh an expired access token
 *   GET  /health              — liveness check
 *
 * Nothing is stored server-side. The proxy is purely a pass-through
 * that injects the server-side client_secret.
 */

import 'dotenv/config';
import { createApp } from './app.js';

const { TRAKT_CLIENT_ID, TRAKT_CLIENT_SECRET, PORT = 3000 } = process.env;

if (!TRAKT_CLIENT_ID || !TRAKT_CLIENT_SECRET) {
  console.error('ERROR: TRAKT_CLIENT_ID and TRAKT_CLIENT_SECRET must be set in .env');
  process.exit(1);
}

const app = createApp({
  clientId: TRAKT_CLIENT_ID,
  clientSecret: TRAKT_CLIENT_SECRET,
});

app.listen(PORT, () => {
  console.log(`WatchBuddy token proxy running on port ${PORT}`);
});
